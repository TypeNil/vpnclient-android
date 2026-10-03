package dev.typenil.vpnclient.core.common.log

import java.util.UUID

internal object DiagnosticExport {
    fun render(app: List<String>, core: List<String>, title: String, empty: String): String = buildString {
        appendLine("VPN Client diagnostics — sensitive data is redacted.")
        appendLine("Recent app and core logs only; no raw proxy configurations.")
        appendLine("generated: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        appendLine()
        appendLine("App log")
        app.forEach { appendLine(it) }
        appendLine()
        appendLine(title)
        if (core.isEmpty()) appendLine(empty) else core.forEach { appendLine(it) }
    }
}

/** Only the latest redacted payload lives here. A fresh URI means an old URI
 * grant cannot read a later export. Process death deliberately expires it. */
internal class DiagnosticShareStore {
    private var id: String? = null
    private var bytes: ByteArray? = null

    @Synchronized fun publish(text: String): String {
        val next = UUID.randomUUID().toString()
        bytes = text.toByteArray(Charsets.UTF_8)
        id = next
        return next
    }

    @Synchronized fun read(request: String): ByteArray? = if (request == id) bytes?.copyOf() else null
}
