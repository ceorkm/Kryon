package com.proxyconnect.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.proxyconnect.app.data.ProxyConfig
import com.proxyconnect.app.service.ProxyVpnService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("BootReceiver", "Boot completed, checking auto-connect")

            val settingsPrefs = context.getSharedPreferences("kryon_settings", Context.MODE_PRIVATE)
            val autoConnect = settingsPrefs.getBoolean("auto_connect_boot", true)
            val wasConnected = ProxyConfig.wasConnected(context)

            if (autoConnect && wasConnected) {
                Log.i("BootReceiver", "Auto-connecting proxy")
                ProxyVpnService.start(context)
            }
        }
    }
}
