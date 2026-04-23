package com.ceyinfo.cerpcashbook.ui.notifications

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.CashNotification
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityNotificationsBinding
import com.ceyinfo.cerpcashbook.databinding.ItemNotificationBinding
import com.ceyinfo.cerpcashbook.databinding.ItemNotificationDateHeaderBinding
import com.ceyinfo.cerpcashbook.fcm.NotificationEvents
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding
    private lateinit var adapter: SectionedAdapter

    private val notifications: MutableList<CashNotification> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        com.ceyinfo.cerpcashbook.ui.common.BottomNav.bind(
            binding.bottomNav.root, this, com.ceyinfo.cerpcashbook.ui.common.BottomNav.Tab.ALERTS
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.swipeRefresh.setOnRefreshListener { loadNotifications() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.btnMarkAll.setOnClickListener { markAllRead() }

        adapter = SectionedAdapter(
            rows = emptyList(),
            onTap = { n ->
                if (!n.isRead) markRead(n.id)
                deepLinkFor(n)
            }
        )
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter

        loadNotifications()

        // Live refresh when an FCM push lands while this screen is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotificationEvents.flow.collectLatest {
                    loadNotifications()
                }
            }
        }
    }

    // ─── Data load ─────────────────────────────────────────────────────────

    private fun loadNotifications() {
        binding.progress.visibility = if (notifications.isEmpty()) View.VISIBLE else View.GONE
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@NotificationsActivity)
                val response = api.getNotifications(limit = 50)
                if (response.isSuccessful && response.body()?.success == true) {
                    notifications.clear()
                    notifications.addAll(response.body()?.data ?: emptyList())
                    rebuild()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NotificationsActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun markRead(id: String) {
        // Optimistic: flip flag locally + rebuild before the API call returns.
        val idx = notifications.indexOfFirst { it.id == id }
        if (idx >= 0) {
            notifications[idx] = notifications[idx].copy(isRead = true)
            rebuild()
        }
        lifecycleScope.launch {
            runCatching { ApiClient.getService(this@NotificationsActivity).markNotificationRead(id) }
        }
    }

    private fun markAllRead() {
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@NotificationsActivity)
                val resp = api.markAllNotificationsRead()
                if (resp.isSuccessful && resp.body()?.success == true) {
                    for (i in notifications.indices) {
                        notifications[i] = notifications[i].copy(isRead = true)
                    }
                    rebuild()
                    Toast.makeText(this@NotificationsActivity,
                        "All notifications marked as read", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NotificationsActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Sectioning + headers ─────────────────────────────────────────────

    private fun rebuild() {
        val rows = mutableListOf<Row>()
        // Sort newest first, then bucket per friendly date header.
        val sorted = notifications.sortedByDescending { it.createdAt ?: "" }
        var lastBucket = ""
        for (n in sorted) {
            val bucket = friendlyBucket(parseDate(n.createdAt))
            if (bucket != lastBucket) {
                val countInBucket = sorted.count { friendlyBucket(parseDate(it.createdAt)) == bucket }
                rows.add(Row.DateHeader(bucket, countInBucket))
                lastBucket = bucket
            }
            rows.add(Row.Item(n))
        }
        adapter.submit(rows)

        // Header sub-title + Mark All visibility based on unread count.
        val unread = notifications.count { !it.isRead }
        if (unread > 0) {
            binding.tvSubtitle.visibility = View.VISIBLE
            binding.tvSubtitle.text = "$unread unread"
            binding.btnMarkAll.visibility = View.VISIBLE
        } else {
            binding.tvSubtitle.visibility = View.GONE
            binding.btnMarkAll.visibility = View.GONE
        }

        binding.emptyState.visibility = if (notifications.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun friendlyBucket(date: Date?): String {
        if (date == null) return "Earlier"
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val that = Calendar.getInstance().apply { time = date }
        val days = ((today.timeInMillis - that.timeInMillis) / (24L * 60 * 60 * 1000)).toInt()
        return when {
            days <= 0 -> "Today"
            days == 1 -> "Yesterday"
            days <= 7 -> "This Week"
            days <= 30 -> "This Month"
            else -> "Earlier"
        }
    }

    // ─── Deep link ────────────────────────────────────────────────────────

    private fun deepLinkFor(n: CashNotification) {
        val intent = when (n.type) {
            "advance_received", "advance_acknowledged" ->
                Intent(this, com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity::class.java)
                    .putExtra(
                        com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity.EXTRA_INITIAL_TAB,
                        if (n.type == "advance_received")
                            com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity.TAB_RECEIVED
                        else
                            com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity.TAB_ISSUES,
                    )
            "voucher_submitted" ->
                Intent(this, com.ceyinfo.cerpcashbook.ui.review.ReviewVouchersActivity::class.java)
            "voucher_approved", "voucher_rejected" ->
                Intent(this, com.ceyinfo.cerpcashbook.ui.ledger.LedgerActivity::class.java)
            else -> null
        }
        intent?.let { startActivity(it) }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun parseDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            isoFull.parse(raw.substring(0, 19.coerceAtMost(raw.length)))
        }.getOrNull()
    }

    /** Friendly relative-time label (`5m`, `2h`, `Yesterday`, `dd MMM`). */
    private fun relativeTime(raw: String?): String {
        val d = parseDate(raw) ?: return ""
        val diffMs = System.currentTimeMillis() - d.time
        val mins = diffMs / 60_000
        val hours = mins / 60
        val days = hours / 24
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            else -> displayDate.format(d)
        }
    }

    /** Per-type metadata: icon, accent hex, action verb. */
    private data class TypeMeta(val iconRes: Int, val accent: String, val action: String)

    private fun typeMeta(type: String): TypeMeta = when (type) {
        "advance_received"     -> TypeMeta(R.drawable.ic_check_circle, "#D97706", "Acknowledge")
        "advance_acknowledged" -> TypeMeta(R.drawable.ic_check_circle, "#059669", "View")
        "voucher_submitted"    -> TypeMeta(R.drawable.ic_review,       "#2563EB", "Review")
        "voucher_approved"     -> TypeMeta(R.drawable.ic_check_circle, "#059669", "View Ledger")
        "voucher_rejected"     -> TypeMeta(R.drawable.ic_x_circle,     "#DC2626", "View Ledger")
        else                   -> TypeMeta(R.drawable.ic_notifications, "#6B7280", "Open")
    }

    // ─── Adapter ──────────────────────────────────────────────────────────

    sealed class Row {
        data class DateHeader(val label: String, val count: Int) : Row()
        data class Item(val notification: CashNotification) : Row()
    }

    private inner class SectionedAdapter(
        rows: List<Row>,
        val onTap: (CashNotification) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = rows.toMutableList()

        fun submit(newItems: List<Row>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) =
            if (items[position] is Row.DateHeader) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0)
                HeaderVH(ItemNotificationDateHeaderBinding.inflate(inf, parent, false))
            else
                ItemVH(ItemNotificationBinding.inflate(inf, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = items[position]) {
                is Row.DateHeader -> (holder as HeaderVH).bind(r)
                is Row.Item -> {
                    val vh = holder as ItemVH
                    vh.bind(r.notification)
                    vh.b.root.setOnClickListener { onTap(r.notification) }
                }
            }
        }
    }

    private inner class HeaderVH(val b: ItemNotificationDateHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(h: Row.DateHeader) {
            b.tvLabel.text = h.label
            b.tvCount.text = "${h.count} ${if (h.count == 1) "item" else "items"}"
        }
    }

    private inner class ItemVH(val b: ItemNotificationBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(n: CashNotification) {
            val meta = typeMeta(n.type)
            val accentColor = android.graphics.Color.parseColor(meta.accent)

            b.ivIcon.setImageResource(meta.iconRes)
            (b.bgIcon.background as? GradientDrawable)?.apply { mutate(); setColor(accentColor) }

            b.tvTitle.text = n.title
            b.tvTitle.paint.isFakeBoldText = !n.isRead
            b.tvMessage.text = n.message ?: ""
            b.tvMessage.visibility = if (n.message.isNullOrBlank()) View.GONE else View.VISIBLE
            b.tvTime.text = relativeTime(n.createdAt)
            b.dotUnread.visibility = if (!n.isRead) View.VISIBLE else View.GONE
            b.tvAction.text = meta.action
            b.tvAction.setTextColor(accentColor)
        }
    }

    companion object {
        private val isoFull = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val displayDate = SimpleDateFormat("dd MMM", Locale.US)
    }
}
