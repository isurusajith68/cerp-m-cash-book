package com.ceyinfo.cerpcashbook.ui.sendcash

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ceyinfo.cerpcashbook.data.model.CreateAdvanceRequest
import com.ceyinfo.cerpcashbook.data.model.Custodian
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivitySendCashBinding
import com.ceyinfo.cerpcashbook.util.SessionManager
import kotlinx.coroutines.launch

class SendCashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendCashBinding
    private lateinit var session: SessionManager
    private var custodians: List<Custodian> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivitySendCashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSend.setOnClickListener { sendCash() }

        loadCustodians()
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
                    val names = custodians.map { "${it.firstName} ${it.lastName} (Bal: ${String.format("%,.0f", it.balance)})" }
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

    private fun sendCash() {
        val idx = binding.spinnerCustodian.selectedItemPosition
        if (idx < 0 || custodians.isEmpty()) {
            showError("Select a custodian")
            return
        }

        val amountStr = binding.etAmount.text?.toString()?.trim() ?: ""
        if (amountStr.isEmpty()) {
            binding.tilAmount.error = "Amount is required"
            return
        }
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            binding.tilAmount.error = "Enter a valid amount"
            return
        }
        binding.tilAmount.error = null

        val custodian = custodians[idx]
        val siteId = session.businessUnitId ?: return

        setLoading(true)

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@SendCashActivity)
                val response = api.createCashAdvance(CreateAdvanceRequest(
                    siteBuId = siteId,
                    custodianId = custodian.userId,
                    amount = amount,
                    referenceNo = binding.etReference.text?.toString()?.trim()?.ifEmpty { null },
                    description = binding.etDescription.text?.toString()?.trim()?.ifEmpty { null }
                ))

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
