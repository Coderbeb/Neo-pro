package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.LinkedList
import java.util.Queue

/**
 * YouTube Automation — Search, List, Play, and Control
 *
 * Provides:
 *   - searchAndPlay(query): Open YouTube → search → play first result
 *   - search(query): Open YouTube → search (don't auto-play)
 *   - listVideos(): List video titles on current screen (skipping ads/nav)
 *   - playVideoAtIndex(n): Click the Nth video (content items only)
 *   - controlPlayer(action): Pause/Play/Next/Previous/Fullscreen
 *
 * Supports both YouTube and YouTube RVX.
 */
class YouTubeAutomation(
    private val service: AccessibilityService,
    private val finder: AdaptiveNodeFinder,
    private val waiter: ScreenWaitSystem,
    private val tts: (String) -> Unit
) {

    companion object {
        private const val TAG = "Neo_YouTube"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_RVX_PACKAGE = "app.rvx.android.youtube"

        // Content descriptions that identify YouTube navigation (not video content)
        private val NAV_DESCRIPTIONS = listOf(
            "home", "shorts", "subscriptions", "library",
            "you", "create", "search", "navigate up", "account",
            "notifications", "cast", "more options"
        )
    }

    // Cached video list from last listVideos() call
    private var cachedVideoTitles = listOf<String>()

    /**
     * Open YouTube, search for something, and play the first result.
     */
    suspend fun searchAndPlay(query: String): Boolean {
        if (!search(query)) return false

        // Wait for results to load
        delay(2000)
        if (!waiter.waitForClickableCount(5, 4000)) {
            Log.w(TAG, "Results may not have fully loaded")
        }

        // Click first video (skip ads)
        val firstVideo = findFirstVideoCard()
        if (firstVideo != null) {
            firstVideo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            tts("Playing first result")
            Log.i(TAG, "✅ Playing first result for '$query'")
            return true
        }
        return fail("No videos found")
    }

    /**
     * Open YouTube and search for a query (without auto-playing).
     */
    suspend fun search(query: String): Boolean {
        Log.i(TAG, "Searching YouTube for: '$query'")

        // Step 1: Launch YouTube
        if (!launchYouTube()) return false

        // Step 2: Click Search icon
        delay(800)
        val searchBtn = finder.find("Search") ?: return fail("Can't find search button")
        searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!waiter.waitForEditableField()) return fail("Search bar didn't appear")

        // Step 3: Type query
        val searchField = waiter.findEditable(service.rootInActiveWindow)
            ?: return fail("Can't find search input")
        searchField.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }
        )
        delay(300)

        // Submit search: try IME action, fallback to key event
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            searchField.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            // Fallback: use paste + newline trick or click search suggestion
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query + "\n")
            searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        tts("Searching YouTube for $query")
        Log.i(TAG, "✅ Search submitted for '$query'")
        return true
    }

    /**
     * List video titles currently visible on screen.
     * Skips ads, navigation elements, and non-video items.
     * Returns numbered list of video titles.
     */
    suspend fun listVideos(): List<String> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val videos = mutableListOf<String>()
        val all = flatten(root)

        for (node in all) {
            if (!node.isClickable) continue
            if (isAd(node)) continue
            if (isNavElement(node)) continue
            val title = extractVideoTitle(node)
            if (title != null && title.length > 5) {
                videos.add(title)
            }
        }

        cachedVideoTitles = videos.take(10)
        Log.i(TAG, "Listed ${cachedVideoTitles.size} videos")
        return cachedVideoTitles
    }

    /**
     * Play the Nth video from the current screen.
     * Uses cached video list from listVideos() if available,
     * otherwise scans the screen fresh.
     */
    suspend fun playVideoAtIndex(index: Int): Boolean {
        Log.i(TAG, "Playing video at index $index")

        // Get video list
        val videos = if (cachedVideoTitles.isNotEmpty()) cachedVideoTitles else listVideos()

        if (index < 1 || index > videos.size) {
            return fail("Video number $index not found. I see ${videos.size} videos")
        }

        val targetTitle = videos[index - 1]
        val targetNode = finder.find(targetTitle, wantClickable = true)
        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            tts("Playing video $index: ${targetTitle.take(30)}")
            Log.i(TAG, "✅ Playing video $index: $targetTitle")
            return true
        }

        // Fallback: try clicking by position in the video card list
        return playByPosition(index)
    }

    /**
     * Control the YouTube video player.
     *
     * First taps the screen center to reveal controls (they auto-hide),
     * then finds and clicks the appropriate button.
     */
    suspend fun controlPlayer(action: String) {
        Log.i(TAG, "Player control: $action")

        // Tap center of screen to reveal controls
        val dm = service.resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val centerY = dm.heightPixels / 2f

        injectTap(centerX, centerY)
        delay(500)

        when (action.lowercase()) {
            "pause", "play" -> {
                val btn = finder.find("Pause video") ?: finder.find("Play video")
                if (btn != null) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    tts(if (action == "pause") "Paused" else "Playing")
                } else {
                    tts("Can't find play/pause button")
                }
            }
            "next" -> {
                val btn = finder.find("Next video")
                if (btn != null) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    tts("Next video")
                } else {
                    tts("Can't find next button")
                }
            }
            "previous" -> {
                val btn = finder.find("Previous video")
                if (btn != null) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    tts("Previous video")
                } else {
                    tts("Can't find previous button")
                }
            }
            "fullscreen" -> {
                val btn = finder.find("Enter full screen") ?: finder.find("Full screen")
                if (btn != null) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    tts("Full screen")
                }
            }
        }
    }

    // ==================== INTERNAL HELPERS ====================

    /**
     * Find the first video card that isn't an ad or nav element.
     */
    private fun findFirstVideoCard(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val all = flatten(root)

        for (node in all) {
            if (!node.isClickable) continue
            if (isAd(node)) continue
            if (isNavElement(node)) continue
            val title = extractVideoTitle(node)
            if (title != null && title.length > 5) return node
        }
        return null
    }

    /**
     * Fallback: click by position among non-ad clickable video cards.
     */
    private fun playByPosition(index: Int): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val all = flatten(root)
        var videoCount = 0

        for (node in all) {
            if (!node.isClickable) continue
            if (isAd(node)) continue
            if (isNavElement(node)) continue
            val title = extractVideoTitle(node)
            if (title != null && title.length > 5) {
                videoCount++
                if (videoCount == index) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    tts("Playing video $index")
                    return true
                }
            }
        }
        return fail("Video $index not found on screen")
    }

    /**
     * Extract video title from a clickable container.
     * Finds the longest text child that isn't a duration or view count.
     */
    private fun extractVideoTitle(container: AccessibilityNodeInfo): String? {
        var longestText: String? = null
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(container)
        while (queue.isNotEmpty()) {
            val n = queue.poll() ?: continue
            val text = n.text?.toString()
            if (text != null && (longestText == null || text.length > longestText!!.length)) {
                // Exclude durations like "3:45", view counts, and time stamps
                if (!text.matches(Regex("^\\d+:\\d+(:\\d+)?$")) &&
                    !text.matches(Regex("^[\\d.]+[KMB]? views?$", RegexOption.IGNORE_CASE)) &&
                    !text.contains(" ago", true) &&
                    !text.matches(Regex("^\\d+ (seconds?|minutes?|hours?|days?|weeks?|months?|years?) ago$", RegexOption.IGNORE_CASE)) &&
                    text.length > 5) {
                    longestText = text
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return longestText
    }

    /**
     * Check if a node is an advertisement.
     */
    private fun isAd(node: AccessibilityNodeInfo): Boolean {
        val allText = getAllChildText(node).lowercase()
        return allText.contains("ad ·") ||
                allText.contains("sponsored") ||
                allText.contains("विज्ञापन") ||
                allText.contains("promoted") ||
                allText.contains("install") && allText.contains("ad")
    }

    /**
     * Check if a node is a navigation element (not video content).
     */
    private fun isNavElement(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        return desc in NAV_DESCRIPTIONS
    }

    /**
     * Get all text from a node and its children.
     */
    private fun getAllChildText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(node)
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
     * Launch YouTube (tries regular first, then RVX).
     */
    private suspend fun launchYouTube(): Boolean {
        // Try regular YouTube first
        var intent = service.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
        var pkg = YOUTUBE_PACKAGE

        // Try YouTube RVX if regular not installed
        if (intent == null) {
            intent = service.packageManager.getLaunchIntentForPackage(YOUTUBE_RVX_PACKAGE)
            pkg = YOUTUBE_RVX_PACKAGE
        }

        if (intent == null) return fail("YouTube not installed")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        if (!waiter.waitForApp(pkg)) return fail("YouTube didn't open")

        Log.i(TAG, "YouTube launched ($pkg)")
        return true
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
