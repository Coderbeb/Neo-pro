package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Core Engine of Neo.
 *
 * This service runs in the background with system-level privileges to:
 * - Dispatch gestures (tap, swipe, scroll)
 * - Perform global actions (Home, Back, Recents, Lock)
 * - Read on-screen content (find buttons by text)
 * - Provide voice feedback via TTS
 * - Detect volume button combo to wake Neo from sleep mode
 */
class AutomationAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "Neo_Service"
        private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        // WeakReference singleton to prevent memory leaks
        private var instanceRef: WeakReference<AutomationAccessibilityService>? = null

        /**
         * Get the service instance safely.
         * Returns null if service is dead or not connected.
         */
        @JvmStatic
        fun get(): AutomationAccessibilityService? = instanceRef?.get()

        /**
         * Returns true only if instance is not null AND service is bound.
         */
        @JvmStatic
        fun isAlive(): Boolean {
            val svc = instanceRef?.get() ?: return false
            return try {
                svc.rootInActiveWindow  // test if binding is alive
                true
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Legacy compat — alias for get() != null
         */
        @JvmStatic
        fun isRunning(): Boolean = get() != null

        /**
         * Backward-compatible instance property.
         * Other existing code reads `AutomationAccessibilityService.instance`.
         */
        @JvmStatic
        val instance: AutomationAccessibilityService?
            get() = instanceRef?.get()
    }

    private lateinit var tts: TextToSpeech
    var screenWidth: Int = 1080
        private set
    var screenHeight: Int = 2400
        private set
    private var ttsReady: Boolean = false

    // Track last spoken text for "repeat" command
    var lastSpokenText: String = ""
        private set

    // === NEO STATE MANAGER ===
    var neoState: NeoStateManager? = null

    // === APP-SPECIFIC AUTOMATION ===
    var commandRouter: CommandRouter? = null
        private set

    // === WAKE TRIGGER (Volume + BT + Proximity) ===
    private var wakeTrigger: WakeTrigger? = null

    // === VOLUME BUTTON COMBO FOR HARDWARE WAKE ===
    private var lastVolumeUpTime: Long = 0
    private var lastVolumeDownTime: Long = 0
    private val COMBO_WINDOW_MS = 400L

    // === SERVICE HEALTH MONITOR ===
    private val healthHandler = Handler(Looper.getMainLooper())
    private var healthFailCount = 0
    private val HEALTH_CHECK_INTERVAL_MS = 30_000L
    private val MAX_FAIL_COUNT = 3

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            try {
                val root = rootInActiveWindow
                if (root != null) {
                    healthFailCount = 0
                    Log.d(TAG, "Health check: OK")
                } else {
                    healthFailCount++
                    Log.w(TAG, "Health check: rootInActiveWindow is NULL (fail #$healthFailCount)")
                    if (healthFailCount > MAX_FAIL_COUNT) {
                        Log.e(TAG, "Service appears zombie — attempting recovery")
                        attemptServiceRecovery()
                    }
                }
            } catch (e: Exception) {
                healthFailCount++
                Log.e(TAG, "Health check exception: ${e.message}")
            }
            healthHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
        }
    }

    // ==================== LIFECYCLE ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        tts = TextToSpeech(this, this)
        updateScreenDimensions()

        // Initialize CommandRouter for app-specific automation
        commandRouter = CommandRouter(this) { text -> speak(text) }

        // Connect Notification Listener to TTS (for "Mom replied on WhatsApp" announcements)
        NeoNotificationListener.ttsCallback = { text -> speak(text) }

        // Initialize WakeTrigger (BT triple-press, proximity sensor)
        wakeTrigger = WakeTrigger(this, this) { triggerWake() }
        wakeTrigger?.register()

        // Start foreground notification to prevent Android from killing service
        startForegroundNotification()

        // Start health monitor — checks every 30 seconds
        healthHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS)

        // Service verification — delayed 1 second to let things settle
        healthHandler.postDelayed({
            val root = rootInActiveWindow
            if (root != null) {
                Log.i(TAG, "SERVICE_VERIFIED — rootInActiveWindow accessible")
                sendBroadcast(Intent("com.autoapk.automation.SERVICE_VERIFIED"))
            } else {
                Log.e(TAG, "SERVICE_FAILED — rootInActiveWindow is null after connect")
                sendBroadcast(Intent("com.autoapk.automation.SERVICE_FAILED"))
            }
        }, 1000)

        Log.i(TAG, "[${dateFormat.format(Date())}] Neo Accessibility Service CONNECTED (with health monitor)")
    }

    override fun onDestroy() {
        healthHandler.removeCallbacks(healthCheckRunnable)
        instanceRef?.clear()
        instanceRef = null
        commandRouter?.destroy()
        commandRouter = null
        NeoNotificationListener.ttsCallback = null
        wakeTrigger?.unregister()
        wakeTrigger = null
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        Log.i(TAG, "[${dateFormat.format(Date())}] Neo Accessibility Service DESTROYED")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We monitor events for future context awareness
    }

    override fun onInterrupt() {
        Log.w(TAG, "Neo Service interrupted")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            // Check Hindi TTS availability
            val hindiResult = tts.isLanguageAvailable(Locale("hi", "IN"))
            hindiTtsAvailable = (hindiResult == TextToSpeech.LANG_AVAILABLE ||
                                hindiResult == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                                hindiResult == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE)

            // Set audio attributes to NOTIFICATION stream so TTS does NOT steal
            // audio focus from the SpeechRecognizer mic
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts.setAudioAttributes(audioAttributes)
            }

            ttsReady = true

            // Track when TTS finishes speaking to resume mic
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    // Resume mic quickly — 150ms is enough to avoid tail-end echo
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        ttsListener?.onTtsFinished()
                    }, 150)
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    ttsListener?.onTtsFinished()
                }
            })

            // Don't speak on startup — only speak when user activates
            Log.i(TAG, "TTS initialized successfully — ready when activated")
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    // ==================== VOLUME BUTTON COMBO — HARDWARE WAKE ====================

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || event.action != KeyEvent.ACTION_DOWN) return super.onKeyEvent(event)

        val now = System.currentTimeMillis()

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // Check if Vol Down was pressed recently → combo!
                if (now - lastVolumeDownTime < COMBO_WINDOW_MS) {
                    lastVolumeUpTime = 0
                    lastVolumeDownTime = 0
                    triggerWake()
                    return true // consume the event (no volume change)
                }
                lastVolumeUpTime = now
                return super.onKeyEvent(event) // let volume change happen
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Check if Vol Up was pressed recently → combo!
                if (now - lastVolumeUpTime < COMBO_WINDOW_MS) {
                    lastVolumeUpTime = 0
                    lastVolumeDownTime = 0
                    triggerWake()
                    return true // consume the event
                }
                lastVolumeDownTime = now
                return super.onKeyEvent(event)
            }
        }

        return super.onKeyEvent(event)
    }

    private fun triggerWake() {
        Log.i(TAG, "Volume combo detected — waking Neo")

        neoState?.let { state ->
            when (state.currentMode) {
                NeoStateManager.NeoMode.SLEEPING -> {
                    state.wake()
                }
                NeoStateManager.NeoMode.IN_CALL -> {
                    muteCallMic(true)
                    state.wake()
                }
                NeoStateManager.NeoMode.ACTIVE -> {
                    Log.d(TAG, "Already active — combo ignored")
                }
            }
        }
    }

    // ==================== CALL MIC MUTE ====================

    private var wasMicMuted = false

    fun muteCallMic(mute: Boolean) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (mute) {
                wasMicMuted = audioManager.isMicrophoneMute
                audioManager.isMicrophoneMute = true
                Log.i(TAG, "Call mic MUTED")
            } else {
                audioManager.isMicrophoneMute = wasMicMuted
                Log.i(TAG, "Call mic UNMUTED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mic mute error: ${e.message}")
        }
    }

    // ==================== VOICE FEEDBACK ====================
    // TTS state tracking
    var isSpeaking: Boolean = false
        private set

    // Listener for TTS completion (used to resume voice after TTS finishes)
    interface TtsListener {
        fun onTtsStarted()
        fun onTtsFinished()
    }
    var ttsListener: TtsListener? = null

    // === HINDI LANGUAGE MODE ===
    // When true, TTS responses are auto-translated to Hindi
    var isHindiMode: Boolean = false
    private var hindiTtsAvailable: Boolean = false
    private val hindiLocale = Locale("hi", "IN")

    /**
     * Speak text via TTS.
     * If Hindi mode is active, auto-translates response and uses Hindi TTS.
     * Notifies listener when TTS starts/finishes so mic can be paused.
     */
    fun speak(text: String) {
        if (ttsReady) {
            val spokenText: String
            if (isHindiMode && hindiTtsAvailable) {
                spokenText = HindiResponseMapper.translate(text)
                tts.language = hindiLocale
            } else {
                spokenText = text
                tts.language = Locale.US
            }
            lastSpokenText = spokenText
            isSpeaking = true
            ttsListener?.onTtsStarted()
            val utteranceId = "neo_${System.currentTimeMillis()}"
            tts.speak(spokenText, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    /**
     * Stop TTS immediately (user said "stop").
     */
    fun stopSpeaking() {
        if (::tts.isInitialized) {
            tts.stop()
            isSpeaking = false
            ttsListener?.onTtsFinished()
        }
    }

    /**
     * Force speak regardless of activation state.
     * Used only for service startup and activation announcements.
     */
    private fun speakForced(text: String) {
        if (ttsReady) {
            isSpeaking = true
            ttsListener?.onTtsStarted()
            val utteranceId = "neo_forced_${System.currentTimeMillis()}"
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    // ==================== FOREGROUND NOTIFICATION ====================

    private fun startForegroundNotification() {
        val channelId = "assistant_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Voice Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps voice assistant active in background"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Voice Assistant Active")
            .setContentText("Listening for commands")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            startForeground(1, notification)
            Log.i(TAG, "Foreground notification started")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground: ${e.message}")
        }
    }

    // ==================== SERVICE HEALTH RECOVERY ====================

    private fun attemptServiceRecovery() {
        try {
            // Force-update serviceInfo to trigger rebind
            val info = serviceInfo
            if (info != null) {
                serviceInfo = info
                Log.i(TAG, "Service recovery: updated serviceInfo to force rebind")
            }
            healthFailCount = 0
        } catch (e: Exception) {
            Log.e(TAG, "Service recovery failed: ${e.message}")
            // Send notification to user
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, "assistant_service")
                .setContentTitle("Voice Assistant Issue")
                .setContentText("Service needs re-enabling. Please disable and re-enable in Accessibility Settings.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(2, notification)
        }
    }

    /**
     * Verify service health — can be called from external code.
     * Returns true if service is alive and functional.
     */
    fun verifyServiceHealth(): Boolean {
        return try {
            val root = rootInActiveWindow
            if (root != null) {
                Log.i(TAG, "verifyServiceHealth: OK")
                true
            } else {
                Log.w(TAG, "verifyServiceHealth: rootInActiveWindow is null")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyServiceHealth: exception: ${e.message}")
            false
        }
    }

    // ==================== GLOBAL ACTIONS ====================

    fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun openRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun openQuickSettings(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    fun takeScreenshotAction(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            Log.w(TAG, "Screenshot requires API 28+")
            false
        }
    }

    fun splitScreen(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
    }

    fun lockScreen(): Boolean {
        speak("Locking screen")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    /**
     * Wake up the screen and dismiss the keyguard.
     * If a PIN is provided, auto-enter it on the lock screen.
     */
    fun unlockScreen(): Boolean {
        return unlockWithPin(null)
    }

    /**
     * Full phone unlock with auto-PIN-entry using POSITION-BASED tapping.
     * No text-based button clicking — this is 100% coordinate-based for reliability.
     *
     * Flow:
     *   1. Wake screen with WakeLock
     *   2. Wait 500ms for screen to fully wake
     *   3. Swipe up to reveal PIN pad
     *   4. Wait 1200ms for PIN pad animation to complete
     *   5. Tap each digit at its calculated position (350ms apart)
     *   6. Tap Enter/OK button
     *   7. Verify if phone unlocked
     */
    fun unlockWithPin(pin: String?): Boolean {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (!keyguardManager.isKeyguardLocked) {
                speak("Phone is already unlocked")
                return true
            }

            Log.i(TAG, "=== UNLOCK: Starting PIN unlock (${pin?.length ?: 0} digits) ===")

            // Step 1: Wake the screen
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "neo:unlock"
            )
            wakeLock.acquire(15000)
            Log.i(TAG, "UNLOCK: Screen wake lock acquired")

            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            // Step 2: Wait for screen to wake, then swipe up
            handler.postDelayed({
                Log.i(TAG, "UNLOCK: Swiping up to reveal PIN pad (screen: ${screenWidth}x${screenHeight})")
                val centerX = screenWidth / 2f
                val startY = screenHeight * 0.8f
                val endY = screenHeight * 0.2f
                swipe(centerX, startY, centerX, endY, 400, null)

                // Step 3: Wait for PIN pad to appear, then enter digits
                if (!pin.isNullOrBlank()) {
                    handler.postDelayed({
                        Log.i(TAG, "UNLOCK: Starting digit entry — ${pin.length} digits")
                        enterPinByPosition(pin, handler, 0)
                    }, 1200) // Long wait for PIN pad to fully appear
                } else {
                    speak("Screen woke up. Swipe to unlock if needed.")
                }
            }, 500) // Wait for screen to wake up

            // Release wake lock after everything completes
            handler.postDelayed({
                if (wakeLock.isHeld) wakeLock.release()
                Log.i(TAG, "UNLOCK: Wake lock released")
            }, 15000)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Unlock failed: ${e.message}")
            speak("Could not unlock screen")
            return false
        }
    }

    /**
     * Full phone unlock with PATTERN gesture.
     * Draws a continuous finger drag through the pattern dot positions.
     *
     * @param pattern Pattern as dot sequence, e.g., "14789" means dots 1→4→7→8→9
     *                Dot positions in the 3x3 grid:
     *                  1  2  3
     *                  4  5  6
     *                  7  8  9
     */
    fun unlockWithPattern(pattern: String): Boolean {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (!keyguardManager.isKeyguardLocked) {
                speak("Phone is already unlocked")
                return true
            }

            Log.i(TAG, "=== UNLOCK: Starting PATTERN unlock (${pattern.length} dots) ===")

            // Step 1: Wake screen
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "neo:unlock"
            )
            wakeLock.acquire(15000)

            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            // Step 2: Swipe up to reveal pattern grid
            handler.postDelayed({
                Log.i(TAG, "UNLOCK: Swiping up for pattern screen")
                val centerX = screenWidth / 2f
                swipe(centerX, screenHeight * 0.8f, centerX, screenHeight * 0.2f, 400, null)

                // Step 3: Draw the pattern gesture
                handler.postDelayed({
                    drawPatternGesture(pattern)
                }, 1200)
            }, 500)

            // Verify unlock after gesture completes
            handler.postDelayed({
                val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                if (!km.isKeyguardLocked) {
                    speak("Phone unlocked successfully")
                } else {
                    speak("Pattern drawn. Check if phone is unlocked.")
                }
                if (wakeLock.isHeld) wakeLock.release()
            }, 5000)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Pattern unlock failed: ${e.message}")
            speak("Could not unlock screen")
            return false
        }
    }

    /**
     * Enter PIN digits by tapping at calculated screen positions.
     * Uses ONLY coordinate-based tapping — no text search.
     *
     * Standard Android PIN pad layout (bottom half of screen):
     *   1  2  3
     *   4  5  6
     *   7  8  9
     *   ⌫  0  ✓
     */
    private fun enterPinByPosition(pin: String, handler: android.os.Handler, index: Int) {
        if (index >= pin.length) {
            // All digits entered — tap the Enter/confirm button (bottom-right)
            Log.i(TAG, "UNLOCK: All ${pin.length} digits entered, tapping confirm button")
            handler.postDelayed({
                tapConfirmButton()

                // Verify unlock after a delay
                handler.postDelayed({
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    if (!km.isKeyguardLocked) {
                        speak("Phone unlocked successfully")
                        Log.i(TAG, "UNLOCK: ✅ Phone unlocked!")
                    } else {
                        speak("PIN entered. Check if phone is unlocked.")
                        Log.w(TAG, "UNLOCK: ⚠ Phone still locked after PIN entry")
                    }
                }, 1500)
            }, 400)
            return
        }

        val digit = pin[index].toString()
        val (x, y) = getPinDigitPosition(digit)

        Log.i(TAG, "UNLOCK: Tapping digit '$digit' [${index + 1}/${pin.length}] at ($x, $y)")
        tapAt(x, y, null)

        // Schedule next digit with reliable delay
        handler.postDelayed({
            enterPinByPosition(pin, handler, index + 1)
        }, 350) // 350ms between digits for reliability
    }

    /**
     * Get the calculated screen position for a PIN pad digit.
     *
     * The PIN pad occupies approximately:
     *   - Horizontally: full screen width divided into 3 columns
     *   - Vertically: from ~48% to ~92% of screen height (4 rows)
     *
     * These values are calibrated for standard Android PIN pads.
     */
    private fun getPinDigitPosition(digit: String): Pair<Float, Float> {
        // PIN pad area boundaries (calibrated for most Android phones)
        val padTop = screenHeight * 0.48f
        val padBottom = screenHeight * 0.92f
        val padLeft = screenWidth * 0.05f
        val padRight = screenWidth * 0.95f

        val rowHeight = (padBottom - padTop) / 4f
        val colWidth = (padRight - padLeft) / 3f

        val (row, col) = when (digit) {
            "1" -> Pair(0, 0)
            "2" -> Pair(0, 1)
            "3" -> Pair(0, 2)
            "4" -> Pair(1, 0)
            "5" -> Pair(1, 1)
            "6" -> Pair(1, 2)
            "7" -> Pair(2, 0)
            "8" -> Pair(2, 1)
            "9" -> Pair(2, 2)
            "0" -> Pair(3, 1) // Bottom center
            else -> Pair(0, 0)
        }

        val x = padLeft + colWidth * col + colWidth / 2f
        val y = padTop + rowHeight * row + rowHeight / 2f

        return Pair(x, y)
    }

    /**
     * Tap the Enter/Confirm button on the PIN pad.
     * On most Android phones this is at the bottom-right of the PIN pad.
     */
    private fun tapConfirmButton() {
        val padBottom = screenHeight * 0.92f
        val padTop = screenHeight * 0.48f
        val rowHeight = (padBottom - padTop) / 4f
        val padRight = screenWidth * 0.95f
        val padLeft = screenWidth * 0.05f
        val colWidth = (padRight - padLeft) / 3f

        // Enter button = bottom-right cell (row 3, col 2)
        val x = padLeft + colWidth * 2 + colWidth / 2f
        val y = padTop + rowHeight * 3 + rowHeight / 2f

        Log.i(TAG, "UNLOCK: Tapping Enter/Confirm at ($x, $y)")
        tapAt(x, y, null)
    }

    /**
     * Draw a continuous pattern gesture through the 3x3 pattern grid.
     *
     * Pattern grid positions:
     *   1  2  3
     *   4  5  6
     *   7  8  9
     *
     * The pattern grid typically occupies the center of the screen.
     */
    private fun drawPatternGesture(pattern: String) {
        if (pattern.length < 2) {
            Log.w(TAG, "Pattern too short: ${pattern.length} dots")
            return
        }

        // Pattern grid boundaries (calibrated for standard Android lock screens)
        val gridCenterY = screenHeight * 0.55f  // Grid is roughly centered vertically
        val gridSize = screenWidth * 0.7f        // Grid is about 70% of screen width
        val gridLeft = (screenWidth - gridSize) / 2f
        val gridTop = gridCenterY - gridSize / 2f

        val cellSize = gridSize / 3f

        // Calculate coordinates for each dot in the pattern
        val points = pattern.mapNotNull { ch ->
            val dotNum = ch.toString().toIntOrNull() ?: return@mapNotNull null
            if (dotNum < 1 || dotNum > 9) return@mapNotNull null

            val row = (dotNum - 1) / 3
            val col = (dotNum - 1) % 3

            val x = gridLeft + col * cellSize + cellSize / 2f
            val y = gridTop + row * cellSize + cellSize / 2f
            Pair(x, y)
        }

        if (points.size < 2) {
            Log.w(TAG, "Not enough valid dots in pattern")
            return
        }

        // Build the gesture path through all dots
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first, points[i].second)
        }

        // Duration: 100ms per segment for smooth drawing
        val duration = (points.size - 1) * 100L + 200L

        Log.i(TAG, "UNLOCK: Drawing pattern through ${points.size} dots (${duration}ms)")
        for ((i, p) in points.withIndex()) {
            Log.d(TAG, "  Dot ${pattern[i]}: (${p.first}, ${p.second})")
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }



    // ==================== APP AWARENESS ====================

    /**
     * Get the name of the currently visible app.
     */
    fun getCurrentAppName(): String {
        val rootNode = rootInActiveWindow ?: return "unknown"
        val packageName = rootNode.packageName?.toString() ?: return "unknown"
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    // ==================== SMART SCREEN DESCRIPTION ====================

    /**
     * Describe screen elements by type — for blind users to understand the layout.
     */
    fun describeScreen(): String {
        val rootNode = rootInActiveWindow ?: return "Cannot read screen"
        var buttons = 0
        var textFields = 0
        var checkboxes = 0
        var images = 0
        val clickableLabels = mutableListOf<String>()
        val textContent = mutableListOf<String>()

        collectScreenInfo(rootNode, clickableLabels, textContent,
            intArrayOf(0, 0, 0, 0)) // buttons, textFields, checkboxes, images counters

        val counters = intArrayOf(0, 0, 0, 0)
        countElements(rootNode, counters)
        buttons = counters[0]; textFields = counters[1]; checkboxes = counters[2]; images = counters[3]

        val sb = StringBuilder()
        sb.append("Screen has: ")
        if (buttons > 0) sb.append("$buttons buttons. ")
        if (textFields > 0) sb.append("$textFields text fields. ")
        if (checkboxes > 0) sb.append("$checkboxes checkboxes. ")
        if (images > 0) sb.append("$images images. ")

        if (clickableLabels.isNotEmpty()) {
            sb.append("Clickable items: ${clickableLabels.take(8).joinToString(", ")}. ")
        }
        if (textContent.isNotEmpty()) {
            sb.append("Text on screen: ${textContent.take(5).joinToString(". ")}. ")
        }

        return sb.toString()
    }

    /**
     * List all clickable items on screen — blind users can then say "click [name]"
     */
    fun listClickableItems(): List<String> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val items = mutableListOf<String>()
        collectClickableLabels(rootNode, items)
        return items
    }

    private fun countElements(node: AccessibilityNodeInfo, counters: IntArray) {
        val className = node.className?.toString() ?: ""
        when {
            className.contains("Button") -> counters[0]++
            className.contains("EditText") -> counters[1]++
            className.contains("CheckBox") || className.contains("Switch") -> counters[2]++
            className.contains("ImageView") -> counters[3]++
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            countElements(child, counters)
        }
    }

    private fun collectScreenInfo(node: AccessibilityNodeInfo,
                                   clickableLabels: MutableList<String>,
                                   textContent: MutableList<String>,
                                   counters: IntArray) {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val label = text ?: desc

        if (node.isClickable && !label.isNullOrBlank() && clickableLabels.size < 10) {
            clickableLabels.add(label)
        }
        if (!text.isNullOrBlank() && textContent.size < 8) {
            textContent.add(text)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScreenInfo(child, clickableLabels, textContent, counters)
        }
    }

    private fun collectClickableLabels(node: AccessibilityNodeInfo, items: MutableList<String>) {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val label = text ?: desc

        if (node.isClickable && !label.isNullOrBlank() && items.size < 15) {
            items.add(label)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableLabels(child, items)
        }
    }

    // ==================== GESTURE ACTIONS ====================

    fun tapAt(x: Float, y: Float, callback: GestureResultCallback? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, callback, null)
    }

    fun scrollDown(callback: GestureResultCallback? = null) {
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.7f
        val endY = screenHeight * 0.3f
        swipe(startX, startY, startX, endY, 300, callback)
    }

    fun scrollUp(callback: GestureResultCallback? = null) {
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.3f
        val endY = screenHeight * 0.7f
        swipe(startX, startY, startX, endY, 300, callback)
    }



    private fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long,
        callback: GestureResultCallback? = null
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, callback, null)
    }

    // ==================== SCREEN ANALYSIS ====================

    /**
     * Finds and clicks on a UI element by its text content.
     * This is the "Smart Click" feature.
     * Returns true if found and clicked, false otherwise.
     */
    fun clickOnText(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val found = findNodeByText(rootNode, targetText)
        if (found != null) {
            val result = found.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!result) {
                // Try clicking the parent if the node itself is not clickable
                var parent = found.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                    parent = parent.parent
                }
            }
            speak("Clicking $targetText")
            return true
        }
        speak("Could not find $targetText on screen")
        return false
    }

    /**
     * Find and click a UI element by its content description.
     * Used for icon-only buttons (e.g., send button in chat apps).
     */
    fun clickOnContentDescription(description: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val found = findNodeByContentDescription(rootNode, description)
        if (found != null) {
            val result = found.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!result) {
                var parent = found.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                    parent = parent.parent
                }
            }
            return true
        }
        return false
    }

    private fun findNodeByContentDescription(
        node: AccessibilityNodeInfo, description: String
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()
        if (desc != null && desc.contains(description, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByContentDescription(child, description)
            if (found != null) return found
        }
        return null
    }
    /**
     * Finds a clickable item at a specific index position on screen.
     * Used for "Play 3rd song" type commands.
     */
    fun clickItemAtIndex(index: Int): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val clickableItems = mutableListOf<AccessibilityNodeInfo>()
        collectClickableItems(rootNode, clickableItems)

        if (index in 1..clickableItems.size) {
            val target = clickableItems[index - 1] // 1-indexed
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            speak("Clicking item number $index")
            return true
        }
        speak("Item number $index not found on screen")
        return false
    }

    /**
     * Reads all visible text on the screen.
     */
    fun readScreen(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val textBuilder = StringBuilder()
        collectText(rootNode, textBuilder)
        return textBuilder.toString()
    }


    // ==================== NOTIFICATIONS ====================

    fun clearAllNotifications(): Boolean {
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        // Use the status bar notification service to clear
        val sbManager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        sbManager?.cancelAll()
        speak("Notifications cleared")
        return true
    }

    fun getNotificationTexts(): List<String> {
        val texts = mutableListOf<String>()
        val rootNode = rootInActiveWindow
        // Try to read from the notification shade
        if (rootNode != null) {
            collectNotificationText(rootNode, texts)
        }
        return texts
    }

    private fun collectNotificationText(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val className = node.className?.toString() ?: ""

        if (!text.isNullOrBlank() && className.contains("TextView")) {
            texts.add(text)
        } else if (!contentDesc.isNullOrBlank() && texts.size < 10) {
            texts.add(contentDesc)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNotificationText(child, texts)
        }
    }

    // ==================== TEXT INPUT ====================

    /**
     * Types text into the currently focused input field using accessibility.
     */
    fun inputText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableNode(rootNode)
            ?: return false

        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
        }
        return null
    }

    // ==================== HELPER METHODS ====================

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true) || contentDesc.contains(text, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, text)
            if (result != null) return result
        }
        return null
    }

    private fun collectClickableItems(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable && node.isVisibleToUser) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableItems(child, list)
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, builder: StringBuilder) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            builder.append(text).append(". ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, builder)
        }
    }

    private fun updateScreenDimensions() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        Log.i(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}")
    }

    // ==================== SMART SCREEN INTERACTION ENGINE ====================

    /**
     * Data class representing a UI element found on screen.
     */
    data class ScreenElement(
        val label: String,
        val type: String,      // "Button", "TextView", "ImageView", etc.
        val isClickable: Boolean,
        val node: AccessibilityNodeInfo,
        val boundsLeft: Int,
        val boundsTop: Int,
        val boundsRight: Int,
        val boundsBottom: Int
    )

    /**
     * Last screen snapshot hash — used for self-correction (verifyScreenChanged)
     */
    private var lastScreenHash: Int = 0

    /**
     * Fuzzy-match a target string against ALL on-screen elements and click the best match.
     *
     * This is the CORE ENGINE for multi-step automation. It works on ANY app because it
     * reads the live accessibility tree — no app-specific hardcoding needed.
     *
     * Search strategy:
     *   1. Exact match (case-insensitive contains)
     *   2. Word-level match (any word in label matches any word in target)
     *   3. Fuzzy match (Levenshtein distance ≤ 2 for similar words)
     *   4. Content description match (for icon-only buttons)
     *
     * Click strategy:
     *   1. Try ACTION_CLICK on the node itself
     *   2. If not clickable, walk up to find a clickable parent
     *   3. If still fails, use coordinate-based tap on the element's center
     *
     * @param target The text to search for (e.g., "search", "reels", "shutter", "send")
     * @return true if an element was found and clicked
     */
    fun findAndClickSmart(target: String, silent: Boolean = false): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "SmartClick: No root window available")
            return false
        }

        // Snapshot current screen state for verification later
        lastScreenHash = computeScreenHash(rootNode)

        val targetLower = target.lowercase().trim()
        val targetWords = targetLower.split(Regex("\\s+")).filter { it.isNotBlank() }

        Log.i(TAG, "SmartClick: Searching for '$target' on screen...")

        // Collect ALL elements with labels
        val allElements = mutableListOf<Pair<ScreenElement, Float>>() // element + similarity score
        collectScoredElements(rootNode, targetLower, targetWords, allElements)

        if (allElements.isEmpty()) {
            Log.w(TAG, "SmartClick: No matching elements found for '$target'")
            if (!silent) speak("Could not find $target on screen")
            return false
        }

        // Sort by score (highest first), then prefer clickable elements
        allElements.sortWith(compareByDescending<Pair<ScreenElement, Float>> { it.second }
            .thenByDescending { it.first.isClickable })

        val bestMatch = allElements[0]
        Log.i(TAG, "SmartClick: Best match = '${bestMatch.first.label}' (score=${bestMatch.second}, clickable=${bestMatch.first.isClickable})")

        // Try to click
        return performSmartClick(bestMatch.first, target)
    }

    /**
     * Recursively collect all screen elements and score them against the target.
     */
    private fun collectScoredElements(
        node: AccessibilityNodeInfo,
        targetLower: String,
        targetWords: List<String>,
        results: MutableList<Pair<ScreenElement, Float>>
    ) {
        // Skip invisible elements
        if (!node.isVisibleToUser) {
            // Still check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectScoredElements(child, targetLower, targetWords, results)
            }
            return
        }

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val label = when {
            text.isNotBlank() && desc.isNotBlank() -> "$text $desc"
            text.isNotBlank() -> text
            desc.isNotBlank() -> desc
            else -> ""
        }

        if (label.isNotBlank()) {
            val score = computeSimilarityScore(label, targetLower, targetWords)
            if (score > 0f) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val className = node.className?.toString() ?: "Unknown"
                val type = className.substringAfterLast(".")
                results.add(Pair(
                    ScreenElement(label, type, node.isClickable, node, bounds.left, bounds.top, bounds.right, bounds.bottom),
                    score
                ))
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScoredElements(child, targetLower, targetWords, results)
        }
    }

    /**
     * Compute a similarity score between a screen element's label and the target text.
     * Higher score = better match.
     *
     * Scoring:
     *   - Exact contains:    10.0
     *   - Label starts with target: 8.0
     *   - Word-level hit:     5.0 per matching word
     *   - Fuzzy word match:   3.0 per fuzzy-matched word
     *   - Partial overlap:    1.0
     */
    private fun computeSimilarityScore(label: String, targetLower: String, targetWords: List<String>): Float {
        val labelLower = label.lowercase()
        val labelWords = labelLower.split(Regex("[\\s,._\\-/]+")).filter { it.isNotBlank() }
        var score = 0f

        // 1. Exact containment (best)
        if (labelLower.contains(targetLower)) {
            score += 10.0f
            return score
        }

        // 2. Target contains entire label (e.g., target="take photo" and label="Photo")
        if (targetLower.contains(labelLower) && labelLower.length >= 3) {
            score += 8.0f
            return score
        }

        // 3. Word-level matching
        for (targetWord in targetWords) {
            if (targetWord.length < 2) continue

            for (labelWord in labelWords) {
                if (labelWord.length < 2) continue

                // Exact word match
                if (targetWord == labelWord) {
                    score += 5.0f
                    break
                }
                // Word starts with target word (or vice versa)
                if (labelWord.startsWith(targetWord) || targetWord.startsWith(labelWord)) {
                    score += 4.0f
                    break
                }
                // Fuzzy match (edit distance ≤ 2)
                if (targetWord.length >= 3 && labelWord.length >= 3) {
                    val dist = smartLevenshtein(targetWord, labelWord)
                    val maxLen = maxOf(targetWord.length, labelWord.length)
                    if (dist <= 2 && dist < maxLen / 2) {
                        score += 3.0f
                        break
                    }
                }
            }
        }

        return score
    }

    /**
     * Levenshtein distance computation for fuzzy matching.
     */
    private fun smartLevenshtein(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }

    /**
     * Perform a smart click on a screen element.
     * Tries ACTION_CLICK, then parent climbing, then coordinate tap.
     */
    private fun performSmartClick(element: ScreenElement, targetName: String): Boolean {
        val node = element.node

        // Strategy 1: Direct click
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                Log.i(TAG, "SmartClick: ✅ Direct click on '${element.label}'")
                speak("Clicking $targetName")
                return true
            }
        }

        // Strategy 2: Parent climbing — find the nearest clickable ancestor
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) {
                    Log.i(TAG, "SmartClick: ✅ Clicked parent of '${element.label}' (depth=$depth)")
                    speak("Clicking $targetName")
                    return true
                }
            }
            parent = parent.parent
            depth++
        }

        // Strategy 3: Coordinate-based tap as last resort
        val centerX = (element.boundsLeft + element.boundsRight) / 2f
        val centerY = (element.boundsTop + element.boundsBottom) / 2f
        if (centerX > 0 && centerY > 0 && centerX < screenWidth && centerY < screenHeight) {
            Log.i(TAG, "SmartClick: ✅ Coordinate tap at ($centerX, $centerY) for '${element.label}'")
            tapAt(centerX, centerY, null)
            speak("Clicking $targetName")
            return true
        }

        Log.w(TAG, "SmartClick: ❌ All strategies failed for '${element.label}'")
        speak("Found $targetName but could not click it")
        return false
    }

    /**
     * Get all labeled elements currently visible on screen.
     * Returns a structured list for intelligent command processing.
     */
    fun getAllScreenElements(): List<ScreenElement> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<ScreenElement>()
        collectAllElements(rootNode, elements)
        return elements
    }

    private fun collectAllElements(node: AccessibilityNodeInfo, elements: MutableList<ScreenElement>) {
        if (!node.isVisibleToUser) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectAllElements(child, elements)
            }
            return
        }

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val label = (text + " " + desc).trim()

        if (label.isNotBlank()) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val className = node.className?.toString() ?: "Unknown"
            val type = className.substringAfterLast(".")
            elements.add(ScreenElement(label, type, node.isClickable, node, bounds.left, bounds.top, bounds.right, bounds.bottom))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllElements(child, elements)
        }
    }

    /**
     * Find the first editable text field on screen, focus it, and make it ready for input.
     * Used before inputText() for reliable typing.
     */
    fun findAndFocusTextField(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        // First check if something is already focused
        val focused = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            Log.i(TAG, "TextField: Already focused on editable field")
            return true
        }

        // Find and focus the first editable node
        val editNode = findEditableNode(rootNode)
        if (editNode != null) {
            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "TextField: Focused editable field")
            return true
        }

        // Fallback: try clicking on any element with "Type" or "Write" or "Message" hint
        val hintNode = findNodeByTextPattern(rootNode, listOf("type a message", "write a message", "type here", "search", "message", "type", "write"))
        if (hintNode != null) {
            hintNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "TextField: Clicked on hint-labeled field")
            return true
        }

        Log.w(TAG, "TextField: No editable field found on screen")
        return false
    }

    /**
     * Find a node whose text or content description contains any of the given patterns.
     */
    private fun findNodeByTextPattern(node: AccessibilityNodeInfo, patterns: List<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hint = node.hintText?.toString()?.lowercase() ?: ""

        for (pattern in patterns) {
            if (text.contains(pattern) || desc.contains(pattern) || hint.contains(pattern)) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextPattern(child, patterns)
            if (found != null) return found
        }
        return null
    }

    // ==================== SELF-CORRECTION & VERIFICATION ====================

    /**
     * Compute a hash of the current screen state (package name + first few element labels).
     * Used by verifyScreenChanged() to detect if an action actually worked.
     */
    private fun computeScreenHash(root: AccessibilityNodeInfo): Int {
        val sb = StringBuilder()
        sb.append(root.packageName?.toString() ?: "")
        // Collect first 5 text labels to create a rough fingerprint
        val labels = mutableListOf<String>()
        collectFirstLabels(root, labels, 5)
        for (label in labels) sb.append("|").append(label)
        return sb.toString().hashCode()
    }

    private fun collectFirstLabels(node: AccessibilityNodeInfo, labels: MutableList<String>, max: Int) {
        if (labels.size >= max) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) labels.add(text)
        for (i in 0 until node.childCount) {
            if (labels.size >= max) return
            val child = node.getChild(i) ?: continue
            collectFirstLabels(child, labels, max)
        }
    }

    /**
     * Verify that the screen actually changed after performing an action.
     * Call this 500ms+ after a click/tap to check if it worked.
     *
     * @return true if screen changed, false if it looks the same
     */
    fun verifyScreenChanged(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val newHash = computeScreenHash(rootNode)
        val changed = newHash != lastScreenHash
        if (changed) {
            Log.i(TAG, "Verify: Screen changed (hash: $lastScreenHash → $newHash)")
        } else {
            Log.w(TAG, "Verify: Screen appears unchanged (hash: $newHash)")
        }
        lastScreenHash = newHash
        return changed
    }
}
