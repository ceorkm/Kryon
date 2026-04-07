package com.proxyconnect.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.proxyconnect.app.R
import com.proxyconnect.app.data.ProxyProfile
import com.proxyconnect.app.data.ProfileRepository
import com.proxyconnect.app.service.ProxyVpnService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val VPN_REQUEST_CODE = 100
        private var connectedSince: Long = 0
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var tvProxyCount: TextView
    private lateinit var adapter: ProxyAdapter

    private var pendingConnectId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        recycler = findViewById(R.id.recyclerProxies)
        emptyState = findViewById(R.id.emptyState)
        tvProxyCount = findViewById(R.id.tvProxyCount)

        // Adapter
        adapter = ProxyAdapter(
            onTap = { profile -> onProxyTapped(profile) },
            onEdit = { profile -> showAddEditSheet(profile) },
            onDelete = { profile -> confirmDelete(profile) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // FAB
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            showAddEditSheet(null)
        }

        // Hamburger
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(findViewById<NavigationView>(R.id.navView))
        }

        // Nav drawer
        findViewById<NavigationView>(R.id.navView).setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawers()
            when (item.itemId) {
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                R.id.nav_developer -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.developer_url))))
                }
                R.id.nav_github -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url))))
                }
            }
            true
        }

        // VPN status listener
        val weakActivity = java.lang.ref.WeakReference(this)
        ProxyVpnService.statusListener = { _, _ ->
            weakActivity.get()?.runOnUiThread { weakActivity.get()?.refreshList() }
        }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        adapter.startTimerUpdates()
    }

    override fun onPause() {
        super.onPause()
        adapter.stopTimerUpdates()
    }

    override fun onDestroy() {
        ProxyVpnService.statusListener = null
        super.onDestroy()
    }

    private fun refreshList() {
        val profiles = ProfileRepository.getAll(this)
        val activeId = ProfileRepository.getActiveId(this)
        val isRunning = ProxyVpnService.isRunning
        val statusMsg = ProxyVpnService.statusMessage

        val items = profiles.map { profile ->
            val isThisActive = profile.id == activeId
            ProxyAdapter.ProxyItem(
                profile = profile,
                isConnected = isThisActive && isRunning,
                isConnecting = isThisActive && !isRunning && statusMsg.contains("onnect"),
                connectedSince = if (isThisActive && isRunning) connectedSince else 0
            )
        }

        adapter.submitList(items)

        val count = profiles.size
        tvProxyCount.text = if (count > 0) "My Proxies ($count)" else "My Proxies"
        emptyState.visibility = if (count == 0) View.VISIBLE else View.GONE
        recycler.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun onProxyTapped(profile: ProxyProfile) {
        val activeId = ProfileRepository.getActiveId(this)

        if (ProxyVpnService.isRunning && profile.id == activeId) {
            // Disconnect
            ProxyVpnService.stop(this)
            ProfileRepository.setActiveId(this, null)
            connectedSince = 0
            refreshList()
            return
        }

        // Disconnect current if any, with delay to let service stop
        if (ProxyVpnService.isRunning) {
            ProxyVpnService.stop(this)
            // Brief delay to let service stop
            recycler.postDelayed({
                if (!profile.isValid()) return@postDelayed
                pendingConnectId = profile.id
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
                else connectProfile(profile.id)
            }, 500)
            return
        }

        if (!profile.isValid()) {
            Toast.makeText(this, "Invalid proxy config", Toast.LENGTH_SHORT).show()
            return
        }

        pendingConnectId = profile.id
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
        } else {
            connectProfile(profile.id)
        }
    }

    private fun connectProfile(profileId: String) {
        val profile = ProfileRepository.get(this, profileId) ?: return
        ProfileRepository.setActiveId(this, profileId)

        // Save as current config for the VPN service
        val config = profile.toProxyConfig()
        com.proxyconnect.app.data.ProxyConfig.save(this, config)
        com.proxyconnect.app.data.ProxyConfig.setWasConnected(this, true)

        connectedSince = System.currentTimeMillis()
        ProxyVpnService.start(this)
        refreshList()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            pendingConnectId?.let { connectProfile(it) }
        }
        pendingConnectId = null
    }

    private fun showAddEditSheet(existing: ProxyProfile?) {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.dialog_add_proxy, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvSheetTitle)
        val editName = view.findViewById<EditText>(R.id.editName)
        val editHost = view.findViewById<EditText>(R.id.editHost)
        val editPort = view.findViewById<EditText>(R.id.editPort)
        val editUsername = view.findViewById<EditText>(R.id.editUsername)
        val editPassword = view.findViewById<EditText>(R.id.editPassword)

        if (existing != null) {
            tvTitle.text = getString(R.string.edit_proxy)
            editName.setText(existing.name)
            editHost.setText(existing.host)
            editPort.setText(existing.port.toString())
            editUsername.setText(existing.username)
            editPassword.setText(existing.password)
        }

        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            val name = editName.text.toString().trim()
            val host = editHost.text.toString().trim()
            val port = editPort.text.toString().toIntOrNull() ?: 1080
            val user = editUsername.text.toString().trim()
            val pass = editPassword.text.toString().trim()

            if (host.isBlank()) {
                Toast.makeText(this, "Host is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (port !in 1..65535) {
                Toast.makeText(this, "Port must be 1-65535", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val profile = (existing ?: ProxyProfile()).copy(
                name = name.ifBlank { host },
                host = host,
                port = port,
                username = user,
                password = pass
            )
            ProfileRepository.save(this, profile)
            dialog.dismiss()
            refreshList()
        }

        dialog.show()
    }

    private fun confirmDelete(profile: ProxyProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete proxy")
            .setMessage("Remove \"${profile.name.ifBlank { profile.host }}\"?")
            .setPositiveButton("Delete") { _, _ ->
                if (ProfileRepository.getActiveId(this) == profile.id) {
                    ProxyVpnService.stop(this)
                }
                ProfileRepository.delete(this, profile.id)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
