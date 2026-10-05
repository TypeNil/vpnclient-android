package dev.typenil.vpnclient.ui.common

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.typenil.vpnclient.R

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
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(stringResource(R.string.battery_hint_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                onDismiss?.let { dismiss ->
                    IconButton(onClick = dismiss, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.battery_hint_dismiss))
                    }
                }
            }
            Text(stringResource(R.string.battery_hint_body), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { unavailable = !openSettings(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.battery_hint_open))
            }
            if (unavailable) Text(stringResource(R.string.battery_hint_unavailable), style = MaterialTheme.typography.bodySmall)
        }
    }
}
