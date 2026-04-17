package com.ceyinfo.cerpcashbook.ui.expense

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ceyinfo.cerpcashbook.data.model.CreateVoucherRequest
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivitySubmitExpenseBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SubmitExpenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubmitExpenseBinding
    private lateinit var session: SessionManager
    private val calendar = Calendar.getInstance()
    private val categories = listOf("Fuel", "Materials", "Labour", "Transport", "Food", "Utilities", "Misc")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivitySubmitExpenseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSubmit.setOnClickListener { submitExpense() }

        // Date picker
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        binding.etDate.setText(dateFormat.format(calendar.time))
        binding.etDate.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                calendar.set(y, m, d)
                binding.etDate.setText(dateFormat.format(calendar.time))
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }

        // Category spinner
        binding.spinnerCategory.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
    }

    private fun submitExpense() {
        val date = binding.etDate.text?.toString()?.trim() ?: ""
        val amountStr = binding.etAmount.text?.toString()?.trim() ?: ""
        val description = binding.etDescription.text?.toString()?.trim() ?: ""

        if (date.isEmpty()) { showError("Date is required"); return }
        if (amountStr.isEmpty()) { binding.tilAmount.error = "Amount is required"; return }

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) { binding.tilAmount.error = "Enter a valid amount"; return }
        binding.tilAmount.error = null

        if (description.isEmpty()) { showError("Description is required"); return }

        val siteId = session.businessUnitId ?: return
        val category = categories[binding.spinnerCategory.selectedItemPosition]

        setLoading(true)

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SubmitExpenseActivity)
                val response = api.createExpenseVoucher(CreateVoucherRequest(
                    siteBuId = siteId,
                    expenseDate = date,
                    category = category,
                    description = description,
                    amount = amount
                ))

                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@SubmitExpenseActivity, "Expense submitted", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    showError(response.body()?.message ?: "Failed to submit")
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
        binding.btnSubmit.isEnabled = !loading
    }
}
