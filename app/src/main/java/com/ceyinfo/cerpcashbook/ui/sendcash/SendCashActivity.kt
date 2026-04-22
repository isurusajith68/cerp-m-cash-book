package com.ceyinfo.cerpcashbook.ui.sendcash

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.BankAccount
import com.ceyinfo.cerpcashbook.data.model.CreateAdvanceRequest
import com.ceyinfo.cerpcashbook.data.model.CreateBankAccountRequest
import com.ceyinfo.cerpcashbook.data.model.ReachableCustodian
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivitySendCashBinding
import com.ceyinfo.cerpcashbook.databinding.DialogCreateBankBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * BU-agnostic cash disbursement form. Custodians come from
 * `/site-cash/custodians/reachable` (one row per (custodian, site) pair the
 * clerk can reach), so the form doesn't need a global BU selection. The site
 * context is derived from the chosen custodian row.
 *
 * When launched from [CashAdvanceDetailActivity] in "person" mode the caller
 * passes [EXTRA_CUSTODIAN_ID] (and optionally [EXTRA_SITE_BU_ID]) and the
 * dropdown is filtered/locked to the matching rows. When launched in "site"
 * mode the caller passes [EXTRA_SITE_BU_ID] only and the dropdown is filtered
 * to that site's custodians.
 */
class SendCashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendCashBinding
    private lateinit var session: SessionManager

    private var allCustodians: List<ReachableCustodian> = emptyList()
    private var visibleCustodians: List<ReachableCustodian> = emptyList()
    private var bankAccounts: List<BankAccount> = emptyList()
    private var receiptUrl: String? = null

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val displayDateFmt = SimpleDateFormat("dd MMM yyyy", Locale.US)
    private var selectedDateMs: Long = System.currentTimeMillis()

    // Pre-filtering extras supplied when launched from a drill-down screen.
    private var lockedCustodianId: String? = null
    private var lockedSiteId: String? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) uploadReceipt(uri)
    }

    private val methodCodes = listOf("cash", "online", "cdm", "cheque", "counter")
    private val methodLabels = listOf("Cash", "Online Transfer", "CDM", "Cheque", "Counter")

    companion object {
        const val EXTRA_CUSTODIAN_ID = "custodian_id"
        const val EXTRA_SITE_BU_ID = "site_bu_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivitySendCashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        lockedCustodianId = intent.getStringExtra(EXTRA_CUSTODIAN_ID)
        lockedSiteId = intent.getStringExtra(EXTRA_SITE_BU_ID)

        binding.btnBack.setOnClickListener { finish() }
        // ACL gate: refuse the form entirely if the user can't add cash advances.
        if (!session.canPerformAction("cash_advance", "add")) {
            showError("You don't have permission to send cash.")
            binding.btnSend.isEnabled = false
            binding.btnAddBank.isEnabled = false
            binding.btnAttachPhoto.isEnabled = false
            binding.btnPickDate.isEnabled = false
            return
        }
        binding.btnSend.setOnClickListener { sendCash() }
        binding.btnAddBank.setOnClickListener { showCreateBankDialog() }
        binding.btnAttachPhoto.setOnClickListener { pickImage.launch("image/*") }
        binding.btnRemovePhoto.setOnClickListener { clearReceipt() }
        binding.btnPickDate.setOnClickListener { openDatePicker() }

        renderDateButton()
        setupMethodSpinner()
        loadCustodians()
    }

    // ─── Date picker ───────────────────────────────────────────────────────

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                cal.set(year, month, day, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                selectedDateMs = cal.timeInMillis
                renderDateButton()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).apply {
            // No future-dated disbursements
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun renderDateButton() {
        binding.btnPickDate.text = displayDateFmt.format(selectedDateMs)
    }

    // ─── Dropdowns ─────────────────────────────────────────────────────────

    private fun setupMethodSpinner() {
        binding.spinnerMethod.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, methodLabels
        )
        binding.spinnerMethod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCash = methodCodes[position] == "cash"
                binding.rowBank.visibility = if (isCash) View.GONE else View.VISIBLE
                if (!isCash) loadBankAccountsForSelection()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadCustodians() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val response = api.getReachableCustodians()
                if (response.isSuccessful && response.body()?.success == true) {
                    allCustodians = response.body()?.data ?: emptyList()
                    refreshCustodianDropdown()
                } else {
                    showError(response.body()?.message ?: "Failed to load custodians")
                }
            } catch (e: Exception) {
                showError(e.message ?: "Failed to load custodians")
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun refreshCustodianDropdown() {
        // Apply locked filters when present (drill-down context).
        visibleCustodians = allCustodians.filter { row ->
            (lockedCustodianId == null || row.userId == lockedCustodianId) &&
                (lockedSiteId == null || row.buId == lockedSiteId)
        }

        if (visibleCustodians.isEmpty()) {
            showError("No custodians available to disburse to.")
            binding.spinnerCustodian.visibility = View.GONE
            binding.lblCustodian.visibility = View.GONE
            binding.tvLockedContext.visibility = View.GONE
            return
        }

        // If exactly one match (e.g. drill-down with one site), show as a
        // locked context label instead of a spinner.
        if (visibleCustodians.size == 1 &&
            (lockedCustodianId != null || lockedSiteId != null)
        ) {
            val only = visibleCustodians.first()
            binding.spinnerCustodian.visibility = View.GONE
            binding.lblCustodian.visibility = View.GONE
            binding.tvLockedContext.visibility = View.VISIBLE
            binding.tvLockedContext.text = "${only.firstName} ${only.lastName} · ${only.buName}"
        } else {
            binding.tvLockedContext.visibility = View.GONE
            binding.spinnerCustodian.visibility = View.VISIBLE
            binding.lblCustodian.visibility = View.VISIBLE
            val labels = visibleCustodians.map {
                "${it.firstName} ${it.lastName} · ${it.buName} (Bal: ${"%,.0f".format(it.balance)})"
            }
            binding.spinnerCustodian.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, labels
            )
        }

        // If a non-cash method is already selected, banks need to refresh now
        // that we know which site we're operating in.
        if (binding.rowBank.visibility == View.VISIBLE) loadBankAccountsForSelection()
    }

    private fun selectedCustodian(): ReachableCustodian? {
        if (visibleCustodians.isEmpty()) return null
        if (visibleCustodians.size == 1) return visibleCustodians.first()
        val idx = binding.spinnerCustodian.selectedItemPosition
        return visibleCustodians.getOrNull(idx)
    }

    // ─── Bank accounts (per-site) ──────────────────────────────────────────

    private fun loadBankAccountsForSelection() {
        val siteId = selectedCustodian()?.buId ?: return
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val response = api.getBankAccounts(siteId)
                if (response.isSuccessful && response.body()?.success == true) {
                    bankAccounts = response.body()?.data ?: emptyList()
                    refreshBankSpinner(selectedId = null)
                }
            } catch (_: Exception) { /* silent — user can still add one inline */ }
        }
    }

    private fun refreshBankSpinner(selectedId: String?) {
        val labels = bankAccounts.map {
            buildString {
                append(it.accountName)
                if (!it.bankName.isNullOrBlank()) append(" · ").append(it.bankName)
                append(" (").append(it.accountNumber).append(')')
            }
        }
        binding.spinnerBank.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        if (selectedId != null) {
            val idx = bankAccounts.indexOfFirst { it.id == selectedId }
            if (idx >= 0) binding.spinnerBank.setSelection(idx)
        }
    }

    // ─── Create Bank Dialog ────────────────────────────────────────────────

    private fun showCreateBankDialog() {
        val siteId = selectedCustodian()?.buId
        if (siteId == null) {
            showError("Pick a custodian first so we know which site the bank account belongs to.")
            return
        }

        val dialogBinding = DialogCreateBankBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_bank_account)
            .setView(dialogBinding.root)
            .setPositiveButton("Save", null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.etAccountName.text?.toString()?.trim().orEmpty()
                val number = dialogBinding.etAccountNumber.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) { dialogBinding.etAccountName.error = "Required"; return@setOnClickListener }
                if (number.isEmpty()) { dialogBinding.etAccountNumber.error = "Required"; return@setOnClickListener }

                createBankAccount(
                    siteId = siteId,
                    accountName = name,
                    accountNumber = number,
                    bankName = dialogBinding.etBankName.text?.toEmptyOrNull(),
                    branch = dialogBinding.etBranch.text?.toEmptyOrNull(),
                    holder = dialogBinding.etHolder.text?.toEmptyOrNull(),
                    onDone = { dialog.dismiss() },
                )
            }
        }
        dialog.show()
    }

    private fun CharSequence.toEmptyOrNull(): String? = toString().trim().ifEmpty { null }

    private fun createBankAccount(
        siteId: String,
        accountName: String,
        accountNumber: String,
        bankName: String?,
        branch: String?,
        holder: String?,
        onDone: () -> Unit,
    ) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val response = api.createBankAccount(
                    CreateBankAccountRequest(
                        buId = siteId,
                        accountName = accountName,
                        accountNumber = accountNumber,
                        accountHolderName = holder,
                        bankName = bankName,
                        branch = branch,
                    )
                )
                if (response.isSuccessful && response.body()?.success == true) {
                    val created = response.body()!!.data!!
                    bankAccounts = bankAccounts + created
                    refreshBankSpinner(selectedId = created.id)
                    Toast.makeText(this@SendCashActivity, "Bank account added", Toast.LENGTH_SHORT).show()
                    onDone()
                } else {
                    showError(response.body()?.message ?: "Failed to add bank account")
                }
            } catch (e: Exception) {
                showError(e.message ?: "An error occurred")
            } finally {
                setLoading(false)
            }
        }
    }

    // ─── Receipt upload ────────────────────────────────────────────────────

    private fun uploadReceipt(uri: Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) { null }
        if (bytes == null) {
            showError("Could not read the selected image")
            return
        }
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"

        binding.ivPhotoPreview.setImageURI(uri)
        binding.ivPhotoPreview.visibility = View.VISIBLE
        binding.btnRemovePhoto.visibility = View.VISIBLE
        binding.btnAttachPhoto.text = getString(R.string.uploading)
        binding.btnAttachPhoto.isEnabled = false

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", "receipt.jpg", body)
                val response = api.uploadAdvanceReceipt(part)
                if (response.isSuccessful && response.body()?.success == true) {
                    receiptUrl = response.body()?.data?.url
                    binding.btnAttachPhoto.text = "Photo Attached"
                } else {
                    showError(response.body()?.message ?: "Photo upload failed")
                    clearReceipt()
                }
            } catch (e: Exception) {
                showError(e.message ?: "Photo upload failed")
                clearReceipt()
            } finally {
                binding.btnAttachPhoto.isEnabled = true
            }
        }
    }

    private fun clearReceipt() {
        receiptUrl = null
        binding.ivPhotoPreview.setImageDrawable(null)
        binding.ivPhotoPreview.visibility = View.GONE
        binding.btnRemovePhoto.visibility = View.GONE
        binding.btnAttachPhoto.text = getString(R.string.attach_photo)
        binding.btnAttachPhoto.isEnabled = true
    }

    // ─── Submit ────────────────────────────────────────────────────────────

    private fun sendCash() {
        val custodian = selectedCustodian() ?: run {
            showError("Select a custodian"); return
        }

        val amountStr = binding.etAmount.text?.toString()?.trim().orEmpty()
        if (amountStr.isEmpty()) { binding.tilAmount.error = "Amount is required"; return }
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) { binding.tilAmount.error = "Enter a valid amount"; return }
        binding.tilAmount.error = null

        val methodIdx = binding.spinnerMethod.selectedItemPosition.coerceAtLeast(0)
        val method = methodCodes[methodIdx]
        var bankAccountId: String? = null
        if (method != "cash") {
            if (bankAccounts.isEmpty()) {
                showError("Add a bank account before sending by $method.")
                return
            }
            val bankIdx = binding.spinnerBank.selectedItemPosition
            if (bankIdx < 0) { showError("Select a deposit account."); return }
            bankAccountId = bankAccounts[bankIdx].id
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val response = api.createCashAdvance(
                    CreateAdvanceRequest(
                        buId = custodian.buId,
                        recipientId = custodian.userId,
                        amount = amount,
                        referenceNo = binding.etReference.text?.toString()?.trim()?.ifEmpty { null },
                        description = binding.etDescription.text?.toString()?.trim()?.ifEmpty { null },
                        methodOfTransfer = method,
                        bankAccountId = bankAccountId,
                        receiptUrl = receiptUrl,
                        advanceDate = dateFmt.format(selectedDateMs),
                    )
                )

                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@SendCashActivity, "Cash sent successfully", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    showError(response.body()?.message ?: "Failed to send cash")
                }
            } catch (e: Exception) {
                showError(e.message ?: "An error occurred")
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
        binding.btnSend.isEnabled = !loading
    }
}
