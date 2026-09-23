package dev.typenil.vpnclient.ui.qrscan

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor
import javax.inject.Inject

/** What the screen should do with a decoded barcode. */
sealed interface ScanOutcome {
    /** The QR carried an import link — deliver it and leave. */
    data class Found(val url: String) : ScanOutcome

    /** Decoded content that isn't a subscription link — tell the user once. */
    data object Rejected : ScanOutcome

    /** Duplicate frames or post-result noise — stay quiet, keep scanning. */
    data object Ignored : ScanOutcome
}

@HiltViewModel
class QrScanViewModel @Inject constructor() : ViewModel() {

    private var consumed = false
    private var lastReject: Pair<String, Long>? = null

    /**
     * Feed a raw QR payload through the same funnel as deep links and text
     * shares. The payload may be a secret-bearing URL — it is never logged.
     */
    fun onBarcode(raw: String, nowMs: Long = SystemClock.elapsedRealtime()): ScanOutcome {
        if (consumed) return ScanOutcome.Ignored
        val import = ImportUrlExtractor.extract(action = null, data = raw, extraText = null)
        if (import != null) {
            consumed = true
            return ScanOutcome.Found(import.url)
        }
        // Holding an unrelated QR steady re-decodes every frame — report it
        // once per payload per window instead of spamming.
        val last = lastReject
        if (last != null && last.first == raw && nowMs - last.second < REJECT_WINDOW_MS) {
            return ScanOutcome.Ignored
        }
        lastReject = raw to nowMs
        return ScanOutcome.Rejected
    }

    private companion object {
        const val REJECT_WINDOW_MS = 3_000L
    }
}
