package com.autoapk.automation.core

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedList
import java.util.Queue

/**
 * Rapido Ride Booking Automation
 *
 * Automates the Rapido app UI via Accessibility Service to book rides.
 * Handles: open app, search destination, confirm destination/pickup,
 * select ride type, confirm booking, read captain details, read PIN, cancel ride.
 *
 * Uses Handler.postDelayed() chains for step-by-step UI interaction with
 * appropriate delays between each step for the app to respond.
 */
class RapidoAutomation(private val context: Context) {

    companion object {
        private const val TAG = "Neo_Rapido"
        const val RAPIDO_PACKAGE = "com.rapido.passenger"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val service: AutomationAccessibilityService?
        get() = AutomationAccessibilityService.instance

    // ==================== OPEN RAPIDO ====================

    /**
     * Launch the Rapido app.
     * @return true if launch intent was sent
     */
    fun openApp(): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(RAPIDO_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Rapido app launched")
                true
            } else {
                Log.w(TAG, "Rapido not installed")
                service?.speak("Rapido is not installed")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Rapido: ${e.message}")
            service?.speak("Could not open Rapido")
            false
        }
    }

    // ==================== SEARCH DESTINATION ====================

    /**
     * Tap the destination search field and type the destination.
     * After typing, waits for suggestions to appear, then reads them.
     *
     * @param destination The place to search for
     * @param onSuggestionsRead Callback with the list of suggestion texts read from screen
     */
    fun searchDestination(destination: String, onSuggestionsRead: (List<String>) -> Unit) {
        Log.i(TAG, "Searching destination: $destination")

        // Step 1: Try to find and tap the destination/drop field
        handler.postDelayed({
            val svc = service ?: return@postDelayed
            val tapped = svc.findAndClickSmart("drop", silent = true)
                || svc.findAndClickSmart("Drop", silent = true)
                || svc.findAndClickSmart("Where", silent = true)
                || svc.findAndClickSmart("destination", silent = true)
                || svc.findAndClickSmart("Search", silent = true)
                || svc.findAndFocusTextField()

            if (!tapped) {
                Log.w(TAG, "Could not find destination field, trying to focus any text field")
                svc.findAndFocusTextField()
            }

            // Step 2: Type the destination
            handler.postDelayed({
                val typed = svc.findAndFocusTextField()
                if (typed) {
                    handler.postDelayed({
                        svc.inputText(destination)
                        Log.i(TAG, "Typed destination: $destination")

                        // Step 3: Wait for suggestions to load and read them
                        handler.postDelayed({
                            val suggestions = readSuggestions()
                            Log.i(TAG, "Found ${suggestions.size} suggestions")
                            onSuggestionsRead(suggestions)
                        }, 2000) // Wait 2s for suggestions
                    }, 500)
                } else {
                    Log.w(TAG, "Could not find text field to type destination")
                    svc.speak("Could not find search field in Rapido")
                }
            }, 800)
        }, 500)
    }

    /**
     * Read destination suggestions from the current screen.
     * Looks for clickable text items that appear below the search field.
     */
    private fun readSuggestions(): List<String> {
        val root = service?.rootInActiveWindow ?: return emptyList()
        val allNodes = flatten(root)
        val suggestions = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // Skip known UI labels
        val skipLabels = setOf(
            "search", "drop", "pickup", "where", "back", "close",
            "please enter", "enter drop", "enter pickup",
            "home", "work", "office", "saved places", "recent",
            "current location", "locate on map"
        )

        for (node in allNodes) {
            val text = node.text?.toString()?.trim() ?: continue
            if (text.length < 3 || text.length > 150) continue
            if (node.isEditable) continue

            val lower = text.lowercase()
            if (skipLabels.any { lower.contains(it) }) continue

            // Look for suggestion items: clickable nodes or those inside a list
            val className = node.className?.toString() ?: ""
            val isClickable = node.isClickable || findClickableParent(node) != null
            val isTextView = className.contains("TextView", true)

            if (isClickable && isTextView && text !in seen) {
                // Filter out very short items that are likely UI elements
                if (text.split(" ").size >= 2 || text.length > 10) {
                    seen.add(text)
                    suggestions.add(text)
                }
            }
        }

        // Cap at 3 suggestions
        return suggestions.take(3)
    }

