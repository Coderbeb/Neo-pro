package com.autoapk.automation.core

/**
 * Translates Hindi and Hinglish commands to their English equivalents.
 *
 * This mapper works by replacing Hindi keywords/phrases with English ones
 * so that the existing CommandProcessor pattern matching works seamlessly.
 * Supports full Hindi (Devanagari script) and Hinglish (romanized Hindi).
 */
object HindiCommandMapper {

    /**
     * Ordered list of translation rules. Each pair is (Hindi/Hinglish pattern → English replacement).
     * Multi-word phrases come FIRST to prevent partial replacements.
     */
    private val translationRules: List<Pair<String, String>> = listOf(
        // === NAVIGATION (multi-word first) ===
        "घर जाओ" to "home",
        "होम जाओ" to "home",
        "ghar jao" to "home",
        "home jao" to "home",
        "पीछे जाओ" to "back",
        "peeche jao" to "back",
        "back jao" to "back",
        "हाल के ऐप्स" to "recent",
        "रीसेंट ऐप्स" to "recent",
        "recent apps" to "recent",
        "नोटिफिकेशन खोलो" to "notification",
        "notification kholo" to "notification",
        "स्क्रीन लॉक करो" to "lock",
        "फोन लॉक करो" to "lock",
        "screen lock karo" to "lock",
        "phone lock karo" to "lock",

        // === SCROLLING & SWIPING (multi-word) ===
        "नीचे स्क्रॉल करो" to "scroll down",
        "neeche scroll karo" to "scroll down",
        "ऊपर स्क्रॉल करो" to "scroll up",
        "upar scroll karo" to "scroll up",
        "बाएं स्वाइप करो" to "swipe left",
        "left swipe karo" to "swipe left",
        "दाएं स्वाइप करो" to "swipe right",
        "right swipe karo" to "swipe right",
        "अगला" to "next",
        "agla" to "next",
        "पिछला" to "previous",
        "pichla" to "previous",

        // === VOLUME (multi-word) ===
        "आवाज़ बढ़ाओ" to "volume up",
        "आवाज बढ़ाओ" to "volume up",
        "awaz badhao" to "volume up",
        "volume badhao" to "volume up",
        "आवाज़ कम करो" to "volume down",
        "आवाज कम करो" to "volume down",
        "awaz kam karo" to "volume down",
        "volume kam karo" to "volume down",
        "म्यूट करो" to "mute",
        "mute karo" to "mute",
        "अनम्यूट करो" to "unmute",
        "unmute karo" to "unmute",

        // === CONNECTIVITY (multi-word) ===
        "वाईफाई चालू करो" to "wifi on",
        "wifi chalu karo" to "wifi on",
        "wifi on karo" to "wifi on",
        "वाईफाई बंद करो" to "wifi off",
        "wifi band karo" to "wifi off",
        "wifi off karo" to "wifi off",
        "ब्लूटूथ चालू करो" to "bluetooth on",
        "bluetooth chalu karo" to "bluetooth on",
        "bluetooth on karo" to "bluetooth on",
        "ब्लूटूथ बंद करो" to "bluetooth off",
        "bluetooth band karo" to "bluetooth off",
        "bluetooth off karo" to "bluetooth off",

        // === FLASHLIGHT (multi-word) ===
        "टॉर्च चालू करो" to "flashlight on",
        "torch chalu karo" to "flashlight on",
        "torch on karo" to "flashlight on",
        "फ्लैश चालू करो" to "flash on",
        "flash chalu karo" to "flash on",
        "टॉर्च बंद करो" to "flashlight off",
        "torch band karo" to "flashlight off",
        "torch off karo" to "flashlight off",
        "फ्लैश बंद करो" to "flash off",
        "flash band karo" to "flash off",

        // === APP LAUNCHING ===
        // "X खोलो" → "open X" handled specially below
        // Hinglish: "X kholo" → "open X" handled specially below

        // === PHONE CALLS (multi-word) ===
        "फोन उठाओ" to "answer",
        "phone uthao" to "answer",
        "कॉल उठाओ" to "answer",
        "call uthao" to "answer",
        "फोन काटो" to "end call",
        "phone kato" to "end call",
        "कॉल काटो" to "end call",
        "call kato" to "end call",
        "कॉल खत्म करो" to "end call",
        "call khatam karo" to "end call",
        "स्पीकर लगाओ" to "speaker",
        "speaker lagao" to "speaker",

        // === SMS ===
        "मैसेज पढ़ो" to "read message",
        "message padho" to "read message",
        "एसएमएस पढ़ो" to "read sms",
        "sms padho" to "read sms",

        // === MEDIA (multi-word) ===
        "गाना बजाओ" to "play music",
        "म्यूजिक बजाओ" to "play music",
        "music bajao" to "play music",
        "gaana bajao" to "play music",
        "गाना रोको" to "pause",
        "music roko" to "pause",
        "रोको" to "pause",
        "roko" to "pause",
        "अगला गाना" to "next song",
        "agla gaana" to "next song",
        "next song" to "next song",

        // === SCREEN READING ===
        "स्क्रीन पढ़ो" to "read screen",
        "screen padho" to "read screen",
        "स्क्रीन पर क्या है" to "what's on screen",
        "screen par kya hai" to "what's on screen",

        // === BATTERY ===
        "बैटरी कितनी है" to "battery",
        "battery kitni hai" to "battery",
        "बैटरी" to "battery",

        // === SCREEN ROTATION (multi-word) ===
        "रोटेशन चालू करो" to "rotation on",
        "rotation chalu karo" to "rotation on",
        "rotation on karo" to "rotation on",
        "रोटेशन बंद करो" to "rotation off",
        "rotation band karo" to "rotation off",
        "rotation off karo" to "rotation off",
        "रोटेट करो" to "rotate",

        // === QUICK SETTINGS ===
        "क्विक सेटिंग" to "quick setting",
        "quick setting kholo" to "quick setting",
        "कंट्रोल पैनल" to "control panel",

        // === BRIGHTNESS (multi-word) ===
        "रोशनी बढ़ाओ" to "brightness increase",
        "brightness badhao" to "brightness increase",
        "रोशनी कम करो" to "brightness decrease",
        "brightness kam karo" to "brightness decrease",
        "रोशनी पूरी करो" to "brightness max",
        "brightness full karo" to "brightness max",
        "रोशनी कम करो" to "brightness dim",
        "रोशनी" to "brightness",

        // === SCREENSHOT ===
        "स्क्रीनशॉट लो" to "screenshot",
        "screenshot lo" to "screenshot",
        "स्क्रीनशॉट" to "screenshot",

        // === ALARM / TIMER (multi-word) ===
        "अलार्म लगाओ" to "set alarm",
        "alarm lagao" to "set alarm",
        "टाइमर लगाओ" to "set timer",
        "timer lagao" to "set timer",

        // === DO NOT DISTURB (multi-word) ===
        "डीएनडी चालू करो" to "dnd on",
        "dnd chalu karo" to "dnd on",
        "साइलेंट मोड चालू करो" to "silent mode on",
        "silent mode chalu karo" to "silent mode on",
        "डीएनडी बंद करो" to "dnd off",
        "dnd band karo" to "dnd off",
        "साइलेंट मोड बंद करो" to "silent mode off disable",
        "silent mode band karo" to "silent mode off disable",
        "परेशान मत करो" to "do not disturb",

        // === HOTSPOT ===
        "हॉटस्पॉट चालू करो" to "hotspot",
        "hotspot chalu karo" to "hotspot",
        "hotspot on karo" to "hotspot",
        "हॉटस्पॉट" to "hotspot",

        // === GOOGLE SEARCH (multi-word) ===
        "गूगल पर खोजो" to "search google",
        "google par khojo" to "search google",
        "सर्च करो" to "search",
        "search karo" to "search",

        // === YOUTUBE SEARCH ===
        "यूट्यूब पर खोजो" to "search youtube",
        "youtube par khojo" to "search youtube",
        "youtube par search karo" to "search youtube",

        // === CLEAR NOTIFICATIONS ===
        "नोटिफिकेशन हटाओ" to "clear notification",
        "notification hatao" to "clear notification",
        "notification clear karo" to "clear notification",

        // === READ NOTIFICATIONS ===
        "नोटिफिकेशन पढ़ो" to "read notification",
        "notification padho" to "read notification",
        "क्या नोटिफिकेशन है" to "what notification",

        // === TYPE TEXT ===
        "लिखो " to "type ",
        "likho " to "type ",
        "type karo " to "type ",

        // === CLICK / TAP ===
        "क्लिक करो " to "click ",
        "click karo " to "click ",
        "दबाओ " to "tap ",
        "dabao " to "tap ",

        // === OFFLINE PHONETIC MISRECOGNITIONS ===
        // When the English offline recognizer hears Hindi words,
        // it produces predictable English approximations:
        "colour" to "kholo",        // "kholo" → "colour"
        "color" to "kholo",         // "kholo" → "color"
        "follow" to "kholo",        // "kholo" → "follow"
        "polo" to "kholo",          // "kholo" → "polo"
        "cola" to "kholo",          // "kholo" → "cola"
        "car oh" to "karo",         // "karo" → "car oh"
        "carlo" to "karo",          // "karo" → "carlo"
        "carol" to "karo",          // "karo" → "carol"
        "jar" to "jao",             // "jao" → "jar"
        "jaw" to "jao",             // "jao" → "jaw"
        "job" to "jao"              // "jao" → "job"
    )

