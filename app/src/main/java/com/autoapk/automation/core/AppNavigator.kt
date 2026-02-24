package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Handles app navigation and global system actions.
 * - Launching apps by name
 * - Global actions (Home, Back, Recents, Notifications)
 * - Scrolling
 */
class AppNavigator(private val context: Context, private val appRegistry: AppRegistry) {

    private val TAG = "Neo_Nav"

    private val service: AutomationAccessibilityService?
        get() = AutomationAccessibilityService.instance

    /**
     * Launch an app by its name (using AppRegistry for smart matching).
     */
    fun openApp(appName: String): Boolean {
        // Use AppRegistry to find the best matching package
        val packageName = appRegistry.findApp(appName)
        
        if (packageName != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                service?.speak("Opening $appName")
                return true
            }
        }
        
        service?.speak("App $appName not found")
        return false
    }

    private fun getLaunchIntentForApp(packageName: String): Intent? {
        return context.packageManager.getLaunchIntentForPackage(packageName)
    }

    /**
     * Perform global actions
     */
    fun goHome(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false.also {
            if (it) service?.speak("Going home")
        }
    }

    fun goBack(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false.also {
            if (it) service?.speak("Going back")
        }
    }

    fun openRecents(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false.also {
            if (it) service?.speak("Opening recent apps")
        }
    }

    fun openNotifications(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) ?: false.also {
            if (it) service?.speak("Opening notifications")
        }
    }

    fun openQuickSettings(): Boolean {
        return service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) ?: false.also {
            if (it) service?.speak("Opening quick settings")
        }
    }

    /**
     * Scrolling
     */
    fun scrollUp(): Boolean {
        val scrollable = findScrollableNode(service?.rootInActiveWindow)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (result) service?.speak("Scrolling up")
            return result
        }
        service?.speak("Nothing to scroll up")
        return false
    }

    fun scrollDown(): Boolean {
        val scrollable = findScrollableNode(service?.rootInActiveWindow)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (result) service?.speak("Scrolling down")
            return result
        }
        service?.speak("Nothing to scroll down")
        return false
    }

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
}
