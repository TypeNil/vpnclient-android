package dev.typenil.vpnclient.core.vpn

/**
 * Decides whether a live session must be rebuilt because the enabled node set
 * changed under it.
 *
 * Pure so the whole transition table is JVM-testable — the service only wires
 * it to the tunnel. The subtlety it exists to encode: "no change requested
 * yet" and "the set is empty" must never be confused, so fingerprints are
 * non-null and a missing baseline is represented by [onSessionEnded] resetting
 * the state instead of by a null fingerprint.
 */
class NodeSetReconciler {
    /** Last fingerprint a rebuild was requested for; null = none yet. */
    private var requested: String? = null

    /**
     * No session owns the tunnel: the next change is compared from scratch
     * rather than against a fingerprint from a previous session.
     */
    fun onSessionEnded() {
        requested = null
    }

    /**
     * @param fingerprint current digest of the enabled node set (never null —
     *   an empty set has its own stable digest).
     * @param compiledFingerprint digest the running config was compiled from;
     *   null when this process hasn't compiled yet.
     * @return true when the change must be applied (recompile + rebuild).
     */
    fun shouldRebuild(
        fingerprint: String,
        compiledFingerprint: String?,
    ): Boolean {
        // A single logical change can emit twice (a node delete plus the row
        // cleanup) before the in-flight compile has updated the compiled
        // fingerprint — the second emission must not queue a duplicate
        // rebuild.
        if (fingerprint == requested) return false
        requested = fingerprint
        // Already what the engine runs with: nothing to do.
        return fingerprint != compiledFingerprint
    }
}
