package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.LinkedList
import java.util.Queue

/**
 * WhatsApp Automation — Full Live Chat System
 *
 * Provides:
 *   - openChat(contact): Open specific contact's chat (FIXED navigation)
 *   - sendMessage(contact, message): Open chat → type → send
 *   - sendInActiveChat(message): Send in currently open chat (no re-navigation)
 *   - readLastMessages(count): Read recent messages via contentDescription parsing
 *   - readUnreadMessages(): Auto-read unread messages when chat opens
 *   - callContact(contact): Open chat → click voice call
 *   - startReplyMonitor(): Background polling for new incoming messages
 *   - stopReplyMonitor(): Stop monitoring and clear session
 *
 * All methods use ScreenWaitSystem for verified step transitions.
 */
class WhatsAppAutomation(
    private val service: AccessibilityService,
    private val finder: AdaptiveNodeFinder,
    private val waiter: ScreenWaitSystem,
    private val tts: (String) -> Unit
) {

    companion object {
        private const val TAG = "Neo_WhatsApp"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val REPLY_POLL_INTERVAL_MS = 2500L
    }

    // === LIVE CHAT SESSION STATE ===
    private var activeChatContact: String? = null
    private var isLiveChat: Boolean = false
    private var lastKnownMessages: List<String> = emptyList()
    private var replyMonitorJob: Job? = null
    private val monitorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // === PENDING RETRY STATE ===
    // When contact not found, stores context so next voice input retries with corrected name
    private var pendingRetryMessage: String? = null   // message to send after retry
    private var pendingRetryAction: String? = null     // "send", "call", "videocall", "open"

    /** Check if a live chat session is active */
    val isInLiveChat: Boolean get() = isLiveChat

    /** Get the name of the contact in the active chat */
    fun getActiveChatContact(): String? = activeChatContact

    /** Check if there's a pending retry from a failed contact search */
    fun hasPendingRetry(): Boolean = pendingRetryAction != null

    /** Consume and return the pending retry (action, message). Clears the state. */
    fun consumePendingRetry(): Pair<String, String?> {
        val action = pendingRetryAction ?: "send"
        val message = pendingRetryMessage
        pendingRetryAction = null
        pendingRetryMessage = null
        return Pair(action, message)
    }

    /** Set pending retry state when contact not found */
    private fun setPendingRetry(action: String, message: String? = null) {
        pendingRetryAction = action
        pendingRetryMessage = message
        Log.i(TAG, "Pending retry set: action=$action, message=$message")
    }

    // ==================== CORE FEATURES ====================

    /**
     * Open a specific contact's chat in WhatsApp.
     * Steps: Launch WA → navigate to chat list → Search → Type name → Click result → Read unread → Start monitor
     *
     * SMART: If WhatsApp is already open, skips launching.
     * SMART: If in another chat, presses Back to return to chat list first.
     * SMART: If contact not found, announces and returns false.
     */
    suspend fun openChat(contact: String): Boolean {
        Log.i(TAG, "Opening chat with: $contact")

        // If already in this chat, just stay
        if (isLiveChat && activeChatContact?.equals(contact, true) == true) {
            tts("Already in $contact's chat")
            return true
        }

        // Stop any existing monitor
        stopReplyMonitor()

        // Step 1: Launch WhatsApp (smart — skips if already open)
        if (!launchWhatsApp()) return false

        // Step 2: Navigate to chat list if not already there
        // If we're inside a chat or some other screen, press Back to get to main screen
        delay(500)
        val root1 = service.rootInActiveWindow
        val hasSearch = root1?.let { findNodeByText(it, "Search") } != null
        if (!hasSearch) {
            // Probably inside a chat or sub-screen — press Back
            Log.i(TAG, "Search not visible — pressing Back to return to chat list")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(800)
            // Try one more time
            val root2 = service.rootInActiveWindow
            if (root2?.let { findNodeByText(it, "Search") } == null) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                delay(800)
            }
        }

        // Step 3: Click Search button
        delay(300)
        val searchBtn = finder.find("Search")
            ?: finder.find("search")
            ?: run { return fail("Can't find search button") }
        searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!waiter.waitForEditableField(3000)) return fail("Search field didn't appear")

        // Step 4: Type contact name
        delay(300)
        val searchField = waiter.findEditable(service.rootInActiveWindow)
            ?: return fail("Can't find text input")
        searchField.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contact)
            }
        )
        delay(2000) // Wait for search results (needs network for some contacts)

        // Step 5: Click the matching contact (multi-strategy)
        val clicked = clickSearchResult(contact)
        if (!clicked) {
            // Contact not found — inform user
            tts("Contact $contact not found in WhatsApp. Please try saying the full name.")
            // Press back to dismiss search
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(300)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return false
        }

        // Step 6: Wait for chat to open (look for message input field)
        if (!waiter.waitForEditableField(5000)) {
            delay(1000)
            if (!waiter.waitForEditableField(3000)) {
                return fail("Chat didn't open")
            }
        }

        // Step 7: Set up live chat session
        activeChatContact = contact
        isLiveChat = true
        Log.i(TAG, "✅ Chat opened with $contact — live session started")

        // Step 8: Read unread messages
        val unread = readUnreadMessages()
        if (unread.isEmpty()) {
            tts("Opened $contact's chat")
        }

        // Step 9: Start reply monitoring
        startReplyMonitor()

        return true
    }

    /**
     * IMPROVED: Click search result with multiple fallback strategies.
     *
     * Strategy 1: finder.find(contact) — fuzzy text match
     * Strategy 2: Look for nodes with contentDescription containing the contact name
     * Strategy 3: Look specifically inside list/recycler views for matching text
     * Strategy 4: Click the first non-editable, non-button visible text match
     */
    private suspend fun clickSearchResult(contact: String): Boolean {
        val root = service.rootInActiveWindow ?: return false

        // Strategy 1: Direct fuzzy find
        val directMatch = finder.find(contact)
        if (directMatch != null) {
            // Make sure we're not clicking the search field itself
            val isEditable = directMatch.isEditable ||
                directMatch.className?.toString()?.contains("EditText") == true
            if (!isEditable) {
                Log.i(TAG, "Strategy 1: Direct match for '$contact'")
                directMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        // Strategy 2: Search contentDescription of all nodes
        val allNodes = flatten(root)
        for (node in allNodes) {
            val desc = node.contentDescription?.toString() ?: continue
            if (desc.contains(contact, ignoreCase = true)) {
                // Skip editable fields
                if (node.isEditable) continue
                Log.i(TAG, "Strategy 2: contentDescription match — '$desc'")
                val clickable = findClickableAncestor(node) ?: node
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        // Strategy 3: Search text of all nodes for the contact name
        for (node in allNodes) {
            val text = node.text?.toString() ?: continue
            if (text.contains(contact, ignoreCase = true)) {
                // Skip editable fields and very short text
                if (node.isEditable) continue
                if (text.length < 2) continue
                Log.i(TAG, "Strategy 3: Text match — '$text'")
                val clickable = findClickableAncestor(node) ?: node
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        // Strategy 4: Try clicking via accessibility service's clickOnContentDescription
        val svc = service as? AutomationAccessibilityService
        if (svc != null) {
            Log.i(TAG, "Strategy 4: clickOnContentDescription fallback")
            return svc.clickOnContentDescription(contact)
        }

        return false
    }

    /**
     * Walk up the tree to find a clickable ancestor node.
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Send a message to a contact.
     * Opens chat first (if not already open), then types and sends.
     * If contact not found, sets pending retry so next voice input retries with corrected name.
     */
    suspend fun sendMessage(contact: String, message: String): Boolean {
        Log.i(TAG, "Sending message to $contact: '$message'")

        // If already in this contact's chat, just send directly
        if (isLiveChat && activeChatContact?.equals(contact, true) == true) {
            return sendInActiveChat(message)
        }

        // Open the chat first
        if (!openChat(contact)) {
            // Contact not found — set pending retry so next input retries
            setPendingRetry("send", message)
            return false
        }

        // Send in the now-active chat
        return sendInActiveChat(message)
    }

    /**
     * Send a message in the currently active chat session.
     * No re-navigation — just type and send.
     *
     * Used when user says "send him how are you" while already in a chat.
     */
    suspend fun sendInActiveChat(message: String): Boolean {
        if (!isLiveChat) {
            Log.w(TAG, "sendInActiveChat called but no active chat")
            tts("No active chat. Open a chat first.")
            return false
        }

        Log.i(TAG, "Sending in active chat ($activeChatContact): '$message'")

        // Find the message input field
        val msgField = waiter.findEditable(service.rootInActiveWindow)
            ?: run {
                // Maybe keyboard closed — click on the text area to focus
                delay(500)
                waiter.findEditable(service.rootInActiveWindow)
            }
            ?: return fail("Can't find message box")

        // Click to focus
        msgField.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(300)

        // Type the message
        msgField.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
        )
        delay(500)

        // Click Send button
        val sendBtn = finder.find("Send")
            ?: finder.find("send")
            ?: return fail("Can't find Send button")
        sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Snapshot messages after sending so monitor doesn't re-read our own message
        delay(1000)
        lastKnownMessages = getCurrentMessages()

        tts("Message sent")
        Log.i(TAG, "✅ Message sent in active chat")
        return true
    }

    /**
     * Read unread messages when first opening a chat.
     * Returns the list of unread messages found.
     */
    private suspend fun readUnreadMessages(): List<String> {
        delay(1500) // Let chat content fully load (WhatsApp can be slow)
        val messages = getCurrentMessages()

        if (messages.isEmpty()) {
            Log.i(TAG, "No messages found to read")
            lastKnownMessages = messages
            return emptyList()
        }

        // Read the last few messages as "unread" context
        val unreadCount = minOf(messages.size, 3) // Read up to 3 most recent
        val unread = messages.takeLast(unreadCount)

        for (msg in unread) {
            tts(msg)
            delay(300) // Gap between messages for clarity
        }

        Log.i(TAG, "Read $unreadCount unread messages")
        lastKnownMessages = messages
        return unread
    }

    /**
     * Read the last N messages from the current chat screen.
     */
    suspend fun readLastMessages(count: Int = 5): List<String> {
        val messages = getCurrentMessages()
        val result = messages.takeLast(count)
        Log.i(TAG, "Read ${result.size} messages")
        return result
    }

    /**
     * Get current messages on screen by parsing contentDescription.
     * Returns list of parsed message strings.
     *
     * Handles multiple WhatsApp contentDescription formats:
     *   Format 1: "Name, Time, Message text, Status"     (4 parts)
     *   Format 2: "Name, Time, Message text"              (3 parts)
     *   Format 3: "Name: Message text"                    (colon-separated)
     *   Format 4: Long text that looks like a chat message (fallback)
     *
     * Also uses text-based fallback: reads visible TextView content
     * inside message list items.
     */
    private fun getCurrentMessages(): List<String> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val all = flatten(root)
        val messages = mutableListOf<String>()
        val seenTexts = mutableSetOf<String>() // Avoid duplicates

        // Debug: log all contentDescriptions to help diagnose format
        var descCount = 0
        for (node in all) {
            val desc = node.contentDescription?.toString() ?: continue
            if (desc.length > 5) {
                descCount++
                if (descCount <= 15) { // Log first 15 for debugging
                    Log.d(TAG, "ContentDesc[$descCount]: '$desc'")
                }
            }
        }
        Log.i(TAG, "Total contentDescriptions found: $descCount")

        for (node in all) {
            val desc = node.contentDescription?.toString() ?: continue

            // Skip very short or irrelevant descriptions
            if (desc.length < 3) continue
            // Skip known UI element descriptions
            if (desc in listOf("Search", "Back", "Send", "Voice call", "Video call",
                    "Audio call", "Attach", "Camera", "Emoji", "More options",
                    "New chat", "Status", "Calls", "Communities", "Chats")) continue

            val parsed = parseMessageFromDesc(desc)
            if (parsed != null && parsed !in seenTexts) {
                seenTexts.add(parsed)
                messages.add(parsed)
            }
        }

        // Fallback: if contentDescription parsing found nothing,
        // try reading text content from message-like nodes
        if (messages.isEmpty()) {
            Log.i(TAG, "ContentDescription parsing found 0 messages, trying text fallback")
            for (node in all) {
                val text = node.text?.toString() ?: continue
                if (text.length < 2 || text.length > 500) continue
                // Skip if it's an input field
                if (node.isEditable) continue
                // Skip timestamps (very short, numbers + colons)
                if (text.matches(Regex("\\d{1,2}:\\d{2}\\s*[APap][Mm]?")) ) continue
                // Skip if it's a button/label
                if (node.isClickable && text.length < 15) continue

                val className = node.className?.toString() ?: ""
                if (className.contains("TextView") || className.contains("text", true)) {
                    if (text !in seenTexts && text.length > 1) {
                        seenTexts.add(text)
                        messages.add(text)
                    }
                }
            }
            Log.i(TAG, "Text fallback found ${messages.size} messages")
        }

        Log.i(TAG, "getCurrentMessages: ${messages.size} total messages found")
        return messages
    }

    /**
     * Parse a message from a WhatsApp contentDescription string.
     * Returns a human-readable string or null if not a message.
     */
    private fun parseMessageFromDesc(desc: String): String? {
        // Strategy 1: "Name, Time, Message text, Status" or "Name, Time, Message text"
        val commaParts = desc.split(",").map { it.trim() }
        if (commaParts.size >= 3) {
            val sender = commaParts[0]
            // Check if second part looks like a time (contains : or am/pm)
            val possibleTime = commaParts[1]
            if (possibleTime.contains(":") || possibleTime.contains("am", true) ||
                possibleTime.contains("pm", true) || possibleTime.matches(Regex(".*\\d+.*"))) {
                val msg = commaParts.drop(2).joinToString(", ")
                    .replace(Regex("\\s*(Read|Delivered|Sent|Double check|Blue check|Pending|Seen)\\s*$"), "")
                    .trim()
                if (msg.isNotBlank() && msg.length > 1) {
                    return "$sender said: $msg"
                }
            }
        }

        // Strategy 2: "Name: Message text"  (some WhatsApp versions use colon)
        val colonIdx = desc.indexOf(":")
        if (colonIdx in 1..30) {
            val sender = desc.substring(0, colonIdx).trim()
            val msg = desc.substring(colonIdx + 1).trim()
            // Verify sender looks like a name (not a timestamp like "2:30")
            if (!sender.matches(Regex("\\d+")) && msg.length > 1 && sender.length > 1) {
                return "$sender said: $msg"
            }
        }

        // Strategy 3: Long text (>10 chars) with no special format — might be a message itself
        if (desc.length > 10 && !desc.contains("button", true) &&
            !desc.contains("tab ", true) && !desc.contains("image", true)) {
            // Check if it looks like a chat bubble description
            if (commaParts.size >= 2 && desc.length > 15) {
                return desc // Return raw description
            }
        }

        return null
    }

    // ==================== REPLY MONITORING ====================

    /**
     * Start background reply monitoring.
     * Polls every 2.5 seconds for new messages in the active chat.
     * Only reads NEW messages (diff from last snapshot).
     */
    fun startReplyMonitor() {
        if (replyMonitorJob?.isActive == true) {
            Log.d(TAG, "Reply monitor already running")
            return
        }

        Log.i(TAG, "Starting reply monitor for $activeChatContact")

        replyMonitorJob = monitorScope.launch {
            while (isActive && isLiveChat) {
                delay(REPLY_POLL_INTERVAL_MS)

                try {
                    // Check if WhatsApp is still in foreground
                    val currentPkg = service.rootInActiveWindow?.packageName?.toString()
                    if (currentPkg != WHATSAPP_PACKAGE && currentPkg != WHATSAPP_BUSINESS_PACKAGE) {
                        Log.i(TAG, "WhatsApp no longer in foreground — stopping monitor")
                        stopReplyMonitor()
                        break
                    }

                    // Get current messages
                    val currentMessages = getCurrentMessages()

                    // Check for new messages (diff)
                    if (currentMessages.size > lastKnownMessages.size) {
                        val newMessages = currentMessages.drop(lastKnownMessages.size)
                        for (msg in newMessages) {
                            // Don't read back our own sent messages
                            val contactName = activeChatContact ?: ""
                            if (msg.startsWith("You said:", ignoreCase = true)) continue

                            Log.i(TAG, "New reply detected: $msg")
                            tts(msg)
                        }
                        lastKnownMessages = currentMessages
                    } else if (currentMessages != lastKnownMessages && currentMessages.isNotEmpty()) {
                        // Same count but different content — messages may have shifted
                        // Only announce if the last message is different
                        val lastKnown = lastKnownMessages.lastOrNull()
                        val lastCurrent = currentMessages.lastOrNull()
                        if (lastKnown != lastCurrent && lastCurrent != null) {
                            if (!lastCurrent.startsWith("You said:", ignoreCase = true)) {
                                Log.i(TAG, "Changed last message: $lastCurrent")
                                tts(lastCurrent)
                            }
                        }
                        lastKnownMessages = currentMessages
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reply monitor error: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop reply monitoring and clear the live chat session.
     */
    fun stopReplyMonitor() {
        replyMonitorJob?.cancel()
        replyMonitorJob = null
        activeChatContact = null
        isLiveChat = false
        lastKnownMessages = emptyList()
        Log.i(TAG, "Reply monitor stopped — live chat session ended")
    }

    // ==================== CALLING ====================

    /**
     * Call a contact on WhatsApp.
     * Opens chat first, then clicks the voice call button.
     */
    suspend fun callContact(contact: String): Boolean {
        Log.i(TAG, "Calling $contact on WhatsApp")

        // Open the chat
        if (!openChat(contact)) return false

        // Find and click voice/audio call button
        delay(500)
        val callBtn = finder.find("Voice call")
            ?: finder.find("Audio call")
            ?: finder.find("Video call") // fallback
            ?: return fail("Can't find call button")
        callBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Stop monitoring during call
        stopReplyMonitor()

        tts("Calling $contact on WhatsApp")
        Log.i(TAG, "✅ Calling $contact")
        return true
    }

    // ==================== VIDEO CALLING ====================

    /**
     * Video call a contact on WhatsApp.
     * Opens chat first, then clicks the video call button.
     */
    suspend fun videoCallContact(contact: String): Boolean {
        Log.i(TAG, "Video calling $contact on WhatsApp")

        // Open the chat
        if (!openChat(contact)) return false

        // Find and click video call button (prioritize video over voice)
        delay(500)
        val callBtn = finder.find("Video call")
            ?: finder.find("video call")
            ?: return fail("Can't find video call button")
        callBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Stop monitoring during call
        stopReplyMonitor()

        tts("Video calling $contact on WhatsApp")
        Log.i(TAG, "✅ Video calling $contact")
        return true
    }

    // ==================== STATUS / UPDATES ====================

    /**
     * View WhatsApp status updates.
     * Launches WhatsApp → clicks "Updates" tab → reads who posted status.
     *
     * WhatsApp renamed "Status" to "Updates" in recent versions.
     * We try both labels for compatibility.
     */
    suspend fun viewStatus(): Boolean {
        Log.i(TAG, "Viewing WhatsApp updates/status")

        // Step 1: Launch WhatsApp
        if (!launchWhatsApp()) return false

        // Step 2: Click Updates/Status tab
        delay(800)
        val updatesTab = finder.find("Updates")
            ?: finder.find("Status")
            ?: finder.find("updates")
            ?: finder.find("status")
            ?: return fail("Can't find Updates tab")
        updatesTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Step 3: Wait for updates screen to load
        delay(2000)

        // Step 4: Read status/update descriptions
        val root = service.rootInActiveWindow ?: return fail("Can't read screen")
        val all = flatten(root)
        val statusEntries = mutableListOf<String>()
        val seenNames = mutableSetOf<String>()

        for (node in all) {
            val desc = node.contentDescription?.toString() ?: continue

            // Skip UI elements
            if (desc.length < 5) continue
            if (desc.lowercase() in listOf("updates", "status", "search", "back",
                    "camera", "channels", "more options", "new status",
                    "my status", "pencil")) continue

            // Look for status entries — typically "Name, time" format
            val commaParts = desc.split(",").map { it.trim() }
            if (commaParts.size >= 2) {
                val name = commaParts[0]
                // Skip if already seen this name or it's too short
                if (name.length < 2 || name in seenNames) continue
                // Skip if first part is purely numeric (timestamps etc.)
                if (name.matches(Regex("\\d+"))) continue
                seenNames.add(name)
                statusEntries.add(name)
            }
        }

        // Also try reading text nodes for status names
        if (statusEntries.isEmpty()) {
            Log.i(TAG, "ContentDescription parsing found 0 updates, trying text fallback")
            for (node in all) {
                val text = node.text?.toString() ?: continue
                if (text.length < 2 || text.length > 50) continue
                if (node.isEditable) continue
                // Skip timestamps, tabs, and UI text
                if (text.matches(Regex("\\d{1,2}:\\d{2}\\s*[APap][Mm]?"))) continue
                if (text.lowercase() in listOf("updates", "status", "channels",
                        "chats", "calls", "communities", "my status")) continue
                val className = node.className?.toString() ?: ""
                if (className.contains("TextView") && node.isClickable) {
                    if (text !in seenNames) {
                        seenNames.add(text)
                        statusEntries.add(text)
                    }
                }
            }
        }

        // Step 5: Announce results
        if (statusEntries.isEmpty()) {
            tts("No new updates found")
            Log.i(TAG, "No status updates found")
        } else {
            tts("${statusEntries.size} updates found")
            delay(300)
            for ((i, name) in statusEntries.withIndex()) {
                tts("${i + 1}. $name")
                delay(200)
            }
        }

        Log.i(TAG, "✅ Status view complete: ${statusEntries.size} entries")
        return true
    }

    // ==================== INTERNAL HELPERS ====================

    /**
     * Launch WhatsApp (smart — skips if already in foreground).
     * Tries regular WhatsApp first, then Business.
     */
    private suspend fun launchWhatsApp(): Boolean {
        // Check if WhatsApp is already in foreground
        val currentPkg = service.rootInActiveWindow?.packageName?.toString()
        if (currentPkg == WHATSAPP_PACKAGE || currentPkg == WHATSAPP_BUSINESS_PACKAGE) {
            Log.i(TAG, "WhatsApp already open ($currentPkg) — skipping launch")
            return true
        }

        // Try regular WhatsApp first
        var intent = service.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)
        var pkg = WHATSAPP_PACKAGE

        // Try WhatsApp Business if regular not installed
        if (intent == null) {
            intent = service.packageManager.getLaunchIntentForPackage(WHATSAPP_BUSINESS_PACKAGE)
            pkg = WHATSAPP_BUSINESS_PACKAGE
        }

        if (intent == null) return fail("WhatsApp not installed")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        if (!waiter.waitForApp(pkg)) return fail("WhatsApp didn't open")

        Log.i(TAG, "WhatsApp launched ($pkg)")
        return true
    }

    private fun fail(msg: String): Boolean {
        Log.w(TAG, "❌ $msg")
        tts(msg)
        return false
    }

    /** Quick search for a node whose text or contentDescription contains the target. */
    private fun findNodeByText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        for (node in flatten(root)) {
            if (node.text?.toString()?.contains(target, true) == true) return node
            if (node.contentDescription?.toString()?.contains(target, true) == true) return node
        }
        return null
    }

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

    /**
     * Cleanup — call when service is destroyed.
     */
    fun destroy() {
        stopReplyMonitor()
        monitorScope.cancel()
    }
}
