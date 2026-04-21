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
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityCashAdvancesBinding
import com.ceyinfo.cerpcashbook.databinding.ItemAdvanceBinding
import com.ceyinfo.cerpcashbook.databinding.ItemAdvanceGroupBinding
import com.ceyinfo.cerpcashbook.ui.expense.SubmitExpenseActivity
import com.ceyinfo.cerpcashbook.ui.sendcash.SendCashActivity
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

    private enum class Tab { ISSUES, RECEIVED }
    private enum class GroupMode { BY_PERSON, BY_SITE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityCashAdvancesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        // Default to Received for custodian-only users so the tab lands on their own data
        val role = session.cashRole ?: "none"
        currentTab = if (role == "custodian" && !session.isOwner) Tab.RECEIVED else Tab.ISSUES

        binding.btnBack.setOnClickListener { finish() }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.swipeRefresh.setOnRefreshListener { loadAdvances() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        setupTabs()
        setupGroupChips()
        setupAddButton()
        renderForCurrentTab()
        loadAdvances()
    }

    override fun onResume() {
        super.onResume()
        // Reload after returning from SendCash / SubmitExpense so new items appear
        loadAdvances()
    }

    // ─── Tab chips ─────────────────────────────────────────────────────────

    private fun setupTabs() {
        binding.tabGroup.removeAllViews()
        listOf(Tab.ISSUES to getString(R.string.tab_issues), Tab.RECEIVED to getString(R.string.tab_received)).forEach { (tab, label) ->
            val chip = buildChip(label, tab == currentTab) {
                if (currentTab != tab) {
                    currentTab = tab
                    renderForCurrentTab()
                    renderList()
                }
            }
            binding.tabGroup.addView(chip)
        }
    }

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

    // ─── Sub-control visibility / Add button wiring ────────────────────────

    private fun renderForCurrentTab() {
        when (currentTab) {
            Tab.ISSUES -> {
                binding.groupModeChips.visibility = View.VISIBLE
                binding.btnAdd.text = getString(R.string.add_new)
            }
            Tab.RECEIVED -> {
                binding.groupModeChips.visibility = View.GONE
                binding.btnAdd.text = getString(R.string.add_expense)
            }
        }
    }

    private fun setupAddButton() {
        binding.btnAdd.setOnClickListener {
            when (currentTab) {
                Tab.ISSUES -> startActivity(Intent(this, SendCashActivity::class.java))
                Tab.RECEIVED -> startActivity(Intent(this, SubmitExpenseActivity::class.java))
            }
        }
    }

    // ─── Data load ─────────────────────────────────────────────────────────

    private fun loadAdvances() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@CashAdvancesTabActivity)
                // Scope by current site BU — backend filters the user's visible rows further.
                val response = api.getCashAdvances(
                    siteBuId = session.businessUnitId,
                    page = 1,
                    limit = 100,
                )
                if (response.isSuccessful && response.body()?.success == true) {
                    allAdvances = response.body()?.data ?: emptyList()
                    renderList()
                } else {
                    Toast.makeText(
                        this@CashAdvancesTabActivity,
                        response.body()?.message ?: "Failed to load advances",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
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
            Tab.RECEIVED -> buildReceivedRows()
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
            Tab.RECEIVED -> FlatAdvanceAdapter(rows.filterIsInstance<CashAdvance>())
        }
    }

    private fun buildIssuesRows(): List<GroupRow> {
        // Only include advances the current user disbursed (defensive — backend should already scope)
        val myDisbursals = allAdvances.filter { it.disbursedBy == session.userId || session.isOwner }
        return when (currentGroupMode) {
            GroupMode.BY_PERSON -> myDisbursals
                .groupBy { it.custodianId }
                .map { (custodianId, group) ->
                    val sample = group.first()
                    GroupRow(
                        id = custodianId,
                        name = listOfNotNull(sample.custodianFirstName, sample.custodianLastName)
                            .joinToString(" ").ifBlank { "Unknown" },
                        count = group.size,
                        total = group.sumOf { it.amount },
                    )
                }
                .sortedByDescending { it.total }
            GroupMode.BY_SITE -> myDisbursals
                .groupBy { it.siteBuId }
                .map { (siteId, group) ->
                    GroupRow(
                        id = siteId,
                        name = group.first().siteName ?: "Unknown site",
                        count = group.size,
                        total = group.sumOf { it.amount },
                    )
                }
                .sortedByDescending { it.total }
        }
    }

    private fun buildReceivedRows(): List<CashAdvance> {
        val myUserId = session.userId ?: return emptyList()
        return allAdvances
            .filter { it.custodianId == myUserId }
            .sortedByDescending { it.createdAt ?: "" }
    }

    // ─── Row models & adapters ─────────────────────────────────────────────

    private data class GroupRow(val id: String, val name: String, val count: Int, val total: Double)

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

    private class FlatAdvanceAdapter(
        private val rows: List<CashAdvance>,
    ) : RecyclerView.Adapter<FlatAdvanceAdapter.VH>() {

        class VH(val binding: ItemAdvanceBinding) : RecyclerView.ViewHolder(binding.root)

        private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.US)
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

            b.tvFrom.text = "From: " + listOfNotNull(a.disbursedFirstName, a.disbursedLastName)
                .joinToString(" ").ifBlank { "—" }

            if (!a.description.isNullOrBlank()) {
                b.tvDescription.text = a.description
                b.tvDescription.visibility = View.VISIBLE
            } else {
                b.tvDescription.visibility = View.GONE
            }

            b.tvDate.text = a.createdAt?.let {
                try {
                    dateFmt.format(isoFmt.parse(it.substring(0, 19))!!)
                } catch (_: Exception) { it.substring(0, 10.coerceAtMost(it.length)) }
            } ?: ""

            b.btnAcknowledge.visibility = View.GONE
        }
    }
}
