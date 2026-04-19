package com.ceyinfo.cerpcashbook.ui.login

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.model.LoginRequest
import com.ceyinfo.cerpcashbook.data.model.Organization
import com.ceyinfo.cerpcashbook.data.model.SelectUnitRequest
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityLoginBinding
import com.ceyinfo.cerpcashbook.databinding.ItemOrgBinding
import com.ceyinfo.cerpcashbook.ui.hub.ModuleHubActivity
import com.ceyinfo.cerpcashbook.util.NetworkMonitor
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var session: SessionManager
    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)
        networkMonitor = NetworkMonitor(this)

        // Already logged in → go straight to the Module Hub. If a BU was never
        // selected in this install, auto-pick the first available site so the user
        // lands on a functional hub without seeing the BU picker.
        if (session.isLoggedIn) {
            if (session.businessUnitId != null) {
                goToHub()
            } else {
                setLoading(true)
                lifecycleScope.launch {
                    autoSelectFirstBuThenGoToHub()
                    setLoading(false)
                }
            }
            return
        }

        binding.btnLogin.setOnClickListener { attemptLogin(null) }

        binding.etPassword.setOnEditorActionListener { _, _, _ ->
            attemptLogin(null)
            true
        }
    }

    private fun attemptLogin(organizationId: String?) {
        val email = binding.etEmail.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString()?.trim() ?: ""

        binding.tilEmail.error = null
        binding.tilPassword.error = null

        if (email.isEmpty()) {
            binding.tilEmail.error = "Email is required"
            binding.etEmail.requestFocus()
            return
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = "Password is required"
            binding.etPassword.requestFocus()
            return
        }

        if (!networkMonitor.checkNetwork()) {
            showError(getString(R.string.error_network))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@LoginActivity)
                val response = api.login(LoginRequest(email, password, organizationId))

                if (response.isSuccessful && response.body()?.success == true) {
                    val data = response.body()!!.data!!

                    // Multi-org: show picker dialog
                    if (data.selectOrgRequired == true && !data.organizations.isNullOrEmpty()) {
                        setLoading(false)
                        showOrgPicker(data.organizations)
                        return@launch
                    }

                    session.isLoggedIn = true
                    session.userId = data.userId
                    session.email = data.email
                    session.organizationId = data.organizationId
                    session.isOwner = data.isOwner

                    // Auto-select first BU so the user lands on the Module Hub,
                    // not a BU picker. They can change BU from the hub later.
                    autoSelectFirstBuThenGoToHub()
                } else {
                    val msg = response.body()?.message
                        ?: response.errorBody()?.string()
                        ?: "Invalid email or password"
                    showError(msg)
                }
            } catch (e: Exception) {
                showError(e.message ?: "An error occurred")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showOrgPicker(organizations: List<Organization>) {
        val dialog = BottomSheetDialog(this, R.style.Theme_CashBook_BottomSheet)
        val view = layoutInflater.inflate(R.layout.dialog_org_picker, null)
        dialog.setContentView(view)

        val rv = view.findViewById<RecyclerView>(R.id.rv_orgs)
        rv.layoutManager = LinearLayoutManager(this)

        val colors = intArrayOf(
            R.color.primary, R.color.info, R.color.success,
            R.color.warning, R.color.error, R.color.status_submitted
        )

        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = organizations.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val b = ItemOrgBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return object : RecyclerView.ViewHolder(b.root) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val org = organizations[position]
                val b = ItemOrgBinding.bind(holder.itemView)

                b.tvOrgName.text = org.name
                b.tvInitial.text = org.name.firstOrNull()?.uppercase() ?: "O"

                val colorRes = colors[position % colors.size]
                val bg = b.bgIcon.background as? GradientDrawable
                    ?: GradientDrawable().apply { cornerRadius = 12f * holder.itemView.resources.displayMetrics.density }
                bg.setColor(ContextCompat.getColor(holder.itemView.context, colorRes))
                b.bgIcon.background = bg

                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    attemptLogin(org.id)
                }
            }
        }

        dialog.show()
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.etEmail.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        if (loading) binding.tvError.visibility = View.GONE
    }

    private fun goToHub() {
        startActivity(Intent(this, ModuleHubActivity::class.java))
        finish()
    }

    /**
     * Fetch the user's cash-role assignments, auto-select the first available site,
     * and navigate to the Module Hub. If no sites are available (or the network
     * fails), we still land on the hub — it handles the "No Site" empty state.
     */
    private suspend fun autoSelectFirstBuThenGoToHub() {
        try {
            val api = ApiClient.getService(this@LoginActivity)
            val roleResp = api.getMyRole()
            if (roleResp.isSuccessful && roleResp.body()?.success == true) {
                val roleData = roleResp.body()!!.data!!
                session.cashRole = roleData.role

                // Prefer a clerk site; fall back to a custodian site
                val firstClerk = roleData.clerkSites.firstOrNull()
                val firstCustodian = roleData.custodianSites.firstOrNull()
                val chosen = firstClerk ?: firstCustodian

                if (chosen != null) {
                    val selectResp = api.selectUnit(SelectUnitRequest(chosen.siteBuId))
                    if (selectResp.isSuccessful && selectResp.body()?.success == true) {
                        val sel = selectResp.body()!!.data!!
                        session.businessUnitId = sel.businessUnitId
                        session.businessUnitName = sel.businessUnitName

                        // Derive the role specific to the chosen site
                        val inClerk = roleData.clerkSites.any { it.siteBuId == chosen.siteBuId }
                        val inCustodian = roleData.custodianSites.any { it.siteBuId == chosen.siteBuId }
                        session.cashRole = when {
                            inClerk && inCustodian -> "both"
                            inClerk -> "clerk"
                            inCustodian -> "custodian"
                            else -> roleData.role
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Non-fatal — the hub shows a "No Site" state and the user can tap "Change".
        }
        goToHub()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.unregister()
    }
}
