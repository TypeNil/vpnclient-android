package dev.typenil.vpnclient.core.engine.singbox

import android.content.Context
import dev.typenil.vpnclient.BuildConfig
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.Locale

/**
 * Process-wide libbox initialization. Call once from Application.onCreate.
 * Keeps all `io.nekohasekai.libbox` references inside `core.engine.singbox`.
 */
object LibboxRuntime {

    fun init(context: Context) {
        val baseDir = File(context.filesDir, "sing-box")
        val workingDir = File(context.cacheDir, "sing-box")
        val tempDir = File(context.cacheDir, "sing-box-tmp")
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
