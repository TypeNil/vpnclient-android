package dev.typenil.vpnclient.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.ui.common.BatteryHintCard
import dev.typenil.vpnclient.core.common.formatBytes
import dev.typenil.vpnclient.core.common.formatRate
import dev.typenil.vpnclient.ui.common.localizedResources
import dev.typenil.vpnclient.ui.common.routeModeSummary
import dev.typenil.vpnclient.ui.common.uptimeText
import dev.typenil.vpnclient.ui.theme.AfterglowCard
import dev.typenil.vpnclient.ui.theme.AfterglowChevron
import dev.typenil.vpnclient.ui.theme.AfterglowInsetDivider
import dev.typenil.vpnclient.ui.theme.AfterglowNotice
import dev.typenil.vpnclient.ui.theme.AfterglowOverline
import dev.typenil.vpnclient.ui.theme.AfterglowRow
import dev.typenil.vpnclient.ui.theme.AfterglowSectionHeader
import dev.typenil.vpnclient.ui.theme.AfterglowType
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.NoticeTone
import dev.typenil.vpnclient.ui.theme.StateTone
import dev.typenil.vpnclient.ui.theme.stateColors
import kotlinx.coroutines.delay
import java.time.Instant

internal enum class VisualState { Offline, Preparing, Permission, Connecting, Connected, Reconnecting, Stopping, Error }

/** Disconnected is neutral, in-flight states are warm, Connected is calm, only Error is red. */
internal fun VisualState.tone() = when (this) {
    VisualState.Offline, VisualState.Stopping -> StateTone.Neutral
    VisualState.Preparing, VisualState.Permission, VisualState.Connecting, VisualState.Reconnecting -> StateTone.Progress
    VisualState.Connected -> StateTone.Positive
    VisualState.Error -> StateTone.Error
}

/** A Connected hero with a failed check turns amber (not red: the tunnel itself is up). */
internal fun heroTone(state: VisualState, hint: HomeHealthHint) =
    if (state == VisualState.Connected && hint != HomeHealthHint.None) StateTone.Progress else state.tone()

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
    onOpenServers: () -> Unit = {},
    onOpenSubscriptions: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onDismissGuard: () -> Unit,
    notices: List<HomeNotice>,
    onDismissSelection: () -> Unit = {},
    onDismissBatteryHint: () -> Unit = {},
) {
    val colors = AfterglowTheme.colors
    val state = ui.connection
    val noServers = ui.serverOptions.isEmpty() && (state is VpnConnectionState.Idle || state is VpnConnectionState.Error)
    // Measured, not fixed: the scroll reserve tracks the real action height
    // (+ modest gap) so large fonts can't bury the tail of the content.
    var actionHeightPx by remember { mutableIntStateOf(0) }
    val actionReserve = with(LocalDensity.current) { actionHeightPx.toDp() } + AfterglowTokens.cardGap
    Box(modifier.fillMaxSize().background(colors.paper)) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxHeight()
            .widthIn(max = HomeContentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AfterglowTokens.screenPadding)
            // The transparent action overlay includes the bottom bar inset;
            // its entire measured height is reserved below the scroll content.
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .padding(bottom = actionReserve)) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (isShortWindow(LocalConfiguration.current.screenHeightDp)) 48.dp else 58.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.brand_wordmark), fontSize = 31.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, color = colors.ink)
                Text(".", fontSize = 31.sp, fontWeight = FontWeight.Black, color = colors.coral)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings),
                        tint = colors.ink)
                }
            }
            ConnectionHero(state.visual(), noServers, ui.selectedDelayMs, ui.healthHint, ui.selectedDelayStaleMinutes)
            Spacer(Modifier.height(AfterglowTokens.cardGap))
            notices.forEach { notice ->
                HomeNoticeView(notice, onOpenDiagnostics, onDismissSelection, onDismissGuard, onDismissBatteryHint)
                Spacer(Modifier.height(AfterglowTokens.cardGap))
            }
            // Selected server + routing and live stats carry the session —
            // shortcuts trail them instead of pushing them below the fold.
            ServerAndRouting(ui.withAppliedServerOnRejection(), onPick, if (ui.noNodesAtAll) onAddServer else onOpenServers, onOpenRouting)
            if (state is VpnConnectionState.Connected) {
                SessionStats(state, onOpenConnections, onOpenDetails)
            } else if (showLastSession(state, ui.lastSession)) {
                LastSessionCard(ui.lastSession!!)
            }
            // Cards are siblings: one gap between them, no extra section header.
            Spacer(Modifier.height(AfterglowTokens.cardGap))
            AfterglowCard {
                AfterglowRow(
                    title = stringResource(R.string.nav_servers),
                    onClick = onOpenServers,
                    caption = stringResource(R.string.afterglow_servers_available, (ui.serverOptions.size - 1).coerceAtLeast(0)),
                    leading = { ServerListIcon() },
                )
                AfterglowInsetDivider()
                AfterglowRow(
                    title = stringResource(R.string.nav_subscriptions),
                    onClick = onOpenSubscriptions,
                    caption = stringResource(R.string.afterglow_subscriptions_enabled, ui.enabledSubscriptionCount),
                    leading = { SubscriptionIcon() },
                )
            }
        }
        // Transparent, non-interactive decoration: only the action row handles
        // input. Measure the mascot space and nav inset too for the scroll tail.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { actionHeightPx = it.height }
            .navigationBarsPadding()) {
            ConnectionAction(state.visual(), noServers, if (noServers) {
                if (ui.noNodesAtAll) onAddServer else onOpenServers
            } else onConnect, onDisconnect,
                Modifier.align(Alignment.TopCenter).widthIn(max = HomeContentMaxWidth)
                    .fillMaxWidth().padding(horizontal = AfterglowTokens.screenPadding),
                hasNodes = !ui.noNodesAtAll)
        }
    }
}

