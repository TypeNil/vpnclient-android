package dev.typenil.vpnclient.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.typenil.vpnclient.core.subscription.OnlineCheck

/** Online = there is an active network that declares internet access (validation is not required: captive portals count as online). */
class AndroidOnlineCheck(private val context: Context) : OnlineCheck {
    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
