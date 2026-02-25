package com.autoapk.automation.core

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log

/**
 * Speaker Detection — Media Audio Check
 *
 * Prevents Neo from listening when the phone's speaker is playing media
 * (YouTube videos, Instagram reels, WhatsApp voice messages, music).
 *
 * Three checks:
 *   1. Headphones/Bluetooth → allow listening (speaker not an issue)
 *   2. AudioManager.isMusicActive → block if media playing on speaker
 *   3. MediaSessionManager → check active playback sessions (Android 8+)
 *
 * Integration: Call shouldListen() before starting voice recognition.
 * If false, skip the recognition cycle and retry in 1 second.
 */
class SpeakerDetection(private val context: Context) {

    companion object {
        private const val TAG = "Neo_Speaker"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Check if it's safe to start voice recognition.
     *
     * Returns true if:
     *   - Headphones/Bluetooth connected (speaker audio won't reach mic)
     *   - No media is currently playing
     *
     * Returns false if:
     *   - Media is playing through the phone's speaker
     */
    fun shouldListen(): Boolean {
        // If headphones/bluetooth connected, speaker won't interfere with mic
        @Suppress("DEPRECATION")
        if (audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn) {
            return true
        }

        // Check if media is playing through speaker
        if (audioManager.isMusicActive) {
            Log.d(TAG, "Media playing on speaker — skipping listen cycle")
            return false
        }

        // Check active media sessions (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                val sessions = msm?.getActiveSessions(null) ?: emptyList()
                for (session in sessions) {
                    val state = session.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) {
                        Log.d(TAG, "Active media session detected (${session.packageName}) — skipping listen")
                        return false
                    }
                }
            } catch (e: SecurityException) {
                // getActiveSessions needs notification listener permission
                // Fall through to allow listening if we can't check
                Log.w(TAG, "Can't check media sessions: ${e.message}")
            }
        }

        return true
    }

    /**
     * Force check — is ANY audio playing right now?
     * Used to decide whether to lower volume temporarily when waking.
     */
    fun isMediaPlaying(): Boolean {
        return audioManager.isMusicActive
    }

    /**
     * Temporarily lower media volume for voice recognition.
     * Returns the original volume to restore later.
     */
    fun lowerMediaVolume(): Int {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            1, // Set to minimum audible
            0   // No flags
        )
        Log.i(TAG, "Lowered media volume from $current to 1")
        return current
    }

    /**
     * Restore media volume after voice recognition.
     */
    fun restoreMediaVolume(previousVolume: Int) {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            previousVolume,
            0
        )
        Log.i(TAG, "Restored media volume to $previousVolume")
    }
}
