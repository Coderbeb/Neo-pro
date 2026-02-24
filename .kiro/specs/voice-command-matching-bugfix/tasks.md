# Implementation Plan: Voice Command Matching Bugfix

## Overview

This implementation plan fixes the voice command matching bug by modifying `SmartCommandMatcher.cleanInput()` to preserve command verbs and their parameters, and enhancing `checkParameterizedCommands()` with detailed logging. The fix ensures commands like "call nitish bhaiya" and "open instagram" are correctly matched and executed.

## Tasks

- [x] 1. Fix cleanInput() to preserve command verbs and parameters
  - Modify `SmartCommandMatcher.cleanInput()` to detect command verbs BEFORE any text transformations
  - Split input into [prefix, verb, parameter] when a command verb is detected
  - Apply normalization and filler stripping only to prefix and verb, preserve parameter completely
  - Maintain backward compatibility for commands without verbs
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 7.1, 7.4_

- [ ]* 1.1 Write property test for command verb and parameter preservation
  - **Property 1: Command verb and parameter preservation**
  - **Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 7.1, 7.4**

- [x] 2. Add detailed logging to checkParameterizedCommands()
  - Add log statement at function entry showing cleaned and raw input
  - Add log statement for each pattern type being tested (call, open, etc.)
  - Add log statement when a pattern matches, showing the extracted parameter
  - Add log statement when no patterns match
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ]* 2.1 Write unit test for logging verification
  - Test that logs contain expected entries for successful matches
  - Test that logs contain expected entries for failed matches
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 3. Test with specific failing commands
  - [ ] 3.1 Create unit test for "call nitish bhaiya"
    - Verify cleanInput() preserves "call nitish bhaiya"
    - Verify checkParameterizedCommands() returns CALL_CONTACT with parameter "nitish bhaiya"
    - _Requirements: 2.1, 2.6_
  
  - [ ] 3.2 Create unit test for "open instagram"
    - Verify cleanInput() preserves "open instagram"
    - Verify checkParameterizedCommands() returns OPEN_APP with parameter "instagram"
    - _Requirements: 3.1_
  
  - [ ] 3.3 Create unit test for "open youtube"
    - Verify cleanInput() preserves "open youtube"
    - Verify checkParameterizedCommands() returns OPEN_APP with parameter "youtube"
    - _Requirements: 3.1_
  
  - [ ] 3.4 Create unit test for "call hrithik roshan"
    - Verify cleanInput() preserves "call hrithik roshan"
    - Verify checkParameterizedCommands() returns CALL_CONTACT with parameter "hrithik roshan"
    - _Requirements: 2.1, 2.6_

- [ ]* 3.5 Write property test for parameterized command extraction
  - **Property 2: Parameterized command extraction**
  - **Validates: Requirements 2.1, 2.2, 2.3, 2.6, 3.1, 3.2, 3.3, 7.1, 7.2, 7.3**

- [ ] 4. Test Hindi and Hinglish commands
  - [ ] 4.1 Create unit test for "कॉल nitish bhaiya"
    - Verify Hindi verb is recognized
    - Verify parameter is extracted correctly
    - _Requirements: 2.4, 6.1_
  
  - [ ] 4.2 Create unit test for "फोन hrithik roshan"
    - Verify Hindi verb is recognized
    - Verify parameter is extracted correctly
    - _Requirements: 2.5, 6.2_
  
  - [ ] 4.3 Create unit test for "चलाओ instagram"
    - Verify Hindi verb is recognized
    - Verify parameter is extracted correctly
    - _Requirements: 3.4, 6.3_

- [ ]* 4.4 Write property test for Hindi command matching
  - **Property 3: Hindi command matching**
  - **Validates: Requirements 2.4, 2.5, 3.4, 6.1, 6.2, 6.3**

- [ ] 5. Checkpoint - Ensure all tests pass
  - Run all unit tests and property tests
  - Verify logs show correct matching behavior
  - Test manually with voice input if possible
  - Ask the user if questions arise

- [ ] 6. Test contact name fallback
  - [ ] 6.1 Create unit test for contact name without "call"
    - Verify that saying just "nitish bhaiya" triggers contact fallback
    - Verify CommandProcessor checks contact registry
    - _Requirements: 4.1, 4.2, 4.3_

- [ ]* 6.2 Write integration test for full command pipeline
  - Test complete flow: voice input → cleanInput → checkParameterizedCommands → executeIntent
  - Verify contact fallback works when no command pattern matches
  - _Requirements: 4.1, 4.2, 4.3_

- [ ] 7. Backward compatibility testing
  - [ ] 7.1 Create unit tests for non-parameterized commands
    - Test "go home", "go back", "volume up", "volume down"
    - Verify these still match correctly after the bugfix
    - _Requirements: 8.1, 8.2_
  
  - [ ] 7.2 Create unit tests for keyword scoring
    - Test commands that use scoring-based matching
    - Verify scoring still works correctly
    - _Requirements: 8.2_
  
  - [ ] 7.3 Create unit tests for verb normalization
    - Test that "kardo" → "karo" still works
    - Test that "calling" → "call" still works
    - _Requirements: 8.3_
  
  - [ ] 7.4 Create unit tests for filler word stripping
    - Test that filler words are still stripped from non-parameter portions
    - Test that "bhai call nitish" → "call nitish" (strips "bhai" before verb)
    - _Requirements: 8.4_

- [ ] 8. Final checkpoint - Ensure all tests pass
  - Run complete test suite (unit + property tests)
  - Verify no regressions in existing functionality
  - Test with real voice commands if possible
  - Ask the user if questions arise

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties with 100+ iterations
- Unit tests validate specific examples, edge cases, and backward compatibility
- The fix is localized to `SmartCommandMatcher.cleanInput()` and logging in `checkParameterizedCommands()`
- No changes to `CommandProcessor` are required