/** Wide screens center the editorial column instead of stretching it. */
private val HomeContentMaxWidth = 560.dp

@Composable
private fun ConnectionHero(
    state: VisualState, noServers: Boolean, delayMs: Int? = null,
    healthHint: HomeHealthHint = HomeHealthHint.None, staleMinutes: Int? = null,
) {
    val colors = AfterglowTheme.colors
    val connected = state == VisualState.Connected
    val hint = if (connected) healthHint else HomeHealthHint.None
    val stateColors = colors.stateColors(heroTone(state, hint))
    val target = stateColors.hero
    // Token tween, not the default spring — hero color shifts are state
    // feedback, not physics; the standard duration matches the other state
    // colors (accent, action surface) so the whole surface lands together.
    val base by animateColorAsState(target, animationSpec = tween(AfterglowTokens.motionStandard),
        label = "Hero status")
    val headline = stringResource(when {
        noServers && state == VisualState.Offline -> R.string.afterglow_no_servers_headline
        else -> when (state) {
            VisualState.Offline -> R.string.home_status_disconnected
            VisualState.Connected -> R.string.home_status_connected
            VisualState.Error -> R.string.home_status_failed
            VisualState.Reconnecting -> R.string.afterglow_status_reconnecting
            VisualState.Preparing -> R.string.home_status_preparing
            VisualState.Permission -> R.string.afterglow_permission
            VisualState.Connecting -> R.string.home_status_connecting
            VisualState.Stopping -> R.string.home_status_disconnecting
        }
    })
    val fontScale = LocalDensity.current.fontScale
    val largeFont = fontScale > 1.3f
    // Landscape keeps a working viewport — compact hero, same type sizes.
    val heroMin = if (isShortWindow(LocalConfiguration.current.screenHeightDp)) 128f else 164f
    val heroMinEff = if (largeFont) heroMin + (fontScale - 1.3f) * 85f else heroMin
    val artScale = heroArtScale(fontScale)
    // The art is as tall as the hero, which grows with wrapped text at large fonts — the hint cap
    // follows the measured height (taller hero -> narrower cap -> at most more lines, bounded by the 40% floor, so it settles).
    var heroHeightPx by remember { mutableIntStateOf(0) }
    val heroHeightDp = maxOf(heroMinEff, with(LocalDensity.current) { heroHeightPx.toDp().value })
    Box(Modifier.fillMaxWidth().heightIn(min = heroMinEff.dp).onSizeChanged { heroHeightPx = it.height }
        .clip(AfterglowTokens.cardShape).background(Brush.horizontalGradient(listOf(base, base, colors.actionSurface)))) {
        Spacer(Modifier.fillMaxWidth().height(heroMin.dp))
        // Decorative artwork never participates in the hero's measurement.
        Box(Modifier.matchParentSize()) {
            Image(painterResource(R.drawable.afterglow_mascot), contentDescription = null,
                contentScale = ContentScale.Crop,
                // Large fonts widen the headline — the art shrinks (bottom-right) instead of
                // sitting under the text; unchanged at fontScale <= 1.3.
                modifier = Modifier.fillMaxHeight(artScale).aspectRatio(1f)
                    .align(Alignment.BottomEnd).offset(x = 18.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(
                            0f to Color.Transparent, .45f to Color.Black, 1f to Color.Black,
                        ), blendMode = BlendMode.DstIn)
                    })
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to base, .3f to base.copy(alpha = .92f), .7f to Color.Transparent)))
        // Width is measured so the hint lines can stop short of the mascot face
        // (it hangs off the bottom-right) instead of sliding under it.
        BoxWithConstraints(Modifier.align(Alignment.CenterStart)) {
            val hintCap = heroHintMaxWidthDp(maxWidth.value, heroHeightDp, artScale).dp
            Column(Modifier.fillMaxWidth(if (largeFont) .82f else .72f)
                .padding(start = 18.dp, end = 8.dp, top = 20.dp, bottom = 20.dp)) {
                Text(stringResource(R.string.afterglow_your_connection),
                    color = stateColors.onHeroMuted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = .8.sp,
                    modifier = Modifier.padding(bottom = 8.dp))
                // Only the state headline announces changes — never the latency/stats.
                // The hint is read as part of the title (one Polite announcement, only when the hint
                // changes); its own node is hidden so it is not spoken twice.
                val hintText = hint.textRes()?.let { stringResource(it) }
                Text(headline, color = stateColors.onHero, fontWeight = FontWeight.Bold,
                    fontSize = 28.sp, lineHeight = 33.sp,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (hintText != null) contentDescription = "$headline. $hintText"
                    })
                if (hintText != null) {
                    Text(hintText, color = stateColors.onHero, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 6.dp).widthIn(max = hintCap).clearAndSetSemantics {})
                }
                HeroLatency(delayMs, stateColors.onHero, Modifier.padding(top = 8.dp))
                if (connected && staleMinutes != null && (delayMs == null || delayMs <= 0)) {
                    Text(stringResource(R.string.home_latency_outdated, staleMinutes),
                        color = stateColors.onHeroMuted, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = hintCap))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter)
            .background(stateColors.line))
    }
}

