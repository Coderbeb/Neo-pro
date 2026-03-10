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
        "Found" to "मिला",

        // === VISION ASSISTANCE ===
        "I cannot see anything. Please connect the camera through OTG cable." to "मुझे कुछ दिख नहीं रहा। कृपया कैमरा OTG केबल से जोड़ें।",
        "I cannot see anything right now. Please check the camera." to "मुझे अभी कुछ दिख नहीं रहा। कृपया कैमरा चेक करें।",
        "Camera got disconnected" to "कैमरा डिस्कनेक्ट हो गया",
        "Camera connected" to "कैमरा जुड़ गया",
        "I stopped watching" to "मैंने देखना बंद कर दिया",
        "Navigation stopped" to "नेविगेशन बंद कर दिया",
        "Navigation mode started" to "नेविगेशन मोड शुरू हो गया",
        "I don't see anyone in front of the camera right now." to "मुझे अभी कैमरे के सामने कोई नहीं दिख रहा।",
        "I haven't looked around yet. Say 'what is around me' first." to "मैंने अभी तक देखा नहीं है। पहले 'mere aas paas kya hai' बोलें।",
        "I don't know anyone yet." to "मैं अभी किसी को नहीं जानता।",
        "Could not start vision mode. Please try again." to "विज़न मोड शुरू नहीं हो पाया। कृपया फिर से कोशिश करें।",
        "Something went wrong. Please try again." to "कुछ गलत हो गया। कृपया फिर से कोशिश करें।",
        "USB camera permission denied" to "USB कैमरा की अनुमति नहीं मिली",
        "I'll remember" to "मैं याद रखूंगा",
        "I've forgotten" to "मैंने भुला दिया",
        "I don't know anyone named" to "मैं किसी को नहीं जानता जिसका नाम है",
        "I know" to "मैं जानता हूँ",
        "people" to "लोगों को",
        "person" to "व्यक्ति को",
        "I need to slow down a bit. Try again in a few seconds." to "मुझे थोड़ा धीमा करना होगा। कुछ सेकंड बाद फिर से कोशिश करें।",
        "I'm having trouble connecting to my vision service." to "विज़न सेवा से जुड़ने में परेशानी हो रही है।",
        "Please check your internet connection." to "कृपया अपना इंटरनेट कनेक्शन चेक करें.",

        // === IN-APP SEARCH ===
        "Searching on YouTube" to "यूट्यूब पर खोज रहे हैं",
        "Searching on Instagram" to "इंस्टाग्राम पर खोज रहे हैं",
        "Searching on Play Store" to "प्ले स्टोर पर खोज रहे हैं",
        "Searching in" to "इसमें खोज रहे हैं",
        "No search found in" to "इसमें सर्च नहीं मिला",
        "searching on Google" to "गूगल पर खोज रहे हैं",

        // === CALL / ANSWER ===
        "Answering call" to "कॉल उठा रहे हैं",
        "Speaker on" to "स्पीकर चालू",
        "Could not answer call" to "कॉल नहीं उठा सकते",
        "Call permission not granted" to "कॉल की अनुमति नहीं है",
        "is calling you" to "आपको कॉल कर रहा है",

        // === RAPIDO RIDE BOOKING ===
        "Opening Rapido" to "रैपिडो खोल रहे हैं",
        "Rapido is not installed" to "रैपिडो इंस्टॉल नहीं है",
        "Where do you want to go?" to "आप कहाँ जाना चाहते हैं?",
        "Is this correct?" to "क्या यह सही है?",
        "Which one?" to "कौन सा?",
        "Searching" to "खोज रहे हैं",
        "Destination selected" to "जगह चुन ली",
        "Pickup is" to "पिकअप है",
        "Could not find" to "नहीं मिला",
        "Please try again." to "कृपया फिर से कोशिश करें।",
        "No results found for" to "कोई नतीजा नहीं मिला",
        "Please say the destination again." to "कृपया जगह का नाम दोबारा बोलें।",
        "Could not select destination. Please try again." to "जगह चुनने में विफल। कृपया दोबारा कोशिश करें।",
        "Ride options are loading. Please wait." to "राइड ऑप्शन लोड हो रहे हैं। कृपया रुकें।",
        "Could not read ride options. Please select manually in Rapido." to "राइड ऑप्शन नहीं पढ़ सके। कृपया रैपिडो में खुद चुनें।",
        "Which one? Bike, Auto, or Cab?" to "कौन सा? बाइक, ऑटो, या कैब?",
        "selected." to "चुन लिया।",
        "Should I book?" to "बुक करूं?",
        "Could not select" to "चुन नहीं सके",
        "Please say Bike, Auto, or Cab." to "कृपया बाइक, ऑटो, या कैब बोलें।",
        "Ride booked! Finding your captain." to "राइड बुक हो गई! कैप्टन ढूंढ रहे हैं।",
        "Could not confirm booking. Please try tapping the book button manually." to "बुकिंग कन्फर्म नहीं हो पाई। कृपया बुक बटन खुद दबाएं।",
        "Booking not confirmed. Say yes to book or cancel to stop." to "बुकिंग कन्फर्म नहीं हुई। बुक करने के लिए हां बोलें या कैंसल बोलें।",
        "Rapido booking cancelled" to "रैपिडो बुकिंग कैंसल की",
        "Captain found!" to "कैप्टन मिल गया!",
        "Captain" to "कैप्टन",
        "Arriving in" to "आ रहे हैं",
        "Vehicle" to "गाड़ी",
        "Your PIN is" to "आपका पिन है",
        "Your Rapido PIN is" to "आपका रैपिडो पिन है",
        "PIN not visible on screen. Please open Rapido first." to "पिन स्क्रीन पर नहीं दिख रहा। कृपया पहले रैपिडो खोलें।",
        "Cancelling ride" to "राइड कैंसल कर रहे हैं",
        "Ride cancelled" to "राइड कैंसल हो गई",
        "Could not cancel ride. Please cancel manually." to "राइड कैंसल नहीं हो पाई। कृपया खुद कैंसल करें।",
        "No active Rapido booking to cancel" to "कोई एक्टिव रैपिडो बुकिंग नहीं है",
        "Still searching for captain. Please check Rapido app." to "अभी भी कैप्टन ढूंढ रहे हैं। कृपया रैपिडो ऐप चेक करें।",
        "Captain assigned. Please check Rapido for details." to "कैप्टन मिल गया। कृपया डिटेल्स रैपिडो ऐप में देखें।",
        "Could not open Rapido" to "रैपिडो नहीं खुल सका",
        "Could not find search field in Rapido" to "रैपिडो में सर्च फील्ड नहीं मिला",
        "Please change pickup location in Rapido app, then tell me when ready." to "कृपया रैपिडो ऐप में पिकअप लोकेशन बदलें, फिर मुझे बताएं।",

        // === WHATSAPP / CHAT AUTOMATION ===
        "Already in" to "पहले से",
        "'s chat" to "की चैट में हैं",
        "Contact" to "कॉन्टैक्ट",
        "not found in WhatsApp. Please try saying the full name." to "व्हाट्सएप में नहीं मिला। कृपया पूरा नाम बोलें।",
        "Opened" to "खोला",
        "No active chat. Open a chat first." to "कोई चैट खुली नहीं है। पहले चैट खोलें।",
        "No active WhatsApp chat" to "कोई व्हाट्सएप चैट एक्टिव नहीं",
        "Message sent" to "मैसेज भेजा",
        "Calling" to "कॉल कर रहे हैं",
        "on WhatsApp" to "व्हाट्सएप पर",
        "Video calling" to "वीडियो कॉल कर रहे हैं",
        "No new updates found" to "कोई नई अपडेट नहीं मिली",
        "updates found" to "अपडेट मिलीं",
        "Closed" to "बंद किया",
        "No messages found in" to "कोई मैसेज नहीं मिला",
        "'s latest messages:" to "के लेटेस्ट मैसेज:",
        "Can only reply to WhatsApp messages right now" to "अभी सिर्फ व्हाट्सएप मैसेज का रिप्लाई कर सकते हैं",
        "No recent notification to reply to" to "रिप्लाई करने के लिए कोई हाल का नोटिफिकेशन नहीं",
        "Can't find search button" to "सर्च बटन नहीं मिला",
        "Search field didn't appear" to "सर्च फील्ड नहीं दिखा",
        "Can't find text input" to "टेक्स्ट इनपुट नहीं मिला",
        "Can't find message box" to "मैसेज बॉक्स नहीं मिला",
        "Can't find Send button" to "सेंड बटन नहीं मिला",
        "Can't find call button" to "कॉल बटन नहीं मिला",
        "Can't find video call button" to "वीडियो कॉल बटन नहीं मिला",
        "Can't find Updates tab" to "अपडेट टैब नहीं मिला",
        "Can't read screen" to "स्क्रीन नहीं पढ़ सकते",
        "Chat didn't open" to "चैट नहीं खुली",
        "WhatsApp not installed" to "व्हाट्सएप इंस्टॉल नहीं है",
        "WhatsApp didn't open" to "व्हाट्सएप नहीं खुला",

        // === VOLUME ===
        "Volume adjusted" to "आवाज़ ठीक की"
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
