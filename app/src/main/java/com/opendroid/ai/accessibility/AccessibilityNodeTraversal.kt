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
     * Finds and clicks the first actionable content item on screen — e.g. the first
     * YouTube search-result card, the first list entry, etc.
     *
     * Strategy (YouTube-aware):
     *  1. Walk the tree depth-first and collect ALL clickable nodes that have a non-empty
     *     contentDescription (YouTube video cards expose their title there) or non-empty
     *     text, and whose bounding-box height >= 80 px (filters out tiny toolbar icons).
     *  2. Sort collected candidates by top-Y coordinate so the topmost item wins.
     *  3. Skip nodes whose label exactly matches known system-chrome strings (search bar,
     *     back button, etc.) to avoid clicking the search field instead of the first video.
     *  4. Try each candidate in order; return true on the first successful ACTION_CLICK.
     *     All collected nodes are recycled afterwards regardless of outcome.
     */
    fun findAndClickFirstResult(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        // We store (topY, node) pairs; all nodes in this list must be recycled when done.
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        try {
            collectResultCandidates(root, candidates)
            candidates.sortBy { it.first }
            for ((_, node) in candidates) {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return true
            }
            return candidates.isNotEmpty()
        } finally {
            // Always recycle every node we collected so we don't leak accessibility objects.
            for ((_, node) in candidates) {
                try { node.recycle() } catch (_: Exception) {}
            }
        }
    }

    private val systemUiLabels = setOf(
        "search", "back", "home", "recents", "more options", "navigate up",
        "youtube", "settings", "account", "notifications", "cast", "voice search",
        "clear search", "search youtube", "close", "menu", "share", "like",
        "dislike", "subscribe", "save", "download", "report"
    )

    private fun collectResultCandidates(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (!node.isVisibleToUser) return

        val contentDesc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val label = contentDesc.ifEmpty { text }

        if (node.isClickable && label.isNotBlank()) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val height = bounds.height()
            val descLower = label.lowercase().trim()
            // Skip tiny elements (icons) and known system-chrome / action-button labels.
            val isSystemUi = systemUiLabels.any { descLower == it || descLower.startsWith("$it ") }
            if (height >= 80 && !isSystemUi) {
                // Keep this node — do NOT recycle it here; caller recycles after clicking.
                out.add(Pair(bounds.top, node))
                // Don't recurse into a matched card: its children would be duplicates.
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val sizeBefore = out.size
            collectResultCandidates(child, out)
            // If the child wasn't added to `out` (and none of its descendants were either
            // via the child reference itself), recycle it now. If it was added, the caller
            // owns its lifecycle.
            if (out.size == sizeBefore || out.none { it.second === child }) {
                // Safe to recycle: child is either a non-candidate intermediate node,
                // or its descendants (not child itself) were added.
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }
}
