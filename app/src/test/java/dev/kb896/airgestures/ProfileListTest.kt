package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileListTest {
    private val map = mapOf("swipe_up" to "media.previous", "index_tap" to "media.play_pause")

    private fun three(): ProfileList {
        var list = ProfileList.migrated(map, "volume")
        list = list.added(map, "volume").first
        list = list.added(map, "seek").first
        return list
    }

    @Test fun `the old mapping becomes Profile 1`() {
        val list = ProfileList.migrated(map, "seek")
        assertEquals(listOf("Profile 1"), list.profiles.map { it.name })
        assertEquals(map, list.active(null).map)
        assertEquals("seek", list.active(null).dial)
    }

    @Test fun `a new profile's and a fresh install's middle dial is brightness, the old mapping's nothing`() {
        assertEquals("brightness", ProfileList.migrated(map, "volume").added(map, "volume").second.middleDial)
        assertEquals("brightness", ProfileList.fresh(map, "volume").active(null).middleDial)
        assertEquals("volume", ProfileList.fresh(map, "volume").active(null).dial)
        assertEquals("none", ProfileList.migrated(map, "volume").active(null).middleDial)
    }

    @Test fun `new profiles get the next free number`() {
        val list = three()
        assertEquals(listOf("p1", "p2", "p3"), list.profiles.map { it.id })
        assertEquals(listOf("Profile 1", "Profile 2", "Profile 3"), list.profiles.map { it.name })
        val (after, made) = list.deleted("p2").added(map, "volume")
        assertEquals("p2", made.id)
        assertEquals("Profile 2", made.name)
        assertEquals(listOf("p1", "p3", "p2"), after.profiles.map { it.id })
    }

    @Test fun `next and previous wrap round`() {
        val list = three()
        assertEquals("p2", list.step(+1).picked)
        assertEquals("p3", list.step(-1).picked)
        assertEquals("p1", list.step(+1).step(+1).step(+1).picked)
    }

    @Test fun `an app's own profile applies and locks while it's in front`() {
        val list = three().assigned("com.google.android.youtube", "p3")
        assertEquals("p3", list.active("com.google.android.youtube").id)
        assertTrue(list.locked("com.google.android.youtube"))
        assertEquals("p1", list.active("com.android.chrome").id)
        assertFalse(list.locked("com.android.chrome"))
        assertFalse(list.locked(null))
    }

    @Test fun `an app belongs to one profile`() {
        val list = three().assigned("app", "p2").assigned("app", "p3")
        assertEquals("p3", list.apps["app"])
        assertEquals(emptyMap<String, String>(), list.unassigned("app").apps)
    }

    @Test fun `the last profile can't be deleted, a deleted one takes its apps and the pick with it`() {
        assertEquals(1, ProfileList.migrated(map, "volume").deleted("p1").profiles.size)
        val list = three().step(+1).assigned("app", "p2").deleted("p2")
        assertEquals(listOf("p1", "p3"), list.profiles.map { it.id })
        assertEquals("p1", list.picked)
        assertEquals(emptyMap<String, String>(), list.apps)
    }

    @Test fun `renaming trims and ignores a blank name`() {
        val list = three().renamed("p2", "  Reading ").renamed("p3", "   ")
        assertEquals(listOf("Profile 1", "Reading", "Profile 3"), list.profiles.map { it.name })
    }

    @Test fun `a gesture or the dial changes in one profile only`() {
        val list = three().withAction("p2", "swipe_up", "volume.up").withDial("p3", "brightness")
        assertEquals("media.previous", list.profiles[0].map["swipe_up"])
        assertEquals("volume.up", list.profiles[1].map["swipe_up"])
        assertEquals("brightness", list.profiles[2].dial)
        assertEquals("volume", list.profiles[1].dial)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a stored list with no profiles is refused`() {
        ProfileList.fromJson("[]", "{}", "p1")
    }

    @Test fun `it round-trips through JSON`() {
        val list = three().renamed("p2", "Reading").assigned("app", "p2")
            .withMusicBluetoothOnly("p2", true).step(+1)
        val json = list.toJson()
        assertEquals(list, ProfileList.fromJson(json.first, json.second, list.picked))
    }

    @Test fun pickingMakesAProfileTheManualOne() {
        val (list, second) = ProfileList.migrated(mapOf(), "volume").added(mapOf(), "volume")
        val picked = list.picking(second.id)
        assertEquals(second.id, picked.picked)
        assertEquals(second, picked.active(null))
    }

    @Test fun pickingAnUnknownProfileChangesNothing() {
        val list = ProfileList.migrated(mapOf(), "volume")
        assertEquals(list, list.picking("nope"))
    }

    @Test fun `palm overrides, at most two, removing the last drops the gesture`() {
        var list = three()
        list = list.withOverride("p1", "index_tap", Palm.UP, "media.next").withOverride("p1", "index_tap", Palm.SIDE, "")
        assertEquals(mapOf("up" to "media.next", "sideways" to ""), list.profile("p1")!!.palm["index_tap"])
        assertEquals(list, list.withOverride("p1", "index_tap", Palm.DOWN, "x"))
        list = list.withoutOverride("p1", "index_tap", Palm.UP).withoutOverride("p1", "index_tap", Palm.SIDE)
        assertFalse("index_tap" in list.profile("p1")!!.palm)
    }

    @Test fun `the dev build's built-in actions leave every profile`() {
        val list = ProfileList(
            listOf(Profile("p1", "A", mapOf("middle_hold" to "controls.toggle", "swipe_left" to "profile.next", "index_tap" to "media.next"), "volume",
                palm = mapOf("index_tap" to mapOf("up" to "profile.previous")))),
            "p1", emptyMap(),
        ).withoutBuiltins()
        assertEquals(mapOf("index_tap" to "media.next"), list.profile("p1")!!.map)
        assertTrue(list.profile("p1")!!.palm.isEmpty())
        assertEquals(setOf("index_double"), ProfileList.builtinCalls(mapOf("index_double" to "controls.toggle", "middle_double" to "-")))
    }

    @Test fun `the new profile fields round-trip and 0_7_7's profiles read with their defaults`() {
        val made = Profile("p1", "A", mapOf("swipe_up" to "media.next"), "seek",
            palm = mapOf("index_tap" to mapOf("up" to "media.next")), middleDial = "brightness",
            middleDrags = Drags(sliders = true, targets = listOf("volume", "none", "none")), drawings = mapOf("Bulb" to "torch.toggle"))
        val list = ProfileList(listOf(made), "p1", emptyMap())
        val (profiles, apps) = list.toJson()
        assertEquals(list, ProfileList.fromJson(profiles, apps, "p1"))
        val old = ProfileList.fromJson("""[{"id":"p1","name":"A","map":{},"dial":"volume"}]""", "{}", "p1").profile("p1")!!
        assertEquals("none", old.middleDial)
        assertEquals(Drags(), old.indexDrags)
        assertFalse(old.musicBluetoothOnly)
        assertTrue(old.palm.isEmpty() && old.drawings.isEmpty())
    }

    @Test fun `a bad palm entry or a null is skipped, not the whole list`() {
        val list = ProfileList.fromJson(
            """[{"id":"p1","name":"A","map":{"swipe_up":null,"index_tap":"media.next"},"dial":"volume",
                "palm":{"index_tap":null,"swipe_up":3,"middle_tap":{"up":null,"down":"x"}},"drawings":{"Bulb":null,"Z":"y"}}]""",
            "{}", "p1",
        )
        val p = list.profile("p1")!!
        assertEquals(mapOf("index_tap" to "media.next"), p.map)
        assertEquals(mapOf("middle_tap" to mapOf("down" to "x")), p.palm)
        assertEquals(mapOf("Z" to "y"), p.drawings)
    }
}
