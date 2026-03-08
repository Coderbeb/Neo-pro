package com.autoapk.automation.core

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Central state manager for the Neo 3-mode Wake/Sleep system.
 *
 * Modes:
 *   ACTIVE   — App just opened or user is actively giving commands. Full processing.
 *   SLEEPING — 60s inactivity or after in-call command. Only wake word "Neo" or hardware combo wakes it.
 *   IN_CALL  — During an ongoing call with in-call toggle ON. Sleeps instantly after each command.
 *
 * Wake word "Neo" is fuzzy-matched anywhere in the command (start, middle, end).
 * Language detection determines TTS response language (Hindi or English).
 */
class NeoStateManager {

    companion object {
        private const val TAG = "Neo_State"

        /** Time before auto-sleep when no command received */
        private const val SLEEP_TIMEOUT_MS = 60_000L

        /** Fuzzy distance threshold for wake word matching */
        private const val WAKE_WORD_MAX_DISTANCE = 2

        /** The wake word */
        private const val WAKE_WORD = "neo"

        /** Wake word variants that are exact matches (no fuzzy needed) */
        private val WAKE_WORD_EXACT = setOf(
            "neo", "nio", "neo", "neyo", "niyo", "neo.", "neo!", "neo,",
            "नियो", "नीओ", "निओ", "neo"
        )
    }

    // ==================== STATE ====================

    enum class NeoMode {
        ACTIVE,
        SLEEPING,
        IN_CALL
    }

    var currentMode: NeoMode = NeoMode.ACTIVE
        private set

    /** Whether the in-call voice mode toggle is ON */
    var inCallModeEnabled: Boolean = false

    /** Last detected language: "hi" for Hindi, "en" for English */
    var lastLanguage: String = "en"
        private set

    // ==================== LISTENERS ====================

    interface StateListener {
        fun onModeChanged(oldMode: NeoMode, newMode: NeoMode)
        fun onSleepAnnouncement(message: String)
        fun onWakeAnnouncement(message: String)
    }

    private var listener: StateListener? = null

    fun setListener(l: StateListener) {
        listener = l
    }

    // ==================== TIMER ====================

    private val handler = Handler(Looper.getMainLooper())
    private val sleepRunnable = Runnable {
        if (currentMode == NeoMode.ACTIVE) {
            Log.i(TAG, "60s inactivity → auto-sleep")
            sleep()
        }
    }

