package com.autoapk.automation.core

import android.util.Log

/**
 * Smart keyword-scoring intent classifier.
 *
 * Instead of rigid `contains()` checks, this scores every user input
 * against all known intents using keyword overlap. The highest-scoring
 * intent wins, provided it meets a minimum confidence threshold.
 *
 * Supports Hindi, Hinglish, and English — with filler word stripping
 * and verb normalization for maximum flexibility.
 */
object SmartCommandMatcher {

    private const val TAG = "Neo_Matcher"

    /** Minimum score to accept a match (lowered from 2.5 for better recall) */
    private const val MIN_SCORE = 1.5f

    // ==================== RESULT ====================

    data class MatchResult(
        val intent: CommandIntent,
        val score: Float,
        val extractedParam: String = ""
    )

    // ==================== INTENT ENUM ====================

    enum class CommandIntent {
        // Navigation
        GO_HOME, GO_BACK, OPEN_RECENTS, OPEN_NOTIFICATIONS,
        SWIPE_LEFT, SWIPE_RIGHT, OPEN_QUICK_SETTINGS,

        // Lock / Unlock
        UNLOCK_PHONE, LOCK_PHONE,

        // Stop everything
        STOP_ALL,

        // Scrolling
        SCROLL_DOWN, SCROLL_UP,

        // Volume
        VOLUME_UP, VOLUME_DOWN, MUTE, UNMUTE,

        // Connectivity / Quick Settings toggles
        WIFI_ON, WIFI_OFF,
        BLUETOOTH_ON, BLUETOOTH_OFF,
        MOBILE_DATA_ON, MOBILE_DATA_OFF,
        DND_ON, DND_OFF,
        HOTSPOT_ON, HOTSPOT_OFF,
        AIRPLANE_MODE_ON, AIRPLANE_MODE_OFF,
        AUTO_ROTATE_ON, AUTO_ROTATE_OFF,
        DARK_MODE_ON, DARK_MODE_OFF,
        LOCATION_ON, LOCATION_OFF,

        // Flashlight
        FLASHLIGHT_ON, FLASHLIGHT_OFF,

        // App launching (parameterized)
        OPEN_APP,

        // Phone calls
        ANSWER_CALL, END_CALL, TOGGLE_SPEAKER,
        CALL_CONTACT,

        // SMS
        READ_SMS,

        // Media
        PLAY_MUSIC, PAUSE_MUSIC, NEXT_SONG, PLAY_SONG,

        // YouTube
        SEARCH_YOUTUBE,

        // Screen
        READ_SCREEN, DESCRIBE_SCREEN, LIST_CLICKABLE,
        TAKE_SCREENSHOT,

        // Battery
        READ_BATTERY,

        // Rotation (legacy alias)
        ROTATION_ON, ROTATION_OFF,

        // Brightness
        BRIGHTNESS_UP, BRIGHTNESS_DOWN, BRIGHTNESS_MAX, BRIGHTNESS_MIN, BRIGHTNESS_HALF,

        // Alarm & Timer
        SET_ALARM, SET_TIMER,


        // Search
        SEARCH_GOOGLE,

        // Notifications
        CLEAR_NOTIFICATIONS, READ_NOTIFICATIONS,

        // Type text (parameterized)
        TYPE_TEXT,

        // Click/Tap (parameterized)
        CLICK_TARGET, SELECT_ITEM,

        // Info
        TELL_TIME, TELL_DAY, WHICH_APP, SHOW_HELP, REPEAT_LAST,

        // Messaging (parameterized)
        SEND_SMS, SEND_WHATSAPP,

        // Chat mode
        ENTER_CHAT, SEND_CHAT_MSG, READ_CHAT, EXIT_CHAT,

        // Daily life
        OPEN_WEATHER, TAKE_PHOTO, READ_CLIPBOARD, COPY,

        // Camera (video)
        RECORD_VIDEO, STOP_RECORDING,

        // Emergency
        EMERGENCY, SEND_LOCATION,

        // Stop TTS
        STOP_SPEAKING,

        // Media (additional)
        PREVIOUS_SONG, TOGGLE_PLAY_PAUSE, STOP_MUSIC
    }

    // ==================== EXACT PHRASES (instant HashMap lookup) ====================

    private val exactPhrases: Map<String, CommandIntent> = buildMap {
        // --- NAVIGATION ---
        for (p in listOf("go home", "ghar jao", "ghar chalo", "home jao", "go to home",
                         "home screen", "home", "ghar", "ホーム", "घर जाओ"))
            put(p, CommandIntent.GO_HOME)

        for (p in listOf("go back", "back", "peeche jao", "peeche", "peche jao",
                         "wapas jao", "wapas", "piche jao", "वापस", "पीछे जाओ", "back jao"))
            put(p, CommandIntent.GO_BACK)

        for (p in listOf("recent apps", "recents", "recent", "open recents",
                         "recent wale", "pichle apps", "multitask"))
            put(p, CommandIntent.OPEN_RECENTS)

        for (p in listOf("notifications", "notification", "open notifications",
                         "notification dikhao", "notification kholo", "suchna"))
            put(p, CommandIntent.OPEN_NOTIFICATIONS)

        for (p in listOf("swipe left", "left swipe", "baaye swipe"))
            put(p, CommandIntent.SWIPE_LEFT)

        for (p in listOf("swipe right", "right swipe", "daaye swipe"))
            put(p, CommandIntent.SWIPE_RIGHT)

        for (p in listOf("quick settings", "open quick settings"))
            put(p, CommandIntent.OPEN_QUICK_SETTINGS)

        // --- WIFI ---
        for (p in listOf("wifi on", "wifi on karo", "wifi chalu karo", "wifi laga", "wifi lagao",
                         "turn on wifi", "enable wifi", "wifi chalu", "wifi connect karo"))
            put(p, CommandIntent.WIFI_ON)
        for (p in listOf("wifi off", "wifi off karo", "wifi band karo", "wifi hata",
                         "turn off wifi", "disable wifi", "wifi band", "wifi disconnect karo"))
            put(p, CommandIntent.WIFI_OFF)

        // --- BLUETOOTH ---
        for (p in listOf("bluetooth on", "bluetooth on karo", "bluetooth chalu", "bt on",
                         "turn on bluetooth", "enable bluetooth", "bluetooth chalu karo"))
            put(p, CommandIntent.BLUETOOTH_ON)
        for (p in listOf("bluetooth off", "bluetooth off karo", "bluetooth band", "bt off",
                         "turn off bluetooth", "disable bluetooth", "bluetooth band karo"))
            put(p, CommandIntent.BLUETOOTH_OFF)

        // --- MOBILE DATA ---
        for (p in listOf("data on", "data on karo", "mobile data on", "data chalu",
                         "net on", "internet on karo", "turn on data", "enable data",
                         "mobile data chalu", "data laga"))
            put(p, CommandIntent.MOBILE_DATA_ON)
        for (p in listOf("data off", "data off karo", "mobile data off", "data band",
                         "net off", "internet band karo", "turn off data", "disable data",
                         "mobile data band", "data hata"))
            put(p, CommandIntent.MOBILE_DATA_OFF)

        // --- DND ---
        for (p in listOf("dnd on", "do not disturb on", "silent mode on", "dnd chalu",
                         "dnd on karo", "silence on", "shant karo"))
            put(p, CommandIntent.DND_ON)
        for (p in listOf("dnd off", "do not disturb off", "silent mode off", "dnd band",
                         "dnd off karo", "silence off", "shant band"))
            put(p, CommandIntent.DND_OFF)

        // --- HOTSPOT ---
        for (p in listOf("hotspot on", "hotspot on karo", "hotspot chalu", "hotspot laga",
                         "turn on hotspot", "enable hotspot"))
            put(p, CommandIntent.HOTSPOT_ON)
        for (p in listOf("hotspot off", "hotspot off karo", "hotspot band",
                         "turn off hotspot", "disable hotspot", "hotspot hata"))
            put(p, CommandIntent.HOTSPOT_OFF)

        // --- AIRPLANE MODE ---
        for (p in listOf("airplane mode on", "flight mode on", "airplane on",
                         "airplane mode chalu", "havayi mode on"))
            put(p, CommandIntent.AIRPLANE_MODE_ON)
        for (p in listOf("airplane mode off", "flight mode off", "airplane off",
                         "airplane mode band", "havayi mode off"))
            put(p, CommandIntent.AIRPLANE_MODE_OFF)

        // --- AUTO ROTATE ---
        for (p in listOf("auto rotate on", "rotation on", "rotate on",
                         "auto rotate chalu", "rotation chalu"))
            put(p, CommandIntent.AUTO_ROTATE_ON)
        for (p in listOf("auto rotate off", "rotation off", "rotate off",
                         "rotation lock", "rotation band", "auto rotate band"))
            put(p, CommandIntent.AUTO_ROTATE_OFF)

        // --- DARK MODE ---
        for (p in listOf("dark mode on", "dark mode chalu", "night mode on",
                         "dark theme on", "dark mode laga"))
            put(p, CommandIntent.DARK_MODE_ON)
        for (p in listOf("dark mode off", "dark mode band", "night mode off",
                         "light mode on", "dark theme off"))
            put(p, CommandIntent.DARK_MODE_OFF)

        // --- LOCATION ---
        for (p in listOf("location on", "gps on", "location chalu",
                         "turn on location", "enable location"))
            put(p, CommandIntent.LOCATION_ON)
        for (p in listOf("location off", "gps off", "location band",
                         "turn off location", "disable location"))
            put(p, CommandIntent.LOCATION_OFF)
    }

