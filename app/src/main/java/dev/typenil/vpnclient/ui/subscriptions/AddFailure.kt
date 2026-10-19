package dev.typenil.vpnclient.ui.subscriptions

import androidx.annotation.StringRes
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError.HwidRefusal
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError.InactiveReason

enum class AddFailureKind(@StringRes val textRes: Int) {
    DeviceLimit(R.string.subs_add_err_limit),
    HwidRequired(R.string.subs_add_err_hwid_required),
    HwidOff(R.string.subs_add_err_hwid_off),
    HwidUnclear(R.string.subs_add_err_hwid_unclear),
    Expired(R.string.subs_add_err_expired),
    UsedUp(R.string.subs_add_err_used_up),
    Http(R.string.subs_add_err_http),
    ServerError(R.string.subs_add_err_server),
    Network(R.string.subs_add_err_network),
    Offline(R.string.subs_add_err_offline),
    Timeout(R.string.subs_add_err_timeout),
    InvalidResponse(R.string.subs_add_err_invalid),
    Insecure(R.string.subs_add_err_insecure),
    BadAddress(R.string.subs_add_err_bad_address),
    Duplicate(R.string.subs_add_err_duplicate),
    Other(R.string.subs_add_err_other),
}

/** Why a first add failed, as the dialog shows it. [providerMessage] is
 *  sanitized provider text — display only, never log. */
data class AddFailure(
    val kind: AddFailureKind,
    val httpCode: Int? = null,
    val providerMessage: String? = null,
)

fun addFailureOf(e: Throwable): AddFailure = when (e) {
    is SubscriptionError.Http -> AddFailure(
        if (e.code >= 500) AddFailureKind.ServerError else AddFailureKind.Http, e.code, e.announce,
    )
    is SubscriptionError.DeviceIdentificationRejected -> AddFailure(
        when {
            e.reason == HwidRefusal.Ambiguous -> AddFailureKind.HwidUnclear
            e.sendingDisabled -> AddFailureKind.HwidOff
            e.reason == HwidRefusal.MaxDevices -> AddFailureKind.DeviceLimit
            else -> AddFailureKind.HwidRequired
        },
        providerMessage = e.announce,
    )
    is SubscriptionError.DeviceLimitReached -> AddFailure(AddFailureKind.HwidUnclear)
    is SubscriptionError.Inactive -> AddFailure(
        if (e.reason == InactiveReason.Expired) AddFailureKind.Expired else AddFailureKind.UsedUp,
    )
    is SubscriptionError.Network -> AddFailure(AddFailureKind.Network)
    is SubscriptionError.Offline -> AddFailure(AddFailureKind.Offline)
    is SubscriptionError.Timeout -> AddFailure(AddFailureKind.Timeout)
    is SubscriptionError.TooLarge, is SubscriptionError.UnsupportedFormat,
    is SubscriptionError.ParseFailed, is SubscriptionError.EmptyResult,
    is SubscriptionError.ConfigRejected,
    -> AddFailure(AddFailureKind.InvalidResponse)
    is SubscriptionError.InsecureTransport, is SubscriptionError.ForbiddenAddress ->
        AddFailure(AddFailureKind.Insecure)
    is SubscriptionError.BadUrl -> AddFailure(AddFailureKind.BadAddress)
    is SubscriptionError.Duplicate -> AddFailure(AddFailureKind.Duplicate)
    else -> AddFailure(AddFailureKind.Other)
}
