package com.kyenet.ksnetlite.net

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PacketParser {
    private const val IPV4_VERSION = 4
    private const val PROTOCOL_TCP = 6
    private const val PROTOCOL_UDP = 17

    sealed class ParsedPacket {
        data class Tcp(
            val srcIp: InetAddress,
            val dstIp: InetAddress,
            val srcPort: Int,
            val dstPort: Int,
            val seq: Long,
            val ack: Long,
            val syn: Boolean,
            val ackFlag: Boolean,
            val fin: Boolean,
            val rst: Boolean,
            val psh: Boolean,
            val window: Int,
            val payload: ByteArray
        ) : ParsedPacket()

        data class Udp(
            val srcIp: InetAddress,
            val dstIp: InetAddress,
            val srcPort: Int,
            val dstPort: Int,
            val payload: ByteArray
        ) : ParsedPacket()
    }

    fun parse(packet: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null
        val version = packet[0].toInt().ushr(4) and 0x0F
        if (version != IPV4_VERSION) return null

        val ihlBytes = (packet[0].toInt() and 0x0F) * 4
        if (length < ihlBytes + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val dstIp = InetAddress.getByAddress(packet.copyOfRange(16, 20))

        return when (protocol) {
            PROTOCOL_TCP -> parseTcp(packet, length, ihlBytes, srcIp, dstIp)
            PROTOCOL_UDP -> parseUdp(packet, length, ihlBytes, srcIp, dstIp)
            else -> null
        }
    }

    private fun parseTcp(
        packet: ByteArray,
        length: Int,
        ipHeaderLen: Int,
        srcIp: InetAddress,
        dstIp: InetAddress
    ): ParsedPacket.Tcp? {
        if (length < ipHeaderLen + 20) return null
        val srcPort = u16(packet, ipHeaderLen)
        val dstPort = u16(packet, ipHeaderLen + 2)
        val seq = u32(packet, ipHeaderLen + 4)
        val ack = u32(packet, ipHeaderLen + 8)
        val dataOffset = ((packet[ipHeaderLen + 12].toInt().ushr(4)) and 0x0F) * 4
        if (dataOffset < 20) return null
        val tcpHeaderEnd = ipHeaderLen + dataOffset
        if (length < tcpHeaderEnd) return null
        val flags = packet[ipHeaderLen + 13].toInt() and 0xFF
        val payload = if (length > tcpHeaderEnd) {
            packet.copyOfRange(tcpHeaderEnd, length)
        } else {
            ByteArray(0)
        }
        return ParsedPacket.Tcp(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            seq = seq,
            ack = ack,
            syn = (flags and 0x02) != 0,
            ackFlag = (flags and 0x10) != 0,
            fin = (flags and 0x01) != 0,
            rst = (flags and 0x04) != 0,
            psh = (flags and 0x08) != 0,
            window = u16(packet, ipHeaderLen + 14),
            payload = payload
        )
    }

    private fun parseUdp(
        packet: ByteArray,
        length: Int,
        ipHeaderLen: Int,
        srcIp: InetAddress,
        dstIp: InetAddress
    ): ParsedPacket.Udp? {
        if (length < ipHeaderLen + 8) return null
        val srcPort = u16(packet, ipHeaderLen)
        val dstPort = u16(packet, ipHeaderLen + 2)
        val udpLen = u16(packet, ipHeaderLen + 4)
        val payloadStart = ipHeaderLen + 8
        val payloadEnd = (payloadStart + (udpLen - 8)).coerceAtMost(length)
        if (payloadEnd < payloadStart) return null
        return ParsedPacket.Udp(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            payload = packet.copyOfRange(payloadStart, payloadEnd)
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        val bb = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN)
        return bb.int.toLong() and 0xFFFF_FFFFL
    }
}
