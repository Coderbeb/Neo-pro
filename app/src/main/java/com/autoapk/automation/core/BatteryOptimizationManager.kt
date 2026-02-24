package com.autoapk.automation.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper to manage Battery Optimization settings.
 * Ensures the app can run in the background without being killed by Doze mode.
 */
class BatteryOptimizationManager(private val context: Context) {

    private val TAG = "Neo_Battery"

    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true // Pre-Marshmallow doesn't have this specific doze mode
    }

    fun getRequestIgnoreOptimizationIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            if (isIgnoringBatteryOptimizations()) {
                return null
            }
            
            // Direct request intent (might be restricted on Play Store, but fine for side-loading)
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:${context.packageName}")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return intent
        }
        return null
    }
    
    fun openBatterySettings() {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not open battery settings: ${e.message}")
            }
        }
    }
}
