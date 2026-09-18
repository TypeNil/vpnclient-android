package dev.typenil.vpnclient

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.singbox.LibboxRuntime
import dev.typenil.vpnclient.core.vpn.VpnNotification

@HiltAndroidApp
class VpnClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SecureLog.debugEnabled = BuildConfig.DEBUG
        createNotificationChannels()
        LibboxRuntime.init(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                VpnNotification.CHANNEL_ID,
                getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
