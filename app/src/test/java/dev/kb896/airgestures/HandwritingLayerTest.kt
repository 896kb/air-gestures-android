package dev.kb896.airgestures

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingLayerTest {
    private fun keys(json: JSONObject, name: String) = json.getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }

    @Test fun `the defaults take only the writing gestures with the pen down and only the double tap with it up`() {
        val layer = Gesture.entries.associate { it.key to HandwritingLayer.default(it) }
        val json = JSONObject(HandwritingLayer.json(layer, false))
        assertEquals(setOf("swipe_left", "swipe_right", "swipe_up", "swipe_down", "index_tap", "index_double"), keys(json, "takes"))
        assertEquals(setOf("index_double"), keys(json, "pen"))
        assertEquals(false, json.getBoolean("dials"))
        assertEquals(WriteAction.CHOICES_POPUP.id, HandwritingLayer.default(Gesture.SWIPE_UP))
    }

    @Test fun `as usual is left to the profile, and on-off works with the pen up`() {
        val layer = mapOf("swipe_left" to HandwritingLayer.AS_USUAL, "middle_tap" to WriteAction.TOGGLE.id, "index_tap" to HandwritingLayer.NOTHING)
        val json = JSONObject(HandwritingLayer.json(layer, true))
        assertEquals(setOf("middle_tap", "index_tap"), keys(json, "takes"))
        assertEquals(setOf("middle_tap"), keys(json, "pen"))
    }
}
