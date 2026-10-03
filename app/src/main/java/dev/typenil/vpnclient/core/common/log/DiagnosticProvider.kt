package dev.typenil.vpnclient.core.common.log

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Read-only, URI-granted memory pipe. No cache file or filesystem paths. */
class DiagnosticProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/plain"

    private fun payload(uri: Uri): ByteArray {
        if (uri.pathSegments.size != 1) throw FileNotFoundException("Unknown diagnostic export")
        return store.read(uri.lastPathSegment.orEmpty())
            ?: throw FileNotFoundException("Diagnostic export expired")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only diagnostic export")
        val bytes = payload(uri)
        return openPipeHelper(uri, "text/plain", null, bytes) { output, _, _, _, data ->
            // A recipient closing early is normal, not an app/core failure.
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(data) } }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val bytes = payload(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> "vpn-diagnostics.txt"
                    OpenableColumns.SIZE -> bytes.size
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    internal companion object {
        val store = DiagnosticShareStore()
    }
}
