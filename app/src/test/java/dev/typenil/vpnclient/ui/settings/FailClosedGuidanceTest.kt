package dev.typenil.vpnclient.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailClosedGuidanceTest {
    @Test
    fun `both locales warn about reconnect and rebuild leaks and name both system options`() {
        val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        for ((locale, terms) in listOf(
            "values" to listOf("reconnect", "rebuild", "leak", "Always-on VPN", "Block connections without VPN"),
            "values-ru" to listOf("переподключ", "перестро", "утеч", "Постоянный VPN", "Блокировать соединения без VPN"),
        )) {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val entries = factory.newDocumentBuilder().parse(File(res, "$locale/strings.xml"))
                .getElementsByTagName("string")
            val strings = (0 until entries.length).associate { index ->
                val node = entries.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            val warning = strings["settings_always_on_warning"].orEmpty()
            terms.forEach { assertTrue("$locale missing $it", warning.contains(it)) }
            listOf("settings_always_on", "settings_always_on_status", "settings_always_on_unavailable")
                .forEach { assertFalse(strings[it].orEmpty().contains("kill switch", ignoreCase = true)) }
        }
        val source = File("src/main/java/dev/typenil/vpnclient/ui/settings/SettingsScreen.kt")
            .takeIf { it.isFile } ?: File("app/src/main/java/dev/typenil/vpnclient/ui/settings/SettingsScreen.kt")
        assertTrue(source.readText().contains("stringResource(R.string.settings_always_on_warning)"))
    }
}