    // ==================== KEYWORD DATABASE ====================

    /**
     * Each intent maps to a list of keyword groups.
     * A keyword group is a set of synonyms — matching ANY word in the group scores +1.
     * Having matches across MULTIPLE groups gives higher scores (more confident match).
     *
     * Format: list of sets. Each set is a synonym group.
     * Score = number of groups that have at least one matching keyword.
     * Bonus +0.5 for each additional keyword match within a group.
     */
    private data class IntentKeywords(
        val intent: CommandIntent,
        val keywordGroups: List<Set<String>>,
        val weight: Float = 1.0f,  // Priority multiplier
        val requireAll: Boolean = false // If true, ALL groups must match
    )

    private val intentDatabase: List<IntentKeywords> = listOf(
        // === STOP ALL (highest priority) ===
        IntentKeywords(CommandIntent.STOP_ALL, listOf(
            setOf("stop", "ruk", "ruko", "rok", "bas", "cancel", "band", "chup", "thahar", "abort", "quit", "रुको", "बस", "बंद", "चुप", "रुक")
        ), weight = 2.5f),

        // === STOP TTS ONLY (needs 2 words) ===
        IntentKeywords(CommandIntent.STOP_SPEAKING, listOf(
            setOf("stop", "shut", "quiet", "silence", "chup", "shant", "band", "bas"),
            setOf("talking", "speaking", "baat", "bolna", "up", "bolo")
        ), weight = 1.5f, requireAll = true),

        // === NAVIGATION ===
        IntentKeywords(CommandIntent.GO_HOME, listOf(
            setOf("home", "ghar", "main", "desktop", "launcher", "ホーム", "घर", "homescreen")
        )),
        IntentKeywords(CommandIntent.GO_BACK, listOf(
            setOf("back", "peeche", "peche", "piche", "wapas", "return", "पीछे", "वापस", "pichhe")
        )),
        IntentKeywords(CommandIntent.OPEN_RECENTS, listOf(
            setOf("recent", "recents", "multitask", "haal", "hal", "pichle", "रीसेंट")
        )),
        IntentKeywords(CommandIntent.OPEN_NOTIFICATIONS, listOf(
            setOf("notification", "notifications", "notify", "notif", "suchna", "नोटिफिकेशन"),
            setOf("open", "show", "kholo", "dikhao", "dekho", "check", "panel")
        )),
        IntentKeywords(CommandIntent.SWIPE_LEFT, listOf(
            setOf("swipe", "slide", "स्वाइप"),
            setOf("left", "baaye", "baye", "बाएं")
        ), requireAll = true),
        IntentKeywords(CommandIntent.SWIPE_RIGHT, listOf(
            setOf("swipe", "slide", "स्वाइप"),
            setOf("right", "daaye", "daye", "दाएं")
        ), requireAll = true),
        IntentKeywords(CommandIntent.OPEN_QUICK_SETTINGS, listOf(
            setOf("quick", "control", "क्विक"),
            setOf("settings", "setting", "panel", "सेटिंग")
        ), requireAll = true),

        // === LOCK / UNLOCK ===
        IntentKeywords(CommandIntent.UNLOCK_PHONE, listOf(
            setOf("unlock", "unlocked", "unlocking", "wake", "jagao", "जगाओ", "अनलॉक"),
            setOf("phone", "screen", "device", "फोन", "स्क्रीन")
        ), requireAll = true, weight = 1.3f),
        IntentKeywords(CommandIntent.LOCK_PHONE, listOf(
            setOf("lock", "locked", "locking", "लॉक", "sleep"),
            setOf("phone", "screen", "device", "karo", "фоन", "स्क्रीन", "करो")
        ), requireAll = true, weight = 1.5f),

        // === SCROLLING ===
        IntentKeywords(CommandIntent.SCROLL_DOWN, listOf(
            setOf("scroll", "neeche", "niche", "down", "page", "नीचे", "स्क्रॉल", "page down")
        )),
        IntentKeywords(CommandIntent.SCROLL_UP, listOf(
            setOf("scroll", "upar", "oopar", "up", "page", "ऊपर", "स्क्रॉल", "page up"),
            setOf("up", "upar", "oopar", "ऊपर")
        ), requireAll = true),

        // === QS TOGGLE KEYWORDS (fallback for variations not in exactPhrases) ===
        IntentKeywords(CommandIntent.WIFI_ON, listOf(
            setOf("wifi", "wi-fi", "वाईफाई"),
            setOf("on", "chalu", "shuru", "enable", "connect", "laga", "चालू")
        ), requireAll = true),
        IntentKeywords(CommandIntent.WIFI_OFF, listOf(
            setOf("wifi", "wi-fi", "वाईफाई"),
            setOf("off", "band", "disable", "disconnect", "hata", "बंद")
        ), requireAll = true),
        IntentKeywords(CommandIntent.BLUETOOTH_ON, listOf(
            setOf("bluetooth", "bt", "ब्लूटूथ"),
            setOf("on", "chalu", "enable", "connect", "laga", "चालू")
        ), requireAll = true),
        IntentKeywords(CommandIntent.BLUETOOTH_OFF, listOf(
            setOf("bluetooth", "bt", "ब्लूटूथ"),
            setOf("off", "band", "disable", "disconnect", "hata", "बंद")
        ), requireAll = true),
        IntentKeywords(CommandIntent.MOBILE_DATA_ON, listOf(
            setOf("data", "mobile", "internet", "net"),
            setOf("on", "chalu", "enable", "laga", "चालू")
        ), requireAll = true),
        IntentKeywords(CommandIntent.MOBILE_DATA_OFF, listOf(
            setOf("data", "mobile", "internet", "net"),
            setOf("off", "band", "disable", "hata", "बंद")
        ), requireAll = true),
        IntentKeywords(CommandIntent.DND_ON, listOf(
            setOf("dnd", "disturb", "silent", "silence", "shant", "डीएनडी"),
            setOf("on", "enable", "chalu", "start", "mode", "चालू")
        )),
        IntentKeywords(CommandIntent.DND_OFF, listOf(
            setOf("dnd", "disturb", "silent", "silence", "shant", "डीएनडी"),
            setOf("off", "disable", "band", "hata", "बंद")
        ), requireAll = true),
        IntentKeywords(CommandIntent.HOTSPOT_ON, listOf(
            setOf("hotspot", "tethering", "हॉटस्पॉट"),
            setOf("on", "chalu", "enable", "laga", "चालू")
        ), requireAll = true),
        IntentKeywords(CommandIntent.HOTSPOT_OFF, listOf(
            setOf("hotspot", "tethering", "हॉटस्पॉट"),
            setOf("off", "band", "disable", "hata", "बंद")
        ), requireAll = true),
        IntentKeywords(CommandIntent.AIRPLANE_MODE_ON, listOf(
            setOf("airplane", "aeroplane", "flight", "havayi", "फ्लाइट"),
            setOf("mode", "on", "chalu", "enable", "चालू")
        )),
        IntentKeywords(CommandIntent.AIRPLANE_MODE_OFF, listOf(
            setOf("airplane", "aeroplane", "flight", "havayi", "फ्लाइट"),
            setOf("off", "band", "disable", "hata", "बंद")
        ), requireAll = true),
        IntentKeywords(CommandIntent.DARK_MODE_ON, listOf(
            setOf("dark", "night", "डार्क"),
            setOf("mode", "theme", "on", "chalu", "enable", "चालू")
        )),
        IntentKeywords(CommandIntent.DARK_MODE_OFF, listOf(
            setOf("dark", "night", "light", "डार्क"),
            setOf("mode", "theme", "off", "band", "disable", "बंद")
        ), requireAll = true),
        IntentKeywords(CommandIntent.LOCATION_ON, listOf(
            setOf("location", "gps", "लोकेशन"),
            setOf("on", "chalu", "enable", "चालू")
        ), requireAll = true),
        IntentKeywords(CommandIntent.LOCATION_OFF, listOf(
            setOf("location", "gps", "लोकेशन"),
            setOf("off", "band", "disable", "बंद")
        ), requireAll = true),

        // === VOLUME ===
        IntentKeywords(CommandIntent.VOLUME_UP, listOf(
            setOf("volume", "sound", "awaz", "awaaz", "आवाज़", "आवाज", "loudness", "vol"),
            setOf("up", "badha", "badhao", "increase", "raise", "zyada", "jyada", "more", "louder", "high", "upar", "बढ़ाओ", "ज़्यादा")
        )),
        IntentKeywords(CommandIntent.VOLUME_DOWN, listOf(
            setOf("volume", "sound", "awaz", "awaaz", "आवाज़", "आवाज", "loudness", "vol"),
            setOf("down", "kam", "decrease", "lower", "less", "reduce", "dheere", "dheema", "slow", "कम", "धीरे")
        )),
        IntentKeywords(CommandIntent.MUTE, listOf(
            setOf("mute", "silent", "shant", "chup", "म्यूट", "शांत", "चुप")
        )),
        IntentKeywords(CommandIntent.UNMUTE, listOf(
            setOf("unmute", "अनम्यूट")
        )),



        // === FLASHLIGHT ===
        IntentKeywords(CommandIntent.FLASHLIGHT_ON, listOf(
            setOf("flashlight", "flash", "torch", "light", "torchlight", "टॉर्च", "फ्लैश", "रोशनी", "bijli"),
            setOf("on", "chalu", "jala", "laga", "enable", "start", "चालू", "जला", "लगा", "do")
        )),
        IntentKeywords(CommandIntent.FLASHLIGHT_OFF, listOf(
            setOf("flashlight", "flash", "torch", "light", "torchlight", "टॉर्च", "फ्लैश"),
            setOf("off", "band", "bujha", "hata", "disable", "बंद", "बुझा", "हटा")
        ), requireAll = true),

        // === PHONE CALLS ===
        IntentKeywords(CommandIntent.ANSWER_CALL, listOf(
            setOf("answer", "pick", "receive", "accept", "uthao", "uthaao", "uthalo", "lelo", "उठाओ", "receive"),
            setOf("call", "phone", "फोन", "कॉल")
        )),
        IntentKeywords(CommandIntent.END_CALL, listOf(
            setOf("hang", "end", "cut", "disconnect", "reject", "kato", "kata", "khatam", "rakh", "काटो", "खत्म", "रख"),
            setOf("up", "call", "phone", "do", "karo", "कॉल", "फोन", "दो", "करो")
        )),
        IntentKeywords(CommandIntent.TOGGLE_SPEAKER, listOf(
            setOf("speaker", "speakerphone", "loudspeaker", "स्पीकर"),
            setOf("on", "off", "laga", "lagao", "hata", "toggle", "लगाओ", "हटा")
        )),

        // === SMS ===
        IntentKeywords(CommandIntent.READ_SMS, listOf(
            setOf("read", "padho", "suna", "batao", "पढ़ो", "सुना", "बताओ"),
            setOf("message", "messages", "sms", "msg", "text", "मैसेज", "एसएमएस")
        ), requireAll = true),

        // === MEDIA ===
        IntentKeywords(CommandIntent.PLAY_MUSIC, listOf(
            setOf("play", "start", "resume", "continue", "chalao", "bajao", "shuru", "चलाओ", "बजाओ", "शुरू"),
            setOf("music", "song", "gaana", "gana", "sangeet", "म्यूजिक", "गाना", "संगीत")
        )),
        IntentKeywords(CommandIntent.PAUSE_MUSIC, listOf(
            setOf("pause", "hold", "wait", "roko", "ruk", "rok", "पॉज", "रोको", "रुक"),
            setOf("music", "song", "gaana", "gana", "sangeet", "media", "म्यूजिक", "गाना")
        )),
        IntentKeywords(CommandIntent.TOGGLE_PLAY_PAUSE, listOf(
            setOf("toggle", "play", "pause", "resume"),
            setOf("pause", "play", "music", "media")
        ), weight = 0.8f),
        IntentKeywords(CommandIntent.NEXT_SONG, listOf(
            setOf("next", "skip", "agla", "agle", "forward", "अगला"),
            setOf("song", "track", "gaana", "gana", "गाना")
        )),
        IntentKeywords(CommandIntent.PREVIOUS_SONG, listOf(
            setOf("previous", "prev", "pichla", "pichle", "last", "पिछला"),
            setOf("song", "track", "gaana", "gana", "गाना")
        )),
        IntentKeywords(CommandIntent.STOP_MUSIC, listOf(
            setOf("stop", "band", "बंद"),
            setOf("music", "song", "gaana", "media", "playback", "गाना", "म्यूजिक")
        ), requireAll = true),

        // === SCREEN ===
        IntentKeywords(CommandIntent.READ_SCREEN, listOf(
            setOf("read", "padho", "suna", "पढ़ो", "सुना"),
            setOf("screen", "page", "display", "स्क्रीन", "पेज")
        ), requireAll = true),
        IntentKeywords(CommandIntent.DESCRIBE_SCREEN, listOf(
            setOf("describe", "explain", "detail", "samjhao", "batao", "बताओ", "समझाओ"),
            setOf("screen", "page", "display", "स्क्रीन", "पेज")
        ), requireAll = true),
        IntentKeywords(CommandIntent.LIST_CLICKABLE, listOf(
            setOf("clickable", "button", "buttons", "click", "tap", "option", "options", "list"),
            setOf("what", "show", "kya", "dikhao", "batao", "क्या", "दिखाओ", "बताओ")
        )),
        IntentKeywords(CommandIntent.TAKE_SCREENSHOT, listOf(
            setOf("screenshot", "screen", "capture", "snap", "स्क्रीनशॉट"),
            setOf("shot", "capture", "take", "lo", "lelo", "ले", "लो")
        )),

        // === BATTERY ===
        IntentKeywords(CommandIntent.READ_BATTERY, listOf(
            setOf("battery", "charge", "charging", "power", "बैटरी", "चार्ज")
        )),

        // === ROTATION ===
        IntentKeywords(CommandIntent.ROTATION_ON, listOf(
            setOf("rotation", "rotate", "auto-rotate", "orientation", "ghuma", "रोटेशन", "घुमा"),
            setOf("on", "chalu", "enable", "start", "चालू")
        ), requireAll = true),
        IntentKeywords(CommandIntent.ROTATION_OFF, listOf(
            setOf("rotation", "rotate", "auto-rotate", "orientation", "ghuma", "रोटेशन", "घुमा"),
            setOf("off", "band", "disable", "lock", "portrait", "बंद")
        ), requireAll = true),



        // === BRIGHTNESS ===
        IntentKeywords(CommandIntent.BRIGHTNESS_UP, listOf(
            setOf("brightness", "bright", "roshni", "light", "display", "रोशनी", "ब्राइटनेस"),
            setOf("up", "increase", "more", "badha", "badhao", "zyada", "high", "बढ़ाओ", "ज़्यादा")
        ), requireAll = true),
        IntentKeywords(CommandIntent.BRIGHTNESS_DOWN, listOf(
            setOf("brightness", "bright", "roshni", "light", "display", "रोशनी", "ब्राइटनेस"),
            setOf("down", "decrease", "less", "kam", "reduce", "low", "dhima", "कम", "धीमा")
        ), requireAll = true),
        IntentKeywords(CommandIntent.BRIGHTNESS_MAX, listOf(
            setOf("brightness", "bright", "roshni", "रोशनी", "ब्राइटनेस"),
            setOf("max", "maximum", "full", "पूरी", "फुल")
        ), requireAll = true),
        IntentKeywords(CommandIntent.BRIGHTNESS_MIN, listOf(
            setOf("brightness", "bright", "roshni", "रोशनी", "ब्राइटनेस"),
            setOf("min", "minimum", "dim", "lowest", "कम", "डिम")
        ), requireAll = true),
        IntentKeywords(CommandIntent.BRIGHTNESS_HALF, listOf(
            setOf("brightness", "bright", "roshni", "रोशनी", "ब्राइटनेस"),
            setOf("half", "medium", "middle", "50", "हाफ", "मीडियम")
        ), requireAll = true),

        // === ALARM & TIMER ===
        IntentKeywords(CommandIntent.SET_ALARM, listOf(
            setOf("alarm", "alaram", "wake", "अलार्म"),
            setOf("set", "laga", "lagao", "baja", "rakh", "लगाओ", "बजाओ", "रख")
        )),
        IntentKeywords(CommandIntent.SET_TIMER, listOf(
            setOf("timer", "countdown", "टाइमर"),
            setOf("set", "laga", "lagao", "start", "shuru", "rakh", "लगाओ", "शुरू", "रख")
        )),



        // === NOTIFICATIONS ===
        IntentKeywords(CommandIntent.CLEAR_NOTIFICATIONS, listOf(
            setOf("clear", "dismiss", "remove", "delete", "hata", "hatao", "mita", "साफ", "हटाओ", "मिटा"),
            setOf("notification", "notifications", "notif", "नोटिफिकेशन")
        ), requireAll = true),
        IntentKeywords(CommandIntent.READ_NOTIFICATIONS, listOf(
            setOf("read", "padho", "suna", "batao", "kya", "पढ़ो", "सुना", "बताओ", "क्या"),
            setOf("notification", "notifications", "notif", "नोटिफिकेशन")
        ), requireAll = true),

        // === INFO ===
        IntentKeywords(CommandIntent.TELL_TIME, listOf(
            setOf("time", "samay", "waqt", "baj", "baje", "टाइम", "समय", "वक़्त", "बज"),
            setOf("what", "tell", "kya", "bata", "kitne", "kitna", "क्या", "बता", "कितने")
        )),
        IntentKeywords(CommandIntent.TELL_DAY, listOf(
            setOf("day", "date", "din", "tarikh", "aaj", "दिन", "तारीख", "आज"),
            setOf("what", "tell", "kya", "bata", "today", "aaj", "क्या", "बता", "आज")
        )),
        IntentKeywords(CommandIntent.WHICH_APP, listOf(
            setOf("which", "what", "where", "kaunsa", "konsa", "kahan", "कौनसा", "कहां"),
            setOf("app", "application", "screen", "ऐप", "स्क्रीन")
        ), requireAll = true),
        IntentKeywords(CommandIntent.SHOW_HELP, listOf(
            setOf("help", "commands", "madad", "sahayata", "command", "मदद", "सहायता", "कमांड"),
            setOf("what", "show", "list", "kya", "dikhao", "batao", "क्या", "दिखाओ", "बताओ", "can")
        )),
        IntentKeywords(CommandIntent.REPEAT_LAST, listOf(
            setOf("repeat", "again", "dobara", "phir", "fir", "दोबारा", "फिर"),
            setOf("say", "bol", "suna", "batao", "that", "बोल", "सुना", "बताओ")
        )),

        // === DAILY LIFE ===
        IntentKeywords(CommandIntent.OPEN_WEATHER, listOf(
            setOf("weather", "mausam", "mosam", "temperature", "temp", "मौसम")
        )),
        IntentKeywords(CommandIntent.TAKE_PHOTO, listOf(
            setOf("photo", "picture", "selfie", "pic", "tasveer", "foto", "फोटो", "तस्वीर"),
            setOf("take", "click", "capture", "lo", "lelo", "khicho", "ले", "लो", "खींचो")
        )),
        IntentKeywords(CommandIntent.READ_CLIPBOARD, listOf(
            setOf("clipboard", "copied", "copy", "paste", "क्लिपबोर्ड"),
            setOf("read", "what", "kya", "padho", "batao", "पढ़ो", "बताओ")
        )),

        // === EMERGENCY ===
        IntentKeywords(CommandIntent.EMERGENCY, listOf(
            setOf("emergency", "help", "sos", "danger", "bachao", "madad", "इमरजेंसी", "बचाओ", "मदद", "911", "112")
        ), weight = 1.5f),
        IntentKeywords(CommandIntent.SEND_LOCATION, listOf(
            setOf("location", "gps", "jagah", "sthan", "लोकेशन", "जगह"),
            setOf("send", "share", "bhejo", "batao", "भेजो", "बताओ")
        ), requireAll = true),

        // === SEARCH ===
        IntentKeywords(CommandIntent.SEARCH_YOUTUBE, listOf(
            setOf("youtube", "yt", "यूट्यूब"),
            setOf("search", "find", "khojo", "dhundho", "dekho", "खोजो", "ढूंढो")
        ), requireAll = true),
        IntentKeywords(CommandIntent.SEARCH_GOOGLE, listOf(
            setOf("search", "google", "find", "khojo", "dhundho", "खोजो", "ढूंढो", "गूगल", "सर्च")
        )),

        // === CAMERA (VIDEO) ===
        IntentKeywords(CommandIntent.RECORD_VIDEO, listOf(
            setOf("record", "recording", "video", "वीडियो", "रेकॉर्ड"),
            setOf("start", "video", "record", "shuru", "chalu", "शुरू", "चालू")
        )),
        IntentKeywords(CommandIntent.STOP_RECORDING, listOf(
            setOf("stop", "end", "finish", "band", "khatam", "rok", "बंद", "खत्म", "रोक"),
            setOf("recording", "record", "video", "रेकॉर्डिंग", "वीडियो")
        ), requireAll = true)
    )

