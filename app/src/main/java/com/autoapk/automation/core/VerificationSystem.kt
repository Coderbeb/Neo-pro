package com.autoapk.automation.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Verification System
 * 
 * Manages verification code storage, validation, and enforcement for sensitive operations.
 * Uses encrypted SharedPreferences for secure storage of verification codes.
 * 
 * Verification Requirements by State:
 * - NORMAL: No verification
 * - CONSUMING_CONTENT: All commands require verification
 * - IN_CALL: All commands require verification (code as prefix)
 * - CAMERA_ACTIVE: Only capture/record commands require verification
 * 
 * Requirements: 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13, 3.14
 */
class VerificationSystem(private val context: Context) {

    companion object {
        private const val TAG = "VerificationSystem"
        private const val PREFS_NAME = "verification_prefs"
        private const val KEY_VERIFICATION_CODE = "verification_code"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val DEFAULT_CODE = "1234"
        private const val MAX_ATTEMPTS = 3
        
        // Spoken number mappings
        private val SPOKEN_NUMBERS = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
            "oh" to "0", "to" to "2", "too" to "2", "for" to "4", "ate" to "8"
        )
    }

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            // Use encrypted SharedPreferences for security
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted preferences, falling back to standard: ${e.message}")
            // Fallback to standard SharedPreferences if encryption fails
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Set verification code
     * Requirement: 3.11 - Allow user to configure custom code
     */
    fun setVerificationCode(code: String) {
        val normalizedCode = code.trim()
        if (normalizedCode.isEmpty()) {
            Log.w(TAG, "Cannot set empty verification code")
            return
        }
        
        sharedPreferences.edit()
            .putString(KEY_VERIFICATION_CODE, normalizedCode)
            .apply()
        
        Log.i(TAG, "Verification code updated (length: ${normalizedCode.length})")
    }

    /**
     * Get verification code
     * Requirement: 3.12 - Default 4-digit numeric code
     */
    fun getVerificationCode(): String {
        return sharedPreferences.getString(KEY_VERIFICATION_CODE, DEFAULT_CODE) ?: DEFAULT_CODE
    }

    /**
     * Validate verification code
     * Requirement: 3.13, 3.14 - Validation with feedback
     */
    fun validateCode(inputCode: String): Boolean {
        val storedCode = getVerificationCode()
        val normalized = inputCode.replace(" ", "").trim().lowercase()
        val isValid = normalized == storedCode.lowercase()
        
        if (isValid) {
            Log.i(TAG, "Verification code validated successfully")
            resetAttempts()
        } else {
            Log.w(TAG, "Verification code validation failed")
            incrementFailedAttempts()
        }
        
        return isValid
    }

    /**
     * Check if verification is required for a command in a given state
     * Requirements: 3.6, 3.7, 3.8, 3.9, 3.10
     */
    /**
     * Check if verification is required for a command in a given state
     * Requirements: 3.6, 3.7, 3.8, 3.9, 3.10
     */
    fun requiresVerification(state: PhoneStateDetector.PhoneState, command: String): Boolean {
        return when (state) {
            PhoneStateDetector.PhoneState.NORMAL -> {
                // Requirement 3.6: No verification in NORMAL state
                false
            }
            PhoneStateDetector.PhoneState.CONSUMING_CONTENT -> {
                // Requirement 3.7: complex commands require verification, but simple navigation should be free
                // User Feedback: "simple task then just perform"
                if (isSimpleCommand(command)) {
                    false
                } else {
                    true
                }
            }
            PhoneStateDetector.PhoneState.IN_CALL -> {
                // Requirement 3.8: All commands require verification
                true
            }
            PhoneStateDetector.PhoneState.CAMERA_ACTIVE -> {
                // Requirement 3.9, 3.10: Only capture/record commands require verification
                isCameraCaptureCommand(command)
            }
        }
    }

    /**
     * Check if command is a "simple" command that doesn't need verification
     * even when consuming content (YouTube/Insta).
     */
    private fun isSimpleCommand(command: String): Boolean {
        val lower = command.lowercase()
        return lower.contains("scroll") || 
               lower.contains("swipe") ||
               lower.contains("volume") ||
               lower.contains("mute") ||
               lower.contains("unmute") ||
               lower.contains("play") ||
               lower.contains("pause") ||
               lower.contains("next") ||
               lower.contains("skip") ||
               lower.contains("back") ||
               lower.contains("home")
    }

    /**
     * Check if command is a camera capture/record command
     */
    private fun isCameraCaptureCommand(command: String): Boolean {
        val lowerCommand = command.lowercase()
        val captureKeywords = listOf(
            "take photo", "take picture", "capture", "take selfie", "click photo",
            "start recording", "record video", "stop recording",
            "flash", "zoom"
        )
        
        return captureKeywords.any { lowerCommand.contains(it) }
    }

    /**
     * Extract verification code from command
     * Supports both numeric and spoken number formats
     * Returns: Pair(extractedCode, remainingCommand)
     * 
     * Examples:
     * - "1234 end call" → ("1234", "end call")
     * - "one two three four mute" → ("1234", "mute")
     */
    /**
     * Extract verification code from command
     * Supports alphanumeric codes (e.g. "aura") and spoken numbers
     * Returns: Pair(extractedCode, remainingCommand)
     */
    fun extractCodeFromCommand(command: String): Pair<String?, String> {
        val storedCode = getVerificationCode().lowercase().trim()
        val input = command.trim().lowercase()
        
        // Direct prefix check (e.g., "aura open youtube")
        if (input.startsWith(storedCode)) {
            val remaining = input.removePrefix(storedCode).trim()
            return Pair(storedCode, remaining)
        }
        
        // Also check if code is separated by space (just in case)
        val words = input.split("\\s+".toRegex())
        if (words.isNotEmpty() && words[0] == storedCode) {
             val remaining = words.drop(1).joinToString(" ")
             return Pair(storedCode, remaining)
        }

        // Fallback: Try numeric code extraction for legacy support
        val numericCode = extractNumericCode(words)
        if (numericCode != null) {
            val remainingWords = words.drop(numericCode.first)
            return Pair(numericCode.second, remainingWords.joinToString(" "))
        }
        
        // Fallback: Try spoken numbers
        val spokenCode = extractSpokenCode(words)
        if (spokenCode != null) {
            val remainingWords = words.drop(spokenCode.first)
            return Pair(spokenCode.second, remainingWords.joinToString(" "))
        }
        
        // No code found
        return Pair(null, command)
    }

    /**
     * Extract numeric code from leading words
     */
    private fun extractNumericCode(words: List<String>): Pair<Int, String>? {
        val codeLength = getVerificationCode().length
        var code = ""
        var wordCount = 0
        
        for (word in words) {
            if (word.all { it.isDigit() }) {
                code += word
                wordCount++
                if (code.length >= codeLength) {
                    return Pair(wordCount, code.take(codeLength))
                }
            } else {
                break
            }
        }
        
        return if (code.length == codeLength) {
            Pair(wordCount, code)
        } else {
            null
        }
    }

    /**
     * Extract spoken number code from leading words
     */
    private fun extractSpokenCode(words: List<String>): Pair<Int, String>? {
        val codeLength = getVerificationCode().length
        var code = ""
        var wordCount = 0
        
        for (word in words) {
            val digit = SPOKEN_NUMBERS[word.lowercase()]
            if (digit != null) {
                code += digit
                wordCount++
                if (code.length >= codeLength) {
                    return Pair(wordCount, code.take(codeLength))
                }
            } else {
                break
            }
        }
        
        return if (code.length == codeLength) {
            Pair(wordCount, code)
        } else {
            null
        }
    }

    /**
     * Reset failed attempts counter
     */
    fun resetAttempts() {
        sharedPreferences.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .apply()
    }

    /**
     * Get remaining attempts before lockout
     */
    fun getRemainingAttempts(): Int {
        val failedAttempts = sharedPreferences.getInt(KEY_FAILED_ATTEMPTS, 0)
        return maxOf(0, MAX_ATTEMPTS - failedAttempts)
    }

    /**
     * Increment failed attempts counter
     */
    private fun incrementFailedAttempts() {
        val currentAttempts = sharedPreferences.getInt(KEY_FAILED_ATTEMPTS, 0)
        val newAttempts = currentAttempts + 1
        
        sharedPreferences.edit()
            .putInt(KEY_FAILED_ATTEMPTS, newAttempts)
            .apply()
        
        val remaining = getRemainingAttempts()
        if (remaining > 0) {
            Log.w(TAG, "Failed verification attempt. Remaining attempts: $remaining")
        } else {
            Log.e(TAG, "Maximum verification attempts exceeded. Lockout activated.")
        }
    }

    /**
     * Check if account is locked due to too many failed attempts
     */
    fun isLocked(): Boolean {
        return getRemainingAttempts() <= 0
    }
}
