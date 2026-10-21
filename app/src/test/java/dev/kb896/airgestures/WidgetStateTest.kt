package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStateTest {
    @Test fun offWhenTheServiceIsntRunning() {
        val s = WidgetState.of(running = false, connected = false, paused = false, battery = 80)
        assertEquals("OFF", s.chip)
        assertEquals(WidgetState.Tone.STOP, s.chipTone)
        assertEquals(WidgetState.Tone.STOP, s.power)
        assertEquals(WidgetState.Tone.STOP, s.hand)
    }

    @Test fun disconnectedIsGreyWithNoBattery() {
        val s = WidgetState.of(running = true, connected = false, paused = false, battery = 80)
        assertEquals("DISCONNECTED", s.chip)
        assertEquals(WidgetState.Tone.GREY, s.chipTone)
        assertEquals(WidgetState.Tone.LIVE, s.power)
        assertEquals(WidgetState.Tone.LIVE, s.hand)
    }

    @Test fun onShowsTheBatteryAfterADot() {
        val s = WidgetState.of(running = true, connected = true, paused = false, battery = 84)
        assertEquals("ON · 84 %", s.chip)
        assertEquals(WidgetState.Tone.LIVE, s.chipTone)
    }

    @Test fun onWithoutAReadingIsJustOn() {
        assertEquals("ON", WidgetState.of(running = true, connected = true, paused = false, battery = null).chip)
    }

    @Test fun pausedGesturesMakeTheHandCoral() {
        val s = WidgetState.of(running = true, connected = true, paused = true, battery = 50)
        assertEquals(WidgetState.Tone.LIVE, s.power)
        assertEquals(WidgetState.Tone.STOP, s.hand)
    }
}
