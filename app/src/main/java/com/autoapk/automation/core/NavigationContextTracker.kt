package com.autoapk.automation.core

import android.util.Log

/**
 * Tracks navigation context for pronoun resolution and command chaining.
 * 
 * This class maintains information about the current app, screen, focused user,
 * and UI element to enable natural language commands with pronouns like "his", "her",
 * "their", "that", "this", and "it".
 * 
 * Requirements: 4.1, 4.2, 4.3, 4.10
 */
class NavigationContextTracker {
    
    companion object {
        private const val TAG = "NavigationContextTracker"
        private const val MAX_COMMAND_HISTORY = 10
    }
    
    /**
     * Data class representing the current navigation context.
     * 
     * @property currentApp The name of the currently active app
     * @property currentScreen The name of the current screen within the app
     * @property focusedUser The name of the user currently in focus (e.g., from a search)
     * @property focusedElement The name of the UI element currently in focus
     * @property commandHistory List of recently executed commands (max 10)
     */
    data class NavigationContext(
        val currentApp: String? = null,
        val currentScreen: String? = null,
        val focusedUser: String? = null,
        val focusedElement: String? = null,
        val commandHistory: List<String> = emptyList()
    )
    
    // Current context state
    private var context = NavigationContext()
    
    /**
     * Updates the current app name.
     * 
     * When switching to a different app, this also clears the focused user
     * and focused element while retaining command history.
     * 
     * @param appName The name of the app being opened
     */
    fun updateApp(appName: String) {
        val previousApp = context.currentApp
        
        if (previousApp != null && previousApp != appName) {
            // App switch detected - clear user context but keep history
            Log.d(TAG, "App switch detected: $previousApp -> $appName. Clearing user context.")
            context = context.copy(
                currentApp = appName,
                currentScreen = null,
                focusedUser = null,
                focusedElement = null
                // commandHistory is retained
            )
        } else {
            // Same app or first app - just update app name
            context = context.copy(currentApp = appName)
        }
        
        Log.d(TAG, "Updated app: $appName")
    }
    
    /**
     * Updates the current screen name within the app.
     * 
     * @param screenName The name of the screen being navigated to
     */
    fun updateScreen(screenName: String) {
        context = context.copy(currentScreen = screenName)
        Log.d(TAG, "Updated screen: $screenName")
    }
    
    /**
     * Sets the focused user (e.g., from a search command).
     * 
     * This user becomes the target for pronoun resolution like "his", "her", "their".
     * 
     * @param userName The name of the user to focus on
     */
    fun setFocusedUser(userName: String) {
        context = context.copy(focusedUser = userName)
        Log.d(TAG, "Set focused user: $userName")
    }
    
    /**
     * Sets the focused UI element.
     * 
     * This element becomes the target for pronoun resolution like "that", "this", "it".
     * 
     * @param elementName The name or description of the UI element
     */
    fun setFocusedElement(elementName: String) {
        context = context.copy(focusedElement = elementName)
        Log.d(TAG, "Set focused element: $elementName")
    }
    
    /**
     * Adds a command to the command history.
     * 
     * Maintains a maximum of 10 commands in history. When the limit is reached,
     * the oldest command is removed.
     * 
     * @param command The command string to add to history
     */
    fun addCommandToHistory(command: String) {
        val updatedHistory = context.commandHistory.toMutableList()
        updatedHistory.add(command)
        
        // Keep only the last MAX_COMMAND_HISTORY commands
        while (updatedHistory.size > MAX_COMMAND_HISTORY) {
            updatedHistory.removeAt(0)
        }
        
        context = context.copy(commandHistory = updatedHistory)
        Log.d(TAG, "Added command to history: $command (history size: ${updatedHistory.size})")
    }
    
    /**
     * Resolves a pronoun to a concrete entity from the current context.
     * 
     * Supported pronouns:
     * - "his", "her", "their" -> focused user
     * - "that", "this", "it" -> focused element
     * 
     * @param pronoun The pronoun to resolve
     * @return The resolved entity name, or null if the pronoun cannot be resolved
     */
    fun resolvePronoun(pronoun: String): String? {
        val normalized = pronoun.lowercase().trim()
        
        return when (normalized) {
            "his", "her", "their" -> {
                val user = context.focusedUser
                if (user != null) {
                    Log.d(TAG, "Resolved pronoun '$pronoun' to user: $user")
                } else {
                    Log.w(TAG, "Cannot resolve pronoun '$pronoun': no focused user")
                }
                user
            }
            "that", "this", "it" -> {
                val element = context.focusedElement
                if (element != null) {
                    Log.d(TAG, "Resolved pronoun '$pronoun' to element: $element")
                } else {
                    Log.w(TAG, "Cannot resolve pronoun '$pronoun': no focused element")
                }
                element
            }
            else -> {
                Log.w(TAG, "Unknown pronoun: $pronoun")
                null
            }
        }
    }
    
    /**
     * Gets the current navigation context.
     * 
     * @return A copy of the current context
     */
    fun getContext(): NavigationContext {
        return context.copy()
    }
    
    /**
     * Clears the focused user and focused element from the context.
     * 
     * This is typically called when switching apps or when context is no longer relevant.
     * Command history is retained.
     */
    fun clearUserContext() {
        context = context.copy(
            focusedUser = null,
            focusedElement = null
        )
        Log.d(TAG, "Cleared user context (user and element)")
    }
    
    /**
     * Clears all context information including command history.
     * 
     * This resets the tracker to its initial state.
     */
    fun clearAll() {
        context = NavigationContext()
        Log.d(TAG, "Cleared all context")
    }
    
    /**
     * Checks if there is any user context available for pronoun resolution.
     * 
     * @return true if either focused user or focused element is set
     */
    fun hasUserContext(): Boolean {
        return context.focusedUser != null || context.focusedElement != null
    }
    
    /**
     * Gets the focused user name if available.
     * 
     * @return The focused user name, or null if not set
     */
    fun getFocusedUser(): String? {
        return context.focusedUser
    }
    
    /**
     * Gets the focused element name if available.
     * 
     * @return The focused element name, or null if not set
     */
    fun getFocusedElement(): String? {
        return context.focusedElement
    }
    
    /**
     * Gets the current app name if available.
     * 
     * @return The current app name, or null if not set
     */
    fun getCurrentApp(): String? {
        return context.currentApp
    }
    
    /**
     * Gets the current screen name if available.
     * 
     * @return The current screen name, or null if not set
     */
    fun getCurrentScreen(): String? {
        return context.currentScreen
    }
    
    /**
     * Gets the command history.
     * 
     * @return A copy of the command history list
     */
    fun getCommandHistory(): List<String> {
        return context.commandHistory.toList()
    }
}
