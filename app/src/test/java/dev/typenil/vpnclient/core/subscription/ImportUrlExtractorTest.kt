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
    fun `unencoded ampersands inside url param are preserved`() {
        // The defect Uri.getQueryParameter has: a subscription URL with a
        // raw '&' must not be cut at the query boundary.
        val link = "clash://install-config?url=https://example.com/sub?a=1&b=2"
        assertEquals(
            "https://example.com/sub?a=1&b=2",
            ImportUrlExtractor.extract(view, link, null),
        )
    }

    @Test
    fun `literal plus inside raw url param is preserved`() {
        // URLDecoder.decode would turn '+' into a space — a nested query
        // token like ?token=a+b must survive verbatim.
        val link = "clash://install-config?url=https://h.example.com/s?token=a+b"
        assertEquals(
            "https://h.example.com/s?token=a+b",
            ImportUrlExtractor.extract(view, link, null),
        )
    }

    @Test
    fun `encoded plus inside url param decodes to plus`() {
        val link = "clash://install-config?url=https%3A%2F%2Fh.example.com%2Fs%3Ftoken%3Da%2Bb"
        assertEquals(
            "https://h.example.com/s?token=a+b",
            ImportUrlExtractor.extract(view, link, null),
        )
    }

    @Test
    fun `trailing name param is stripped from unencoded url`() {
        val link = "clash://install-config?url=https://example.com/s?a=1&b=2&name=MySub"
        assertEquals(
            "https://example.com/s?a=1&b=2",
            ImportUrlExtractor.extract(view, link, null),
        )
    }

    @Test
    fun `url param not in first position still resolves`() {
        val link = "sing-box://import-remote-profile?name=x&url=https%3A%2F%2Fexample.com%2Fs"
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
