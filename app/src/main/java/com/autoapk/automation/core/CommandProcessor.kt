package com.autoapk.automation.core

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.autoapk.automation.vision.VisionAssistantOrchestrator

/**
 * Maps voice/command strings to actual system actions.
 *
 * This is the BRAIN of the app. It parses a raw command string
 * (from voice or Bluetooth) and routes it to the correct handler.
 */
class CommandProcessor(private val context: Context, private val appRegistry: AppRegistry) {

    companion object {
        private const val TAG = "Neo_CMD"
    }

    // App Navigator — handles launching and global actions
    private val appNavigator = AppNavigator(context, appRegistry)

    // Smart contact registry — fuzzy matches voice-recognized names
    val contactRegistry = ContactRegistry(context)

    // Smart command matcher — keyword-scoring intent classifier
    private val smartMatcher = SmartCommandMatcher

    // Auto-learning memory — silently remembers successful commands
    private val commandMemory = CommandMemory(context)
    
    // Phone state detector — monitors state for verification requirements
    private val stateDetector = PhoneStateDetector(context).also {
        it.contactRegistry = contactRegistry
    }
    
    // Verification system — manages verification codes and validation
    private val verificationSystem = VerificationSystem(context)
    
    // Navigation context tracker — maintains context for pronoun resolution and command chaining
    private val navigationContext = NavigationContextTracker()

    // Phase 3 Controllers
    private val cameraController = CameraController(context)
    private val callManager = CallManager(context)
    private val flashlightController = FlashlightController(context)
    private val directToggleController = DirectToggleController(context)

    // Vision Assistance Module — lazily initialized on first vision command
    private var visionOrchestrator: VisionAssistantOrchestrator? = null
    private fun getVision(): VisionAssistantOrchestrator {
        if (visionOrchestrator == null) {
            visionOrchestrator = VisionAssistantOrchestrator(
                context = context,
                tts = { text -> service?.speak(text) },
                isTtsSpeaking = { service?.isSpeaking == true },
                phoneStateDetector = stateDetector
            )
        }
        // Sync language mode on every call
        visionOrchestrator?.isHindiMode = service?.isHindiMode == true
        return visionOrchestrator!!
    }


    private val service: AutomationAccessibilityService?
        get() = AutomationAccessibilityService.instance

    // Audio Feedback
    private val audioFeedback = AudioFeedbackManager(context)

    // Neo State Manager — controls wake/sleep/in-call modes
    var neoState: NeoStateManager? = null

    // === LAST ACTION MEMORY (for "again" / "repeat" command) ===
    private var lastAction: LastAction? = null
    data class LastAction(
        val intent: SmartCommandMatcher.CommandIntent,
        val command: String,
        val rawCommand: String,
        val param: String = ""
    )

    // === PHONE LOCK STORAGE (PIN or PATTERN) ===
    private val prefs = context.getSharedPreferences("autoapk_unlock", Context.MODE_PRIVATE)
    private var lockType: String = prefs.getString("lock_type", "PIN") ?: "PIN"
    private var phonePin: String = prefs.getString("phone_pin", "") ?: ""
    private var phonePattern: String = prefs.getString("phone_pattern", "") ?: ""

    // Voice unlock code system
    private var unlockCode: String = prefs.getString("voice_unlock_code", "") ?: ""
    private var waitingForUnlockCode: Boolean = false
    private var unlockAttemptsRemaining: Int = 3

    // Emergency contact
    private var emergencyContact: String = prefs.getString("emergency_contact", "") ?: ""

    // === CONTACT DISAMBIGUATION STATE ===
    private var waitingForContactDisambiguation: Boolean = false
    private var pendingContactMatches: List<ContactRegistry.ContactMatch> = emptyList()

    // === CONFIRMATION STATE (confidence levels) ===
    private var waitingForConfirmation: Boolean = false
    private var pendingConfirmationIntent: SmartCommandMatcher.CommandIntent? = null
    private var pendingConfirmationCommand: String = ""
    private var pendingConfirmationRawCommand: String = ""
    private var pendingConfirmationParam: String = ""
    private var confirmationTimeoutMs: Long = 0L

    // === IN-CALL STATE ===
    // When true, commands require the user's security code as prefix
    var isOnActiveCall: Boolean = false
        private set

    // === NAVIGATION / MAPS STATE ===
    private enum class NavigationState { NONE, WAITING_FOR_DESTINATION, CONFIRMING_START }
    private var navigationState = NavigationState.NONE
    private var pendingDestination: String = ""
    private var navigationMode: String = "d" // d=driving, w=walking, b=bicycling, r=transit

    // === RAPIDO RIDE BOOKING STATE ===
    private enum class RapidoState {
        NONE,
        WAITING_FOR_DESTINATION,
        CONFIRMING_DESTINATION,
        CONFIRMING_PICKUP,
        SELECTING_RIDE_TYPE,
        CONFIRMING_BOOKING
    }
    private var rapidoState = RapidoState.NONE
    private var rapidoPendingDestination: String = ""
    private var rapidoSuggestions: List<String> = emptyList()
    private var rapidoSelectedRideType: String = ""
    private var rapidoSelectedFare: String = ""
    private val rapidoAutomation by lazy { RapidoAutomation(context) }

    // Initialize call state by checking if there's actually an active call
    init {
        // Reset call state on initialization (app start/restart)
        isOnActiveCall = false
        Log.i(TAG, "CommandProcessor initialized - call state reset to false")
        
        // Start phone state monitoring
        stateDetector.setListener(object : PhoneStateDetector.StateChangeListener {
            override fun onStateChanged(oldState: PhoneStateDetector.PhoneState, newState: PhoneStateDetector.PhoneState) {
                Log.i(TAG, "Phone state changed: $oldState → $newState")
                
                // Update call state tracking
                isOnActiveCall = (newState == PhoneStateDetector.PhoneState.IN_CALL)
                
                // Announce state change if verification requirements changed
                if (stateDetector.requiresVerification() && oldState == PhoneStateDetector.PhoneState.NORMAL) {
                    service?.speak("Verification required for commands")
                }
            }
        })
        stateDetector.startMonitoring()

        // Initialize Camera Controller listener
        cameraController.listener = object : CameraController.CameraListener {
            override fun onPhotoCaptured(path: String) {
                service?.speak("Photo taken")
            }
            override fun onVideoRecordingStarted() {
                service?.speak("Recording started")
            }
            override fun onVideoRecordingStopped(path: String) {
                service?.speak("Video saved")
            }
            override fun onError(message: String) {
                service?.speak(message)
            }
            override fun onMessage(message: String) {
                service?.speak(message)
            }
        }

        // Initialize Call Manager listener
        callManager.listener = object : CallManager.CallListener {
            override fun onMessage(message: String) {
                service?.speak(message)
            }
            override fun onError(message: String) {
                service?.speak(message)
            }
        }
    }

    // === COMMAND COOLDOWN (anti-repeat) ===
    private var lastProcessedCommand: String = ""
    private var lastProcessedTime: Long = 0L
    private val COMMAND_COOLDOWN_MS = 2000L  // Reject same command within 2 seconds

    /**
     * Process a raw command string and execute the matching action.
     * Returns true if command was recognized and executed.
     *
     * Uses a 3-layer matching pipeline:
     *   Layer 1: CommandMemory (instant recall of previously-learned input)
     *   Layer 2: SmartCommandMatcher (keyword scoring with massive synonym database)
     *   Layer 3: Fallback — "Command not recognized"
     *
     * Hindi/Hinglish translation is applied before matching.
     * On success, the raw input → intent mapping is silently learned.
     * 
     * Enhanced with state detection and verification system:
     *   - Checks current phone state (NORMAL, CONSUMING_CONTENT, IN_CALL, CAMERA_ACTIVE)
     *   - Requires verification code for sensitive states
     *   - Extracts and validates verification codes from commands
     * 
     * Enhanced with command chaining support:
     *   - Detects multi-step commands separated by commas
     *   - Executes each step sequentially
     *   - Maintains context between steps using NavigationContextTracker
     */
    fun process(rawCommand: String): Boolean {
        // === TRANSLATE HINDI/HINGLISH FIRST ===
        // Must happen before routing so app-specific handlers also understand Hindi commands
        val translated = HindiCommandMapper.translate(rawCommand)
        val preProcessed = InputPreProcessor.preProcess(translated)
        
        // Auto-detect Hindi: check if translation changed OR if raw input contains Hindi indicators
        val isHindiInput = isHindiDetected(rawCommand, translated)
        service?.isHindiMode = isHindiInput
        
        Log.i("Neo_CMD", "Process: raw='$rawCommand' → translated='$preProcessed' (hindi=$isHindiInput)")

        // Try app-specific routing first (WhatsApp, YouTube, Instagram)
        val router = AutomationAccessibilityService.instance?.commandRouter
        if (router != null && router.route(preProcessed)) {
            Log.i("Neo_CMD", "Routed to app-specific handler: '$preProcessed'")
            return true
        }

        // Check if this is a chained command (contains commas)
        if (preProcessed.contains(",")) {
            return processChainedCommand(preProcessed)
        }
        
        return processSingleCommand(rawCommand, preProcessed)
    }
    
    /**
     * Process a chained command containing multiple steps separated by commas.
     * Each step is executed sequentially, maintaining context between steps.
     * 
     * Example: "Open Instagram, search John, open his profile, scroll photos"
     * 
     * Requirements: 4.9
     */
    private fun processChainedCommand(rawCommand: String): Boolean {
        Log.i(TAG, "Processing chained command: '$rawCommand'")
        
        // Split by comma and trim each step
        val steps = rawCommand.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        
        if (steps.isEmpty()) {
            Log.w(TAG, "Chained command resulted in no steps after parsing")
            return false
        }
        
        Log.i(TAG, "Chained command has ${steps.size} steps")
        
        var successCount = 0
        var failedStep: String? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        // Execute each step sequentially using Handler.postDelayed (non-blocking)
        fun executeStep(index: Int) {
            if (index >= steps.size) {
                // All steps completed
                if (successCount == steps.size) {
                    service?.speak("Completed all $successCount steps")
                    Log.i(TAG, "Chained command completed successfully")
                } else if (successCount > 0) {
                    service?.speak("Completed $successCount of ${steps.size} steps")
                    Log.w(TAG, "Chained command partially completed")
                } else {
                    service?.speak("Failed to execute chained command")
                    Log.e(TAG, "Chained command failed completely")
                }
                return
            }
            
            val step = steps[index]
            Log.i(TAG, "Executing step ${index + 1}/${steps.size}: '$step'")
            
            val success = processSingleCommand(step)
            if (success) {
                successCount++
                navigationContext.addCommandToHistory(step)
            } else {
                failedStep = step
                Log.w(TAG, "Step ${index + 1} failed: '$step', continuing...")
            }
            
            // Schedule next step with delay for UI to settle
            if (index < steps.size - 1) {
                handler.postDelayed({ executeStep(index + 1) }, 500)
            } else {
                // Last step — report
                handler.postDelayed({ executeStep(index + 1) }, 100)
            }
        }
        
        executeStep(0)
        return true  // Started execution (async)
    }
    
    /**
     * Process a single command (non-chained).
     * This is the original process() logic extracted into a separate method.
     */
    private fun processSingleCommand(rawCommand: String, preTranslated: String? = null): Boolean {
        // === NEO WAKE/SLEEP FILTER ===
        // If sleeping, only wake word "Neo" (or hardware combo) can wake it
        val state = neoState
        val commandToProcess: String
        if (state != null) {
            val filtered = state.processWakeFilter(rawCommand)
            if (filtered == null) {
                // Sleeping and no wake word found — silently ignore
                return false
            }
            if (filtered.isEmpty()) {
                // Just "Neo" spoken — woke up, no command to process
                return true
            }
            // Wake word was found and stripped, or already ACTIVE
            commandToProcess = filtered
        } else {
            // No state manager — process everything (fallback)
            commandToProcess = rawCommand
        }

        // Use pre-translated command if available, otherwise translate now
        val command: String
        if (preTranslated != null) {
            command = preTranslated.trim().lowercase()
        } else {
            val translated = HindiCommandMapper.translate(commandToProcess)
            val processed = InputPreProcessor.preProcess(translated)
            command = processed.trim().lowercase()
        }
        Log.i(TAG, "Processing command: '$command' (original: '${rawCommand.trim()}')")

        // === TTS ECHO FILTER — while speaking, only accept stop commands ===
        val isTtsSpeaking = service?.isSpeaking == true
        if (isTtsSpeaking) {
            val stopWords = setOf("stop", "ruko", "ruk", "bas", "cancel", "band", "chup",
                "रुको", "रुक", "बस", "बंद", "चुप")
            if (command !in stopWords) {
                Log.i(TAG, "TTS ECHO FILTER: Ignoring '$command' while TTS is speaking")
                return false
            }
            // Stop word during TTS — stop TTS and reset states
            Log.i(TAG, "STOP during TTS — stopping speech")
            service?.stopSpeaking()
            waitingForUnlockCode = false
            waitingForContactDisambiguation = false
            pendingContactMatches = emptyList()
            waitingForConfirmation = false
            pendingConfirmationIntent = null
            pendingConfirmationCommand = ""
            pendingConfirmationParam = ""
            navigationState = NavigationState.NONE
            pendingDestination = ""
            rapidoState = RapidoState.NONE
            rapidoPendingDestination = ""
            rapidoSuggestions = emptyList()
            rapidoSelectedRideType = ""
            isOnActiveCall = false
            service?.speak("Stopped")
            return true
        }

        // === COMMAND COOLDOWN — reject duplicate within 2 seconds ===
        val now = System.currentTimeMillis()
        if (command == lastProcessedCommand && (now - lastProcessedTime) < COMMAND_COOLDOWN_MS) {
            Log.w(TAG, "COOLDOWN: Rejecting duplicate command '$command' (${now - lastProcessedTime}ms since last)")
            return false
        }

        // === STOP_ALL — always check first, even during pending states ===
        val stopWords = setOf("stop", "ruko", "ruk", "bas", "cancel", "band", "chup",
            "रुको", "रुक", "बस", "बंद", "चुप")
        if (command in stopWords) {
            Log.i(TAG, "STOP_ALL triggered — clearing ALL pending states")
            // Stop TTS immediately
            service?.stopSpeaking()
            // Reset ALL pending states → return to idle
            waitingForUnlockCode = false
            waitingForContactDisambiguation = false
            pendingContactMatches = emptyList()
            waitingForConfirmation = false
            pendingConfirmationIntent = null
            pendingConfirmationCommand = ""
            pendingConfirmationParam = ""
            navigationState = NavigationState.NONE
            pendingDestination = ""
            rapidoState = RapidoState.NONE
            rapidoPendingDestination = ""
            rapidoSuggestions = emptyList()
            rapidoSelectedRideType = ""
            isOnActiveCall = false
            service?.speak("Stopped")
            lastProcessedCommand = command
            lastProcessedTime = now
            return true
        }

        // No verification required, process normally
        return processNormalCommand(command, rawCommand, now)
    }
    
