package com.proxyconnect.app.proxy

import android.net.VpnService
import android.util.Log
import com.proxyconnect.app.data.ProxyConfig
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import kotlin.random.Random

class ProxyException(message: String) : Exception(message)

/**
 * Manages a single TCP connection's full lifecycle through the proxy.
 * Implements the TCP state machine with proper seq/ack tracking.
 *
 * Flow:
 *   Device SYN → connect to proxy → SYN-ACK to device → ESTABLISHED
 *   Device DATA → forward to proxy → ACK to device
 *   Proxy DATA → wrap in TCP → send to device via TUN
 *   Device FIN → FIN-ACK → CLOSED
 */
class TcpConnection(
    val srcAddr: InetAddress,
    val srcPort: Int,
    val dstAddr: InetAddress,
    val dstPort: Int,
    val targetHost: String, // Domain name from DNS cache, or raw IP as fallback
    private val config: ProxyConfig,
    private val vpnService: VpnService,
    private val tunOutput: FileOutputStream,
    private val onClose: (String) -> Unit
) {
    companion object {
        private const val TAG = "TcpConn"
    }

    enum class State {
        SYN_RECEIVED,
        ESTABLISHED,
        CLOSE_WAIT,
        CLOSED
    }

    val key = "${srcAddr.hostAddress}:$srcPort->${dstAddr.hostAddress}:$dstPort"

    @Volatile
    var state = State.SYN_RECEIVED
        private set

    // Our sequence number (we are the "server" side from the device's perspective)
    private var mySeq: Long = Random.nextLong(0, 0xFFFFFFFFL) and 0xFFFFFFFFL
    // What we've acknowledged from the device
    private var myAck: Long = 0L
    private val seqLock = Any()

    private var proxySocket: Socket? = null
    private var readThread: Thread? = null

    @Volatile
    private var closed = false

    /**
     * Handle the initial SYN from the device.
     * Connects to the remote destination through the proxy,
     * then sends SYN-ACK back to the device.
     */
    fun handleSyn(pkt: Packet) {
        synchronized(seqLock) { myAck = (pkt.tcpSeqNum + 1) and 0xFFFFFFFFL }
        state = State.SYN_RECEIVED

        Thread {
            try {
                Log.i(TAG, "[$key] Connecting via proxy to $targetHost:$dstPort")

                val socket = connectWithAutoDetect(targetHost, dstPort)
                socket.soTimeout = 0

                proxySocket = socket

                // Send SYN-ACK back to device
                sendToDevice(Packet.FLAG_SYN or Packet.FLAG_ACK)
                synchronized(seqLock) { mySeq = (mySeq + 1) and 0xFFFFFFFFL } // SYN consumes 1 seq

                // Start reading from proxy socket → device
                startProxyReader(socket)

            } catch (e: Exception) {
                Log.e(TAG, "[$key] Proxy connect failed: ${e.message}")
                sendRst()
                close()
            }
        }.start()
    }

    /**
     * Handle a packet from the device for this connection.
     */
    fun handlePacket(pkt: Packet) {
        if (closed) return

        when {
            pkt.isRst -> {
                close()
            }

            pkt.isFin -> {
                // Forward any data that arrived with the FIN before closing
                if (pkt.tcpPayloadLength > 0) {
                    forwardData(pkt)
                }
                synchronized(seqLock) {
                    myAck = (pkt.tcpSeqNum + pkt.tcpPayloadLength + 1) and 0xFFFFFFFFL
                }
                // Send FIN-ACK
                sendToDevice(Packet.FLAG_FIN or Packet.FLAG_ACK)
                synchronized(seqLock) { mySeq = (mySeq + 1) and 0xFFFFFFFFL }
                state = State.CLOSE_WAIT
                close()
            }

            pkt.isAck && state == State.SYN_RECEIVED -> {
                state = State.ESTABLISHED
                // Handshake complete, forward any data if proxy socket is ready
                if (pkt.tcpPayloadLength > 0 && proxySocket != null) {
                    forwardData(pkt)
                }
            }

            pkt.tcpPayloadLength > 0 && state == State.ESTABLISHED -> {
                forwardData(pkt)
            }

            pkt.isAck -> {
                // Pure ACK - nothing to do
            }
        }
    }

    private fun forwardData(pkt: Packet) {
        val payload = pkt.tcpPayload
        if (payload.isEmpty()) return

        TrafficStats.addUpload(payload.size)
        synchronized(seqLock) { myAck = (pkt.tcpSeqNum + payload.size.toLong()) and 0xFFFFFFFFL }

        // Forward data to proxy
        try {
            proxySocket?.getOutputStream()?.apply {
                write(payload)
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$key] Write to proxy failed: ${e.message}")
            sendRst()
            close()
            return
        }

        // ACK the data back to device
        sendToDevice(Packet.FLAG_ACK)
    }

    /**
     * Read data from the proxy socket and send it back to the device as TCP segments.
     */
    private fun startProxyReader(socket: Socket) {
        readThread = Thread {
            try {
                val input = socket.getInputStream()
                val buffer = ByteArray(1400)

                while (!closed && !socket.isClosed) {
                    val n = input.read(buffer)
                    if (n <= 0) break

                    val data = buffer.copyOfRange(0, n)
                    sendToDevice(Packet.FLAG_ACK or Packet.FLAG_PSH, data)
                    synchronized(seqLock) { mySeq = (mySeq + n) and 0xFFFFFFFFL }
                }

                // Proxy closed the connection - send FIN to device
                if (!closed) {
                    sendToDevice(Packet.FLAG_FIN or Packet.FLAG_ACK)
                    synchronized(seqLock) { mySeq = (mySeq + 1) and 0xFFFFFFFFL }
                }

            } catch (e: Exception) {
                if (!closed) {
                    Log.e(TAG, "[$key] Proxy read error: ${e.message}")
                }
            } finally {
                close()
            }
        }
        readThread?.name = "proxy-read-$key"
        readThread?.isDaemon = true
        readThread?.start()
    }

    /**
     * Send a TCP packet back to the device through the TUN interface.
     * The src/dst are SWAPPED (we're the remote server responding).
     */
    private fun sendToDevice(flags: Int, payload: ByteArray = ByteArray(0)) {
        val flagStr = buildString {
            if (flags and Packet.FLAG_SYN != 0) append("SYN ")
            if (flags and Packet.FLAG_ACK != 0) append("ACK ")
            if (flags and Packet.FLAG_PSH != 0) append("PSH ")
            if (flags and Packet.FLAG_FIN != 0) append("FIN ")
            if (flags and Packet.FLAG_RST != 0) append("RST ")
        }
        val (seq, ack) = synchronized(seqLock) { mySeq to myAck }
        Log.d(TAG, "[$key] -> device: ${flagStr.trim()} seq=$seq ack=$ack len=${payload.size}")
        try {
            val pkt = Packet.buildTcpPacket(
                srcAddr = dstAddr, srcPort = dstPort,  // We are the "server"
                dstAddr = srcAddr, dstPort = srcPort,  // Device is the "client"
                seqNum = seq,
                ackNum = ack,
                flags = flags,
                payload = payload
            )
            synchronized(tunOutput) {
                tunOutput.write(pkt)
                tunOutput.flush()
            }
            if (payload.isNotEmpty()) TrafficStats.addDownload(payload.size)
        } catch (e: Exception) {
            Log.e(TAG, "[$key] TUN write failed: ${e.message}")
        }
    }

    private fun sendRst() {
        try {
            sendToDevice(Packet.FLAG_RST or Packet.FLAG_ACK)
        } catch (_: Exception) {}
    }

    /**
     * Auto-detect proxy protocol. Tries protocols in order:
     * - If a protocol was previously detected, try that first.
     * - Otherwise try: HTTP → HTTPS → SOCKS5
     * - Cache the working protocol for future connections.
     */
    private fun connectWithAutoDetect(targetHost: String, targetPort: Int): Socket {
        val cached = config.detectedProtocol
        val protocols = if (cached != ProxyConfig.ProxyProtocol.UNKNOWN) {
            // Try cached first, then the rest
            listOf(cached) + listOf(
                ProxyConfig.ProxyProtocol.HTTP,
                ProxyConfig.ProxyProtocol.HTTPS,
                ProxyConfig.ProxyProtocol.SOCKS5
            ).filter { it != cached }
        } else {
            listOf(
                ProxyConfig.ProxyProtocol.HTTP,
                ProxyConfig.ProxyProtocol.HTTPS,
                ProxyConfig.ProxyProtocol.SOCKS5
            )
        }

        var lastError: Exception? = null

        for (proto in protocols) {
            var socket: Socket? = null
            var sslSocket: javax.net.ssl.SSLSocket? = null
            try {
                socket = Socket()
                vpnService.protect(socket)

                if (proto == ProxyConfig.ProxyProtocol.HTTPS) {
                    // For HTTPS proxy, wrap in SSL before HTTP CONNECT
                    socket.connect(java.net.InetSocketAddress(config.host, config.port), 10_000)
                    socket.soTimeout = 10_000
                    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf(TrustAllManager()), null)
                    sslSocket = sslContext.socketFactory.createSocket(
                        socket, config.host, config.port, true
                    ) as javax.net.ssl.SSLSocket
                    vpnService.protect(sslSocket)
                    sslSocket.soTimeout = 10_000
                    httpConnectHandshake(sslSocket, targetHost, targetPort)
                    Log.i(TAG, "[$key] Connected via HTTPS proxy")
                    ProxyConfig.saveDetectedProtocol(vpnService as android.content.Context, proto)
                    return sslSocket
                } else {
                    socket.connect(java.net.InetSocketAddress(config.host, config.port), 10_000)
                    socket.soTimeout = 10_000
                    socket.keepAlive = true

                    when (proto) {
                        ProxyConfig.ProxyProtocol.SOCKS5 -> socks5Handshake(socket, targetHost, targetPort)
                        ProxyConfig.ProxyProtocol.HTTP -> httpConnectHandshake(socket, targetHost, targetPort)
                        else -> {}
                    }

                    Log.i(TAG, "[$key] Connected via ${proto.name} proxy")
                    ProxyConfig.saveDetectedProtocol(vpnService as android.content.Context, proto)
                    return socket
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$key] ${proto.name} failed: ${e.message}")
                lastError = e
                try { sslSocket?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }

        throw lastError ?: ProxyException("All proxy protocols failed")
    }

    /** Trust-all manager for HTTPS proxies with self-signed certs */
    private class TrustAllManager : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    fun close() {
        if (closed) return
        closed = true
        state = State.CLOSED

        try { proxySocket?.close() } catch (_: Exception) {}
        proxySocket = null

        onClose(key)
    }

    // ---- SOCKS5 handshake (duplicated here to use pre-protected socket) ----

    private fun socks5Handshake(socket: Socket, targetHost: String, targetPort: Int) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        val hasAuth = config.username.isNotBlank()

        if (hasAuth) {
            output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            output.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        output.flush()

        val greeting = readBytes(input, 2)
        if (greeting[0] != 0x05.toByte()) throw ProxyException("Bad SOCKS5 greeting")

        if (greeting[1] == 0x02.toByte()) {
            val user = config.username.toByteArray()
            val pass = config.password.toByteArray()
            if (user.size > 255) throw ProxyException("SOCKS5 username too long (max 255 bytes)")
            if (pass.size > 255) throw ProxyException("SOCKS5 password too long (max 255 bytes)")
            val auth = ByteArray(3 + user.size + pass.size)
            auth[0] = 0x01
            auth[1] = user.size.toByte()
            System.arraycopy(user, 0, auth, 2, user.size)
            auth[2 + user.size] = pass.size.toByte()
            System.arraycopy(pass, 0, auth, 3 + user.size, pass.size)
            output.write(auth)
            output.flush()
            val authResp = readBytes(input, 2)
            if (authResp[1] != 0x00.toByte()) throw ProxyException("SOCKS5 auth failed")
        } else if (greeting[1] != 0x00.toByte()) {
            throw ProxyException("SOCKS5 no acceptable auth")
        }

        val domain = targetHost.toByteArray()
        if (domain.size > 255) throw ProxyException("Domain too long")
        val req = ByteArray(7 + domain.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = (domain.size and 0xFF).toByte()
        System.arraycopy(domain, 0, req, 5, domain.size)
        req[5 + domain.size] = (targetPort shr 8).toByte()
        req[6 + domain.size] = (targetPort and 0xFF).toByte()
        output.write(req)
        output.flush()

        val resp = readBytes(input, 4)
        if (resp[1] != 0x00.toByte()) throw ProxyException("SOCKS5 connect failed: ${resp[1]}")

        when (resp[3].toInt()) {
            0x01 -> readBytes(input, 6)   // IPv4 + port
            0x03 -> { val len = input.read(); if (len < 0) throw ProxyException("EOF during SOCKS5 bind addr"); readBytes(input, len + 2) } // domain + port
            0x04 -> readBytes(input, 18)  // IPv6 + port
        }
    }

    private fun httpConnectHandshake(socket: Socket, targetHost: String, targetPort: Int) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        val sb = StringBuilder()
        sb.append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
        sb.append("Host: $targetHost:$targetPort\r\n")
        if (config.username.isNotBlank()) {
            val cred = "${config.username}:${config.password}"
            val enc = java.util.Base64.getEncoder().encodeToString(cred.toByteArray())
            sb.append("Proxy-Authorization: Basic $enc\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray())
        output.flush()

        val line = readLine(input)
        if (!(line.startsWith("HTTP/") && line.split(" ").getOrNull(1) == "200")) throw ProxyException("HTTP CONNECT failed: $line")
        while (readLine(input).isNotBlank()) { /* skip headers */ }
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n < 0) throw ProxyException("EOF during read")
            off += n
        }
        return buf
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}
