package com.autoapk.automation.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.LinkedList
import java.util.Queue

/**
 * WhatsApp Automation — Full Chat Automation
 *
 * Provides:
 *   - sendMessage(contact, message): Open chat → type → send
 *   - openChat(contact): Open specific contact's chat
 *   - readLastMessages(count): Read recent messages via contentDescription parsing
 *   - callContact(contact): Open chat → click voice call
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
    }

    /**
     * Open a specific contact's chat in WhatsApp.
     * Steps: Launch WA → Search → Type name → Click result
     */
    suspend fun openChat(contact: String): Boolean {
        Log.i(TAG, "Opening chat with: $contact")

        // Step 1: Launch WhatsApp
        if (!launchWhatsApp()) return false

        // Step 2: Click Search button
        delay(500)
        val searchBtn = finder.find("Search") ?: run {
            return fail("Can't find search button")
        }
        searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!waiter.waitForEditableField()) return fail("Search field didn't appear")

        // Step 3: Type contact name
        val searchField = waiter.findEditable(service.rootInActiveWindow)
            ?: return fail("Can't find text input")
        searchField.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contact)
            }
        )
        delay(1500) // Wait for search results (needs network for some contacts)

        // Step 4: Click the matching contact
        val contactNode = finder.find(contact) ?: return fail("Contact $contact not found")
        contactNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!waiter.waitForEditableField(4000)) return fail("Chat didn't open")

        tts("Opened $contact's chat")
        Log.i(TAG, "✅ Chat opened with $contact")
        return true
    }

    /**
     * Send a message to a contact.
     * Opens chat first, then types and sends.
     */
    suspend fun sendMessage(contact: String, message: String): Boolean {
        Log.i(TAG, "Sending message to $contact: '$message'")

        // Open the chat first
        if (!openChat(contact)) return false

        // Step 5: Find and type in the message field
        val msgField = waiter.findEditable(service.rootInActiveWindow)
            ?: return fail("Can't find message box")
        msgField.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(300)
        msgField.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
        )
        delay(500)

        // Step 6: Click Send button
        val sendBtn = finder.find("Send") ?: return fail("Can't find Send button")
        sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        tts("Message sent to $contact")
        Log.i(TAG, "✅ Message sent to $contact")
        return true
    }

    /**
     * Read the last N messages from the current chat screen.
     *
     * WhatsApp stores message info in contentDescription with format:
     * "Name, Time, Message text, Read/Delivered/Sent"
     */
    suspend fun readLastMessages(count: Int = 5): List<String> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val all = flatten(root)
        val messages = mutableListOf<String>()

        for (node in all) {
            val desc = node.contentDescription?.toString() ?: continue
            // WhatsApp contentDescription format: "Name, Time, Message text"
            val parts = desc.split(",").map { it.trim() }
            if (parts.size >= 3) {
                val sender = parts[0]
                val msg = parts.drop(2).joinToString(", ")
                    .replace(Regex("(Read|Delivered|Sent|Double check|Blue check|Pending).*$"), "")
                    .trim()
                if (msg.isNotBlank() && msg.length > 1) {
                    messages.add("$sender said: $msg")
                }
            }
        }

        val result = messages.takeLast(count)
        Log.i(TAG, "Read ${result.size} messages")
        return result
    }

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

        tts("Calling $contact on WhatsApp")
        Log.i(TAG, "✅ Calling $contact")
        return true
    }

    /**
     * Launch WhatsApp (tries regular first, then Business).
     */
    private suspend fun launchWhatsApp(): Boolean {
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
}
