package com.proxyconnect.app.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.proxyconnect.app.R
import com.proxyconnect.app.data.ProxyProfile
import com.proxyconnect.app.proxy.TrafficStats

class ProxyAdapter(
    private val onTap: (ProxyProfile) -> Unit,
    private val onEdit: (ProxyProfile) -> Unit,
    private val onDelete: (ProxyProfile) -> Unit
) : ListAdapter<ProxyAdapter.ProxyItem, ProxyAdapter.ViewHolder>(DIFF) {

    data class ProxyItem(
        val profile: ProxyProfile,
        val isConnected: Boolean = false,
        val isConnecting: Boolean = false,
        val connectedSince: Long = 0
    )

    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    fun startTimerUpdates() {
        timerRunnable = object : Runnable {
            override fun run() {
                TrafficStats.tick()
                notifyItemRangeChanged(0, itemCount, "tick")
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timerRunnable!!)
    }

    fun stopTimerUpdates() {
        timerRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_proxy, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("tick")) {
            holder.updateLive(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val tvHost: TextView = view.findViewById(R.id.tvHost)
        private val tvTimer: TextView = view.findViewById(R.id.tvTimer)
        private val tvFlag: TextView = view.findViewById(R.id.tvFlag)
        private val statusDot: View = view.findViewById(R.id.statusDot)
        private val ivSignal: ImageView = view.findViewById(R.id.ivSignal)
        private val speedBar: LinearLayout = view.findViewById(R.id.speedBar)
        private val tvUpload: TextView = view.findViewById(R.id.tvUpload)
        private val tvDownload: TextView = view.findViewById(R.id.tvDownload)
        private val cardRoot: View = view.findViewById(R.id.cardRoot)
        private val connectArea: View = view.findViewById(R.id.connectArea)
        private val btnEdit: View = view.findViewById(R.id.btnEdit)
        private val btnDelete: View = view.findViewById(R.id.btnDelete)

        fun bind(item: ProxyItem) {
            tvName.text = item.profile.name.ifBlank { "Unnamed Proxy" }
            tvHost.text = item.profile.displaySubtitle()

            // Show country flag if detected, otherwise show signal icon
            val flag = item.profile.countryFlag()
            if (flag.isNotEmpty()) {
                tvFlag.text = flag
                tvFlag.visibility = View.VISIBLE
                ivSignal.visibility = View.GONE
            } else {
                tvFlag.visibility = View.GONE
                ivSignal.visibility = View.VISIBLE
            }

            when {
                item.isConnected -> {
                    statusDot.setBackgroundResource(R.drawable.bg_status_connected)
                    itemView.setBackgroundResource(R.drawable.bg_card_connected)
                    ivSignal.alpha = 1f
                    speedBar.visibility = View.VISIBLE
                }
                item.isConnecting -> {
                    statusDot.setBackgroundColor(itemView.context.getColor(R.color.connecting))
                    itemView.setBackgroundResource(R.drawable.bg_card_proxy)
                    ivSignal.alpha = 0.7f
                    speedBar.visibility = View.GONE
                }
                else -> {
                    statusDot.setBackgroundResource(R.drawable.bg_status_disconnected)
                    itemView.setBackgroundResource(R.drawable.bg_card_proxy)
                    ivSignal.alpha = 0.4f
                    speedBar.visibility = View.GONE
                }
            }

            updateLive(item)

            // Tap anywhere on the card to connect/disconnect
            cardRoot.setOnClickListener { onTap(item.profile) }

            // Edit & Delete buttons
            btnEdit.setOnClickListener { onEdit(item.profile) }
            btnDelete.setOnClickListener { onDelete(item.profile) }
        }

        fun updateLive(item: ProxyItem) {
            if (item.isConnected && item.connectedSince > 0) {
                val elapsed = (System.currentTimeMillis() - item.connectedSince) / 1000
                val h = elapsed / 3600
                val m = (elapsed % 3600) / 60
                val s = elapsed % 60
                tvTimer.text = if (h > 0) String.format("%d:%02d:%02d", h, m, s)
                else String.format("%02d:%02d", m, s)
                tvTimer.visibility = View.VISIBLE
            } else if (item.isConnecting) {
                tvTimer.text = "..."
                tvTimer.visibility = View.VISIBLE
            } else {
                tvTimer.visibility = View.GONE
            }

            if (item.isConnected) {
                tvUpload.text = "↑ ${TrafficStats.formatSpeed(TrafficStats.uploadSpeed)}"
                tvDownload.text = "↓ ${TrafficStats.formatSpeed(TrafficStats.downloadSpeed)}"
                speedBar.visibility = View.VISIBLE
            } else {
                speedBar.visibility = View.GONE
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ProxyItem>() {
            override fun areItemsTheSame(a: ProxyItem, b: ProxyItem) = a.profile.id == b.profile.id
            override fun areContentsTheSame(a: ProxyItem, b: ProxyItem) = a == b
        }
    }
}
