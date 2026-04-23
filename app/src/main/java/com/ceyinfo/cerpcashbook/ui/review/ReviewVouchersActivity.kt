package com.ceyinfo.cerpcashbook.ui.review

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
import com.ceyinfo.cerpcashbook.data.model.ExpenseVoucher
import com.ceyinfo.cerpcashbook.data.model.ReviewVoucherRequest
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityReviewVouchersBinding
import com.ceyinfo.cerpcashbook.databinding.ItemVoucherBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class ReviewVouchersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewVouchersBinding
    private lateinit var session: SessionManager
    private var vouchers: MutableList<ExpenseVoucher> = mutableListOf()
    private var currentStatus: String? = null  // null = "All"

    // ISO date → "22 Apr 2026"
    private val isoDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateFmt = SimpleDateFormat("dd MMM yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityReviewVouchersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvVouchers.layoutManager = LinearLayoutManager(this)
        binding.swipeRefresh.setOnRefreshListener { loadVouchers() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        // Filter button (top-right): for now opens the same status chips below.
        // Reserved for future advanced filters (date range, custodian, etc.).
        binding.btnFilter.setOnClickListener {
            Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }

        setupStatusChips()
        loadVouchers()
    }

    private fun setupStatusChips() {
        val statuses = listOf(null to "All", "submitted" to "Pending", "approved" to "Approved", "rejected" to "Rejected")

        for ((status, label) in statuses) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = status == currentStatus
                val dp = resources.displayMetrics.density
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
                setOnClickListener {
                    currentStatus = status
                    loadVouchers()
                }
            }
            binding.chipGroupStatus.addView(chip)
        }
    }

    private fun loadVouchers() {

        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@ReviewVouchersActivity)
                val response = api.getExpenseVouchers(buId = null, status = currentStatus)
                if (response.isSuccessful) {
                    vouchers = (response.body()?.data ?: emptyList()).toMutableList()
                    binding.rvVouchers.adapter = VoucherAdapter()
                    binding.tvEmpty.visibility = if (vouchers.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvVouchers.visibility = if (vouchers.isEmpty()) View.GONE else View.VISIBLE
                }
            } catch (e: Exception) {
                Toast.makeText(this@ReviewVouchersActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun reviewVoucher(id: String, action: String, position: Int) {
        if (action == "reject") {
            val input = android.widget.EditText(this).apply {
                hint = "Reason for rejection"
                setPadding(48, 32, 48, 16)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Reject Voucher")
                .setView(input)
                .setPositiveButton("Reject") { _, _ ->
                    doReview(id, action, input.text.toString().trim(), position)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            doReview(id, action, null, position)
        }
    }

    private fun doReview(id: String, action: String, reason: String?, position: Int) {
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@ReviewVouchersActivity)
                val response = api.reviewVoucher(id, ReviewVoucherRequest(action, reason))
                if (response.isSuccessful && response.body()?.success == true) {
                    val newStatus = if (action == "approve") "approved" else "rejected"
                    Toast.makeText(this@ReviewVouchersActivity, "Voucher $newStatus", Toast.LENGTH_SHORT).show()
                    vouchers[position] = vouchers[position].copy(status = newStatus)
                    binding.rvVouchers.adapter?.notifyItemChanged(position)
                } else {
                    Toast.makeText(this@ReviewVouchersActivity, response.body()?.message ?: "Failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ReviewVouchersActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class VoucherAdapter : RecyclerView.Adapter<VoucherAdapter.VH>() {
        inner class VH(val b: ItemVoucherBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = vouchers.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemVoucherBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = vouchers[position]
            val b = holder.b
            val ctx = b.root.context

            b.tvVoucherNo.text = v.voucherNo

            // Friendly date: "22 Apr 2026"
            b.tvDate.text = runCatching {
                displayDateFmt.format(isoDateFmt.parse(v.expenseDate.substring(0, 10))!!)
            }.getOrDefault(v.expenseDate.substring(0, 10))

            // Description row hidden when blank
            if (!v.description.isNullOrBlank()) {
                b.tvDescription.text = v.description
                b.rowDescription.visibility = View.VISIBLE
            } else {
                b.rowDescription.visibility = View.GONE
            }

            // Status pill: tinted background + matching icon + label
            val (statusLabel, chipBg, iconRes, fgColor) = when (v.status) {
                "approved" -> Quad("Approved", R.drawable.bg_status_chip_approved,
                    R.drawable.ic_check_circle, "#15803D")
                "rejected" -> Quad("Rejected", R.drawable.bg_status_chip_rejected,
                    R.drawable.ic_x_circle, "#DC2626")
                "submitted" -> Quad("Pending", R.drawable.bg_status_chip_pending,
                    R.drawable.ic_clock, "#64748B")
                else -> Quad(v.status.replaceFirstChar { it.uppercase() },
                    R.drawable.bg_status_chip_pending, R.drawable.ic_clock, "#64748B")
            }
            val fg = android.graphics.Color.parseColor(fgColor)
            b.statusChip.setBackgroundResource(chipBg)
            b.tvStatus.text = statusLabel
            b.tvStatus.setTextColor(fg)
            b.ivStatusIcon.setImageResource(iconRes)
            b.ivStatusIcon.setColorFilter(fg)

            // Amount — turn red on rejected (matches mockup)
            b.tvAmount.text = "LKR ${String.format(Locale.US, "%,.2f", v.amount)}"
            b.tvAmount.setTextColor(
                if (v.status == "rejected") fg
                else ContextCompat.getColor(ctx, R.color.on_surface)
            )

            // ACL-gated buttons: only show what the current user is actually
            // allowed to do per their merged permissions. A user with only
            // `expense_voucher.approve` sees Approve but not Reject, etc.
            val canApprove = session.canPerformAction("expense_voucher", "approve")
            val canReject = session.canPerformAction("expense_voucher", "reject")

            if (v.status == "submitted" && (canApprove || canReject)) {
                b.actionButtons.visibility = View.VISIBLE
                b.btnApprove.visibility = if (canApprove) View.VISIBLE else View.GONE
                b.btnReject.visibility = if (canReject) View.VISIBLE else View.GONE
                b.btnApprove.setOnClickListener { reviewVoucher(v.id, "approve", position) }
                b.btnReject.setOnClickListener { reviewVoucher(v.id, "reject", position) }
            } else {
                b.actionButtons.visibility = View.GONE
            }
        }
    }

    private data class Quad(
        val label: String,
        val chipBgRes: Int,
        val iconRes: Int,
        val fgColor: String,
    )
}
