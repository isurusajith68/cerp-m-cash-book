package com.ceyinfo.cerpcashbook.ui.ledger

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.LedgerEntry
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityLedgerBinding
import com.ceyinfo.cerpcashbook.databinding.ItemLedgerBinding
import com.ceyinfo.cerpcashbook.databinding.ItemLedgerBuHeaderBinding
import com.ceyinfo.cerpcashbook.databinding.ItemLedgerDateHeaderBinding
import com.ceyinfo.cerpcashbook.databinding.SheetLedgerDetailBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Improved ledger view.
 *
 * Layout:
 *  [Header bar with row count]
 *  [Summary card (Net across visible rows)]
 *  [Type-filter chips]   All / Cash In / Cash Out / Adjustments
 *  [Range-filter chips]  Today / 7 Days / 30 Days / All
 *  [Sectioned list]
 *      └── BU header (name + level chip + per-BU running balance + count)
 *          └── Date header (Today / Yesterday / dd MMM yyyy + per-day subtotal)
 *              └── Entry rows (icon + description + amount + running balance)
 *
 * Tapping a row opens a bottom-sheet with full details. Pagination triggers
 * when scrolled near the end. Pull-to-refresh resets to page 1.
 */
class LedgerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLedgerBinding
    private lateinit var session: SessionManager
    private lateinit var adapter: SectionedAdapter

    private var allEntries: MutableList<LedgerEntry> = mutableListOf()
    private var currentTypeFilter: String? = null  // null = all
    private var currentRange: Range = Range.ALL
    private var page = 1
    private val pageSize = 50
    private var hasMore = true
    private var loadJob: Job? = null

    private enum class Range(val label: String, val days: Int?) {
        TODAY("Today", 0),
        WEEK("7 Days", 7),
        MONTH("30 Days", 30),
        ALL("All", null),
    }

    private val typeFilters = listOf(
        null to "All",
        "advance_in" to "Cash In",
        "expense_out" to "Cash Out",
        "adjustment" to "Adjustment",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityLedgerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        com.ceyinfo.cerpcashbook.ui.common.BottomNav.bind(
            binding.bottomNav.root, this, com.ceyinfo.cerpcashbook.ui.common.BottomNav.Tab.LEDGER
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.swipeRefresh.setOnRefreshListener { reload() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        adapter = SectionedAdapter(emptyList()) { entry -> showDetailSheet(entry) }
        binding.rvLedger.layoutManager = LinearLayoutManager(this)
        binding.rvLedger.adapter = adapter

        // Pagination — load more when within 5 rows of the end.
        binding.rvLedger.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !hasMore || loadJob?.isActive == true) return
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 5) loadPage()
            }
        })

        setupTypeChips()
        setupRangeChips()
        reload()
    }

    // ─── Filter chips ──────────────────────────────────────────────────────

    private fun setupTypeChips() {
        binding.chipsType.removeAllViews()
        typeFilters.forEach { (code, label) ->
            binding.chipsType.addView(
                buildChip(label, code == currentTypeFilter) {
                    if (currentTypeFilter != code) {
                        currentTypeFilter = code
                        reload()
                    }
                }
            )
        }
    }

    private fun setupRangeChips() {
        binding.chipsRange.removeAllViews()
        Range.values().forEach { r ->
            binding.chipsRange.addView(
                buildChip(r.label, r == currentRange) {
                    if (currentRange != r) {
                        currentRange = r
                        reload()
                    }
                }
            )
        }
    }

    private fun buildChip(label: String, checked: Boolean, onChecked: () -> Unit): Chip {
        val dp = resources.displayMetrics.density
        return Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = checked
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
            setOnClickListener { if (isChecked) onChecked() }
        }
    }

    // ─── Data load ─────────────────────────────────────────────────────────

    private fun reload() {
        page = 1
        hasMore = true
        allEntries.clear()
        loadPage()
    }

    private fun loadPage() {
        if (!hasMore) return
        loadJob?.cancel()
        binding.progress.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.emptyState.visibility = View.GONE

        val (from, to) = rangeBounds(currentRange)

        loadJob = lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@LedgerActivity)
                val resp = api.getLedger(
                    buId = null,
                    recipientId = null,
                    txnType = currentTypeFilter,
                    fromDate = from,
                    toDate = to,
                    page = page,
                    limit = pageSize,
                )
                if (resp.isSuccessful && resp.body()?.success == true) {
                    val rows = resp.body()?.data ?: emptyList()
                    allEntries.addAll(rows)
                    hasMore = rows.size == pageSize
                    page++
                    rebuildSections()
                    updateSummary()
                    binding.tvCount.text = "${allEntries.size}${if (hasMore) "+" else ""}"
                    renderEmpty()
                } else {
                    showToast(resp.body()?.message ?: "Failed to load ledger")
                }
            } catch (e: Exception) {
                showToast(e.message ?: "Network error")
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun rangeBounds(r: Range): Pair<String?, String?> {
        if (r == Range.ALL) return null to null
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val toDate = isoDay.format(today.time)
        val days = r.days ?: 0
        cal.add(Calendar.DAY_OF_YEAR, -days)
        val fromDate = isoDay.format(cal.time)
        return fromDate to toDate
    }

    // ─── Sectioning + summary ──────────────────────────────────────────────

    private fun rebuildSections() {
        // Group by BU first, then by day, preserving server's reverse-chrono
        // order. Each BU chunk keeps its own running balance from the rows.
        val rows = mutableListOf<Row>()
        val groupedByBu = allEntries.groupBy { it.buId ?: "" }
        for ((buId, buEntries) in groupedByBu) {
            val first = buEntries.first()
            val totalIn = buEntries.sumOf { it.debit }
            val totalOut = buEntries.sumOf { it.credit }
            val balance = totalIn - totalOut
            rows.add(
                Row.BuHeader(
                    buId = buId,
                    name = first.buName ?: "(unknown BU)",
                    level = first.buLevel,
                    count = buEntries.size,
                    balance = balance,
                )
            )

            var lastDay = ""
            for (entry in buEntries) {
                val day = entry.txnDate.take(10)
                if (day != lastDay) {
                    val dayEntries = buEntries.filter { it.txnDate.take(10) == day }
                    val dayNet = dayEntries.sumOf { it.debit } - dayEntries.sumOf { it.credit }
                    rows.add(Row.DateHeader(day = day, label = friendlyDate(day), subtotal = dayNet))
                    lastDay = day
                }
                rows.add(Row.Entry(entry))
            }
        }
        adapter.submit(rows)
    }

    private fun updateSummary() {
        val received = allEntries.sumOf { it.debit }
        val spent = allEntries.sumOf { it.credit }
        binding.tvTotalReceived.text = "LKR " + fmtMoney(received)
        binding.tvTotalSpent.text = "LKR " + fmtMoney(spent)
        binding.tvBalance.text = "LKR " + fmtMoney(received - spent)
    }

    private fun renderEmpty() {
        if (allEntries.isNotEmpty()) {
            binding.emptyState.visibility = View.GONE
            return
        }
        binding.emptyState.visibility = View.VISIBLE
        val hasFilter = currentTypeFilter != null || currentRange != Range.ALL
        binding.tvEmptyTitle.text =
            if (hasFilter) "No transactions match these filters" else "No transactions yet"
        binding.tvEmptySub.text =
            if (hasFilter) "Try widening the date range or switching the type filter."
            else "Cash you send, receive, or have approved will appear here."
    }

    // ─── Detail bottom sheet ───────────────────────────────────────────────

    private fun showDetailSheet(entry: LedgerEntry) {
        val sheetBinding = SheetLedgerDetailBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this).apply { setContentView(sheetBinding.root) }

        val isDebit = entry.debit > 0
        val typeLabel = when (entry.txnType) {
            "advance_in" -> "Cash In"
            "expense_out" -> "Cash Out"
            "adjustment" -> "Adjustment"
            else -> entry.txnType
        }
        val typeColor = when {
            isDebit -> R.color.txn_debit
            entry.credit > 0 -> R.color.txn_credit
            else -> R.color.text_secondary
        }
        sheetBinding.tvTypePill.text = typeLabel
        (sheetBinding.tvTypePill.background as? GradientDrawable)?.apply {
            mutate(); setColor(ContextCompat.getColor(this@LedgerActivity, typeColor))
        }
        val signed = if (isDebit) "+ LKR " + fmtMoney(entry.debit)
                     else "- LKR " + fmtMoney(entry.credit)
        sheetBinding.tvAmount.text = signed
        sheetBinding.tvAmount.setTextColor(ContextCompat.getColor(this, typeColor))
        sheetBinding.tvDescription.text = entry.description ?: "(no description)"

        bindKv(sheetBinding.rowDate.root, "Date", friendlyDateTime(entry.createdAt ?: entry.txnDate))
        bindKv(sheetBinding.rowBu.root, "Business Unit",
            entry.buName + (entry.buLevel?.let { " · ${it.uppercase(Locale.US)}" } ?: ""))
        bindKv(sheetBinding.rowRecipient.root, "Recipient",
            listOfNotNull(entry.recipientFirstName, entry.recipientLastName)
                .joinToString(" ").ifBlank { "—" })
        bindKv(sheetBinding.rowRunningBalance.root, "Running balance",
            "LKR " + fmtMoney(entry.runningBalance))
        bindKv(sheetBinding.rowCreated.root, "Reference",
            entry.referenceType?.replace("_", " ")?.replaceFirstChar { it.uppercase() }
                ?.let { "$it · ${entry.referenceId?.take(8) ?: "—"}" }
                ?: "—")

        sheetBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun bindKv(root: View, label: String, value: String) {
        root.findViewById<TextView>(R.id.tv_label).text = label
        root.findViewById<TextView>(R.id.tv_value).text = value
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun fmtMoney(v: Double) = String.format(Locale.US, "%,.2f", v)

    private fun friendlyDate(yyyyMMdd: String): String {
        val d = runCatching { isoDay.parse(yyyyMMdd) }.getOrNull() ?: return yyyyMMdd
        val today = Calendar.getInstance()
        val that = Calendar.getInstance().apply { time = d }
        val sameDay = today.get(Calendar.YEAR) == that.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return "Today"
        today.add(Calendar.DAY_OF_YEAR, -1)
        val yest = today.get(Calendar.YEAR) == that.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
        if (yest) return "Yesterday"
        return displayDay.format(d)
    }

    private fun friendlyDateTime(raw: String): String {
        val d = runCatching {
            isoFull.parse(raw.substring(0, 19.coerceAtMost(raw.length)))
        }.getOrNull() ?: return raw
        return displayFull.format(d)
    }

    // ─── Adapter ───────────────────────────────────────────────────────────

    sealed class Row {
        data class BuHeader(val buId: String, val name: String, val level: String?, val count: Int, val balance: Double) : Row()
        data class DateHeader(val day: String, val label: String, val subtotal: Double) : Row()
        data class Entry(val entry: LedgerEntry) : Row()
    }

    private inner class SectionedAdapter(
        rows: List<Row>,
        val onTap: (LedgerEntry) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = rows.toMutableList()

        fun submit(newItems: List<Row>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Row.BuHeader -> 0
            is Row.DateHeader -> 1
            is Row.Entry -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> BuHeaderVH(ItemLedgerBuHeaderBinding.inflate(inf, parent, false))
                1 -> DateHeaderVH(ItemLedgerDateHeaderBinding.inflate(inf, parent, false))
                else -> EntryVH(ItemLedgerBinding.inflate(inf, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = items[position]) {
                is Row.BuHeader -> (holder as BuHeaderVH).bind(r)
                is Row.DateHeader -> (holder as DateHeaderVH).bind(r)
                is Row.Entry -> {
                    val vh = holder as EntryVH
                    vh.bind(r.entry)
                    // Click is wired here (not in VH.bind) so EntryVH doesn't
                    // need a reference to the adapter's onTap callback.
                    vh.b.root.setOnClickListener { onTap(r.entry) }
                }
            }
        }
    }

    private inner class BuHeaderVH(val b: ItemLedgerBuHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(h: Row.BuHeader) {
            b.tvBuName.text = h.name
            b.tvBuCount.text = "${h.count} ${if (h.count == 1) "entry" else "entries"}"
            b.tvBuBalance.text = "LKR " + fmtMoney(h.balance)
            b.tvBuBalance.setTextColor(
                ContextCompat.getColor(b.root.context,
                    if (h.balance >= 0) R.color.txn_debit else R.color.txn_credit)
            )
            if (!h.level.isNullOrBlank()) {
                b.tvLevel.visibility = View.VISIBLE
                b.tvLevel.text = h.level
                val lvlColor = when (h.level.lowercase(Locale.US)) {
                    "org", "organization" -> "#1E3A8A"
                    "division" -> "#7C3AED"
                    "project" -> "#059669"
                    "site"    -> "#D97706"
                    else      -> "#6B7280"
                }
                (b.tvLevel.background as? GradientDrawable)?.apply {
                    mutate(); setColor(android.graphics.Color.parseColor(lvlColor))
                }
            } else {
                b.tvLevel.visibility = View.GONE
            }
        }
    }

    private inner class DateHeaderVH(val b: ItemLedgerDateHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(h: Row.DateHeader) {
            b.tvDateLabel.text = h.label
            b.tvDateSubtotal.text = (if (h.subtotal >= 0) "+" else "") + fmtMoney(h.subtotal)
            b.tvDateSubtotal.setTextColor(
                ContextCompat.getColor(b.root.context,
                    if (h.subtotal >= 0) R.color.txn_debit else R.color.txn_credit)
            )
        }
    }

    private inner class EntryVH(val b: ItemLedgerBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(entry: LedgerEntry) {
            val isDebit = entry.debit > 0
            val color = ContextCompat.getColor(b.root.context,
                if (isDebit) R.color.txn_debit else R.color.txn_credit)

            (b.bgType.background as? GradientDrawable)?.apply { mutate(); setColor(color) }

            // Type icon
            val iconRes = when (entry.txnType) {
                "advance_in" -> R.drawable.ic_send_cash
                "expense_out" -> R.drawable.ic_receipt
                else -> R.drawable.ic_ledger
            }
            b.ivTypeIcon.setImageResource(iconRes)

            b.tvDescription.text = entry.description?.takeIf { it.isNotBlank() }
                ?: entry.txnType.replace("_", " ").replaceFirstChar { it.uppercase() }
            b.tvMeta.text = listOfNotNull(
                entry.recipientFirstName?.let { "${it} ${entry.recipientLastName ?: ""}".trim() },
                entry.referenceType?.replace("_", " ")
            ).joinToString(" · ").ifBlank { "—" }

            b.tvAmount.text = (if (isDebit) "+" else "−") + " " + fmtMoney(if (isDebit) entry.debit else entry.credit)
            b.tvAmount.setTextColor(color)
            b.tvRunningBalance.text = "Bal " + fmtMoney(entry.runningBalance)
            // click listener is wired in SectionedAdapter.onBindViewHolder
        }
    }

    companion object {
        private val isoDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val isoFull = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val displayDay = SimpleDateFormat("dd MMM yyyy", Locale.US)
        private val displayFull = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.US)
    }
}
