package com.autoapk.automation.core

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Phone State Detector
 * 
 * Monitors phone state and classifies it into one of four states:
 * - NORMAL: Regular navigation (no verification required)
 * - CONSUMING_CONTENT: Video playing or photo viewing (verification required)
 * - IN_CALL: Active phone call (verification required)
 * - CAMERA_ACTIVE: Camera app open (verification required for capture/record)
 * 
 * Uses event-driven callbacks for zero-latency state detection:
 * - TelephonyManager for call state
 * - AudioManager for media playback
 * - CameraManager for camera usage
 * 
 * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.15, 7.12
 */
class PhoneStateDetector(private val context: Context) {

    companion object {
        private const val TAG = "PhoneStateDetector"
        private const val CALL_END_TRANSITION_DELAY_MS = 1000L
    }

    /**
     * Phone state enum
     */
    enum class PhoneState {
        NORMAL,              // No special state
        CONSUMING_CONTENT,   // Video playing or photo viewing
        IN_CALL,            // Active phone call
        CAMERA_ACTIVE       // Camera app open
    }

    /**
     * State change listener interface
     */
    interface StateChangeListener {
        fun onStateChanged(oldState: PhoneState, newState: PhoneState)
    }

    private var currentState = PhoneState.NORMAL
    private var listener: StateChangeListener? = null
    private val handler = Handler(Looper.getMainLooper())

    // Telephony monitoring
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null // TelephonyCallback for Android 12+
    
    // Audio monitoring
    private var audioManager: AudioManager? = null
    
    // Camera monitoring
    private var cameraManager: CameraManager? = null
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null
    private var isCameraInUse = false

    // Call state tracking
    private var isCallActive = false
    private var callEndTransitionPending = false

    // Neo State Manager — for in-call mode transitions
    var neoState: NeoStateManager? = null

    // Contact Registry — for caller name lookup
    var contactRegistry: ContactRegistry? = null

    /**
     * Set the state change listener
     */
    fun setListener(listener: StateChangeListener) {
        this.listener = listener
    }

    /**
     * Get current phone state
     */
    fun getCurrentState(): PhoneState = currentState

    /**
     * Check if current state requires verification
     */
    fun requiresVerification(): Boolean {
        return when (currentState) {
            PhoneState.NORMAL -> false
            PhoneState.CONSUMING_CONTENT -> true
            PhoneState.IN_CALL -> true
            PhoneState.CAMERA_ACTIVE -> false // Verification checked per-command
        }
    }

    /**
     * Start monitoring phone state
     */
    fun startMonitoring() {
        Log.i(TAG, "Starting phone state monitoring...")
        
        // Initialize managers
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        // Register call state listener
        registerCallStateListener()
        
        // Register camera availability callback
        registerCameraCallback()
        
        // Initial state check
        updateState()
        
        Log.i(TAG, "Phone state monitoring started. Initial state: $currentState")
    }

