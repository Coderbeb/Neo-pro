Neo - Complete Implementation Plan
Table of Contents
Project Setup
Architecture Overview
UI Implementation
Accessibility Service
Permission System
Foreground Service
Voice Engine (SpeechRecognizer)
Text-to-Speech Engine
State Machine
Command Brain (6-Phase Engine)
Action Executor
Hardware Controls
App Control System
Screen Reading
Phone Calls and Contacts
SMS System
Media Control
Utilities
Navigation
Notification System
Chat Monitoring
Security Vault
Gemini Background Learning
Database Design
Logs Feature
First Time Setup Flow
Error Handling
Testing Plan
File Structure
Development Tools and Recommendations
1. Project Setup
Project Configuration
Language: 100% Kotlin
Minimum SDK: API 26 (Android 8.0)
Target SDK: API 34 (Android 14)
Build System: Gradle with Kotlin DSL
Architecture Pattern: MVVM with Clean Architecture principles
Dependency Injection: Hilt
Database: Room
Background Work: Kotlin Coroutines and WorkManager
Network: Retrofit with OkHttp (only for Gemini API)
No Jetpack Compose — use traditional XML layouts for simplicity and reliability
Dependencies Required
Hilt for dependency injection
Room for local database
Kotlin Coroutines for async operations
WorkManager for background Gemini tasks
Retrofit and OkHttp for Gemini API calls
Google Gson for JSON parsing
AndroidX Core KTX
AndroidX Lifecycle (ViewModel, LiveData, Service)
AndroidX Activity KTX for permission handling
Material Design Components for UI
Manifest Declarations
The AndroidManifest.xml must declare:

MainActivity (the single screen UI)
NeoForegroundService (the brain service) with foregroundServiceType including microphone
NeoAccessibilityService with its meta-data pointing to accessibility config XML
NeoNotificationListenerService
All permissions listed in Section 5
2. Architecture Overview
Component Diagram
text

┌─────────────────────────────────────────────────┐
│                  MainActivity                     │
│  (4 Buttons + Logs Screen)                       │
│  - Enable Accessibility                          │
│  - Grant Permissions                             │
│  - Start Voice Control                           │
│  - Stop All Services                             │
│  - Logs View                                     │
└──────────────────┬──────────────────────────────┘
                   │ starts/stops
                   ▼
┌─────────────────────────────────────────────────┐
│            NeoForegroundService                   │
│  ┌──────────────┐  ┌──────────────────────┐     │
│  │ SpeechRecognizer│  │   TTS Manager       │     │
│  │ Manager       │  │                      │     │
│  └──────┬───────┘  └──────────┬───────────┘     │
│         │                      │                  │
│  ┌──────▼──────────────────────▼───────────┐     │
│  │          State Machine                    │     │
│  │  SLEEPING → ACTIVE → EXECUTING →          │     │
│  │  WAITING_RESPONSE                         │     │
│  └──────────────┬───────────────────────────┘     │
│                 │                                  │
│  ┌──────────────▼───────────────────────────┐     │
│  │         Command Brain                      │     │
│  │  Phase 1: Pre-processing                   │     │
│  │  Phase 2: Synonym Lookup                   │     │
│  │  Phase 3: Pattern Matching                 │     │
│  │  Phase 4: Learned Memory                   │     │
│  │  Phase 5: Fuzzy Matching                   │     │
│  │  Phase 6: Context Resolver                 │     │
│  └──────────────┬───────────────────────────┘     │
│                 │                                  │
│  ┌──────────────▼───────────────────────────┐     │
│  │        Action Executor                     │     │
│  │  - Hardware Controls                       │     │
│  │  - Intent Launcher                         │     │
│  │  - Accessibility Bridge                    │     │
│  └──────────────────────────────────────────┘     │
│                                                    │
│  ┌──────────────────────────────────────────┐     │
│  │     Gemini Background Worker               │     │
│  │  (WorkManager - async learning)            │     │
│  └──────────────────────────────────────────┘     │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│         NeoAccessibilityService                   │
│  - Receives all screen events                    │
│  - Traverses UI trees                            │
│  - Performs click/type/scroll actions             │
│  - Communicates via shared singleton bridge       │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│       NeoNotificationListenerService              │
│  - Receives all notifications                    │
│  - Filters relevant ones                         │
│  - Forwards to foreground service                │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│              Room Database                        │
│  - synonyms_table                                │
│  - learned_commands_table                        │
│  - failed_commands_queue                         │
│  - command_history                               │
│  - contact_aliases                               │
│  - user_settings                                 │
│  - app_profiles                                  │
│  - logs_table                                    │
└─────────────────────────────────────────────────┘
Communication Between Components
MainActivity communicates with ForegroundService via bound service connection and intents
ForegroundService communicates with AccessibilityService via a shared singleton object called AccessibilityBridge
AccessibilityBridge holds two queues — action requests (from service to accessibility) and event data (from accessibility to service)
NotificationListenerService communicates with ForegroundService via LocalBroadcastManager or shared singleton
All database access happens through Repository classes injected via Hilt
3. UI Implementation
Single Activity with Single Screen
The app has ONE activity and ONE screen. No fragments. No navigation. Just a vertical scrollable layout.

Layout Structure
Top Section — App Header

App icon and name "Neo" in large bold text
Tagline "Voice Assistant for Visually Impaired" in smaller text
Status indicator — a colored circle (red/yellow/green/grey) with status text next to it
Grey dot + "Setup Incomplete" — when accessibility or permissions are pending
Red dot + "Neo is not running" — when services are stopped
Yellow dot + "Neo is sleeping" — when service is running and waiting for wake word
Green dot + "Neo is active" — when Neo heard wake word and is processing commands
Middle Section — 4 Action Buttons

Button 1: Enable Accessibility Service

Large button with an icon (accessibility icon)
Text: "Enable Accessibility Service"
Below button: status text showing "Not Enabled" (red) or "Enabled ✓" (green)
Tap action: fires intent to Settings.ACTION_ACCESSIBILITY_SETTINGS
On return to app (onResume): checks if NeoAccessibilityService is enabled and updates status
When enabled: button becomes greyed out with checkmark, not clickable
Button 2: Grant All Permissions

Large button with permissions icon
Text: "Grant All Permissions"
Below button: status text showing "X/Y Permissions Granted" or "All Permissions Granted ✓"
Tap action: starts sequential permission request flow (described in Section 5)
After each permission result: updates the counter
When all granted: button becomes greyed out with checkmark
Button 3: Start Voice Control

Large button with microphone icon, different color (primary action color)
Text: "Start Voice Control"
This button is DISABLED until Button 1 and Button 2 are both completed
When disabled: greyed out, shows tooltip "Complete setup first"
Tap action: starts NeoForegroundService
After starting: button text changes to "Voice Control Active" with pulsing animation
Tapping again while active: manually activates Neo (same as saying wake word)
Button 4: Stop All Services

Large button with stop icon, red color
Text: "Stop All Services"
This button is DISABLED when services are not running
Tap action: stops NeoForegroundService, destroys SpeechRecognizer, shuts down TTS
After stopping: button disables, Button 3 returns to "Start Voice Control" state
Bottom Section — Logs

Button 5 / Section: Logs

This could be a expandable section or a button that scrolls down to reveal logs
Shows real-time app information in a scrollable list
Each log entry has a timestamp and message
Log categories and what they show:
System Logs:

"Accessibility Service: Enabled/Disabled"
"Foreground Service: Running/Stopped"
"Notification Listener: Active/Inactive"
"SpeechRecognizer: Listening/Stopped/Error"
"TTS Engine: Ready/Not Initialized"
Data Logs:

"Contacts Found: [number]" — shows how many contacts were loaded (only if permission granted)
"Installed Apps: [number]" — shows total apps detected on device
"Synonym Database: [number] entries loaded"
"Learned Commands: [number] mappings"
"Failed Commands in Queue: [number]"
"Command History: [number] commands processed"
Runtime Logs:

Every voice command recognized: "Heard: [text]"
Every command resolved: "Command: [action_name]"
Every action executed: "Executed: [action] - Success/Failed"
Every TTS output: "Spoke: [response text]"
State changes: "State: SLEEPING → ACTIVE"
Errors: "Error: [description]"
Logs Implementation Details:

Logs are stored in a Room database table called logs_table
Each log has: id, timestamp, category (SYSTEM/DATA/RUNTIME/ERROR), message, level (INFO/WARNING/ERROR)
The UI observes this table via LiveData
New logs appear at the top (reverse chronological)
Maximum 500 logs stored, older ones auto-deleted
A "Clear Logs" button at the top of the logs section
Logs are color coded: green for success, red for errors, white for info, yellow for warnings
How Logs Get Populated:

When the app starts: system checks and data counts are logged
A LogManager singleton is accessible from anywhere in the app
Any component can call LogManager.log(category, message, level)
The foreground service logs all voice activity
The AccessibilityService logs screen interactions
The Command Brain logs processing steps
The Action Executor logs execution results
UI Behavior Rules
On app launch, always check current state of accessibility and permissions. Update button states accordingly. User might have disabled accessibility from system settings while app was closed.

The status indicator at the top updates in real-time using LiveData observed from the foreground service state.

The entire UI works with TalkBack for accessibility. All buttons have proper content descriptions. Focus order is logical (top to bottom).

Screen orientation is locked to portrait. No landscape support needed.

Theme is dark by default (easier on eyes, less battery on OLED). High contrast text.

4. Accessibility Service
Service Declaration
NeoAccessibilityService extends AccessibilityService. It is declared in the manifest with meta-data pointing to an XML configuration file.

Configuration XML Settings
accessibilityEventTypes: all types (typeAllMask)
accessibilityFeedbackType: feedbackSpoken
canRetrieveWindowContent: true
accessibilityFlags: flagReportViewIds, flagRetrieveInteractiveWindows, flagRequestKeyEvents, flagIncludeNotImportantViews
notificationTimeout: 100 (milliseconds between events)
packageNames: not specified (receive events from ALL apps)
Core Responsibilities
Screen Event Monitoring:

onAccessibilityEvent() is called for every UI change on the phone
Events we care about:
TYPE_WINDOW_STATE_CHANGED — a new screen/activity/dialog opened
TYPE_WINDOW_CONTENT_CHANGED — content on current screen changed
TYPE_NOTIFICATION_STATE_CHANGED — new notification appeared
TYPE_VIEW_CLICKED — user clicked something
TYPE_VIEW_TEXT_CHANGED — text in a field changed
For each relevant event, extract the package name, event type, and source node info
Send this information to AccessibilityBridge for the foreground service to use
UI Tree Traversal:

