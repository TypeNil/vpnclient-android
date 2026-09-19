package dev.typenil.vpnclient.core.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportUrlExtractorTest {

    private val send = "android.intent.action.SEND"
    private val view = "android.intent.action.VIEW"

    @Test
    fun `view on bare https url`() {
        assertEquals(
            "https://example.com/sub",
            ImportUrlExtractor.extract(view, "https://example.com/sub", null),
        )
    }

    @Test
    fun `send shared url text`() {
        assertEquals(
            "https://example.com/sub?token=x",
            ImportUrlExtractor.extract(send, null, "https://example.com/sub?token=x"),
        )
    }

    @Test
    fun `sing-box import-remote-profile decodes url param`() {
        val link = "sing-box://import-remote-profile?url=" +
            "https%3A%2F%2Fexample.com%2Fsub%3Fa%3D1%26b%3D2&name=x"
        assertEquals(
            "https://example.com/sub?a=1&b=2",
            ImportUrlExtractor.extract(view, link, null),
        )
    }

    @Test
    fun `clash install-config decodes url param`() {
        val link = "clash://install-config?url=https%3A%2F%2Fexample.com%2Fs"
        assertEquals(
            "https://example.com/s",
            ImportUrlExtractor.extract(view, link, null),
        )
    }

    @Test
    fun `unknown scheme rejected`() {
        assertNull(ImportUrlExtractor.extract(view, "vless://abc@1.2.3.4:443", null))
        assertNull(ImportUrlExtractor.extract(view, "ftp://example.com/sub", null))
    }

    @Test
    fun `deep link without http url param rejected`() {
        assertNull(
            ImportUrlExtractor.extract(view, "sing-box://import-remote-profile?url=javascript%3A%2F%2Fx", null),
        )
        assertNull(ImportUrlExtractor.extract(view, "clash://install-config", null))
    }

    @Test
    fun `empty input rejected`() {
        assertNull(ImportUrlExtractor.extract(null, null, null))
        assertNull(ImportUrlExtractor.extract(view, "  ", null))
    }
}
