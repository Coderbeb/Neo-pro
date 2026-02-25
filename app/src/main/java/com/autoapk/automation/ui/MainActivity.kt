package com.autoapk.automation.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autoapk.automation.R
import com.autoapk.automation.core.AutomationAccessibilityService
import com.autoapk.automation.core.AutomationForegroundService
import com.autoapk.automation.core.CommandProcessor
import com.autoapk.automation.databinding.ActivityMainBinding
import com.autoapk.automation.input.BluetoothCommandReceiver
import com.autoapk.automation.input.GoogleVoiceCommandManager
import com.autoapk.automation.core.AppRegistry
import com.autoapk.automation.core.NeoStateManager

/**
 * Main Activity - Setup & Control Dashboard
 *
 * Handles:
 * - Runtime permission requests
 * - Accessibility Service enable guidance
 * - Voice/Bluetooth toggle controls
 * - Live status indicators
 * - Command log display
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Neo_Main"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val CREDENTIAL_REQUEST_CODE = 1002
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var voiceManager: GoogleVoiceCommandManager
    private lateinit var bluetoothReceiver: BluetoothCommandReceiver
    private lateinit var appRegistry: AppRegistry

    private var isVoiceActive = false
    private var isBluetoothActive = false
    private val commandLog = mutableListOf<String>()

    // Neo State Manager
    private lateinit var neoState: NeoStateManager

    // ==================== LIFECYCLE ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize components
        // Initialize components
        appRegistry = AppRegistry(this)
        commandProcessor = CommandProcessor(this, appRegistry)
        voiceManager = GoogleVoiceCommandManager(this)
        bluetoothReceiver = BluetoothCommandReceiver(this)

        // Check Battery Optimizations (ensure background service survival)
        val batteryManager = com.autoapk.automation.core.BatteryOptimizationManager(this)
        if (!batteryManager.isIgnoringBatteryOptimizations()) {
            val intent = batteryManager.getRequestIgnoreOptimizationIntent()
            if (intent != null) {
                try {
                    startActivity(intent)
                    addToLog("⚠️ Requesting to disable battery optimization")
                } catch (e: Exception) {
                    addToLog("⚠️ Could not launch battery optimization settings")
                }
            } else {
                 batteryManager.openBatterySettings()
                 addToLog("⚠️ Please manually disable battery optimization for Neo")
            }
        } else {
            addToLog("✅ Battery optimization ignored (background safe)")
        }

        // Auto-discover all installed apps
        appRegistry.scanInstalledApps()
        Log.i(TAG, "App Registry: discovered ${appRegistry.getAppCount()} apps")
        addToLog("✅ Discovered ${appRegistry.getAppCount()} apps on this device")

        // Auto-discover all contacts (with fuzzy matching)
        try {
            commandProcessor.contactRegistry.scanContacts()
            Log.i(TAG, "Contact Registry: loaded ${commandProcessor.contactRegistry.getContactCount()} contacts")
            addToLog("✅ Loaded ${commandProcessor.contactRegistry.getContactCount()} contacts with smart matching")
        } catch (e: Exception) {
            Log.w(TAG, "Contact scan skipped (permission needed): ${e.message}")
            addToLog("⚠️ Contacts: grant permission for call-by-name")
        }

        setupVoiceListener()
        setupBluetoothListener()
        setupButtons()
        setupManualCommandInput()
        setupPinUI()
        setupNeoStateManager()

        // Google Speech Recognition is ready immediately (no model loading needed)
        binding.btnStartVoice.isEnabled = true
        binding.btnStartVoice.text = "🎤  Start Voice Control"
        addToLog("✅ Voice recognition ready (Google Speech)")
    }

    private fun setupNeoStateManager() {
        neoState = NeoStateManager()

        // Wire NeoStateManager to CommandProcessor
        commandProcessor.neoState = neoState

        // Wire NeoStateManager to AccessibilityService
        AutomationAccessibilityService.instance?.neoState = neoState

        // Wire NeoStateManager to PhoneStateDetector (via CommandProcessor's stateDetector)
        // PhoneStateDetector is private in CommandProcessor, so we set it on the service instead

        // Register state listener for UI updates
        neoState.setListener(object : NeoStateManager.StateListener {
            override fun onModeChanged(oldMode: NeoStateManager.NeoMode, newMode: NeoStateManager.NeoMode) {
                runOnUiThread {
                    updateNeoModeUI(newMode)
                    when (newMode) {
                        NeoStateManager.NeoMode.ACTIVE -> addToLog("🟢 Neo ACTIVE")
                        NeoStateManager.NeoMode.SLEEPING -> addToLog("😴 Neo SLEEPING")
                        NeoStateManager.NeoMode.IN_CALL -> addToLog("📞 Neo IN-CALL mode")
                    }
                }
            }

            override fun onSleepAnnouncement(message: String) {
                AutomationAccessibilityService.instance?.speak(message)
            }

            override fun onWakeAnnouncement(message: String) {
                AutomationAccessibilityService.instance?.speak(message)
            }
        })

        // In-Call toggle
        binding.switchInCallMode.setOnCheckedChangeListener { _, isChecked ->
            neoState.inCallModeEnabled = isChecked
            if (isChecked) {
                addToLog("📞 In-Call voice mode ENABLED")
            } else {
                addToLog("📞 In-Call voice mode DISABLED")
                // If currently in IN_CALL mode, exit it
                if (neoState.currentMode == NeoStateManager.NeoMode.IN_CALL) {
                    neoState.exitInCallMode()
                }
            }
        }

        updateNeoModeUI(neoState.currentMode)
    }

    private fun updateNeoModeUI(mode: NeoStateManager.NeoMode) {
        val (text, colorRes) = when (mode) {
            NeoStateManager.NeoMode.ACTIVE -> "🟢 ACTIVE" to R.color.status_active
            NeoStateManager.NeoMode.SLEEPING -> "😴 SLEEPING (say \"Neo\" or press Vol↑+Vol↓)" to R.color.text_secondary
            NeoStateManager.NeoMode.IN_CALL -> "📞 IN-CALL (press Vol↑+Vol↓ to command)" to R.color.primary
        }
        binding.tvNeoModeStatus.text = text
        binding.indicatorNeoMode.setBackgroundColor(getColor(colorRes))
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
        // Refresh app registry if stale
        if (appRegistry.needsScan()) {
            appRegistry.scanInstalledApps()
        }
        // Refresh contact registry if stale
        if (commandProcessor.contactRegistry.needsScan()) {
            try { commandProcessor.contactRegistry.scanContacts() } catch (e: Exception) { }
        }
        // Re-wire NeoStateManager to service (may have reconnected)
        AutomationAccessibilityService.instance?.neoState = neoState

        // Register TTS listener — pause mic while TTS speaks to prevent feedback loop
        AutomationAccessibilityService.instance?.ttsListener =
            object : AutomationAccessibilityService.TtsListener {
                override fun onTtsStarted() {
                    Log.d(TAG, "TTS started → pausing mic")
                    voiceManager.pauseListening()
                }
                override fun onTtsFinished() {
                    Log.d(TAG, "TTS finished → resuming mic")
                    voiceManager.resumeListening()
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stopListening()
        voiceManager.destroy()
        bluetoothReceiver.stop()
        stopForegroundService()
        neoState.destroy()
        Log.d(TAG, "MainActivity destroyed")
        AutomationAccessibilityService.instance?.neoState = null
        AutomationAccessibilityService.instance?.ttsListener = null
    }

    // ==================== BUTTON HANDLERS ====================

    private fun setupButtons() {
        // Enable Accessibility Service
        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'Neo' and enable it", Toast.LENGTH_LONG).show()
        }

        // Grant All Permissions
        binding.btnGrantPermissions.setOnClickListener {
            requestAllPermissions()
        }

        // Start Voice Control
        binding.btnStartVoice.setOnClickListener {
            if (!isVoiceActive) {
                if (checkPermission(Manifest.permission.RECORD_AUDIO)) {
                    startVoiceControl()
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        PERMISSION_REQUEST_CODE
                    )
                }
            } else {
                stopVoiceControl()
            }
        }

        // Connect Bluetooth
        binding.btnConnectBluetooth.setOnClickListener {
            if (!isBluetoothActive) {
                startBluetoothServer()
            } else {
                stopBluetoothServer()
            }
        }

        // Stop All
        binding.btnStopAll.setOnClickListener {
            stopVoiceControl()
            stopBluetoothServer()
            stopForegroundService()
            addToLog("⛔ All services stopped")
        }
    }

    // ==================== MANUAL TEXT COMMAND INPUT ====================

    private fun setupManualCommandInput() {
        val sendCommand = {
            val text = binding.etManualCommand.text?.toString()?.trim() ?: ""
            if (text.isBlank()) {
                Toast.makeText(this, "Please type a command first", Toast.LENGTH_SHORT).show()
            } else {
                addToLog("⌨️ \"$text\"")
                val success = commandProcessor.process(text)
                if (success) {
                    addToLog("✅ $text")
                } else {
                    addToLog("❌ Not recognized: $text")
                }
                binding.etManualCommand.text?.clear()
                // Hide keyboard
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(binding.etManualCommand.windowToken, 0)
            }
        }

        // Send button click
        binding.btnSendCommand.setOnClickListener { sendCommand() }

        // Keyboard "Send" / Enter key also triggers send
        binding.etManualCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                sendCommand()
                true
            } else false
        }
    }

    // ==================== SCREEN LOCK SETUP (PIN or PATTERN) ====================

    private var identityVerified = false

    private fun setupPinUI() {
        // Check if ANY credential is already saved
        if (commandProcessor.hasPhoneLockCredential()) {
            binding.tvPinStatus.text = "✅ Screen lock credential saved — voice unlock active"
            binding.btnVerifyIdentity.text = "✅ Setup Complete (tap to change)"
        }

        // Step 1: Verify Identity via system lock screen
        binding.btnVerifyIdentity.setOnClickListener {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardSecure) {
                // System has a lock screen set — show it for verification
                val intent = keyguardManager.createConfirmDeviceCredentialIntent(
                    "Verify Your Identity",
                    "Enter your phone PIN/pattern to confirm you are the owner"
                )
                if (intent != null) {
                    startActivityForResult(intent, CREDENTIAL_REQUEST_CODE)
                } else {
                    onIdentityVerified()
                }
            } else {
                Toast.makeText(this, "No screen lock detected on your phone", Toast.LENGTH_LONG).show()
                onIdentityVerified()
            }
        }

        // Handle Lock Type Selection
        binding.rgLockType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbPin) {
                // PIN Mode
                binding.tilLockInput.hint = "Enter phone PIN"
                binding.etLockCredential.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                binding.tvPatternHelper.visibility = View.GONE
            } else {
                // Pattern Mode
                binding.tilLockInput.hint = "Dot sequence (e.g. 14789)"
                binding.etLockCredential.inputType = android.text.InputType.TYPE_CLASS_NUMBER // Numbers only for dots
                binding.tvPatternHelper.visibility = View.VISIBLE
            }
        }

        // Step 2: Save Credential button
        binding.btnSaveLock.setOnClickListener {
            val credential = binding.etLockCredential.text?.toString()?.trim() ?: ""
            if (credential.isBlank()) {
                Toast.makeText(this, "Please enter your PIN or Pattern sequence", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (binding.rbPin.isChecked) {
                // Save PIN
                commandProcessor.setPhonePin(credential)
                addToLog("🔓 Phone PIN saved for voice unlock")
            } else {
                // Save Pattern
                if (credential.length < 2) {
                    Toast.makeText(this, "Pattern must be at least 2 dots", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                commandProcessor.setPhonePattern(credential)
                addToLog("🔓 Phone Pattern saved for voice unlock")
            }

            // Reset UI
            binding.etLockCredential.text?.clear()
            binding.tvPinStatus.text = "✅ Screen lock credential saved — voice unlock active"
            binding.layoutLockInput.visibility = View.GONE
            binding.rgLockType.visibility = View.GONE
            binding.tvPatternHelper.visibility = View.GONE
            binding.btnVerifyIdentity.text = "✅ Setup Complete (tap to change)"
            Toast.makeText(this, "Credentials saved! Say 'unlock phone' to test.", Toast.LENGTH_LONG).show()
        }

        // Voice Code Setup
        val savedVoiceCode = getUnlockCode()
        if (savedVoiceCode.isNotBlank()) {
            binding.etVoiceCode.setText(savedVoiceCode)
            binding.btnSaveVoiceCode.text = "Update"
        }

        binding.btnSaveVoiceCode.setOnClickListener {
            val code = binding.etVoiceCode.text?.toString()?.trim() ?: ""
            if (code.length < 3) {
                Toast.makeText(this, "Code is too short (min 3 letters)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setUnlockCode(code)
            addToLog("🔐 Voice Security Code set: '$code'")
            Toast.makeText(this, "Voice Security Code saved!", Toast.LENGTH_SHORT).show()
            binding.btnSaveVoiceCode.text = "Update"
        }
    }

    /**
     * Called when system identity verification succeeds.
     * Shows the Lock Type selector (PIN/Pattern) and input field.
     */
    private fun onIdentityVerified() {
        identityVerified = true
        binding.rgLockType.visibility = View.VISIBLE
        binding.layoutLockInput.visibility = View.VISIBLE
        
        // Reset to PIN default
        binding.rbPin.isChecked = true
        binding.tvPatternHelper.visibility = View.GONE
        
        binding.btnVerifyIdentity.text = "✅ Identity Verified — Enter Lock Info below"
        Toast.makeText(this, "Identity verified! Now enter your phone PIN or Pattern.", Toast.LENGTH_SHORT).show()
        addToLog("🔓 Identity verified — Lock setup unlocked")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CREDENTIAL_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // User successfully verified with PIN/pattern/fingerprint
                onIdentityVerified()
            } else {
                // Verification failed or cancelled
                Toast.makeText(this, "Identity verification failed. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== VOICE CONTROL ====================

    private fun setupVoiceListener() {
        voiceManager.setListener(object : GoogleVoiceCommandManager.VoiceCommandListener {
            override fun onCommandReceived(command: String): Boolean {
                addToLog("🎤 \"$command\"")
                val success = commandProcessor.process(command)
                if (success) {
                    addToLog("✅ $command")
                } else {
                    addToLog("❌ $command")
                }
                return success
            }

            override fun onListeningStarted() {
                runOnUiThread {
                    val modeText = if (voiceManager.isOnline()) "🌐 Online" else "📤 Offline"
                    binding.tvVoiceStatus.text = "Listening ($modeText)"
                    binding.indicatorVoice.setBackgroundColor(
                        ContextCompat.getColor(this@MainActivity, R.color.status_active)
                    )
                    binding.btnStartVoice.text = "🛑  Stop Voice Control"
                }
            }

            override fun onListeningStopped() {
                runOnUiThread {
                    binding.tvVoiceStatus.text = getString(R.string.status_voice_off)
                    binding.indicatorVoice.setBackgroundColor(
                        ContextCompat.getColor(this@MainActivity, R.color.status_inactive)
                    )
                    binding.btnStartVoice.text = "🎤  Start Voice Control"
                }
            }

            override fun onError(errorMessage: String) {
                if (errorMessage.contains("permission", ignoreCase = true) ||
                    errorMessage.contains("not available", ignoreCase = true) ||
                    errorMessage.contains("stopped", ignoreCase = true)) {
                    addToLog("⚠️ $errorMessage")
                }
            }

            override fun onModeChanged(mode: GoogleVoiceCommandManager.RecognitionMode) {
                runOnUiThread {
                    val modeText = if (mode == GoogleVoiceCommandManager.RecognitionMode.ONLINE)
                        "🌐 Online" else "📤 Offline"
                    binding.tvVoiceStatus.text = "Listening ($modeText)"
                    addToLog("🔄 Voice mode: $modeText")
                }
            }

            override fun onStatusUpdate(status: String) {
                runOnUiThread {
                    binding.tvVoiceStatus.text = status
                }
            }
        })
    }

    private fun startVoiceControl() {
        if (!AutomationAccessibilityService.isRunning()) {
            Toast.makeText(this, "Please enable Accessibility Service first!", Toast.LENGTH_LONG).show()
            return
        }
        isVoiceActive = true
        neoState.activate()
        AutomationAccessibilityService.instance?.neoState = neoState
        voiceManager.startListening()
        startForegroundService()
        val mode = if (voiceManager.isOnline()) "🌐 Online" else "📤 Offline"
        addToLog("🎤 Voice started ($mode)")
    }

    private fun setUnlockCode(code: String) {
        commandProcessor.setUnlockCode(code)
    }

    fun getUnlockCode(): String {
        return commandProcessor.getUnlockCode()
    }
    private fun stopVoiceControl() {
        isVoiceActive = false
        neoState.deactivate()
        voiceManager.stopListening()
    }

    // ==================== BLUETOOTH CONTROL ====================

    private fun setupBluetoothListener() {
        bluetoothReceiver.setListener(object : BluetoothCommandReceiver.BluetoothListener {
            override fun onCommandReceived(command: String) {
                addToLog("📡 BT: \"$command\"")
                val success = commandProcessor.process(command)
                if (success) {
                    addToLog("✅ BT Executed: $command")
                    bluetoothReceiver.sendResponse("OK:$command")
                } else {
                    addToLog("❌ BT Failed: $command")
                    bluetoothReceiver.sendResponse("FAIL:$command")
                }
            }

            override fun onDeviceConnected(deviceName: String) {
                runOnUiThread {
                    binding.tvBluetoothStatus.text = "Connected: $deviceName"
                    binding.indicatorBluetooth.setBackgroundColor(
                        ContextCompat.getColor(this@MainActivity, R.color.status_active)
                    )
                    binding.btnConnectBluetooth.text = "📡  Disconnect Bluetooth"
                }
                addToLog("📡 BT Connected: $deviceName")
            }

            override fun onDeviceDisconnected() {
                runOnUiThread {
                    binding.tvBluetoothStatus.text = getString(R.string.status_bt_disconnected)
                    binding.indicatorBluetooth.setBackgroundColor(
                        ContextCompat.getColor(this@MainActivity, R.color.status_inactive)
                    )
                    binding.btnConnectBluetooth.text = "📡  Connect Bluetooth Device"
                }
                addToLog("📡 BT Disconnected")
            }

            override fun onError(message: String) {
                addToLog("⚠️ BT: $message")
            }
        })
    }

    private fun startBluetoothServer() {
        if (!AutomationAccessibilityService.isRunning()) {
            Toast.makeText(this, "Please enable Accessibility Service first!", Toast.LENGTH_LONG).show()
            return
        }

        val btPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!checkPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                btPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!checkPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                btPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (btPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, btPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
            return
        }

        isBluetoothActive = true
        bluetoothReceiver.startServer()
        startForegroundService()
        addToLog("📡 Bluetooth server started, waiting for device...")
    }

    private fun stopBluetoothServer() {
        isBluetoothActive = false
        bluetoothReceiver.stop()
    }

    // ==================== FOREGROUND SERVICE ====================

    private fun startForegroundService() {
        val intent = Intent(this, AutomationForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        stopService(Intent(this, AutomationForegroundService::class.java))
    }

    // ==================== PERMISSIONS ====================

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        val notGranted = permissions.filter { !checkPermission(it) }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                notGranted.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            Toast.makeText(this, "All permissions already granted! ✅", Toast.LENGTH_SHORT).show()
            addToLog("✅ All permissions granted")
        }
    }

    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            val total = grantResults.size
            addToLog("✅ Permissions granted: $granted/$total")
            Toast.makeText(this, "Permissions granted: $granted/$total", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== STATUS UI ====================

    private fun updateStatusIndicators() {
        // Accessibility Service
        val isServiceRunning = AutomationAccessibilityService.isRunning()
        binding.tvAccessibilityStatus.text = if (isServiceRunning) {
            getString(R.string.status_service_active)
        } else {
            getString(R.string.status_service_inactive)
        }
        binding.indicatorAccessibility.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isServiceRunning) R.color.status_active else R.color.status_inactive
            )
        )

        // Voice
        binding.tvVoiceStatus.text = if (voiceManager.isCurrentlyListening()) {
            getString(R.string.status_voice_listening)
        } else {
            getString(R.string.status_voice_off)
        }
        binding.indicatorVoice.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (voiceManager.isCurrentlyListening()) R.color.status_active else R.color.status_inactive
            )
        )

        // Bluetooth
        binding.tvBluetoothStatus.text = if (bluetoothReceiver.isConnected()) {
            getString(R.string.status_bt_connected)
        } else {
            getString(R.string.status_bt_disconnected)
        }
        binding.indicatorBluetooth.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (bluetoothReceiver.isConnected()) R.color.status_active else R.color.status_inactive
            )
        )
    }

    // ==================== COMMAND LOG ====================

    private fun addToLog(message: String) {
        Log.i(TAG, message)
        runOnUiThread {
            commandLog.add(0, message) // Add to top
            if (commandLog.size > 50) {
                commandLog.removeAt(commandLog.size - 1) // Keep max 50
            }
            binding.tvCommandLog.text = commandLog.joinToString("\n")
        }
    }
}
