package dev.typenil.vpnclient.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Brand surfaces remain stable even when Material dynamic color is enabled. */
@Immutable
data class AfterglowPalette(
    val paper: Color,
    val paperSecondary: Color,
    val surfaceElevated: Color,
    val ink: Color,
    val muted: Color,
    val border: Color,
    val coral: Color,
    val coralLight: Color,
    val jade: Color,
    val amber: Color,
    val error: Color,
    val errorSurface: Color,
    val onInk: Color,
    val actionSurface: Color,
    val onActionSurface: Color,
    val offlineHero: Color,
    val connectedHero: Color,
    val transitionalHero: Color,
    val errorHero: Color,
)

val LightAfterglow =
    AfterglowPalette(
        paper = Color(0xFFFAF4ED),
        paperSecondary = Color(0xFFF2E9E0),
        surfaceElevated = Color(0xFFFFF8F1),
        ink = Color(0xFF272528),
        muted = Color(0xFF776D6C),
        border = Color(0xFFD7C9C0),
        coral = Color(0xFFD84E49),
        coralLight = Color(0xFFFF8580),
        jade = Color(0xFF5DC9A7),
        amber = Color(0xFFF4C27B),
        error = Color(0xFFAE303C),
        errorSurface = Color(0xFFF8DFD9),
        onInk = Color(0xFFFFFAF6),
        actionSurface = Color(0xFF272528),
        onActionSurface = Color(0xFFFFFAF6),
        offlineHero = Color(0xFFBC3E3E),
        connectedHero = Color(0xFF193E39),
        transitionalHero = Color(0xFF915044),
        errorHero = Color(0xFF942E3B),
    )

val DarkAfterglow =
    AfterglowPalette(
        paper = Color(0xFF171719),
        paperSecondary = Color(0xFF252427),
        surfaceElevated = Color(0xFF302D30),
        ink = Color(0xFFF7F0E8),
        muted = Color(0xFFBEB4B0),
        border = Color(0xFF4F4545),
        coral = Color(0xFFFF8580),
        coralLight = Color(0xFFFFA49C),
        jade = Color(0xFF5DC9A7),
        amber = Color(0xFFF4C27B),
        error = Color(0xFFFF9CA5),
        errorSurface = Color(0xFF4C292D),
        onInk = Color(0xFF171719),
        actionSurface = Color(0xFF302D30),
        onActionSurface = Color(0xFFFFFAF6),
        offlineHero = Color(0xFFBC3E3E),
        connectedHero = Color(0xFF193E39),
        transitionalHero = Color(0xFF915044),
        errorHero = Color(0xFF942E3B),
    )

val LocalAfterglowPalette = compositionLocalOf { LightAfterglow }
