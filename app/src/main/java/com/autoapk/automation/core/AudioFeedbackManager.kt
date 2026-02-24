package com.autoapk.automation.core

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Manages audio feedback (beeps/chimes) for user interactions.
 * Uses system ToneGenerator for low-latency, asset-free sound effects.
 */
class AudioFeedbackManager(private val context: Context) {

    private val TAG = "Neo_Audio"
    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            // STREAM_MUSIC allows volume control via media volume
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            Log.i(TAG, "AudioFeedbackManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ToneGenerator: ${e.message}")
        }
    }

    fun playSuccess() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing success tone: ${e.message}")
        }
    }

    fun playError() {
        try {
            // Two quick low beeps for error
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing error tone: ${e.message}")
        }
    }

    fun playStartListening() {
        try {
            // High pitch short beep
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing start listening tone: ${e.message}")
        }
    }

    fun playStopListening() {
        try {
            // Low pitch short beep
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ANSWER, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing stop listening tone: ${e.message}")
        }
    }

    fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }
}
