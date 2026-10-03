package dev.typenil.vpnclient.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.typenil.vpnclient.BuildConfig
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.ui.theme.AfterglowTokens

@Composable
internal fun AboutSection() {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
        stringResource(R.string.about_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    InfoRow(
        stringResource(R.string.about_version),
        stringResource(R.string.about_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
    )
    InfoRow(stringResource(R.string.about_license), "GPL-3.0")
    InfoRow(stringResource(R.string.common_vpn_core), "sing-box / libbox ${BuildConfig.VPN_CORE_VERSION}")
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.about_core_attribution),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.about_dependencies), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.about_dependency_licenses),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val openFailed = stringResource(R.string.about_link_unavailable)
    visibleAboutLinks().forEach { (label, url) ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(role = Role.Button) {
                    runCatching { uriHandler.openUri(url) }.onFailure {
                        Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
                    }
                }
                .heightIn(min = AfterglowTokens.touchTarget)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            NavChevron()
        }
    }
}
