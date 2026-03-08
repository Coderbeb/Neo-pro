package com.autoapk.automation.vision

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.sqrt

/**
 * Face Recognition Engine — Fully On-Device
 *
 * Features:
 *   - Google ML Kit Face Detection (accurate mode)
 *   - MobileFaceNet TFLite for 128-D embeddings
 *   - Multiple embeddings per person (up to 5 angles)
 *   - Guided multi-shot registration with voice prompts
 *   - Auto-learn: blends new embeddings on high-confidence recognition
 *   - Periodic refresh: tracks staleness and suggests re-registration
 *   - 20% bounding box padding for better embedding quality
 *
 * All face processing is 100% on-device.
 */
class FaceRecognitionEngine(private val context: Context) {

    companion object {
        private const val TAG = "Neo_FaceRec"
        private const val MODEL_FILE = "mobilefacenet_int8.tflite"
        private const val DEFAULT_EMBEDDING_SIZE = 192
        private const val INPUT_SIZE = 112
        private const val SIMILARITY_THRESHOLD = 0.55f
        private const val AUTO_LEARN_THRESHOLD = 0.70f  // Only auto-learn above this confidence
        private const val AUTO_LEARN_WEIGHT = 0.15f      // Blend 15% new + 85% old
        private const val MAX_EMBEDDINGS_PER_PERSON = 5
        private const val STALE_DAYS = 7  // Suggest re-registration after 7 days
        private const val PREFS_NAME = "neo_face_database"
        private const val KEY_FACES = "known_faces"
        private const val MAX_FACES = 100
    }

    data class RecognizedFace(
        val name: String,
        val boundingBox: Rect,
        val clockPosition: String,
        val expression: String,
        val confidence: Float,
        val horizontalPos: String
    )

    data class RegisterResult(val success: Boolean, val message: String)

    /**
     * Internal face record — stores multiple embeddings + metadata.
     */
    private data class FaceRecord(
        val name: String,
        val embeddings: MutableList<FloatArray>,  // Up to MAX_EMBEDDINGS_PER_PERSON
        var lastUpdated: Long = System.currentTimeMillis(),
        var recognitionCount: Int = 0
    )

    private var faceDetector: FaceDetector? = null
    private var interpreter: Interpreter? = null
    private var isModelLoaded = false
    private var embeddingSize = DEFAULT_EMBEDDING_SIZE

    // Face database: name → FaceRecord (multiple embeddings)
    private val faceDatabase = mutableMapOf<String, FaceRecord>()
    private lateinit var prefs: SharedPreferences

    // ==================== INITIALIZATION ====================

