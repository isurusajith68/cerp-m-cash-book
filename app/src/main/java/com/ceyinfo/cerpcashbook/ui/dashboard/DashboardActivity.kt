package com.ceyinfo.cerpcashbook.ui.dashboard

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.databinding.ActivityDashboardBinding
import com.ceyinfo.cerpcashbook.ui.acknowledge.AcknowledgeActivity
import com.ceyinfo.cerpcashbook.ui.buselect.BuSelectActivity
import com.ceyinfo.cerpcashbook.ui.custodians.CustodiansActivity
import com.ceyinfo.cerpcashbook.ui.expense.SubmitExpenseActivity
import com.ceyinfo.cerpcashbook.ui.ledger.LedgerActivity
import com.ceyinfo.cerpcashbook.ui.login.LoginActivity
import com.ceyinfo.cerpcashbook.ui.notifications.NotificationsActivity
import com.ceyinfo.cerpcashbook.ui.review.ReviewVouchersActivity
import com.ceyinfo.cerpcashbook.ui.sendcash.SendCashActivity
import com.ceyinfo.cerpcashbook.ui.settings.SettingsActivity
import com.ceyinfo.cerpcashbook.util.NetworkMonitor
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var session: SessionManager
    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        session = SessionManager(this)
        networkMonitor = NetworkMonitor(this)

        if (!session.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setupUI()
        setupBottomNav()
        observeNetwork()
        loadDashboardStats()

        binding.swipeRefresh.setOnRefreshListener {
            loadDashboardStats()
            binding.swipeRefresh.isRefreshing = false
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
    }

    private fun setupUI() {
        binding.tvBuName.text = session.businessUnitName ?: "No Site"
        binding.tvUserEmail.text = session.email
        binding.tvAvatar.text = session.email?.firstOrNull()?.uppercase() ?: "U"

        // Role badge
        val role = session.cashRole ?: "none"
        val roleBadgeColor = when (role) {
            "clerk" -> R.color.info
            "custodian" -> R.color.warning
            "both" -> R.color.success
            else -> R.color.primary
        }
        val roleText = when (role) {
            "clerk" -> "CLERK"
            "custodian" -> "CUSTODIAN"
            "both" -> "CLERK + CUSTODIAN"
            else -> "NO ROLE"
        }
        binding.tvRoleBadge.text = roleText
        val badgeBg = binding.tvRoleBadge.background as? GradientDrawable
        badgeBg?.setColor(ContextCompat.getColor(this, roleBadgeColor))

        // Build action cards based on role
        buildActionCards(role)

        binding.btnChangeBu.setOnClickListener {
            startActivity(Intent(this, BuSelectActivity::class.java))
        }
    }

    private fun buildActionCards(role: String) {
        binding.row1.removeAllViews()
        binding.row2.removeAllViews()
        binding.row3.removeAllViews()

        when (role) {
            "clerk" -> {
                binding.row1.addView(makeCard("Send Cash", "Disburse to custodian", R.drawable.ic_send_cash, "#DBEAFE", "#2563EB"))
                binding.row1.addView(makeCard("Review Expenses", "Approve or reject", R.drawable.ic_review, "#D1FAE5", "#059669"))
                binding.row2.addView(makeCard("Custodians", "View staff & balances", R.drawable.ic_people, "#EDE9FE", "#7C3AED"))
                binding.row2.addView(makeCard("Ledger", "Transaction history", R.drawable.ic_ledger, "#FEF3C7", "#D97706"))
            }
            "custodian" -> {
                binding.row1.addView(makeCard("Acknowledge", "Confirm cash received", R.drawable.ic_check_circle, "#DBEAFE", "#2563EB"))
                binding.row1.addView(makeCard("New Expense", "Submit a voucher", R.drawable.ic_receipt, "#D1FAE5", "#059669"))
                binding.row2.addView(makeCard("My Expenses", "View expense history", R.drawable.ic_review, "#EDE9FE", "#7C3AED"))
                binding.row2.addView(makeCard("Ledger", "Transaction history", R.drawable.ic_ledger, "#FEF3C7", "#D97706"))
            }
            "both" -> {
                binding.row1.addView(makeCard("Send Cash", "Disburse to custodian", R.drawable.ic_send_cash, "#DBEAFE", "#2563EB"))
                binding.row1.addView(makeCard("Acknowledge", "Confirm cash received", R.drawable.ic_check_circle, "#D1FAE5", "#059669"))
                binding.row2.addView(makeCard("New Expense", "Submit a voucher", R.drawable.ic_receipt, "#EDE9FE", "#7C3AED"))
                binding.row2.addView(makeCard("Review Expenses", "Approve or reject", R.drawable.ic_review, "#FEF3C7", "#D97706"))
                binding.row3.visibility = View.VISIBLE
                binding.row3.addView(makeCard("Custodians", "View staff & balances", R.drawable.ic_people, "#FCE7F3", "#DB2777"))
                binding.row3.addView(makeCard("Ledger", "Transaction history", R.drawable.ic_ledger, "#F0F9FF", "#0369A1"))
            }
            else -> {
                val tv = TextView(this).apply {
                    text = "No cash role assigned for this site."
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 14f
                    setPadding(0, 48, 0, 48)
                    gravity = Gravity.CENTER
                }
                binding.row1.addView(tv)
            }
        }
    }

    private fun makeCard(title: String, subtitle: String, iconRes: Int, bgTint: String, iconTint: String): MaterialCardView {
        val dp = resources.displayMetrics.density

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * dp).toInt()
                marginStart = (6 * dp).toInt()
            }
            radius = 16 * dp
            cardElevation = 0f
            strokeColor = android.graphics.Color.parseColor("#E8EEF4")
            strokeWidth = (1 * dp).toInt()
            isClickable = true
            isFocusable = true
            foreground = ContextCompat.getDrawable(context, android.R.attr.selectableItemBackground.let {
                val a = context.obtainStyledAttributes(intArrayOf(it))
                val d = a.getDrawable(0)
                a.recycle()
                0 // just return 0, we'll set it differently
            }.let { android.R.attr.selectableItemBackground }.let {
                val a = context.obtainStyledAttributes(intArrayOf(it))
                val res = a.getResourceId(0, 0)
                a.recycle()
                res
            })
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt())
        }

        // Icon container
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

        // Title
        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        content.addView(tvTitle)

        // Subtitle
        val tvSub = TextView(this).apply {
            text = subtitle
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }
        content.addView(tvSub)

        card.addView(content)

        // Click handler
        card.setOnClickListener {
            when (title) {
                "Send Cash" -> startActivity(Intent(this, SendCashActivity::class.java))
                "Review Expenses" -> startActivity(Intent(this, ReviewVouchersActivity::class.java))
                "Custodians" -> startActivity(Intent(this, CustodiansActivity::class.java))
                "Ledger" -> startActivity(Intent(this, LedgerActivity::class.java))
                "Acknowledge" -> startActivity(Intent(this, AcknowledgeActivity::class.java))
                "New Expense" -> startActivity(Intent(this, SubmitExpenseActivity::class.java))
                "My Expenses" -> startActivity(Intent(this, ReviewVouchersActivity::class.java))
            }
        }

        return card
    }

    private fun setupBottomNav() {
        binding.navHome.setOnClickListener { /* already on home */ }
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

    private fun observeNetwork() {
        networkMonitor.isOnline.observe(this) { online ->
            binding.tvStatus.text = if (online) "Online" else "Offline"

            val dotBg = binding.dotStatus.background as? GradientDrawable
                ?: GradientDrawable().apply { shape = GradientDrawable.OVAL }
            dotBg.setColor(ContextCompat.getColor(this,
                if (online) R.color.success else R.color.error
            ))
            binding.dotStatus.background = dotBg
        }
    }

    private fun loadDashboardStats() {
        val siteId = session.businessUnitId ?: return

        // Reset alerts so pull-to-refresh doesn't stack duplicate cards
        binding.alertsContainer.removeAllViews()
        binding.alertsContainer.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@DashboardActivity)
                val response = api.getDashboardStats(siteId)
                if (response.isSuccessful && response.body()?.success == true) {
                    val stats = response.body()!!.data!!

                    binding.statsRow.visibility = View.VISIBLE

                    val role = session.cashRole ?: "none"
                    when (role) {
                        "clerk" -> {
                            binding.tvStat1Value.text = stats.advances.pendingAdvances.toString()
                            binding.tvStat1Label.text = "Pending Advances"
                            binding.tvStat2Value.text = stats.vouchers.pendingReview.toString()
                            binding.tvStat2Label.text = "Awaiting Review"
                        }
                        "custodian" -> {
                            binding.tvStat1Value.text = stats.advances.pendingAdvances.toString()
                            binding.tvStat1Label.text = "Cash to Acknowledge"
                            binding.tvStat2Value.text = stats.vouchers.approvedVouchers.toString()
                            binding.tvStat2Label.text = "Approved Expenses"
                        }
                        else -> {
                            binding.tvStat1Value.text = stats.advances.totalAdvances.toString()
                            binding.tvStat1Label.text = "Total Advances"
                            binding.tvStat2Value.text = stats.vouchers.totalVouchers.toString()
                            binding.tvStat2Label.text = "Total Vouchers"
                        }
                    }

                    // Notification badge
                    if (stats.unreadNotifications > 0) {
                        binding.tvNotifBadge.text = stats.unreadNotifications.toString()
                        binding.tvNotifBadge.visibility = View.VISIBLE
                    }

                    // Role-wise alert: pending approvals (clerk/both)
                    if ((role == "clerk" || role == "both") && stats.vouchers.pendingReview > 0) {
                        showPendingApprovalsAlert(stats.vouchers.pendingReview)
                    }

                    // Balance for custodian — also drives the low-balance alert
                    if (role == "custodian" || role == "both") {
                        loadBalance(siteId)
                    }
                }
            } catch (e: Exception) {
                // Silent fail — stats are supplementary
            }
        }
    }

    private fun loadBalance(siteId: String) {
        val userId = session.userId ?: return

        lifecycleScope.launch {
            try {
                val api = ApiClient.getService(this@DashboardActivity)
                val response = api.getCustodianBalance(userId, siteId)
                if (response.isSuccessful && response.body()?.success == true) {
                    val bal = response.body()!!.data!!
                    binding.tvBalance.text = "Balance: LKR ${String.format("%,.2f", bal.balance)}"
                    binding.tvBalance.visibility = View.VISIBLE

                    if (bal.balance < LOW_BALANCE_THRESHOLD) {
                        showLowBalanceAlert(bal.balance)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun showPendingApprovalsAlert(count: Int) {
        val title = getString(R.string.alert_pending_approvals)
        val subtitle = "$count voucher${if (count == 1) "" else "s"} awaiting review — ${getString(R.string.alert_tap_to_review)}"
        addAlertCard(
            title = title,
            subtitle = subtitle,
            iconRes = R.drawable.ic_review,
            accentColor = "#2563EB",
            bgColor = "#EFF6FF",
        ) {
            startActivity(Intent(this, ReviewVouchersActivity::class.java))
        }
    }

    private fun showLowBalanceAlert(balance: Double) {
        val title = getString(R.string.alert_low_balance)
        val subtitle = "LKR ${String.format("%,.2f", balance)} remaining — ${getString(R.string.alert_top_up_needed)}"
        addAlertCard(
            title = title,
            subtitle = subtitle,
            iconRes = R.drawable.ic_receipt,
            accentColor = "#D97706",
            bgColor = "#FFFBEB",
        ) {
            // No dedicated top-up flow yet — tap routes to custodian's own ledger for context
            startActivity(Intent(this, LedgerActivity::class.java))
        }
    }

    private fun addAlertCard(
        title: String,
        subtitle: String,
        iconRes: Int,
        accentColor: String,
        bgColor: String,
        onClick: () -> Unit,
    ) {
        val dp = resources.displayMetrics.density
        binding.alertsContainer.visibility = View.VISIBLE

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (binding.alertsContainer.childCount > 0) topMargin = (10 * dp).toInt()
            }
            radius = 14 * dp
            cardElevation = 0f
            setCardBackgroundColor(android.graphics.Color.parseColor(bgColor))
            strokeColor = android.graphics.Color.parseColor(accentColor)
            strokeWidth = (1 * dp).toInt()
            isClickable = true
            isFocusable = true
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
            (background as? GradientDrawable)?.setColor(android.graphics.Color.parseColor(accentColor))
        }
        val iconView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(iconRes)
            setColorFilter(android.graphics.Color.WHITE)
        }
        iconFrame.addView(iconBg)
        iconFrame.addView(iconView)
        row.addView(iconFrame)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * dp).toInt()
            }
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.parseColor(accentColor))
            textSize = 13f
            paint.isFakeBoldText = true
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(texts)

        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt())
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(android.graphics.Color.parseColor(accentColor))
        })

        card.addView(row)
        card.setOnClickListener { onClick() }
        binding.alertsContainer.addView(card)
    }

    companion object {
        private const val LOW_BALANCE_THRESHOLD = 10_000.0
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.unregister()
    }
}