@Composable
private fun ConnectionAction(state: VisualState, noServers: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit,
    modifier: Modifier = Modifier, hasNodes: Boolean = false) {
    val colors = AfterglowTheme.colors
    // Content and behaviour come from the pure spec; this composable only draws it.
    val spec = connectionActionSpec(state, noServers, hasNodes)
    val label = stringResource(spec.labelRes)
    val caption = spec.captionRes?.let { stringResource(it) }
    val accent = colors.stateColors(spec.tone).accent
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
    val tint = colors.stateColors(spec.tone).hero
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) .98f else 1f,
        animationSpec = tween(AfterglowTokens.motionFast), label = "Connection press")
    val animatedAccent by animateColorAsState(accent, animationSpec = tween(AfterglowTokens.motionStandard),
        label = "Connection accent")
    val gradientEnd by animateColorAsState(lerp(colors.actionSurface, tint, .4f),
        animationSpec = tween(AfterglowTokens.motionEmphasized), label = "Connection surface")
    var shownFace by remember { mutableIntStateOf(companion) }
    val faceAlpha = remember { Animatable(1f) }
    LaunchedEffect(companion) {
        if (shownFace != companion) {
            // Swap one sprite while invisible: crossfading two expressions made
            // overlapping eyes and paws look like a flicker during quick states.
            faceAlpha.animateTo(0f, tween(AfterglowTokens.motionFast))
            shownFace = companion
            faceAlpha.animateTo(1f, tween(AfterglowTokens.motionEmphasized))
        }
    }
    // Decorative mascot earns its pad only on tall-enough screens; on
    // landscape/short heights it leaves so the CTA keeps usable viewport.
    val shortWindow = isShortWindow(LocalConfiguration.current.screenHeightDp)
    val showMascot = !shortWindow
    Box(modifier.fillMaxWidth().padding(top = if (showMascot) AfterglowTokens.mascotReserve else 0.dp)
        .graphicsLayer { scaleX = pressScale; scaleY = pressScale }) {
        // Grows with wrapped text at large fonts (min height, never a fixed one).
        Row(Modifier.fillMaxWidth().clip(AfterglowTokens.barShape)
            .background(Brush.horizontalGradient(listOf(colors.actionSurface, gradientEnd)))
            .border(AfterglowTokens.border, colors.onActionSurface.copy(alpha = .18f), AfterglowTokens.barShape)
            // The caption is the state; the spoken label stays the action, so it is not read twice.
            .semantics { if (caption != null) stateDescription = caption }
            .clickable(enabled = spec.enabled, role = Role.Button, interactionSource = interaction, indication = null,
                onClick = if (spec.tap == ActionTap.Stop) onDisconnect else onConnect)
            .heightIn(min = if (shortWindow) AfterglowTokens.actionBarHeightCompact else AfterglowTokens.actionBarHeight)
            .padding(AfterglowTokens.actionPadding),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (shortWindow) AfterglowTokens.tileSize else AfterglowTokens.actionTileSize)
                .clip(AfterglowTokens.actionTileShape).background(animatedAccent),
                contentAlignment = Alignment.Center) {
                val glyph = Modifier.size(AfterglowTokens.actionIconSize)
                when (spec.icon) {
                    ActionIcon.Next -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        tint = colors.actionSurface, modifier = glyph)
                    ActionIcon.Refresh -> Icon(Icons.Default.Refresh, contentDescription = null,
                        tint = colors.actionSurface, modifier = glyph)
                    ActionIcon.Lock -> Icon(Icons.Default.Lock, contentDescription = null,
                        tint = colors.actionSurface, modifier = glyph)
                    ActionIcon.Spinner -> CircularProgressIndicator(glyph, color = colors.actionSurface,
                        strokeWidth = AfterglowTokens.actionStroke, trackColor = Color.Transparent)
                    ActionIcon.Power -> Canvas(glyph) {
                        val stroke = AfterglowTokens.actionStroke.toPx()
                        drawArc(colors.actionSurface, -45f, 270f, false,
                            style = Stroke(stroke, cap = StrokeCap.Round))
                        drawLine(colors.actionSurface, Offset(size.width / 2, size.height * .06f),
                            Offset(size.width / 2, size.height * .53f), stroke, cap = StrokeCap.Round)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = AfterglowTokens.actionPadding), verticalArrangement = Arrangement.Center) {
                // Localized labels grow with font scale — three lines keeps a
                // long CTA honest without hiding it behind the mascot strip.
                Text(label, color = colors.onActionSurface, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (caption != null) {
                    Text(caption, color = colors.onActionSurface.copy(alpha = .72f), fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {})
                }
            }
        }
        // Transparent sprite padding lets the paws rest just inside the edge;
        // the label column still owns the row — no in-row mascot spacer.
        if (showMascot) {
            Image(painterResource(shownFace), contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = AfterglowTokens.mascotInset)
                    .offset(y = -AfterglowTokens.mascotLift)
                    .height(AfterglowTokens.mascotHeight).graphicsLayer {
                        alpha = faceAlpha.value
                        scaleX = .97f + .03f * faceAlpha.value
                        scaleY = scaleX
                        translationY = (1f - faceAlpha.value) * 4.dp.toPx()
                    })
        }
    }
}

