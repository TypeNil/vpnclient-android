package dev.typenil.vpnclient.ui.connections

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.core.engine.ConnectionInfo
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatRelativeTime
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live connections through the tunnel. Everything rendered here is user
 * traffic data — destinations stay on screen, never in logs.
 */
@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Connections", style = MaterialTheme.typography.titleLarge)
        }

        when {
            // Only a live session has a tracker — anything else would be a
            // stale or invented list.
            !ui.connected -> EmptyState("Connect to see live connections")
            ui.connections.isEmpty() -> EmptyState("No active connections")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(ui.connections, key = { it.id }) { connection ->
                    ConnectionRow(
                        connection = connection,
                        onClose = { viewModel.closeConnection(connection.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConnectionRow(
    connection: ConnectionInfo,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val packageName = connection.packages.firstOrNull()
    // Label lookup is a binder call — off the main thread, remembered per
    // package name while the row stays composed.
    val appLabel by produceState<String?>(null, packageName) {
        value = if (packageName == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                loadAppLabel(context.packageManager, packageName)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = connection.destination,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaLine(connection, appLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Text(
                text = "↓ ${formatBytes(connection.downlinkTotalBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "↑ ${formatBytes(connection.uplinkTotalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close connection",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "App · tcp · tls · 5 min ago" — label falls back to the package name.
 *  Internal for JVM tests. */
internal fun metaLine(connection: ConnectionInfo, appLabel: String?): String {
    val parts = mutableListOf<String>()
    connection.packages.firstOrNull()?.let { parts += appLabel ?: it }
    if (connection.network.isNotEmpty()) parts += connection.network
    if (connection.protocol.isNotEmpty()) parts += connection.protocol
    if (connection.createdAtMs > 0) {
        parts += formatRelativeTime(Instant.ofEpochMilli(connection.createdAtMs))
    }
    return parts.joinToString(" · ")
}

private fun loadAppLabel(pm: PackageManager, packageName: String): String? =
    runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        pm.getApplicationLabel(info).toString()
    }.getOrNull()
