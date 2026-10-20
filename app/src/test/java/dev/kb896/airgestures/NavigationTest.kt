package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {
    /** A screen element for the tests. */
    private data class Fake(
        override val id: String? = null,
        override val text: String? = null,
        override val left: Int = 0,
        override val top: Int = 0,
        override val right: Int = 100,
        override val bottom: Int = 50,
        override val clickable: Boolean = false,
        override val editable: Boolean = false,
        override val checkable: Boolean = false,
        override val visible: Boolean = true,
        override val enabled: Boolean = true,
        override val children: List<Fake> = emptyList(),
    ) : Navigation.NavNode

    private val screen = Fake(
        children = listOf(
            Fake(text = "Title"),
            Fake(id = "search", editable = true, top = 50, bottom = 100),
            Fake(
                id = "row1", clickable = true, top = 100, bottom = 150,
                children = listOf(Fake(text = "Row one"), Fake(id = "star1", clickable = true, top = 100, bottom = 150)),
            ),
            Fake(id = "hidden", clickable = true, visible = false),
            Fake(id = "off", clickable = true, enabled = false),
            Fake(id = "empty", clickable = true, right = 0, bottom = 0),
            Fake(id = "wifi", checkable = true, top = 150, bottom = 200),
        ),
    )

    @Test fun `focusable items in reading order, skipping hidden, disabled and empty ones`() =
        assertEquals(listOf("search", "row1", "star1", "wifi"), Navigation.focusables(screen).map { it.id })

    @Test fun `next and previous wrap round, and start at the first`() {
        assertEquals(1, Navigation.step(size = 4, at = 0, by = 1))
        assertEquals(0, Navigation.step(size = 4, at = 3, by = 1))
        assertEquals(3, Navigation.step(size = 4, at = 0, by = -1))
        assertEquals(0, Navigation.step(size = 4, at = -1, by = 1))
        assertEquals(3, Navigation.step(size = 4, at = -1, by = -1))
        assertEquals(-1, Navigation.step(size = 0, at = -1, by = 1))
    }

    @Test fun `an item with no id or text is found again by where it is`() {
        val anonymous = Fake(children = listOf(Fake(clickable = true, top = 0, bottom = 50), Fake(clickable = true, top = 60, bottom = 110)))
        val items = Navigation.focusables(anonymous)
        assertEquals(1, Navigation.find(items, Navigation.Mark.of(items[1])))
        assertEquals(Navigation.step(items.size, 1, 1), Navigation.step(items.size, Navigation.find(items, Navigation.Mark.of(items[1])), 1))
    }

    @Test fun `in a grid of look-alikes focus moves on, cell by cell`() {
        val grid = Fake(children = (0 until 3).map { Fake(id = "thumb", clickable = true, left = it * 100, right = it * 100 + 90) })
        val items = Navigation.focusables(grid)
        assertEquals(1, Navigation.find(items, Navigation.Mark.of(items[1])))
        assertEquals(2, Navigation.find(items, Navigation.Mark.of(items[2])))
    }

    @Test fun `a pressed button whose text changed keeps the focus`() {
        val before = Fake(id = "play", text = "Play", clickable = true, top = 300, bottom = 350)
        val after = Fake(children = listOf(Fake(id = "prev", clickable = true), Fake(id = "play", text = "Pause", clickable = true, top = 300, bottom = 350)))
        assertEquals(1, Navigation.find(Navigation.focusables(after), Navigation.Mark.of(before)))
        // No id either: where it is still finds it.
        val bare = Fake(text = "Follow", clickable = true, top = 300, bottom = 350)
        val followed = Fake(children = listOf(Fake(text = "Following", clickable = true, top = 300, bottom = 350)))
        assertEquals(0, Navigation.find(Navigation.focusables(followed), Navigation.Mark.of(bare)))
    }

    @Test fun `focus finds its item again after the screen changes`() {
        val before = Navigation.focusables(screen)[2] // star1
        val after = Fake(children = listOf(Fake(id = "wifi", checkable = true), Fake(id = "star1", clickable = true, top = 400, bottom = 450)))
        assertEquals(1, Navigation.find(Navigation.focusables(after), Navigation.Mark.of(before)))
        assertEquals(-1, Navigation.find(Navigation.focusables(after), Navigation.Mark.of(Fake(id = "gone"))))
    }
}
