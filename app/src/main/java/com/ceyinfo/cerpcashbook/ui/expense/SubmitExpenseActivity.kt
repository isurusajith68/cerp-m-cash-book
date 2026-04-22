package com.ceyinfo.cerpcashbook.ui.expense

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.CashSite
import com.ceyinfo.cerpcashbook.data.model.CreateVoucherRequest
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivitySubmitExpenseBinding
import com.ceyinfo.cerpcashbook.databinding.ItemExpenseBillBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Multi-bill expense voucher form.
 *
 * The custodian fills date / description / amount / photo, taps **Add Bill**
 * to push it into the in-memory list, repeats, then taps **Submit** to POST
 * one expense voucher per bill row in parallel against the chosen BU.
 *
 * The BU dropdown lists every BU the user can reach (any level — org /
 * division / project / site) — same source the BU-agnostic flow uses
 * everywhere else.
 */
class SubmitExpenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubmitExpenseBinding
    private lateinit var session: SessionManager

    private var sites: List<CashSite> = emptyList()
    private val bills = mutableListOf<Bill>()
    private lateinit var billAdapter: BillAdapter

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayFmt = SimpleDateFormat("dd MMM yyyy", Locale.US)
    private var pickedDateMs: Long = System.currentTimeMillis()

    /** Receipt photo for the bill currently being composed (pre-Add). */
    private var pendingReceiptUrl: String? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) uploadPendingReceipt(uri)
    }

    /** A single bill row queued for submission. */
    private data class Bill(
        val date: String,        // yyyy-MM-dd
        val displayDate: String, // dd MMM yyyy
        val description: String,
        val amount: Double,
        val receiptUrl: String?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivitySubmitExpenseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        binding.btnBack.setOnClickListener { finish() }

        // ACL gate: refuse the form entirely if the user can't submit vouchers.
        if (!session.canPerformAction("expense_voucher", "add")) {
            showError("You don't have permission to submit expense vouchers.")
            binding.btnSubmit.isEnabled = false
            binding.btnAddBill.isEnabled = false
            binding.btnAttachPhoto.isEnabled = false
            binding.btnPickDate.isEnabled = false
            return
        }

        binding.btnPickDate.setOnClickListener { openDatePicker() }
        binding.btnAttachPhoto.setOnClickListener { pickImage.launch("image/*") }
        binding.btnRemovePhoto.setOnClickListener { clearPendingReceipt() }
        binding.btnAddBill.setOnClickListener { addBillFromForm() }
        binding.btnSubmit.setOnClickListener { submitBatch() }

        billAdapter = BillAdapter(bills) { idx ->
            bills.removeAt(idx)
            billAdapter.notifyDataSetChanged()
            renderEmptyState()
        }
        binding.rvBills.layoutManager = LinearLayoutManager(this)
        binding.rvBills.adapter = billAdapter

        renderDateButton()
        renderEmptyState()
        loadSites()
    }

    // ─── BU dropdown ───────────────────────────────────────────────────────

    private fun loadSites() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SubmitExpenseActivity)
                val resp = api.getMySites()
                if (resp.isSuccessful && resp.body()?.success == true) {
                    sites = resp.body()?.data ?: emptyList()
                    val labels = sites.map {
                        val lvl = it.level?.uppercase(Locale.US) ?: ""
                        if (lvl.isBlank()) it.buName else "${it.buName} · $lvl"
                    }
                    binding.spinnerSite.adapter = ArrayAdapter(
                        this@SubmitExpenseActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        labels
                    )
                    if (sites.isEmpty()) showError("You have no accessible business units.")
                } else {
                    showError(resp.body()?.message ?: "Failed to load business units")
                }
            } catch (e: Exception) {
                showError(e.message ?: "Failed to load business units")
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun selectedSite(): CashSite? =
        sites.getOrNull(binding.spinnerSite.selectedItemPosition)

    // ─── Date picker ───────────────────────────────────────────────────────

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = pickedDateMs }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d, 0, 0, 0); cal.set(Calendar.MILLISECOND, 0)
            pickedDateMs = cal.timeInMillis
            renderDateButton()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            .apply { datePicker.maxDate = System.currentTimeMillis() }
            .show()
    }

    private fun renderDateButton() {
        binding.btnPickDate.text = displayFmt.format(pickedDateMs)
    }

    // ─── Photo upload (one per bill, uploaded to OSS up-front) ─────────────

    private fun uploadPendingReceipt(uri: Uri) {
        val bytes = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
        if (bytes == null) { showError("Could not read the selected image"); return }
        val mime = contentResolver.getType(uri) ?: "image/jpeg"

        binding.ivPhotoPreview.setImageURI(uri)
        binding.ivPhotoPreview.visibility = View.VISIBLE
        binding.btnRemovePhoto.visibility = View.VISIBLE
        binding.btnAttachPhoto.text = getString(R.string.uploading)
        binding.btnAttachPhoto.isEnabled = false

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SubmitExpenseActivity)
                val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", "bill.jpg", body)
                val resp = api.uploadAdvanceReceipt(part) // shared OSS upload endpoint
                if (resp.isSuccessful && resp.body()?.success == true) {
                    pendingReceiptUrl = resp.body()?.data?.url
                    binding.btnAttachPhoto.text = "Photo Attached"
                } else {
                    showError(resp.body()?.message ?: "Photo upload failed")
                    clearPendingReceipt()
                }
            } catch (e: Exception) {
                showError(e.message ?: "Photo upload failed"); clearPendingReceipt()
            } finally {
                binding.btnAttachPhoto.isEnabled = true
            }
        }
    }

    private fun clearPendingReceipt() {
        pendingReceiptUrl = null
        binding.ivPhotoPreview.setImageDrawable(null)
        binding.ivPhotoPreview.visibility = View.GONE
        binding.btnRemovePhoto.visibility = View.GONE
        binding.btnAttachPhoto.text = getString(R.string.attach_photo)
        binding.btnAttachPhoto.isEnabled = true
    }

    // ─── Add bill → list ───────────────────────────────────────────────────

    private fun addBillFromForm() {
        val description = binding.etDescription.text?.toString()?.trim().orEmpty()
        val amountStr = binding.etAmount.text?.toString()?.trim().orEmpty()
        val amount = amountStr.toDoubleOrNull()

        when {
            description.isEmpty() -> { showError("Description is required"); return }
            amount == null || amount <= 0 -> { binding.tilAmount.error = "Enter a valid amount"; return }
        }
        binding.tilAmount.error = null
        binding.tvError.visibility = View.GONE

        bills.add(
            Bill(
                date = isoFmt.format(pickedDateMs),
                displayDate = displayFmt.format(pickedDateMs),
                description = description,
                amount = amount!!,
                receiptUrl = pendingReceiptUrl,
            )
        )
        billAdapter.notifyItemInserted(bills.size - 1)
        renderEmptyState()

        // Clear the form for the next bill but keep the date + BU selection.
        binding.etDescription.setText("")
        binding.etAmount.setText("")
        clearPendingReceipt()
    }

    private fun renderEmptyState() {
        binding.tvEmptyBills.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
        binding.rvBills.visibility = if (bills.isEmpty()) View.GONE else View.VISIBLE
    }

    // ─── Submit batch ──────────────────────────────────────────────────────

    private fun submitBatch() {
        // Allow submitting an in-progress bill that hasn't been added yet —
        // common UX confusion. If the form has values, Add it first.
        val descRaw = binding.etDescription.text?.toString()?.trim().orEmpty()
        val amtRaw = binding.etAmount.text?.toString()?.trim().orEmpty()
        if (descRaw.isNotEmpty() || amtRaw.isNotEmpty()) {
            addBillFromForm()
            // addBillFromForm shows its own validation errors; bail if it didn't actually add.
            if (descRaw.isNotEmpty() && bills.lastOrNull()?.description != descRaw) return
        }

        if (bills.isEmpty()) { showError("Add at least one bill before submitting."); return }
        val site = selectedSite() ?: run { showError("Pick a business unit"); return }

        setLoading(true)
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SubmitExpenseActivity)
                // Submit all bills in parallel — backend creates one voucher per call.
                val results = bills.map { bill ->
                    async {
                        api.createExpenseVoucher(
                            CreateVoucherRequest(
                                buId = site.buId,
                                expenseDate = bill.date,
                                category = null,
                                description = bill.description,
                                amount = bill.amount,
                                receiptUrl = bill.receiptUrl,
                            )
                        )
                    }
                }.awaitAll()

                val failures = results.filter { !(it.isSuccessful && it.body()?.success == true) }
                if (failures.isEmpty()) {
                    Toast.makeText(
                        this@SubmitExpenseActivity,
                        "${bills.size} bill${if (bills.size == 1) "" else "s"} submitted",
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                } else {
                    showError("${failures.size} of ${results.size} bills failed to submit. Check logs.")
                }
            } catch (e: Exception) {
                showError(e.message ?: "Submission failed")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.btnAddBill.isEnabled = !loading
    }

    // ─── Bill list adapter ─────────────────────────────────────────────────

    private class BillAdapter(
        private val rows: MutableList<Bill>,
        private val onRemove: (Int) -> Unit,
    ) : RecyclerView.Adapter<BillAdapter.VH>() {

        class VH(val binding: ItemExpenseBillBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemExpenseBillBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val bill = rows[position]
            val b = holder.binding
            b.tvDate.text = bill.displayDate
            b.tvDescription.text = bill.description
            b.tvAmount.text = "LKR " + String.format(Locale.US, "%,.2f", bill.amount)
            b.ivPhotoIndicator.visibility = if (bill.receiptUrl.isNullOrBlank()) View.GONE else View.VISIBLE
            b.btnRemove.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) onRemove(pos)
            }
        }
    }
}
