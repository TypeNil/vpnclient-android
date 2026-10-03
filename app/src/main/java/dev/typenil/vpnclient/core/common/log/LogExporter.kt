package dev.typenil.vpnclient.core.common.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Shares a redacted snapshot through a memory pipe, never a cache file. */
@Singleton
class LogExporter @Inject constructor(private val connectionManager: ConnectionManager) {
    fun buildShareIntent(context: Context): Intent {
        val text = DiagnosticExport.render(
            SecureLog.ring.snapshot(), connectionManager.coreLogSnapshot(),
            context.getString(R.string.diag_core_log_title),
            context.getString(R.string.diag_core_log_empty),
        )
        val id = DiagnosticProvider.store.publish(text)
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.diagnostics").appendPath(id).build()
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "VPN diagnostics (redacted)")
            clipData = ClipData.newRawUri("Diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
