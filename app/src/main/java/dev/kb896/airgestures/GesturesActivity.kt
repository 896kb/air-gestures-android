package dev.kb896.airgestures

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/** The gesture map: what each gesture does, the pinch and turn, the fixed middle hold. */
class GesturesActivity : Screen() {
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rows = column(gap = 4f)
        show(pageColumn().apply { addView(card(null, rows)) }, title = "Gestures")
    }

    override fun onResume() {
        super.onResume()
        // An app picked for a gesture may have been uninstalled meanwhile.
        render()
    }

    /** One row per gesture: tap to choose its action. The pinch and the middle hold are fixed. */
    private fun render() {
        rows.removeAllViews()
        val prefs = Prefs(this)
        for (gesture in Gesture.entries) {
            val action = prefs.action(gesture)
            val label = if (action == Action.OPEN_APP) "Open ${appName(prefs.actionId(gesture).removePrefix(APP_PREFIX))}" else action.label
            rows.addView(gestureRow(action.icon, gesture.label, label, action == Action.NONE) { pick(gesture) })
        }
        val dial = prefs.dial
        rows.addView(gestureRow(dial.icon, "Pinch and turn", dial.label, dial == DialChoice.NONE) { pickDial() })
        rows.addView(gestureRow(R.drawable.ic_power, "Middle hold", "Controls on or off", false, null))
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

    private fun pick(gesture: Gesture) {
        val prefs = Prefs(this)
        choose(gesture.label, Action.entries.map { Option(it.label, it == prefs.action(gesture), it.icon) }) { index ->
            val action = Action.entries[index]
            if (action == Action.OPEN_APP) pickApp(gesture) else setAction(gesture, action.id)
            render()
        }
    }

    private fun setAction(gesture: Gesture, id: String) =
        BandService.instance?.setAction(gesture, id) ?: Prefs(this).setAction(gesture, id)

    /** The apps with a launcher icon, by name, each with its own icon. */
    private fun pickApp(gesture: Gesture) {
        val current = Prefs(this).actionId(gesture).removePrefix(APP_PREFIX)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .map { it.activityInfo.packageName to it }
            .sortedBy { (_, info) -> info.loadLabel(packageManager).toString().lowercase() }
        choose("Open an app", apps.map { (pkg, info) ->
            Option(info.loadLabel(packageManager).toString(), pkg == current, image = info.loadIcon(packageManager))
        }) { index ->
            setAction(gesture, APP_PREFIX + apps[index].first)
            render()
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
