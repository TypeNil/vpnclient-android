package dev.typenil.vpnclient.ui.subscriptions

import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.ui.common.UserMessage

/** Shared by cards, details and VM messages; old fixed strings remain readable. */
fun subscriptionFailureMessage(token: String, hasServers: Boolean): UserMessage.Resource? {
    val (res, fallback) = when (token) {
        "sub:hwid:max" -> if (hasServers) {
            R.string.subs_hwid_limit_kept to "Subscription not updated: device limit reached. Ask your provider to remove an old device or raise the limit. Saved servers were kept."
        } else {
            R.string.subs_hwid_limit to "Subscription not updated: device limit reached. Ask your provider to remove an old device or raise the limit."
        }
        "sub:hwid:required" -> R.string.subs_hwid_required to "Subscription not updated: provider requires a valid HWID. Review HWID settings or contact support."
        "sub:hwid:disabled" -> R.string.subs_hwid_disabled_refusal to "HWID sending is off. This provider refused the update without it. Review HWID settings or contact support."
        "sub:hwid:ambiguous", "device limit / HWID rejected", "device identification rejected" ->
            R.string.subs_hwid_ambiguous to "Provider rejected device identification. A device limit is not confirmed; contact support."
        else -> {
            val code = Regex("^(?:sub:http:|HTTP )([1-5][0-9]{2})$").matchEntire(token)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return UserMessage.Resource(R.string.subs_http_error, listOf(code),
                "Subscription not updated: server returned HTTP $code.")
        }
    }
    return UserMessage.Resource(res, fallback = fallback)
}
