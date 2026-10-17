package com.opendroid.ai.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject

/**
 * Pure node-tree traversal/matching logic used by [OpenDroidAccessibilityService],
 * extracted so it can be exercised in tests without a bound accessibility service.
 *
 * Every function here takes the root [AccessibilityNodeInfo] as a parameter instead
 * of reading `rootInActiveWindow` itself, so a test can hand it either the real
 * service's root or a root obtained straight from `UiAutomation` - same behavior,
 * no service required. See #66 (prototype) / #105 (this extraction).
 *
 * Stateless and dependency-free: production wires it in through Hilt, tests can
 * just `AccessibilityNodeTraversal()` it directly.
 *
 * Callers remain responsible for recycling the root node they obtained; this class
 * only recycles the intermediate/child nodes it creates while walking.
 */
class AccessibilityNodeTraversal @Inject constructor() {

    /** Concatenates the text/contentDescription of every node in the tree, depth-first. */
    fun screenText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val nodeText = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        if (!nodeText.isNullOrEmpty()) {
            sb.append(nodeText).append("\n")
        } else if (!contentDesc.isNullOrEmpty()) {
            sb.append(contentDesc).append("\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
            child.recycle()
        }
    }

    /**
     * Finds the first node matching [text] (by text or contentDescription,
     * or the nearest clickable ancestor, for leaves that aren't themselves clickable)
     * and performs ACTION_CLICK on it. Returns whether a click was dispatched.
     */
    fun findAndClick(root: AccessibilityNodeInfo?, text: String): Boolean {
        if (root == null || text.isBlank()) return false

        // 1. Primary fast-path: Android's native text lookup
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                if (ok) return true
            } else {
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        node.recycle()
                        if (ok) return true
                        break
                    }
                    val grandParent = parent.parent
                    parent.recycle()
                    parent = grandParent
                }
            }
            node.recycle()
        }

        // 2. Secondary fallback: recursive depth-first matching checking text AND contentDescription
        // (YouTube and media apps often place titles in contentDescription or composite accessibility labels)
        return findAndClickRecursive(root, text.lowercase().trim())
    }

    private fun findAndClickRecursive(node: AccessibilityNodeInfo, targetLower: String): Boolean {
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        val isMatch = (nodeText.isNotEmpty() && (nodeText.contains(targetLower) || targetLower.contains(nodeText))) ||
            (contentDesc.isNotEmpty() && (contentDesc.contains(targetLower) || targetLower.contains(contentDesc)))

        if (isMatch) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    if (clicked) return true
                    break
                }
                val grandParent = parent.parent
                parent.recycle()
                parent = grandParent
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickRecursive(child, targetLower)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /**
     * Walks the tree depth-first for a clickable node whose text or
     * contentDescription (case-insensitive) is one of [labels], and clicks it.
     * This is the IME-submit fallback used when ACTION_IME_ENTER isn't
     * available or fails.
     */
    fun findAndClickSubmitControl(root: AccessibilityNodeInfo?, labels: List<String>): Boolean {
        if (root == null) return false
        return findSubmitNode(root, labels)
    }

    private fun findSubmitNode(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase()
        val contentDesc = node.contentDescription?.toString()?.lowercase()
        if (node.isClickable && (labels.contains(text) || labels.contains(contentDesc))) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findSubmitNode(child, labels)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /**
     * Finds and clicks the first actionable content item on screen
     * (e.g. first YouTube search result, first list card, or first prominent clickable view).
     */
    fun findAndClickFirstResult(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        return findFirstContentNode(root)
    }

    private fun findFirstContentNode(node: AccessibilityNodeInfo): Boolean {
        // Look for clickable containers or views with meaningful content
        val isClickable = node.isClickable
        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val className = node.className?.toString()?.lowercase() ?: ""

        val isLikelyResult = isClickable && hasText &&
            (className.contains("viewgroup") || className.contains("layout") || className.contains("view") || className.contains("card"))

        if (isLikelyResult) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findFirstContentNode(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }
}
