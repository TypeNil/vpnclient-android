package dev.typenil.vpnclient.ui.subscriptions

import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError.HwidRefusal
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError.InactiveReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddFailureTest {
    private fun kind(e: Throwable) = addFailureOf(e).kind

    @Test
    fun `http carries code and provider text and is not an hwid refusal`() {
        val f = addFailureOf(SubscriptionError.Http(403, "host", "note"))
        assertEquals(AddFailure(AddFailureKind.Http, 403, "note"), f)
    }

    @Test
    fun `5xx is a provider server error not a refusal of our request`() {
        assertEquals(AddFailure(AddFailureKind.ServerError, 502, null), addFailureOf(SubscriptionError.Http(502, "host", null)))
        assertEquals(AddFailureKind.Http, kind(SubscriptionError.Http(499, "host", null)))
        assertEquals(AddFailureKind.ServerError, kind(SubscriptionError.Http(500, "host", null)))
    }

    @Test
    fun `hwid refusals map by reason and keep provider text`() {
        fun rej(r: HwidRefusal, off: Boolean) = SubscriptionError.DeviceIdentificationRejected(r, off, "msg")
        assertEquals(AddFailure(AddFailureKind.DeviceLimit, null, "msg"), addFailureOf(rej(HwidRefusal.MaxDevices, false)))
        assertEquals(AddFailureKind.HwidRequired, kind(rej(HwidRefusal.MissingOrInvalid, false)))
        assertEquals(AddFailureKind.HwidOff, kind(rej(HwidRefusal.MissingOrInvalid, true)))
        assertEquals(AddFailureKind.HwidUnclear, kind(rej(HwidRefusal.Ambiguous, false)))
        assertEquals(AddFailureKind.HwidUnclear, kind(SubscriptionError.DeviceLimitReached(null)))
    }

    @Test
    fun `every other typed error has its own category`() {
        assertEquals(AddFailureKind.Expired, kind(SubscriptionError.Inactive(InactiveReason.Expired)))
        assertEquals(AddFailureKind.UsedUp, kind(SubscriptionError.Inactive(InactiveReason.TrafficUsedUp)))
        assertEquals(AddFailureKind.Network, kind(SubscriptionError.Network))
        assertEquals(AddFailureKind.Offline, kind(SubscriptionError.Offline))
        assertEquals(AddFailureKind.Timeout, kind(SubscriptionError.Timeout))
        for (e in listOf(
            SubscriptionError.TooLarge(1), SubscriptionError.UnsupportedFormat("x"),
            SubscriptionError.ParseFailed("x"), SubscriptionError.EmptyResult(), SubscriptionError.ConfigRejected,
        )) assertEquals(AddFailureKind.InvalidResponse, kind(e))
        assertEquals(AddFailureKind.Insecure, kind(SubscriptionError.InsecureTransport))
        assertEquals(AddFailureKind.Insecure, kind(SubscriptionError.ForbiddenAddress))
        assertEquals(AddFailureKind.BadAddress, kind(SubscriptionError.BadUrl))
        assertEquals(AddFailureKind.Duplicate, kind(SubscriptionError.Duplicate))
        assertEquals(AddFailureKind.Other, kind(IllegalStateException()))
        assertNull(addFailureOf(SubscriptionError.Network).providerMessage)
    }
}
