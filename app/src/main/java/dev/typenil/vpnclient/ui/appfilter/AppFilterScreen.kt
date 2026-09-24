package dev.typenil.vpnclient.ui.appfilter

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.core.vpn.PerAppMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppFilterScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppFilterViewModel = hiltViewModel(),
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
            Text("Per-app VPN", style = MaterialTheme.typography.titleLarge)
        }

        Column(Modifier.selectableGroup()) {
            ModeRow(
                title = "All apps",
                subtitle = "Route everything through the tunnel",
                selected = ui.mode == PerAppMode.ALL,
                onClick = { viewModel.setMode(PerAppMode.ALL) },
            )
            ModeRow(
                title = "Only selected apps",
                subtitle = "Checked apps use the tunnel, the rest bypass it",
                selected = ui.mode == PerAppMode.INCLUDE,
                onClick = { viewModel.setMode(PerAppMode.INCLUDE) },
            )
            ModeRow(
                title = "All except selected",
                subtitle = "Checked apps bypass the tunnel",
                selected = ui.mode == PerAppMode.EXCLUDE,
                onClick = { viewModel.setMode(PerAppMode.EXCLUDE) },
            )
        }

        if (ui.mode != PerAppMode.ALL) {
            // The plan is baked into the TUN fd — a live tunnel rebuilds it
            // in place, which briefly interrupts traffic.
            Text(
                "Changes reconnect the tunnel briefly",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (ui.mode == PerAppMode.INCLUDE && ui.selected.isEmpty()) {
                // An empty include-list degenerates to self-only — nothing
                // else rides the tunnel.
                Text(
                    "No apps selected — only this app uses the tunnel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            OutlinedTextField(
                value = ui.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (ui.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.filtered, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            checked = app.packageName in ui.selected,
                            onToggle = { viewModel.toggle(app.packageName) },
                            loadIcon = viewModel::loadIcon,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    checked: Boolean,
    onToggle: () -> Unit,
    loadIcon: (String) -> android.graphics.drawable.Drawable?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = onToggle, role = Role.Checkbox)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon decode is a binder call + bitmap work — off the main thread.
        val icon by produceState<ImageBitmap?>(null, app.packageName) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    loadIcon(app.packageName)?.toBitmap()?.asImageBitmap()
                }.getOrNull()
            }
        }
        if (icon != null) {
            Image(
                bitmap = icon!!,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        } else {
            Box(Modifier.size(36.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
