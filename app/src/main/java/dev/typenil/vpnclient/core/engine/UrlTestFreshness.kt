package dev.typenil.vpnclient.core.engine

/** Probe interval of the Auto (`urltest`) group — the single source for the
 *  compiled config (`"${URLTEST_INTERVAL_MINUTES}m"`) and for the freshness
 *  window below. */
const val URLTEST_INTERVAL_MINUTES = 3

/**
 * How old an [OutboundItemInfo.urlTestTime] may be and still be shown as a
 * current measurement: two missed probe cycles plus one minute of slack for
 * probe duration, whole-second truncation and status-poll lag. Older entries
 * are leftovers (probing stops after the group's idle timeout, a dead node
 * keeps its last good delay) and must not name a "best" node.
 */
const val URLTEST_FRESH_WINDOW_MS = (2L * URLTEST_INTERVAL_MINUTES + 1) * 60_000L

/**
 * The delay to display for this item, or null when it is missing, `0` time
 * (never measured), from the future (clock moved back) or older than
 * [URLTEST_FRESH_WINDOW_MS]. [nowMs] is the app wall clock; the core stamps
 * the same system clock, so comparing here is valid for freshness only —
 * ordering of two measurements of one tag still uses the stored baseline.
 */
fun OutboundItemInfo.freshDelayMs(nowMs: Long): Int? {
    val delay = urlTestDelayMs?.takeIf { it > 0 } ?: return null
    if (urlTestTime <= 0) return null
    val ageMs = nowMs - urlTestTime * 1000
    return delay.takeIf { ageMs in 0..URLTEST_FRESH_WINDOW_MS }
}

/** Lowest fresh delay among [items]; null when none is fresh. The core's own
 *  `tolerance` hysteresis is deliberately not re-implemented here. */
internal fun pickBestLatency(
    items: List<OutboundItemInfo>,
    nowMs: Long,
): OutboundItemInfo? =
    items
        .filter { it.freshDelayMs(nowMs) != null }
        .minByOrNull { it.urlTestDelayMs!! }
