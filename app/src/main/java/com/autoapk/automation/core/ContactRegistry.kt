package com.autoapk.automation.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONObject

/**
 * Smart Contact Registry — auto-discovers all contacts, caches them,
 * and uses SCORED fuzzy/phonetic matching so voice-recognized names
 * correctly match the RIGHT contact.
 *
 * Key improvement: Instead of a flat map where first-name keys overwrite
 * each other, this uses scored matching across ALL contacts to find the
 * best match, with disambiguation when multiple contacts score similarly.
 */
class ContactRegistry(private val context: Context) {

    companion object {
        private const val TAG = "Neo_Contacts"
        private const val PREFS_NAME = "contact_registry"
        private const val KEY_CONTACTS = "contacts_map"
        private const val KEY_LAST_SCAN = "last_scan_time"
        private const val SCAN_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        /** Minimum match score to accept (0-100). Below this → "contact not found" */
        private const val MIN_MATCH_SCORE = 60  // Increased from 40 to require better matches
        /** If top two matches score within this range, trigger disambiguation */
        private const val AMBIGUITY_THRESHOLD = 15
    }

    // display name → phone number (the canonical source of truth)
    private val displayNames = mutableMapOf<String, String>()

    init {
        loadFromCache()
    }

    /**
     * Scan all phone contacts and build the lookup map.
     */
    fun scanContacts() {
        displayNames.clear()

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx) ?: continue
                    val cleanNumber = number.replace(Regex("[^0-9+]"), "")

