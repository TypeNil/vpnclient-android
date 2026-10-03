package dev.typenil.vpnclient.ui.settings

import dev.typenil.vpnclient.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutLinksTest {
    @Test
    fun `empty configured URLs hide both rows`() {
        assertTrue(visibleAboutLinks().isEmpty())
        assertTrue(visibleAboutLinks("", " \n ").isEmpty())
    }

    @Test
    fun `filled source URL shows only source row`() {
        assertEquals(listOf(R.string.about_source_code to "https://source.example/project"),
            visibleAboutLinks("https://source.example/project", ""))
    }

    @Test
    fun `filled privacy URL shows only privacy row`() {
        assertEquals(listOf(R.string.about_privacy_policy to "https://privacy.example/policy"),
            visibleAboutLinks("", "https://privacy.example/policy"))
    }

    @Test
    fun `both filled URLs show both rows in order`() {
        assertEquals(listOf(R.string.about_source_code to "https://source.example/project",
            R.string.about_privacy_policy to "https://privacy.example/policy"),
            visibleAboutLinks(" https://source.example/project ", "https://privacy.example/policy"))
    }
}
