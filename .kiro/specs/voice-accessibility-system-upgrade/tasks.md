# Implementation Plan: Voice Accessibility System Upgrade

## Overview

This implementation plan breaks down the voice accessibility system upgrade into incremental coding tasks. Each task builds on previous work and includes property-based tests to validate correctness. The implementation follows a bottom-up approach: core components first, then integration, then advanced features.

## Tasks

- [x] 1. Implement Volume Toggle Detector
  - Create `VolumeToggleDetector` class with state machine for detecting simultaneous button presses
  - Implement timing logic (600ms window) for gesture detection
  - Add listener interface for toggle events
  - Integrate with `AutomationForegroundService` to work when screen is off
  - _Requirements: 1.1, 1.2, 1.3, 1.8_

- [ ]* 1.1 Write property test for volume toggle state transitions
  - **Property 1: Volume Toggle State Transition**
  - **Validates: Requirements 1.1, 1.2, 1.3**

- [x] 2. Enhance Voice Recognition Engine with Mode Management
  - [x] 2.1 Add network connectivity monitoring to `GoogleVoiceCommandManager`
    - Implement `ConnectivityManager` callback for network changes
    - Add mode detection logic (online vs offline)
    - Add offline model availability checking
    - _Requirements: 2.2, 2.3, 2.4, 2.5_
  
  - [ ]* 2.2 Write property test for recognition mode selection
    - **Property 5: Recognition Mode Selection**
    - **Validates: Requirements 2.2, 2.3**
  
  - [x] 2.3 Implement automatic mode switching
    - Add mode switch logic with 2-second timeout
    - Add mode change announcements via audio feedback
    - Handle offline model not installed error
    - _Requirements: 2.4, 2.5, 2.6, 2.7, 2.11_
  
  - [ ]* 2.4 Write property test for automatic mode switching
    - **Property 6: Automatic Mode Switching**
    - **Validates: Requirements 2.4, 2.5**
  
  - [ ]* 2.5 Write property test for command consistency across modes
    - **Property 7: Command Consistency Across Modes**
    - **Validates: Requirements 2.10**

- [x] 3. Checkpoint - Ensure volume toggle and voice recognition tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement Phone State Detector
  - [x] 4.1 Create `PhoneStateDetector` class with state classification logic
    - Implement state enum (NORMAL, CONSUMING_CONTENT, IN_CALL, CAMERA_ACTIVE)
    - Add `PhoneStateListener` for call state monitoring
    - Add `AudioManager` monitoring for media playback
    - Add `CameraManager` availability callback for camera detection
    - Add state change listener interface
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_
  
  - [ ]* 4.2 Write property test for state classification
    - **Property 9: State Classification**
    - **Validates: Requirements 3.1, 3.3, 3.4, 3.5**
  
  - [x] 4.3 Implement state transition timing
    - Add 1-second timeout for call end state transition
    - Add state change announcements
    - _Requirements: 3.15, 7.12_
  
  - [ ]* 4.4 Write property test for call end state transition
    - **Property 12: Call End State Transition**
    - **Validates: Requirements 3.15, 7.12**

- [x] 5. Implement Verification System
  - [x] 5.1 Create `VerificationSystem` class
    - Implement verification code storage using encrypted SharedPreferences
    - Add code validation logic
    - Add code extraction from commands (handle spoken numbers)
    - Add attempt tracking and lockout mechanism
    - _Requirements: 3.11, 3.12, 3.13, 3.14_
  
  - [ ]* 5.2 Write property test for verification requirements by state
    - **Property 10: Verification Requirements by State**
    - **Validates: Requirements 3.6, 3.7, 3.8, 3.9, 3.10**
  
  - [ ]* 5.3 Write property test for verification code validation
    - **Property 11: Verification Code Validation**
    - **Validates: Requirements 3.13, 3.14**

- [x] 6. Integrate State Detection and Verification with Command Processor
  - [x] 6.1 Update `CommandProcessor` to use `PhoneStateDetector`
    - Add state detector instance
    - Check verification requirements before command execution
    - Add verification code extraction and validation
    - Update in-call command handling to use verification system
    - _Requirements: 3.6, 3.7, 3.8, 3.9, 3.10, 7.4_
  
  - [x] 6.2 Add audio feedback for verification
    - Announce "Incorrect code" on failed verification
    - Announce "Verified" on successful verification
    - Announce state changes requiring verification
    - _Requirements: 3.13, 3.14, 9.12, 9.13_

- [x] 7. Checkpoint - Ensure state detection and verification tests pass
  - Ensure all tests pass, ask the user if questions arise.


