package dev.typenil.vpnclient.ui.common

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidBatteryOptimizationStatus @Inject constructor(
    @ApplicationContext private val context: Context,
) : BatteryOptimizationStatus {
    override fun enabled(): Boolean? = try {
        context.getSystemService(PowerManager::class.java)?.let {
            !it.isIgnoringBatteryOptimizations(context.packageName)
        }
    } catch (_: SecurityException) {
        null
    }
}
