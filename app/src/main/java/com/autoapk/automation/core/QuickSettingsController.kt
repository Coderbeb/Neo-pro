package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * QuickSettingsController — High-accuracy Quick Settings toggle via accessibility.
 *
 * ACCURACY STRATEGY (targets 90-95%):
 *   1. POSITION FILTER:  Only scan nodes in y=150..800 (tile grid area)
 *   2. SIZE FILTER:       Only consider elements 60-350px (tile-sized)
 *   3. CLICKABLE FILTER:  Only match clickable nodes or nodes with clickable parent ≤3 levels
 *   4. EXCLUSION FILTER:  Blacklist "Open * settings", "Expand", time/date, brightness, etc.
 *   5. NAME EXTRACTION:   Parse content-desc to extract just the tile name (before comma)
 *   6. SMART SCORING:     Exact/contains/word/fuzzy matching against extracted name only
 *
 * LATENCY STRATEGY:
 *   - 600ms wait for QS to open (minimal safe delay)
 *   - Direct node click (no coordinate fallback unless needed)
 *   - Close QS immediately after click
 */
class QuickSettingsController(
    private val service: AutomationAccessibilityService
) {
    companion object {
        private const val TAG = "Neo_QS"

        // === POSITION FILTER BOUNDS ===
        // QS tile grid is roughly in this Y range (based on 2400px height screen)
        private const val TILE_Y_MIN = 150
        private const val TILE_Y_MAX = 850
        // Minimum tile dimension (too small = icon/status, not a tile)
        private const val TILE_MIN_SIZE = 55
        // Maximum tile dimension (too large = container, not a tile)
        private const val TILE_MAX_SIZE = 500

        // === EXCLUSION PATTERNS ===
        // Content-desc patterns that are definitely NOT toggle tiles
        private val EXCLUSION_PATTERNS = listOf(
            "open ", "expand", "collapse", "display brightness", "brightness",
            "modify the settings", "settings order",
            "status bar", "navigation bar",
            "silent notification", "transferring", "usb debugging",
            "minutes ago", "hours ago", "just now"
        )

        // Classes that are definitely not QS tiles
        private val EXCLUDED_CLASSES = setOf(
            "android.widget.SeekBar",      // Brightness slider
            "android.widget.ImageView",     // Status icons
            "android.widget.TextView"       // Pure text labels
        )

        // === SYNONYM MAP (10+ ways per tile) ===
        private val SYNONYMS: Map<String, List<String>> = mapOf(
            // WiFi
            "wifi" to listOf("wi-fi", "wifi", "wlan", "wireless"),
            "wi-fi" to listOf("wi-fi", "wifi", "wlan"),
            "internet" to listOf("wi-fi", "wifi", "mobile data"),
            "net" to listOf("wi-fi", "wifi", "mobile data"),
            "wlan" to listOf("wi-fi", "wlan", "wifi"),
            "wireless" to listOf("wi-fi", "wifi"),
            "वाईफाई" to listOf("wi-fi", "wifi"),

            // Bluetooth
            "bluetooth" to listOf("bluetooth"),
            "bt" to listOf("bluetooth"),
            "ब्लूटूथ" to listOf("bluetooth"),
            "blutooth" to listOf("bluetooth"),

            // Mobile Data
            "data" to listOf("mobile data", "data"),
            "mobile data" to listOf("mobile data"),
            "cellular" to listOf("mobile data"),
            "4g" to listOf("mobile data"),
            "5g" to listOf("mobile data"),
            "डाटा" to listOf("mobile data"),

            // Flashlight / Torch
            "flashlight" to listOf("flashlight", "torch", "flash"),
            "torch" to listOf("torch", "flashlight"),
            "flash" to listOf("flashlight", "torch"),
            "टॉर्च" to listOf("torch", "flashlight"),
            "रोशनी" to listOf("torch", "flashlight"),

            // Silent Mode
            "silent" to listOf("silent mode", "silent"),
            "silent mode" to listOf("silent mode"),
            "साइलेंट" to listOf("silent mode"),

            // Location / GPS
            "location" to listOf("location", "gps", "location reporting"),
            "gps" to listOf("location", "gps"),
            "लोकेशन" to listOf("location"),

            // Airplane / Flight Mode
            "airplane" to listOf("aeroplane mode", "airplane mode", "flight mode", "aeroplane"),
            "aeroplane" to listOf("aeroplane mode", "airplane mode", "aeroplane"),
            "airplane mode" to listOf("aeroplane mode", "airplane mode"),
            "flight mode" to listOf("aeroplane mode", "flight mode"),
            "flight" to listOf("aeroplane mode", "flight mode"),
            "हवाई" to listOf("aeroplane mode"),
            "फ्लाइट" to listOf("aeroplane mode"),

            // Auto-Rotate
            "auto rotate" to listOf("auto-rotate", "auto rotate"),
            "rotate" to listOf("auto-rotate", "auto rotate"),
            "rotation" to listOf("auto-rotate", "auto rotate"),
            "रोटेशन" to listOf("auto-rotate"),

            // Device Controls
            "device controls" to listOf("device controls"),
            "smart home" to listOf("device controls"),

            // Wallet
            "wallet" to listOf("wallet"),
            "gpay" to listOf("wallet"),

            // Video Enhancement
            "video enhancement" to listOf("video enhancement", "video enhance"),

            // Quick Share
            "quick share" to listOf("quick share", "nearby share"),
            "nearby share" to listOf("quick share", "nearby share"),

            // Vibration
            "vibration" to listOf("vibration", "vibrate"),
            "vibrate" to listOf("vibration", "vibrate"),

            // Eye Comfort
            "eye comfort" to listOf("eye comfort", "night light", "blue light"),
            "night light" to listOf("eye comfort", "night light"),
            "blue light" to listOf("eye comfort"),

            // Power Saving
            "power saving" to listOf("power", "power saving", "battery saver"),
            "battery saver" to listOf("power", "battery saver"),
            "power" to listOf("power"),

            // Dark Mode
            "dark mode" to listOf("dark mode", "dark theme"),
            "dark theme" to listOf("dark mode", "dark theme"),
            "dark" to listOf("dark mode"),
            "night mode" to listOf("dark mode"),
            "डार्क" to listOf("dark mode"),

            // Screenshot
            "screenshot" to listOf("screenshot"),

            // Screen Recording
            "screen recording" to listOf("screen recording"),
            "screen record" to listOf("screen recording"),
            "record screen" to listOf("screen recording"),

            // Hotspot
            "hotspot" to listOf("personal hotspot", "hotspot", "mobile hotspot"),
            "personal hotspot" to listOf("personal hotspot", "hotspot"),
            "tethering" to listOf("personal hotspot", "hotspot"),
            "हॉटस्पॉट" to listOf("personal hotspot", "hotspot"),

            // DND
            "dnd" to listOf("do not disturb", "dnd"),
            "do not disturb" to listOf("do not disturb"),
            "disturb" to listOf("do not disturb"),
            "डीएनडी" to listOf("do not disturb"),

            // Screencast
            "screencast" to listOf("screencast", "cast"),
            "cast" to listOf("screencast", "cast"),
            "mirror" to listOf("screencast"),

            // NFC
            "nfc" to listOf("nfc"),
            "एनएफसी" to listOf("nfc"),

            // QR Code
            "qr" to listOf("qr code", "qr"),
            "qr code" to listOf("qr code"),
            "scan" to listOf("qr code")
        )

        // On/Off keywords
        private val ON_WORDS = setOf(
            "on", "enable", "chalu", "shuru", "laga", "lagao", "start",
            "connect", "jala", "jalao", "activate",
            "चालू", "शुरू", "लगाओ", "जलाओ", "ऑन"
        )
        private val OFF_WORDS = setOf(
            "off", "disable", "band", "hata", "hatao", "bujha",
            "stop", "disconnect", "deactivate",
            "बंद", "हटाओ", "बुझाओ", "ऑफ"
        )

        // Page 2 tile keywords (need swipe to reach)
        private val PAGE_2_HINTS = setOf(
            "power", "dark mode", "dark", "screenshot", "screen recording",
            "personal hotspot", "hotspot", "do not disturb", "dnd",
            "screencast", "cast", "nfc", "qr code", "qr"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    // ==================== PUBLIC API ====================

    /**
     * Toggle a QS tile by name.
     * @param userQuery   e.g., "wifi", "data", "flashlight"
     * @param enable      true=ON, false=OFF, null=toggle
     */
    fun toggle(userQuery: String, enable: Boolean? = null): Boolean {
        val query = userQuery.trim().lowercase()
        Log.i(TAG, "─── TOGGLE: '$query' enable=$enable ───")

        // Open Quick Settings
        val opened = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        if (!opened) {
            Log.e(TAG, "Cannot open Quick Settings")
            service.speak("Could not open Quick Settings")
            return false
        }

        // Determine if we likely need page 2
        val needsPage2 = isLikelyPage2(query)
        val delay = 600L // Minimal safe delay for QS to render

        handler.postDelayed({
            if (needsPage2) {
                // Swipe to page 2 first
                swipeLeft {
                    handler.postDelayed({ findAndToggleTile(query, enable, retrySwipe = false) }, 500)
                }
            } else {
                findAndToggleTile(query, enable, retrySwipe = true)
            }
        }, delay)

        return true
    }

    /**
     * Parse raw command to extract tile name and direction.
     */
    fun parseToggleCommand(rawCommand: String): Pair<String, Boolean?> {
        val words = rawCommand.lowercase().trim().split(Regex("\\s+"))
        var enable: Boolean? = null
        val tileWords = mutableListOf<String>()

        for (word in words) {
            when {
                word in ON_WORDS -> enable = true
                word in OFF_WORDS -> enable = false
                word in setOf("turn", "switch", "toggle", "set", "karo", "mode") -> {
                    // "mode" can be part of tile name too
                    if (word == "mode") tileWords.add(word)
                }
                else -> tileWords.add(word)
            }
        }
        return Pair(tileWords.joinToString(" ").trim(), enable)
    }

    // ==================== CORE LOGIC ====================

    /**
     * Find the target tile in the current QS view and toggle it.
     */
    private fun findAndToggleTile(query: String, enable: Boolean?, retrySwipe: Boolean) {
        val root = service.rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "No root window")
            service.speak("Cannot read screen")
            closeQS(); return
        }

        // Build candidate names from synonyms
        val candidates = buildCandidates(query)
        Log.i(TAG, "Candidates: $candidates")

        // Scan tree with ALL filters applied
        val tiles = mutableListOf<TileCandidate>()
        scanFiltered(root, candidates, query, tiles)

        // Deduplicate: keep highest-scoring match per tile name
        val unique = tiles
            .groupBy { extractTileName(it.label).lowercase() }
            .mapValues { (_, v) -> v.maxByOrNull { it.score }!! }
            .values.sortedByDescending { it.score }
            .toList()

        Log.i(TAG, "Matched ${unique.size} tiles after filtering:")
        unique.take(3).forEach { Log.i(TAG, "  ✓ '${it.label}' score=${it.score} on=${it.isOn}") }

        if (unique.isEmpty()) {
            if (retrySwipe) {
                // Try the other page
                Log.i(TAG, "Not found — swiping to other page")
                swipeLeft {
                    handler.postDelayed({ findAndToggleTile(query, enable, retrySwipe = false) }, 500)
                }
            } else {
                service.speak("Could not find $query in Quick Settings")
                closeQS()
            }
            return
        }

        val best = unique[0]
        Log.i(TAG, "Best match: '${best.label}' score=${best.score}")
        executeToggle(best, enable)
    }

    // ==================== FILTERED SCANNING ====================

    data class TileCandidate(
        val node: AccessibilityNodeInfo,
        val label: String,
        val isOn: Boolean,
        val score: Float
    )

    /**
     * Scan with all 6 accuracy filters applied.
     */
    private fun scanFiltered(
        node: AccessibilityNodeInfo,
        candidates: List<String>,
        query: String,
        results: MutableList<TileCandidate>
    ) {
        // === FILTER 1: POSITION ===
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val centerY = rect.centerY()

        // Skip nodes outside the QS tile grid area
        if (centerY < TILE_Y_MIN || centerY > TILE_Y_MAX) {
            // Still recurse into children (container might be outside but children inside)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { scanFiltered(it, candidates, query, results) }
            }
            return
        }

        // === FILTER 2: SIZE ===
        val width = rect.width()
        val height = rect.height()
        val isTileSize = width in TILE_MIN_SIZE..TILE_MAX_SIZE && height in TILE_MIN_SIZE..TILE_MAX_SIZE

        // === FILTER 3: CLICKABILITY ===
        val isClickableNearby = node.isClickable || hasClickableParent(node, 3)

        // === FILTER 4: EXCLUSION ===
        val contentDesc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val className = node.className?.toString() ?: ""

        // Skip excluded classes (pure text labels, sliders, images)
        if (className in EXCLUDED_CLASSES && !node.isClickable) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { scanFiltered(it, candidates, query, results) }
            }
            return
        }

        // Skip excluded content-desc patterns
        val descLower = contentDesc.lowercase()
        if (EXCLUSION_PATTERNS.any { descLower.startsWith(it) || descLower == it }) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { scanFiltered(it, candidates, query, results) }
            }
            return
        }

        // Skip time/date strings (e.g., "07:55", "Tuesday, 24 Feb")
        if (descLower.matches(Regex("\\d{1,2}[:\\-]\\d{2}.*")) ||
            descLower.matches(Regex("(monday|tuesday|wednesday|thursday|friday|saturday|sunday).*"))) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { scanFiltered(it, candidates, query, results) }
            }
            return
        }

        // === FILTER 5: NAME EXTRACTION ===
        // Extract just the tile name from content-desc (before first comma or dot)
        val tileName = extractTileName(if (contentDesc.isNotBlank()) contentDesc else text)

        if (tileName.isNotBlank() && isTileSize && isClickableNearby) {
            // === FILTER 6: SMART SCORING ===
            val score = scoreTile(tileName.lowercase(), candidates, query)

            if (score > 0.4f) {
                val isOn = detectState(descLower, node)
                results.add(TileCandidate(node, tileName, isOn, score))
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { scanFiltered(it, candidates, query, results) }
        }
    }

    /**
     * Extract the clean tile name from a content-desc string.
     * "Wi-Fi,Wi-Fi two bars.,JioFiber-xy3ck" → "Wi-Fi"
     * "Mobile data, Mobile data off" → "Mobile data"
     * "Aeroplane mode" → "Aeroplane mode"
     * "Bluetooth." → "Bluetooth"
     */
    private fun extractTileName(raw: String): String {
        if (raw.isBlank()) return ""
        // Take text before first comma
        var name = raw.split(",")[0].trim()
        // Remove trailing period
        if (name.endsWith(".")) name = name.dropLast(1).trim()
        // Remove state suffixes
        name = name
            .replace(Regex("\\s+(on|off|enabled|disabled|active|inactive)$", RegexOption.IGNORE_CASE), "")
            .trim()
        return name
    }

    /**
     * Score how well a tile name matches the candidates.
     */
    private fun scoreTile(tileName: String, candidates: List<String>, query: String): Float {
        var best = 0f

        for (c in candidates) {
            val cl = c.lowercase()

            // Exact match
            if (tileName == cl) {
                best = maxOf(best, 1.0f); continue
            }

            // StartsWith match (e.g., "aeroplane" matches "aeroplane mode")
            if (tileName.startsWith(cl) || cl.startsWith(tileName)) {
                best = maxOf(best, 0.95f); continue
            }

            // Contains match
            if (tileName.contains(cl) || cl.contains(tileName)) {
                best = maxOf(best, 0.85f); continue
            }

            // Word overlap
            val tWords = tileName.split(Regex("[\\s\\-_]+")).filter { it.length > 1 }
            val cWords = cl.split(Regex("[\\s\\-_]+")).filter { it.length > 1 }
            val hits = tWords.count { tw -> cWords.any { cw -> tw == cw || tw.startsWith(cw) || cw.startsWith(tw) } }
            if (hits > 0) {
                val wordScore = hits.toFloat() / maxOf(tWords.size, cWords.size).coerceAtLeast(1)
                best = maxOf(best, wordScore * 0.8f)
            }

            // Levenshtein (for typos)
            if (tileName.length in 2..20 && cl.length in 2..20) {
                val d = levenshtein(tileName, cl)
                if (d <= 2) {
                    best = maxOf(best, (1f - d.toFloat() / maxOf(tileName.length, cl.length)) * 0.7f)
                }
            }
        }
        return best
    }

    // ==================== STATE DETECTION ====================

    private fun detectState(desc: String, node: AccessibilityNodeInfo): Boolean {
        // Check explicit state in content-desc
        // Important: use boundaries to avoid false matches (e.g., "location" contains "on")
        if (desc.contains(", on") || desc.contains(",on") || desc.endsWith(" on") ||
            desc.contains(" enabled") || desc.contains(" active") || desc.contains(" connected")) return true
        if (desc.contains(", off") || desc.contains(",off") || desc.endsWith(" off") ||
            desc.contains("data off") ||
            desc.contains(" disabled") || desc.contains(" inactive") || desc.contains(" not connected")) return false

        if (node.isChecked) return true
        if (node.isSelected) return true

        try {
            val p = node.parent
            if (p != null) {
                if (p.isChecked || p.isSelected) return true
                val pd = p.contentDescription?.toString()?.lowercase() ?: ""
                if (pd.contains(", on") || pd.contains(" enabled")) return true
                if (pd.contains(", off") || pd.contains(" disabled")) return false
            }
        } catch (_: Exception) {}

        return false
    }

    // ==================== EXECUTION ====================

    private fun executeToggle(tile: TileCandidate, enable: Boolean?) {
        val name = tile.label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        // Check if already in desired state
        if (enable == true && tile.isOn) {
            service.speak("$name is already on"); closeQS(); return
        }
        if (enable == false && !tile.isOn) {
            service.speak("$name is already off"); closeQS(); return
        }

        // Click it
        if (clickTile(tile.node)) {
            val action = when {
                enable == true -> "turned on"
                enable == false -> "turned off"
                tile.isOn -> "turned off"
                else -> "turned on"
            }
            handler.postDelayed({
                service.speak("$name $action")
                closeQS()
            }, 400)
        } else {
            service.speak("Could not toggle $name")
            closeQS()
        }
    }

    private fun clickTile(node: AccessibilityNodeInfo): Boolean {
        // Try direct click
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "Direct click OK"); return true
        }

        // Try clickable parents (up to 3 levels)
        var p = node.parent; var d = 0
        while (p != null && d < 3) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "Parent click at depth $d"); return true
            }
            p = p.parent; d++
        }

        // Fallback: coordinate tap
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    // ==================== HELPERS ====================

    private fun hasClickableParent(node: AccessibilityNodeInfo, maxDepth: Int): Boolean {
        var p = node.parent; var d = 0
        while (p != null && d < maxDepth) {
            if (p.isClickable) return true
            p = p.parent; d++
        }
        return false
    }

    private fun buildCandidates(query: String): List<String> {
        val c = mutableListOf(query)
        SYNONYMS[query]?.let { c.addAll(it) }
        query.split(Regex("\\s+")).forEach { w -> SYNONYMS[w]?.let { c.addAll(it) } }
        SYNONYMS.entries.forEach { (k, v) ->
            if (k.contains(query) || query.contains(k)) c.addAll(v)
        }
        return c.distinct()
    }

    private fun isLikelyPage2(query: String): Boolean {
        if (query in PAGE_2_HINTS) return true
        val syns = SYNONYMS[query] ?: return false
        return syns.any { it.lowercase() in PAGE_2_HINTS }
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x + 1, y + 1) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { Log.d(TAG, "Tap at ($x,$y)") }
        }, null)
        return true
    }

    private fun swipeLeft(onDone: () -> Unit) {
        val path = Path().apply { moveTo(900f, 500f); lineTo(200f, 500f) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onDone() }
            override fun onCancelled(g: GestureDescription?) { onDone() }
        }, null)
    }

    private fun closeQS() {
        handler.postDelayed({
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            handler.postDelayed({
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }, 250)
        }, 150)
    }

    private fun levenshtein(s: String, t: String): Int {
        val m = s.length; val n = t.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (s[i-1] == t[j-1]) dp[i-1][j-1]
            else minOf(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+1)
        }
        return dp[m][n]
    }
}
