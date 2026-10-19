package dev.typenil.vpnclient.core.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUrlTest {
    private val base = "https://sub.example.test/path/token"

    @Test fun `trim scheme host case default port fragment and trailing slash are not identity`() {
        for (same in listOf(
            "  $base  ", "HTTPS://SUB.Example.TEST/path/token", "https://sub.example.test:443/path/token",
            "$base/", "$base///", "$base#Some%20name", "$base/#x",
        )) assertTrue(same, sameSubscriptionUrl(base, same))
    }

    @Test fun `path case query userinfo port and scheme are identity`() {
        for (other in listOf(
            "https://sub.example.test/Path/token", "https://sub.example.test/path/token?x=1",
            "https://user@sub.example.test/path/token", "https://sub.example.test:8443/path/token",
            "http://sub.example.test/path/token", "https://other.example.test/path/token",
        )) assertFalse(other, sameSubscriptionUrl(base, other))
    }

    @Test fun `query is kept verbatim and root path collapses`() {
        assertEquals("https://sub.example.test/p?t=AbC", normalizedSubscriptionUrl("https://SUB.example.test/p/?t=AbC#f"))
        assertFalse(sameSubscriptionUrl("https://sub.example.test/p?t=abc", "https://sub.example.test/p?t=ABC"))
        assertEquals("https://sub.example.test", normalizedSubscriptionUrl("https://sub.example.test/"))
        assertEquals("https://sub.example.test", normalizedSubscriptionUrl("https://SUB.example.test"))
    }

    @Test fun `non http text compares by trimmed text`() {
        assertEquals("not a url", normalizedSubscriptionUrl("  not a url "))
    }
}
