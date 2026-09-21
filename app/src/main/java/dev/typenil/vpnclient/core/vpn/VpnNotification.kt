package dev.typenil.vpnclient.core.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.typenil.vpnclient.MainActivity
import dev.typenil.vpnclient.R

/** Foreground-service notification for the tunnel. */
class VpnNotification(private val service: Service) {

    companion object {
        const val CHANNEL_ID = "vpn"
        /** One-shot alerts (e.g. restart-guard trip) — high importance. */
        const val ALERT_CHANNEL_ID = "vpn_alerts"
        const val NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
        private const val REQUEST_OPEN = 0
        private const val REQUEST_DISCONNECT = 1

        fun openPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openPendingIntent(service))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Don't defer the foreground-service notification — the VPN
            // status should appear immediately when the tunnel starts.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(false)

    fun build(
        title: String,
        text: String,
        showDisconnect: Boolean,
    ): Notification {
        val builder = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
        if (showDisconnect) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    null,
                    service.getString(R.string.action_disconnect),
                    PendingIntent.getService(
                        service,
                        REQUEST_DISCONNECT,
                        ClientVpnService.disconnectIntent(service),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
        }
        return builder.build()
    }

    /**
     * One-shot user-visible alert — posted through NotificationManager, not
     * the foreground slot, so it works while the service is stopping.
     */
    fun postAlert(title: String, text: String) {
        val notification = NotificationCompat.Builder(service, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPendingIntent(service))
            .setAutoCancel(true)
            .build()
        service.getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, notification)
    }
}