    /**
     * Stop monitoring phone state
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping phone state monitoring...")
        
        // Unregister call state listener
        unregisterCallStateListener()
        
        // Unregister camera callback
        unregisterCameraCallback()
        
        // Cancel pending transitions
        handler.removeCallbacksAndMessages(null)
        
        telephonyManager = null
        audioManager = null
        cameraManager = null
        
        Log.i(TAG, "Phone state monitoring stopped")
    }

    /**
     * Register call state listener (handles both old and new APIs)
     */
    private fun registerCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ uses TelephonyCallback
                registerTelephonyCallback()
            } else {
                // Older Android uses PhoneStateListener
                registerPhoneStateListener()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register call state listener: ${e.message}")
        }
    }

    /**
     * Register PhoneStateListener for Android < 12
     */
    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChange(state, phoneNumber)
            }
        }
        
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        Log.d(TAG, "PhoneStateListener registered (Android < 12)")
    }

    /**
     * Register TelephonyCallback for Android 12+
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state)
            }
        }
        
        telephonyCallback = callback
        telephonyManager?.registerTelephonyCallback(
            context.mainExecutor,
            callback
        )
        Log.d(TAG, "TelephonyCallback registered (Android 12+)")
    }

    /**
     * Handle call state change
     * Requirement: 3.4, 3.15, 7.12 - IN_CALL detection and 1-second transition
     */
    private fun handleCallStateChange(state: Int, incomingNumber: String? = null) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended
                if (isCallActive) {
                    Log.i(TAG, "Call ended, scheduling transition to NORMAL in ${CALL_END_TRANSITION_DELAY_MS}ms")
                    isCallActive = false
                    callEndTransitionPending = true
                    
                    // Requirement 3.15, 7.12: Transition within 1 second
                    handler.postDelayed({
                        if (callEndTransitionPending) {
                            callEndTransitionPending = false
                            updateState()
                            // Return Neo to ACTIVE mode after call ends
                            neoState?.exitInCallMode()
                        }
                    }, CALL_END_TRANSITION_DELAY_MS)
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                // Incoming call — announce caller name
                Log.i(TAG, "Incoming call detected")
                announceIncomingCall(incomingNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call active (answered or outgoing)
                if (!isCallActive) {
                    Log.i(TAG, "Call active detected")
                    isCallActive = true
                    callEndTransitionPending = false
                    handler.removeCallbacksAndMessages(null)
                    updateState()
                    // Enter IN_CALL mode if toggle is enabled
                    if (neoState?.inCallModeEnabled == true) {
                        neoState?.enterInCallMode()
                    }
                }
            }
        }
    }

    /**
     * Announce incoming caller name via TTS.
     * Looks up the number in ContactRegistry for a friendly name.
     */
    private fun announceIncomingCall(number: String?) {
        val service = AutomationAccessibilityService.instance ?: return
        
        val callerName = if (!number.isNullOrBlank()) {
            val contact = contactRegistry?.findContactByNumber(number)
            contact?.name ?: number
        } else {
            "Unknown"
        }

        val lang = neoState?.lastLanguage ?: "en"
        val announcement = if (lang == "hi") {
            "$callerName aapko call kar raha hai"
        } else {
            "$callerName is calling you"
        }

        Log.i(TAG, "Announcing incoming call: $announcement")
        service.speak(announcement)
    }

    /**
     * Unregister call state listener
     */
    private fun unregisterCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager?.unregisterTelephonyCallback(it as TelephonyCallback)
                }
                telephonyCallback = null
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener?.let {
                    telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
                phoneStateListener = null
            }
            Log.d(TAG, "Call state listener unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering call state listener: ${e.message}")
        }
    }

    /**
     * Register camera availability callback
     * Requirement: 3.5 - CAMERA_ACTIVE detection
     */
    private fun registerCameraCallback() {
        try {
            cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    Log.d(TAG, "Camera $cameraId available")
                    isCameraInUse = false
                    updateState()
                }

                override fun onCameraUnavailable(cameraId: String) {
                    Log.d(TAG, "Camera $cameraId unavailable (in use)")
                    isCameraInUse = true
                    updateState()
                }
            }
            
            cameraManager?.registerAvailabilityCallback(
                cameraAvailabilityCallback!!,
                handler
            )
            Log.d(TAG, "Camera availability callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register camera callback: ${e.message}")
        }
    }

    /**
     * Unregister camera availability callback
     */
    private fun unregisterCameraCallback() {
        try {
            cameraAvailabilityCallback?.let {
                cameraManager?.unregisterAvailabilityCallback(it)
            }
            cameraAvailabilityCallback = null
            Log.d(TAG, "Camera availability callback unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering camera callback: ${e.message}")
        }
    }

    /**
     * Check if media is currently playing
     * Requirement: 3.3 - CONSUMING_CONTENT detection
     */
    private fun isMediaPlaying(): Boolean {
        return try {
            audioManager?.isMusicActive == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking media playback: ${e.message}")
            false
        }
    }

    /**
     * Update current state based on all conditions
     * Requirement: 3.1, 3.2, 3.3, 3.4, 3.5 - State classification
     */
    private fun updateState() {
        val newState = determineState()
        
        if (newState != currentState) {
            val oldState = currentState
            currentState = newState
            
            Log.i(TAG, "State changed: $oldState → $newState")
            
            // Notify listener
            listener?.onStateChanged(oldState, newState)
        }
    }

    /**
     * Determine current state based on all conditions
     */
    private fun determineState(): PhoneState {
        // Priority order: IN_CALL > CAMERA_ACTIVE > CONSUMING_CONTENT > NORMAL
        
        // Check call state (highest priority)
        if (isCallActive) {
            return PhoneState.IN_CALL
        }
        
        // Check camera state
        if (isCameraInUse) {
            return PhoneState.CAMERA_ACTIVE
        }
        
        // Check media playback
        if (isMediaPlaying()) {
            return PhoneState.CONSUMING_CONTENT
        }
        
        // Default to normal
        return PhoneState.NORMAL
    }

    /**
     * Force state update (for external triggers)
     */
    fun forceStateUpdate() {
        updateState()
    }
}
