package com.autoapk.automation.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini Model Manager — Dynamic Model List from API + API Key Management
 *
 * Fetches available Gemini models from the REST API, filters to "flash" models.
 * Falls back to a curated list if API call fails.
 * Also manages the API key via SharedPreferences.
 */
class GeminiModelManager(private val context: Context) {

    companion object {
        private const val TAG = "Neo_ModelMgr"
        private const val PREFS_NAME = "neo_settings"
        private const val KEY_SELECTED_MODEL = "selected_gemini_model"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val DEFAULT_MODEL = "gemini-2.0-flash"
        private const val MODELS_API_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        /**
         * Fallback curated list (used when API is unreachable).
         */
        val CURATED_MODELS = listOf(
            GeminiModel("gemini-2.0-flash", "Gemini 2.0 Flash"),
            GeminiModel("gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite"),
            GeminiModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
            GeminiModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite"),
            GeminiModel("gemini-flash-latest", "Gemini Flash Latest")
        )

        // ==================== VISION LOG (singleton) ====================
        private val visionLogEntries = mutableListOf<String>()
        private var visionLogListener: ((List<String>) -> Unit)? = null
        private const val MAX_VISION_LOG = 50

        fun addVisionLog(msg: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val entry = "[$timestamp] $msg"
            synchronized(visionLogEntries) {
                visionLogEntries.add(entry)
                if (visionLogEntries.size > MAX_VISION_LOG) {
                    visionLogEntries.removeAt(0)
                }
            }
            visionLogListener?.invoke(visionLogEntries.toList())
        }

        fun getVisionLog(): List<String> = synchronized(visionLogEntries) { visionLogEntries.toList() }

        fun clearVisionLog() {
            synchronized(visionLogEntries) { visionLogEntries.clear() }
            visionLogListener?.invoke(emptyList())
        }

        fun setVisionLogListener(listener: ((List<String>) -> Unit)?) {
            visionLogListener = listener
        }
    }

    /**
     * Represents a Gemini model.
     */
    data class GeminiModel(
        val modelId: String,        // e.g., "gemini-2.0-flash"
        val displayName: String     // e.g., "Gemini 2.0 Flash"
    )

    // ==================== MODEL SELECTION ====================

    /**
     * Get the fallback curated model list.
     */
    fun getModels(): List<GeminiModel> = CURATED_MODELS

    /**
     * Fetch available Gemini models from the API.
     * Filters to "flash" models only. Runs on a background thread.
     * Calls back on the main thread.
     *
     * @param onResult callback with the model list (or curated fallback on error)
     */
    fun fetchModelsFromApi(onResult: (List<GeminiModel>) -> Unit) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key — using curated list")
            onResult(CURATED_MODELS)
            return
        }

        Thread {
            try {
                val url = URL("$MODELS_API_URL?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"

                val code = conn.responseCode
                if (code != 200) {
                    Log.w(TAG, "Models API returned $code — using curated list")
                    postToMain { onResult(CURATED_MODELS) }
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                val modelsArray = json.getJSONArray("models")

                val models = mutableListOf<GeminiModel>()
                for (i in 0 until modelsArray.length()) {
                    val obj = modelsArray.getJSONObject(i)
                    val rawName = obj.getString("name") // e.g., "models/gemini-2.0-flash"
                    val modelId = rawName.removePrefix("models/")
                    val displayName = obj.optString("displayName", modelId)

                    // Only include "flash" models
                    if (modelId.contains("flash", ignoreCase = true)) {
                        models.add(GeminiModel(modelId, displayName))
                    }
                }

                // Sort: latest/higher version first
                models.sortByDescending { it.modelId }

                Log.i(TAG, "Fetched ${models.size} flash models from API")
                postToMain { onResult(if (models.isNotEmpty()) models else CURATED_MODELS) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch models: ${e.message}")
                postToMain { onResult(CURATED_MODELS) }
            }
        }.start()
    }

    private fun postToMain(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    /**
     * Get the currently selected model ID.
     */
    fun getSelectedModelId(): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    /**
     * Save the selected model ID.
     */
    fun setSelectedModelId(modelId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_MODEL, modelId).apply()
        Log.i(TAG, "Selected model saved: $modelId")
    }

    // ==================== API KEY MANAGEMENT ====================

    /**
     * Get the saved API key from SharedPreferences.
     * Falls back to BuildConfig if no saved key.
     */
    fun getApiKey(): String {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, null)
        if (!saved.isNullOrBlank()) return saved

        // Fallback to BuildConfig
        return try {
            val key = com.autoapk.automation.BuildConfig.GEMINI_API_KEY
            if (key.isNotBlank() && key != "YOUR_KEY_HERE") key else ""
        } catch (_: Exception) { "" }
    }

    /**
     * Save the API key to SharedPreferences.
     */
    fun setApiKey(apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, apiKey).apply()
        Log.i(TAG, "API key saved (length=${apiKey.length})")
    }

    /**
     * Check if an API key is configured (either saved or from BuildConfig).
     */
    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    /**
     * Release resources.
     */
    fun release() {
        // Nothing to release
    }
}