When the foreground service requests screen content, AccessibilityService calls getRootInActiveWindow()
This returns the root AccessibilityNodeInfo of the current screen
Traverse the tree recursively using getChild(index) for each node
For each node, extract:
Text (getText())
Content description (getContentDescription())
Class name (getClassName()) — to know if it's a Button, EditText, TextView, etc.
Resource ID (getViewIdResourceName()) — most reliable identifier
Is clickable, is editable, is scrollable, is checkable, is checked
Bounds on screen (for position information)
Build a structured list of all meaningful elements on screen
Return this list through AccessibilityBridge
Action Execution:

When foreground service requests an action:
CLICK on element: find the node matching criteria → performAction(ACTION_CLICK)
TYPE text in field: find the EditText node → performAction(ACTION_SET_TEXT, Bundle with text)
SCROLL: find scrollable container → performAction(ACTION_SCROLL_FORWARD or ACTION_SCROLL_BACKWARD)
GLOBAL actions: performGlobalAction(GLOBAL_ACTION_BACK), performGlobalAction(GLOBAL_ACTION_HOME), performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS), performGlobalAction(GLOBAL_ACTION_RECENTS)
After performing action, wait briefly and report result back through bridge
Key Event Interception:

onKeyEvent() is called for hardware key presses
We intercept:
Volume up + Volume down pressed together → Wake Neo
Power button double press → Wake Neo (if detectable)
Return true to consume the event, false to let it pass through
Element Finding Strategy
When the foreground service says "find the search button in WhatsApp", the AccessibilityService needs to locate it. The search strategy in priority order:

By Resource ID — most reliable. Example: find node where viewIdResourceName contains "search" or "com.whatsapp:id/menuitem_search"
By Content Description — find node where contentDescription matches "Search" or "खोजें"
By Text — find node where text matches the expected text
By Class Type — find all nodes of type Button, then filter by other criteria
By Position — if nothing else works, use bounds to find element in expected screen region
App Profiles
For popular apps, we maintain known element identifiers in the database. This makes interaction faster and more reliable.

Example WhatsApp profile:

Search button: viewId contains "menuitem_search"
Chat input: viewId contains "entry"
Send button: viewId contains "send"
Contact name in chat: viewId contains "conversation_contact_name"
Message text: viewId contains "message_text"
Example YouTube profile:

Search button: contentDescription contains "Search"
Play/Pause: contentDescription contains "Play" or "Pause"
Skip ad: text contains "Skip"
These profiles are stored in app_profiles table and loaded when interacting with specific apps. For unknown apps, the service relies on generic element finding.

AccessibilityBridge (Shared Singleton)
This is a singleton object that both services can access:

It contains:

A currentScreenContent variable holding the latest screen information
A currentPackageName variable holding which app is in foreground
An actionRequestQueue — foreground service adds requests, accessibility service processes them
An actionResultCallback — accessibility service reports results back
A currentNotification variable for the latest notification data
Methods: requestScreenRead(), requestClick(criteria), requestType(criteria, text), requestGlobalAction(action)
Thread safety is ensured using synchronized blocks or Kotlin's Mutex.

5. Permission System
Complete Permission List
Runtime Permissions (requested via standard Android dialog):

RECORD_AUDIO — voice input
CALL_PHONE — making calls
READ_CONTACTS — accessing contacts
WRITE_CONTACTS — potentially adding contact aliases
READ_CALL_LOG — reading call history
SEND_SMS — sending messages
READ_SMS — reading messages
RECEIVE_SMS — detecting incoming messages
ACCESS_FINE_LOCATION — navigation and nearby places
ACCESS_COARSE_LOCATION — approximate location
CAMERA — future features and Raspberry Pi
READ_PHONE_STATE — detecting call state
ANSWER_PHONE_CALLS — answering/rejecting calls
READ_MEDIA_IMAGES — accessing photos (Android 13+)
READ_MEDIA_VIDEO — accessing videos (Android 13+)
READ_MEDIA_AUDIO — accessing music (Android 13+)
READ_EXTERNAL_STORAGE — accessing files (Android 12 and below)
POST_NOTIFICATIONS — showing notifications (Android 13+)
BLUETOOTH_CONNECT — Bluetooth control (Android 12+)
NEARBY_WIFI_NETWORKS — WiFi scanning (Android 13+)
Special Permissions (require navigating to system settings):

Accessibility Service — already handled by Button 1
Notification Listener — Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
WRITE_SETTINGS — Settings.ACTION_MANAGE_WRITE_SETTINGS (for brightness, timeout)
SYSTEM_ALERT_WINDOW — Settings.ACTION_MANAGE_OVERLAY_PERMISSION (for overlays)
ACCESS_NOTIFICATION_POLICY — for DND control
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — to prevent battery optimization killing our service (for demo this helps)
SCHEDULE_EXACT_ALARM — for setting alarms and reminders (Android 12+)
Manifest-Only Permissions (auto-granted, no user prompt):

INTERNET — for Gemini API
ACCESS_NETWORK_STATE — to check connectivity
FOREGROUND_SERVICE — to run foreground service
FOREGROUND_SERVICE_MICROPHONE — foreground service type
RECEIVE_BOOT_COMPLETED — optional auto-start
VIBRATE — haptic feedback
FLASHLIGHT — torch control
WAKE_LOCK — keeping CPU awake during processing
ACCESS_WIFI_STATE — checking WiFi status
CHANGE_WIFI_STATE — toggling WiFi
Permission Request Flow
When user taps Button 2:

Step 1: Show a brief explanation dialog: "Neo needs several permissions to work. I'll ask for them in groups. Please allow all of them."

Step 2: Request Batch 1 — Core (Microphone, Phone, Contacts)

Use ActivityResultContracts.RequestMultiplePermissions
After result, log granted/denied, update counter
Step 3: Request Batch 2 — Communication (SMS, Call Log)

Same approach
Step 4: Request Batch 3 — Location

Fine and Coarse location together
Step 5: Request Batch 4 — Media and Storage

Media permissions for Android 13+, Storage for older
Step 6: Request Batch 5 — Phone and Bluetooth

Phone state, Answer calls, Bluetooth connect
Step 7: Request Batch 6 — Notification posting

POST_NOTIFICATIONS for Android 13+
Step 8: Special permissions — one at a time

For each special permission, check if already granted
If not, explain why it's needed and open the relevant settings page
Wait for user to return (onResume), check if granted
Step 9: After all batches, show final status

Log all permission states
Update Button 2 status
Permission Check on Every App Launch
In onResume of MainActivity, re-check all permissions. User might revoke permissions from system settings between app launches. Update UI accordingly. If a critical permission (like Microphone) was revoked, disable Button 3 until it's re-granted.


6. Foreground Service
Service Lifecycle
Starting:

Started from MainActivity via startForegroundService() intent
In onCreate(): initialize all components (SpeechRecognizer, TTS, Command Brain, etc.)
In onStartCommand(): call startForeground() with notification, begin listening loop
Return START_STICKY so Android restarts the service if it's killed
Running:

Service stays alive indefinitely
Persistent notification shows in status bar
All voice processing happens here
Manages the state machine
Coordinates all other components
Stopping:

