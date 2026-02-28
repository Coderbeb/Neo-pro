package com.autoapk.automation.vision


import android.util.Log
import com.autoapk.automation.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.type.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream

/**
 * Gemini Vision Service — Gemini 2.5 Flash Integration
 *
 * Features:
 *   - Multimodal image+text requests via official Google Generative AI SDK
 *   - Streaming response for low-latency TTS (speak first sentence while rest generates)
 *   - Multi-turn chat session (remembers previous descriptions for follow-ups)
 *   - Configurable: temperature 0.3, max 300 tokens, safety filters off
 *   - Stream cancellation on user interrupt ("stop", "bas", "ruko")
 *   - Error handling: network, rate limit, blocked response
 */
class GeminiVisionService {

    companion object {
        private const val TAG = "Neo_Gemini"
        private const val MODEL_NAME = "gemini-2.0-flash"
        private const val TEMPERATURE = 0.3f
        private const val MAX_OUTPUT_TOKENS = 300
    }

    private var generativeModel: GenerativeModel? = null
    private var chat: Chat? = null
    private var isInitialized = false

    // Track if a stream is currently active (for cancellation)
    @Volatile
    private var isStreaming = false

    @Volatile
    private var cancelled = false

    /**
     * Initialize the Gemini model and start a chat session.
     * Uses API key from BuildConfig (set via local.properties).
     */
    fun initialize() {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_KEY_HERE") {
            Log.e(TAG, "Gemini API key not configured! Set GEMINI_API_KEY in local.properties")
            return
        }

        try {
            generativeModel = GenerativeModel(
                modelName = MODEL_NAME,
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
            Log.i(TAG, "Gemini Vision Service initialized (model=$MODEL_NAME, temp=$TEMPERATURE)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemini: ${e.message}", e)
            isInitialized = false
        }
    }

    /**
     * Send an image + text prompt to Gemini and stream the response.
     *
     * Returns a Flow<String> where each emission is a text chunk.
     * The caller can collect these chunks and send them to TTS sentence-by-sentence.
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

        cancelled = false
        isStreaming = true

        try {
            // Build multimodal content with image bytes and prompt
            val inputContent = content("user") {
                blob("image/jpeg", imageBytes)
                text(prompt)
            }

            val response = chat!!.sendMessageStream(inputContent)

            response.collect { chunk ->
                if (cancelled) {
                    Log.i(TAG, "Stream cancelled by user")
                    return@collect
                }
                val text = chunk.text
                if (!text.isNullOrBlank()) {
                    emit(text)
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Stream cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API error: ${e.message}", e)
            val errorMsg = classifyError(e)
            emit(errorMsg)
        } finally {
            isStreaming = false
        }
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
     * Classify an error into a user-friendly message.
     */
    private fun classifyError(e: Throwable): String {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("api key") || msg.contains("authentication") ->
                "Vision service is not configured. Please set up the API key."
            msg.contains("rate limit") || msg.contains("quota") || msg.contains("429") ->
                "I need to slow down a bit. Try again in a few seconds."
            msg.contains("network") || msg.contains("connect") || msg.contains("timeout") ->
                "I'm having trouble connecting to my vision service. Please check your internet connection."
            msg.contains("blocked") || msg.contains("safety") ->
                "I couldn't understand the scene properly. Please try again."
            msg.contains("empty") || msg.contains("no content") ->
                "I couldn't understand the scene properly. Please try again."
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
