package dev.kb896.airgestures

import android.os.Bundle

/** The widget's profile picker: the profiles in the app's sheet, over the home screen. */
class ProfilePickerActivity : Screen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = Profiles.load(this)
        val active = list.active(Profiles.foreground)
        val locked = list.locked(Profiles.foreground)
        val dialog = choose(
            "Profile",
            list.profiles.map { Option(it.name, it.id == active.id, R.drawable.ic_layers) },
            heading = if (locked) "The app in front has its own" else "Switch to",
        ) { index -> BandService.pickProfile(this, list.profiles[index].id) }
        dialog.setOnDismissListener { finish() }
    }
}
