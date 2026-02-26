package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Task 1.2: Service Health Monitor
 *
 * Checks every 3 seconds if AccessibilityService is alive.
 * - If service dies → shows persistent notification "Please enable accessibility"
 * - If service is alive → hides notification
 * - On app launch → checks if enabled, guides user if not
 * - Toast "Voice control ready" on reconnect
 */
class ServiceHealthMonitor(private val context: Context) {

    companion object {
        private const val TAG = "Neo_HealthMonitor"
        private const val CHECK_INTERVAL_MS = 3000L
        private const val CHANNEL_ID = "neo_service_health"
        private const val NOTIFICATION_ID = 9001
    }

    enum class ServiceState {
        ACTIVE,
        DISABLED,
        CRASHED,
        UNKNOWN
    }

    var currentState: ServiceState = ServiceState.UNKNOWN
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var wasServiceAlive = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            val alive = isServiceEnabled()
            val newState = when {
                alive -> ServiceState.ACTIVE
                wasServiceAlive -> ServiceState.CRASHED  // Was alive, now dead = crashed
                else -> ServiceState.DISABLED
            }

            if (newState != currentState) {
                Log.i(TAG, "Service state changed: $currentState → $newState")
                currentState = newState

                when (newState) {
                    ServiceState.ACTIVE -> {
                        hideNotification()
                        if (wasServiceAlive || currentState == ServiceState.CRASHED) {
                            // Reconnected after being dead
                            Toast.makeText(context, "Voice control ready", Toast.LENGTH_SHORT).show()
                        }
                    }
                    ServiceState.DISABLED, ServiceState.CRASHED -> {
                        showEnableNotification()
                    }
                    ServiceState.UNKNOWN -> { /* do nothing */ }
                }
            }

            wasServiceAlive = alive

            if (isMonitoring) {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Start monitoring the service health every 3 seconds.
     */
    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        createNotificationChannel()
        handler.post(checkRunnable)
        Log.i(TAG, "Health monitoring started (every ${CHECK_INTERVAL_MS}ms)")
    }

    /**
     * Stop monitoring.
     */
    fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacks(checkRunnable)
        hideNotification()
        Log.i(TAG, "Health monitoring stopped")
    }

    /**
     * On app launch: check if service is enabled, guide user if not.
     */
    fun onAppLaunch() {
        if (!isServiceEnabled()) {
            currentState = ServiceState.DISABLED
            Log.w(TAG, "Service not enabled on app launch — guiding user")
            Toast.makeText(context, "Please enable Neo accessibility service", Toast.LENGTH_LONG).show()
        } else {
            currentState = ServiceState.ACTIVE
            wasServiceAlive = true
            Log.i(TAG, "Service is enabled on app launch")
        }
    }

    /**
     * Check if our AccessibilityService is enabled in system settings.
     * Uses AccessibilityManager.getEnabledAccessibilityServiceList().
     */
    fun isServiceEnabled(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            val ourServiceName = ComponentName(context, AutomationAccessibilityService::class.java)
            enabledServices.any { info ->
                val componentName = ComponentName.unflattenFromString(info.id)
                componentName == ourServiceName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service state: ${e.message}")
            false
        }
    }

    /**
     * Open Accessibility Settings so user can enable the service.
     */
    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open accessibility settings: ${e.message}")
        }
    }

    // ==================== NOTIFICATION ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Service Health",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when Neo accessibility service needs to be enabled"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun showEnableNotification() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Neo voice control is disabled")
            .setContentText("Tap to enable accessibility service")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Showing 'enable service' notification")
    }

    private fun hideNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }
}
