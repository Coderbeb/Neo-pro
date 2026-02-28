package com.autoapk.automation.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autoapk.automation.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Auto-Describe Manager — Manages the Periodic Capture Loop
 *
 * Features:
 *   - Toggleable auto-describe mode (10-second default interval)
 *   - Navigation mode variant (5-second interval, safety-priority prompt)
 *   - Adjustable interval via voice (min 5s, max 60s)
 *   - Persistent notification while active
 *   - Auto-pause during: phone calls, TTS speaking, scene unchanged
 *   - Coroutine-based with clean cancellation
 */
class AutoDescribeManager(private val context: Context) {

    companion object {
        private const val TAG = "Neo_AutoDesc"
        private const val CHANNEL_ID = "neo_vision_channel"
        private const val NOTIFICATION_ID = 2001
        private const val DEFAULT_INTERVAL_MS = 10_000L
        private const val NAVIGATION_INTERVAL_MS = 5_000L
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MAX_INTERVAL_MS = 60_000L
    }

    interface AutoDescribeListener {
        /** Called each tick — orchestrator should run the full pipeline */
        fun onAutoCaptureTick(isNavigationMode: Boolean)
        /** Check if TTS is currently speaking */
        fun isTtsSpeaking(): Boolean
        /** Check if phone is in a call */
        fun isInCall(): Boolean
    }

    private var listener: AutoDescribeListener? = null
    private var autoJob: Job? = null
    private var intervalMs: Long = DEFAULT_INTERVAL_MS
    private var isNavigationMode = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun setListener(l: AutoDescribeListener) { listener = l }
    fun isActive(): Boolean = autoJob?.isActive == true
    fun isNavigationMode(): Boolean = isNavigationMode && isActive()
    fun getCurrentIntervalMs(): Long = intervalMs

    // ==================== START / STOP ====================

    /**
     * Start auto-describe mode with the configured interval.
     */
    fun start(customIntervalMs: Long? = null) {
        if (isActive()) {
            Log.w(TAG, "Already active — stopping first")
            stop(silent = true)
        }

        isNavigationMode = false
        intervalMs = (customIntervalMs ?: DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

        createNotificationChannel()
        showNotification("Neo Vision is actively watching")
        startLoop()
        Log.i(TAG, "Auto-describe started (interval=${intervalMs}ms)")
    }

    /**
     * Start navigation mode (5-second interval, safety-priority).
     */
    fun startNavigation() {
        if (isActive()) stop(silent = true)

        isNavigationMode = true
        intervalMs = NAVIGATION_INTERVAL_MS

        createNotificationChannel()
        showNotification("Neo Vision — Navigation mode active")
        startLoop()
        Log.i(TAG, "Navigation mode started (interval=${intervalMs}ms)")
    }

    /**
     * Stop auto-describe or navigation mode.
     */
    fun stop(silent: Boolean = false) {
        autoJob?.cancel()
        autoJob = null
        isNavigationMode = false
        removeNotification()
        Log.i(TAG, "Auto-describe stopped")
    }

    /**
     * Adjust the capture interval.
     * @param seconds New interval in seconds (clamped to 5-60)
     */
    fun setInterval(seconds: Int) {
        val newInterval = (seconds * 1000L).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        intervalMs = newInterval
        Log.i(TAG, "Interval updated to ${intervalMs}ms")

        // If currently running, restart with new interval
        if (isActive()) {
            autoJob?.cancel()
            startLoop()
        }
    }

    // ==================== INTERNAL LOOP ====================

    private fun startLoop() {
        autoJob = scope.launch {
            while (isActive) {
                delay(intervalMs)

                // Skip if phone is in a call
                if (listener?.isInCall() == true) {
                    Log.d(TAG, "Tick skipped — phone in call")
                    continue
                }

                // Skip if TTS is still speaking the previous description
                if (listener?.isTtsSpeaking() == true) {
                    Log.d(TAG, "Tick skipped — TTS still speaking")
                    continue
                }

                // Trigger the capture+describe pipeline
                try {
                    listener?.onAutoCaptureTick(isNavigationMode)
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-capture tick error: ${e.message}", e)
                }
            }
        }
    }

    // ==================== NOTIFICATIONS ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Neo Vision",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Neo Vision auto-describe notifications"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(text: String) {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Neo Vision")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun removeNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop(silent = true)
        scope.cancel()
        Log.i(TAG, "AutoDescribeManager released")
    }
}