Triggered by Button 4 or voice command "Neo band ho ja"
Destroy SpeechRecognizer
Shutdown TTS
Cancel all coroutines
Remove notification
Call stopForeground() and stopSelf()
Notification Configuration
Channel name: "Neo Voice Assistant"
Channel importance: LOW (no sound for the notification itself)
Notification title: "Neo is running"
Notification text: changes based on state — "Listening for wake word..." / "Active and ready" / "Processing command..."
Small icon: microphone icon
Ongoing: true (can't be swiped away)
Actions on notification: "Activate Neo" button, "Stop" button
Service Components Initialization Order
Initialize LogManager (so everything can log from the start)
Initialize Database (Room)
Load synonym table into memory
Load learned commands into memory
Load user settings
Initialize TTS engine (async, takes a moment)
Initialize Command Brain with loaded data
Initialize Action Executor
Initialize SpeechRecognizer Manager
Start continuous listening loop
Log "Neo service started successfully"
Speak "Neo ready hai" through TTS
Wakelock Strategy
Acquire a partial wakelock to keep the CPU running even when screen is off. This ensures SpeechRecognizer continues working when the phone screen turns off during the demo. Release the wakelock when service stops.

7. Voice Engine (SpeechRecognizer)
SpeechRecognizer Manager
This is a wrapper class around Android's SpeechRecognizer. It handles the continuous listening loop and all callbacks.

Initialization
Check if SpeechRecognizer.isRecognitionAvailable(context) returns true
Create SpeechRecognizer instance using SpeechRecognizer.createSpeechRecognizer(context)
Set RecognitionListener with all callback implementations
Prepare RecognizerIntent with:
ACTION_RECOGNIZE_SPEECH
EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM
EXTRA_LANGUAGE = "hi-IN" (Hindi India — handles Hinglish perfectly)
EXTRA_LANGUAGE_PREFERENCE = "hi-IN"
EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE = true (not needed but good practice)
EXTRA_PARTIAL_RESULTS = true
EXTRA_MAX_RESULTS = 5
EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 3000 (wait 3 seconds of silence before finalizing)
EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = 2000
EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS = 1000
Continuous Listening Loop
The loop works as follows:

Call startListening(intent) → SpeechRecognizer starts → Callbacks fire:

onReadyForSpeech: Neo is now listening. Log this. If in ACTIVE state, could give subtle vibration.

onBeginningOfSpeech: User has started speaking. Log this.

onPartialResults: Intermediate results available. Extract the partial text. Can be used for live transcription in logs. Don't process commands from partial results (too unreliable).

onEndOfSpeech: User has stopped speaking. SpeechRecognizer is processing. Brief moment.

onResults: Final results available. This is the main callback.

Extract the results ArrayList of strings
Take the first result (highest confidence)
Pass the text to the State Machine for processing
IMMEDIATELY restart listening by calling startListening(intent) again
This creates the continuous loop
onError: Something went wrong. Common errors:

ERROR_NO_MATCH — silence, nobody spoke. Just restart listening.
ERROR_SPEECH_TIMEOUT — same as above. Restart.
ERROR_AUDIO — microphone issue. Log error, retry after 1 second.
ERROR_RECOGNIZER_BUSY — recognizer is busy. Wait 500ms, retry.
ERROR_NETWORK — no internet for online recognition. Will use offline model if available.
ERROR_SERVER — Google's server issue. Fall back to offline.
For any error, restart listening after a brief delay (500ms-1000ms)
onRmsChanged: Audio level changed. This gives us the real-time audio amplitude. Useful for visual feedback in logs (showing that mic is picking up audio).

Handling TTS Conflict
When TTS is speaking, we need to prevent SpeechRecognizer from processing Neo's own voice:

Before TTS starts: set isSpeaking = true, optionally stop SpeechRecognizer
After TTS finishes (onDone callback): set isSpeaking = false, restart SpeechRecognizer
Alternative approach: keep SpeechRecognizer running but discard any results received while isSpeaking is true
The second approach is simpler and has less gap. The SpeechRecognizer might hear Neo's voice and return some text, but we just throw it away. When TTS finishes, the next real human speech is captured normally.

Language Handling
Setting language to "hi-IN" means:

Pure Hindi commands are recognized perfectly
Pure English commands are also recognized (Google's Hindi model understands English words)
Hinglish (mixed) commands are recognized naturally because that's how most Indians speak
Example: "WiFi on karo" returns exactly "WiFi on karo" — Google keeps English words in English and Hindi words in Hindi transliteration
If user chose English during setup, set language to "en-IN" which is Indian English and also handles Hindi words reasonably well.

8. Text-to-Speech Engine
TTS Manager
Wrapper class around Android's TextToSpeech.

Initialization
Create TextToSpeech instance with OnInitListener
In OnInitListener.onInit():
Check if initialization was successful
Set language to Locale("hi", "IN") for Hindi
If Hindi is not available, fall back to Locale("en", "IN")
Set speech rate to 0.9f (slightly slower for clarity)
Set pitch to 1.0f (normal)
Register UtteranceProgressListener for speech start/end callbacks
Speaking Responses
TTS Manager provides a speak(text, language) method:

If language is HINDI: set locale to Hindi, speak
If language is ENGLISH: set locale to English India, speak
If language is HINGLISH: set locale to Hindi (Hindi TTS handles English words in middle)
Queue mode: QUEUE_FLUSH — new speech interrupts any ongoing speech
Generate a unique utterance ID for each speech (UUID)
UtteranceProgressListener.onStart() → set isSpeaking = true, notify SpeechRecognizer Manager
UtteranceProgressListener.onDone() → set isSpeaking = false, notify SpeechRecognizer Manager to restart
Response Templates
All Neo responses are pre-defined in a ResponseManager:

Each response has three versions (Hindi, English, Hinglish). The version used depends on the language the user spoke in (language mirroring).

Example response entries:

WIFI_ON_SUCCESS: "WiFi on kar diya" / "WiFi has been turned on" / "WiFi on kar diya hai"
COMMAND_NOT_UNDERSTOOD: "Samajh nahi aaya, dobara bolo" / "I didn't understand, please repeat" / "Samajh nahi aaya, please dobara bolo"
GREETING: "Haan, boliye" / "Yes, I'm listening" / "Haan, boliye"
Dynamic responses (with variables) use string templates:

CALLING_CONTACT: "{{name}} ko call laga raha hun" / "Calling {{name}}" / "{{name}} ko call kar raha hun"
MESSAGE_SENT: "{{name}} ko message bhej diya" / "Message sent to {{name}}" / "{{name}} ko message bhej diya hai"
9. State Machine
States
SLEEPING

SpeechRecognizer is running but only checking for wake word "Neo"
Any recognized text is checked: does it contain "Neo"?
If yes → transition to ACTIVE
If no → discard and continue listening
Status: Yellow dot "Neo is sleeping"
ACTIVE

Neo has been woken up, beep played, vibration given
SpeechRecognizer results are now sent to Command Brain
60-second idle timer starts
If a command is received → transition to EXECUTING
If 60 seconds pass with no command → transition back to SLEEPING
At 45 seconds of idle, Neo asks "Kya aap kuch kehna chahte hain?"
Status: Green dot "Neo is active"
EXECUTING

A command has been recognized and is being processed/executed
SpeechRecognizer continues listening but results are queued, not processed immediately
Once execution completes → TTS speaks response → transition to ACTIVE (ready for next command)
Reset the 60-second idle timer
Status: Green dot "Neo is active"
WAITING_RESPONSE

Neo has asked the user a question and is waiting for an answer
Examples: "Rahul Sharma ya Rahul Verma?" or "Kya aap sure hain?"
SpeechRecognizer result is treated as an answer to the question, not a new command
Once answer is received → process it → transition to EXECUTING or ACTIVE
Status: Green dot "Neo is active"
State Transitions
text

SLEEPING ---(hears "Neo")-→ ACTIVE
SLEEPING ---(power button/shake)-→ ACTIVE

ACTIVE ---(hears command)-→ EXECUTING
ACTIVE ---(60s timeout)-→ SLEEPING
ACTIVE ---(hears "Neo so ja")-→ SLEEPING

EXECUTING ---(action complete)-→ ACTIVE
EXECUTING ---(needs clarification)-→ WAITING_RESPONSE
EXECUTING ---(error)-→ ACTIVE (after error message)

WAITING_RESPONSE ---(hears answer)-→ EXECUTING
WAITING_RESPONSE ---(10s timeout)-→ ACTIVE (cancel current action)
Wake Word Processing
When in SLEEPING state, every SpeechRecognizer result goes through:

Convert to lowercase
Check if the text contains "neo" (not just equals, but contains — user might say "hey neo" or "neo sun")
If found, extract anything after "neo" — this might be the command itself
"Neo WiFi on karo" → wake word detected + command "WiFi on karo"
In this case, transition to ACTIVE and immediately process "WiFi on karo" through Command Brain
If just "Neo" with nothing after → transition to ACTIVE, wait for command
Play activation beep and short vibration to confirm wake
Manual Activation
When user uses physical activation (power button combo, shake, notification tap, Button 3 tap):

Directly transition from SLEEPING to ACTIVE
Skip wake word check
Play activation beep and vibration
Start listening for commands
10. Command Brain (6-Phase Engine)
Phase 1: Pre-processing
Input: raw text from SpeechRecognizer
Output: cleaned, normalized text

Processing steps in order:

Step 1: Lowercase conversion

Convert entire text to lowercase
"WIFI On Karo" → "wifi on karo"
Step 2: Remove wake word

If text starts with "neo", remove it
"neo wifi on karo" → "wifi on karo"
Step 3: Remove filler words

Remove: "umm", "uh", "hmm", "please", "kindly", "can you", "could you", "will you", "I want to", "I want you to", "mujhe", "zara", "thoda", "kya aap", "kya tum"
"please wifi on karo na" → "wifi on karo"
Be careful not to remove meaningful words that happen to be in the filler list
Step 4: Normalize numbers

Hindi number words to digits: "ek"→"1", "do"→"2", "teen"→"3", "char"→"4", "paanch"→"5", "chhe"→"6", "saat"→"7", "aath"→"8", "nau"→"9", "das"→"10"
English number words to digits: "one"→"1", "two"→"2", etc.
"volume paanch par kar" → "volume 5 par kar"
Step 5: Common substitutions

"karr" → "kar"
"kardo" → "kar do"
"bata do" → "batao"
Normalize common spelling variations from speech recognition
Step 6: Trim extra spaces

Multiple spaces to single space
Trim leading/trailing spaces
Phase 2: Synonym Lookup (Target: ~70% resolution)
This is a HashMap lookup. The key is a phrase, the value is a command identifier.

Data Structure:

HashMap<String, CommandAction>
Loaded from synonyms_table in Room database at app startup
Stays in memory for O(1) lookup
Command Actions (enum or sealed class):
Each command has a unique identifier like:

WIFI_ON, WIFI_OFF, WIFI_STATUS
BLUETOOTH_ON, BLUETOOTH_OFF
FLASHLIGHT_ON, FLASHLIGHT_OFF
VOLUME_UP, VOLUME_DOWN, VOLUME_FULL, VOLUME_MUTE
BRIGHTNESS_UP, BRIGHTNESS_DOWN, BRIGHTNESS_FULL
CALL(contact), MESSAGE(contact, body)
OPEN_APP(app_name)
SCREEN_READ
TIME_NOW, DATE_NOW
BATTERY_STATUS
... and all other 86+ commands
Synonym Examples (per command):

WIFI_ON:

"wifi on", "wifi on kar", "wifi on karo", "wifi chalu kar", "wifi chalu karo", "wifi enable kar", "wifi enable karo", "wifi start kar", "wifi start karo", "internet on kar", "internet on karo", "internet chalu kar", "net on kar", "net on karo", "wifi connect kar", "wifi laga", "wifi lagao", "turn on wifi", "enable wifi", "start wifi", "switch on wifi", "wifi activate", "wifi chalao"
VOLUME_UP:

"volume up", "volume up kar", "volume up karo", "volume badha", "volume badhao", "volume increase kar", "volume increase karo", "awaz badha", "awaz badhao", "sound badha", "sound badhao", "volume zyada kar", "volume upar kar", "raise volume", "increase volume", "turn up volume", "louder", "aur zor se", "volume high kar"
Each command has 20-50 synonym phrases. Total approximately 3000 entries across all 86 commands.

Lookup Process:

Take pre-processed text
Check exact match in HashMap
If found → return CommandAction → Phase 2 SUCCESS
If not found → pass to Phase 3
Phase 3: Pattern Matching (Target: ~15% resolution)
For commands with variables (contact names, app names, times, etc.), exact synonym matching won't work because the variable part changes.

Pattern Structure:
Each pattern has:

A regex pattern with named capture groups
A command action with variable slots
Priority (lower number = higher priority, checked first)
Pattern Examples:

text

Priority 1 (most specific):
"{contact} ko whatsapp par {message} bhej" → WHATSAPP_MESSAGE(contact, message)
"{contact} ko whatsapp message kar {message}" → WHATSAPP_MESSAGE(contact, message)
"{contact} ko call kar" → MAKE_CALL(contact)
"{contact} ko call karo" → MAKE_CALL(contact)
"{contact} ko call laga" → MAKE_CALL(contact)
"{contact} ko phone kar" → MAKE_CALL(contact)
"{contact} ko message kar {message}" → SEND_SMS(contact, message)
"{contact} ko message bhej {message}" → SEND_SMS(contact, message)

Priority 2:
"{app} open kar" → OPEN_APP(app)
"{app} open karo" → OPEN_APP(app)
"{app} khol" → OPEN_APP(app)
"{app} kholo" → OPEN_APP(app)
"{app} chalu kar" → OPEN_APP(app)
"{app} start kar" → OPEN_APP(app)

Priority 3:
"alarm {time} baje laga" → SET_ALARM(time)
"alarm {time} baje ka laga" → SET_ALARM(time)
"{time} baje alarm laga" → SET_ALARM(time)
"timer {duration} minute ka laga" → SET_TIMER(duration)
"volume {level} kar" → SET_VOLUME(level)
"brightness {level} kar" → SET_BRIGHTNESS(level)
"navigate to {place}" → NAVIGATE(place)
"{place} ka raasta batao" → NAVIGATE(place)
"{place} kaise jaun" → NAVIGATE(place)
How Pattern Matching Works:

Take the pre-processed text
Try each pattern (in priority order) against the text
Patterns are converted to regex where {variable} becomes a capture group
"{contact} ko call kar" becomes regex: "(.+) ko call kar" with group 1 = contact
If regex matches, extract variables
For contact variable: attempt to match against contacts list (exact, contains, phonetic)
For app variable: attempt to match against installed apps list
If match found → return CommandAction with variables → Phase 3 SUCCESS
If no pattern matches → pass to Phase 4
Phase 4: Learned Memory (Starts ~10%, grows to 90%+)
Query the learned_commands_table in Room database.

Lookup Process:

Take pre-processed text
Query: SELECT command_id FROM learned_commands WHERE input_text = ?
If found and confidence > 80% → return the command → Phase 4 SUCCESS
If found but confidence 60-80% → ask user for confirmation
If not found → pass to Phase 5
Why This Grows Over Time:
Every time a command is resolved through Phases 5, 6, or Gemini learning, the mapping is stored here. Next time the same or similar text appears, Phase 4 catches it before reaching the expensive phases.

Phase 5: Fuzzy Matching (Target: ~3% resolution)
When exact lookup and patterns fail, try approximate matching.

Algorithm: Levenshtein Distance

Take pre-processed text
Compare against all synonym entries using Levenshtein distance
Calculate similarity percentage: 1 - (distance / max_length)
Collect all entries with similarity > 70%
Sort by similarity descending
Decision Logic:

If top match similarity > 85% and second match is < 70% → confident single match → execute
If top match similarity > 85% but second match is also > 80% → ambiguous → ask user
If top match similarity 70-85% → ask user for confirmation: "Kya aap {action} karna chahte hain?"
If no match above 70% → Phase 5 FAIL → pass to Phase 6
Phase 6: Context Resolver (Target: ~1% resolution)
For ambiguous commands that could mean multiple things.

Context Stack:
Maintain a list of the last 5 commands with their categories. Stored in memory, not database.

Example stack:

text

1. MEDIA_PLAY (category: MEDIA) — 30 seconds ago
2. VOLUME_UP (category: MEDIA) — 45 seconds ago
3. OPEN_APP_SPOTIFY (category: APP) — 1 minute ago
Resolution Logic:
When an ambiguous command like "volume up" could mean media volume or ringer volume:

Check context stack
If recent commands are media-related → resolve as MEDIA_VOLUME_UP
If recent commands are call-related → resolve as RING_VOLUME_UP
If no context → default to the more common interpretation (media volume)
Other Context Signals:

Current foreground app (from AccessibilityBridge)
Time of day
Whether media is currently playing (from MediaSession)
Whether a call is active
If All 6 Phases Fail
Neo speaks: "Samajh nahi aaya. Dobara bolo" (Hindi) or "I didn't understand. Please repeat" (English)
The failed text is logged in failed_commands_queue table
Gemini Background Worker will process it later
State remains ACTIVE, ready for the next attempt
Log the failure in logs_table
11. Action Executor
Responsibility
Receives a resolved CommandAction from Command Brain and executes it using the appropriate Android API.

Execution Categories
Category 1: Direct System API Calls
These are instant, no AccessibilityService needed:

Flashlight on/off: CameraManager.setTorchMode()
Volume control: AudioManager.adjustStreamVolume() or setStreamVolume()
Ringer mode: AudioManager.setRingerMode()
DND: NotificationManager.setInterruptionFilter()
Brightness: Settings.System.putInt() for SCREEN_BRIGHTNESS
Auto-rotate: Settings.System.putInt() for ACCELEROMETER_ROTATION
Screen timeout: Settings.System.putInt() for SCREEN_OFF_TIMEOUT
Making calls: Intent(ACTION_CALL) with tel: URI
Sending SMS: SmsManager.sendTextMessage()
Setting alarm: Intent(ACTION_SET_ALARM) with extras
Setting timer: Intent(ACTION_SET_TIMER) with extras
Opening apps: packageManager.getLaunchIntentForPackage()
Media controls: MediaController.getTransportControls()
Category 2: Settings Panel Navigation
For toggles that Android restricts direct API access (Android 10+):

WiFi: Intent(Settings.Panel.ACTION_WIFI) then AccessibilityService clicks toggle
Bluetooth: BluetoothAdapter.enable()/disable() for older Android, Settings panel for newer
Mobile Data: Intent to network settings then AccessibilityService
Airplane Mode: Intent to airplane settings then AccessibilityService
Hotspot: Intent to tethering settings then AccessibilityService
GPS/Location: Intent to location settings then AccessibilityService
Category 3: AccessibilityService Required
For controlling third-party apps:

WhatsApp messaging
App navigation
Screen reading
Any UI interaction in other apps
Execution Flow
Receive CommandAction from Command Brain
Determine category
If Category 1: execute directly, get result, return
If Category 2: open settings panel, send action request through AccessibilityBridge, wait for result
If Category 3: send action sequence through AccessibilityBridge, monitor each step, handle timeouts
After execution: construct response message, return to TTS Manager
Log the execution result
Error Handling in Executor
If a direct API call throws exception → catch, log, return error response
If AccessibilityService action times out (3 seconds per step) → cancel remaining steps, return error response
If an app is not installed → "App nahi mili. Kya naam sahi hai?"
If contact not found → "Contact nahi mila. Kya naam dobara bolenge?"
If permission not available → "Is action ke liye permission nahi hai"
12. Hardware Controls
WiFi Control
Check current state: WifiManager.isWifiEnabled()
Toggle on Android 9 and below: WifiManager.setWifiEnabled(true/false)
Toggle on Android 10+: Open Settings.Panel.ACTION_WIFI, use AccessibilityService to find and click the WiFi toggle switch
Report: "WiFi on kar diya" / "WiFi band kar diya" / "WiFi pehle se on hai"
Bluetooth Control
Check current state: BluetoothAdapter.isEnabled()
Toggle: BluetoothAdapter.enable() / disable() — requires BLUETOOTH_CONNECT on Android 12+
If direct API fails on newer Android: use Settings panel approach
Report: "Bluetooth on kar diya" / "Bluetooth band kar diya"
Flashlight Control
Get camera ID: CameraManager.getCameraIdList()[0] (rear camera)
Toggle: CameraManager.setTorchMode(cameraId, true/false)
Register TorchCallback to track current state
Report: "Flashlight on kar di" / "Flashlight band kar di"
Volume Controls
Media volume: AudioManager, stream STREAM_MUSIC
Ringer volume: AudioManager, stream STREAM_RING
Notification volume: AudioManager, stream STREAM_NOTIFICATION
Alarm volume: AudioManager, stream STREAM_ALARM
In-call volume: AudioManager, stream STREAM_VOICE_CALL
For "volume up/down": use adjustStreamVolume with ADJUST_RAISE/LOWER
For "volume full": setStreamVolume to getStreamMaxVolume
For "volume mute/silent": setStreamVolume to 0 or setRingerMode(RINGER_MODE_SILENT)
For "volume X percent": calculate value = maxVolume * X / 100, setStreamVolume
Brightness Control
Check WRITE_SETTINGS permission
Set manual brightness mode first: Settings.System.putInt(SCREEN_BRIGHTNESS_MODE, 0)
Set brightness value: Settings.System.putInt(SCREEN_BRIGHTNESS, value) — value is 0-255
For "brightness full": value = 255
For "brightness low/dim": value = 25
For "brightness X percent": value = 255 * X / 100
Report: "Brightness full kar di" / "Brightness kam kar di"
DND (Do Not Disturb)
Check notification policy access
Turn on: NotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_NONE)
Turn off: NotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_ALL)
Report: "Do Not Disturb on kar diya" / "Do Not Disturb band kar diya"
Auto-Rotate
Check current: Settings.System.getInt(ACCELEROMETER_ROTATION)
Toggle: Settings.System.putInt(ACCELEROMETER_ROTATION, 1/0)
Report: "Auto rotate on kar diya" / "Auto rotate band kar diya"
Screen Timeout
Set: Settings.System.putInt(SCREEN_OFF_TIMEOUT, milliseconds)
Values: 15000 (15s), 30000 (30s), 60000 (1min), 120000 (2min), 300000 (5min), 600000 (10min)
For "screen off mat karo": set to very high value like 1800000 (30min)
Report: "Screen timeout 2 minute kar diya"
Airplane Mode
Cannot be toggled directly since Android 4.2 without root
Open: Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
Use AccessibilityService to find and toggle
Report: "Airplane mode on kar diya" / "Airplane mode band kar diya"
Hotspot
On older Android: WifiManager.startLocalOnlyHotspot() or reflection methods
On newer Android: Intent to tethering settings + AccessibilityService
Report: "Hotspot on kar diya"
GPS/Location
Cannot be toggled directly
Open: Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
Use AccessibilityService to toggle
Report: "Location on kar diya"
Mobile Data
Cannot be toggled directly without root
Open: Intent(Settings.ACTION_DATA_ROAMING_SETTINGS) or network settings
Use AccessibilityService to toggle
Report: "Mobile data on kar diya"


