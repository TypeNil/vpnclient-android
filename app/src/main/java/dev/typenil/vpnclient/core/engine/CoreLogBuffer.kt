package dev.typenil.vpnclient.core.engine

import dev.typenil.vpnclient.core.common.log.LogRing
import dev.typenil.vpnclient.core.common.log.Redactor

/** Owned by one engine, never persisted. Subscription tokens reject callbacks
 * from disconnected clients, including callbacks racing a stop or screen-off. */
class CoreLogBuffer {
    private val ring = LogRing(500)
    private var epoch = 0L
    private var running = false
    private var subscribed = false
    private var sensitiveValues: List<String> = emptyList()

    @Synchronized fun start(values: Collection<String>) {
        stop()
        sensitiveValues = values.filter { it.isNotBlank() }.distinct()
        running = true
    }

    @Synchronized fun subscribe(): Long {
        epoch++
        subscribed = running
        return epoch
    }

    @Synchronized fun pause() {
        epoch++
        subscribed = false
    }

    @Synchronized fun stop() {
        pause()
        running = false
        sensitiveValues = emptyList()
        ring.clear()
    }

    @Synchronized fun clear(token: Long) {
        if (subscribed && token == epoch) ring.clear()
    }

    /** Native levels: panic=0, fatal=1, error=2, warn=3, info=4. */
    @Synchronized fun add(token: Long, level: Int, message: String) {
        if (!subscribed || token != epoch || level !in 0..3) return
        val label = listOf("PANIC", "FATAL", "ERROR", "WARN")[level]
        ring.add(("$label " + Redactor.redactCore(message, sensitiveValues)).take(512))
    }

    @Synchronized fun snapshot(): List<String> = ring.snapshot()
}
