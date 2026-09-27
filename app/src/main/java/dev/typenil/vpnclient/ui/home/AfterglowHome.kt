package dev.typenil.vpnclient.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.ui.common.formatBytes
import dev.typenil.vpnclient.ui.common.formatRate
import dev.typenil.vpnclient.ui.common.routeModeSummary
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

private enum class VisualState { Offline, Preparing, Permission, Connecting, Connected, Reconnecting, Stopping, Error }

internal fun frameTimeNanos(lastTickNanos: Long, measuredNanos: Long): Long = maxOf(lastTickNanos, measuredNanos)

private fun VpnConnectionState.visual() = when (this) {
    VpnConnectionState.Idle -> VisualState.Offline
    is VpnConnectionState.Preparing -> VisualState.Preparing
    VpnConnectionState.PermissionRequired -> VisualState.Permission
    is VpnConnectionState.Connecting -> VisualState.Connecting
    is VpnConnectionState.Connected -> VisualState.Connected
    is VpnConnectionState.Reconnecting -> VisualState.Reconnecting
    VpnConnectionState.Stopping -> VisualState.Stopping
    is VpnConnectionState.Error -> VisualState.Error
}

@Composable
internal fun AfterglowHomeContent(
    ui: HomeUiState,
    modifier: Modifier = Modifier,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPick: () -> Unit,
    onAddServer: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRouting: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissGuard: () -> Unit,
    errorMessage: String?,
) {
    val colors = AfterglowTheme.colors
    val state = ui.connection
    val noServers = ui.serverOptions.isEmpty() && (state is VpnConnectionState.Idle || state is VpnConnectionState.Error)
    Box(modifier.fillMaxSize().background(colors.paper)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 150.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("RUNE", fontSize = 31.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, color = colors.ink)
                Text(".", fontSize = 31.sp, fontWeight = FontWeight.Black, color = colors.coral)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings),
                        tint = colors.ink)
                }
            }
            ConnectionHero(state.visual(), noServers, ui.selectedDelayMs)
            if (errorMessage != null) StatusNotice(errorMessage, onOpenDiagnostics, true)
            if (state == VpnConnectionState.PermissionRequired) {
                Text(stringResource(R.string.afterglow_permission_explanation), color = colors.muted,
                    modifier = Modifier.padding(top = 12.dp), fontSize = 14.sp)
            }
            if (ui.restartGuardTripped) StatusNotice(stringResource(R.string.home_restart_guard_text), onDismissGuard, false)
            Spacer(Modifier.height(18.dp))
            SectionHeader(stringResource(R.string.afterglow_quick_access),
                Modifier.padding(start = 2.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShortcutCard(R.string.nav_servers,
                    stringResource(R.string.afterglow_servers_available,
                        (ui.serverOptions.size - 1).coerceAtLeast(0)), true, onOpenServers, Modifier.weight(1f))
                ShortcutCard(R.string.nav_subscriptions,
                    stringResource(R.string.afterglow_subscriptions_enabled, ui.enabledSubscriptionCount),
                    false, onOpenSubscriptions, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            ServerAndRouting(ui, onPick, if (ui.noNodesAtAll) onAddServer else onOpenServers, onOpenRouting)
            if (state is VpnConnectionState.Connected) {
                SessionStats(state, onOpenConnections, onOpenDetails)
            }
        }
        ConnectionAction(state.visual(), noServers, if (noServers) {
            if (ui.noNodesAtAll) onAddServer else onOpenServers
        } else onConnect, onDisconnect,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(horizontal = 16.dp),
            hasNodes = !ui.noNodesAtAll)
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = AfterglowTheme.colors.coral, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, letterSpacing = 1.sp, modifier = modifier)
}

