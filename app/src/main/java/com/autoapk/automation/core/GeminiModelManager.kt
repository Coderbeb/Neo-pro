package com.autoapk.automation.core

import android.content.Context
import android.util.Log

/**
 * Gemini Model Manager — Curated Model List + API Key Management
 *
 * Provides a curated list of Gemini models for the vision feature.
 * Also manages the API key via SharedPreferences (user can input it in sidebar).
 */
class GeminiModelManager(private val context: Context) {

    companion object {
        private const val TAG = "Neo_ModelMgr"
        private const val PREFS_NAME = "neo_settings"
        private const val KEY_SELECTED_MODEL = "selected_gemini_model"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val DEFAULT_MODEL = "gemini-2.0-flash"

        /**
         * Curated list of Gemini models.
         */
        val CURATED_MODELS = listOf(
            GeminiModel("gemini-2.0-flash", "Gemini 2.0 Flash"),
            GeminiModel("gemini-2.0-flash-001", "Gemini 2.0 Flash 001"),
            GeminiModel("gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite"),
            GeminiModel("gemini-2.0-flash-lite-001", "Gemini 2.0 Flash Lite 001"),
            GeminiModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
            GeminiModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite"),
            GeminiModel("gemini-2.5-flash-lite-preview-0925", "Gemini 2.5 Flash Lite Preview Sep 2025"),
            GeminiModel("gemini-3.0-flash", "Gemini 3 Flash"),
            GeminiModel("gemini-3.1-flash-lite-preview", "Gemini 3.1 Flash Lite Preview"),
            GeminiModel("gemini-flash-latest", "Gemini Flash Latest"),
            GeminiModel("gemini-flash-lite-latest", "Gemini Flash-Lite Latest")
        )
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
     * Get the curated model list.
     */
    fun getModels(): List<GeminiModel> = CURATED_MODELS

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
        // Nothing to release for curated list approach
    }
}
