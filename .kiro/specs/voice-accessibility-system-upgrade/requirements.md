# Requirements Document

## Introduction

This document specifies requirements for upgrading the voice-controlled accessibility application for blind users. The system enables hands-free phone control through voice commands with intelligent state detection, multi-step contextual navigation, and comprehensive app integration. The upgrade focuses on battery efficiency, security verification, seamless online/offline voice recognition, and enhanced camera/call management capabilities.

## Glossary

- **System**: The voice-controlled accessibility application
- **User**: A blind or visually impaired person using the application
- **Voice_Recognition_Engine**: Android SpeechRecognizer API component that converts speech to text
- **Accessibility_Service**: Android AccessibilityService that performs UI automation
- **Command_Processor**: Component that interprets voice commands and executes actions
- **State_Detector**: Component that identifies current phone state (NORMAL, CONSUMING_CONTENT, IN_CALL, CAMERA_ACTIVE)
- **Verification_Code**: User-defined numeric code required for sensitive operations
- **Navigation_Context**: Tracked information about current app, screen, user, and UI element
- **Volume_Button_Toggle**: Simultaneous press of both volume buttons to activate/deactivate listening
- **Online_Mode**: Voice recognition using cloud-based Google services
- **Offline_Mode**: Voice recognition using on-device models
- **ACTIVE_State**: System is listening for voice commands
- **INACTIVE_State**: System is not listening, consuming zero battery
- **Camera2_API**: Android camera control interface
- **TelephonyManager**: Android component for call management
- **Audio_Feedback**: Text-to-speech announcements for all actions
- **Fuzzy_Matcher**: Component that handles pronunciation variations and speech recognition errors
- **Command_Memory**: Component that tracks command history for context resolution

## Requirements

### Requirement 1: Volume Button Toggle System

**User Story:** As a blind user, I want to activate and deactivate voice listening by pressing both volume buttons simultaneously, so that I can control when the system listens without needing to see the screen.

#### Acceptance Criteria

1. WHEN both volume buttons are pressed simultaneously (within 600ms), THE System SHALL detect this as a toggle gesture
2. WHEN the System is in INACTIVE_State and the toggle gesture is detected, THE System SHALL transition to ACTIVE_State
3. WHEN the System is in ACTIVE_State and the toggle gesture is detected, THE System SHALL transition to INACTIVE_State
4. WHEN the System transitions to ACTIVE_State, THE System SHALL provide audio feedback announcing "Listening"
5. WHEN the System transitions to INACTIVE_State, THE System SHALL provide haptic feedback without audio announcement
6. WHEN the System is in INACTIVE_State, THE Voice_Recognition_Engine SHALL consume zero battery power
7. WHEN the System is in ACTIVE_State, THE Voice_Recognition_Engine SHALL continuously listen for voice commands
8. WHEN the phone screen is locked or off, THE System SHALL still detect volume button toggle gestures
9. WHEN the toggle gesture is detected, THE System SHALL not interfere with normal volume control functionality

### Requirement 2: Automatic Online/Offline Voice Recognition

**User Story:** As a blind user, I want voice recognition to work both with and without internet connectivity, so that I can use the app anywhere without worrying about network availability.

#### Acceptance Criteria

1. THE System SHALL use Android SpeechRecognizer API for all voice recognition
2. WHEN internet connectivity is available, THE Voice_Recognition_Engine SHALL operate in Online_Mode
3. WHEN internet connectivity is unavailable, THE Voice_Recognition_Engine SHALL operate in Offline_Mode
4. WHEN network connectivity changes from available to unavailable, THE Voice_Recognition_Engine SHALL automatically switch to Offline_Mode within 2 seconds
5. WHEN network connectivity changes from unavailable to available, THE Voice_Recognition_Engine SHALL automatically switch to Online_Mode within 2 seconds
6. WHEN the System starts for the first time, THE System SHALL check if offline speech models are installed on the device
7. WHEN offline speech models are not installed and internet is unavailable, THE System SHALL provide audio feedback "Offline voice recognition requires downloading speech models from Google app settings"
8. THE System SHALL maintain an APK size under 10MB
9. THE System SHALL NOT bundle any voice recognition models in the APK
10. FOR ALL voice commands, THE Command_Processor SHALL produce identical results in both Online_Mode and Offline_Mode
11. WHEN switching between Online_Mode and Offline_Mode, THE System SHALL provide audio feedback announcing the current mode

