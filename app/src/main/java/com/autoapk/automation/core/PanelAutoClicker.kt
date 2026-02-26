package com.autoapk.automation.core

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * PanelAutoClicker — Handles auto-clicking toggles in Settings panels.
 *
 * When WiFi or Mobile Data needs a panel (API 29+), this class:
 * 1. Waits for the panel to render (700ms)
 * 2. Searches the accessibility tree for the target toggle
 * 3. Detects current state (on/off)
 * 4. Clicks if needed
 * 5. Presses back to close the panel
 */
class PanelAutoClicker(private val service: AutomationAccessibilityService) {

    companion object {
        private const val TAG = "Neo_PanelClick"
        private const val PANEL_WAIT_MS = 700L
        private const val CLOSE_DELAY_MS = 300L
        private const val GLOBAL_ACTION_BACK = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Open a settings panel and click the target toggle.
     *
     * @param panelIntent The intent to open the panel (e.g., ACTION_WIFI)
     * @param targetLabel The label to search for (e.g., "Wi-Fi", "Mobile data")
     * @param desiredState true = want ON, false = want OFF, null = just toggle
     * @param callback Called with success/failure result
     */
    fun clickToggleInPanel(
        panelIntent: Intent,
        targetLabel: String,
        desiredState: Boolean?,
        callback: (Boolean) -> Unit
    ) {
        // Step 1: Launch panel
        try {
            panelIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            service.startActivity(panelIntent)
            Log.i(TAG, "Panel launched for '$targetLabel'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch panel: ${e.message}")
            callback(false)
            return
        }

        // Step 2: Wait for panel to render, then scan
        handler.postDelayed({
            try {
                val root = service.rootInActiveWindow
                if (root == null) {
                    Log.e(TAG, "rootInActiveWindow is null — cannot scan panel")
                    callback(false)
                    return@postDelayed
                }

                // Step 3: Search for the target toggle
                val candidates = mutableListOf<NodeCandidate>()
                findToggleNodes(root, targetLabel.lowercase(), candidates)

                if (candidates.isEmpty()) {
                    Log.w(TAG, "No toggle found for '$targetLabel' in panel")
                    closePanel()
                    callback(false)
                    return@postDelayed
                }

                // Step 4: Pick the best candidate
                val best = candidates.maxByOrNull { it.score }!!
                Log.i(TAG, "Found toggle: '${best.label}' (score=${best.score}, checked=${best.isChecked})")

                // Step 5: Check current state
                if (desiredState != null && best.isChecked == desiredState) {
                    Log.i(TAG, "Toggle '$targetLabel' already in desired state (${if (desiredState) "ON" else "OFF"})")
                    closePanel()
                    callback(true)
                    return@postDelayed
                }

                // Step 6: Click the toggle
                val clicked = clickNode(best.node)
                Log.i(TAG, "Click result for '$targetLabel': $clicked")

                // Step 7: Close panel after brief delay
                handler.postDelayed({ closePanel() }, CLOSE_DELAY_MS)
                callback(clicked)

            } catch (e: Exception) {
                Log.e(TAG, "Panel scan error: ${e.message}")
                closePanel()
                callback(false)
            }
        }, PANEL_WAIT_MS)
    }

    // ==================== NODE SEARCH ====================

    private data class NodeCandidate(
        val node: AccessibilityNodeInfo,
        val label: String,
        val score: Float,
        val isChecked: Boolean
    )

    /**
     * Recursively search the accessibility tree for nodes matching the target label.
     * Looks for Switch, ToggleButton, CompoundButton, or clickable nodes near matching text.
     */
    private fun findToggleNodes(
        node: AccessibilityNodeInfo,
        targetLower: String,
        results: MutableList<NodeCandidate>
    ) {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val className = node.className?.toString() ?: ""

        val label = desc.ifBlank { text }
        var score = 0f

        // Score by label match
        if (label.contains(targetLower)) {
            score += 0.8f
            if (label.startsWith(targetLower)) score += 0.1f
            if (label == targetLower) score += 0.1f
        }

        // Bonus for toggle-type classes
        val isToggleClass = className.contains("Switch") ||
                className.contains("ToggleButton") ||
                className.contains("CompoundButton") ||
                className.contains("CheckBox")

        if (isToggleClass) score += 0.3f

        // Detect checked state
        val isChecked = node.isChecked ||
                desc.contains(", on") || desc.contains(" on,") ||
                desc.contains("enabled") || desc.contains("connected") ||
                node.isSelected

        if (score > 0.4f && (node.isClickable || isToggleClass)) {
            results.add(NodeCandidate(node, label, score, isChecked))
        }

        // If this node has matching text but isn't clickable, check parent/siblings
        if (score > 0.4f && !node.isClickable && !isToggleClass) {
            val parent = node.parent
            if (parent != null) {
                // Search siblings for a switch
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    val sibClass = sibling.className?.toString() ?: ""
                    if (sibClass.contains("Switch") || sibClass.contains("Toggle") || sibling.isCheckable) {
                        val sibChecked = sibling.isChecked || sibling.isSelected
                        results.add(NodeCandidate(sibling, label, score + 0.2f, sibChecked))
                    }
                }
                // Check parent clickability
                if (parent.isClickable) {
                    results.add(NodeCandidate(parent, label, score + 0.1f, isChecked))
                }
            }
        }

        // Recurse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findToggleNodes(child, targetLower, results)
        }
    }

    // ==================== CLICK ====================

    /**
     * Click a node — try direct click, then parent click, then coordinate tap.
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Tier 1: Direct click
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) return true
        }

        // Tier 2: Parent click (walk up to 3 levels)
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) return true
            }
            parent = parent.parent
            depth++
        }

        // Tier 3: Coordinate tap fallback
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()
        if (centerX > 0 && centerY > 0) {
            service.tapAt(centerX, centerY)
            Log.i(TAG, "Fallback: tapped at ($centerX, $centerY)")
            return true
        }

        return false
    }

    // ==================== CLOSE PANEL ====================

    private fun closePanel() {
        service.performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            service.performGlobalAction(GLOBAL_ACTION_BACK)
        }, 250)
        Log.i(TAG, "Panel closed (2x back)")
    }

}