13. App Control System

Opening Apps
Step 1: Build App Index

At startup, query PackageManager for all installed apps with launch intents
Build a HashMap<String, String> mapping app names to package names
Include both the app's label (display name) and common aliases
Example: "whatsapp" → "com.whatsapp", "youtube" → "com.google.android.youtube"
Store common alternate names: "insta" → "com.instagram.android", "wp" → "com.whatsapp"
Log total apps found in logs
Step 2: App Name Resolution
When user says "WhatsApp kholo":

Pre-processing extracts "whatsapp"
Check app index for exact match
If not found, try fuzzy matching against all app names
If still not found: "Yeh app nahi mili. Kya naam sahi hai?"
If found: launch using getLaunchIntentForPackage(packageName)
Step 3: Closing Apps

"WhatsApp band karo" → performGlobalAction(GLOBAL_ACTION_HOME) to go home
Or performGlobalAction(GLOBAL_ACTION_RECENTS) then find WhatsApp in recents and swipe away
Direct app killing is not possible without root
Controlling Apps (Layer 2 — Accessibility Based)
General App Interaction Steps:

Open the app (Intent)
Wait for AccessibilityEvent confirming app is in foreground (TYPE_WINDOW_STATE_CHANGED with matching package name)
Read current screen via AccessibilityBridge
Find target element using search strategy (by ID, description, text, class, position)
Perform action on found element (click, type, scroll)
Wait for next screen/state change
Repeat until task is complete
Multi-Step Task Engine:

