package com.autoapk.automation.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Silent auto-learning system for command recognition.
 *
 * When a command is successfully matched and executed, the raw input text
 * is mapped to the intent name and saved to SharedPreferences.
 *
 * IMPORTANT: Parameterized commands (CALL_CONTACT, OPEN_APP, etc.) are
 * NOT memorized because they depend on the extracted parameter and the
 * parameter resolution can change (e.g., new contacts added).
 *
 * Stores up to MAX_ENTRIES learned mappings (LRU-style eviction).
 */
class CommandMemory(context: Context) {

    companion object {
        private const val TAG = "Neo_Memory"
        private const val PREFS_NAME = "autoapk_command_memory"
        private const val MAX_ENTRIES = 500
        private const val KEY_ENTRIES = "learned_commands"

        /**
         * Intents that should NEVER be memorized because they depend on
         * runtime parameter resolution (contacts, app names, etc.).
         * Memorizing these causes stale/wrong mappings.
         */
        private val DO_NOT_MEMORIZE = setOf(
            SmartCommandMatcher.CommandIntent.CALL_CONTACT,
            SmartCommandMatcher.CommandIntent.OPEN_APP,
            SmartCommandMatcher.CommandIntent.SEND_SMS,
            SmartCommandMatcher.CommandIntent.SEND_WHATSAPP,
            SmartCommandMatcher.CommandIntent.SEND_CHAT_MSG,
            SmartCommandMatcher.CommandIntent.ENTER_CHAT,
            SmartCommandMatcher.CommandIntent.TYPE_TEXT,
            SmartCommandMatcher.CommandIntent.CLICK_TARGET,
            SmartCommandMatcher.CommandIntent.SELECT_ITEM,
            SmartCommandMatcher.CommandIntent.SEARCH_YOUTUBE,
            SmartCommandMatcher.CommandIntent.SEARCH_GOOGLE,
            SmartCommandMatcher.CommandIntent.PLAY_SONG,
            SmartCommandMatcher.CommandIntent.SET_ALARM,
            SmartCommandMatcher.CommandIntent.SET_TIMER
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache for fast lookup:  normalized_input → intent_name
    private val memoryCache = LinkedHashMap<String, String>(64, 0.75f, true)

    init {
        loadFromDisk()
    }

    // ==================== PUBLIC API ====================

    /**
     * Look up a previously-learned command mapping.
     * Returns the CommandIntent if found, null otherwise.
     *
     * Skips recall for parameterized intents to prevent stale mappings.
     */
    fun recall(rawInput: String): SmartCommandMatcher.CommandIntent? {
        val key = normalize(rawInput)
        val intentName = memoryCache[key] ?: return null

        return try {
            val intent = SmartCommandMatcher.CommandIntent.valueOf(intentName)
            // Don't recall parameterized intents — they need fresh matching each time
            if (intent in DO_NOT_MEMORIZE) {
                Log.d(TAG, "Skipping memory recall for parameterized intent: $intent")
                null
            } else {
                intent
            }
        } catch (e: IllegalArgumentException) {
            // Stale/invalid entry — remove it
            memoryCache.remove(key)
            null
        }
    }

    /**
     * Learn a new command mapping (called after successful execution).
     * Silently saves rawInput → intent for future instant recall.
     *
     * Will NOT memorize parameterized commands.
     */
    fun remember(rawInput: String, intent: SmartCommandMatcher.CommandIntent) {
        // Don't memorize parameterized commands — they cause stale wrong mappings
        if (intent in DO_NOT_MEMORIZE) {
            Log.d(TAG, "Skipping memorization of parameterized intent: $intent for '$rawInput'")
            return
        }

        val key = normalize(rawInput)

        // Don't store very short inputs (likely noise)
        if (key.length < 3) return

        memoryCache[key] = intent.name

        // Evict oldest entries if over limit
        while (memoryCache.size > MAX_ENTRIES) {
            val oldest = memoryCache.keys.iterator().next()
            memoryCache.remove(oldest)
        }

        // Persist to disk (async)
        saveToDisk()
        Log.d(TAG, "Learned: '$key' → ${intent.name}")
    }

    /**
     * Forget a specific command mapping (called when a command fails).
     */
    fun forget(rawInput: String) {
        val key = normalize(rawInput)
        if (memoryCache.remove(key) != null) {
            saveToDisk()
            Log.d(TAG, "Forgot: '$key'")
        }
    }

    /**
     * Get the number of learned commands.
     */
    fun getLearnedCount(): Int = memoryCache.size

    // ==================== INTERNAL ====================

    private fun normalize(input: String): String {
        return input.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private fun loadFromDisk() {
        val data = prefs.getString(KEY_ENTRIES, null) ?: return
        try {
            // Format: "key1=INTENT1;key2=INTENT2;..."
            val entries = data.split(";").filter { it.contains("=") }
            for (entry in entries) {
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) {
                    memoryCache[parts[0]] = parts[1]
                }
            }
            Log.i(TAG, "Loaded ${memoryCache.size} learned commands from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading command memory: ${e.message}")
        }
    }

    private fun saveToDisk() {
        try {
            val data = memoryCache.entries.joinToString(";") { "${it.key}=${it.value}" }
            prefs.edit().putString(KEY_ENTRIES, data).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving command memory: ${e.message}")
        }
    }
}
