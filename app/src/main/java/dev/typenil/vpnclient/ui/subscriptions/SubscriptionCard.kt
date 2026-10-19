package dev.typenil.vpnclient.ui.subscriptions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatDate
import dev.typenil.vpnclient.ui.common.formatRelativeTime
import dev.typenil.vpnclient.ui.common.localizedResources
import dev.typenil.vpnclient.ui.theme.AfterglowCard
import dev.typenil.vpnclient.ui.theme.AfterglowNotice
import dev.typenil.vpnclient.ui.theme.NoticeTone
import dev.typenil.vpnclient.ui.theme.AfterglowDialog
import dev.typenil.vpnclient.ui.theme.AfterglowTextField
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.AfterglowType
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Meta sits in two columns only while the text is small; at larger fonts one column keeps every line readable. */
internal fun metaTwoColumns(fontScale: Float): Boolean = fontScale < 1.3f

private const val ANNOUNCE_LINES = 2

/** Host-only display of a subscription URL — the URL itself may carry credentials and must never be rendered. */
@Composable
internal fun redactedHost(url: String): String =
    runCatching { url.toHttpUrl().host }.getOrNull() ?: stringResource(R.string.subs_redacted_url)

/**
 * One subscription: name + host, Refresh and a "⋮" menu on the right, then the meta
 * (servers · updated | traffic · expiry), the provider announcement, Support and the last error.
 * Delete lives only in the menu (last, error tone). Tapping the card opens the detail sheet.
 */
@Composable
internal fun SubscriptionCard(
    profile: SubscriptionProfile,
    nodeCount: Int,
    refreshing: Boolean,
    manual: Boolean,
    onOpenDetails: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onEditUrl: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val c = AfterglowTheme.colors
    val uriHandler = LocalUriHandler.current
    val name = profile.name.ifBlank { stringResource(R.string.common_subscription_fallback) }
    val bodyEnd = AfterglowTokens.rowPaddingH - AfterglowTokens.headerInset
    AfterglowCard(Modifier.clickable(role = Role.Button, onClick = onOpenDetails)) {
        Column(
            Modifier
                .animateContentSize(tween(AfterglowTokens.motionStandard))
                .padding(start = AfterglowTokens.rowPaddingH, top = AfterglowTokens.cardGap, end = AfterglowTokens.headerInset, bottom = AfterglowTokens.cardGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = AfterglowType.rowTitle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        // The sentinel URL isn't a fetchable address — label the row instead of running it through redactedHost.
                        text = if (manual) stringResource(R.string.subs_manual_hint) else redactedHost(profile.url),
                        style = AfterglowType.rowCaption,
                        color = c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!manual) RefreshButton(name, refreshing, onRefresh)
                CardMenu(name, profile.name, manual, onOpenDetails, onRename, onEditUrl, onDelete)
            }
            CardMeta(profile, nodeCount, Modifier.padding(end = bodyEnd))
            Column(Modifier.padding(end = bodyEnd)) {
                profile.announce?.let { announce ->
                    var overflow by remember(announce) { mutableStateOf(false) }
                    Text(
                        announce, style = AfterglowType.rowCaption, color = c.muted,
                        maxLines = ANNOUNCE_LINES, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = AfterglowTokens.headerInset),
                        onTextLayout = { overflow = it.hasVisualOverflow },
                    )
                    // The full text lives in the detail sheet; the card only hints that there is more.
                    if (overflow) {
                        CardLink(stringResource(R.string.subs_announce_more), null, onOpenDetails)
                    }
                }
                profile.supportUrl?.takeIf { it.startsWith("http") }?.let { url ->
                    val supportCd = stringResource(R.string.subs_support_cd, redactedHost(url))
                    CardLink(stringResource(R.string.subs_support), supportCd) { uriHandler.openUri(url) }
                }
                // The notice fades/expands in when a refresh fails and out when one succeeds; it keeps the last
                // error text while leaving so it doesn't blank out mid-animation.
                val lastShown = remember { arrayOfNulls<String>(1) }
                profile.lastError?.let { lastShown[0] = it }
                AnimatedVisibility(
                    visible = profile.lastError != null,
                    enter = fadeIn(tween(AfterglowTokens.motionStandard)) + expandVertically(tween(AfterglowTokens.motionStandard)),
                    exit = fadeOut(tween(AfterglowTokens.motionFast)) + shrinkVertically(tween(AfterglowTokens.motionFast)),
                ) {
                    lastShown[0]?.let { error ->
                        val reason = subscriptionFailureMessage(error, nodeCount > 0)?.render(LocalContext.current) ?: error
                        val updated = profile.lastUpdatedAt
                        AfterglowNotice(
                            tone = NoticeTone.Error,
                            text = if (updated != null) stringResource(R.string.subs_refresh_failed_notice, formatRelativeTime(updated)) else reason,
                            detail = if (updated != null) reason else null,
                            modifier = Modifier.padding(top = AfterglowTokens.headerGap),
                        )
                    }
                }
            }
        }
    }
}

/** A text link whose text starts exactly where the card's text does; the touch target is still at least 48 dp each way. */
@Composable
private fun CardLink(text: String, description: String?, onClick: () -> Unit) {
    Box(
        Modifier
            .defaultMinSize(minWidth = AfterglowTokens.touchTarget, minHeight = AfterglowTokens.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = AfterglowTheme.colors.coral) }
}

