package com.brain.tracscript.plugins.scenario

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class AccessibilityNodeFinder(
    private val service: AccessibilityService,
    private val logI: (String) -> Unit = {},
    private val logW: (String) -> Unit = {},
    private val logE: (String, Throwable?) -> Unit = { _, _ -> }
) {

    companion object {
        private const val MAX_FIND_BEST_CHECKS = 10000
    }

    // ---------------------------
    // Root selection (windows-aware)
    // ---------------------------

    private fun findRootForPoint(x: Int, y: Int): AccessibilityNodeInfo? {
        val ws = service.windows ?: return service.rootInActiveWindow

        val candidates = ws.mapNotNull { w ->
            val r = Rect()
            w.getBoundsInScreen(r)
            val root = w.root ?: return@mapNotNull null
            Triple(w, r, root)
        }.filter { (_, r, _) ->
            r.contains(x, y)
        }.sortedWith(
            compareByDescending<Triple<AccessibilityWindowInfo, Rect, AccessibilityNodeInfo>> { (w, _, _) ->
                (w.type == AccessibilityWindowInfo.TYPE_APPLICATION)
            }.thenByDescending { (w, _, _) ->
                (w.isActive || w.isFocused)
            }
        )

        return candidates.firstOrNull()?.third ?: service.rootInActiveWindow
    }

    fun dumpWindows(x: Int, y: Int): String {
        val sb = StringBuilder()
        sb.appendLine("---- WINDOWS DUMP for point ($x,$y) ----")
        val ws = service.windows
        if (ws == null) {
            sb.appendLine("windows=null")
            return sb.toString()
        }

        ws.forEachIndexed { i, w ->
            val r = Rect()
            w.getBoundsInScreen(r)
            val root = w.root
            val pkg = root?.packageName
            sb.appendLine(
                "W[$i] type=${w.type} active=${w.isActive} focused=${w.isFocused} " +
                        "bounds=$r contains=${r.contains(x, y)} rootPkg=$pkg root=${root != null}"
            )
        }
        return sb.toString()
    }

    // ---------------------------
    // Hit-test by coordinates
    // ---------------------------

    fun findNodeAtPosition(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null

        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null

        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val hit = findNodeAtPosition(child, x, y)
            if (hit != null) return hit
        }
        return root
    }

    fun findNodeAtPositionSmart(x: Int, y: Int): AccessibilityNodeInfo? {
        val root = findRootForPoint(x, y) ?: return null
        root.refresh()
        return findNodeAtPosition(root, x, y)
    }

    // ---------------------------
    // Search by ViewId (exact)
    // ---------------------------

    fun findNodeByViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val list = root.findAccessibilityNodeInfosByViewId(id)
        return list.firstOrNull()
    }

    // ---------------------------
    // Search by ViewId with wildcard (*)
    // ---------------------------

    fun findNodeByIdLike(root: AccessibilityNodeInfo?, idQuery: String): AccessibilityNodeInfo? {
        if (root == null) return null

        val hasWildcard = idQuery.contains('*')
        val pattern = if (hasWildcard) idQuery.replace("*", ".*").toRegex() else null

        fun matches(resId: String?): Boolean {
            if (resId == null) return false
            return if (hasWildcard) pattern!!.matches(resId) else resId == idQuery
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = HashSet<Int>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val id = System.identityHashCode(node)
            if (!visited.add(id)) continue

            val resId = node.viewIdResourceName
            if (matches(resId)) {
                logI("findNodeByIdLike: found id=$resId, class=${node.className}")
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return null
    }

    // ---------------------------
    // Search by text (exact)
    // ---------------------------

    fun findNodeByTextExact(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null

        val target = text.trim().lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = HashSet<Int>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val id = System.identityHashCode(node)
            if (!visited.add(id)) continue

            val nodeText = (node.text ?: node.contentDescription)
                ?.toString()
                ?.trim()
                ?.lowercase()

            if (nodeText == target) return node

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ---------------------------
    // Search "best" node by text (your old logic)
    // ---------------------------

    fun findBestNodeByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null

        val target = text.trim().lowercase()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val visited = HashSet<String>()
        val matches = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

        queue.add(root to 0)
        var checks = 0

        fun nodeId(n: AccessibilityNodeInfo): String =
            "${n.windowId}:${System.identityHashCode(n)}"

        while (queue.isNotEmpty()) {
            if (++checks > MAX_FIND_BEST_CHECKS) {
                logE("findBestNodeByText: HARD STOP, visited=$checks", null)
                break
            }

            val (node, depth) = queue.removeFirst()
            val id = nodeId(node)
            if (!visited.add(id)) continue

            val nodeText = (node.text ?: node.contentDescription)
                ?.toString()
                ?.trim()
                ?.lowercase()

            if (nodeText == target) {
                matches.add(node to depth)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    queue.add(child to (depth + 1))
                }
            }
        }

        if (matches.isEmpty()) return null

        // priority: buttons
        matches
            .filter { it.first.className?.toString()?.contains("button", true) == true }
            .minByOrNull { it.second }
            ?.let { return it.first }

        // then clickable
        matches
            .filter { it.first.isClickable }
            .minByOrNull { it.second }
            ?.let { return it.first }

        return matches.minByOrNull { it.second }?.first
    }

    // ---------------------------
    // Click node or its parents
    // ---------------------------

    fun clickNodeOrParents(startNode: AccessibilityNodeInfo, maxDepth: Int = 20): Boolean {
        var current: AccessibilityNodeInfo? = startNode
        var depth = 0
        val visited = mutableSetOf<Int>()

        while (current != null && depth < maxDepth) {
            val idHash = System.identityHashCode(current)
            if (!visited.add(idHash)) {
                logW("Cycle detected in Accessibility tree (clickNodeOrParents), aborting")
                return false
            }

            val cls = current.className
            val txt = current.text
            val clickableFlag = current.isClickable

            logI("Try to click: depth=$depth cls=$cls txt=$txt clickable=$clickableFlag")

            val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) {
                logI("performAction(ACTION_CLICK) success on depth=$depth cls=$cls")
                return true
            }

            current = current.parent
            depth++
        }

        logI("Failed to click the node or any parent (maxDepth=$maxDepth)")
        return false
    }

    // ---------------------------
    // Dump helpers
    // ---------------------------

    fun dumpFullTreeFrom(node: AccessibilityNodeInfo?): String {
        if (node == null) return "node=null"

        val sb = StringBuilder()
        sb.appendLine("\n================= INSPECTOR =================")

        sb.appendLine("\n--- PARENTS ---")
        var p = node.parent
        var level = 0
        while (p != null && level < 20) {
            sb.appendLine(describeNode("Parent[$level]", p))
            p = p.parent
            level++
        }

        sb.appendLine("\n--- CURRENT NODE ---")
        sb.appendLine(describeNode("Node", node))

        sb.appendLine("\n--- CHILDREN ---")
        for (i in 0 until node.childCount) {
            sb.appendLine(describeNode("Child[$i]", node.getChild(i)))
        }

        sb.appendLine("\n--- SIBLINGS ---")
        val parent = node.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                sb.appendLine(describeNode("Sibling[$i]", parent.getChild(i)))
            }
        }

        sb.appendLine("\n=============================================\n")
        return sb.toString()
    }

    fun describeNode(tag: String, n: AccessibilityNodeInfo?): String {
        if (n == null) return "$tag: null"

        val rect = Rect()
        n.getBoundsInScreen(rect)

        val text = n.text ?: n.contentDescription ?: ""

        return "$tag: cls=${n.className}, txt='$text', id=${n.viewIdResourceName}, " +
                "click=${n.isClickable}, childCount=${n.childCount}, bounds=$rect"
    }
}