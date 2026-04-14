package com.kyenet.ksnetlite.net

import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

internal class DnsProxy(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val ruleEngine: RuleEngine,
    private val trafficShaper: TrafficShaper,
    private val profileProvider: () -> NetworkProfile,
    private val sendToTun: suspend (ByteArray) -> Unit
) {
    fun handleUdpPacket(packet: PacketParser.ParsedPacket.Udp) {
        if (packet.dstPort != DNS_PORT) return
        scope.launch(Dispatchers.IO) {
            val profile = profileProvider()
            if (ruleEngine.shouldDropPacket(profile)) return@launch
            val upstreamDelay = ruleEngine.computeDelayMs(profile) +
                trafficShaper.computeRequiredDelayMs(packet.payload.size, profile)
            if (upstreamDelay > 0) delay(upstreamDelay)

            val response = queryDns(packet.payload) ?: return@launch

            val downstreamProfile = profileProvider()
            if (ruleEngine.shouldDropPacket(downstreamProfile)) return@launch
            val downstreamDelay = ruleEngine.computeDelayMs(downstreamProfile) +
                trafficShaper.computeRequiredDelayMs(response.size, downstreamProfile)
            if (downstreamDelay > 0) delay(downstreamDelay)

            val responsePacket = PacketBuilder.buildUdpPacket(
                srcIp = packet.dstIp,
                dstIp = packet.srcIp,
                srcPort = DNS_PORT,
                dstPort = packet.srcPort,
                payload = response
            )
            sendToTun(responsePacket)
        }
    }

    private fun queryDns(queryPayload: ByteArray): ByteArray? {
        val socket = DatagramSocket()
        return try {
            vpnService.protect(socket)
            socket.soTimeout = 4_000
            socket.connect(InetSocketAddress(DNS_SERVER, DNS_PORT))
            socket.send(DatagramPacket(queryPayload, queryPayload.size))
            val buffer = ByteArray(1500)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)
            buffer.copyOf(responsePacket.length)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val DNS_PORT = 53
        private val DNS_SERVER: InetAddress = InetAddress.getByName("8.8.8.8")
    }
}
