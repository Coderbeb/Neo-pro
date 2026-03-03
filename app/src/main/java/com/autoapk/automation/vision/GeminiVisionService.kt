package com.autoapk.automation.vision


import android.content.Context
import android.util.Log
import com.autoapk.automation.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.type.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/**
 * Gemini Vision Service — Gemini Integration (Configurable Model)
 *
 * Features:
 *   - Multimodal image+text requests via official Google Generative AI SDK
 *   - Streaming response for low-latency TTS (speak first sentence while rest generates)
 *   - Multi-turn chat session (remembers previous descriptions for follow-ups)
 *   - Configurable model name (changeable from sidebar UI)
 *   - Higher output tokens (1024) for richer descriptions
 *   - Automatic retry with exponential backoff for transient errors
 *   - Stream cancellation on user interrupt ("stop", "bas", "ruko")
 *   - Comprehensive error handling
 */
class GeminiVisionService {

    companion object {
        private const val TAG = "Neo_Gemini"
        private const val DEFAULT_MODEL_NAME = "gemini-2.0-flash"
        private const val TEMPERATURE = 0.4f
        private const val MAX_OUTPUT_TOKENS = 1024
        private const val MAX_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 1000L

        // SharedPreferences key for selected model
        private const val PREFS_NAME = "neo_settings"
        private const val KEY_SELECTED_MODEL = "selected_gemini_model"

        fun getSelectedModel(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_MODEL, DEFAULT_MODEL_NAME) ?: DEFAULT_MODEL_NAME
        }

