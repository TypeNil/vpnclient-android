package dev.typenil.vpnclient.ui.subscriptions

import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.identificationErrorToken
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class SubscriptionFailureMessageTest {
    @Test fun `typed failures persist only fixed tokens`() {
        for (reason in SubscriptionError.HwidRefusal.entries) for (off in listOf(false, true)) {
            val token = SubscriptionError.DeviceIdentificationRejected(reason, off).identificationErrorToken()!!
            assertTrue(token in setOf("sub:hwid:max", "sub:hwid:required", "sub:hwid:disabled", "sub:hwid:ambiguous"))
            assertNotNull(subscriptionFailureMessage(token, false))
        }
        assertEquals("sub:hwid:ambiguous", SubscriptionError.DeviceLimitReached("untrusted detail").identificationErrorToken())
        assertEquals("sub:http:404", SubscriptionError.Http(404, "untrusted host").identificationErrorToken())
    }

    @Test fun `legacy and new errors share localized rendering without false limit claims`() {
        assertEquals(R.string.subs_hwid_limit, subscriptionFailureMessage("sub:hwid:max", false)!!.resId)
        assertEquals(R.string.subs_hwid_limit_kept, subscriptionFailureMessage("sub:hwid:max", true)!!.resId)
        for (old in listOf("device limit / HWID rejected", "device identification rejected", "sub:hwid:ambiguous")) {
            assertEquals(R.string.subs_hwid_ambiguous, subscriptionFailureMessage(old, true)!!.resId)
        }
        for (token in listOf("HTTP 404", "sub:http:404")) {
            val message = subscriptionFailureMessage(token, true)!!
            assertEquals(R.string.subs_http_error, message.resId)
            assertEquals(listOf(404), message.args)
            assertFalse(message.fallback.contains("limit"))
        }
        assertNull(subscriptionFailureMessage("HTTP untrusted", false))
    }

    @Test fun `en ru resources cover every outcome and kept sentence is conditional`() {
        val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        for ((locale, kept) in listOf("values" to "Saved servers were kept", "values-ru" to "Сохранённые серверы оставлены")) {
            val nodes = factory.newDocumentBuilder().parse(File(res, "$locale/strings.xml")).getElementsByTagName("string")
            val s = (0 until nodes.length).associate {
                nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
            }
            for (key in listOf("limit", "limit_kept", "required", "disabled_refusal", "ambiguous")) {
                assertTrue(s["subs_hwid_$key"]!!.isNotBlank())
            }
            assertFalse(s["subs_hwid_limit"]!!.contains(kept))
            assertTrue(s["subs_hwid_limit_kept"]!!.contains(kept))
            assertTrue(s["subs_http_error"]!!.contains("%1\$d"))
            if (locale == "values-ru") assertTrue(s["subs_hwid_ambiguous"]!!.contains("не подтверждён"))
        }
    }
}
