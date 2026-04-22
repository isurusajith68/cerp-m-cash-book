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
import com.ceyinfo.cerpcashbook.ui.ledger.LedgerActivity
import com.ceyinfo.cerpcashbook.ui.login.LoginActivity
import com.ceyinfo.cerpcashbook.ui.notifications.NotificationsActivity
import com.ceyinfo.cerpcashbook.ui.review.ReviewVouchersActivity
import com.ceyinfo.cerpcashbook.ui.settings.SettingsActivity
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

/**
 * Landing screen shown after login. Presents finance modules as tiles, gated
 * by the user's aggregated cash role across all assigned BUs.
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
            refreshPermissionsAndTiles {
                binding.swipeRefresh.isRefreshing = false
            }
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
    }

    override fun onResume() {
        super.onResume()
        // Role / permissions may have changed since last open — refresh from
        // the server, then rebuild tiles + badge with the fresh ACL.
        setupHeader()
        loadNotifBadge()
        refreshPermissionsAndTiles()
    }

    /**
     * Refresh `/my-permissions` + `/my-role` and rebuild the tile grid. Tiles
     * render immediately from cached ACL so the user sees something instantly;
     * once the network call returns we cache the fresh data and re-render.
     * `onDone` fires whether the refresh succeeded or failed — used by
     * pull-to-refresh to dismiss its spinner.
     */
    private fun refreshPermissionsAndTiles(onDone: (() -> Unit)? = null) {
        buildModuleTiles()
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@ModuleHubActivity)
                val perms = api.getMyPermissions()
                if (perms.isSuccessful && perms.body()?.success == true) {
                    session.savePermissions(perms.body()?.data)
                    val role = api.getMyRole()
                    if (role.isSuccessful && role.body()?.success == true) {
                        session.cashRole = role.body()?.data?.role
                    }
                    buildModuleTiles()
                }
            } catch (_: Exception) {
                // Silent — cached ACL still drives the UI.
            } finally {
                onDone?.invoke()
            }
        }
    }

    private fun setupHeader() {
        // Hub is BU-agnostic: header shows the app title, not a per-BU name.
        binding.tvBuName.text = getString(R.string.app_name)
        binding.tvUserEmail.text = session.email
        binding.tvAvatar.text = session.email?.firstOrNull()?.uppercase() ?: "U"
    }

    private fun buildModuleTiles() {
        binding.row1.removeAllViews()
        binding.row2.removeAllViews()
        binding.row3.removeAllViews()

        val canCashAdvance = session.isEntityAllowed("cash_advance")
        val canExpenseVoucher = session.isEntityAllowed("expense_voucher")
        

        if (canCashAdvance) {
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
        }
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

        if (canExpenseVoucher) {
            binding.row3.visibility = View.VISIBLE
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
        } else {
            binding.row3.visibility = View.GONE
        }
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
        // Aggregated across every site the user can reach — backend handles it
        // when site_bu_id is omitted.
        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@ModuleHubActivity)
                val response = api.getDashboardStats()
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
