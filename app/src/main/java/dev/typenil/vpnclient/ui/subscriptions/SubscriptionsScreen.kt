package dev.typenil.vpnclient.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatDate
import dev.typenil.vpnclient.ui.common.formatRelativeTime
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Host-only display of a subscription URL — the URL itself may carry
 * credentials in userinfo/path and must never be rendered.
 */
private fun redactedHost(url: String): String =
    runCatching { url.toHttpUrl().host }.getOrNull() ?: "hidden"

@Composable
fun SubscriptionsScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    importUrl: String? = null,
    onImportConsumed: () -> Unit = {},
    onScanQr: () -> Unit = {},
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var importPrefill by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SubscriptionProfile?>(null) }

    // Deep-link/share funnel: open the add dialog prefilled — the user still
    // confirms; nothing is imported silently.
    LaunchedEffect(importUrl) {
        if (importUrl != null) {
            importPrefill = importUrl
            showAddDialog = true
            onImportConsumed()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (ui.profiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No subscriptions yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = ui.profiles,
                    key = { it.id },
                ) { profile ->
                    SubscriptionCard(
                        profile = profile,
                        nodeCount = ui.nodeCounts[profile.id] ?: 0,
                        refreshing = profile.id in ui.refreshingIds,
                        onRefresh = { viewModel.refresh(profile.id) },
                        onDelete = { pendingDelete = profile },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add subscription")
        }
    }

    if (showAddDialog) {
        // Key on the prefill so a second deep link refreshes an open dialog.
        key(importPrefill) {
            AddSubscriptionDialog(
                initialUrl = importPrefill.orEmpty(),
                onScanQr = onScanQr,
                onDismiss = {
                    showAddDialog = false
                    importPrefill = null
                },
                onConfirm = { url, name, allowInsecure ->
                    showAddDialog = false
                    importPrefill = null
                    viewModel.add(url, name, allowInsecure)
                },
            )
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete subscription?") },
            text = { Text("\"${profile.name}\" and its servers will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.remove(profile.id)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SubscriptionCard(
    profile: SubscriptionProfile,
    nodeCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = profile.name.ifBlank { "Subscription" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = redactedHost(profile.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(12.dp)
                            .size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "$nodeCount nodes · updated ${formatRelativeTime(profile.lastUpdatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            profile.userInfo?.let { info ->
                Spacer(Modifier.height(2.dp))
                val usage = if (info.totalBytes > 0) {
                    "Used ${formatBytes(info.usedBytes)} of ${formatBytes(info.totalBytes)}"
                } else {
                    "Used ${formatBytes(info.usedBytes)}"
                }
                val expiry = info.expireEpochSeconds
                    ?.let { " · expires ${formatDate(it)}" }
                    .orEmpty()
                Text(
                    text = usage + expiry,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            profile.lastError?.let { error ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String?, allowInsecure: Boolean) -> Unit,
    onScanQr: () -> Unit,
    initialUrl: String = "",
) {
    // All saveable: navigating to the QR scanner disposes this composition,
    // and a typed name or the cleartext opt-in must survive the round-trip.
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var name by rememberSaveable { mutableStateOf("") }
    var allowInsecure by rememberSaveable { mutableStateOf(false) }
    // In-dialog validation — a rejected http add would otherwise lose the URL.
    val isHttp = url.trim().startsWith("http://", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add subscription") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Subscription URL") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = onScanQr) {
                            Icon(
                                painterResource(R.drawable.ic_qr_scanner),
                                contentDescription = "Scan QR code",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isHttp && !allowInsecure) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Plain HTTP — enable the option below to allow it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = allowInsecure,
                            role = Role.Checkbox,
                            onValueChange = { allowInsecure = it },
                        ),
                ) {
                    Checkbox(
                        checked = allowInsecure,
                        onCheckedChange = null,
                    )
                    Text(
                        text = "Allow insecure HTTP (not recommended)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim(), name.trim().ifEmpty { null }, allowInsecure) },
                enabled = url.isNotBlank() && (!isHttp || allowInsecure),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
