package dev.kb896.airgestures

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What a gesture does while handwriting has the pen down (the Handwriting layer's choices). */
enum class WriteAction(val id: String, val label: String, val icon: Int) {
    PEN_DOWN("write.pen_down", "Pen down", R.drawable.ic_pencil),
    STOP("write.stop", "Stop writing", R.drawable.ic_hand),
    TOGGLE("write.toggle", "Handwriting on/off", R.drawable.ic_power),
    CHOICES_POPUP("write.choices.popup", "Word choices (popup)", R.drawable.ic_layers),
    CHOICES_INLINE("write.choices.inline", "Word choices (in text)", R.drawable.ic_layers),
    MENU_POPUP("write.menu.popup", "Edit menu (popup)", R.drawable.ic_list_ordered),
    MENU_INLINE("write.menu.inline", "Edit menu (in text)", R.drawable.ic_list_ordered),
    LEFT("write.left", "Cursor left", R.drawable.ic_arrow_left),
    RIGHT("write.right", "Cursor right", R.drawable.ic_arrow_right),
    SELECT("write.select", "Select", R.drawable.ic_mouse_pointer_click),
    DELETE_WORD("write.delete_word", "Del word", R.drawable.ic_undo_2),
    DELETE_ALL("write.delete_all", "Del all", R.drawable.ic_x);

    /** Whether it works with the pen up too (it's what brings the pen or handwriting back). */
    val withPenUp get() = this == PEN_DOWN || this == TOGGLE

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** How handwriting starts in a text field. */
enum class WriteStart(val key: String, val label: String) {
    /** The pen down as the field gets the focus. */
    RIGHT_AWAY("now", "Right away"),
    /** The pen up until ".." is written (or Pen down): gestures stay as usual till then. */
    DOTS("dots", "After .."),
    /** Off until the Handwriting on/off gesture. */
    GESTURE("gesture", "On a gesture");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: RIGHT_AWAY
    }
}

/**
 * The Handwriting layer: what each gesture does while the band writes into a text field with
 * the pen down ([AS_USUAL]: its profile's action; [NOTHING]; or a [WriteAction]'s id), whether
 * the dials and sliders still step, and how handwriting starts in a field. Shared by both bands.
 */
object HandwritingLayer {
    const val AS_USUAL = ""
    const val NOTHING = "-"
    private const val PREFIX = "write."
    private const val DIALS = "write.dials"
    private const val START = "write.start"

    /** Today's gestures in a field; everything else does nothing while writing. */
    val DEFAULTS = mapOf(
        Gesture.SWIPE_LEFT.key to WriteAction.LEFT.id,
        Gesture.SWIPE_RIGHT.key to WriteAction.RIGHT.id,
        Gesture.SWIPE_UP.key to WriteAction.CHOICES_POPUP.id,
        Gesture.SWIPE_DOWN.key to WriteAction.MENU_POPUP.id,
        Gesture.INDEX_TAP.key to WriteAction.SELECT.id,
        Gesture.INDEX_DOUBLE.key to WriteAction.PEN_DOWN.id,
    )

    fun default(gesture: Gesture) = DEFAULTS[gesture.key] ?: NOTHING

    private fun prefs(context: Context) = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    /** Gesture key → its value in the layer. */
    fun load(context: Context): Map<String, String> =
        Gesture.entries.associate { it.key to (prefs(context).getString(PREFIX + it.key, null) ?: default(it)) }

    fun set(context: Context, gesture: Gesture, value: String) {
        prefs(context).edit().putString(PREFIX + gesture.key, value).apply()
        changed()
    }

    /** Dials and sliders step as usual while writing (else they do nothing). */
    fun dials(context: Context) = prefs(context).getBoolean(DIALS, false)

    fun setDials(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(DIALS, on).apply()
        changed()
    }

    fun start(context: Context) = WriteStart.of(prefs(context).getString(START, null))

    fun setStart(context: Context, start: WriteStart) = prefs(context).edit().putString(START, start.key).apply()

    /** The gestures with [action] in the layer. */
    fun keysFor(context: Context, action: WriteAction) = load(context).filterValues { it == action.id }.keys

    private fun changed() = BandService.instance?.let { it.writingLayerChanged(); it.remap() }

    fun json(context: Context) = json(load(context), dials(context))

    /**
     * What the bridge needs (rust/bridge/src/lib.rs `set_writing_layer`): the gestures the field
     * takes with the pen down (all but As usual), the ones it takes with the pen up (what puts
     * it down or turns handwriting off), and the dials.
     */
    fun json(layer: Map<String, String>, dials: Boolean): String = JSONObject()
        .put("takes", JSONArray(layer.filterValues { it != AS_USUAL }.keys.sorted()))
        .put("pen", JSONArray(layer.filterValues { WriteAction.of(it)?.withPenUp == true }.keys.sorted()))
        .put("dials", dials)
        .toString()
}
