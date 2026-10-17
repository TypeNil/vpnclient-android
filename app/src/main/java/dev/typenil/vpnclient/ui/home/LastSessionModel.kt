package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.vpn.LastSession
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.ui.common.RelativeAge
import dev.typenil.vpnclient.ui.common.relativeAge

/** Home shows the card only while no session is live or starting, and only when a record exists. */
internal fun showLastSession(state: VpnConnectionState, record: LastSession?): Boolean =
    record != null && state !is VpnConnectionState.Connected &&
        state !is VpnConnectionState.Connecting && state !is VpnConnectionState.Reconnecting

/**
 * Age of the record, or null when it cannot be stated honestly: a record from the
 * future (the wall clock went backwards) reads "Ended earlier", never a negative number.
 */
internal fun endedAge(nowMs: Long, endedAtMs: Long): RelativeAge? =
    if (nowMs < endedAtMs) null else relativeAge(nowMs - endedAtMs)

/** One Downloaded/Uploaded column; [description] is the whole column's TalkBack label. */
internal data class LastSessionColumn(val download: Boolean, val label: String, val value: String, val description: String)

/** Localized pieces injected so the mapping stays a pure function. */
internal class LastSessionTexts(
    val downloadedLabel: String,
    val uploadedLabel: String,
    val bytes: (Long) -> String,
    val describe: (label: String, value: String) -> String,
)

/** Downloaded first, Uploaded second — the totals of the finished session. */
internal fun lastSessionColumns(s: LastSession, t: LastSessionTexts): Pair<LastSessionColumn, LastSessionColumn> {
    fun column(download: Boolean, label: String, total: Long): LastSessionColumn {
        val value = t.bytes(total)
        return LastSessionColumn(download, label, value, t.describe(label, value))
    }
    return column(true, t.downloadedLabel, s.downBytes) to column(false, t.uploadedLabel, s.upBytes)
}
