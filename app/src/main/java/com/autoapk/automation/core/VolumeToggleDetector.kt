package com.autoapk.automation.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Volume Toggle Detector
 * 
 * Detects simultaneous volume button presses (within 600ms window) to toggle
 * voice listening activation state. This provides a physical, accessible way
 * for blind users to control the system without needing to see the screen.
 * 
 * State Machine:
 * IDLE → VOLUME_DOWN_PRESSED → BOTH_PRESSED → TOGGLE_DETECTED → IDLE
 * IDLE → VOLUME_UP_PRESSED → BOTH_PRESSED → TOGGLE_DETECTED → IDLE
 * 
 * Requirements: 1.1, 1.2, 1.3, 1.8
 */
class VolumeToggleDetector(private val context: Context) {

    companion object {
        private const val TAG = "VolumeToggleDetector"
        private const val SIMULTANEOUS_PRESS_WINDOW_MS = 600L
        private const val STATE_RESET_TIMEOUT_MS = 1000L
    }

    /**
     * Listener interface for toggle events
     */
    interface ToggleListener {
        fun onToggleDetected()
    }

    private enum class State {
        IDLE,
        VOLUME_DOWN_PRESSED,
        VOLUME_UP_PRESSED,
        BOTH_PRESSED,
        TOGGLE_DETECTED
    }

    private var currentState = State.IDLE
    private var volumeDownPressTime = 0L
    private var volumeUpPressTime = 0L
    private var listener: ToggleListener? = null
    private var lastToggleTime = 0L

    /**
     * Set the listener for toggle events
     */
    fun setListener(listener: ToggleListener) {
        this.listener = listener
    }

    /**
     * Handle volume key down event
     * @return true if event should be consumed (toggle detected), false otherwise
     */
    fun onVolumeKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) {
            // Ignore key repeats
            return false
        }

        val currentTime = SystemClock.elapsedRealtime()

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownPressTime = currentTime
                handleVolumeDownPressed(currentTime)
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeUpPressTime = currentTime
                handleVolumeUpPressed(currentTime)
            }
        }

        // Check if both buttons are now pressed within the window
        if (areBothButtonsPressed(currentTime)) {
            return handleBothPressed()
        }

        // Reset state if timeout exceeded
        if (currentTime - maxOf(volumeDownPressTime, volumeUpPressTime) > STATE_RESET_TIMEOUT_MS) {
            resetState()
        }

        return false
    }

    /**
     * Handle volume key up event
     * @return true if event should be consumed (toggle detected), false otherwise
     */
    fun onVolumeKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // If we detected a toggle, consume the key up event to prevent volume change
        if (currentState == State.TOGGLE_DETECTED) {
            val currentTime = SystemClock.elapsedRealtime()
            // Only consume if within a short time after toggle detection
            if (currentTime - lastToggleTime < 500L) {
                Log.d(TAG, "Consuming key up event after toggle detection")
                return true
            }
        }
        return false
    }

    /**
     * Check if this is a toggle gesture
     */
    fun isToggleGesture(): Boolean {
        return currentState == State.TOGGLE_DETECTED
    }

    private fun handleVolumeDownPressed(currentTime: Long) {
        when (currentState) {
            State.IDLE -> {
                currentState = State.VOLUME_DOWN_PRESSED
                Log.d(TAG, "State: IDLE → VOLUME_DOWN_PRESSED")
            }
            State.VOLUME_UP_PRESSED -> {
                // Other button already pressed, check timing
                if (currentTime - volumeUpPressTime <= SIMULTANEOUS_PRESS_WINDOW_MS) {
                    currentState = State.BOTH_PRESSED
                    Log.d(TAG, "State: VOLUME_UP_PRESSED → BOTH_PRESSED")
                } else {
                    // Too late, reset to this button
                    currentState = State.VOLUME_DOWN_PRESSED
                    Log.d(TAG, "State: VOLUME_UP_PRESSED → VOLUME_DOWN_PRESSED (timeout)")
                }
            }
            else -> {
                // Already in BOTH_PRESSED or TOGGLE_DETECTED, ignore
            }
        }
    }

    private fun handleVolumeUpPressed(currentTime: Long) {
        when (currentState) {
            State.IDLE -> {
                currentState = State.VOLUME_UP_PRESSED
                Log.d(TAG, "State: IDLE → VOLUME_UP_PRESSED")
            }
            State.VOLUME_DOWN_PRESSED -> {
                // Other button already pressed, check timing
                if (currentTime - volumeDownPressTime <= SIMULTANEOUS_PRESS_WINDOW_MS) {
                    currentState = State.BOTH_PRESSED
                    Log.d(TAG, "State: VOLUME_DOWN_PRESSED → BOTH_PRESSED")
                } else {
                    // Too late, reset to this button
                    currentState = State.VOLUME_UP_PRESSED
                    Log.d(TAG, "State: VOLUME_DOWN_PRESSED → VOLUME_UP_PRESSED (timeout)")
                }
            }
            else -> {
                // Already in BOTH_PRESSED or TOGGLE_DETECTED, ignore
            }
        }
    }

    private fun areBothButtonsPressed(currentTime: Long): Boolean {
        val downPressed = volumeDownPressTime > 0 && 
                         (currentTime - volumeDownPressTime) <= SIMULTANEOUS_PRESS_WINDOW_MS
        val upPressed = volumeUpPressTime > 0 && 
                       (currentTime - volumeUpPressTime) <= SIMULTANEOUS_PRESS_WINDOW_MS
        
        return downPressed && upPressed && 
               Math.abs(volumeDownPressTime - volumeUpPressTime) <= SIMULTANEOUS_PRESS_WINDOW_MS
    }

    private fun handleBothPressed(): Boolean {
        if (currentState == State.BOTH_PRESSED || currentState == State.TOGGLE_DETECTED) {
            // Already handled
            return currentState == State.TOGGLE_DETECTED
        }

        currentState = State.TOGGLE_DETECTED
        lastToggleTime = SystemClock.elapsedRealtime()
        Log.i(TAG, "Toggle gesture detected! Notifying listener.")
        
        // Notify listener
        listener?.onToggleDetected()
        
        // Reset state after a short delay to allow key up events to be consumed
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            resetState()
        }, 500L)
        
        return true // Consume the event to prevent volume change
    }

    private fun resetState() {
        if (currentState != State.IDLE) {
            Log.d(TAG, "State: $currentState → IDLE (reset)")
        }
        currentState = State.IDLE
        volumeDownPressTime = 0L
        volumeUpPressTime = 0L
    }
}
