package dev.typenil.vpnclient.core.vpn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.common.log.SecureLog

/** Narrow seam over the platform notification so the policy is testable on the JVM. */
internal interface NoticeSink {
    /** True when a notice is now visible; false when it could not be shown. */
    fun post(notice: AutoStartNotice): Boolean
    fun cancel()
}

/**
 * Shows one failure notice for a failed automatic start and clears it on the next
 * successful start. Never throws: a notice is a courtesy, the start path must converge.
 */
internal class AutoStartNotifier(private val sink: NoticeSink) {
    fun failed(branch: AutomaticStartBranch, error: VpnError) {
        val notice = autoStartNotice(branch, error) ?: return
        val shown = runCatching { sink.post(notice) }.getOrDefault(false)
        if (!shown) SecureLog.w(TAG, "automatic start notice not shown: ${notice.name}")
    }

    fun started() {
        runCatching { sink.cancel() }
    }

    private companion object {
        const val TAG = "AutoStartNotifier"
    }
}

internal val AUTO_START_TITLE_RES: Int = R.string.notification_autostart_title

@StringRes
internal fun AutoStartNotice.textRes(): Int = when (this) {
    AutoStartNotice.NoServer -> R.string.notification_autostart_no_server
    AutoStartNotice.VpnPermission -> R.string.notification_autostart_vpn_permission
    AutoStartNotice.Unencrypted -> R.string.notification_autostart_unencrypted
    AutoStartNotice.InvalidConfig -> R.string.notification_autostart_invalid_config
    AutoStartNotice.StartFailed -> R.string.notification_autostart_start_failed
}

/** Posts on the existing alerts channel with the shared alert id, so a newer notice replaces the old one. */
internal class AndroidNoticeSink(private val context: Context) : NoticeSink {
    override fun post(notice: AutoStartNotice): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val notification = NotificationCompat.Builder(context, VpnNotification.ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(context.getString(AUTO_START_TITLE_RES))
            .setContentText(context.getString(notice.textRes()))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(VpnNotification.openPendingIntent(context))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(VpnNotification.ALERT_NOTIFICATION_ID, notification)
        return true
    }

    override fun cancel() {
        NotificationManagerCompat.from(context).cancel(VpnNotification.ALERT_NOTIFICATION_ID)
    }
}
