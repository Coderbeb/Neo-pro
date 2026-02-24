package com.autoapk.automation.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject

/**
 * AppRegistry automatically discovers all installed apps on the device,
 * caches their names and package names, and provides fuzzy matching
 * so voice commands like "open youtube" work on any phone brand.
 */
class AppRegistry(private val context: Context) {

    companion object {
        private const val TAG = "Neo_Registry"
        private const val PREFS_NAME = "autoapk_app_registry"
        private const val KEY_APP_MAP = "app_map"
        private const val KEY_LAST_SCAN = "last_scan_time"
        private const val SCAN_INTERVAL_MS = 30 * 60 * 1000L // Re-scan every 30 min
    }

    // In-memory cache: lowercase name -> package name
    private var appMap: MutableMap<String, String> = mutableMapOf()

    // Common aliases that users might say instead of the actual app name
    private val aliases = mapOf(
        "youtube" to listOf("youtube", "you tube", "utube"),
        "whatsapp" to listOf("whatsapp", "whats app", "what's app", "whatapp"),
        "instagram" to listOf("instagram", "insta", "insta gram"),
        "chrome" to listOf("chrome", "browser", "google chrome"),
        "gmail" to listOf("gmail", "g mail", "google mail", "email", "mail"),
        "maps" to listOf("maps", "google maps", "map", "navigation"),
        "spotify" to listOf("spotify", "spot if i", "spot a fi"),
        "telegram" to listOf("telegram", "tele gram"),
        "facebook" to listOf("facebook", "face book", "fb"),
        "twitter" to listOf("twitter", "x"),
        "calculator" to listOf("calculator", "calc", "calculate"),
        "calendar" to listOf("calendar", "calender"),
        "camera" to listOf("camera", "cam"),
        "clock" to listOf("clock", "alarm", "timer", "stopwatch"),
        "settings" to listOf("settings", "setting"),
        "gallery" to listOf("gallery", "photos", "photo", "pictures"),
        "phone" to listOf("phone", "dialer", "dial", "calls"),
        "messages" to listOf("messages", "message", "sms", "texting", "texts"),
        "contacts" to listOf("contacts", "contact", "people"),
        "files" to listOf("files", "file manager", "file"),
        "music" to listOf("music", "music player"),
        "notes" to listOf("notes", "note", "notepad"),
        "weather" to listOf("weather"),
        "play store" to listOf("play store", "app store", "store", "playstore")
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        loadFromCache()
    }

    /**
     * Scan all installed apps on the device and build the name->package map.
     * Call this once on app startup or when permissions are granted.
     */
    fun scanInstalledApps() {
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val newMap = mutableMapOf<String, String>()

            for (resolveInfo in resolveInfos) {
                val packageName = resolveInfo.activityInfo.packageName
                val appLabel = resolveInfo.loadLabel(pm).toString()

                // Store with the exact display name (lowercased)
                val key = appLabel.lowercase().trim()
                if (key.isNotEmpty()) {
                    newMap[key] = packageName
                }

                // Also store without common suffixes for easier matching
                val simplified = key
                    .replace(" - ", " ")
                    .replace("google ", "")
                    .trim()
                if (simplified.isNotEmpty() && simplified != key) {
                    newMap[simplified] = packageName
                }
            }

            // Build alias reverse map: for each installed app, check if any alias points to it
            for ((aliasGroup, aliasList) in aliases) {
                // Find an installed app whose name contains this alias group
                val matchingPkg = newMap.entries.find { (name, _) ->
                    name.contains(aliasGroup) || aliasGroup.contains(name)
                }?.value

                if (matchingPkg != null) {
                    for (alias in aliasList) {
                        if (!newMap.containsKey(alias)) {
                            newMap[alias] = matchingPkg
                        }
                    }
                }
            }

            appMap = newMap
            saveToCache()

            Log.i(TAG, "Scanned ${resolveInfos.size} apps, built ${appMap.size} name mappings")
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning apps: ${e.message}", e)
        }
    }

    /**
     * Find the package name for a given app name using fuzzy matching.
     * Returns the best-matching package name, or null if not found.
     */
    fun findApp(query: String): String? {
        val key = query.lowercase().trim()

        // 1. Exact match
        appMap[key]?.let { return it }

        // 2. Check aliases
        for ((_, aliasList) in aliases) {
            if (aliasList.contains(key)) {
                // Find which alias group this belongs to, then look up by group name
                for (alias in aliasList) {
                    appMap[alias]?.let { return it }
                }
            }
        }

        // 3. Partial match: query is contained in an app name
        for ((name, pkg) in appMap) {
            if (name.contains(key)) return pkg
        }

        // 4. Partial match: app name is contained in query
        // Sort by name length descending to prefer longer (more specific) matches
        val reverseMatches = appMap.entries
            .filter { (name, _) -> key.contains(name) && name.length >= 3 }
            .sortedByDescending { it.key.length }

        if (reverseMatches.isNotEmpty()) {
            return reverseMatches.first().value
        }

        // 5. Fuzzy: find closest match using word overlap
        val queryWords = key.split(" ").filter { it.length >= 2 }
        var bestMatch: String? = null
        var bestScore = 0

        for ((name, pkg) in appMap) {
            val nameWords = name.split(" ").filter { it.length >= 2 }
            val score = queryWords.count { qw ->
                nameWords.any { nw -> nw.contains(qw) || qw.contains(nw) }
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = pkg
            }
        }

        if (bestScore > 0) return bestMatch

        // 6. Final fallback: use PackageManager to search by label
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label.contains(key) || key.contains(label)) {
                    if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                        return app.packageName
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Fallback search failed: ${e.message}")
            null
        }
    }

    /**
     * Launch an app by its query name. Returns true if successful.
     */
    fun launchApp(query: String): Boolean {
        val packageName = findApp(query)
        if (packageName != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                Log.i(TAG, "Launched '$query' -> $packageName")
                return true
            }
        }
        Log.w(TAG, "Could not find app for query: '$query'")
        return false
    }

    /**
     * Check if the registry needs a refresh (first time or stale cache).
     */
    fun needsScan(): Boolean {
        val lastScan = prefs.getLong(KEY_LAST_SCAN, 0)
        return appMap.isEmpty() || (System.currentTimeMillis() - lastScan > SCAN_INTERVAL_MS)
    }

    /**
     * Get the count of registered apps.
     */
    fun getAppCount(): Int = appMap.size

    /**
     * Get all registered app names (for debugging).
     */
    fun getAllAppNames(): List<String> = appMap.keys.sorted()

    // ==================== PERSISTENCE ====================

    private fun saveToCache() {
        try {
            val json = JSONObject()
            for ((name, pkg) in appMap) {
                json.put(name, pkg)
            }
            prefs.edit()
                .putString(KEY_APP_MAP, json.toString())
                .putLong(KEY_LAST_SCAN, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Saved ${appMap.size} apps to cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cache: ${e.message}")
        }
    }

    private fun loadFromCache() {
        try {
            val jsonStr = prefs.getString(KEY_APP_MAP, null) ?: return
            val json = JSONObject(jsonStr)
            val loaded = mutableMapOf<String, String>()
            for (key in json.keys()) {
                loaded[key] = json.getString(key)
            }
            appMap = loaded
            Log.d(TAG, "Loaded ${appMap.size} apps from cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache: ${e.message}")
            appMap = mutableMapOf()
        }
    }
}