### Requirement 3: Smart State Detection and Verification System

**User Story:** As a blind user, I want the system to require verification for sensitive actions when I'm watching videos or on calls, so that accidental voice commands don't disrupt my activities.

#### Acceptance Criteria

1. THE State_Detector SHALL continuously monitor phone state and classify it as one of: NORMAL, CONSUMING_CONTENT, IN_CALL, or CAMERA_ACTIVE
2. WHEN no video is playing, no photo is being viewed, no call is active, and camera is not open, THE State_Detector SHALL set state to NORMAL
3. WHEN a video is playing or a photo is being viewed, THE State_Detector SHALL set state to CONSUMING_CONTENT
4. WHEN a phone call is active (incoming, outgoing, or connected), THE State_Detector SHALL set state to IN_CALL
5. WHEN the camera application is open, THE State_Detector SHALL set state to CAMERA_ACTIVE
6. WHEN state is NORMAL, THE Command_Processor SHALL execute all commands without verification
7. WHEN state is CONSUMING_CONTENT, THE Command_Processor SHALL require Verification_Code before executing any command
8. WHEN state is IN_CALL, THE Command_Processor SHALL require Verification_Code before executing any command
9. WHEN state is CAMERA_ACTIVE and User issues a capture or record command, THE Command_Processor SHALL require Verification_Code
10. WHEN state is CAMERA_ACTIVE and User issues a non-capture command (open camera, switch camera), THE Command_Processor SHALL execute without verification
11. THE System SHALL allow User to configure a custom Verification_Code during initial setup
12. THE Verification_Code SHALL be a 4-digit numeric code by default
13. WHEN verification is required and User provides incorrect Verification_Code, THE System SHALL provide audio feedback "Incorrect code" and reject the command
14. WHEN verification is required and User provides correct Verification_Code, THE System SHALL execute the command and provide audio confirmation
15. WHEN state transitions from IN_CALL to NORMAL (call ends), THE State_Detector SHALL automatically update state within 1 second

### Requirement 4: Multi-Step Contextual Navigation

**User Story:** As a blind user, I want to navigate through apps using conversational commands with pronouns, so that I can interact naturally without repeating full names every time.

#### Acceptance Criteria

1. THE Navigation_Context SHALL track the current app name, screen name, focused user profile, and focused UI element
2. WHEN User issues a command to open an app, THE Navigation_Context SHALL update the current app name
3. WHEN User issues a command referencing a user (e.g., "search John"), THE Navigation_Context SHALL store "John" as the focused user
4. WHEN User issues a command with a pronoun ("his profile", "their photos", "that video"), THE Command_Processor SHALL resolve the pronoun using Navigation_Context
5. WHEN Navigation_Context contains a focused user and User says "open his profile", THE Command_Processor SHALL open the profile of the focused user
6. WHEN Navigation_Context contains a focused user and User says "scroll his photos", THE Command_Processor SHALL navigate to and scroll the photos of the focused user
7. THE Command_Memory SHALL maintain a history of the last 10 executed commands
8. WHEN User issues a command that requires context and Navigation_Context is empty, THE System SHALL provide audio feedback "Please specify who or what you're referring to"
9. WHEN User issues a chained command sequence (e.g., "Open Instagram, search John, open his profile, scroll photos"), THE System SHALL execute each step sequentially and maintain context between steps
10. WHEN User switches to a different app, THE Navigation_Context SHALL clear the focused user and UI element but retain command history
11. THE System SHALL support pronoun resolution for: "his", "her", "their", "that", "this", "it"

### Requirement 5: App Navigation Support

**User Story:** As a blind user, I want to control popular apps through voice commands, so that I can use social media, messaging, and phone features without touching the screen.

#### Acceptance Criteria

