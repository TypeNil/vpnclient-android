package dev.typenil.vpnclient.ui.qrscan

import org.junit.Assert.assertEquals
import org.junit.Test

class QrScanViewModelTest {

    private val viewModel = QrScanViewModel()

    @Test
    fun `bare https qr delivers the url`() {
        assertEquals(
            ScanOutcome.Found("https://example.com/sub"),
            viewModel.onBarcode("https://example.com/sub", nowMs = 1_000),
        )
    }

    @Test
    fun `sing-box deep link qr decodes the url param`() {
        val raw = "sing-box://import-remote-profile?url=https%3A%2F%2Fexample.com%2Fs"
        assertEquals(
            ScanOutcome.Found("https://example.com/s"),
            viewModel.onBarcode(raw, nowMs = 1_000),
        )
    }

    @Test
    fun `non-subscription qr is rejected once then deduped`() {
        val raw = "WIFI:T:nopass;S:net;;"
        assertEquals(ScanOutcome.Rejected, viewModel.onBarcode(raw, nowMs = 1_000))
        assertEquals(ScanOutcome.Ignored, viewModel.onBarcode(raw, nowMs = 2_000))
        assertEquals(ScanOutcome.Ignored, viewModel.onBarcode(raw, nowMs = 3_900))
        // The same payload outside the window is reported again.
        assertEquals(ScanOutcome.Rejected, viewModel.onBarcode(raw, nowMs = 4_100))
    }

    @Test
    fun `different invalid payloads each report`() {
        assertEquals(ScanOutcome.Rejected, viewModel.onBarcode("plain text", nowMs = 1_000))
        assertEquals(ScanOutcome.Rejected, viewModel.onBarcode("other text", nowMs = 1_100))
    }

    @Test
    fun `frames after a found url are ignored`() {
        assertEquals(
            ScanOutcome.Found("https://example.com/sub"),
            viewModel.onBarcode("https://example.com/sub", nowMs = 1_000),
        )
        assertEquals(ScanOutcome.Ignored, viewModel.onBarcode("https://example.com/sub", nowMs = 1_100))
        assertEquals(ScanOutcome.Ignored, viewModel.onBarcode("plain text", nowMs = 1_200))
    }
}
