package dev.typenil.vpnclient.core.vpn

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ConnectionHealthTest {
    private var now = 100L
    private val store = ConnectionHealthStore({ now }, { Instant.EPOCH })
    private fun evidence(generation: Long = 1, checked: Long = now) =
        HealthObservation(HealthLevel.EngineRunning, HealthStatus.Ok, HealthReason.Started, HealthSource.ServiceLifecycle,
            HealthScope.LocalRuntime, generation, checked, Instant.EPOCH, 10)
    private fun runtimeToken() = store.token(HealthLevel.EngineRunning)!!
    private fun slot(level: HealthLevel = HealthLevel.EngineRunning) = store.state.value.observations.first { it.level == level }

    @Test fun `export snapshot requires active session and projects current ttl`() {
        assertNull(store.snapshot())
        store.begin(1)
        assertTrue(store.observe(evidence(), runtimeToken()))
        assertEquals(HealthStatus.Ok, store.snapshot()!!.observations.first { it.level == HealthLevel.EngineRunning }.status)
        now += 10
        val expired = store.snapshot()!!.observations.first { it.level == HealthLevel.EngineRunning }
        assertEquals(HealthStatus.Unverified, expired.status)
        assertEquals(HealthReason.Expired, expired.reason)
        assertEquals(HealthFreshness.Expired, expired.freshness)
        assertEquals(HealthStatus.Ok, slot().status)
        store.end(0)
        assertNotNull(store.snapshot())
        store.end(1)
        assertNull(store.snapshot())
        assertEquals(1L, store.state.value.generation)
        store.begin(2)
        assertNotNull(store.snapshot())
        assertTrue(store.snapshot()!!.observations.all { it.checkedAt == null })
    }

    @Test fun `initial slots have no invented time or success`() {
        assertEquals(8, store.state.value.observations.size)
        assertNull(store.token(HealthLevel.EngineRunning))
        store.state.value.observations.forEach {
            assertEquals(HealthStatus.Unverified, it.status)
            assertNull(it.checkedAtMillis)
            assertNull(it.checkedAt)
        }
    }

    @Test fun `old generations and ended sessions cannot enter store`() {
        store.begin(1)
        val firstToken = runtimeToken()
        assertTrue(store.observe(evidence(), firstToken))
        store.begin(2)
        val secondToken = runtimeToken()
        assertFalse(store.observe(evidence(1), firstToken))
        assertFalse(store.observe(evidence(2), firstToken))
        assertTrue(store.observe(evidence(2), secondToken))
        store.end(2)
        assertNull(store.token(HealthLevel.EngineRunning))
        assertFalse(store.observe(evidence(2), secondToken))
        assertTrue(store.state.value.observations.all { it.status == HealthStatus.Unverified && it.checkedAt == null })
        store.begin(1)
        assertEquals(2L, store.state.value.generation)
        store.begin(3)
        assertFalse(store.observe(evidence(3), secondToken))
        assertTrue(store.observe(evidence(3), runtimeToken()))
    }

    @Test fun `future out of order and already expired observations rejected`() {
        store.begin(1)
        val token = runtimeToken()
        assertFalse(store.observe(evidence(checked = 101), token))
        now = 104
        assertTrue(store.observe(evidence(), token))
        assertFalse(store.observe(evidence(checked = 103), token))
        now = 120
        assertFalse(store.observe(evidence(checked = 104), token))
        assertEquals(104L, slot().checkedAtMillis)
    }

    @Test fun `ttl expiry renders unverified without modifying stored evidence`() {
        store.begin(1)
        assertTrue(store.observe(evidence(), runtimeToken()))
        assertEquals(HealthStatus.Ok, slot().at(109).status)
        assertEquals(HealthStatus.Unverified, slot().at(110).status)
        assertEquals(HealthFreshness.Expired, slot().at(110).freshness)
        assertEquals(HealthStatus.Unverified, slot().at(99).status)
        assertEquals(HealthStatus.Ok, slot().status)
    }

    @Test fun `path invalidation preserves local runtime but runtime invalidation clears it`() {
        store.begin(1)
        val runtimeToken = runtimeToken()
        assertTrue(store.observe(evidence(), runtimeToken))
        val pathToken = store.token(HealthLevel.OutboundReachable)!!
        val unknownPath = HealthObservation(HealthLevel.OutboundReachable, generation = 1, checkedAtMillis = now)
        now = 105
        store.invalidate(1, ConnectionHealthStore.PATH_LEVELS, HealthReason.PathChanged)
        assertEquals(HealthStatus.Ok, slot().status)
        assertEquals(HealthStatus.Unverified, slot(HealthLevel.OutboundReachable).status)
        assertFalse(store.observe(unknownPath, pathToken))
        assertTrue(store.observe(evidence(), runtimeToken))
        store.invalidate(0, ConnectionHealthStore.RUNTIME_LEVELS, HealthReason.RuntimeInvalidated)
        assertEquals(HealthStatus.Ok, slot().status)
        store.invalidate(1, ConnectionHealthStore.RUNTIME_LEVELS, HealthReason.RuntimeInvalidated)
        assertEquals(HealthStatus.Unverified, slot().status)
    }

    @Test fun `same millisecond rebuild rejects old tokens and local records use current revision`() {
        store.begin(1)
        val before = evidence()
        val oldToken = runtimeToken()
        assertTrue(store.observe(before, oldToken))
        store.invalidate(1, ConnectionHealthStore.RUNTIME_LEVELS, HealthReason.RuntimeInvalidated)
        assertFalse(store.observe(before, oldToken))
        assertEquals(HealthStatus.Unverified, slot().status)
        assertTrue(store.observe(before, runtimeToken()))
        store.invalidate(1, ConnectionHealthStore.RUNTIME_LEVELS, HealthReason.RuntimeInvalidated)
        store.record(1, HealthLevel.EngineRunning, HealthStatus.Ok, HealthReason.Started,
            HealthSource.ServiceLifecycle, HealthScope.LocalRuntime)
        assertEquals(HealthStatus.Ok, slot().status)
        assertEquals(100L, slot().checkedAtMillis)
        assertTrue(store.state.value.observations.filter { it.level in ConnectionHealthStore.PATH_LEVELS }.all {
            it.status == HealthStatus.Unverified
        })
    }

    @Test fun `same millisecond path invalidation rejects old path token not unrelated runtime token`() {
        store.begin(1)
        val runtimeToken = runtimeToken()
        val pathToken = store.token(HealthLevel.OutboundReachable)!!
        val unknownPath = HealthObservation(HealthLevel.OutboundReachable, generation = 1, checkedAtMillis = now)
        store.invalidate(1, ConnectionHealthStore.PATH_LEVELS, HealthReason.PathChanged)
        assertFalse(store.observe(unknownPath, pathToken))
        assertTrue(store.observe(unknownPath, store.token(HealthLevel.OutboundReachable)!!))
        assertTrue(store.observe(evidence(), runtimeToken))
        assertFalse(store.observe(evidence(), store.token(HealthLevel.TunEstablished)!!))
    }

    @Test fun `verified statuses require source scope and display timestamp`() {
        store.begin(1)
        val token = runtimeToken()
        HealthStatus.entries.filter { it != HealthStatus.Unverified }.forEach { status ->
            val valid = evidence().copy(status = status)
            assertFalse(store.observe(valid.copy(source = HealthSource.None), token))
            assertFalse(store.observe(valid.copy(scope = HealthScope.None), token))
            assertFalse(store.observe(valid.copy(checkedAt = null), token))
            assertFalse(store.observe(valid.copy(checkedAtMillis = null), token))
            assertTrue(store.observe(valid, token))
        }
    }

    @Test fun `store has no lifecycle dependency or writer`() {
        listOf(ConnectionHealthStore::class.java, ConnectionHealth::class.java, HealthObservation::class.java).forEach { type ->
            assertFalse(type.declaredFields.any { VpnConnectionState::class.java.isAssignableFrom(it.type) })
            assertFalse(type.declaredMethods.any { method -> method.parameterTypes.any { VpnConnectionState::class.java.isAssignableFrom(it) } })
        }
    }
}
