package com.autoapk.automation.core

import android.util.Log

/**
 * Task 5.1: VoiceFeedback — Centralized voice feedback system.
 *
 * Standardizes ALL spoken feedback for consistency:
 * - success(action)   → "Done. [action]"
 * - fail(action)      → "Could not [action]"
 * - confirm(question) → "Did you mean [question]?"
 * - info(msg)         → "[msg]"
 * - notReady(feature) → "[feature] is not available right now"
 *
 * All methods guard against null service with fallback logging.
 */
object VoiceFeedback {

    private const val TAG = "Neo_Feedback"

    /**
     * Speak success — "Done. [action]"
     * Example: success("Opening WhatsApp") → "Done. Opening WhatsApp"
     */
    fun success(action: String) {
        speak("$action")
        Log.i(TAG, "✅ $action")
    }

    /**
     * Speak failure — "Could not [action]"
     * Example: fail("open WhatsApp") → "Could not open WhatsApp"
     */
    fun fail(action: String) {
        speak("Could not $action")
        Log.w(TAG, "❌ Could not $action")
    }

    /**
     * Speak confirmation request — "Did you mean [question]?"
     * Example: confirm("turn on WiFi") → "Did you mean turn on WiFi?"
     */
    fun confirm(question: String) {
        speak("Did you mean $question?")
        Log.i(TAG, "❓ Did you mean $question?")
    }

    /**
     * Speak informational message — "[msg]"
     * Example: info("Battery is at 85 percent") → "Battery is at 85 percent"
     */
    fun info(msg: String) {
        speak(msg)
        Log.i(TAG, "ℹ️ $msg")
    }

    /**
     * Speak not-ready message — "[feature] is not available right now"
     * Example: notReady("WiFi toggle") → "WiFi toggle is not available right now"
     */
    fun notReady(feature: String) {
        speak("$feature is not available right now")
        Log.w(TAG, "⚠️ $feature is not available right now")
    }

    /**
     * Speak error with recovery hint — "[error]. Try [hint]"
     * Example: error("WiFi toggle failed", "saying 'turn on wifi' again") → ...
     */
    fun error(errorMsg: String, hint: String? = null) {
        val full = if (hint != null) "$errorMsg. Try $hint" else errorMsg
        speak(full)
        Log.e(TAG, "💥 $full")
    }

    /**
     * Speak retry message — "Retrying [action]..."
     */
    fun retrying(action: String) {
        speak("Retrying $action")
        Log.i(TAG, "🔄 Retrying $action")
    }

    /**
     * Internal speak — gets service and calls speak, or logs if unavailable.
     */
    private fun speak(text: String) {
        val svc = AutomationAccessibilityService.get()
        if (svc != null) {
            svc.speak(text)
        } else {
            Log.w(TAG, "Service unavailable, cannot speak: '$text'")
        }
    }
}
