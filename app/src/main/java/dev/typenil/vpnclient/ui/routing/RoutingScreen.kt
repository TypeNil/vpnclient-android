package dev.typenil.vpnclient.ui.routing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.engine.DnsMode
import dev.typenil.vpnclient.core.engine.DnsUpstream
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.engine.RoutingRule
import dev.typenil.vpnclient.data.db.RoutingRuleEntity

/**
 * Routing policy screen: RouteMode + per-app entry + LAN bypass. Everything
 * here is compiled into the tunnel at connect time, so changes on a live
 * session surface the reconnect prompt rather than pretending to apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    onBack: () -> Unit,
    onOpenAppFilter: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: RoutingViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reconnectMessage = stringResource(R.string.settings_snackbar_reconnect_message)
    val reconnectAction = stringResource(R.string.settings_snackbar_reconnect_action)
    LaunchedEffect(ui.reconnectRecommended, reconnectMessage, reconnectAction) {
        if (ui.reconnectRecommended) {
            val result =
                snackbarHostState.showSnackbar(
                    message = reconnectMessage,
                    actionLabel = reconnectAction,
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.reconnect()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
        ) {
            RouteModeRow(
                mode = ui.routeMode,
                applied = ui.appliedRouteMode,
                onSelect = viewModel::setRouteMode,
            )
            SwitchRow(
                title = stringResource(R.string.routing_bypass_lan),
                subtitle = stringResource(R.string.routing_bypass_lan_sub),
                checked = ui.bypassLan,
                onCheckedChange = viewModel::setBypassLan,
            )
            // Saved-vs-applied: the switch shows the stored value; a diverged
            // live session calls out the applied state honestly.
            if (ui.sessionActive && ui.appliedBypassLan != null &&
                ui.appliedBypassLan != ui.bypassLan
            ) {
                Text(
                    text =
                        stringResource(
                            if (ui.appliedBypassLan == true) {
                                R.string.routing_applied_on
                            } else {
                                R.string.routing_applied_off
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            DnsModeRow(
                mode = ui.dnsMode,
                onSelect = viewModel::setDnsMode,
            )
            DnsUpstreamRow(
                upstream = ui.dnsUpstream,
                onSelect = viewModel::setDnsUpstream,
            )
            if (ui.sessionActive && ui.appliedDnsSummary != null) {
                Text(
                    text =
                        stringResource(R.string.routing_dns_applied, ui.appliedDnsSummary ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            RulesSection(
                rules = ui.rules,
                onAdd = viewModel::addRule,
                onToggle = viewModel::setRuleEnabled,
                onDelete = viewModel::deleteRule,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAppFilter)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.common_per_app_vpn),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_per_app_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Region/domain routing picker. When a live session runs a different mode
 *  the subtitle shows the applied one — pending change is honest, not hidden. */
@Composable
private fun RouteModeRow(
    mode: RouteMode,
    applied: RouteMode?,
    onSelect: (RouteMode) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { showDialog = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.common_routing_mode),
                style = MaterialTheme.typography.bodyLarge,
            )
            val subtitle =
                if (applied != null && applied != mode) {
                    stringResource(
                        R.string.routing_saved_applied,
                        routeModeLabel(mode),
                        routeModeLabel(applied),
                    )
                } else {
                    routeModeLabel(mode)
                }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.common_routing_mode)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    RouteMode.entries.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == mode,
                                        onClick = {
                                            onSelect(option)
                                            showDialog = false
                                        },
                                        role = Role.RadioButton,
                                    ).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == mode, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(
                                    routeModeLabel(option),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    routeModeSubtitle(option),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.routing_applies_next_connect),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun routeModeLabel(mode: RouteMode): String =
    stringResource(
        when (mode) {
            RouteMode.ALL -> R.string.route_mode_all
            RouteMode.BYPASS_RU -> R.string.route_mode_bypass_ru
            RouteMode.PROXY_BLOCKED -> R.string.route_mode_proxy_blocked
        },
    )

