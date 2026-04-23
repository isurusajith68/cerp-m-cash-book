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
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ceyinfo.cerpcashbook.R
import android.widget.Toast
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityModuleHubBinding
import com.ceyinfo.cerpcashbook.fcm.NotificationEvents
import com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity
import com.ceyinfo.cerpcashbook.ui.common.BottomNav
import com.ceyinfo.cerpcashbook.ui.login.LoginActivity
import com.ceyinfo.cerpcashbook.ui.notifications.NotificationsActivity
import com.ceyinfo.cerpcashbook.ui.review.ReviewVouchersActivity
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
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

        session = SessionManager(this)

        if (!session.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setupHeader()
        setupBottomNav()
        buildModuleTiles()
        
        checkForUpdates()

        binding.swipeRefresh.setOnRefreshListener {
            loadNotifBadge()
            refreshPermissionsAndTiles {
                binding.swipeRefresh.isRefreshing = false
            }
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

       
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotificationEvents.flow.collectLatest {
                    loadNotifBadge()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
     
        setupHeader()
        loadNotifBadge()
        refreshPermissionsAndTiles()
    }

    
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
                        val data = role.body()?.data
                        session.cashRole = data?.role
                        session.saveRoleLabels(data?.roleLabels)
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
        BottomNav.bind(binding.bottomNav.root, this, BottomNav.Tab.HOME)
    }

   
    private fun checkForUpdates() {
        lifecycleScope.launch {
            val updater = com.ceyinfo.cerpcashbook.updater.AppUpdater(this@ModuleHubActivity)
            val release = updater.checkForUpdate()
            if (release != null) {
                com.ceyinfo.cerpcashbook.updater.UpdateDialog.show(
                    this@ModuleHubActivity, release, updater
                )
            }
        }
    }

   
    private var notifBadgeJob: Job? = null

    private fun loadNotifBadge() {
        notifBadgeJob?.cancel()
        notifBadgeJob = lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@ModuleHubActivity)

                // Notifications badge (independent of advances/vouchers state).
                val statsResp = api.getDashboardStats()
                val unread = if (statsResp.isSuccessful && statsResp.body()?.success == true) {
                    statsResp.body()?.data?.unreadNotifications ?: 0
                } else 0
                if (unread > 0) {
                    binding.bottomNav.tvNotifBadge.text = unread.toString()
                    binding.bottomNav.tvNotifBadge.visibility = View.VISIBLE
                } else {
                    binding.bottomNav.tvNotifBadge.visibility = View.GONE
                }

                buildAlertCards(unread)
            } catch (_: Exception) {
                // Silent — alerts are supplementary; UI degrades to no cards.
            }
        }
    }

    /**
     * Build the "needs your attention" cards above the tile grid.
     * Three sources, each rendered only if (a) the user has the relevant
     * action ACL and (b) there's at least one item to act on.
     *
     *   - Pending advances I need to acknowledge      → Cash Advance / Received
     *   - Submitted vouchers I can approve            → Expense Vouchers
     *   - Unread notifications                        → Notifications
     */
    private suspend fun buildAlertCards(unreadCount: Int) {
        binding.alertsContainer.removeAllViews()
        binding.alertsContainer.visibility = View.GONE

        val api = ApiClient.getService(this@ModuleHubActivity)

        // Pending advances awaiting MY acknowledgement
        val canAck = session.canPerformAction("cash_advance", "acknowledge")
        var pendingForMe = 0
        if (canAck) {
            try {
                val resp = api.getCashAdvances(
                    buId = null,
                    recipientId = session.userId,
                    status = "pending",
                    page = 1, limit = 1,
                )
                if (resp.isSuccessful && resp.body()?.success == true) {
                    pendingForMe = resp.body()?.total ?: (resp.body()?.data?.size ?: 0)
                }
            } catch (_: Exception) { /* card just won't appear */ }
        }

        // Vouchers awaiting MY review
        val canReview = session.canPerformAction("expense_voucher", "approve") ||
            session.canPerformAction("expense_voucher", "reject")
        var pendingReview = 0
        if (canReview) {
            try {
                val resp = api.getExpenseVouchers(
                    buId = null,
                    status = "submitted",
                    page = 1, limit = 1,
                )
                if (resp.isSuccessful && resp.body()?.success == true) {
                    pendingReview = resp.body()?.total ?: (resp.body()?.data?.size ?: 0)
                }
            } catch (_: Exception) { /* silent */ }
        }

        if (pendingForMe > 0) {
            addAlertCard(
                title = "$pendingForMe ${if (pendingForMe == 1) "advance" else "advances"} awaiting acknowledgement",
                subtitle = "Tap to confirm receipt and post to your balance.",
                accent = "#D97706",
                bg = "#FFFBEB",
                iconRes = R.drawable.ic_check_circle,
            ) {
                startActivity(
                    Intent(this, com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity::class.java)
                        .putExtra(
                            com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity.EXTRA_INITIAL_TAB,
                            com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity.TAB_RECEIVED,
                        )
                )
            }
        }

        if (pendingReview > 0) {
            addAlertCard(
                title = "$pendingReview ${if (pendingReview == 1) "voucher" else "vouchers"} awaiting review",
                subtitle = "Tap to approve or reject submitted expenses.",
                accent = "#2563EB",
                bg = "#EFF6FF",
                iconRes = R.drawable.ic_review,
            ) {
                startActivity(
                    Intent(this, com.ceyinfo.cerpcashbook.ui.review.ReviewVouchersActivity::class.java)
                )
            }
        }

        if (unreadCount > 0) {
            addAlertCard(
                title = "$unreadCount unread ${if (unreadCount == 1) "notification" else "notifications"}",
                subtitle = "Tap to read updates from your team.",
                accent = "#7C3AED",
                bg = "#F3E8FF",
                iconRes = R.drawable.ic_notifications,
            ) {
                startActivity(Intent(this, NotificationsActivity::class.java))
            }
        }

        binding.alertsContainer.visibility =
            if (binding.alertsContainer.childCount > 0) View.VISIBLE else View.GONE
    }

    /** Programmatically add a single alert card to the alerts container. */
    private fun addAlertCard(
        title: String,
        subtitle: String,
        accent: String,
        bg: String,
        iconRes: Int,
        onClick: () -> Unit,
    ) {
        val dp = resources.displayMetrics.density
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (binding.alertsContainer.childCount > 0) topMargin = (10 * dp).toInt()
            }
            radius = 14 * dp
            cardElevation = 0f
            setCardBackgroundColor(android.graphics.Color.parseColor(bg))
            strokeColor = android.graphics.Color.parseColor(accent)
            strokeWidth = (1 * dp).toInt()
            isClickable = true; isFocusable = true
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }
        val iconFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
        }
        val iconBg = View(this).apply {
            layoutParams = FrameLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
            background = ContextCompat.getDrawable(context, R.drawable.bg_org_icon)?.mutate()
            (background as? GradientDrawable)?.setColor(android.graphics.Color.parseColor(accent))
        }
        val iconView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(iconRes)
            setColorFilter(android.graphics.Color.WHITE)
        }
        iconFrame.addView(iconBg); iconFrame.addView(iconView)
        row.addView(iconFrame)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * dp).toInt()
            }
        }
        texts.addView(android.widget.TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.parseColor(accent))
            textSize = 13f
            paint.isFakeBoldText = true
        })
        texts.addView(android.widget.TextView(this).apply {
            text = subtitle
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(texts)

        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt())
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(android.graphics.Color.parseColor(accent))
        })

        card.addView(row)
        card.setOnClickListener { onClick() }
        binding.alertsContainer.addView(card)
    }
}
