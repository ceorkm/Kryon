package com.proxyconnect.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val autoConnect: Boolean = false,
    val detectedProtocol: String = "UNKNOWN",
    val countryCode: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean = host.isNotBlank() && port in 1..65535

    fun displaySubtitle(): String = "$host:$port"

    fun countryFlag(): String {
        if (countryCode.length != 2) return ""
        val first = Character.toChars(0x1F1E6 - 'A'.code + countryCode[0].uppercaseChar().code)
        val second = Character.toChars(0x1F1E6 - 'A'.code + countryCode[1].uppercaseChar().code)
        return String(first) + String(second)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
        put("autoConnect", autoConnect)
        put("detectedProtocol", detectedProtocol)
        put("countryCode", countryCode)
        put("createdAt", createdAt)
    }

    fun toProxyConfig(): ProxyConfig = ProxyConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        autoConnect = autoConnect,
        detectedProtocol = try {
            ProxyConfig.ProxyProtocol.valueOf(detectedProtocol)
        } catch (_: Exception) {
            ProxyConfig.ProxyProtocol.UNKNOWN
        }
    )

    companion object {
        fun fromJson(json: JSONObject): ProxyProfile = ProxyProfile(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            host = json.optString("host", ""),
            port = json.optInt("port", 1080),
            username = json.optString("username", ""),
            password = json.optString("password", ""),
            autoConnect = json.optBoolean("autoConnect", false),
            detectedProtocol = json.optString("detectedProtocol", "UNKNOWN"),
            countryCode = json.optString("countryCode", ""),
            createdAt = json.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

object ProfileRepository {
    private const val PREFS = "kryon_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile_id"

    private const val KEY_SEEDED = "seeded_v1"

    fun getAll(context: Context): List<ProxyProfile> {
        seedIfNeeded(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { ProxyProfile.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun seedIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val defaults = FreeProxies.getDefaults()
        val arr = JSONArray()
        defaults.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_PROFILES, arr.toString())
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    fun save(context: Context, profile: ProxyProfile) {
        val all = getAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile else all.add(profile)
        saveAll(context, all)
    }

    fun delete(context: Context, profileId: String) {
        val all = getAll(context).filter { it.id != profileId }
        saveAll(context, all)
        if (getActiveId(context) == profileId) setActiveId(context, null)
    }

    fun get(context: Context, profileId: String): ProxyProfile? {
        return getAll(context).find { it.id == profileId }
    }

    fun getActiveId(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null)
    }

    fun setActiveId(context: Context, id: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, id).apply()
    }

    fun saveDetectedProtocol(context: Context, profileId: String, protocol: String) {
        val profile = get(context, profileId) ?: return
        save(context, profile.copy(detectedProtocol = protocol))
    }

    fun saveCountryCode(context: Context, profileId: String, countryCode: String) {
        val profile = get(context, profileId) ?: return
        save(context, profile.copy(countryCode = countryCode))
    }

    private fun saveAll(context: Context, profiles: List<ProxyProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, arr.toString()).apply()
    }
}