    // ==================== SELECT SUGGESTION ====================

    /**
     * Tap a specific suggestion by its text or index (1-based).
     *
     * @param identifier Either the suggestion text or "1", "2", "3" for index
     * @param suggestions The list of suggestions previously read
     * @return true if a suggestion was clicked
     */
    fun selectSuggestion(identifier: String, suggestions: List<String>): Boolean {
        val svc = service ?: return false

        // Check if it's a number (1, 2, 3)
        val index = identifier.replace(Regex("[^0-9]"), "").toIntOrNull()
        if (index != null && index in 1..suggestions.size) {
            val targetText = suggestions[index - 1]
            Log.i(TAG, "Selecting suggestion #$index: $targetText")
            return svc.findAndClickSmart(targetText, silent = true)
        }

        // Check for ordinal words
        val ordinalMap = mapOf(
            "first" to 0, "pehla" to 0, "pehle" to 0, "pahla" to 0, "पहला" to 0,
            "second" to 1, "doosra" to 1, "dusra" to 1, "दूसरा" to 1,
            "third" to 2, "teesra" to 2, "tisra" to 2, "तीसरा" to 2
        )
        val lower = identifier.lowercase()
        for ((word, idx) in ordinalMap) {
            if (lower.contains(word) && idx < suggestions.size) {
                val targetText = suggestions[idx]
                Log.i(TAG, "Selecting suggestion by ordinal '$word': $targetText")
                return svc.findAndClickSmart(targetText, silent = true)
            }
        }

        // "yes" / "haan" = select first suggestion
        val yesWords = setOf("yes", "haan", "ha", "han", "ok", "okay", "sahi", "correct",
            "theek", "right", "हां", "ठीक", "सही")
        if (yesWords.any { lower.contains(it) } && suggestions.isNotEmpty()) {
            val targetText = suggestions[0]
            Log.i(TAG, "Confirmed first suggestion: $targetText")
            return svc.findAndClickSmart(targetText, silent = true)
        }

        // Try to match by text content
        for (suggestion in suggestions) {
            if (suggestion.contains(identifier, ignoreCase = true)) {
                Log.i(TAG, "Selecting suggestion by text match: $suggestion")
                return svc.findAndClickSmart(suggestion, silent = true)
            }
        }

        // Fallback: click the first suggestion
        if (suggestions.isNotEmpty()) {
            return svc.findAndClickSmart(suggestions[0], silent = true)
        }

        return false
    }

    // ==================== READ RIDE OPTIONS ====================

    /**
     * Read available ride types and fares from the screen.
     * Returns a list of RideOption (type + fare).
     */
    data class RideOption(val type: String, val fare: String)

    fun readRideOptions(): List<RideOption> {
        val root = service?.rootInActiveWindow ?: return emptyList()
        val screenText = service?.readScreen() ?: ""
        val options = mutableListOf<RideOption>()

        // Strategy 1: Look for known ride type labels
        val rideTypes = listOf("Bike", "Bike Taxi", "Bike-Taxi", "Auto", "Cab", "Car")
        val allNodes = flatten(root)

        for (rideType in rideTypes) {
            for (node in allNodes) {
                val text = node.text?.toString() ?: continue
                if (text.contains(rideType, ignoreCase = true)) {
                    // Try to find the fare near this node (sibling or nearby)
                    val fare = findFareNearNode(node, allNodes)
                    if (fare != null) {
                        options.add(RideOption(rideType, fare))
                    } else {
                        // Add without fare
                        options.add(RideOption(rideType, ""))
                    }
                    break // Found this ride type, move to next
                }
            }
        }

        // Strategy 2: Regex fallback on full screen text
        if (options.isEmpty()) {
            val farePattern = Regex("(Bike|Auto|Cab|Car)[^₹]*₹\\s*(\\d+)", RegexOption.IGNORE_CASE)
            farePattern.findAll(screenText).forEach { match ->
                val type = match.groupValues[1]
                val fare = "₹${match.groupValues[2]}"
                options.add(RideOption(type, fare))
            }
        }

        Log.i(TAG, "Ride options found: ${options.size} → $options")
        return options
    }