@Composable
private fun ShortcutCard(label: Int, caption: String, server: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    val colors = AfterglowTheme.colors
    Column(modifier.heightIn(min = 112.dp).clip(RoundedCornerShape(9.dp))
        .border(1.dp, colors.border, RoundedCornerShape(9.dp))
        .background(colors.paperSecondary)
        .semantics { role = Role.Button }
        .clickable(onClick = onClick)
        .padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (server) ServerListBadge() else SubscriptionBadge()
            CircledChevron()
        }
        Column {
            Text(stringResource(label), color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(caption, color = colors.muted, fontSize = 11.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ConnectionHero(state: VisualState, noServers: Boolean, delayMs: Int? = null) {
    val colors = AfterglowTheme.colors
    val target = when (state) {
        VisualState.Connected -> colors.connectedHero
        VisualState.Error -> colors.errorHero
        VisualState.Preparing, VisualState.Connecting, VisualState.Permission, VisualState.Reconnecting -> colors.transitionalHero
        else -> colors.offlineHero
    }
    val base by animateColorAsState(target, label = "Hero status")
    val headline = stringResource(when {
        noServers && state == VisualState.Offline -> R.string.afterglow_no_servers_headline
        else -> when (state) {
            VisualState.Offline -> R.string.afterglow_offline
            VisualState.Connected -> R.string.afterglow_online
            VisualState.Error -> R.string.afterglow_failed
            VisualState.Reconnecting -> R.string.afterglow_relinking
            VisualState.Preparing -> R.string.afterglow_preparing
            VisualState.Permission -> R.string.afterglow_permission
            VisualState.Connecting -> R.string.afterglow_connecting
            VisualState.Stopping -> R.string.afterglow_stopping
        }
    })
    val fontScale = LocalDensity.current.fontScale
    val largeFont = fontScale > 1.3f
    val narrow = LocalConfiguration.current.screenWidthDp <= 360
    Box(Modifier.fillMaxWidth().heightIn(min = if (largeFont) (188f + (fontScale - 1.3f) * 85f).dp else 188.dp)
        .clip(androidx.compose.ui.graphics.RectangleShape).background(Brush.horizontalGradient(listOf(base, base, colors.actionSurface)))) {
        Spacer(Modifier.fillMaxWidth().height(188.dp))
        // matchParentSize doesn't participate in the Hero's measurement; the 280dp artwork cannot stretch it.
        Box(Modifier.matchParentSize()) {
            Image(painterResource(R.drawable.afterglow_mascot), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.requiredSize(280.dp).align(Alignment.CenterEnd).offset(x = 92.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(0f to Color.Transparent, .55f to Color.Black, 1f to Color.Black),
                            blendMode = BlendMode.DstIn)
                    })
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to base, .3f to base.copy(alpha = .92f), .7f to Color.Transparent)))
        val longHeadline = state == VisualState.Connecting || state == VisualState.Reconnecting ||
            state == VisualState.Preparing || state == VisualState.Permission || state == VisualState.Stopping
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.62f)
            .padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 20.dp)) {
            Text(headline, color = colors.onActionSurface, fontWeight = FontWeight.Black,
                fontSize = if (largeFont) 24.sp else if (longHeadline) 26.sp else if (narrow) 32.sp else 36.sp,
                lineHeight = if (largeFont || longHeadline) 32.sp else 39.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            HeroLatency(delayMs, Modifier.padding(top = 8.dp))
        }
        Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter)
            .background(if (state == VisualState.Connected) colors.jade else colors.coralLight))
    }
}

