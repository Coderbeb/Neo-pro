package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

/**
 * Command Router — Central Hub for App-Specific Automation
 *
 * Routes app-specific voice commands to the correct automation class:
 *   - WhatsApp commands → WhatsAppAutomation
 *   - YouTube commands → YouTubeAutomation
 *   - Instagram commands → InstagramAutomation
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

    /**
     * Try to route a command to an app-specific handler.
     * Returns true if handled, false if not matched (fallback to existing).
     */
    fun route(command: String): Boolean {
        val cmd = command.lowercase().trim()
        Log.i(TAG, "Routing: '$cmd'")

        // ═══════════════════ WHATSAPP ═══════════════════

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

        // "read whatsapp messages" or "read messages" or "read his messages"
        if (cmd.matches(Regex("read\\s+(whatsapp\\s+)?messages?.*")) || cmd == "read messages") {
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

        // "call [contact] on whatsapp" or "whatsapp call [contact]"
        val waCallMatch = Regex("(?:whatsapp\\s+)?call\\s+(.+?)\\s+(?:on\\s+whatsapp|whatsapp)", RegexOption.IGNORE_CASE).find(cmd)
            ?: Regex("whatsapp\\s+call\\s+(.+)", RegexOption.IGNORE_CASE).find(cmd)
        if (waCallMatch != null) {
            val contact = waCallMatch.groupValues[1].trim()
            scope.launch { whatsApp.callContact(contact) }
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
            if (query.length > 3 && !query.matches(Regex("video|next|previous|song"))) {
                // Check if YouTube is already open
                val currentPkg = service.rootInActiveWindow?.packageName?.toString()
                if (currentPkg == "com.google.android.youtube" || currentPkg == "app.rvx.android.youtube") {
                    // YouTube is open — search within it
                    scope.launch { youtube.search(query) }
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

        // "play 3rd" or "play 2nd song" — with YouTube open
        val playNthMatch = Regex("play\\s+(\\d+)(?:st|nd|rd|th)?(?:\\s+(?:song|video))?", RegexOption.IGNORE_CASE).find(cmd)
        if (playNthMatch != null) {
            val currentPkg = service.rootInActiveWindow?.packageName?.toString()
            if (currentPkg == "com.google.android.youtube" || currentPkg == "app.rvx.android.youtube") {
                val num = playNthMatch.groupValues[1].toInt()
                scope.launch { youtube.playVideoAtIndex(num) }
                return true
            }
        }

        // YouTube player controls (only when YouTube is open)
        val currentPkg = service.rootInActiveWindow?.packageName?.toString()
        if (currentPkg == "com.google.android.youtube" || currentPkg == "app.rvx.android.youtube") {
            when {
                cmd in listOf("pause", "pause video", "ruko", "stop video") -> {
                    scope.launch { youtube.controlPlayer("pause") }
                    return true
                }
                cmd in listOf("resume", "play video", "chalao") -> {
                    scope.launch { youtube.controlPlayer("play") }
                    return true
                }
                cmd in listOf("next video", "next song", "agla", "agla song", "agla video") -> {
                    scope.launch { youtube.controlPlayer("next") }
                    return true
                }
                cmd in listOf("previous video", "previous song", "pichla", "pichla song") -> {
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
                cmd in listOf("next reel", "next", "agla", "swipe up") -> {
                    instagram.nextReel()
                    return true
                }
                cmd in listOf("previous reel", "previous", "pichla", "swipe down") -> {
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
        scope.cancel()
    }
}
