package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
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
 *   - searchAndPlay(query): Open YouTube -> search -> play first result
 *   - search(query): Open YouTube -> search (don't auto-play)
 *   - listVideos(): List video titles on current screen (skipping ads/nav)
 *   - playVideoAtIndex(n): Click the Nth video (content items only)
 *   - playVideoByName(name): Find and click a video by its title
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

        // All possible content descriptions for player controls
        // YouTube uses different labels across versions and languages
        private val PAUSE_LABELS = listOf(
            "Pause video", "Pause", "pause video", "pause",
            "वीडियो रोकें", "रोकें"
        )
        private val PLAY_LABELS = listOf(
            "Play video", "Play", "play video", "play",
            "Resume", "resume",
            "वीडियो चलाएं", "चलाएं"
        )
        private val NEXT_LABELS = listOf(
            "Next video", "Next", "next video", "next",
            "Skip", "skip",
            "अगला वीडियो", "अगला"
        )
        private val PREVIOUS_LABELS = listOf(
            "Previous video", "Previous", "previous video", "previous",
            "Rewind", "rewind",
            "पिछला वीडियो", "पिछला"
        )
        private val FULLSCREEN_LABELS = listOf(
            "Enter full screen", "Full screen", "Fullscreen",
            "Enter fullscreen", "Maximize",
            "fullscreen", "full screen", "enter full screen",
            "फुल स्क्रीन"
        )
        private val EXIT_FULLSCREEN_LABELS = listOf(
            "Exit full screen", "Exit fullscreen", "Minimize",
            "exit full screen", "minimize"
        )
    }

    // Cached video list from last scan
    private var cachedVideoNodes = listOf<Pair<String, AccessibilityNodeInfo>>()
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
            clickNode(firstVideo)
            tts("Playing first result")
            Log.i(TAG, "Playing first result for '$query'")
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
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query + "\n")
            searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        tts("Searching YouTube for $query")
        Log.i(TAG, "Search submitted for '$query'")
        return true
    }

    /**
     * List video titles currently visible on screen.
     */
    suspend fun listVideos(): List<String> {
        val videoCards = scanVideoCards()
        cachedVideoNodes = videoCards
        cachedVideoTitles = videoCards.map { it.first }
        Log.i(TAG, "Listed ${cachedVideoTitles.size} videos")
        return cachedVideoTitles
    }

    /**
     * Play the Nth video from the current screen.
     * Directly clicks the video card node — no secondary search needed.
     */
    suspend fun playVideoAtIndex(index: Int): Boolean {
        Log.i(TAG, "Playing video at index $index")

        // Re-scan video cards to get fresh clickable nodes
        val videoCards = scanVideoCards()
        cachedVideoNodes = videoCards
        cachedVideoTitles = videoCards.map { it.first }

        if (index < 1 || index > videoCards.size) {
            return fail("Video number $index not found. I see ${videoCards.size} videos")
        }

        val (title, node) = videoCards[index - 1]
        val clicked = clickNode(node)
        if (clicked) {
            tts("Playing video $index: ${title.take(30)}")
            Log.i(TAG, "Playing video $index: $title")
            return true
        }

        // Fallback: tap the center of the node's bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            injectTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            tts("Playing video $index")
            return true
        }

        return fail("Couldn't click video $index")
    }

    /**
     * Play a video by its name (fuzzy match against titles on screen).
     * E.g., "play chunnari chunnari"
     */
    suspend fun playVideoByName(name: String): Boolean {
        Log.i(TAG, "Playing video by name: '$name'")

        val videoCards = scanVideoCards()
        if (videoCards.isEmpty()) {
            return false  // silent fail — caller will search instead
        }

        // Find best fuzzy match
        val nameLower = name.lowercase()
        var bestMatch: Pair<String, AccessibilityNodeInfo>? = null
        var bestScore = 0f

        for ((title, node) in videoCards) {
            val titleLower = title.lowercase()
            val score = when {
                titleLower == nameLower -> 1.0f
                titleLower.contains(nameLower) -> 0.9f
                nameLower.split(" ").all { titleLower.contains(it) } -> 0.8f
                nameLower.split(" ").size > 1 &&
                    nameLower.split(" ").count { titleLower.contains(it) }.toFloat() /
                    nameLower.split(" ").size > 0.5f -> 0.6f
                else -> 0f
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = title to node
            }
        }

        if (bestMatch != null && bestScore >= 0.6f) {
            val (title, node) = bestMatch
            val clicked = clickNode(node)
            if (clicked) {
                tts("Playing ${title.take(30)}")
                Log.i(TAG, "Playing by name: $title (score=$bestScore)")
                return true
            }
            // Fallback: tap center of bounds
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0) {
                injectTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                tts("Playing ${title.take(30)}")
                return true
            }
        }

        return false  // silent fail — caller will search
    }

    /**
     * Control the YouTube video player.
     *
     * Multi-strategy approach:
     *   1. Tap screen to reveal controls (they auto-hide)
     *   2. Search by content description (multiple known labels)
     *   3. Search by resource ID (YouTube-specific IDs)
     *   4. Fallback: double-tap center to toggle play/pause
     */
    suspend fun controlPlayer(action: String) {
        Log.i(TAG, "Player control: $action")

        // Tap center of screen to reveal player controls
        revealPlayerControls()

        val root = service.rootInActiveWindow
        if (root == null) {
            tts("Can't access screen")
            return
        }

        when (action.lowercase()) {
            "pause", "play" -> {
                val labels = if (action == "pause") PAUSE_LABELS + PLAY_LABELS else PLAY_LABELS + PAUSE_LABELS
                val btn = findByContentDescription(root, labels)
                    ?: findByResourceId(root, listOf("player_control_play_pause_replay_button", "player_play_pause_replay_button"))
                if (btn != null) {
                    clickNode(btn)
                    tts(if (action == "pause") "Paused" else "Playing")
                } else {
                    // Last resort: tap center to toggle play/pause
                    val dm = service.resources.displayMetrics
                    injectTap(dm.widthPixels / 2f, dm.heightPixels / 3f)
                    delay(100)
                    injectTap(dm.widthPixels / 2f, dm.heightPixels / 3f)
                    tts(if (action == "pause") "Paused" else "Playing")
                }
            }
            "next" -> {
                val btn = findByContentDescription(root, NEXT_LABELS)
                    ?: findByResourceId(root, listOf("player_next_button", "player_control_next_button"))
                if (btn != null) {
                    clickNode(btn)
                    tts("Next video")
                } else {
                    tts("Can't find next button")
                }
            }
            "previous" -> {
                val btn = findByContentDescription(root, PREVIOUS_LABELS)
                    ?: findByResourceId(root, listOf("player_previous_button", "player_control_previous_button"))
                if (btn != null) {
                    clickNode(btn)
                    tts("Previous video")
                } else {
                    tts("Can't find previous button")
                }
            }
            "fullscreen" -> {
                val btn = findByContentDescription(root, FULLSCREEN_LABELS + EXIT_FULLSCREEN_LABELS)
                    ?: findByResourceId(root, listOf("fullscreen_button", "player_fullscreen_button"))
                if (btn != null) {
                    clickNode(btn)
                    tts("Fullscreen")
                } else {
                    tts("Can't find fullscreen button")
                }
            }
        }
    }

    // ==================== VIDEO CARD SCANNING ====================

    /**
     * Scan the accessibility tree and return video card pairs (title, clickableNode).
     */
    private fun scanVideoCards(): List<Pair<String, AccessibilityNodeInfo>> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val all = flatten(root)
        val videos = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        val seenTitles = mutableSetOf<String>()

        for (node in all) {
            if (!node.isClickable) continue
            if (isAd(node)) continue
            if (isNavElement(node)) continue

            val title = extractVideoTitle(node)
            if (title != null && title.length > 5 && title !in seenTitles) {
                seenTitles.add(title)
                videos.add(title to node)
            }
        }

        return videos.take(10)
    }

    // ==================== PLAYER CONTROL HELPERS ====================

    /**
     * Reveal player controls by tapping the video area.
     */
    private suspend fun revealPlayerControls() {
        val dm = service.resources.displayMetrics
        val playerCenterX = dm.widthPixels / 2f
        val playerCenterY = dm.heightPixels / 4f

        // First tap to reveal controls
        injectTap(playerCenterX, playerCenterY)
        delay(600)

        // Check if controls appeared; if not, tap again at center
        val root = service.rootInActiveWindow
        if (root != null) {
            val hasControls = findByContentDescription(root, PAUSE_LABELS + PLAY_LABELS) != null
            if (!hasControls) {
                injectTap(dm.widthPixels / 2f, dm.heightPixels / 2f)
                delay(600)
            }
        }
    }

    /**
     * Find a node by matching its contentDescription against known labels.
     */
    private fun findByContentDescription(
        root: AccessibilityNodeInfo,
        labels: List<String>
    ): AccessibilityNodeInfo? {
        val all = flatten(root)
        // Pass 1: exact match
        for (label in labels) {
            for (node in all) {
                val desc = node.contentDescription?.toString() ?: continue
                if (desc.equals(label, ignoreCase = true) && node.isVisibleToUser) {
                    Log.i(TAG, "Found control by description: '$desc'")
                    return if (node.isClickable) node else findClickableParent(node)
                }
            }
        }
        // Pass 2: contains match
        for (label in labels) {
            for (node in all) {
                val desc = node.contentDescription?.toString() ?: continue
                if (desc.contains(label, ignoreCase = true) && node.isVisibleToUser) {
                    Log.i(TAG, "Found control by contains: '$desc' (label='$label')")
                    return if (node.isClickable) node else findClickableParent(node)
                }
            }
        }
        return null
    }

    /**
     * Find a node by matching its resource ID.
     */
    private fun findByResourceId(
        root: AccessibilityNodeInfo,
        partialIds: List<String>
    ): AccessibilityNodeInfo? {
        val all = flatten(root)
        for (partialId in partialIds) {
            for (node in all) {
                val resId = node.viewIdResourceName ?: continue
                if (resId.contains(partialId, ignoreCase = true) && node.isVisibleToUser) {
                    Log.i(TAG, "Found control by resource ID: '$resId'")
                    return if (node.isClickable) node else findClickableParent(node)
                }
            }
        }
        return null
    }

    // ==================== INTERNAL HELPERS ====================

    /**
     * Click a node using multiple strategies.
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Strategy 1: Direct ACTION_CLICK
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        // Strategy 2: Walk up to clickable parent
        val clickableParent = findClickableParent(node)
        if (clickableParent != null) {
            if (clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        // Strategy 3: Tap at the center of the node's bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            injectTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            return true
        }
        return false
    }

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

    private fun extractVideoTitle(container: AccessibilityNodeInfo): String? {
        var longestText: String? = null
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(container)
        while (queue.isNotEmpty()) {
            val n = queue.poll() ?: continue
            val text = n.text?.toString()
            if (text != null && (longestText == null || text.length > longestText!!.length)) {
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

    private fun isAd(node: AccessibilityNodeInfo): Boolean {
        val allText = getAllChildText(node).lowercase()
        return allText.contains("ad ·") ||
                allText.contains("sponsored") ||
                allText.contains("विज्ञापन") ||
                allText.contains("promoted") ||
                allText.contains("install") && allText.contains("ad")
    }

    private fun isNavElement(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        return desc in NAV_DESCRIPTIONS
    }

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

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private suspend fun launchYouTube(): Boolean {
        var intent = service.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
        var pkg = YOUTUBE_PACKAGE

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