    /**
     * Process command after verification (code already validated and stripped)
     */
    private fun processVerifiedCommand(command: String, rawCommand: String, timestamp: Long): Boolean {
        // Process the command without verification checks
        return processCommandInternal(command, rawCommand, timestamp)
    }
    
    /**
     * Process command in normal mode (no verification required)
     */
    private fun processNormalCommand(command: String, rawCommand: String, timestamp: Long): Boolean {
        return processCommandInternal(command, rawCommand, timestamp)
    }
    
    /**
     * Internal command processing logic (shared by verified and normal paths)
     */
    private fun processCommandInternal(command: String, rawCommand: String, timestamp: Long): Boolean {
        // === CONTACT DISAMBIGUATION STATE ===
        if (waitingForContactDisambiguation) {
            lastProcessedCommand = command
            lastProcessedTime = timestamp
            return handleContactDisambiguation(command)
        }

        // === NAVIGATION STATE MACHINE ===
        if (navigationState != NavigationState.NONE) {
            lastProcessedCommand = command
            lastProcessedTime = timestamp
            return handleNavigationState(command)
        }

        // === RAPIDO STATE MACHINE ===
        if (rapidoState != RapidoState.NONE) {
            lastProcessedCommand = command
            lastProcessedTime = timestamp
            return handleRapidoState(command)
        }

        // === CONFIRMATION STATE (confidence levels) ===
        if (waitingForConfirmation) {
            lastProcessedCommand = command
            lastProcessedTime = timestamp
            return handleConfirmationResponse(command, rawCommand, timestamp)
        }

        // === UNLOCK CODE VERIFICATION STATE MACHINE ===
        if (waitingForUnlockCode) {
            lastProcessedCommand = command
            lastProcessedTime = timestamp
            return handleUnlockCodeAttempt(command)
        }

        // === IN-CALL MODE CHECK ===
        // In IN_CALL mode, only call-related commands are allowed
        // The user already woke Neo via hardware combo (call is muted)
        if (neoState?.currentMode == NeoStateManager.NeoMode.IN_CALL ||
            (isOnActiveCall && neoState?.inCallModeEnabled == true)) {
            return handleInCallCommand(command, rawCommand)
        }

        // === LAYER 1: MEMORY RECALL ===
        // Check if we've seen this exact input before and know what it means
        // (parameterized intents are automatically skipped by CommandMemory)
        val memorizedIntent = commandMemory.recall(command)
        if (memorizedIntent != null) {
            Log.i(TAG, "Memory hit: '$command' → $memorizedIntent")
            val result = executeIntent(memorizedIntent, command, rawCommand)
            if (result) {
                lastProcessedCommand = command
                lastProcessedTime = timestamp
                audioFeedback.playSuccess()
                return true
            }
            // Memory mapping failed — forget this bad mapping
            commandMemory.forget(command)
        }

        // === PRONOUN RESOLUTION ===
        // Before matching, resolve pronouns like "his", "her", "that"
        var resolvedCommand = command
        val pronouns = listOf("his", "her", "their", "that", "this", "it")
        for (pronoun in pronouns) {
            if (command.contains(pronoun)) {
                val resolved = navigationContext.resolvePronoun(pronoun)
                if (resolved != null) {
                    resolvedCommand = command.replace(pronoun, resolved)
                    Log.i(TAG, "Pronoun '$pronoun' resolved to '$resolved': $resolvedCommand")
                    break
                }
            }
        }

        // === LAYER 2: SMART KEYWORD MATCHING ===
        // Pass current app name for context-aware matching
        val matchResult = smartMatcher.match(resolvedCommand, navigationContext.getCurrentApp() ?: "")
        if (matchResult != null) {
            Log.i(TAG, "Smart match: '$resolvedCommand' → ${matchResult.intent} (score=${matchResult.score}, param='${matchResult.extractedParam}')")

            // === TASK 3.3: CONFIDENCE LEVELS ===
            when {
                matchResult.score >= 3.0f -> {
                    // HIGH confidence → execute immediately
                    Log.i(TAG, "HIGH confidence (${matchResult.score}) → executing immediately")
                }
                matchResult.score >= 1.5f -> {
                    // MEDIUM confidence → execute but inform user
                    Log.i(TAG, "MEDIUM confidence (${matchResult.score}) → executing with info")
                }
            }

            val result = executeIntent(matchResult.intent, resolvedCommand, rawCommand, matchResult.extractedParam)
            if (result) {
                // Silently learn this mapping for faster future recall
                commandMemory.remember(command, matchResult.intent)
                // Store last action for "again" / "repeat"
                lastAction = LastAction(matchResult.intent, resolvedCommand, rawCommand, matchResult.extractedParam)
                lastProcessedCommand = command
                lastProcessedTime = timestamp
                audioFeedback.playSuccess()
                // Notify Neo state manager — resets 60s timer or triggers instant sleep in IN_CALL
                neoState?.onCommandProcessed()
                return true
            }
        }

        // === LAYER 3: CONTACT NAME FALLBACK ===
        // If no command matched, check if the input is a contact name
        // This allows "Nitish bhaiya" to work without saying "call" first
        // Blocklist: words that should NEVER trigger a contact call
        val contactBlocklist = setOf(
            "setting", "settings", "open", "close", "search", "play", "pause", "stop",
            "volume", "mute", "unmute", "next", "previous", "back", "home",
            "recents", "recent", "quick", "notification", "notifications",
            "scroll", "swipe", "bluetooth", "wifi", "data", "flashlight",
            "camera", "brightness", "timer", "alarm", "screenshot",
            "battery", "time", "date", "weather", "navigate", "direction",
            "lock", "unlock", "fullscreen", "vision", "describe", "read",
            "rapido", "ride", "book", "pin", "cancel", "auto", "bike", "cab"
        )
        if (command.lowercase() !in contactBlocklist) {
            val contactMatch = contactRegistry.findContact(command)
            if (contactMatch != null && contactMatch.score >= 65) {
                Log.i(TAG, "Fallback: Detected contact name '$command' → calling ${contactMatch.name} (score=${contactMatch.score})")
                service?.speak("Calling ${contactMatch.name}")
                return try {
                    val intent = Intent(Intent.ACTION_CALL).apply {
                        data = android.net.Uri.parse("tel:${contactMatch.number}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    isOnActiveCall = true
                    lastProcessedCommand = command
                    lastProcessedTime = timestamp
                    true
                } catch (e: SecurityException) {
                    service?.speak("Call permission not granted")
                    false
                }
            }
        }

        // === LAYER 4: FINAL FALLBACK ===
        service?.speak("I didn't understand. Please try again")
        audioFeedback.playError()
        Log.w(TAG, "Unrecognized command: '$command' (no match in memory or scoring)")
        return false
    }

    /**
     * Handle confirmation response ("yes"/"haan" or "no"/"nahi").
     * Used for LOW confidence commands.
     */
    private fun handleConfirmationResponse(command: String, rawCommand: String, timestamp: Long): Boolean {
        val yesWords = setOf("yes", "haan", "ha", "han", "ok", "okay", "sure", "theek", "sahi", "right", "correct")
        val noWords = setOf("no", "nahi", "nah", "na", "mat", "cancel", "galat", "wrong")

        val isYes = command.split(" ").any { it in yesWords }
        val isNo = command.split(" ").any { it in noWords }

        // Check for timeout (3 seconds)
        val isTimedOut = System.currentTimeMillis() > confirmationTimeoutMs

        if (isYes && pendingConfirmationIntent != null) {
            Log.i(TAG, "Confirmation: YES → executing ${pendingConfirmationIntent}")
            waitingForConfirmation = false
            val result = executeIntent(
                pendingConfirmationIntent!!,
                pendingConfirmationCommand,
                pendingConfirmationRawCommand,
                pendingConfirmationParam
            )
            pendingConfirmationIntent = null
            lastProcessedCommand = command
            lastProcessedTime = timestamp
            return result
        } else if (isNo || isTimedOut) {
            Log.i(TAG, "Confirmation: ${if (isTimedOut) "TIMEOUT" else "NO"} → cancelled")
            waitingForConfirmation = false
            pendingConfirmationIntent = null
            service?.speak("Cancelled")
            lastProcessedCommand = command
            lastProcessedTime = timestamp
            return true
        }

        // Neither yes nor no — treat as a new command
        waitingForConfirmation = false
        pendingConfirmationIntent = null
        return processCommandInternal(command, rawCommand, timestamp)
    }

    /**
     * Handle commands during active call with in-call mode ON.
     * User woke Neo via hardware combo (call mic is already muted).
     * Only call-related commands are allowed.
     * After execution, Neo sleeps instantly and call mic is unmuted.
     */
    private fun handleInCallCommand(command: String, rawCommand: String): Boolean {
        Log.i(TAG, "In-call command: '$command'")

        // Match against all intents but filter to call-related ones
        val matchResult = smartMatcher.match(command, "")
        if (matchResult != null) {
            val allowedIntents = setOf(
                SmartCommandMatcher.CommandIntent.END_CALL,
                SmartCommandMatcher.CommandIntent.TOGGLE_SPEAKER,
                SmartCommandMatcher.CommandIntent.MUTE,
                SmartCommandMatcher.CommandIntent.UNMUTE,
                SmartCommandMatcher.CommandIntent.STOP_ALL,
                SmartCommandMatcher.CommandIntent.VOLUME_UP,
                SmartCommandMatcher.CommandIntent.VOLUME_DOWN
            )
            if (matchResult.intent in allowedIntents) {
                Log.i(TAG, "In-call command matched: ${matchResult.intent}")
                val result = executeIntent(matchResult.intent, command, rawCommand, matchResult.extractedParam)
                // After executing in-call command: unmute mic and sleep instantly
                service?.muteCallMic(false)
                neoState?.onCommandProcessed() // triggers instant sleep in IN_CALL mode
                return result
            } else {
                Log.w(TAG, "In-call: matched ${matchResult.intent} but not allowed during call")
            }
        }

        service?.speak("During a call, I can only: end call, toggle speaker, mute, or adjust volume.")
        // Unmute mic after responding
        service?.muteCallMic(false)
        neoState?.onCommandProcessed()
        return true
    }

    /**
     * Handle contact disambiguation — user said a name that matched multiple contacts.
     * Now expecting them to say the last name to narrow down.
     */
    private fun handleContactDisambiguation(spokenInput: String): Boolean {
        val input = spokenInput.trim().lowercase()

        // Try to match against pending contacts
        var bestMatch: ContactRegistry.ContactMatch? = null
        for (contact in pendingContactMatches) {
            val contactLower = contact.name.lowercase()
            if (contactLower.contains(input) || input.contains(contactLower)) {
                bestMatch = contact
                break
            }
            // Also check individual words
            val contactWords = contactLower.split(Regex("\\s+"))
            for (word in contactWords) {
                if (word == input || word.startsWith(input) || input.startsWith(word)) {
                    bestMatch = contact
                    break
                }
            }
            if (bestMatch != null) break
        }

        waitingForContactDisambiguation = false

        if (bestMatch != null) {
            pendingContactMatches = emptyList()
            Log.i(TAG, "Disambiguation resolved: '${bestMatch.name}' (${bestMatch.number})")
            service?.speak("Calling ${bestMatch.name}")
            return try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = android.net.Uri.parse("tel:${bestMatch.number}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                isOnActiveCall = true
                true
            } catch (e: SecurityException) {
                service?.speak("Call permission not granted")
                false
            }
        } else {
            pendingContactMatches = emptyList()
            service?.speak("Could not find that contact. Please try again.")
            return false
        }
    }

    /**
     * Execute a matched intent by routing to the appropriate action handler.
     * This replaces the old rigid `when` block with intent-based dispatch.
     */
    private fun executeIntent(
        intent: SmartCommandMatcher.CommandIntent,
        command: String,
        rawCommand: String,
        param: String = ""
    ): Boolean {
        return when (intent) {
            // === STOP ALL ===
            SmartCommandMatcher.CommandIntent.STOP_ALL -> {
                service?.stopSpeaking()
                waitingForUnlockCode = false
                waitingForContactDisambiguation = false
                pendingContactMatches = emptyList()
                isOnActiveCall = false
                service?.speak("Stopped")
                true
            }

            // === STOP TTS ===
            SmartCommandMatcher.CommandIntent.STOP_SPEAKING -> {
                service?.stopSpeaking(); true
            }

            // === NAVIGATION ===
            SmartCommandMatcher.CommandIntent.GO_HOME -> {
                val svc = service ?: return notConnected()
                val result = svc.goHome()
                if (result) { svc.speak("Going home"); true }
                else { 
                    // Retry once after 200ms
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val retry = service?.goHome() ?: false
                        if (!retry) service?.speak("Failed to go home")
                    }, 200)
                    false
                }
            }
            SmartCommandMatcher.CommandIntent.GO_BACK -> {
                val svc = service ?: return notConnected()
                val result = svc.goBack()
                if (result) { svc.speak("Going back"); true }
                else { svc.speak("Failed to go back"); false }
            }
            SmartCommandMatcher.CommandIntent.OPEN_RECENTS -> {
                val svc = service ?: return notConnected()
                val result = svc.openRecents()
                if (result) { svc.speak("Opening recent apps"); true }
                else { svc.speak("Failed to open recents"); false }
            }
            SmartCommandMatcher.CommandIntent.OPEN_NOTIFICATIONS -> {
                val svc = service ?: return notConnected()
                val result = svc.openNotifications()
                if (result) { svc.speak("Opening notifications"); true }
                else { svc.speak("Failed to open notifications"); false }
            }
            SmartCommandMatcher.CommandIntent.SWIPE_LEFT -> {
                val svc = service ?: return notConnected()
                val startX = svc.screenWidth * 0.8f
                val endX = svc.screenWidth * 0.2f
                val y = svc.screenHeight / 2f
                val path = android.graphics.Path().apply {
                    moveTo(startX, y)
                    lineTo(endX, y)
                }
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 250))
                    .build()
                svc.dispatchGesture(gesture, null, null)
                svc.speak("Swiped left")
                true
            }
            SmartCommandMatcher.CommandIntent.SWIPE_RIGHT -> {
                val svc = service ?: return notConnected()
                val startX = svc.screenWidth * 0.2f
                val endX = svc.screenWidth * 0.8f
                val y = svc.screenHeight / 2f
                val path = android.graphics.Path().apply {
                    moveTo(startX, y)
                    lineTo(endX, y)
                }
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 250))
                    .build()
                svc.dispatchGesture(gesture, null, null)
                svc.speak("Swiped right")
                true
            }
            SmartCommandMatcher.CommandIntent.OPEN_QUICK_SETTINGS -> {
                val svc = service ?: return notConnected()
                val result = svc.openQuickSettings()
                if (result) { svc.speak("Opening quick settings"); true }
                else { svc.speak("Failed to open quick settings"); false }
            }

            // === LOCK / UNLOCK ===
            SmartCommandMatcher.CommandIntent.UNLOCK_PHONE -> handleUnlockCommand(command)
            SmartCommandMatcher.CommandIntent.LOCK_PHONE -> service?.lockScreen() ?: notConnected()

            // === SCROLLING ===
            SmartCommandMatcher.CommandIntent.SCROLL_DOWN -> appNavigator.scrollDown()
            SmartCommandMatcher.CommandIntent.SCROLL_UP -> appNavigator.scrollUp()

            // === VOLUME ===
            SmartCommandMatcher.CommandIntent.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            SmartCommandMatcher.CommandIntent.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            SmartCommandMatcher.CommandIntent.MUTE -> adjustVolume(AudioManager.ADJUST_MUTE)
            SmartCommandMatcher.CommandIntent.UNMUTE -> adjustVolume(AudioManager.ADJUST_UNMUTE)

            // === CONNECTIVITY - WiFi ===
            SmartCommandMatcher.CommandIntent.WIFI_ON -> handleToggle("WiFi", directToggleController.setWifi(true), true, listOf("Wi-Fi", "WiFi", "Wi‑Fi", "WLAN", "Internet"))
            SmartCommandMatcher.CommandIntent.WIFI_OFF -> handleToggle("WiFi", directToggleController.setWifi(false), false, listOf("Wi-Fi", "WiFi", "Wi‑Fi", "WLAN", "Internet"))

            // === CONNECTIVITY - Bluetooth ===
            SmartCommandMatcher.CommandIntent.BLUETOOTH_ON -> handleToggle("Bluetooth", directToggleController.setBluetooth(true), true, listOf("Bluetooth", "BT"))
            SmartCommandMatcher.CommandIntent.BLUETOOTH_OFF -> handleToggle("Bluetooth", directToggleController.setBluetooth(false), false, listOf("Bluetooth", "BT"))

            // === CONNECTIVITY - Mobile Data ===
            SmartCommandMatcher.CommandIntent.MOBILE_DATA_ON -> handleToggle("Mobile data", directToggleController.setMobileData(true), true, listOf("Mobile data", "Mobile Data", "Data", "Mobile Data.", "Cellular data", "Cellular", "Internet", "Data connection", "SIM"))
            SmartCommandMatcher.CommandIntent.MOBILE_DATA_OFF -> handleToggle("Mobile data", directToggleController.setMobileData(false), false, listOf("Mobile data", "Mobile Data", "Data", "Mobile Data.", "Cellular data", "Cellular", "Internet", "Data connection", "SIM"))

            // === DND ===
            SmartCommandMatcher.CommandIntent.DND_ON -> handleToggle("Do not disturb", directToggleController.setDND(true))
            SmartCommandMatcher.CommandIntent.DND_OFF -> handleToggle("Do not disturb", directToggleController.setDND(false))

            // === HOTSPOT ===
            SmartCommandMatcher.CommandIntent.HOTSPOT_ON -> handleToggle("Hotspot", directToggleController.setHotspot(true), true, listOf("Hotspot", "Wi-Fi hotspot", "Personal Hotspot", "Tethering"))
            SmartCommandMatcher.CommandIntent.HOTSPOT_OFF -> handleToggle("Hotspot", directToggleController.setHotspot(false), false, listOf("Hotspot", "Wi-Fi hotspot", "Personal Hotspot", "Tethering"))

            // === AIRPLANE MODE ===
            SmartCommandMatcher.CommandIntent.AIRPLANE_MODE_ON -> handleToggle("Airplane mode", directToggleController.setAirplaneMode(true), true, listOf("Airplane mode", "Aeroplane mode", "Flight mode", "Airplane", "Flight"))
            SmartCommandMatcher.CommandIntent.AIRPLANE_MODE_OFF -> handleToggle("Airplane mode", directToggleController.setAirplaneMode(false), false, listOf("Airplane mode", "Aeroplane mode", "Flight mode", "Airplane", "Flight"))

            // === DARK MODE ===
            SmartCommandMatcher.CommandIntent.DARK_MODE_ON -> handleToggle("Dark mode", directToggleController.setDarkMode(true), true, listOf("Dark theme", "Dark mode", "Night mode", "Dark"))
            SmartCommandMatcher.CommandIntent.DARK_MODE_OFF -> handleToggle("Dark mode", directToggleController.setDarkMode(false), false, listOf("Dark theme", "Dark mode", "Night mode", "Dark"))

            // === LOCATION ===
            SmartCommandMatcher.CommandIntent.LOCATION_ON -> handleToggle("Location", directToggleController.setLocation(true), true, listOf("Location", "GPS"))
            SmartCommandMatcher.CommandIntent.LOCATION_OFF -> handleToggle("Location", directToggleController.setLocation(false), false, listOf("Location", "GPS"))

            // === AUTO ROTATE ===
            SmartCommandMatcher.CommandIntent.AUTO_ROTATE_ON -> handleToggle("Auto rotate", directToggleController.setAutoRotate(true), true, listOf("Auto-rotate", "Auto rotate", "Rotation"))
            SmartCommandMatcher.CommandIntent.AUTO_ROTATE_OFF -> handleToggle("Auto rotate", directToggleController.setAutoRotate(false), false, listOf("Auto-rotate", "Auto rotate", "Rotation"))

            // === FLASHLIGHT (via CameraManager — instant, no QS needed) ===
            SmartCommandMatcher.CommandIntent.FLASHLIGHT_ON -> flashlightController.turnOn()
            SmartCommandMatcher.CommandIntent.FLASHLIGHT_OFF -> flashlightController.turnOff()

            // === GENERIC QS TILE TOGGLE ===
            SmartCommandMatcher.CommandIntent.TOGGLE_QS_TILE -> {
                val tileName = extractQsTileName(command)
                val tileLabels = getQsTileLabels(tileName)
                val isOn = !command.contains("off") && !command.contains("band") && !command.contains("बंद")
                toggleViaQuickSettings(tileName, isOn, tileLabels)
            }

            // === APP LAUNCHING ===
            SmartCommandMatcher.CommandIntent.OPEN_APP -> {
                val appName = param.ifBlank {
                    command.replace("open", "").replace("launch", "").replace("start", "").trim()
                }
                val result = appNavigator.openApp(appName)
                if (result) {
                    // Update navigation context with the opened app
                    navigationContext.updateApp(appName)
                }
                result
            }

            // === PHONE CALLS ===
            SmartCommandMatcher.CommandIntent.TOGGLE_SPEAKER -> toggleSpeaker()
            SmartCommandMatcher.CommandIntent.CALL_CONTACT -> {
                var input = param.ifBlank {
                    command.replace("call", "").replace("dial", "").replace("phone", "").trim()
                }
                // Check for "in speaker" / "on speaker" suffix
                val wantSpeaker = input.contains(Regex("(?i)\\s+(in|on)\\s+speaker"))
                input = input.replace(Regex("(?i)\\s+(in|on)\\s+speaker"), "").trim()
                
                val callResult = if (input.any { it.isDigit() }) makePhoneCall(input) else callByContactName(input)
                if (callResult && wantSpeaker) {
                    // Auto-enable speaker after a short delay for the call to connect
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.isSpeakerphoneOn = true
                        audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                        service?.speak("Speaker on")
                    }, 2000)
                }
                callResult
            }

            // === ANSWER CALL (set active call state) ===
            SmartCommandMatcher.CommandIntent.ANSWER_CALL -> {
                val result = answerCall()
                if (result) {
                    isOnActiveCall = true
                    // Check for "in speaker" / "on speaker"
                    val wantSpeaker = command.contains(Regex("(?i)(in|on)\\s+speaker"))
                    if (wantSpeaker) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            audioManager.isSpeakerphoneOn = true
                            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                            service?.speak("Speaker on")
                        }, 2000)
                    }
                }
                result
            }
            SmartCommandMatcher.CommandIntent.END_CALL -> {
                val result = endCall()
                isOnActiveCall = false
                result
            }

            // === SMS ===
            SmartCommandMatcher.CommandIntent.READ_SMS -> readLastSms()

            // === MEDIA ===
            SmartCommandMatcher.CommandIntent.PLAY_MUSIC -> sendMediaAction(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            SmartCommandMatcher.CommandIntent.PAUSE_MUSIC -> sendMediaAction(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            SmartCommandMatcher.CommandIntent.NEXT_SONG -> sendMediaAction(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            SmartCommandMatcher.CommandIntent.PLAY_SONG -> {
                val songName = param.ifBlank {
                    command.replace("play song", "").replace("play music", "").replace("play", "").trim()
                }
                playSong(songName)
            }
            SmartCommandMatcher.CommandIntent.PREVIOUS_SONG -> sendMediaAction(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            SmartCommandMatcher.CommandIntent.TOGGLE_PLAY_PAUSE -> sendMediaAction(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            SmartCommandMatcher.CommandIntent.STOP_MUSIC -> sendMediaAction(android.view.KeyEvent.KEYCODE_MEDIA_STOP)

            // === YOUTUBE SEARCH ===
            SmartCommandMatcher.CommandIntent.SEARCH_YOUTUBE -> {
                val query = param.ifBlank {
                    command.replace("search", "").replace("youtube", "").replace("on", "").replace("for", "").trim()
                }
                searchOnYouTube(query)
            }

            // === SCREEN ===
            SmartCommandMatcher.CommandIntent.READ_SCREEN -> {
                val screenText = service?.readScreen() ?: ""
                if (screenText.isNotBlank()) service?.speak(screenText)
                else service?.speak("Screen is empty or could not be read")
                true
            }
            SmartCommandMatcher.CommandIntent.DESCRIBE_SCREEN -> {
                val desc = service?.describeScreen() ?: "Cannot read screen"
                service?.speak(desc); true
            }
            SmartCommandMatcher.CommandIntent.LIST_CLICKABLE -> {
                val items = service?.listClickableItems() ?: emptyList()
                if (items.isEmpty()) {
                    service?.speak("No clickable items found")
                } else {
                    val list = items.mapIndexed { i, it -> "${i + 1}. $it" }.joinToString(". ")
                    service?.speak("${items.size} clickable items: $list")
                }
                true
            }
            SmartCommandMatcher.CommandIntent.TAKE_SCREENSHOT -> takeScreenshot()

            // === BATTERY ===
            SmartCommandMatcher.CommandIntent.READ_BATTERY -> readBattery()

            // === ROTATION ===
            SmartCommandMatcher.CommandIntent.ROTATION_ON -> toggleAutoRotate(true)
            SmartCommandMatcher.CommandIntent.ROTATION_OFF -> toggleAutoRotate(false)

            // === BRIGHTNESS ===
            SmartCommandMatcher.CommandIntent.BRIGHTNESS_UP -> adjustBrightness(+30)
            SmartCommandMatcher.CommandIntent.BRIGHTNESS_DOWN -> adjustBrightness(-30)
            SmartCommandMatcher.CommandIntent.BRIGHTNESS_MAX -> setBrightness(255)
            SmartCommandMatcher.CommandIntent.BRIGHTNESS_MIN -> setBrightness(20)
            SmartCommandMatcher.CommandIntent.BRIGHTNESS_HALF -> setBrightness(128)

            // === ALARM & TIMER ===
            SmartCommandMatcher.CommandIntent.SET_ALARM -> {
                val timeStr = param.ifBlank {
                    command.replace(Regex(".*(alarm|set alarm)\\s*(for|at)?\\s*"), "").trim()
                }
                setAlarm(timeStr)
            }
            SmartCommandMatcher.CommandIntent.SET_TIMER -> {
                val durationStr = param.ifBlank {
                    command.replace(Regex(".*(timer|set timer)\\s*(for)?\\s*"), "").trim()
                }
                setTimer(durationStr)
            }

            // === SEARCH ===
            SmartCommandMatcher.CommandIntent.SEARCH_GOOGLE -> {
                val query = param.ifBlank {
                    command.replace("search", "").replace("google", "").replace("for", "").replace("on", "").trim()
                }
                // Check the ACTUAL foreground app via accessibility (not navigationContext)
                val currentPkg = service?.rootInActiveWindow?.packageName?.toString()?.lowercase() ?: ""
                val currentAppName = service?.getCurrentAppName() ?: ""
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                
                when {
                    // YouTube is open — use YouTube search
                    currentPkg.contains("youtube") -> {
                        service?.speak("Searching on YouTube")
                        if (query.isNotBlank()) {
                            handler.postDelayed({
                                service?.findAndClickSmart("Search", silent = true)
                                handler.postDelayed({
                                    service?.findAndFocusTextField()
                                    handler.postDelayed({
                                        service?.inputText(query)
                                    }, 300)
                                }, 600)
                            }, 200)
                        }
                        true
                    }
                    // Instagram is open — use in-app search
                    currentPkg.contains("instagram") -> {
                        service?.speak("Searching on Instagram")
                        handler.postDelayed({
                            val svc = service ?: return@postDelayed
                            val clicked = svc.findAndClickSmart("Search", silent = true) 
                                || svc.findAndClickSmart("Explore", silent = true)
                            if (clicked) {
                                handler.postDelayed({
                                    svc.findAndFocusTextField()
                                    handler.postDelayed({
                                        if (query.isNotBlank()) svc.inputText(query)
                                    }, 300)
                                }, 600)
                            }
                        }, 200)
                        true
                    }
                    // Play Store is open
                    currentPkg.contains("vending") || currentPkg.contains("play.store") -> {
                        service?.speak("Searching on Play Store")
                        handler.postDelayed({
                            val svc = service ?: return@postDelayed
                            svc.findAndClickSmart("Search", silent = true)
                            handler.postDelayed({
                                svc.findAndFocusTextField()
                                handler.postDelayed({
                                    if (query.isNotBlank()) svc.inputText(query)
                                }, 300)
                            }, 600)
                        }, 200)
                        true
                    }
                    // Any other non-home app — try generic in-app search
                    currentPkg.isNotBlank() && !currentPkg.contains("launcher") && 
                    !currentPkg.contains("home") && !currentPkg.contains("systemui") -> {
                        val appName = if (currentAppName.isNotBlank() && currentAppName != "unknown") 
                            currentAppName else "this app"
                        service?.speak("Searching in $appName")
                        handler.postDelayed({
                            val svc = service ?: return@postDelayed
                            val clicked = svc.findAndClickSmart("search", silent = true)
                                || svc.findAndClickSmart("Search", silent = true)
                            if (clicked && query.isNotBlank()) {
                                handler.postDelayed({
                                    svc.findAndFocusTextField()
                                    handler.postDelayed({
                                        svc.inputText(query)
                                    }, 300)
                                }, 600)
                            } else if (!clicked) {
                                svc.speak("No search found in $appName, searching on Google")
                                searchOnGoogle(query)
                            }
                        }, 200)
                        true
                    }
                    // On home/launcher — default to Google
                    else -> {
                        searchOnGoogle(query)
                    }
                }
            }

            // === NOTIFICATIONS ===
            SmartCommandMatcher.CommandIntent.CLEAR_NOTIFICATIONS -> service?.clearAllNotifications() ?: notConnected()
            SmartCommandMatcher.CommandIntent.READ_NOTIFICATIONS -> readNotifications()

            // === TYPE TEXT ===
            SmartCommandMatcher.CommandIntent.TYPE_TEXT -> {
                val text = param.ifBlank { rawCommand.trim().let { it.substring(it.indexOf(' ') + 1) } }
                typeText(text)
            }

            // === CLICK/TAP ===
            SmartCommandMatcher.CommandIntent.CLICK_TARGET -> {
                val target = param.ifBlank { command.replace("click", "").replace("tap", "").replace("press", "").trim() }
                // Use smart fuzzy finder instead of exact-match clickOnText
                service?.findAndClickSmart(target) ?: notConnected()
            }
            SmartCommandMatcher.CommandIntent.SELECT_ITEM -> {
                val index = param.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                service?.clickItemAtIndex(index) ?: notConnected()
            }

            // === INFO ===
            SmartCommandMatcher.CommandIntent.TELL_TIME -> tellTime()
            SmartCommandMatcher.CommandIntent.TELL_DAY -> tellDay()
            SmartCommandMatcher.CommandIntent.WHICH_APP -> {
                val appName = service?.getCurrentAppName() ?: "unknown"
                service?.speak("You are in $appName"); true
            }
            SmartCommandMatcher.CommandIntent.SHOW_HELP -> showHelp()
            SmartCommandMatcher.CommandIntent.REPEAT_LAST -> {
                // Replay last action (not just repeat last TTS text)
                if (lastAction != null) {
                    service?.speak("Repeating")
                    executeIntent(lastAction!!.intent, lastAction!!.command, lastAction!!.rawCommand, lastAction!!.param)
                } else {
                    val last = service?.lastSpokenText
                    if (!last.isNullOrBlank()) service?.speak(last) else service?.speak("Nothing to repeat")
                    true
                }
            }

            // === MESSAGING ===
            SmartCommandMatcher.CommandIntent.SEND_SMS -> {
                val body = param.ifBlank {
                    command.removePrefix("send message to ").removePrefix("sms ").trim()
                }
                sendSms(body)
            }
            SmartCommandMatcher.CommandIntent.SEND_WHATSAPP -> {
                val body = param.ifBlank {
                    command.removePrefix("send whatsapp to ").removePrefix("whatsapp message to ").trim()
                }
                // Route through CommandRouter → WhatsAppAutomation (accessibility-based)
                val router = AutomationAccessibilityService.instance?.commandRouter
                if (router != null && router.route("send whatsapp to $body")) {
                    true
                } else {
                    sendWhatsApp(body) // fallback to wa.me URL if router unavailable
                }
            }

            // === CHAT MODE ===
            SmartCommandMatcher.CommandIntent.ENTER_CHAT -> enterChatMode(command)
            SmartCommandMatcher.CommandIntent.SEND_CHAT_MSG -> {
                val msg = param.ifBlank { rawCommand.trim().substring(5) }
                chatSendMessage(msg)
            }
            SmartCommandMatcher.CommandIntent.READ_CHAT -> chatReadMessages()
            SmartCommandMatcher.CommandIntent.EXIT_CHAT -> exitChatMode()

            // === DAILY LIFE ===
            SmartCommandMatcher.CommandIntent.OPEN_WEATHER -> openWeather()
            SmartCommandMatcher.CommandIntent.TAKE_PHOTO -> {
                // Open camera app and click shutter via accessibility
                val cameraOpened = appNavigator.openApp("camera")
                if (cameraOpened) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // Try to click shutter button via accessibility
                        val clicked = service?.findAndClickSmart("shutter") ?: false
                        if (!clicked) {
                            // Fallback: try other common camera button names
                            service?.findAndClickSmart("capture") ?:
                            service?.findAndClickSmart("take photo") ?:
                            service?.findAndClickSmart("photo")
                        }
                    }, 1500) // Wait for camera app to fully load
                }
                true
            }
            SmartCommandMatcher.CommandIntent.READ_CLIPBOARD -> readClipboard()
            SmartCommandMatcher.CommandIntent.COPY -> {
                // Copy selected text via the focused node's ACTION_COPY
                val svc = service ?: return notConnected()
                val focusedNode = svc.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                val copied = focusedNode?.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY
                ) ?: false
                if (copied) svc.speak("Copied to clipboard")
                else svc.speak("Select text first, then say copy")
                true
            }

            // === EMERGENCY ===
            SmartCommandMatcher.CommandIntent.EMERGENCY -> emergencyCall()
            SmartCommandMatcher.CommandIntent.SEND_LOCATION -> sendLocation()
            SmartCommandMatcher.CommandIntent.RECORD_VIDEO -> {
                // If already in camera app, switch to video mode and record
                val currentApp = service?.getCurrentAppName()?.lowercase() ?: ""
                if (currentApp.contains("camera")) {
                    service?.findAndClickSmart("video")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        service?.findAndClickSmart("record") ?:
                        service?.findAndClickSmart("shutter") ?:
                        service?.findAndClickSmart("start")
                    }, 800)
                } else {
                    // Open camera first, then switch to video
                    appNavigator.openApp("camera")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        service?.findAndClickSmart("video")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            service?.findAndClickSmart("record") ?:
                            service?.findAndClickSmart("shutter")
                        }, 800)
                    }, 1500)
                }
                true
            }
            SmartCommandMatcher.CommandIntent.STOP_RECORDING -> {
                // Click stop/record button to stop recording
                service?.findAndClickSmart("stop") ?:
                service?.findAndClickSmart("record") ?:
                service?.findAndClickSmart("shutter") ?: false
            }

            // === NAVIGATION / MAPS ===
            SmartCommandMatcher.CommandIntent.OPEN_MAP -> {
                openGoogleMaps()
                navigationState = NavigationState.WAITING_FOR_DESTINATION
                service?.speak("Where do you want to go?")
                true
            }
            SmartCommandMatcher.CommandIntent.NAVIGATE -> {
                if (param.isNotBlank()) {
                    // Direct: "navigate to Delhi" — search first then ask to start
                    pendingDestination = param
                    searchInGoogleMaps(param)
                    navigationState = NavigationState.CONFIRMING_START
                    service?.speak("Found $param. Should I start navigation?")
                } else {
                    // Just "navigate" — ask for destination
                    openGoogleMaps()
                    navigationState = NavigationState.WAITING_FOR_DESTINATION
                    service?.speak("Where do you want to go?")
                }
                true
            }

            // === RAPIDO RIDE BOOKING ===
            SmartCommandMatcher.CommandIntent.BOOK_RAPIDO -> {
                handleBookRapido(param)
                true
            }
            SmartCommandMatcher.CommandIntent.READ_RAPIDO_PIN -> {
                handleReadRapidoPin()
                true
            }
            SmartCommandMatcher.CommandIntent.CANCEL_RAPIDO -> {
                handleCancelRapido()
                true
            }

            // === VISION ASSISTANCE ===
            SmartCommandMatcher.CommandIntent.DESCRIBE_SCENE -> {
                getVision().describeScene(if (param.isNotBlank()) param else null)
                true
            }
            SmartCommandMatcher.CommandIntent.WHO_IS_THERE -> {
                getVision().whoIsThere()
                true
            }
            SmartCommandMatcher.CommandIntent.READ_TEXT_VISION -> {
                getVision().readText()
                true
            }
            SmartCommandMatcher.CommandIntent.START_AUTO_DESCRIBE -> {
                // Check if user specified interval: "describe every 5 seconds"
                val intervalMatch = Regex("(\\d+)\\s*(?:second|sec)").find(command)
                val interval = intervalMatch?.groupValues?.get(1)?.toIntOrNull()
                getVision().startAutoDescribe(interval)
                true
            }
            SmartCommandMatcher.CommandIntent.STOP_AUTO_DESCRIBE -> {
                getVision().stopAutoDescribe()
                true
            }
            SmartCommandMatcher.CommandIntent.REMEMBER_FACE -> {
                // Extract name: "remember this face as Rajesh" / "remember him as Rajesh" → "Rajesh"
                val name = command.replace(Regex(".*(?:face\\s+as|him\\s+as|her\\s+as|this\\s+as|face|chehra)\\s*", RegexOption.IGNORE_CASE), "").trim()
                    .ifBlank { param }
                if (name.isNotBlank()) {
                    getVision().rememberFace(name)
                } else {
                    service?.speak("Please say the name. For example: remember this face as Rajesh.")
                }
                true
            }
            SmartCommandMatcher.CommandIntent.FORGET_FACE -> {
                // Extract name: "forget Rajesh's face" → "Rajesh"
                val name = command.replace(Regex(".*(?:forget|delete|remove|bhool|hata)\\s*"), "")
                    .replace(Regex("'?s?\\s*(?:face|chehra).*"), "").trim()
                    .ifBlank { param }
                if (name.isNotBlank()) {
                    getVision().forgetFace(name)
                } else {
                    service?.speak("Please say the name of the person to forget.")
                }
                true
            }
            SmartCommandMatcher.CommandIntent.LIST_KNOWN_FACES -> {
                getVision().listKnownFaces()
                true
            }
            SmartCommandMatcher.CommandIntent.WHAT_CHANGED -> {
                getVision().whatChanged()
                true
            }
            SmartCommandMatcher.CommandIntent.IS_PATH_SAFE -> {
                getVision().isPathSafe()
                true
            }
            SmartCommandMatcher.CommandIntent.FIND_OBJECT -> {
                // Extract object: "where is my bag" → "bag"
                val obj = command.replace(Regex(".*(?:where is|find|locate|kahan|dikha)\\s*(?:my|mera|meri)?\\s*"), "").trim()
                    .ifBlank { param }
                if (obj.isNotBlank()) {
                    getVision().findObject(obj)
                } else {
                    service?.speak("What are you looking for?")
                }
                true
            }
            SmartCommandMatcher.CommandIntent.START_NAVIGATION_MODE -> {
                getVision().startNavigation()
                true
            }
            SmartCommandMatcher.CommandIntent.STOP_NAVIGATION_MODE -> {
                getVision().stopNavigation()
                true
            }
            SmartCommandMatcher.CommandIntent.VISION_FOLLOW_UP -> {
                getVision().handleFollowUp(command)
                true
            }
        }
    }

    // ==================== GOOGLE MAPS NAVIGATION ====================

    /**
     * Handle navigation state machine.
     * Called when navigationState != NONE — intercepts all commands.
     */
    private fun handleNavigationState(command: String): Boolean {
        val cancelWords = setOf("cancel", "no", "nahi", "nah", "na", "mat", "chhodo", "band",
            "रद्द", "नहीं", "मत", "छोड़ो", "बंद", "stop", "ruk", "back")
        val yesWords = setOf("yes", "haan", "ha", "han", "ok", "okay", "sure", "theek", "sahi",
            "right", "chalo", "shuru", "start",
            "हां", "ठीक", "सही", "चलो", "शुरू")
        val walkWords = setOf("walk", "walking", "paidal", "पैदल", "on foot")
        val transitWords = setOf("bus", "metro", "train", "transit", "public transport",
            "बस", "मेट्रो", "ट्रेन")
        val bikeWords = setOf("bike", "cycle", "bicycle", "cycling", "साइकिल")

        when (navigationState) {
            NavigationState.WAITING_FOR_DESTINATION -> {
                // User is supposed to say the destination
                if (cancelWords.any { command.contains(it) }) {
                    navigationState = NavigationState.NONE
                    pendingDestination = ""
                    service?.speak("Navigation cancelled")
                    return true
                }

                // Anything else is treated as the destination
                pendingDestination = command.trim()
                searchInGoogleMaps(pendingDestination)
                navigationState = NavigationState.CONFIRMING_START
                service?.speak("Found $pendingDestination. Should I start navigation?")
                return true
            }

            NavigationState.CONFIRMING_START -> {
                // User should say yes/no or a travel mode
                if (cancelWords.any { command.contains(it) }) {
                    navigationState = NavigationState.NONE
                    pendingDestination = ""
                    service?.speak("Navigation cancelled")
                    return true
                }

                // Check for travel mode before starting
                when {
                    walkWords.any { command.contains(it) } -> {
                        navigationMode = "w"
                        startNavigation(pendingDestination, "w")
                        service?.speak("Starting walking navigation to $pendingDestination")
                        navigationState = NavigationState.NONE
                        return true
                    }
                    transitWords.any { command.contains(it) } -> {
                        navigationMode = "r"
                        startNavigation(pendingDestination, "r")
                        service?.speak("Starting transit navigation to $pendingDestination")
                        navigationState = NavigationState.NONE
                        return true
                    }
                    bikeWords.any { command.contains(it) } -> {
                        navigationMode = "b"
                        startNavigation(pendingDestination, "b")
                        service?.speak("Starting cycling navigation to $pendingDestination")
                        navigationState = NavigationState.NONE
                        return true
                    }
                    yesWords.any { command.contains(it) } -> {
                        startNavigation(pendingDestination, navigationMode)
                        service?.speak("Starting navigation to $pendingDestination")
                        navigationState = NavigationState.NONE
                        return true
                    }
                    else -> {
                        service?.speak("Say yes to start, or walking, transit, or cycling for a different mode. Say cancel to stop.")
                        return true
                    }
                }
            }

            NavigationState.NONE -> return false // Should not reach here
        }
    }

    // ==================== RAPIDO RIDE BOOKING ====================

    /**
     * Handle the BOOK_RAPIDO intent.
     * Opens Rapido and starts the booking flow.
     * If a destination is provided (e.g., "book rapido to airport"), searches directly.
     */
    private fun handleBookRapido(destination: String) {
        Log.i(TAG, "RAPIDO: Starting booking flow. destination='$destination'")

        if (!rapidoAutomation.openApp()) {
            service?.speak("Rapido is not installed")
            return
        }

        service?.speak("Opening Rapido")

        if (destination.isNotBlank()) {
            // User said "book rapido to [place]" — search directly after app opens
            rapidoPendingDestination = destination
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                rapidoAutomation.searchDestination(destination) { suggestions ->
                    rapidoSuggestions = suggestions
                    if (suggestions.isEmpty()) {
                        service?.speak("Could not find $destination. Please try again.")
                        rapidoState = RapidoState.WAITING_FOR_DESTINATION
                    } else if (suggestions.size == 1) {
                        // Only one suggestion — confirm it
                        service?.speak("${suggestions[0]}. Is this correct?")
                        rapidoState = RapidoState.CONFIRMING_DESTINATION
                    } else {
                        // Multiple — ask which one
                        val msg = StringBuilder()
                        for ((i, s) in suggestions.withIndex()) {
                            msg.append("${i + 1}. $s. ")
                        }
                        msg.append("Which one?")
                        service?.speak(msg.toString())
                        rapidoState = RapidoState.CONFIRMING_DESTINATION
                    }
                }
            }, 3000) // Wait 3s for Rapido to fully load
        } else {
            // Just "open rapido" — ask for destination
            rapidoState = RapidoState.WAITING_FOR_DESTINATION
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                service?.speak("Where do you want to go?")
            }, 2500)
        }
    }

    /**
     * Handle the READ_RAPIDO_PIN intent.
     * Reads the Rapid PIN from the current screen.
     */
    private fun handleReadRapidoPin() {
        Log.i(TAG, "RAPIDO: Reading PIN")
        val pin = rapidoAutomation.readPin()
        if (pin != null) {
            service?.speak("Your Rapido PIN is $pin")
        } else {
            service?.speak("PIN not visible on screen. Please open Rapido first.")
        }
    }

    /**
     * Handle the CANCEL_RAPIDO intent.
     * Cancels the current ride or resets booking state.
     */
    private fun handleCancelRapido() {
        Log.i(TAG, "RAPIDO: Cancelling")

        // If in a booking flow state, just reset
        if (rapidoState != RapidoState.NONE) {
            rapidoState = RapidoState.NONE
            rapidoPendingDestination = ""
            rapidoSuggestions = emptyList()
            rapidoSelectedRideType = ""
            rapidoSelectedFare = ""
            service?.speak("Rapido booking cancelled")
            return
        }

        // If Rapido is in foreground, try to cancel the active ride
        if (rapidoAutomation.isRapidoInForeground()) {
            service?.speak("Cancelling ride")
            rapidoAutomation.cancelRide { success ->
                if (success) {
                    service?.speak("Ride cancelled")
                } else {
                    service?.speak("Could not cancel ride. Please cancel manually.")
                }
            }
        } else {
            service?.speak("No active Rapido booking to cancel")
        }
    }

    /**
     * Rapido conversational state machine.
     * Handles all intermediate states of the booking flow.
     */
    private fun handleRapidoState(command: String): Boolean {
        val cancelWords = setOf("cancel", "no", "nahi", "nah", "na", "mat", "chhodo", "band",
            "रद्द", "नहीं", "मत", "छोड़ो", "बंद", "stop", "ruk", "back")
        val yesWords = setOf("yes", "haan", "ha", "han", "ok", "okay", "sure", "theek", "sahi",
            "right", "correct", "book", "confirm",
            "हां", "ठीक", "सही", "बुक", "कन्फर्म")

        // Cancel at any state
        if (cancelWords.any { command.contains(it) }) {
            rapidoState = RapidoState.NONE
            rapidoPendingDestination = ""
            rapidoSuggestions = emptyList()
            rapidoSelectedRideType = ""
            rapidoSelectedFare = ""
            service?.speak("Rapido booking cancelled")
            return true
        }

        when (rapidoState) {
            RapidoState.WAITING_FOR_DESTINATION -> {
                // User says the destination name
                rapidoPendingDestination = command.trim()
                service?.speak("Searching $rapidoPendingDestination")

                rapidoAutomation.searchDestination(rapidoPendingDestination) { suggestions ->
                    rapidoSuggestions = suggestions
                    if (suggestions.isEmpty()) {
                        service?.speak("No results found for $rapidoPendingDestination. Please say the destination again.")
                        // Stay in WAITING_FOR_DESTINATION
                    } else if (suggestions.size == 1) {
                        service?.speak("${suggestions[0]}. Is this correct?")
                        rapidoState = RapidoState.CONFIRMING_DESTINATION
                    } else {
                        val msg = StringBuilder()
                        for ((i, s) in suggestions.withIndex()) {
                            msg.append("${i + 1}. $s. ")
                        }
                        msg.append("Which one?")
                        service?.speak(msg.toString())
                        rapidoState = RapidoState.CONFIRMING_DESTINATION
                    }
                }
                return true
            }

            RapidoState.CONFIRMING_DESTINATION -> {
                // User confirms destination or picks from options
                val selected = rapidoAutomation.selectSuggestion(command, rapidoSuggestions)
                if (selected) {
                    service?.speak("Destination selected")

                    // Wait for ride options to load, read pickup location
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed({
                        val pickup = rapidoAutomation.readPickupLocation()
                        service?.speak("Pickup is $pickup. Is this correct?")
                        rapidoState = RapidoState.CONFIRMING_PICKUP
                    }, 2500)
                } else {
                    service?.speak("Could not select destination. Please try again.")
                    rapidoState = RapidoState.WAITING_FOR_DESTINATION
                }
                return true
            }

            RapidoState.CONFIRMING_PICKUP -> {
                if (yesWords.any { command.contains(it) }) {
                    // Pickup confirmed — read ride options
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed({
                        val rideOptions = rapidoAutomation.readRideOptions()
                        if (rideOptions.isEmpty()) {
                            service?.speak("Ride options are loading. Please wait.")
                            // Retry after a delay
                            handler.postDelayed({
                                val retryOptions = rapidoAutomation.readRideOptions()
                                announceRideOptions(retryOptions)
                            }, 3000)
                        } else {
                            announceRideOptions(rideOptions)
                        }
                    }, 1500)
                } else {
                    // User wants to change pickup
                    service?.speak("Please change pickup location in Rapido app, then tell me when ready.")
                    // Stay in CONFIRMING_PICKUP to wait for user to say "ok" / "done" / "ready"
                }
                return true
            }

            RapidoState.SELECTING_RIDE_TYPE -> {
                // User says "bike", "auto", or "cab"
                val rideType = extractRideType(command)
                if (rideType != null) {
                    rapidoSelectedRideType = rideType
                    val selected = rapidoAutomation.selectRideType(rideType)
                    if (selected) {
                        // Read fare for selected ride
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        handler.postDelayed({
                            val options = rapidoAutomation.readRideOptions()
                            val matchedFare = options.find { it.type.contains(rideType, ignoreCase = true) }?.fare ?: ""
                            rapidoSelectedFare = matchedFare
                            service?.speak("$rideType selected. $matchedFare. Should I book?")
                            rapidoState = RapidoState.CONFIRMING_BOOKING
                        }, 1500)
                    } else {
                        service?.speak("Could not select $rideType. Please try again.")
                    }
                } else {
                    service?.speak("Please say Bike, Auto, or Cab.")
                }
                return true
            }

            RapidoState.CONFIRMING_BOOKING -> {
                if (yesWords.any { command.contains(it) }) {
                    // Confirm booking
                    val booked = rapidoAutomation.confirmBooking(rapidoSelectedRideType)
                    if (booked) {
                        service?.speak("Ride booked! Finding your captain.")

                        // Poll for captain details
                        pollForCaptain(0)
                    } else {
                        service?.speak("Could not confirm booking. Please try tapping the book button manually.")
                    }
                    rapidoState = RapidoState.NONE
                } else {
                    service?.speak("Booking not confirmed. Say yes to book or cancel to stop.")
                }
                return true
            }

            RapidoState.NONE -> return false
        }
    }

    /**
     * Announce ride options to the user and move to SELECTING_RIDE_TYPE state.
     */
    private fun announceRideOptions(options: List<RapidoAutomation.RideOption>) {
        if (options.isEmpty()) {
            service?.speak("Could not read ride options. Please select manually in Rapido.")
            rapidoState = RapidoState.NONE
            return
        }

        val msg = StringBuilder()
        for (option in options) {
            msg.append("${option.type} ${option.fare}. ")
        }
        msg.append("Which one? Bike, Auto, or Cab?")
        service?.speak(msg.toString())
        rapidoState = RapidoState.SELECTING_RIDE_TYPE
    }

    /**
     * Extract ride type from user's command.
     */
    private fun extractRideType(command: String): String? {
        val lower = command.lowercase()
        return when {
            lower.contains("bike") -> "Bike"
            lower.contains("auto") -> "Auto"
            lower.contains("cab") || lower.contains("car") -> "Cab"
            lower.contains("ऑटो") -> "Auto"
            lower.contains("बाइक") -> "Bike"
            lower.contains("कैब") || lower.contains("कार") -> "Cab"
            else -> null
        }
    }

    /**
     * Poll for captain assignment after booking.
     * Checks every 5 seconds, up to 6 attempts (30 seconds).
     */
    private fun pollForCaptain(attempt: Int) {
        if (attempt >= 6) {
            service?.speak("Still searching for captain. Please check Rapido app.")
            return
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            if (rapidoAutomation.isCaptainAssigned()) {
                val details = rapidoAutomation.readCaptainDetails()
                if (details != null) {
                    val msg = StringBuilder("Captain found! ")
                    if (details.name.isNotBlank()) msg.append("Captain ${details.name}. ")
                    if (details.eta.isNotBlank()) msg.append("Arriving in ${details.eta}. ")
                    if (details.vehicleNumber.isNotBlank()) msg.append("Vehicle ${details.vehicleNumber}. ")
                    if (details.pin.isNotBlank()) msg.append("Your PIN is ${details.pin}.")
                    service?.speak(msg.toString())
                } else {
                    service?.speak("Captain assigned. Please check Rapido for details.")
                }
            } else {
                // Keep polling
                pollForCaptain(attempt + 1)
            }
        }, 5000)
    }

    /**
     * Open Google Maps app.
     */
    private fun openGoogleMaps() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: try launching by package name
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                } else {
                    service?.speak("Google Maps is not installed")
                }
            } catch (e2: Exception) {
                service?.speak("Could not open Google Maps")
            }
        }
    }

    /**
     * Search for a location in Google Maps using the official geo: URI.
     * Opens Maps and shows the location on screen.
     */
    private fun searchInGoogleMaps(destination: String) {
        try {
            val encodedDest = android.net.Uri.encode(destination)
            val geoUri = android.net.Uri.parse("geo:0,0?q=$encodedDest")
            val intent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Google Maps search: $destination")
        } catch (e: Exception) {
            Log.e(TAG, "Maps search error: ${e.message}")
            service?.speak("Could not search in Google Maps")
        }
    }

    /**
     * Start turn-by-turn navigation using Google Maps navigation intent.
     * @param destination The place to navigate to
     * @param mode d=driving, w=walking, b=bicycling, r=transit
     */
    private fun startNavigation(destination: String, mode: String = "d") {
        try {
            val encodedDest = android.net.Uri.encode(destination)
            val navUri = android.net.Uri.parse("google.navigation:q=$encodedDest&mode=$mode")
            val intent = Intent(Intent.ACTION_VIEW, navUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Navigation started: $destination (mode=$mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Navigation error: ${e.message}")
            service?.speak("Could not start navigation")
        }
    }



    // ==================== QUICK SETTINGS TOGGLE ====================

    /**
     * Toggle a setting by opening Quick Settings and clicking the tile.
     * Works for WiFi, Mobile Data, and any other QS tile.
     *
     * @param name Display name for feedback (e.g., "WiFi")
     * @param enabled Whether we want to turn it on or off (used for feedback only)
     * @param tileLabels List of possible tile labels to search for (fuzzy matched)
     */
    private fun toggleViaQuickSettings(name: String, enabled: Boolean, tileLabels: List<String>): Boolean {
        val svc = service ?: return notConnected()

        // Step 1: Open Quick Settings
        val opened = svc.openQuickSettings()
        if (!opened) {
            svc.speak("Could not open quick settings")
            return false
        }

        // Step 2: Wait for QS to load, then find and click the tile
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            var clicked = false
            // Try each possible tile label on PAGE 1 (silent = no error TTS per label)
            for (label in tileLabels) {
                if (svc.findAndClickSmart(label, silent = true)) {
                    clicked = true
                    Log.i(TAG, "QS Toggle: Clicked '$label' tile for $name (page 1)")
                    break
                }
            }

            if (!clicked) {
                // PAGE 1 failed — swipe RIGHT to check page 2
                Log.i(TAG, "QS Toggle: '$name' not found on page 1, swiping to page 2")
                val startX = svc.screenWidth * 0.8f
                val endX = svc.screenWidth * 0.2f
                val y = svc.screenHeight * 0.35f  // QS tiles are in the upper portion
                val path = android.graphics.Path().apply {
                    moveTo(startX, y)
                    lineTo(endX, y)
                }
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 250))
                    .build()
                svc.dispatchGesture(gesture, null, null)

                // Wait for page 2 to render, then retry
                handler.postDelayed({
                    for (label in tileLabels) {
                        if (svc.findAndClickSmart(label, silent = true)) {
                            clicked = true
                            Log.i(TAG, "QS Toggle: Clicked '$label' tile for $name (page 2)")
                            break
                        }
                    }

                    if (clicked) {
                        svc.speak("$name ${if (enabled) "turned on" else "turned off"}")
                    } else {
                        // Only ONE error message for all failed attempts
                        svc.speak("Could not find $name in quick settings")
                    }

                    // Dismiss QS panel
                    handler.postDelayed({
                        svc.goBack()
                        handler.postDelayed({ svc.goBack() }, 500)
                    }, 500)
                }, 600) // Wait for swipe animation
            } else {
                svc.speak("$name ${if (enabled) "turned on" else "turned off"}")

                // Step 3: Dismiss QS panel
                handler.postDelayed({
                    svc.goBack()
                    handler.postDelayed({ svc.goBack() }, 500)
                }, 500)
            }
        }, 800) // Wait 800ms for QS panel to fully load

        return true
    }

    /**
     * Extract the QS tile feature name from the command.
     * Strips on/off/start/stop and common filler words.
     */
    private fun extractQsTileName(command: String): String {
        return command
            .replace(Regex("\\b(turn|switch|toggle|enable|disable|start|stop|chalu|band|karo|on|off)\\b"), "")
            .replace(Regex("\\b(करो|चालू|बंद)"), "")
            .trim()
            .ifBlank { command }
    }

    /**
     * Map a feature name to possible QS tile labels across different phone brands.
     * Returns multiple possible labels for fuzzy matching.
     * If the feature is unknown, returns the name itself as a fallback.
     */
    private fun getQsTileLabels(featureName: String): List<String> {
        val name = featureName.lowercase().trim()

        // Comprehensive mapping: feature name → possible QS tile labels
        val tileMap = mapOf(
            // Eye comfort / Blue light
            "eye comfort" to listOf("Eye comfort shield", "Eye Comfort Shield", "Eye comfort", "Blue light filter", "Night Light", "Night light", "Eye Care", "Reading mode"),
            "blue light" to listOf("Blue light filter", "Blue light", "Night Light", "Eye comfort shield", "Eye Comfort Shield"),
            "eye care" to listOf("Eye Care", "Eye comfort shield", "Blue light filter", "Night Light"),
            "night light" to listOf("Night Light", "Night light", "Eye comfort shield", "Blue light filter"),

            // Power saving
            "power saving" to listOf("Power saving", "Battery Saver", "Battery saver", "Power Saving", "Power saver", "Ultra battery saver"),
            "battery saver" to listOf("Battery Saver", "Battery saver", "Power saving", "Power Saving"),
            "power saver" to listOf("Power saver", "Power saving", "Battery Saver", "Battery saver"),

            // Screen recording
            "screen recording" to listOf("Screen recorder", "Screen recording", "Screen record", "Record screen"),
            "screen record" to listOf("Screen recorder", "Screen recording", "Screen record"),

            // Quick share / Nearby share
            "quick share" to listOf("Quick Share", "Quick share", "Nearby Share", "Nearby share"),
            "nearby share" to listOf("Nearby Share", "Nearby share", "Quick Share", "Quick share"),

            // OTG / USB
            "otg" to listOf("OTG", "USB OTG", "OTG connection"),
            "usb" to listOf("OTG", "USB OTG", "USB"),

            // Extra tiles people might have
            "nfc" to listOf("NFC", "Nfc"),
            "sync" to listOf("Sync", "Auto sync", "Auto-sync"),
            "cast" to listOf("Cast", "Screen cast", "Smart View", "Wireless display"),
            "focus" to listOf("Focus mode", "Focus"),
            "extra dim" to listOf("Extra dim", "Extra Dim"),
            "bedtime" to listOf("Bedtime mode", "Bedtime"),
            "ultra saving" to listOf("Ultra battery saver", "Ultra power saving"),
            "private dns" to listOf("Private DNS", "Private dns"),
            "vpn" to listOf("VPN", "Vpn"),
            "dolby" to listOf("Dolby Atmos", "Dolby", "dolby atmos"),
            "always display" to listOf("Always On Display", "AOD", "Always on display"),
            "aod" to listOf("Always On Display", "AOD", "Always on display")
        )

        // Try to find a matching key
        for ((key, labels) in tileMap) {
            if (name.contains(key)) {
                return labels
            }
        }

        // Fallback: use the feature name itself (fuzzy matching will try it)
        return listOf(featureName, featureName.replaceFirstChar { it.uppercase() })
    }

    // ==================== SYSTEM ACTIONS ====================

    private fun adjustVolume(direction: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
        service?.speak("Volume adjusted")
        return true
    }

    /**
     * Handle toggle result from DirectToggleController with appropriate voice feedback.
     * If QS tile labels are provided and direct API fails, falls back to Quick Settings.
     *
     * @param name Display name for feedback
     * @param result Result from DirectToggleController
     * @param enabled Whether turning on or off (used for QS fallback feedback)
     * @param qsTileLabels Optional QS tile labels for fallback. If null, no QS fallback.
     */
    private fun handleToggle(
        name: String,
        result: DirectToggleController.ToggleResult,
        enabled: Boolean = true,
        qsTileLabels: List<String>? = null
    ): Boolean {
        return when (result) {
            DirectToggleController.ToggleResult.SUCCESS -> {
                service?.speak("$name toggled")
                true
            }
            DirectToggleController.ToggleResult.ALREADY_IN_STATE -> {
                service?.speak("$name is already in the desired state")
                true
            }
            DirectToggleController.ToggleResult.NEEDS_PERMISSION -> {
                service?.speak("$name needs permission. Opening settings.")
                true
            }
            DirectToggleController.ToggleResult.NEEDS_PANEL,
            DirectToggleController.ToggleResult.FAILED -> {
                if (qsTileLabels != null) {
                    // Direct API didn't work → fall back to Quick Settings tile
                    Log.i(TAG, "$name direct API returned ${result.name} → QS tile fallback")
                    toggleViaQuickSettings(name, enabled, qsTileLabels)
                } else {
                    service?.speak("Could not toggle $name")
                    false
                }
            }
        }
    }




    private fun exitChatMode(): Boolean {
        chatMode.isActive = false
        chatMode.currentApp = ""
        chatMode.currentContact = ""
        chatMode.contactNumber = ""
        // Press back to leave chat
        service?.let { it.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
        service?.speak("Chat mode ended")
        return true
    }

    // ==================== PHONE CALLS ====================

    private fun makePhoneCall(input: String): Boolean {
        val number = input.replace(Regex("[^0-9+]"), "")
        if (number.isEmpty()) {
            service?.speak("Invalid phone number")
            return false
        }
        return makeCallTo(number, number)
    }

    private fun answerCall(): Boolean {
        callManager.answerCall()
        service?.speak("Answering call")
        return true
    }

    private fun endCall(): Boolean {
        callManager.endCall()
        return true
    }

    private fun toggleSpeaker(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        val newState = !audioManager.isSpeakerphoneOn
        callManager.toggleSpeaker(newState)
        return true
    }

    // ==================== SMS ====================

    private fun readLastSms(): Boolean {
        return try {
            val cursor = context.contentResolver.query(
                android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("address", "body", "date"),
                null,
                null,
                "date DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val address = it.getString(0)
                    val body = it.getString(1)
                    service?.speak("Message from $address: $body")
                    return true
                }
            }
            service?.speak("No messages found")
            false
        } catch (e: SecurityException) {
            service?.speak("SMS permission not granted")
            false
        }
    }

    // ==================== MEDIA ====================

    private fun sendMediaAction(keyCode: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        audioManager.dispatchMediaKeyEvent(event)
        val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(eventUp)
        service?.speak("Media command sent")
        return true
    }

    // ==================== YOUTUBE MACRO ====================

    private fun searchOnYouTube(query: String): Boolean {
        if (query.isBlank()) {
            service?.speak("What should I search for on YouTube?")
            return false
        }
        // Open YouTube with search intent
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            service?.speak("Searching YouTube for $query")
            true
        } catch (e: Exception) {
            // Fallback: open YouTube via web
            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://www.youtube.com/results?search_query=${android.net.Uri.encode(query)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
            service?.speak("Searching YouTube for $query")
            true
        }
    }

    // ==================== BATTERY ====================

    private fun readBattery(): Boolean {
        val batteryStatus = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percentage = (level * 100) / scale
        val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val chargingText = if (isCharging) " and charging" else ""
        service?.speak("Battery level is $percentage percent$chargingText")
        return true
    }

    // ==================== UTILS ====================

    private fun notConnected(): Boolean {
        Log.w(TAG, "Accessibility Service not connected")
        return false
    }

    // ==================== SCREEN ROTATION ====================

    private fun toggleAutoRotate(enable: Boolean): Boolean {
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (enable) 1 else 0
            )
            service?.speak(if (enable) "Auto rotation on" else "Auto rotation off")
            true
        } catch (e: Exception) {
            // Need WRITE_SETTINGS permission
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            service?.speak("Please grant settings permission")
            false
        }
    }

    // ==================== BRIGHTNESS ====================

    private fun adjustBrightness(delta: Int): Boolean {
        return try {
            // Disable auto brightness first
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val current = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, 128
            )
            val newBrightness = (current + delta).coerceIn(10, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, newBrightness
            )
            val pct = (newBrightness * 100) / 255
            service?.speak("Brightness set to $pct percent")
            true
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            service?.speak("Please grant settings permission for brightness")
            false
        }
    }

    private fun setBrightness(level: Int): Boolean {
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(10, 255)
            )
            val pct = (level * 100) / 255
            service?.speak("Brightness set to $pct percent")
            true
        } catch (e: Exception) {
            service?.speak("Cannot change brightness. Permission required.")
            false
        }
    }

    // ==================== SCREENSHOT ====================

    private fun takeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service?.takeScreenshotAction() ?: notConnected()
        } else {
            service?.speak("Screenshot requires Android 9 or later")
            false
        }
    }

    // ==================== ALARM / TIMER ====================

    private fun setAlarm(timeStr: String): Boolean {
        return try {
            // Parse time like "7 am", "7:30 pm", "7 30", etc.
            val cleaned = timeStr.lowercase().replace(".", "").trim()
            var hour = 0
            var minute = 0

            val timeMatch = Regex("(\\d{1,2})(?::| )?(\\d{2})?\\s*(am|pm)?").find(cleaned)
            if (timeMatch != null) {
                hour = timeMatch.groupValues[1].toIntOrNull() ?: 0
                minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
                val amPm = timeMatch.groupValues[3]

                if (amPm == "pm" && hour < 12) hour += 12
                if (amPm == "am" && hour == 12) hour = 0
            }

            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            service?.speak("Alarm set for $hour:${String.format("%02d", minute)}")
            true
        } catch (e: Exception) {
            service?.speak("Could not set alarm")
            Log.e(TAG, "Alarm error: ${e.message}")
            false
        }
    }

    private fun setTimer(durationStr: String): Boolean {
        return try {
            // Parse duration like "5 minutes", "10 min", "1 hour", "30 seconds"
            val cleaned = durationStr.lowercase().trim()
            var seconds = 0

            val numMatch = Regex("(\\d+)").find(cleaned)
            val number = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5

            seconds = when {
                cleaned.contains("hour") -> number * 3600
                cleaned.contains("minute") || cleaned.contains("min") -> number * 60
                cleaned.contains("second") || cleaned.contains("sec") -> number
                else -> number * 60 // Default to minutes
            }

            val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            service?.speak("Timer set for $durationStr")
            true
        } catch (e: Exception) {
            service?.speak("Could not set timer")
            Log.e(TAG, "Timer error: ${e.message}")
            false
        }
    }




    // ==================== GOOGLE SEARCH ====================

    private fun searchOnGoogle(query: String): Boolean {
        if (query.isBlank()) {
            service?.speak("What should I search for?")
            return false
        }
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            service?.speak("Searching for $query")
            true
        } catch (e: Exception) {
            // Fallback to browser
            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(query)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
            service?.speak("Searching for $query")
            true
        }
    }

    // ==================== TYPE TEXT ====================

    private fun typeText(text: String): Boolean {
        val typed = service?.inputText(text) ?: false
        if (typed) {
            service?.speak("Typed: $text")
        } else {
            service?.speak("No text field focused. Tap a text field first.")
        }
        return typed
    }

    // ==================== READ NOTIFICATIONS ====================

    private fun readNotifications(): Boolean {
        val notifications = service?.getNotificationTexts()
        if (notifications.isNullOrEmpty()) {
            service?.speak("No notifications")
            return true
        }
        val summary = notifications.take(5).joinToString(". ")
        service?.speak("You have ${notifications.size} notifications. $summary")
        return true
    }

    // ==================== CALL HELPERS ====================

    /**
     * Unified phone call helper — places a call and updates state.
     * Handles TTS feedback and SecurityException in one place.
     */
    private fun makeCallTo(number: String, displayName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            isOnActiveCall = true
            service?.speak("Calling $displayName")
            true
        } catch (e: SecurityException) {
            service?.speak("Phone call permission not granted")
            false
        }
    }

    // ==================== CALL BY CONTACT NAME ====================

    private fun callByContactName(name: String): Boolean {
        // Ensure contacts are loaded
        if (contactRegistry.needsScan()) {
            contactRegistry.scanContacts()
        }

        Log.i(TAG, "Call by contact name: '$name'")

        // Check for multiple matches (disambiguation)
        val allMatches = contactRegistry.findAllMatches(name)
        Log.i(TAG, "Found ${allMatches.size} potential matches for '$name'")

        if (allMatches.size > 1) {
            // Check if there's a clear winner (top score much higher than rest)
            val topScore = allMatches[0].score
            val secondScore = if (allMatches.size > 1) allMatches[1].score else 0

            if (topScore - secondScore >= 25) {
                // Clear winner — call directly
                val match = allMatches[0]
                Log.i(TAG, "Clear winner: '${match.name}' (score=${match.score}) vs second (score=$secondScore)")
                return makeCallTo(match.number, match.name)
            }

            // Multiple contacts match closely — ask user to specify
            pendingContactMatches = allMatches.take(3) // Max 3 for disambiguation
            waitingForContactDisambiguation = true
            val names = allMatches.take(3).joinToString(", ") { "${it.name}(${it.score})" }
            Log.i(TAG, "Contact disambiguation: '$name' → ${allMatches.size} matches: $names")
            val spokenNames = allMatches.take(3).joinToString(", ") { it.name }
            service?.speak("Multiple contacts found: $spokenNames. Please say the full name.")
            return true
        }

        // Single match from findAllMatches
        if (allMatches.size == 1) {
            val match = allMatches[0]
            Log.i(TAG, "Single match: '${match.name}' (score=${match.score}, type=${match.matchType})")
            return makeCallTo(match.number, match.name)
        }

        // Try scored findContact as fallback (uses fuzzy matching)
        val match = contactRegistry.findContact(name)
        if (match != null) {
            Log.i(TAG, "Fuzzy match: '${match.name}' (score=${match.score}, type=${match.matchType})")
            return makeCallTo(match.number, match.name)
        }

        service?.speak("Contact $name not found. Please try saying the full name.")
        return false
    }

    // ==================== PHASE 1: ESSENTIAL INFO ====================

    private fun tellTime(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val amPm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        val displayHour = if (hour == 0) 12 else hour
        val timeStr = if (minute == 0) {
            "$displayHour $amPm"
        } else {
            "$displayHour:${String.format("%02d", minute)} $amPm"
        }
        service?.speak("The time is $timeStr")
        return true
    }

    private fun tellDay(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val dayOfWeek = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(cal.time)
        val fullDate = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(cal.time)
        service?.speak("Today is $dayOfWeek, $fullDate")
        return true
    }

    private fun showHelp(): Boolean {
        val help = "You can say: " +
            "Open any app name. " +
            "Go home, go back, open recents. " +
            "Call a contact name or number. " +
            "Send message or WhatsApp. " +
            "Chat with someone on WhatsApp, Instagram, or Facebook. " +
            "Volume up or down. Wifi or Bluetooth on or off. " +
            "Flashlight on or off. " +
            "What time is it. What day is it. " +
            "Read screen. Describe screen. " +
            "What can I click. " +
            "Read notifications. Battery status. " +
            "Brightness up or down. Take screenshot. " +
            "Set alarm or timer. " +
            "Search on Google or YouTube. " +
            "Take a photo. Read clipboard. Play a song. " +
            "Emergency for help. Send location. " +
            "Say stop to cancel speech. Say repeat to hear again."
        service?.speak(help)
        return true
    }

    // ==================== MESSAGING HELPERS ====================

    /**
     * Unified contact + message parser for voice commands.
     * Tries progressively longer prefixes against contacts to handle
     * multi-word names like "Mahto Krishna hello" → contact="Mahto Krishna", message="hello".
     *
     * @return Triple of (contactQuery, bestMatch, message)
     */
    private fun parseContactAndMessage(body: String): Triple<String, ContactRegistry.ContactMatch?, String> {
        val words = body.split(" ")
        var contactQuery = ""
        var message = ""
        var bestMatch: ContactRegistry.ContactMatch? = null

        if (contactRegistry.needsScan()) contactRegistry.scanContacts()

        // Try longest prefix first for best contact match
        for (i in (words.size - 1).coerceAtMost(3) downTo 0) {
            val candidateName = words.subList(0, i + 1).joinToString(" ")
            val match = contactRegistry.findContact(candidateName)
            if (match != null && match.score >= 50) {
                contactQuery = candidateName
                bestMatch = match
                message = words.subList(i + 1, words.size).joinToString(" ")
                break
            }
        }

        // Fallback to first word as contact
        if (contactQuery.isBlank()) {
            contactQuery = words.getOrElse(0) { "" }
            message = words.drop(1).joinToString(" ")
            bestMatch = contactRegistry.findContact(contactQuery)
        }

        return Triple(contactQuery, bestMatch, message)
    }

    // ==================== PHASE 2: MESSAGING ====================

    private fun sendSms(body: String): Boolean {
        val (contactQuery, bestMatch, message) = parseContactAndMessage(body)

        if (contactQuery.isBlank()) {
            service?.speak("Who should I send the message to?")
            return false
        }

        val number = bestMatch?.number
        if (number != null) {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:$number")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            service?.speak("Sending SMS to ${bestMatch.name}: $message")
            return true
        } else {
            service?.speak("Contact $contactQuery not found")
            return false
        }
    }

    private fun sendWhatsApp(body: String): Boolean {
        val (contactQuery, bestMatch, message) = parseContactAndMessage(body)

        if (contactQuery.isBlank()) {
            service?.speak("Who should I send the WhatsApp to?")
            return false
        }

        if (bestMatch != null) {
            var number = bestMatch.number.replace("+", "").replace(" ", "")
            if (!number.startsWith("91") && number.length == 10) number = "91$number"

            val whatsappUri = android.net.Uri.parse(
                "https://wa.me/$number?text=${android.net.Uri.encode(message)}"
            )
            val intent = Intent(Intent.ACTION_VIEW, whatsappUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            service?.speak("Opening WhatsApp chat with ${bestMatch.name}")
            return true
        } else {
            service?.speak("Contact $contactQuery not found")
            return false
        }
    }

    // ==================== PHASE 2: UNIVERSAL CHAT MODE ====================

    /**
     * Chat mode state — tracks active chat session across any messaging app.
     */
    data class ChatState(
        var isActive: Boolean = false,
        var currentApp: String = "",
        var currentContact: String = "",
        var contactNumber: String = ""
    )

    val chatMode = ChatState()

    private fun enterChatMode(command: String): Boolean {
        // Parse: "chat Sushant on whatsapp" or "chat Rahul on instagram"
        val cleaned = command.removePrefix("chat ").trim()

        // Detect app
        val app = when {
            cleaned.contains("whatsapp") || cleaned.contains("what's app") -> "whatsapp"
            cleaned.contains("instagram") || cleaned.contains("insta") -> "instagram"
            cleaned.contains("facebook") || cleaned.contains("messenger") -> "facebook"
            cleaned.contains("twitter") || cleaned.contains(" x ") || cleaned.endsWith(" x") -> "twitter"
            cleaned.contains("telegram") -> "telegram"
            else -> "whatsapp" // Default to WhatsApp
        }

        // Extract contact name (remove app name keywords)
        val contactName = cleaned
            .replace("on ", "")
            .replace("whatsapp", "").replace("what's app", "")
            .replace("instagram", "").replace("insta", "")
            .replace("facebook", "").replace("messenger", "")
            .replace("twitter", "").replace("telegram", "")
            .replace(" x ", "").replace("message", "")
            .trim()

        if (contactName.isBlank()) {
            // Just open the app without a specific contact
            chatMode.isActive = true
            chatMode.currentApp = app
            appNavigator.openApp(app)
            service?.speak("Opened $app. Chat mode active. Say send followed by your message.")
            return true
        }

        // Look up contact
        if (contactRegistry.needsScan()) contactRegistry.scanContacts()
        val match = contactRegistry.findContact(contactName)

        chatMode.isActive = true
        chatMode.currentApp = app
        chatMode.currentContact = match?.name ?: contactName
        chatMode.contactNumber = match?.number ?: ""

        // Open the chat
        when (app) {
            "whatsapp" -> {
                // Route through CommandRouter → WhatsAppAutomation (accessibility-based)
                val router = AutomationAccessibilityService.instance?.commandRouter
                if (router != null && chatMode.currentContact.isNotBlank()) {
                    router.route("open whatsapp chat with ${chatMode.currentContact}")
                } else {
                    appNavigator.openApp("whatsapp")
                }
            }
            else -> {
                // For Instagram/Facebook/Twitter — open app, then user can navigate
                appNavigator.openApp(app)
            }
        }

        service?.speak("Chat mode active with ${chatMode.currentContact} on $app. Say send followed by your message. Say read messages to hear responses. Say exit chat when done.")
        return true
    }

    private fun chatSendMessage(message: String): Boolean {
        if (!chatMode.isActive) {
            service?.speak("No chat session active. Say chat followed by contact name and app.")
            return false
        }

        // Focus the text input field using smart finder
        service?.findAndFocusTextField()

        // Type the message into the text field
        val typed = service?.inputText(message) ?: false
        if (typed) {
            // Click send button using smart fuzzy finder (handles all apps)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val sent = service?.findAndClickSmart("send") ?: false
                if (sent) {
                    service?.speak("Sent: $message")
                } else {
                    service?.speak("Typed the message but could not find send button. Please tap send manually.")
                }
            }, 500)
            return true
        } else {
            service?.speak("Could not find text field. Make sure the chat is open first.")
            return false
        }
    }

    /**
     * Find the editable text field and focus it before typing.
     */
    private fun findAndFocusEditField(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndFocusEditField(child)) return true
        }
        return false
    }

    private fun chatReadMessages(): Boolean {
        if (!chatMode.isActive) {
            service?.speak("No chat session active")
            return false
        }

        val screenText = service?.readScreen() ?: ""
        if (screenText.isNotBlank()) {
            service?.speak("Messages on screen: $screenText")
        } else {
            service?.speak("No messages visible")
        }
        return true
    }

    // ==================== PHASE 4: DAILY LIFE ====================

    // ==================== APP LAUNCHING ====================

    private fun openApp(appName: String): Boolean {
        return appNavigator.openApp(appName)
    }

    private fun openWeather(): Boolean {
        // Try to open any weather app, fallback to Google weather search
        val weatherApps = listOf(
            "com.google.android.apps.weather",
            "com.accuweather.android",
            "com.weather.Weather"
        )
        for (pkg in weatherApps) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                service?.speak("Opening weather")
                return true
            }
        }
        // Fallback: Google search for weather
        searchOnGoogle("weather today")
        return true
    }

    private fun takePhoto(): Boolean {
        cameraController.takePhoto()
        return true
    }

    private fun readClipboard(): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank()) {
                service?.speak("Clipboard contains: $text")
                return true
            }
        }
        service?.speak("Clipboard is empty")
        return true
    }

    private fun playSong(songName: String): Boolean {
        if (songName.isBlank()) {
            service?.speak("What song should I play?")
            return false
        }
        // Try YouTube Music or Spotify or default music search
        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS,
                "vnd.android.cursor.item/audio")
            putExtra(android.app.SearchManager.QUERY, songName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            service?.speak("Playing $songName")
            true
        } catch (e: Exception) {
            // Fallback: open music app
            appNavigator.openApp("music")
            true
        }
    }

    // ==================== PHASE 5: SAFETY & EMERGENCY ====================

    private fun emergencyCall(): Boolean {
        service?.speak("Emergency mode activated")
        
        // 1. Call emergency services
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = android.net.Uri.parse("tel:112")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(callIntent)
        } catch (e: SecurityException) {
            // Fallback to dial (no CALL_PHONE permission)
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = android.net.Uri.parse("tel:112")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
        }
        
        // 2. Send emergency SMS with location to emergency contact
        if (emergencyContact.isNotBlank()) {
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                
                val locationText = if (location != null) {
                    "Location: https://maps.google.com/?q=${location.latitude},${location.longitude}"
                } else {
                    "Location: GPS unavailable"
                }
                
                val smsMessage = "EMERGENCY! I need help. $locationText. Sent via Neo."
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    android.telephony.SmsManager.getDefault()
                }
                smsManager?.sendTextMessage(emergencyContact, null, smsMessage, null, null)
                
                Log.i(TAG, "Emergency SMS sent to $emergencyContact with location")
                service?.speak("Emergency SMS sent to your emergency contact")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send emergency SMS: ${e.message}")
                // Don't block the emergency call if SMS fails
            }
        }
        
        return true
    }

    private fun sendLocation(): Boolean {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                val mapUrl = "https://maps.google.com/?q=$lat,$lon"
                
                // If emergency contact is set, send SMS directly
                if (emergencyContact.isNotBlank()) {
                    try {
                        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.getSystemService(android.telephony.SmsManager::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            android.telephony.SmsManager.getDefault()
                        }
                        smsManager?.sendTextMessage(emergencyContact, null, "My location: $mapUrl", null, null)
                        service?.speak("Location sent to your emergency contact")
                        return true
                    } catch (_: Exception) {
                        // Fall through to share intent
                    }
                }
                
                // Fallback: share intent
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "My location: $mapUrl")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Location").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                service?.speak("Sharing your location")
                true
            } else {
                service?.speak("Could not get your location. Make sure GPS is on.")
                false
            }
        } catch (e: SecurityException) {
            service?.speak("Location permission not granted")
            false
        }
    }

    /**
     * Set emergency contact number
     */
    fun setEmergencyContact(number: String) {
        emergencyContact = number.trim().replace(" ", "")
        prefs.edit().putString("emergency_contact", emergencyContact).apply()
        Log.i(TAG, "Emergency contact set: $emergencyContact")
    }

    fun getEmergencyContact(): String {
        return prefs.getString("emergency_contact", "") ?: ""
    }


    /**
     * Store the phone's actual screen lock PIN.
     */
    fun setPhonePin(pin: String) {
        lockType = "PIN"
        phonePin = pin.trim().replace(" ", "")
        prefs.edit()
            .putString("lock_type", "PIN")
            .putString("phone_pin", phonePin)
            .apply()
        Log.i(TAG, "Phone PIN saved (${phonePin.length} digits)")
    }

    /**
     * Store the phone's pattern as a sequence of dots (e.g. "14789").
     */
    fun setPhonePattern(pattern: String) {
        lockType = "PATTERN"
        phonePattern = pattern.trim().replace(" ", "")
        prefs.edit()
            .putString("lock_type", "PATTERN")
            .putString("phone_pattern", phonePattern)
            .apply()
        Log.i(TAG, "Phone Pattern saved (${phonePattern.length} dots)")
    }

    /**
     * Check if any phone lock credential is stored.
     */
    fun hasPhoneLockCredential(): Boolean {
        return (lockType == "PIN" && phonePin.isNotBlank()) ||
               (lockType == "PATTERN" && phonePattern.isNotBlank())
    }

    fun setUnlockCode(code: String) {
        unlockCode = code.trim().replace(" ", "").lowercase()
        prefs.edit().putString("voice_unlock_code", unlockCode).apply()
        Log.i(TAG, "Unlock code set: '$unlockCode'")
    }

    fun getUnlockCode(): String {
        return prefs.getString("voice_unlock_code", "") ?: ""
    }

    // (lines omitted — process() and executeIntent() are above)

    // ==================== VOICE UNLOCK CODE HANDLERS ====================

    /**
     * Handles the "unlock" command.
     * Checks if the command ITSELF contains the secret code (e.g., "Unlock phone code Delta").
     * If yes -> Unlocks immediately.
     * If no  -> Asks user to say the code.
     */
    private fun handleUnlockCommand(commandArgument: String = ""): Boolean {
        // 1. Check if voice security code is set
        if (unlockCode.isNotBlank()) {
            // 2. SMART CHECK: Did the user already say the code in the command?
            // E.g. User said: "Unlock phone code delta" (contains "delta")
            if (commandArgument.contains(unlockCode)) {
                Log.i(TAG, "Smart Unlock: Code '$unlockCode' detected inside command!")
                service?.speak("Code accepted. Unlocking.")
                waitingForUnlockCode = false
                unlockAttemptsRemaining = 3
                return performPhysicalUnlock()
            }

            // 3. Code not found -> Ask for it
            waitingForUnlockCode = true
            unlockAttemptsRemaining = 3
            service?.speak("Please say your security code to unlock.")
            return true
        }

        return performPhysicalUnlock()
    }

    /**
     * Executes the physical unlock steps (PIN/Pattern/Swipe).
     * Separated from handleUnlockCommand so we can call it from multiple places.
     */
    private fun performPhysicalUnlock(): Boolean {
        // If no code is set, proceed with direct unlock simulation
        if (lockType == "PIN" && phonePin.isNotBlank()) {
            Log.i(TAG, "Unlocking with stored PIN")
            return service?.unlockWithPin(phonePin) ?: notConnected()
        } else if (lockType == "PATTERN" && phonePattern.isNotBlank()) {
            Log.i(TAG, "Unlocking with stored PATTERN")
            return service?.unlockWithPattern(phonePattern) ?: notConnected()
        }

        // No credential stored — just wake screen
        service?.speak("No screen lock saved. Please set it up in the app.")
        return service?.unlockScreen() ?: notConnected()
    }

    /**
     * Verifies the spoken code against the saved unlock code.
     * 3 attempts max, then tells user to open manually.
     */
    private fun handleUnlockCodeAttempt(spokenCode: String): Boolean {
        // Strip ALL spaces — voice recognition splits numbers like "843428" → "8434 28"
        val normalized = spokenCode.trim().replace(" ", "").lowercase()

        if (normalized == unlockCode) {
            // ✅ Code matches — unlock!
            waitingForUnlockCode = false
            unlockAttemptsRemaining = 3
            service?.speak("Code accepted. Unlocking phone.")

            // Perform the actual unlock with stored PIN
            return performPhysicalUnlock()
        } else {
            // ❌ Wrong code
            unlockAttemptsRemaining--

            if (unlockAttemptsRemaining > 0) {
                service?.speak("Wrong code. $unlockAttemptsRemaining ${if (unlockAttemptsRemaining == 1) "attempt" else "attempts"} remaining. Try again.")
                return false
            } else {
                // All attempts exhausted
                waitingForUnlockCode = false
                unlockAttemptsRemaining = 3
                service?.speak("Too many wrong attempts. Please unlock your phone manually.")
                return false
            }
        }
    }

    /**
     * Destroy the Vision Assistance Module.
     * Called from AutomationAccessibilityService.onDestroy().
     */
    fun destroyVision() {
        visionOrchestrator?.destroy()
        visionOrchestrator = null
    }

    /**
     * Change the Gemini model used for vision features.
     * Called from the sidebar model selector in MainActivity.
     */
    fun changeVisionModel(modelId: String) {
        Log.i(TAG, "Changing vision model to: $modelId")
        if (visionOrchestrator != null) {
            visionOrchestrator?.changeModel(modelId)
        } else {
            // Not initialized yet — model will be read from SharedPreferences on first use
            com.autoapk.automation.vision.GeminiVisionService.setSelectedModel(context, modelId)
        }
    }

    /**
     * Stop vision auto-describe/navigation if running.
     * Called from MainActivity's Stop All button.
     */
    fun stopVisionWatching() {
        if (visionOrchestrator != null) {
            visionOrchestrator?.stopAutoDescribe()
            visionOrchestrator?.stopNavigation()
            visionOrchestrator?.cancelCurrentDescription()
        }
    }

    // ==================== LANGUAGE DETECTION ====================

    /**
     * Detect whether the user's input is Hindi/Hinglish.
     * Uses 3 indicators:
     * 1. HindiCommandMapper translation changed the text
     * 2. Devanagari script characters present
     * 3. Common romanized Hindi words detected
     */
    private fun isHindiDetected(rawInput: String, translated: String): Boolean {
        val raw = rawInput.lowercase().trim()
        
        // Check 1: Translation mapper changed the text → definitely Hindi
        if (translated.lowercase().trim() != raw) return true
        
        // Check 2: Devanagari script present
        if (raw.any { it in '\u0900'..'\u097F' }) return true
        
        // Check 3: Common romanized Hindi words
        val hindiWords = setOf(
            // Verbs / action words
            "karo", "kro", "kardo", "kar", "krdo",
            "kholo", "khol", "kholdo",
            "band", "bnd", "bandh",
            "batao", "bta", "btao", "btado",
            "dikhao", "dikha", "dikhaao",  
            "sunao", "suna", "sunaao",
            "padho", "padh", "pado",
            "bhejo", "bhej", "bhejdo",
            "bulao", "bula", "bulaao",
            "chalao", "chala", "chlao",
            "bajao", "baja", "bjaao",
            "lagao", "laga", "lgao",
            "hatao", "hata", "htao",
            "uthao", "utha", "uthaao",
            "rukhao", "rukho", "ruko", "ruk",
            "bolo", "bol",
            "jao", "ja", "chalo",
            "aao", "aaja", "aao",
            "dekho", "dekh",
            "suno", "sun",
            "dedo", "dede", "de",
            "lelo", "lele", "le",
            
            // Question words
            "kya", "kaun", "kahan", "kab", "kaise", "kitna", "kitne", "kitni", "kyun", "kyu",
            
            // Pronouns / common words
            "mera", "meri", "mere", "tera", "teri", "tere",
            "uska", "uski", "uske", "iska", "iski", "iske",
            "apna", "apni", "apne",
            "hai", "hain", "tha", "thi", "the",
            "ko", "ka", "ki", "ke", "se", "me", "pe", "par",
            "nahi", "nhi", "mat", "na",
            "haan", "han", "ha",
            "aur", "ya", "lekin", "magar",
            "abhi", "ab", "phir", "fir",
            "yeh", "ye", "woh", "wo",
            "sab", "kuch", "koi",
            
            // Common nouns/words in Hindi commands
            "samne", "saamne", "aas", "paas", "aasp",
            "peeche", "peche", "piche",
            "upar", "oopar", "neeche", "niche",
            "chalu", "shuru", "khatam",
            "awaaz", "awaz", "aawaz",
            "roshni", "roshini",
            "phone", // common in Hinglish
            "bhaiya", "bhai", "didi", "ji",
            "dhundho", "dhundo", "khoj", "talaash",
            "message", // context dependent
            
            // Greetings
            "namaste", "namaskar",
            
            // Time
            "subah", "shaam", "raat", "dopahar",
            
            // Adjectives
            "zyada", "jyada", "kam", "thoda", "bahut", "poora", "pura"
        )
        
        val words = raw.split(Regex("\\s+"))
        val hindiWordCount = words.count { it in hindiWords }
        
        // If 2+ Hindi words found, or 1 Hindi word AND the command is short (≤3 words)
        return hindiWordCount >= 2 || (hindiWordCount >= 1 && words.size <= 3)
    }
}
