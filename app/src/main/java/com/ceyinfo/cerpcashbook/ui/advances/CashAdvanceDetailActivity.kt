package com.ceyinfo.cerpcashbook.ui.advances

import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.CashAdvance
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityCashAdvanceDetailBinding
import com.ceyinfo.cerpcashbook.databinding.ItemAdvanceCompactBinding
import com.ceyinfo.cerpcashbook.databinding.ItemAdvanceMonthHeaderBinding
import com.ceyinfo.cerpcashbook.databinding.SheetAdvanceDetailBinding
import com.ceyinfo.cerpcashbook.ui.common.ActionSheets
import com.ceyinfo.cerpcashbook.ui.sendcash.SendCashActivity
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Drill-down for one custodian (`person` mode) or one BU (`site` mode).
 *
 * Layout sections:
 *   • Header bar with title, subtitle, and "Add New" pill (ACL-gated).
 *   • Status breakdown card: total disbursed + outstanding + 3 status pills
 *     (pending / acknowledged / cancelled) with counts.
 *   • Status filter chips (All / Pending / Acknowledged / Cancelled).
 *   • Sectioned list grouped by month (sticky-style headers + month subtotal).
 *   • Tap a row → bottom sheet with full advance detail (incl. receipt image).
 *   • Swipe a `pending` row → cancel with 5-sec snackbar undo.
 */
class CashAdvanceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCashAdvanceDetailBinding
    private lateinit var session: SessionManager
    private lateinit var adapter: SectionedAdapter

    private lateinit var mode: String
    private lateinit var filterId: String
    private lateinit var displayName: String

    private var allAdvances: List<CashAdvance> = emptyList()
    private var statusFilter: String? = null   // null = All

    /** Pending cancel ops keyed by advance id, kept while undo snackbar lives. */
    private val pendingCancels = mutableMapOf<String, Job>()

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_ID = "filter_id"
        const val EXTRA_NAME = "display_name"

        private val isoFull = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val displayDate = SimpleDateFormat("dd MMM yyyy", Locale.US)
        private val displayDateTime = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.US)
        private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.US)
        private val monthKeyFmt = SimpleDateFormat("yyyy-MM", Locale.US)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityCashAdvanceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        mode = intent.getStringExtra(EXTRA_MODE) ?: "person"
        filterId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        displayName = intent.getStringExtra(EXTRA_NAME) ?: "Details"

        binding.tvTitle.text = displayName
        binding.tvSubtitle.text = if (mode == "person") "Cash advances · per person" else "Cash advances · per BU"
        binding.tvSubtitle.visibility = View.VISIBLE
        binding.btnBack.setOnClickListener { finish() }

        // Add New gated by ACL — drops the button entirely if denied.
        if (session.canPerformAction("cash_advance", "add")) {
            binding.btnAddNew.visibility = View.VISIBLE
            binding.btnAddNew.setOnClickListener {
                val i = Intent(this, SendCashActivity::class.java)
                when (mode) {
                    "person" -> i.putExtra(SendCashActivity.EXTRA_CUSTODIAN_ID, filterId)
                    "site" -> i.putExtra(SendCashActivity.EXTRA_SITE_BU_ID, filterId)
                }
                startActivity(i)
            }
        } else {
            binding.btnAddNew.visibility = View.GONE
        }

        adapter = SectionedAdapter(emptyList()) { advance -> showAdvanceSheet(advance) }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter

        setupSwipeToCancel()
        setupStatusChips()
        binding.swipeRefresh.setOnRefreshListener { loadTransactions() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        loadTransactions()
    }

    override fun onResume() {
        super.onResume()
        loadTransactions()
    }

    // ─── Filter chips ──────────────────────────────────────────────────────

    private val statusFilters = listOf(
        null to "All",
        "pending" to "Pending",
        "acknowledged" to "Acknowledged",
        "cancelled" to "Cancelled",
    )

    private fun setupStatusChips() {
        binding.chipsStatus.removeAllViews()
        statusFilters.forEach { (code, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = code == statusFilter
                val dp = resources.displayMetrics.density
                chipCornerRadius = 18f * dp
                chipStrokeWidth = 1f * dp
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.divider)
                )
                chipBackgroundColor = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        ContextCompat.getColor(context, R.color.primary),
                        ContextCompat.getColor(context, R.color.white),
                    )
                )
                setTextColor(
                    android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(
                            ContextCompat.getColor(context, R.color.white),
                            ContextCompat.getColor(context, R.color.on_surface),
                        )
                    )
                )
                isCheckedIconVisible = false
                setOnClickListener { if (isChecked) {
                    if (statusFilter != code) {
                        statusFilter = code
                        rebuild()
                    }
                } }
            }
            binding.chipsStatus.addView(chip)
        }
    }

    // ─── Data load ─────────────────────────────────────────────────────────

    private fun loadTransactions() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@CashAdvanceDetailActivity)
                val response = when (mode) {
                    "person" -> api.getCashAdvances(
                        buId = null, recipientId = filterId, page = 1, limit = 200,
                    )
                    else -> api.getCashAdvances(buId = filterId, page = 1, limit = 200)
                }
                if (response.isSuccessful && response.body()?.success == true) {
                    allAdvances = response.body()?.data ?: emptyList()
                    rebuild()
                } else {
                    Toast.makeText(
                        this@CashAdvanceDetailActivity,
                        response.body()?.message ?: "Failed to load",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@CashAdvanceDetailActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun rebuild() {
        renderBreakdown()
        renderList()
    }

    // ─── Status breakdown card ─────────────────────────────────────────────

    private fun renderBreakdown() {
        val pending = allAdvances.count { it.status == "pending" }
        val acked = allAdvances.count { it.status == "acknowledged" }
        val cancelled = allAdvances.count { it.status == "cancelled" }

        // Disbursed = sum of pending + acknowledged amounts (cancelled doesn't move money).
        val disbursed = allAdvances
            .filter { it.status != "cancelled" }
            .sumOf { it.amount }
        // Outstanding = pending only (custodian hasn't taken receipt yet).
        val outstanding = allAdvances
            .filter { it.status == "pending" }
            .sumOf { it.amount }

        binding.tvTotalDisbursed.text = "LKR " + String.format(Locale.US, "%,.2f", disbursed)
        binding.tvOutstanding.text = "LKR " + String.format(Locale.US, "%,.2f", outstanding)

        bindStatusPill(binding.statPending.root, pending, "Pending", "#D97706")
        bindStatusPill(binding.statAcknowledged.root, acked, "Acknowledged", "#059669")
        bindStatusPill(binding.statCancelled.root, cancelled, "Cancelled", "#DC2626")
    }

    private fun bindStatusPill(root: View, count: Int, label: String, hex: String) {
        val color = android.graphics.Color.parseColor(hex)
        root.findViewById<TextView>(R.id.tv_count).text = count.toString()
        root.findViewById<TextView>(R.id.tv_label).text = label
        val dot = root.findViewById<View>(R.id.dot)
        (dot.background as? GradientDrawable)?.apply { mutate(); setColor(color) }
    }

    // ─── List + sectioning ────────────────────────────────────────────────

    private fun renderList() {
        val filtered = if (statusFilter == null) allAdvances
        else allAdvances.filter { it.status == statusFilter }

        // Sort newest first (createdAt is ISO 8601, lexically sortable).
        val sorted = filtered.sortedByDescending { it.createdAt ?: "" }

        val rows = mutableListOf<Row>()
        var lastMonthKey = ""
        for (advance in sorted) {
            val ts = parseDate(advance.createdAt)
            val key = ts?.let { monthKeyFmt.format(it) } ?: ""
            if (key != lastMonthKey) {
                val monthLabel = ts?.let { monthFmt.format(it) } ?: "Earlier"
                val monthRows = sorted.filter {
                    parseDate(it.createdAt)?.let { d -> monthKeyFmt.format(d) } == key
                }
                val subtotal = monthRows.sumOf { it.amount }
                rows.add(Row.MonthHeader(monthLabel, subtotal))
                lastMonthKey = key
            }
            rows.add(Row.Item(advance))
        }
        adapter.submit(rows)

        renderEmptyState(filtered.isEmpty())
    }

    private fun renderEmptyState(empty: Boolean) {
        if (!empty) {
            binding.emptyState.visibility = View.GONE
            return
        }
        binding.emptyState.visibility = View.VISIBLE
        binding.tvEmptyTitle.text =
            if (statusFilter != null) "No ${statusFilter!!} advances"
            else "No advances yet"
        binding.tvEmptySub.text = if (statusFilter != null)
            "Try switching to a different status filter."
        else if (session.canPerformAction("cash_advance", "add"))
            "Tap Add New to disburse the first advance."
        else
            "Cash advances will appear here once issued."
    }

    private fun parseDate(raw: String?): java.util.Date? {
        if (raw.isNullOrBlank()) return null
        return runCatching { isoFull.parse(raw.substring(0, 19.coerceAtMost(raw.length))) }.getOrNull()
    }

    // ─── Swipe-to-cancel with snackbar undo ───────────────────────────────

    private fun setupSwipeToCancel() {
        if (!session.canPerformAction("cash_advance", "cancel")) return

        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ) {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return 0
                val row = adapter.itemAt(pos) ?: return 0
                // Only swipe-cancel on Pending item rows. Headers + non-pending = no swipe.
                if (row !is Row.Item || row.advance.status != "pending") return 0
                return super.getMovementFlags(rv, vh)
            }

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val row = adapter.itemAt(pos) as? Row.Item ?: return
                askCancelWithUndo(row.advance)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
            ) {
                // Tint the swiped row red so the user sees the destructive intent.
                val v = vh.itemView
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#FEE2E2")
                }
                c.drawRect(v.left.toFloat(), v.top.toFloat(),
                    v.right.toFloat(), v.bottom.toFloat(), paint)
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.rvList)
    }

    /**
     * Optimistically remove the advance from the list and show a 5-sec
     * snackbar with Undo. The actual API call only fires when the snackbar
     * times out without an Undo tap. This keeps cancel reversible.
     */
    private fun askCancelWithUndo(advance: CashAdvance) {
        // Optimistic remove
        allAdvances = allAdvances.filterNot { it.id == advance.id }
        rebuild()

        val snack = Snackbar.make(
            binding.swipeRefresh,
            "Advance cancelled · LKR " + String.format(Locale.US, "%,.2f", advance.amount),
            5000,
        )
        snack.setAction("UNDO") {
            // Restore — kill the pending API call and re-add.
            pendingCancels.remove(advance.id)?.cancel()
            allAdvances = allAdvances + advance
            rebuild()
        }
        snack.show()

        val job = lifecycleScope.launch {
            delay(5000)
            // Snackbar timed out → fire the actual cancel.
            try {
                val api = ApiClient.getService(this@CashAdvanceDetailActivity)
                val resp = api.cancelAdvance(advance.id)
                if (!(resp.isSuccessful && resp.body()?.success == true)) {
                    // Reverse the optimistic update if the server rejects.
                    allAdvances = allAdvances + advance.copy(status = "pending")
                    rebuild()
                    Toast.makeText(this@CashAdvanceDetailActivity,
                        resp.body()?.message ?: "Cancel failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                allAdvances = allAdvances + advance.copy(status = "pending")
                rebuild()
                Toast.makeText(this@CashAdvanceDetailActivity,
                    e.message ?: "Cancel failed", Toast.LENGTH_SHORT).show()
            } finally {
                pendingCancels.remove(advance.id)
            }
        }
        pendingCancels[advance.id] = job
    }

    // ─── Detail bottom sheet ──────────────────────────────────────────────

    private fun showAdvanceSheet(advance: CashAdvance) {
        val sb = SheetAdvanceDetailBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this).apply { setContentView(sb.root) }

        val statusColor = when (advance.status) {
            "pending" -> "#D97706"
            "acknowledged" -> "#059669"
            "cancelled" -> "#DC2626"
            else -> "#6B7280"
        }
        sb.tvStatusPill.text = advance.status.uppercase()
        (sb.tvStatusPill.background as? GradientDrawable)?.apply {
            mutate(); setColor(android.graphics.Color.parseColor(statusColor))
        }
        sb.tvAmount.text = "LKR " + String.format(Locale.US, "%,.2f", advance.amount)
        sb.tvDescription.text = advance.description?.takeIf { it.isNotBlank() }
            ?: "(no description)"

        bindKv(sb.rowRecipient.root, "Recipient",
            listOfNotNull(advance.recipientFirstName, advance.recipientLastName)
                .joinToString(" ").ifBlank { "—" })
        bindKv(sb.rowSender.root, "Sender",
            listOfNotNull(advance.senderFirstName, advance.senderLastName)
                .joinToString(" ").ifBlank { "—" })
        bindKv(sb.rowBu.root, "Business Unit", advance.buName ?: "—")
        bindKv(sb.rowMethod.root, "Method",
            advance.methodOfTransfer?.uppercase(Locale.US)?.replace("_", " ") ?: "Cash")
        bindKv(sb.rowBank.root, "Bank Account",
            if (advance.bankAccountId != null) {
                listOfNotNull(advance.bankAccountName, advance.bankName, advance.bankAccountNumber)
                    .joinToString(" · ").ifBlank { "—" }
            } else "—")
        bindKv(sb.rowReference.root, "Reference No", advance.referenceNo ?: "—")
        bindKv(sb.rowDate.root, "Sent",
            advance.createdAt?.let { friendlyDateTime(it) } ?: "—")
        bindKv(sb.rowAcknowledgedAt.root, "Acknowledged",
            advance.acknowledgedAt?.let { friendlyDateTime(it) } ?: "—")

        if (!advance.receiptUrl.isNullOrBlank()) {
            sb.ivReceipt.visibility = View.VISIBLE
            // Lightweight image load — Glide isn't a project dep, so use a
            // background fetch via OkHttp into a Bitmap. Acceptable for a
            // single sheet preview; switch to Glide/Coil if added later.
            loadImageInto(sb.ivReceipt, advance.receiptUrl)
        }

        if (advance.status == "pending" && session.canPerformAction("cash_advance", "cancel")) {
            sb.btnCancelAdvance.visibility = View.VISIBLE
            sb.btnCancelAdvance.setOnClickListener {
                dialog.dismiss()
                askCancelWithUndo(advance)
            }
        }
        sb.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun bindKv(root: View, label: String, value: String) {
        root.findViewById<TextView>(R.id.tv_label).text = label
        root.findViewById<TextView>(R.id.tv_value).text = value
    }

    private fun loadImageInto(view: android.widget.ImageView, url: String) {
        lifecycleScope.launch {
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.net.URL(url).openStream().use { it.readBytes() }
                }
                val bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                view.setImageBitmap(bm)
            } catch (_: Exception) {
                view.visibility = View.GONE
            }
        }
    }

    private fun friendlyDateTime(raw: String): String {
        val d = parseDate(raw) ?: return raw
        return displayDateTime.format(d)
    }

    // ─── Adapter ──────────────────────────────────────────────────────────

    sealed class Row {
        data class MonthHeader(val label: String, val subtotal: Double) : Row()
        data class Item(val advance: CashAdvance) : Row()
    }

    private inner class SectionedAdapter(
        rows: List<Row>,
        val onTap: (CashAdvance) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = rows.toMutableList()

        fun submit(newItems: List<Row>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }

        fun itemAt(position: Int): Row? = items.getOrNull(position)

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Row.MonthHeader -> 0
            is Row.Item -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0)
                MonthVH(ItemAdvanceMonthHeaderBinding.inflate(inf, parent, false))
            else
                ItemVH(ItemAdvanceCompactBinding.inflate(inf, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = items[position]) {
                is Row.MonthHeader -> (holder as MonthVH).bind(r)
                is Row.Item -> {
                    val vh = holder as ItemVH
                    vh.bind(r.advance)
                    vh.b.root.setOnClickListener { onTap(r.advance) }
                }
            }
        }
    }

    private inner class MonthVH(val b: ItemAdvanceMonthHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(h: Row.MonthHeader) {
            b.tvMonth.text = h.label
            b.tvMonthSubtotal.text = "LKR " + String.format(Locale.US, "%,.2f", h.subtotal)
        }
    }

    private inner class ItemVH(val b: ItemAdvanceCompactBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(a: CashAdvance) {
            b.tvAmount.text = "LKR " + String.format(Locale.US, "%,.2f", a.amount)
            val party = if (mode == "person")
                "From: " + listOfNotNull(a.senderFirstName, a.senderLastName)
                    .joinToString(" ").ifBlank { "—" }
            else
                "To: " + listOfNotNull(a.recipientFirstName, a.recipientLastName)
                    .joinToString(" ").ifBlank { "—" }
            b.tvMeta.text = listOfNotNull(party, a.description?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
            b.tvStatus.text = a.status.uppercase()

            val statusHex = when (a.status) {
                "pending" -> "#D97706"
                "acknowledged" -> "#059669"
                "cancelled" -> "#DC2626"
                else -> "#6B7280"
            }
            val statusColor = android.graphics.Color.parseColor(statusHex)
            (b.tvStatus.background as? GradientDrawable)?.apply { mutate(); setColor(statusColor) }
            (b.barStatus.background as? GradientDrawable)?.apply { mutate(); setColor(statusColor) }

            b.tvDate.text = parseDate(a.createdAt)?.let { displayDate.format(it) }
                ?: a.createdAt?.take(10) ?: ""
        }
    }
}