- [x] 8. Implement Navigation Context Tracker
  - [x] 8.1 Create `NavigationContextTracker` class
    - Implement context data model (app, screen, focused user, focused element)
    - Add context update methods (updateApp, setFocusedUser, etc.)
    - Implement pronoun resolution logic
    - Add command history tracking (max 10 commands)
    - Add context clearing on app switch
    - _Requirements: 4.1, 4.2, 4.3, 4.10_
  
  - [ ]* 8.2 Write property test for context field tracking
    - **Property 13: Context Field Tracking**
    - **Validates: Requirements 4.1**
  
  - [ ]* 8.3 Write property test for context updates on commands
    - **Property 14: Context Updates on Commands**
    - **Validates: Requirements 4.2, 4.3**
  
  - [ ]* 8.4 Write property test for pronoun resolution
    - **Property 15: Pronoun Resolution**
    - **Validates: Requirements 4.4**
  
  - [ ]* 8.5 Write property test for command history size limit
    - **Property 16: Command History Size Limit**
    - **Validates: Requirements 4.7**
  
  - [ ]* 8.6 Write property test for missing context error handling
    - **Property 17: Missing Context Error Handling**
    - **Validates: Requirements 4.8**
  
  - [ ]* 8.7 Write property test for context clearing on app switch
    - **Property 19: Context Clearing on App Switch**
    - **Validates: Requirements 4.10**

- [x] 9. Enhance Fuzzy Matcher with Context Awareness
  - [x] 9.1 Add context-aware matching to `SmartCommandMatcher`
    - Add `matchWithContext()` method that accepts NavigationContext
    - Implement context-based disambiguation logic
    - Add pronunciation variation database expansion
    - Add learning system for corrections
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.9_
  
  - [ ]* 9.2 Write property test for pronunciation variation matching
    - **Property 24: Pronunciation Variation Matching**
    - **Validates: Requirements 8.1, 8.2**
  
  - [ ]* 9.3 Write property test for context-aware disambiguation
    - **Property 25: Context-Aware Disambiguation**
    - **Validates: Requirements 8.3, 8.4**
  
  - [ ]* 9.4 Write property test for multi-language support
    - **Property 26: Multi-Language Support**
    - **Validates: Requirements 8.5**
  
  - [ ]* 9.5 Write property test for edit distance fuzzy matching
    - **Property 27: Edit Distance Fuzzy Matching**
    - **Validates: Requirements 8.6**
  
  - [ ]* 9.6 Write property test for ambiguity clarification
    - **Property 28: Ambiguity Clarification**
    - **Validates: Requirements 8.8**
  
  - [ ]* 9.7 Write property test for learning from corrections
    - **Property 29: Learning from Corrections**
    - **Validates: Requirements 8.9**

- [x] 10. Implement Command Chaining Support
  - [x] 10.1 Add command chaining parser to `CommandProcessor`
    - Parse multi-step commands separated by commas
    - Execute each step sequentially
    - Maintain context between steps
    - Handle errors in chained execution
    - _Requirements: 4.9_
  
  - [ ]* 10.2 Write property test for command chaining execution
    - **Property 18: Command Chaining Execution**
    - **Validates: Requirements 4.9**

- [x] 11. Checkpoint - Ensure navigation context and fuzzy matching tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 12. Implement Camera Controller
  - [ ] 12.1 Create `CameraController` class
    - Implement camera opening/closing via accessibility service
    - Add photo capture functionality
    - Add video recording start/stop
    - Add camera switching (front/back)
    - Add flash and zoom control
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8_
  
  - [ ] 12.2 Implement face detection for selfies
    - Use Camera2 API face detection
    - Add face detection callback
    - Calculate face centering
    - Add audio feedback for face detection
    - _Requirements: 6.10_
  
  - [ ]* 12.3 Write property test for selfie face detection feedback
    - **Property 20: Selfie Face Detection Feedback**
    - **Validates: Requirements 6.10**
  
  - [ ] 12.4 Implement selfie countdown
    - Add countdown timer (3, 2, 1)
    - Announce countdown via audio feedback
    - Trigger capture after countdown
    - _Requirements: 6.11_
  
  - [ ]* 12.5 Write property test for selfie countdown
    - **Property 21: Selfie Countdown**
    - **Validates: Requirements 6.11**
  
  - [ ]* 12.6 Write property test for universal camera action feedback
    - **Property 22: Universal Camera Action Feedback**
    - **Validates: Requirements 6.12, 6.13**

