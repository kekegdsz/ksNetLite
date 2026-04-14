package com.kyenet.ksnetlite.net

import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

internal class UdpProxy(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val ruleEngine: RuleEngine,
    private val trafficShaper: TrafficShaper,
    private val profileProvider: () -> NetworkProfile,
    private val sendToTun: suspend (ByteArray) -> Unit
) {
    fun handleUdpPacket(packet: PacketParser.ParsedPacket.Udp) {
        scope.launch(Dispatchers.IO) {
            val profile = profileProvider()
            val protectControlPacket = packet.dstPort == DNS_PORT || packet.srcPort == DNS_PORT
            if (!protectControlPacket && ruleEngine.shouldDropPacket(profile)) {
                StatsStore.onPacketDropped()
                return@launch
            }

            val upstreamDelay = ruleEngine.computeDelayMs(profile) +
                trafficShaper.computeRequiredDelayMs(packet.payload.size, profile)
            if (upstreamDelay > 0) delay(upstreamDelay)

            val response = queryUpstream(packet) ?: return@launch

            val downProfile = profileProvider()
            if (!protectControlPacket && ruleEngine.shouldDropPacket(downProfile)) {
                StatsStore.onPacketDropped()
                return@launch
            }

            val downstreamDelay = ruleEngine.computeDelayMs(downProfile) +
                trafficShaper.computeRequiredDelayMs(response.size, downProfile)
            if (downstreamDelay > 0) delay(downstreamDelay)

            val responsePacket = PacketBuilder.buildUdpPacket(
                srcIp = packet.dstIp,
                dstIp = packet.srcIp,
                srcPort = packet.dstPort,
                dstPort = packet.srcPort,
                payload = response
            )
            sendToTun(responsePacket)
        }
    }

    private fun queryUpstream(packet: PacketParser.ParsedPacket.Udp): ByteArray? {
        val socket = DatagramSocket()
        return try {
            vpnService.protect(socket)
            socket.soTimeout = 4_000
            socket.connect(InetSocketAddress(packet.dstIp, packet.dstPort))
            socket.send(DatagramPacket(packet.payload, packet.payload.size))
            val buffer = ByteArray(8 * 1024)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)
            buffer.copyOf(responsePacket.length)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private companion object {
        private const val DNS_PORT = 53
    }
}
