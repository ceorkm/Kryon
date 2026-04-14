package com.proxyconnect.app.proxy

import android.net.VpnService
import android.util.Log
import com.proxyconnect.app.data.ProxyConfig
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

class ProxyException(message: String) : Exception(message)

class TcpConnection(
    val srcAddr: InetAddress,
    val srcPort: Int,
    val dstAddr: InetAddress,
    val dstPort: Int,
    val targetHost: String,
    private val config: ProxyConfig,
    private val vpnService: VpnService,
    private val tunOutput: FileOutputStream,
    private val onClose: (String) -> Unit
) {
    companion object {
        private const val TAG = "TcpConn"
        private const val MAX_QUEUE_BYTES = 512 * 1024
        private const val SOCKET_BUFFER_BYTES = 256 * 1024
        private const val PROXY_STREAM_BUFFER_BYTES = 64 * 1024
        private const val PROXY_READ_BUFFER_BYTES = 1460
        private const val WINDOW_SCALE_SHIFT = 7
        private const val ACK_EVERY_SEGMENTS = 2
        private const val WINDOW_UPDATE_THRESHOLD_RAW = 256
    }

    enum class State { SYN_RECEIVED, ESTABLISHED, CLOSE_WAIT, CLOSED }

    private sealed class WriteCommand {
        data class Data(val bytes: ByteArray) : WriteCommand()
        object Finish : WriteCommand()
    }

    val key = "${srcAddr.hostAddress}:$srcPort->${dstAddr.hostAddress}:$dstPort"

    @Volatile var state = State.SYN_RECEIVED; private set

    private var mySeq: Long = Random.nextLong(0, 0x1_0000_0000L)
    private var myAck: Long = 0L

    private val seqLock = Any()
    private val proxyIoLock = Any()

    private val writeQueue = LinkedBlockingQueue<WriteCommand>()
    private val queuedBytes = AtomicInteger(0)
    private val pendingAckSegments = AtomicInteger(0)
    private val lastAdvertisedWindowRaw = AtomicInteger(computeAdvertisedWindowRaw(0))
    private val closed = AtomicBoolean(false)
    private val finQueued = AtomicBoolean(false)

    private var proxySocket: Socket? = null
    private var proxyOut: OutputStream? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null

    fun handleSyn(pkt: Packet) {
        synchronized(seqLock) {
            myAck = (pkt.tcpSeqNum + 1) and 0xFFFFFFFFL
        }
        state = State.SYN_RECEIVED

        Thread {
            try {
                Log.i(TAG, "[$key] Connecting via proxy to $targetHost:$dstPort")

                val socket = connectWithAutoDetect(targetHost, dstPort)
                socket.soTimeout = 0
                socket.sendBufferSize = SOCKET_BUFFER_BYTES
                socket.receiveBufferSize = SOCKET_BUFFER_BYTES

                synchronized(proxyIoLock) {
                    proxySocket = socket
                    proxyOut = BufferedOutputStream(socket.getOutputStream(), PROXY_STREAM_BUFFER_BYTES)
                }

                startProxyWriter()

                val window = lastAdvertisedWindowRaw.get()
                sendToDevice(
                    flags = Packet.FLAG_SYN or Packet.FLAG_ACK,
                    tcpOptions = Packet.SYN_ACK_OPTIONS,
                    window = window
                )
                synchronized(seqLock) {
                    mySeq = (mySeq + 1) and 0xFFFFFFFFL
                }

                startProxyReader(socket)
            } catch (e: Exception) {
                Log.e(TAG, "[$key] Proxy connect failed: ${e.message}", e)
                sendRst()
                close()
            }
        }.apply {
            name = "proxy-connect-$key"
            isDaemon = true
            start()
        }
    }

    fun handlePacket(pkt: Packet) {
        if (closed.get()) return

        if (pkt.isRst) {
            close()
            return
        }

        if (pkt.isAck && state == State.SYN_RECEIVED) {
            state = State.ESTABLISHED
        }

        if (pkt.tcpPayloadLength > 0) {
            enqueueData(pkt)
        }

        if (pkt.isFin) {
            synchronized(seqLock) {
                myAck = (pkt.tcpSeqNum + pkt.tcpPayloadLength + 1L) and 0xFFFFFFFFL
            }
            pendingAckSegments.set(0)
            sendToDevice(Packet.FLAG_FIN or Packet.FLAG_ACK, window = currentAdvertisedWindowRaw())
            synchronized(seqLock) {
                mySeq = (mySeq + 1) and 0xFFFFFFFFL
            }
            state = State.CLOSE_WAIT
            enqueueFinAfterDrain()
            return
        }

        if (pkt.tcpPayloadLength > 0 && state != State.CLOSED) {
            val queueWindowChanged = maybeSendWindowUpdate(fromWriter = false)
            val segmentCount = pendingAckSegments.incrementAndGet()
            if (pkt.isPsh || segmentCount >= ACK_EVERY_SEGMENTS) {
                pendingAckSegments.set(0)
                sendAckWithWindow()
            } else if (queueWindowChanged) {
                pendingAckSegments.set(0)
            }
        }
    }

    private fun enqueueData(pkt: Packet) {
        val payload = pkt.tcpPayload
        if (payload.isEmpty() || closed.get()) return

        if (!writeQueue.offer(WriteCommand.Data(payload))) {
            throw IllegalStateException("Write queue rejected payload for $key")
        }

        queuedBytes.addAndGet(payload.size)
        TrafficStats.addUpload(payload.size)

        synchronized(seqLock) {
            myAck = (pkt.tcpSeqNum + payload.size.toLong()) and 0xFFFFFFFFL
        }
    }

    private fun enqueueFinAfterDrain() {
        if (closed.get()) return
        if (finQueued.compareAndSet(false, true)) {
            if (!writeQueue.offer(WriteCommand.Finish)) {
                throw IllegalStateException("Write queue rejected FIN sentinel for $key")
            }
        }
    }

    private fun startProxyWriter() {
        writeThread = Thread {
            try {
                while (!closed.get()) {
                    val command = writeQueue.take()
                    when (command) {
                        is WriteCommand.Data -> {
                            val sawFinish = writeBatch(command)
                            maybeSendWindowUpdate(fromWriter = true)
                            if (sawFinish) {
                                close()
                                return@Thread
                            }
                        }

                        WriteCommand.Finish -> {
                            flushProxyOutput()
                            close()
                            return@Thread
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // close() interrupts the writer to unblock take()/write shutdown.
            } catch (e: Exception) {
                if (!closed.get()) {
                    Log.e(TAG, "[$key] Proxy write error: ${e.message}", e)
                    sendRst()
                    close()
                }
            }
        }.apply {
            name = "proxy-write-$key"
            isDaemon = true
            start()
        }
    }

    private fun writeBatch(first: WriteCommand.Data): Boolean {
        var sawFinish = false
        synchronized(proxyIoLock) {
            val out = proxyOut ?: return true
            out.write(first.bytes)
            queuedBytes.addAndGet(-first.bytes.size)

            while (true) {
                val next = writeQueue.poll() ?: break
                if (next is WriteCommand.Data) {
                    out.write(next.bytes)
                    queuedBytes.addAndGet(-next.bytes.size)
                } else {
                    sawFinish = true
                    break
                }
            }
            out.flush()
        }
        return sawFinish
    }

    private fun startProxyReader(socket: Socket) {
        readThread = Thread {
            try {
                val input = socket.getInputStream()
                val buffer = ByteArray(PROXY_READ_BUFFER_BYTES)

                while (!closed.get() && !socket.isClosed) {
                    val n = input.read(buffer)
                    if (n <= 0) break

                    val data = buffer.copyOfRange(0, n)
                    sendToDevice(Packet.FLAG_ACK or Packet.FLAG_PSH, data, window = currentAdvertisedWindowRaw())
                    synchronized(seqLock) {
                        mySeq = (mySeq + n) and 0xFFFFFFFFL
                    }
                }

                if (!closed.get()) {
                    sendToDevice(Packet.FLAG_FIN or Packet.FLAG_ACK, window = currentAdvertisedWindowRaw())
                    synchronized(seqLock) {
                        mySeq = (mySeq + 1) and 0xFFFFFFFFL
                    }
                }
            } catch (e: Exception) {
                if (!closed.get()) {
                    Log.e(TAG, "[$key] Proxy read error: ${e.message}", e)
                    sendRst()
                }
            } finally {
                close()
            }
        }.apply {
            name = "proxy-read-$key"
            isDaemon = true
            start()
        }
    }

    private fun currentAdvertisedWindowRaw(): Int {
        val raw = computeAdvertisedWindowRaw(queuedBytes.get())
        lastAdvertisedWindowRaw.set(raw)
        return raw
    }

    private fun computeAdvertisedWindowRaw(queued: Int): Int {
        val available = (MAX_QUEUE_BYTES - queued).coerceAtLeast(0)
        return (available shr WINDOW_SCALE_SHIFT).coerceIn(0, 0xFFFF)
    }

    private fun maybeSendWindowUpdate(fromWriter: Boolean): Boolean {
        if (closed.get()) return false

        val next = computeAdvertisedWindowRaw(queuedBytes.get())
        val previous = lastAdvertisedWindowRaw.get()
        if (!shouldSendWindowUpdate(previous, next, fromWriter)) return false
        if (!lastAdvertisedWindowRaw.compareAndSet(previous, next)) return false

        sendToDevice(Packet.FLAG_ACK, window = next)
        return true
    }

    private fun shouldSendWindowUpdate(previous: Int, next: Int, fromWriter: Boolean): Boolean {
        if (next == previous) return false
        if (next == 0 || previous == 0) return true

        val delta = abs(next - previous)
        if (delta >= WINDOW_UPDATE_THRESHOLD_RAW) return true

        return if (fromWriter) next > previous && queuedBytes.get() <= MAX_QUEUE_BYTES / 2
        else next < previous && queuedBytes.get() >= MAX_QUEUE_BYTES / 2
    }

    private fun sendAckWithWindow() {
        sendToDevice(Packet.FLAG_ACK, window = currentAdvertisedWindowRaw())
    }

    private fun sendToDevice(
        flags: Int,
        payload: ByteArray = ByteArray(0),
        tcpOptions: ByteArray = ByteArray(0),
        window: Int = currentAdvertisedWindowRaw()
    ) {
        val (seq, ack) = synchronized(seqLock) { mySeq to myAck }
        try {
            val packet = Packet.buildTcpPacket(
                srcAddr = dstAddr,
                srcPort = dstPort,
                dstAddr = srcAddr,
                dstPort = srcPort,
                seqNum = seq,
                ackNum = ack,
                flags = flags,
                payload = payload,
                window = window,
                tcpOptions = tcpOptions
            )
            synchronized(tunOutput) {
                tunOutput.write(packet)
                // tunOutput is a raw TUN fd — no buffering, flush is unnecessary.
            }
            if (payload.isNotEmpty()) {
                TrafficStats.addDownload(payload.size)
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                Log.e(TAG, "[$key] TUN write failed: ${e.message}", e)
            }
        }
    }

    private fun flushProxyOutput() {
        synchronized(proxyIoLock) {
            try {
                proxyOut?.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun sendRst() {
        try {
            sendToDevice(Packet.FLAG_RST or Packet.FLAG_ACK, window = currentAdvertisedWindowRaw())
        } catch (_: Exception) {
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return

        state = State.CLOSED
        pendingAckSegments.set(0)
        queuedBytes.set(0)
        writeQueue.clear()

        synchronized(proxyIoLock) {
            try {
                proxyOut?.flush()
            } catch (_: Exception) {
            }
            try {
                proxySocket?.close()
            } catch (_: Exception) {
            }
            proxyOut = null
            proxySocket = null
        }

        writeThread?.interrupt()
        readThread?.interrupt()
        onClose(key)
    }

    private fun connectWithAutoDetect(targetHost: String, targetPort: Int): Socket {
        val cached = config.detectedProtocol
        val protocols = if (cached != ProxyConfig.ProxyProtocol.UNKNOWN) {
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
                socket = Socket().apply {
                    keepAlive = true
                    sendBufferSize = SOCKET_BUFFER_BYTES
                    receiveBufferSize = SOCKET_BUFFER_BYTES
                }
                vpnService.protect(socket)

                if (proto == ProxyConfig.ProxyProtocol.HTTPS) {
                    socket.connect(InetSocketAddress(config.host, config.port), 10_000)
                    socket.soTimeout = 10_000

                    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf(TrustAllManager()), null)
                    sslSocket = sslContext.socketFactory.createSocket(
                        socket,
                        config.host,
                        config.port,
                        true
                    ) as javax.net.ssl.SSLSocket
                    vpnService.protect(sslSocket)
                    sslSocket.soTimeout = 10_000
                    sslSocket.sendBufferSize = SOCKET_BUFFER_BYTES
                    sslSocket.receiveBufferSize = SOCKET_BUFFER_BYTES

                    httpConnectHandshake(sslSocket, targetHost, targetPort)
                    Log.i(TAG, "[$key] Connected via HTTPS proxy")
                    ProxyConfig.saveDetectedProtocol(vpnService, proto)
                    return sslSocket
                }

                socket.connect(InetSocketAddress(config.host, config.port), 10_000)
                socket.soTimeout = 10_000

                when (proto) {
                    ProxyConfig.ProxyProtocol.SOCKS5 -> socks5Handshake(socket, targetHost, targetPort)
                    ProxyConfig.ProxyProtocol.HTTP -> httpConnectHandshake(socket, targetHost, targetPort)
                    else -> Unit
                }

                Log.i(TAG, "[$key] Connected via ${proto.name} proxy")
                ProxyConfig.saveDetectedProtocol(vpnService, proto)
                return socket
            } catch (e: Exception) {
                Log.w(TAG, "[$key] ${proto.name} failed: ${e.message}")
                lastError = e
                try {
                    sslSocket?.close()
                } catch (_: Exception) {
                }
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }

        throw lastError ?: ProxyException("All proxy protocols failed")
    }

    private class TrustAllManager : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<java.security.cert.X509Certificate>?,
            authType: String?
        ) {
        }

        override fun checkServerTrusted(
            chain: Array<java.security.cert.X509Certificate>?,
            authType: String?
        ) {
        }

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

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
        if (greeting[0] != 0x05.toByte()) {
            throw ProxyException("Bad SOCKS5 greeting")
        }

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
            if (authResp[1] != 0x00.toByte()) {
                throw ProxyException("SOCKS5 auth failed")
            }
        } else if (greeting[1] != 0x00.toByte()) {
            throw ProxyException("SOCKS5 no acceptable auth")
        }

        val domain = targetHost.toByteArray()
        if (domain.size > 255) throw ProxyException("Domain too long")

        val req = ByteArray(7 + domain.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = (domain.size and 0xFF).toByte()
        System.arraycopy(domain, 0, req, 5, domain.size)
        req[5 + domain.size] = (targetPort shr 8).toByte()
        req[6 + domain.size] = (targetPort and 0xFF).toByte()
        output.write(req)
        output.flush()

        val resp = readBytes(input, 4)
        if (resp[1] != 0x00.toByte()) {
            throw ProxyException("SOCKS5 connect failed: ${resp[1]}")
        }

        when (resp[3].toInt() and 0xFF) {
            0x01 -> readBytes(input, 6)
            0x03 -> {
                val len = input.read()
                if (len < 0) throw ProxyException("EOF during SOCKS5 bind addr")
                readBytes(input, len + 2)
            }
            0x04 -> readBytes(input, 18)
        }
    }

    private fun httpConnectHandshake(socket: Socket, targetHost: String, targetPort: Int) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        val request = buildString {
            append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
            append("Host: $targetHost:$targetPort\r\n")
            if (config.username.isNotBlank()) {
                val cred = "${config.username}:${config.password}"
                val enc = Base64.getEncoder().encodeToString(cred.toByteArray())
                append("Proxy-Authorization: Basic $enc\r\n")
            }
            append("\r\n")
        }

        output.write(request.toByteArray())
        output.flush()

        val statusLine = readLine(input)
        if (!(statusLine.startsWith("HTTP/") && statusLine.split(" ").getOrNull(1) == "200")) {
            throw ProxyException("HTTP CONNECT failed: $statusLine")
        }
        while (readLine(input).isNotBlank()) {
            // Skip proxy response headers.
        }
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buffer, offset, count - offset)
            if (n < 0) throw ProxyException("EOF during read")
            offset += n
        }
        return buffer
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
