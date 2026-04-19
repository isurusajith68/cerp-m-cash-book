package com.ceyinfo.cerpcashbook.ui.advances

import android.content.Intent
import android.graphics.drawable.GradientDrawable
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
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.CashAdvance
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityCashAdvanceDetailBinding
import com.ceyinfo.cerpcashbook.databinding.ItemAdvanceBinding
import com.ceyinfo.cerpcashbook.ui.sendcash.SendCashActivity
import com.ceyinfo.cerpcashbook.util.SessionManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Drill-down from CashAdvancesTabActivity's Issues groups: shows transactions
 * scoped to a single person (custodian) or site. "Add New" routes to SendCashActivity.
 */
class CashAdvanceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCashAdvanceDetailBinding
    private lateinit var session: SessionManager

    private lateinit var mode: String   // "person" | "site"
    private lateinit var filterId: String
    private lateinit var displayName: String

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_ID = "filter_id"
        const val EXTRA_NAME = "display_name"
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
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddNew.setOnClickListener {
            startActivity(Intent(this, SendCashActivity::class.java))
        }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.swipeRefresh.setOnRefreshListener { loadTransactions() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        loadTransactions()
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from SendCashActivity
        loadTransactions()
    }

    private fun loadTransactions() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@CashAdvanceDetailActivity)
                val response = when (mode) {
                    "person" -> api.getCashAdvances(
                        siteBuId = session.businessUnitId,
                        custodianId = filterId,
                        page = 1, limit = 100,
                    )
                    else -> api.getCashAdvances(
                        siteBuId = filterId,
                        page = 1, limit = 100,
                    )
                }
                if (response.isSuccessful && response.body()?.success == true) {
                    val data = response.body()?.data ?: emptyList()
                    // When scoping by person we still filter to rows the current user disbursed (owners see all)
                    val filtered = if (mode == "person") {
                        data.filter { it.disbursedBy == session.userId || session.isOwner }
                    } else {
                        data.filter { it.disbursedBy == session.userId || session.isOwner }
                    }
                    renderList(filtered)
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

    private fun renderList(items: List<CashAdvance>) {
        val total = items.sumOf { it.amount }
        binding.tvBalance.text = "LKR " + String.format(Locale.US, "%,.2f", total)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.rvList.adapter = AdvanceAdapter(items.sortedByDescending { it.createdAt ?: "" })
    }

    private class AdvanceAdapter(
        private val rows: List<CashAdvance>,
    ) : RecyclerView.Adapter<AdvanceAdapter.VH>() {

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
            (b.tvStatus.background as? GradientDrawable)?.mutate()?.setColor(statusColor)

            val counterparty = listOfNotNull(a.custodianFirstName, a.custodianLastName)
                .joinToString(" ").ifBlank { "—" }
            b.tvFrom.text = "To: $counterparty"

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
