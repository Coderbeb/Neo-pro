# Requirements Document: Voice Command Matching Bugfix

## Introduction

This bugfix addresses a critical issue where voice commands are being correctly recognized by Google Speech Recognition (86-88% confidence) but failing to execute due to a bug in the command matching pipeline. Commands like "call nitish bhaiya", "open instagram", and "open youtube" are being processed but returning "Command not recognized" despite having clear regex patterns that should match them.

The root cause is in the interaction between `SmartCommandMatcher.cleanInput()` and `SmartCommandMatcher.checkParameterizedCommands()`. The cleaning function is stripping or modifying words that the regex patterns depend on, causing pattern matching to fail.

## Glossary

- **Command_Processor**: The main command routing system that receives voice input and executes actions
- **Smart_Command_Matcher**: The keyword-scoring intent classifier that matches commands to intents
- **Parameterized_Command**: A command that extracts a parameter (e.g., contact name, app name) from the input
- **Clean_Input**: The normalized and processed version of the raw voice command
- **Command_Intent**: An enumerated action type (e.g., CALL_CONTACT, OPEN_APP)
- **Match_Result**: A data structure containing the matched intent, confidence score, and extracted parameter

## Requirements

### Requirement 1: Preserve Command Verbs During Input Cleaning

**User Story:** As a user, I want my voice commands with action verbs to be recognized, so that I can control my phone hands-free.

#### Acceptance Criteria

1. WHEN a command contains "call" followed by a contact name, THE Smart_Command_Matcher SHALL preserve both "call" and the contact name during cleaning
2. WHEN a command contains "open" followed by an app name, THE Smart_Command_Matcher SHALL preserve both "open" and the app name during cleaning
3. WHEN a command contains "dial" followed by a contact name, THE Smart_Command_Matcher SHALL preserve both "dial" and the contact name during cleaning
4. WHEN a command contains "phone" followed by a contact name, THE Smart_Command_Matcher SHALL preserve both "phone" and the contact name during cleaning
5. WHEN a command contains "launch" followed by an app name, THE Smart_Command_Matcher SHALL preserve both "launch" and the app name during cleaning

### Requirement 2: Match Parameterized Call Commands

**User Story:** As a user, I want to call contacts by saying their name, so that I can make phone calls without touching my device.

#### Acceptance Criteria

1. WHEN a user says "call [contact name]", THE Smart_Command_Matcher SHALL extract the contact name and return CALL_CONTACT intent
2. WHEN a user says "dial [contact name]", THE Smart_Command_Matcher SHALL extract the contact name and return CALL_CONTACT intent
3. WHEN a user says "phone [contact name]", THE Smart_Command_Matcher SHALL extract the contact name and return CALL_CONTACT intent
4. WHEN a user says "कॉल [contact name]" (Hindi), THE Smart_Command_Matcher SHALL extract the contact name and return CALL_CONTACT intent
5. WHEN a user says "फोन [contact name]" (Hindi), THE Smart_Command_Matcher SHALL extract the contact name and return CALL_CONTACT intent
6. WHEN the contact name contains multiple words (e.g., "nitish bhaiya"), THE Smart_Command_Matcher SHALL extract the complete name

### Requirement 3: Match Parameterized App Launch Commands

**User Story:** As a user, I want to open apps by saying their name, so that I can navigate my phone hands-free.

#### Acceptance Criteria

1. WHEN a user says "open [app name]", THE Smart_Command_Matcher SHALL extract the app name and return OPEN_APP intent
2. WHEN a user says "launch [app name]", THE Smart_Command_Matcher SHALL extract the app name and return OPEN_APP intent
3. WHEN a user says "start [app name]", THE Smart_Command_Matcher SHALL extract the app name and return OPEN_APP intent
4. WHEN a user says "चलाओ [app name]" (Hindi), THE Smart_Command_Matcher SHALL extract the app name and return OPEN_APP intent
5. WHEN the app name contains multiple words (e.g., "google maps"), THE Smart_Command_Matcher SHALL extract the complete name

### Requirement 4: Maintain Contact Name Fallback

**User Story:** As a user, I want to call contacts by just saying their name without "call", so that I can make calls with minimal words.

#### Acceptance Criteria

1. WHEN a user says only a contact name without "call", THE Command_Processor SHALL attempt to match it as a contact
2. WHEN the contact name matches a known contact, THE Command_Processor SHALL initiate a call to that contact
3. WHEN the contact name does not match any command pattern, THE Command_Processor SHALL check the contact registry before returning "Command not recognized"

### Requirement 5: Log Matching Pipeline for Debugging

**User Story:** As a developer, I want detailed logs of the matching pipeline, so that I can debug command recognition issues.

#### Acceptance Criteria

1. WHEN a command enters the matching pipeline, THE Smart_Command_Matcher SHALL log the raw input
2. WHEN input cleaning occurs, THE Smart_Command_Matcher SHALL log the cleaned input
3. WHEN parameterized command checking occurs, THE Smart_Command_Matcher SHALL log which patterns are being tested
4. WHEN a regex pattern matches, THE Smart_Command_Matcher SHALL log the matched intent and extracted parameter
5. WHEN no pattern matches, THE Smart_Command_Matcher SHALL log that parameterized command checking returned null

### Requirement 6: Handle Hindi and Hinglish Variations

**User Story:** As a Hindi-speaking user, I want to use Hindi commands, so that I can control my phone in my native language.

#### Acceptance Criteria

1. WHEN a user says "कॉल [contact name]", THE Smart_Command_Matcher SHALL match the CALL_CONTACT intent
2. WHEN a user says "फोन [contact name]", THE Smart_Command_Matcher SHALL match the CALL_CONTACT intent
3. WHEN Hindi commands are translated by HindiCommandMapper, THE Smart_Command_Matcher SHALL match the translated English equivalents
4. WHEN Hinglish commands mix Hindi and English words, THE Smart_Command_Matcher SHALL handle both languages in the same command

### Requirement 7: Preserve Parameter Integrity

**User Story:** As a user, I want my full contact and app names to be recognized, so that the system can find the correct contact or app.

#### Acceptance Criteria

1. WHEN a parameter contains multiple words, THE Smart_Command_Matcher SHALL preserve all words in the extracted parameter
2. WHEN a parameter contains honorifics (e.g., "bhaiya", "ji"), THE Smart_Command_Matcher SHALL preserve them in the extracted parameter
3. WHEN a parameter contains special characters or numbers, THE Smart_Command_Matcher SHALL preserve them in the extracted parameter
4. WHEN filler words appear after a command verb, THE Smart_Command_Matcher SHALL NOT strip them if they are part of the parameter

### Requirement 8: Maintain Backward Compatibility

**User Story:** As a user, I want all my existing commands to continue working, so that the bugfix doesn't break other functionality.

#### Acceptance Criteria

1. WHEN the bugfix is applied, THE Smart_Command_Matcher SHALL continue to match all non-parameterized commands
2. WHEN the bugfix is applied, THE Smart_Command_Matcher SHALL continue to score intents using the keyword database
3. WHEN the bugfix is applied, THE Smart_Command_Matcher SHALL continue to apply verb normalization
4. WHEN the bugfix is applied, THE Smart_Command_Matcher SHALL continue to strip filler words from non-parameter portions of commands
5. WHEN the bugfix is applied, THE Command_Processor SHALL continue to use the three-layer matching pipeline (memory, smart matcher, fallback)
