package com.autoapk.automation.core

/**
 * Shared string-matching utilities used by SmartCommandMatcher, NeoStateManager, and ContactRegistry.
 *
 * Consolidates fuzzy matching intelligence into one place:
 *   - Levenshtein edit distance (standard DP)
 *   - Soundex-like phonetic encoding for Hindi/English
 *   - Composite fuzzy matcher with opposite-word protection
 */
object StringMatchUtils {

    /**
     * Levenshtein edit distance between two strings.
     * Returns the minimum number of single-character edits (insert, delete, substitute)
     * needed to transform [s1] into [s2].
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[len1][len2]
    }

    /**
     * Simple phonetic encoding (Soundex-like) for fuzzy matching.
     *
     * Keeps the first letter, then encodes consonants into digit groups.
     * Skips vowels and consecutive identical codes. Pads/truncates to 4 characters.
     *
     * Consonant groups:
     *   1 = B F P V
     *   2 = C G J K Q S X Z
     *   3 = D T
     *   4 = L
     *   5 = M N
     *   6 = R
     */
    fun getPhoneticCode(word: String): String {
        if (word.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append(word[0].lowercaseChar())

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
            // Skip vowels (code '0') and consecutive repeated codes
            if (code != '0' && code != lastCode) {
                sb.append(code)
                if (sb.length >= 4) break  // Early exit once we have 4 chars
            }
            lastCode = code
        }

        // Pad to exactly 4 characters
        while (sb.length < 4) sb.append('0')
        return sb.toString()
    }

    /**
     * Composite fuzzy matcher using edit distance + phonetic similarity.
     *
     * Intelligence:
     *   - Rejects very short words (< 3 chars) to avoid false positives
     *   - Protects opposite pairs (lock/unlock, on/off, enable/disable, start/stop)
     *   - Uses length-adaptive edit distance thresholds
     *   - Falls back to phonetic matching for longer words (4+ chars)
     *
     * @param word1 First word
     * @param word2 Second word
     * @return true if the words are considered a fuzzy match
     */
    fun isFuzzyMatch(word1: String, word2: String): Boolean {
        // Skip very short words to avoid false positives
        if (word1.length < 3 || word2.length < 3) return false

        // CRITICAL: Don't match opposites — these are semantic inversions
        val opposites = setOf(
            setOf("lock", "unlock"),
            setOf("locked", "unlocked"),
            setOf("locking", "unlocking"),
            setOf("on", "off"),
            setOf("enable", "disable"),
            setOf("start", "stop"),
            setOf("mute", "unmute"),
            setOf("do", "undo")
        )
        for (pair in opposites) {
            if ((word1 in pair && word2 in pair) && word1 != word2) {
                return false
            }
        }

        // Edit distance with length-adaptive threshold
        val distance = levenshteinDistance(word1, word2)
        val maxLen = maxOf(word1.length, word2.length)

        val threshold = when {
            maxLen <= 4 -> 1
            maxLen <= 6 -> 2
            else -> 3
        }

        if (distance <= threshold) return true

        // Phonetic similarity fallback (for words 4+ characters)
        if (word1.length >= 4 && word2.length >= 4) {
            val phonetic1 = getPhoneticCode(word1)
            val phonetic2 = getPhoneticCode(word2)
            if (phonetic1 == phonetic2) return true
        }

        return false
    }
}
