package dev.typenil.vpnclient.core.subscription

/** Warn this far ahead of subscription expiry. */
internal const val EXPIRY_ALERT_WINDOW_MS = 3L * 24 * 3600 * 1000

/**
 * Whether a "subscription expiring" alert should fire now: the expiry is
 * known, inside the warning window (not already past), and this exact expiry
 * hasn't been alerted before. A renewed subscription gets a new expiry → a
 * new alerted-key → alerts again.
 */
internal fun expiryAlertDue(
    expireEpochSeconds: Long?,
    nowMs: Long,
    alreadyAlerted: Boolean,
): Boolean = expireEpochSeconds != null && !alreadyAlerted &&
    (expireEpochSeconds * 1000 - nowMs) in 0..EXPIRY_ALERT_WINDOW_MS

/**
 * Posts the user-visible expiry notification. Implemented in `data` where
 * `NotificationCompat` lives; the repository only sees this contract, which
 * keeps it JVM-testable like the other subscription seams.
 */
interface SubscriptionExpiryNotifier {
    /** Alert that [subscriptionName] expires at [expireEpochSeconds].
     *  Returns false when nothing was posted (e.g. permission denied) so the
     *  caller doesn't record an alert the user never saw. */
    fun notifyExpiring(subscriptionId: Long, subscriptionName: String, expireEpochSeconds: Long): Boolean
}