A Task is a sequence of Steps. Each Step has:

action: what to do (CLICK, TYPE, SCROLL, WAIT, FIND, GLOBAL_ACTION)
target: how to find the element (by ID, text, description, class)
value: any input text for TYPE actions
timeout: how long to wait for this step (default 3 seconds)
onSuccess: what step comes next
onFailure: what to do if this step fails (retry, skip, abort)
Example Task — Send WhatsApp Message("Rahul", "main aa raha hun"):

text

Step 1: LAUNCH app "com.whatsapp" | timeout 5s | onFail: abort("WhatsApp nahi khul raha")
Step 2: WAIT for package "com.whatsapp" in foreground | timeout 3s
Step 3: FIND element by contentDescription "Search" OR by viewId containing "search" | CLICK
Step 4: WAIT for EditText to appear | timeout 2s
Step 5: FIND EditText | TYPE "Rahul"
Step 6: WAIT 1 second for search results
Step 7: FIND element containing text "Rahul" | CLICK
Step 8: WAIT for chat screen (viewId containing "conversation" or "chat") | timeout 3s
Step 9: FIND EditText (message input) by viewId containing "entry" or by hint "Type a message"
Step 10: TYPE "main aa raha hun"
Step 11: FIND send button by contentDescription "Send" or viewId containing "send" | CLICK
Step 12: REPORT success "Rahul ko WhatsApp message bhej diya"
Pre-built Task Templates for Common Apps:

WhatsApp: send message, read chat, make WhatsApp call
YouTube: search video, play/pause
Chrome: open URL, search
Settings: navigate to specific setting
Camera: take photo
Gallery: open latest photo
14. Screen Reading
Full Screen Read
When user says "screen padho":

AccessibilityService calls getRootInActiveWindow()
Recursive traversal of the node tree
For each node, check:
Does it have text? If yes, add to reading list with node type label
Does it have content description? If yes, add to reading list
Is it a clickable element? Note it for the user
Is it an EditText with text? Read what's typed
Is it a checked/unchecked toggle? Mention its state
Organize reading list into natural language:
Start with the app name (from package name of root window)
Read the title/toolbar text
Read main content area
Mention interactive elements ("Yahan ek search button hai, ek back button hai")
Speak through TTS
Smart Reading (Contextual)
Instead of dumping all text, Neo contextualizes:

In WhatsApp chat: "Rahul ki chat hai. Last message unhone bheja: 'Kab aa rahe ho?' aaj 3:45 PM ko. Usse pehle aapne bheja tha: 'Thodi der mein' 3:30 PM ko."
In Settings: "WiFi settings khuli hai. WiFi abhi on hai. Connected network: Home_WiFi. Signal achha hai."
In Phone app: "3 missed calls hain. Rahul ka 2 baje, Priya ka 3 baje, Unknown number ka 4 baje."
Notification Reading
When user says "notifications padho":

Pull notification shade using performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
Read the notification list from AccessibilityService
Or use NotificationListenerService's getActiveNotifications() for a cleaner list
For each notification, read: app name, title, content, time
User can say "pehli notification kholo" → click on the first notification
15. Phone Calls and Contacts
Contact Loading
At startup (after contacts permission granted):

Query ContactsContract.Contacts for all contacts
For each contact, load:
Display name
All phone numbers (mobile, home, work)
Photo URI (not used for blind users but available)
Build contact index:
HashMap<String, ContactInfo> mapping lowercase name → contact info
Include partial names: "Rahul Sharma" → entries for "rahul sharma", "rahul", "sharma"
Load contact aliases from contact_aliases table
Log: "X contacts loaded"
Contact Matching
When user says a name:

Check exact match in alias table first (user might have said "Mom" → alias for "Mummy")
Check exact match in contact index
Check contains match ("Rahul" matches "Rahul Sharma")
Check phonetic match using Soundex algorithm (handles pronunciation variations)
If multiple matches found → ask user: "Rahul Sharma ya Rahul Verma?"
If no match → "Contact nahi mila. Kya naam dobara bolenge?"
Making Calls
Once contact is resolved:

Get phone number
If contact has multiple numbers → ask: "Mobile number par ya office number par?"
Create Intent(ACTION_CALL, Uri.parse("tel:$number"))
Start intent — call begins immediately (CALL_PHONE permission)
Report: "Rahul ko call laga raha hun"
Answering/Rejecting Calls
When phone rings (detected via TelephonyManager or PhoneStateListener):

Neo announces: "Rahul ka call aa raha hai. Uthana hai ya reject karna hai?"
User says "uthao" → TelecomManager.acceptRingingCall()
User says "reject karo" → TelecomManager.endCall()
User says "speaker par uthao" → accept call then AudioManager.setSpeakerphoneOn(true)
Call Log Reading
When user asks about calls:

"Last call": query CallLog.Calls, sort by date descending, read first entry
"Missed calls": query with type MISSED_TYPE, read results
"Aaj ke calls": query with date filter for today
For each entry: read contact name (match number to contacts), type (incoming/outgoing/missed), time
16. SMS System
Sending Messages
When command SEND_SMS(contact, message) is resolved:

Resolve contact name to phone number (same as call flow)
Use SmsManager.getDefault().sendTextMessage(number, null, message, sentPendingIntent, deliveryPendingIntent)
sentPendingIntent confirms message was sent
Report: "Rahul ko message bhej diya: 'main aa raha hun'"
Reading Messages
When user says "messages padho" or "SMS padho":

Query content://sms/inbox for received messages
Sort by date descending
Read the latest 5 messages: sender (match number to contacts), message body, time
User can ask "aur padho" for more messages
Or "Rahul ke messages padho" → filter by contact
Incoming Message Alert
Via BroadcastReceiver for SMS_RECEIVED or via NotificationListenerService:

When new SMS arrives, Neo announces: "Rahul ka message aaya hai: 'Kab aa rahe ho?'"
User can say "reply kar 'aa raha hun'" → sends reply
17. Media Control
Controlling Active Media
Using MediaSessionManager and MediaController:

Get active sessions: mediaSessionManager.getActiveSessions(notificationListenerComponent)
Get the controller for the first (active) session
Available controls:
Play: controller.transportControls.play()
Pause: controller.transportControls.pause()
Next: controller.transportControls.skipToNext()
Previous: controller.transportControls.skipToPrevious()
Stop: controller.transportControls.stop()
Media Information
Get current media info from MediaController.metadata:

Title: metadata.getString(METADATA_KEY_TITLE)
Artist: metadata.getString(METADATA_KEY_ARTIST)
Album: metadata.getString(METADATA_KEY_ALBUM)
Duration: metadata.getLong(METADATA_KEY_DURATION)
Neo responds: "Spotify par 'Tum Hi Ho' chal raha hai, Arijit Singh ka gaana"

Playing Specific Music
When user says "Arijit Singh ka gaana chalao":

Create intent: Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
Add extra: EXTRA_MEDIA_ARTIST = "Arijit Singh"
Add extra: EXTRA_MEDIA_FOCUS = "vnd.android.cursor.item/artist"
Start intent — default music app handles it
If no app handles the intent → open Spotify/YouTube Music via AccessibilityService and search
Volume During Media
"Volume badha" while media plays → increase STREAM_MUSIC volume
"Volume kam kar" → decrease STREAM_MUSIC volume
"Mute kar" → pause media or mute stream

18. Utilities
Calculator
When user says math expressions:

Pre-processing extracts the mathematical expression
Parse using a simple expression evaluator:
Support: +, -, ×, ÷, %, square root, power
Handle Hindi math words: "plus/jama" (+), "minus/ghata" (-), "into/guna" (×), "divided by/bhag" (÷)
Calculate result
Speak result: "245 plus 380 barabar 625"
Time and Date
"Kitne baje hain?" → Read Calendar.getInstance() → "Abhi shaam ke 5 baj kar 30 minute hain"
"Aaj kya date hai?" → Read date → "Aaj 15 January 2025 hai, Wednesday"
"Aaj kaun sa din hai?" → "Aaj Wednesday hai"

Alarm
"Subah 7 baje alarm laga" →

Intent(AlarmClock.ACTION_SET_ALARM)
Extras: EXTRA_HOUR = 7, EXTRA_MINUTES = 0
Start intent
Report: "Subah 7 baje ka alarm laga diya"
Timer
"5 minute ka timer laga" →

Intent(AlarmClock.ACTION_SET_TIMER)
Extras: EXTRA_LENGTH = 300 (seconds)
Start intent
Report: "5 minute ka timer laga diya"
Reminders
"3 baje yaad dila dena dawai khane ka" →

Store reminder in database with alarm time
Use AlarmManager.setExact() to schedule
When alarm fires, broadcast receiver triggers Neo to speak: "Reminder: dawai khane ka time ho gaya"
Stopwatch
Implement using a simple counter in the service:

"Stopwatch start kar" → record System.currentTimeMillis(), start counting
"Stopwatch stop kar" → calculate elapsed time → "32 seconds hue"
"Stopwatch reset kar" → clear counter
Battery Status
"Battery kitni hai?" →

Register BatteryManager receiver or get current battery info
Get level percentage, charging status
Report: "Battery 75% hai, charging chal rahi hai" or "Battery 45% hai, charging nahi hai"
19. Navigation
Current Location
"Main kahan hun?" →

Get last known location from FusedLocationProviderClient
Use Geocoder to reverse geocode coordinates to address
Report: "Aap Connaught Place, New Delhi mein hain"
Navigate to Place
"Nearest hospital le chalo" →

Get current location
Create Google Maps intent: Intent(ACTION_VIEW, Uri.parse("google.navigation:q=nearest+hospital&mode=w"))
Launch Google Maps — navigation starts automatically
Report: "Google Maps mein navigation start kar diya hai"
Nearby Places
"Paas mein ATM hai?" →

Get current location
Create Maps search intent: Intent(ACTION_VIEW, Uri.parse("geo:lat,lon?q=ATM"))
Launch Google Maps with ATM search
Report: "Google Maps mein nearby ATM dikhaye hain"
Direction Commands
"Ghar ka raasta batao" →