1. THE System SHALL support voice navigation for Instagram with actions: open app, search users, view profiles, scroll reels, scroll photos, scroll stories, open direct messages
2. THE System SHALL support voice navigation for WhatsApp with actions: open chats, search contacts, send messages, view status, make voice calls, make video calls
3. THE System SHALL support voice navigation for Phone app with actions: make calls, answer calls, reject calls, end calls, mute/unmute, add call, merge calls, start call recording
4. THE System SHALL support voice navigation for Facebook with actions: navigate feed, open groups, browse marketplace, watch videos, view profiles
5. THE System SHALL support voice navigation for YouTube with actions: search videos, play/pause, navigate channels, browse playlists, adjust playback speed
6. THE System SHALL support voice navigation for Twitter/X with actions: view timeline, read tweets, view profiles, join spaces
7. THE System SHALL support voice navigation for at least 20 different applications
8. WHEN User issues an app-specific command and the target app is not currently open, THE System SHALL first open the app then execute the command
9. WHEN User issues an app-specific command and the target app is already open, THE System SHALL execute the command directly
10. FOR ALL app navigation commands, THE Accessibility_Service SHALL use UI element identification to locate and interact with the correct buttons and fields

### Requirement 6: Camera Control with Verification

**User Story:** As a blind user, I want to control the camera through voice commands with safety verification, so that I can take photos and videos independently without accidentally capturing sensitive content.

#### Acceptance Criteria

1. WHEN User says "open camera", THE System SHALL launch the camera application without requiring verification
2. WHEN User says "take photo" and State_Detector state is CAMERA_ACTIVE, THE System SHALL require Verification_Code before capturing
3. WHEN User says "take selfie" and State_Detector state is CAMERA_ACTIVE, THE System SHALL require Verification_Code, then switch to front camera, then capture photo
4. WHEN User says "switch camera" and State_Detector state is CAMERA_ACTIVE, THE System SHALL require Verification_Code before switching between front and back cameras
5. WHEN User says "start recording" and State_Detector state is CAMERA_ACTIVE, THE System SHALL require Verification_Code before starting video recording
6. WHEN User says "stop recording" and video is recording, THE System SHALL require Verification_Code before stopping video recording
7. WHEN User says "flash on" or "flash off" and State_Detector state is CAMERA_ACTIVE, THE System SHALL require Verification_Code before toggling flash
8. WHEN User says "zoom in" or "zoom out" and State_Detector state is CAMERA_ACTIVE, THE System SHALL require Verification_Code before adjusting zoom level
9. THE System SHALL use Camera2_API for all camera control operations
10. WHEN taking a selfie, THE System SHALL use face detection to provide audio cues "face detected" and "face centered"
11. WHEN taking a selfie, THE System SHALL provide a countdown "3, 2, 1" before capturing
12. FOR ALL camera actions, THE System SHALL provide audio feedback confirming the action (e.g., "Photo captured", "Recording started", "Flash enabled")
13. WHEN a camera action fails, THE System SHALL provide audio feedback describing the error

### Requirement 7: Call Management with Verification

**User Story:** As a blind user, I want to manage phone calls through voice commands with verification during active calls, so that I can control calls hands-free while preventing accidental commands during conversations.

#### Acceptance Criteria

1. WHEN User says "answer call" and a call is incoming, THE System SHALL answer the call without requiring verification
2. WHEN User says "reject call" and a call is incoming, THE System SHALL reject the call without requiring verification
3. WHEN a call becomes active (connected), THE State_Detector SHALL set state to IN_CALL
4. WHEN state is IN_CALL and User issues any command, THE Command_Processor SHALL require Verification_Code as a prefix to the command
5. WHEN state is IN_CALL and User says "[Verification_Code] end call", THE System SHALL end the active call
6. WHEN state is IN_CALL and User says "[Verification_Code] mute", THE System SHALL mute the microphone
7. WHEN state is IN_CALL and User says "[Verification_Code] unmute", THE System SHALL unmute the microphone
8. WHEN state is IN_CALL and User says "[Verification_Code] add call", THE System SHALL initiate adding another call to create a conference
9. WHEN state is IN_CALL and User says "[Verification_Code] merge calls", THE System SHALL merge multiple calls into a conference call
10. WHEN state is IN_CALL and User says "[Verification_Code] switch call", THE System SHALL switch between active calls
11. WHEN state is IN_CALL and User says "[Verification_Code] start recording", THE System SHALL start call recording
12. WHEN a call ends, THE State_Detector SHALL transition state from IN_CALL to NORMAL within 1 second
13. THE System SHALL use TelephonyManager for all call management operations
14. FOR ALL call management actions, THE System SHALL provide audio feedback confirming the action

### Requirement 8: Smart Fuzzy Command Matching