    // ==================== FILLER WORDS ====================

    private val fillerWords = setOf(
        // Hindi fillers
        "bhai", "yaar", "yar", "bro", "dost", "ji",
        "zara", "thoda", "thodi", "ek", "na", "toh", "to",
        "abhi", "jaldi", "please", "plz", "pls",
        "mera", "meri", "mere", "apna", "apni", "apne",
        "is", "isko", "usko", "us", "ye", "woh", "wo",
        "bhi", "aur", "se", "ke", "ki", "ka", "ko", "me", "mai", "mein",
        "hai", "he", "hain", "tha", "the", "thi",
        "karo", "kardo", "karna", "kariye", "kijiye",
        "do", "de", "dena", "dedo", "dijiye",
        // Hindi Devanagari fillers
        "भाई", "यार", "ज़रा", "थोड़ा", "एक", "ना", "तो",
        "अभी", "जल्दी", "मेरा", "मेरी", "अपना",
        "है", "हैं", "था", "थे", "थी",
        "करो", "करना", "कीजिए", "कर", "दो", "दे", "देना"
    )

    // Words that should NOT be stripped (they carry command meaning)
    private val protectedWords = setOf(
        "on", "off", "up", "down", "open", "close", "start", "stop",
        "call", "send", "read", "type", "click", "tap", "search",
        "home", "back", "lock", "unlock", "mute", "unmute",
        "play", "pause", "next", "skip", "alarm", "timer",
        "screenshot", "battery", "brightness", "volume", "wifi",
        "bluetooth", "flashlight", "torch", "hotspot", "weather",
        "help", "repeat", "emergency", "sos", "location",
        "chat", "exit", "leave", "notification", "screen"
    )

