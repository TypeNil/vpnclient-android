package dev.typenil.vpnclient.core.vpn

import dev.typenil.vpnclient.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartNotifierTest {
    private class FakeSink(var shown: Boolean = true, var boom: Boolean = false) : NoticeSink {
        val posted = mutableListOf<AutoStartNotice>()
        var cancels = 0
        override fun post(notice: AutoStartNotice): Boolean {
            if (boom) error("sink failure")
            posted += notice
            return shown
        }
        override fun cancel() { cancels++ }
    }

    @Test fun `failed start posts the kind for its error`() {
        val sink = FakeSink()
        val notifier = AutoStartNotifier(sink)
        notifier.failed(AutomaticStartBranch.AlwaysOn, VpnError.UnencryptedTransport)
        notifier.failed(AutomaticStartBranch.Restore, VpnError.NoNodeSelected)
        assertEquals(listOf(AutoStartNotice.Unencrypted, AutoStartNotice.NoServer), sink.posted)
    }

    @Test fun `stop branch posts nothing`() {
        val sink = FakeSink()
        AutoStartNotifier(sink).failed(AutomaticStartBranch.Stop, VpnError.NoNodeSelected)
        assertTrue(sink.posted.isEmpty())
    }

    @Test fun `a sink that cannot show or throws never propagates`() {
        AutoStartNotifier(FakeSink(shown = false))
            .failed(AutomaticStartBranch.AlwaysOn, VpnError.PermissionDenied)
        AutoStartNotifier(FakeSink(boom = true))
            .failed(AutomaticStartBranch.AlwaysOn, VpnError.PermissionDenied)
    }

    @Test fun `a successful start clears the notice`() {
        val sink = FakeSink()
        AutoStartNotifier(sink).started()
        assertEquals(1, sink.cancels)
    }

    // ---- resource contract: every kind has a localized, argument-free text ----

    private fun strings(locale: String): Map<String, String> {
        val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(File(res, "$locale/strings.xml"))
            .getElementsByTagName("string")
        return (0 until nodes.length).associate {
            nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
        }
    }

    private fun nameOf(id: Int): String =
        R.string::class.java.fields.single { it.getInt(null) == id }.name

    @Test fun `every notice kind has a distinct en and ru text without arguments`() {
        val en = strings("values")
        val ru = strings("values-ru")
        val keys = AutoStartNotice.entries.map { nameOf(it.textRes()) } + nameOf(AUTO_START_TITLE_RES)
        assertEquals(keys.size, keys.toSet().size)
        for (key in keys) {
            val e = en[key].orEmpty()
            val r = ru[key].orEmpty()
            assertTrue("en $key", e.isNotBlank())
            assertTrue("ru $key", r.isNotBlank())
            assertFalse("en $key has args", e.contains('%') || e.contains('$'))
            assertFalse("ru $key has args", r.contains('%') || r.contains('$'))
            assertTrue("ru $key not Cyrillic", r.any { it in 'а'..'я' || it in 'А'..'Я' })
            assertFalse("en $key is Cyrillic", e.any { it in 'а'..'я' || it in 'А'..'Я' })
        }
    }
}
