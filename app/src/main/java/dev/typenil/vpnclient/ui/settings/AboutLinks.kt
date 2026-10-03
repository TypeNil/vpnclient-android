package dev.typenil.vpnclient.ui.settings

import dev.typenil.vpnclient.R

/** Fill these when publishing. Empty URLs have no visible or clickable row. */
internal object AboutUrls {
    const val SOURCE_CODE = ""
    const val PRIVACY_POLICY = ""
}

internal fun visibleAboutLinks(
    sourceUrl: String = AboutUrls.SOURCE_CODE,
    privacyUrl: String = AboutUrls.PRIVACY_POLICY,
): List<Pair<Int, String>> = listOf(
    R.string.about_source_code to sourceUrl.trim(),
    R.string.about_privacy_policy to privacyUrl.trim(),
).filter { (_, url) -> url.isNotEmpty() }
