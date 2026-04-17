package com.ceyinfo.cerpcashbook.ui.acknowledge

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
import com.ceyinfo.cerpcashbook.databinding.ActivityAcknowledgeBinding
import com.ceyinfo.cerpcashbook.databinding.ItemAdvanceBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import kotlinx.coroutines.launch

class AcknowledgeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAcknowledgeBinding
    private lateinit var session: SessionManager
    private var advances: MutableList<CashAdvance> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityAcknowledgeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvAdvances.layoutManager = LinearLayoutManager(this)
        binding.swipeRefresh.setOnRefreshListener { loadAdvances() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        loadAdvances()
    }

    private fun loadAdvances() {
        val siteId = session.businessUnitId ?: return
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@AcknowledgeActivity)
                val response = api.getCashAdvances(siteBuId = siteId, custodianId = session.userId)
                if (response.isSuccessful) {
                    advances = (response.body()?.data ?: emptyList()).toMutableList()
                    binding.rvAdvances.adapter = AdvanceAdapter()
                    binding.tvEmpty.visibility = if (advances.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                Toast.makeText(this@AcknowledgeActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun acknowledge(id: String, position: Int) {
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@AcknowledgeActivity)
                val response = api.acknowledgeAdvance(id)
                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@AcknowledgeActivity, "Cash acknowledged", Toast.LENGTH_SHORT).show()
                    advances[position] = advances[position].copy(status = "acknowledged")
                    binding.rvAdvances.adapter?.notifyItemChanged(position)
                } else {
                    Toast.makeText(this@AcknowledgeActivity, response.body()?.message ?: "Failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AcknowledgeActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class AdvanceAdapter : RecyclerView.Adapter<AdvanceAdapter.VH>() {
        inner class VH(val b: ItemAdvanceBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = advances.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemAdvanceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val adv = advances[position]
            val b = holder.b

            b.tvAmount.text = "LKR ${String.format("%,.2f", adv.amount)}"
            b.tvFrom.text = "From: ${adv.disbursedFirstName ?: ""} ${adv.disbursedLastName ?: ""}"
            b.tvDate.text = adv.createdAt?.substring(0, 10) ?: ""

            if (!adv.description.isNullOrEmpty()) {
                b.tvDescription.text = adv.description
                b.tvDescription.visibility = View.VISIBLE
            } else {
                b.tvDescription.visibility = View.GONE
            }

            // Status badge
            b.tvStatus.text = adv.status.uppercase()
            val statusColor = when (adv.status) {
                "pending" -> R.color.status_pending
                "acknowledged" -> R.color.status_acknowledged
                else -> R.color.status_cancelled
            }
            (b.tvStatus.background as? GradientDrawable)?.setColor(
                ContextCompat.getColor(b.root.context, statusColor)
            )

            // Show acknowledge button only for pending
            if (adv.status == "pending") {
                b.btnAcknowledge.visibility = View.VISIBLE
                b.btnAcknowledge.setOnClickListener { acknowledge(adv.id, position) }
            } else {
                b.btnAcknowledge.visibility = View.GONE
            }
        }
    }
}