If "home" address is configured in setup → navigate to home
If not → "Ghar ka address set nahi hai. Pehle ghar ka address batayein"
20. Notification System
NotificationListenerService Setup
Service must be declared in manifest. User grants access through Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS (part of permissions flow).

onNotificationPosted Callback
When any notification arrives:

Extract: packageName, title (extras.getString(EXTRA_TITLE)), text (extras.getString(EXTRA_TEXT)), time
Determine app name from package name using PackageManager
Send to foreground service via bridge/broadcast
Foreground service decides whether to announce:
If Neo is in ACTIVE state → announce immediately
If Neo is in SLEEPING state → don't announce (user hasn't activated Neo)
Unless it's from a monitored contact → announce regardless
Notification Announcement Format
"WhatsApp par Rahul ka message: 'Are you coming?'"
"Gmail par naya email: 'Meeting at 3 PM' from boss@company.com"
"Phone: Missed call from Priya"

Notification Actions
Some notifications have action buttons (like Reply, Mark as Read):

User says "reply kar 'haan aa raha hun'" → find Reply action → send reply
This uses Notification.Action's actionIntent or RemoteInput for inline replies
21. Chat Monitoring
WhatsApp Monitoring
Passive (via Notifications):

Every WhatsApp notification captured by NotificationListenerService
Extract sender and message from notification
Store in local database for history
Announce if user has set monitoring rules
Active (via Accessibility):

User says "WhatsApp messages padho"
Open WhatsApp
AccessibilityService reads the chat list screen
Identify unread chats (bold text, unread count badges)
Report: "3 unread chats hain. Rahul ke 2, Priya ka 1. Kiska padhun?"
User says "Rahul ka" → open Rahul's chat → read messages
Monitoring Rules
User can say "Agar Rahul ka message aaye toh turant batana"

Store rule in database: contact = "Rahul", app = "WhatsApp", action = ANNOUNCE_IMMEDIATELY
When notification matches rule → announce even in SLEEPING state (wake Neo briefly)
22. Security Vault
PIN Setup
During first-time setup:

Neo asks: "Ek 4 digit ka PIN set karein"
User says 4 digits
Neo repeats and asks for confirmation
Hash the PIN using SHA-256 with a salt
Store hash in user_settings (never store plain PIN)
Protected Actions
These actions require PIN before execution:

Reading messages
Reading call log
Accessing contacts details
Deleting anything
Changing app settings
Any action user has marked as sensitive
PIN Verification Flow
User gives a protected command
Neo says: "Ye secure action hai. Apna PIN bolen"
State changes to WAITING_RESPONSE
User says PIN digits
Hash the input, compare with stored hash
If match → execute the protected action
If no match → "Galat PIN. Dobara try karein" (max 3 attempts)
After 3 failed attempts → "PIN 3 baar galat. 5 minute baad try karein"
23. Gemini Background Learning
Purpose
Learn from failed commands so Neo gets smarter over time. NEVER used for real-time processing. NEVER blocks user interaction.

Implementation
Failed Command Queue:

Every command that fails all 6 phases goes into failed_commands_queue table
Fields: input_text, timestamp, context (last 3 commands), app_in_foreground, resolved (boolean)
Background Worker:

Using WorkManager, schedule a periodic worker
Worker runs when: device has internet AND there are unresolved entries
Batch size: up to 10 failed commands per batch
Gemini API Call:

Use Retrofit to call Gemini 2.0 Flash API
API key stored encrypted using Android Keystore
Prompt template:
"You are a command resolver for a voice assistant for blind users. The assistant supports these commands: [list of all 86 command identifiers with descriptions]. The user said: '{failed_text}'. Context: the user recently used commands [{context}]. The app in foreground was {app_name}. What command did the user most likely intend? Respond in JSON format: {command_id: string, confidence: float 0-1, reasoning: string}"

Processing Response:

Parse Gemini's JSON response
If confidence > 0.85 → store in learned_commands_table automatically
If confidence 0.6-0.85 → store with flag for user verification next time
If confidence < 0.6 → discard, mark as unresolvable
Mark entry as resolved in failed_commands_queue
API Cost Management:

Gemini 2.0 Flash is very cheap
Batching reduces API calls
Typical usage: maybe 5-10 calls per day initially, decreasing over time
For college demo: might not even be needed if synonym table is comprehensive
24. Database Design (Room)
Tables
synonyms_table

text

id: Int (primary key, auto-generate)
phrase: String (indexed for fast lookup)
command_id: String (like "WIFI_ON", "VOLUME_UP")
language: String ("hi", "en", "hinglish")
category: String ("hardware", "call", "media", "app", "utility", "navigation")
Pre-populated with ~3000 entries.

learned_commands_table

text

id: Int (primary key)
input_text: String (indexed)
command_id: String
confidence: Float
usage_count: Int (default 1, incremented on each use)
needs_verification: Boolean
created_at: Long (timestamp)
last_used_at: Long (timestamp)
failed_commands_queue

text

id: Int (primary key)
input_text: String
context: String (JSON array of last 3 commands)
foreground_app: String
timestamp: Long
resolved: Boolean (default false)
resolution: String (nullable, filled after Gemini processes)
command_history

text

id: Int (primary key)
input_text: String
resolved_command: String
was_successful: Boolean
response_text: String
timestamp: Long
Keep last 200 entries, auto-delete older ones.

contact_aliases

text

id: Int (primary key)
alias_name: String (indexed, like "Mom", "Papa")
actual_name: String (like "Mummy Ji", "Papa Ji")
phone_number: String
user_settings

text

key: String (primary key)
value: String
Stores: language_preference, speech_rate, pin_hash, pin_salt, first_setup_complete, voice_wake_enabled, etc.

app_profiles

text

id: Int (primary key)
package_name: String (indexed)
element_name: String (like "search_button", "send_button")
find_by: String ("id", "description", "text")
find_value: String (the actual ID/description/text to search for)
action: String ("click", "type", "scroll")
monitoring_rules

text

id: Int (primary key)
contact_name: String
app_package: String (nullable, if specific to an app)
action: String ("announce_immediately", "log_silently")
active: Boolean
logs_table

text

id: Int (primary key, auto-generate)
timestamp: Long
category: String ("SYSTEM", "DATA", "RUNTIME", "ERROR")
message: String
level: String ("INFO", "WARNING", "ERROR", "SUCCESS")
Keep last 500 entries.

Database Pre-population
The synonyms_table needs to come pre-populated with ~3000 entries. This can be done by:

Creating a pre-built database file (.db) in the assets folder
Using Room's createFromAsset() builder method
The database ships with the APK and is copied to the app's database location on first launch
Alternatively, the synonyms can be stored in a JSON file in assets, and on first launch, a database initialization routine parses the JSON and inserts all entries. This is easier to maintain and edit.

25. Logs Feature
Log Manager
A singleton class accessible throughout the app:

Methods:

logInfo(category, message) — normal info log
logSuccess(category, message) — successful action
logWarning(category, message) — warning
logError(category, message) — error
Each method creates a log entry and inserts it into logs_table via Room DAO.

What Gets Logged
At App Start:

"App launched"
"Accessibility Service: Enabled/Not Enabled"
"Permissions: X/Y granted"
"Contacts loaded: [number]" (if permission granted)
"Installed apps indexed: [number]"
"Synonym database: [number] entries"
"Learned commands: [number] entries"
"Failed commands pending: [number] entries"
During Service Run:

"Foreground Service started"
"SpeechRecognizer initialized"
"TTS Engine ready"
"State: SLEEPING"
Every speech recognition result: "Heard: '[text]'"
Wake word detection: "Wake word detected, activating"
Command processing: "Processing: '[text]'"
Phase resolution: "Resolved via Phase 2 (Synonym): WIFI_ON"
Action execution: "Executing: WIFI_ON"
Execution result: "WiFi turned on successfully"
TTS output: "Speaking: 'WiFi on kar diya'"
State transitions: "State: SLEEPING → ACTIVE"
Errors: "SpeechRecognizer error: ERROR_NO_MATCH, restarting"
Data Updates:

"New learned command stored: 'light off kar' → FLASHLIGHT_OFF"
"Gemini processed 5 failed commands, 3 resolved"
"Contact alias added: 'Mom' → 'Mother'"
Logs UI Implementation
RecyclerView in the bottom section of MainActivity
Each item shows: timestamp (HH:mm:ss format), category tag, message
Color coding: green background for SUCCESS, red for ERROR, yellow for WARNING, white/grey for INFO
Auto-scrolls to newest entry
LiveData observer on logs_table query ensures real-time updates
Clear button at top resets the table
Maximum 500 entries displayed (matching database limit)
Logs During Demo
The logs section will be extremely impressive during the college demo because the audience can SEE what Neo is doing internally:

text

14:30:01 [SYSTEM] Foreground Service started
14:30:01 [DATA] Contacts loaded: 245
14:30:01 [DATA] Apps indexed: 67
14:30:01 [DATA] Synonyms: 3000 entries
14:30:02 [RUNTIME] SpeechRecognizer listening...
14:30:05 [RUNTIME] Heard: "neo"
14:30:05 [RUNTIME] Wake word detected!
14:30:05 [RUNTIME] State: SLEEPING → ACTIVE
14:30:07 [RUNTIME] Heard: "wifi on karo"
14:30:07 [RUNTIME] Processing: "wifi on karo"
14:30:07 [RUNTIME] Resolved via Phase 2 (Synonym): WIFI_ON
14:30:07 [RUNTIME] Executing: WIFI_ON
14:30:08 [SUCCESS] WiFi turned on
14:30:08 [RUNTIME] Speaking: "WiFi on kar diya"
This gives a transparent view into Neo's brain and makes the presentation much more technical and impressive.

26. First Time Setup Flow
Detection
When service starts, check user_settings for key "first_setup_complete". If value is "false" or not found, trigger setup flow.

Setup Sequence
Step 1: Welcome
TTS speaks: "Namaste! Main Neo hun, aapka voice assistant. Pehle kuch setup karte hain."
Wait 2 seconds.

Step 2: Language Selection
TTS: "Aap Hindi mein baat karna chahte hain, English mein, ya dono mein?"
Listen for response.

If response contains "hindi" → set language_preference = "hi"
If response contains "english" → set language_preference = "en"
If response contains "dono" or "both" → set language_preference = "hinglish"
Save to user_settings.
Step 3: Wake Word Test
TTS: "Main ab so jaata hun. Mujhe jagane ke liye 'Neo' bolen."
Transition to SLEEPING state.
Wait for wake word.
When detected: TTS: "Bahut acche! Aapne mujhe sahi se jagaya."

