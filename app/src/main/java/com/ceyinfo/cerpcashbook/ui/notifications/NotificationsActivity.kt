package com.ceyinfo.cerpcashbook.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ceyinfo.cerpcashbook.data.model.CashNotification
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityNotificationsBinding
import com.ceyinfo.cerpcashbook.databinding.ItemNotificationBinding
import kotlinx.coroutines.launch

class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding
    private var notifications: MutableList<CashNotification> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.swipeRefresh.setOnRefreshListener { loadNotifications() }
        binding.swipeRefresh.setColorSchemeResources(com.ceyinfo.cerpcashbook.R.color.primary)

        binding.btnMarkAll.setOnClickListener { markAllRead() }

        loadNotifications()
    }

    private fun loadNotifications() {
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@NotificationsActivity)
                val response = api.getNotifications(limit = 50)
                if (response.isSuccessful && response.body()?.success == true) {
                    notifications = (response.body()?.data ?: emptyList()).toMutableList()
                    binding.rvNotifications.adapter = NotifAdapter()
                    binding.tvEmpty.visibility = if (notifications.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                Toast.makeText(this@NotificationsActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun markRead(id: String, position: Int) {
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@NotificationsActivity)
                api.markNotificationRead(id)
                notifications[position] = notifications[position].copy(isRead = true)
                binding.rvNotifications.adapter?.notifyItemChanged(position)
            } catch (_: Exception) { }
        }
    }

    private fun markAllRead() {
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@NotificationsActivity)
                api.markAllNotificationsRead()
                for (i in notifications.indices) {
                    notifications[i] = notifications[i].copy(isRead = true)
                }
                binding.rvNotifications.adapter?.notifyDataSetChanged()
                Toast.makeText(this@NotificationsActivity, "All marked as read", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@NotificationsActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class NotifAdapter : RecyclerView.Adapter<NotifAdapter.VH>() {
        inner class VH(val b: ItemNotificationBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = notifications.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = notifications[position]
            val b = holder.b

            b.tvTitle.text = n.title
            b.tvMessage.text = n.message ?: ""
            b.tvTime.text = n.createdAt?.substring(0, 16)?.replace("T", " ") ?: ""
            b.dotUnread.visibility = if (!n.isRead) View.VISIBLE else View.GONE

            // Bold title for unread
            b.tvTitle.paint.isFakeBoldText = !n.isRead

            b.root.setOnClickListener {
                if (!n.isRead) markRead(n.id, position)
            }
        }
    }
}
