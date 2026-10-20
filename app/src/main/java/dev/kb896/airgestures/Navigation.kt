package dev.kb896.airgestures

import kotlin.math.abs

/**
 * Moving through what's on screen with gestures: which items can be focused, in what order,
 * and how focus finds its item again when the screen changes. Free of Android (the service
 * wraps the real nodes), so it's unit-tested.
 */
object Navigation {
    /** A screen element, as much of it as navigation needs. */
    interface NavNode {
        val id: String?
        val text: String?
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        val clickable: Boolean
        val editable: Boolean
        val checkable: Boolean
        val visible: Boolean
        val enabled: Boolean
        val children: List<NavNode>
    }

    /** What can be focused under [root]: visible, enabled things to press, tick or type in, in reading order. */
    fun focusables(root: NavNode): List<NavNode> = buildList {
        fun walk(node: NavNode) {
            if (!node.visible) return
            val usable = node.enabled && (node.clickable || node.editable || node.checkable) &&
                node.right > node.left && node.bottom > node.top
            if (usable) add(node)
            node.children.forEach(::walk)
        }
        walk(root)
    }

    /** From [at] (−1 = nothing focused yet) [by] items, wrapping round; −1 when there's nothing. */
    fun step(size: Int, at: Int, by: Int): Int = when {
        size == 0 -> -1
        at < 0 -> if (by >= 0) 0 else size - 1
        else -> Math.floorMod(at + by, size)
    }

    /** Enough of a focused item to find it again on a changed screen. */
    data class Mark(val id: String?, val text: String?, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val named get() = id != null || text != null

        companion object {
            fun of(node: NavNode) = Mark(node.id, node.text, node.left, node.top, node.right, node.bottom)
        }
    }

    /**
     * Where [mark]'s item is in [items] now: the same id and text, nearest to where it was; an
     * item with neither, by exactly where it was. −1 if it's gone.
     */
    fun find(items: List<NavNode>, mark: Mark?): Int {
        if (mark == null) return -1
        val same = items.withIndex().filter { (_, node) ->
            if (mark.named) {
                node.id == mark.id && node.text == mark.text
            } else {
                node.id == null && node.text == null && node.left == mark.left && node.top == mark.top &&
                    node.right == mark.right && node.bottom == mark.bottom
            }
        }
        return same.minByOrNull { (_, node) -> abs(node.top - mark.top) }?.index ?: -1
    }
}
