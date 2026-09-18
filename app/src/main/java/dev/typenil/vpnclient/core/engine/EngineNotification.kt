package dev.typenil.vpnclient.core.engine

/**
 * Engine-neutral notification the core wants surfaced to the user.
 * Mirrors the fields cores like libbox provide, without exposing their types.
 */
data class EngineNotification(
    val identifier: String,
    val typeName: String,
    val typeId: Int,
    val title: String,
    val subtitle: String,
    val body: String,
    val openUrl: String?,
)

/** Receives notifications the core raises (rare; e.g. warnings/alerts). */
interface EngineNotificationSink {
    fun send(notification: EngineNotification)
    fun cancel(identifier: String, typeId: Int)
}
