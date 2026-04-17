package com.ceyinfo.cerpcashbook.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ceyinfo.cerpcashbook.BuildConfig
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivitySettingsBinding
import com.ceyinfo.cerpcashbook.ui.login.LoginActivity
import com.ceyinfo.cerpcashbook.util.SessionManager
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvSiteName.text = session.businessUnitName ?: "—"
        binding.tvRole.text = when (session.cashRole) {
            "clerk" -> "Site Cash Clerk"
            "custodian" -> "Site Cash Custodian"
            "both" -> "Clerk + Custodian"
            else -> "—"
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.btnLogout.setOnClickListener {
            lifecycleScope.launch {
                try {
                    ApiClient.getService(this@SettingsActivity).logout()
                } catch (_: Exception) { }
                ApiClient.clearSession()
                session.clearSession()
                startActivity(Intent(this@SettingsActivity, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
    }
}
