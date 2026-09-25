package dev.typenil.vpnclient.ui.common

import android.content.Context
import androidx.annotation.StringRes

/**
 * A user-facing message whose text is resolved at render time — carriers like
 * snackbar flows live in the ViewModel (no Context), so they hand the UI a
 * resource id + args or an already-rendered raw string instead of baking a
 * locale-frozen literal into state.
 */
sealed interface UserMessage {
    /** Human-readable default used by unit tests and as the raw body when
     *  [Context] can't resolve the resource — mirrors strings.xml. */
    val fallback: String

    fun render(context: Context): String

    /** A localized resource with optional format args. */
    data class Resource(
        @param:StringRes val resId: Int,
        val args: List<Any> = emptyList(),
        override val fallback: String,
    ) : UserMessage {
        override fun render(context: Context): String =
            if (args.isEmpty()) {
                context.getString(resId)
            } else {
                context.getString(resId, *args.toTypedArray())
            }
    }

    /** A pre-rendered message produced upstream (typed parse/fetch errors,
     *  provider-supplied text) — passed through as-is. */
    data class Raw(override val fallback: String) : UserMessage {
        override fun render(context: Context): String = fallback
    }
}
