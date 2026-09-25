package dev.typenil.vpnclient.core.vpn

import dev.typenil.vpnclient.core.engine.EngineConfig
import kotlinx.coroutines.CancellationException

/**
 * Result of compiling the config for a session start or rebuild.
 *
 * The three cases must stay distinguishable: "nothing is enabled" is a real
 * state the user can act on, while a compile failure is a defect to report
 * with its own cause. Collapsing both into a null config (as the restore path
 * used to) tells the user to pick a server when the engine actually refused
 * the configuration.
 */
internal sealed interface CompileOutcome {
    data class Ready(
        val config: EngineConfig,
    ) : CompileOutcome

    /** No enabled nodes — not a failure. */
    data object NoNodes : CompileOutcome

    /** The compile threw; [cause] is preserved for the typed error. */
    data class Failed(
        val cause: Exception,
    ) : CompileOutcome
}

/** Compile the selected node's config, classifying failure modes. */
internal suspend fun NodeConfigProvider.compileOutcome(): CompileOutcome =
    try {
        compileSelected()?.let(CompileOutcome::Ready) ?: CompileOutcome.NoNodes
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CompileOutcome.Failed(e)
    }
