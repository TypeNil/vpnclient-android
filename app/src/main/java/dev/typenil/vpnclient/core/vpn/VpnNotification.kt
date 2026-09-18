package dev.typenil.vpnclient.core.vpn

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.typenil.vpnclient.MainActivity
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.model.NodeSummary

/** Foreground-service notification for the tunnel. */
class VpnNotification(private val service: Service) {

    companion object {
        const val CHANNEL_ID = "vpn"
        const val NOTIFICATION_ID = 1
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
            .setShowWhen(false)

    fun build(
        title: String,
        text: String,
        node: NodeSummary?,
        showDisconnect: Boolean,
    ): Notification {
        val builder = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
        if (node != null) {
            builder.setSubText(node.name)
        }
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
}
