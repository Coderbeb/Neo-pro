package com.autoapk.automation.vision

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * USB OTG Camera Manager
 *
 * Manages USB UVC camera connection, frame capture, and image processing.
 * Uses Android's USB Host API for camera detection + permission handling.
 *
 * Frame capture approach:
 *   1. Open UsbDeviceConnection and claim the UVC Video Streaming interface
 *   2. Find a bulk IN endpoint on the streaming interface
 *   3. Start a background thread that reads MJPEG frames from the endpoint
 *   4. Decode MJPEG to Bitmap and store the latest frame
 *   5. captureFrame() returns the most recent decoded frame
 *
 * Features:
 *   - Detect and connect to USB UVC cameras
 *   - Capture single frame via USB bulk transfer
 *   - Compress for API: downscale to 768x576, JPEG 80%
 *   - Perceptual hash for scene-change detection
 *   - USB connect/disconnect broadcast receiver
 */
class UsbCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "Neo_UsbCam"
        private const val ACTION_USB_PERMISSION = "com.autoapk.automation.USB_PERMISSION"

        // Capture resolution
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480

        // API compression settings — higher quality for better Gemini vision results
        private const val API_WIDTH = 768
        private const val API_HEIGHT = 576
        private const val JPEG_QUALITY = 80

        // Perceptual hash size
        private const val HASH_SIZE = 8

        // USB Video Class constants
        private const val USB_CLASS_VIDEO = 14          // 0x0E
        private const val USB_SUBCLASS_STREAMING = 2    // Video Streaming
        private const val USB_SUBCLASS_CONTROL = 1      // Video Control

        // MJPEG marker bytes
        private const val MJPEG_START_MARKER_1: Byte = 0xFF.toByte()
        private const val MJPEG_START_MARKER_2: Byte = 0xD8.toByte()
        private const val MJPEG_END_MARKER_1: Byte = 0xFF.toByte()
        private const val MJPEG_END_MARKER_2: Byte = 0xD9.toByte()

        // USB transfer settings
        private const val TRANSFER_TIMEOUT_MS = 1000
        private const val MAX_PACKET_SIZE = 16384  // 16KB per bulk read
        private const val MAX_FRAME_SIZE = 512 * 1024  // 512KB max frame
    }

    interface CameraListener {
        fun onCameraConnected()
        fun onCameraDisconnected()
        fun onError(message: String)
    }

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var cameraDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var streamingInterface: UsbInterface? = null
    private var bulkEndpoint: UsbEndpoint? = null
    private var isConnected = false
    private var listener: CameraListener? = null

    // Frame capture
    private var capturedFrame: Bitmap? = null
    private val frameLock = Object()

    // Background frame reader
    private var frameReaderThread: Thread? = null
    @Volatile private var isStreaming = false

    fun setListener(l: CameraListener) { listener = l }
    fun isConnected(): Boolean = isConnected

    // ==================== USB PERMISSION RECEIVER ====================

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        Log.i(TAG, "USB permission granted for ${device.deviceName}")
                        openCamera(device)
                    } else {
                        Log.w(TAG, "USB permission denied")
                        listener?.onError("USB camera permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device == cameraDevice) {
                        Log.i(TAG, "USB camera detached")
                        disconnect()
                        listener?.onCameraDisconnected()
                    }
                }
            }
        }
    }

    /**
     * Initialize and register broadcast receivers.
     */
    fun initialize() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        Log.i(TAG, "USB receiver registered")
    }

    /**
     * Scan for connected USB cameras and request permission.
     * Returns true if a camera was found (permission dialog may still show).
     */
    fun connect(): Boolean {
        val deviceList = usbManager.deviceList
        Log.i(TAG, "Scanning USB devices: ${deviceList.size} found")

        for ((_, device) in deviceList) {
            if (isUvcCamera(device)) {
                Log.i(TAG, "Found UVC camera: ${device.deviceName} (VID=${device.vendorId}, PID=${device.productId})")

                if (usbManager.hasPermission(device)) {
                    openCamera(device)
                    return true
                } else {
                    // Request permission — result comes via broadcast
                    val pi = PendingIntent.getBroadcast(
                        context, 0,
                        Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    usbManager.requestPermission(device, pi)
                    Log.i(TAG, "Requested permission for ${device.deviceName}")
                    return true
                }
            }
        }

        Log.w(TAG, "No UVC camera found")
        return false
    }

    /**
     * Check if a USB device is a UVC (Video Class) camera.
     * UVC devices have interface class 0x0E (Video).
     */
    private fun isUvcCamera(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // USB class 14 = Video (0x0E)
            if (iface.interfaceClass == USB_CLASS_VIDEO) return true
        }
        // Also check device class
        return device.deviceClass == USB_CLASS_VIDEO || device.deviceClass == 239 // 0xEF = Misc (IAD)
    }

    /**
     * Open the camera device, claim the UVC streaming interface,
     * find the bulk endpoint, and start reading frames.
     */
    private fun openCamera(device: UsbDevice) {
        try {
            cameraDevice = device

            // Step 1: Open USB device connection
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.e(TAG, "Failed to open USB device connection")
                listener?.onError("Failed to open camera connection")
                return
            }
            usbConnection = connection
            Log.i(TAG, "USB device connection opened: ${device.deviceName}")

            // Step 2: Find and claim the Video Streaming interface
            var streamIface: UsbInterface? = null
            var bulkIn: UsbEndpoint? = null

            // First pass: look for Video Streaming interface with bulk IN endpoint
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                Log.d(TAG, "Interface $i: class=${iface.interfaceClass}, subclass=${iface.interfaceSubclass}, endpoints=${iface.endpointCount}")

                if (iface.interfaceClass == USB_CLASS_VIDEO) {
                    // Look for an endpoint we can read from
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        Log.d(TAG, "  Endpoint $e: type=${ep.type}, dir=${ep.direction}, maxPacket=${ep.maxPacketSize}")
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                // Bulk IN — best option
                                streamIface = iface
                                bulkIn = ep
                                Log.i(TAG, "Found BULK IN endpoint on interface $i")
                                break
                            } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && bulkIn == null) {
                                // Isochronous IN — fallback option
                                streamIface = iface
                                bulkIn = ep
                                Log.i(TAG, "Found ISOCHRONOUS IN endpoint on interface $i")
                            }
                        }
                    }
                    if (bulkIn?.type == UsbConstants.USB_ENDPOINT_XFER_BULK) break // prefer bulk
                }
            }

            // Second pass: if no video-class endpoint found, try ANY interface with IN endpoint
            if (bulkIn == null) {
                Log.w(TAG, "No UVC streaming endpoint found, trying any IN endpoint...")
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_IN &&
                            (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK || ep.type == UsbConstants.USB_ENDPOINT_XFER_INT)) {
                            streamIface = iface
                            bulkIn = ep
                            Log.i(TAG, "Using fallback endpoint on interface $i (type=${ep.type})")
                            break
                        }
                    }
                    if (bulkIn != null) break
                }
            }

            if (streamIface == null || bulkIn == null) {
                Log.e(TAG, "No readable endpoint found on USB camera")
                // Still set connected so isConnected() returns true — some cameras need
                // alternate settings which require control transfers we'll handle below
                isConnected = true
                listener?.onCameraConnected()
                return
            }

            // Step 3: Claim the interface
            val claimed = connection.claimInterface(streamIface, true)
            if (!claimed) {
                Log.e(TAG, "Failed to claim interface ${streamIface.id}")
                // Try force claim
                connection.claimInterface(streamIface, true)
            }
            Log.i(TAG, "Claimed interface ${streamIface.id} (class=${streamIface.interfaceClass}, subclass=${streamIface.interfaceSubclass})")

            streamingInterface = streamIface
            bulkEndpoint = bulkIn
            isConnected = true

            // Step 4: Start background frame reader
            startFrameReader(connection, bulkIn)

            listener?.onCameraConnected()
            Log.i(TAG, "✅ Camera opened and streaming started: ${device.deviceName}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
            isConnected = false
            listener?.onError("Failed to open camera: ${e.message}")
        }
    }

    /**
     * Start background thread that continuously reads frames from the USB endpoint.
     * Reads raw bytes, finds MJPEG frame boundaries (FFD8...FFD9),
     * decodes to Bitmap, and stores the latest frame.
     */
    private fun startFrameReader(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        isStreaming = true

        frameReaderThread = Thread({
            Log.i(TAG, "Frame reader thread started (endpoint type=${endpoint.type}, maxPacket=${endpoint.maxPacketSize})")

            val readBuffer = ByteArray(MAX_PACKET_SIZE)
            val frameBuffer = ByteArrayOutputStream(MAX_FRAME_SIZE)
            var inFrame = false
            var frameCount = 0
            var errorCount = 0
            val maxConsecutiveErrors = 50

            while (isStreaming) {
                try {
                    val bytesRead = connection.bulkTransfer(
                        endpoint, readBuffer, readBuffer.size, TRANSFER_TIMEOUT_MS
                    )

                    if (bytesRead > 0) {
                        errorCount = 0  // Reset on successful read

                        // Scan for MJPEG frame boundaries
                        for (i in 0 until bytesRead) {
                            val b = readBuffer[i]

                            if (!inFrame) {
                                // Look for JPEG start marker: FF D8
                                if (i < bytesRead - 1 &&
                                    readBuffer[i] == MJPEG_START_MARKER_1 &&
                                    readBuffer[i + 1] == MJPEG_START_MARKER_2) {
                                    inFrame = true
                                    frameBuffer.reset()
                                    frameBuffer.write(MJPEG_START_MARKER_1.toInt())
                                    frameBuffer.write(MJPEG_START_MARKER_2.toInt())
                                    // Skip next byte since we already consumed it
                                }
                            } else {
                                frameBuffer.write(b.toInt())

                                // Look for JPEG end marker: FF D9
                                if (frameBuffer.size() >= 4 &&
                                    i > 0 &&
                                    readBuffer[i - 1] == MJPEG_END_MARKER_1 &&
                                    readBuffer[i] == MJPEG_END_MARKER_2) {
                                    // Complete JPEG frame found!
                                    inFrame = false
                                    val jpegBytes = frameBuffer.toByteArray()

                                    if (jpegBytes.size > 100) { // Sanity check: valid JPEG > 100 bytes
                                        try {
                                            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                            if (bitmap != null) {
                                                onFrameAvailable(bitmap)
                                                frameCount++
                                                if (frameCount % 30 == 1) {
                                                    Log.d(TAG, "Frame #$frameCount captured: ${bitmap.width}x${bitmap.height} (${jpegBytes.size} bytes)")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "JPEG decode failed (${jpegBytes.size} bytes): ${e.message}")
                                        }
                                    }
                                    frameBuffer.reset()
                                }

                                // Safety: prevent unbounded frame buffer
                                if (frameBuffer.size() > MAX_FRAME_SIZE) {
                                    Log.w(TAG, "Frame buffer overflow — resetting")
                                    frameBuffer.reset()
                                    inFrame = false
                                }
                            }
                        }
                    } else if (bytesRead == 0) {
                        // No data — brief pause to avoid busy loop
                        Thread.sleep(10)
                    } else {
                        // Error
                        errorCount++
                        if (errorCount >= maxConsecutiveErrors) {
                            Log.e(TAG, "Too many consecutive read errors ($errorCount) — stopping reader")
                            break
                        }
                        Thread.sleep(50)
                    }
                } catch (e: InterruptedException) {
                    Log.i(TAG, "Frame reader interrupted — stopping")
                    break
                } catch (e: Exception) {
                    errorCount++
                    Log.e(TAG, "Frame reader error: ${e.message}")
                    if (errorCount >= maxConsecutiveErrors) {
                        Log.e(TAG, "Too many errors — stopping reader")
                        break
                    }
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }

            isStreaming = false
            Log.i(TAG, "Frame reader thread stopped (total frames: $frameCount)")
        }, "Neo_UsbFrameReader").also { it.isDaemon = true }

        frameReaderThread?.start()
    }

    /**
     * Capture a single frame from the USB camera.
     * Returns the latest decoded bitmap or null if no frame available.
     */
    fun captureFrame(): Bitmap? {
        if (!isConnected || cameraDevice == null) {
            Log.w(TAG, "Cannot capture — camera not connected")
            return null
        }

        synchronized(frameLock) {
            capturedFrame?.let { frame ->
                val copy = frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false)
                Log.d(TAG, "Frame captured: ${copy.width}x${copy.height}")
                return copy
            }
        }

        // If no frame available yet, wait briefly for the first frame
        Log.w(TAG, "No frame available yet — waiting for first frame...")
        for (attempt in 1..10) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            synchronized(frameLock) {
                capturedFrame?.let { frame ->
                    val copy = frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false)
                    Log.i(TAG, "Got first frame after ${attempt * 200}ms wait")
                    return copy
                }
            }
        }

        Log.w(TAG, "No frame available from camera after 2s wait")
        return null
    }

    /**
     * Called by the frame reader thread when a new frame is decoded.
     * Stores the latest frame for captureFrame() to return.
     */
    fun onFrameAvailable(bitmap: Bitmap) {
        synchronized(frameLock) {
            capturedFrame?.recycle()
            capturedFrame = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }
    }

    /**
     * Compress a captured frame for the Gemini API.
     * Downscales to 768x576 and compresses to JPEG at 80% quality.
     */
    fun compressForApi(bitmap: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, API_WIDTH, API_HEIGHT, true)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        if (scaled !== bitmap) scaled.recycle()
        val bytes = stream.toByteArray()
        Log.d(TAG, "Compressed image: ${bytes.size} bytes (${API_WIDTH}x${API_HEIGHT}, ${JPEG_QUALITY}% JPEG)")
        return bytes
    }

    /**
     * Compute a 64-bit perceptual hash of a bitmap.
     *
     * Algorithm:
     * 1. Resize to 8x8 pixels
     * 2. Convert to grayscale
     * 3. Calculate mean pixel value
     * 4. Each pixel above mean = 1, below = 0
     * 5. Result: 64-bit binary hash
     */
    fun computePerceptualHash(bitmap: Bitmap): Long {
        // Step 1: Resize to 8x8
        val small = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)

        // Step 2 & 3: Convert to grayscale and compute mean
        val pixels = IntArray(HASH_SIZE * HASH_SIZE)
        small.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
        if (small !== bitmap) small.recycle()

        val grayValues = IntArray(pixels.size)
        var sum = 0L
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            grayValues[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            sum += grayValues[i]
        }
        val mean = sum / pixels.size

        // Step 4: Build hash — each pixel above mean = 1
        var hash = 0L
        for (i in grayValues.indices) {
            if (grayValues[i] >= mean) {
                hash = hash or (1L shl i)
            }
        }

        return hash
    }

    /**
     * Calculate the Hamming distance between two perceptual hashes.
     * Distance 0-5 = same scene, 6+ = scene changed.
     */
    fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }

    /**
     * Stop the frame reader and disconnect from the USB camera.
     */
    fun disconnect() {
        try {
            // Stop the frame reader thread
            isStreaming = false
            frameReaderThread?.interrupt()
            try {
                frameReaderThread?.join(2000)
            } catch (_: InterruptedException) {}
            frameReaderThread = null

            // Release the interface
            streamingInterface?.let { iface ->
                try {
                    usbConnection?.releaseInterface(iface)
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing interface: ${e.message}")
                }
            }
            streamingInterface = null
            bulkEndpoint = null

            // Close the connection
            try {
                usbConnection?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing connection: ${e.message}")
            }
            usbConnection = null

            // Release the frame
            synchronized(frameLock) {
                capturedFrame?.recycle()
                capturedFrame = null
            }

            cameraDevice = null
            isConnected = false
            Log.i(TAG, "Camera disconnected and resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}", e)
        }
    }

    /**
     * Release all resources and unregister receivers.
     */
    fun release() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        Log.i(TAG, "UsbCameraManager released")
    }
}
