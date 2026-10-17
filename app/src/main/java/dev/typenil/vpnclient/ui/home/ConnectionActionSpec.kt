package dev.typenil.vpnclient.ui.home

import androidx.annotation.StringRes
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.ui.theme.StateTone

internal enum class ActionIcon { Power, Spinner, Refresh, Next, Lock }

/** What a tap does. [Start] also covers the "no servers" shortcut: the caller maps it to navigation. */
internal enum class ActionTap { Start, Stop }

/** Everything the bottom bar shows or does for one state; the composable adds no logic of its own. */
internal data class ConnectionActionSpec(
    @StringRes val labelRes: Int,
    @StringRes val captionRes: Int?,
    val icon: ActionIcon,
    val tone: StateTone,
    val enabled: Boolean,
    val tap: ActionTap,
)

/**
 * Pure state -> bar content. Permission waits on the user, not on work in progress, so it shows a
 * still lock: a spinner's short-arc frames read as a stray "," while the system dialog is up. Captions exist only for stable states: the model carries no connect
 * stage, so a transitional state names its action in the label and has no caption to invent.
 * Preparing is the only phase the manager can abort, so only it offers Cancel.
 */
internal fun connectionActionSpec(state: VisualState, noServers: Boolean, hasNodes: Boolean): ConnectionActionSpec {
    val tone = state.tone()
    if (noServers) {
        return ConnectionActionSpec(
            if (hasNodes) R.string.nav_servers else R.string.common_add_server_cta,
            null, ActionIcon.Next, tone, enabled = true, ActionTap.Start,
        )
    }
    return when (state) {
        VisualState.Offline ->
            ConnectionActionSpec(R.string.afterglow_start, R.string.afterglow_offline, ActionIcon.Power, tone, true, ActionTap.Start)
        VisualState.Error ->
            ConnectionActionSpec(R.string.afterglow_retry, R.string.afterglow_error_caption, ActionIcon.Refresh, tone, true, ActionTap.Start)
        VisualState.Connected ->
            ConnectionActionSpec(R.string.action_disconnect, R.string.afterglow_online, ActionIcon.Power, tone, true, ActionTap.Stop)
        VisualState.Reconnecting ->
            ConnectionActionSpec(R.string.action_disconnect, null, ActionIcon.Power, tone, true, ActionTap.Stop)
        VisualState.Preparing ->
            ConnectionActionSpec(R.string.common_cancel, null, ActionIcon.Spinner, tone, true, ActionTap.Stop)
        VisualState.Connecting ->
            ConnectionActionSpec(R.string.home_status_connecting, null, ActionIcon.Spinner, tone, false, ActionTap.Start)
        VisualState.Permission ->
            ConnectionActionSpec(R.string.home_status_permission, null, ActionIcon.Lock, tone, false, ActionTap.Start)
        VisualState.Stopping ->
            ConnectionActionSpec(R.string.home_status_disconnecting, null, ActionIcon.Spinner, tone, false, ActionTap.Start)
    }
}