                    if (cleanNumber.isNotEmpty()) {
                        displayNames[name] = cleanNumber
                    }
                }
            }

            Log.i(TAG, "Scanned ${displayNames.size} contacts")
            saveToCache()

        } catch (e: SecurityException) {
            Log.e(TAG, "Contact permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning contacts: ${e.message}")
        }
    }

    /**
     * Find the best matching contact for a voice-recognized query.
     * Uses SCORED multi-level matching across ALL contacts.
     * Returns null only if no contact scores above MIN_MATCH_SCORE.
     */
    fun findContact(query: String): ContactMatch? {
        if (displayNames.isEmpty()) return null

        val scored = scoreAllContacts(query)
        if (scored.isEmpty()) return null

        val best = scored[0]
        if (best.score < MIN_MATCH_SCORE) {
            Log.w(TAG, "Best match '${best.name}' scored ${best.score} (below threshold $MIN_MATCH_SCORE) for query '$query'")
            return null
        }

        // Check for ambiguity — if second-best is close, DON'T auto-pick
        if (scored.size > 1) {
            val second = scored[1]
            if (second.score >= MIN_MATCH_SCORE && (best.score - second.score) < AMBIGUITY_THRESHOLD) {
                // Too close to call — let disambiguation handle it
                Log.i(TAG, "Ambiguous: '${best.name}'(${best.score}) vs '${second.name}'(${second.score}) — deferring to disambiguation")
                return null
            }
        }

        Log.i(TAG, "Best match: '${best.name}' (score=${best.score}, type=${best.matchType}) for query '$query'")
        return best
    }

    /**
     * Score ALL contacts against the query and return sorted by score descending.
     */
    private fun scoreAllContacts(query: String): List<ContactMatch> {
        val normalized = query.lowercase().trim()
        val queryWords = normalized.split(Regex("\\s+"))
        val queryPhonetic = toPhonetic(normalized)
        val queryWordPhonetics = queryWords.map { toPhonetic(it) }
        val results = mutableListOf<ContactMatch>()
        val seenNumbers = mutableSetOf<String>()

        for ((displayName, number) in displayNames) {
            if (seenNumbers.contains(number)) continue

            val contactLower = displayName.lowercase().trim()
            val contactWords = contactLower.split(Regex("\\s+"))
            val contactPhonetic = toPhonetic(contactLower)
            val contactWordPhonetics = contactWords.map { toPhonetic(it) }

            var score = 0
            var matchType = "none"

            // === LEVEL 1: Exact full-name match (100 points) ===
            if (contactLower == normalized) {
                score = 100
                matchType = "exact"
            }

            // === LEVEL 2: Full phonetic match (90 points) ===
            if (score < 90 && contactPhonetic == queryPhonetic) {
                score = 90
                matchType = "phonetic-exact"
            }

            // === LEVEL 3: Full name contains query or vice versa (80 points) ===
            if (score < 80) {
                if (contactLower.contains(normalized) && normalized.length > 2) {
                    // Query is a substring of contact (e.g. "sujeet" in "sujeet yogada")
                    val ratio = normalized.length.toFloat() / contactLower.length.toFloat()
                    score = (60 + ratio * 20).toInt()
                    matchType = "contains"
                } else if (normalized.contains(contactLower) && contactLower.length > 2) {
                    val ratio = contactLower.length.toFloat() / normalized.length.toFloat()
                    score = (55 + ratio * 20).toInt()
                    matchType = "reverse-contains"
                }
            }

            // === LEVEL 4: Word-level matching (up to 85 points) ===
            if (score < 85) {
                var wordMatchCount = 0
                var totalWordScore = 0f
                for (i in queryWords.indices) {
                    val qw = queryWords[i]
                    val qwp = queryWordPhonetics[i]
                    var bestWordScore = 0f

                    for (j in contactWords.indices) {
                        val cw = contactWords[j]
                        val cwp = contactWordPhonetics[j]

                        when {
                            // Exact word match
                            cw == qw -> {
                                bestWordScore = maxOf(bestWordScore, 1.0f)
                            }
                            // Word starts with query word or vice versa
                            cw.startsWith(qw) || qw.startsWith(cw) -> {
                                val shorter = minOf(cw.length, qw.length)
                                val longer = maxOf(cw.length, qw.length)
                                bestWordScore = maxOf(bestWordScore, shorter.toFloat() / longer.toFloat() * 0.9f)
                            }
                            // Phonetic word match
                            cwp == qwp -> {
                                bestWordScore = maxOf(bestWordScore, 0.85f)
                            }
                            // Phonetic prefix match
                            cwp.startsWith(qwp) || qwp.startsWith(cwp) -> {
                                bestWordScore = maxOf(bestWordScore, 0.7f)
                            }
                            // Fuzzy phonetic match (Levenshtein)
                            else -> {
                                val dist = levenshtein(qwp, cwp)
                                val maxLen = maxOf(qwp.length, cwp.length)
                                if (maxLen > 0) {
                                    val similarity = 1.0f - dist.toFloat() / maxLen.toFloat()
                                    if (similarity >= 0.6f) {
                                        bestWordScore = maxOf(bestWordScore, similarity * 0.65f)
                                    }
                                }
                            }
                        }
                    }

                    if (bestWordScore > 0.3f) {
                        wordMatchCount++
                        totalWordScore += bestWordScore
                    }
                }

                if (wordMatchCount > 0) {
                    val wordMatchRatio = wordMatchCount.toFloat() / queryWords.size.toFloat()
                    val avgScore = totalWordScore / queryWords.size.toFloat()
                    val combinedScore = (wordMatchRatio * 0.5f + avgScore * 0.5f) * 85f
                    if (combinedScore > score) {
                        score = combinedScore.toInt()
                        matchType = "word-match($wordMatchCount/${queryWords.size})"
                    }
                }
            }

            // === LEVEL 5: Fuzzy whole-string Levenshtein (up to 60 points) ===
            if (score < 60) {
                val dist = levenshtein(queryPhonetic, contactPhonetic)
                val maxLen = maxOf(queryPhonetic.length, contactPhonetic.length)
                if (maxLen > 0) {
                    val similarity = 1.0f - dist.toFloat() / maxLen.toFloat()
                    if (similarity >= 0.6f) {
                        val fuzzyScore = (similarity * 60f).toInt()
                        if (fuzzyScore > score) {
                            score = fuzzyScore
                            matchType = "fuzzy(dist=$dist)"
                        }
                    }
                }
            }

            if (score >= MIN_MATCH_SCORE) {
                results.add(ContactMatch(displayName, number, matchType, score))
                seenNumbers.add(number)
            }
        }

        // Sort by score descending
        results.sortByDescending { it.score }

        // Log top matches for debugging
        if (results.isNotEmpty()) {
            Log.i(TAG, "Top matches for '$query':")
            results.take(5).forEach {
                Log.i(TAG, "  ${it.score}: '${it.name}' (${it.matchType})")
            }
        }

        return results
    }

    /**
     * Find ALL matching contacts for a query (for disambiguation).
     * Returns contacts that score above the minimum threshold.
     */
    fun findAllMatches(query: String): List<ContactMatch> {
        if (displayNames.isEmpty()) return emptyList()

        val scored = scoreAllContacts(query)
        if (scored.isEmpty()) return emptyList()

        // Only return contacts that scored decently
        val topScore = scored[0].score
        val viable = scored.filter { it.score >= MIN_MATCH_SCORE && it.score >= topScore - 25 }

        Log.i(TAG, "findAllMatches('$query') → ${viable.size} viable results (of ${scored.size} scored)")
        return viable
    }

    /**
     * Call a contact by voice-recognized name.
     */
    fun callContact(query: String): Boolean {
        val match = findContact(query)
        if (match != null) {
            Log.i(TAG, "Calling: '${match.name}' (${match.number}) via ${match.matchType}, score=${match.score}")
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${match.number}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
                return true
            } catch (e: SecurityException) {
                Log.e(TAG, "Call permission denied")
                return false
            }
        }
        Log.w(TAG, "No contact match found for: $query")
        return false
    }

    fun getContactCount(): Int = displayNames.size

    fun needsScan(): Boolean {
        if (displayNames.isEmpty()) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastScan = prefs.getLong(KEY_LAST_SCAN, 0)
        return System.currentTimeMillis() - lastScan > SCAN_INTERVAL_MS
    }

    // ==================== PHONETIC ENGINE ====================

    /**
     * Convert a string to its phonetic representation.
     * Strips vowels (except leading), normalizes similar-sounding consonants,
     * and handles common Hindi/English phonetic equivalents.
     */
    private fun toPhonetic(input: String): String {
        var s = input.lowercase().trim()

        // Common Hindi suffix/title normalizations
        s = s.replace("bhaiya", "bhaia")
            .replace("bhaiyya", "bhaia")
            .replace("bhaya", "bhaia")
            .replace("bhai", "bhai")
            .replace("didi", "didi")
            .replace("jee", "ji")
            .replace("aunty", "anti")
            .replace("auntie", "anti")
            .replace("sir", "sr")
            .replace("madam", "mdm")
            .replace("ma'am", "mdm")
            .replace("maam", "mdm")

        // Phonetic consonant normalization
        s = s.replace("ph", "f")
            .replace("gh", "g")
            .replace("kh", "k")
            .replace("th", "t")
            .replace("dh", "d")
            .replace("sh", "s")
            .replace("ch", "c")
            .replace("ck", "k")
            .replace("qu", "kw")
            .replace("x", "ks")
            .replace("z", "s")
            .replace("v", "w")
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("aa", "a")

        // Remove double letters
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i == 0 || s[i] != s[i - 1]) {
                sb.append(s[i])
            }
        }
        s = sb.toString()

        // Remove interior vowels (keep first char and consonants)
        val result = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            if (i == 0 || c !in "aeiou ") {
                result.append(c)
            }
        }

        return result.toString()
    }

    /**
     * Levenshtein edit distance between two strings.
     */
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    // ==================== HELPERS ====================

    private fun saveToCache() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONObject()
        for ((name, number) in displayNames) {
            json.put(name, number)
        }
        prefs.edit()
            .putString(KEY_CONTACTS, json.toString())
            .putLong(KEY_LAST_SCAN, System.currentTimeMillis())
            .apply()
    }

    private fun loadFromCache() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_CONTACTS, null) ?: return
        try {
            val json = JSONObject(cached)
            for (name in json.keys()) {
                val number = json.getString(name)
                displayNames[name] = number
            }
            Log.d(TAG, "Loaded ${displayNames.size} contacts from cache")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load contact cache: ${e.message}")
        }
    }

    data class ContactMatch(
        val name: String,
        val number: String,
        val matchType: String,
        val score: Int = 0
    )
}