@Composable
private fun ConnectionAction(state: VisualState, noServers: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit,
    modifier: Modifier = Modifier, hasNodes: Boolean = false) {
    val colors = AfterglowTheme.colors
    val active = state == VisualState.Connected || state == VisualState.Reconnecting
    val enabled = noServers || active || state == VisualState.Offline || state == VisualState.Error
    val label = when {
        noServers -> stringResource(if (hasNodes) R.string.nav_servers else R.string.common_add_server_cta)
        active -> stringResource(R.string.action_disconnect)
        state == VisualState.Offline -> stringResource(R.string.afterglow_start)
        state == VisualState.Error -> stringResource(R.string.afterglow_retry)
        state == VisualState.Stopping -> stringResource(R.string.home_status_disconnecting)
        state == VisualState.Permission -> stringResource(R.string.home_status_permission)
        state == VisualState.Preparing -> stringResource(R.string.home_status_preparing)
        else -> stringResource(R.string.home_status_connecting)
    }
    val accent = when {
        active -> colors.jade
        state == VisualState.Error -> colors.coralLight
        state == VisualState.Connecting || state == VisualState.Reconnecting ||
            state == VisualState.Preparing || state == VisualState.Permission -> colors.amber
        else -> colors.coral
    }
    // Companion face per state: idle=sad, connecting/transition=thinking,
    // error=crossed-eyes, connected=happy. The peer mascot perches on the
    // button's top edge (negative offset keeps it overlapping, not inside).
    val companion = when {
        noServers -> R.drawable.companion_idle
        state == VisualState.Connected -> R.drawable.companion_connected
        state == VisualState.Error -> R.drawable.companion_error
        state == VisualState.Offline || state == VisualState.Stopping -> R.drawable.companion_idle
        else -> R.drawable.companion_connecting
    }
    // Subtitle only for stable states; transitional states already name the
    // action in the title, so a static "VPN OFF/ON" subtitle would misreport.
    val subtitle = when {
        noServers -> stringResource(R.string.afterglow_no_servers_headline)
        active -> stringResource(R.string.afterglow_online)
        state == VisualState.Offline -> stringResource(R.string.afterglow_offline)
        else -> null
    }
    Box(modifier.fillMaxWidth().padding(top = 40.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(colors.actionSurface, colors.actionSurface, colors.actionSurface.copy(alpha = .0f))))
            .border(1.dp, accent.copy(alpha = .5f), RoundedCornerShape(16.dp))
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = if (active && !noServers) onDisconnect else onConnect)
            .padding(start = 76.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)) {
            Text(label, color = colors.onActionSurface, fontWeight = FontWeight.Black, fontSize = 20.sp,
                letterSpacing = 0.5.sp)
            subtitle?.let {
                Text(it, color = colors.onActionSurface.copy(alpha = .7f), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        // Companion perches on the button's top-left edge, overlapping upward.
        Image(painterResource(companion), contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp).offset(y = (-34).dp).height(64.dp))
        // Status dot pinned to the button's trailing edge.
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
            .size(10.dp).background(accent, CircleShape))
    }
}

@Composable
private fun HeroLatency(delayMs: Int?, modifier: Modifier = Modifier) {
    if (delayMs == null) return
    val colors = AfterglowTheme.colors
    // Match LatencyBadge semantics: <800 = healthy, >=800 = degraded. Use amber
    // for degraded so the dot stays visible on the red transitional/error heroes.
    val tone = if (delayMs < 800) colors.jade else colors.amber
    Row(modifier.clip(RoundedCornerShape(20.dp))
        .background(colors.onActionSurface.copy(alpha = .14f))
        .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(tone, CircleShape))
        Text(stringResource(R.string.servers_latency_ms, delayMs),
            color = colors.onActionSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun SubscriptionBadge() {
    val colors = AfterglowTheme.colors
    Box(Modifier.size(38.dp).border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val outline = Path().apply {
                moveTo(size.width * .2f, size.height * .1f)
                lineTo(size.width * .68f, size.height * .1f)
                lineTo(size.width * .82f, size.height * .26f)
                lineTo(size.width * .82f, size.height * .9f)
                lineTo(size.width * .2f, size.height * .9f)
                close()
            }
            drawPath(outline, colors.coral, style = stroke)
            drawLine(colors.coral, Offset(size.width * .32f, size.height * .52f),
                Offset(size.width * .68f, size.height * .52f), strokeWidth = stroke.width)
            drawLine(colors.coral, Offset(size.width * .32f, size.height * .7f),
                Offset(size.width * .6f, size.height * .7f), strokeWidth = stroke.width)
        }
    }
}

@Composable
private fun ServerListBadge() {
    val colors = AfterglowTheme.colors
    Box(Modifier.size(38.dp).border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            // Stacked server rows — the list affordance for the Servers screen.
            val stroke = 1.9.dp.toPx()
            listOf(.28f, .5f, .72f).forEach { y ->
                drawLine(colors.coral, Offset(size.width * .2f, size.height * y),
                    Offset(size.width * .8f, size.height * y), strokeWidth = stroke,
                    cap = StrokeCap.Round)
                drawCircle(colors.coral, stroke * .8f, Offset(size.width * .2f, size.height * y))
            }
        }
    }
}

