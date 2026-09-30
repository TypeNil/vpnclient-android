package dev.typenil.vpnclient.ui.home

/** Observed rates, placed on a monotonic 30-second axis rather than by list index. */
internal class TrafficHistory {
    data class Point(val atNanos: Long, val down: Long, val up: Long)

    private val points = ArrayDeque<Point>()

    fun observe(atNanos: Long, down: Long, up: Long) {
        if (atNanos <= 0 || (points.isNotEmpty() && atNanos <= points.last().atNanos)) return
        val rawDown = down.coerceAtLeast(0)
        val rawUp = up.coerceAtLeast(0)
        val previous = points.lastOrNull()
        // Smooth only the chart; numeric rates and cumulative totals remain the core's actual values.
        // A paused status stream starts a new segment instead of blending with stale traffic.
        val alpha = if (previous == null || atNanos - previous.atNanos > GAP_NANOS) 1.0
            else 1.0 - kotlin.math.exp(-(atNanos - previous.atNanos).toDouble() / 1_500_000_000.0)
        points.addLast(Point(atNanos,
            (previous?.down?.let { it + (rawDown - it) * alpha } ?: rawDown.toDouble()).toLong(),
            (previous?.up?.let { it + (rawUp - it) * alpha } ?: rawUp.toDouble()).toLong()))
        while (points.isNotEmpty() && points.first().atNanos < atNanos - WINDOW_NANOS) points.removeFirst()
        while (points.size > 64) points.removeFirst()
    }

    fun recent(nowNanos: Long): List<Point> =
        points.filter { it.atNanos in (nowNanos - WINDOW_NANOS)..nowNanos }

    fun isFresh(nowNanos: Long, receiptNanos: Long): Boolean =
        receiptNanos > 0 && nowNanos - receiptNanos in 0 until STALE_NANOS

    companion object {
        const val WINDOW_NANOS = 30_000_000_000L
        const val STALE_NANOS = 10_000_000_000L
        const val GAP_NANOS = 2_500_000_000L
    }
}