    // ==================== VERB NORMALIZATION ====================

    /** Normalize Hindi verb endings to base form */
    private fun normalizeVerbs(text: String): String {
        var result = text
        // Multi-word verb forms → single word
        result = result.replace("kar do", "karo")
        result = result.replace("kar de", "karo")
        result = result.replace("kar dena", "karo")
        result = result.replace("kar dey", "karo")
        result = result.replace("कर दो", "करो")
        result = result.replace("कर दे", "करो")

        // Single word verb normalizations
        val verbNormalizations = mapOf(
            "kardo" to "karo", "karna" to "karo", "kariye" to "karo",
            "kijiye" to "karo", "karunga" to "karo", "karenge" to "karo",
            "karde" to "karo", "kardena" to "karo",
            "dedo" to "do", "dena" to "do", "dijiye" to "do", "dey" to "do",
            "lagao" to "laga", "lagado" to "laga", "lagana" to "laga",
            "kholo" to "open", "kholna" to "open", "kholdo" to "open", "kholiye" to "open",
            "bajao" to "play", "bajana" to "play", "chalao" to "play", "chalana" to "play",
            "badhao" to "badha", "badhana" to "badha", "badhado" to "badha",
            "hatao" to "hata", "hatana" to "hata", "hatado" to "hata",
            "dikhao" to "dikhao", "dikhana" to "dikhao", "dikhado" to "dikhao",
            "padho" to "read", "padhna" to "read", "padho" to "read",
            "sunao" to "suna", "sunana" to "suna", "sunado" to "suna",
            "batao" to "bata", "batana" to "bata", "batado" to "bata",
            "bhejo" to "send", "bhejna" to "send", "bhejdo" to "send",
            "jala" to "on", "jalao" to "on", "jalado" to "on",
            "bujha" to "off", "bujhao" to "off", "bujhado" to "off"
        )

        val words = result.split(" ").toMutableList()
        for (i in words.indices) {
            verbNormalizations[words[i]]?.let { words[i] = it }
        }
        return words.joinToString(" ")
    }

    // ==================== CORE MATCHING ====================

