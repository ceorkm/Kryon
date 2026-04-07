package com.proxyconnect.app.proxy

import android.util.Log
import java.util.LinkedHashMap

/**
 * FakeDNS implementation.
 *
 * Instead of forwarding DNS queries to a real server (which requires socket protection
 * that doesn't work reliably on all devices), we:
 *
 * 1. Intercept DNS queries from the TUN
 * 2. Parse the queried domain name
 * 3. Assign a fake IP from 198.18.0.0/15 range
 * 4. Return a forged DNS response with the fake IP
 * 5. Map fake IP -> domain name
 * 6. When TCP connects to the fake IP, we look up the domain
 * 7. Pass the domain name to the SOCKS5/HTTP proxy (proxy resolves it)
 *
 * This is the same approach used by Shadowrocket, v2ray, Clash, etc.
 * The 198.18.0.0/15 range (198.18.0.0 - 198.19.255.255) gives us ~131k IPs.
 */
object FakeDns {
    private const val TAG = "FakeDns"

    // 198.18.0.0/15 = 198.18.0.0 to 198.19.255.255 (~131k addresses)
    private const val BASE_IP_FIRST = 198
    private const val BASE_IP_SECOND_MIN = 18
    private const val BASE_IP_SECOND_MAX = 19

    private var counter = 1 // Start from 198.18.0.1
    private val ipToDomain = LinkedHashMap<String, String>(256, 0.75f, true) // access-order for LRU
    private val domainToIp = HashMap<String, String>()

    /**
     * Assign a fake IP for a domain. Returns existing if already assigned.
     */
    fun assignIp(domain: String): String = synchronized(this) {
        domainToIp[domain]?.let { return@synchronized it }

        val n = counter++
        // Encode into 198.18.x.x or 198.19.x.x
        val secondOctet = BASE_IP_SECOND_MIN + (n / 65536) // 18 or 19
        val thirdOctet = (n / 256) % 256
        val fourthOctet = n % 256

        if (secondOctet > BASE_IP_SECOND_MAX) {
            // Ran out of IPs, evict oldest 25% of entries (LRU)
            val evictCount = ipToDomain.size / 4
            val iterator = ipToDomain.iterator()
            var evicted = 0
            while (iterator.hasNext() && evicted < evictCount) {
                val entry = iterator.next()
                domainToIp.remove(entry.value)
                iterator.remove()
                evicted++
            }
            counter = 1
            return@synchronized assignIp(domain) // Retry
        }

        val ip = "$BASE_IP_FIRST.$secondOctet.$thirdOctet.$fourthOctet"
        ipToDomain[ip] = domain
        domainToIp[domain] = ip
        Log.d(TAG, "Assigned $domain -> $ip")
        return@synchronized ip
    }

    /**
     * Look up domain name for a fake IP. Returns null if not a fake IP.
     */
    fun lookup(ip: String): String? = synchronized(this) {
        return@synchronized ipToDomain[ip]
    }

    /**
     * Check if an IP is in our fake range.
     */
    fun isFakeIp(ip: String): Boolean {
        return ip.startsWith("198.18.") || ip.startsWith("198.19.")
    }

