package com.proxyconnect.app.proxy

import android.net.VpnService
import android.util.Log
import com.proxyconnect.app.data.ProxyConfig
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Main packet forwarding loop.
 * Reads IP packets from the TUN interface, dispatches TCP to TcpConnection,
 * handles UDP/DNS directly.
 */
class PacketForwarder(
    private val tunInput: FileInputStream,
    private val tunOutput: FileOutputStream,
    private val config: ProxyConfig,
    private val vpnService: VpnService
) {
    companion object {
        private const val TAG = "PacketFwd"
    }

    private val tcpConnections = ConcurrentHashMap<String, TcpConnection>()

    @Volatile
    private var running = false

    /**
     * Blocking loop - reads packets from TUN and dispatches them.
     * Call from a background thread. Call stop() to break out.
     */
    fun run() {
        running = true
        val buffer = ByteArray(32767)

        Log.i(TAG, "Packet forwarder started")

        while (running) {
            try {
                val n = tunInput.read(buffer)
                if (n <= 0) {
                    Thread.sleep(1)
                    continue
                }

                val pktData = buffer.copyOfRange(0, n)
                val pkt = Packet(pktData, n)

                if (pkt.version != 4) continue // IPv4 only
                if (pkt.totalLength > n) continue // Malformed

                when {
                    pkt.isTcp -> handleTcp(pkt)
                    pkt.isUdp -> handleUdp(pkt)
                }

            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Read error: ${e.message}")
                    // Don't spin on EBADF - means TUN fd is closed
                    if (e.message?.contains("EBADF") == true) {
                        Log.e(TAG, "TUN fd closed, stopping forwarder")
                        break
                    }
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
        }

        Log.i(TAG, "Packet forwarder stopped")
    }

    fun stop() {
        running = false
        // Close all TCP connections
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()
    }

    private fun handleTcp(pkt: Packet) {
        val key = pkt.sessionKey

        if (pkt.isSyn && !pkt.isAck) {
            // Block DNS-over-TLS (port 853) to our DNS server - force fallback to plain UDP DNS
            // which we handle ourselves. The proxy likely blocks DoT connections.
            val dstIp = pkt.dstAddr.hostAddress ?: return
            if (pkt.dstPort == 853 && dstIp == config.dns) {
                Log.d(TAG, "Blocking DoT to $dstIp:853, forcing UDP DNS fallback")
                sendRst(pkt)
                return
            }

            // SYN retransmit? Don't destroy in-progress connections.
            val existing = tcpConnections[key]
            if (existing != null && existing.state != TcpConnection.State.CLOSED) {
                return
            }
            existing?.close()
            tcpConnections.remove(key)

            val targetHost = FakeDns.lookup(dstIp) ?: dstIp
            Log.d(TAG, "TCP SYN $key -> target=$targetHost")

            val conn = TcpConnection(
                srcAddr = pkt.srcAddr,
                srcPort = pkt.srcPort,
                dstAddr = pkt.dstAddr,
                dstPort = pkt.dstPort,
                targetHost = targetHost, // Pass the domain name!
                config = config,
                vpnService = vpnService,
                tunOutput = tunOutput,
                onClose = { k -> tcpConnections.remove(k) }
            )
            tcpConnections[key] = conn
            conn.handleSyn(pkt)
            return
        }

        // Existing connection
        val conn = tcpConnections[key]
        if (conn != null) {
            conn.handlePacket(pkt)
        } else if (!pkt.isRst) {
            // No connection found - send RST
            sendRst(pkt)
        }
    }

    private fun sendRst(pkt: Packet) {
        try {
            val rst = Packet.buildTcpPacket(
                srcAddr = pkt.dstAddr, srcPort = pkt.dstPort,
                dstAddr = pkt.srcAddr, dstPort = pkt.srcPort,
                seqNum = pkt.tcpAckNum,
                ackNum = (pkt.tcpSeqNum + maxOf(1, pkt.tcpPayloadLength.toLong())) and 0xFFFFFFFFL,
                flags = Packet.FLAG_RST or Packet.FLAG_ACK
            )
            synchronized(tunOutput) {
                tunOutput.write(rst)
                // tunOutput is a raw TUN fd — no buffering, flush is unnecessary
            }
        } catch (_: Exception) {}
    }

    private fun handleUdp(pkt: Packet) {
        if (pkt.udpDstPort == 53) {
            // DNS query - use FakeDNS: assign fake IP, return forged response.
            // No need to forward DNS queries to a real server.
            // The SOCKS5 proxy will resolve the real IP when we connect with the domain name.
            try {
                val query = pkt.udpPayload
                if (query.isEmpty()) return

                val result = FakeDns.buildFakeResponse(query)
                if (result != null) {
                    val (domain, response) = result

                    val udpResp = Packet.buildUdpPacket(
                        srcAddr = pkt.dstAddr, srcPort = pkt.udpDstPort,
                        dstAddr = pkt.srcAddr, dstPort = pkt.udpSrcPort,
                        payload = response
                    )
                    synchronized(tunOutput) {
                        tunOutput.write(udpResp)
                        // tunOutput is a raw TUN fd — no buffering, flush is unnecessary
                    }
                }
                // AAAA (IPv6) queries now get an empty NODATA response from buildFakeResponse,
                // so apps immediately fall back to IPv4 instead of timing out.
            } catch (e: Exception) {
                Log.e(TAG, "FakeDNS error: ${e.message}")
            }
        } else {
            // Non-DNS UDP (QUIC, STUN, etc): send ICMP Port Unreachable so apps
            // instantly fall back to TCP instead of waiting for a timeout.
            try {
                val icmp = Packet.buildIcmpUnreachable(pkt.raw, pkt.length)
                synchronized(tunOutput) {
                    tunOutput.write(icmp)
                    // tunOutput is a raw TUN fd — no buffering, flush is unnecessary
                }
            } catch (_: Exception) {}
        }
    }
}
