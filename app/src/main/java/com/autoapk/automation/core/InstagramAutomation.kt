package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.LinkedList
import java.util.Queue

/**
 * Instagram Automation — Reels, Feed, Profile, Auto-Scroll
 *
 * Provides:
 *   - openReels(): Navigate to Reels tab
 *   - nextReel() / previousReel(): Swipe gestures
 *   - likeCurrentPost(): Like via button or double-tap
 *   - startAutoScroll(interval) / stopAutoScroll(): Auto-scroll with timer
 *   - openProfile(username): Search and open a user profile
 *   - followUser(): Click Follow button on current profile
 *   - describeCurrentContent(): Read content descriptions aloud
 */
class InstagramAutomation(
    private val service: AccessibilityService,
    private val finder: AdaptiveNodeFinder,
    private val waiter: ScreenWaitSystem,
    private val tts: (String) -> Unit
) {

    companion object {
        private const val TAG = "Neo_Instagram"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }

    // Auto-scroll coroutine job — cancel to stop
    private var autoScrollJob: Job? = null

    /**
     * Open Instagram and navigate to the Reels tab.
     */
    suspend fun openReels(): Boolean {
        Log.i(TAG, "Opening Reels")

        if (!launchInstagram()) return false

        delay(800)
        val reelsTab = finder.find("Reels") ?: return fail("Can't find Reels tab")
        reelsTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        tts("Reels opened")
        Log.i(TAG, "✅ Reels opened")
        return true
    }

    /**
     * Navigate to a specific Instagram tab.
     * Supports: Home, Search, Reels, Shop, Profile
     */
    suspend fun navigateToTab(tabName: String): Boolean {
        Log.i(TAG, "Navigating to tab: $tabName")

        // Make sure Instagram is open
        val root = service.rootInActiveWindow
        if (root?.packageName?.toString() != INSTAGRAM_PACKAGE) {
            if (!launchInstagram()) return false
            delay(800)
        }

        val tab = finder.find(tabName) ?: return fail("Can't find $tabName tab")
        tab.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        tts("$tabName opened")
        return true
    }

    /**
     * Swipe up to go to next reel/post.
     */
    fun nextReel() {
        Log.d(TAG, "Next reel (swipe up)")
        val dm = service.resources.displayMetrics
        swipe(
            dm.widthPixels / 2f,
            dm.heightPixels * 0.75f,
            dm.widthPixels / 2f,
            dm.heightPixels * 0.25f,
            300
        )
    }

    /**
     * Swipe down to go to previous reel/post.
     */
    fun previousReel() {
        Log.d(TAG, "Previous reel (swipe down)")
        val dm = service.resources.displayMetrics
        swipe(
            dm.widthPixels / 2f,
            dm.heightPixels * 0.25f,
            dm.widthPixels / 2f,
            dm.heightPixels * 0.75f,
            300
        )
    }

    /**
     * Scroll down in the feed.
     */
    fun scrollDown() {
        val dm = service.resources.displayMetrics
        swipe(
            dm.widthPixels / 2f,
            dm.heightPixels * 0.7f,
            dm.widthPixels / 2f,
            dm.heightPixels * 0.3f,
            300
        )
    }

    /**
     * Scroll up in the feed.
     */
    fun scrollUp() {
        val dm = service.resources.displayMetrics
        swipe(
            dm.widthPixels / 2f,
            dm.heightPixels * 0.3f,
            dm.widthPixels / 2f,
            dm.heightPixels * 0.7f,
            300
        )
    }

    /**
     * Like the current post/reel.
     * Tries the Like button first, falls back to double-tap.
     */
    suspend fun likeCurrentPost() {
        Log.i(TAG, "Liking current post")

        // Try finding Like button
        val likeBtn = finder.find("Like") ?: finder.find("Liked")
        if (likeBtn != null) {
            likeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            tts("Liked")
            return
        }

        // Fallback: double-tap center of screen (Instagram's universal like)
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        injectTap(cx, cy)
        delay(100)
        injectTap(cx, cy)
        tts("Double tapped to like")
    }

    /**
     * Start auto-scrolling at a fixed interval.
     * Each interval, performs a swipe to next content.
     * Cancel by calling stopAutoScroll() or saying "stop".
     */
    fun startAutoScroll(intervalSeconds: Int = 10) {
        Log.i(TAG, "Starting auto-scroll every ${intervalSeconds}s")
        stopAutoScroll() // Cancel any existing job

        autoScrollJob = CoroutineScope(Dispatchers.Main).launch {
            tts("Auto scroll started. Say stop to end.")
            while (isActive) {
                delay(intervalSeconds * 1000L)
                nextReel()
                Log.d(TAG, "Auto-scroll tick")
            }
        }
    }

    /**
     * Stop auto-scrolling.
     */
    fun stopAutoScroll() {
        if (autoScrollJob != null) {
            autoScrollJob?.cancel()
            autoScrollJob = null
            tts("Auto scroll stopped")
            Log.i(TAG, "Auto-scroll stopped")
        }
    }

    /**
     * Check if auto-scroll is currently running.
     */
    fun isAutoScrolling(): Boolean = autoScrollJob?.isActive == true

    /**
     * Open a user's profile by searching for them.
     */
    suspend fun openProfile(username: String): Boolean {
        Log.i(TAG, "Opening profile: $username")

        if (!launchInstagram()) return false

        delay(800)
        // Navigate to Search/Explore tab
        val searchTab = finder.find("Search") ?: finder.find("Search and explore")
            ?: return fail("Can't find search tab")
        searchTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!waiter.waitForEditableField()) return fail("Search didn't open")

        // Type username
        val searchField = waiter.findEditable(service.rootInActiveWindow)
            ?: return fail("No search input found")
        searchField.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, username)
            }
        )
        delay(1500) // Wait for search results

        // Click on the user
        val userNode = finder.find(username) ?: return fail("User $username not found")
        userNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        tts("Opened $username's profile")
        Log.i(TAG, "✅ Opened profile: $username")
        return true
    }

    /**
     * Follow the user whose profile is currently open.
     */
    suspend fun followUser(): Boolean {
        val followBtn = finder.find("Follow") ?: return fail("Can't find follow button")
        followBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        tts("Followed")
        Log.i(TAG, "✅ Followed user")
        return true
    }

    /**
     * Describe the current content visible on screen.
     * Reads content descriptions, filtering out nav/UI elements.
     */
    suspend fun describeCurrentContent(): String {
        val root = service.rootInActiveWindow ?: return "Can't read screen"
        val all = flatten(root)
        val parts = mutableListOf<String>()

        for (node in all) {
            val desc = node.contentDescription?.toString() ?: continue
            if (desc.length > 10 && !desc.lowercase().let {
                    it.contains("tab") || it.contains("button") || it.contains("navigate") ||
                    it.contains("back") || it.contains("close")
                }) {
                parts.add(desc)
            }
        }
        return parts.joinToString(". ").ifBlank { "No description available" }
    }

    // ==================== INTERNAL HELPERS ====================

    /**
     * Launch Instagram.
     */
    private suspend fun launchInstagram(): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE)
            ?: return fail("Instagram not installed")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        if (!waiter.waitForApp(INSTAGRAM_PACKAGE)) return fail("Instagram didn't open")
        Log.i(TAG, "Instagram launched")
        return true
    }

    private fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun injectTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun fail(msg: String): Boolean {
        Log.w(TAG, "❌ $msg")
        tts(msg)
        return false
    }

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll() ?: continue
            result.add(n)
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }
}
