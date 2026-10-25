package dev.kb896.airgestures

/** An "open this app" action: the prefix, then the app's package name. */
const val APP_PREFIX = "app:"

/** What a gesture can do, as the bridge names it (see Actions.kt). */
enum class Action(val id: String, val label: String, val icon: Int) {
    NONE("", "Nothing", R.drawable.ic_minus),

    PLAY_PAUSE("media.play_pause", "Play or pause", R.drawable.ic_play),
    NEXT("media.next", "Next track", R.drawable.ic_skip_forward),
    PREVIOUS("media.previous", "Previous track", R.drawable.ic_skip_back),
    VOLUME_UP("volume.up", "Volume up", R.drawable.ic_volume_2),
    VOLUME_DOWN("volume.down", "Volume down", R.drawable.ic_volume_1),
    MUTE("volume.mute", "Mute", R.drawable.ic_volume_x),
    BRIGHTNESS_UP("brightness.up", "Brightness up", R.drawable.ic_sun),
    BRIGHTNESS_DOWN("brightness.down", "Brightness down", R.drawable.ic_sun_dim),
    SCREEN_UP("screen.swipe_up", "Swipe up on screen", R.drawable.ic_arrow_up),
    SCREEN_DOWN("screen.swipe_down", "Swipe down on screen", R.drawable.ic_arrow_down),
    SCREEN_LEFT("screen.swipe_left", "Swipe left on screen", R.drawable.ic_arrow_left),
    SCREEN_RIGHT("screen.swipe_right", "Swipe right on screen", R.drawable.ic_arrow_right),
    BACK("screen.back", "Back", R.drawable.ic_undo_2),
    HOME("screen.home", "Home", R.drawable.ic_house),
    RECENTS("screen.recents", "Recent apps", R.drawable.ic_layers),
    FLASHLIGHT("torch.toggle", "Flashlight on or off", R.drawable.ic_flashlight),

    /** Stored as `tap:<x>,<y>`; the picker asks where. */
    TAP(Tap.PREFIX, "Tap here", R.drawable.ic_crosshair),
    /** Stored as `swipe:…` (two orbs) or `path:…` (drawn); the picker asks which. */
    SWIPE_HERE(Strokes.SWIPE, "Swipe here", R.drawable.ic_move_up_right),
    /** Stored as `seq:<id>`: a list of actions and waits (see [Sequences]). */
    SEQUENCE(Sequences.PREFIX, "Sequence", R.drawable.ic_list_ordered),
    ASSISTANT("assistant.voice", "Voice assistant", R.drawable.ic_mic),
    DISMISS("notification.dismiss", "Dismiss notification", R.drawable.ic_bell_off),
    READ("notification.read", "Read notification", R.drawable.ic_message_square_text),
    AUTOMATION(Mappings.AUTOMATION, "Send to automation", R.drawable.ic_zap),

    FOCUS_NEXT("nav.next", "Focus next", R.drawable.ic_circle_arrow_right),
    FOCUS_PREVIOUS("nav.previous", "Focus previous", R.drawable.ic_circle_arrow_left),
    SELECT("nav.select", "Select", R.drawable.ic_mouse_pointer_click),
    SCROLL_FORWARD("nav.scroll_forward", "Scroll forward", R.drawable.ic_chevrons_down),
    SCROLL_BACK("nav.scroll_back", "Scroll back", R.drawable.ic_chevrons_up),

    /** During calls only. */
    ANSWER("call.answer", "Answer call", R.drawable.ic_phone),
    /** During calls only: declines a ringing call, ends an active one. */
    END_CALL("call.end", "Decline or hang up", R.drawable.ic_phone_off),

    /** Stored as `app:<package>`; the picker asks which app. */
    OPEN_APP(APP_PREFIX, "Open an app", R.drawable.ic_app_open);

    /** Whether it belongs to the During calls picker only. */
    val callOnly get() = this == ANSWER || this == END_CALL

    companion object {
        fun of(id: String) = when {
            id.startsWith(APP_PREFIX) -> OPEN_APP
            id.startsWith(Tap.PREFIX) -> TAP
            id.startsWith(Strokes.SWIPE) || id.startsWith(Strokes.PATH) -> SWIPE_HERE
            id.startsWith(Sequences.PREFIX) -> SEQUENCE
            else -> entries.firstOrNull { it.id == id } ?: NONE
        }
    }
}

/** Which part of the hand makes a gesture: the profile screen's groups. */
enum class Part(val label: String) { THUMB("Thumb"), INDEX("Index"), MIDDLE("Middle") }

/**
 * The gestures band-core reads (its keys; its labels, which the lessons match lowercased),
 * with the defaults mirroring the desktop layout and what each does during a call by
 * default. [short]: the name within its [part]'s group. The triples and holds default to
 * nothing: the band controls sit on them (see [BandControls.standard]).
 */