    private fun resetSleepTimer() {
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, SLEEP_TIMEOUT_MS)
    }

    private fun cancelSleepTimer() {
        handler.removeCallbacks(sleepRunnable)
    }

    // ==================== STATE TRANSITIONS ====================

    /**
     * Wake Neo from SLEEPING mode.
     * Called by hardware combo or wake word detection.
     */
    fun wake() {
        if (currentMode == NeoMode.SLEEPING) {
            val old = currentMode
            currentMode = NeoMode.ACTIVE
            Log.i(TAG, "WAKE: $old → ACTIVE")
            resetSleepTimer()

            val msg = if (lastLanguage == "hi") "Mai sun raha hu" else "Neo is listening"
            listener?.onWakeAnnouncement(msg)
            listener?.onModeChanged(old, currentMode)
        }
    }

    /**
     * Put Neo to sleep.
     * Called by 60s timeout, or instantly after in-call command.
     */
    fun sleep() {
        if (currentMode != NeoMode.SLEEPING) {
            val old = currentMode
            currentMode = NeoMode.SLEEPING
            cancelSleepTimer()
            Log.i(TAG, "SLEEP: $old → SLEEPING")

            // Don't announce sleep if coming from IN_CALL (already handled)
            if (old == NeoMode.ACTIVE) {
                val msg = if (lastLanguage == "hi") "Sone ja raha hu" else "Going to sleep"
                listener?.onSleepAnnouncement(msg)
            }
            listener?.onModeChanged(old, currentMode)
        }
    }

    /**
     * Enter in-call mode. Neo sleeps instantly after each command.
     * Called when user is on an active call and in-call toggle is ON.
     */
    fun enterInCallMode() {
        val old = currentMode
        currentMode = NeoMode.IN_CALL
        cancelSleepTimer()
        Log.i(TAG, "IN_CALL: $old → IN_CALL")
        listener?.onModeChanged(old, currentMode)
    }

    /**
     * Exit in-call mode. Returns to ACTIVE.
     * Called when call ends.
     */
    fun exitInCallMode() {
        if (currentMode == NeoMode.IN_CALL || currentMode == NeoMode.SLEEPING) {
            val old = currentMode
            currentMode = NeoMode.ACTIVE
            resetSleepTimer()
            Log.i(TAG, "CALL_END: $old → ACTIVE")

            val msg = if (lastLanguage == "hi") "Mai sun raha hu" else "Neo is listening"
            listener?.onWakeAnnouncement(msg)
            listener?.onModeChanged(old, currentMode)
        }
    }

    /**
     * Notify that a command was successfully processed.
     * Resets the 60s sleep timer (keeps Neo awake).
     * In IN_CALL mode, puts Neo to sleep instantly.
     */
    fun onCommandProcessed() {
        when (currentMode) {
            NeoMode.ACTIVE -> {
                resetSleepTimer()
                Log.d(TAG, "Command processed — timer reset")
            }
            NeoMode.IN_CALL -> {
                // In-call: sleep instantly after command execution
                Log.i(TAG, "In-call command done — sleeping instantly")
                currentMode = NeoMode.SLEEPING
                listener?.onModeChanged(NeoMode.IN_CALL, NeoMode.SLEEPING)
            }
            NeoMode.SLEEPING -> {
                // Shouldn't happen, but just in case
                Log.w(TAG, "Command processed while sleeping?")
            }
        }
    }

    /**
     * Called on app start / voice control start.
     * Sets Neo to ACTIVE mode with timer running.
     */
    fun activate() {
        val old = currentMode
        currentMode = NeoMode.ACTIVE
        resetSleepTimer()
        Log.i(TAG, "ACTIVATED: $old → ACTIVE")
        listener?.onModeChanged(old, currentMode)
    }

    /**
     * Called when voice control is completely stopped.
     */
    fun deactivate() {
        cancelSleepTimer()
        currentMode = NeoMode.SLEEPING
        Log.i(TAG, "DEACTIVATED → SLEEPING")
    }

    // ==================== WAKE WORD DETECTION ====================

    /**
     * Process a raw command through the wake word filter.
     *
     * - If ACTIVE: pass through (no wake word needed), detect language
     * - If SLEEPING: check for "Neo" anywhere in command (fuzzy matched)
     *   - If found: wake up, strip "Neo", return remaining command
     *   - If not found: return null (command ignored)
     * - If IN_CALL + SLEEPING: same as SLEEPING
     *
     * @return The command to process (wake word stripped), or null if sleeping and no wake word
     */
    fun processWakeFilter(rawCommand: String): String? {
        val command = rawCommand.trim()
        if (command.isBlank()) return null

        // Detect language from input
        detectLanguage(command)

        when (currentMode) {
            NeoMode.ACTIVE -> {
                // Already awake — process everything, reset timer
                resetSleepTimer()
                return command
            }

            NeoMode.SLEEPING -> {
                // Check for wake word
                val stripped = extractAndStripWakeWord(command)
                if (stripped != null) {
                    // Wake word found!
                    wake()
                    // If just "Neo" with no command, return empty to indicate wake-only
                    return if (stripped.isBlank()) "" else stripped
                }
                // No wake word — silently ignore
                Log.d(TAG, "Sleeping — ignored: '${command.take(30)}'")
                return null
            }

            NeoMode.IN_CALL -> {
                // In-call mode: commands only come through hardware wake
                // So if we get here, user already pressed hardware combo
                return command
            }
        }
    }

    /**
     * Check if the command contains the wake word "Neo" (fuzzy matched).
     * If found, strip it and return the remaining command.
     * If not found, return null.
     */
    private fun extractAndStripWakeWord(command: String): String? {
        val words = command.lowercase().split(Regex("\\s+"))

        for (i in words.indices) {
            val word = words[i].replace(Regex("[^a-zA-Z\\u0900-\\u097F]"), "") // strip punctuation
            if (word.isBlank()) continue

            if (isWakeWord(word)) {
                // Found wake word — strip it and return rest
                val remaining = words.toMutableList()
                remaining.removeAt(i)
                val result = remaining.joinToString(" ").trim()
                Log.i(TAG, "Wake word found at position $i: '$word' → command: '$result'")
                return result
            }
        }

        return null
    }

    /**
     * Check if a word matches "Neo" using exact match or fuzzy matching.
     */
    private fun isWakeWord(word: String): Boolean {
        // Exact match (includes Hindi variants)
        if (word in WAKE_WORD_EXACT) return true

        // Fuzzy match using Levenshtein distance
        val distance = levenshteinDistance(word, WAKE_WORD)
        if (distance <= WAKE_WORD_MAX_DISTANCE && word.length >= 2) {
            Log.d(TAG, "Fuzzy wake match: '$word' (distance=$distance)")
            return true
        }

        // Phonetic similarity check
        if (word.length in 2..5 && getPhoneticCode(word) == getPhoneticCode(WAKE_WORD)) {
            Log.d(TAG, "Phonetic wake match: '$word'")
            return true
        }

        return false
    }

    // ==================== LANGUAGE DETECTION ====================

    /**
     * Detect language from input text.
     * If >30% Devanagari characters → Hindi, else English.
     */
    private fun detectLanguage(text: String) {
        val devanagariCount = text.count { it in '\u0900'..'\u097F' }
        val totalAlpha = text.count { it.isLetter() }.coerceAtLeast(1)
        val ratio = devanagariCount.toFloat() / totalAlpha

        lastLanguage = if (ratio > 0.3f) "hi" else "en"
    }

    // ==================== UTILITY FUNCTIONS ====================

    /**
     * Levenshtein edit distance between two strings.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }

    /**
     * Simple phonetic code (Soundex-like) for fuzzy matching.
     */
    private fun getPhoneticCode(word: String): String {
        if (word.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(word[0])

        val map = mapOf(
            'b' to '1', 'f' to '1', 'p' to '1', 'v' to '1',
            'c' to '2', 'g' to '2', 'j' to '2', 'k' to '2', 'q' to '2', 's' to '2', 'x' to '2', 'z' to '2',
            'd' to '3', 't' to '3',
            'l' to '4',
            'm' to '5', 'n' to '5',
            'r' to '6'
        )

        var lastCode = map[word[0].lowercaseChar()] ?: '0'
        for (i in 1 until word.length) {
            val code = map[word[i].lowercaseChar()] ?: '0'
            if (code != '0' && code != lastCode) {
                sb.append(code)
                if (sb.length >= 4) break
            }
            lastCode = code
        }

        while (sb.length < 4) sb.append('0')
        return sb.toString()
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        cancelSleepTimer()
        handler.removeCallbacksAndMessages(null)
    }
}
