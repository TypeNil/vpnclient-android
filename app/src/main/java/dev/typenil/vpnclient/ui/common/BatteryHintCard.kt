package dev.typenil.vpnclient.ui.common

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.ui.theme.AfterglowNotice
import dev.typenil.vpnclient.ui.theme.NoticeTone

/** General system screen only; explicit resolution avoids a chooser or OEM deep-link. */
internal fun openBatterySettings(context: Context): Boolean = try {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val info = intent.resolveActivityInfo(context.packageManager, PackageManager.MATCH_DEFAULT_ONLY)
    if (info == null || !info.exported ||
        info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 ||
        info.name.endsWith("ResolverActivity") || info.name.endsWith("ChooserActivity")) false
    else {
        intent.component = ComponentName(info.packageName, info.name)
        context.startActivity(intent)
        true
    }
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
internal fun BatteryHintCard(onDismiss: (() -> Unit)? = null, openSettings: ((Context) -> Boolean) = ::openBatterySettings) {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    AfterglowNotice(
        NoticeTone.Info,
        stringResource(R.string.battery_hint_body),
        title = stringResource(R.string.battery_hint_title),
        detail = if (unavailable) stringResource(R.string.battery_hint_unavailable) else null,
        actionLabel = stringResource(R.string.battery_hint_open),
        onAction = { unavailable = !openSettings(context) },
        dismissLabel = stringResource(R.string.battery_hint_dismiss),
        onDismiss = onDismiss,
    )
}