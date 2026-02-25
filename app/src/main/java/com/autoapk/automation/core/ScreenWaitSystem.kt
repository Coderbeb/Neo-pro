package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.LinkedList
import java.util.Queue

/**
 * Smart Screen Wait System
 *
 * Replaces fixed Thread.sleep() / Handler.postDelayed() delays with
 * intelligent polling that verifies screen state before proceeding.
 *
 * Uses exponential backoff (100ms → 500ms) with double-confirmation
 * to prevent false positives from splash screens or loading states.
 */
class ScreenWaitSystem(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "Neo_ScreenWait"
    }

    /**
     * Wait until a specific app's package is in the foreground.
     * Used after launching an app to confirm it actually opened.
     */
    suspend fun waitForApp(packageName: String, timeoutMs: Long = 6000): Boolean {
        Log.i(TAG, "Waiting for app: $packageName (timeout=${timeoutMs}ms)")
        return poll(timeoutMs) {
            service.rootInActiveWindow?.packageName?.toString() == packageName
        }.also { success ->
            if (success) Log.i(TAG, "✅ App $packageName is foreground")
            else Log.w(TAG, "❌ Timeout waiting for $packageName")
        }
    }

    /**
     * Wait until an editable text field appears on screen.
     * Used after clicking a search icon to confirm the search bar appeared.
     */
    suspend fun waitForEditableField(timeoutMs: Long = 3000): Boolean {
        Log.i(TAG, "Waiting for editable field (timeout=${timeoutMs}ms)")
        return poll(timeoutMs) {
            findEditable(service.rootInActiveWindow) != null
        }.also { success ->
            if (success) Log.i(TAG, "✅ Editable field found")
            else Log.w(TAG, "❌ Timeout waiting for editable field")
        }
    }

    /**
     * Wait until specific text appears on screen.
     * Used after typing in search to confirm results loaded.
     */
    suspend fun waitForText(text: String, timeoutMs: Long = 5000): Boolean {
        Log.i(TAG, "Waiting for text: '$text' (timeout=${timeoutMs}ms)")
        return poll(timeoutMs) {
            getAllText(service.rootInActiveWindow).contains(text, true)
        }.also { success ->
            if (success) Log.i(TAG, "✅ Text '$text' found on screen")
            else Log.w(TAG, "❌ Timeout waiting for text '$text'")
        }
    }

    /**
     * Wait until the screen content changes from a previous snapshot.
     * Used to verify an action (click, navigation) actually had an effect.
     */
    suspend fun waitForScreenChange(previousText: String, timeoutMs: Long = 3000): Boolean {
        Log.i(TAG, "Waiting for screen change (timeout=${timeoutMs}ms)")
        return poll(timeoutMs) {
            getAllText(service.rootInActiveWindow) != previousText
        }.also { success ->
            if (success) Log.i(TAG, "✅ Screen changed")
            else Log.w(TAG, "❌ Screen did not change")
        }
    }

    /**
     * Wait until a minimum number of clickable elements are on screen.
     * Used after page load to confirm content (not just loading spinner) appeared.
     */
    suspend fun waitForClickableCount(min: Int, timeoutMs: Long = 4000): Boolean {
        Log.i(TAG, "Waiting for at least $min clickable items (timeout=${timeoutMs}ms)")
        return poll(timeoutMs) {
            countClickables(service.rootInActiveWindow) >= min
        }.also { success ->
            if (success) Log.i(TAG, "✅ Found >= $min clickable items")
            else Log.w(TAG, "❌ Timeout: fewer than $min clickable items")
        }
    }

    /**
     * Core polling mechanism with exponential backoff and double-confirmation.
     *
     * The double-confirmation (stableCount >= 2) prevents false positives:
     * e.g., a splash screen briefly shows the correct package name before
     * the actual app UI loads.
     *
     * Backoff: 100ms → 150ms → 225ms → 337ms → 500ms (capped)
     */
    private suspend fun poll(timeoutMs: Long, check: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        var interval = 100L
        var stableCount = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                if (check()) {
                    stableCount++
                    if (stableCount >= 2) return true
                } else {
                    stableCount = 0
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll check failed: ${e.message}")
                stableCount = 0
            }
            delay(interval)
            interval = minOf((interval * 1.5).toLong(), 500L)
        }
        return false
    }

    /**
     * Find the first editable text field on screen using BFS traversal.
     * Returns the node if found, null otherwise.
     */
    fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            if (node.isEditable || node.className?.toString()?.contains("EditText") == true)
                return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Collect ALL text and content descriptions from  the screen.
     * Used by waitForText() and waitForScreenChange().
     */
    fun getAllText(root: AccessibilityNodeInfo?): String {
        root ?: return ""
        val sb = StringBuilder()
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll() ?: continue
            n.text?.let { sb.append(it).append(" ") }
            n.contentDescription?.let { sb.append(it).append(" ") }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return sb.toString()
    }

    /**
     * Count all clickable elements on screen.
     */
    private fun countClickables(root: AccessibilityNodeInfo?): Int {
        root ?: return 0
        var count = 0
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll() ?: continue
            if (n.isClickable) count++
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return count
    }
}
