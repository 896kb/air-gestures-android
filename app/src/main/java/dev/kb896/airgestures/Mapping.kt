package dev.kb896.airgestures

/** An "open this app" action: the prefix, then the app's package name. */
const val APP_PREFIX = "app:"

/** What a gesture can do, as the bridge names it (see Actions.kt). */
enum class Action(val id: String, val label: String, val icon: Int) {
    NONE("", "Nothing", R.drawable.ic_minus),

    /** Built into the band layer (band-core): any gesture can have them. */
    CONTROLS("controls.toggle", "Controls on/off", R.drawable.ic_power),
    NEXT_PROFILE("profile.next", "Next profile", R.drawable.ic_layers),
    PREVIOUS_PROFILE("profile.previous", "Previous profile", R.drawable.ic_layers),

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

    /** Done by the band's layer itself (band-core), so only a gesture can run it. */
    val builtin get() = this == CONTROLS || this == NEXT_PROFILE || this == PREVIOUS_PROFILE

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

/**
 * The mappable gestures, with the defaults mirroring the desktop layout, and what each
 * does during a call by default.
 */
enum class Gesture(val key: String, val label: String, val default: Action, val callDefault: String = Mappings.AS_USUAL) {
    SWIPE_UP("swipe_up", "Swipe up", Action.PREVIOUS),
    SWIPE_DOWN("swipe_down", "Swipe down", Action.NEXT),
    SWIPE_LEFT("swipe_left", "Swipe left", Action.NONE),
    SWIPE_RIGHT("swipe_right", "Swipe right", Action.NONE),
    INDEX_TAP("index_tap", "Index tap", Action.PLAY_PAUSE),
    INDEX_DOUBLE("index_double", "Index double tap", Action.NONE, Action.ANSWER.id),
    MIDDLE_TAP("middle_tap", "Middle tap", Action.NONE),
    MIDDLE_DOUBLE("middle_double", "Middle double tap", Action.MUTE, Action.END_CALL.id),
    INDEX_TRIPLE("index_triple", "Index triple tap", Action.NEXT_PROFILE),
    MIDDLE_TRIPLE("middle_triple", "Middle triple tap", Action.PREVIOUS_PROFILE),
    MIDDLE_HOLD("middle_hold", "Middle hold", Action.CONTROLS),
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