@Composable
private fun HeroLatency(delayMs: Int?, onHero: Color, modifier: Modifier = Modifier) {
    if (delayMs == null || delayMs <= 0) return
    val colors = AfterglowTheme.colors
    // Match LatencyBadge semantics: <800 = healthy, >=800 = degraded. Use amber
    // for degraded so the dot stays visible on the red transitional/error heroes.
    val tone = if (delayMs < 800) colors.jade else colors.amber
    Row(modifier.clip(RoundedCornerShape(20.dp))
        .background(onHero.copy(alpha = .14f))
        .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(tone, CircleShape))
        Text(stringResource(R.string.afterglow_measured_latency, delayMs),
            color = onHero, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun SubscriptionIcon() {
    val colors = AfterglowTheme.colors
    Canvas(Modifier.size(AfterglowTokens.iconSize)) {
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

@Composable
private fun ServerListIcon() {
    val colors = AfterglowTheme.colors
    Canvas(Modifier.size(AfterglowTokens.iconSize)) {
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

@Composable
private fun ServerIcon() {
    val colors = AfterglowTheme.colors
    Canvas(Modifier.size(AfterglowTokens.iconSize)) {
        // Globe: outer circle + two meridians + equator. Distinct from the
        // horizontal-stack "server list" glyph used in Quick access.
        val stroke = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round)
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - stroke.width
        drawCircle(colors.coral, r, c, style = stroke)
        drawOval(colors.coral, topLeft = Offset(c.x - r * .45f, c.y - r),
            size = androidx.compose.ui.geometry.Size(r * .9f, r * 2f), style = stroke)
        drawLine(colors.coral, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = stroke.width)
    }
}

@Composable
private fun RoutingIcon() {
    val colors = AfterglowTheme.colors
    Canvas(Modifier.size(AfterglowTokens.iconSize)) {
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
    // A measured candidate is not necessarily the member used by the session.
    val autoResolved = if (ui.autoSelected) {
        (ui.connection as? VpnConnectionState.Connected)?.node?.name
            ?.substringAfter("→", "")?.trim()?.takeIf { it.isNotBlank() }
    } else null
    val title = when {
        // The hero already says "No servers"; the card carries the one action hint.
        noServers -> stringResource(
            if (ui.noNodesAtAll) R.string.common_add_servers_hint else R.string.afterglow_no_usable_servers,
        )
        autoResolved != null -> autoResolved
        ui.autoSelected -> stringResource(R.string.home_auto_subtitle)
        else -> ui.selectedNodeName ?: live?.name ?: stringResource(R.string.home_no_server_selected)
    }
    val protocolLabel = if (ui.autoSelected) null else (ui.selectedNodeProtocol ?: live?.protocol?.label)
        ?.takeIf { it.isNotBlank() && !it.equals("Other", ignoreCase = true) }
    val modeLabel = if (ui.autoSelected) stringResource(R.string.afterglow_auto) else protocolLabel
    AfterglowCard {
        AfterglowRow(
            title = title,
            onClick = if (noServers) onAdd else onPick,
            overline = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AfterglowTokens.headerGap), verticalArrangement = Arrangement.spacedBy(AfterglowTokens.headerInset)) {
                    AfterglowOverline(stringResource(R.string.afterglow_selected_server))
                    if (!noServers && modeLabel != null) Text(modeLabel, style = AfterglowType.overline, color = colors.coral)
                }
            },
            leading = { ServerIcon() },
        )
        AfterglowInsetDivider()
        AfterglowRow(
            title = ui.routingMode?.let { routeModeSummary(it) } ?: stringResource(R.string.common_none),
            onClick = onRouting,
            caption = if (ui.routingPending) stringResource(R.string.afterglow_pending) else null,
            captionColor = colors.coral,
            overline = { AfterglowOverline(stringResource(R.string.afterglow_routing_title)) },
            leading = { RoutingIcon() },
        )
    }
}

@Composable
private fun SessionStats(state: VpnConnectionState.Connected, onOpenConnections: () -> Unit, onOpenDetails: () -> Unit) {
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
    // clock for this frame so a negative age cannot flash a stale dash.
    val displayNanos = frameTimeNanos(nowNanos, System.nanoTime())
    val fresh = stats != null && history.isFresh(displayNanos, state.statsReceivedAtNanos)
    val visibleSamples = samples.filter { it.atNanos in (displayNanos - TrafficHistory.WINDOW_NANOS)..displayNanos }
    val compact = LocalDensity.current.fontScale > 1.3f ||
        LocalConfiguration.current.screenWidthDp < 340
    val res = localizedResources()
    val dash = stringResource(R.string.common_none)
    val texts = StatTexts(
        downloadLabel = stringResource(R.string.home_stat_download),
        uploadLabel = stringResource(R.string.home_stat_upload),
        rate = { res.formatRate(it) },
        bytes = { res.formatBytes(it) },
        totalLine = { res.getString(R.string.home_stat_total_suffix, it) },
        describe = { label, rate, total -> res.getString(R.string.home_stat_column_desc, label, rate, total) },
        describeEmpty = { res.getString(R.string.home_stat_column_empty, it) },
        dash = dash,
    )
    val (download, upload) = statColumns(stats.takeIf { fresh }, texts)
    val connections = connectionsRow(
        stats.takeIf { fresh },
        ConnectionsTexts(
            describe = { i, o -> res.getString(R.string.home_stat_connections_desc, i, o) },
            describeEmpty = res.getString(R.string.home_stat_connections_empty),
            dash = dash,
        ),
    )
    val chartSamples = if (fresh) visibleSamples else emptyList()
    // Shared uptime formatter — explicit localized units, days included,
    // never a raw 52:30:50 clock.
    val uptime = stringResource(R.string.afterglow_session_elapsed, uptimeText(state.since, now))
    val headerPadding = Modifier.fillMaxWidth()
        .padding(start = AfterglowTokens.headerInset, top = AfterglowTokens.sectionGap, bottom = AfterglowTokens.headerGap)
    if (compact) {
        // Large fonts: uptime sits on its own line instead of breaking mid-phrase.
        Column(headerPadding) {
            AfterglowSectionHeader(stringResource(R.string.afterglow_statistics))
            Text(uptime, style = AfterglowType.statLabel, color = colors.muted)
        }
    } else {
        // One shared baseline for the 12sp heading and the 12sp uptime.
        Row(headerPadding) {
            AfterglowSectionHeader(stringResource(R.string.afterglow_statistics), Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            Text(uptime, style = AfterglowType.statLabel, color = colors.muted, textAlign = TextAlign.End,
                modifier = Modifier.alignByBaseline())
        }
    }
    AfterglowCard {
        if (compact) {
            // Side-by-side columns can't survive large fonts/narrow widths — stack them.
            StatColumnView(download, chartSamples, displayNanos, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(AfterglowTokens.border).background(colors.border))
            StatColumnView(upload, chartSamples, displayNanos, Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                StatColumnView(download, chartSamples, displayNanos, Modifier.weight(1f))
                Box(Modifier.width(AfterglowTokens.border).fillMaxHeight().background(colors.border))
                StatColumnView(upload, chartSamples, displayNanos, Modifier.weight(1f))
            }
        }
        Box(Modifier.fillMaxWidth().height(AfterglowTokens.border).background(colors.border))
        ConnectionsRowView(connections, onOpenConnections)
        Box(Modifier.fillMaxWidth().height(AfterglowTokens.border).background(colors.border))
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenDetails)
            .heightIn(min = AfterglowTokens.touchTarget).padding(horizontal = AfterglowTokens.rowPaddingH),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_details_title), style = AfterglowType.rowCaption,
                fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.weight(1f))
            AfterglowChevron()
        }
    }
}

