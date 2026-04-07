package com.proxyconnect.app.proxy

import java.net.InetAddress

/**
 * Lightweight IP/TCP/UDP packet parser and builder.
 * Works with raw byte arrays from the TUN interface.
 */
class Packet(val raw: ByteArray, val length: Int) {

    // IP fields
    val version: Int get() = (raw[0].toInt() shr 4) and 0xF
    val ipHeaderLen: Int get() = (raw[0].toInt() and 0xF) * 4
    val totalLength: Int get() = u16(2)
    val protocol: Int get() = raw[9].toInt() and 0xFF
    val srcAddr: InetAddress get() = InetAddress.getByAddress(raw.copyOfRange(12, 16))
    val dstAddr: InetAddress get() = InetAddress.getByAddress(raw.copyOfRange(16, 20))

    val isTcp: Boolean get() = protocol == 6
    val isUdp: Boolean get() = protocol == 17

    // TCP fields (only valid if isTcp)
    val srcPort: Int get() = u16(ipHeaderLen)
    val dstPort: Int get() = u16(ipHeaderLen + 2)
    val tcpSeqNum: Long get() = u32(ipHeaderLen + 4)
    val tcpAckNum: Long get() = u32(ipHeaderLen + 8)
    val tcpHeaderLen: Int get() = ((raw[ipHeaderLen + 12].toInt() shr 4) and 0xF) * 4
    val tcpFlags: Int get() = raw[ipHeaderLen + 13].toInt() and 0xFF
    val tcpWindow: Int get() = u16(ipHeaderLen + 14)

    val isSyn: Boolean get() = (tcpFlags and FLAG_SYN) != 0
    val isAck: Boolean get() = (tcpFlags and FLAG_ACK) != 0
    val isFin: Boolean get() = (tcpFlags and FLAG_FIN) != 0
    val isRst: Boolean get() = (tcpFlags and FLAG_RST) != 0
    val isPsh: Boolean get() = (tcpFlags and FLAG_PSH) != 0

    val tcpPayloadOffset: Int get() = ipHeaderLen + tcpHeaderLen
    val tcpPayloadLength: Int get() = totalLength - tcpPayloadOffset
    val tcpPayload: ByteArray
        get() {
            val off = tcpPayloadOffset
            val len = tcpPayloadLength
            return if (len > 0 && off + len <= length) raw.copyOfRange(off, off + len) else ByteArray(0)
        }

    // UDP fields (only valid if isUdp)
    val udpSrcPort: Int get() = u16(ipHeaderLen)
    val udpDstPort: Int get() = u16(ipHeaderLen + 2)
    val udpLength: Int get() = u16(ipHeaderLen + 4)
    val udpPayload: ByteArray
        get() {
            val off = ipHeaderLen + 8
            val len = udpLength - 8
            return if (len > 0 && off + len <= length) raw.copyOfRange(off, off + len) else ByteArray(0)
        }

    /** TCP session key: "srcIp:srcPort->dstIp:dstPort" */
    val sessionKey: String
        get() = "${srcAddr.hostAddress}:$srcPort->${dstAddr.hostAddress}:$dstPort"

