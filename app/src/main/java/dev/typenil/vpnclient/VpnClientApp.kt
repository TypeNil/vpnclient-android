package dev.typenil.vpnclient

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import dev.typenil.vpnclient.core.vpn.VpnNotification
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.Locale

@HiltAndroidApp
class VpnClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        setupLibbox()
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

    private fun setupLibbox() {
        val baseDir = File(filesDir, "sing-box")
        val workingDir = File(cacheDir, "sing-box")
        val tempDir = File(cacheDir, "sing-box-tmp")
        // The command server binds a unix socket under basePath — it must exist.
        baseDir.mkdirs()
        workingDir.mkdirs()
        tempDir.mkdirs()
        Libbox.setup(
            SetupOptions().also {
                it.basePath = baseDir.absolutePath
                it.workingPath = workingDir.absolutePath
                it.tempPath = tempDir.absolutePath
                // 0 → in-process unix socket, nothing listens on TCP.
                it.commandServerListenPort = 0
                it.crashReportSource = "vpnclient"
                it.logMaxLines = 300
                it.debug = BuildConfig.DEBUG
                it.fixAndroidStack = true
            },
        )
        Libbox.setLocale(Locale.getDefault().toLanguageTag())
        Libbox.prepareCrashSignalHandlers()
    }
}
