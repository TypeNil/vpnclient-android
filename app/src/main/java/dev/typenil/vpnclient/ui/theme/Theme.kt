package dev.typenil.vpnclient.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import dev.typenil.vpnclient.core.common.ThemeMode

object AfterglowTheme {
    val colors: AfterglowPalette
        @Composable get() = LocalAfterglowPalette.current
}

private fun scheme(colors: AfterglowPalette, dark: Boolean, personalizedInverse: androidx.compose.ui.graphics.Color) =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.coral,
        onPrimary = colors.onInk,
        secondary = colors.ink,
        onSecondary = colors.paper,
        primaryContainer = colors.errorSurface,
        onPrimaryContainer = colors.ink,
        secondaryContainer = colors.paperSecondary,
        onSecondaryContainer = colors.ink,
        tertiary = colors.jade,
        onTertiary = colors.onInk,
        tertiaryContainer = colors.paperSecondary,
        onTertiaryContainer = colors.ink,
        inversePrimary = personalizedInverse,
        background = colors.paper,
        onBackground = colors.ink,
        surface = colors.paper,
        onSurface = colors.ink,
        surfaceVariant = colors.paperSecondary,
        onSurfaceVariant = colors.muted,
        surfaceContainer = colors.paperSecondary,
        surfaceContainerLow = colors.paper,
        surfaceContainerHigh = colors.surfaceElevated,
        surfaceContainerHighest = colors.surfaceElevated,
        surfaceContainerLowest = colors.paper,
        surfaceBright = colors.paper,
        surfaceDim = colors.paperSecondary,
        surfaceTint = colors.coral,
        outline = colors.border,
        outlineVariant = colors.border,
        error = colors.error,
        onError = colors.onInk,
        errorContainer = colors.errorSurface,
        onErrorContainer = colors.ink,
        inverseSurface = colors.ink,
        inverseOnSurface = colors.paper,
        scrim = androidx.compose.ui.graphics.Color.Black,
    )

@Composable
fun VPNClientTheme(
    themeMode: ThemeMode = ThemeMode.System,
    // Dynamic color is available on Android 12+; when disabled the brand
    // palette wins — it's the app's identity, so it stays the default.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val colors = if (darkTheme) DarkAfterglow else LightAfterglow
    // Dynamic color can personalize the tertiary accent, never the brand or semantic surfaces.
    val adaptive = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null
    CompositionLocalProvider(LocalAfterglowPalette provides colors) {
        MaterialTheme(
            colorScheme = scheme(colors, darkTheme, adaptive?.primary ?: colors.coral),
            typography = Typography,
            shapes = Shapes(small = AfterglowTokens.inputShape, medium = AfterglowTokens.cardShape,
                large = AfterglowTokens.dialogShape),
            content = content,
        )
    }
}