- [ ] 13. Implement Call Manager
  - [ ] 13.1 Create `CallManager` class
    - Implement call answering/rejecting using TelecomManager
    - Add call ending functionality
    - Add mute/unmute using AudioManager
    - Add speaker toggle
    - Add call state tracking
    - _Requirements: 7.1, 7.2, 7.5, 7.6, 7.7_
  
  - [ ] 13.2 Implement conference call management
    - Add call functionality
    - Merge calls functionality
    - Switch call functionality
    - _Requirements: 7.8, 7.9, 7.10_
  
  - [ ] 13.3 Implement call recording
    - Use MediaRecorder with VOICE_CALL audio source
    - Add recording start/stop
    - Store recordings in app-specific directory
    - _Requirements: 7.11_
  
  - [ ]* 13.4 Write property test for universal call action feedback
    - **Property 23: Universal Call Action Feedback**
    - **Validates: Requirements 7.14**

- [ ] 14. Integrate Camera and Call Management with Command Processor
  - [ ] 14.1 Add camera commands to `CommandProcessor`
    - Add camera controller instance
    - Add command intents for camera operations
    - Integrate with verification system
    - Add audio feedback for camera actions
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8_
  
  - [ ] 14.2 Add call management commands to `CommandProcessor`
    - Add call manager instance
    - Add command intents for call operations
    - Integrate with verification system for in-call commands
    - Add audio feedback for call actions
    - _Requirements: 7.1, 7.2, 7.5, 7.6, 7.7, 7.8, 7.9, 7.10, 7.11_

- [ ] 15. Checkpoint - Ensure camera and call management tests pass
  - Ensure all tests pass, ask the user if questions arise.


- [ ] 16. Implement App Navigator
  - [ ] 16.1 Create `AppNavigator` class
    - Implement app opening functionality
    - Add user search functionality
    - Add profile opening functionality
    - Add scrolling functionality (feed, photos, reels, stories)
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7_
  
  - [ ] 16.2 Add Instagram-specific navigation
    - Map Instagram UI elements (search, profile, reels, stories, DMs)
    - Implement Instagram action handlers
    - _Requirements: 5.1_
  
  - [ ] 16.3 Add WhatsApp-specific navigation
    - Map WhatsApp UI elements (chats, search, call buttons)
    - Implement WhatsApp action handlers
    - _Requirements: 5.2_
  
  - [ ] 16.4 Add Phone app-specific navigation
    - Map Phone app UI elements (dialpad, call buttons)
    - Implement Phone app action handlers
    - _Requirements: 5.3_
  
  - [ ] 16.5 Add Facebook-specific navigation
    - Map Facebook UI elements (feed, groups, marketplace, watch)
    - Implement Facebook action handlers
    - _Requirements: 5.4_
  
  - [ ] 16.6 Add YouTube-specific navigation
    - Map YouTube UI elements (search, play/pause, channels, playlists)
    - Implement YouTube action handlers
    - _Requirements: 5.5_
  
  - [ ] 16.7 Add Twitter/X-specific navigation
    - Map Twitter UI elements (timeline, profile, spaces)
    - Implement Twitter action handlers
    - _Requirements: 5.6_
  
  - [ ] 16.8 Add support for 14 additional apps
    - Research and map UI elements for remaining apps
    - Implement action handlers for each app
    - _Requirements: 5.7_
  
  - [ ]* 16.9 Write unit tests for app-specific navigation
    - Test Instagram navigation flow
    - Test WhatsApp navigation flow
    - Test Phone app navigation flow
    - Test Facebook navigation flow
    - Test YouTube navigation flow
    - Test Twitter navigation flow
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

- [ ] 17. Integrate App Navigator with Command Processor
  - [ ] 17.1 Add app navigation commands to `CommandProcessor`
    - Add app navigator instance
    - Add command intents for app-specific actions
    - Integrate with navigation context tracker
    - Add audio feedback for navigation actions
    - _Requirements: 5.8, 5.9, 5.10_

- [ ] 18. Implement Audio Feedback Manager
  - [ ] 18.1 Create `AudioFeedbackManager` class
    - Implement TTS initialization and configuration
    - Add announcement queue with priority support
    - Add speech completion callbacks
    - Integrate with voice recognition pause/resume
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10, 9.11, 9.12, 9.13, 9.14, 9.15_
  
  - [ ]* 18.2 Write property test for state transition audio feedback
    - **Property 2: State Transition Audio Feedback**
    - **Validates: Requirements 1.4, 1.5**
  
  - [ ]* 18.3 Write property test for mode change announcements
    - **Property 8: Mode Change Announcements**
    - **Validates: Requirements 2.11**

