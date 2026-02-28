package com.autoapk.automation.vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Built-in Camera Manager — captures frames using the device's rear camera (Camera2 API).
 *
 * Used as an alternative to USB OTG camera when the user enables
 * "Use Built-in Camera" in the UI. Designed for the same interface as
 * UsbCameraManager so the Orchestrator can swap between them seamlessly.
 *
 * Features:
 *   - Opens rear camera, captures a single JPEG frame, returns Bitmap
 *   - Auto-closes camera after capture to save battery
 *   - Thread-safe with background HandlerThread
 *   - Falls back to front camera if rear not found
 */
class BuiltInCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "Neo_BuiltInCam"
        private const val CAPTURE_TIMEOUT_SECONDS = 5L
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var isInitialized = false

    /**
     * Initialize the background thread.
     */
    fun initialize() {
        startBackgroundThread()
        isInitialized = true
        Log.i(TAG, "BuiltInCameraManager initialized")
    }

    /**
     * Always "connected" since built-in cameras are always available.
     */
    fun isConnected(): Boolean {
        if (!isInitialized) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Capture a single frame from the rear camera and return it as a Bitmap.
     * The camera is opened, one frame is captured, and the camera is closed.
     * This is a BLOCKING call — must be called from a coroutine/background thread.
     *
     * @return Bitmap at camera resolution, or null on failure
     */
    fun captureFrame(): Bitmap? {
        if (!isConnected()) {
            Log.w(TAG, "Camera permission not granted")
            return null
        }

        val latch = CountDownLatch(1)
        var resultBitmap: Bitmap? = null

        try {
            val cameraId = getRearCameraId() ?: getFrontCameraId()
            if (cameraId == null) {
                Log.e(TAG, "No camera found")
                return null
            }

            // Get optimal size for vision (we don't need full resolution)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)

            // Find a size close to 640x480 for fast processing
            val targetSize = jpegSizes?.minByOrNull {
                Math.abs(it.width * it.height - 640 * 480)
            } ?: jpegSizes?.lastOrNull()

            val width = targetSize?.width ?: 640
            val height = targetSize?.height ?: 480

            Log.d(TAG, "Capturing at ${width}x${height}")

            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            imageReader!!.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        // Decode JPEG to Bitmap
                        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                        // Apply rotation based on camera sensor orientation
                        val sensorOrientation = characteristics.get(
                            CameraCharacteristics.SENSOR_ORIENTATION
                        ) ?: 0
                        if (sensorOrientation != 0) {
                            val matrix = Matrix()
                            matrix.postRotate(sensorOrientation.toFloat())
                            val rotated = Bitmap.createBitmap(
                                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                            )
                            if (rotated !== bitmap) bitmap.recycle()
                            bitmap = rotated
                        }

                        resultBitmap = bitmap
                        Log.d(TAG, "Frame captured: ${bitmap.width}x${bitmap.height}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading image: ${e.message}", e)
                } finally {
                    latch.countDown()
                }
            }, backgroundHandler)

            // Open camera
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission missing")
                return null
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val captureBuilder = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_STILL_CAPTURE
                        )
                        captureBuilder.addTarget(imageReader!!.surface)
                        captureBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        captureBuilder.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON
                        )

                        camera.createCaptureSession(
                            listOf(imageReader!!.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(
                                            captureBuilder.build(),
                                            object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) {
                                                    // Image will be delivered via ImageReader listener
                                                    // Close camera after a short delay to let image arrive
                                                    backgroundHandler?.postDelayed({
                                                        closeCamera()
                                                    }, 200)
                                                }
                                            },
                                            backgroundHandler
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Capture request failed: ${e.message}", e)
                                        latch.countDown()
                                        closeCamera()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Camera session config failed")
                                    latch.countDown()
                                    closeCamera()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Create capture session failed: ${e.message}", e)
                        latch.countDown()
                        closeCamera()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    latch.countDown()
                }
            }, backgroundHandler)

            // Wait for capture to complete
            latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        } catch (e: Exception) {
            Log.e(TAG, "captureFrame failed: ${e.message}", e)
        }

        return resultBitmap
    }

    // ==================== CAMERA ID HELPERS ====================

    private fun getRearCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding rear camera: ${e.message}")
            null
        }
    }

    private fun getFrontCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding front camera: ${e.message}")
            null
        }
    }

    // ==================== LIFECYCLE ====================

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("NeoVisionCamThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun closeCamera() {
        try {
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera: ${e.message}")
        }
    }

    fun release() {
        closeCamera()
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
        } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
        isInitialized = false
        Log.i(TAG, "BuiltInCameraManager released")
    }
}
