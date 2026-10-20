package dev.kb896.airgestures

import android.content.Intent

/**
 * What the gesture screens share: the rows and the pickers for an action, an app, a tap
 * spot and the dial. A pick edits [profile]'s gestures, or the During calls layer when
 * `calls` is set (shared by every profile).
 */
abstract class GestureScreen : Screen() {
    /** Draw the screen again after a change. */
    abstract fun render()

    /** What a row says for an action id. In a profile "" is Nothing, in the call layer As usual. */
    fun label(id: String, calls: Boolean) = when {
        calls && id == Mappings.AS_USUAL -> "As usual"
        calls && id == Mappings.NOTHING -> "Nothing"
        else -> when (val action = Action.of(id)) {
            Action.OPEN_APP -> "Open ${appName(id.removePrefix(APP_PREFIX))}"
            else -> action.label
        }
    }

    fun gestureRow(iconRes: Int, gesture: String, action: String, unset: Boolean, onClick: (() -> Unit)?) =
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

    /** The rows of [profile]'s gestures, its dial and the fixed middle hold. */
    fun profileRows(profile: String) = column(gap = 4f).apply {
        val prefs = Prefs(context)
        for (gesture in Gesture.entries) {
            val id = prefs.actionId(gesture, profile)
            val action = Action.of(id)
            addView(gestureRow(action.icon, gesture.label, label(id, calls = false), action == Action.NONE) {
                pick(gesture, profile)
            })
        }
        val dial = prefs.dial(profile)
        addView(gestureRow(dial.icon, "Pinch and turn", dial.label, dial == DialChoice.NONE) { pickDial(profile) })
        addView(gestureRow(R.drawable.ic_power, "Middle hold", "Controls on or off", false, null))
    }

    /** The During calls rows: every gesture, As usual unless set. */
    fun callRows() = column(gap = 4f).apply {
        val prefs = Prefs(context)
        for (gesture in Gesture.entries) {
            val id = prefs.callActionId(gesture)
            val usual = id == Mappings.AS_USUAL || id == Mappings.NOTHING
            val icon = if (usual) R.drawable.ic_minus else Action.of(id).icon
            addView(gestureRow(icon, gesture.label, label(id, calls = true), usual) { pick(gesture, null) })
        }
    }

    /**
     * Choose a gesture's action in [profile], or in the During calls layer when it's null:
     * that layer also offers As usual, Nothing and the call actions; a profile never shows
     * the call actions.
     */
    fun pick(gesture: Gesture, profile: String?) {
        val prefs = Prefs(this)
        val calls = profile == null
        val current = if (profile == null) prefs.callActionId(gesture) else prefs.actionId(gesture, profile)
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
                store(gesture, profile, extra[index].second)
            } else {
                when (val action = actions[index - extra.size]) {
                    Action.OPEN_APP -> pickApp(exclude = emptySet(), current = current.removePrefix(APP_PREFIX)) { pkg ->
                        store(gesture, profile, APP_PREFIX + pkg)
                    }
                    Action.TAP -> pickTap(gesture, profile, current)
                    else -> store(gesture, profile, action.id)
                }
            }
            render()
        }
    }

    private fun store(gesture: Gesture, profile: String?, id: String) {
        if (profile == null) {
            BandService.setCallAction(applicationContext, gesture, id)
        } else {
            BandService.setAction(applicationContext, profile, gesture, id)
        }
    }

    /** The apps with a launcher icon, by name, each with its own icon (but not [exclude]). */
    fun pickApp(title: String = "Open an app", exclude: Set<String>, current: String? = null, onPicked: (String) -> Unit) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in exclude }
            .map { it.activityInfo.packageName to it }
            .sortedBy { (_, info) -> info.loadLabel(packageManager).toString().lowercase() }
        choose(title, apps.map { (pkg, info) ->
            Option(info.loadLabel(packageManager).toString(), pkg == current, image = info.loadIcon(packageManager))
        }, heading = if (title == "Open an app") "Gesture" else "Profile") { index ->
            onPicked(apps[index].first)
            render()
        }
    }

    /**
     * Pick the spot over the app it's for: the app steps aside, the overlay asks the user
     * to open the app and drag the target, then this screen comes back.
     */
    private fun pickTap(gesture: Gesture, profile: String?, current: String) {
        val touch = TouchService.instance
        if (touch == null) {
            confirm(
                "Turn on screen gestures",
                "Tap here uses the same accessibility service as the screen gestures. Turn it on in Setup first.",
                "Open Setup",
            ) { open<SetupActivity>() }
            return
        }
        val back = Intent(intent).setClass(applicationContext, javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        moveTaskToBack(true)
        touch.pickSpot(Tap.parse(current)) { spot ->
            if (spot != null) store(gesture, profile, Tap.format(spot.first, spot.second))
            // Back to this screen; the service may start it from the background.
            TouchService.instance?.startFromBackground(back)
        }
    }

    /** An app's name, or its package if it's gone. */
    fun appName(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun pickDial(profile: String) {
        val current = Prefs(this).dial(profile)
        choose("Pinch and turn", DialChoice.entries.map { Option(it.label, it == current, it.icon) }) { index ->
            BandService.setDial(applicationContext, profile, DialChoice.entries[index])
            render()
        }
    }
}
