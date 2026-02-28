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
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.sqrt

/**
 * Face Recognition Engine — Fully On-Device
 *
 * Combines:
 *   - Google ML Kit Face Detection (via Play Services, ~0 MB APK impact)
 *   - MobileFaceNet TFLite model (INT8 quantized, ~1.2 MB) for 128-D embeddings
 *   - Local face database in SharedPreferences with cosine similarity matching
 *
 * All face processing is 100% on-device. No face data is ever sent to any server.
 *
 * Recognition flow:
 *   1. ML Kit detects face bounding boxes + classifications (smile, eyes)
 *   2. Each face is cropped, resized to 112x112, normalized to [-1, 1]
 *   3. MobileFaceNet extracts 128-dimensional embedding vector
 *   4. Cosine similarity compared against stored database (threshold 0.72)
 *   5. Results include name, position, clock direction, expression
 */
class FaceRecognitionEngine(private val context: Context) {

    companion object {
        private const val TAG = "Neo_FaceRec"
        private const val MODEL_FILE = "mobilefacenet_int8.tflite"
        private const val DEFAULT_EMBEDDING_SIZE = 192 // MobileFaceNet standard
        private const val INPUT_SIZE = 112
        private const val SIMILARITY_THRESHOLD = 0.72f
        private const val PREFS_NAME = "neo_face_database"
        private const val KEY_FACES = "known_faces"
        private const val MAX_FACES = 100
    }

    data class RecognizedFace(
        val name: String,          // Person name or "Unknown Person"
        val boundingBox: Rect,     // Pixel coordinates in original image
        val clockPosition: String, // e.g., "12 o'clock", "3 o'clock"
        val expression: String,    // "smiling", "neutral", "eyes closed"
        val confidence: Float,     // Cosine similarity score (0-1)
        val horizontalPos: String  // "left side", "center", "right side"
    )

    // ML Kit face detector with classification
    private var faceDetector: FaceDetector? = null

    // TFLite interpreter for MobileFaceNet
    private var interpreter: Interpreter? = null
    private var isModelLoaded = false
    private var embeddingSize = DEFAULT_EMBEDDING_SIZE

    // Face database: name → embedding vector
    private val faceDatabase = mutableMapOf<String, FloatArray>()
    private lateinit var prefs: SharedPreferences

