package com.kyenet.ksnetlite.net

import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

internal class TcpSessionManager(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val ruleEngine: RuleEngine,
    private val trafficShaper: TrafficShaper,
    private val profileProvider: () -> NetworkProfile,
    private val sendToTun: suspend (ByteArray) -> Unit
) {
    private data class SessionKey(
        val clientIp: InetAddress,
        val clientPort: Int,
        val remoteIp: InetAddress,
        val remotePort: Int
    )

    private data class Session(
        val key: SessionKey,
        val socket: Socket,
        val readJob: Job,
        var clientSeqNext: Long,
        var serverSeq: Long,
        var closed: Boolean = false
    )

    private val sessions = ConcurrentHashMap<SessionKey, Session>()

    fun handleClientPacket(packet: PacketParser.ParsedPacket.Tcp) {
        val key = SessionKey(packet.srcIp, packet.srcPort, packet.dstIp, packet.dstPort)
        val existing = sessions[key]
        if (existing == null) {
            if (packet.syn && !packet.ackFlag) {
                createSession(key, packet)
            }
            return
        }
        handleExistingSession(existing, packet)
    }

    fun shutdown() {
        sessions.values.forEach { closeSession(it) }
        sessions.clear()
    }

    private fun createSession(key: SessionKey, synPacket: PacketParser.ParsedPacket.Tcp) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                vpnService.protect(socket)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(key.remoteIp, key.remotePort), 8_000)
                val initialServerSeq = Random.nextInt(1, Int.MAX_VALUE).toLong()
                val session = Session(
                    key = key,
                    socket = socket,
                    readJob = startSocketReader(key, socket, initialServerSeq, synPacket.seq + 1),
                    clientSeqNext = synPacket.seq + 1,
                    serverSeq = initialServerSeq + 1
                )
                sessions[key] = session

                sendTcpControl(
                    key = key,
                    seq = initialServerSeq,
                    ack = synPacket.seq + 1,
                    flags = TCP_SYN or TCP_ACK
                )
            } catch (_: Exception) {
                sendTcpControl(
                    key = key,
                    seq = 0,
                    ack = synPacket.seq + 1,
                    flags = TCP_RST or TCP_ACK
                )
            }
        }
    }

    private fun startSocketReader(
        key: SessionKey,
        socket: Socket,
        initialServerSeq: Long,
        initialAck: Long
    ): Job {
        return scope.launch(Dispatchers.IO) {
            var serverSeq = initialServerSeq + 1
            var clientAck = initialAck
            val input = BufferedInputStream(socket.getInputStream())
            val buffer = ByteArray(16 * 1024)
            while (isActive) {
                val read = input.read(buffer)
                if (read <= 0) break
                val profile = profileProvider()
                if (ruleEngine.shouldDropPacket(profile)) {
                    StatsStore.onPacketDropped()
                    continue
                }
                val shapingDelay = trafficShaper.computeRequiredDelayMs(read, profile)
                val networkDelay = ruleEngine.computeDelayMs(profile)
                val totalDelay = shapingDelay + networkDelay
                if (totalDelay > 0) delay(totalDelay)

                val payload = buffer.copyOf(read)
                val packet = PacketBuilder.buildTcpPacket(
                    srcIp = key.remoteIp,
                    dstIp = key.clientIp,
                    srcPort = key.remotePort,
                    dstPort = key.clientPort,
                    seq = serverSeq,
                    ack = clientAck,
                    flags = TCP_ACK or TCP_PSH,
                    payload = payload
                )
                sendToTun(packet)
                serverSeq += payload.size
                sessions[key]?.serverSeq = serverSeq
                clientAck = sessions[key]?.clientSeqNext ?: clientAck
            }

            sendTcpControl(
                key = key,
                seq = sessions[key]?.serverSeq ?: serverSeq,
                ack = sessions[key]?.clientSeqNext ?: clientAck,
                flags = TCP_FIN or TCP_ACK
            )
            sessions[key]?.let { closeSession(it) }
            sessions.remove(key)
        }
    }

    private fun handleExistingSession(session: Session, packet: PacketParser.ParsedPacket.Tcp) {
        scope.launch(Dispatchers.IO) {
            if (session.closed) return@launch
            if (packet.rst) {
                closeSession(session)
                sessions.remove(session.key)
                return@launch
            }

            if (packet.fin) {
                session.clientSeqNext = packet.seq + 1
                sendTcpControl(
                    key = session.key,
                    seq = session.serverSeq,
                    ack = session.clientSeqNext,
                    flags = TCP_ACK
                )
                closeSession(session)
                sessions.remove(session.key)
                return@launch
            }

            if (packet.payload.isNotEmpty()) {
                session.clientSeqNext = packet.seq + packet.payload.size
                val profile = profileProvider()
                if (!ruleEngine.shouldDropPacket(profile)) {
                    val shapingDelay = trafficShaper.computeRequiredDelayMs(packet.payload.size, profile)
                    val networkDelay = ruleEngine.computeDelayMs(profile)
                    val totalDelay = shapingDelay + networkDelay
                    if (totalDelay > 0) delay(totalDelay)

                    val out = BufferedOutputStream(session.socket.getOutputStream())
                    out.write(packet.payload)
                    out.flush()
                } else {
                    StatsStore.onPacketDropped()
                }

                sendTcpControl(
                    key = session.key,
                    seq = session.serverSeq,
                    ack = session.clientSeqNext,
                    flags = TCP_ACK
                )
                return@launch
            }

            if (packet.ackFlag && packet.payload.isEmpty()) {
                session.clientSeqNext = maxOf(session.clientSeqNext, packet.seq)
            }
        }
    }

    private suspend fun sendTcpControl(
        key: SessionKey,
        seq: Long,
        ack: Long,
        flags: Int
    ) {
        val packet = PacketBuilder.buildTcpPacket(
            srcIp = key.remoteIp,
            dstIp = key.clientIp,
            srcPort = key.remotePort,
            dstPort = key.clientPort,
            seq = seq,
            ack = ack,
            flags = flags
        )
        sendToTun(packet)
    }

    private fun closeSession(session: Session) {
        session.closed = true
        runCatching { session.readJob.cancel() }
        runCatching { session.socket.close() }
    }

    companion object {
        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10
    }
}
