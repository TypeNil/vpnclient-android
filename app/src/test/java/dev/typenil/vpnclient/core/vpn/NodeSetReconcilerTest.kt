package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconciliation decision table. The regression this locks down: an empty
 * node set is a real fingerprint, so emptying the set must rebuild (and end)
 * the session rather than being mistaken for "no change requested yet".
 */
class NodeSetReconcilerTest {
    private val reconciler = NodeSetReconciler()

    @Test
    fun `the first change under a live session rebuilds`() {
        assertTrue(reconciler.shouldRebuild(fingerprint = "b", compiledFingerprint = "a"))
    }

    @Test
    fun `a re-emission of the same change does not rebuild twice`() {
        assertTrue(reconciler.shouldRebuild(fingerprint = "b", compiledFingerprint = "a"))

        // The in-flight compile hasn't updated the baseline yet — the second
        // emission of one logical change must not queue a duplicate rebuild.
        assertFalse(reconciler.shouldRebuild(fingerprint = "b", compiledFingerprint = "a"))
    }

    @Test
    fun `emptying the set rebuilds`() {
        assertTrue(reconciler.shouldRebuild(fingerprint = "empty", compiledFingerprint = "nodes-a"))
    }

    @Test
    fun `an empty set right after a session started is not mistaken for no request`() {
        // Emissions while no session owned the tunnel reset the baseline...
        reconciler.onSessionEnded()

        // ...so the session's first real change — here: the user disabled the
        // only subscription — must still rebuild.
        assertTrue(reconciler.shouldRebuild(fingerprint = "empty", compiledFingerprint = "nodes-a"))
    }

    @Test
    fun `a change equal to the compiled set does nothing`() {
        assertFalse(reconciler.shouldRebuild(fingerprint = "a", compiledFingerprint = "a"))
    }

    @Test
    fun `a set that fills back in rebuilds`() {
        assertTrue(reconciler.shouldRebuild(fingerprint = "empty", compiledFingerprint = "nodes-a"))

        assertTrue(reconciler.shouldRebuild(fingerprint = "nodes-b", compiledFingerprint = "empty"))
    }

    @Test
    fun `a fresh session compares from scratch`() {
        assertTrue(reconciler.shouldRebuild(fingerprint = "b", compiledFingerprint = "a"))

        // The tunnel is gone: the same set is re-evaluated against the new
        // session's compile instead of being skipped as already requested.
        reconciler.onSessionEnded()

        assertTrue(reconciler.shouldRebuild(fingerprint = "b", compiledFingerprint = "a"))
    }

    @Test
    fun `a missing compile baseline counts as a change`() {
        assertTrue(reconciler.shouldRebuild(fingerprint = "a", compiledFingerprint = null))
    }

    @Test
    fun `a settled session keeps reporting no change`() {
        assertTrue(reconciler.shouldRebuild(fingerprint = "b", compiledFingerprint = "a"))
        // The rebuild compiled the new set — its re-emissions are now no-ops.
        assertFalse(reconciler.shouldRebuild(fingerprint = "b", compiledFingerprint = "b"))
        assertFalse(reconciler.shouldRebuild(fingerprint = "b", compiledFingerprint = "b"))
    }
}
