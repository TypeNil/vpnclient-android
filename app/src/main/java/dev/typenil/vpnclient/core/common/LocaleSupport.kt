package dev.typenil.vpnclient.core.common

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app locale plumbing.
 *
 * - Android 13+ (API 33+): `LocaleManager.applicationLocales` is authoritative
 *   — the system persists the choice, recreates activities, applies it to
 *   every context (including the VPN service's notification strings), and
 *   surfaces it in system per-app language settings.
 * - Below API 33: the tag stored in [LocaleTagPrefs] is read synchronously
 *   from `MainActivity.attachBaseContext` and applied via
 *   `createConfigurationContext`; changing it triggers `Activity.recreate()`.
 *
 * Either way this is a display-only concern — the VPN service is never
 * restarted for a language change.
 */
object LocaleSupport {
    private const val PREFS_FILE = "locale_compat"
    private const val PREF_TAG = "tag"

    /**
     * Synchronous store for the pre-33 wrap path — `attachBaseContext` runs
     * before coroutines/DataStore can be read, so MainActivity mirrors the
     * DataStore value into a plain SharedPreferences entry here. On 33+ the
     * platform is authoritative and this file stays unused.
     */
    fun storedTag(context: Context): String? =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(PREF_TAG, null)

    fun storeTag(
        context: Context,
        tag: String?,
    ) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (tag == null) remove(PREF_TAG) else putString(PREF_TAG, tag)
            }.apply()
    }

    /** Wrap [base] so its resources resolve in [tag]'s locale. Below API 33
     *  this is the whole mechanism; on 33+ the system already applied the
     *  per-app locale and wrapping is a harmless no-op — we skip it anyway so
     *  the platform path stays untouched. */
    fun wrap(
        base: Context,
        tag: String?,
    ): Context {
        if (tag == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull() ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** Push the stored choice into the system per-app locale store (API 33+).
     *  [tag] null → empty list = "follow system language". */
    fun applyToSystem(
        context: Context,
        tag: String?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(android.app.LocaleManager::class.java) ?: return
        val target =
            if (tag == null) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
        // setApplicationLocales recreates the activities — only write when the
        // current value actually differs or a stale pref would loop recreation.
        if (manager.applicationLocales.toLanguageTags() != target.toLanguageTags()) {
            manager.applicationLocales = target
        }
    }

    /** The locales the system currently applies for this app (API 33+);
     *  empty tags below — the pre-33 path wraps contexts itself. */
    fun currentSystemTag(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = context.getSystemService(android.app.LocaleManager::class.java) ?: return null
        return manager.applicationLocales.toLanguageTags().ifEmpty { null }
    }
}