    /**
     * Match a raw command string against all known intents.
     * Returns the best MatchResult or null if no confident match.
     * 
     * @param rawCommand The user's spoken command
     * @param currentAppName The package name or label of the currently active app (for context)
     */
    fun match(rawCommand: String, currentAppName: String = ""): MatchResult? {
        val cleaned = cleanInput(rawCommand)
        val words = cleaned.split(" ").filter { it.isNotBlank() }.toSet()

        if (words.isEmpty()) return null

        // --- STEP 1: EXACT PHRASE LOOKUP (instant 0ms) ---
        val normalized = rawCommand.trim().lowercase()
        val exactMatch = exactPhrases[normalized]
        if (exactMatch != null) {
            Log.i(TAG, "Exact phrase match: '$normalized' → $exactMatch")
            return MatchResult(exactMatch, 5.0f)
        }

        // --- STEP 2: CONTEXT-AWARE PRE-PROCESSING ---
        val lowerRaw = rawCommand.lowercase()
        val lowerApp = currentAppName.lowercase()
        
        // 1. "Go to [X]" -> Click [X]
        if (lowerRaw.startsWith("go to ")) {
            val target = lowerRaw.removePrefix("go to ").trim()
            if (target.isNotBlank()) {
                Log.i(TAG, "Context Match: 'Go to $target' -> CLICK_TARGET")
                return MatchResult(CommandIntent.CLICK_TARGET, 3.5f, target)
            }
        }
        
        // 2. "Search [X]" inside specific apps (YouTube, RVX, Insta, etc.)
        // If saying just "search X" while in YouTube, treat as YouTube search
        if (lowerRaw.startsWith("search ") && 
           (lowerApp.contains("youtube") || lowerApp.contains("rvx") || lowerApp.contains("vanced"))) {
             val query = lowerRaw.removePrefix("search ").trim()
             if (query.isNotBlank()) {
                 Log.i(TAG, "Context Match: Search in YouTube -> SEARCH_YOUTUBE")
                 return MatchResult(CommandIntent.SEARCH_YOUTUBE, 3.5f, query)
             }
        }
        
        // 3. "Search [X]" inside OTHER apps -> Click Search icon then type
        if (lowerRaw.startsWith("search ") && lowerApp.isNotEmpty() && 
            !lowerApp.contains("launcher") && !lowerApp.contains("home")) {
            // General in-app search command
             val query = lowerRaw.removePrefix("search ").trim()
             if (query.isNotBlank()) {
                 Log.i(TAG, "Context Match: General In-App Search -> CLICK_TARGET (Search)")
                 // We return a special "SEARCH_GOOGLE" intent but CommandProcessor will redirect it 
                 // to in-app search based on context.
             }
        }

        // Check for parameterized commands first
        val paramResult = checkParameterizedCommands(cleaned, rawCommand)
        if (paramResult != null) return paramResult

        // Score all intents
        var bestMatch: MatchResult? = null

        for (intentDef in intentDatabase) {
            val score = scoreIntent(words, intentDef)
            if (score >= MIN_SCORE && (bestMatch == null || score > bestMatch.score)) {
                bestMatch = MatchResult(intentDef.intent, score)
            }
        }

        if (bestMatch != null) {
            Log.i(TAG, "Matched: ${bestMatch.intent} (score=${bestMatch.score}) for '$rawCommand'")
        }

        return bestMatch
    }

    /** Clean and normalize input text.
     *  Protects parameter words after command verbs (call, open, etc.)
     *  so contact names and app names aren't stripped. */
    private fun cleanInput(raw: String): String {
        var text = raw.trim().lowercase()
        
        // CRITICAL: Detect command verbs BEFORE any transformations
        val commandVerbs = setOf("call", "dial", "phone", "open", "launch", "start", 
                                 "search", "type", "click", "tap", "run",
                                 "कॉल", "फोन", "चलाओ", "shuru", "kholo")
        val words = text.split(" ", "\t").filter { it.isNotBlank() }
        val verbIdx = words.indexOfFirst { it in commandVerbs }
        
        // If we found a command verb, split into [prefix, verb, parameter]
        if (verbIdx >= 0) {
            val prefix = words.subList(0, verbIdx)
            val verb = words[verbIdx]
            val parameter = words.subList(verbIdx + 1, words.size)
            
            // Clean prefix (strip fillers), keep verb, preserve parameter completely
            val cleanedPrefix = prefix.filter { it !in fillerWords || it in protectedWords }
            
            // Apply normalization ONLY to the prefix and verb, NOT the parameter
            val prefixAndVerb = (cleanedPrefix + listOf(verb)).joinToString(" ")
            val normalizedPrefixAndVerb = normalizeVerbs(stemWords(prefixAndVerb))
            val parameterStr = parameter.joinToString(" ")
            
            return if (parameterStr.isNotBlank()) {
                "$normalizedPrefixAndVerb $parameterStr"
            } else {
                normalizedPrefixAndVerb
            }
        }
        
        // No command verb found — apply full cleaning as before
        text = normalizeVerbs(text)
        text = stemWords(text)
        val filtered = words.filter { it !in fillerWords || it in protectedWords }
        return if (filtered.isEmpty()) text else filtered.joinToString(" ")
    }

    /** Simple word stemming to handle common tense/form variations.
     *  This helps match "unlocked" → "unlock", "calling" → "call", etc. */
    private fun stemWords(text: String): String {
        val words = text.split(" ")
        val stemmed = words.map { word ->
            when {
                // Past tense -ed
                word.endsWith("ed") && word.length > 4 -> {
                    val base = word.removeSuffix("ed")
                    // Handle doubled consonants: "stopped" → "stop"
                    if (base.length >= 3 && base[base.length-1] == base[base.length-2]) {
                        base.dropLast(1)
                    } else {
                        base
                    }
                }
                // Present continuous -ing
                word.endsWith("ing") && word.length > 5 -> {
                    val base = word.removeSuffix("ing")
                    // Handle doubled consonants: "running" → "run"
                    if (base.length >= 2 && base[base.length-1] == base[base.length-2]) {
                        base.dropLast(1)
                    } else {
                        base
                    }
                }
                // Plural -s
                word.endsWith("s") && word.length > 3 && !word.endsWith("ss") -> {
                    word.removeSuffix("s")
                }
                else -> word
            }
        }
        return stemmed.joinToString(" ")
    }

    /** Score a set of input words against an intent definition with fuzzy matching.
     *  Scoring per keyword match:
     *    - Exact word match → 1.5
     *    - Contains match   → 1.0
     *    - Levenshtein ≤ 1  → 0.8
     *    - Phonetic match   → 0.5
     *  Bonuses:
     *    - Short command (≤ 3 words) with score > 0  → +0.5
     *    - requireAll and ALL groups matched          → +1.0
     */
    private fun scoreIntent(inputWords: Set<String>, intentDef: IntentKeywords): Float {
        var totalScore = 0f
        var groupsMatched = 0

        for (group in intentDef.keywordGroups) {
            var groupScore = 0f
            for (keyword in group) {
                // Layer 1: Exact word match → 1.5
                if (keyword in inputWords) {
                    groupScore += 1.5f
                    continue
                }

                // Layer 2: Contains match → 1.0
                var containsMatched = false
                for (inputWord in inputWords) {
                    if (inputWord.length >= 4 && keyword.length >= 4) {
                        if (inputWord.contains(keyword) || keyword.contains(inputWord)) {
                            // Prevent opposite matching (lock/unlock, on/off)
                            val isOpposite = (inputWord == "lock" && keyword.startsWith("un")) ||
                                             (keyword == "lock" && inputWord.startsWith("un")) ||
                                             (inputWord == "mute" && keyword.startsWith("un")) ||
                                             (keyword == "mute" && inputWord.startsWith("un")) ||
                                             (inputWord == "do" && keyword.startsWith("un")) ||
                                             (keyword == "do" && inputWord.startsWith("un"))
                            if (!isOpposite) {
                                groupScore += 1.0f
                                containsMatched = true
                                break
                            }
                        }
                    }
                }

                // Layer 3: Levenshtein ≤ 1 → 0.8
                if (!containsMatched) {
                    var fuzzyMatched = false
                    for (inputWord in inputWords) {
                        if (inputWord.length >= 3 && keyword.length >= 3) {
                            val dist = levenshteinDistance(inputWord, keyword)
                            if (dist <= 1) {
                                groupScore += 0.8f
                                fuzzyMatched = true
                                break
                            }
                        }
                    }

                    // Layer 4: Phonetic match (first 2 consonants same) → 0.5
                    if (!fuzzyMatched) {
                        for (inputWord in inputWords) {
                            if (inputWord.length >= 4 && keyword.length >= 4) {
                                val p1 = getPhoneticCode(inputWord)
                                val p2 = getPhoneticCode(keyword)
                                if (p1 == p2) {
                                    groupScore += 0.5f
                                    break
                                }
                            }
                        }
                    }
                }
            }

            // If requireAll and this group has 0 score → total = 0, break
            if (intentDef.requireAll && groupScore == 0f) {
                return 0f
            }

            if (groupScore > 0f) {
                groupsMatched++
                totalScore += groupScore
            }
        }

        // Apply bonuses
        if (totalScore > 0f) {
            // Short command bonus: ≤ 3 words → +0.5
            if (inputWords.size <= 3) {
                totalScore += 0.5f
            }

            // requireAll bonus: all groups matched → +1.0
            if (intentDef.requireAll && groupsMatched == intentDef.keywordGroups.size) {
                totalScore += 1.0f
            }
        }

        return totalScore * intentDef.weight
    }