@Composable
private fun RefreshButton(name: String, refreshing: Boolean, onRefresh: () -> Unit) {
    if (refreshing) {
        // The existing in-progress state, in a box as large as the button it replaces.
        Box(Modifier.size(AfterglowTokens.touchTarget), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(AfterglowTokens.actionIconSize), strokeWidth = AfterglowTokens.progressStroke)
        }
    } else {
        IconButton(onClick = onRefresh, modifier = Modifier.size(AfterglowTokens.touchTarget)) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.subs_refresh_named, name), tint = AfterglowTheme.colors.ink)
        }
    }
}

@Composable
private fun CardMenu(
    name: String,
    currentName: String,
    manual: Boolean,
    onOpenDetails: () -> Unit,
    onRename: (String) -> Unit,
    onEditUrl: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val c = AfterglowTheme.colors
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingUrl by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(AfterglowTokens.touchTarget)) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.subs_menu_more, name), tint = c.ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.subs_rename)) }, onClick = { open = false; renaming = true })
            if (!manual) DropdownMenuItem(text = { Text(stringResource(R.string.subs_edit_url)) }, onClick = { open = false; editingUrl = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.subs_menu_details)) }, onClick = { open = false; onOpenDetails() })
            if (!manual) {
                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = c.error) }, onClick = { open = false; onDelete() })
            }
        }
    }
    if (renaming) {
        var field by remember { mutableStateOf(currentName) }
        FieldDialog(
            title = stringResource(R.string.subs_rename),
            label = stringResource(R.string.subs_name_label),
            value = field,
            onValueChange = { field = it },
            confirm = stringResource(R.string.subs_save),
            onConfirm = { renaming = false; if (field.trim() != currentName) onRename(field) },
            onDismiss = { renaming = false },
        )
    }
    if (editingUrl) {
        // Never prefilled: the stored URL may carry credentials; the repository validates the new one before it replaces anything.
        var field by remember { mutableStateOf("") }
        FieldDialog(
            title = stringResource(R.string.subs_edit_url),
            label = stringResource(R.string.subs_new_url_label),
            hint = stringResource(R.string.subs_url_edit_hint),
            value = field,
            onValueChange = { field = it },
            confirm = stringResource(R.string.subs_validate_save),
            onConfirm = { editingUrl = false; onEditUrl(field) },
            onDismiss = { editingUrl = false },
        )
    }
}

@Composable
private fun FieldDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
) {
    AfterglowDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AfterglowTokens.headerGap)) {
                AfterglowTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (hint != null) Text(hint, style = AfterglowType.rowCaption, color = AfterglowTheme.colors.muted)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Left column: servers / updated (+ launch and disabled flags). Right column: used / expires. Every fact is its own
 * line. One column, same order, at larger fonts, without userInfo, or as soon as a line would wrap in its column.
 */
@Composable
private fun CardMeta(profile: SubscriptionProfile, nodeCount: Int, modifier: Modifier = Modifier) {
    val c = AfterglowTheme.colors
    val updated = profile.lastUpdatedAt
    val left = buildList {
        add(localizedResources().getQuantityString(R.plurals.subs_card_servers, nodeCount, nodeCount) to c.muted)
        add((if (updated != null) stringResource(R.string.subs_card_updated, formatRelativeTime(updated)) else stringResource(R.string.subs_card_not_updated)) to c.muted)
        // Suppressed when the row can't actually launch-refresh: disabled subscription or Disabled refresh policy.
        if (profile.updateAlways && profile.enabled && profile.refreshPolicy != RefreshPolicy.Disabled) add(stringResource(R.string.subs_card_updates_on_launch) to c.muted)
        if (!profile.enabled) add(stringResource(R.string.subs_card_disabled) to c.error)
    }
    val right = profile.userInfo?.let { info ->
        buildList {
            add(
                (if (info.totalBytes > 0) stringResource(R.string.subs_usage_of, formatBytes(info.usedBytes), formatBytes(info.totalBytes))
                else stringResource(R.string.subs_usage, formatBytes(info.usedBytes))) to c.muted,
            )
            expiryFact(info, System.currentTimeMillis())?.let {
                add(stringResource(if (it.expired) R.string.subs_expired else R.string.subs_expires, formatDate(it.epochSeconds, localizedResources().configuration.locales[0])) to c.muted)
            }
        }
    }.orEmpty()
    var wraps by remember(left, right) { mutableStateOf(false) }
    val lines: @Composable (List<Pair<String, Color>>, Modifier) -> Unit = { items, m ->
        Column(m) {
            items.forEach { (text, color) ->
                Text(text, style = AfterglowType.rowCaption, color = color, onTextLayout = { if (it.lineCount > 1) wraps = true })
            }
        }
    }
    if (right.isNotEmpty() && !wraps && metaTwoColumns(LocalDensity.current.fontScale)) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.cardGap)) {
            lines(left, Modifier.weight(1f))
            lines(right, Modifier.weight(1f))
        }
    } else {
        lines(left + right, modifier.fillMaxWidth())
    }
}