- [ ] 19. Integrate Audio Feedback System
  - [ ] 19.1 Replace all `service?.speak()` calls with `AudioFeedbackManager`
    - Update `CommandProcessor` to use audio feedback manager
    - Update `PhoneStateDetector` to announce state changes
    - Update `GoogleVoiceCommandManager` to announce mode changes
    - Update `VolumeToggleDetector` to announce state transitions
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10, 9.11, 9.12, 9.13, 9.14, 9.15_
  
  - [ ] 19.2 Implement voice recognition pause during announcements
    - Pause voice recognition when TTS starts
    - Resume voice recognition when TTS completes
    - Handle interruptions and errors
    - _Requirements: 9.15_

- [ ] 20. Checkpoint - Ensure app navigation and audio feedback tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 21. Implement Battery Optimization Manager
  - [ ] 21.1 Create `BatteryOptimizationManager` class
    - Implement listening state enum (INACTIVE, ACTIVE, LOW_POWER)
    - Add state transition methods
    - Add wake lock management
    - Add low-power mode timer (30 seconds)
    - _Requirements: 10.1, 10.2, 10.3, 10.7, 10.8_
  
  - [ ]* 21.2 Write property test for zero battery in inactive state
    - **Property 3: Zero Battery Consumption in Inactive State**
    - **Validates: Requirements 1.6, 10.1**
  
  - [ ]* 21.3 Write property test for active listening in active state
    - **Property 4: Active Listening in Active State**
    - **Validates: Requirements 1.7**
  
  - [ ]* 21.4 Write property test for inactive state resource cleanup
    - **Property 30: Inactive State Resource Cleanup**
    - **Validates: Requirements 10.5, 10.6**
  
  - [ ]* 21.5 Write property test for low-power mode transition
    - **Property 31: Low-Power Mode Transition**
    - **Validates: Requirements 10.7**
  
  - [ ]* 21.6 Write property test for low-power mode wake-up
    - **Property 32: Low-Power Mode Wake-Up**
    - **Validates: Requirements 10.8**

- [ ] 22. Integrate Battery Optimization
  - [ ] 22.1 Update `AutomationForegroundService` to use battery optimization manager
    - Add battery optimization manager instance
    - Transition to INACTIVE on volume toggle off
    - Transition to ACTIVE on volume toggle on
    - Implement low-power mode transitions
    - _Requirements: 10.1, 10.2, 10.7, 10.8_
  
  - [ ] 22.2 Update `GoogleVoiceCommandManager` to support low-power mode
    - Add low-power listening mode
    - Implement wake-up on speech detection
    - Optimize CPU usage in active mode
    - _Requirements: 10.3, 10.4, 10.7, 10.8_
  
  - [ ]* 22.3 Write property test for on-demand UI queries
    - **Property 33: On-Demand UI Queries**
    - **Validates: Requirements 10.9**

- [ ] 23. Wire All Components Together
  - [ ] 23.1 Update `AutomationForegroundService` initialization
    - Initialize all new components (state detector, verification system, navigation context, etc.)
    - Wire volume toggle detector to voice recognition engine
    - Wire state detector to command processor
    - Wire audio feedback manager to all components
    - Wire battery optimization manager to service lifecycle
    - _Requirements: All_
  
  - [ ] 23.2 Update `MainActivity` for initial setup
    - Add verification code configuration UI
    - Add offline model check and guidance
    - Add permission requests (camera, phone, microphone)
    - Add feature introduction and tutorial
    - _Requirements: 2.6, 2.7, 3.11_
  
  - [ ] 23.3 Update AndroidManifest.xml with new permissions
    - Add CAMERA permission
    - Add CALL_PHONE permission
    - Add ANSWER_PHONE_CALLS permission
    - Add READ_PHONE_STATE permission
    - Add RECORD_AUDIO permission
    - Add MODIFY_AUDIO_SETTINGS permission
    - _Requirements: All_

- [ ] 24. Final Checkpoint - Ensure all tests pass and integration is complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ]* 25. Write integration tests for end-to-end flows
  - Test volume toggle → voice command → execution flow
  - Test state detection → verification → command execution flow
  - Test multi-step navigation with context flow
  - Test camera capture with face detection flow
  - Test in-call command with verification flow
  - Test online/offline mode switching flow
  - _Requirements: All_

## Notes

- Tasks marked with `*` are optional property-based tests and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation at major milestones
- Property tests validate universal correctness properties with 100+ iterations
- Unit tests validate specific examples and edge cases
- Integration tests validate end-to-end flows across components
- All components integrate with existing `AutomationAccessibilityService` and `CommandProcessor` architecture
- Kotlin is used for all implementation (existing codebase language)
- Target Android API 26+ (Android 8.0+) with special handling for API 31+ (Android 12+) for on-device speech recognition

