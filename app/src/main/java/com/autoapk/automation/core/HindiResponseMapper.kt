package com.autoapk.automation.core

/**
 * Translates English TTS responses to Hindi.
 *
 * When the user speaks in Hindi/Hinglish, Neo responds in Hindi too.
 * Uses a pattern-matching approach to translate common response phrases.
 */
object HindiResponseMapper {

    /**
     * Translation rules: English phrase → Hindi replacement.
     * Longer phrases come first to prevent partial matches.
     */
    private val responseRules: List<Pair<String, String>> = listOf(
        // === NAVIGATION ===
        "Going home" to "होम जा रहे हैं",
        "Going back" to "पीछे जा रहे हैं",
        "Opening recent apps" to "हाल के ऐप्स खोल रहे हैं",
        "Opening notifications" to "नोटिफिकेशन खोल रहे हैं",
        "Opening quick settings" to "क्विक सेटिंग्स खोल रहे हैं",
        "Swiped left" to "बाएं स्वाइप किया",
        "Swiped right" to "दाएं स्वाइप किया",
        "Failed to go home" to "होम जाने में विफल",
        "Failed to go back" to "पीछे जाने में विफल",
        "Failed to open recents" to "रीसेंट ऐप्स खोलने में विफल",
        "Failed to open notifications" to "नोटिफिकेशन खोलने में विफल",
        "Failed to open quick settings" to "क्विक सेटिंग्स खोलने में विफल",

        // === LOCK / UNLOCK ===
        "Locking screen" to "स्क्रीन लॉक कर रहे हैं",
        "Phone is already unlocked" to "फोन पहले से अनलॉक है",
        "Phone unlocked successfully" to "फोन अनलॉक हो गया",
        "Could not unlock screen" to "स्क्रीन अनलॉक नहीं हो पाई",
        "No screen lock saved. Please set it up in the app." to "कोई स्क्रीन लॉक सेव नहीं है। ऐप में सेट करें।",
        "Code accepted. Unlocking." to "कोड स्वीकार। अनलॉक कर रहे हैं।",
        "Code accepted. Unlocking phone." to "कोड स्वीकार। फोन अनलॉक कर रहे हैं।",
        "Please say your security code to unlock." to "अनलॉक करने के लिए अपना सिक्योरिटी कोड बोलें।",
        "Too many wrong attempts. Please unlock your phone manually." to "बहुत ज्यादा गलत प्रयास। फोन मैन्युअली अनलॉक करें।",

        // === VOLUME ===
        "Volume up" to "आवाज़ बढ़ाई",
        "Volume down" to "आवाज़ कम की",
        "Volume muted" to "आवाज़ म्यूट की",
        "Volume unmuted" to "आवाज़ अनम्यूट की",

        // === CONNECTIVITY ===
        "WiFi turned on" to "वाईफाई चालू किया",
        "WiFi turned off" to "वाईफाई बंद किया",
        "Bluetooth turned on" to "ब्लूटूथ चालू किया",
        "Bluetooth turned off" to "ब्लूटूथ बंद किया",
        "WiFi on" to "वाईफाई चालू",
        "WiFi off" to "वाईफाई बंद",
        "Bluetooth on" to "ब्लूटूथ चालू",
        "Bluetooth off" to "ब्लूटूथ बंद",
        "Hotspot enabled" to "हॉटस्पॉट चालू किया",
        "Hotspot disabled" to "हॉटस्पॉट बंद किया",
        "Airplane mode" to "हवाई जहाज मोड",
        "Mobile data" to "मोबाइल डेटा",
        "Dark mode enabled" to "डार्क मोड चालू",
        "Dark mode disabled" to "डार्क मोड बंद",

        // === FLASHLIGHT ===
        "Flashlight on" to "टॉर्च चालू",
        "Flashlight off" to "टॉर्च बंद",
        "Flash on" to "फ्लैश चालू",
        "Flash off" to "फ्लैश बंद",

        // === BATTERY ===
        "Battery level is" to "बैटरी लेवल है",
        "and charging" to "और चार्ज हो रहा है",
        "percent" to "प्रतिशत",

        // === TIME / DATE ===
        "The time is" to "समय है",
        "Today is" to "आज है",

        // === SCREEN READING ===
        "Reading screen" to "स्क्रीन पढ़ रहे हैं",
        "Cannot read screen" to "स्क्रीन नहीं पढ़ सकते",
        "Screen has:" to "स्क्रीन में है:",
        "buttons" to "बटन",
        "text fields" to "टेक्स्ट फील्ड",

        // === MESSAGING ===
        "Who should I send the message to?" to "मैसेज किसको भेजूं?",
        "Who should I send the WhatsApp to?" to "व्हाट्सएप किसको भेजूं?",
        "Sending SMS to" to "एसएमएस भेज रहे हैं",
        "Opening WhatsApp chat with" to "व्हाट्सएप चैट खोल रहे हैं",
        "Contact" to "कॉन्टैक्ट",
        "not found" to "नहीं मिला",

        // === PHONE CALLS ===
        "Calling" to "कॉल कर रहे हैं",
        "Call ended" to "कॉल खत्म",
        "Call answered" to "कॉल उठाया",
        "Phone call permission not granted" to "फोन कॉल की अनुमति नहीं है",
        "Multiple contacts found" to "कई कॉन्टैक्ट मिले",
        "Please say the full name." to "पूरा नाम बोलें।",

        // === NOTIFICATIONS ===
        "No notifications" to "कोई नोटिफिकेशन नहीं",
        "You have" to "आपके पास हैं",
        "notifications" to "नोटिफिकेशन",

        // === CAMERA ===
        "Photo taken" to "फोटो ली गई",
        "Recording started" to "रिकॉर्डिंग शुरू",
        "Video saved" to "वीडियो सेव हो गया",

        // === CLIPBOARD ===
        "Clipboard contains:" to "क्लिपबोर्ड में है:",
        "Clipboard is empty" to "क्लिपबोर्ड खाली है",
        "Copied to clipboard" to "क्लिपबोर्ड में कॉपी किया",
        "Select text first, then say copy" to "पहले टेक्स्ट चुनें, फिर कॉपी बोलें",

        // === MEDIA ===
        "Playing" to "बजा रहे हैं",
        "What song should I play?" to "कौन सा गाना बजाऊं?",
        "Music" to "म्यूजिक",
        "Paused" to "रुका हुआ",
        "Resumed" to "फिर से चालू",

        // === EMERGENCY ===
        "Emergency mode activated" to "इमरजेंसी मोड चालू",
        "Sharing your location" to "आपकी लोकेशन शेयर कर रहे हैं",
        "Could not get your location. Make sure GPS is on." to "लोकेशन नहीं मिली। GPS चालू करें।",
        "Location permission not granted" to "लोकेशन की अनुमति नहीं है",

        // === ALARM ===
        "Setting alarm for" to "अलार्म सेट कर रहे हैं",
        "Setting timer for" to "टाइमर सेट कर रहे हैं",
        "minutes" to "मिनट",

        // === SCREENSHOT ===
        "Screenshot taken" to "स्क्रीनशॉट लिया",
        "Taking screenshot" to "स्क्रीनशॉट ले रहे हैं",

        // === BRIGHTNESS ===
        "Brightness increased" to "रोशनी बढ़ाई",
        "Brightness decreased" to "रोशनी कम की",
        "Brightness set to maximum" to "रोशनी पूरी की",

        // === GENERAL ===
        "Stopped" to "रुक गया",
        "Cancelled" to "रद्द किया",
        "I didn't understand. Please try again" to "समझ नहीं आया। कृपया दोबारा बोलें",
        "Not connected to accessibility service" to "एक्सेसिबिलिटी सर्विस से कनेक्ट नहीं है",
        "Opening" to "खोल रहे हैं",
        "Opened" to "खोला",

        // === CHAT MODE ===
        "No chat session active" to "कोई चैट सेशन एक्टिव नहीं",
        "No messages visible" to "कोई मैसेज नहीं दिख रहा",
        "Messages on screen:" to "स्क्रीन पर मैसेज:",
        "Sent:" to "भेजा:",
        "Chat mode ended" to "चैट मोड खत्म",

        // === SEARCH ===
        "Searching Google for" to "गूगल पर खोज रहे हैं",
        "Searching YouTube for" to "यूट्यूब पर खोज रहे हैं",

        // === HELP ===
        "You can say:" to "आप बोल सकते हैं:",

        // === DO NOT DISTURB ===
        "Do not disturb enabled" to "डू नॉट डिस्टर्ब चालू",
        "Do not disturb disabled" to "डू नॉट डिस्टर्ब बंद",
        "Silent mode on" to "साइलेंट मोड चालू",
        "Silent mode off" to "साइलेंट मोड बंद",

        // === ROTATION ===
        "Auto rotate on" to "ऑटो रोटेट चालू",
        "Auto rotate off" to "ऑटो रोटेट बंद",

        // === VERIFICATION ===
        "Verification required for commands" to "कमांड के लिए वेरिफिकेशन ज़रूरी है",
        "Normal mode" to "नॉर्मल मोड",

        // === GOOGLE MAPS NAVIGATION ===
        "Where do you want to go?" to "आप कहाँ जाना चाहते हैं?",
        "Should I start navigation?" to "क्या मैं नेविगेशन शुरू करूं?",
        "Starting navigation to" to "नेविगेशन शुरू कर रहे हैं",
        "Starting walking navigation to" to "पैदल नेविगेशन शुरू कर रहे हैं",
        "Starting transit navigation to" to "ट्रांजिट नेविगेशन शुरू कर रहे हैं",
        "Starting cycling navigation to" to "साइकिल नेविगेशन शुरू कर रहे हैं",
        "Navigation cancelled" to "नेविगेशन रद्द किया",
        "Google Maps is not installed" to "गूगल मैप इंस्टॉल नहीं है",
        "Could not open Google Maps" to "गूगल मैप नहीं खुल सका",
        "Could not search in Google Maps" to "गूगल मैप में खोज नहीं हो पाई",
        "Could not start navigation" to "नेविगेशन शुरू नहीं हो पाई",
        "Say yes to start, or walking, transit, or cycling for a different mode. Say cancel to stop." to "शुरू करने के लिए हां बोलें, या पैदल, ट्रांजिट, या साइकिल बोलें। रद्द करने के लिए कैंसल बोलें।",
        "Found" to "मिला"
    )

    /**
     * Translate an English response to Hindi.
     * Uses substring matching to handle dynamic content like names and numbers.
     */
    fun translate(englishResponse: String): String {
        var result = englishResponse
        for ((english, hindi) in responseRules) {
            if (result.contains(english, ignoreCase = true)) {
                result = result.replace(english, hindi, ignoreCase = true)
            }
        }
        return result
    }
}