    /**
     * Initialize the engine: load ML Kit detector, TFLite model, and face database.
     * Call this before any detection/recognition.
     */
    fun initialize() {
        // Initialize ML Kit Face Detector
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        faceDetector = FaceDetection.getClient(options)
        Log.i(TAG, "ML Kit face detector initialized")

        // Initialize TFLite interpreter
        try {
            val modelBuffer = loadModelFile()
            if (modelBuffer != null) {
                interpreter = Interpreter(modelBuffer)
                // Auto-detect embedding size from model output shape
                val outputShape = interpreter!!.getOutputTensor(0).shape()
                embeddingSize = outputShape[outputShape.size - 1]
                isModelLoaded = true
                Log.i(TAG, "MobileFaceNet model loaded (${MODEL_FILE}), embedding size=$embeddingSize")
            } else {
                Log.w(TAG, "MobileFaceNet model not found in assets — recognition disabled, detection still works")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${e.message}", e)
            isModelLoaded = false
        }

        // Load face database
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFaceDatabase()
        Log.i(TAG, "Face database loaded: ${faceDatabase.size} known faces")
    }

    /**
     * Detect faces and recognize them against the database.
     *
     * @param bitmap The captured frame from the USB camera
     * @param imageWidth Full image width (for calculating positions)
     * @return List of recognized faces with metadata
     */
    suspend fun detectAndRecognize(bitmap: Bitmap): List<RecognizedFace> {
        val detector = faceDetector ?: run {
            Log.w(TAG, "Face detector not initialized")
            return emptyList()
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = detectFaces(detector, inputImage)
        Log.i(TAG, "Detected ${faces.size} faces")

        if (faces.isEmpty()) return emptyList()

        val results = mutableListOf<RecognizedFace>()
        for (face in faces) {
            val expression = classifyExpression(face)
            val clockPos = computeClockPosition(face.boundingBox, bitmap.width)
            val horizontalPos = computeHorizontalPosition(face.boundingBox, bitmap.width)

            // Try to recognize via embedding if model is loaded
            val (name, confidence) = if (isModelLoaded) {
                recognizeFace(bitmap, face.boundingBox)
            } else {
                "Unknown Person" to 0f
            }

            results.add(
                RecognizedFace(
                    name = name,
                    boundingBox = face.boundingBox,
                    clockPosition = clockPos,
                    expression = expression,
                    confidence = confidence,
                    horizontalPos = horizontalPos
                )
            )
        }

        Log.i(TAG, "Recognition complete: ${results.map { "${it.name}(${it.clockPosition})" }}")
        return results
    }

    /**
     * Register a new face with a name.
     * Captures embedding from the frame and saves to database.
     *
     * @return true if registration succeeded
     */
    suspend fun registerFace(name: String, bitmap: Bitmap): RegisterResult {
        if (!isModelLoaded) {
            return RegisterResult(false, "Face recognition model not loaded")
        }

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
                        return RegisterResult(false, "Face database is full. Maximum $MAX_FACES faces. Delete some first.")
                    }
                    faceDatabase[name] = embedding
                    saveFaceDatabase()
                    Log.i(TAG, "✅ Face registered: $name (${faceDatabase.size} total)")
                    RegisterResult(true, "I'll remember $name's face")
                } else {
                    RegisterResult(false, "Could not extract face features. Please try again.")
                }
            }
        }
    }

    data class RegisterResult(val success: Boolean, val message: String)

    /**
     * Delete a face from the database.
     */
    fun deleteFace(name: String): Boolean {
        val normalizedName = faceDatabase.keys.find { it.equals(name, ignoreCase = true) }
        return if (normalizedName != null) {
            faceDatabase.remove(normalizedName)
            saveFaceDatabase()
            Log.i(TAG, "Face deleted: $normalizedName")
            true
        } else {
            Log.w(TAG, "Face not found: $name")
            false
        }
    }

    /**
     * List all known face names.
     */
    fun listKnownFaces(): List<String> = faceDatabase.keys.toList()

    // ==================== INTERNAL: FACE DETECTION ====================

    /**
     * Detect faces using ML Kit (suspending wrapper).
     */
    private suspend fun detectFaces(detector: FaceDetector, image: InputImage): List<Face> {
        return suspendCoroutine { continuation ->
            detector.process(image)
                .addOnSuccessListener { faces ->
                    continuation.resume(faces)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed: ${e.message}", e)
                    continuation.resume(emptyList())
                }
        }
    }

    // ==================== INTERNAL: FACE RECOGNITION ====================

    /**
     * Recognize a face against the database.
     * Returns (name, confidence) or ("Unknown Person", 0f).
     */
    private fun recognizeFace(bitmap: Bitmap, boundingBox: Rect): Pair<String, Float> {
        val embedding = extractEmbedding(bitmap, boundingBox) ?: return "Unknown Person" to 0f

        var bestName = "Unknown Person"
        var bestSimilarity = 0f

        for ((name, storedEmbedding) in faceDatabase) {
            val similarity = cosineSimilarity(embedding, storedEmbedding)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestName = name
            }
        }

        return if (bestSimilarity >= SIMILARITY_THRESHOLD) {
            Log.d(TAG, "Recognized: $bestName (similarity=${"%.3f".format(bestSimilarity)})")
            bestName to bestSimilarity
        } else {
            Log.d(TAG, "Unknown face (best match: $bestName at ${"%.3f".format(bestSimilarity)})")
            "Unknown Person" to 0f
        }
    }

    /**
     * Extract 128-D embedding from a face crop using MobileFaceNet.
     */
    private fun extractEmbedding(bitmap: Bitmap, boundingBox: Rect): FloatArray? {
        val interp = interpreter ?: return null

        try {
            // Clamp bounding box to image bounds
            val left = boundingBox.left.coerceAtLeast(0)
            val top = boundingBox.top.coerceAtLeast(0)
            val right = boundingBox.right.coerceAtMost(bitmap.width)
            val bottom = boundingBox.bottom.coerceAtMost(bitmap.height)
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return null

            // Crop and resize to 112x112
            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
            val resized = Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)
            if (cropped !== bitmap) cropped.recycle()

            // Normalize pixels to [-1, 1] and create input buffer
            val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())

            for (y in 0 until INPUT_SIZE) {
                for (x in 0 until INPUT_SIZE) {
                    val pixel = resized.getPixel(x, y)
                    inputBuffer.putFloat(((pixel shr 16 and 0xFF) / 127.5f) - 1f) // R
                    inputBuffer.putFloat(((pixel shr 8 and 0xFF) / 127.5f) - 1f)  // G
                    inputBuffer.putFloat(((pixel and 0xFF) / 127.5f) - 1f)         // B
                }
            }
            resized.recycle()

            // Run inference
            val outputBuffer = Array(1) { FloatArray(embeddingSize) }
            interp.run(inputBuffer, outputBuffer)

            // L2 normalize the embedding
            val embedding = outputBuffer[0]
            val norm = sqrt(embedding.map { it * it }.sum())
            if (norm > 0) {
                for (i in embedding.indices) embedding[i] /= norm
            }

            return embedding
        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Cosine similarity between two normalized vectors.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    // ==================== INTERNAL: CLASSIFICATION ====================

    /**
     * Classify face expression from ML Kit's classification data.
     */
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

    /**
     * Compute clock position from horizontal center of face bounding box.
     * Maps 0-100% horizontal position to clock positions (9-3 o'clock).
     */
    private fun computeClockPosition(box: Rect, imageWidth: Int): String {
        val centerX = (box.left + box.right) / 2f
        val ratio = centerX / imageWidth

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

    /**
     * Compute human-friendly horizontal position.
     */
    private fun computeHorizontalPosition(box: Rect, imageWidth: Int): String {
        val centerX = (box.left + box.right) / 2f
        val ratio = centerX / imageWidth

        return when {
            ratio < 0.33 -> "left side"
            ratio < 0.66 -> "center"
            else -> "right side"
        }
    }

    // ==================== INTERNAL: MODEL & DATABASE ====================

    /**
     * Load the TFLite model file from assets.
     */
    private fun loadModelFile(): MappedByteBuffer? {
        return try {
            val fd = context.assets.openFd(MODEL_FILE)
            val inputStream = FileInputStream(fd.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fd.startOffset
            val declaredLength = fd.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.w(TAG, "Model file '$MODEL_FILE' not found in assets: ${e.message}")
            null
        }
    }

    /**
     * Load face database from SharedPreferences.
     */
    private fun loadFaceDatabase() {
        faceDatabase.clear()
        val json = prefs.getString(KEY_FACES, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val embJson = obj.getJSONArray("embedding")
                val embedding = FloatArray(embJson.length()) { embJson.getDouble(it).toFloat() }
                faceDatabase[name] = embedding
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load face database: ${e.message}", e)
        }
    }

    /**
     * Save face database to SharedPreferences.
     */
    private fun saveFaceDatabase() {
        try {
            val arr = JSONArray()
            for ((name, embedding) in faceDatabase) {
                val obj = JSONObject()
                obj.put("name", name)
                val embArr = JSONArray()
                embedding.forEach { embArr.put(it.toDouble()) }
                obj.put("embedding", embArr)
                obj.put("timestamp", System.currentTimeMillis())
                arr.put(obj)
            }
            prefs.edit().putString(KEY_FACES, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save face database: ${e.message}", e)
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        faceDetector?.close()
        faceDetector = null
        interpreter?.close()
        interpreter = null
        isModelLoaded = false
        Log.i(TAG, "FaceRecognitionEngine released")
    }
}
