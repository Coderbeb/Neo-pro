package com.autoapk.automation.ui

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autoapk.automation.core.AutomationAccessibilityService

/**
 * Phase 7: SetupActivity — Permission setup wizard.
 *
 * Guides user through enabling all required permissions and services
 * with live verification at each step.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Neo_Setup"
        private const val REQUEST_RECORD_AUDIO = 100
        private const val REQUEST_BLUETOOTH = 101
    }

    private lateinit var statusContainer: LinearLayout
    private lateinit var nextButton: Button
    private lateinit var titleText: TextView
    private lateinit var descText: TextView
    private lateinit var statusIcon: ImageView

    private var currentStep = 0
    private val totalSteps = 7

    private var serviceVerified = false

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.autoapk.automation.SERVICE_VERIFIED" -> {
                    serviceVerified = true
                    updateStep()
                }
                "com.autoapk.automation.SERVICE_FAILED" -> {
                    serviceVerified = false
                    updateStep()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build UI programmatically
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(48, 96, 48, 48)
            setBackgroundColor(0xFF121212.toInt())
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        titleText = TextView(this).apply {
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 24)
            text = "Welcome to Voice Assistant"
        }
        mainLayout.addView(titleText)

        descText = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFB0B0B0.toInt())
            setPadding(0, 0, 0, 32)
            text = "Let's set up all the required permissions."
        }
        mainLayout.addView(descText)

        statusIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(128, 128).apply {
                bottomMargin = 32
            }
            setImageResource(android.R.drawable.ic_dialog_info)
        }
        mainLayout.addView(statusIcon)

        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }
        mainLayout.addView(statusContainer)

        nextButton = Button(this).apply {
            text = "Next"
            textSize = 18f
            setPadding(48, 24, 48, 24)
            setOnClickListener { onNextClicked() }
        }
        mainLayout.addView(nextButton)

        scrollView.addView(mainLayout)
        setContentView(scrollView)

        // Register service broadcast
        val filter = IntentFilter().apply {
            addAction("com.autoapk.automation.SERVICE_VERIFIED")
            addAction("com.autoapk.automation.SERVICE_FAILED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }

        updateStep()
    }

    override fun onDestroy() {
        try { unregisterReceiver(serviceReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateStep()
    }

    private fun onNextClicked() {
        when (currentStep) {
            0 -> currentStep = 1  // Welcome → Accessibility
            1 -> {
                // Open accessibility settings
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                return
            }
            2 -> {
                // Request microphone
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                return
            }
            3 -> {
                // Request write settings
                if (!Settings.System.canWrite(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    return
                }
                currentStep = 4
            }
            4 -> {
                // Request DND access
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    startActivity(intent)
                    return
                }
                currentStep = 5
            }
            5 -> {
                // Disable battery optimization
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                        return
                    }
                }
                currentStep = 6
            }
            6 -> {
                // All done — go to main activity
                finish()
                return
            }
        }
        updateStep()
    }

    private fun updateStep() {
        // Auto-advance steps based on current permission state
        when {
            currentStep == 1 && isAccessibilityEnabled() -> currentStep = 2
            currentStep == 2 && hasMicPermission() -> currentStep = 3
            currentStep == 3 && Settings.System.canWrite(this) -> currentStep = 4
            currentStep == 4 && hasDndAccess() -> currentStep = 5
            currentStep == 5 && isBatteryOptimized() -> currentStep = 6
        }

        when (currentStep) {
            0 -> {
                titleText.text = "Welcome to Voice Assistant"
                descText.text = "We need a few permissions to control your phone with voice commands.\n\nThis setup will take about 2 minutes."
                nextButton.text = "Get Started"
                statusIcon.setImageResource(android.R.drawable.ic_dialog_info)
            }
            1 -> {
                titleText.text = "Step 1: Accessibility Service"
                val isEnabled = isAccessibilityEnabled()
                descText.text = if (isEnabled) {
                    "✅ Accessibility Service is enabled!\n\nService verified: ${if (serviceVerified) "✅ Working" else "⏳ Checking..."}"
                } else {
                    "The accessibility service is required for voice control.\n\nTap the button below, find 'Neo' in the list, and enable it."
                }
                nextButton.text = if (isEnabled) "Next" else "Open Accessibility Settings"
                statusIcon.setImageResource(if (isEnabled) android.R.drawable.ic_dialog_info else android.R.drawable.ic_dialog_alert)
                if (isEnabled) currentStep = 2
            }
            2 -> {
                titleText.text = "Step 2: Microphone Permission"
                val hasPermission = hasMicPermission()
                descText.text = if (hasPermission) {
                    "✅ Microphone permission granted!"
                } else {
                    "We need microphone access to listen to your voice commands."
                }
                nextButton.text = if (hasPermission) "Next" else "Allow Microphone"
                if (hasPermission) currentStep = 3
            }
            3 -> {
                titleText.text = "Step 3: Modify Settings"
                val canWrite = Settings.System.canWrite(this)
                descText.text = if (canWrite) {
                    "✅ Settings modification allowed!"
                } else {
                    "This permission allows controlling brightness and auto-rotate."
                }
                nextButton.text = if (canWrite) "Next" else "Allow Modify Settings"
                if (canWrite) currentStep = 4
            }
            4 -> {
                titleText.text = "Step 4: Do Not Disturb Control"
                val hasAccess = hasDndAccess()
                descText.text = if (hasAccess) {
                    "✅ DND control enabled!"
                } else {
                    "This allows toggling Do Not Disturb mode with voice commands."
                }
                nextButton.text = if (hasAccess) "Next" else "Allow DND Control"
                if (hasAccess) currentStep = 5
            }
            5 -> {
                titleText.text = "Step 5: Battery Optimization"
                val optimized = isBatteryOptimized()
                descText.text = if (optimized) {
                    "✅ Battery optimization disabled for this app!"
                } else {
                    "Disabling battery optimization prevents Android from killing the voice assistant in the background.\n\nThis is critical for reliability."
                }
                nextButton.text = if (optimized) "Next" else "Disable Battery Optimization"
                if (optimized) currentStep = 6
            }
            6 -> {
                titleText.text = "All Set! 🎉"
                descText.text = "Everything is configured.\n\nTry saying:\n• \"Go home\"\n• \"Turn on WiFi\"\n• \"Open YouTube\"\n• \"What's the battery?\""
                nextButton.text = "Done"
                statusIcon.setImageResource(android.R.drawable.ic_dialog_info)
            }
        }
    }

    // ==================== PERMISSION CHECKS ====================

    private fun isAccessibilityEnabled(): Boolean {
        return AutomationAccessibilityService.isRunning()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasDndAccess(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isBatteryOptimized(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            currentStep = 3
        }
        updateStep()
    }
}
