package dev.typenil.vpnclient.ui.common

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Reads the real resource XML (UTF-8): en/ru parity and Russian plural forms, JVM-only. */
class ResourceParityTest {
    private class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun load(dir: String): Res {
        val base = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(base, "$dir/strings.xml"))
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> if (e.getAttribute("translatable") != "false") strings[e.getAttribute("name")] = e.textContent
                "plurals" -> {
                    val items = linkedMapOf<String, String>()
                    val kids = e.getElementsByTagName("item")
                    for (j in 0 until kids.length) {
                        val k = kids.item(j) as Element
                        items[k.getAttribute("quantity")] = k.textContent
                    }
                    plurals[e.getAttribute("name")] = items
                }
            }
        }
        return Res(strings, plurals)
    }

    private val en = load("values")
    private val ru = load("values-ru")
    private fun placeholders(s: String) = Regex("""%\d+\$[sd]""").findAll(s).map { it.value }.toSortedSet()

    @Test
    fun `string and plural keys match in en and ru`() {
        assertEquals("strings only in one language", en.strings.keys - ru.strings.keys, ru.strings.keys - en.strings.keys)
        assertEquals("plurals", en.plurals.keys, ru.plurals.keys)
    }

    @Test
    fun `placeholders match between en and ru`() {
        val bad = en.strings.filter { (k, v) -> ru.strings[k]?.let { placeholders(it) != placeholders(v) } == true }.keys
        assertTrue("placeholder mismatch: $bad", bad.isEmpty())
    }

    @Test
    fun `plural forms are complete per language and keep placeholders`() {
        for ((name, items) in en.plurals) {
            assertEquals("en $name", setOf("one", "other"), items.keys)
            assertEquals("ru $name", setOf("one", "few", "many", "other"), ru.plurals.getValue(name).keys)
            val want = placeholders(items.getValue("other"))
            (items.values + ru.plurals.getValue(name).values).forEach { assertEquals("$name: $it", want, placeholders(it)) }
        }
    }

    // CLDR ru integer rule — what Android's ICU applies at runtime.
    private fun ruCategory(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "one"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
        else -> "many"
    }

    private fun ruText(plural: String, n: Int) = ru.plurals.getValue(plural).getValue(ruCategory(n)).replace("%1\$d", n.toString())

    @Test
    fun `ru relative minutes decline correctly`() {
        val expected = mapOf(
            0 to "0 минут назад", 1 to "1 минуту назад", 2 to "2 минуты назад", 5 to "5 минут назад",
            11 to "11 минут назад", 21 to "21 минуту назад", 22 to "22 минуты назад", 25 to "25 минут назад",
        )
        expected.forEach { (n, text) -> assertEquals(text, ruText("relative_minutes_ago", n)) }
    }

    @Test
    fun `ru hours days and counters decline correctly`() {
        assertEquals("1 час назад", ruText("relative_hours_ago", 1))
        assertEquals("3 часа назад", ruText("relative_hours_ago", 3))
        assertEquals("11 часов назад", ruText("relative_hours_ago", 11))
        assertEquals("21 час назад", ruText("relative_hours_ago", 21))
        assertEquals("1 день назад", ruText("relative_days_ago", 1))
        assertEquals("2 дня назад", ruText("relative_days_ago", 2))
        assertEquals("5 дней назад", ruText("relative_days_ago", 5))
        assertEquals("Через VPN: 21 приложение", ruText("home_per_app_include", 21))
        assertEquals("Без VPN: 22 приложения", ruText("home_per_app_exclude", 22))
        assertEquals("25 серверов", ruText("subs_card_servers", 25))
    }

    @Test
    fun `ru byte units are not the English ones`() {
        listOf("b", "kb", "mb", "gb", "tb").forEach {
            val k = "format_unit_$it"
            assertTrue("$k not localized", ru.strings.getValue(k) != en.strings.getValue(k))
        }
        assertEquals("%1\$s/с", ru.strings["format_rate"])
    }
}
