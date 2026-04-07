package com.proxyconnect.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.proxyconnect.app.ProxyApp
import com.proxyconnect.app.R
import com.proxyconnect.app.data.ProfileRepository
import com.proxyconnect.app.data.ProxyConfig
import com.proxyconnect.app.proxy.PacketForwarder
import com.proxyconnect.app.proxy.TrafficStats
import com.proxyconnect.app.ui.MainActivity
import org.json.JSONObject
import android.net.IpPrefix
import java.io.FileInputStream
import java.io.FileOutputStream

class ProxyVpnService : VpnService() {

    companion object {
        private const val TAG = "ProxyVpnService"
        const val ACTION_CONNECT = "com.proxyconnect.CONNECT"
        const val ACTION_DISCONNECT = "com.proxyconnect.DISCONNECT"
        const val ACTION_KEEPALIVE = "com.proxyconnect.KEEPALIVE"
        private const val ALARM_INTERVAL_MS = 60_000L

        @Volatile var isRunning = false; private set
        @Volatile var statusMessage = "Disconnected"; private set
        var statusListener: ((Boolean, String) -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, ProxyVpnService::class.java).apply { action = ACTION_CONNECT }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProxyVpnService::class.java).apply { action = ACTION_DISCONNECT }
            context.startService(intent)
        }
    }

    private var vpnFd: ParcelFileDescriptor? = null
    private var forwarder: PacketForwarder? = null
    private var forwarderThread: Thread? = null
    private var reconnectThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val connectLock = Any()
    @Volatile private var isConnecting = false
    @Volatile private var shouldRun = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_KEEPALIVE -> {
                if (shouldRun && !isRunning) {
                    Log.w(TAG, "Alarm: service should be running, reconnecting")
                    doConnect()
                }
                if (shouldRun) scheduleAlarm()
                return START_STICKY
            }
            else -> {
                startForegroundNotification()
                doConnect()
                return START_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (shouldRun) scheduleAlarm()
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked")
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        releaseWakeLock()
        unregisterNetCallback()
        cancelAlarm()
        cleanupConnection()
        super.onDestroy()
    }

    private fun doConnect() {
        synchronized(connectLock) {
            if (isConnecting) return
            isConnecting = true
        }

        shouldRun = true
        acquireWakeLock()
        registerNetCallback()
        scheduleAlarm()

        val config = ProxyConfig.load(this)
        if (!config.isValid()) {
            updateStatus(false, "Invalid config")
            isConnecting = false
            stopSelf()
            return
        }

        // Connect in background thread
        Thread {
            try {
                updateStatus(false, "Testing proxy...")
                // Test proxy reachability
                val testSock = java.net.Socket()
                protect(testSock)
                testSock.connect(java.net.InetSocketAddress(config.host, config.port), 10_000)
                testSock.close()

                // Check if user disconnected while we were testing
                if (!shouldRun) return@Thread

                // Establish new VPN BEFORE tearing down old one to minimize the
                // reconnect window where traffic could leak via the real network.
                // Android VpnService.Builder.establish() atomically replaces the
                // old TUN interface, so there's no gap in traffic capture.
                updateStatus(false, "Establishing VPN...")

                // Stop old forwarder (it will exit its read loop when old TUN closes)
                forwarder?.stop()
                forwarder = null
                forwarderThread?.interrupt()
                forwarderThread = null
                reconnectThread?.interrupt()
                reconnectThread = null

                val fd = establishVpn(config) ?: run {
                    // VPN setup failed - clean up fully
                    try { vpnFd?.close() } catch (_: Exception) {}
                    vpnFd = null
                    updateStatus(false, "VPN setup failed")
                    scheduleReconnect()
                    return@Thread
                }

                // Check again after VPN setup
                if (!shouldRun) {
                    try { fd.close() } catch (_: Exception) {}
                    return@Thread
                }

                // Now close the old fd (new TUN is already active)
                try { vpnFd?.close() } catch (_: Exception) {}
                vpnFd = fd
                val input = FileInputStream(fd.fileDescriptor)
                val output = FileOutputStream(fd.fileDescriptor)

                val fwd = PacketForwarder(input, output, config, this)
                forwarder = fwd

                ProxyConfig.setWasConnected(this, true)
                TrafficStats.reset()
                updateStatus(true, "Connected (auto-detect)")

                // Detect country in background after connection is live
                detectCountry(config)

                // Run forwarder - this blocks until stopped
                forwarderThread = Thread.currentThread()
                fwd.run()

                // If we get here, forwarder stopped
                if (shouldRun) {
                    updateStatus(false, "Tunnel stopped")
                    scheduleReconnect()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}", e)
                updateStatus(false, "Failed: ${e.message}")
                scheduleReconnect()
            } finally {
                isConnecting = false
            }
        }.apply {
            name = "vpn-connect"
            isDaemon = true
            start()
        }
    }

    private fun establishVpn(config: ProxyConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("ProxyConnect")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addDnsServer(config.dns)
            .setBlocking(true)

        // Exclude our own app to prevent routing loops
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        // Route ALL IPv4 traffic through the VPN tunnel
        builder.addRoute("0.0.0.0", 0)

        // CRITICAL: Block ALL IPv6 to prevent IPv6 leak.
        // Without this, IPv6 traffic bypasses the VPN entirely and exposes
        // the user's real IP. We add an IPv6 address + route so the TUN
        // captures IPv6 packets; the forwarder drops them (IPv4-only proxy),
        // which forces apps to fall back to IPv4 through the tunnel.
        // FAIL CLOSED: If we can't set up IPv6 blocking, abort VPN setup entirely.
        // A VPN without IPv6 capture is worse than no VPN (false sense of security).
        builder.addAddress("fd00::1", 128)
        builder.addRoute("::", 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                // Exclude proxy server
                val proxyAddr = java.net.InetAddress.getByName(config.host)
                builder.excludeRoute(IpPrefix(proxyAddr, 32))
                Log.i(TAG, "Excluded proxy route: ${config.host}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude proxy route: ${e.message}")
            }
            // DNS goes through TUN for FakeDNS interception.
            // FakeDNS assigns IPs from 198.18.0.0/15 and maps them to domain names.
            // The proxy resolves the real IP when we connect with the domain name.
        }

        builder.setConfigureIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )

        return builder.establish()
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return

        // Kill switch: if enabled, keep the VPN TUN alive as a blackhole so
        // all traffic is captured but goes nowhere — prevents real IP leaks.
        // If disabled, tear down fully so traffic flows via real network.
        val killSwitch = getSharedPreferences("kryon_settings", MODE_PRIVATE)
            .getBoolean("kill_switch", true)

        forwarder?.stop()
        forwarder = null
        forwarderThread?.interrupt()
        forwarderThread = null

        if (!killSwitch) {
            // No kill switch — close TUN so apps can use real network
            try { vpnFd?.close() } catch (_: Exception) {}
            vpnFd = null
        }
        // else: keep vpnFd open as blackhole

        updateStatus(false, "Reconnecting...")

        reconnectThread = Thread {
            var delay = 2_000L
            val maxDelay = 60_000L

            while (shouldRun) {
                try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
                if (!shouldRun) break

                Log.i(TAG, "Reconnect attempt (delay=${delay}ms)")
                try {
                    val config = ProxyConfig.load(this)
                    val testSock = java.net.Socket()
                    protect(testSock)
                    testSock.connect(java.net.InetSocketAddress(config.host, config.port), 10_000)
                    testSock.close()

                    // Proxy is reachable - reconnect on main service thread
                    val intent = Intent(this, ProxyVpnService::class.java).apply { action = ACTION_CONNECT }
                    startService(intent)
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect failed: ${e.message}")
                    updateStatus(false, "Retry in ${delay / 1000}s...")
                    delay = (delay * 2).coerceAtMost(maxDelay)
                }
            }
        }
        reconnectThread?.name = "vpn-reconnect"
        reconnectThread?.isDaemon = true
        reconnectThread?.start()
    }

    private fun disconnect() {
        shouldRun = false
        isConnecting = false
        ProxyConfig.setWasConnected(this, false)
        cleanupConnection()
        releaseWakeLock()
        unregisterNetCallback()
        cancelAlarm()
        updateStatus(false, "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupConnection() {
        forwarder?.stop()
        forwarder = null
        forwarderThread?.interrupt()
        forwarderThread = null
        reconnectThread?.interrupt()
        reconnectThread = null
        try { vpnFd?.close() } catch (_: Exception) {}
        vpnFd = null
    }

    // --- Wake Lock ---
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProxyConnect::VPN")
        }
        if (wakeLock?.isHeld != true) wakeLock?.acquire(30 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    // --- Network Callback ---
    private fun registerNetCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Only reconnect if we're supposed to be running but aren't,
                // and not already in the middle of connecting
                if (shouldRun && !isRunning && !isConnecting) {
                    Log.i(TAG, "Network available, reconnecting")
                    Thread {
                        Thread.sleep(2000) // Wait for network to stabilize
                        if (shouldRun && !isRunning && !isConnecting) {
                            val intent = Intent(this@ProxyVpnService, ProxyVpnService::class.java).apply { action = ACTION_CONNECT }
                            startService(intent)
                        }
                    }.start()
                }
            }
            override fun onLost(network: Network) {
                // Only show "network lost" if we're not already connected
                // (VPN network changes can trigger this spuriously)
                if (shouldRun && !isRunning) {
                    updateStatus(false, "Network lost, waiting...")
                }
            }
        }
        cm.registerNetworkCallback(req, networkCallback!!)
    }

    private fun unregisterNetCallback() {
        networkCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // --- Alarm ---
    private fun scheduleAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getService(this, 0,
            Intent(this, ProxyVpnService::class.java).apply { action = ACTION_KEEPALIVE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS, pi)
    }

    private fun cancelAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getService(this, 0,
            Intent(this, ProxyVpnService::class.java).apply { action = ACTION_KEEPALIVE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    // --- Country Detection ---
    private fun detectCountry(config: ProxyConfig) {
        Thread {
            try {
                // Our app is excluded from the VPN, so we can query a geo-IP API
                // directly with the proxy server's IP to find its country.
                // No need to route through the proxy itself.
                val proxyIp = java.net.InetAddress.getByName(config.host).hostAddress
                val url = java.net.URL("http://ip-api.com/json/$proxyIp?fields=countryCode")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "curl/8.0")

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(response)
                val country = json.optString("countryCode", "")

                if (country.length == 2) {
                    val activeId = ProfileRepository.getActiveId(this) ?: return@Thread
                    ProfileRepository.saveCountryCode(this, activeId, country)
                    Log.i(TAG, "Detected proxy country: $country for IP $proxyIp")
                    statusListener?.invoke(true, "Connected")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Country detection failed: ${e.message}")
            }
        }.apply {
            name = "country-detect"
            isDaemon = true
            start()
        }
    }

    // --- Status & Notification ---
    private fun updateStatus(running: Boolean, message: String) {
        isRunning = running
        statusMessage = message
        statusListener?.invoke(running, message)
        Log.i(TAG, "Status: $running - $message")
        if (running) updateNotification(message)
    }

    private fun startForegroundNotification() {
        startForeground(ProxyApp.NOTIFICATION_ID, buildNotification("Connecting..."))
    }

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(ProxyApp.NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String) =
        NotificationCompat.Builder(this, ProxyApp.CHANNEL_ID)
            .setContentTitle("Kryon")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setSilent(true)
            .build()
}
