package com.autoapk.automation.vision

/**
 * Vision Prompt Builder — Constructs All Prompt Variants
 *
 * 9 capture modes, each producing a specialized prompt:
 *   - MANUAL: Full detailed scene description (6-10 sentences)
 *   - MONITORING: Brief auto-describe (2-3 sentences, changes only)
 *   - NAVIGATION: Path safety priority (2-3 sentences, hazards first)
 *   - PEOPLE: People-focused only
 *   - TEXT_READING: Read visible text
 *   - FOLLOW_UP: Text-only continuation (no image)
 *   - SAFETY_CHECK: Path safety assessment
 *   - FIND_OBJECT: Locate a specific object
 *   - WHAT_CHANGED: Delta from last scene
 *
 * All prompts include:
 *   - Distance estimation instruction (visual-cue based)
 *   - Clock position convention
 *   - Safety-first ordering
 *   - Face metadata injection (if recognized faces present)
 *   - Previous scene context (if available)
 */
object VisionPromptBuilder {

    enum class CaptureMode {
        MANUAL,
        MONITORING,
        NAVIGATION,
        PEOPLE,
        TEXT_READING,
        FOLLOW_UP,
        SAFETY_CHECK,
        FIND_OBJECT,
        WHAT_CHANGED
    }

    /**
     * The system instruction used for ALL Gemini vision conversations.
     * Sets up the Neo Vision persona and ground rules.
     */
    val systemInstruction: String = """
You are "Neo Vision", a real-time visual guide for a blind person. You are their eyes. Your job is to describe what you see in the image clearly, naturally, and helpfully.

CRITICAL RULES:
1. SAFETY FIRST: Always mention obstacles, stairs, vehicles, edges, sharp objects, or any hazards BEFORE describing the general environment.
2. Use CLOCK POSITIONS for spatial directions (standard convention for visually impaired): 12 o'clock = straight ahead, 3 o'clock = to the right, 9 o'clock = to the left, 6 o'clock = behind.
3. Speak in SECOND PERSON: "In front of you...", "To your left...", "About 3 steps ahead of you..."
4. Use NATURAL, FRIENDLY language. No bullet points, no technical terms, no markdown.
5. When people are recognized by name, use their EXACT NAME naturally in your description.

DISTANCE ESTIMATION (MANDATORY):
You must estimate approximate distances of objects and people from the camera. Use visual cues: relative size of known objects (a person is roughly 5-6 feet tall, a door is roughly 7 feet, a car is roughly 4.5 feet tall), perspective convergence, how much of the frame an object occupies, focus blur, and overlap. Express distances in human-friendly terms: "about one step away", "roughly arm's reach", "about 3 steps ahead", "across the room maybe 4-5 meters", "far away down the street". Do NOT say you cannot estimate distance. Always give your best approximate guess. Being roughly right is infinitely better than saying nothing.
""".trimIndent()

    /**
     * Build a complete prompt for the given mode and context.
     *
     * @param mode The capture mode determining prompt style
     * @param faces List of recognized faces with metadata
     * @param previousContext Formatted context from VisionContextMemory
     * @param userQuery Optional specific question from the user
     * @param objectToFind Optional object name for FIND_OBJECT mode
     * @return The complete prompt text to send with the image
     */
    fun buildPrompt(
        mode: CaptureMode,
        faces: List<FaceRecognitionEngine.RecognizedFace> = emptyList(),
        previousContext: String = "",
        userQuery: String? = null,
        objectToFind: String? = null,
        respondInHindi: Boolean = false
    ): String {
        val sb = StringBuilder()

        // Language instruction — placed first for maximum prominence
        if (respondInHindi) {
            sb.appendLine("IMPORTANT: Respond ENTIRELY in Hindi (Devanagari script). Do NOT use any English words. Use natural spoken Hindi.")
            sb.appendLine()
        } else {
            sb.appendLine("Respond in English only. Do NOT mix Hindi or any other language.")
            sb.appendLine()
        }

        // Inject face metadata if any faces were detected
        if (faces.isNotEmpty()) {
            sb.appendLine(buildFaceMetadata(faces))
            sb.appendLine()
        }

        // Inject previous scene context if available
        if (previousContext.isNotBlank()) {
            sb.appendLine(previousContext)
            sb.appendLine()
        }

        // Mode-specific instructions
        sb.appendLine(getModePrompt(mode, userQuery, objectToFind))

        return sb.toString().trim()
    }

    // ==================== MODE-SPECIFIC PROMPTS ====================

    private fun getModePrompt(mode: CaptureMode, userQuery: String?, objectToFind: String?): String {
        return when (mode) {
            CaptureMode.MANUAL -> buildManualPrompt(userQuery)
            CaptureMode.MONITORING -> buildMonitoringPrompt()
            CaptureMode.NAVIGATION -> buildNavigationPrompt()
            CaptureMode.PEOPLE -> buildPeoplePrompt()
            CaptureMode.TEXT_READING -> buildTextReadingPrompt()
            CaptureMode.FOLLOW_UP -> buildFollowUpPrompt(userQuery ?: "tell me more")
            CaptureMode.SAFETY_CHECK -> buildSafetyCheckPrompt()
            CaptureMode.FIND_OBJECT -> buildFindObjectPrompt(objectToFind ?: "the object")
            CaptureMode.WHAT_CHANGED -> buildWhatChangedPrompt()
        }
    }