Step 4: Security PIN
TTS: "Ek 4 digit ka PIN set karein. Yeh important actions ke liye use hoga."
Listen for 4 digits.
TTS: "Aapka PIN hai [digits]. Kya yeh sahi hai?"
Listen for confirmation.
If confirmed → hash and store. If not → repeat.

Step 5: Emergency Contact
TTS: "Kya aap ek emergency contact set karna chahte hain?"
Listen for response.
If yes → "Contact ka naam bolen" → resolve contact → store
If no → skip

Step 6: Quick Tutorial
TTS: "Setup ho gaya! Chalen kuch commands try karte hain."
Guide through 5 commands:

"Bolo: 'kitne baje hain'" → Neo tells time
"Bolo: 'flashlight on karo'" → flashlight turns on
"Bolo: 'volume full karo'" → volume maxes out
"Bolo: 'battery kitni hai'" → Neo tells battery level
"Bolo: 'Neo so ja'" → Neo goes to sleep
Step 7: Complete
TTS: "Bahut acche! Aap ready hain. Jab bhi zaroorat ho, 'Neo' bolo aur main hazir hun."
Set first_setup_complete = "true" in user_settings.

27. Error Handling
Voice Recognition Errors
ERROR_NO_MATCH → Silently restart listener (user was silent)
ERROR_SPEECH_TIMEOUT → Same as above
ERROR_AUDIO → Log error, wait 1 second, retry. If persistent: "Microphone mein problem hai"
ERROR_RECOGNIZER_BUSY → Wait 500ms, retry
ERROR_NETWORK → Continue in offline mode, log warning
ERROR_SERVER → Continue in offline mode, log warning
ERROR_CLIENT → Destroy and recreate SpeechRecognizer
Command Execution Errors
App not found → "Yeh app aapke phone mein nahi hai"
Contact not found → "Yeh contact nahi mila. Kya naam dobara bolenge?"
Permission missing → "Is kaam ke liye permission chahiye. Settings mein jaake allow karein"
Timeout during app control → "App respond nahi kar raha. Dobara try karte hain"
Unknown error → "Kuch gadbad ho gayi. Dobara try karein"
Service Recovery
If SpeechRecognizer crashes → recreate and restart
If TTS fails → reinitialize TTS engine
If AccessibilityService disconnects → log warning, features depending on it gracefully degrade
If database error → log error, continue with in-memory data
All errors are:
Logged in logs_table
Spoken to user in natural language (not technical jargon)
Recovered from automatically where possible
28. Testing Plan
Voice Command Testing
Test all 86 base commands
Test at least 10 synonyms per command
Test in Hindi, English, and Hinglish
Test with background noise (TV, people talking)
Test with multiple team members' voices
Test rapid sequential commands
Test compound commands ("WiFi on karo aur volume full karo")
Hardware Toggle Testing
Test each hardware toggle on/off
Verify state is correctly reported
Test toggles that require Settings panel + Accessibility
App Control Testing
Test opening top 10 apps
Test WhatsApp message sending end-to-end
Test screen reading on 5 different apps
Test navigation between apps
State Machine Testing
Test wake word detection from silence
Test 60-second timeout
Test manual activation methods
Test state recovery after errors
Permission Testing
Test with all permissions granted
Test with individual permissions denied (verify graceful degradation)
Stress Testing
Give 50 commands in rapid succession
Run Neo for full 3 hours to check stability
Test with phone screen off
Test while other apps are sending notifications
29. File Structure
text

neo/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/neo/assistant/
│   │   │   │   ├── di/                          # Dependency Injection
│   │   │   │   │   ├── AppModule.kt             # Hilt app module
│   │   │   │   │   ├── DatabaseModule.kt        # Room database provider
│   │   │   │   │   └── ServiceModule.kt         # Service dependencies
│   │   │   │   │
│   │   │   │   ├── ui/                          # UI Layer
│   │   │   │   │   ├── MainActivity.kt          # Single activity with 4 buttons + logs
│   │   │   │   │   ├── MainViewModel.kt         # ViewModel for UI state
│   │   │   │   │   └── LogsAdapter.kt           # RecyclerView adapter for logs
│   │   │   │   │
│   │   │   │   ├── service/                     # Services
│   │   │   │   │   ├── NeoForegroundService.kt  # Main brain service
│   │   │   │   │   ├── NeoAccessibilityService.kt # Accessibility service
│   │   │   │   │   └── NeoNotificationListener.kt # Notification listener
│   │   │   │   │
│   │   │   │   ├── voice/                       # Voice Engine
│   │   │   │   │   ├── SpeechRecognizerManager.kt # Continuous listening loop
│   │   │   │   │   ├── TTSManager.kt            # Text-to-speech wrapper
│   │   │   │   │   └── ResponseTemplates.kt     # Pre-defined responses in 3 languages
│   │   │   │   │
│   │   │   │   ├── brain/                       # Command Brain
│   │   │   │   │   ├── CommandBrain.kt          # Main 6-phase engine
│   │   │   │   │   ├── PreProcessor.kt          # Phase 1: text cleaning
│   │   │   │   │   ├── SynonymLookup.kt         # Phase 2: HashMap lookup
│   │   │   │   │   ├── PatternMatcher.kt        # Phase 3: regex patterns
│   │   │   │   │   ├── LearnedMemory.kt         # Phase 4: database lookup
│   │   │   │   │   ├── FuzzyMatcher.kt          # Phase 5: Levenshtein distance
│   │   │   │   │   ├── ContextResolver.kt       # Phase 6: context-based resolution
│   │   │   │   │   └── CommandAction.kt         # Sealed class of all commands
│   │   │   │   │
│   │   │   │   ├── executor/                    # Action Execution
│   │   │   │   │   ├── ActionExecutor.kt        # Main executor routing
│   │   │   │   │   ├── HardwareController.kt    # WiFi, BT, flash, volume, etc.
│   │   │   │   │   ├── AppController.kt         # Opening and controlling apps
│   │   │   │   │   ├── CallController.kt        # Phone calls
│   │   │   │   │   ├── SmsController.kt         # SMS operations
│   │   │   │   │   ├── MediaController.kt       # Media playback control
│   │   │   │   │   ├── NavigationController.kt  # Location and navigation
│   │   │   │   │   ├── UtilityController.kt     # Calculator, alarm, timer, etc.
│   │   │   │   │   └── ScreenReader.kt          # Screen reading logic
│   │   │   │   │
│   │   │   │   ├── accessibility/               # Accessibility Layer
│   │   │   │   │   ├── AccessibilityBridge.kt   # Shared singleton for communication
│   │   │   │   │   ├── NodeTraverser.kt         # UI tree traversal logic
│   │   │   │   │   ├── ElementFinder.kt         # Find UI elements by various criteria
│   │   │   │   │   └── TaskEngine.kt            # Multi-step task executor
│   │   │   │   │
│   │   │   │   ├── state/                       # State Machine
│   │   │   │   │   ├── StateMachine.kt          # State management
│   │   │   │   │   └── NeoState.kt              # State enum/sealed class
│   │   │   │   │
│   │   │   │   ├── data/                        # Data Layer
│   │   │   │   │   ├── db/
│   │   │   │   │   │   ├── NeoDatabase.kt       # Room database
│   │   │   │   │   │   ├── SynonymDao.kt        # Synonym table DAO
│   │   │   │   │   │   ├── LearnedCommandDao.kt # Learned commands DAO
│   │   │   │   │   │   ├── FailedCommandDao.kt  # Failed commands DAO
│   │   │   │   │   │   ├── CommandHistoryDao.kt # History DAO
│   │   │   │   │   │   ├── ContactAliasDao.kt   # Contact aliases DAO
│   │   │   │   │   │   ├── UserSettingsDao.kt   # Settings DAO
│   │   │   │   │   │   ├── AppProfileDao.kt     # App profiles DAO
│   │   │   │   │   │   ├── MonitoringRuleDao.kt # Monitoring rules DAO
│   │   │   │   │   │   └── LogDao.kt            # Logs DAO
│   │   │   │   │   │
│   │   │   │   │   ├── model/                   # Data models/entities
│   │   │   │   │   │   ├── Synonym.kt
│   │   │   │   │   │   ├── LearnedCommand.kt
│   │   │   │   │   │   ├── FailedCommand.kt
│   │   │   │   │   │   ├── CommandHistory.kt
│   │   │   │   │   │   ├── ContactAlias.kt
│   │   │   │   │   │   ├── UserSetting.kt
│   │   │   │   │   │   ├── AppProfile.kt
│   │   │   │   │   │   ├── MonitoringRule.kt
│   │   │   │   │   │   └── LogEntry.kt
│   │   │   │   │   │
│   │   │   │   │   └── repository/
│   │   │   │   │       ├── SynonymRepository.kt
│   │   │   │   │       ├── CommandRepository.kt
│   │   │   │   │       ├── ContactRepository.kt
│   │   │   │   │       ├── SettingsRepository.kt
│   │   │   │   │       └── LogRepository.kt
│   │   │   │   │
│   │   │   │   ├── gemini/                      # Gemini Integration
│   │   │   │   │   ├── GeminiApi.kt             # Retrofit interface
│   │   │   │   │   ├── GeminiWorker.kt          # WorkManager worker
│   │   │   │   │   └── GeminiPromptBuilder.kt   # Prompt construction
│   │   │   │   │
│   │   │   │   ├── contacts/                    # Contact Management
│   │   │   │   │   ├── ContactLoader.kt         # Load contacts from system
│   │   │   │   │   ├── ContactMatcher.kt        # Fuzzy name matching
│   │   │   │   │   └── ContactIndex.kt          # In-memory contact index
│   │   │   │   │
│   │   │   │   ├── notification/                # Notification Handling
│   │   │   │   │   ├── NotificationHandler.kt   # Process incoming notifications
│   │   │   │   │   └── NotificationAnnouncer.kt # Format and speak notifications
│   │   │   │   │
│   │   │   │   ├── security/                    # Security
│   │   │   │   │   ├── PinManager.kt            # PIN hash/verify
│   │   │   │   │   └── SecureActionGuard.kt     # Check if action needs PIN
│   │   │   │   │
│   │   │   │   ├── setup/                       # First Time Setup
│   │   │   │   │   └── SetupFlowManager.kt      # Manages the setup conversation
│   │   │   │   │
│   │   │   │   ├── util/                        # Utilities
│   │   │   │   │   ├── LogManager.kt            # Logging singleton
│   │   │   │   │   ├── PermissionHelper.kt      # Permission checking/requesting
│   │   │   │   │   ├── LevenshteinDistance.kt    # String distance calculation
│   │   │   │   │   ├── SoundexAlgorithm.kt      # Phonetic matching
│   │   │   │   │   ├── ExpressionEvaluator.kt   # Calculator math parser
│   │   │   │   │   └── Constants.kt             # App-wide constants
│   │   │   │   │
│   │   │   │   └── NeoApplication.kt            # Application class with Hilt
│   │   │   │
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml        # Main screen layout
│   │   │   │   │   └── item_log.xml             # Log entry layout
│   │   │   │   │
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml              # English strings
│   │   │   │   │   ├── colors.xml               # Color definitions
│   │   │   │   │   ├── themes.xml               # Dark theme
│   │   │   │   │   └── dimens.xml               # Dimensions
│   │   │   │   │
│   │   │   │   ├── values-hi/
│   │   │   │   │   └── strings.xml              # Hindi strings
│   │   │   │   │
│   │   │   │   ├── xml/
│   │   │   │   │   └── accessibility_config.xml # Accessibility service config
│   │   │   │   │
│   │   │   │   ├── raw/
│   │   │   │   │   ├── activation_beep.mp3      # Wake word confirmation sound
│   │   │   │   │   ├── success_tone.mp3         # Success sound
│   │   │   │   │   └── error_tone.mp3           # Error sound
│   │   │   │   │
│   │   │   │   └── drawable/
│   │   │   │       ├── ic_mic.xml               # Microphone icon
│   │   │   │       ├── ic_accessibility.xml     # Accessibility icon
│   │   │   │       ├── ic_permission.xml        # Permission icon
│   │   │   │       ├── ic_stop.xml              # Stop icon
│   │   │   │       └── ic_notification.xml      # Notification icon
│   │   │   │
│   │   │   ├── assets/
│   │   │   │   └── synonyms.json                # Pre-built synonym database
│   │   │   │
│   │   │   └── AndroidManifest.xml              # All declarations and permissions
│   │   │
│   │   └── test/                                # Unit tests
│   │       └── java/com/neo/assistant/
│   │           ├── brain/
│   │           │   ├── PreProcessorTest.kt
│   │           │   ├── SynonymLookupTest.kt
│   │           │   ├── PatternMatcherTest.kt
│   │           │   └── FuzzyMatcherTest.kt
│   │           └── util/
│   │               ├── LevenshteinDistanceTest.kt
│   │               └── ExpressionEvaluatorTest.kt
│   │
│   └── build.gradle.kts                         # App-level build config
│
├── build.gradle.kts                             # Project-level build config
├── settings.gradle.kts                          # Project settings
├── gradle.properties                            # Gradle properties
├── requirements.md                              # Project requirements document
└── plan.md                                      # This implementation plan
30. Development Tools and Recommendations
IDE and Agent Setup
Primary IDE: Antigravity IDE with AI Agent feature

