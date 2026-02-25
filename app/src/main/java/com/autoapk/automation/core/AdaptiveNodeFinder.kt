package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedList
import java.util.Queue

/**
 * Adaptive Node Finder — 7-Strategy UI Element Finder
 *
 * Finds and returns accessibility nodes on screen using multiple strategies:
 *   1. Exact text match (score 0.95)
 *   2. Exact contentDescription match (score 0.97)
 *   3. Contains match (score 0.78-0.80)
 *   4. Resource ID match (score 0.90)
 *   5. Synonym match including Hindi (score 0.75)
 *   6. Fuzzy Levenshtein match (score up to 0.85)
 *   7. Clickable parent climbing (if best match isn't clickable)
 *
 * Penalties: invisible elements (score * 0.1)
 * Bonuses: clickable nodes when wantClickable=true (score * 1.05)
 */
class AdaptiveNodeFinder(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "Neo_NodeFinder"
    }

    /**
     * Synonym dictionary — maps English targets to alternate labels
     * that might appear in different app versions or Hindi UIs.
     */
    private val synonyms = mapOf(
        "search" to listOf("find", "खोजें", "look up", "explore"),
        "send" to listOf("भेजें", "submit", "post"),
        "back" to listOf("वापस", "पीछे", "return", "navigate up"),
        "home" to listOf("होम", "main", "feed"),
        "play" to listOf("चलाएं", "resume", "watch"),
        "pause" to listOf("रुकें", "stop", "hold"),
        "next" to listOf("अगला", "skip", "forward"),
        "like" to listOf("पसंद", "heart", "love"),
        "reels" to listOf("रील्स", "shorts", "videos"),
        "profile" to listOf("प्रोफ़ाइल", "account", "me"),
        "share" to listOf("शेयर", "forward"),
        "comment" to listOf("टिप्पणी", "reply"),
        "follow" to listOf("फॉलो", "subscribe"),
        "message" to listOf("संदेश", "chat", "type a message"),
        "call" to listOf("कॉल", "voice call", "audio call", "phone"),
        "video call" to listOf("वीडियो कॉल", "facetime")
    )

    /**
     * Find the best matching node on screen for the given target text.
     *
     * @param target The text to search for (e.g., "Search", "Send", "Reels")
     * @param wantClickable If true, prefer clickable nodes and walk up to clickable parent
     * @return The best matching AccessibilityNodeInfo, or null if no match above threshold (0.5)
     */
    fun find(target: String, wantClickable: Boolean = true): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: run {
            Log.w(TAG, "find('$target'): No root window")
            return null
        }
        val all = flatten(root)
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Float>>()

        for (node in all) {
            val score = scoreNode(node, target, wantClickable)
            if (score > 0.5f) candidates.add(node to score)
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "find('$target'): No matches found (threshold 0.5)")
            return null
        }

        candidates.sortByDescending { it.second }
        val best = candidates[0]
        Log.i(TAG, "find('$target'): Best match = '${getNodeLabel(best.first)}' (score=${String.format("%.2f", best.second)}, clickable=${best.first.isClickable})")

        // If the best node itself isn't clickable and we want clickable, find clickable parent
        return if (wantClickable && !best.first.isClickable) {
            val clickableParent = findClickableParent(best.first)
            if (clickableParent != null) {
                Log.i(TAG, "  → Using clickable parent instead")
                clickableParent
            } else {
                best.first
            }
        } else {
            best.first
        }
    }

    /**
     * Find ALL matching nodes above threshold, not just the best one.
     * Useful for listing videos, contacts, etc.
     */
    fun findAll(target: String, maxResults: Int = 10): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val all = flatten(root)
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Float>>()

        for (node in all) {
            val score = scoreNode(node, target, false)
            if (score > 0.5f) candidates.add(node to score)
        }

        candidates.sortByDescending { it.second }
        return candidates.take(maxResults).map { it.first }
    }

    /**
     * Score a node against the target using all 6 strategies.
     */
    private fun scoreNode(node: AccessibilityNodeInfo, target: String, wantClickable: Boolean): Float {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val resId = node.viewIdResourceName?.toString()?.lowercase() ?: ""
        val targetLow = target.lowercase()
        var score = 0f

        // Strategy 1: Exact text match
        if (text.equals(target, true)) score = maxOf(score, 0.95f)

        // Strategy 2: Exact contentDescription match
        if (desc.equals(target, true)) score = maxOf(score, 0.97f)

        // Strategy 3: Contains match
        if (desc.contains(target, true)) score = maxOf(score, 0.80f)
        if (text.contains(target, true)) score = maxOf(score, 0.78f)

        // Strategy 4: Resource ID match (e.g., "com.whatsapp:id/send")
        if (resId.contains(targetLow)) score = maxOf(score, 0.90f)

        // Strategy 5: Synonym match (Hindi/English)
        val variants = synonyms[targetLow] ?: emptyList()
        for (v in variants) {
            if (desc.contains(v, true) || text.contains(v, true))
                score = maxOf(score, 0.75f)
        }

        // Strategy 6: Fuzzy match (Levenshtein distance)
        if (targetLow.length >= 3) {
            val fuzzyText = fuzzyScore(targetLow, text.lowercase())
            val fuzzyDesc = fuzzyScore(targetLow, desc.lowercase())
            score = maxOf(score, maxOf(fuzzyText, fuzzyDesc) * 0.85f)
        }

        // Penalty: invisible nodes (zero-size bounds)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) score *= 0.1f

        // Penalty: nodes not visible to user
        if (!node.isVisibleToUser) score *= 0.1f

        // Bonus: clickable when we want clickable
        if (wantClickable && node.isClickable) score *= 1.05f

        return score.coerceAtMost(1.0f)
    }

    /**
     * Compute fuzzy similarity score between two strings.
     * Returns 0.0 (completely different) to 1.0 (identical).
     */
    private fun fuzzyScore(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = maxOf(a.length, b.length)
        val dist = levenshtein(a, b)
        return (1f - dist.toFloat() / maxLen).coerceAtLeast(0f)
    }

    /**
     * Levenshtein distance computation for fuzzy matching.
     */
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            )
        }
        return dp[a.length][b.length]
    }

    /**
     * Walk up the accessibility tree to find a clickable parent.
     * Walks up to 5 levels to avoid going too far.
     */
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

    /**
     * Extract a human-readable label from a node for logging.
     */
    private fun getNodeLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.take(30) ?: ""
        val desc = node.contentDescription?.toString()?.take(30) ?: ""
        return when {
            text.isNotBlank() && desc.isNotBlank() -> "$text ($desc)"
            text.isNotBlank() -> text
            desc.isNotBlank() -> desc
            else -> "[no label]"
        }
    }

    /**
     * Flatten the accessibility tree into a list using BFS traversal.
     * This avoids stack overflow on deeply nested UI trees.
     */
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
