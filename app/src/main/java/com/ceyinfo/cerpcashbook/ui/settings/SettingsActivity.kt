package com.ceyinfo.cerpcashbook.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ceyinfo.cerpcashbook.BuildConfig
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivitySettingsBinding
import com.ceyinfo.cerpcashbook.ui.login.LoginActivity
import com.ceyinfo.cerpcashbook.updater.AppUpdater
import com.ceyinfo.cerpcashbook.updater.UpdateDialog
import com.ceyinfo.cerpcashbook.util.SessionManager
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var session: SessionManager
    private lateinit var updater: AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        com.ceyinfo.cerpcashbook.ui.common.BottomNav.bind(
            binding.bottomNav.root, this, com.ceyinfo.cerpcashbook.ui.common.BottomNav.Tab.SETTINGS
        )

        session = SessionManager(this)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvRole.text = when (session.cashRole) {
            "clerk" -> "Site Cash Clerk"
            "custodian" -> "Site Cash Custodian"
            "both" -> "Clerk + Custodian"
            else -> "—"
        }

        binding.btnBack.setOnClickListener { finish() }

        updater = AppUpdater(this)
        binding.rowCheckUpdate.setOnClickListener { checkForUpdate() }

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

    /**
     * Force a fresh GitHub Releases check (bypasses the 4h throttle and
     * any "Skip" the user may have set on a prior version).
     */
    private fun checkForUpdate() {
        binding.progressUpdate.visibility = View.VISIBLE
        binding.icUpdateArrow.visibility = View.GONE
        binding.tvUpdateStatus.text = "Checking…"

        lifecycleScope.launch {
            val release = updater.checkForUpdate(forceCheck = true)

            binding.progressUpdate.visibility = View.GONE
            binding.icUpdateArrow.visibility = View.VISIBLE

            if (release != null) {
                val version = release.tagName.removePrefix("v")
                binding.tvUpdateStatus.text = "New version available: v$version"
                UpdateDialog.show(this@SettingsActivity, release, updater)
            } else {
                binding.tvUpdateStatus.text = "You're on the latest version"
                Toast.makeText(this@SettingsActivity, "App is up to date", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
