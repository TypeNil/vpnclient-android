package dev.typenil.vpnclient.core.common.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the [LogRing] snapshot to a private file and hands back a
 * FileProvider share intent. The file lives under `cacheDir/diagnostics` —
 * readable only through the granted URI and auto-cleaned by the system.
 *
 * Contents are the same redacted lines SecureLog emits; the header repeats
 * that so a recipient doesn't mistake the export for a full logcat dump.
 */
@Singleton
class LogExporter
    @Inject
    constructor() {
        /**
         * Build an `ACTION_SEND` intent for the current snapshot, or null when
         * nothing is buffered. Caller launches `Intent.createChooser`.
         */
        fun buildShareIntent(context: Context): Intent? {
            val lines = SecureLog.ring.snapshot()
            if (lines.isEmpty()) return null

            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            // Stale exports would linger in cache until the system reclaims it —
            // delete before writing so only the latest snapshot is shared.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, EXPORT_FILE)
            file.writeText(
                buildString {
                    appendLine(HEADER_1)
                    appendLine(HEADER_2)
                    appendLine("generated: " + DATE_FORMAT.format(Date()))
                    appendLine()
                    lines.forEach { appendLine(it) }
                },
            )

            val uri = FileProvider.getUriForFile(context, authority(context), file)
            return Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, EXPORT_SUBJECT)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        private fun authority(context: Context): String = "${context.packageName}.fileprovider"

        companion object {
            private const val EXPORT_DIR = "diagnostics"
            private const val EXPORT_FILE = "vpn-diagnostics.txt"
            private const val EXPORT_SUBJECT = "VPN diagnostics (redacted)"
            private const val HEADER_1 =
                "VPN Client diagnostics — all sensitive data is redacted."
            private const val HEADER_2 =
                "Contains recent app log lines only; no subscription URLs, node ids or credentials."
            private val DATE_FORMAT =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        }
    }
