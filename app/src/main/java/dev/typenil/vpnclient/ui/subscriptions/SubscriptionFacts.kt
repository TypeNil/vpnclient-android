package dev.typenil.vpnclient.ui.subscriptions

import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo

internal data class ExpiryFact(val epochSeconds: Long, val expired: Boolean)

/** Provider expiry; `expire=0` (rows stored before the parser dropped it) and a missing value mean "no expiry shown". */
internal fun expiryFact(info: SubscriptionUserInfo?, nowMs: Long): ExpiryFact? =
    info?.expireEpochSeconds?.takeIf { it > 0 }?.let { ExpiryFact(it, it * 1000 < nowMs) }

/** Share of the quota used, 0..1; null when the provider publishes no total (0 = unlimited or unknown), so no bar is drawn. */
internal fun usageFraction(info: SubscriptionUserInfo?): Float? =
    info?.takeIf { it.totalBytes > 0 }?.let { (it.usedBytes.toDouble() / it.totalBytes).coerceIn(0.0, 1.0).toFloat() }