    /**
     * Special patterns for "open" and "call" commands where the object follows the verb.
     * Hindi: "X खोलो" → "open X"
     * Hindi: "X को कॉल करो" → "call X"
     *
     * Also handles common OFFLINE MISRECOGNITIONS where the English speech model
     * hears Hindi words as similar-sounding English words:
     *   "kholo" → "colour", "follow", "polo", "call oh", "hello"
     *   "karo"  → "car oh", "Carlo", "Carol"
     */
    private val openPattern = Regex(
        "(.+?)\\s*(खोलो|kholo|colour|color|follow|polo|call\\s*oh|hello|cola)$",
        RegexOption.IGNORE_CASE
    )
    private val callPattern = Regex("(.+?)\\s*(को\\s*कॉल\\s*करो|ko\\s*call\\s*karo|को\\s*फोन\\s*करो|ko\\s*phone\\s*karo)$", RegexOption.IGNORE_CASE)
    private val callPrefixPattern = Regex("^(कॉल\\s*करो\\s*|call\\s*karo\\s*)(.+)", RegexOption.IGNORE_CASE)

    /**
     * Translate a Hindi/Hinglish command to English.
     * If the command is already in English, it passes through unchanged.
     */
    fun translate(rawCommand: String): String {
        var command = rawCommand.trim().lowercase()

        // 1. Check special structural patterns first (word order differs in Hindi)

        // "X खोलो" / "X kholo" → "open X"
        openPattern.find(command)?.let { match ->
            val appName = match.groupValues[1].trim()
            return "open $appName"
        }

        // "X को कॉल करो" / "X ko call karo" → "call X"
        callPattern.find(command)?.let { match ->
            val contactName = match.groupValues[1].trim()
            return "call $contactName"
        }

        // "कॉल करो X" / "call karo X" → "call X"
        callPrefixPattern.find(command)?.let { match ->
            val contactName = match.groupValues[2].trim()
            return "call $contactName"
        }

        // 2. Apply direct translation rules (longest match first — they're ordered that way)
        for ((hindi, english) in translationRules) {
            if (command.contains(hindi.lowercase())) {
                command = command.replace(hindi.lowercase(), english)
            }
        }

        return command.trim()
    }
}
