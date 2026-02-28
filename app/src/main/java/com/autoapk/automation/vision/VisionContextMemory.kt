package com.autoapk.automation.vision

import android.util.Log

/**
 * Vision Context Memory — Scene History & Conversation Memory
 *
 * Maintains:
 *   - Rolling window of last 5 scene summaries (with perceptual hashes)
 *   - Conversation history for Gemini (last 3 exchange pairs = 6 messages)
 *   - Scene change detection via Hamming distance on perceptual hashes
 *   - Last description timestamp (for follow-up timeout — 30 seconds)
 *
 * Prevents redundant API calls when scene hasn't changed in auto-describe mode.
 */
class VisionContextMemory {

    companion object {
        private const val TAG = "Neo_VisionMem"
        private const val MAX_SCENE_HISTORY = 5
        private const val MAX_CONVERSATION_PAIRS = 3
        private const val FOLLOW_UP_TIMEOUT_MS = 30_000L
        private const val SCENE_CHANGE_THRESHOLD = 5 // Hamming distance
    }

    /**
     * A snapshot of what was described in a single scene.
     */
    data class SceneSummary(
        val timestamp: Long,
        val textSummary: String,
        val peopleDetected: List<String>,
        val keyObjects: List<String>,
        val perceptualHash: Long
    )

    /**
     * A single exchange in the Gemini conversation.
     */
    data class ConversationTurn(
        val role: String,  // "user" or "model"
        val text: String
    )

    // Scene history: most recent last
    private val sceneHistory = mutableListOf<SceneSummary>()

    // Conversation history: alternating user/model turns
    private val conversationHistory = mutableListOf<ConversationTurn>()

    // Timestamp of the last description spoken
    var lastDescriptionTimestamp: Long = 0L
        private set

    // ==================== SCENE HISTORY ====================

    /**
     * Check if the scene has changed compared to the last captured hash.
     * Unchanged = Hamming distance <= 5.
     */
    fun hasSceneChanged(newHash: Long): Boolean {
        val lastHash = sceneHistory.lastOrNull()?.perceptualHash ?: return true
        val distance = java.lang.Long.bitCount(lastHash xor newHash)
        val changed = distance > SCENE_CHANGE_THRESHOLD
        Log.d(TAG, "Scene change check: Hamming distance = $distance → ${if (changed) "CHANGED" else "SAME"}")
        return changed
    }

    /**
     * Add a new scene summary to the rolling window.
     */
    fun addSummary(summary: SceneSummary) {
        sceneHistory.add(summary)
        while (sceneHistory.size > MAX_SCENE_HISTORY) {
            sceneHistory.removeAt(0)
        }
        lastDescriptionTimestamp = summary.timestamp
        Log.d(TAG, "Scene added. History size: ${sceneHistory.size}")
    }

    /**
     * Get the most recent scene summary, or null if no scenes recorded.
     */
    fun getLastSummary(): SceneSummary? = sceneHistory.lastOrNull()

    /**
     * Build a context string from previous scenes for the prompt.
     * Includes the last 2 scene summaries for continuity.
     */
    fun getPreviousContext(): String {
        if (sceneHistory.isEmpty()) return ""

        val recent = sceneHistory.takeLast(2)
        val sb = StringBuilder()
        sb.appendLine("=== PREVIOUS SCENE CONTEXT ===")
        for ((i, scene) in recent.withIndex()) {
            val ageMs = System.currentTimeMillis() - scene.timestamp
            val ageSec = ageMs / 1000
            sb.appendLine("Scene ${i + 1} (${ageSec}s ago):")
            sb.appendLine("  Summary: ${scene.textSummary}")
            if (scene.peopleDetected.isNotEmpty()) {
                sb.appendLine("  People: ${scene.peopleDetected.joinToString(", ")}")
            }
            if (scene.keyObjects.isNotEmpty()) {
                sb.appendLine("  Objects: ${scene.keyObjects.joinToString(", ")}")
            }
        }
        sb.appendLine("Focus on what has CHANGED. Do NOT repeat unchanged details.")
        return sb.toString()
    }

    // ==================== CONVERSATION HISTORY ====================

    /**
     * Add a conversation turn (user or model).
     * Keeps only the last 3 exchange pairs (6 messages).
     */
    fun addConversationTurn(role: String, text: String) {
        conversationHistory.add(ConversationTurn(role, text))
        // Trim to max 6 messages (3 pairs)
        while (conversationHistory.size > MAX_CONVERSATION_PAIRS * 2) {
            conversationHistory.removeAt(0)
        }
    }

    /**
     * Get the conversation history for context.
     */
    fun getConversationHistory(): List<ConversationTurn> =
        conversationHistory.toList()

    /**
     * Check if a follow-up question is within the 30-second window.
     */
    fun isFollowUpValid(): Boolean {
        if (lastDescriptionTimestamp == 0L) return false
        val elapsed = System.currentTimeMillis() - lastDescriptionTimestamp
        val valid = elapsed <= FOLLOW_UP_TIMEOUT_MS
        Log.d(TAG, "Follow-up check: ${elapsed}ms elapsed → ${if (valid) "VALID" else "EXPIRED"}")
        return valid
    }

    /**
     * Check if any scene has been described in this session.
     */
    fun hasDescribedAnything(): Boolean = sceneHistory.isNotEmpty()

    /**
     * Clear all memory (e.g., when starting a fresh session).
     */
    fun clear() {
        sceneHistory.clear()
        conversationHistory.clear()
        lastDescriptionTimestamp = 0L
        Log.i(TAG, "Memory cleared")
    }
}