@Composable
private fun ServerBadge(auto: Boolean, modifier: Modifier = Modifier) {
    val colors = AfterglowTheme.colors
    Box(modifier.size(38.dp).border(1.dp, colors.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        if (auto) Icon(Icons.Default.Refresh, contentDescription = null,
            tint = colors.coral, modifier = Modifier.size(22.dp))
        else Canvas(Modifier.size(20.dp)) {
            // Globe: outer circle + two meridians + equator. Distinct from the
            // horizontal-stack "server list" glyph used on the shortcut card.
            val stroke = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round)
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - stroke.width
            drawCircle(colors.coral, r, c, style = stroke)
            drawOval(colors.coral, topLeft = Offset(c.x - r * .45f, c.y - r),
                size = androidx.compose.ui.geometry.Size(r * .9f, r * 2f), style = stroke)
            drawLine(colors.coral, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = stroke.width)
        }
    }
}

@Composable
private fun CircledChevron() {
    val colors = AfterglowTheme.colors
    Box(Modifier.size(32.dp).border(1.dp, colors.border, CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = colors.coral, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ServerAndRouting(ui: HomeUiState, onPick: () -> Unit, onAdd: () -> Unit, onRouting: () -> Unit) {
    val colors = AfterglowTheme.colors
    val noServers = ui.serverOptions.isEmpty() && (ui.connection is VpnConnectionState.Idle || ui.connection is VpnConnectionState.Error)
    val live = when (val state = ui.connection) {
        is VpnConnectionState.Connected -> state.node
        is VpnConnectionState.Connecting -> state.node
        is VpnConnectionState.Preparing -> state.node
        is VpnConnectionState.Reconnecting -> state.node
        else -> null
    }
    val title = when {
        noServers -> stringResource(R.string.common_no_servers_yet)
        ui.autoSelected -> stringResource(R.string.afterglow_auto_select)
        else -> ui.selectedNodeName ?: live?.name ?: ui.selectedNodeNameRes?.let { stringResource(it) }
            ?: stringResource(R.string.home_no_server_selected)
    }
    val protocolLabel = (if (ui.autoSelected) ui.bestLatencyNodeProtocol else ui.selectedNodeProtocol ?: live?.protocol?.label)
        ?.takeIf { it.isNotBlank() && !it.equals("Other", ignoreCase = true) }
    // Latency already lives in the hero chip; don't echo a latency-derived
    // subtitle here. For auto-select show which node the urltest picked.
    val subtitle = when {
        noServers -> stringResource(if (ui.noNodesAtAll) R.string.common_add_servers_hint else R.string.afterglow_no_usable_servers)
        ui.autoSelected -> ui.bestLatencyNodeName ?: stringResource(R.string.home_auto_subtitle)
        else -> null
    }
    val metadata = listOfNotNull(ui.subscriptionName, protocolLabel).filter { it.isNotBlank() }.joinToString(" · ")
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.afterglow_selected_server),
            Modifier.padding(start = 2.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().border(1.dp, colors.border).background(colors.paperSecondary)) {
        Row(Modifier.fillMaxWidth().semantics { role = Role.Button }
            .clickable(onClick = if (noServers) onAdd else onPick)
            .heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerBadge(ui.autoSelected && !noServers)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = colors.muted, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp))
                if (!noServers && metadata.isNotEmpty()) Text(metadata, color = colors.muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            CircledChevron()
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().semantics { role = Role.Button }
            .clickable(onClick = onRouting).heightIn(min = 58.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Symmetrical rounded square badge matching the two-line text block height.
            Box(Modifier.size(38.dp).border(1.dp, colors.border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(20.dp)) {
                    val stroke = Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val w = size.width
                    val h = size.height
                    val topPath = Path().apply {
                        // Stem from mid-left going up then turning right
                        moveTo(w * 0.18f, h * 0.55f)
                        lineTo(w * 0.18f, h * 0.32f)
                        quadraticTo(w * 0.18f, h * 0.18f, w * 0.36f, h * 0.18f)
                        lineTo(w * 0.82f, h * 0.18f)
                        // Arrowhead pointing right
                        moveTo(w * 0.64f, h * 0.05f)
                        lineTo(w * 0.84f, h * 0.18f)
                        lineTo(w * 0.64f, h * 0.31f)
                    }
                    drawPath(topPath, colors.coral, style = stroke)

                    val bottomPath = Path().apply {
                        // Stem from mid-right going down then turning left
                        moveTo(w * 0.82f, h * 0.45f)
                        lineTo(w * 0.82f, h * 0.68f)
                        quadraticTo(w * 0.82f, h * 0.82f, w * 0.64f, h * 0.82f)
                        lineTo(w * 0.18f, h * 0.82f)
                        // Arrowhead pointing left
                        moveTo(w * 0.36f, h * 0.69f)
                        lineTo(w * 0.16f, h * 0.82f)
                        lineTo(w * 0.36f, h * 0.95f)
                    }
                    drawPath(bottomPath, colors.coral, style = stroke)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.afterglow_routing_title).uppercase(),
                    color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Text(ui.routingMode?.let { routeModeSummary(it) } ?: stringResource(R.string.common_none),
                    color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.padding(top = 2.dp))
                if (ui.routingPending) Text(stringResource(R.string.afterglow_pending), color = colors.coral, fontSize = 12.sp)
            }
            CircledChevron()
        }
        }
    }
}

@Composable
private fun SessionStats(
    state: VpnConnectionState.Connected,
    onOpenConnections: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val colors = AfterglowTheme.colors
    val history = remember(state.since) { TrafficHistory() }
    var samples by remember(state.since) { mutableStateOf<List<TrafficHistory.Point>>(emptyList()) }
    var now by remember(state.since) { mutableStateOf(Instant.now()) }
    var nowNanos by remember(state.since) { mutableLongStateOf(System.nanoTime()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, state.since) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); nowNanos = System.nanoTime(); delay(1000) }
        }
    }
    val stats = state.stats
    LaunchedEffect(state.since, state.statsReceivedAtNanos) {
        if (stats != null) {
            history.observe(state.statsReceivedAtNanos, stats.downlinkBytesPerSec, stats.uplinkBytesPerSec)
            samples = history.recent(System.nanoTime())
        }
    }
    // The 1 Hz ticker may predate a just-arrived snapshot. Read the monotonic
    // clock for this frame so a negative age cannot flash "unavailable".
    val displayNanos = frameTimeNanos(nowNanos, System.nanoTime())
    val fresh = stats != null && history.isFresh(displayNanos, state.statsReceivedAtNanos)
    val visibleSamples = samples.filter { it.atNanos in (displayNanos - TrafficHistory.WINDOW_NANOS)..displayNanos }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(stringResource(R.string.afterglow_statistics), Modifier.weight(1f))
        val elapsed = Duration.between(state.since, now).seconds.coerceAtLeast(0)
        Text(stringResource(R.string.afterglow_session_elapsed,
            String.format(Locale.ROOT, "%02d:%02d", elapsed / 60, elapsed % 60)),
            color = colors.muted, fontSize = 12.sp, maxLines = 1)
    }
    val s = stats.takeIf { fresh }
    Column(Modifier.fillMaxWidth().border(1.dp, colors.border).background(colors.paperSecondary)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            SpeedMetric(stringResource(R.string.home_stat_download), s?.let { formatRate(it.downlinkBytesPerSec) },
                s?.let { formatBytes(it.downlinkTotalBytes) },
                visibleSamples, displayNanos, true, colors.jade, Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
            SpeedMetric(stringResource(R.string.home_stat_upload), s?.let { formatRate(it.uplinkBytesPerSec) },
                s?.let { formatBytes(it.uplinkTotalBytes) },
                visibleSamples, displayNanos, false, colors.coral, Modifier.weight(1f))
        }
        if (s == null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Text(stringResource(R.string.afterglow_stats_unavailable), color = colors.muted,
                modifier = Modifier.padding(16.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().semantics { role = Role.Button }
            .clickable(onClick = onOpenConnections).heightIn(min = 48.dp)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_stat_connections), color = colors.ink,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (s != null) Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 10.dp)) {
                Text("↓", color = colors.jade, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(" ${s.connectionsIn}", color = colors.muted, fontSize = 13.sp)
                Text("  ↑", color = colors.coral, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(" ${s.connectionsOut}", color = colors.muted, fontSize = 13.sp)
            }
            CircledChevron()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().semantics { role = Role.Button }
            .clickable(onClick = onOpenDetails).heightIn(min = 48.dp)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_details_title), color = colors.ink,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            CircledChevron()
        }
    }
}

