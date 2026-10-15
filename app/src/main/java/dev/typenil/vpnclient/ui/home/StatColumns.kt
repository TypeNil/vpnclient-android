package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.engine.TrafficStats

/** One Statistics column. [value]/[total] are already display text; [description] is the TalkBack label of the whole column. */
internal data class StatColumn(
    val download: Boolean,
    val label: String,
    val value: String,
    val total: String,
    val description: String,
)

/** Localized pieces injected so the mapping stays a pure function. */
internal class StatTexts(
    val downloadLabel: String,
    val uploadLabel: String,
    val rate: (Long) -> String,
    val bytes: (Long) -> String,
    val totalLine: (String) -> String,
    val describe: (label: String, rate: String, total: String) -> String,
    val describeEmpty: (label: String) -> String,
    val dash: String,
)

/**
 * Download first, Upload second. `null` stats (none yet, or stale) show a dash in
 * both columns — never a formatted zero that would read as "idle".
 */
internal fun statColumns(stats: TrafficStats?, t: StatTexts): Pair<StatColumn, StatColumn> {
    fun column(download: Boolean, label: String, rate: Long?, total: Long?): StatColumn =
        if (rate == null || total == null) StatColumn(download, label, t.dash, t.dash, t.describeEmpty(label))
        else {
            val r = t.rate(rate)
            val b = t.bytes(total)
            StatColumn(download, label, r, t.totalLine(b), t.describe(label, r, b))
        }
    return column(true, t.downloadLabel, stats?.downlinkBytesPerSec, stats?.downlinkTotalBytes) to
        column(false, t.uploadLabel, stats?.uplinkBytesPerSec, stats?.uplinkTotalBytes)
}
