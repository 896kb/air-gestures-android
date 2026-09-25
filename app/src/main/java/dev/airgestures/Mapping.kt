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
    DIAL("dial.toggle", "Switch the dial", R.drawable.ic_rotate_cw);

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
    INDEX_DOUBLE("index_double", "Index double tap", Action.DIAL),
    MIDDLE_TAP("middle_tap", "Middle tap", Action.NONE),
    MIDDLE_DOUBLE("middle_double", "Middle double tap", Action.MUTE),
}