@Composable
private fun SpeedMetric(label: String, value: String?, total: String?, samples: List<TrafficHistory.Point>,
    nowNanos: Long, download: Boolean, lineColor: Color, modifier: Modifier) {
    val colors = AfterglowTheme.colors
    Column(modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (download) "↓" else "↑", color = lineColor, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
            Text(label, color = colors.muted, fontSize = 12.sp, maxLines = 1)
            if (total != null) Text(total, color = colors.muted, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Text(value ?: "—", color = colors.ink, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Canvas(Modifier.fillMaxWidth().height(32.dp).padding(top = 4.dp)) {
            val baseline = size.height - 1.dp.toPx()
            drawLine(colors.border, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
            val maxRate = samples.maxOfOrNull { if (download) it.down else it.up } ?: 0L
            val scale = maxOf(32.0, maxRate.toDouble() * 1.2)
            val start = nowNanos - TrafficHistory.WINDOW_NANOS
            fun point(sample: TrafficHistory.Point): Offset {
                val rate = if (download) sample.down else sample.up
                val x = size.width * ((sample.atNanos - start).toDouble() / TrafficHistory.WINDOW_NANOS).toFloat()
                return Offset(x.coerceIn(0f, size.width),
                    baseline * (1f - (rate / scale).toFloat().coerceIn(0f, 1f)))
            }
            val segment = mutableListOf<TrafficHistory.Point>()
            fun drawSegment() {
                if (segment.isEmpty()) return
                val first = point(segment.first())
                if (segment.size == 1) {
                    drawCircle(lineColor, 1.5.dp.toPx(), first)
                } else {
                    val line = Path().apply {
                        moveTo(first.x, first.y)
                        segment.drop(1).forEach { val p = point(it); lineTo(p.x, p.y) }
                    }
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(point(segment.last()).x, baseline)
                        lineTo(first.x, baseline)
                        close()
                    }
                    drawPath(fill, lineColor.copy(alpha = .12f))
                    drawPath(line, lineColor, style = Stroke(1.75.dp.toPx()))
                }
                segment.clear()
            }
            samples.forEach { sample ->
                if (segment.isNotEmpty() && sample.atNanos - segment.last().atNanos > TrafficHistory.GAP_NANOS) drawSegment()
                segment.add(sample)
            }
            drawSegment()
        }
    }
}

@Composable
private fun StatusNotice(message: String, onClick: () -> Unit, error: Boolean) {
    val colors = AfterglowTheme.colors
    Column(Modifier.fillMaxWidth().background(if (error) colors.errorSurface else colors.paperSecondary)
        .semantics { role = Role.Button }
        .clickable(onClick = onClick).padding(16.dp)) {
        Text(if (error) stringResource(R.string.home_status_failed) else stringResource(R.string.afterglow_restart_guard_title),
            color = colors.ink, fontWeight = FontWeight.Bold)
        Text(message, color = colors.ink, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(if (error) R.string.afterglow_open_diagnostics else R.string.common_dismiss),
            color = colors.coral, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    }
}
