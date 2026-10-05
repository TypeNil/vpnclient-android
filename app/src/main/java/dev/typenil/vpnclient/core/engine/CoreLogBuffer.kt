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
        ring.clear()
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

    @Synchronized fun pause(token: Long) {
        if (token == epoch) pause()
    }

    @Synchronized fun stop() {
        pause()
        running = false
        sensitiveValues = emptyList()
    }

    @Synchronized fun clear() = ring.clear()

    @Synchronized fun clear(token: Long) {
        if (subscribed && token == epoch) ring.clear()
    }

    /** Native levels: panic=0, fatal=1, error=2, warn=3, info=4. */
    @Synchronized fun add(token: Long, level: Int, message: String) {
        if (!subscribed || token != epoch || level !in 0..3) return
        val label = listOf("PANIC", "FATAL", "ERROR", "WARN")[level]
        ring.add(("$label " + Redactor.redactCore(message.replace(terminal, ""), sensitiveValues)).take(512))
    }

    @Synchronized fun snapshot(): List<String> = ring.snapshot()

    private companion object {
        /** Native colour escapes and other control chars are noise (and could split a secret). */
        val terminal = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]|[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    }
}
