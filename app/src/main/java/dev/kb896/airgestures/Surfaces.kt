package dev.kb896.airgestures

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/**
 * The band's state outside the app: two Quick Settings tiles and the home-screen widget.
 * They're refreshed when what they show changes (not on every status update).
 */
object Surfaces {
    @Volatile
    private var shown: String? = null

    /** Connected · 72 % / Searching / Off / Bluetooth off, as the tiles and the widget say it. */
    fun status(context: Context): String {
        val state = BandService.state.value
        return when {
            BandService.instance == null -> "Off"
            !Setup.bluetoothOn(context) -> "Bluetooth off"
            state.phase == Phase.CONNECTED -> listOfNotNull("Connected", state.battery?.let { "$it %" }).joinToString(" · ")
            else -> "Searching"
        }
    }

    private fun controls() = when {
        BandService.instance == null -> null
        BandService.state.value.paused -> "Controls off"
        else -> "Controls on"
    }

    fun refresh(context: Context) {
        // The stored profile too: the widget shows it while the connection is off, when the state has none.
        val now = listOf(status(context), controls(), Profiles.active(context).name, BandService.state.value.band, ready(context))
            .joinToString("|")
        if (now == shown) return
        shown = now
        for (tile in listOf(BandTile::class.java, ControlsTile::class.java)) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, tile)) }
        }
        BandWidget.update(context)
    }

    /** The app, as the launcher opens it (so it comes back as it was). */
    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** A command to the band service, started in the foreground (as from a notification). */
    fun service(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getForegroundService(
        context,
        code,
        Intent(context, BandService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** Ready to connect without a trip to the app first (and not while a claim holds the band). */
    fun ready(context: Context) =
        Setup.granted(context) && Setup.bluetoothOn(context) && Identity.present(context) && BandClaim.current == null
}

/** Opens the app from a tile (Android 14 wants a PendingIntent; 13 an Intent). */
private fun TileService.openApp() {
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(Surfaces.openApp(this))
    } else {
        @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }
}

/** Quick Settings: the band connection, on (kept connected) or off (released, e.g. for a laptop). */
class BandTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        when {
            BandService.instance != null -> BandService.stop(this)
            Surfaces.ready(this) -> runCatching { BandService.start(this) }.onFailure { openApp() }
            else -> openApp()
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.state = if (BandService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_band)
        tile.subtitle = Surfaces.status(this)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_power)
        tile.updateTile()
    }
}

/** Quick Settings: the controls, on or off (as the middle hold); unavailable while the band is off. */
class ControlsTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        val service = BandService.instance ?: return openApp()
        service.setPaused(!BandService.state.value.paused)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val running = BandService.instance != null
        val paused = BandService.state.value.paused
        tile.state = when {
            !running -> Tile.STATE_UNAVAILABLE
            paused -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.label = getString(R.string.tile_controls)
        tile.subtitle = if (!running) "Band off" else if (paused) "Off" else "On"
        tile.icon = Icon.createWithResource(this, if (paused) R.drawable.ic_hand_off else R.drawable.ic_hand)
        tile.updateTile()
    }
}

/** The 4×1 widget: band, status and battery, the profile, a power and a controls button. */
class BandWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, BandWidget::class.java))
            if (ids.isEmpty()) return
            runCatching { manager.updateAppWidget(ids, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val state = BandService.state.value
            val running = BandService.instance != null
            val ui = WidgetState.of(running, state.phase == Phase.CONNECTED, state.paused, state.battery)
            val views = RemoteViews(context.packageName, R.layout.widget_band)
            views.setTextViewText(R.id.widget_chip, ui.chip)
            views.setInt(R.id.widget_chip, "setBackgroundResource", chipBackground(ui.chipTone))
            views.setTextColor(R.id.widget_chip, label(ui.chipTone))
            // The profile in force, from the store: it shows while the connection is off too.
            views.setTextViewText(R.id.widget_profile, "${Profiles.active(context).name}  ›")
            views.setOnClickPendingIntent(R.id.widget_profile, picker(context))
            views.setOnClickPendingIntent(R.id.widget, Surfaces.openApp(context))
            views.setOnClickPendingIntent(
                R.id.widget_power,
                when {
                    running -> Surfaces.service(context, BandService.ACTION_STOP, 31)
                    Surfaces.ready(context) -> Surfaces.service(context, BandService.ACTION_START, 32)
                    else -> Surfaces.openApp(context)
                },
            )
            views.setInt(R.id.widget_power, "setBackgroundResource", button(ui.power))
            views.setInt(R.id.widget_power, "setColorFilter", label(ui.power))
            views.setOnClickPendingIntent(
                R.id.widget_controls,
                if (running) Surfaces.service(context, BandService.ACTION_TOGGLE_CONTROLS, 33) else Surfaces.openApp(context),
            )
            views.setImageViewResource(R.id.widget_controls, if (running && state.paused) R.drawable.ic_hand_off else R.drawable.ic_hand)
            views.setInt(R.id.widget_controls, "setBackgroundResource", button(ui.hand))
            views.setInt(R.id.widget_controls, "setColorFilter", label(ui.hand))
            return views
        }

        private fun chipBackground(tone: WidgetState.Tone) = when (tone) {
            WidgetState.Tone.LIVE -> R.drawable.widget_chip_live
            WidgetState.Tone.STOP -> R.drawable.widget_chip_stop
            WidgetState.Tone.GREY -> R.drawable.widget_chip_grey
        }

        private fun button(tone: WidgetState.Tone) =
            if (tone == WidgetState.Tone.LIVE) R.drawable.widget_button_live else R.drawable.widget_button_stop

        /** The dark label that goes on each signal colour (white on the grey chip). */
        private fun label(tone: WidgetState.Tone) = when (tone) {
            WidgetState.Tone.LIVE -> Cb.Signal.LIVE.label
            WidgetState.Tone.STOP -> Cb.Signal.STOP.label
            WidgetState.Tone.GREY -> 0xFFFFFFFF.toInt()
        }

        /** The profile picker, over the home screen. */
        private fun picker(context: Context) = PendingIntent.getActivity(
            context,
            34,
            Intent(context, ProfilePickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
