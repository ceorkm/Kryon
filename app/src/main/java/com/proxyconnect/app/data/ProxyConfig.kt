package com.proxyconnect.app.data

import android.content.Context
import android.content.SharedPreferences

data class ProxyConfig(
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val autoConnect: Boolean = true,
    val detectedProtocol: ProxyProtocol = ProxyProtocol.UNKNOWN,
    val dns: String = "1.1.1.1"
) {
    enum class ProxyProtocol { UNKNOWN, SOCKS5, HTTP, HTTPS }

    fun isValid(): Boolean {
        return host.isNotBlank() && port in 1..65535
    }

    companion object {
        private const val PREFS_NAME = "proxy_config"
        private const val KEY_TYPE = "proxy_type"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_DNS = "dns"
        private const val KEY_WAS_CONNECTED = "was_connected"

        fun save(context: Context, config: ProxyConfig) {
            getPrefs(context).edit().apply {
                putString(KEY_TYPE, config.detectedProtocol.name)
                putString(KEY_HOST, config.host)
                putInt(KEY_PORT, config.port)
                putString(KEY_USERNAME, config.username)
                putString(KEY_PASSWORD, config.password)
                putBoolean(KEY_AUTO_CONNECT, config.autoConnect)
                apply()
            }
        }

        fun load(context: Context): ProxyConfig {
            val prefs = getPrefs(context)
            return ProxyConfig(
                host = prefs.getString(KEY_HOST, "") ?: "",
                port = prefs.getInt(KEY_PORT, 1080),
                username = prefs.getString(KEY_USERNAME, "") ?: "",
                password = prefs.getString(KEY_PASSWORD, "") ?: "",
                autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true),
                detectedProtocol = try {
                    ProxyProtocol.valueOf(prefs.getString(KEY_TYPE, "UNKNOWN") ?: "UNKNOWN")
                } catch (e: Exception) {
                    ProxyProtocol.UNKNOWN
                }
            )
        }

        fun saveDetectedProtocol(context: Context, protocol: ProxyProtocol) {
            getPrefs(context).edit().putString(KEY_TYPE, protocol.name).apply()
        }

        fun setWasConnected(context: Context, connected: Boolean) {
            getPrefs(context).edit().putBoolean(KEY_WAS_CONNECTED, connected).apply()
        }

        fun wasConnected(context: Context): Boolean {
            return getPrefs(context).getBoolean(KEY_WAS_CONNECTED, false)
        }

        private fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
