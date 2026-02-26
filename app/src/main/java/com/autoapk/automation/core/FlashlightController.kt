package com.autoapk.automation.core

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * Task 4.3: Flashlight Controller — Direct Implementation
 *
 * Uses CameraManager.setTorchMode() directly — works on Android 6+.
 * Instant flashlight on/off.
 */
class FlashlightController(private val context: Context) {

    companion object {
        private const val TAG = "Neo_Flashlight"
    }

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraId: String? = null
    private var currentState: Boolean = false
    private var isCallbackRegistered = false

    // Speak callback — set by CommandProcessor
    var speakCallback: ((String) -> Unit)? = null

    init {
        try {
            cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                registerTorchCallback()
                Log.i(TAG, "Initialized with camera ID: $cameraId")
            } else {
                Log.w(TAG, "No cameras found on device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
        }
    }

    /**
     * Register torch callback to track current state.
     * This tells us if the flashlight is on or off at any time.
     */
    private fun registerTorchCallback() {
        if (isCallbackRegistered) return
        try {
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id == cameraId) {
                        currentState = enabled
                        Log.d(TAG, "Torch state changed: ${if (enabled) "ON" else "OFF"}")
                    }
                }

                override fun onTorchModeUnavailable(id: String) {
                    if (id == cameraId) {
                        Log.w(TAG, "Torch unavailable (camera in use by another app)")
                    }
                }
            }, null)
            isCallbackRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Could not register torch callback: ${e.message}")
        }
    }

    /**
     * Turn flashlight ON.
     * Returns true if successful.
     */
    fun turnOn(): Boolean {
        val id = cameraId
        if (id == null) {
            speak("No flashlight available")
            return false
        }

        if (currentState) {
            speak("Flashlight is already on")
            return true
        }

        return try {
            cameraManager.setTorchMode(id, true)
            speak("Flashlight on")
            Log.i(TAG, "Flashlight turned ON")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not turn on flashlight: ${e.message}")
            speak("Could not turn on flashlight")
            false
        }
    }

    /**
     * Turn flashlight OFF.
     * Returns true if successful.
     */
    fun turnOff(): Boolean {
        val id = cameraId
        if (id == null) {
            speak("No flashlight available")
            return false
        }

        if (!currentState) {
            speak("Flashlight is already off")
            return true
        }

        return try {
            cameraManager.setTorchMode(id, false)
            speak("Flashlight off")
            Log.i(TAG, "Flashlight turned OFF")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not turn off flashlight: ${e.message}")
            speak("Could not turn off flashlight")
            false
        }
    }

    /**
     * Toggle flashlight (on → off, off → on).
     * Returns true if successful.
     */
    fun toggle(): Boolean {
        return if (currentState) turnOff() else turnOn()
    }

    /**
     * Check if flashlight is currently on.
     */
    fun isOn(): Boolean = currentState

    private fun speak(text: String) {
        speakCallback?.invoke(text)
            ?: AutomationAccessibilityService.get()?.speak(text)
    }
}
