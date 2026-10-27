package dev.kb896.airgestures

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What a gesture does while handwriting has the pen down (the Handwriting layer's choices). */
enum class WriteAction(val id: String, val label: String, val icon: Int) {
    PEN_DOWN("write.pen_down", "Pen down", R.drawable.ic_pencil),
    /** The pen up (its id from when it was called Stop writing). */
    STOP("write.stop", "Pen up", R.drawable.ic_hand),
    PEN_TOGGLE("write.pen_toggle", "Pen up/down", R.drawable.ic_pencil),
    TOGGLE("write.toggle", "Handwriting on/off", R.drawable.ic_power),
    CHOICES_POPUP("write.choices.popup", "Word choices (popup)", R.drawable.ic_layers),
    CHOICES_INLINE("write.choices.inline", "Word choices (in text)", R.drawable.ic_layers),
    MENU_POPUP("write.menu.popup", "Edit menu (popup)", R.drawable.ic_list_ordered),
    MENU_INLINE("write.menu.inline", "Edit menu (in text)", R.drawable.ic_list_ordered),
    LEFT("write.left", "Cursor left", R.drawable.ic_arrow_left),
    RIGHT("write.right", "Cursor right", R.drawable.ic_arrow_right),
    HOME("write.home", "Cursor home", R.drawable.ic_skip_back),
    END("write.end", "Cursor end", R.drawable.ic_skip_forward),
    SELECT("write.select", "Select", R.drawable.ic_mouse_pointer_click),
    DELETE_WORD("write.delete_word", "Del word", R.drawable.ic_undo_2),
    DELETE_ALL("write.delete_all", "Del all", R.drawable.ic_x);

    /** Whether it works with the pen up too (it's what brings the pen or handwriting back). */
    val withPenUp get() = this == PEN_DOWN || this == PEN_TOGGLE || this == TOGGLE

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
    private const val DOTS = "write.two_dots"
    private const val DOTS3 = "write.three_dots"

    /** Today's gestures in a field; every other gesture keeps its profile's action while writing. */
    val DEFAULTS = mapOf(
        Gesture.SWIPE_LEFT.key to WriteAction.LEFT.id,
        Gesture.SWIPE_RIGHT.key to WriteAction.RIGHT.id,
        Gesture.SWIPE_UP.key to WriteAction.CHOICES_POPUP.id,
        Gesture.SWIPE_DOWN.key to WriteAction.MENU_POPUP.id,
        Gesture.INDEX_TAP.key to WriteAction.SELECT.id,
        Gesture.INDEX_DOUBLE.key to WriteAction.PEN_DOWN.id,
    )

    fun default(gesture: Gesture) = DEFAULTS[gesture.key] ?: AS_USUAL

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

    /**
     * Two dots on the surface (".."): Pen down by default (with the pen up they put it down, with
     * it down they're two full stops), or Nothing, or another [WriteAction] (then never typed).
     */
    fun dots(context: Context) = prefs(context).getString(DOTS, null) ?: WriteAction.PEN_DOWN.id

    fun setDots(context: Context, value: String) {
        prefs(context).edit().putString(DOTS, value).apply()
        changed()
    }

    /** Three dots ("..."): [AS_USUAL] by default (not a gesture: as two dots and a full stop), or Nothing, or a [WriteAction]. */
    fun dots3(context: Context) = prefs(context).getString(DOTS3, null) ?: AS_USUAL

    fun setDots3(context: Context, value: String) {
        prefs(context).edit().putString(DOTS3, value).apply()
        changed()
    }

    fun start(context: Context) = WriteStart.of(prefs(context).getString(START, null))

    fun setStart(context: Context, start: WriteStart) = prefs(context).edit().putString(START, start.key).apply()

    /** The gestures with [action] in the layer. */
    fun keysFor(context: Context, action: WriteAction) = load(context).filterValues { it == action.id }.keys

    private fun changed() = BandService.instance?.let { it.writingLayerChanged(); it.remap() }

    fun json(context: Context) = json(load(context), dials(context), dots(context), dots3(context))

    /**
     * What the bridge needs (rust/bridge/src/lib.rs `set_writing_layer`): the gestures the field
     * takes with the pen down (all but As usual), the ones it takes with the pen up (what puts
     * it down or turns handwriting off), the dials, and whether two dots are a gesture of their
     * own (anything but Pen down).
     */
    fun json(layer: Map<String, String>, dials: Boolean, dots: String = WriteAction.PEN_DOWN.id, dots3: String = AS_USUAL): String = JSONObject()
        .put("takes", JSONArray(layer.filterValues { it != AS_USUAL }.keys.sorted()))
        .put("pen", JSONArray(layer.filterValues { WriteAction.of(it)?.withPenUp == true }.keys.sorted()))
        .put("dials", dials)
        .put("dots", dots != WriteAction.PEN_DOWN.id)
        .put("dots3", dots3 != AS_USUAL)
        .toString()
}
