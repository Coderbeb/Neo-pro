package com.autoapk.automation.core

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Unit tests for context-aware matching in SmartCommandMatcher.
 * 
 * Tests Requirements: 8.1, 8.2, 8.3, 8.4, 8.9
 */
class SmartCommandMatcherContextTest {

    @Before
    fun setup() {
        // Clear any learned corrections before each test
        SmartCommandMatcher.clearLearnedCorrections()
    }

    @Test
    fun testMatchWithContext_InstagramProfileScreen_OpenCommand() {
        // Given: User is on Instagram profile screen
        val context = NavigationContextTracker.NavigationContext(
            currentApp = "Instagram",
            currentScreen = "profile"
        )
        
        // When: User says "open"
        val result = SmartCommandMatcher.matchWithContext("open", context)
        
        // Then: Should disambiguate to opening profile, not opening app
        assertNotNull("Result should not be null", result)
        assertEquals("Intent should be OPEN_APP", SmartCommandMatcher.CommandIntent.OPEN_APP, result?.intent)
        assertEquals("Extracted param should be 'profile'", "profile", result?.extractedParam)
    }

    @Test
    fun testMatchWithContext_InstagramReelsScreen_ScrollCommand() {
        // Given: User is on Instagram reels screen
        val context = NavigationContextTracker.NavigationContext(
            currentApp = "Instagram",
            currentScreen = "reels"
        )
        
        // When: User says "scroll"
        val result = SmartCommandMatcher.matchWithContext("scroll", context)
        
        // Then: Should scroll down reels
        assertNotNull(result)
        assertEquals(SmartCommandMatcher.CommandIntent.SCROLL_DOWN, result?.intent)
        assertEquals("reels", result?.extractedParam)
    }

    @Test
    fun testMatchWithContext_WhatsAppChatList_OpenCommand() {
        // Given: User is on WhatsApp chat list
        val context = NavigationContextTracker.NavigationContext(
            currentApp = "WhatsApp",
            currentScreen = "chat list"
        )
        
        // When: User says "open"
        val result = SmartCommandMatcher.matchWithContext("open", context)
        
        // Then: Should click first chat
        assertNotNull(result)
        assertEquals(SmartCommandMatcher.CommandIntent.CLICK_TARGET, result?.intent)
        assertEquals("first chat", result?.extractedParam)
    }

    @Test
    fun testMatchWithContext_YouTubeSearchResults_PlayCommand() {
        // Given: User is on YouTube search results
        val context = NavigationContextTracker.NavigationContext(
            currentApp = "YouTube",
            currentScreen = "search results"
        )
        
        // When: User says "play"
        val result = SmartCommandMatcher.matchWithContext("play", context)
        
        // Then: Should click first video
        assertNotNull(result)
        assertEquals(SmartCommandMatcher.CommandIntent.CLICK_TARGET, result?.intent)
        assertEquals("first video", result?.extractedParam)
    }

    @Test
    fun testMatchWithContext_NoContext_StandardMatching() {
        // Given: No context available
        val context = NavigationContextTracker.NavigationContext()
        
        // When: User says "go home"
        val result = SmartCommandMatcher.matchWithContext("go home", context)
        
        // Then: Should use standard matching
        assertNotNull(result)
        assertEquals(SmartCommandMatcher.CommandIntent.GO_HOME, result?.intent)
    }

    @Test
    fun testLearnFromCorrection_StoresCorrection() {
        // Given: A mismatched command
        val input = "opn instagram"
        val correctIntent = SmartCommandMatcher.CommandIntent.OPEN_APP
        
        // When: We learn from the correction
        SmartCommandMatcher.learnFromCorrection(input, correctIntent)
        
        // Then: The correction should be stored
        assertEquals(1, SmartCommandMatcher.getLearnedCorrectionsCount())
        
        // And: Future matches should use the learned correction
        val result = SmartCommandMatcher.matchCommand(input)
        assertNotNull(result)
        assertEquals(correctIntent, result?.intent)
    }

    @Test
    fun testLearnFromCorrection_IncreasesConfidence() {
        // Given: A command that needs correction
        val input = "opn instagram"
        val correctIntent = SmartCommandMatcher.CommandIntent.OPEN_APP
        
        // When: We learn from the same correction multiple times
        SmartCommandMatcher.learnFromCorrection(input, correctIntent)
        val firstResult = SmartCommandMatcher.matchCommand(input)
        val firstScore = firstResult?.score ?: 0f
        
        SmartCommandMatcher.learnFromCorrection(input, correctIntent)
        val secondResult = SmartCommandMatcher.matchCommand(input)
        val secondScore = secondResult?.score ?: 0f
        
        // Then: Confidence should increase
        assertTrue(secondScore > firstScore)
    }

    @Test
    fun testAddSynonym_CreatesLearning() {
        // Given: A canonical command and a variation
        val canonical = "open instagram"
        val variation = "launch insta"
        
        // When: We add the synonym
        SmartCommandMatcher.addSynonym(canonical, variation)
        
        // Then: The variation should match the same intent
        val canonicalResult = SmartCommandMatcher.matchCommand(canonical)
        val variationResult = SmartCommandMatcher.matchCommand(variation)
        
        assertNotNull(canonicalResult)
        assertNotNull(variationResult)
        assertEquals(canonicalResult?.intent, variationResult?.intent)
    }

    @Test
    fun testClearLearnedCorrections_RemovesAllLearning() {
        // Given: Some learned corrections
        SmartCommandMatcher.learnFromCorrection("opn instagram", SmartCommandMatcher.CommandIntent.OPEN_APP)
        SmartCommandMatcher.learnFromCorrection("cls app", SmartCommandMatcher.CommandIntent.GO_BACK)
        assertEquals(2, SmartCommandMatcher.getLearnedCorrectionsCount())
        
        // When: We clear all corrections
        SmartCommandMatcher.clearLearnedCorrections()
        
        // Then: No corrections should remain
        assertEquals(0, SmartCommandMatcher.getLearnedCorrectionsCount())
    }

    @Test
    fun testGetConfidenceScore_ReturnsNormalizedScore() {
        // Given: A well-matched command
        val input = "go home"
        val intent = SmartCommandMatcher.CommandIntent.GO_HOME
        
        // When: We get the confidence score
        val confidence = SmartCommandMatcher.getConfidenceScore(input, intent)
        
        // Then: Score should be between 0.0 and 1.0
        assertTrue(confidence >= 0.0f)
        assertTrue(confidence <= 1.0f)
        assertTrue(confidence > 0.5f) // Should be high confidence for exact match
    }

    @Test
    fun testGetConfidenceScore_WrongIntent_ReturnsZero() {
        // Given: A command that doesn't match the intent
        val input = "go home"
        val wrongIntent = SmartCommandMatcher.CommandIntent.VOLUME_UP
        
        // When: We get the confidence score
        val confidence = SmartCommandMatcher.getConfidenceScore(input, wrongIntent)
        
        // Then: Score should be zero
        assertEquals(0.0f, confidence, 0.01f)
    }
}