    /**
     * Find a fare (₹XX) near a given node in the tree.
     */
    private fun findFareNearNode(target: AccessibilityNodeInfo, allNodes: List<AccessibilityNodeInfo>): String? {
        // Look at siblings and nearby nodes for ₹ text
        val parent = target.parent ?: return null
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChild(i) ?: continue
            val text = sibling.text?.toString() ?: continue
            if (text.contains("₹")) {
                return text.trim()
            }
            // Check children of sibling too
            for (j in 0 until sibling.childCount) {
                val child = sibling.getChild(j) ?: continue
                val childText = child.text?.toString() ?: continue
                if (childText.contains("₹")) {
                    return childText.trim()
                }
            }
        }

        // Also check grandparent's children
        val grandParent = parent.parent
        if (grandParent != null) {
            for (i in 0 until grandParent.childCount) {
                val uncle = grandParent.getChild(i) ?: continue
                for (j in 0 until uncle.childCount) {
                    val cousin = uncle.getChild(j) ?: continue
                    val text = cousin.text?.toString() ?: continue
                    if (text.contains("₹")) {
                        return text.trim()
                    }
                }
            }
        }

        return null
    }

    // ==================== SELECT RIDE TYPE ====================

    /**
     * Tap a specific ride type (Bike, Auto, or Cab).
     */
    fun selectRideType(rideType: String): Boolean {
        val svc = service ?: return false
        Log.i(TAG, "Selecting ride type: $rideType")

        // Try multiple label variations
        val labels = when (rideType.lowercase()) {
            "bike", "bike taxi", "bike-taxi" -> listOf("Bike", "Bike Taxi", "Bike-Taxi", "bike")
            "auto" -> listOf("Auto", "auto", "AUTO")
            "cab", "car" -> listOf("Cab", "Car", "cab", "car")
            else -> listOf(rideType)
        }

        for (label in labels) {
            if (svc.findAndClickSmart(label, silent = true)) {
                Log.i(TAG, "Clicked ride type: $label")
                return true
            }
        }

        Log.w(TAG, "Could not find ride type: $rideType")
        return false
    }

    // ==================== CONFIRM BOOKING ====================

    /**
     * Tap the booking confirmation button.
     * Tries common button labels: "Book Auto", "Book Bike", "Book Cab", "Book", "Confirm".
     */
    fun confirmBooking(rideType: String): Boolean {
        val svc = service ?: return false
        Log.i(TAG, "Confirming booking for: $rideType")

        val buttons = listOf(
            "Book $rideType",
            "Book ${rideType.replaceFirstChar { it.uppercase() }}",
            "Book",
            "Confirm",
            "BOOK",
            "CONFIRM",
            "Confirm Booking",
            "Book Ride",
            "Book Now",
            "RIDE NOW"
        )

        for (btn in buttons) {
            if (svc.findAndClickSmart(btn, silent = true)) {
                Log.i(TAG, "Clicked booking button: $btn")
                return true
            }
        }

        Log.w(TAG, "Could not find booking button")
        return false
    }

    // ==================== READ PICKUP LOCATION ====================

    /**
     * Read the current pickup location shown on screen.
     */
    fun readPickupLocation(): String {
        val screenText = service?.readScreen() ?: ""

        // Look for text near "pickup" or after the pickup field
        val root = service?.rootInActiveWindow ?: return ""
        val allNodes = flatten(root)

        for (node in allNodes) {
            val text = node.text?.toString() ?: continue
            val lower = text.lowercase()

            // Find the pickup location text (not the label itself)
            if (lower.contains("pickup") && !lower.contains("enter") && !lower.contains("please")) {
                // The actual location is usually in a nearby node
                val parent = node.parent ?: continue
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    val sibText = sibling.text?.toString() ?: continue
                    if (!sibText.lowercase().contains("pickup") && sibText.length > 5) {
                        Log.i(TAG, "Pickup location found: $sibText")
                        return sibText.trim()
                    }
                }
            }
        }

        // Fallback: look for "Current location" or similar
        for (node in allNodes) {
            val text = node.text?.toString() ?: continue
            if (text.contains("Current location", ignoreCase = true) ||
                text.contains("current location", ignoreCase = true)) {
                return "Current location"
            }
        }

        Log.i(TAG, "Could not determine pickup location")
        return "Your current location"
    }

    // ==================== READ PIN ====================

    /**
     * Read the Rapid PIN from the current screen.
     * Rapido shows a fixed PIN for each user.
     */
    fun readPin(): String? {
        val screenText = service?.readScreen() ?: ""

        // Look for PIN pattern in screen text
        val pinPatterns = listOf(
            Regex("(?:PIN|Pin|pin)[:\\s]*([0-9]{4,6})"),
            Regex("(?:Rapid\\s*PIN|RAPID\\s*PIN)[:\\s]*([0-9]{4,6})"),
            Regex("(?:OTP|otp)[:\\s]*([0-9]{4,6})")
        )

        for (pattern in pinPatterns) {
            pattern.find(screenText)?.let { match ->
                val pin = match.groupValues[1]
                Log.i(TAG, "PIN found: $pin")
                return pin
            }
        }

        // Fallback: scan nodes for PIN
        val root = service?.rootInActiveWindow ?: return null
        val allNodes = flatten(root)

        var foundPinLabel = false
        for (node in allNodes) {
            val text = node.text?.toString() ?: continue
            val lower = text.lowercase()

            if (lower.contains("pin")) {
                foundPinLabel = true
                // Check if the PIN value is in the same node
                val pinMatch = Regex("([0-9]{4,6})").find(text)
                if (pinMatch != null) {
                    Log.i(TAG, "PIN from same node: ${pinMatch.value}")
                    return pinMatch.value
                }
                // Look in nearby sibling nodes
                val parent = node.parent ?: continue
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    val sibText = sibling.text?.toString() ?: continue
                    val sibPin = Regex("^[0-9]{4,6}$").find(sibText.trim())
                    if (sibPin != null) {
                        Log.i(TAG, "PIN from sibling: ${sibPin.value}")
                        return sibPin.value
                    }
                }
            }
        }

        Log.w(TAG, "PIN not found on screen (foundLabel=$foundPinLabel)")
        return null
    }

    // ==================== READ CAPTAIN DETAILS ====================

    /**
     * Read captain (driver) details from the screen.
     * Returns a CaptainDetails object with name, vehicle, ETA, PIN.
     */
    data class CaptainDetails(
        val name: String = "",
        val vehicleNumber: String = "",
        val eta: String = "",
        val pin: String = ""
    )

    fun readCaptainDetails(): CaptainDetails? {
        val screenText = service?.readScreen() ?: ""
        val root = service?.rootInActiveWindow ?: return null
        val allNodes = flatten(root)

        var name = ""
        var vehicleNumber = ""
        var eta = ""
        var pin = readPin() ?: ""

        // Look for captain name (usually near "Captain" label)
        for (node in allNodes) {
            val text = node.text?.toString() ?: continue
            val lower = text.lowercase()

            // Captain name
            if (lower.contains("captain") && text.length > 8) {
                name = text.replace(Regex("(?i)captain\\s*"), "").trim()
                if (name.isBlank()) {
                    // Name might be in sibling node
                    val parent = node.parent
                    if (parent != null) {
                        for (i in 0 until parent.childCount) {
                            val sib = parent.getChild(i)?.text?.toString() ?: continue
                            if (!sib.lowercase().contains("captain") && sib.length in 2..30) {
                                name = sib.trim()
                                break
                            }
                        }
                    }
                }
            }

            // Vehicle number — Indian format: XX 00 XX 0000
            val vehiclePattern = Regex("[A-Z]{2}\\s*\\d{1,2}\\s*[A-Z]{1,3}\\s*\\d{1,4}")
            vehiclePattern.find(text)?.let {
                vehicleNumber = it.value.trim()
            }

            // ETA — "X min" or "arriving in X minutes"
            val etaPattern = Regex("(\\d+)\\s*(?:min|mins|minute|minutes)")
            etaPattern.find(lower)?.let {
                eta = "${it.groupValues[1]} minutes"
            }
        }

        // If we found anything useful, return it
        return if (name.isNotBlank() || vehicleNumber.isNotBlank() || eta.isNotBlank() || pin.isNotBlank()) {
            val details = CaptainDetails(name, vehicleNumber, eta, pin)
            Log.i(TAG, "Captain details: $details")
            details
        } else {
            Log.w(TAG, "No captain details found on screen")
            null
        }
    }

    // ==================== CANCEL RIDE ====================

    /**
     * Cancel the current ride.
     * Taps Cancel → selects first reason → confirms.
     */
    fun cancelRide(onComplete: (Boolean) -> Unit) {
        val svc = service ?: run {
            onComplete(false)
            return
        }

        Log.i(TAG, "Cancelling ride")

        // Try to find cancel button
        val cancelled = svc.findAndClickSmart("Cancel", silent = true)
            || svc.findAndClickSmart("Cancel Ride", silent = true)
            || svc.findAndClickSmart("cancel", silent = true)

        if (cancelled) {
            // Wait for cancel reason screen
            handler.postDelayed({
                // Try to select first reason
                val root = svc.rootInActiveWindow
                if (root != null) {
                    val nodes = flatten(root)
                    var clickedReason = false
                    for (node in nodes) {
                        val text = node.text?.toString() ?: continue
                        val lower = text.lowercase()
                        // Look for cancel reason options
                        if (lower.contains("change") || lower.contains("plan") ||
                            lower.contains("other") || lower.contains("reason")) {
                            val clickable = findClickableParent(node) ?: node
                            if (clickable.isClickable) {
                                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                clickedReason = true
                                break
                            }
                        }
                    }

                    // After selecting reason, confirm
                    handler.postDelayed({
                        svc.findAndClickSmart("Confirm", silent = true)
                            || svc.findAndClickSmart("Yes", silent = true)
                            || svc.findAndClickSmart("Submit", silent = true)
                            || svc.findAndClickSmart("Cancel Ride", silent = true)

                        Log.i(TAG, "Ride cancellation completed")
                        onComplete(true)
                    }, 1000)
                } else {
                    onComplete(false)
                }
            }, 1500)
        } else {
            Log.w(TAG, "Could not find Cancel button")
            onComplete(false)
        }
    }

    // ==================== CHECK RAPIDO FOREGROUND ====================

    /**
     * Check if Rapido is currently in the foreground.
     */
    fun isRapidoInForeground(): Boolean {
        val currentPkg = service?.rootInActiveWindow?.packageName?.toString() ?: ""
        return currentPkg.contains("rapido", ignoreCase = true)
    }

    // ==================== CHECK CAPTAIN ASSIGNED ====================

    /**
     * Check if a captain has been assigned by looking for captain-related
     * text on screen. Used for polling after booking.
     */
    fun isCaptainAssigned(): Boolean {
        val screenText = service?.readScreen()?.lowercase() ?: ""
        return screenText.contains("captain") ||
               screenText.contains("arriving") ||
               screenText.contains("on the way") ||
               screenText.contains("otp") ||
               screenText.contains("pin") && screenText.contains("min")
    }

    // ==================== HELPERS ====================

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll() ?: continue
            result.add(n)
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }
}