/** One announced node per column (label, rate and total together); the tile, chart and arrow are decorative. */
@Composable
private fun StatColumnView(col: StatColumn, samples: List<TrafficHistory.Point>, nowNanos: Long, modifier: Modifier) {
    val colors = AfterglowTheme.colors
    // Ink/coral arrows over a jade/coral tint — light jade alone fails contrast on paper.
    val arrowColor = if (col.download) colors.ink else colors.coral
    val accent = if (col.download) colors.jade else colors.coral
    Column(modifier.clearAndSetSemantics { contentDescription = col.description }
        .padding(horizontal = AfterglowTokens.noticePadding, vertical = AfterglowTokens.rowPaddingV)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(AfterglowTokens.statTileSize).clip(AfterglowTokens.chipShape)
                .background(accent.copy(alpha = AfterglowTokens.statTileTint)), contentAlignment = Alignment.Center) {
                StatArrow(col.download, arrowColor, AfterglowTokens.statArrowSize)
            }
            Text(col.label, style = AfterglowType.statLabel, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = AfterglowTokens.headerGap))
        }
        Text(col.value, style = AfterglowType.statValue, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = AfterglowTokens.headerGap))
        TrafficChart(samples, nowNanos, col.download, accent)
        Text(col.total, style = AfterglowType.statTotal, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Connections ↓in ↑out ›" — one TalkBack node, one button. */
@Composable
private fun ConnectionsRowView(row: ConnectionsRow, onClick: () -> Unit) {
    val colors = AfterglowTheme.colors
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = row.description }
        .heightIn(min = AfterglowTokens.touchTarget).padding(horizontal = AfterglowTokens.rowPaddingH),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_stat_connections), style = AfterglowType.rowCaption,
                fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.weight(1f))
            ConnectionsCount(true, row.incoming, colors.ink)
            Spacer(Modifier.width(AfterglowTokens.noticePadding))
            ConnectionsCount(false, row.outgoing, colors.coral)
            Spacer(Modifier.width(AfterglowTokens.headerGap))
        }
        AfterglowChevron()
    }
}