    private fun buildManualPrompt(userQuery: String?): String {
        val base = """
Describe the scene in this image as a visual guide for a blind person.

Instructions:
- First mention any safety concerns (obstacles, stairs, vehicles, edges, moving objects)
- Then describe the environment (indoor/outdoor, lighting, weather, general setting)
- Describe people present (use their names if provided in face metadata above, otherwise approximate age/appearance)
- Mention notable objects, signs, or features
- Use clock positions for spatial directions
- Estimate distances of key objects and people using visual cues
- Keep response to 6-10 natural sentences
""".trimIndent()

        return if (userQuery != null) {
            "$base\n\nThe user specifically asked: \"$userQuery\"\nPrioritize answering their question while still covering safety."
        } else base
    }

    private fun buildMonitoringPrompt(): String = """
AUTO-MONITORING MODE. Be VERY brief — maximum 2-3 sentences.

You are continuously describing the scene every few seconds. Only mention:
1. Any NEW safety hazards or changes in existing ones
2. People who entered, left, or moved significantly
3. Important environmental changes (lighting, approaching vehicles, obstacles)

If nothing meaningful has changed from the previous context, respond with ONLY the word: "unchanged"

Keep responses extremely short. Prioritize movement and safety.
""".trimIndent()

    private fun buildNavigationPrompt(): String = """
NAVIGATION MODE — Walking guidance. Maximum 2-3 sentences. URGENT tone for dangers.

ABSOLUTE TOP PRIORITY:
- Path safety: Is the path ahead clear? Any obstacles at any height (ground level AND head level)?
- Ground conditions: stairs, curbs, slopes, puddles, uneven surface, cracks
- Approaching vehicles or cyclists
- Overhanging branches, signs, poles, or anything at head/face height

SECONDARY:
- Brief environment (indoor hallway, outdoor sidewalk, etc.)
- Direction guidance if a turn or decision point is visible

Estimate how far ahead obstacles are. Use urgent language for immediate dangers (within 2-3 steps). Be extremely concise.
""".trimIndent()

    private fun buildPeoplePrompt(): String = """
Describe ONLY the people visible in this image:
- How many people are there?
- For each person: name (if provided in face metadata), position (clock direction), approximate distance
- What are they doing? (standing, sitting, walking, looking at phone, talking)
- Their expression and approximate age/appearance if unknown
- Ignore environment details unless safety-relevant

Use clock positions. Be natural and conversational.
""".trimIndent()

    private fun buildTextReadingPrompt(): String = """
Focus ENTIRELY on reading visible text in this image:
- Signs, boards, menus, labels, screens, papers, price tags, street names
- Read the text EXACTLY as written
- Mention where the text is located (on a sign above, on a screen to the right, etc.)
- If text is partially visible or unclear, read what you can and note it's partial
- If multiple text elements are visible, read them in spatial order (top to bottom, left to right)
- If no text is visible, say so clearly
""".trimIndent()

    private fun buildFollowUpPrompt(question: String): String = """
The user has a follow-up question about the scene you just described.
Answer based on what you saw in the last image and our conversation so far.
Keep your response brief and focused.

User's question: "$question"
""".trimIndent()

    private fun buildSafetyCheckPrompt(): String = """
SAFETY ASSESSMENT — Is the path ahead safe to walk?

Analyze ONLY safety-relevant elements:
- Is the path clear of obstacles?
- Any stairs, steps, curbs, or elevation changes?
- Any vehicles, cyclists, or fast-moving objects?
- Any wet, slippery, or uneven surfaces?
- Any overhanging obstacles at head height?
- Any edges, drops, or open areas (like road vs sidewalk)?

Give a clear YES/NO safety assessment first, then briefly explain why.
Estimate distances to any hazards. Be direct and concise — maximum 3 sentences.
""".trimIndent()

    private fun buildFindObjectPrompt(objectName: String): String = """
The user is looking for: "$objectName"

Search the image for this object. If found:
- Describe where it is using clock position and estimated distance
- Describe its appearance briefly so the user can confirm
- Mention anything between the user and the object (obstacles)

If NOT found:
- Say clearly that you cannot see it in the current view
- Suggest which direction the user might try looking
""".trimIndent()

    private fun buildWhatChangedPrompt(): String = """
Compare this image with the previous scene context provided above.

Describe ONLY what has CHANGED:
- People who appeared, disappeared, or moved
- Objects that moved or are new
- Environmental changes (lighting, doors opened/closed, vehicles)
- Any new safety concerns

If nothing has changed, say "Everything looks the same as before."
Keep response to 2-3 sentences maximum.
""".trimIndent()

    // ==================== FACE METADATA ====================

    /**
     * Build a structured face metadata block to inject into the prompt.
     */
    private fun buildFaceMetadata(faces: List<FaceRecognitionEngine.RecognizedFace>): String {
        val sb = StringBuilder()
        sb.appendLine("=== DETECTED PEOPLE ===")
        sb.appendLine("${faces.size} ${if (faces.size == 1) "person" else "people"} detected in the image:")

        for ((i, face) in faces.withIndex()) {
            sb.append("Person ${i + 1}: ")
            if (face.name != "Unknown Person") {
                sb.append("RECOGNIZED as \"${face.name}\" (confidence: ${"%.0f".format(face.confidence * 100)}%). ")
            } else {
                sb.append("Unknown person. ")
            }
            sb.append("Position: ${face.horizontalPos} of frame (${face.clockPosition}). ")
            sb.append("Expression: ${face.expression}.")
            sb.appendLine()
        }

        sb.appendLine("Use the exact names of recognized people in your description.")
        return sb.toString()
    }
}
