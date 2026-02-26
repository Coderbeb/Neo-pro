package com.autoapk.automation.core

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Task 5.2: ErrorRecovery — Automatic error recovery system.
 *
 * When a command fails, try automatic recovery:
 *   1. SERVICE_DEAD    → guide user to re-enable accessibility
 *   2. APP_NOT_FOUND   → suggest similar app names
 *   3. QS_TILE_FAIL    → retry once, then fallback to Settings intent
 *   4. GESTURE_FAIL    → retry with longer duration
 *   5. UNKNOWN         → speak "Could not complete. Please try again"
 *
 * Max retry: 1 attempt per error.
 * After recovery: speak result.
 */
object ErrorRecovery {

    private const val TAG = "Neo_Recovery"
    private val handler = Handler(Looper.getMainLooper())

    enum class ErrorType {
        SERVICE_DEAD,
        APP_NOT_FOUND,
        QS_TILE_FAIL,
        GESTURE_FAIL,
        NAVIGATION_FAIL,
        TOGGLE_FAIL,
        UNKNOWN
    }

    data class RecoveryResult(
        val recovered: Boolean,
        val message: String
    )

    /**
     * Attempt automatic recovery for a failed command.
     *
     * @param errorType The type of error that occurred
     * @param context   Additional context (e.g., app name, tile name)
     * @param retryAction Optional retry lambda — called if recovery strategy is "retry"
     * @return RecoveryResult with success flag and message
     */
    fun recover(
        errorType: ErrorType,
        context: String = "",
        retryAction: (() -> Boolean)? = null
    ): RecoveryResult {
        Log.i(TAG, "Attempting recovery for $errorType (context='$context')")

        return when (errorType) {
            ErrorType.SERVICE_DEAD -> recoverServiceDead()
            ErrorType.APP_NOT_FOUND -> recoverAppNotFound(context)
            ErrorType.QS_TILE_FAIL -> recoverQSTile(context, retryAction)
            ErrorType.GESTURE_FAIL -> recoverGesture(context, retryAction)
            ErrorType.NAVIGATION_FAIL -> recoverNavigation(context, retryAction)
            ErrorType.TOGGLE_FAIL -> recoverToggle(context, retryAction)
            ErrorType.UNKNOWN -> recoverUnknown(context)
        }
    }

    // ==================== RECOVERY STRATEGIES ====================

    private fun recoverServiceDead(): RecoveryResult {
        Log.w(TAG, "Service is dead — prompting user")
        VoiceFeedback.error(
            "Voice control service is not running",
            "opening the accessibility settings and enabling Neo"
        )
        return RecoveryResult(false, "Service not connected. Please enable accessibility.")
    }

    private fun recoverAppNotFound(appName: String): RecoveryResult {
        Log.w(TAG, "App not found: '$appName'")
        VoiceFeedback.fail("find app $appName")
        return RecoveryResult(false, "App '$appName' not found")
    }

    private fun recoverQSTile(tileName: String, retryAction: (() -> Boolean)?): RecoveryResult {
        if (retryAction != null) {
            Log.i(TAG, "QS tile '$tileName' failed — retrying once")
            VoiceFeedback.retrying("$tileName toggle")

            // Retry after a short delay
            var result = false
            handler.postDelayed({
                result = retryAction.invoke()
                if (result) {
                    VoiceFeedback.success("$tileName toggled on retry")
                } else {
                    VoiceFeedback.fail("toggle $tileName even after retry")
                }
            }, 500)

            return RecoveryResult(true, "Retrying QS toggle for '$tileName'")
        }
        VoiceFeedback.fail("toggle $tileName")
        return RecoveryResult(false, "QS toggle failed for '$tileName'")
    }

    private fun recoverGesture(action: String, retryAction: (() -> Boolean)?): RecoveryResult {
        if (retryAction != null) {
            Log.i(TAG, "Gesture '$action' failed — retrying with longer duration")
            VoiceFeedback.retrying(action)
            val result = retryAction.invoke()
            if (result) {
                VoiceFeedback.success(action)
                return RecoveryResult(true, "Gesture succeeded on retry")
            }
        }
        VoiceFeedback.fail(action)
        return RecoveryResult(false, "Gesture failed for '$action'")
    }

    private fun recoverNavigation(action: String, retryAction: (() -> Boolean)?): RecoveryResult {
        if (retryAction != null) {
            Log.i(TAG, "Navigation '$action' failed — retrying")
            val result = retryAction.invoke()
            if (result) {
                return RecoveryResult(true, "Navigation succeeded on retry")
            }
        }
        VoiceFeedback.fail(action)
        return RecoveryResult(false, "Navigation failed for '$action'")
    }

    private fun recoverToggle(toggleName: String, retryAction: (() -> Boolean)?): RecoveryResult {
        if (retryAction != null) {
            Log.i(TAG, "Toggle '$toggleName' failed — retrying")
            VoiceFeedback.retrying("$toggleName toggle")
            val result = retryAction.invoke()
            if (result) {
                VoiceFeedback.success("$toggleName toggled")
                return RecoveryResult(true, "Toggle succeeded on retry")
            }
        }
        VoiceFeedback.fail("toggle $toggleName")
        return RecoveryResult(false, "Toggle failed for '$toggleName'")
    }

    private fun recoverUnknown(context: String): RecoveryResult {
        Log.w(TAG, "Unknown error — no recovery available (context='$context')")
        VoiceFeedback.error("Could not complete the action", "saying the command again")
        return RecoveryResult(false, "Unknown error")
    }
}
