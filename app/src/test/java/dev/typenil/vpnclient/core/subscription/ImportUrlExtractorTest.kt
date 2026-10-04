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
            ImportUrlExtractor.extract(view, "https://example.com/sub", null)?.url,
        )
    }

    @Test
    fun `send shared url text`() {
        assertEquals(
            "https://example.com/sub?token=x",
            ImportUrlExtractor.extract(send, null, "https://example.com/sub?token=x")?.url,
        )
    }

    @Test
    fun `sing-box import-remote-profile decodes url param`() {
        val link = "sing-box://import-remote-profile?url=" +
            "https%3A%2F%2Fexample.com%2Fsub%3Fa%3D1%26b%3D2&name=x"
        assertEquals(
            "https://example.com/sub?a=1&b=2",
            ImportUrlExtractor.extract(view, link, null)?.url,
        )
    }

    @Test
    fun `clash install-config decodes url param`() {
        val link = "clash://install-config?url=https%3A%2F%2Fexample.com%2Fs"
        assertEquals(
            "https://example.com/s",
            ImportUrlExtractor.extract(view, link, null)?.url,
        )
    }

    @Test
    fun `unencoded ampersands inside url param are preserved`() {
        // The defect Uri.getQueryParameter has: a subscription URL with a
        // raw '&' must not be cut at the query boundary.
        val link = "clash://install-config?url=https://example.com/sub?a=1&b=2"
        assertEquals(
            "https://example.com/sub?a=1&b=2",
            ImportUrlExtractor.extract(view, link, null)?.url,
        )
    }

    @Test
    fun `literal plus inside raw url param is preserved`() {
        // URLDecoder.decode would turn '+' into a space — a nested query
        // token like ?token=a+b must survive verbatim.
        val link = "clash://install-config?url=https://h.example.com/s?token=a+b"
        assertEquals(
            "https://h.example.com/s?token=a+b",
            ImportUrlExtractor.extract(view, link, null)?.url,
        )
    }

    @Test
    fun `encoded plus inside url param decodes to plus`() {
        val link = "clash://install-config?url=https%3A%2F%2Fh.example.com%2Fs%3Ftoken%3Da%2Bb"
        assertEquals(
            "https://h.example.com/s?token=a+b",
            ImportUrlExtractor.extract(view, link, null)?.url,
        )
    }

    @Test
    fun `trailing name param is stripped from unencoded url`() {
        val link = "clash://install-config?url=https://example.com/s?a=1&b=2&name=MySub"
        assertEquals(
            "https://example.com/s?a=1&b=2",
            ImportUrlExtractor.extract(view, link, null)?.url,
        )
    }

    @Test
    fun `url param not in first position still resolves`() {
        val link = "sing-box://import-remote-profile?name=x&url=https%3A%2F%2Fexample.com%2Fs"
        assertEquals(
            "https://example.com/s",
            ImportUrlExtractor.extract(view, link, null)?.url,
        )
    }

    @Test
    fun `name param is decoded and returned`() {
        val link = "sing-box://import-remote-profile?url=" +
            "https%3A%2F%2Fexample.com%2Fsub&name=My%20Sub"
        val import = ImportUrlExtractor.extract(view, link, null)
        assertEquals("https://example.com/sub", import?.url)
        assertEquals("My Sub", import?.name)
    }

    @Test
    fun `absent name yields null`() {
        val link = "clash://install-config?url=https%3A%2F%2Fexample.com%2Fs"
        val import = ImportUrlExtractor.extract(view, link, null)
        assertEquals("https://example.com/s", import?.url)
        assertNull(import?.name)
    }

    @Test
    fun `unencoded ampersand inside url plus trailing name still splits`() {
        // The url carries a raw '&' — only the LAST &name= boundary splits.
        val link = "sing-box://import-remote-profile?url=" +
            "https://example.com/sub?a=1&b=2&name=Edge"
        val import = ImportUrlExtractor.extract(view, link, null)
        assertEquals("https://example.com/sub?a=1&b=2", import?.url)
        assertEquals("Edge", import?.name)
    }

    @Test
    fun `bare http url carries no name`() {
        val import = ImportUrlExtractor.extract(view, "https://example.com/sub", null)
        assertNull(import?.name)
    }

    @Test
    fun `unknown scheme rejected`() {
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
    fun `node schemes use the same candidate for paste and share`() {
        val schemes = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic", "anytls", "wireguard", "wg", "socks", "socks5")
        for (scheme in schemes) {
            // Classification only; payload validation belongs to the repository.
            val raw = "$scheme://synthetic"
            val paste = ImportUrlExtractor.extract(null, "  $raw  ", null)
            assertEquals(raw, paste?.url)
            assertEquals(ImportUrlExtractor.Kind.ShareLink, paste?.kind)
            assertEquals(paste, ImportUrlExtractor.extract(send, null, raw))
            assertEquals(raw, ImportUrlExtractor.extract(view, raw.uppercase(), null)?.url?.lowercase())
        }
        assertEquals(
            ImportUrlExtractor.Kind.Subscription,
            ImportUrlExtractor.extract(null, "https://example.com/sub", null)?.kind,
        )
        assertNull(ImportUrlExtractor.extract(null, "plain text", null))
    }

    @Test
    fun `empty input rejected`() {
        assertNull(ImportUrlExtractor.extract(null, null, null))
        assertNull(ImportUrlExtractor.extract(view, "  ", null))
    }

    @Test
    fun `hostile or non-http inputs never yield a candidate`() {
        val inner = listOf(
            "file%3A%2F%2F%2Fdata%2Fx", "content%3A%2F%2Fx%2Fy", "javascript%3Aalert(1)",
            "ftp%3A%2F%2Fexample.invalid%2Fs", "%2F%2Fexample.invalid%2Fs", "",
            // double-encoded: one decode leaves "https%3A%2F%2F…", not a URL
            "https%253A%252F%252Fexample.invalid%252Fs",
        )
        for (scheme in listOf("sing-box://import-remote-profile", "clash://install-config", "clashmeta://install-config")) {
            for (value in inner) {
                assertNull("$scheme url=$value", ImportUrlExtractor.extract(view, "$scheme?url=$value", null))
            }
        }
        for (raw in listOf("file:///data/x", "content://x/y", "javascript:alert(1)", "data:text/plain,x", "intent://x#Intent;end")) {
            assertNull(raw, ImportUrlExtractor.extract(view, raw, null))
            assertNull(raw, ImportUrlExtractor.extract(send, null, raw))
        }
        // Shared prose is not a link: nothing is guessed out of free text.
        assertNull(ImportUrlExtractor.extract(send, null, "look https://example.invalid/s now"))
    }

    @Test
    fun `scheme and host case do not matter and userinfo or fragment pass through unmodified`() {
        val nested = "https%3A%2F%2Fu%3Ap%40example.invalid%2Fs%3Ft%3D1%23frag"
        assertEquals(
            "https://u:p@example.invalid/s?t=1#frag",
            ImportUrlExtractor.extract(view, "SING-BOX://import-remote-profile?url=$nested", null)?.url,
        )
        assertEquals(
            "HTTPS://example.invalid/s",
            ImportUrlExtractor.extract(view, "HTTPS://example.invalid/s", null)?.url,
        )
    }

    @Test
    fun `oversized input is rejected`() {
        val huge = "https://example.invalid/" + "a".repeat(8192)
        assertNull(ImportUrlExtractor.extract(view, huge, null))
        assertNull(ImportUrlExtractor.extract(view, "sing-box://import-remote-profile?url=$huge", null))
        assertNull(ImportUrlExtractor.extract(send, null, "vless://" + "a".repeat(8192)))
        // a long but sane URL still passes
        val ok = "https://example.invalid/" + "a".repeat(2000)
        assertEquals(ok, ImportUrlExtractor.extract(view, ok, null)?.url)
    }
}
