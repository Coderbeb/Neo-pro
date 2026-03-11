package com.autoapk.automation.input

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Hybrid Voice Recognition Manager — Dual Mode
 *
 * Two listening modes to avoid media interference:
 *
 *   PASSIVE MODE (AudioRecord):
 *     - Raw mic capture via AudioRecord (NO audio focus request)
 *     - Media keeps playing at full volume, uninterrupted
 *     - Monitors RMS energy to detect voice activity
 *     - When voice detected → switches to SpeechRecognizer briefly
 *
 *   ACTIVE MODE (SpeechRecognizer):
 *     - Standard SpeechRecognizer for transcription
 *     - Used only when voice activity is detected
 *     - 3 fallback levels: Online → prefer_offline → on-device
 *     - May briefly affect media (Android platform behavior)
 *
 * This prevents the pause-resume cycling where SpeechRecognizer's
 * internal audio focus requests cause media to pause every few seconds.
 */
class GoogleVoiceCommandManager(private val context: Context) {

    companion object {
        private const val TAG = "Neo_Voice"
        private const val DEDUP_WINDOW_MS = 2000L
        private const val MAX_CONSECUTIVE_ERRORS = 10

        // AudioRecord config
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Voice Activity Detection threshold
        // Values: silence ~50-200, soft speech ~500-1500, normal speech ~2000-5000
        private const val VAD_THRESHOLD = 600.0
        // How many consecutive frames must exceed threshold before triggering
        private const val VAD_TRIGGER_FRAMES = 3
    }

    enum class RecognitionMode { ONLINE, OFFLINE }

    // Which offline level we are currently trying (0 = online, 1 = prefer_offline flag, 2 = on-device)
    private var offlineLevel = 0
    private var error13Count = 0

    interface VoiceCommandListener {
        fun onCommandReceived(command: String): Boolean
        fun onListeningStarted()
        fun onListeningStopped()
        fun onError(errorMessage: String)
        fun onModeChanged(mode: RecognitionMode)
        fun onStatusUpdate(status: String)
    }

    // SpeechRecognizer fields
    private var speechRecognizer: SpeechRecognizer? = null
    private var listener: VoiceCommandListener? = null
    private var isListening = false
    private var isPaused = false
    private var shouldKeepListening = false
    private var consecutiveErrors = 0
    private var lastCommand = ""
    private var lastCommandTime = 0L
    private var ttsCheckCallback: (() -> Boolean)? = null

    // AudioRecord fields (passive mode)
    private var audioRecord: AudioRecord? = null
    private var passiveThread: Thread? = null
    @Volatile private var isPassiveMode = false
    // When true, we stay in active SpeechRecognizer mode (after wake word / during active Neo)
    private var forceActiveMode = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkHandler = Handler(Looper.getMainLooper())

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun setListener(l: VoiceCommandListener) { listener = l }
    fun setTtsCheckCallback(cb: () -> Boolean) { ttsCheckCallback = cb }
    fun isCurrentlyListening() = isListening || isPassiveMode
    fun isOnline() = offlineLevel == 0
    fun getCurrentMode() = if (offlineLevel == 0) RecognitionMode.ONLINE else RecognitionMode.OFFLINE

    /**
     * Force active SpeechRecognizer mode (called when Neo wakes up).
     * In this mode, we skip passive AudioRecord and go straight to SpeechRecognizer.
     */
    fun setForceActiveMode(active: Boolean) {
        forceActiveMode = active
        if (active && isPassiveMode && shouldKeepListening) {
            Log.i(TAG, "Force active mode ON → switching from passive to SpeechRecognizer")
            stopPassiveListening()
            doStartListening()
        } else if (!active && !isPassiveMode && shouldKeepListening) {
            Log.i(TAG, "Force active mode OFF → switching from SpeechRecognizer to passive")
            destroyRecognizer()
            isListening = false
            startPassiveListening()
        }
    }