    private fun u16(offset: Int): Int =
        ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)

    private fun u32(offset: Int): Long =
        ((raw[offset].toLong() and 0xFF) shl 24) or
                ((raw[offset + 1].toLong() and 0xFF) shl 16) or
                ((raw[offset + 2].toLong() and 0xFF) shl 8) or
                (raw[offset + 3].toLong() and 0xFF)

    companion object {
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10

        /**
         * Build a TCP response packet (going back through the TUN to the device).
         * src/dst are SWAPPED relative to the original packet.
         */
        fun buildTcpPacket(
            srcAddr: InetAddress, srcPort: Int,
            dstAddr: InetAddress, dstPort: Int,
            seqNum: Long, ackNum: Long,
            flags: Int,
            payload: ByteArray = ByteArray(0),
            window: Int = 65535
        ): ByteArray {
            val ipHdrLen = 20
            val tcpHdrLen = 20
            val totalLen = ipHdrLen + tcpHdrLen + payload.size
            val pkt = ByteArray(totalLen)

            // --- IP header ---
            pkt[0] = 0x45.toByte() // IPv4, IHL=5
            pkt[1] = 0x00.toByte() // DSCP/ECN
            pkt[2] = (totalLen shr 8).toByte()
            pkt[3] = (totalLen and 0xFF).toByte()
            // Identification (bytes 4-5): 0
            pkt[6] = 0x40.toByte() // Don't fragment
            pkt[8] = 64 // TTL
            pkt[9] = 6  // TCP
            System.arraycopy(srcAddr.address, 0, pkt, 12, 4)
            System.arraycopy(dstAddr.address, 0, pkt, 16, 4)
            // IP checksum
            val ipCksum = checksum(pkt, 0, ipHdrLen)
            pkt[10] = (ipCksum shr 8).toByte()
            pkt[11] = (ipCksum and 0xFF).toByte()

            // --- TCP header ---
            pkt[ipHdrLen + 0] = (srcPort shr 8).toByte()
            pkt[ipHdrLen + 1] = (srcPort and 0xFF).toByte()
            pkt[ipHdrLen + 2] = (dstPort shr 8).toByte()
            pkt[ipHdrLen + 3] = (dstPort and 0xFF).toByte()
            // Seq
            pkt[ipHdrLen + 4] = (seqNum shr 24).toByte()
            pkt[ipHdrLen + 5] = (seqNum shr 16).toByte()
            pkt[ipHdrLen + 6] = (seqNum shr 8).toByte()
            pkt[ipHdrLen + 7] = (seqNum and 0xFF).toByte()
            // Ack
            pkt[ipHdrLen + 8] = (ackNum shr 24).toByte()
            pkt[ipHdrLen + 9] = (ackNum shr 16).toByte()
            pkt[ipHdrLen + 10] = (ackNum shr 8).toByte()
            pkt[ipHdrLen + 11] = (ackNum and 0xFF).toByte()
            // Data offset (5 words = 20 bytes)
            pkt[ipHdrLen + 12] = 0x50.toByte()
            // Flags
            pkt[ipHdrLen + 13] = flags.toByte()
            // Window
            pkt[ipHdrLen + 14] = (window shr 8).toByte()
            pkt[ipHdrLen + 15] = (window and 0xFF).toByte()

            // Payload
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, pkt, ipHdrLen + tcpHdrLen, payload.size)
            }

            // TCP checksum (with pseudo-header)
            val tcpCksum = tcpChecksum(pkt, srcAddr, dstAddr, ipHdrLen, tcpHdrLen + payload.size)
            pkt[ipHdrLen + 16] = (tcpCksum shr 8).toByte()
            pkt[ipHdrLen + 17] = (tcpCksum and 0xFF).toByte()

            return pkt
        }

        fun buildUdpPacket(
            srcAddr: InetAddress, srcPort: Int,
            dstAddr: InetAddress, dstPort: Int,
            payload: ByteArray
        ): ByteArray {
            val ipHdrLen = 20
            val udpHdrLen = 8
            val totalLen = ipHdrLen + udpHdrLen + payload.size
            val pkt = ByteArray(totalLen)

            pkt[0] = 0x45.toByte()
            pkt[2] = (totalLen shr 8).toByte()
            pkt[3] = (totalLen and 0xFF).toByte()
            pkt[6] = 0x40.toByte()
            pkt[8] = 64
            pkt[9] = 17 // UDP
            System.arraycopy(srcAddr.address, 0, pkt, 12, 4)
            System.arraycopy(dstAddr.address, 0, pkt, 16, 4)
            val ipCksum = checksum(pkt, 0, ipHdrLen)
            pkt[10] = (ipCksum shr 8).toByte()
            pkt[11] = (ipCksum and 0xFF).toByte()

            val udpLen = udpHdrLen + payload.size
            pkt[ipHdrLen + 0] = (srcPort shr 8).toByte()
            pkt[ipHdrLen + 1] = (srcPort and 0xFF).toByte()
            pkt[ipHdrLen + 2] = (dstPort shr 8).toByte()
            pkt[ipHdrLen + 3] = (dstPort and 0xFF).toByte()
            pkt[ipHdrLen + 4] = (udpLen shr 8).toByte()
            pkt[ipHdrLen + 5] = (udpLen and 0xFF).toByte()

            System.arraycopy(payload, 0, pkt, ipHdrLen + udpHdrLen, payload.size)

            // UDP checksum (with pseudo-header)
            val udpCk = udpChecksum(pkt, srcAddr, dstAddr, ipHdrLen, udpLen)
            pkt[ipHdrLen + 6] = (udpCk shr 8).toByte()
            pkt[ipHdrLen + 7] = (udpCk and 0xFF).toByte()

            return pkt
        }

        fun checksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0L
            var i = offset
            val end = offset + length
            while (i < end - 1) {
                sum += ((data[i].toLong() and 0xFF) shl 8) or (data[i + 1].toLong() and 0xFF)
                i += 2
            }
            if (i < end) sum += (data[i].toLong() and 0xFF) shl 8
            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }

        private fun tcpChecksum(
            pkt: ByteArray, srcAddr: InetAddress, dstAddr: InetAddress,
            tcpOffset: Int, tcpLength: Int
        ): Int {
            // Pseudo-header: srcIP(4) + dstIP(4) + zero(1) + proto(1) + tcpLen(2) = 12 bytes
            val pseudo = ByteArray(12 + tcpLength)
            System.arraycopy(srcAddr.address, 0, pseudo, 0, 4)
            System.arraycopy(dstAddr.address, 0, pseudo, 4, 4)
            pseudo[8] = 0
            pseudo[9] = 6 // TCP
            pseudo[10] = (tcpLength shr 8).toByte()
            pseudo[11] = (tcpLength and 0xFF).toByte()
            // Clear existing checksum in copy
            System.arraycopy(pkt, tcpOffset, pseudo, 12, tcpLength)
            pseudo[12 + 16] = 0
            pseudo[12 + 17] = 0
            return checksum(pseudo, 0, pseudo.size)
        }

        private fun udpChecksum(
            pkt: ByteArray, srcAddr: InetAddress, dstAddr: InetAddress,
            udpOffset: Int, udpLength: Int
        ): Int {
            val pseudo = ByteArray(12 + udpLength)
            System.arraycopy(srcAddr.address, 0, pseudo, 0, 4)
            System.arraycopy(dstAddr.address, 0, pseudo, 4, 4)
            pseudo[8] = 0
            pseudo[9] = 17 // UDP
            pseudo[10] = (udpLength shr 8).toByte()
            pseudo[11] = (udpLength and 0xFF).toByte()
            System.arraycopy(pkt, udpOffset, pseudo, 12, udpLength)
            pseudo[12 + 6] = 0
            pseudo[12 + 7] = 0
            return checksum(pseudo, 0, pseudo.size)
        }
    }
}