Agent Instructions:
When giving the command to the AI agent, use this prompt:
"Read requirements.md for the project vision and feature specifications. Read plan.md for the complete technical implementation plan. Create a task breakdown file (tasks.md) that lists every implementation task in order of dependency. Then start building the application following the plan.md file structure and architecture. Build one component at a time, testing each before moving to the next. Start with project setup, then database, then UI, then services, then voice engine, then command brain, then action executors."

Recommended MCPs (Model Context Protocols) - Free
Android Documentation MCP

If available, connect an MCP that gives the agent access to Android documentation
This helps the agent write correct API calls for AccessibilityService, SpeechRecognizer, etc.
File System MCP

The agent needs to read and write files in your project directory
Make sure the file system MCP is enabled and has access to the project folder
Git MCP

If available, connect Git MCP so the agent can commit after each major feature
This gives you rollback points if something breaks
Recommended Extensions/Plugins
For Antigravity IDE:

Kotlin language support
Android development extensions
Gradle integration
XML formatting for layouts and manifest
For Faster Development:

If the agent supports it, enable auto-import for Kotlin
Enable lint checking to catch permission and API issues early
Build Strategy for the Agent
The agent should build in this exact order because of dependencies:

Phase 1: Foundation (Build first, everything depends on this)

Create project with correct build.gradle configuration and all dependencies
Set up AndroidManifest.xml with all permissions, service declarations
Create NeoApplication.kt with Hilt setup
Create all data models (entities)
Create Room database with all DAOs
Create the synonym JSON file with at least 500 entries for initial testing
Create LogManager singleton
Create Constants.kt with all command identifiers
Phase 2: UI (Build second, needed for manual testing)
9. Create activity_main.xml layout
10. Create item_log.xml layout
11. Create MainActivity.kt with button logic
12. Create MainViewModel.kt
13. Create LogsAdapter.kt
14. Implement Button 1 (Accessibility enable)
15. Implement Button 2 (Permissions)
16. Implement Logs section

Phase 3: Core Services (Build third, the brain)
17. Create NeoForegroundService.kt (skeleton)
18. Create AccessibilityBridge.kt singleton
19. Create NeoAccessibilityService.kt (skeleton)
20. Create accessibility_config.xml
21. Implement service start/stop from Button 3 and Button 4
22. Create notification channel and foreground notification

Phase 4: Voice Engine (Build fourth, input/output)
23. Create SpeechRecognizerManager.kt with continuous listening loop
24. Create TTSManager.kt
25. Create ResponseTemplates.kt
26. Integrate SpeechRecognizer with foreground service
27. Integrate TTS with foreground service
28. Test: voice input → logs show recognized text → TTS speaks back

Phase 5: State Machine (Build fifth, controls everything)
29. Create NeoState.kt
30. Create StateMachine.kt
31. Implement SLEEPING state with wake word detection
32. Implement ACTIVE state with timeout
33. Implement state transitions
34. Test: say "Neo" → activates → 60s timeout → sleeps

Phase 6: Command Brain (Build sixth, the intelligence)
35. Create PreProcessor.kt
36. Create SynonymLookup.kt with HashMap loading from database
37. Create PatternMatcher.kt with regex patterns
38. Create LearnedMemory.kt
39. Create FuzzyMatcher.kt with Levenshtein distance
40. Create ContextResolver.kt
41. Create CommandBrain.kt combining all 6 phases
42. Create CommandAction.kt sealed class
43. Test: say "Neo wifi on karo" → logs show all processing phases → command resolved

Phase 7: Action Executors (Build seventh, makes things happen)
44. Create ActionExecutor.kt (router)
45. Create HardwareController.kt — implement all hardware toggles
46. Test: voice command → hardware actually changes
47. Create AppController.kt — app launching
48. Create CallController.kt — making and managing calls
49. Create SmsController.kt — SMS operations
50. Create MediaController.kt — media control
51. Create UtilityController.kt — calculator, alarm, timer, battery, time
52. Create NavigationController.kt — location and maps

Phase 8: Accessibility Features (Build eighth, advanced control)
53. Implement NeoAccessibilityService.kt fully — event monitoring, node traversal
54. Create NodeTraverser.kt
55. Create ElementFinder.kt
56. Create ScreenReader.kt
57. Create TaskEngine.kt for multi-step tasks
58. Implement WhatsApp message sending task
59. Test: voice command to send WhatsApp message → works end to end

Phase 9: Notification and Monitoring (Build ninth)
60. Create NeoNotificationListener.kt
61. Create NotificationHandler.kt
62. Create NotificationAnnouncer.kt
63. Implement chat monitoring
64. Test: receive WhatsApp notification → Neo announces it

Phase 10: Advanced Features (Build tenth)
65. Create PinManager.kt and SecureActionGuard.kt
66. Create SetupFlowManager.kt for first-time setup
67. Create GeminiApi.kt, GeminiWorker.kt, GeminiPromptBuilder.kt
68. Create ContactLoader.kt, ContactMatcher.kt, ContactIndex.kt
69. Implement monitoring rules

Phase 11: Polish (Build last)
70. Add all error handling
71. Add activation beep and sound effects
72. Add vibration feedback patterns
73. Complete the synonym database to 3000 entries
74. Test all features end to end
75. Fix bugs found during testing

Tips for Working with AI Agent
One file at a time: Don't let the agent try to create everything at once. Guide it file by file.

Test frequently: After every major component, run the app and verify it works before moving on.

Provide context: When the agent is building a new file, remind it of related files it already created. "You already created AccessibilityBridge.kt, now create NeoAccessibilityService.kt that uses it."

Synonym database: The agent should create the synonyms.json file gradually. Start with 10 commands × 10 synonyms each (100 entries). Test. Then expand to full 3000.

Handle Android version differences: Remind the agent to add version checks (Build.VERSION.SDK_INT) for APIs that differ between Android versions, especially for WiFi, Bluetooth, and storage permissions.

Keep the manifest updated: Every time a new service, permission, or receiver is added, make sure the manifest is updated.

Test on real device: This app CANNOT be tested on emulator because it needs real microphone, real accessibility service, real notifications, real hardware toggles. Always test on a physical Android phone.

Free Tools That Help
Android Debug Bridge (ADB): For debugging services and permissions from command line
Layout Inspector: Built into Android Studio, helps debug UI issues
Logcat: Essential for debugging — filter by your app's tag to see all logs
Scrcpy: Free tool to mirror phone screen to computer — useful during demo to show phone screen on projector
OBS Studio: Free screen recording — record your demo for submission
Important Warnings for the Agent
SpeechRecognizer MUST be created on the main thread. The agent must not try to create it in a coroutine or background thread.

AccessibilityService has its own lifecycle managed by Android. It cannot be started or stopped programmatically. Only enabled/disabled by user in settings.

Room database operations MUST happen on background threads. Never query database on main thread.

Foreground service MUST call startForeground() within 5 seconds of being started, otherwise Android kills it.

On Android 13+, POST_NOTIFICATIONS permission must be granted before showing any notification including the foreground service notification.

The app must handle the case where TTS engine is not ready yet when the first speech request comes. Queue the speech and play when ready.

SpeechRecognizer.startListening() cannot be called while the recognizer is already listening. Must wait for onResults or onError before calling startListening again.

Summary
This plan covers every aspect of building Neo:

Single screen UI with 4 buttons + logs
Foreground service as the always-running brain
Google SpeechRecognizer for voice input (wake word + commands)
Android TTS for voice output
4-state machine controlling all flow
6-phase Command Brain for intelligent command resolution
AccessibilityService for universal app control and screen reading
NotificationListenerService for notification handling
Room database for persistent storage and learning
Gemini API for background learning from failures
86+ commands across 9 categories with ~3000 synonyms
Real-time logs showing everything happening inside Neo
The app is designed to work perfectly for a 1-3 hour college demo with all testers using Gboard-equipped Android phones.