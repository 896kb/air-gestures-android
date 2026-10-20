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
     * Where [mark]'s item is in [items] now, or −1 if it's gone: the same id and text; else the
     * same id (a pressed Play now says Pause); else, for an item without an id, the very same
     * place. Look-alikes (a grid of the same cell) are told apart by how close they are.
     */
    fun find(items: List<NavNode>, mark: Mark?): Int {
        if (mark == null) return -1
        fun distance(node: NavNode) =
            abs(node.left - mark.left) + abs(node.top - mark.top) + abs(node.right - mark.right) + abs(node.bottom - mark.bottom)
        val tiers = listOf<(NavNode) -> Boolean>(
            { mark.named && it.id == mark.id && it.text == mark.text },
            { mark.id != null && it.id == mark.id },
            { mark.id == null && it.id == null && distance(it) == 0 },
        )
        for (tier in tiers) {
            val hit = items.withIndex().filter { tier(it.value) }.minByOrNull { distance(it.value) }
            if (hit != null) return hit.index
        }
        return -1
    }
}
