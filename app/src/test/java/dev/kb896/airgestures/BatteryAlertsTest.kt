package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BatteryAlertsTest {
    /** Feed readings in order; returns the alerts posted. */
    private fun run(vararg readings: Pair<Int, Boolean>): List<BatteryAlerts.Alert> {
        var flags = BatteryAlerts.Flags()
        val posted = mutableListOf<BatteryAlerts.Alert>()
        for ((level, charging) in readings) {
            val (alert, next) = BatteryAlerts.check(level, charging, flags)
            alert?.let(posted::add)
            flags = next
        }
        return posted
    }

    private fun low(level: Int) = BatteryAlerts.Alert(BatteryAlerts.Kind.LOW, level)
    private val charged = BatteryAlerts.Alert(BatteryAlerts.Kind.CHARGED, 100)

    @Test fun `20 and 10 percent warn once each`() =
        assertEquals(listOf(low(20), low(10)), run(30 to false, 20 to false, 19 to false, 15 to false, 10 to false, 8 to false))

    @Test fun `a drop straight past 10 warns once`() = assertEquals(listOf(low(9)), run(25 to false, 9 to false, 7 to false))

    @Test fun `charging re-arms the low warnings`() = assertEquals(
        listOf(low(18), low(19)),
        run(18 to false, 40 to true, 60 to true, 19 to false),
    )

    @Test fun `charged once per charge`() = assertEquals(
        listOf(charged, charged),
        run(90 to true, 100 to true, 100 to true, 99 to false, 100 to true),
    )

    @Test fun `a brief plug-in below 50 doesn't re-arm`() =
        assertEquals(listOf(low(20)), run(20 to false, 22 to true, 19 to false))

    @Test fun `the evening reminder wants a recent reading below half, not charging`() {
        val now = 1_000_000_000L
        val hour = 3_600_000L
        assertTrue(Reminder.due(38, false, now - 2 * hour, now))
        assertFalse(Reminder.due(50, false, now - hour, now))
        assertFalse(Reminder.due(30, true, now - hour, now))
        assertFalse(Reminder.due(30, false, now - 30 * hour, now))
        assertFalse(Reminder.due(null, false, now, now))
    }

    @Test fun `the reminder is at the next 21 00`() {
        val zone = ZoneId.of("Europe/Bucharest")
        val afternoon = ZonedDateTime.of(2026, 10, 20, 15, 0, 0, 0, zone)
        val late = ZonedDateTime.of(2026, 10, 20, 22, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 10, 20, 21, 0, 0, 0, zone), Reminder.next(afternoon))
        assertEquals(ZonedDateTime.of(2026, 10, 21, 21, 0, 0, 0, zone), Reminder.next(late))
        assertNull(null)
    }
}
