package dev.typenil.vpnclient.core.engine.singbox

import android.content.Context
import dev.typenil.vpnclient.core.engine.EngineNotificationSink
import dev.typenil.vpnclient.core.engine.EnginePlatform
import dev.typenil.vpnclient.core.engine.VpnEngine
import dev.typenil.vpnclient.core.engine.VpnEngineFactory
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Creates the libbox-backed engine. Bound to [VpnEngineFactory] in DI. */
class SingBoxEngineFactory @Inject constructor() : VpnEngineFactory {
    override fun create(
        context: Context,
        platform: EnginePlatform,
        scope: CoroutineScope,
        notifications: EngineNotificationSink,
    ): VpnEngine = SingBoxEngine(context, platform, scope, notifications)
}
