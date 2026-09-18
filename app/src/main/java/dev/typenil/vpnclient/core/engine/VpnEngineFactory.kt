package dev.typenil.vpnclient.core.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a [VpnEngine] for the service without the service knowing which
 * core backs it. Bound in DI to the selected core's implementation.
 */
interface VpnEngineFactory {
    fun create(
        context: Context,
        platform: EnginePlatform,
        scope: CoroutineScope,
        notifications: EngineNotificationSink,
    ): VpnEngine
}
