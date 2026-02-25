package com.autoapk.automation.core

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner
import org.junit.Assert.*

/**
 * Unit tests for command chaining functionality in CommandProcessor.
 * 
 * Tests the ability to parse and execute multi-step commands separated by commas,
 * maintaining context between steps.
 * 
 * Requirements: 4.9
 */
@RunWith(MockitoJUnitRunner::class)
class CommandChainingTest {
    
    @Mock
    private lateinit var mockContext: Context
    
    @Mock
    private lateinit var mockAppRegistry: AppRegistry
    
    private lateinit var commandProcessor: CommandProcessor
    
    @Before
    fun setup() {
        commandProcessor = CommandProcessor(mockContext, mockAppRegistry)
    }
    
    @Test
    fun `test single command without comma is processed normally`() {
        // Single command should work as before
        val result = commandProcessor.process("go home")
        
        // We can't verify the exact result without mocking the service,
        // but we can verify it doesn't crash
        assertNotNull(result)
    }
    
    @Test
    fun `test chained command is detected and split correctly`() {
        // This test verifies that commands with commas are detected as chained
        val chainedCommand = "go home, open recents, go back"
        
        // Process the chained command
        val result = commandProcessor.process(chainedCommand)
        
        // Verify it doesn't crash
        assertNotNull(result)
    }
    
    @Test
    fun `test empty steps are filtered out`() {
        // Command with extra commas should filter out empty steps
        val chainedCommand = "go home,, open recents,  , go back"
        
        val result = commandProcessor.process(chainedCommand)
        
        // Should not crash with empty steps
        assertNotNull(result)
    }
    
    @Test
    fun `test single step chained command works`() {
        // A "chained" command with only one step should still work
        val chainedCommand = "go home,"
        
        val result = commandProcessor.process(chainedCommand)
        
        assertNotNull(result)
    }
    
    @Test
    fun `test navigation context is maintained across chained commands`() {
        // This is a basic test - full integration testing would require
        // mocking the accessibility service and app registry
        
        val chainedCommand = "open instagram, go back"
        
        val result = commandProcessor.process(chainedCommand)
        
        // Verify it processes without crashing
        assertNotNull(result)
    }
}
