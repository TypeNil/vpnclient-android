package dev.typenil.vpnclient.ui.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatDate
import dev.typenil.vpnclient.ui.common.formatRelativeTime
import dev.typenil.vpnclient.ui.common.localizedResources
import dev.typenil.vpnclient.ui.theme.AfterglowButton
import dev.typenil.vpnclient.ui.theme.AfterglowCard
import dev.typenil.vpnclient.ui.theme.AfterglowChevron
import dev.typenil.vpnclient.ui.theme.AfterglowNotice
import dev.typenil.vpnclient.ui.theme.AfterglowSectionHeader
import dev.typenil.vpnclient.ui.theme.AfterglowSheet
import dev.typenil.vpnclient.ui.theme.AfterglowSheetHeader
import dev.typenil.vpnclient.ui.theme.AfterglowSwitch
import dev.typenil.vpnclient.ui.theme.AfterglowTextField
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.AfterglowType
import dev.typenil.vpnclient.ui.theme.NoticeTone

/**
 * Detail sheet of one subscription, top to bottom: failure / empty notice, status facts (servers, updated, traffic,
 * expiry), settings (auto-refresh, enabled), provider text, the primary Refresh and the rarer actions with Delete last.
 * It reads the live profile, so a refresh landing while it is open shows current values. A new URL is validated by
 * the repository before it replaces anything — Save is a request, not a commit.
 */
