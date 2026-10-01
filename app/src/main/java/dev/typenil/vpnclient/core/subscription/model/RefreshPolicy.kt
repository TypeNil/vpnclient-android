package dev.typenil.vpnclient.core.subscription.model

/**
 * Per-subscription auto-refresh policy.
 *
 * Stored on the `subscriptions` row as a raw string token (`refreshPolicy`)
 * plus a nullable minutes column (`refreshFixedMinutes`); the domain type is
 * recovered by [fromStorage]. Anything unrecognized — a token this build
 * doesn't know, a `fixed` row without a usable minutes value, a NULL the
 * NOT NULL column should never hold — decodes to [Disabled]: the safe option
 * is "no scheduled work", never a guessed one.
 */
sealed class RefreshPolicy {
    /**
     * Follow the app-wide auto-refresh setting — the legacy behavior every
     * pre-v7 row had: `-1` = off, `0` = provider hint, `>0` = fixed minutes.
     */
    data object InheritGlobal : RefreshPolicy()

    /**
     * Follow the provider's `profile-update-interval` only — a missing or
     * unusable hint means manual-only, never a fallback to the global value.
     */
    data object Provider : RefreshPolicy()

    /**
     * No periodic refresh and no provider-requested (`update-always`) launch
     * refresh. A manual pull refresh stays available.
     */
    data object Disabled : RefreshPolicy()

    /** Fixed user interval in minutes — construction rejects anything below
     *  [MIN_FIXED_MINUTES], so a persisted "fixed" token can't smuggle in a
     *  sub-floor interval that later resolves to something else. */
    data class Fixed(val minutes: Int) : RefreshPolicy() {
        init {
            require(minutes >= MIN_FIXED_MINUTES) {
                "fixed refresh interval $minutes < $MIN_FIXED_MINUTES"
            }
        }
    }

    /** Raw token for the `refreshPolicy` column. */
    val storageKey: String
        get() =
            when (this) {
                InheritGlobal -> KEY_INHERIT
                Provider -> KEY_PROVIDER
                Disabled -> KEY_DISABLED
                is Fixed -> KEY_FIXED
            }

    /** Value for the `refreshFixedMinutes` column — non-null only while the
     *  policy is [Fixed], so a stale leftover can't decode as one later. */
    val storageFixedMinutes: Int?
        get() = (this as? Fixed)?.minutes

    companion object {
        /**
         * WorkManager's periodic floor. A `fixed` value below it is rejected
         * at the UI; one that still reaches storage decodes to [Disabled].
         */
        const val MIN_FIXED_MINUTES = 15

        const val KEY_INHERIT = "inherit"
        const val KEY_PROVIDER = "provider"
        const val KEY_DISABLED = "disabled"
        const val KEY_FIXED = "fixed"

        /** Safe decode of the stored pair — see the class kdoc for fallbacks. */
        fun fromStorage(
            raw: String?,
            fixedMinutes: Int?,
        ): RefreshPolicy =
            when (raw) {
                KEY_INHERIT -> InheritGlobal
                // NULL should be impossible (the column is NOT NULL with a
                // default) — treat it as invalid data, not as legacy
                // inherit: guessing "old behavior" would silently re-enable
                // scheduled work on a corrupt row.
                null -> Disabled
                KEY_PROVIDER -> Provider
                KEY_DISABLED -> Disabled
                KEY_FIXED ->
                    fixedMinutes
                        ?.takeIf { it >= MIN_FIXED_MINUTES }
                        ?.let(::Fixed)
                        ?: Disabled
                else -> Disabled
            }
    }
}
