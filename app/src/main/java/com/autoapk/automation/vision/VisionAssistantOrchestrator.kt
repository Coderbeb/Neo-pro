package com.autoapk.automation.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.autoapk.automation.core.PhoneStateDetector
import com.autoapk.automation.core.GeminiModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Vision Assistant Orchestrator — Master Coordinator
 *
 * Wires together all vision components and exposes high-level actions
 * that CommandProcessor can call directly.
 *
 * Components (all lazily initialized on first vision command):
 *   - UsbCameraManager: frame capture
 *   - FaceRecognitionEngine: on-device face detection + recognition
 *   - VisionContextMemory: scene history + conversation memory
 *   - VisionPromptBuilder: prompt construction
 *   - GeminiVisionService: Gemini API + streaming
 *   - AutoDescribeManager: periodic capture loop
 *
 * Error Handling:
 *   - Camera not connected → TTS error message
 *   - No face for registration → TTS guidance
 *   - Gemini failure → TTS with retry suggestion
 *   - Scene unchanged (auto mode) → silent skip
 */
class VisionAssistantOrchestrator(
    private val context: Context,
    private val tts: (String) -> Unit,
    private val isTtsSpeaking: () -> Boolean,
    private val phoneStateDetector: PhoneStateDetector?
) {
    companion object {
        private const val TAG = "Neo_VisionOrch"
        // Rate limit: minimum 3 seconds between Gemini API calls
        private const val MIN_API_INTERVAL_MS = 3000L
    }

    // Components — initialized lazily
    private var usbCameraManager: UsbCameraManager? = null
    private var builtInCameraManager: BuiltInCameraManager? = null
    private var faceEngine: FaceRecognitionEngine? = null
    private var memory: VisionContextMemory? = null
    private var geminiService: GeminiVisionService? = null
    private var autoDescribeManager: AutoDescribeManager? = null

    private var isInitialized = false
    private var lastApiCallTime = 0L
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Language mode — set by CommandProcessor before each vision call */
    var isHindiMode: Boolean = false

    // ==================== LAZY INITIALIZATION ====================

    /**
     * Initialize all vision components on first use.
     * Returns true if initialization succeeded.
     */
    private fun ensureInitialized(): Boolean {
        if (isInitialized) return true

        try {
            Log.i(TAG, "Initializing Vision Assistance Module...")

            // USB Camera (always initialized for hot-plug detection)
            usbCameraManager = UsbCameraManager(context).also {
                it.initialize()
                it.setListener(object : UsbCameraManager.CameraListener {
                    override fun onCameraConnected() {
                        tts("Camera connected")
                        Log.i(TAG, "USB camera connected")
                        GeminiModelManager.addVisionLog("📷 USB camera connected")
                    }
                    override fun onCameraDisconnected() {
                        tts("Camera got disconnected")
                        autoDescribeManager?.stop()
                        Log.w(TAG, "USB camera disconnected")
                        GeminiModelManager.addVisionLog("📷 USB camera disconnected")
                    }
                    override fun onError(message: String) {
                        Log.e(TAG, "Camera error: $message")
                        GeminiModelManager.addVisionLog("❌ Camera error: $message")
                    }
                })
            }

            // Built-in Camera (lazily init when preference is ON)
            if (isBuiltInCameraEnabled()) {
                builtInCameraManager = BuiltInCameraManager(context).also {
                    it.initialize()
                }
                Log.i(TAG, "Built-in camera enabled via user preference")
            }

            // Face Recognition
            faceEngine = FaceRecognitionEngine(context).also {
                it.initialize()
            }

            // Context Memory
            memory = VisionContextMemory()

            // Gemini Service — use the model and API key selected by user in settings
            val selectedModel = GeminiVisionService.getSelectedModel(context)
            val modelManager = com.autoapk.automation.core.GeminiModelManager(context)
            val apiKey = modelManager.getApiKey()
            geminiService = GeminiVisionService().also {
                it.initialize(selectedModel, apiKey)
            }

            // Auto-Describe Manager
            autoDescribeManager = AutoDescribeManager(context).also {
                it.setListener(autoDescribeListener)
            }

            isInitialized = true
            Log.i(TAG, "✅ Vision module initialized successfully")
            GeminiModelManager.addVisionLog("✅ Vision module initialized")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Vision module initialization failed: ${e.message}", e)
            GeminiModelManager.addVisionLog("❌ Init failed: ${e.message}")
            tts("Could not start vision mode. Please try again.")
            return false
        }
    }

    /**
     * Check if user has enabled the built-in camera toggle in the UI.
     */
    private fun isBuiltInCameraEnabled(): Boolean {
        return context.getSharedPreferences("neo_vision", Context.MODE_PRIVATE)
            .getBoolean("use_builtin_camera", false)
    }

    /**
     * Ensure a camera is available (built-in or USB).
     * If built-in camera is enabled AND available, use it.
     * Otherwise fall back to USB camera.
     */
    private fun ensureCamera(): Boolean {
        // Re-check preference each time in case user toggled mid-session
        if (isBuiltInCameraEnabled()) {
            // Initialize built-in camera if not yet done
            if (builtInCameraManager == null) {
                builtInCameraManager = BuiltInCameraManager(context).also { it.initialize() }
            }
            if (builtInCameraManager!!.isConnected()) return true
            // Built-in camera not available (permission denied?) — fall through to USB
            Log.w(TAG, "Built-in camera not available, checking USB...")
        }

        // USB camera
        val cam = usbCameraManager ?: return false
        if (cam.isConnected()) return true

        // Try to connect USB
        if (!cam.connect()) {
            if (isBuiltInCameraEnabled()) {
                tts("Camera is not available. Please grant camera permission.")
            } else {
                tts("I cannot see anything. Please connect the camera through OTG cable, or enable built-in camera in settings.")
            }
            return false
        }

        // Connection pending (permission dialog may show)
        return cam.isConnected()
    }

    /**
     * Capture a frame from whichever camera source is active.
     */
    private fun captureFrameFromActiveCamera(): android.graphics.Bitmap? {
        // Prefer built-in camera if enabled and connected
        if (isBuiltInCameraEnabled() && builtInCameraManager?.isConnected() == true) {
            return builtInCameraManager!!.captureFrame()
        }
        // Fall back to USB
        return usbCameraManager?.captureFrame()
    }

    /**
     * Compress bitmap for API — inline implementation.
     * Downscales to 768x576 at 80% JPEG quality for good Gemini results.
     */
    private fun compressForApi(bitmap: android.graphics.Bitmap): ByteArray {
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 768, 576, true)
        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
        if (scaled !== bitmap) scaled.recycle()
        return stream.toByteArray()
    }

    /**
     * Check if a frame is mostly black (avg brightness < 15).
     */
    private fun isBlackFrame(bitmap: android.graphics.Bitmap): Boolean {
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val pixels = IntArray(256)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        if (small !== bitmap) small.recycle()
        var totalBrightness = 0L
        for (p in pixels) {
            val r = android.graphics.Color.red(p)
            val g = android.graphics.Color.green(p)
            val b = android.graphics.Color.blue(p)
            totalBrightness += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }
        val avgBrightness = totalBrightness / pixels.size
        return avgBrightness < 15
    }

    /**
     * Compute perceptual hash — inline implementation.
     * 64-bit hash based on 8x8 grayscale mean comparison.
     */
    private fun computeHash(bitmap: android.graphics.Bitmap): Long {
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        if (small !== bitmap) small.recycle()

        var sum = 0L
        val grayValues = IntArray(64)
        for (i in pixels.indices) {
            val r = android.graphics.Color.red(pixels[i])
            val g = android.graphics.Color.green(pixels[i])
            val b = android.graphics.Color.blue(pixels[i])
            grayValues[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            sum += grayValues[i]
        }
        val mean = sum / 64

        var hash = 0L
        for (i in grayValues.indices) {
            if (grayValues[i] >= mean) hash = hash or (1L shl i)
        }
        return hash
    }

    /**
     * Change the Gemini model at runtime.
     * Reinitializes the GeminiVisionService with the new model and resets chat.
     */
    fun changeModel(modelName: String) {
        Log.i(TAG, "Changing Gemini model to: $modelName")
        GeminiVisionService.setSelectedModel(context, modelName)
        if (isInitialized) {
            geminiService?.reinitialize(modelName)
            memory?.clear()  // Clear memory since responses may differ with new model
        }
    }

    // ==================== HIGH-LEVEL ACTIONS ====================

    /**
     * Describe the current scene (MANUAL mode).
     * Full pipeline: capture → face recognize → prompt → Gemini → streaming TTS.
     */
    fun describeScene(query: String? = null) {
        if (!ensureInitialized() || !ensureCamera()) return

        scope.launch {
            try {
                GeminiModelManager.addVisionLog("📸 Capturing frame for describeScene...")
                val bitmap = captureFrameFromActiveCamera()
                if (bitmap == null) {
                    GeminiModelManager.addVisionLog("❌ Frame capture returned null")
                    tts("I cannot see anything right now. Please check the camera.")
                    return@launch
                }

                val w = bitmap.width; val h = bitmap.height
                val isBlack = isBlackFrame(bitmap)
                GeminiModelManager.addVisionLog("📸 Frame: ${w}x${h}" + if (isBlack) " ⚠️ BLACK FRAME" else " ✅ OK")

                val faces = faceEngine?.detectAndRecognize(bitmap) ?: emptyList()
                val previousContext = memory?.getPreviousContext() ?: ""
                val prompt = VisionPromptBuilder.buildPrompt(
                    mode = VisionPromptBuilder.CaptureMode.MANUAL,
                    faces = faces,
                    previousContext = previousContext,
                    userQuery = query,
                    respondInHindi = isHindiMode
                )

                val imageBytes = compressForApi(bitmap)
                val hash = computeHash(bitmap)
                bitmap.recycle()

                GeminiModelManager.addVisionLog("🤖 Sending to Gemini (${imageBytes.size/1024}KB)")
                streamAndSpeak(imageBytes, prompt, faces, hash)
            } catch (e: Exception) {
                Log.e(TAG, "describeScene failed: ${e.message}", e)
                GeminiModelManager.addVisionLog("❌ describeScene error: ${e.message}")
                tts("Something went wrong. Please try again.")
            }
        }
    }

    /**
     * Describe only the people in the scene (PEOPLE mode).
     */
    fun whoIsThere() {
        if (!ensureInitialized() || !ensureCamera()) return

        scope.launch {
            try {
                val bitmap = captureFrameFromActiveCamera()
                if (bitmap == null) {
                    tts("I cannot see anything right now.")
                    return@launch
                }

                val faces = faceEngine?.detectAndRecognize(bitmap) ?: emptyList()
                if (faces.isEmpty()) {
                    tts("I don't see anyone in front of the camera right now.")
                    bitmap.recycle()
                    return@launch
                }

                val prompt = VisionPromptBuilder.buildPrompt(
                    mode = VisionPromptBuilder.CaptureMode.PEOPLE,
                    faces = faces,
                    respondInHindi = isHindiMode
                )
                val imageBytes = compressForApi(bitmap)
                val hash = computeHash(bitmap)
                bitmap.recycle()

                streamAndSpeak(imageBytes, prompt, faces, hash)
            } catch (e: Exception) {
                Log.e(TAG, "whoIsThere failed: ${e.message}", e)
                tts("Something went wrong. Please try again.")
            }
        }
    }

    /**
     * Read visible text in the scene (TEXT_READING mode).
     */
    fun readText() {
        if (!ensureInitialized() || !ensureCamera()) return

        scope.launch {
            try {
                val bitmap = captureFrameFromActiveCamera() ?: run {
                    tts("I cannot see anything right now.")
                    return@launch
                }
                val prompt = VisionPromptBuilder.buildPrompt(mode = VisionPromptBuilder.CaptureMode.TEXT_READING, respondInHindi = isHindiMode)
                val imageBytes = compressForApi(bitmap)
                val hash = computeHash(bitmap)
                bitmap.recycle()
                streamAndSpeak(imageBytes, prompt, emptyList(), hash)
            } catch (e: Exception) {
                Log.e(TAG, "readText failed: ${e.message}", e)
                tts("Something went wrong. Please try again.")
            }
        }
    }

    /**
     * Describe what changed since the last scene.
     */
    fun whatChanged() {
        if (!ensureInitialized() || !ensureCamera()) return

        if (memory?.hasDescribedAnything() != true) {
            tts("I haven't looked around yet. Say 'what is around me' first.")
            return
        }

        scope.launch {
            try {
                val bitmap = captureFrameFromActiveCamera() ?: run {
                    tts("I cannot see anything right now.")
                    return@launch
                }
                val faces = faceEngine?.detectAndRecognize(bitmap) ?: emptyList()
                val previousContext = memory?.getPreviousContext() ?: ""
                val prompt = VisionPromptBuilder.buildPrompt(
                    mode = VisionPromptBuilder.CaptureMode.WHAT_CHANGED,
                    faces = faces,
                    previousContext = previousContext,
                    respondInHindi = isHindiMode
                )
                val imageBytes = compressForApi(bitmap)
                val hash = computeHash(bitmap)
                bitmap.recycle()
                streamAndSpeak(imageBytes, prompt, faces, hash)
            } catch (e: Exception) {
                Log.e(TAG, "whatChanged failed: ${e.message}", e)
                tts("Something went wrong. Please try again.")
            }
        }
    }

    /**
     * Check if the path ahead is safe.
     */
    fun isPathSafe() {
        if (!ensureInitialized() || !ensureCamera()) return

        scope.launch {
            try {
                val bitmap = captureFrameFromActiveCamera() ?: run {
                    tts("I cannot see anything right now.")
                    return@launch
                }
                val prompt = VisionPromptBuilder.buildPrompt(mode = VisionPromptBuilder.CaptureMode.SAFETY_CHECK, respondInHindi = isHindiMode)
                val imageBytes = compressForApi(bitmap)
                val hash = computeHash(bitmap)
                bitmap.recycle()
                streamAndSpeak(imageBytes, prompt, emptyList(), hash)
            } catch (e: Exception) {
                Log.e(TAG, "isPathSafe failed: ${e.message}", e)
                tts("Something went wrong. Please try again.")
            }
        }
    }

    /**
     * Find a specific object in the scene.
     */
    fun findObject(objectName: String) {
        if (!ensureInitialized() || !ensureCamera()) return

        scope.launch {
            try {
                val bitmap = captureFrameFromActiveCamera() ?: run {
                    tts("I cannot see anything right now.")
                    return@launch
                }
                val prompt = VisionPromptBuilder.buildPrompt(
                    mode = VisionPromptBuilder.CaptureMode.FIND_OBJECT,
                    objectToFind = objectName,
                    respondInHindi = isHindiMode
                )
                val imageBytes = compressForApi(bitmap)
                val hash = computeHash(bitmap)
                bitmap.recycle()
                streamAndSpeak(imageBytes, prompt, emptyList(), hash)
            } catch (e: Exception) {
                Log.e(TAG, "findObject failed: ${e.message}", e)
                tts("Something went wrong. Please try again.")
            }
        }
    }

    /**
     * Handle a follow-up question (no new image).
     */
    fun handleFollowUp(question: String) {
        if (!ensureInitialized()) return

        val mem = memory ?: return
        if (!mem.isFollowUpValid()) {
            tts("I haven't looked around yet. Say 'what is around me' first.")
            return
        }

        scope.launch {
            try {
                val prompt = VisionPromptBuilder.buildPrompt(
                    mode = VisionPromptBuilder.CaptureMode.FOLLOW_UP,
                    userQuery = question,
                    respondInHindi = isHindiMode
                )
                geminiService?.followUp(prompt)?.collect { chunk ->
                    tts(chunk)
                }
                mem.addConversationTurn("user", question)
            } catch (e: Exception) {
                Log.e(TAG, "followUp failed: ${e.message}", e)
                tts("I couldn't answer that. Please try again.")
            }
        }
    }

    // ==================== FACE MANAGEMENT ====================

    /**
     * Register a face with guided multi-shot capture.
     * Takes 3 photos from different angles with voice guidance.
     */
    fun rememberFace(name: String) {
        if (!ensureInitialized() || !ensureCamera()) return

        scope.launch {
            try {
                val result = faceEngine?.registerFaceGuided(
                    name = name,
                    captureFrame = { captureFrameFromActiveCamera() },
                    tts = tts
                )
                if (result != null) {
                    tts(result.message)
                } else {
                    tts("Face recognition is not available.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "rememberFace failed: ${e.message}", e)
                tts("Could not register the face. Please try again.")
            }
        }
    }

    /**
     * Delete a face from the database.
     */
    fun forgetFace(name: String) {
        if (!ensureInitialized()) return

        val deleted = faceEngine?.deleteFace(name) ?: false
        if (deleted) {
            tts("I've forgotten ${name}'s face")
        } else {
            tts("I don't know anyone named $name")
        }
    }

    /**
     * List all known faces.
     */
    fun listKnownFaces() {
        if (!ensureInitialized()) return

        val faces = faceEngine?.listKnownFaces() ?: emptyList()
        if (faces.isEmpty()) {
            tts("I don't know anyone yet. You can teach me by saying 'remember this face as' followed by the name.")
        } else {
            val names = faces.joinToString(", ")
            tts("I know ${faces.size} ${if (faces.size == 1) "person" else "people"}: $names")
        }
    }

    // ==================== AUTO-DESCRIBE / NAVIGATION ====================

    /**
     * Start auto-describe mode.
     */
    fun startAutoDescribe(intervalSeconds: Int? = null) {
        if (!ensureInitialized() || !ensureCamera()) return

        val intervalMs = intervalSeconds?.let { it * 1000L }
        autoDescribeManager?.start(intervalMs)
        tts("I'll keep watching and tell you if something changes. Say 'stop watching' to stop.")
    }

    /**
     * Stop auto-describe mode.
     */
    fun stopAutoDescribe() {
        autoDescribeManager?.stop()
        // Cancel any in-flight Gemini API stream
        geminiService?.cancelStream()
        // Cancel any pending coroutine jobs (e.g. ongoing describe)
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        tts("I stopped watching")
    }

    /**
     * Start navigation mode (5-second interval, safety-first).
     */
    fun startNavigation() {
        if (!ensureInitialized() || !ensureCamera()) return

        autoDescribeManager?.startNavigation()
        tts("Navigation mode started. I'll guide you as you walk. Say 'stop navigation' to stop.")
    }

    /**
     * Stop navigation mode.
     */
    fun stopNavigation() {
        autoDescribeManager?.stop()
        tts("Navigation stopped")
    }

    /**
     * Set the auto-describe interval.
     */
    fun setAutoDescribeInterval(seconds: Int) {
        autoDescribeManager?.setInterval(seconds)
        tts("I'll describe every $seconds seconds")
    }

    /**
     * Cancel the current Gemini stream and stop TTS.
     */
    fun cancelCurrentDescription() {
        geminiService?.cancelStream()
        Log.i(TAG, "Description cancelled")
    }

    /**
     * Check if auto-describe or navigation is active.
     */
    fun isAutoModeActive(): Boolean = autoDescribeManager?.isActive() == true

    // ==================== INTERNAL: STREAMING + TTS ====================

    /**
     * Send image+prompt to Gemini, stream response to TTS, and save to memory.
     */
    private suspend fun streamAndSpeak(
        imageBytes: ByteArray,
        prompt: String,
        faces: List<FaceRecognitionEngine.RecognizedFace>,
        hash: Long
    ) {
        // Rate limit
        val now = System.currentTimeMillis()
        val elapsed = now - lastApiCallTime
        if (elapsed < MIN_API_INTERVAL_MS) {
            delay(MIN_API_INTERVAL_MS - elapsed)
        }
        lastApiCallTime = System.currentTimeMillis()

        val fullResponse = StringBuilder()

        try {
            geminiService?.describeScene(imageBytes, prompt)?.collect { chunk ->
                fullResponse.append(chunk)
                tts(chunk)
            }
            if (fullResponse.isNotBlank()) {
                GeminiModelManager.addVisionLog("✅ Gemini response (${fullResponse.length} chars)")
            }
        } catch (e: Exception) {
            if (fullResponse.isNotBlank()) {
                // Gemini already delivered some text (user heard it via TTS).
                // Don't re-throw — it would trigger the outer catch that says
                // "Something went wrong", creating a double output.
                Log.w(TAG, "Gemini stream error after partial response (${fullResponse.length} chars): ${e.message}")
            } else {
                // No text was received at all — rethrow so the caller can speak the error
                throw e
            }
        }

        val responseText = fullResponse.toString().trim()
        if (responseText.isNotBlank() && responseText != "unchanged") {
            // Save to memory
            memory?.addSummary(
                VisionContextMemory.SceneSummary(
                    timestamp = System.currentTimeMillis(),
                    textSummary = responseText.take(500),
                    peopleDetected = faces.map { it.name },
                    keyObjects = emptyList(), // Extracted from response in future
                    perceptualHash = hash
                )
            )
            memory?.addConversationTurn("model", responseText)
        }
    }

    // ==================== AUTO-DESCRIBE LISTENER ====================

    private val autoDescribeListener = object : AutoDescribeManager.AutoDescribeListener {
        override fun onAutoCaptureTick(isNavigationMode: Boolean) {
            scope.launch {
                try {
                    val bitmap = captureFrameFromActiveCamera() ?: return@launch
                    val hash = computeHash(bitmap)

                    // Check if scene changed
                    if (memory?.hasSceneChanged(hash) != true) {
                        Log.d(TAG, "Auto-tick: scene unchanged — skipping")
                        bitmap.recycle()
                        return@launch
                    }

                    val faces = faceEngine?.detectAndRecognize(bitmap) ?: emptyList()
                    val previousContext = memory?.getPreviousContext() ?: ""
                    val mode = if (isNavigationMode)
                        VisionPromptBuilder.CaptureMode.NAVIGATION
                    else
                        VisionPromptBuilder.CaptureMode.MONITORING

                    val prompt = VisionPromptBuilder.buildPrompt(
                        mode = mode,
                        faces = faces,
                        previousContext = previousContext,
                        respondInHindi = isHindiMode
                    )
                    val imageBytes = compressForApi(bitmap)
                    bitmap.recycle()

                    streamAndSpeak(imageBytes, prompt, faces, hash)
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-describe tick error: ${e.message}", e)
                }
            }
        }

        override fun isTtsSpeaking(): Boolean = this@VisionAssistantOrchestrator.isTtsSpeaking()

        override fun isInCall(): Boolean {
            return phoneStateDetector?.getCurrentState() == PhoneStateDetector.PhoneState.IN_CALL
        }
    }

    // ==================== CLEANUP ====================

    /**
     * Release all vision module resources.
     */
    fun destroy() {
        Log.i(TAG, "Destroying Vision module...")
        autoDescribeManager?.release()
        geminiService?.release()
        faceEngine?.release()
        usbCameraManager?.release()
        builtInCameraManager?.release()
        memory?.clear()
        scope.cancel()
        isInitialized = false
        Log.i(TAG, "Vision module destroyed")
    }
}