@Composable
internal fun SubscriptionDetailSheet(
    profile: SubscriptionProfile,
    nodeCount: Int,
    manualNodes: List<NodeEntity>,
    refreshing: Boolean,
    globalAutoRefreshMinutes: Int,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onEditUrl: (String) -> Unit,
    onPolicyChange: (RefreshPolicy) -> Unit,
    onRemoveNode: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val c = AfterglowTheme.colors
    val manual = SubscriptionRepository.isManualSubscription(profile.url)
    var renaming by remember { mutableStateOf(false) }
    var editingUrl by remember { mutableStateOf(false) }
    var showPolicyDialog by remember { mutableStateOf(false) }
    var nameField by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var urlField by rememberSaveable(profile.id) { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current

    AfterglowSheet(onDismiss = onDismiss) {
        AfterglowSheetHeader(
            title = profile.name.ifBlank { stringResource(R.string.common_subscription_fallback) },
            closeLabel = stringResource(R.string.common_dismiss),
            onDismiss = onDismiss,
        )
        // Scrollable: a long manual-node list or announcement must not push the actions out of the sheet.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = AfterglowTokens.sectionGap, end = AfterglowTokens.sectionGap, bottom = AfterglowTokens.sectionGap),
            verticalArrangement = Arrangement.spacedBy(AfterglowTokens.cardGap),
        ) {
            Text(
                text = if (manual) stringResource(R.string.subs_manual_sheet_hint) else redactedHost(profile.url),
                style = AfterglowType.rowCaption, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            profile.lastError?.let { error ->
                val reason = subscriptionFailureMessage(error, nodeCount > 0)?.render(LocalContext.current) ?: error
                val updated = profile.lastUpdatedAt
                AfterglowNotice(
                    tone = NoticeTone.Error,
                    text = if (updated != null) stringResource(R.string.subs_refresh_failed_notice, formatRelativeTime(updated)) else reason,
                    detail = if (updated != null) reason else null,
                )
            }
            if (!manual && nodeCount == 0 && profile.lastError == null) {
                AfterglowNotice(
                    tone = NoticeTone.Warning,
                    text = stringResource(R.string.subs_detail_no_servers),
                    actionLabel = if (refreshing) null else stringResource(R.string.subs_refresh_now),
                    onAction = onRefresh,
                )
            }

            val expiry = expiryFact(profile.userInfo, System.currentTimeMillis())
            DetailCard {
                Column(Modifier.padding(AfterglowTokens.rowPaddingH), verticalArrangement = Arrangement.spacedBy(AfterglowTokens.cardGap)) {
                    Fact(stringResource(R.string.subs_detail_nodes), nodeCount.toString())
                    Fact(stringResource(R.string.subs_detail_updated), formatRelativeTime(profile.lastUpdatedAt))
                    profile.userInfo?.let { info ->
                        Fact(
                            stringResource(R.string.subs_detail_traffic),
                            if (info.totalBytes > 0) stringResource(R.string.subs_usage_of, formatBytes(info.usedBytes), formatBytes(info.totalBytes))
                            else stringResource(R.string.subs_usage, formatBytes(info.usedBytes)),
                            bar = usageFraction(info),
                        )
                    }
                    expiry?.let {
                        Fact(
                            stringResource(if (it.expired) R.string.subs_detail_expired else R.string.subs_detail_expires),
                            formatDate(it.epochSeconds, localizedResources().configuration.locales[0]),
                            valueColor = if (it.expired) c.error else c.ink,
                        )
                    }
                }
            }

            DetailCard {
                if (!manual) {
                    ValueRow(
                        title = stringResource(R.string.subs_policy_title),
                        caption = refreshPolicySummary(profile, globalAutoRefreshMinutes),
                        onClick = { showPolicyDialog = true },
                    )
                    CardDivider()
                    Column(Modifier.padding(horizontal = AfterglowTokens.rowPaddingH, vertical = AfterglowTokens.rowPaddingV)) {
                        Fact(
                            stringResource(R.string.subs_update_interval),
                            when {
                                profile.updateAlways -> stringResource(R.string.subs_interval_launch)
                                profile.updateIntervalMinutes != null -> stringResource(R.string.subs_interval_minutes, profile.updateIntervalMinutes)
                                else -> stringResource(R.string.subs_interval_manual)
                            },
                        )
                    }
                    CardDivider()
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        // Whole row toggles: TalkBack reads "Enabled, switch, on".
                        .toggleable(value = profile.enabled, role = Role.Switch, onValueChange = onToggle)
                        .heightIn(min = AfterglowTokens.rowMinHeight)
                        .padding(horizontal = AfterglowTokens.rowPaddingH, vertical = AfterglowTokens.rowPaddingV),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.subs_enabled), style = AfterglowType.rowTitle, color = c.ink)
                        Text(
                            stringResource(if (profile.enabled) R.string.subs_enabled_sub_on else R.string.subs_enabled_sub_off),
                            style = AfterglowType.rowCaption, color = c.muted,
                        )
                    }
                    AfterglowSwitch(checked = profile.enabled, onCheckedChange = null)
                }
            }

            val support = profile.supportUrl?.takeIf { it.startsWith("http") }
            if (profile.announce != null || support != null) {
                AfterglowSectionHeader(stringResource(R.string.subs_detail_provider))
                DetailCard {
                    Column(Modifier.padding(horizontal = AfterglowTokens.rowPaddingH, vertical = AfterglowTokens.rowPaddingV)) {
                        profile.announce?.let { Text(it, style = AfterglowType.rowCaption, color = c.ink) }
                        support?.let { url ->
                            val supportCd = stringResource(R.string.subs_support_cd, redactedHost(url))
                            Box(
                                Modifier
                                    .defaultMinSize(minWidth = AfterglowTokens.touchTarget, minHeight = AfterglowTokens.touchTarget)
                                    .clickable(role = Role.Button) { uriHandler.openUri(url) }
                                    .semantics { contentDescription = supportCd },
                                contentAlignment = Alignment.CenterStart,
                            ) { Text(stringResource(R.string.subs_support), style = AfterglowType.rowTitle, color = c.coral) }
                        }
                    }
                }
            }

            // The manual row's nodes are append-only, so without per-node delete an accidentally pasted link is stuck.
            if (manual && manualNodes.isNotEmpty()) {
                AfterglowSectionHeader(stringResource(R.string.subs_imported_servers))
                DetailCard {
                    manualNodes.forEachIndexed { i, node ->
                        if (i > 0) CardDivider()
                        Row(Modifier.padding(start = AfterglowTokens.rowPaddingH, end = AfterglowTokens.headerInset), verticalAlignment = Alignment.CenterVertically) {
                            Text(node.name, style = AfterglowType.rowCaption, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemoveNode(node.id) }, modifier = Modifier.size(AfterglowTokens.touchTarget)) {
                                Icon(Icons.Default.Delete, stringResource(R.string.subs_remove_node_cd, node.name), tint = c.error)
                            }
                        }
                    }
                }
            }

            if (!manual) {
                AfterglowButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(AfterglowTokens.progressSize), color = c.paper, strokeWidth = AfterglowTokens.progressStroke)
                        Spacer(Modifier.width(AfterglowTokens.headerGap))
                    }
                    Text(stringResource(if (refreshing) R.string.subs_refreshing else R.string.subs_refresh_now))
                }
            }

            DetailCard {
                if (renaming) {
                    Column(Modifier.padding(AfterglowTokens.rowPaddingH)) {
                        AfterglowTextField(
                            value = nameField, onValueChange = { nameField = it }, singleLine = true,
                            label = { Text(stringResource(R.string.subs_name_label)) }, modifier = Modifier.fillMaxWidth(),
                        )
                        Row {
                            TextButton(
                                onClick = { renaming = false; if (nameField.trim() != profile.name) onRename(nameField) },
                                enabled = nameField.isNotBlank(),
                            ) { Text(stringResource(R.string.subs_save)) }
                            TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) }
                        }
                    }
                } else {
                    ActionRow(stringResource(R.string.subs_rename)) { nameField = profile.name; renaming = true }
                }
                if (!manual) {
                    CardDivider()
                    if (editingUrl) {
                        Column(Modifier.padding(AfterglowTokens.rowPaddingH)) {
                            AfterglowTextField(
                                value = urlField, onValueChange = { urlField = it }, singleLine = true,
                                label = { Text(stringResource(R.string.subs_new_url_label)) }, modifier = Modifier.fillMaxWidth(),
                            )
                            Text(stringResource(R.string.subs_url_edit_hint), style = AfterglowType.rowCaption, color = c.muted)
                            Row {
                                TextButton(
                                    onClick = { editingUrl = false; onEditUrl(urlField) },
                                    enabled = urlField.isNotBlank() && !refreshing,
                                ) { Text(stringResource(R.string.subs_validate_save)) }
                                TextButton(onClick = { editingUrl = false }) { Text(stringResource(R.string.common_cancel)) }
                            }
                        }
                    } else {
                        // Never prefill the raw URL — it may carry credentials; the user pastes the new one.
                        ActionRow(stringResource(R.string.subs_edit_url)) { urlField = ""; editingUrl = true }
                    }
                    CardDivider()
                    ActionRow(stringResource(R.string.common_delete), c.error, onDelete)
                }
            }
        }
    }

    if (showPolicyDialog) {
        RefreshPolicyDialog(
            profile = profile,
            onDismiss = { showPolicyDialog = false },
            onSelect = { policy -> showPolicyDialog = false; onPolicyChange(policy) },
        )
    }
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) = AfterglowCard(content = content)

