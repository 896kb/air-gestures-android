package dev.kb896.airgestures

import android.os.Bundle

/** The widget's profile picker: the profiles in the app's sheet, over the home screen. */
class ProfilePickerActivity : Screen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Over the home screen, bars and all: no black status bar above the sheet.
        @Suppress("DEPRECATION")
        window.apply {
            setDecorFitsSystemWindows(false)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = 0
            navigationBarColor = 0
        }
        // The band the home screen shows: its profiles.
        val list = Bands.profiles(this, Bands.selected(this))
        val active = list.active(Profiles.foreground)
        val locked = list.locked(Profiles.foreground)
        val dialog = choose(
            "Profile",
            list.profiles.map { Option(it.name, it.id == active.id, R.drawable.ic_layers) },
            heading = if (locked) "The app in front has its own" else if (Bands.two(this)) "Switch the ${Bands.name(this, Bands.selected(this)).lowercase()} to" else "Switch to",
        ) { index -> BandService.pickProfile(this, list.profiles[index].id) }
        dialog.setOnDismissListener { finish() }
    }
}