        fun setSelectedModel(context: Context, modelName: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SELECTED_MODEL, modelName).apply()
        }
    }

    private var generativeModel: GenerativeModel? = null
    private var chat: Chat? = null
    private var isInitialized = false
    private var currentModelName: String = DEFAULT_MODEL_NAME
    private var currentApiKey: String? = null

    // Track if a stream is currently active (for cancellation)
    @Volatile
    private var isStreaming = false

    @Volatile
    private var cancelled = false

    /**
     * Initialize the Gemini model and start a chat session.
     * API key priority: parameter > SharedPreferences > BuildConfig
     *
     * @param modelName The Gemini model to use
     * @param apiKeyOverride Optional API key (if null, reads from prefs/BuildConfig)
     */
    fun initialize(modelName: String = DEFAULT_MODEL_NAME, apiKeyOverride: String? = null) {
        val apiKey = when {
            !apiKeyOverride.isNullOrBlank() -> apiKeyOverride
            else -> {
                // Try BuildConfig fallback
                try {
                    val key = BuildConfig.GEMINI_API_KEY
                    if (key.isNotBlank() && key != "YOUR_KEY_HERE") key else ""
                } catch (_: Exception) { "" }
            }
        }

        currentApiKey = apiKey

        if (apiKey.isBlank()) {
            Log.e(TAG, "Gemini API key not configured! Enter it in the sidebar or set GEMINI_API_KEY in local.properties")
            return
        }

        try {
            currentModelName = modelName
            generativeModel = GenerativeModel(
                modelName = currentModelName,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = TEMPERATURE
                    maxOutputTokens = MAX_OUTPUT_TOKENS
                },
                systemInstruction = content {
                    text(VisionPromptBuilder.systemInstruction)
                },
                safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE)
                )
            )

            chat = generativeModel!!.startChat()
            isInitialized = true
            Log.i(TAG, "Gemini Vision Service initialized (model=$currentModelName, temp=$TEMPERATURE, maxTokens=$MAX_OUTPUT_TOKENS)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemini: ${e.message}", e)
            isInitialized = false
        }
    }

    /**
     * Reinitialize with a different model name.
     * Resets the chat session on model switch.
     * Preserves the current API key.
     */
    fun reinitialize(modelName: String, apiKeyOverride: String? = null) {
        Log.i(TAG, "Reinitializing with model: $modelName (was: $currentModelName)")
        cancelStream()
        chat = null
        generativeModel = null
        isInitialized = false
        initialize(modelName, apiKeyOverride ?: currentApiKey)
    }

    /**
     * Get the currently active model name.
     */
    fun getCurrentModelName(): String = currentModelName

    /**
     * Send an image + text prompt to Gemini and stream the response.
     *
     * Returns a Flow<String> where each emission is a text chunk.
     * The caller can collect these chunks and send them to TTS sentence-by-sentence.
     *
     * Includes automatic retry (up to 2) for transient errors.
     *
     * @param imageBytes JPEG image data (compressed for API)
     * @param prompt The text prompt from VisionPromptBuilder
     * @return Flow emitting response text chunks
     */
    fun describeScene(imageBytes: ByteArray, prompt: String): Flow<String> = flow {
        if (!isInitialized || chat == null) {
            emit("I'm having trouble connecting to my vision service. Please check your internet connection.")
            return@flow
        }

        // Validate image data
        if (imageBytes.isEmpty()) {
            emit("I received an empty image. Please try again.")
            return@flow
        }

        cancelled = false
        isStreaming = true

        var lastError: Exception? = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                if (attempt > 0) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1))
                    Log.i(TAG, "Retry attempt $attempt after ${delayMs}ms")
                    delay(delayMs)

                    // Reset chat on retry to prevent stale context issues
                    if (attempt >= 2) {
                        chat = generativeModel?.startChat()
                    }
                }

                // Build multimodal content with image bytes and prompt
                val inputContent = content("user") {
                    blob("image/jpeg", imageBytes)
                    text(prompt)
                }

                val response = chat!!.sendMessageStream(inputContent)
                var hasContent = false

                response.collect { chunk ->
                    if (cancelled) {
                        Log.i(TAG, "Stream cancelled by user")
                        return@collect
                    }
                    val text = chunk.text
                    if (!text.isNullOrBlank()) {
                        hasContent = true
                        emit(text)
                    }
                }

                // If we got content, success — break out of retry loop
                if (hasContent || cancelled) {
                    lastError = null
                    break
                }

                // Empty response — treat as error for retry
                if (!hasContent && attempt < MAX_RETRIES) {
                    Log.w(TAG, "Empty response from Gemini, will retry...")
                    lastError = Exception("Empty response")
                    continue
                }

            } catch (e: CancellationException) {
                Log.i(TAG, "Stream cancelled")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Gemini API error (attempt $attempt): ${e.message}", e)
                lastError = e
                if (!isRetryable(e) || attempt >= MAX_RETRIES) {
                    break
                }
            }
        }

        if (lastError != null) {
            val errorMsg = classifyError(lastError!!)
            emit(errorMsg)
        }

        isStreaming = false
    }.catch { e ->
        Log.e(TAG, "Flow error: ${e.message}", e)
        emit(classifyError(e))
        isStreaming = false
    }

    /**
     * Send a text-only follow-up question to the existing chat session.
     * No new image is captured — Gemini answers from conversation context.
     */
    fun followUp(textPrompt: String): Flow<String> = flow {
        if (!isInitialized || chat == null) {
            emit("I haven't looked around yet. Say 'what is around me' first.")
            return@flow
        }

        cancelled = false
        isStreaming = true

        try {
            val response = chat!!.sendMessageStream(textPrompt)
            response.collect { chunk ->
                if (cancelled) return@collect
                val text = chunk.text
                if (!text.isNullOrBlank()) {
                    emit(text)
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Follow-up stream cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Follow-up error: ${e.message}", e)
            emit(classifyError(e))
        } finally {
            isStreaming = false
        }
    }.catch { e ->
        Log.e(TAG, "Follow-up flow error: ${e.message}", e)
        emit(classifyError(e))
        isStreaming = false
    }

    /**
     * Cancel the current streaming response.
     * Called when user says "stop", "bas", "ruko", etc.
     */
    fun cancelStream() {
        if (isStreaming) {
            cancelled = true
            Log.i(TAG, "Cancel requested — stream will stop at next chunk")
        }
    }

    /**
     * Check if a stream is currently active.
     */
    fun isStreaming(): Boolean = isStreaming

    /**
     * Reset the chat session (start fresh conversation).
     * Useful when context becomes stale or confused.
     */
    fun resetSession() {
        try {
            chat = generativeModel?.startChat()
            Log.i(TAG, "Chat session reset")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset session: ${e.message}", e)
        }
    }

    /**
     * Check if an error is transient and worth retrying.
     */
    private fun isRetryable(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("network") ||
                msg.contains("connect") ||
                msg.contains("timeout") ||
                msg.contains("rate limit") ||
                msg.contains("429") ||
                msg.contains("503") ||
                msg.contains("unavailable") ||
                msg.contains("internal") ||
                msg.contains("500")
    }

    /**
     * Classify an error into a user-friendly message.
     */
    private fun classifyError(e: Throwable): String {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("api key") || msg.contains("authentication") || msg.contains("401") ->
                "Vision service is not configured. Please set up the API key."
            msg.contains("rate limit") || msg.contains("quota") || msg.contains("429") ->
                "I need to slow down a bit. Try again in a few seconds."
            msg.contains("network") || msg.contains("connect") || msg.contains("timeout") ->
                "I'm having trouble connecting. Please check your internet connection."
            msg.contains("blocked") || msg.contains("safety") ->
                "I couldn't process that image properly. Please try again."
            msg.contains("empty") || msg.contains("no content") ->
                "I couldn't understand the scene properly. Please try again."
            msg.contains("invalid") || msg.contains("not found") || msg.contains("404") ->
                "The selected model is not available. Please choose a different model in settings."
            msg.contains("503") || msg.contains("unavailable") ->
                "The vision service is temporarily busy. Please try again in a moment."
            msg.contains("500") || msg.contains("internal") ->
                "The vision service had an internal error. Please try again."
            else ->
                "Something went wrong with vision. Please try again."
        }
    }

    /**
     * Release resources.
     */
    fun release() {
        cancelStream()
        chat = null
        generativeModel = null
        isInitialized = false
        Log.i(TAG, "GeminiVisionService released")
    }
}
