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

        // Already logged in → refresh the aggregated cash role and go to the
        // hub. The hub is BU-agnostic now: it shows tiles based on the union of
        // the user's permissions across every BU they're assigned to.
        if (session.isLoggedIn) {
            setLoading(true)
            lifecycleScope.launch {
                refreshRoleThenGoToHub()
                setLoading(false)
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
                    // Hub is BU-agnostic: no select-unit call, no businessUnitId.
                    session.businessUnitId = null
                    session.businessUnitName = null

                    refreshRoleThenGoToHub()
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
     * Refresh both the aggregated cash role and the merged entity permissions
     * across every BU the user is assigned to. Both come from the server
     * unioned across the user's whole assignment tree — no BU selection
     * needed on the client.
     */
    private suspend fun refreshRoleThenGoToHub() {
        try {
            val api = ApiClient.getService(this@LoginActivity)

            val roleData = runCatching { api.getMyRole() }
                .getOrNull()
                ?.takeIf { it.isSuccessful && it.body()?.success == true }
                ?.body()?.data
            session.cashRole = roleData?.role
            session.saveRoleLabels(roleData?.roleLabels)

            val perms = runCatching { api.getMyPermissions() }
                .getOrNull()
                ?.takeIf { it.isSuccessful && it.body()?.success == true }
                ?.body()?.data
            session.savePermissions(perms)
        } catch (_: Exception) {
            // Non-fatal — hub renders fine even if these calls fail (tiles hidden).
        }
        goToHub()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.unregister()
    }
}
