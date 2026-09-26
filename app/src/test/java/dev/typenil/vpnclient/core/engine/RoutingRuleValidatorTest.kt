package dev.typenil.vpnclient.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM coverage of the single pattern gate the editor and the DB
 *  read path share. */
class RoutingRuleValidatorTest {

    // ---- DOMAIN --------------------------------------------------------

    @Test
    fun `domain accepts literal and wildcard suffix`() {
        assertEquals("example.com", RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "example.com"))
        assertEquals(
            "example.com",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "  EXAMPLE.COM  "),
        )
        assertEquals(
            "example.com",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "*.example.com"),
        )
        assertEquals(
            "a-b.example.co.uk",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "a-b.example.co.uk"),
        )
    }

    @Test
    fun `domain rejects non-hostnames`() {
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, ""))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "   "))
        // No dot — a bare label is not a suffix matcher.
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "hostname"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "localhost"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "example"))
        // Underscores and other symbols are not host chars.
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "my_site.example.com"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "-bad.example.com"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "bad-.example.com"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "example..com"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "."))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "*"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.DOMAIN, "*.example"))
    }

    // ---- IP_CIDR -------------------------------------------------------

    @Test
    fun `ipv4 bare host gets slash32`() {
        assertEquals("1.2.3.4/32", RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3.4"))
        assertEquals(
            "1.2.3.4/32",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, " 1.2.3.4 "),
        )
    }

    @Test
    fun `ipv4 with valid prefix`() {
        assertEquals("10.0.0.0/8", RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "10.0.0.0/8"))
        assertEquals(
            "10.0.0.0/8",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "10.0.0.0/08"),
        )
        assertEquals(
            "0.0.0.0/0",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "0.0.0.0/0"),
        )
        assertEquals(
            "192.168.1.1/32",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "192.168.1.1/32"),
        )
    }

    @Test
    fun `ipv4 rejects malformed octets`() {
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "256.1.1.1"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "256.1.1.1/24"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3.4.5"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3.4/33"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3.4/-1"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3.4/+8"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3.4/ 8"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3.4/8x"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3.4/"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "/8"))
        // Leading-zero octets are rejected: "01" is ambiguous (octal vs
        // decimal) across resolvers.
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "01.2.3.4"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "010.1.1.1"))
        // Not an IP at all — must never hit DNS.
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "example.com/24"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "example.com"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "999.1.1.1"))
        // A v6 prefix on a v4 host is a family mismatch.
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1.2.3.4/128"))
    }

    @Test
    fun `ipv6 bare host gets slash128`() {
        assertEquals("::/128", RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "::"))
        assertEquals(
            "2001:db8::1/128",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "2001:db8::1"),
        )
    }

    @Test
    fun `ipv6 with valid prefix`() {
        assertEquals("::/0", RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "::/0"))
        assertEquals(
            "2001:db8::/32",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "2001:db8::/32"),
        )
        assertEquals(
            "fe80::/10",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "fe80::/10"),
        )
    }

    @Test
    fun `ipv6 rejects malformed literals`() {
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "2001:db8::/129"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "2001:zz::/32"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "2001:db8:::1"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, ":2001:db8::1"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "::::"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "1:2"))
        // Zone index is not a plain literal.
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "fe80::1%eth0"))
        // IPv4-mapped forms parse as Inet4Address — not accepted as v6.
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "::ffff:1.2.3.4"))
    }

    @Test
    fun `ipv6 accepts any prefix up to 128`() {
        // A small prefix is not a family mismatch on v6 — only v4 rejects
        // prefixes above 32.
        assertEquals(
            "2001:db8::/24",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "2001:db8::/24"),
        )
        assertEquals(
            "2001:db8::1/128",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.IP_CIDR, "2001:db8::1/128"),
        )
    }

    // ---- PORT ----------------------------------------------------------

    @Test
    fun `port accepts single and ordered range`() {
        assertEquals("443", RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "443"))
        assertEquals(
            "443",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, " 443 "),
        )
        assertEquals(
            "8000:8080",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "8000:8080"),
        )
        assertEquals(
            "80:8000",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "80:8000"),
        )
        assertEquals(
            "1",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "1"),
        )
        assertEquals(
            "65535",
            RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "65535"),
        )
    }

    @Test
    fun `port rejects unordered ranges and junk`() {
        // The original bug: inverted range passed validation.
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "8000:80"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "0"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "65536"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "abc"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, ":"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "80:80:80"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "80:"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, ":80"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "80:0"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "80:65536"))
        // Leading zeros are unambiguous for ports (decimal all the way
        // through) — accepted, unlike IPv4 octets where "01" is octal.
        assertEquals("080", RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "080"))
        // "+443" parses as 443 via toIntOrNull in the old code — rejected now.
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "+443"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, "-1"))
        assertNull(RoutingRuleValidator.validatePattern(RoutingRule.Kind.PORT, ""))
    }
}