    /** Fuzzy matching using edit distance and phonetic similarity */
    private fun isFuzzyMatch(word1: String, word2: String): Boolean {
        // Skip very short words to avoid false positives
        if (word1.length < 3 || word2.length < 3) return false
        
        // CRITICAL: Don't match "lock" with "unlock" - they're opposites!
        val opposites = setOf(
            setOf("lock", "unlock"),
            setOf("locked", "unlocked"),
            setOf("locking", "unlocking"),
            setOf("on", "off"),
            setOf("enable", "disable"),
            setOf("start", "stop")
        )
        for (pair in opposites) {
            if ((word1 in pair && word2 in pair) && word1 != word2) {
                return false  // Don't fuzzy match opposites
            }
        }
        
        // Calculate edit distance (Levenshtein)
        val distance = levenshteinDistance(word1, word2)
        val maxLen = maxOf(word1.length, word2.length)
        
        // Allow 1-2 character differences for words 4+ chars
        val threshold = when {
            maxLen <= 4 -> 1
            maxLen <= 6 -> 2
            else -> 3
        }
        
        if (distance <= threshold) return true
        
        // Phonetic similarity (simple Soundex-like)
        if (word1.length >= 4 && word2.length >= 4) {
            val phonetic1 = getPhoneticCode(word1)
            val phonetic2 = getPhoneticCode(word2)
            if (phonetic1 == phonetic2) return true
        }
        
        return false
    }

