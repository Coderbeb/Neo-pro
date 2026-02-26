package com.autoapk.automation.core

import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import java.lang.reflect.Method

/**
 * DirectToggleController — Uses direct Android APIs to toggle system settings.
 *
 * Tier 1 (Direct API): Bluetooth, DND, Auto-Rotate, Dark Mode
 * Tier 2 (Panel/Settings fallback): WiFi (API 29+), Mobile Data, Hotspot, Airplane, Location
 *
 * Each method returns a ToggleResult describing what happened.
 */
class DirectToggleController(private val context: Context) {

    companion object {
        private const val TAG = "Neo_Toggle"
    }

    enum class ToggleResult {
        SUCCESS,
        ALREADY_IN_STATE,
        FAILED,
        NEEDS_PANEL,
        NEEDS_PERMISSION
    }

    private val contentResolver: ContentResolver = context.contentResolver

    // ==================== WIFI ====================

    /**
     * Toggle WiFi on/off.
     * API < 29: Direct WifiManager.setWifiEnabled()
     * API >= 29: Opens Settings.Panel.ACTION_WIFI
     */
    @Suppress("DEPRECATION")
    fun setWifi(enabled: Boolean): ToggleResult {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Direct toggle for API < 29
                if (wifiManager.isWifiEnabled == enabled) {
                    Log.i(TAG, "WiFi already ${if (enabled) "on" else "off"}")
                    return ToggleResult.ALREADY_IN_STATE
                }
                val result = wifiManager.setWifiEnabled(enabled)
                if (result) {
                    Log.i(TAG, "WiFi toggled ${if (enabled) "on" else "off"} via direct API")
                    ToggleResult.SUCCESS
                } else {
                    Log.e(TAG, "WiFi direct toggle failed")
                    ToggleResult.FAILED
                }
            } else {
                // API 29+: Need to open panel
                Log.i(TAG, "WiFi requires panel on API ${Build.VERSION.SDK_INT}")
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToggleResult.NEEDS_PANEL
            }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi toggle error: ${e.message}")
            ToggleResult.FAILED
        }
    }

    // ==================== BLUETOOTH ====================

    /**
     * Toggle Bluetooth on/off.
     * Uses BluetoothAdapter.enable/disable() — deprecated on API 33+ but still functional.
     */
    @Suppress("DEPRECATION")
    fun setBluetooth(enabled: Boolean): ToggleResult {
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

            if (adapter == null) {
                Log.e(TAG, "No Bluetooth adapter found")
                return ToggleResult.FAILED
            }

            if (adapter.isEnabled == enabled) {
                Log.i(TAG, "Bluetooth already ${if (enabled) "on" else "off"}")
                return ToggleResult.ALREADY_IN_STATE
            }

            val result = if (enabled) adapter.enable() else adapter.disable()
            if (result) {
                Log.i(TAG, "Bluetooth toggled ${if (enabled) "on" else "off"}")
                ToggleResult.SUCCESS
            } else {
                Log.e(TAG, "Bluetooth toggle failed — may need BLUETOOTH_CONNECT permission")
                ToggleResult.FAILED
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission error: ${e.message}")
            ToggleResult.NEEDS_PERMISSION
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth toggle error: ${e.message}")
            ToggleResult.FAILED
        }
    }

    // ==================== MOBILE DATA ====================

    /**
     * Toggle mobile data on/off.
     * Method 1: TelephonyManager reflection (setDataEnabled)
     * Method 2: Open internet connectivity panel
     */
    fun setMobileData(enabled: Boolean): ToggleResult {
        // Method 1: Reflection
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val method: Method = tm.javaClass.getDeclaredMethod("setDataEnabled", Boolean::class.java)
            method.isAccessible = true
            method.invoke(tm, enabled)
            Log.i(TAG, "Mobile data toggled ${if (enabled) "on" else "off"} via reflection")
            return ToggleResult.SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "Mobile data reflection failed: ${e.message}")
        }

        // Method 2: Open panel
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Log.i(TAG, "Opened internet connectivity panel for mobile data")
                ToggleResult.NEEDS_PANEL
            } else {
                val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToggleResult.NEEDS_PANEL
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mobile data panel error: ${e.message}")
            ToggleResult.FAILED
        }
    }

    // ==================== DND ====================

    /**
     * Toggle Do Not Disturb on/off.
     * Direct API via NotificationManager — requires notification policy access.
     */
    fun setDND(enabled: Boolean): ToggleResult {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (!nm.isNotificationPolicyAccessGranted) {
                Log.w(TAG, "DND: notification policy access not granted")
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return ToggleResult.NEEDS_PERMISSION
            }

            val currentFilter = nm.currentInterruptionFilter
            val isCurrentlyOn = currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL

            if (isCurrentlyOn == enabled) {
                Log.i(TAG, "DND already ${if (enabled) "on" else "off"}")
                return ToggleResult.ALREADY_IN_STATE
            }

            nm.setInterruptionFilter(
                if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            Log.i(TAG, "DND toggled ${if (enabled) "on" else "off"}")
            ToggleResult.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "DND toggle error: ${e.message}")
            ToggleResult.FAILED
        }
    }

    // ==================== HOTSPOT ====================

    /**
     * Toggle WiFi hotspot on/off.
     * Method 1: WifiManager reflection (setWifiApEnabled)
     * Method 2: Open tethering settings
     */
    @Suppress("DEPRECATION")
    fun setHotspot(enabled: Boolean): ToggleResult {
        // Method 1: Reflection
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getDeclaredMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.java
            )
            method.isAccessible = true
            method.invoke(wifiManager, null, enabled)
            Log.i(TAG, "Hotspot toggled ${if (enabled) "on" else "off"} via reflection")
            return ToggleResult.SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "Hotspot reflection failed: ${e.message}")
        }

        // Method 2: Open settings
        return try {
            val intent = Intent("android.settings.TETHERING_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened tethering settings for hotspot")
            ToggleResult.NEEDS_PANEL
        } catch (e: Exception) {
            // Fallback to wireless settings
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToggleResult.NEEDS_PANEL
            } catch (e2: Exception) {
                Log.e(TAG, "Hotspot settings error: ${e2.message}")
                ToggleResult.FAILED
            }
        }
    }

    // ==================== AIRPLANE MODE ====================

    /**
     * Toggle airplane mode on/off.
     * Try 1: Settings.Global.putInt (needs WRITE_SECURE_SETTINGS)
     * Try 2: Open airplane mode settings
     */
    fun setAirplaneMode(enabled: Boolean): ToggleResult {
        return try {
            val currentState = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

            if (currentState == enabled) {
                Log.i(TAG, "Airplane mode already ${if (enabled) "on" else "off"}")
                return ToggleResult.ALREADY_IN_STATE
            }

            // Try direct write (needs WRITE_SECURE_SETTINGS — granted via ADB)
            try {
                Settings.Global.putInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (enabled) 1 else 0)
                // Broadcast the change
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", enabled)
                }
                context.sendBroadcast(intent)
                Log.i(TAG, "Airplane mode toggled ${if (enabled) "on" else "off"} via Settings.Global")
                return ToggleResult.SUCCESS
            } catch (e: SecurityException) {
                Log.w(TAG, "Airplane mode direct write failed (no WRITE_SECURE_SETTINGS): ${e.message}")
            }

            // Fallback: open settings
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened airplane mode settings")
            ToggleResult.NEEDS_PANEL
        } catch (e: Exception) {
            Log.e(TAG, "Airplane mode error: ${e.message}")
            ToggleResult.FAILED
        }
    }

    // ==================== AUTO ROTATE ====================

    /**
     * Toggle auto-rotate on/off.
     * Direct API via Settings.System — needs WRITE_SETTINGS permission.
     */
    fun setAutoRotate(enabled: Boolean): ToggleResult {
        return try {
            if (!Settings.System.canWrite(context)) {
                Log.w(TAG, "Cannot write system settings — need permission")
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return ToggleResult.NEEDS_PERMISSION
            }

            val currentState = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0

            if (currentState == enabled) {
                Log.i(TAG, "Auto-rotate already ${if (enabled) "on" else "off"}")
                return ToggleResult.ALREADY_IN_STATE
            }

            Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (enabled) 1 else 0)
            Log.i(TAG, "Auto-rotate toggled ${if (enabled) "on" else "off"}")
            ToggleResult.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Auto-rotate error: ${e.message}")
            ToggleResult.FAILED
        }
    }

    // ==================== DARK MODE ====================

    /**
     * Toggle dark mode on/off.
     * Uses UiModeManager on API 30+.
     */
    fun setDarkMode(enabled: Boolean): ToggleResult {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "Dark mode toggle requires API 30+")
                return ToggleResult.FAILED
            }

            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            uiModeManager.setApplicationNightMode(
                if (enabled) UiModeManager.MODE_NIGHT_YES
                else UiModeManager.MODE_NIGHT_NO
            )
            Log.i(TAG, "Dark mode toggled ${if (enabled) "on" else "off"}")
            ToggleResult.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Dark mode error: ${e.message}")
            ToggleResult.FAILED
        }
    }

    // ==================== LOCATION ====================

    /**
     * Toggle location/GPS on/off.
     * Try 1: Settings.Secure.putInt (needs WRITE_SECURE_SETTINGS)
     * Try 2: Open location settings
     */
    @Suppress("DEPRECATION")
    fun setLocation(enabled: Boolean): ToggleResult {
        return try {
            // Check current state
            val locationMode = Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0)
            val isCurrentlyOn = locationMode != Settings.Secure.LOCATION_MODE_OFF

            if (isCurrentlyOn == enabled) {
                Log.i(TAG, "Location already ${if (enabled) "on" else "off"}")
                return ToggleResult.ALREADY_IN_STATE
            }

            // Try direct write (needs WRITE_SECURE_SETTINGS)
            try {
                Settings.Secure.putInt(
                    contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    if (enabled) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
                )
                Log.i(TAG, "Location toggled ${if (enabled) "on" else "off"} via Settings.Secure")
                return ToggleResult.SUCCESS
            } catch (e: SecurityException) {
                Log.w(TAG, "Location direct write failed: ${e.message}")
            }

            // Fallback: open location settings
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened location settings")
            ToggleResult.NEEDS_PANEL
        } catch (e: Exception) {
            Log.e(TAG, "Location toggle error: ${e.message}")
            ToggleResult.FAILED
        }
    }
}
