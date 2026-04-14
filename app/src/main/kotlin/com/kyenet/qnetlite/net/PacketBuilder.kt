package com.kyenet.ksnetlite.net

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PacketBuilder {
    private const val IPV4_HEADER_LEN = 20
    private const val TCP_HEADER_LEN = 20
    private const val UDP_HEADER_LEN = 8
    private const val PROTOCOL_TCP = 6
    private const val PROTOCOL_UDP = 17

    fun buildTcpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int = 65535,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val totalLen = IPV4_HEADER_LEN + TCP_HEADER_LEN + payload.size
        val packet = ByteArray(totalLen)
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

        // IPv4 header
        bb.put(0x45.toByte()) // version + IHL
        bb.put(0) // DSCP/ECN
        bb.putShort(totalLen.toShort())
        bb.putShort(0) // identification
        bb.putShort(0x4000.toShort()) // don't fragment
        bb.put(64.toByte()) // ttl
        bb.put(PROTOCOL_TCP.toByte())
        bb.putShort(0) // ip checksum placeholder
        bb.put(srcIp.address)
        bb.put(dstIp.address)

        // TCP header
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putInt(seq.toInt())
        bb.putInt(ack.toInt())
        bb.put(((TCP_HEADER_LEN / 4) shl 4).toByte())
        bb.put(flags.toByte())
        bb.putShort(window.toShort())
        bb.putShort(0) // tcp checksum placeholder
        bb.putShort(0) // urgent pointer
        bb.put(payload)

        val ipChecksum = checksum(packet, 0, IPV4_HEADER_LEN)
        putU16(packet, 10, ipChecksum)

        val tcpChecksum = tcpChecksum(packet, srcIp.address, dstIp.address, TCP_HEADER_LEN + payload.size)
        putU16(packet, IPV4_HEADER_LEN + 16, tcpChecksum)

        return packet
    }

    fun buildUdpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = UDP_HEADER_LEN + payload.size
        val totalLen = IPV4_HEADER_LEN + udpLen
        val packet = ByteArray(totalLen)
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

        bb.put(0x45.toByte())
        bb.put(0)
        bb.putShort(totalLen.toShort())
        bb.putShort(0)
        bb.putShort(0x4000.toShort())
        bb.put(64.toByte())
        bb.put(PROTOCOL_UDP.toByte())
        bb.putShort(0)
        bb.put(srcIp.address)
        bb.put(dstIp.address)

        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putShort(udpLen.toShort())
        bb.putShort(0)
        bb.put(payload)

        val ipChecksum = checksum(packet, 0, IPV4_HEADER_LEN)
        putU16(packet, 10, ipChecksum)

        val udpChecksum = udpChecksum(packet, srcIp.address, dstIp.address, udpLen)
        putU16(packet, IPV4_HEADER_LEN + 6, udpChecksum)

        return packet
    }

    private fun tcpChecksum(packet: ByteArray, src: ByteArray, dst: ByteArray, tcpLen: Int): Int {
        return transportChecksum(packet, src, dst, PROTOCOL_TCP, tcpLen, IPV4_HEADER_LEN)
    }

    private fun udpChecksum(packet: ByteArray, src: ByteArray, dst: ByteArray, udpLen: Int): Int {
        return transportChecksum(packet, src, dst, PROTOCOL_UDP, udpLen, IPV4_HEADER_LEN)
    }

    private fun transportChecksum(
        packet: ByteArray,
        src: ByteArray,
        dst: ByteArray,
        protocol: Int,
        transportLen: Int,
        transportOffset: Int
    ): Int {
        var sum = 0L
        sum += sumWords(src, 0, src.size)
        sum += sumWords(dst, 0, dst.size)
        sum += protocol.toLong()
        sum += transportLen.toLong()
        sum += sumWords(packet, transportOffset, transportLen)
        return finalizeChecksum(sum)
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        val sum = sumWords(bytes, offset, length)
        return finalizeChecksum(sum)
    }

    private fun sumWords(bytes: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            sum += word.toLong()
            i += 2
        }
        if (i < end) {
            sum += ((bytes[i].toInt() and 0xFF) shl 8).toLong()
        }
        return sum
    }

    private fun finalizeChecksum(initialSum: Long): Int {
        var sum = initialSum
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }
}
