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
     * Requires ANSWER_PHONE_CALLS permission
     */
    fun answerCall() {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                listener?.onError("Answer call permission required")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager.acceptRingingCall()
                listener?.onMessage("Call answered")
            } else {
                // Deprecated way for older Android, simplified here as minSdk is likely higher
                listener?.onError("Device not supported for answering calls")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call", e)
            listener?.onError("Could not answer call: ${e.message}")
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
