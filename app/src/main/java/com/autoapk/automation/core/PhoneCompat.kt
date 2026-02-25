package com.autoapk.automation.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Phone Manufacturer Compatibility
 *
 * Chinese phone manufacturers (Xiaomi, Samsung, OnePlus, Oppo, Vivo, Realme, Huawei)
 * have aggressive battery killers that murder background services.
 *
 * This class:
 *   1. Detects the phone manufacturer
 *   2. Opens the correct settings page to whitelist Neo
 *   3. Provides user-friendly guidance messages
 *
 * Call applyFixes() during first-time setup or from settings.
 */
object PhoneCompat {

    private const val TAG = "Neo_PhoneCompat"

    /**
     * Detect manufacturer and open the appropriate battery/autostart settings.
     */
    fun applyFixes(context: Context) {
        val mfr = Build.MANUFACTURER.lowercase()
        Log.i(TAG, "Phone manufacturer: $mfr (model: ${Build.MODEL})")

        when {
            mfr in listOf("xiaomi", "redmi", "poco") -> xiaomiFix(context)
            mfr == "samsung" -> samsungFix(context)
            mfr == "oneplus" -> oneplusFix(context)
            mfr in listOf("oppo", "vivo", "realme") -> oppoFix(context)
            mfr in listOf("huawei", "honor") -> huaweiFix(context)
            else -> {
                Log.i(TAG, "No special fix needed for $mfr")
                // Try generic battery optimization setting
                genericBatteryFix(context)
            }
        }
    }

    /**
     * Get a user-friendly message explaining what permissions are needed.
     */
    fun getSetupMessage(): String {
        val mfr = Build.MANUFACTURER.lowercase()
        return when {
            mfr in listOf("xiaomi", "redmi", "poco") ->
                "Xiaomi phone detected. I need autostart permission. Opening settings now. Please enable AutoAPK and say done."
            mfr == "samsung" ->
                "Samsung phone detected. I need to disable battery optimization. Opening settings now. Find AutoAPK and set it to unrestricted. Say done when ready."
            mfr == "oneplus" ->
                "OnePlus phone detected. I need autostart permission. Opening settings now. Please enable AutoAPK and say done."
            mfr in listOf("oppo", "vivo", "realme") ->
                "I need autostart permission on your phone. Opening settings now. Please enable AutoAPK and say done."
            mfr in listOf("huawei", "honor") ->
                "Huawei phone detected. I need to be added to protected apps. Opening settings now. Please enable AutoAPK and say done."
            else ->
                "Let me check your battery settings to make sure I can run in the background."
        }
    }

    /**
     * Check if the current manufacturer needs special handling.
     */
    fun needsSpecialHandling(): Boolean {
        val mfr = Build.MANUFACTURER.lowercase()
        return mfr in listOf(
            "xiaomi", "redmi", "poco",
            "samsung", "oneplus",
            "oppo", "vivo", "realme",
            "huawei", "honor"
        )
    }

    // ==================== MANUFACTURER-SPECIFIC FIXES ====================

    private fun xiaomiFix(ctx: Context) {
        Log.i(TAG, "Applying Xiaomi fix")
        // Try MIUI autostart management
        tryOpen(ctx, "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity")
    }

    private fun samsungFix(ctx: Context) {
        Log.i(TAG, "Applying Samsung fix")
        // Try Samsung battery/device care
        tryOpen(ctx, "com.samsung.android.lool",
            "com.samsung.android.sm.battery.ui.BatteryActivity")
    }

    private fun oneplusFix(ctx: Context) {
        Log.i(TAG, "Applying OnePlus fix")
        tryOpen(ctx, "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
    }

    private fun oppoFix(ctx: Context) {
        Log.i(TAG, "Applying Oppo/Vivo/Realme fix")
        tryOpen(ctx, "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity")
    }

    private fun huaweiFix(ctx: Context) {
        Log.i(TAG, "Applying Huawei fix")
        tryOpen(ctx, "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
    }

    private fun genericBatteryFix(ctx: Context) {
        Log.i(TAG, "Trying generic battery optimization settings")
        try {
            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open battery settings: ${e.message}")
        }
    }

    private fun tryOpen(ctx: Context, pkg: String, cls: String) {
        try {
            ctx.startActivity(Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Log.i(TAG, "✅ Opened $cls")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open $cls: ${e.message}")
            // Fallback to generic battery settings
            genericBatteryFix(ctx)
        }
    }
}
