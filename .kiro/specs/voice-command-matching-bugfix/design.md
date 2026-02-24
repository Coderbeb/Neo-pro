# Design Document: Voice Command Matching Bugfix

## Overview

This bugfix resolves a critical issue in the voice command matching pipeline where correctly recognized voice commands fail to execute. The root cause is in `SmartCommandMatcher.cleanInput()` which strips or modifies words that `checkParameterizedCommands()` regex patterns depend on.

The fix involves modifying the input cleaning logic to preserve command verbs and their parameters, while maintaining backward compatibility with existing command matching functionality.

## Architecture

The command matching pipeline follows this flow:

```
Voice Input → HindiCommandMapper.translate() → CommandProcessor.process()
  ↓
  cleanInput() → checkParameterizedCommands() → match()
  ↓
  executeIntent()
```

The bug occurs at the `cleanInput()` step, where the current implementation:
1. Normalizes verbs (e.g., "kardo" → "karo")
2. Stems words (e.g., "calling" → "call")
3. Strips filler words
4. **BUG**: Strips words that are part of parameters after command verbs

The fix modifies `cleanInput()` to detect command verbs and preserve everything after them as parameters.

## Components and Interfaces

### Modified Component: SmartCommandMatcher.cleanInput()

**Current Behavior:**
```kotlin
private fun cleanInput(raw: String): String {
    var text = raw.trim().lowercase()
    text = normalizeVerbs(text)
    text = stemWords(text)
    val words = text.split(" ", "\t").filter { it.isNotBlank() }
    val commandVerbs = setOf("call", "dial", "phone", "open", "launch", "search", "type", "click", "tap")
    val verbIdx = words.indexOfFirst { it in commandVerbs }
    val filtered = words.mapIndexed { idx, word ->
        if (verbIdx >= 0 && idx > verbIdx) {
            word  // Keep as-is — this is part of the parameter
        } else if (word !in fillerWords || word in protectedWords) {
            word
        } else {
            null  // Strip filler word
        }
    }.filterNotNull()
    return if (filtered.isEmpty()) text else filtered.joinToString(" ")
}
```

**Issue:** The logic attempts to preserve words after command verbs, but the verb detection happens AFTER stemming and normalization, which may have already modified or removed the verb.

**Fixed Behavior:**
```kotlin
private fun cleanInput(raw: String): String {
    var text = raw.trim().lowercase()
    
    // CRITICAL: Detect command verbs BEFORE any transformations
    val commandVerbs = setOf("call", "dial", "phone", "open", "launch", "start", 
                             "search", "type", "click", "tap", "run",
                             "कॉल", "फोन", "चलाओ", "shuru")
    val words = text.split(" ", "\t").filter { it.isNotBlank() }
    val verbIdx = words.indexOfFirst { it in commandVerbs }
    
    // If we found a command verb, split into [prefix, verb, parameter]
    if (verbIdx >= 0) {
        val prefix = words.subList(0, verbIdx)
        val verb = words[verbIdx]
        val parameter = words.subList(verbIdx + 1, words.size)
        
        // Clean prefix (strip fillers), keep verb, preserve parameter completely
        val cleanedPrefix = prefix.filter { it !in fillerWords || it in protectedWords }
        val result = (cleanedPrefix + listOf(verb) + parameter).joinToString(" ")
        
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
```

### Modified Component: SmartCommandMatcher.checkParameterizedCommands()

**Current Behavior:**
The function receives cleaned input and attempts regex matching. However, the cleaned input may have lost critical words.

**Enhanced Behavior:**
Add logging to track which patterns are tested and why they fail:

```kotlin
private fun checkParameterizedCommands(cleaned: String, raw: String): MatchResult? {
    Log.d(TAG, "checkParameterizedCommands: cleaned='$cleaned', raw='$raw'")
    
    // --- CALL CONTACT ---
    val callPatterns = listOf(
        Regex("^(?:call|dial|phone|कॉल|फोन)\\s+(.+)"),
        Regex("^(.+?)\\s+(?:ko\\s*call|ko\\s*phone|को\\s*कॉल|को\\s*फोन)"),
        Regex("^(?:call\\s*karo|कॉल\\s*करो)\\s+(.+)")
    )
    for ((index, pattern) in callPatterns.withIndex()) {
        pattern.find(cleaned)?.let { match ->
            val contact = match.groupValues[1].replace(Regex("\\s*(karo|करो)$"), "").trim()
            if (contact.isNotBlank()) {
                Log.i(TAG, "CALL pattern $index matched: contact='$contact'")
                return MatchResult(CommandIntent.CALL_CONTACT, 3.0f, contact)
            }
        }
    }
    
    // Similar logging for other patterns...
    
    Log.d(TAG, "checkParameterizedCommands: no patterns matched")
    return null
}
```

## Data Models

No new data models are required. The existing `MatchResult` data class is sufficient:

