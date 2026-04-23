package com.ceyinfo.cerpcashbook.ui.advances

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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.CashAdvance
import com.ceyinfo.cerpcashbook.data.model.CashSite
import com.ceyinfo.cerpcashbook.data.model.ReachableCustodian
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityCashAdvancesBinding
import com.ceyinfo.cerpcashbook.databinding.ItemAdvanceBinding
import com.ceyinfo.cerpcashbook.databinding.ItemAdvanceGroupBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Cash Advances screen with Issues / Received tabs.
 *
 * Issues tab (clerk / both / owner): list of advances the user disbursed, optionally
 * grouped by custodian ("By Person") or by site ("By Site"). Tapping a group opens
 * [CashAdvanceDetailActivity] filtered to that person or site.
 *
 * Received tab (custodian / both / owner): flat list of advances the user received.
 * Top button is "Add Expense" instead of "Add New".
 */
class CashAdvancesTabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCashAdvancesBinding
    private lateinit var session: SessionManager

    private var currentTab: Tab = Tab.ISSUES
    private var currentGroupMode: GroupMode = GroupMode.BY_PERSON
    private var allAdvances: List<CashAdvance> = emptyList()
    private var reachableCustodians: List<ReachableCustodian> = emptyList()
    private var accessibleSites: List<CashSite> = emptyList()

    private enum class Tab { ISSUES, RECEIVED }
    private enum class GroupMode { BY_PERSON, BY_SITE }

    companion object {
        // Optional intent extra to land on a specific tab (e.g. from a hub
        // alert card or a notification deep-link). Values: TAB_ISSUES / TAB_RECEIVED.
        const val EXTRA_INITIAL_TAB = "initial_tab"
        const val TAB_ISSUES = "issues"
        const val TAB_RECEIVED = "received"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityCashAdvancesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        // Initial tab: explicit deep-link wins (alert card / notification),
        // otherwise pick a sensible default based on the user's ACL.
        currentTab = when (intent.getStringExtra(EXTRA_INITIAL_TAB)) {
            TAB_RECEIVED -> Tab.RECEIVED
            TAB_ISSUES -> Tab.ISSUES
            else -> {
                val canDisburse = session.canPerformAction("cash_advance", "add")
                val canSubmitBill = session.canPerformAction("expense_voucher", "add")
                if (!canDisburse && canSubmitBill) Tab.RECEIVED else Tab.ISSUES
            }
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.swipeRefresh.setOnRefreshListener { loadAdvances() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        binding.tabIssues.setOnClickListener { switchTab(Tab.ISSUES) }
        binding.tabReceived.setOnClickListener { switchTab(Tab.RECEIVED) }

        binding.fabSend.setOnClickListener {
            startActivity(Intent(this, com.ceyinfo.cerpcashbook.ui.sendcash.SendCashActivity::class.java))
        }
        binding.tvViewHistory.setOnClickListener {
            startActivity(Intent(this, com.ceyinfo.cerpcashbook.ui.ledger.LedgerActivity::class.java))
        }
        binding.btnSearch.setOnClickListener {
            Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }

        setupGroupChips()
        renderTabIndicator()
        renderForCurrentTab()
        loadAdvances()
    }

    private fun switchTab(tab: Tab) {
        if (currentTab == tab) return
        currentTab = tab
        renderTabIndicator()
        renderForCurrentTab()
        renderList()
    }

    private fun renderTabIndicator() {
        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_tab_underline)
        val inactiveBg = android.graphics.drawable.ColorDrawable(
            ContextCompat.getColor(this, R.color.white)
        )
        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        binding.tabIssues.background =
            if (currentTab == Tab.ISSUES) activeBg else inactiveBg
        binding.tabIssues.setTextColor(
            if (currentTab == Tab.ISSUES) activeColor else inactiveColor
        )
        binding.tabReceived.background =
            if (currentTab == Tab.RECEIVED) activeBg else inactiveBg
        binding.tabReceived.setTextColor(
            if (currentTab == Tab.RECEIVED) activeColor else inactiveColor
        )
    }

    override fun onResume() {
        super.onResume()
        // Reload after returning from SendCash / SubmitExpense so new items appear
        loadAdvances()
    }

    // ─── Sub-grouping chips (Issues tab only) ──────────────────────────────

    private fun setupGroupChips() {
        binding.groupModeChips.removeAllViews()
        listOf(
            GroupMode.BY_PERSON to getString(R.string.group_by_person),
            GroupMode.BY_SITE to getString(R.string.group_by_site),
        ).forEach { (mode, label) ->
            val chip = buildChip(label, mode == currentGroupMode) {
                if (currentGroupMode != mode) {
                    currentGroupMode = mode
                    renderList()
                }
            }
            binding.groupModeChips.addView(chip)
        }
    }

    private fun buildChip(label: String, checked: Boolean, onChecked: () -> Unit): Chip {
        val dp = resources.displayMetrics.density
        return Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = checked
            chipCornerRadius = 20f * dp
            chipStrokeWidth = 1f * dp
            chipStrokeColor = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.divider)
            )
            chipBackgroundColor = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(context, R.color.primary),
                    ContextCompat.getColor(context, R.color.white)
                )
            )
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(context, R.color.white),
                    ContextCompat.getColor(context, R.color.on_surface)
                )
            ))
            isCheckedIconVisible = false
            setOnClickListener { if (isChecked) onChecked() }
        }
    }

    // ─── Sub-control visibility ───────────────────────────────────────────

    private fun renderForCurrentTab() {
        val isReceived = currentTab == Tab.RECEIVED

        // Issues-tab UI
        binding.groupModeChips.visibility = if (isReceived) View.GONE else View.VISIBLE
        binding.fabSend.visibility =
            if (!isReceived && session.canPerformAction("cash_advance", "add"))
                View.VISIBLE else View.GONE

        // Received-tab UI
        val canAddExpense = isReceived && session.canPerformAction("expense_voucher", "add")
        binding.btnAddExpense.visibility = if (canAddExpense) View.VISIBLE else View.GONE
        binding.btnAddExpense.setOnClickListener {
            startActivity(Intent(this, com.ceyinfo.cerpcashbook.ui.expense.SubmitExpenseActivity::class.java))
        }
        binding.statsRow.visibility = if (isReceived) View.VISIBLE else View.GONE
        binding.tvRecentHeader.visibility = if (isReceived) View.VISIBLE else View.GONE
        binding.tvViewHistory.visibility = if (isReceived) View.VISIBLE else View.GONE
    }

    private fun renderStats(received: List<CashAdvance>) {
        val total = received.sumOf { it.amount }
        val pending = received.count { it.status == "pending" }
        binding.tvTotalReceived.text = "LKR " + String.format(Locale.US, "%,.0f", total)
        binding.tvPendingAck.text = String.format(Locale.US, "%02d Items", pending)
    }

    // ─── Data load ─────────────────────────────────────────────────────────

    /**
     * Loads three master lists in parallel:
     *   - advances (for counts / totals on each row)
     *   - reachable custodians (master list for the "List view" rows)
     *   - accessible sites (master list for the "BU view" rows)
     *
     * Custodians and sites drive the row set so a fresh person/BU still
     * appears even before any cash has been disbursed to them.
     */
    private fun loadAdvances() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@CashAdvancesTabActivity)

                // /cash-advances accepts a null site filter → backend aggregates
                // across every site the user can reach.
                val advResp = api.getCashAdvances(buId = null, page = 1, limit = 200)
                if (advResp.isSuccessful && advResp.body()?.success == true) {
                    allAdvances = advResp.body()?.data ?: emptyList()
                }

                val custResp = api.getReachableCustodians()
                if (custResp.isSuccessful && custResp.body()?.success == true) {
                    reachableCustodians = custResp.body()?.data ?: emptyList()
                }

                val sitesResp = api.getMySites()
                if (sitesResp.isSuccessful && sitesResp.body()?.success == true) {
                    accessibleSites = sitesResp.body()?.data ?: emptyList()
                }

                renderList()
            } catch (e: Exception) {
                Toast.makeText(this@CashAdvancesTabActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    // ─── Rendering ─────────────────────────────────────────────────────────

    private fun renderList() {
        val rows: List<Any> = when (currentTab) {
            Tab.ISSUES -> buildIssuesRows()
            Tab.RECEIVED -> buildReceivedRows().also { renderStats(it) }
        }
        binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.rvList.adapter = when (currentTab) {
            Tab.ISSUES -> GroupAdapter(rows.filterIsInstance<GroupRow>()) { row ->
                val intent = Intent(this, CashAdvanceDetailActivity::class.java)
                    .putExtra(CashAdvanceDetailActivity.EXTRA_MODE, if (currentGroupMode == GroupMode.BY_PERSON) "person" else "site")
                    .putExtra(CashAdvanceDetailActivity.EXTRA_ID, row.id)
                    .putExtra(CashAdvanceDetailActivity.EXTRA_NAME, row.name)
                startActivity(intent)
            }
            Tab.RECEIVED -> FlatAdvanceAdapter(
                rows = rows.filterIsInstance<CashAdvance>(),
                canAcknowledge = session.canPerformAction("cash_advance", "acknowledge"),
                onAcknowledge = { advance -> confirmAndAcknowledge(advance) },
            )
        }
    }

    /**
     * Show a confirm bottom sheet, call the ack endpoint, then show a success
     * sheet. On success the advance is mutated in place and the row re-renders.
     */
    private fun confirmAndAcknowledge(advance: CashAdvance) {
        val sender = listOfNotNull(advance.senderFirstName, advance.senderLastName)
            .joinToString(" ").ifBlank { "—" }
        com.ceyinfo.cerpcashbook.ui.common.ActionSheets.showConfirm(
            context = this,
            scope = lifecycleScope,
            title = "Acknowledge cash received",
            details = listOf(
                com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Detail(
                    "Amount", "LKR " + String.format(Locale.US, "%,.2f", advance.amount)),
                com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Detail("From", sender),
                com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Detail(
                    "Business Unit", advance.buName ?: "—"),
            ),
            confirmLabel = "Acknowledge",
            warning = "This confirms you have received the cash. A ledger debit will post to your balance.",
            tone = com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Tone.SUCCESS,
        ) {
            val api = ApiClient.getService(this@CashAdvancesTabActivity)
            val resp = api.acknowledgeAdvance(advance.id)
            if (!(resp.isSuccessful && resp.body()?.success == true)) {
                throw RuntimeException(resp.body()?.message ?: "Acknowledge failed")
            }
            // Update the row in place instead of full reload for snappier feedback.
            allAdvances = allAdvances.map {
                if (it.id == advance.id) it.copy(status = "acknowledged") else it
            }
            renderList()

            com.ceyinfo.cerpcashbook.ui.common.ActionSheets.showSuccess(
                context = this@CashAdvancesTabActivity,
                title = "Acknowledged",
                subtitle = "LKR " + String.format(Locale.US, "%,.2f", advance.amount) +
                    " added to your balance.",
                primaryLabel = "Done",
                secondaryLabel = "View ledger",
                onSecondary = {
                    startActivity(Intent(this@CashAdvancesTabActivity,
                        com.ceyinfo.cerpcashbook.ui.ledger.LedgerActivity::class.java))
                },
            )
        }
    }

    private fun buildIssuesRows(): List<GroupRow> {
        // Backend already BU-scopes the advances list to the current user,
        // so there's no need to re-filter by senderId / owner here.
        val myDisbursals = allAdvances

        return when (currentGroupMode) {
            GroupMode.BY_PERSON -> {
                // One row per distinct custodian (a custodian assigned to
                // multiple sites collapses into a single entry — drilling in
                // surfaces all their advances across sites).
                val advancesByPerson = myDisbursals.groupBy { it.recipientId }
                reachableCustodians
                    .distinctBy { it.userId }
                    .map { c ->
                        val group = advancesByPerson[c.userId].orEmpty()
                        GroupRow(
                            id = c.userId,
                            name = listOf(c.firstName, c.lastName)
                                .joinToString(" ").ifBlank { "Unknown" },
                            count = group.size,
                            total = group.sumOf { it.amount },
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            }
            GroupMode.BY_SITE -> {
                val advancesBySite = myDisbursals.groupBy { it.buId }
                accessibleSites.map { s ->
                    val group = advancesBySite[s.buId].orEmpty()
                    GroupRow(
                        id = s.buId,
                        name = s.buName,
                        count = group.size,
                        total = group.sumOf { it.amount },
                        level = s.level,
                    )
                }.sortedBy { it.name.lowercase() }
            }
        }
    }

    private fun buildReceivedRows(): List<CashAdvance> {
        val myUserId = session.userId ?: return emptyList()
        return allAdvances
            .filter { it.recipientId == myUserId }
            .sortedByDescending { it.createdAt ?: "" }
    }

    // ─── Row models & adapters ─────────────────────────────────────────────

    private data class GroupRow(
        val id: String,
        val name: String,
        val count: Int,
        val total: Double,
        val level: String? = null, // Org/Division/Project/Site — only set in BY_SITE mode
    )

    private class GroupAdapter(
        private val rows: List<GroupRow>,
        private val onClick: (GroupRow) -> Unit,
    ) : RecyclerView.Adapter<GroupAdapter.VH>() {

        class VH(val binding: ItemAdvanceGroupBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemAdvanceGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val b = holder.binding

            b.tvName.text = row.name
            b.tvInitial.text = row.name.firstOrNull()?.uppercase() ?: "?"
            b.tvCount.text = "${row.count} ${if (row.count == 1) "advance" else "advances"}"
            b.tvTotal.text = "LKR " + String.format(Locale.US, "%,.2f", row.total)

            // Level badge — only meaningful in BY_SITE mode (org/division/project/site).
            if (!row.level.isNullOrBlank()) {
                b.tvLevel.visibility = View.VISIBLE
                b.tvLevel.text = row.level
                val levelColor = when (row.level.lowercase(Locale.US)) {
                    "org", "organization" -> android.graphics.Color.parseColor("#1E3A8A")
                    "division" -> android.graphics.Color.parseColor("#7C3AED")
                    "project" -> android.graphics.Color.parseColor("#059669")
                    "site"    -> android.graphics.Color.parseColor("#D97706")
                    else      -> android.graphics.Color.parseColor("#6B7280")
                }
                (b.tvLevel.background as? GradientDrawable)?.apply { mutate(); setColor(levelColor) }
            } else {
                b.tvLevel.visibility = View.GONE
            }

            val palette = intArrayOf(
                android.graphics.Color.parseColor("#2563EB"),
                android.graphics.Color.parseColor("#059669"),
                android.graphics.Color.parseColor("#D97706"),
                android.graphics.Color.parseColor("#7C3AED"),
                android.graphics.Color.parseColor("#DB2777"),
            )
            (b.bgInitial.background as? GradientDrawable)?.apply { mutate(); setColor(palette[position % palette.size]) }

            b.root.setOnClickListener { onClick(row) }
        }
    }

    /**
     * Received-tab row adapter. Shows the inline Acknowledge button on each
     * `pending` row when the user has the `cash_advance.acknowledge` ACL.
     */
    private class FlatAdvanceAdapter(
        private val rows: List<CashAdvance>,
        private val canAcknowledge: Boolean,
        private val onAcknowledge: (CashAdvance) -> Unit,
    ) : RecyclerView.Adapter<FlatAdvanceAdapter.VH>() {

        class VH(val binding: ItemAdvanceBinding) : RecyclerView.ViewHolder(binding.root)

        private val dateFmt = SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.US)
        private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemAdvanceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = rows[position]
            val b = holder.binding

            b.tvAmount.text = "LKR " + String.format(Locale.US, "%,.2f", a.amount)
            b.tvStatus.text = a.status.uppercase()
            val statusColor = when (a.status) {
                "pending" -> android.graphics.Color.parseColor("#D97706")
                "acknowledged" -> android.graphics.Color.parseColor("#059669")
                "cancelled" -> android.graphics.Color.parseColor("#DC2626")
                else -> android.graphics.Color.parseColor("#6B7280")
            }
            (b.tvStatus.background as? GradientDrawable)?.apply { mutate(); setColor(statusColor) }

            val senderName = listOfNotNull(a.senderFirstName, a.senderLastName)
                .joinToString(" ").ifBlank { "—" }
            b.tvFrom.text = "From: $senderName"
            b.tvFromInitial.text = senderName.firstOrNull()?.uppercase() ?: "?"

            // Cycle a small palette so adjacent rows don't share the same avatar color.
            val palette = intArrayOf(
                android.graphics.Color.parseColor("#2563EB"),
                android.graphics.Color.parseColor("#059669"),
                android.graphics.Color.parseColor("#D97706"),
                android.graphics.Color.parseColor("#7C3AED"),
                android.graphics.Color.parseColor("#DB2777"),
            )
            (b.bgFromIcon.background as? GradientDrawable)?.apply {
                mutate(); setColor(palette[position % palette.size])
            }

            // Description doubles as the BU/project subtitle when present.
            val subtitle = listOfNotNull(
                a.description?.takeIf { it.isNotBlank() },
                a.buName?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                b.tvDescription.text = subtitle
                b.tvDescription.visibility = View.VISIBLE
            } else {
                b.tvDescription.visibility = View.GONE
            }

            b.tvDate.text = a.createdAt?.let {
                try {
                    dateFmt.format(isoFmt.parse(it.substring(0, 19))!!)
                } catch (_: Exception) { it.substring(0, 10.coerceAtMost(it.length)) }
            } ?: ""

            // Inline Acknowledge: only on pending rows AND when the user has
            // the cash_advance.acknowledge action allowed in their ACL.
            if (a.status == "pending" && canAcknowledge) {
                b.btnAcknowledge.visibility = View.VISIBLE
                b.btnAcknowledge.setOnClickListener { onAcknowledge(a) }
            } else {
                b.btnAcknowledge.visibility = View.GONE
                b.btnAcknowledge.setOnClickListener(null)
            }
        }
    }
}
