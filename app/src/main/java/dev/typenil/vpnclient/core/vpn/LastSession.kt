package dev.typenil.vpnclient.core.vpn

/**
 * Summary of the most recent finished session. Local only: never logged, never sent,
 * excluded from backup/transfer (see `data_extraction_rules.xml`).
 *
 * [downBytes]/[upBytes] are totals for the whole session — summed across engine
 * restarts, because the core's counters restart with every new engine instance.
 */
data class LastSession(
    val endedAtEpochMs: Long,
    val durationMs: Long,
    val downBytes: Long,
    val upBytes: Long,
    val nodeName: String,
)

/** Where a finished session is persisted. Failures are the caller's to contain. */
fun interface LastSessionSink {
    suspend fun save(session: LastSession)
}

/**
 * Pure policy deciding when a session ends and what is recorded. Fed every state
 * transition by the manager; no Android types, clocks are injected.
 *
 * - A session starts at the first `Connected` and ends when the state becomes anything
 *   other than `Connected`/`Reconnecting`. A reconnect neither ends nor restarts it,
 *   so `Connected.since` (which resets on every reconnect) is not used.
 * - Totals come from the last stats snapshot seen on a `Connected` state, the one the
 *   Home statistics last showed. When the engine epoch changes the previous epoch's
 *   final totals are carried over (sum across epochs).
 * - Nothing is recorded for a session shorter than [MIN_DURATION_MS] or one that never
 *   received a stats snapshot.
 */
class LastSessionTracker(
    private val monotonicMs: () -> Long,
    private val wallClockMs: () -> Long,
) {
    private var active = false
    private var startMs = 0L
    private var nodeName = ""
    private var hasStats = false
    private var epoch = -1L
    private var carriedDown = 0L
    private var carriedUp = 0L
    private var lastDown = 0L
    private var lastUp = 0L

    /** Returns the record to persist when [next] ends a recordable session, else null. */
    @Synchronized
    fun onTransition(prev: VpnConnectionState, next: VpnConnectionState, engineEpoch: Long): LastSession? {
        when (prev) {
            is VpnConnectionState.Connected -> {
                nodeName = prev.node.name
                prev.stats?.let { observe(it.downlinkTotalBytes, it.uplinkTotalBytes, engineEpoch) }
            }
            is VpnConnectionState.Reconnecting -> nodeName = prev.node.name
            else -> Unit
        }
        if (next is VpnConnectionState.Connected) {
            if (!active) start(next.node.name)
            return null
        }
        if (next is VpnConnectionState.Reconnecting || !active) return null
        active = false
        val duration = monotonicMs() - startMs
        if (duration < MIN_DURATION_MS || !hasStats) return null
        return LastSession(
            endedAtEpochMs = wallClockMs(),
            durationMs = duration,
            downBytes = carriedDown + lastDown,
            upBytes = carriedUp + lastUp,
            nodeName = nodeName,
        )
    }

    private fun start(name: String) {
        active = true
        startMs = monotonicMs()
        nodeName = name
        hasStats = false
        epoch = -1L
        carriedDown = 0L
        carriedUp = 0L
        lastDown = 0L
        lastUp = 0L
    }

    private fun observe(down: Long, up: Long, engineEpoch: Long) {
        if (hasStats && engineEpoch != epoch) {
            carriedDown += lastDown
            carriedUp += lastUp
        }
        hasStats = true
        epoch = engineEpoch
        lastDown = down
        lastUp = up
    }

    companion object {
        const val MIN_DURATION_MS = 5_000L
    }
}
