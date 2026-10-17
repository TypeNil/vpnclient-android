package dev.typenil.vpnclient.ui.home

import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.vpn.LastSession
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import dev.typenil.vpnclient.ui.common.RelativeAge
import dev.typenil.vpnclient.ui.common.RelativeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LastSessionModelTest {
    private val node = NodeSummary("n1", "Demo Node", ProtocolType.VLESS, "demo.invalid")
    private val record = LastSession(1_000_000L, 12_000L, 2_048L, 512L, "Demo Node")

    private fun connected() = VpnConnectionState.Connected(node, Instant.EPOCH, null)

    @Test
    fun `card needs a record`() {
        assertFalse(showLastSession(VpnConnectionState.Idle, null))
        assertTrue(showLastSession(VpnConnectionState.Idle, record))
    }

    @Test
    fun `card is hidden while a session is live or starting`() {
        assertFalse(showLastSession(connected(), record))
        assertFalse(showLastSession(VpnConnectionState.Connecting(node), record))
        assertFalse(
            showLastSession(
                VpnConnectionState.Reconnecting(node, VpnConnectionState.Reconnecting.Reason.NetworkUnavailable, 1),
                record,
            ),
        )
    }

    @Test
    fun `card is shown after stop or failure`() {
        assertTrue(showLastSession(VpnConnectionState.Stopping, record))
        assertTrue(showLastSession(VpnConnectionState.Error(VpnError.NoNodeSelected, null), record))
    }

    @Test
    fun `age buckets`() {
        val t = 1_000_000L
        assertEquals(RelativeAge(RelativeUnit.Now, 0), endedAge(t, t))
        assertEquals(RelativeAge(RelativeUnit.Now, 0), endedAge(t + 59_999, t))
        assertEquals(RelativeAge(RelativeUnit.Minutes, 1), endedAge(t + 60_000, t))
        assertEquals(RelativeAge(RelativeUnit.Hours, 1), endedAge(t + 3_600_000, t))
        assertEquals(RelativeAge(RelativeUnit.Days, 3), endedAge(t + 3 * 86_400_000L, t))
    }

    @Test
    fun `a record from the future is not given an age`() {
        assertNull(endedAge(999_999L, 1_000_000L))
        assertNull(endedAge(0L, Long.MAX_VALUE))
    }

    private val texts = LastSessionTexts("Downloaded", "Uploaded", { "<$it>" }, { l, v -> "$l, $v" })

    @Test
    fun `downloaded is first and carries the download total`() {
        val (down, up) = lastSessionColumns(record, texts)
        assertTrue(down.download)
        assertEquals("Downloaded", down.label)
        assertEquals("<2048>", down.value)
        assertEquals("Downloaded, <2048>", down.description)
        assertFalse(up.download)
        assertEquals("Uploaded, <512>", up.description)
    }

    @Test
    fun `zero and huge totals pass through untouched`() {
        val (down, up) = lastSessionColumns(record.copy(downBytes = 0, upBytes = Long.MAX_VALUE), texts)
        assertEquals("<0>", down.value)
        assertEquals("<${Long.MAX_VALUE}>", up.value)
    }
}