    // ===================== NETWORK MONITORING =====================

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (offlineLevel > 0 && shouldKeepListening) {
                    networkHandler.removeCallbacksAndMessages(null)
                    networkHandler.postDelayed({
                        if (offlineLevel > 0 && shouldKeepListening) {
                            Log.i(TAG, "Network restored → switching to ONLINE")
                            offlineLevel = 0
                            error13Count = 0
                            listener?.onModeChanged(RecognitionMode.ONLINE)
                            updateStatus("🌐 Online — switching...")
                            if (!isPassiveMode && isListening) {
                                isListening = false
                                destroyRecognizer()
                                restartListening(500)
                            }
                        }
                    }, 2000)
                }
            }
            override fun onLost(network: Network) {
                // handled by ERROR_NETWORK in onError
            }
        }
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            connectivityManager?.registerNetworkCallback(req, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        networkCallback = null
    }

    private fun isNetworkConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // ===================== PASSIVE LISTENING (AudioRecord — no audio focus) =====================

    /**
     * Start passive listening with AudioRecord.
     * This does NOT request audio focus → media plays uninterrupted.
     * Monitors RMS energy to detect voice activity.
     */
    private fun startPassiveListening() {
        if (isPassiveMode) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted for passive listening")
            listener?.onError("Microphone permission required")
            return
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "Invalid AudioRecord buffer size: $bufferSize")
                // Fallback to SpeechRecognizer
                doStartListening()
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize — falling back to SpeechRecognizer")
                audioRecord?.release()
                audioRecord = null
                doStartListening()
                return
            }

            audioRecord?.startRecording()
            isPassiveMode = true
            isListening = true
            updateStatus("🎤 Passive listening — media unaffected")
            listener?.onListeningStarted()
            Log.i(TAG, "Passive listening started (AudioRecord, no audio focus)")

            // Background thread for RMS monitoring
            passiveThread = Thread({
                val buffer = ShortArray(bufferSize / 2)
                var consecutiveVoiceFrames = 0

                while (isPassiveMode && shouldKeepListening) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            val rms = calculateRMS(buffer, read)
                            if (rms > VAD_THRESHOLD) {
                                consecutiveVoiceFrames++
                                if (consecutiveVoiceFrames >= VAD_TRIGGER_FRAMES) {
                                    Log.i(TAG, "Voice activity detected (RMS=${"%.0f".format(rms)}) → starting SpeechRecognizer")
                                    mainHandler.post { onVoiceDetected() }
                                    break
                                }
                            } else {
                                consecutiveVoiceFrames = 0
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Passive read error: ${e.message}")
                        break
                    }
                }
            }, "Neo_PassiveMic").also { it.isDaemon = true }
            passiveThread?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Passive listening failed: ${e.message}", e)
            isPassiveMode = false
            // Fallback to SpeechRecognizer
            doStartListening()
        }
    }

    private fun stopPassiveListening() {
        isPassiveMode = false
        try {
            passiveThread?.interrupt()
            passiveThread = null
        } catch (_: Exception) {}
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}
        Log.d(TAG, "Passive listening stopped")
    }

    private fun calculateRMS(buffer: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i].toLong() * buffer[i].toLong()
        }
        return Math.sqrt(sum / readSize)
    }

    /**
     * Called when voice activity is detected in passive mode.
     * Switches to SpeechRecognizer for transcription.
     */
    private fun onVoiceDetected() {
        if (!shouldKeepListening) return
        if (ttsCheckCallback?.invoke() == true) {
            // TTS is speaking — don't switch, retry passive after delay
            mainHandler.postDelayed({
                if (shouldKeepListening && !isPassiveMode) startPassiveListening()
            }, 500)
            return
        }
        stopPassiveListening()
        doStartListening()
    }

    // ===================== START / STOP =====================

    fun startListening() {
        if (isListening || isPassiveMode) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener?.onError("Speech recognition not available on this device")
            updateStatus("❌ Recognition unavailable")
            return
        }
        shouldKeepListening = true
        consecutiveErrors = 0
        offlineLevel = if (isNetworkConnected()) 0 else 1
        error13Count = 0
        registerNetworkCallback()

        if (forceActiveMode) {
            // Neo is already active — go straight to SpeechRecognizer
            doStartListening()
        } else {
            // Start in passive mode (no audio focus, no media interference)
            startPassiveListening()
        }
    }

    private fun doStartListening() {
        try {
            destroyRecognizer()

            speechRecognizer = when {
                offlineLevel >= 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> {
                    Log.i(TAG, "Level 3: createOnDeviceSpeechRecognizer (API ${Build.VERSION.SDK_INT})")
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }
                else -> {
                    val label = if (offlineLevel == 0) "Level 1: Cloud" else "Level 2: prefer_offline"
                    Log.i(TAG, "$label recognizer")
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            }

            speechRecognizer?.setRecognitionListener(recognitionListener)
            speechRecognizer?.startListening(buildIntent())
            isListening = true
            isPaused = false
            isPassiveMode = false

            val icon = if (offlineLevel == 0) "🌐" else "📴"
            updateStatus("$icon Listening — speak now")
            listener?.onListeningStarted()

        } catch (e: Exception) {
            Log.e(TAG, "Start error: ${e.message}", e)
            isListening = false
            updateStatus("❌ Mic error — retrying")
            if (shouldKeepListening) restartListening(2000)
        }
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayListOf("hi-IN", "en-IN"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (offlineLevel >= 1) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
    }

    fun stopListening() {
        shouldKeepListening = false
        forceActiveMode = false
        isListening = false
        isPaused = false
        mainHandler.removeCallbacksAndMessages(null)
        networkHandler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        stopPassiveListening()
        destroyRecognizer()
        updateStatus("🔇 Stopped")
        listener?.onListeningStopped()
    }

    fun pauseListening() {
        if (!isListening && !shouldKeepListening && !isPassiveMode) return
        isPaused = true
        shouldKeepListening = false
        mainHandler.removeCallbacksAndMessages(null)
        if (isPassiveMode) {
            stopPassiveListening()
        } else {
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        }
    }

    fun resumeListening() {
        if (!isPaused) return
        isPaused = false
        shouldKeepListening = true
        isListening = false

        mainHandler.postDelayed({
            if (forceActiveMode) {
                doStartListening()
            } else {
                startPassiveListening()
            }
        }, 400)
    }

    fun destroy() {
        stopListening()
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
    }

    // ===================== RECOGNITION LISTENER =====================

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            consecutiveErrors = 0
            error13Count = 0
            val icon = if (offlineLevel == 0) "🌐" else "📴"
            updateStatus("$icon Ready — speak now")
        }

        override fun onBeginningOfSpeech() { updateStatus("🗣️ Hearing...") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { updateStatus("⏳ Processing...") }

        override fun onError(error: Int) {
            Log.w(TAG, "Error $error | offlineLevel=$offlineLevel | consecutive=$consecutiveErrors")
            consecutiveErrors++

            if (!shouldKeepListening) {
                isListening = false
                return
            }

            if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                consecutiveErrors = 0
                updateStatus("⏳ Pausing 5s...")
                restartListening(5000)
                return
            }

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveErrors = 0
                    // If not in forced active mode, go back to passive (save battery, no focus)
                    if (!forceActiveMode) {
                        isListening = false
                        destroyRecognizer()
                        startPassiveListening()
                        return
                    }
                    val icon = if (offlineLevel == 0) "🌐" else "📴"
                    updateStatus("$icon Listening...")
                    restartListening(150)
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    if (offlineLevel == 0) {
                        offlineLevel = 1
                        error13Count = 0
                        listener?.onModeChanged(RecognitionMode.OFFLINE)
                        updateStatus("📴 Offline — trying prefer_offline...")
                        Log.i(TAG, "Network error → escalate to Level 2 (prefer_offline)")
                    } else if (offlineLevel == 1) {
                        offlineLevel = 2
                        updateStatus("📴 Offline — trying on-device...")
                        Log.i(TAG, "prefer_offline failed → escalate to Level 3 (on-device)")
                    } else {
                        updateStatus("📴 Offline — retrying...")
                    }
                    restartListening(800)
                }

                SpeechRecognizer.ERROR_CLIENT -> {
                    restartListening(300)
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    restartListening(1500)
                }

                SpeechRecognizer.ERROR_AUDIO -> {
                    updateStatus("🎤 Audio issue — retrying")
                    restartListening(1500)
                }

                13 -> {
                    error13Count++
                    Log.w(TAG, "Error 13  (#$error13Count) offlineLevel=$offlineLevel")
                    when {
                        offlineLevel >= 2 && error13Count <= 3 -> {
                            val delay = error13Count * 2000L
                            updateStatus("📴 Offline — warming up ($error13Count/3)...")
                            restartListening(delay)
                        }
                        offlineLevel >= 2 && error13Count > 3 -> {
                            offlineLevel = 1
                            error13Count = 0
                            updateStatus("📴 Offline — prefer_offline mode...")
                            restartListening(1000)
                        }
                        offlineLevel == 1 && error13Count > 3 -> {
                            offlineLevel = 2
                            error13Count = 0
                            updateStatus("📴 Offline — switching engine...")
                            restartListening(1000)
                        }
                        else -> {
                            restartListening(2000)
                        }
                    }
                }

                else -> {
                    restartListening(1000)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val best = matches[0].trim()
                if (best.isNotBlank()) {
                    Log.i(TAG, "✅ HEARD: '$best' (level=$offlineLevel)")
                    val now = System.currentTimeMillis()
                    val isDup = best.lowercase() == lastCommand && (now - lastCommandTime) < DEDUP_WINDOW_MS
                    if (!isDup) {
                        lastCommand = best.lowercase()
                        lastCommandTime = now
                        val icon = if (offlineLevel == 0) "🌐" else "📴"
                        updateStatus("$icon \"$best\"")
                        listener?.onCommandReceived(best)
                    }
                }
            }

            if (!shouldKeepListening) {
                isListening = false
                return
            }

            if (forceActiveMode) {
                // Stay in active mode — restart SpeechRecognizer
                restartListening(150)
            } else {
                // Go back to passive mode (no audio focus)
                isListening = false
                destroyRecognizer()
                startPassiveListening()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                updateStatus("🗣️ \"${matches[0]}\"")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ===================== RESTART =====================

    private fun restartListening(delayMs: Long) {
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!shouldKeepListening) return@postDelayed
            if (ttsCheckCallback?.invoke() == true) {
                restartListening(500); return@postDelayed
            }
            doStartListening()
        }, delayMs)
    }

    private fun updateStatus(status: String) {
        Log.d(TAG, "STATUS: $status")
        listener?.onStatusUpdate(status)
    }
}
