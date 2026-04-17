package com.ceyinfo.cerpcashbook.ui.custodians

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
import com.ceyinfo.cerpcashbook.data.model.Custodian
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityCustodiansBinding
import com.ceyinfo.cerpcashbook.databinding.ItemCustodianBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import kotlinx.coroutines.launch

class CustodiansActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustodiansBinding
    private lateinit var session: SessionManager
    private var custodians: List<Custodian> = emptyList()

    private val avatarColors = intArrayOf(
        R.color.primary, R.color.info, R.color.success,
        R.color.warning, R.color.error, R.color.status_submitted
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityCustodiansBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvCustodians.layoutManager = LinearLayoutManager(this)
        binding.swipeRefresh.setOnRefreshListener { loadCustodians() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        loadCustodians()
    }

    private fun loadCustodians() {
        val siteId = session.businessUnitId ?: return
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@CustodiansActivity)
                val response = api.getSiteCustodians(siteId)
                if (response.isSuccessful && response.body()?.success == true) {
                    custodians = response.body()!!.data!!
                    binding.rvCustodians.adapter = CustodianAdapter()
                    binding.tvEmpty.visibility = if (custodians.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                Toast.makeText(this@CustodiansActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    inner class CustodianAdapter : RecyclerView.Adapter<CustodianAdapter.VH>() {
        inner class VH(val b: ItemCustodianBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = custodians.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemCustodianBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = custodians[position]
            val b = holder.b

            val name = "${c.firstName} ${c.lastName}"
            b.tvName.text = name
            b.tvEmail.text = c.email ?: ""
            b.tvInitial.text = c.firstName.firstOrNull()?.uppercase() ?: "?"
            b.tvBalance.text = "LKR ${String.format("%,.0f", c.balance)}"

            // Color-code balance
            val balColor = when {
                c.balance > 0 -> R.color.success
                c.balance < 0 -> R.color.error
                else -> R.color.text_secondary
            }
            b.tvBalance.setTextColor(ContextCompat.getColor(b.root.context, balColor))

            // Avatar color
            val colorRes = avatarColors[position % avatarColors.size]
            (b.bgAvatar.background as? GradientDrawable)?.setColor(
                ContextCompat.getColor(b.root.context, colorRes)
            )

            // Last transaction
            if (!c.lastTxnAt.isNullOrEmpty()) {
                b.tvLastTxn.text = "Last txn: ${c.lastTxnAt.substring(0, 10)}"
                b.tvLastTxn.visibility = View.VISIBLE
            } else {
                b.tvLastTxn.visibility = View.GONE
            }
        }
    }
}