enum class Gesture(
    val key: String,
    val label: String,
    val short: String,
    val part: Part,
    val default: Action = Action.NONE,
    val callDefault: String = Mappings.AS_USUAL,
) {
    SWIPE_UP("swipe_up", "Swipe up", "Swipe up", Part.THUMB, Action.PREVIOUS),
    SWIPE_DOWN("swipe_down", "Swipe down", "Swipe down", Part.THUMB, Action.NEXT),
    SWIPE_LEFT("swipe_left", "Swipe left", "Swipe left", Part.THUMB),
    SWIPE_RIGHT("swipe_right", "Swipe right", "Swipe right", Part.THUMB),
    INDEX_TAP("index_tap", "Index tap", "Tap", Part.INDEX, Action.PLAY_PAUSE),
    INDEX_DOUBLE("index_double", "Index double tap", "Double tap", Part.INDEX, Action.NONE, Action.ANSWER.id),
    INDEX_TRIPLE("index_triple", "Index triple tap", "Triple tap", Part.INDEX),
    INDEX_HOLD("index_hold", "Index hold", "Hold", Part.INDEX),
    MIDDLE_TAP("middle_tap", "Middle tap", "Tap", Part.MIDDLE),
    MIDDLE_DOUBLE("middle_double", "Middle double tap", "Double tap", Part.MIDDLE, Action.MUTE, Action.END_CALL.id),
    MIDDLE_TRIPLE("middle_triple", "Middle triple tap", "Triple tap", Part.MIDDLE),
    MIDDLE_HOLD("middle_hold", "Middle hold", "Hold", Part.MIDDLE),
    INDEX_DRAG_LEFT("index_drag_left", "Index drag left", "Drag left", Part.INDEX),
    INDEX_DRAG_RIGHT("index_drag_right", "Index drag right", "Drag right", Part.INDEX),
    INDEX_DRAG_TOWARD("index_drag_toward", "Index drag toward", "Drag toward", Part.INDEX),
    INDEX_DRAG_AWAY("index_drag_away", "Index drag away", "Drag away", Part.INDEX),
    INDEX_DRAG_UP("index_drag_up", "Index drag up", "Drag up", Part.INDEX),
    INDEX_DRAG_DOWN("index_drag_down", "Index drag down", "Drag down", Part.INDEX),
    MIDDLE_DRAG_LEFT("middle_drag_left", "Middle drag left", "Drag left", Part.MIDDLE),
    MIDDLE_DRAG_RIGHT("middle_drag_right", "Middle drag right", "Drag right", Part.MIDDLE),
    MIDDLE_DRAG_TOWARD("middle_drag_toward", "Middle drag toward", "Drag toward", Part.MIDDLE),
    MIDDLE_DRAG_AWAY("middle_drag_away", "Middle drag away", "Drag away", Part.MIDDLE),
    MIDDLE_DRAG_UP("middle_drag_up", "Middle drag up", "Drag up", Part.MIDDLE),
    MIDDLE_DRAG_DOWN("middle_drag_down", "Middle drag down", "Drag down", Part.MIDDLE);

    /** A pinch-and-move drag: on the Drags screen, not in the profile's groups. */
    val drag get() = "_drag_" in key

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }

        /** A finger's six drags, in band-core's order (left, right, toward, away, up, down). */
        fun drags(part: Part) = entries.filter { it.drag && it.part == part }
    }
}

/** What pinch and turn does: one action per step each way. */
enum class DialChoice(val key: String, val label: String, val up: String, val down: String, val icon: Int) {
    VOLUME("volume", "Volume", "volume.up", "volume.down", R.drawable.ic_volume_2),
    SEEK("seek", "Playback position", "dial.seek_forward", "dial.seek_back", R.drawable.ic_fast_forward),
    BRIGHTNESS("brightness", "Brightness", "brightness.up", "brightness.down", R.drawable.ic_sun),
    /** Each step moves the navigation focus (next / previous). */
    NAVIGATE("navigate", "Navigate", "nav.next", "nav.previous", R.drawable.ic_scan_line),
    /** For smart lights and the like: Tasker / Home Assistant get the steps, gathered every 250 ms. */
    AUTOMATION("automation", "Send to automation", "automation:dial_up", "automation:dial_down", R.drawable.ic_zap),
    NONE("none", "Nothing", "", "", R.drawable.ic_minus);

    companion object {
        /** "tracks" was the old next/previous choice; playback position replaced it. */
        fun of(key: String?) = if (key == "tracks") SEEK else entries.firstOrNull { it.key == key } ?: VOLUME
    }
}

/** The ready-made profiles New profile offers besides an empty one. */
object Presets {
    /** Moving through the screen: focus, select, scroll, back and home. */
    val navigate = mapOf(
        Gesture.SWIPE_RIGHT.key to Action.FOCUS_NEXT.id,
        Gesture.SWIPE_LEFT.key to Action.FOCUS_PREVIOUS.id,
        // As a finger on the screen: moving up scrolls forward.
        Gesture.SWIPE_UP.key to Action.SCROLL_FORWARD.id,
        Gesture.SWIPE_DOWN.key to Action.SCROLL_BACK.id,
        Gesture.INDEX_TAP.key to Action.SELECT.id,
        Gesture.INDEX_DOUBLE.key to Action.NONE.id,
        Gesture.MIDDLE_TAP.key to Action.HOME.id,
        Gesture.MIDDLE_DOUBLE.key to Action.BACK.id,
    )
}
