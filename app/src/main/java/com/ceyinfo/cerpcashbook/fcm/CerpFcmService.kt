package com.ceyinfo.cerpcashbook.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ceyinfo.cerpcashbook.R
import com.ceyinfo.cerpcashbook.data.remote.ApiClient
import com.ceyinfo.cerpcashbook.data.remote.FcmTokenRegisterRequest
import com.ceyinfo.cerpcashbook.ui.advances.CashAdvanceDetailActivity
import com.ceyinfo.cerpcashbook.ui.advances.CashAdvancesTabActivity
import com.ceyinfo.cerpcashbook.ui.hub.ModuleHubActivity
import com.ceyinfo.cerpcashbook.ui.notifications.NotificationsActivity
import com.ceyinfo.cerpcashbook.ui.review.ReviewVouchersActivity
import com.ceyinfo.cerpcashbook.util.SessionManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


class CerpFcmService : FirebaseMessagingService() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "CerpFcmService"
    
        const val CHANNEL_ID = "cerp_cashbook_default_v2"
        const val CHANNEL_NAME = "Cash Book"
        private const val CHANNEL_DESC = "Cash advances, expense vouchers, and approvals"
    }

  
    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated: $token")
        registerToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.i(TAG, "FCM message received — data=$data")

        val title = data["title"] ?: message.notification?.title ?: "CERP Cash Book"
        val body  = data["body"]  ?: message.notification?.body  ?: ""
        val type  = data["type"]
        val refId = data["ref_id"]

        showSystemNotification(title, body, type, refId)
        
        NotificationEvents.signalPush()
    }

    private fun showSystemNotification(
        title: String,
        body: String,
        type: String?,
        refId: String?,
    ) {
        ensureChannel()

        val launchIntent = buildDeepLinkIntent(type, refId).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
          
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Unique ID per notification so multiple don't collapse into one.
        nm.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), builder.build())
    }

    private fun buildDeepLinkIntent(type: String?, refId: String?): Intent {
        return when {
            type?.startsWith("advance_") == true -> Intent(this, CashAdvancesTabActivity::class.java).apply {
                putExtra(
                    CashAdvancesTabActivity.EXTRA_INITIAL_TAB,
                    if (type == "advance_received") CashAdvancesTabActivity.TAB_RECEIVED
                    else CashAdvancesTabActivity.TAB_ISSUES,
                )
                if (!refId.isNullOrBlank()) {
                    // Best-effort: tab activity may use it to surface a detail
                    // sheet; if not, the user still lands on the right list.
                    putExtra("ref_id", refId)
                }
            }
            type?.startsWith("voucher_") == true -> Intent(this, ReviewVouchersActivity::class.java)
            else -> Intent(this, NotificationsActivity::class.java)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 200, 250)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /**
     * Helper used by both `onNewToken` and `LoginActivity` post-login.
     * Silent on failure — the next login or app restart will retry.
     */
    fun registerToken(ctx: Context, token: String) {
        val session = SessionManager(ctx)
        if (!session.isLoggedIn) {
            Log.d(TAG, "Skipping FCM token register — no active session yet")
            return
        }
        ioScope.launch {
            try {
                val resp = ApiClient.getService(ctx)
                    .registerFcmToken(FcmTokenRegisterRequest(token = token))
                if (resp.isSuccessful) {
                    Log.i(TAG, "FCM token registered with backend")
                } else {
                    Log.w(TAG, "FCM token register failed: HTTP ${resp.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "FCM token register threw", e)
            }
        }
    }
}
