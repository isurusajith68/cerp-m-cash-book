package com.ceyinfo.cerpcashbook.ui.hub

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.ceyinfo.cerpcashbook.R
import android.widget.Toast
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityModuleHubBinding
import com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity
import com.ceyinfo.cerpcashbook.ui.buselect.BuSelectActivity
import com.ceyinfo.cerpcashbook.ui.ledger.LedgerActivity
import com.ceyinfo.cerpcashbook.ui.login.LoginActivity
import com.ceyinfo.cerpcashbook.ui.notifications.NotificationsActivity
import com.ceyinfo.cerpcashbook.ui.review.ReviewVouchersActivity
import com.ceyinfo.cerpcashbook.ui.settings.SettingsActivity
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

/**
 * Landing screen shown after BU selection. Presents finance modules as tiles,
 * gated by the user's cash role and owner flag.
 */
class ModuleHubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModuleHubBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityModuleHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        session = SessionManager(this)

        if (!session.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setupHeader()
        setupBottomNav()
        buildModuleTiles()
        loadNotifBadge()

        binding.swipeRefresh.setOnRefreshListener {
            loadNotifBadge()
            binding.swipeRefresh.isRefreshing = false
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
    }

    override fun onResume() {
        super.onResume()
        // BU / role may have changed (user returned from BuSelectActivity), so rebuild
        // header and tiles. Notifications badge also refreshes.
        setupHeader()
        buildModuleTiles()
        loadNotifBadge()
    }

    private fun setupHeader() {
        binding.tvBuName.text = session.businessUnitName ?: "No Site"
        binding.tvUserEmail.text = session.email
        binding.tvAvatar.text = session.email?.firstOrNull()?.uppercase() ?: "U"

        val role = session.cashRole ?: "none"
        val roleText = when {
            session.isOwner -> "OWNER"
            role == "clerk" -> "CLERK"
            role == "custodian" -> "CUSTODIAN"
            role == "both" -> "CLERK + CUSTODIAN"
            else -> "NO ROLE"
        }
        val roleColor = when {
            session.isOwner -> R.color.success
            role == "clerk" -> R.color.info
            role == "custodian" -> R.color.warning
            role == "both" -> R.color.success
            else -> R.color.primary
        }
        binding.tvRoleBadge.text = roleText
        (binding.tvRoleBadge.background as? GradientDrawable)
            ?.setColor(ContextCompat.getColor(this, roleColor))

        binding.btnChangeBu.setOnClickListener {
            startActivity(Intent(this, BuSelectActivity::class.java))
        }
    }

    private fun buildModuleTiles() {
        binding.row1.removeAllViews()
        binding.row2.removeAllViews()
        binding.row3.removeAllViews()

        // 5 entity tiles per the design mockup (permission-gating placeholder — all roles
        // see all tiles; Banks / Petty Cash / Cashbook are stubs until backend exists).
        // Layout: row1 = Cash Advance + Banks, row2 = Petty Cash + Cashbook, row3 = Expense Vouchers.

        binding.row1.addView(
            makeTile(
                title = getString(R.string.module_cash_advance),
                subtitle = getString(R.string.module_cash_advance_desc),
                iconRes = R.drawable.ic_send_cash,
                bgTint = "#DBEAFE",
                iconTint = "#2563EB",
            ) {
                startActivity(Intent(this, CashAdvancesTabActivity::class.java))
            }
        )
        binding.row1.addView(makeComingSoonTile(
            title = getString(R.string.module_banks),
            subtitle = getString(R.string.module_banks_desc),
            iconRes = R.drawable.ic_ledger,
            bgTint = "#D1FAE5",
            iconTint = "#059669",
        ))

        binding.row2.addView(makeComingSoonTile(
            title = getString(R.string.module_petty_cash),
            subtitle = getString(R.string.module_petty_cash_desc),
            iconRes = R.drawable.ic_receipt,
            bgTint = "#FEF3C7",
            iconTint = "#D97706",
        ))
        binding.row2.addView(makeComingSoonTile(
            title = getString(R.string.module_cashbook),
            subtitle = getString(R.string.module_cashbook_desc),
            iconRes = R.drawable.ic_ledger,
            bgTint = "#EDE9FE",
            iconTint = "#7C3AED",
        ))

        binding.row3.addView(
            makeTile(
                title = getString(R.string.module_expense_vouchers),
                subtitle = getString(R.string.module_expense_vouchers_desc),
                iconRes = R.drawable.ic_review,
                bgTint = "#FCE7F3",
                iconTint = "#DB2777",
            ) {
                startActivity(Intent(this, ReviewVouchersActivity::class.java))
            }
        )
        // Row 3 has a single tile; pad with spacer so it doesn't stretch full width.
        binding.row3.addView(makeSpacerTile())
    }

    private fun makeComingSoonTile(
        title: String,
        subtitle: String,
        iconRes: Int,
        bgTint: String,
        iconTint: String,
    ): MaterialCardView = makeTile(title, subtitle, iconRes, bgTint, iconTint) {
        Toast.makeText(this, "${title}: ${getString(R.string.coming_soon)}", Toast.LENGTH_SHORT).show()
    }

    private fun makeTile(
        title: String,
        subtitle: String,
        iconRes: Int,
        bgTint: String,
        iconTint: String,
        onClick: () -> Unit,
    ): MaterialCardView {
        val dp = resources.displayMetrics.density

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (6 * dp).toInt()
                marginEnd = (6 * dp).toInt()
            }
            radius = 16 * dp
            cardElevation = 0f
            strokeColor = android.graphics.Color.parseColor("#E8EEF4")
            strokeWidth = (1 * dp).toInt()
            isClickable = true
            isFocusable = true
            val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            foreground = attrs.getDrawable(0)
            attrs.recycle()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt())
        }

        val iconFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
        }
        val iconBg = View(this).apply {
            layoutParams = FrameLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            background = ContextCompat.getDrawable(context, R.drawable.bg_org_icon)?.mutate()
            (background as? GradientDrawable)?.setColor(android.graphics.Color.parseColor(bgTint))
        }
        val iconView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(iconRes)
            setColorFilter(android.graphics.Color.parseColor(iconTint))
        }
        iconFrame.addView(iconBg)
        iconFrame.addView(iconView)
        content.addView(iconFrame)

        content.addView(TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(0, (14 * dp).toInt(), 0, 0)
        })

        content.addView(TextView(this).apply {
            text = subtitle
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })

        card.addView(content)
        card.setOnClickListener { onClick() }
        return card
    }

    /** Invisible placeholder so a single tile in a row doesn't stretch to full width. */
    private fun makeSpacerTile(): View {
        val dp = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = (6 * dp).toInt()
                marginEnd = (6 * dp).toInt()
            }
        }
    }

    private fun setupBottomNav() {
        binding.navHome.setOnClickListener { /* already home */ }
        binding.navNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }
        binding.navLedger.setOnClickListener {
            startActivity(Intent(this, LedgerActivity::class.java))
        }
        binding.navSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun loadNotifBadge() {
        val siteId = session.businessUnitId ?: return

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@ModuleHubActivity)
                val response = api.getDashboardStats(siteId)
                if (response.isSuccessful && response.body()?.success == true) {
                    val stats = response.body()!!.data!!
                    if (stats.unreadNotifications > 0) {
                        binding.tvNotifBadge.text = stats.unreadNotifications.toString()
                        binding.tvNotifBadge.visibility = View.VISIBLE
                    } else {
                        binding.tvNotifBadge.visibility = View.GONE
                    }
                }
            } catch (_: Exception) {
                // Silent — badge is supplementary
            }
        }
    }
}
