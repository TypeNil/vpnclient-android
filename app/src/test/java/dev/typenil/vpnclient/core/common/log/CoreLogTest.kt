package dev.typenil.vpnclient.core.common.log

import dev.typenil.vpnclient.core.engine.CoreLogBuffer
import org.junit.Assert.*
import org.junit.Test

class CoreLogTest {
    @Test fun `realistic core messages lose secrets but retain failure meaning`() {
        val secrets = listOf("Summer EU", "tiny-pass", "abc123", "tiny-key")
        val messages = listOf(
            "outbound/vless[Summer EU]: reality public_key=tiny-key short_id=abc123 handshake failed",
            "outbound/trojan: password=tiny-pass dial tcp 192.0.2.7:443 connection refused",
            "hysteria2/quic: token=abc123 dial [2001:db8::7]:443 timeout",
            "dns: lookup proxy.example.net: no such host",
            "outbound dial https://panel.example.net/sub/tiny?token=abc123 failed",
            "reality uuid=550e8400-e29b-41d4-a716-446655440000 unauthorized",
        )
        for (message in messages) {
            val out = Redactor.redactCore(message, listOf("Summer EU"))
            for (secret in secrets + listOf("192.0.2.7", "2001:db8::7", "proxy.example.net", "panel.example.net", "550e8400")) {
                assertFalse(out, out.contains(secret))
            }
            assertTrue(out, out.contains(message.substringAfterLast(' ')))
        }
    }

    @Test fun `bounds apply after redaction and newline normalization`() {
        val buffer = CoreLogBuffer()
        buffer.start(listOf("hidden"))
        val epoch = buffer.subscribe()
        repeat(501) { buffer.add(epoch, 3, "$it " + "word ".repeat(120) + "\nhidden") }
        val lines = buffer.snapshot()
        assertEquals(500, lines.size)
        assertTrue(lines.first().startsWith("WARN 1 "))
        assertEquals(512, lines.first().length)
        assertTrue(lines.all { it.length <= 512 && '\n' !in it && "hidden" !in it })
        buffer.add(epoch, 3, "hidden\nwarning")
        assertEquals("WARN <redacted> warning", buffer.snapshot().last())
    }

    @Test fun `explicit clear works without a subscription and does not end ingestion`() {
        val buffer = CoreLogBuffer()
        buffer.start(listOf("retained-secret"))
        val token = buffer.subscribe()
        buffer.add(token, 3, "embedded-retained-secret failure")
        buffer.stop()
        assertEquals(listOf("WARN embedded-<redacted> failure"), buffer.snapshot())
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
        buffer.start(emptyList())
        val next = buffer.subscribe()
        buffer.add(next, 3, "first")
        buffer.clear()
        buffer.add(next, 3, "second")
        assertEquals(listOf("WARN second"), buffer.snapshot())
    }

    @Test fun `fake subscription rejects stale callbacks pause and stopped session`() {
        val buffer = CoreLogBuffer()
        // Before start, even a subscription request cannot enable ingestion.
        val idle = buffer.subscribe()
        buffer.add(idle, 3, "idle warning")
        assertTrue(buffer.snapshot().isEmpty())
        buffer.start(emptyList())
        val first = buffer.subscribe()
        buffer.add(first, 3, "warn timeout")
        buffer.add(first, 2, "error refused")
        buffer.add(first, 4, "info ignored")
        assertEquals(2, buffer.snapshot().size)
        buffer.pause()
        buffer.add(first, 3, "screen off ignored")
        buffer.clear(first)
        assertEquals(2, buffer.snapshot().size)
        val resumed = buffer.subscribe()
        buffer.add(first, 3, "old callback ignored")
        buffer.add(resumed, 3, "resumed warning")
        assertEquals(3, buffer.snapshot().size)
        buffer.stop()
        buffer.add(resumed, 3, "stopped ignored")
        buffer.clear(resumed)
        assertEquals(3, buffer.snapshot().size)
        buffer.stop()
        assertEquals(3, buffer.snapshot().size)
        buffer.start(emptyList())
        assertTrue(buffer.snapshot().isEmpty())
        val restarted = buffer.subscribe()
        buffer.add(resumed, 3, "previous engine ignored")
        buffer.add(restarted, 3, "fresh warning")
        assertEquals(listOf("WARN fresh warning"), buffer.snapshot())
        buffer.clear(restarted)
        assertTrue(buffer.snapshot().isEmpty())
    }
}
