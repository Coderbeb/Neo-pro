package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

/**
 * Command Router — Central Hub for App-Specific Automation
 *
 * Routes app-specific voice commands to the correct automation class:
 *   - WhatsApp commands → WhatsAppAutomation (with live chat support)
 *   - YouTube commands → YouTubeAutomation
 *   - Instagram commands → InstagramAutomation
 *   - Notification commands → NeoNotificationListener ("read it", "reply")
 *   - Everything else → returns false (handled by existing CommandProcessor)
 *
 * Uses regex-based pattern matching for high-accuracy command routing.
 * All operations run in coroutine scope on the main thread.
 */
class CommandRouter(
    private val service: AccessibilityService,
    private val tts: (String) -> Unit
) {

    companion object {
        private const val TAG = "Neo_Router"
    }

    private val finder = AdaptiveNodeFinder(service)
    private val waiter = ScreenWaitSystem(service)
    private val whatsApp = WhatsAppAutomation(service, finder, waiter, tts)
    private val youtube = YouTubeAutomation(service, finder, waiter, tts)
    private val instagram = InstagramAutomation(service, finder, waiter, tts)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        // Connect WhatsApp live chat status to notification listener
        NeoNotificationListener.isWhatsAppLiveChatActive = { whatsApp.isInLiveChat }
    }

    /**
     * Try to route a command to an app-specific handler.
     * Returns true if handled, false if not matched (fallback to existing).
     */
    fun route(command: String): Boolean {
        val cmd = command.lowercase().trim()
        Log.i(TAG, "Routing: '$cmd'")

        // ═══════════════════ SYSTEM COMMANDS (intercept before OPEN_APP) ═══════════════════

        // "open recents" / "recent apps" / "recents"
        if (cmd in listOf("open recents", "open recent", "recent apps", "recents", "recent",
                          "show recents", "recent wale", "pichle apps", "multitask")) {
            val svc = service as? AutomationAccessibilityService
            if (svc != null) {
                svc.openRecents()
                tts("Opening recent apps")
            }
            return true
        }

        // "open quick settings" / "quick settings"
        if (cmd in listOf("open quick settings", "quick settings", "open quick setting",
                          "quick setting", "control panel", "control center")) {
            val svc = service as? AutomationAccessibilityService
            if (svc != null) {
                svc.openQuickSettings()
                tts("Opening quick settings")
            }
            return true
        }

        // ═══════════════════ NOTIFICATION COMMANDS (global) ═══════════════════

        // "read it" / "read that" / "read message" / "read notification" / "padh" / "padho"
        if (cmd.matches(Regex("read\\s+(?:it|that|this|message|notification)|padh(?:o)?"))) {
            NeoNotificationListener.readLastNotification(tts)
            return true
        }

        // "reply [message]" / "reply him/her [message]" — reply to last notified contact
        val replyMatch = Regex("reply\\s+(?:him|her|them|usse|usko|use\\s+)?(.+)", RegexOption.IGNORE_CASE).find(cmd)
        if (replyMatch != null) {
            val message = replyMatch.groupValues[1].trim()
            val sender = NeoNotificationListener.getLastSender()
            val pkg = NeoNotificationListener.getLastPackage()
            if (sender != null && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")) {
                scope.launch { whatsApp.sendMessage(sender, message) }
                return true
            } else if (sender != null) {
                tts("Can only reply to WhatsApp messages right now")
                return true
            } else {
                tts("No recent notification to reply to")
                return true
            }
        }

        // ═══════════════════ WHATSAPP ═══════════════════

        // "close whatsapp chat" / "exit whatsapp chat" — end live chat session
        // Requires "whatsapp" keyword to avoid conflicting with other apps
        if (cmd in listOf("close whatsapp chat", "exit whatsapp chat", "close whatsapp",
                          "stop whatsapp chat", "end whatsapp chat",
                          "whatsapp chat band karo", "band karo whatsapp chat",
                          "whatsapp band karo", "whatsapp chat close")) {
            if (whatsApp.isInLiveChat) {
                val contact = whatsApp.getActiveChatContact() ?: "contact"
                whatsApp.stopReplyMonitor()
                tts("Closed $contact's chat")
            } else {
                tts("No active WhatsApp chat")
            }
            return true
        }

        // "send him/her [message]" — contextual send in active chat OR via last WhatsApp contact
        val contextSendMatch = Regex("send\\s+(?:him|her|them|usse|usko|use)\\s+(.+)", RegexOption.IGNORE_CASE).find(cmd)
        if (contextSendMatch != null) {
            val message = contextSendMatch.groupValues[1].trim()
            if (whatsApp.isInLiveChat) {
                scope.launch { whatsApp.sendInActiveChat(message) }
                return true
            }
            // Not in live chat — try to send to last WhatsApp contact from notification
            val lastSender = NeoNotificationListener.getLastSender()
            val lastPkg = NeoNotificationListener.getLastPackage()
            if (lastSender != null && (lastPkg == "com.whatsapp" || lastPkg == "com.whatsapp.w4b")) {
                scope.launch { whatsApp.sendMessage(lastSender, message) }
                return true
            }

            // "send [message]" — when in active WhatsApp chat, treat as sending message
            val simpleSendMatch = Regex("send\\s+(.+)", RegexOption.IGNORE_CASE).find(cmd)
            if (simpleSendMatch != null) {
                val message = simpleSendMatch.groupValues[1].trim()
                // Avoid consuming "send whatsapp to X" or "send message to X" patterns
                if (!message.startsWith("whatsapp", true) && !message.startsWith("message to", true)) {
                    scope.launch { whatsApp.sendInActiveChat(message) }
                    return true
                }
            }
        }

        // REMOVED: "open chat with X" without "whatsapp" keyword
        // Use "open whatsapp chat with X" below instead

        // "open whatsapp chat with [contact]"
        val openChatMatch = Regex("open\\s+whatsapp\\s+(?:chat|message)\\s+(?:with|to)\\s+(.+)", RegexOption.IGNORE_CASE).find(cmd)
        if (openChatMatch != null) {
            val contact = openChatMatch.groupValues[1].trim()
            scope.launch { whatsApp.openChat(contact) }
            return true
        }

        // "send whatsapp to [contact] saying [message]" or "send message to [contact] saying [message]"
        val sendMsgMatch = Regex("send\\s+(?:whatsapp|message)\\s+to\\s+(.+?)\\s+(?:saying|that|message)\\s+(.+)", RegexOption.IGNORE_CASE).find(cmd)
        if (sendMsgMatch != null) {
            val contact = sendMsgMatch.groupValues[1].trim()
            val message = sendMsgMatch.groupValues[2].trim()
            scope.launch { whatsApp.sendMessage(contact, message) }
            return true
        }

        // "whatsapp [contact] [message]" — simplified send
        val quickSendMatch = Regex("whatsapp\\s+(.+?)\\s+(?:saying|bolo|bol|message)\\s+(.+)", RegexOption.IGNORE_CASE).find(cmd)
        if (quickSendMatch != null) {
            val contact = quickSendMatch.groupValues[1].trim()
            val message = quickSendMatch.groupValues[2].trim()
            scope.launch { whatsApp.sendMessage(contact, message) }
            return true
        }

        // "read whatsapp messages" — requires "whatsapp" keyword
        if (cmd.matches(Regex("read\\s+whatsapp\\s+messages?.*"))) {
            scope.launch {
                val messages = whatsApp.readLastMessages()
                if (messages.isEmpty()) {
                    tts("No messages found")
                } else {
                    messages.forEach { tts(it) }
                }
            }
            return true
        }

        // "video call [contact] on whatsapp" or "whatsapp video call [contact]"
        // Must come BEFORE voice call patterns since "video call" is more specific
        val waVideoCallMatch = Regex("(?:whatsapp\\s+)?video\\s+call\\s+(.+?)\\s+(?:on\\s+whatsapp|whatsapp)", RegexOption.IGNORE_CASE).find(cmd)
            ?: Regex("whatsapp\\s+video\\s+call\\s+(.+)", RegexOption.IGNORE_CASE).find(cmd)
            ?: Regex("video\\s+call\\s+(.+?)\\s+(?:on\\s+whatsapp|whatsapp)", RegexOption.IGNORE_CASE).find(cmd)
        if (waVideoCallMatch != null) {
            val contact = waVideoCallMatch.groupValues[1].trim()
            scope.launch { whatsApp.videoCallContact(contact) }
            return true
        }

        // "call [contact] on whatsapp" or "whatsapp call [contact]" (voice call)
        val waCallMatch = Regex("(?:whatsapp\\s+)?call\\s+(.+?)\\s+(?:on\\s+whatsapp|whatsapp)", RegexOption.IGNORE_CASE).find(cmd)
            ?: Regex("whatsapp\\s+call\\s+(.+)", RegexOption.IGNORE_CASE).find(cmd)
        if (waCallMatch != null) {
            val contact = waCallMatch.groupValues[1].trim()
            scope.launch { whatsApp.callContact(contact) }
            return true
        }

        // "view whatsapp updates" / "show whatsapp status" / "whatsapp updates dikhao"
        if (cmd.matches(Regex("(?:view|show|check|see|open|dekho|dikhao)\\s+whatsapp\\s+(?:updates?|status).*")) ||
            cmd.matches(Regex("whatsapp\\s+(?:updates?|status)\\s*(?:dikhao|dekho|batao|show|view)?.*"))) {
            scope.launch { whatsApp.viewStatus() }
            return true
        }

        // ═══════════════════ YOUTUBE ═══════════════════

        // "search youtube for [query]" or "search [query] on youtube"
        val ytSearchMatch = Regex("search\\s+(?:youtube\\s+(?:for\\s+)?|(.+?)\\s+on\\s+youtube)", RegexOption.IGNORE_CASE).find(cmd)
        if (ytSearchMatch != null) {
            val query = cmd.replace(Regex("search\\s+(?:youtube\\s+(?:for\\s+)?)?"), "")
                .replace(Regex("\\s+on\\s+youtube"), "")
                .trim()
            if (query.isNotBlank()) {
                scope.launch { youtube.search(query) }
                return true
            }
        }

        // "play [query]" — but only if it looks like a YouTube search, not "play 3rd"
        if (cmd.startsWith("play ") && !cmd.matches(Regex("play\\s+\\d+(st|nd|rd|th)?.*"))) {
            val query = cmd.removePrefix("play ").trim()
            if (query.length > 1 && !query.matches(Regex("video|next|previous|song"))) {
                // Check if YouTube is already open
                val currentPkg = service.rootInActiveWindow?.packageName?.toString()
                if (currentPkg == "com.google.android.youtube" || currentPkg == "app.rvx.android.youtube") {
                    // YouTube is open — try to click a matching video on screen first
                    scope.launch {
                        val played = youtube.playVideoByName(query)
                        if (!played) {
                            // No match on screen — search instead
                            youtube.search(query)
                        }
                    }
                } else {
                    // YouTube not open — open and search
                    scope.launch { youtube.searchAndPlay(query) }
                }
                return true
            }
        }

        // "list videos" or "explain screen" or "what videos" — on YouTube
        if (cmd in listOf("list videos", "what videos", "explain screen", "what's on screen")) {
            val currentPkg = service.rootInActiveWindow?.packageName?.toString()
            if (currentPkg == "com.google.android.youtube" || currentPkg == "app.rvx.android.youtube") {
                scope.launch {
                    val videos = youtube.listVideos()
                    if (videos.isEmpty()) {
                        tts("No videos found on screen")
                    } else {
                        videos.forEachIndexed { i, title ->
                            tts("Number ${i + 1}: $title")
                        }
                    }
                }
                return true
            }
            // If on Instagram, use Instagram's describe
            if (currentPkg == "com.instagram.android") {
                scope.launch {
                    val desc = instagram.describeCurrentContent()
                    tts(desc)
                }
                return true
            }
        }

        // "play 3rd" or "play second" or "play third song" — with YouTube open
        val playNthMatch = Regex("play\\s+(\\d+)(?:st|nd|rd|th)?(?:\\s+(?:song|video))?", RegexOption.IGNORE_CASE).find(cmd)
        // Also match word-based numbers: "play first", "play second", "play third", etc.
        val wordNumMap = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4,
            "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
            "pehla" to 1, "dusra" to 2, "teesra" to 3, "chautha" to 4, "panchwa" to 5)
        val wordNthMatch = Regex("play\\s+(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|pehla|dusra|teesra|chautha|panchwa)(?:\\s+(?:song|video|one))?", RegexOption.IGNORE_CASE).find(cmd)
        
        val nthNum = if (playNthMatch != null) {
            playNthMatch.groupValues[1].toIntOrNull()
        } else if (wordNthMatch != null) {
            wordNumMap[wordNthMatch.groupValues[1].lowercase()]
        } else null
        
        if (nthNum != null) {
            val currentPkg = service.rootInActiveWindow?.packageName?.toString()
            if (currentPkg == "com.google.android.youtube" || currentPkg == "app.rvx.android.youtube") {
                scope.launch { youtube.playVideoAtIndex(nthNum) }
                return true
            }
        }

        // YouTube player controls (only when YouTube is open)
        val currentPkg = service.rootInActiveWindow?.packageName?.toString()
        if (currentPkg == "com.google.android.youtube" || currentPkg == "app.rvx.android.youtube") {
            when {
                cmd in listOf("pause", "pause video", "ruko", "stop video", "stop") -> {
                    scope.launch { youtube.controlPlayer("pause") }
                    return true
                }
                cmd in listOf("resume", "play", "play video", "chalao") -> {
                    scope.launch { youtube.controlPlayer("play") }
                    return true
                }
                cmd in listOf("next", "next video", "next song", "agla", "agla song", "agla video") -> {
                    scope.launch { youtube.controlPlayer("next") }
                    return true
                }
                cmd in listOf("previous", "previous video", "previous song", "pichla", "pichla song") -> {
                    scope.launch { youtube.controlPlayer("previous") }
                    return true
                }
                cmd in listOf("fullscreen", "full screen", "expand") -> {
                    scope.launch { youtube.controlPlayer("fullscreen") }
                    return true
                }
            }
        }

        // ═══════════════════ INSTAGRAM ═══════════════════

        // "open reels" or "go to reels"
        if (cmd in listOf("open reels", "go to reels", "reels kholo", "reels")) {
            scope.launch { instagram.openReels() }
            return true
        }

        // "next reel" — only when Instagram is open
        if (currentPkg == "com.instagram.android") {
            when {
                cmd in listOf("next reel", "next post", "agla reel", "agla", "swipe up") -> {
                    instagram.nextReel()
                    return true
                }
                cmd in listOf("previous reel", "previous post", "pichla reel", "pichla", "swipe down") -> {
                    instagram.previousReel()
                    return true
                }
                cmd in listOf("like", "like this", "pasand", "heart") -> {
                    scope.launch { instagram.likeCurrentPost() }
                    return true
                }
                cmd in listOf("follow", "follow user", "follow karo") -> {
                    scope.launch { instagram.followUser() }
                    return true
                }
                cmd in listOf("scroll down", "neeche") -> {
                    instagram.scrollDown()
                    return true
                }
                cmd in listOf("scroll up", "upar") -> {
                    instagram.scrollUp()
                    return true
                }
                cmd in listOf("describe", "what is this", "kya hai", "explain") -> {
                    scope.launch {
                        val desc = instagram.describeCurrentContent()
                        tts(desc)
                    }
                    return true
                }
            }
        }

        // "auto scroll" — works from any context
        if (cmd.startsWith("auto scroll") || cmd.startsWith("auto-scroll")) {
            val seconds = Regex("(\\d+)").find(cmd)?.value?.toIntOrNull() ?: 10
            instagram.startAutoScroll(seconds)
            return true
        }

        // "stop" or "stop scrolling" — stops auto-scroll if active
        if (cmd in listOf("stop", "stop scrolling", "ruko", "bas") && instagram.isAutoScrolling()) {
            instagram.stopAutoScroll()
            return true
        }

        // "open [username] profile on instagram"
        val igProfileMatch = Regex("(?:open\\s+)?(.+?)\\s+(?:profile|instagram)\\s*(?:on instagram|profile)?", RegexOption.IGNORE_CASE).find(cmd)
        if (igProfileMatch != null && cmd.contains("instagram", true)) {
            val username = igProfileMatch.groupValues[1].trim()
            scope.launch { instagram.openProfile(username) }
            return true
        }

        // ═══════════════════ NOT MATCHED ═══════════════════
        Log.d(TAG, "No app-specific route for: '$cmd'")
        return false
    }

    /**
     * Cleanup when service is destroyed.
     */
    fun destroy() {
        instagram.stopAutoScroll()
        whatsApp.destroy()
        NeoNotificationListener.isWhatsAppLiveChatActive = null
        scope.cancel()
    }
}
