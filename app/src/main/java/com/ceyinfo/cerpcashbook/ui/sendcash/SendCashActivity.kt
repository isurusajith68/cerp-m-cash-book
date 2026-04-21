package com.ceyinfo.cerpcashbook.ui.sendcash

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.BankAccount
import com.ceyinfo.cerpcashbook.data.model.CreateAdvanceRequest
import com.ceyinfo.cerpcashbook.data.model.CreateBankAccountRequest
import com.ceyinfo.cerpcashbook.data.model.Custodian
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivitySendCashBinding
import com.ceyinfo.cerpcashbook.databinding.DialogCreateBankBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class SendCashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendCashBinding
    private lateinit var session: SessionManager

    private var custodians: List<Custodian> = emptyList()
    private var bankAccounts: List<BankAccount> = emptyList()
    private var receiptUrl: String? = null

    // Photo picker — returns a content URI for an image chosen from gallery/photos.
    // Uses ACTION_GET_CONTENT under the hood so it works on all Android versions
    // without needing runtime READ permissions.
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) uploadReceipt(uri)
    }

    // The TRANSFER_METHODS array's first entry maps to NULL on the server —
    // "cash" is the implicit default, but we surface it explicitly in the UI.
    private val methodCodes = listOf("cash", "online", "cdm", "cheque", "counter")
    private val methodLabels = listOf("Cash", "Online Transfer", "CDM", "Cheque", "Counter")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivitySendCashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSend.setOnClickListener { sendCash() }
        binding.btnAddBank.setOnClickListener { showCreateBankDialog() }
        binding.btnAttachPhoto.setOnClickListener { pickImage.launch("image/*") }
        binding.btnRemovePhoto.setOnClickListener { clearReceipt() }

        setupMethodSpinner()
        loadCustodians()
        loadBankAccounts()
    }

    // ─── Dropdowns ─────────────────────────────────────────────────────────

    private fun setupMethodSpinner() {
        binding.spinnerMethod.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, methodLabels
        )
        binding.spinnerMethod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // "cash" (index 0) hides the bank dropdown; anything else reveals it.
                binding.rowBank.visibility = if (methodCodes[position] == "cash") View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadCustodians() {
        val siteId = session.businessUnitId ?: return
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val response = api.getSiteCustodians(siteId)
                if (response.isSuccessful && response.body()?.success == true) {
                    custodians = response.body()!!.data!!
                    val names = custodians.map {
                        "${it.firstName} ${it.lastName} (Bal: ${String.format("%,.0f", it.balance)})"
                    }
                    binding.spinnerCustodian.adapter = ArrayAdapter(
                        this@SendCashActivity, android.R.layout.simple_spinner_dropdown_item, names
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(this@SendCashActivity, e.message, Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun loadBankAccounts() {
        val siteId = session.businessUnitId ?: return
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
        val dialogBinding = DialogCreateBankBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_bank_account)
            .setView(dialogBinding.root)
            .setPositiveButton("Save", null) // override below so we can keep dialog open on validation error
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.etAccountName.text?.toString()?.trim().orEmpty()
                val number = dialogBinding.etAccountNumber.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) { dialogBinding.etAccountName.error = "Required"; return@setOnClickListener }
                if (number.isEmpty()) { dialogBinding.etAccountNumber.error = "Required"; return@setOnClickListener }

                createBankAccount(
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
        accountName: String,
        accountNumber: String,
        bankName: String?,
        branch: String?,
        holder: String?,
        onDone: () -> Unit,
    ) {
        val siteId = session.businessUnitId ?: return
        setLoading(true)
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val response = api.createBankAccount(
                    CreateBankAccountRequest(
                        siteBuId = siteId,
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
        } catch (e: Exception) { null }
        if (bytes == null) {
            showError("Could not read the selected image")
            return
        }
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"

        // Optimistically show the preview; if the upload fails we clear it.
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
        val idx = binding.spinnerCustodian.selectedItemPosition
        if (idx < 0 || custodians.isEmpty()) {
            showError("Select a custodian")
            return
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

        val custodian = custodians[idx]
        val siteId = session.businessUnitId ?: return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val response = api.createCashAdvance(
                    CreateAdvanceRequest(
                        siteBuId = siteId,
                        custodianId = custodian.userId,
                        amount = amount,
                        referenceNo = binding.etReference.text?.toString()?.trim()?.ifEmpty { null },
                        description = binding.etDescription.text?.toString()?.trim()?.ifEmpty { null },
                        methodOfTransfer = method,
                        bankAccountId = bankAccountId,
                        receiptUrl = receiptUrl,
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