**User Story:** As a blind user, I want the system to understand my commands even with pronunciation variations and speech recognition errors, so that I don't have to repeat commands multiple times.

#### Acceptance Criteria

1. THE Fuzzy_Matcher SHALL accept multiple pronunciation variations for each command
2. WHEN User says "open Instagram" or "open Insta" or "launch Instagram", THE Fuzzy_Matcher SHALL map all variations to the same OPEN_INSTAGRAM command
3. THE Fuzzy_Matcher SHALL use context from Navigation_Context to disambiguate commands with multiple meanings
4. WHEN User says "open" in the context of Instagram profile screen, THE Fuzzy_Matcher SHALL interpret it as "open profile" not "open app"
5. THE Fuzzy_Matcher SHALL support commands in English, Hindi, and Hinglish (mixed English-Hindi)
6. WHEN Voice_Recognition_Engine produces text with speech recognition errors, THE Fuzzy_Matcher SHALL use edit distance algorithms to find the closest matching command
7. THE Fuzzy_Matcher SHALL maintain a synonym database mapping command variations to canonical commands
8. WHEN User issues a command that matches multiple possible intents with similar confidence scores, THE System SHALL ask for clarification with audio feedback listing the options
9. THE Fuzzy_Matcher SHALL learn from User corrections and improve matching accuracy over time
10. FOR ALL matched commands, THE Fuzzy_Matcher SHALL log the confidence score for debugging purposes

### Requirement 9: Comprehensive Audio Feedback System

**User Story:** As a blind user, I want audio confirmation for every action the system performs, so that I always know what's happening without needing to see the screen.

#### Acceptance Criteria

1. WHEN System transitions to ACTIVE_State, THE System SHALL announce "Listening"
2. WHEN System transitions to INACTIVE_State, THE System SHALL provide haptic feedback only (no audio)
3. WHEN Voice_Recognition_Engine switches to Online_Mode, THE System SHALL announce "Online mode"
4. WHEN Voice_Recognition_Engine switches to Offline_Mode, THE System SHALL announce "Offline mode"
5. WHEN State_Detector changes state, THE System SHALL announce the new state (e.g., "Call active, verification required")
6. WHEN a camera action completes successfully, THE System SHALL announce the action (e.g., "Photo captured", "Recording started")
7. WHEN face detection detects a face during selfie mode, THE System SHALL announce "Face detected"
8. WHEN face detection determines face is centered during selfie mode, THE System SHALL announce "Face centered"
9. WHEN taking a selfie, THE System SHALL count down "3, 2, 1" before capturing
10. WHEN a call management action completes, THE System SHALL announce the action (e.g., "Call muted", "Recording started")
11. WHEN a command fails to execute, THE System SHALL announce the error with a descriptive message
12. WHEN verification is required and User provides incorrect code, THE System SHALL announce "Incorrect code"
13. WHEN verification is required and User provides correct code, THE System SHALL announce "Verified" before executing the command
14. THE System SHALL use Android TextToSpeech API for all audio announcements
15. THE System SHALL pause voice recognition while audio feedback is playing to prevent the system from hearing its own voice

### Requirement 10: Battery and Performance Optimization

**User Story:** As a blind user, I want the app to use minimal battery when not actively listening, so that I can use my phone throughout the day without frequent charging.

#### Acceptance Criteria

1. WHEN System is in INACTIVE_State, THE Voice_Recognition_Engine SHALL not run and SHALL consume zero CPU cycles
2. WHEN System is in INACTIVE_State, THE System SHALL only monitor for volume button toggle gestures
3. WHEN System is in ACTIVE_State, THE Voice_Recognition_Engine SHALL use efficient wake word detection to minimize CPU usage
4. THE Voice_Recognition_Engine SHALL use a maximum of 5% CPU when actively listening in ACTIVE_State
5. THE System SHALL not maintain any persistent network connections when in INACTIVE_State
6. THE System SHALL not perform any background processing when in INACTIVE_State
7. WHEN System is in ACTIVE_State and no speech is detected for 30 seconds, THE System SHALL enter a low-power listening mode
8. WHEN System is in low-power listening mode and speech is detected, THE System SHALL resume full listening mode within 200ms
9. THE Accessibility_Service SHALL only query UI elements when executing commands, not continuously
10. THE State_Detector SHALL use efficient system callbacks to detect state changes rather than polling