@Composable
private fun CardDivider() = HorizontalDivider(Modifier.padding(horizontal = AfterglowTokens.rowPaddingH), color = AfterglowTheme.colors.border)

/** One fact: small label over its value (one TalkBack node), plus a quota bar under the value when [bar] is given. */
@Composable
private fun Fact(label: String, value: String, valueColor: Color = AfterglowTheme.colors.ink, bar: Float? = null) {
    val c = AfterglowTheme.colors
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(AfterglowTokens.chartGap)) {
        Text(label, style = AfterglowType.rowCaption, color = c.muted)
        Text(value, style = AfterglowType.rowTitle, color = valueColor)
        if (bar != null) {
            Box(Modifier.fillMaxWidth().heightIn(min = AfterglowTokens.headerGap).clip(AfterglowTokens.chipShape).background(c.border)) {
                Box(Modifier.fillMaxWidth(bar).heightIn(min = AfterglowTokens.headerGap).clip(AfterglowTokens.chipShape).background(if (bar >= 1f) c.error else c.coral))
            }
        }
    }
}

@Composable
private fun ValueRow(title: String, caption: String, onClick: () -> Unit) {
    val c = AfterglowTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = AfterglowTokens.rowMinHeight)
            .padding(horizontal = AfterglowTokens.rowPaddingH, vertical = AfterglowTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AfterglowType.rowTitle, color = c.ink)
            Text(caption, style = AfterglowType.rowCaption, color = c.muted)
        }
        AfterglowChevron()
    }
}

@Composable
private fun ActionRow(label: String, color: Color = AfterglowTheme.colors.ink, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = AfterglowTokens.rowHeight)
            .padding(horizontal = AfterglowTokens.rowPaddingH),
        contentAlignment = Alignment.CenterStart,
    ) { Text(label, style = AfterglowType.rowTitle, color = color) }
}
