package dev.typenil.vpnclient.core.engine.singbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read access to the .srs baselines shipped in `assets/rule_sets/` — the
 * offline seed for [RuleSetStore]. Injectable seam because unit tests run
 * on the JVM where `Context.assets` is unavailable.
 */
@Singleton
open class BundledRuleSets @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Stream for the bundled `<tag>.srs`, or null when the tag isn't
     *  shipped (e.g. a new RouteMode tag added without its asset). */
    open fun open(tag: String): InputStream? =
        runCatching { context.assets.open("rule_sets/$tag.srs") }.getOrNull()
}
