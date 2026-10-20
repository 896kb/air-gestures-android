package dev.kb896.airgestures

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/**
 * The gesture map: what each gesture does, the pinch and turn, the fixed middle hold,
 * and what the gestures do instead while the phone rings or a call is on.
 */
class GesturesActivity : Screen() {
    private lateinit var rows: LinearLayout
    private lateinit var callRows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rows = column(gap = 4f)
        callRows = column(gap = 4f)
        show(pageColumn().apply {
            addView(card(null, rows))
            addView(card("During calls", column(gap = 8f).apply {
                addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                    text = "While the phone rings or a call is on. Phone calls only; WhatsApp and other app calls can't be answered by another app."
                })
                addView(callRows)
            }))
        }, title = "Gestures")
    }

    override fun onResume() {
        super.onResume()
        // An app picked for a gesture may have been uninstalled meanwhile.
        render()
    }

    /** One row per gesture: tap to choose its action. The pinch and the middle hold are fixed. */
    private fun render() {
        val prefs = Prefs(this)
        rows.removeAllViews()
        for (gesture in Gesture.entries) {
            val id = prefs.actionId(gesture)
            val action = Action.of(id)
            rows.addView(gestureRow(action.icon, gesture.label, label(id, calls = false), action == Action.NONE) { pick(gesture, calls = false) })
        }
        val dial = prefs.dial
        rows.addView(gestureRow(dial.icon, "Pinch and turn", dial.label, dial == DialChoice.NONE) { pickDial() })
        rows.addView(gestureRow(R.drawable.ic_power, "Middle hold", "Controls on or off", false, null))

        callRows.removeAllViews()
        for (gesture in Gesture.entries) {
            val id = prefs.callActionId(gesture)
            val usual = id == Mappings.AS_USUAL || id == Mappings.NOTHING
            val icon = if (usual) R.drawable.ic_minus else Action.of(id).icon
            callRows.addView(gestureRow(icon, gesture.label, label(id, calls = true), usual) { pick(gesture, calls = true) })
        }
    }

    /** What a row says for an action id. In the normal layer "" is Nothing, in the call layer As usual. */
    private fun label(id: String, calls: Boolean) = when {
        calls && id == Mappings.AS_USUAL -> "As usual"
        calls && id == Mappings.NOTHING -> "Nothing"
        else -> when (val action = Action.of(id)) {
            Action.OPEN_APP -> "Open ${appName(id.removePrefix(APP_PREFIX))}"
            else -> action.label
        }
    }

    private fun gestureRow(iconRes: Int, gesture: String, action: String, unset: Boolean, onClick: (() -> Unit)?) =
        row(gap = 12f).apply {
            setPadding(0, dpi(8f), 0, dpi(8f))
            addView(icon(iconRes, 16f, if (unset) Cb.INK_300 else Cb.INK_900))
            addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = gesture }, weighted())
            addView(Cb.text(context, 13f, color = if (unset) Cb.INK_400 else Cb.INK_500).apply { text = action })
            addView(icon(if (onClick != null) R.drawable.ic_chevron_right else R.drawable.ic_lock, 14f, Cb.INK_400))
            if (onClick != null) {
                isClickable = true
                background = ripple()
                setOnClickListener { onClick() }
            }
        }

    /**
     * Choose a gesture's action. The During calls layer also offers As usual (the normal
     * action), Nothing, and the call actions; the normal layer never shows the call actions.
     */
    private fun pick(gesture: Gesture, calls: Boolean) {
        val prefs = Prefs(this)
        val current = if (calls) prefs.callActionId(gesture) else prefs.actionId(gesture)
        val actions = if (calls) Action.entries.filter { it != Action.NONE } else Action.entries.filterNot { it.callOnly }
        val extra = if (calls) {
            listOf(
                Option("As usual", current == Mappings.AS_USUAL, R.drawable.ic_minus) to Mappings.AS_USUAL,
                Option("Nothing", current == Mappings.NOTHING, R.drawable.ic_minus) to Mappings.NOTHING,
            )
        } else {
            emptyList()
        }
        val selected = Action.of(current).takeUnless { calls && (current == Mappings.AS_USUAL || current == Mappings.NOTHING) }
        val options = extra.map { it.first } + actions.map { Option(it.label, it == selected, it.icon) }
        choose(gesture.label, options, heading = if (calls) "During calls" else "Gesture") { index ->
            if (index < extra.size) {
                store(gesture, calls, extra[index].second)
            } else {
                when (val action = actions[index - extra.size]) {
                    Action.OPEN_APP -> pickApp(gesture, calls)
                    Action.TAP -> pickTap(gesture, calls, current)
                    else -> store(gesture, calls, action.id)
                }
            }
            render()
        }
    }

    private fun store(gesture: Gesture, calls: Boolean, id: String) {
        if (calls) BandService.setCallAction(applicationContext, gesture, id) else BandService.setAction(applicationContext, gesture, id)
    }

    /** The apps with a launcher icon, by name, each with its own icon. */
    private fun pickApp(gesture: Gesture, calls: Boolean) {
        val prefs = Prefs(this)
        val current = (if (calls) prefs.callActionId(gesture) else prefs.actionId(gesture)).removePrefix(APP_PREFIX)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .map { it.activityInfo.packageName to it }
            .sortedBy { (_, info) -> info.loadLabel(packageManager).toString().lowercase() }
        choose("Open an app", apps.map { (pkg, info) ->
            Option(info.loadLabel(packageManager).toString(), pkg == current, image = info.loadIcon(packageManager))
        }) { index ->
            store(gesture, calls, APP_PREFIX + apps[index].first)
            render()
        }
    }

    /**
     * Pick the spot over the app it's for: the app steps aside, the overlay asks the user
     * to open the app and drag the target, then this screen comes back.
     */
    private fun pickTap(gesture: Gesture, calls: Boolean, current: String) {
        val touch = TouchService.instance
        if (touch == null) {
            confirm(
                "Turn on screen gestures",
                "Tap here uses the same accessibility service as the screen gestures. Turn it on in Setup first.",
                "Open Setup",
            ) { open<SetupActivity>() }
            return
        }
        val context = applicationContext
        moveTaskToBack(true)
        touch.pickSpot(Tap.parse(current)) { spot ->
            if (spot != null) store(gesture, calls, Tap.format(spot.first, spot.second))
            // Back to this screen; the service may start it from the background.
            TouchService.instance?.startFromBackground(
                Intent(context, GesturesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    /** An app's name, or its package if it's gone. */
    private fun appName(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun pickDial() {
        val prefs = Prefs(this)
        choose("Pinch and turn", DialChoice.entries.map { Option(it.label, it == prefs.dial, it.icon) }) { index ->
            val dial = DialChoice.entries[index]
            BandService.instance?.setDial(dial) ?: run { prefs.dial = dial }
            render()
        }
    }
}
