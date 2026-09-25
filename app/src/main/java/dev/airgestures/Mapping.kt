package dev.airgestures

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
    BACK("screen.back", "Back", R.drawable.ic_arrow_left),
    HOME("screen.home", "Home", R.drawable.ic_house),
    RECENTS("screen.recents", "Recent apps", R.drawable.ic_layers);

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * The mappable gestures (the middle hold stays the controls toggle), with the
 * defaults mirroring the desktop layout.
 */
enum class Gesture(val key: String, val label: String, val default: Action) {
    SWIPE_UP("swipe_up", "Swipe up", Action.PREVIOUS),
    SWIPE_DOWN("swipe_down", "Swipe down", Action.NEXT),
    SWIPE_LEFT("swipe_left", "Swipe left", Action.NONE),
    SWIPE_RIGHT("swipe_right", "Swipe right", Action.NONE),
    INDEX_TAP("index_tap", "Index tap", Action.PLAY_PAUSE),
    INDEX_DOUBLE("index_double", "Index double tap", Action.NONE),
    MIDDLE_TAP("middle_tap", "Middle tap", Action.NONE),
    MIDDLE_DOUBLE("middle_double", "Middle double tap", Action.MUTE),
}

/** What pinch and turn does: one action per step each way. */
enum class DialChoice(val key: String, val label: String, val up: String, val down: String, val icon: Int) {
    VOLUME("volume", "Volume", "volume.up", "volume.down", R.drawable.ic_volume_2),
    TRACKS("tracks", "Next or previous track", "dial.track_next", "dial.track_previous", R.drawable.ic_skip_forward),
    BRIGHTNESS("brightness", "Brightness", "brightness.up", "brightness.down", R.drawable.ic_sun),
    NONE("none", "Nothing", "", "", R.drawable.ic_minus);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: VOLUME
    }
}
