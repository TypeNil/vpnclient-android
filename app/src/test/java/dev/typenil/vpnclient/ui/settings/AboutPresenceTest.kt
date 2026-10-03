package dev.typenil.vpnclient.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutPresenceTest {
    @Test
    fun `Settings exposes About with build version licensing and optional links`() {
        val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        val settings = File(main, "java/dev/typenil/vpnclient/ui/settings/SettingsScreen.kt").readText()
        assertTrue("Settings must expose About", settings.contains("AboutSection()"))
        val about = File(main, "java/dev/typenil/vpnclient/ui/settings/AboutSection.kt").readText()
        listOf("BuildConfig.VERSION_NAME", "BuildConfig.VERSION_CODE", "BuildConfig.VPN_CORE_VERSION",
            "R.string.about_license", "R.string.about_core_attribution", "R.string.about_dependencies",
            "visibleAboutLinks").forEach { assertTrue("Missing $it", about.contains(it)) }
    }
}
