package com.autoapk.automation.input

import android.content.Context
import android.content.Intent
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

/**
 * Hybrid Voice Recognition Manager
 *
 * Strategy (3 fallback levels):
 *  Level 1 — Online:  standard SpeechRecognizer (Google Cloud)
 *  Level 2 — Offline: standard SpeechRecognizer + EXTRA_PREFER_OFFLINE=true
 *  Level 3 — Offline: createOnDeviceSpeechRecognizer (Android 10+, API 31 system module)
 *
 * Error 13 → immediately try next level, do NOT give up
 * Network errors → switch to offline levels, keep retrying
 */
class GoogleVoiceCommandManager(private val context: Context) {

    companion object {
        private const val TAG = "Neo_Voice"
        private const val DEDUP_WINDOW_MS = 2000L
        private const val MAX_CONSECUTIVE_ERRORS = 10
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

    private var speechRecognizer: SpeechRecognizer? = null
    private var listener: VoiceCommandListener? = null
    private var isListening = false
    private var isPaused = false
    private var shouldKeepListening = false
    private var consecutiveErrors = 0
    private var lastCommand = ""
    private var lastCommandTime = 0L
    private var ttsCheckCallback: (() -> Boolean)? = null
    private val speakerDetection = com.autoapk.automation.core.SpeakerDetection(context)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkHandler = Handler(Looper.getMainLooper())

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun setListener(l: VoiceCommandListener) { listener = l }
    fun setTtsCheckCallback(cb: () -> Boolean) { ttsCheckCallback = cb }
    fun isCurrentlyListening() = isListening
    fun isOnline() = offlineLevel == 0
    fun getCurrentMode() = if (offlineLevel == 0) RecognitionMode.ONLINE else RecognitionMode.OFFLINE

    // ===================== NETWORK MONITORING =====================

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (offlineLevel > 0 && shouldKeepListening) {
                    // Network restored — go back to online
                    networkHandler.removeCallbacksAndMessages(null)
                    networkHandler.postDelayed({
                        if (offlineLevel > 0 && shouldKeepListening) {
                            Log.i(TAG, "Network restored → switching to ONLINE")
                            offlineLevel = 0
                            error13Count = 0
                            listener?.onModeChanged(RecognitionMode.ONLINE)
                            updateStatus("🌐 Online — switching...")
                            if (isListening) {
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

    // ===================== START / STOP =====================

    fun startListening() {
        if (isListening) return
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
        doStartListening()
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
            // For offline levels 1 and 2, add prefer_offline flag
            if (offlineLevel >= 1) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
    }

    fun stopListening() {
        shouldKeepListening = false
        isListening = false
        isPaused = false
        mainHandler.removeCallbacksAndMessages(null)
        networkHandler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        destroyRecognizer()
        updateStatus("🔇 Stopped")
        listener?.onListeningStopped()
    }

    fun pauseListening() {
        if (!isListening && !shouldKeepListening) return
        isPaused = true
        shouldKeepListening = false
        mainHandler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
    }

    fun resumeListening() {
        if (!isPaused) return
        isPaused = false
        shouldKeepListening = true
        isListening = false
        mainHandler.postDelayed({ doStartListening() }, 400)
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
                    // Normal silence — just restart
                    consecutiveErrors = 0
                    val icon = if (offlineLevel == 0) "🌐" else "📴"
                    updateStatus("$icon Listening...")
                    restartListening(150)
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    // Network gone — escalate offline level
                    if (offlineLevel == 0) {
                        offlineLevel = 1
                        error13Count = 0
                        listener?.onModeChanged(RecognitionMode.OFFLINE)
                        updateStatus("📴 Offline — trying prefer_offline...")
                        Log.i(TAG, "Network error → escalate to Level 2 (prefer_offline)")
                    } else if (offlineLevel == 1) {
                        // prefer_offline also failed → try on-device
                        offlineLevel = 2
                        updateStatus("📴 Offline — trying on-device...")
                        Log.i(TAG, "prefer_offline failed → escalate to Level 3 (on-device)")
                    } else {
                        // All levels failed — stay at level 2, keep trying
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
                    // Error 13 (InsufficientPermissions / on-device not ready)
                    error13Count++
                    Log.w(TAG, "Error 13  (#$error13Count) offlineLevel=$offlineLevel")
                    when {
                        offlineLevel >= 2 && error13Count <= 3 -> {
                            // On-device not ready yet — retry with backoff
                            val delay = error13Count * 2000L
                            updateStatus("📴 Offline — warming up ($error13Count/3)...")
                            restartListening(delay)
                        }
                        offlineLevel >= 2 && error13Count > 3 -> {
                            // On-device truly unavailable — fall back to prefer_offline
                            offlineLevel = 1
                            error13Count = 0
                            updateStatus("📴 Offline — prefer_offline mode...")
                            restartListening(1000)
                        }
                        offlineLevel == 1 && error13Count > 3 -> {
                            // prefer_offline also getting Error 13 — try on-device
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
            if (shouldKeepListening) restartListening(150) else isListening = false
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
            // Speaker rejection — skip listen if media is playing on speaker
            if (!speakerDetection.shouldListen()) {
                restartListening(1000); return@postDelayed
            }
            doStartListening()
        }, delayMs)
    }

    private fun updateStatus(status: String) {
        Log.d(TAG, "STATUS: $status")
        listener?.onStatusUpdate(status)
    }
}