internal const val CONN_ARROW_DOWN_TAG = "conn_arrow_down"
internal const val CONN_ARROW_UP_TAG = "conn_arrow_up"

@Composable
internal fun ConnectionsCount(download: Boolean, text: String, arrowColor: Color) {
    // sp-based like the row text beside it: a fixed dp arrow shrinks relative to text at large fonts.
    val arrowSize = with(LocalDensity.current) { AfterglowTokens.connArrowSize.value.sp.toDp() }
    StatArrow(download, arrowColor, arrowSize, Modifier.testTag(if (download) CONN_ARROW_DOWN_TAG else CONN_ARROW_UP_TAG))
    Text(text, style = AfterglowType.rowCaption, fontWeight = FontWeight.SemiBold, color = AfterglowTheme.colors.ink,
        maxLines = 1, modifier = Modifier.padding(start = AfterglowTokens.headerInset))
}

@Composable
private fun TrafficChart(samples: List<TrafficHistory.Point>, nowNanos: Long, download: Boolean, lineColor: Color) {
    val colors = AfterglowTheme.colors
    var chartNanos by remember { mutableLongStateOf(nowNanos) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { chartNanos = System.nanoTime(); delay(100) }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(AfterglowTokens.chartHeight).padding(top = AfterglowTokens.chartGap)) {
            val baseline = size.height - AfterglowTokens.border.toPx()
            drawLine(colors.border, Offset(0f, baseline), Offset(size.width, baseline), AfterglowTokens.border.toPx())
            val maxRate = samples.maxOfOrNull { if (download) it.down else it.up } ?: 0L
            val scale = maxOf(32.0, maxRate.toDouble() * 1.2)
            val start = maxOf(nowNanos, chartNanos) - TrafficHistory.WINDOW_NANOS
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
                    drawCircle(lineColor, AfterglowTokens.chartLine.toPx(), first)
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
                    drawPath(fill, lineColor.copy(alpha = AfterglowTokens.chartFillAlpha))
                    drawPath(line, lineColor, style = Stroke(AfterglowTokens.chartLine.toPx()))
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

@Composable
private fun HomeNoticeView(
    notice: HomeNotice,
    onOpenDiagnostics: () -> Unit,
    onDismissSelection: () -> Unit,
    onDismissGuard: () -> Unit,
    onDismissBatteryHint: () -> Unit,
) {
    val dismiss = stringResource(R.string.common_dismiss)
    // A notice never repeats the hero headline ("Connection failed"): the hero
    // names the state, the notice carries the reason and its action.
    when (notice) {
        is HomeNotice.Failure -> AfterglowNotice(
            NoticeTone.Error, errorText(notice.error),
            actionLabel = stringResource(R.string.afterglow_open_diagnostics), onAction = onOpenDiagnostics,
        )
        is HomeNotice.SelectionRejected -> AfterglowNotice(
            NoticeTone.Warning, stringResource(R.string.home_selection_unencrypted),
            actionLabel = dismiss, onAction = onDismissSelection,
        )
        HomeNotice.PermissionExplanation -> AfterglowNotice(NoticeTone.Info, stringResource(R.string.afterglow_permission_explanation))
        HomeNotice.RestartGuard -> AfterglowNotice(
            NoticeTone.Warning, stringResource(R.string.home_restart_guard_text),
            title = stringResource(R.string.afterglow_restart_guard_title), actionLabel = dismiss, onAction = onDismissGuard,
        )
        HomeNotice.BatteryHint -> BatteryHintCard(onDismiss = onDismissBatteryHint)
    }
}
