package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Test

class BandBondsTest {
    // Band A's pairings from three claims (a factory reset in between), band B's, and a speaker.
    private val bonds = listOf(
        "AA:00:00:00:00:01" to "Meta Band 1A2B",
        "AA:00:00:00:00:02" to "Meta Band 1A2B",
        "AA:00:00:00:00:03" to "Meta Band 1A2B",
        "BB:00:00:00:00:01" to "Meta Band 00DF",
        "CC:00:00:00:00:01" to "Speaker",
    )

    @Test fun `a claim removes every pairing of the band's name`() {
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02", "AA:00:00:00:00:03"), Identity.bandBonds(bonds, "Meta Band 1A2B", null, emptyList()))
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02", "AA:00:00:00:00:03"), Identity.bandBonds(bonds, "Meta Band 1A2B", null, listOf("bb:00:00:00:00:01")))
    }

    @Test fun `the other band's pairing stays, even under the same name`() {
        val same = bonds + ("DD:00:00:00:00:01" to "Meta Band 1A2B")
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02", "AA:00:00:00:00:03"), Identity.bandBonds(same, "Meta Band 1A2B", null, listOf("dd:00:00:00:00:01")))
        // Not even at its own address as this band's record says.
        assertEquals(emptyList<String>(), Identity.bandBonds(bonds, null, "BB:00:00:00:00:01", listOf("BB:00:00:00:00:01")))
    }

    @Test fun `forget takes the band's address and name`() {
        val renamed = bonds + ("AA:00:00:00:00:04" to null)
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02", "AA:00:00:00:00:03", "AA:00:00:00:00:04"), Identity.bandBonds(renamed, "Meta Band 1A2B", "aa:00:00:00:00:04", listOf("BB:00:00:00:00:01")))
        assertEquals(listOf("AA:00:00:00:00:04"), Identity.bandBonds(renamed, null, "AA:00:00:00:00:04", emptyList()))
    }

    @Test fun `with nothing known, every Meta band's, only with one band`() {
        assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02", "AA:00:00:00:00:03", "BB:00:00:00:00:01"), Identity.bandBonds(bonds, null, null, emptyList()))
        assertEquals(emptyList<String>(), Identity.bandBonds(bonds, null, null, listOf(null)))
    }
}
