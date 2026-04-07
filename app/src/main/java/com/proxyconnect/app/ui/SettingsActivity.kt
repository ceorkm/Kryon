package com.proxyconnect.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.proxyconnect.app.R
import com.proxyconnect.app.service.ProxyVpnService
import com.proxyconnect.app.data.ProfileRepository

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        // Auto-connect
        val prefs = getSharedPreferences("kryon_settings", Context.MODE_PRIVATE)
        val switchAuto = findViewById<Switch>(R.id.switchAutoConnect)
        switchAuto.isChecked = prefs.getBoolean("auto_connect_boot", true)
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_connect_boot", checked).apply()
        }

        // Battery optimization
        val tvBattery = findViewById<TextView>(R.id.tvBatteryStatus)
        updateBatteryStatus(tvBattery)
        findViewById<android.view.View>(R.id.btnBattery).setOnClickListener {
            requestBatteryOptimization()
        }

        // Kill switch toggle — blocks all traffic when proxy drops
        val switchKillSwitch = findViewById<Switch>(R.id.switchKillSwitch)
        switchKillSwitch.isChecked = prefs.getBoolean("kill_switch", true)
        switchKillSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("kill_switch", checked).apply()
        }

        // Reset proxies
        findViewById<android.view.View>(R.id.btnClearAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Proxies")
                .setMessage("Remove all custom proxies and restore defaults?")
                .setPositiveButton("Reset") { _, _ ->
                    // Stop VPN if running
                    ProxyVpnService.stop(this)
                    // Clear profiles
                    getSharedPreferences("kryon_profiles", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                    // Clear active proxy config and wasConnected flag
                    getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                    Toast.makeText(this, "Proxies reset", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryStatus(findViewById(R.id.tvBatteryStatus))
    }

    private fun updateBatteryStatus(tv: TextView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                tv.text = "Unrestricted (good)"
                tv.setTextColor(getColor(R.color.connected))
            } else {
                tv.text = "Tap to disable for always-on connection"
                tv.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
