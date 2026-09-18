package dev.typenil.vpnclient.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `uuid is redacted`() {
        val out = Redactor.redact("node 550e8400-e29b-41d4-a716-446655440000 failed")
        assertFalse(out.contains("550e8400"))
        assertTrue(out.contains("<uuid>"))
    }

    @Test
    fun `userinfo credentials are redacted`() {
        val out = Redactor.redact("vless://secret-uuid@example.com:443?security=reality")
        assertFalse(out.contains("secret-uuid"))
        assertTrue(out.contains("<redacted>@"))
    }

    @Test
    fun `sensitive query params are redacted`() {
        val out = Redactor.redact("https://panel/api/sub/abc?token=t0k3n&other=keep&pbk=key123")
        assertFalse(out.contains("t0k3n"))
        assertFalse(out.contains("key123"))
        assertTrue(out.contains("other=keep"))
    }

    @Test
    fun `bearer tokens are redacted`() {
        val out = Redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `long opaque tokens are redacted`() {
        val out = Redactor.redact("short_id=abc12345 pbk=aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789")
        assertFalse(out.contains("aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789"))
        // short values survive
        assertTrue(out.contains("abc12345"))
    }

    @Test
    fun `null and empty are safe`() {
        assertEquals("", Redactor.redact(null))
        assertEquals("", Redactor.redact(""))
    }

    @Test
    fun `urlForDisplay keeps scheme host port only`() {
        val out = Redactor.urlForDisplay("https://user:pass@panel.example.com:8443/api/sub/secret123?x=1")
        assertEquals("https://<redacted>@panel.example.com:8443/…", out)
    }

    @Test
    fun `urlForDisplay on plain url`() {
        assertEquals("https://panel.example.com", Redactor.urlForDisplay("https://panel.example.com"))
    }
}