    /**
     * Process a DNS query and return a forged DNS response with a fake IP.
     * Returns null if the query can't be parsed.
     */
    fun buildFakeResponse(queryData: ByteArray): Pair<String, ByteArray>? {
        try {
            if (queryData.size < 12) return null

            // Parse the domain name from the question
            val domain = extractDomain(queryData, 12) ?: return null
            if (domain.isBlank()) return null

            // Skip question to find QTYPE
            var offset = 12
            offset = skipName(queryData, offset)
            if (offset < 0 || offset + 4 > queryData.size) return null
            val qtype = ((queryData[offset].toInt() and 0xFF) shl 8) or (queryData[offset + 1].toInt() and 0xFF)

            // Only handle A records (type 1). For ALL other query types (AAAA/28,
            // HTTPS/65, SVCB/64, TXT/16, etc.), return an empty NODATA response
            // immediately. This prevents apps from timing out and retrying via the
            // real network, which could leak the user's real IP address.
            if (qtype != 1) {
                return buildEmptyResponse(queryData, offset + 4, domain)
            }

            val fakeIp = assignIp(domain)
            val ipParts = fakeIp.split(".").map { it.toInt() }

            // Build DNS response
            // Copy the query as base for response
            val response = queryData.copyOf()

            // Set response flags: QR=1, AA=1, RCODE=0
            response[2] = (0x81).toByte() // QR=1, Opcode=0, AA=0, TC=0, RD=1
            response[3] = (0x80).toByte() // RA=1, RCODE=0

            // Answer count = 1
            response[6] = 0
            response[7] = 1

            // Authority count = 0
            response[8] = 0
            response[9] = 0

            // Additional count = 0
            response[10] = 0
            response[11] = 0

            // Skip past the question section to append our answer
            offset += 4 // Skip QTYPE + QCLASS

            // Build answer record
            val answer = ByteArray(16)
            answer[0] = 0xC0.toByte() // Pointer to name at offset 12
            answer[1] = 0x0C.toByte()
            answer[2] = 0x00 // Type A
            answer[3] = 0x01
            answer[4] = 0x00 // Class IN
            answer[5] = 0x01
            answer[6] = 0x00 // TTL = 60 seconds
            answer[7] = 0x00
            answer[8] = 0x00
            answer[9] = 0x3C
            answer[10] = 0x00 // RDLENGTH = 4
            answer[11] = 0x04
            answer[12] = ipParts[0].toByte()
            answer[13] = ipParts[1].toByte()
            answer[14] = ipParts[2].toByte()
            answer[15] = ipParts[3].toByte()

            // Combine: response header+question + answer
            val fullResponse = ByteArray(offset + answer.size)
            System.arraycopy(response, 0, fullResponse, 0, offset)
            System.arraycopy(answer, 0, fullResponse, offset, answer.size)

            Log.d(TAG, "FakeDNS: $domain -> $fakeIp")
            return Pair(domain, fullResponse)

        } catch (e: Exception) {
            Log.e(TAG, "FakeDNS error: ${e.message}")
            return null
        }
    }

    /**
     * Build an empty DNS response (NODATA) for query types we don't support (e.g., AAAA).
     * Returns a valid DNS response with 0 answers, so the app immediately knows
     * there's no record and falls back to A (IPv4) without waiting for a timeout.
     */
    private fun buildEmptyResponse(queryData: ByteArray, questionEnd: Int, domain: String): Pair<String, ByteArray>? {
        try {
            val response = queryData.copyOfRange(0, questionEnd)
            // Set response flags: QR=1, RD=1, RA=1, RCODE=0 (no error)
            response[2] = 0x81.toByte()
            response[3] = 0x80.toByte()
            // Answer count = 0
            response[6] = 0; response[7] = 0
            // Authority count = 0
            response[8] = 0; response[9] = 0
            // Additional count = 0
            response[10] = 0; response[11] = 0
            Log.d(TAG, "FakeDNS: AAAA $domain -> empty (IPv6 blocked)")
            return Pair(domain, response)
        } catch (e: Exception) {
            Log.e(TAG, "FakeDNS empty response error: ${e.message}")
            return null
        }
    }

    private fun extractDomain(data: ByteArray, startOffset: Int): String? {
        val parts = mutableListOf<String>()
        var offset = startOffset

        while (offset < data.size) {
            val len = data[offset].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) break // Compressed pointer, shouldn't happen in question
            if (offset + 1 + len > data.size) return null

            parts.add(String(data, offset + 1, len))
            offset += 1 + len
        }

        return if (parts.isNotEmpty()) parts.joinToString(".") else null
    }

    private fun skipName(data: ByteArray, startOffset: Int): Int {
        var offset = startOffset
        while (offset < data.size) {
            val len = data[offset].toInt() and 0xFF
            if (len == 0) return offset + 1
            if ((len and 0xC0) == 0xC0) return offset + 2
            offset += 1 + len
        }
        return -1
    }

    fun clear() = synchronized(this) {
        ipToDomain.clear()
        domainToIp.clear()
        counter = 1
    }
}
