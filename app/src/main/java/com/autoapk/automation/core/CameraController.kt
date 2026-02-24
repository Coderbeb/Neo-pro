package com.autoapk.automation.core

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera Controller
 * 
 * Manages camera operations using Camera2 API.
 * Features:
 * - Take photo (back/front)
 * - Record video
 * - Toggle flash (torch mode)
 * - Zoom control
 * - Face detection (via characteristics check)
 * - Countdown timer
 * 
 * Requirements: 3.9, 3.10
 */
class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "CameraController"
    }

    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var currentCameraId: String? = null
    private var isFlashOn = false
    private var isRecording = false

    interface CameraListener {
        fun onPhotoCaptured(path: String)
        fun onVideoRecordingStarted()
        fun onVideoRecordingStopped(path: String)
        fun onError(message: String)
        fun onMessage(message: String)
    }

    var listener: CameraListener? = null

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    /**
     * Take a photo with optional countdown
     */
    fun takePhoto(useFrontCamera: Boolean = false, countdownSeconds: Int = 0) {
        if (countdownSeconds > 0) {
            startCountdown(countdownSeconds) {
                capturePhotoInternal(useFrontCamera)
            }
        } else {
            capturePhotoInternal(useFrontCamera)
        }
    }

    private fun startCountdown(seconds: Int, onComplete: () -> Unit) {
        val handler = Handler(context.mainLooper)
        for (i in seconds downTo 1) {
            handler.postDelayed({
                listener?.onMessage("$i")
            }, ((seconds - i) * 1000).toLong())
        }
        handler.postDelayed({
            onComplete()
        }, (seconds * 1000).toLong())
    }

    private fun capturePhotoInternal(useFrontCamera: Boolean) {
        try {
            val cameraId = getCameraId(useFrontCamera)
            if (cameraId == null) {
                listener?.onError("Camera not found")
                return
            }

            if (cameraDevice == null || currentCameraId != cameraId) {
                closeCamera()
                openCamera(cameraId) {
                    takePicture()
                }
            } else {
                takePicture()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo", e)
            listener?.onError("Error taking photo: ${e.message}")
        }
    }

    private fun getCameraId(useFrontCamera: Boolean): String? {
        val facing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) {
                return id
            }
        }
        return null
    }

    private fun openCamera(cameraId: String, onOpened: () -> Unit) {
        try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                listener?.onError("Camera permission required")
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    currentCameraId = cameraId
                    onOpened()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDevice?.close()
                    cameraDevice = null
                    listener?.onError("Camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            listener?.onError("Could not open camera: ${e.message}")
        }
    }

    private fun takePicture() {
        if (cameraDevice == null) return

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
            val jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
            
            val width = jpegSizes?.get(0)?.width ?: 640
            val height = jpegSizes?.get(0)?.height ?: 480

            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            val outputSurfaces = listOf(imageReader!!.surface)

            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            // Orientation
            val rotation = 90 // Default
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotation)

            val file = createImageFile()
            
            val readerListener = ImageReader.OnImageAvailableListener { reader ->
                try {
                    val image = reader.acquireLatestImage()
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    save(bytes, file)
                    image.close()
                    listener?.onPhotoCaptured(file.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving image", e)
                    listener?.onError("Failed to save photo")
                }
            }
            imageReader!!.setOnImageAvailableListener(readerListener, backgroundHandler)

            cameraDevice!!.createCaptureSession(outputSurfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                super.onCaptureCompleted(session, request, result)
                                closeCamera() // Close after capture to release resource
                            }
                        }, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture failed", e)
                        listener?.onError("Capture failed: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    listener?.onError("Camera configuration failed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error taking picture", e)
            listener?.onError("Error taking picture: ${e.message}")
        }
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    private fun save(bytes: ByteArray, file: File) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
        }
        // Scan the file so it shows up in Gallery immediately
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null
        ) { path, uri ->
            Log.i(TAG, "Scanned $path: -> $uri")
        }
    }

    fun closeCamera() {
        try {
            if (isRecording) {
                try {
                    mediaRecorder?.stop()
                } catch (_: Exception) {}
                isRecording = false
            }
            mediaRecorder?.release()
            mediaRecorder = null
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
    
    /**
     * Toggle flash (torch mode)
     */
    fun toggleFlash(enable: Boolean) {
        try {
            val cameraId = getCameraId(false) // Use back camera for flash
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                isFlashOn = enable
                listener?.onMessage(if (enable) "Flashlight on" else "Flashlight off")
            } else {
                listener?.onError("No flash available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flash", e)
            listener?.onError("Error toggling flash: ${e.message}")
        }
    }

    // ==================== VIDEO RECORDING ====================

    /**
     * Start video recording using Camera2 API + MediaRecorder.
     * Records from the back camera at 720p with AAC audio.
     */
    fun startVideoRecording(useFrontCamera: Boolean = false) {
        if (isRecording) {
            listener?.onMessage("Already recording")
            return
        }

        try {
            val cameraId = getCameraId(useFrontCamera)
            if (cameraId == null) {
                listener?.onError("Camera not found")
                return
            }

            closeCamera() // Close any existing session
            
            val videoFile = createVideoFile()
            
            // Setup MediaRecorder
            @Suppress("DEPRECATION")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoFile.absolutePath)
                setVideoEncodingBitRate(5_000_000) // 5 Mbps
                setVideoFrameRate(30)
                setVideoSize(1280, 720) // 720p
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)
                prepare()
            }

            // Open camera and start recording
            openCamera(cameraId) {
                try {
                    val recorderSurface = mediaRecorder!!.surface
                    val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    captureBuilder.addTarget(recorderSurface)

                    cameraDevice!!.createCaptureSession(
                        listOf(recorderSurface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                cameraCaptureSession = session
                                try {
                                    captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    session.setRepeatingRequest(captureBuilder.build(), null, backgroundHandler)
                                    
                                    mediaRecorder?.start()
                                    isRecording = true
                                    listener?.onVideoRecordingStarted()
                                    Log.i(TAG, "Video recording started: ${videoFile.absolutePath}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error starting recording", e)
                                    listener?.onError("Failed to start recording: ${e.message}")
                                    cleanupRecording()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                listener?.onError("Camera configuration failed for video")
                                cleanupRecording()
                            }
                        },
                        backgroundHandler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up video capture session", e)
                    listener?.onError("Error setting up video: ${e.message}")
                    cleanupRecording()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting video recording", e)
            listener?.onError("Error starting video: ${e.message}")
            cleanupRecording()
        }
    }

    /**
     * Stop video recording and save the file.
     */
    fun stopVideoRecording() {
        if (!isRecording) {
            listener?.onMessage("Not recording")
            return
        }

        try {
            cameraCaptureSession?.stopRepeating()
            cameraCaptureSession?.abortCaptures()
        } catch (_: Exception) {}

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }

        isRecording = false

        // Get the video file path before cleanup
        val videoFile = getLastVideoFile()
        closeCamera()

        if (videoFile != null && videoFile.exists() && videoFile.length() > 0) {
            // Scan to Gallery
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(videoFile.absolutePath),
                arrayOf("video/mp4")
            ) { path, uri ->
                Log.i(TAG, "Video scanned: $path -> $uri")
            }
            listener?.onVideoRecordingStopped(videoFile.absolutePath)
            Log.i(TAG, "Video saved: ${videoFile.absolutePath}")
        } else {
            listener?.onError("Video file may be empty or missing")
        }
    }

    private fun cleanupRecording() {
        try {
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
        } catch (_: Exception) {}
    }

    private fun createVideoFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return File(storageDir, "VID_${timeStamp}.mp4")
    }

    private var lastVideoFilePath: String? = null

    private fun getLastVideoFile(): File? {
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
        return storageDir.listFiles()?.maxByOrNull { it.lastModified() }
    }
}
