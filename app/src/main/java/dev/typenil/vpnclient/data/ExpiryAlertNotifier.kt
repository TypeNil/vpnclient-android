package dev.typenil.vpnclient.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.subscription.SubscriptionExpiryNotifier
import dev.typenil.vpnclient.core.vpn.VpnNotification
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "subscription expiring" alert on the high-importance alerts
 * channel. Silent no-op when POST_NOTIFICATIONS is denied — the in-app
 * expiry text on the subscription card still carries the information.
 */
@Singleton
class ExpiryAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : SubscriptionExpiryNotifier {

    override fun notifyExpiring(
        subscriptionId: Long,
        subscriptionName: String,
        expireEpochSeconds: Long,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val expires = DateFormat.getDateInstance().format(Date(expireEpochSeconds * 1000))
        val notification = NotificationCompat.Builder(context, VpnNotification.ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Subscription expiring")
            .setContentText("$subscriptionName expires $expires")
            .setContentIntent(VpnNotification.openPendingIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(
            VpnNotification.EXPIRY_NOTIFICATION_ID_BASE + subscriptionId.toInt(),
            notification,
        )
        return true
    }
}
