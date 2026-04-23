package com.ceyinfo.cerpcashbook.ui.sendcash

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
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
    private val methodLabels = listOf("Cash", "Online", "CDM", "Cheque", "Counter")
    private var selectedMethodIdx: Int = 0

    private val amountQuickPicks = listOf(1_000.0, 5_000.0, 10_000.0, 25_000.0, 50_000.0)

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
        binding.btnAmountCopy.setOnClickListener { copyAmountToClipboard() }
        binding.btnHistory.setOnClickListener {
            // Open the Cash Advance Issues tab to view recent disbursements.
            startActivity(android.content.Intent(this,
                com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity::class.java))
        }
        // Tap the recipient card to open the picker (when more than one option).
        binding.cardRecipient.setOnClickListener {
            if (visibleCustodians.size > 1) showRecipientPicker()
        }

        renderDateButton()
        setupMethodChips()
        setupAmountQuickPicks()
        // Keep the bottom Send button label in sync with whatever's typed.
        binding.etAmount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateSendButtonLabel() }
        })
        updateSendButtonLabel()
        loadCustodians()
    }

    /** Send button reads "Send LKR X,XXX" so the user always sees the total. */
    private fun updateSendButtonLabel() {
        val raw = binding.etAmount.text?.toString()?.trim().orEmpty()
        val v = raw.toDoubleOrNull()
        binding.btnSend.text = if (v != null && v > 0)
            "Send LKR " + java.lang.String.format(Locale.US, "%,.2f", v)
        else
            "Send Cash"
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
        // Date is now an input-style row: tv_date_label is the value text.
        binding.tvDateLabel.text = displayDateFmt.format(selectedDateMs)
    }

    /** Copies the current amount value (digits only, no formatting) to clipboard. */
    private fun copyAmountToClipboard() {
        val raw = binding.etAmount.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("amount", raw))
        Toast.makeText(this, "Amount copied", Toast.LENGTH_SHORT).show()
    }

    /** Opens an alert dialog to pick a custodian when there's more than one. */
    private fun showRecipientPicker() {
        if (visibleCustodians.size <= 1) return
        val labels = visibleCustodians.map {
            "${it.firstName} ${it.lastName} · ${it.buName}"
        }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Select recipient")
            .setItems(labels) { _, which ->
                binding.spinnerCustodian.setSelection(which)
                renderRecipientCard()
                if (binding.rowBank.visibility == View.VISIBLE) loadBankAccountsForSelection()
            }
            .show()
    }

    /** Reads the currently-selected custodian and paints the card header. */
    private fun renderRecipientCard() {
        val r = selectedCustodian()
        if (r == null) {
            binding.tvAvatar.text = "?"
            binding.tvRecipientName.text = "No recipient available"
            binding.tvRecipientBu.text = "—"
            binding.btnChangeRecipient.visibility = View.GONE
            return
        }
        binding.tvAvatar.text = r.firstName.firstOrNull()?.uppercase()
            ?: r.lastName.firstOrNull()?.uppercase() ?: "?"
        binding.tvRecipientName.text = "${r.firstName} ${r.lastName}".trim()
        binding.tvRecipientBu.text = r.buName +
            (r.buLevel?.let { " · ${it.uppercase(Locale.US)}" } ?: "")
        binding.btnChangeRecipient.visibility =
            if (visibleCustodians.size > 1) View.VISIBLE else View.GONE
    }

    // ─── Dropdowns ─────────────────────────────────────────────────────────

    /**
     * Replaces the old method spinner with a row of chips. Selecting a non-cash
     * method reveals the bank-account row and triggers a fresh bank fetch for
     * the currently-selected custodian's site.
     */
    private fun setupMethodChips() {
        binding.chipsMethod.removeAllViews()
        methodLabels.forEachIndexed { index, label ->
            val chip = buildPickerChip(label, index == selectedMethodIdx) {
                if (selectedMethodIdx != index) {
                    selectedMethodIdx = index
                    val isCash = methodCodes[index] == "cash"
                    binding.rowBank.visibility = if (isCash) View.GONE else View.VISIBLE
                    if (!isCash) loadBankAccountsForSelection()
                }
            }
            binding.chipsMethod.addView(chip)
        }
        // Initial visibility for the bank row.
        binding.rowBank.visibility =
            if (methodCodes[selectedMethodIdx] == "cash") View.GONE else View.VISIBLE
    }

    /** Quick-pick amounts (1K / 5K / 10K / 25K / 50K). Tap to fill. */
    private fun setupAmountQuickPicks() {
        binding.chipsAmount.removeAllViews()
        amountQuickPicks.forEach { amt ->
            val label = when {
                amt >= 1000 -> "+${(amt / 1000).toInt()}K"
                else -> "+${amt.toInt()}"
            }
            val chip = com.google.android.material.chip.Chip(this).apply {
                val dp = resources.displayMetrics.density
                text = label
                isCheckable = false
                chipCornerRadius = 18f * dp
                chipStrokeWidth = 1f * dp
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.divider)
                )
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.white)
                )
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.on_surface))
                setOnClickListener {
                    val current = binding.etAmount.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
                    val next = current + amt
                    binding.etAmount.setText(formatAmountInput(next))
                    binding.etAmount.setSelection(binding.etAmount.text?.length ?: 0)
                }
            }
            binding.chipsAmount.addView(chip)
        }
    }

    /** Builds a single-select picker chip for the method row. */
    private fun buildPickerChip(
        label: String,
        checked: Boolean,
        onChecked: () -> Unit,
    ): com.google.android.material.chip.Chip {
        val dp = resources.displayMetrics.density
        return com.google.android.material.chip.Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = checked
            chipCornerRadius = 18f * dp
            chipStrokeWidth = 1f * dp
            chipStrokeColor = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.divider)
            )
            chipBackgroundColor = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.primary),
                    androidx.core.content.ContextCompat.getColor(context, R.color.white),
                )
            )
            setTextColor(
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        androidx.core.content.ContextCompat.getColor(context, R.color.white),
                        androidx.core.content.ContextCompat.getColor(context, R.color.on_surface),
                    )
                )
            )
            // Show a check on the selected method chip (matches the mockup).
            isCheckedIconVisible = true
            checkedIconTint = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.white)
            )
            setOnClickListener { if (isChecked) onChecked() }
        }
    }

    /** Formats a Double into the input field — drops trailing `.0` for integers. */
    private fun formatAmountInput(amt: Double): String =
        if (amt == amt.toLong().toDouble()) amt.toLong().toString()
        else String.format(Locale.US, "%.2f", amt)

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
            renderRecipientCard()
            return
        }

        // The recipient card is the visible UI now — the spinner stays hidden,
        // backing the selection. We populate it so the index lookup works for
        // selectedCustodian().
        val labels = visibleCustodians.map {
            "${it.firstName} ${it.lastName} · ${it.buName} (Bal: ${"%,.0f".format(it.balance)})"
        }
        binding.spinnerCustodian.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        renderRecipientCard()

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
        binding.photoPlaceholder.visibility = View.GONE
        binding.btnRemovePhoto.visibility = View.VISIBLE
        binding.btnAttachPhoto.isEnabled = false

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", "receipt.jpg", body)
                val response = api.uploadAdvanceReceipt(part)
                if (response.isSuccessful && response.body()?.success == true) {
                    receiptUrl = response.body()?.data?.url
                    // Image is already shown in iv_photo_preview; placeholder is hidden.
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
        binding.photoPlaceholder.visibility = View.VISIBLE
        binding.btnRemovePhoto.visibility = View.GONE
        binding.btnAttachPhoto.isEnabled = true
    }

    // ─── Submit ────────────────────────────────────────────────────────────

    /**
     * Validates the form, then opens a confirm bottom sheet showing the user
     * exactly what they're about to send. The actual POST happens inside the
     * sheet's onConfirm callback (sheet handles its own loading + error).
     * On success a green success sheet replaces it with primary/secondary
     * CTAs ("Send another" / "Done").
     */
    private fun sendCash() {
        val custodian = selectedCustodian() ?: run {
            showError("Select a custodian"); return
        }

        val amountStr = binding.etAmount.text?.toString()?.trim().orEmpty()
        if (amountStr.isEmpty()) { showError("Amount is required"); return }
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) { showError("Enter a valid amount"); return }
        binding.tvError.visibility = View.GONE

        val methodIdx = selectedMethodIdx.coerceIn(0, methodCodes.lastIndex)
        val method = methodCodes[methodIdx]
        val methodLabel = methodLabels[methodIdx]
        var bankAccountId: String? = null
        var bankLabel: String? = null
        if (method != "cash") {
            if (bankAccounts.isEmpty()) {
                showError("Add a bank account before sending by $method.")
                return
            }
            val bankIdx = binding.spinnerBank.selectedItemPosition
            if (bankIdx < 0) { showError("Select a deposit account."); return }
            val bank = bankAccounts[bankIdx]
            bankAccountId = bank.id
            bankLabel = "${bank.accountName} · ${bank.bankName ?: "—"} (${bank.accountNumber})"
        }
        val displayDate = displayDateFmt.format(selectedDateMs)
        val amountLabel = "LKR " + java.lang.String.format(Locale.US, "%,.2f", amount)
        val recipientLabel = "${custodian.firstName} ${custodian.lastName} · ${custodian.buName}"

        val details = mutableListOf(
            com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Detail("Amount", amountLabel),
            com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Detail("To", recipientLabel),
            com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Detail("Date", displayDate),
            com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Detail("Method", methodLabel),
        )
        if (bankLabel != null) details.add(
            com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Detail("Deposit to", bankLabel)
        )

        com.ceyinfo.cerpcashbook.ui.common.ActionSheets.showConfirm(
            context = this,
            scope = lifecycleScope,
            title = "Send cash?",
            details = details,
            confirmLabel = "Send $amountLabel",
            warning = "A pending cash advance will be created. The recipient must acknowledge it before the ledger is debited.",
            tone = com.ceyinfo.cerpcashbook.ui.common.ActionSheets.Tone.PRIMARY,
        ) {
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
            if (!(response.isSuccessful && response.body()?.success == true)) {
                throw RuntimeException(response.body()?.message ?: "Failed to send cash")
            }

            // Show success sheet with two CTAs.
            com.ceyinfo.cerpcashbook.ui.common.ActionSheets.showSuccess(
                context = this@SendCashActivity,
                title = "Cash sent",
                subtitle = "$amountLabel sent to ${custodian.firstName}. Awaiting their acknowledgement.",
                primaryLabel = "Done",
                onPrimary = { finish() },
                secondaryLabel = "Send another",
                onSecondary = { resetFormForReuse() },
            )
        }
    }

    /** Clear amount + reference + description + photo so the form is fresh
     * for another disbursement. Keeps date + custodian + method + bank. */
    private fun resetFormForReuse() {
        binding.etAmount.setText("")
        binding.etReference.setText("")
        binding.etDescription.setText("")
        clearReceipt()
        binding.tvError.visibility = View.GONE
        binding.etAmount.requestFocus()
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