    /** Calculate Levenshtein distance (edit distance) between two strings */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j
        
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        
        return dp[len1][len2]
    }

    /** Simple phonetic encoding (Soundex-like) for fuzzy matching */
    private fun getPhoneticCode(word: String): String {
        if (word.isEmpty()) return ""
        
        val normalized = word.lowercase()
        val result = StringBuilder()
        
        // Keep first letter
        result.append(normalized[0])
        
        // Encode consonants
        val phoneticMap = mapOf(
            'b' to '1', 'f' to '1', 'p' to '1', 'v' to '1',
            'c' to '2', 'g' to '2', 'j' to '2', 'k' to '2', 'q' to '2', 's' to '2', 'x' to '2', 'z' to '2',
            'd' to '3', 't' to '3',
            'l' to '4',
            'm' to '5', 'n' to '5',
            'r' to '6'
        )
        
        var prevCode = phoneticMap[normalized[0]] ?: '0'
        
        for (i in 1 until normalized.length) {
            val char = normalized[i]
            val code = phoneticMap[char] ?: '0'
            
            // Skip vowels and repeated codes
            if (code != '0' && code != prevCode) {
                result.append(code)
                prevCode = code
            }
        }
        
        // Pad or truncate to 4 characters
        return result.toString().padEnd(4, '0').take(4)
    }

    // ==================== PARAMETERIZED COMMANDS ====================

    /** Check for commands that extract a parameter (app name, contact, text, etc.) */
    private fun checkParameterizedCommands(cleaned: String, raw: String): MatchResult? {
        Log.d(TAG, "checkParameterizedCommands: cleaned='$cleaned', raw='$raw'")
        
        // --- OPEN APP ---
        // "open xyz", "xyz kholo", "xyz open karo", "launch xyz"
        Log.d(TAG, "Testing OPEN_APP patterns...")
        val openPatterns = listOf(
            Regex("^(?:open|launch|start|run|चलाओ|shuru)\\s+(.+)"),
            Regex("^(.+?)\\s+(?:kholo|kholdo|kholna|kholiye|खोलो|open|chalu|chalao)$"),
            Regex("^(.+?)\\s+(?:colour|color|follow|polo|cola|call\\s*oh|hello)$") // Offline misrecognitions of "kholo"
        )
        for ((index, pattern) in openPatterns.withIndex()) {
            pattern.find(cleaned)?.let { match ->
                val appName = match.groupValues[1].trim()
                if (appName.isNotBlank() && appName.length > 1) {
                    Log.i(TAG, "OPEN_APP pattern $index matched: appName='$appName'")
                    return MatchResult(CommandIntent.OPEN_APP, 3.0f, appName)
                }
            }
        }

        // --- CALL CONTACT ---
        // "call xyz", "xyz ko call karo", "xyz ko phone karo"
        Log.d(TAG, "Testing CALL_CONTACT patterns...")
        val callPatterns = listOf(
            Regex("^(?:call|dial|phone|कॉल|फोन)\\s+(.+)"),
            Regex("^(.+?)\\s+(?:ko\\s*call|ko\\s*phone|को\\s*कॉल|को\\s*फोन)"),
            Regex("^(?:call\\s*karo|कॉल\\s*करो)\\s+(.+)")
        )
        for ((index, pattern) in callPatterns.withIndex()) {
            pattern.find(cleaned)?.let { match ->
                val contact = match.groupValues[1].replace(Regex("\\s*(karo|करो)$"), "").trim()
                if (contact.isNotBlank()) {
                    Log.i(TAG, "CALL_CONTACT pattern $index matched: contact='$contact'")
                    return MatchResult(CommandIntent.CALL_CONTACT, 3.0f, contact)
                }
            }
        }

        // --- TYPE TEXT ---
        // "type xyz", "likho xyz", "लिखो xyz"
        val typePatterns = listOf(
            Regex("^(?:type|write|likho|लिखो)\\s+(.+)")
        )
        for (pattern in typePatterns) {
            pattern.find(cleaned)?.let { match ->
                // Use raw text to preserve original casing
                val text = raw.trim().substring(raw.trim().indexOf(' ') + 1)
                return MatchResult(CommandIntent.TYPE_TEXT, 3.0f, text)
            }
        }

        // --- CLICK/TAP ---
        // "click xyz", "tap xyz", "press xyz"
        val clickPatterns = listOf(
            Regex("^(?:click|tap|press|dabao|दबाओ)\\s+(.+)")
        )
        for (pattern in clickPatterns) {
            pattern.find(cleaned)?.let { match ->
                val target = match.groupValues[1].trim()
                if (target.isNotBlank()) {
                    return MatchResult(CommandIntent.CLICK_TARGET, 3.0f, target)
                }
            }
        }

        // --- SELECT ITEM ---
        // "select item 3", "play number 2"
        val selectPatterns = listOf(
            Regex("(?:select|choose|play).*(?:item|number)\\s*(\\d+)"),
            Regex("(?:item|number)\\s*(\\d+)\\s*(?:select|choose|play|bajao|chalao)")
        )
        for (pattern in selectPatterns) {
            pattern.find(cleaned)?.let { match ->
                return MatchResult(CommandIntent.SELECT_ITEM, 3.0f, match.groupValues[1])
            }
        }

        // --- SEARCH YOUTUBE (with query) ---
        if (cleaned.contains("youtube") || cleaned.contains("yt")) {
            val searchWords = setOf("search", "find", "khojo", "dhundho", "dekho", "खोजो", "ढूंढो")
            if (searchWords.any { cleaned.contains(it) } || cleaned.contains("youtube")) {
                val query = cleaned
                    .replace("youtube", "").replace("yt", "")
                    .replace("search", "").replace("find", "")
                    .replace("khojo", "").replace("dhundho", "")
                    .replace("on", "").replace("for", "").replace("par", "")
                    .replace("pe", "").replace("mein", "").replace("me", "")
                    .trim()
                if (query.isNotBlank()) {
                    return MatchResult(CommandIntent.SEARCH_YOUTUBE, 3.0f, query)
                }
                return MatchResult(CommandIntent.SEARCH_YOUTUBE, 3.0f, "")
            }
        }

        // --- SEARCH GOOGLE (with query) ---
        val googlePatterns = listOf(
            Regex("^(?:search|google|find|khojo|dhundho|सर्च|खोजो|ढूंढो)\\s+(?:for\\s+|on\\s+google\\s+)?(.+)"),
            Regex("^(.+?)\\s+(?:search|google)\\s*(?:karo|करो)?$")
        )
        for (pattern in googlePatterns) {
            pattern.find(cleaned)?.let { match ->
                val query = match.groupValues[1]
                    .replace("google", "").replace("search", "")
                    .replace("for", "").replace("on", "")
                    .replace("karo", "").replace("करो", "")
                    .trim()
                if (query.isNotBlank() && !query.matches(Regex("^(google|search|karo|on|for)$"))) {
                    return MatchResult(CommandIntent.SEARCH_GOOGLE, 3.0f, query)
                }
            }
        }

        // --- SEND SMS ---
        val smsPatterns = listOf(
            Regex("^(?:send\\s+message\\s+to|sms)\\s+(.+)"),
            Regex("^(?:message\\s+bhejo|मैसेज\\s+भेजो)\\s+(.+)")
        )
        for (pattern in smsPatterns) {
            pattern.find(cleaned)?.let { match ->
                return MatchResult(CommandIntent.SEND_SMS, 3.0f, match.groupValues[1].trim())
            }
        }

        // --- SEND WHATSAPP ---
        val waPatterns = listOf(
            Regex("^(?:send\\s+whatsapp\\s+to|whatsapp\\s+message\\s+to)\\s+(.+)"),
            Regex("^(?:whatsapp\\s+bhejo|whatsapp\\s+karo)\\s+(.+)")
        )
        for (pattern in waPatterns) {
            pattern.find(cleaned)?.let { match ->
                return MatchResult(CommandIntent.SEND_WHATSAPP, 3.0f, match.groupValues[1].trim())
            }
        }

        // --- CHAT MODE ---
        val chatPatterns = listOf(
            Regex("^chat\\s+(.+)"),
            Regex("^(?:message|msg)\\s+(?:on\\s+)?(?:whatsapp|instagram|insta|facebook|telegram|twitter)\\s*(.*)"),
        )
        for (pattern in chatPatterns) {
            pattern.find(cleaned)?.let { match ->
                return MatchResult(CommandIntent.ENTER_CHAT, 3.0f, cleaned)
            }
        }

        // --- SEND in chat mode ---
        // IMPORTANT: Only match SEND_CHAT_MSG for generic "send" — NOT for
        // "send message to", "send whatsapp", "send location" etc.
        val sendExclusions = listOf("send message", "send sms", "send whatsapp", "send location",
            "send email", "send mail", "bhejo message", "message bhejo")
        if ((cleaned.startsWith("send ") || cleaned.startsWith("bhejo ")) &&
            sendExclusions.none { cleaned.startsWith(it) }) {
            val msg = raw.trim().let {
                val idx = it.indexOf(' ')
                if (idx >= 0) it.substring(idx + 1) else ""
            }
            if (msg.isNotBlank()) {
                return MatchResult(CommandIntent.SEND_CHAT_MSG, 3.0f, msg)
            }
        }

        // --- PLAY SONG ---
        val songPatterns = listOf(
            Regex("^(?:play\\s+song|play\\s+music|bajao|chalao)\\s+(.+)"),
            Regex("^(.+?)\\s+(?:song|gaana|gana)\\s*(?:bajao|chalao|play)?$")
        )
        for (pattern in songPatterns) {
            pattern.find(cleaned)?.let { match ->
                val songName = match.groupValues[1].trim()
                if (songName.isNotBlank() && songName != "music" && songName != "song") {
                    return MatchResult(CommandIntent.PLAY_SONG, 3.0f, songName)
                }
            }
        }

        // --- ALARM with time ---
        val alarmPatterns = listOf(
            Regex("(?:alarm|alaram|अलार्म).*?(\\d.*)"),
            Regex("(?:set\\s+alarm|alarm\\s+set|alarm\\s+laga).*?(\\d.*)")
        )
        for (pattern in alarmPatterns) {
            pattern.find(cleaned)?.let { match ->
                return MatchResult(CommandIntent.SET_ALARM, 3.0f, match.groupValues[1].trim())
            }
        }

        // --- TIMER with duration ---
        val timerPatterns = listOf(
            Regex("(?:timer|टाइमर).*?(\\d.*)"),
            Regex("(?:set\\s+timer|timer\\s+set|timer\\s+laga).*?(\\d.*)")
        )
        for (pattern in timerPatterns) {
            pattern.find(cleaned)?.let { match ->
                return MatchResult(CommandIntent.SET_TIMER, 3.0f, match.groupValues[1].trim())
            }
        }

        Log.d(TAG, "checkParameterizedCommands: no patterns matched")
        return null
    }

    // ==================== SINGLE-WORD SHORTCUTS ====================

    /**
     * For single-word inputs, check if the word uniquely maps to one intent.
     * Called from match() before the full scoring.
     */
    private val singleWordShortcuts = mapOf(
        "torch" to CommandIntent.FLASHLIGHT_ON,
        "flashlight" to CommandIntent.FLASHLIGHT_ON,
        "flash" to CommandIntent.FLASHLIGHT_ON,
        "टॉर्च" to CommandIntent.FLASHLIGHT_ON,
        "screenshot" to CommandIntent.TAKE_SCREENSHOT,
        "स्क्रीनशॉट" to CommandIntent.TAKE_SCREENSHOT,
        "battery" to CommandIntent.READ_BATTERY,
        "बैटरी" to CommandIntent.READ_BATTERY,
        "home" to CommandIntent.GO_HOME,
        "होम" to CommandIntent.GO_HOME,
        "back" to CommandIntent.GO_BACK,
        "पीछे" to CommandIntent.GO_BACK,
        "recents" to CommandIntent.OPEN_RECENTS,
        "notifications" to CommandIntent.OPEN_NOTIFICATIONS,
        "hotspot" to CommandIntent.HOTSPOT_ON,
        "हॉटस्पॉट" to CommandIntent.HOTSPOT_ON,
        "weather" to CommandIntent.OPEN_WEATHER,
        "मौसम" to CommandIntent.OPEN_WEATHER,
        "help" to CommandIntent.SHOW_HELP,
        "मदद" to CommandIntent.SHOW_HELP,
        "repeat" to CommandIntent.REPEAT_LAST,
        "mute" to CommandIntent.MUTE,
        "unmute" to CommandIntent.UNMUTE,
        "speaker" to CommandIntent.TOGGLE_SPEAKER,
        "pause" to CommandIntent.PAUSE_MUSIC,
        "resume" to CommandIntent.PLAY_MUSIC,
        "skip" to CommandIntent.NEXT_SONG,
        "emergency" to CommandIntent.EMERGENCY,
        "sos" to CommandIntent.EMERGENCY,
        "lock" to CommandIntent.LOCK_PHONE,
        "unlock" to CommandIntent.UNLOCK_PHONE,
        // Stop ALL — single word stops everything
        "stop" to CommandIntent.STOP_ALL,
        "cancel" to CommandIntent.STOP_ALL,
        "ruko" to CommandIntent.STOP_ALL,
        "रुको" to CommandIntent.STOP_ALL,
        "bas" to CommandIntent.STOP_ALL,
        "बस" to CommandIntent.STOP_ALL,
        "chup" to CommandIntent.STOP_ALL,
        "चुप" to CommandIntent.STOP_ALL,
        "ruk" to CommandIntent.STOP_ALL,
        "रुक" to CommandIntent.STOP_ALL
    )

    /**
     * Extended match — tries single-word shortcut first, then full scoring.
     * This is the method CommandProcessor should call.
     */
    fun matchCommand(rawCommand: String): MatchResult? {
        val cleaned = rawCommand.trim().lowercase()

        // Try single-word shortcut
        if (!cleaned.contains(" ")) {
            singleWordShortcuts[cleaned]?.let {
                Log.i(TAG, "Single-word shortcut: '$cleaned' → $it")
                return MatchResult(it, 5.0f)
            }
        }

        // Full match
        return match(rawCommand)
    }

    // ==================== CONTEXT-AWARE MATCHING ====================

    /**
     * Match a command with navigation context for disambiguation.
     * 
     * This method uses the current navigation context (app, screen, focused user)
     * to disambiguate commands that could have multiple meanings.
     * 
     * Requirements: 8.1, 8.2, 8.3, 8.4, 8.9
     * 
     * @param rawCommand The raw command string from voice recognition
     * @param context The current navigation context
     * @return The best MatchResult or null if no confident match
     */
    fun matchWithContext(
        rawCommand: String,
        context: NavigationContextTracker.NavigationContext
    ): MatchResult? {
        Log.d(TAG, "matchWithContext: command='$rawCommand', app=${context.currentApp}, screen=${context.currentScreen}")
        
        // First try standard matching
        val standardMatch = matchCommand(rawCommand)
        
        // Check if we need context-based disambiguation
        val cleaned = cleanInput(rawCommand)
        val disambiguated = disambiguateWithContext(cleaned, context, standardMatch)
        
        if (disambiguated != null) {
            Log.i(TAG, "Context-based disambiguation: ${disambiguated.intent} (score=${disambiguated.score})")
            return disambiguated
        }
        
        // Check learned corrections
        val learned = checkLearnedCorrections(cleaned)
        if (learned != null && (standardMatch == null || learned.score > standardMatch.score)) {
            Log.i(TAG, "Using learned correction: ${learned.intent} (score=${learned.score})")
            return learned
        }
        
        return standardMatch
    }

    /**
     * Disambiguate commands based on navigation context.
     * 
     * Examples:
     * - "open" in Instagram profile screen → OPEN_PROFILE not OPEN_APP
     * - "scroll" in Instagram → SCROLL_FEED vs SCROLL_PHOTOS based on screen
     * 
     * Requirements: 8.3, 8.4
     */
    private fun disambiguateWithContext(
        cleaned: String,
        context: NavigationContextTracker.NavigationContext,
        standardMatch: MatchResult?
    ): MatchResult? {
        val app = context.currentApp?.lowercase()
        val screen = context.currentScreen?.lowercase()
        
        // Instagram-specific disambiguation
        if (app == "instagram" || app == "insta") {
            // "open" command disambiguation
            if (cleaned.contains("open") && !cleaned.contains("app")) {
                when (screen) {
                    "profile", "user profile" -> {
                        // "open" in profile screen likely means open profile
                        return MatchResult(CommandIntent.OPEN_APP, 4.0f, "profile")
                    }
                    "search", "search results" -> {
                        // "open" in search likely means open selected result
                        return MatchResult(CommandIntent.CLICK_TARGET, 4.0f, "first result")
                    }
                }
            }
            
            // "scroll" command disambiguation
            if (cleaned.contains("scroll")) {
                when (screen) {
                    "profile", "user profile" -> {
                        if (cleaned.contains("photo") || cleaned.contains("pic")) {
                            return MatchResult(CommandIntent.SCROLL_DOWN, 4.0f, "photos")
                        }
                    }
                    "reels" -> {
                        return MatchResult(CommandIntent.SCROLL_DOWN, 4.0f, "reels")
                    }
                    "stories" -> {
                        return MatchResult(CommandIntent.SWIPE_LEFT, 4.0f, "stories")
                    }
                }
            }
        }
        
        // WhatsApp-specific disambiguation
        if (app == "whatsapp" || app == "whats app") {
            if (cleaned.contains("open") && !cleaned.contains("app")) {
                when (screen) {
                    "chat list", "chats" -> {
                        return MatchResult(CommandIntent.CLICK_TARGET, 4.0f, "first chat")
                    }
                    "status" -> {
                        return MatchResult(CommandIntent.CLICK_TARGET, 4.0f, "first status")
                    }
                }
            }
        }
        
        // YouTube-specific disambiguation
        if (app == "youtube" || app == "yt") {
            if (cleaned.contains("play")) {
                when (screen) {
                    "search results" -> {
                        return MatchResult(CommandIntent.CLICK_TARGET, 4.0f, "first video")
                    }
                    "video player" -> {
                        return MatchResult(CommandIntent.PLAY_MUSIC, 4.0f)
                    }
                }
            }
        }
        
        return null
    }

    // ==================== PRONUNCIATION VARIATIONS ====================

    /**
     * Expanded pronunciation variation database.
     * Maps common app names and commands to their variations.
     * 
     * Requirements: 8.1, 8.2
     */
    private val pronunciationVariations = mapOf(
        // Social media apps
        "instagram" to setOf("insta", "instagram", "insta gram", "instagram app", "instgram", "instagaram"),
        "whatsapp" to setOf("whatsapp", "whats app", "watsapp", "what's app", "whatsup", "wassup", "वाट्सएप"),
        "facebook" to setOf("facebook", "fb", "face book", "फेसबुक"),
        "twitter" to setOf("twitter", "x", "tweet", "ट्विटर"),
        "youtube" to setOf("youtube", "yt", "you tube", "यूट्यूब"),
        "telegram" to setOf("telegram", "tg", "टेलीग्राम"),
        "snapchat" to setOf("snapchat", "snap", "स्नैपचैट"),
        
        // Communication apps
        "gmail" to setOf("gmail", "g mail", "email", "mail", "जीमेल"),
        "phone" to setOf("phone", "dialer", "call", "फोन", "कॉल"),
        "messages" to setOf("messages", "sms", "text", "msg", "मैसेज"),
        
        // Media apps
        "spotify" to setOf("spotify", "spot", "स्पॉटिफाई"),
        "netflix" to setOf("netflix", "नेटफ्लिक्स"),
        "amazon" to setOf("amazon", "prime", "अमेज़न"),
        
        // Utility apps
        "camera" to setOf("camera", "cam", "photo", "कैमरा", "फोटो"),
        "gallery" to setOf("gallery", "photos", "pics", "गैलरी"),
        "settings" to setOf("settings", "setting", "सेटिंग"),
        "chrome" to setOf("chrome", "browser", "क्रोम"),
        "maps" to setOf("maps", "google maps", "navigation", "मैप्स"),
        "calendar" to setOf("calendar", "cal", "कैलेंडर"),
        "clock" to setOf("clock", "alarm", "timer", "घड़ी", "अलार्म"),
        
        // Commands
        "open" to setOf("open", "launch", "start", "run", "kholo", "खोलो", "chalu", "chalao"),
        "close" to setOf("close", "exit", "quit", "band", "बंद"),
        "search" to setOf("search", "find", "khojo", "dhundho", "खोजो", "ढूंढो"),
        "call" to setOf("call", "dial", "phone", "कॉल", "फोन"),
        "message" to setOf("message", "msg", "text", "sms", "मैसेज"),
        "scroll" to setOf("scroll", "swipe", "slide", "स्क्रॉल"),
        "back" to setOf("back", "peeche", "peche", "piche", "wapas", "पीछे", "वापस"),
        "home" to setOf("home", "ghar", "होम", "घर")
    )

    /**
     * Check if a word matches any pronunciation variation.
     * 
     * @param word The word to check
     * @param canonical The canonical form to match against
     * @return true if the word is a variation of the canonical form
     */
    private fun matchesPronunciationVariation(word: String, canonical: String): Boolean {
        val variations = pronunciationVariations[canonical.lowercase()] ?: return false
        return variations.any { variation ->
            word.lowercase() == variation || 
            isFuzzyMatch(word.lowercase(), variation) ||
            word.lowercase().contains(variation) ||
            variation.contains(word.lowercase())
        }
    }

    /**
     * Get confidence score for a match considering pronunciation variations.
     * 
     * Requirements: 8.10
     * 
     * @param input The input command string
     * @param intent The command intent to score
     * @return Confidence score (0.0 to 1.0)
     */
    fun getConfidenceScore(input: String, intent: CommandIntent): Float {
        val result = match(input)
        if (result == null || result.intent != intent) {
            return 0.0f
        }
        
        // Normalize score to 0.0-1.0 range
        // MIN_SCORE is 2.5, typical max is around 10.0
        val normalized = (result.score - MIN_SCORE) / (10.0f - MIN_SCORE)
        return normalized.coerceIn(0.0f, 1.0f)
    }

    // ==================== LEARNING SYSTEM ====================

    /**
     * Storage for learned corrections.
     * Maps input patterns to their correct intents.
     * 
     * Requirements: 8.9
     */
    private val learnedCorrections = mutableMapOf<String, Pair<CommandIntent, Float>>()

    /**
     * Learn from a user correction.
     * 
     * When the system mismatches a command and the user corrects it,
     * this method stores the correction for future reference.
     * 
     * Requirements: 8.9
     * 
     * @param input The original input that was mismatched
     * @param correctIntent The correct intent provided by the user
     */
    fun learnFromCorrection(input: String, correctIntent: CommandIntent) {
        val normalized = cleanInput(input)
        val currentScore = learnedCorrections[normalized]?.second ?: 0.0f
        
        // Increase confidence with each correction (up to 5.0)
        val newScore = (currentScore + 1.0f).coerceAtMost(5.0f)
        
        learnedCorrections[normalized] = Pair(correctIntent, newScore)
        Log.i(TAG, "Learned correction: '$normalized' → $correctIntent (score=$newScore)")
    }

    /**
     * Check if we have a learned correction for this input.
     * 
     * @param cleaned The cleaned input string
     * @return MatchResult if a learned correction exists, null otherwise
     */
    private fun checkLearnedCorrections(cleaned: String): MatchResult? {
        val learned = learnedCorrections[cleaned]
        if (learned != null) {
            val (intent, score) = learned
            return MatchResult(intent, score + 3.0f) // Boost learned corrections
        }
        
        // Check for fuzzy matches in learned corrections
        for ((pattern, intentScore) in learnedCorrections) {
            if (isFuzzyMatch(cleaned, pattern)) {
                val (intent, score) = intentScore
                return MatchResult(intent, score + 2.0f) // Slightly lower boost for fuzzy
            }
        }
        
        return null
    }

    /**
     * Clear all learned corrections.
     * Useful for testing or resetting the learning system.
     */
    fun clearLearnedCorrections() {
        learnedCorrections.clear()
        Log.i(TAG, "Cleared all learned corrections")
    }

    /**
     * Get the number of learned corrections.
     * 
     * @return The count of learned corrections
     */
    fun getLearnedCorrectionsCount(): Int {
        return learnedCorrections.size
    }

    /**
     * Add a synonym for a command.
     * 
     * This allows manual addition of synonyms to improve matching.
     * 
     * Requirements: 8.1, 8.2
     * 
     * @param canonical The canonical command form
     * @param variation The variation to add
     */
    fun addSynonym(canonical: String, variation: String) {
        val normalizedCanonical = canonical.lowercase()
        val normalizedVariation = variation.lowercase()
        
        // Find the intent that matches the canonical form
        val canonicalMatch = match(canonical)
        if (canonicalMatch != null) {
            // Learn this as a correction
            learnFromCorrection(variation, canonicalMatch.intent)
            Log.i(TAG, "Added synonym: '$variation' → ${canonicalMatch.intent}")
        } else {
            Log.w(TAG, "Cannot add synonym: canonical form '$canonical' not recognized")
        }
    }
}
