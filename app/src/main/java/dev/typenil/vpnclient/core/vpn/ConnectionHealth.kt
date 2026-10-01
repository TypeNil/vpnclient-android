package dev.typenil.vpnclient.core.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class HealthLevel {
    VpnConsent,
    TunEstablished,
    EngineRunning,
    UnderlyingNetwork,
    OutboundReachable,
    TrafficForwarding,
    DnsReachable,
    LastSuccessfulCheck,
}

enum class HealthStatus { Ok, Degraded, Failed, Unverified }

enum class HealthReason {
    NotObserved,
    ConsentGranted,
    ConsentRequired,
    ConsentDenied,
    Revoked,
    Started,
    Stopped,
    RuntimeInvalidated,
    NetworkAvailable,
    NetworkLost,
    PathChanged,
    StartFailed,
    Expired,
    HttpResponseRouteUnverified,
    HttpTimeout,
    HttpError,
    HttpNetworkError,
    HttpUnexpectedResponse,
}

enum class HealthSource { None, VpnConsent, ServiceLifecycle, PlatformUnderlay, IpEcho }

enum class HealthScope { None, LocalRuntime, PhysicalUnderlay, AppHttpRouteUnverified }

enum class HealthFreshness { Unobserved, Fresh, Expired }

/** Safe evidence only: no endpoints, credentials, error strings or lifecycle state. */
data class HealthObservation(
    val level: HealthLevel,
    val status: HealthStatus = HealthStatus.Unverified,
    val reason: HealthReason = HealthReason.NotObserved,
    val source: HealthSource = HealthSource.None,
    val scope: HealthScope = HealthScope.None,
    val generation: Long = 0,
    /** Monotonic evidence time; asynchronous callers must retain the captured
     * time rather than re-stamping delayed results when publishing them. */
    val checkedAtMillis: Long? = null,
    val checkedAt: Instant? = null,
    val ttlMillis: Long = 60_000,
    val freshness: HealthFreshness = HealthFreshness.Unobserved,
    /** Revision of the accepted evidence token; absent for unobserved/invalidated slots. */
    val revision: Long? = null,
) {
    fun at(nowMillis: Long): HealthObservation {
        val checked = checkedAtMillis ?: return this
        return if (nowMillis < checked || nowMillis - checked >= ttlMillis) {
            copy(status = HealthStatus.Unverified, reason = HealthReason.Expired, freshness = HealthFreshness.Expired)
        } else {
            copy(freshness = HealthFreshness.Fresh)
        }
    }
}

data class ConnectionHealth(
    val generation: Long = 0,
    val revision: Long = 0,
    val observations: List<HealthObservation> = HealthLevel.entries.map { HealthObservation(it) },
) {
    fun at(nowMillis: Long) = copy(observations = observations.map { it.at(nowMillis) })
}

/** Captured before asynchronous work. A token belongs to one level and revision. */
class HealthEvidenceToken internal constructor(val generation: Long, val level: HealthLevel, val revision: Long)

/** Small synchronized store; service callbacks may arrive from engine threads.
 * Runtime/path invalidation advances revisions and timestamp floors, rejecting delayed evidence.
 * Expiry is a read projection, not polling or a lifecycle transition.
 */
class ConnectionHealthStore(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val displayTime: () -> Instant = { Instant.now() },
) {
    private val mutable = MutableStateFlow(ConnectionHealth())
    val state: StateFlow<ConnectionHealth> = mutable.asStateFlow()
    private var active = false
    private val floors = mutableMapOf<HealthLevel, Long>()
    private val revisions = mutableMapOf<HealthLevel, Long>()
    private var revision = 0L

    @Synchronized
    fun begin(generation: Long) {
        if (generation <= mutable.value.generation) return
        active = true
        floors.clear()
        val now = monotonicMillis()
        val nextRevision = ++revision
        HealthLevel.entries.forEach {
            floors[it] = now
            revisions[it] = nextRevision
        }
        mutable.value = ConnectionHealth(generation, nextRevision, HealthLevel.entries.map { HealthObservation(it, generation = generation) })
    }

    @Synchronized
    fun token(level: HealthLevel): HealthEvidenceToken? =
        if (active) HealthEvidenceToken(mutable.value.generation, level, revisions.getValue(level)) else null

    @Synchronized
    fun isCurrent(token: HealthEvidenceToken): Boolean =
        active && token.generation == mutable.value.generation && token.revision == revisions[token.level]

    @Synchronized
    fun observe(observation: HealthObservation, token: HealthEvidenceToken): Boolean {
        if (!isCurrent(token) || token.generation != observation.generation || token.level != observation.level) return false
        if (observation.status != HealthStatus.Unverified &&
            (observation.source == HealthSource.None || observation.scope == HealthScope.None || observation.checkedAt == null)
        ) return false
        val checked = observation.checkedAtMillis ?: return false
        val now = monotonicMillis()
        if (checked > now || observation.ttlMillis <= 0 || now - checked >= observation.ttlMillis) return false
        val previous = mutable.value.observations.first { it.level == observation.level }
        if (checked < (floors[observation.level] ?: checked) || checked < (previous.checkedAtMillis ?: checked)) return false
        mutable.value = mutable.value.copy(observations = mutable.value.observations.map {
            if (it.level == observation.level) observation.copy(freshness = HealthFreshness.Fresh, revision = token.revision) else it
        })
        return true
    }

    @Synchronized
    fun record(generation: Long, level: HealthLevel, status: HealthStatus, reason: HealthReason, source: HealthSource, scope: HealthScope) {
        val token = token(level) ?: return
        observe(HealthObservation(level, status, reason, source, scope, generation, monotonicMillis(), displayTime()), token)
    }

    @Synchronized
    fun invalidate(generation: Long, levels: Set<HealthLevel>, reason: HealthReason) {
        if (!active || generation != mutable.value.generation) return
        val now = monotonicMillis()
        val nextRevision = ++revision
        levels.forEach {
            floors[it] = now
            revisions[it] = nextRevision
        }
        mutable.value = mutable.value.copy(revision = nextRevision, observations = mutable.value.observations.map {
            if (it.level in levels) HealthObservation(it.level, reason = reason, generation = generation) else it
        })
    }

    @Synchronized
    fun end(generation: Long, reason: HealthReason = HealthReason.Stopped) {
        if (!active || generation != mutable.value.generation) return
        invalidate(generation, HealthLevel.entries.toSet(), reason)
        if (reason == HealthReason.ConsentDenied || reason == HealthReason.Revoked) {
            record(generation, HealthLevel.VpnConsent, HealthStatus.Failed, reason, HealthSource.VpnConsent, HealthScope.LocalRuntime)
        }
        active = false
    }

    companion object {
        val PATH_LEVELS = setOf(HealthLevel.OutboundReachable, HealthLevel.TrafficForwarding, HealthLevel.DnsReachable, HealthLevel.LastSuccessfulCheck)
        val RUNTIME_LEVELS = PATH_LEVELS + setOf(HealthLevel.TunEstablished, HealthLevel.EngineRunning)
    }
}