@Composable
private fun routeModeSubtitle(mode: RouteMode): String =
    stringResource(
        when (mode) {
            RouteMode.ALL -> R.string.route_mode_sub_all
            RouteMode.BYPASS_RU -> R.string.route_mode_sub_bypass_ru
            RouteMode.PROXY_BLOCKED -> R.string.route_mode_sub_proxy_blocked
        },
    )

/** DNS policy: follow the RouteMode, or pin every query to the proxied
 *  upstream. Honest about bootstrap: even in proxy-only the upstream's own
 *  hostname and node names still resolve via the system resolver — that is
 *  the required loop-break, not a leak. */
@Composable
private fun DnsModeRow(
    mode: DnsMode,
    onSelect: (DnsMode) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { showDialog = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.routing_dns_mode),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                dnsModeLabel(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.routing_dns_mode)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    DnsMode.entries.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == mode,
                                        onClick = {
                                            onSelect(option)
                                            showDialog = false
                                        },
                                        role = Role.RadioButton,
                                    ).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == mode, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(
                                    dnsModeLabel(option),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    dnsModeSubtitle(option),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.routing_dns_bootstrap_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun dnsModeLabel(mode: DnsMode): String =
    stringResource(
        when (mode) {
            DnsMode.POLICY -> R.string.dns_mode_policy
            DnsMode.PROXY_ONLY -> R.string.dns_mode_proxy_only
        },
    )

@Composable
private fun dnsModeSubtitle(mode: DnsMode): String =
    stringResource(
        when (mode) {
            DnsMode.POLICY -> R.string.dns_mode_policy_sub
            DnsMode.PROXY_ONLY -> R.string.dns_mode_proxy_only_sub
        },
    )

/** Upstream picker: presets + a validated custom spec field. The custom
 *  value is stored only after parse; an invalid input shows an error and
 *  never reaches the compiler. */
@Composable
private fun DnsUpstreamRow(
    upstream: DnsUpstream,
    onSelect: (DnsUpstream) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { showDialog = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.routing_dns_upstream),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                dnsUpstreamLabel(upstream),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        var customSpec by remember {
            mutableStateOf((upstream as? DnsUpstream.Custom)?.spec ?: "")
        }
        var customError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.routing_dns_upstream)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    DnsUpstream.presets.forEach { option ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option.key == upstream.key,
                                        onClick = {
                                            onSelect(option)
                                            showDialog = false
                                        },
                                        role = Role.RadioButton,
                                    ).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option.key == upstream.key, onClick = null)
                            Text(
                                dnsUpstreamLabel(option),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.routing_dns_custom_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = customSpec,
                        onValueChange = {
                            customSpec = it
                            customError = false
                        },
                        placeholder = { Text(stringResource(R.string.routing_dns_custom_hint)) },
                        isError = customError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (customError) {
                        Text(
                            stringResource(R.string.routing_dns_custom_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        stringResource(R.string.routing_dns_custom_formats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (customSpec.isBlank()) {
                            showDialog = false
                            return@TextButton
                        }
                        val parsed = DnsUpstream.parseCustom(customSpec)
                        if (parsed == null) {
                            customError = true
                        } else {
                            onSelect(parsed)
                            showDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.routing_dns_use_custom))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun dnsUpstreamLabel(upstream: DnsUpstream): String =
    when (upstream) {
        is DnsUpstream.Cloudflare -> stringResource(R.string.dns_upstream_cloudflare)
        is DnsUpstream.Google -> stringResource(R.string.dns_upstream_google)
        is DnsUpstream.Quad9 -> stringResource(R.string.dns_upstream_quad9)
        is DnsUpstream.AdGuard -> stringResource(R.string.dns_upstream_adguard)
        is DnsUpstream.Custom -> upstream.spec
    }

/**
 * User routing rules — evaluated top-down before the mode's rule-sets.
 * Each row shows the matcher + action with an enable switch and a delete;
 * "Add" opens a kind-picker + validated input. Compiled into route.rules.
 */
@Composable
private fun RulesSection(
    rules: List<RoutingRuleEntity>,
    onAdd: (RoutingRule.Kind, String, RoutingRule.Action) -> Boolean,
    onToggle: (RoutingRuleEntity, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.routing_rules_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { showAdd = true }) {
            Text(stringResource(R.string.routing_rules_add))
        }
    }
    if (rules.isEmpty()) {
        Text(
            stringResource(R.string.routing_rules_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    rules.forEach { rule ->
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    rule.pattern,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    "${ruleKindLabel(rule.kind)} → ${ruleActionLabel(rule.action)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = rule.isEnabled,
                onCheckedChange = { onToggle(rule, it) },
            )
            IconButton(onClick = { onDelete(rule.id) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.routing_rules_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showAdd) {
        AddRuleDialog(
            onAdd = onAdd,
            onAdded = { showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun ruleKindLabel(kindKey: String): String =
    stringResource(
        when (RoutingRule.Kind.fromKey(kindKey)) {
            RoutingRule.Kind.DOMAIN -> R.string.routing_rule_kind_domain
            RoutingRule.Kind.IP_CIDR -> R.string.routing_rule_kind_ip
            RoutingRule.Kind.PORT -> R.string.routing_rule_kind_port
            null -> R.string.routing_rule_kind_domain
        },
    )

@Composable
private fun ruleActionLabel(actionKey: String): String =
    stringResource(
        when (RoutingRule.Action.fromKey(actionKey)) {
            RoutingRule.Action.PROXY -> R.string.routing_rule_action_proxy
            RoutingRule.Action.DIRECT -> R.string.routing_rule_action_direct
            RoutingRule.Action.BLOCK -> R.string.routing_rule_action_block
            null -> R.string.routing_rule_action_proxy
        },
    )

/** Kind picker → validated pattern → action. The ViewModel's validate is the
 *  gate; an invalid input keeps the dialog open with an error. */
@Composable
private fun AddRuleDialog(
    onAdd: (RoutingRule.Kind, String, RoutingRule.Action) -> Boolean,
    onAdded: () -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(RoutingRule.Kind.DOMAIN) }
    var action by remember { mutableStateOf(RoutingRule.Action.PROXY) }
    var pattern by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_rules_add)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.routing_rule_kind_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(Modifier.selectableGroup()) {
                    RoutingRule.Kind.entries.forEach { k ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .selectable(
                                        selected = k == kind,
                                        onClick = { kind = k },
                                        role = Role.RadioButton,
                                    ).padding(end = 8.dp),
                        ) {
                            RadioButton(selected = k == kind, onClick = null)
                            Text(ruleKindLabel(k.key), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        error = false
                    },
                    placeholder = {
                        Text(
                            stringResource(
                                when (kind) {
                                    RoutingRule.Kind.DOMAIN -> R.string.routing_rule_hint_domain
                                    RoutingRule.Kind.IP_CIDR -> R.string.routing_rule_hint_ip
                                    RoutingRule.Kind.PORT -> R.string.routing_rule_hint_port
                                },
                            ),
                        )
                    },
                    isError = error,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error) {
                    Text(
                        stringResource(R.string.routing_rule_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.routing_rule_action_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.selectableGroup()) {
                    RoutingRule.Action.entries.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .selectable(
                                        selected = a == action,
                                        onClick = { action = a },
                                        role = Role.RadioButton,
                                    ).padding(end = 8.dp),
                        ) {
                            RadioButton(selected = a == action, onClick = null)
                            Text(ruleActionLabel(a.key), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onAdd(kind, pattern, action)) {
                        onAdded()
                    } else {
                        error = true
                    }
                },
            ) {
                Text(stringResource(R.string.routing_rules_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
