package com.autoapk.automation.core

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Neo Notification Listener — Monitors ALL incoming notifications.
 *
 * Two behaviors based on context:
 *   - WhatsApp CLOSED: Announces "[Name] replied on WhatsApp" (or other app)
 *     and stores the message. User says "read it" to hear content.
 *   - WhatsApp OPEN (live chat active): Suppressed — reply monitor handles it.
 *
 * Stores the last notification for "read it" / "reply" commands.
 */
class NeoNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "Neo_Notif"

        // Singleton for access from CommandRouter
        private var instanceRef: NeoNotificationListener? = null
        fun get(): NeoNotificationListener? = instanceRef

        // Last received notification buffer (for "read it" command)
        private var lastNotification: PendingNotification? = null
        private val recentNotifications = mutableListOf<PendingNotification>()
        private const val MAX_STORED = 10

        // TTS callback — set by AutomationAccessibilityService
        var ttsCallback: ((String) -> Unit)? = null

        // Callback to check if WhatsApp live chat is active
        var isWhatsAppLiveChatActive: (() -> Boolean)? = null

        /**
         * Read the last stored notification message via TTS.
         * Called when user says "read it".
         */
        fun readLastNotification(tts: (String) -> Unit): Boolean {
            val notif = lastNotification ?: run {
                tts("No recent notifications")
                return false
            }
            tts("${notif.senderName} said: ${notif.messageContent}")
            Log.i(TAG, "Read notification: ${notif.senderName} → ${notif.messageContent}")
            return true
        }

        /**
         * Get the sender name from the last notification.
         * Used by "reply" command to know who to reply to.
         */
        fun getLastSender(): String? = lastNotification?.senderName

        /**
         * Get the package name from the last notification.
         * Used to know which app the reply should go to.
         */
        fun getLastPackage(): String? = lastNotification?.packageName

        /**
         * Get the last message content without speaking it.
         */
        fun getLastMessage(): String? = lastNotification?.messageContent
    }

    /**
     * Stored notification data.
     */
    data class PendingNotification(
        val appName: String,
        val senderName: String,
        val messageContent: String,
        val packageName: String,
        val timestamp: Long
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instanceRef = this
        Log.i(TAG, "Notification Listener CONNECTED")
    }

    override fun onListenerDisconnected() {
        instanceRef = null
        Log.i(TAG, "Notification Listener DISCONNECTED")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // Skip our own notifications
        if (sbn.packageName == "com.autoapk.automation") return

        // Skip ongoing/non-clearable notifications (system, media controls)
        if (sbn.isOngoing) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Extract sender and message
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Skip empty or very short notifications
        if (text.length < 2) return

        val appName = getAppName(sbn.packageName)
        val isWhatsApp = sbn.packageName == "com.whatsapp" || sbn.packageName == "com.whatsapp.w4b"

        Log.i(TAG, "Notification from $appName: $title → $text")

        // If WhatsApp live chat is active, skip WhatsApp notifications
        // (the reply monitor inside WhatsAppAutomation handles it)
        if (isWhatsApp && isWhatsAppLiveChatActive?.invoke() == true) {
            Log.d(TAG, "Skipping WhatsApp notification — live chat is active")
            return
        }

        // Store this notification
        val pending = PendingNotification(
            appName = appName,
            senderName = title,
            messageContent = text,
            packageName = sbn.packageName,
            timestamp = System.currentTimeMillis()
        )
        lastNotification = pending
        recentNotifications.add(pending)
        if (recentNotifications.size > MAX_STORED) {
            recentNotifications.removeAt(0)
        }

        // Announce: "[Name] replied on [App]"
        val announcement = if (isWhatsApp) {
            "$title replied on WhatsApp"
        } else {
            "$title on $appName"
        }

        ttsCallback?.invoke(announcement)
        Log.i(TAG, "Announced: $announcement")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed
    }

    /**
     * Get a human-readable app name from package name.
     */
    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            when (packageName) {
                "com.whatsapp" -> "WhatsApp"
                "com.whatsapp.w4b" -> "WhatsApp Business"
                "com.instagram.android" -> "Instagram"
                "com.google.android.youtube" -> "YouTube"
                "com.google.android.gm" -> "Gmail"
                else -> packageName.substringAfterLast(".")
            }
        }
    }
}
