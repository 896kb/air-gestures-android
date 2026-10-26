package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityLineTest {
    private fun line(json: String) = ActivityLine.format(json, label = { ActivityLine.label(it) { id -> Action.of(id).label } }, profile = { listOf("Music", "Reading").getOrNull(it) })

    @Test fun `a gesture, its palm and what it did, in the app's words`() {
        assertEquals("index tap · palm up → Next track", line("""{"what":"index tap","palm":"up","calibrated":true,"run":"media.next"}"""))
        assertEquals("index drag away · palm up → nothing", line("""{"what":"index drag away","palm":"up","calibrated":true,"nothing":true}"""))
        assertEquals("swipe down · palm down (not calibrated) → Next track", line("""{"what":"swipe down","palm":"down","calibrated":false,"run":"media.next"}"""))
        assertEquals("index tap · palm down → would: Play or pause", line("""{"what":"index tap","palm":"down","calibrated":true,"practiced":"media.play_pause"}"""))
        assertEquals("swipe up · sideways → nothing (controls off)", line("""{"what":"swipe up","palm":"sideways","calibrated":true,"off":true}"""))
    }

    @Test fun `a pinch's steps are one line`() {
        assertEquals("index dial · palm down → Volume down ×6", line("""{"what":"index dial","palm":"down","calibrated":true,"steps":[["volume.down",6]]}"""))
        assertEquals("middle left/right slider · palm up → Brightness up ×2, Brightness down",
            line("""{"what":"middle left/right slider","palm":"up","calibrated":true,"steps":[["brightness.up",2],["brightness.down",1]]}"""))
        assertEquals("index dial · palm down → Seek forward ×3", line("""{"what":"index dial","palm":"down","calibrated":true,"steps":[["dial.seek_forward",3]]}"""))
        assertEquals("middle dial · palm down → Automation up ×2", line("""{"what":"middle dial","palm":"down","calibrated":true,"steps":[["automation:dial:middle_dial:up",2]]}"""))
    }

    @Test fun `band controls, paused actions and drawings`() {
        assertEquals("middle hold · palm down → Controls off", line("""{"what":"middle hold","palm":"down","calibrated":true,"band":"controls","result":"off"}"""))
        assertEquals("middle double tap · sideways → paused (Draw)", line("""{"what":"middle double tap","palm":"sideways","calibrated":true,"paused":"draw"}"""))
        assertEquals("index triple tap · palm down → Next profile: Reading", line("""{"what":"index triple tap","palm":"down","calibrated":true,"band":"next","profile":1}"""))
        assertEquals("index triple tap · palm down → Next profile: not here (this app has its own)",
            line("""{"what":"index triple tap","palm":"down","calibrated":true,"band":"next","held":"org.vlc"}"""))
        assertEquals("swipe down · palm down → Draw (pad open)", line("""{"what":"swipe down","palm":"down","calibrated":true,"band":"draw","result":"opened"}"""))
        assertEquals("Lightbulb (drawn) → Flashlight on or off", line("""{"drawn":"Lightbulb","run":"torch.toggle"}"""))
        assertEquals("drawing → not one of your drawings", line("""{"not_drawn":true}"""))
        assertEquals("index tap · palm up → Send to automation", line("""{"what":"index tap","palm":"up","calibrated":true,"run":"automation:index_tap:up"}"""))
        assertNull(line("not json"))
    }
}
