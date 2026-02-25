package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.KeyEvent

/**
 * Wake Trigger — Multi-Method Neo Wake System
 *
 * Three methods to wake Neo when speaker audio is blocking the mic:
 *
 *   1. Volume Button Combo: Press Vol Up + Vol Down within 500ms
 *      (Already exists in the codebase — extracted and unified here)
 *
 *   2. Bluetooth Triple-Press: Press headset button 3 times rapidly
 *      (Works when phone is in pocket with BT earbuds)
 *
 *   3. Proximity Sensor Triple-Cover: Cover sensor 3 times
 *      (Works when phone is on table, no buttons needed)
 *
 * All methods call the unified onWake callback.
 */
class WakeTrigger(
    private val context: Context,
    private val service: AccessibilityService,
    private val onWake: () -> Unit
) {

    companion object {
        private const val TAG = "Neo_Wake"
    }

    // ==================== METHOD 1: Volume Button Combo ====================
    private var volUpTime = 0L
    private var volDownTime = 0L

    /**
     * Call this from AccessibilityService.onKeyEvent()
     * Returns false to not consume the key event.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volUpTime = System.currentTimeMillis()
                checkVolumeCombo()
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volDownTime = System.currentTimeMillis()
                checkVolumeCombo()
            }
        }
        return false // Don't consume the event
    }

    private fun checkVolumeCombo() {
        if (Math.abs(volUpTime - volDownTime) < 500) {
            Log.i(TAG, "🔊 Volume combo detected — WAKING NEO")
            volUpTime = 0L
            volDownTime = 0L
            onWake()
        }
    }

    // ==================== METHOD 2: Bluetooth Triple-Press ====================
    private var lastBtPress = 0L
    private var btPressCount = 0

    val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
            if (event.action != KeyEvent.ACTION_DOWN) return
            if (event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK &&
                event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return

            val now = System.currentTimeMillis()
            btPressCount = if (now - lastBtPress < 600) btPressCount + 1 else 1
            lastBtPress = now

            Log.d(TAG, "BT press #$btPressCount")

            if (btPressCount >= 3) {
                btPressCount = 0
                Log.i(TAG, "🎧 Bluetooth triple-press detected — WAKING NEO")
                onWake()
            }
        }
    }

    // ==================== METHOD 3: Proximity Sensor Triple-Cover ====================
    private var proxCount = 0
    private var lastProxTime = 0L
    private var wasNear = false

    val proxListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val isNear = event.values[0] < event.sensor.maximumRange
            // Only count transitions from far → near
            if (isNear && !wasNear) {
                val now = System.currentTimeMillis()
                proxCount = if (now - lastProxTime < 800) proxCount + 1 else 1
                lastProxTime = now

                Log.d(TAG, "Proximity cover #$proxCount")

                if (proxCount >= 3) {
                    proxCount = 0
                    Log.i(TAG, "👋 Proximity triple-cover detected — WAKING NEO")
                    onWake()
                }
            }
            wasNear = isNear
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ==================== LIFECYCLE ====================

    /**
     * Register all wake triggers.
     * Call from AccessibilityService.onServiceConnected().
     */
    fun register() {
        Log.i(TAG, "Registering wake triggers")

        // Bluetooth media button listener
        try {
            @Suppress("DEPRECATION")
            context.registerReceiver(
                btReceiver,
                IntentFilter(Intent.ACTION_MEDIA_BUTTON).apply {
                    priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                }
            )
            Log.i(TAG, "✅ Bluetooth triple-press registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register BT receiver: ${e.message}")
        }

        // Proximity sensor listener
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensor ->
                sm.registerListener(proxListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                Log.i(TAG, "✅ Proximity sensor registered")
            } ?: Log.w(TAG, "No proximity sensor available")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register proximity sensor: ${e.message}")
        }
    }

    /**
     * Unregister all wake triggers.
     * Call from AccessibilityService.onDestroy().
     */
    fun unregister() {
        Log.i(TAG, "Unregistering wake triggers")

        try {
            context.unregisterReceiver(btReceiver)
        } catch (_: Exception) { }

        try {
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
                .unregisterListener(proxListener)
        } catch (_: Exception) { }
    }
}
