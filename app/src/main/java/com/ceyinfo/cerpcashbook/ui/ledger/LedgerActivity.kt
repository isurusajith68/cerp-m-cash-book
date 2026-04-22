package com.ceyinfo.cerpcashbook.ui.ledger

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
import com.ceyinfo.cerpcashbook.data.model.LedgerEntry
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityLedgerBinding
import com.ceyinfo.cerpcashbook.databinding.ItemLedgerBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import kotlinx.coroutines.launch

class LedgerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLedgerBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityLedgerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvLedger.layoutManager = LinearLayoutManager(this)
        binding.swipeRefresh.setOnRefreshListener { loadLedger() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        loadLedger()
    }

    private fun loadLedger() {
        // BU-agnostic: backend unions across reachable BUs when site_bu_id is
        // omitted. We aggregate the balance summary client-side from the
        // returned entries so it reflects all BUs at once instead of one.
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@LedgerActivity)

                // BU-agnostic: backend already scopes ledger rows to the
                // current user (assigned BUs ∪ rows where I'm the custodian).
                // No client-side role-based filtering needed.
                val response = api.getLedger(buId = null, recipientId = null, limit = 100)
                if (response.isSuccessful) {
                    val entries = response.body()?.data ?: emptyList()
                    binding.rvLedger.adapter = LedgerAdapter(entries)
                    binding.tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

                    // Aggregate summary across all returned rows so the cards
                    // reflect every reachable BU at once, not a single site.
                    val received = entries.sumOf { it.debit }
                    val spent = entries.sumOf { it.credit }
                    binding.tvTotalReceived.text = "LKR ${String.format("%,.2f", received)}"
                    binding.tvTotalSpent.text = "LKR ${String.format("%,.2f", spent)}"
                    binding.tvBalance.text = "LKR ${String.format("%,.2f", received - spent)}"
                }
            } catch (e: Exception) {
                Toast.makeText(this@LedgerActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    inner class LedgerAdapter(private val entries: List<LedgerEntry>) :
        RecyclerView.Adapter<LedgerAdapter.VH>() {

        inner class VH(val b: ItemLedgerBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemLedgerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            val b = holder.b

            b.tvDescription.text = entry.description ?: entry.txnType.replace("_", " ").replaceFirstChar { it.uppercase() }
            b.tvDate.text = entry.txnDate.substring(0, 10)
            b.tvBalance.text = "Bal: ${String.format("%,.0f", entry.runningBalance)}"

            val isDebit = entry.debit > 0
            if (isDebit) {
                b.tvAmount.text = "+${String.format("%,.2f", entry.debit)}"
                b.tvAmount.setTextColor(ContextCompat.getColor(b.root.context, R.color.txn_debit))
                (b.barType.background as? GradientDrawable)?.setColor(
                    ContextCompat.getColor(b.root.context, R.color.txn_debit)
                )
            } else {
                b.tvAmount.text = "-${String.format("%,.2f", entry.credit)}"
                b.tvAmount.setTextColor(ContextCompat.getColor(b.root.context, R.color.txn_credit))
                (b.barType.background as? GradientDrawable)?.setColor(
                    ContextCompat.getColor(b.root.context, R.color.txn_credit)
                )
            }
        }
    }
}