```kotlin
data class MatchResult(
    val intent: CommandIntent,
    val score: Float,
    val extractedParam: String = ""
)
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Acceptance Criteria Testing Prework

1.1 WHEN a command contains "call" followed by a contact name, THE Smart_Command_Matcher SHALL preserve both "call" and the contact name during cleaning
  Thoughts: This is about ensuring that for any command with "call" followed by text, both parts are preserved. We can generate random contact names, prepend "call", and verify the cleaned output contains both.
  Testable: yes - property

1.2 WHEN a command contains "open" followed by an app name, THE Smart_Command_Matcher SHALL preserve both "open" and the app name during cleaning
  Thoughts: Similar to 1.1, this applies to all "open" commands. We can test with random app names.
  Testable: yes - property

2.1 WHEN a user says "call [contact name]", THE Smart_Command_Matcher SHALL extract the contact name and return CALL_CONTACT intent
  Thoughts: This is a universal rule about all "call" commands. We can generate random contact names and verify the intent and parameter extraction.
  Testable: yes - property

2.6 WHEN the contact name contains multiple words (e.g., "nitish bhaiya"), THE Smart_Command_Matcher SHALL extract the complete name
  Thoughts: This is testing that multi-word parameters are preserved. We can generate random multi-word names and verify they're extracted completely.
  Testable: yes - property

3.1 WHEN a user says "open [app name]", THE Smart_Command_Matcher SHALL extract the app name and return OPEN_APP intent
  Thoughts: Universal rule for all "open" commands. Testable with random app names.
  Testable: yes - property

4.1 WHEN a user says only a contact name without "call", THE Command_Processor SHALL attempt to match it as a contact
  Thoughts: This is about the fallback behavior. We can test with known contact names and verify the fallback is triggered.
  Testable: yes - example

5.1 WHEN a command enters the matching pipeline, THE Smart_Command_Matcher SHALL log the raw input
  Thoughts: This is about logging behavior. We can verify logs contain expected entries.
  Testable: yes - example

7.1 WHEN a parameter contains multiple words, THE Smart_Command_Matcher SHALL preserve all words in the extracted parameter
  Thoughts: This is a universal rule about parameter extraction. We can test with random multi-word parameters.
  Testable: yes - property

7.2 WHEN a parameter contains honorifics (e.g., "bhaiya", "ji"), THE Smart_Command_Matcher SHALL preserve them in the extracted parameter
  Thoughts: This is an edge case of multi-word parameters. We should ensure our generators include honorifics.
  Testable: edge-case

8.1 WHEN the bugfix is applied, THE Smart_Command_Matcher SHALL continue to match all non-parameterized commands
  Thoughts: This is about regression testing. We can test with a set of known non-parameterized commands.
  Testable: yes - example

### Property Reflection

Reviewing the properties identified:
- Property 1.1 and 1.2 are similar (preserve verb + parameter) - can be combined
- Property 2.1 and 3.1 are similar (extract parameter and return intent) - can be combined
- Property 2.6 and 7.1 are the same (multi-word parameter preservation) - redundant
- Property 7.2 is an edge case that should be covered by generators, not a separate property

Consolidated properties:
1. Command verb and parameter preservation (combines 1.1, 1.2)
2. Parameterized command matching (combines 2.1, 3.1, 2.6, 7.1)
3. Contact name fallback (4.1 - example)
4. Logging verification (5.1 - example)
5. Backward compatibility (8.1 - example)

### Correctness Properties

Property 1: Command verb and parameter preservation
*For any* command verb ("call", "open", "dial", "phone", "launch") and any parameter string, when cleanInput() processes the command, the output should contain both the verb and the complete parameter string.
**Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 7.1, 7.4**

Property 2: Parameterized command extraction
*For any* command verb ("call", "open", "dial", "phone", "launch") and any non-empty parameter string, when checkParameterizedCommands() processes the cleaned command, it should return a MatchResult with the correct intent and the complete parameter extracted.
**Validates: Requirements 2.1, 2.2, 2.3, 2.6, 3.1, 3.2, 3.3, 7.1, 7.2, 7.3**

Property 3: Hindi command matching
*For any* Hindi command verb ("कॉल", "फोन", "चलाओ") and any parameter string, when checkParameterizedCommands() processes the command, it should return a MatchResult with the correct intent and the complete parameter extracted.
**Validates: Requirements 2.4, 2.5, 3.4, 6.1, 6.2, 6.3**

## Error Handling

The bugfix maintains existing error handling:

1. **Empty parameters**: If a regex matches but the extracted parameter is blank, the match is rejected
2. **No pattern match**: If no parameterized pattern matches, the function returns null and the pipeline continues to the scoring-based matcher
3. **Logging failures**: All pattern matching attempts are logged for debugging

No new error conditions are introduced.

## Testing Strategy

### Dual Testing Approach

This bugfix requires both unit tests and property-based tests:

**Unit Tests** (specific examples and edge cases):
- Test specific failing commands: "call nitish bhaiya", "open instagram", "open youtube"
- Test contact name fallback with known contacts
- Test logging output verification
- Test backward compatibility with existing commands
- Test Hindi command variations

**Property-Based Tests** (universal properties):
- Property 1: Generate random command verbs and parameters, verify preservation
- Property 2: Generate random parameterized commands, verify extraction
- Property 3: Generate random Hindi commands, verify matching

### Property-Based Testing Configuration

- **Library**: Use Kotest property testing for Kotlin
- **Iterations**: Minimum 100 iterations per property test
- **Tagging**: Each test references its design property
  - Tag format: `Feature: voice-command-matching-bugfix, Property {number}: {property_text}`

### Test Data Generation

For property-based tests, generate:
- Command verbs: ["call", "open", "dial", "phone", "launch", "start"]
- Hindi verbs: ["कॉल", "फोन", "चलाओ"]
- Contact names: Random strings with 1-4 words, including honorifics
- App names: Random strings with 1-3 words
- Filler words: Randomly insert from the filler word set before the verb

### Regression Testing

Maintain existing test suite to ensure:
- Non-parameterized commands still match
- Keyword scoring still works
- Verb normalization still works
- Filler word stripping still works for non-parameter portions