    fun initialize() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.10f)
            .build()
        faceDetector = FaceDetection.getClient(options)
        Log.i(TAG, "ML Kit face detector initialized (accurate mode)")

        try {
            val modelBuffer = loadModelFile()
            if (modelBuffer != null) {
                interpreter = Interpreter(modelBuffer)
                val outputShape = interpreter!!.getOutputTensor(0).shape()
                embeddingSize = outputShape[outputShape.size - 1]
                isModelLoaded = true
                Log.i(TAG, "MobileFaceNet loaded, embedding size=$embeddingSize")
            } else {
                Log.w(TAG, "MobileFaceNet model not found — recognition disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${e.message}", e)
            isModelLoaded = false
        }

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFaceDatabase()
        Log.i(TAG, "Face database loaded: ${faceDatabase.size} known faces")
    }

    // ==================== DETECTION & RECOGNITION ====================

    suspend fun detectAndRecognize(bitmap: Bitmap): List<RecognizedFace> {
        val detector = faceDetector ?: return emptyList()
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = detectFaces(detector, inputImage)
        Log.i(TAG, "Detected ${faces.size} faces")
        if (faces.isEmpty()) return emptyList()

        val results = mutableListOf<RecognizedFace>()
        for (face in faces) {
            val expression = classifyExpression(face)
            val clockPos = computeClockPosition(face.boundingBox, bitmap.width)
            val horizontalPos = computeHorizontalPosition(face.boundingBox, bitmap.width)

            val (name, confidence) = if (isModelLoaded) {
                val result = recognizeFace(bitmap, face.boundingBox)
                // Auto-learn: if high confidence, blend new embedding
                if (result.first != "Unknown Person" && result.second >= AUTO_LEARN_THRESHOLD) {
                    autoLearnEmbedding(result.first, bitmap, face.boundingBox)
                }
                result
            } else {
                "Unknown Person" to 0f
            }

            results.add(RecognizedFace(name, face.boundingBox, clockPos, expression, confidence, horizontalPos))
        }

        // Check for stale faces and log
        checkStaleFaces()

        Log.i(TAG, "Recognition: ${results.map { "${it.name}(${it.clockPosition})" }}")
        return results
    }

    // ==================== GUIDED MULTI-SHOT REGISTRATION ====================

    /**
     * Register a face with guided multi-shot capture.
     * Takes 3 frames over ~3 seconds with voice guidance for different angles.
     *
     * @param name The person's name
     * @param captureFrame Callback to capture a new frame from the camera
     * @param tts Callback to speak voice guidance
     * @return RegisterResult with success/failure message
     */
    suspend fun registerFaceGuided(
        name: String,
        captureFrame: suspend () -> Bitmap?,
        tts: (String) -> Unit
    ): RegisterResult {
        if (!isModelLoaded) {
            return RegisterResult(false, "Face recognition model not loaded")
        }

        val detector = faceDetector ?: return RegisterResult(false, "Face detector not initialized")

        if (faceDatabase.size >= MAX_FACES) {
            return RegisterResult(false, "Face database is full. Maximum $MAX_FACES faces. Delete some first.")
        }

        val collectedEmbeddings = mutableListOf<FloatArray>()

        // Shot 1: Straight face
        tts("Look straight at the camera. Capturing now...")
        kotlinx.coroutines.delay(1200)
        val shot1 = captureAndExtract(captureFrame, detector)
        if (shot1 != null) {
            collectedEmbeddings.add(shot1)
            tts("Got it.")
        } else {
            return RegisterResult(false, "I can't see a face clearly. Please face the camera directly.")
        }

        // Shot 2: Slightly turned
        tts("Now turn your head slightly to the right.")
        kotlinx.coroutines.delay(1500)
        val shot2 = captureAndExtract(captureFrame, detector)
        if (shot2 != null) {
            collectedEmbeddings.add(shot2)
            tts("Good.")
        } else {
            Log.w(TAG, "Shot 2 failed — continuing with available shots")
        }

        // Shot 3: Other side
        tts("Now turn slightly to the left.")
        kotlinx.coroutines.delay(1500)
        val shot3 = captureAndExtract(captureFrame, detector)
        if (shot3 != null) {
            collectedEmbeddings.add(shot3)
        } else {
            Log.w(TAG, "Shot 3 failed — continuing with available shots")
        }

        if (collectedEmbeddings.isEmpty()) {
            return RegisterResult(false, "Could not capture any face. Please try again.")
        }

        // Save all embeddings for this person
        val record = FaceRecord(
            name = name,
            embeddings = collectedEmbeddings,
            lastUpdated = System.currentTimeMillis(),
            recognitionCount = 0
        )
        faceDatabase[name] = record
        saveFaceDatabase()

        val shotCount = collectedEmbeddings.size
        Log.i(TAG, "Face registered: $name with $shotCount embeddings (${faceDatabase.size} total)")
        return RegisterResult(true, "I'll remember $name's face. Captured $shotCount angles for better recognition.")
    }

    /**
     * Fallback single-shot registration (when guided isn't possible).
     */
    suspend fun registerFace(name: String, bitmap: Bitmap): RegisterResult {
        if (!isModelLoaded) return RegisterResult(false, "Face recognition model not loaded")
        val detector = faceDetector ?: return RegisterResult(false, "Face detector not initialized")
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = detectFaces(detector, inputImage)

        return when {
            faces.isEmpty() -> RegisterResult(false, "I cannot see any face clearly. Please ask the person to face the camera directly.")
            faces.size > 1 -> RegisterResult(false, "I see ${faces.size} people. Please make sure only $name is in the frame.")
            else -> {
                val face = faces[0]
                val embedding = extractEmbedding(bitmap, face.boundingBox)
                if (embedding != null) {
                    if (faceDatabase.size >= MAX_FACES) {
                        return RegisterResult(false, "Face database is full. Maximum $MAX_FACES faces.")
                    }
                    // Check if person already exists — add embedding to existing
                    val existing = faceDatabase[name]
                    if (existing != null && existing.embeddings.size < MAX_EMBEDDINGS_PER_PERSON) {
                        existing.embeddings.add(embedding)
                        existing.lastUpdated = System.currentTimeMillis()
                    } else {
                        faceDatabase[name] = FaceRecord(
                            name = name,
                            embeddings = mutableListOf(embedding),
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                    saveFaceDatabase()
                    Log.i(TAG, "Face registered: $name (${faceDatabase.size} total)")
                    RegisterResult(true, "I'll remember $name's face")
                } else {
                    RegisterResult(false, "Could not extract face features. Please try again.")
                }
            }
        }
    }

    // ==================== AUTO-LEARN ====================

    /**
     * When a person is recognized with high confidence, blend the new embedding
     * into their stored embeddings. This improves recognition over time as the
     * system sees the person under different conditions.
     */
    private fun autoLearnEmbedding(name: String, bitmap: Bitmap, boundingBox: Rect) {
        val record = faceDatabase[name] ?: return
        val newEmbedding = extractEmbedding(bitmap, boundingBox) ?: return

        record.recognitionCount++

        if (record.embeddings.size < MAX_EMBEDDINGS_PER_PERSON) {
            // Still room — check if this angle is different enough to be useful
            val maxSimilarity = record.embeddings.maxOf { cosineSimilarity(newEmbedding, it) }
            if (maxSimilarity < 0.85f) {
                // This is a meaningfully different angle/lighting — add it
                record.embeddings.add(newEmbedding)
                record.lastUpdated = System.currentTimeMillis()
                saveFaceDatabase()
                Log.i(TAG, "Auto-learned new angle for $name (${record.embeddings.size} embeddings)")
            }
        } else {
            // Blend into the least similar existing embedding
            val (leastIdx, _) = record.embeddings.withIndex()
                .minByOrNull { (_, emb) -> cosineSimilarity(newEmbedding, emb) } ?: return
            val old = record.embeddings[leastIdx]
            val blended = FloatArray(old.size)
            for (i in old.indices) {
                blended[i] = old[i] * (1f - AUTO_LEARN_WEIGHT) + newEmbedding[i] * AUTO_LEARN_WEIGHT
            }
            // Re-normalize
            val norm = sqrt(blended.map { it * it }.sum())
            if (norm > 0) for (i in blended.indices) blended[i] /= norm
            record.embeddings[leastIdx] = blended
            record.lastUpdated = System.currentTimeMillis()

            // Save periodically (every 10 recognitions) to avoid excessive IO
            if (record.recognitionCount % 10 == 0) {
                saveFaceDatabase()
                Log.i(TAG, "Auto-learn blended embedding for $name (count=${record.recognitionCount})")
            }
        }
    }

    // ==================== STALENESS CHECK ====================

    /**
     * Check for faces that haven't been updated recently.
     * Returns names of stale faces (for potential TTS notification).
     */
    fun checkStaleFaces(): List<String> {
        val staleMs = STALE_DAYS * 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val stale = faceDatabase.filter { (_, record) ->
            now - record.lastUpdated > staleMs
        }.keys.toList()
        if (stale.isNotEmpty()) {
            Log.i(TAG, "Stale faces (not updated in $STALE_DAYS days): $stale")
        }
        return stale
    }

    // ==================== FACE MANAGEMENT ====================

    fun deleteFace(name: String): Boolean {
        val normalizedName = faceDatabase.keys.find { it.equals(name, ignoreCase = true) }
        return if (normalizedName != null) {
            faceDatabase.remove(normalizedName)
            saveFaceDatabase()
            Log.i(TAG, "Face deleted: $normalizedName")
            true
        } else false
    }

    fun listKnownFaces(): List<String> = faceDatabase.keys.toList()

    /**
     * Get face details for diagnostics.
     */
    fun getFaceInfo(name: String): String? {
        val record = faceDatabase[name] ?: return null
        val daysSinceUpdate = (System.currentTimeMillis() - record.lastUpdated) / (24 * 60 * 60 * 1000)
        return "$name: ${record.embeddings.size} angles, recognized ${record.recognitionCount} times, updated $daysSinceUpdate days ago"
    }

    // ==================== INTERNAL: DETECTION ====================

    private suspend fun detectFaces(detector: FaceDetector, image: InputImage): List<Face> {
        return suspendCoroutine { continuation ->
            detector.process(image)
                .addOnSuccessListener { faces -> continuation.resume(faces) }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed: ${e.message}", e)
                    continuation.resume(emptyList())
                }
        }
    }

    // ==================== INTERNAL: RECOGNITION ====================

    /**
     * Recognize a face against all stored embeddings.
     * Compares against ALL embeddings for each person, uses the best score.
     */
    private fun recognizeFace(bitmap: Bitmap, boundingBox: Rect): Pair<String, Float> {
        val embedding = extractEmbedding(bitmap, boundingBox) ?: return "Unknown Person" to 0f

        var bestName = "Unknown Person"
        var bestSimilarity = 0f

        for ((name, record) in faceDatabase) {
            // Compare against ALL embeddings for this person
            val maxSim = record.embeddings.maxOfOrNull { cosineSimilarity(embedding, it) } ?: 0f
            if (maxSim > bestSimilarity) {
                bestSimilarity = maxSim
                bestName = name
            }
        }

        return if (bestSimilarity >= SIMILARITY_THRESHOLD) {
            Log.d(TAG, "Recognized: $bestName (similarity=${"%.3f".format(bestSimilarity)})")
            bestName to bestSimilarity
        } else {
            Log.d(TAG, "Unknown face (best: $bestName at ${"%.3f".format(bestSimilarity)})")
            "Unknown Person" to 0f
        }
    }

    /**
     * Extract embedding from a single capture callback.
     */
    private suspend fun captureAndExtract(
        captureFrame: suspend () -> Bitmap?,
        detector: FaceDetector
    ): FloatArray? {
        val bitmap = captureFrame() ?: return null
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = detectFaces(detector, inputImage)

        if (faces.isEmpty() || faces.size > 1) {
            bitmap.recycle()
            return null
        }

        val embedding = extractEmbedding(bitmap, faces[0].boundingBox)
        bitmap.recycle()
        return embedding
    }

    /**
     * Extract embedding with 20% padding around the face for context.
     */
    private fun extractEmbedding(bitmap: Bitmap, boundingBox: Rect): FloatArray? {
        val interp = interpreter ?: return null

        try {
            val left = boundingBox.left.coerceAtLeast(0)
            val top = boundingBox.top.coerceAtLeast(0)
            val right = boundingBox.right.coerceAtMost(bitmap.width)
            val bottom = boundingBox.bottom.coerceAtMost(bitmap.height)
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return null

            // 20% padding for better embeddings
            val padW = (width * 0.20).toInt()
            val padH = (height * 0.20).toInt()
            val padLeft = (left - padW).coerceAtLeast(0)
            val padTop = (top - padH).coerceAtLeast(0)
            val padRight = (right + padW).coerceAtMost(bitmap.width)
            val padBottom = (bottom + padH).coerceAtMost(bitmap.height)
            val padWidth = padRight - padLeft
            val padHeight = padBottom - padTop
            if (padWidth <= 0 || padHeight <= 0) return null

            val cropped = Bitmap.createBitmap(bitmap, padLeft, padTop, padWidth, padHeight)
            val resized = Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)
            if (cropped !== bitmap) cropped.recycle()

            // Normalize to [-1, 1]
            val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    val pixel = resized.getPixel(x, y)
                    inputBuffer.putFloat(((pixel shr 16 and 0xFF) / 127.5f) - 1f)
                    inputBuffer.putFloat(((pixel shr 8 and 0xFF) / 127.5f) - 1f)
                    inputBuffer.putFloat(((pixel and 0xFF) / 127.5f) - 1f)
                }
            }
            resized.recycle()

            val outputBuffer = Array(1) { FloatArray(embeddingSize) }
            interp.run(inputBuffer, outputBuffer)

            // L2 normalize
            val embedding = outputBuffer[0]
            val norm = sqrt(embedding.map { it * it }.sum())
            if (norm > 0) for (i in embedding.indices) embedding[i] /= norm

            return embedding
        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction failed: ${e.message}", e)
            return null
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    // ==================== CLASSIFICATION ====================

    private fun classifyExpression(face: Face): String {
        val smileProb = face.smilingProbability ?: -1f
        val leftEyeOpen = face.leftEyeOpenProbability ?: -1f
        val rightEyeOpen = face.rightEyeOpenProbability ?: -1f
        return when {
            leftEyeOpen >= 0 && rightEyeOpen >= 0 &&
                leftEyeOpen < 0.3f && rightEyeOpen < 0.3f -> "eyes closed"
            smileProb >= 0.7f -> "smiling"
            smileProb >= 0.3f -> "slight smile"
            else -> "neutral"
        }
    }

    private fun computeClockPosition(box: Rect, imageWidth: Int): String {
        val ratio = (box.left + box.right) / 2f / imageWidth
        return when {
            ratio < 0.15 -> "9 o'clock"
            ratio < 0.30 -> "10 o'clock"
            ratio < 0.45 -> "11 o'clock"
            ratio < 0.55 -> "12 o'clock"
            ratio < 0.70 -> "1 o'clock"
            ratio < 0.85 -> "2 o'clock"
            else -> "3 o'clock"
        }
    }

    private fun computeHorizontalPosition(box: Rect, imageWidth: Int): String {
        val ratio = (box.left + box.right) / 2f / imageWidth
        return when {
            ratio < 0.33 -> "left side"
            ratio < 0.66 -> "center"
            else -> "right side"
        }
    }

    // ==================== DATABASE PERSISTENCE ====================

    /**
     * Save face database with multiple embeddings per person.
     * Format: [{name, embeddings:[[...],[...]], lastUpdated, recognitionCount}]
     */
    private fun saveFaceDatabase() {
        try {
            val arr = JSONArray()
            for ((_, record) in faceDatabase) {
                val obj = JSONObject()
                obj.put("name", record.name)
                val embsArr = JSONArray()
                for (emb in record.embeddings) {
                    val embArr = JSONArray()
                    emb.forEach { embArr.put(it.toDouble()) }
                    embsArr.put(embArr)
                }
                obj.put("embeddings", embsArr)
                obj.put("lastUpdated", record.lastUpdated)
                obj.put("recognitionCount", record.recognitionCount)
                arr.put(obj)
            }
            prefs.edit().putString(KEY_FACES, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save face database: ${e.message}", e)
        }
    }

    /**
     * Load face database — supports both old (single embedding) and new (multi-embedding) format.
     */
    private fun loadFaceDatabase() {
        faceDatabase.clear()
        val json = prefs.getString(KEY_FACES, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")

                val embeddings = mutableListOf<FloatArray>()

                // New format: "embeddings" is array of arrays
                if (obj.has("embeddings")) {
                    val embsArr = obj.getJSONArray("embeddings")
                    for (j in 0 until embsArr.length()) {
                        val embArr = embsArr.getJSONArray(j)
                        embeddings.add(FloatArray(embArr.length()) { embArr.getDouble(it).toFloat() })
                    }
                }
                // Legacy format: "embedding" is single array
                else if (obj.has("embedding")) {
                    val embArr = obj.getJSONArray("embedding")
                    embeddings.add(FloatArray(embArr.length()) { embArr.getDouble(it).toFloat() })
                }

                val lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
                val recognitionCount = obj.optInt("recognitionCount", 0)

                if (embeddings.isNotEmpty()) {
                    faceDatabase[name] = FaceRecord(name, embeddings, lastUpdated, recognitionCount)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load face database: ${e.message}", e)
        }
    }

    // ==================== MODEL LOADING ====================

    private fun loadModelFile(): MappedByteBuffer? {
        return try {
            val fd = context.assets.openFd(MODEL_FILE)
            val inputStream = FileInputStream(fd.fileDescriptor)
            val fileChannel = inputStream.channel
            fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        } catch (e: Exception) {
            Log.w(TAG, "Model file '$MODEL_FILE' not found: ${e.message}")
            null
        }
    }

    fun release() {
        faceDetector?.close()
        faceDetector = null
        interpreter?.close()
        interpreter = null
        isModelLoaded = false
        Log.i(TAG, "FaceRecognitionEngine released")
    }
}
