package com.proxyconnect.app.proxy

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks upload/download bytes for speed display.
 */
object TrafficStats {
    private val totalUp = AtomicLong(0)
    private val totalDown = AtomicLong(0)
    private var lastUp = 0L
    private var lastDown = 0L
    private var lastTime = 0L

    @Volatile var uploadSpeed = 0L; private set   // bytes per second
    @Volatile var downloadSpeed = 0L; private set  // bytes per second
    @Volatile var totalUpload = 0L; private set
    @Volatile var totalDownload = 0L; private set

    fun addUpload(bytes: Int) { totalUp.addAndGet(bytes.toLong()) }
    fun addDownload(bytes: Int) { totalDown.addAndGet(bytes.toLong()) }

    /** Call once per second to compute speeds */
    @Synchronized
    fun tick() {
        val now = System.currentTimeMillis()
        val elapsed = if (lastTime > 0) (now - lastTime) else 1000L
        if (elapsed <= 0) return

        val curUp = totalUp.get()
        val curDown = totalDown.get()

        uploadSpeed = ((curUp - lastUp) * 1000) / elapsed
        downloadSpeed = ((curDown - lastDown) * 1000) / elapsed
        totalUpload = curUp
        totalDownload = curDown

        lastUp = curUp
        lastDown = curDown
        lastTime = now
    }

    @Synchronized
    fun reset() {
        totalUp.set(0)
        totalDown.set(0)
        lastUp = 0
        lastDown = 0
        lastTime = 0
        uploadSpeed = 0
        downloadSpeed = 0
        totalUpload = 0
        totalDownload = 0
    }

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024 -> String.format("%.0f KB/s", bytesPerSec / 1_024.0)
        else -> "$bytesPerSec B/s"
    }
}
