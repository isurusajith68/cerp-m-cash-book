package com.ceyinfo.cerpcashbook.ui.common

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.ui.hub.ModuleHubActivity
import com.ceyinfo.cerpcashbook.ui.ledger.LedgerActivity
import com.ceyinfo.cerpcashbook.ui.notifications.NotificationsActivity
import com.ceyinfo.cerpcashbook.ui.settings.SettingsActivity

/**
 * Paints the green pill behind the active tab and wires the four nav items
 * to their target activities. Pass the include layout's root View and the
 * key of the tab that this Activity represents.
 *
 * Navigation uses `SINGLE_TOP | CLEAR_TOP` so re-tapping a tab doesn't pile
 * up duplicate Activities on the back stack — instead the existing one is
 * brought to front (or recreated via onNewIntent).
 */
object BottomNav {

    enum class Tab { HOME, ALERTS, LEDGER, SETTINGS }

    /**
     * @param root the root LinearLayout returned by the `<include>` (or its `binding.bottomBar`)
     * @param activity the host Activity (used to start the target Activities)
     * @param active which tab to render as selected on this screen
     */
    fun bind(root: View, activity: Activity, active: Tab) {
        val ctx = activity
        val activeColor = ContextCompat.getColor(ctx, R.color.primary)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.text_secondary)

        // Apply system-nav-bar inset uniformly across every host screen so the
        // bar always sits the same distance above the gesture/nav bar. The
        // include's intrinsic paddingBottom (12dp) is the floor; the inset is
        // added on top when the window extends behind the system bars.
        val basePaddingBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = basePaddingBottom + nav.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        data class Item(
            val tab: Tab,
            val pillId: Int,
            val iconId: Int,
            val labelId: Int,
            val rowId: Int,
            val target: Class<out Activity>?,
        )

        val items = listOf(
            Item(Tab.HOME, R.id.pill_home, R.id.icon_home, R.id.label_home,
                R.id.nav_home, ModuleHubActivity::class.java),
            Item(Tab.ALERTS, R.id.pill_alerts, R.id.icon_alerts, R.id.label_alerts,
                R.id.nav_notifications, NotificationsActivity::class.java),
            Item(Tab.LEDGER, R.id.pill_ledger, R.id.icon_ledger, R.id.label_ledger,
                R.id.nav_ledger, LedgerActivity::class.java),
            Item(Tab.SETTINGS, R.id.pill_settings, R.id.icon_settings, R.id.label_settings,
                R.id.nav_settings, SettingsActivity::class.java),
        )

        for (item in items) {
            val pill = root.findViewById<FrameLayout>(item.pillId)
            val icon = root.findViewById<ImageView>(item.iconId)
            val label = root.findViewById<TextView>(item.labelId)
            val row = root.findViewById<View>(item.rowId)

            val isActive = item.tab == active
            pill.setBackgroundResource(if (isActive) R.drawable.bg_nav_pill else 0)
            icon.setColorFilter(if (isActive) activeColor else inactiveColor)
            label.setTextColor(if (isActive) activeColor else inactiveColor)
            label.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

            row.setOnClickListener {
                if (isActive) return@setOnClickListener
                val target = item.target ?: return@setOnClickListener
                val intent = Intent(activity, target).addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                activity.startActivity(intent)
                activity.overridePendingTransition(0, 0)
                if (active != Tab.HOME) activity.finish()
            }
        }
    }
}
