package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappingsTest {
    private val normal = linkedMapOf(
        "swipe_up" to "media.previous",
        "index_double" to "",
        "middle_tap" to "automation",
        "middle_double" to "volume.mute",
    )
    private val calls = mapOf("index_double" to "call.answer", "middle_double" to "call.end", "swipe_up" to "", "middle_tap" to "-")

    private fun effective(inCall: Boolean) =
        Mappings.effective(normal, calls, "volume.up", "volume.down", "band", inCall)

    @Test fun `out of a call the normal mapping goes through, automation named after its gesture`() = assertEquals(
        "swipe_up=media.previous;index_double=;middle_tap=automation:middle_tap;middle_double=volume.mute;" +
            "dial_up=volume.up;dial_down=volume.down;hand=band",
        effective(inCall = false),
    )

    @Test fun `in a call the call layer is on top, as usual passes through, a dash is nothing`() = assertEquals(
        "swipe_up=media.previous;index_double=call.answer;middle_tap=;middle_double=call.end;" +
            "dial_up=volume.up;dial_down=volume.down;hand=band",
        effective(inCall = true),
    )

    @Test fun `automation inside the call layer is named after its gesture too`() = assertEquals(
        "swipe_up=automation:swipe_up",
        Mappings.effective(linkedMapOf("swipe_up" to "media.next"), mapOf("swipe_up" to "automation"), "", "", "band", true)
            .substringBefore(";dial_up"),
    )

    @Test fun `a tap spot round-trips with four decimals`() {
        assertEquals("tap:0.5000,0.8125", Tap.format(.5f, .8125f))
        assertEquals(.5f to .8125f, Tap.parse("tap:0.5000,0.8125"))
    }

    @Test fun `bad or off-screen tap spots are refused`() {
        assertNull(Tap.parse("tap:1.2,0.5"))
        assertNull(Tap.parse("tap:abc"))
        assertNull(Tap.parse("media.next"))
    }

    @Test fun `unlocked the whole notification is read, locked only app and title`() {
        assertEquals("WhatsApp. Ana: are you coming tonight?", Speech.sentence("WhatsApp", "Ana", "are you coming tonight?", locked = false))
        assertEquals("WhatsApp. Ana.", Speech.sentence("WhatsApp", "Ana", "are you coming tonight?", locked = true))
        assertEquals("Gmail. 3 new emails", Speech.sentence("Gmail", null, "3 new emails", locked = false))
        assertEquals("Gmail.", Speech.sentence("Gmail", " ", null, locked = true))
    }

    @Test fun `the newest notification skips ongoing, ours and empty summaries`() {
        val notes = listOf(
            Notes.Note("old", 1, clearable = true, ongoing = false, ours = false, emptySummary = false),
            Notes.Note("nav", 9, clearable = false, ongoing = true, ours = false, emptySummary = false),
            Notes.Note("ours", 8, clearable = true, ongoing = false, ours = true, emptySummary = false),
            Notes.Note("summary", 7, clearable = true, ongoing = false, ours = false, emptySummary = true),
            Notes.Note("chat", 5, clearable = true, ongoing = false, ours = false, emptySummary = false),
        )
        assertEquals("chat", Notes.newest(notes)?.key)
        assertNull(Notes.newest(notes.take(0)))
    }
}
