package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.SubscriptionError.HwidRefusal
import okhttp3.Headers

/** Hostile header values: exact true only, bounded values; duplicate true wins. */
internal fun hwidRefusal(headers: Headers): HwidRefusal? {
    fun enabled(name: String) = headers.values(name).take(16).any {
        it.length <= 16 && it.trim().equals("true", ignoreCase = true)
    }
    val max = enabled("x-hwid-max-devices-reached")
    val missing = enabled("x-hwid-not-supported")
    return when {
        max && missing -> HwidRefusal.Ambiguous
        max -> HwidRefusal.MaxDevices
        missing -> HwidRefusal.MissingOrInvalid
        enabled("x-hwid-limit") -> HwidRefusal.Ambiguous
        else -> null // active is informational, never a refusal
    }
}
