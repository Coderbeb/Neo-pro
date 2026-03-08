package com.autoapk.automation.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Call Manager
 * 
 * Manages phone call interactions.
 * Features:
 * - Answer call
 * - End call
 * - Reject call (End call works as reject when ringing)
 * - Toggle Speaker
 * - Mute/Unmute microphone
 * 
 * Requirements: 3.8
 */
class CallManager(private val context: Context) {

    companion object {
        private const val TAG = "CallManager"
    }

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    interface CallListener {
        fun onMessage(message: String)
        fun onError(message: String)
    }

    var listener: CallListener? = null

    /**
     * Answer incoming call
     * Uses 3 strategies for maximum compatibility:
     * 1. TelecomManager.acceptRingingCall() (standard API)
     * 2. Accessibility: click "Answer"/"Accept" button on call screen
     * 3. KeyEvent: simulate headset button press
     */
    fun answerCall() {
        var answered = false

        // STRATEGY 1: TelecomManager API
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    telecomManager.acceptRingingCall()
                    answered = true
                    Log.i(TAG, "Call answered via TelecomManager")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager answer failed: ${e.message}")
        }

        // STRATEGY 2: Accessibility — click Answer/Accept button on screen
        if (!answered) {
            try {
                val service = AutomationAccessibilityService.instance
                if (service != null) {
                    // Try common answer button labels
                    val answerLabels = listOf("Answer", "Accept", "answer", "accept",
                        "Swipe up to answer", "Slide to answer", "Answer call")
                    for (label in answerLabels) {
                        if (service.findAndClickSmart(label, silent = true)) {
                            answered = true
                            Log.i(TAG, "Call answered via accessibility click: '$label'")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility answer failed: ${e.message}")
            }
        }

        // STRATEGY 3: KeyEvent — simulate headset button press (works on many devices)
        if (!answered) {
            try {
                val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK)
                val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK)
                
                @Suppress("DEPRECATION")
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(downEvent)
                am.dispatchMediaKeyEvent(upEvent)
                
                answered = true
                Log.i(TAG, "Call answered via headset hook KeyEvent")
            } catch (e: Exception) {
                Log.w(TAG, "KeyEvent answer failed: ${e.message}")
            }
        }

        if (answered) {
            listener?.onMessage("Call answered")
        } else {
            listener?.onError("Could not answer call")
        }
    }

    /**
     * End or reject call
     * Requires ANSWER_PHONE_CALLS permission
     */
    fun endCall() {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                listener?.onError("End call permission required")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val success = telecomManager.endCall()
                if (success) {
                    listener?.onMessage("Call ended")
                } else {
                    listener?.onError("Failed to end call")
                }
            } else {
                 listener?.onError("Device not supported for ending calls")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call", e)
            listener?.onError("Could not end call: ${e.message}")
        }
    }

    /**
     * Toggle speakerphone
     */
    fun toggleSpeaker(enable: Boolean) {
        try {
            audioManager.isSpeakerphoneOn = enable
            listener?.onMessage(if (enable) "Speaker on" else "Speaker off")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling speaker", e)
            listener?.onError("Could not toggle speaker")
        }
    }

    /**
     * Toggle microphone mute
     */
    fun toggleMute(enable: Boolean) {
        try {
            audioManager.isMicrophoneMute = enable
            listener?.onMessage(if (enable) "Microphone muted" else "Microphone unmuted")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling mute", e)
            listener?.onError("Could not toggle mute")
        }
    }
}
