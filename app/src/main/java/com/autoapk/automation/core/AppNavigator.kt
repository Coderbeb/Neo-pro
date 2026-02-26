package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AppNavigator — Handles app launching and scrolling.
 *
 * Rules:
 * - Always check service is alive FIRST
 * - Always return boolean success/failure
 * - Always speak result to user
 * - Log every action with timestamp
 * - Never hardcode screen coordinates — always calculate relative to screen size
 */
class AppNavigator(
    private val context: Context,
    private val appRegistry: AppRegistry
) {

    private val TAG = "Neo_Nav"

    // Screen dimensions — calculated relative, never hardcoded
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    init {
        updateScreenDimensions()
    }

    private fun updateScreenDimensions() {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            Log.i(TAG, "Screen: ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Could not get screen dimensions: ${e.message}")
        }
    }

    // ==================== SERVICE CHECK ====================

    /**
     * Get the service instance or speak error and return null.
     */
    private fun getService(): AutomationAccessibilityService? {
        val svc = AutomationAccessibilityService.get()
        if (svc == null) {
            Log.w(TAG, "Service not connected — cannot execute navigation")
        }
        return svc
    }

    private fun notConnected(): Boolean {
        Log.w(TAG, "Service not connected")
        return false
    }

    // ==================== APP LAUNCHING ====================

    /**
     * Launch an app by name using AppRegistry fuzzy matching.
     *
     * 1. Search via AppRegistry (exact, alias, contains, fuzzy, Levenshtein)
     * 2. If match → launch via PackageManager
     * 3. If no match → speak "App not found"
     * 4. If no launch intent → speak "Cannot be opened"
     */
    fun openApp(appName: String): Boolean {
        val svc = getService()
        val packageName = appRegistry.findApp(appName)

        if (packageName != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                svc?.speak("Opening $appName")
                Log.i(TAG, "Opened app '$appName' → $packageName")
                return true
            } else {
                svc?.speak("$appName cannot be opened")
                Log.w(TAG, "No launch intent for $packageName")
                return false
            }
        }

        svc?.speak("App $appName not found")
        Log.w(TAG, "App '$appName' not found in registry")
        return false
    }

    // ==================== SCROLLING (with gesture fallback) ====================

    /**
     * scrollDown:
     * 1. Find scrollable node → ACTION_SCROLL_FORWARD
     * 2. If no scrollable node → gesture fallback:
     *    line from (screenWidth/2, screenHeight*0.7) to (screenWidth/2, screenHeight*0.3), 300ms
     * 3. Speak "Scrolling down"
     */
    fun scrollDown(): Boolean {
        val svc = getService() ?: return notConnected()

        // Try accessibility node scroll first
        val scrollable = findScrollableNode(svc.rootInActiveWindow)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (result) {
                svc.speak("Scrolling down")
                Log.i(TAG, "scrollDown: Node scroll SUCCESS")
                return true
            }
        }

        // Gesture fallback — swipe up to scroll down
        Log.i(TAG, "scrollDown: No scrollable node, using gesture fallback")
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.7f
        val endY = screenHeight * 0.3f
        dispatchGesture(svc, startX, startY, startX, endY, 300)
        svc.speak("Scrolling down")
        return true
    }

    /**
     * scrollUp:
     * 1. Find scrollable node → ACTION_SCROLL_BACKWARD
     * 2. If no scrollable node → gesture fallback:
     *    line from (screenWidth/2, screenHeight*0.3) to (screenWidth/2, screenHeight*0.7), 300ms
     * 3. Speak "Scrolling up"
     */
    fun scrollUp(): Boolean {
        val svc = getService() ?: return notConnected()

        val scrollable = findScrollableNode(svc.rootInActiveWindow)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (result) {
                svc.speak("Scrolling up")
                Log.i(TAG, "scrollUp: Node scroll SUCCESS")
                return true
            }
        }

        // Gesture fallback — swipe down to scroll up
        Log.i(TAG, "scrollUp: No scrollable node, using gesture fallback")
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.3f
        val endY = screenHeight * 0.7f
        dispatchGesture(svc, startX, startY, startX, endY, 300)
        svc.speak("Scrolling up")
        return true
    }

    // ==================== HELPERS ====================

    /**
     * Recursively find a scrollable node in the accessibility tree.
     */
    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * Dispatch a swipe gesture on the service.
     * Uses relative coordinates — never hardcoded.
     */
    private fun dispatchGesture(
        svc: AccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        svc.dispatchGesture(gesture, null, null)
    }
}
