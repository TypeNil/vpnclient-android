package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.SubscriptionError

/** Fixed storage tokens only: no header values, URLs or exception messages. */
fun SubscriptionError.identificationErrorToken(): String? = when (this) {
    is SubscriptionError.Http -> "sub:http:$code"
    is SubscriptionError.DeviceLimitReached -> "sub:hwid:ambiguous"
    is SubscriptionError.DeviceIdentificationRejected -> when {
        reason == SubscriptionError.HwidRefusal.Ambiguous -> "sub:hwid:ambiguous"
        sendingDisabled -> "sub:hwid:disabled"
        reason == SubscriptionError.HwidRefusal.MaxDevices -> "sub:hwid:max"
        else -> "sub:hwid:required"
    }
    else -> null
}

/** Fixed tokens of the user-facing transport/input failures that have a localized message. */
fun SubscriptionError.transportErrorToken(): String? = when (this) {
    is SubscriptionError.Network -> "network unavailable"
    is SubscriptionError.Offline -> "device offline"
    is SubscriptionError.Timeout -> "request timed out"
    is SubscriptionError.Duplicate -> "duplicate link"
    else -> null
}
