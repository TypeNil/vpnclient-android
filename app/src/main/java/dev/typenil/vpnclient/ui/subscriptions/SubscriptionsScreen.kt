package dev.typenil.vpnclient.ui.subscriptions

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor.ExtractedImport
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatDate
import dev.typenil.vpnclient.ui.common.formatRelativeTime
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Host-only display of a subscription URL — the URL itself may carry
 * credentials in userinfo/path and must never be rendered.
 */
@Composable
private fun redactedHost(url: String): String =
    runCatching { url.toHttpUrl().host }.getOrNull()
        ?: stringResource(R.string.subs_redacted_url)

@Composable
fun SubscriptionsScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    import: ExtractedImport? = null,
    onImportConsumed: () -> Unit = {},
    onScanQr: () -> Unit = {},
    /** Cross-tab "open the add dialog" signal (e.g. the Servers empty
     *  state). One-shot: consumed via [onAddDialogSignalConsumed]. */
    openAddDialog: Boolean = false,
    onAddDialogSignalConsumed: () -> Unit = {},
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    // Dialog fields are hoisted, not keyed to the prefill: a QR result must
    // fill the field in place — key()ing on it would dispose and recreate
    // the whole dialog a frame after the restored copy appeared.
    var dialogUrl by rememberSaveable { mutableStateOf("") }
    var dialogName by rememberSaveable { mutableStateOf("") }
    var dialogAllowInsecure by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SubscriptionProfile?>(null) }
    // The profile id whose detail sheet is open — the sheet reads live
    // state via `detail`, so a refresh that lands while it's open updates
    // in place instead of showing a stale snapshot.
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val detail = ui.profiles.firstOrNull { it.id == detailId }

    // Deep-link/share funnel: open the add dialog prefilled — the user still
    // confirms; nothing is imported silently.
    LaunchedEffect(import) {
        if (import != null) {
            dialogUrl = import.url
            dialogName = import.name.orEmpty()
            showAddDialog = true
            onImportConsumed()
        }
    }

    // Cross-tab open signal (Servers empty state): same dialog, unprefilled.
    LaunchedEffect(openAddDialog) {
        if (openAddDialog) {
            showAddDialog = true
            onAddDialogSignalConsumed()
        }
    }

    // Durable message: held in uiState until shown, so a failure that lands
    // while this destination isn't composed still surfaces on return.
    LaunchedEffect(ui.pendingMessage?.id) {
        ui.pendingMessage?.let { message ->
            snackbarHostState.showSnackbar(message.body.render(context))
            viewModel.acknowledgeMessage(message.id)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (ui.profiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.subs_empty),
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
                        // The manual "Manual servers" row is local-only: it
                        // can never be refreshed, and deleting it is refused
                        // in the repository — hide both affordances.
                        manual = SubscriptionRepository.isManualSubscription(profile.url),
                        onClick = { detailId = profile.id },
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
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.subs_add_cd),
            )
        }
    }

    if (showAddDialog) {
        AddSubscriptionDialog(
            url = dialogUrl,
            onUrlChange = { dialogUrl = it },
            name = dialogName,
            onNameChange = { dialogName = it },
            allowInsecure = dialogAllowInsecure,
            onAllowInsecureChange = { dialogAllowInsecure = it },
            onScanQr = onScanQr,
            onDismiss = {
                showAddDialog = false
                dialogUrl = ""
                dialogName = ""
                dialogAllowInsecure = false
            },
            onConfirm = { url, name, allowInsecure ->
                showAddDialog = false
                dialogUrl = ""
                dialogName = ""
                dialogAllowInsecure = false
                viewModel.add(url, name, allowInsecure)
            },
        )
    }

    detail?.let { profile ->
        SubscriptionDetailSheet(
            profile = profile,
            nodeCount = ui.nodeCounts[profile.id] ?: 0,
            manualNodes = ui.manualNodes,
            refreshing = profile.id in ui.refreshingIds,
            onDismiss = { detailId = null },
            onToggle = { enabled -> viewModel.setEnabled(profile.id, enabled) },
            onRefresh = { viewModel.refresh(profile.id) },
            onRename = { name -> viewModel.rename(profile.id, name) },
            onEditUrl = { url -> viewModel.editUrl(profile.id, url) },
            onRemoveNode = viewModel::removeManualNode,
            onDelete = {
                detailId = null
                pendingDelete = profile
            },
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.subs_delete_confirm_title)) },
            text = { Text(stringResource(R.string.subs_delete_confirm_text, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.remove(profile.id)
                    },
                ) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SubscriptionCard(
    profile: SubscriptionProfile,
    nodeCount: Int,
    refreshing: Boolean,
    manual: Boolean,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // Disabled is a real state, rendered honestly: dimmed container +
        // a "disabled" label — never a fake enabled look.
        colors = if (profile.enabled) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
                    .copy(alpha = 0.55f),
            )
        },
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = profile.name.ifBlank { stringResource(R.string.common_subscription_fallback) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // The sentinel URL isn't a fetchable address — label
                        // the row instead of running it through redactedHost
                        // (which would render "hidden").
                        text =
                            if (manual) {
                                stringResource(R.string.subs_manual_hint)
                            } else {
                                redactedHost(profile.url)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!manual) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(12.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.common_refresh),
                            )
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.subs_card_meta,
                    nodeCount,
                    formatRelativeTime(profile.lastUpdatedAt),
                ) +
                    (if (profile.updateAlways) {
                        stringResource(R.string.subs_card_updates_on_launch)
                    } else {
                        ""
                    }) +
                    (if (!profile.enabled) {
                        stringResource(R.string.subs_card_disabled)
                    } else {
                        ""
                    }),
                style = MaterialTheme.typography.bodySmall,
                color = if (profile.enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

            profile.userInfo?.let { info ->
                Spacer(Modifier.height(2.dp))
                val usage = if (info.totalBytes > 0) {
                    stringResource(
                        R.string.subs_usage_of,
                        formatBytes(info.usedBytes),
                        formatBytes(info.totalBytes),
                    )
                } else {
                    stringResource(R.string.subs_usage, formatBytes(info.usedBytes))
                }
                val expiry = info.expireEpochSeconds
                    ?.let { stringResource(R.string.subs_expires, formatDate(it)) }
                    .orEmpty()
                Text(
                    text = usage + expiry,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            profile.announce?.let { announce ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = announce,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            profile.supportUrl?.takeIf { it.startsWith("http") }?.let { url ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.subs_support),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(url) },
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
    url: String,
    onUrlChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    allowInsecure: Boolean,
    onAllowInsecureChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String?, allowInsecure: Boolean) -> Unit,
    onScanQr: () -> Unit,
) {
    // Stateless: fields live in the caller's saveable state so a QR result
    // fills the open dialog in place instead of recreating it.
    val isHttp = url.trim().startsWith("http://", ignoreCase = true)
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subs_add_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        label = { Text(stringResource(R.string.subs_url_label)) },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = onScanQr) {
                                Icon(
                                    painterResource(R.drawable.ic_qr_scanner),
                                    contentDescription = stringResource(R.string.subs_scan_qr_cd),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val text = clipboard.getText()?.text?.trim()
                            if (!text.isNullOrEmpty()) {
                                val parsed = ImportUrlExtractor.extract(null, text, null)
                                if (parsed != null) {
                                    onUrlChange(parsed.url)
                                    if (!parsed.name.isNullOrBlank()) onNameChange(parsed.name)
                                } else {
                                    onUrlChange(text)
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.subs_paste))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.subs_name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isHttp && !allowInsecure) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.subs_http_warning),
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
                            onValueChange = onAllowInsecureChange,
                        ),
                ) {
                    Checkbox(
                        checked = allowInsecure,
                        onCheckedChange = null,
                    )
                    Text(
                        text = stringResource(R.string.subs_allow_insecure),
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
                Text(stringResource(R.string.subs_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Detail/edit sheet for one subscription. Reads the live profile (the
 * caller passes the instance resolved from uiState) so a refresh or
 * rename landing while the sheet is open shows current values.
 *
 * URL editing is not inline-committal: the repository validates the new
 * URL through the full fetch→parse→validate pipeline before committing —
 * a broken URL can never clobber a working subscription.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionDetailSheet(
    profile: SubscriptionProfile,
    nodeCount: Int,
    manualNodes: List<NodeEntity>,
    refreshing: Boolean,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onEditUrl: (String) -> Unit,
    onRemoveNode: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var editingUrl by remember { mutableStateOf(false) }
    var nameField by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var urlField by rememberSaveable(profile.id) { mutableStateOf("") }
    val manual = SubscriptionRepository.isManualSubscription(profile.url)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Scrollable: a long manual-node list (or a big announcement) must
        // not push the actions out of the sheet.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = profile.name.ifBlank { stringResource(R.string.common_subscription_fallback) },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text =
                    if (manual) {
                        stringResource(R.string.subs_manual_sheet_hint)
                    } else {
                        redactedHost(profile.url)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(12.dp))

            // --- facts: node count, last updated/error, update interval ---
            DetailRow(
                label = stringResource(R.string.subs_detail_nodes),
                value = stringResource(
                    R.string.subs_detail_nodes_value,
                    nodeCount,
                    formatRelativeTime(profile.lastUpdatedAt),
                ),
            )
            profile.lastError?.let {
                DetailRow(label = stringResource(R.string.common_last_error), value = it, error = true)
            }
            DetailRow(
                label = stringResource(R.string.subs_update_interval),
                value = when {
                    manual -> stringResource(R.string.subs_interval_never)
                    profile.updateAlways -> stringResource(R.string.subs_interval_launch)
                    profile.updateIntervalMinutes != null ->
                        stringResource(
                            R.string.subs_interval_minutes,
                            profile.updateIntervalMinutes,
                        )
                    else -> stringResource(R.string.subs_interval_manual)
                },
            )
            profile.announce?.let {
                DetailRow(label = stringResource(R.string.subs_announcement), value = it)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // --- enable/disable ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.subs_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(
                            if (profile.enabled) {
                                R.string.subs_enabled_sub_on
                            } else {
                                R.string.subs_enabled_sub_off
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = profile.enabled, onCheckedChange = onToggle)
            }

            // --- manually imported nodes: per-node delete ---
            // The manual row's nodes are append-only (nothing replaces them),
            // so without this an accidentally pasted link is stuck: the row
            // can only be disabled, never corrected.
            if (manual && manualNodes.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.subs_imported_servers),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                manualNodes.forEach { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemoveNode(node.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription =
                                    stringResource(R.string.subs_remove_node_cd, node.name),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // --- actions ---
            if (!manual) {
                TextButton(
                    onClick = onRefresh,
                    enabled = !refreshing,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.subs_refresh_now))
                }
            }

            // Rename: inline field, committed on Save.
            if (renaming) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = nameField,
                        onValueChange = { nameField = it },
                        label = { Text(stringResource(R.string.subs_name_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            renaming = false
                            if (nameField.trim() != profile.name) {
                                onRename(nameField)
                            }
                        },
                        enabled = nameField.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.subs_save))
                    }
                }
            } else {
                TextButton(
                    onClick = {
                        nameField = profile.name
                        renaming = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.subs_rename))
                }
            }

            // Edit URL: inline field; the repository fetches/validates the
            // candidate BEFORE committing — Save is a request, not a commit.
            if (!manual) {
                if (editingUrl) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                        OutlinedTextField(
                            value = urlField,
                            onValueChange = { urlField = it },
                            label = { Text(stringResource(R.string.subs_new_url_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.subs_url_edit_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(
                                onClick = {
                                    editingUrl = false
                                    onEditUrl(urlField)
                                },
                                enabled = urlField.isNotBlank() && !refreshing,
                            ) {
                                Text(stringResource(R.string.subs_validate_save))
                            }
                            TextButton(onClick = { editingUrl = false }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    }
                } else {
                    TextButton(
                        onClick = {
                            // Never prefill the raw URL — it may carry
                            // credentials; the user pastes the new one.
                            urlField = ""
                            editingUrl = true
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        Text(stringResource(R.string.subs_edit_url))
                    }
                }
            }

            if (!manual) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, error: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
