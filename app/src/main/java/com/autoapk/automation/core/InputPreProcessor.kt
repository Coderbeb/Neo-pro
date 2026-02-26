package com.autoapk.automation.core

import android.util.Log

/**
 * Task 3.2: Input Pre-Processing Pipeline
 *
 * Before matching, process the input text:
 * Step 1: Lowercase everything
 * Step 2: Remove extra spaces
 * Step 3: Replace common speech-to-text errors
 * Step 4: Normalize Hindi/Hinglish
 * Step 5: Remove filler words
 * Step 6: Return cleaned string
 */
object InputPreProcessor {

    private const val TAG = "Neo_PreProc"

    /**
     * Step 3: Common speech-to-text error corrections.
     * "why fi" → "wifi", "blue tooth" → "bluetooth", etc.
     * "go to" stays as "go to" (important for CLICK_TARGET detection).
     */
    private val sttErrorCorrections = listOf(
        "why fi" to "wifi",
        "wi fi" to "wifi",
        "why fi" to "wifi",
        "blue tooth" to "bluetooth",
        "hot spot" to "hotspot",
        "flash light" to "flashlight",
        "home screen" to "homescreen",
        "air plane" to "airplane",
        "air plane mode" to "airplane mode",
        "aero plane" to "airplane",
        "flight mode" to "airplane mode",
        "do not disturb" to "dnd",
        "screen shot" to "screenshot",
        "whats app" to "whatsapp",
        "what's app" to "whatsapp",
        "you tube" to "youtube",
        "face book" to "facebook",
        "insta gram" to "instagram",
        "tele gram" to "telegram",
        "play store" to "playstore",
        "google chrome" to "chrome",
        "g mail" to "gmail",
        "google mail" to "gmail"
    )

    /**
     * Step 4: Hindi/Hinglish normalization.
     * "karo" = "kar do" = "kar" → normalized form
     * "chalu" = "shuru" = "on"
     * "band" = "bund" = "off"
     * "kholo" = "open karo" = "open"
     */
    private val hindiNormalizations = listOf(
        // "do it" variants → "karo"
        "kar do" to "karo",
        "kar de" to "karo",
        "kardo" to "karo",
        "kar dena" to "karo",
        "karke" to "karo",

        // "start/on" variants
        "chalu karo" to "on karo",
        "chalu kar" to "on karo",
        "shuru karo" to "on karo",
        "shuru kar" to "on karo",
        "chalu" to "on",
        "shuru" to "on",
        "laga do" to "on karo",
        "laga" to "on",
        "jala do" to "on karo",
        "jala" to "on",
        "jala de" to "on karo",

        // "stop/off" variants
        "band karo" to "off karo",
        "band kar" to "off karo",
        "bund karo" to "off karo",
        "bund kar" to "off karo",
        "band" to "off",
        "bund" to "off",
        "hata do" to "off karo",
        "hata" to "off",
        "bujha do" to "off karo",
        "bujha" to "off",
        "bujha de" to "off karo",

        // "open" variants
        "kholo" to "open",
        "khol do" to "open",
        "khol" to "open",
        "open karo" to "open",

        // "go" / navigation
        "ja" to "go",
        "jao" to "go",
        "pe ja" to "go",
        "pe jao" to "go",
        "me ja" to "go",
        "me jao" to "go",

        // "show" / "dikhao"
        "dikhao" to "show",
        "dikha do" to "show",
        "dikha" to "show",
        "batao" to "show",
        "bata do" to "show"
    )

    /**
     * Step 5: Filler words to remove.
     */
    private val fillerWords = setOf(
        "please", "can you", "just", "zara", "thoda", "kindly",
        "bhai", "yaar", "na", "hey", "ok", "okay",
        "karo na", "do na", "abhi", "jaldi", "quickly"
    )

    /**
     * Main pre-processing pipeline.
     * Takes raw input and returns cleaned, normalized string.
     */
    fun preProcess(raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw

        // Step 1: Lowercase everything
        text = text.lowercase().trim()

        // Step 2: Remove extra spaces
        text = text.replace(Regex("\\s+"), " ")

        // Step 3: Fix speech-to-text errors
        for ((error, correction) in sttErrorCorrections) {
            text = text.replace(error, correction)
        }

        // Step 4: Normalize Hindi/Hinglish (apply longer phrases first)
        for ((hindi, english) in hindiNormalizations) {
            text = text.replace(hindi, english)
        }

        // Step 5: Remove filler words
        for (filler in fillerWords) {
            text = text.replace(Regex("\\b${Regex.escape(filler)}\\b"), "")
        }

        // Final cleanup: remove extra spaces again after replacements
        text = text.replace(Regex("\\s+"), " ").trim()

        Log.d(TAG, "Pre-processed: '$raw' → '$text'")
        return text
    }
}
