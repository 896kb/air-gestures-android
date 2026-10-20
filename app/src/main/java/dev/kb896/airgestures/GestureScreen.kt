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
        else -> stepLabel(id)
    }

    /** An action id in words: Open <app>, Drawn swipe, Sequence: <name>, Wait 2 s… */
    fun stepLabel(id: String): String = when {
        Sequences.waitMs(id) != null -> "Wait ${formatSeconds(Sequences.waitMs(id)!!)}"
        id.startsWith(Strokes.PATH) -> "Drawn swipe"
        id.startsWith(Sequences.PREFIX) ->
            Profiles.sequences(this).get(id.removePrefix(Sequences.PREFIX))?.let { "Sequence: ${it.name}" } ?: "Sequence (deleted)"
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
        val current = if (profile == null) prefs.callActionId(gesture) else prefs.actionId(gesture, profile)
        pickAction(gesture.label, current, calls = profile == null) { id ->
            store(gesture, profile, id)
            render()
        }
    }

    /**
     * The action picker: [calls] adds As usual, Nothing and the call actions; [inSequence] leaves
     * out what a sequence can't hold (another sequence, automation, nothing). Actions that need
     * more (an app, a spot, a swipe, a sequence) ask for it before [onChosen] gets the id.
     */
    fun pickAction(title: String, current: String, calls: Boolean = false, inSequence: Boolean = false, onChosen: (String) -> Unit) {
        val actions = Action.entries.filter { action ->
            when {
                inSequence -> action != Action.NONE && !action.callOnly && action != Action.SEQUENCE && action != Action.AUTOMATION
                calls -> action != Action.NONE
                else -> !action.callOnly
            }
        }
        val extra = if (calls) {
            listOf(
                Option("As usual", current == Mappings.AS_USUAL, R.drawable.ic_minus) to Mappings.AS_USUAL,
                Option("Nothing", current == Mappings.NOTHING, R.drawable.ic_minus) to Mappings.NOTHING,
            )
        } else {
            emptyList()
        }
        val selected = Action.of(current).takeUnless { calls && (current == Mappings.AS_USUAL || current == Mappings.NOTHING) }
        val options = extra.map { it.first } + actions.map { Option(it.label, it == selected && !inSequence, it.icon) }
        val heading = when {
            inSequence -> "Step"
            calls -> "During calls"
            else -> "Gesture"
        }
        choose(title, options, heading = heading) { index ->
            if (index < extra.size) return@choose onChosen(extra[index].second)
            when (val action = actions[index - extra.size]) {
                Action.OPEN_APP -> pickApp(exclude = emptySet(), current = current.removePrefix(APP_PREFIX)) { onChosen(APP_PREFIX + it) }
                Action.TAP -> pickScreen(SpotPicker.Mode.TAP, current) { onChosen(it.first()) }
                Action.SWIPE_HERE -> choose(
                    "Swipe here",
                    listOf(Option("Place it: start and end", false, R.drawable.ic_move_up_right), Option("Draw it", false, R.drawable.ic_scan_line)),
                    heading = heading,
                ) { how ->
                    val mode = if (how == 0) SpotPicker.Mode.SWIPE else SpotPicker.Mode.DRAW
                    pickScreen(mode, current) { onChosen(it.first()) }
                }
                Action.SEQUENCE -> pickSequence(current, onChosen)
                else -> onChosen(action.id)
            }
        }
    }

    /** A sequence from the library, or a new one (which opens to be filled in). */
    private fun pickSequence(current: String, onChosen: (String) -> Unit) {
        val library = Profiles.sequences(this).sequences
        val options = library.map { Option(it.name, Sequences.PREFIX + it.id == current, R.drawable.ic_list_ordered) } +
            Option("New sequence", false, R.drawable.ic_layers)
        choose("Sequence", options, heading = "Gesture") { index ->
            val sequence = library.getOrNull(index) ?: Profiles.updateSequences(applicationContext) { it.added().first }.sequences.last()
            onChosen(Sequences.PREFIX + sequence.id)
            if (index >= library.size) SequenceActivity.open(this, sequence.id)
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
     * Set up a screen action over the app it's for (a tap spot, a swipe, a drawing, a
     * recording): the app steps aside, the overlay asks the user to open the app, then this
     * screen comes back with the result.
     */
    fun pickScreen(mode: SpotPicker.Mode, current: String?, onDone: (List<String>) -> Unit) {
        val touch = TouchService.instance
        if (touch == null) {
            confirm(
                "Turn on screen gestures",
                "This uses the same accessibility service as the screen gestures. Turn it on in Setup first.",
                "Open Setup",
            ) { open<SetupActivity>() }
            return
        }
        val back = Intent(intent).setClass(applicationContext, javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        moveTaskToBack(true)
        touch.pick(mode, current) { ids ->
            if (!ids.isNullOrEmpty()) onDone(ids)
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

    /** 0.5 s, 2 s, 1.2 s. */
    fun formatSeconds(ms: Long) = if (ms % 1000 == 0L) "${ms / 1000} s" else String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)
}
