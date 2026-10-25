package dev.kb896.airgestures

import android.content.Context
import android.content.Intent

/** Actions in words, for screens and services alike (the draw pad names what a drawing ran). */
object Labels {
    /** An action id in words: Open <app>, Drawn swipe, Sequence: <name>, Wait 2 s… */
    fun action(context: Context, id: String): String = when {
        Sequences.waitMs(id) != null -> "Wait ${seconds(Sequences.waitMs(id)!!)}"
        id.startsWith(Strokes.PATH) -> "Drawn swipe"
        id.startsWith(Sequences.PREFIX) ->
            Profiles.sequences(context).get(id.removePrefix(Sequences.PREFIX))?.let { "Sequence: ${it.name}" } ?: "Sequence (deleted)"
        else -> when (val action = Action.of(id)) {
            Action.OPEN_APP -> "Open ${appName(context, id.removePrefix(APP_PREFIX))}"
            else -> action.label
        }
    }

    /** An app's name, or its package if it's gone. */
    fun appName(context: Context, pkg: String) = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 0.5 s, 2 s, 1.2 s. */
    fun seconds(ms: Long) = if (ms % 1000 == 0L) "${ms / 1000} s" else String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)
}

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

    /** An action id in words (see [Labels.action]). */
    fun stepLabel(id: String): String = Labels.action(this, id)

    /**
     * A gesture's row: its icon and name, then on the right its [action] (a coral Paused chip
     * before it when a band control holds it back), each tag under it (a palm override, "palm up ·
     * Next track"; `true`: that one is paused), a [hint] ("when you let go"), the (i), the coral
     * [count] of paused actions behind it (a row opening another screen) and the chevron.
     */
    fun gestureRow(
        iconRes: Int,
        gesture: String,
        action: String,
        unset: Boolean,
        paused: Boolean = false,
        tags: List<Pair<String, Boolean>> = emptyList(),
        hint: String? = null,
        info: (() -> Unit)? = null,
        count: Int = 0,
        onClick: () -> Unit,
    ) =
        row(gap = 12f).apply {
            setPadding(0, dpi(8f), 0, dpi(8f))
            addView(icon(iconRes, 16f, if (unset) Cb.INK_300 else Cb.INK_900))
            addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = gesture }, weighted())
            addView(column(gap = 3f).apply {
                gravity = android.view.Gravity.END
                // Above the action, not beside it: beside, a long action squeezes the gesture's name onto two lines.
                // Each at its own width, at the end (a vertical layout's default is the whole width).
                val end = { android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = android.view.Gravity.END } }
                if (paused) addView(compactChip(pausedChip()), end())
                addView(Cb.text(context, 13f, color = if (unset) Cb.INK_400 else Cb.INK_500).apply { text = action }, end())
                for ((tag, tagPaused) in tags) {
                    addView(row(gap = 6f).apply {
                        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                        if (tagPaused) addView(compactChip(pausedChip()))
                        addView(Cb.text(context, 12f, color = Cb.INK_500).apply { text = tag })
                    }, end())
                }
                if (hint != null) addView(Cb.text(context, 12f, color = Cb.INK_400).apply { text = hint }, end())
            })
            // (i): how this action is used (Send to automation's guide).
            if (info != null) {
                addView(android.widget.FrameLayout(context).apply {
                    addView(icon(R.drawable.ic_info, 16f, Cb.INK_500), android.widget.FrameLayout.LayoutParams(dpi(16f), dpi(16f), android.view.Gravity.CENTER))
                    layoutParams = android.widget.LinearLayout.LayoutParams(dpi(30f), dpi(30f))
                    contentDescription = "How to use it"
                    isClickable = true
                    background = ripple(999f)
                    setOnClickListener { info() }
                })
            }
            if (count > 0) addView(Cb.count(context, count))
            addView(icon(R.drawable.ic_chevron_right, 14f, Cb.INK_400))
            isClickable = true
            background = ripple()
            setOnClickListener { onClick() }
        }

    /** A status chip sized for a row's right side (9 sp, tighter padding). */
    fun compactChip(chip: android.widget.TextView) = chip.apply {
        textSize = 9f
        letterSpacing = .06f
        setPadding(dpi(7f), dpi(3f), dpi(7f), dpi(3f))
    }

    /**
     * A profile's gestures in groups (mockup 9): Thumb (the swipes), Index and Middle (tap, double
     * and triple tap, hold); the drags are on their own screen. Paused chips and palm tags from [report].
     */
    fun profileRows(profileId: String, report: Report) = column(gap = 14f).apply {
        val list = Profiles.load(context)
        val index = list.profiles.indexOfFirst { it.id == profileId }
        val profile = list.profiles.getOrNull(index) ?: return@apply
        for (part in Part.entries) {
            addView(Cb.label(context, part.label))
            addView(column(gap = 4f).apply {
                for (gesture in Gesture.entries.filter { it.part == part && !it.drag }) addView(profileRow(profile, index, gesture, report))
            })
        }
    }

    /**
     * One gesture of [profile] (the [index]-th): its normal action (Paused when a band control
     * holds it), each palm override as a tag, and for a hold "when you let go" where it waits.
     */
    fun profileRow(profile: Profile, index: Int, gesture: Gesture, report: Report): android.view.View {
        val id = profile.map[gesture.key] ?: gesture.default.id
        val action = Action.of(id)
        val tags = Palm.entries.mapNotNull { palm ->
            profile.palm[gesture.key]?.get(palm.key)?.let { "${palm.label} · ${label(it, calls = false)}" to (report.paused(index, gesture, palm) != null) }
        }
        val hold = gesture == Gesture.INDEX_HOLD || gesture == Gesture.MIDDLE_HOLD
        val waits = if (hold) report.holdWaits(index, gesture.part) else emptySet()
        val letGo = when {
            waits.isEmpty() -> null
            waits.size == Palm.entries.size -> "when you let go"
            else -> "when you let go · " + Palm.entries.filter { it in waits }.joinToString(" or ") { it.label.lowercase() }
        }
        val paused = report.paused(index, gesture, null)
        val hint = listOfNotNull(pausedWhere(paused), letGo).joinToString(" · ").ifEmpty { null }
        val guide = if (action == Action.AUTOMATION) ({ automationGuide() }) else null
        return gestureRow(action.icon, gesture.short, label(id, calls = false), action == Action.NONE,
            paused = paused != null, tags = tags, hint = hint, info = guide) {
            GestureActivity.open(this, profile.id, gesture)
        }
    }

    /**
     * Under a Paused chip, when it's paused only in some positions (a band control limited to the
     * palm up): "palm up only", "palm down, sideways only". Null when it's paused in all three.
     */
    fun pausedWhere(paused: Report.Paused?): String? = paused?.positions?.takeIf { it.size < Palm.entries.size }
        ?.let { p -> Palm.entries.filter { it in p }.joinToString(", ") { it.label.lowercase() } + " only" }

    /**
     * The During calls rows, grouped as a profile's: every gesture but the drags (as usual in a
     * call), As usual unless set; one a band control holds back is Paused ([report]).
     */
    fun callRows(report: Report) = column(gap = 14f).apply {
        val prefs = Prefs(context)
        for (part in Part.entries) {
            addView(Cb.label(context, part.label))
            addView(column(gap = 4f).apply {
                for (gesture in Gesture.entries.filter { it.part == part && !it.drag }) {
                    val id = prefs.callActionId(gesture)
                    val usual = id == Mappings.AS_USUAL || id == Mappings.NOTHING
                    val icon = if (usual) R.drawable.ic_minus else Action.of(id).icon
                    val paused = report.paused(null, gesture, null)
                    addView(gestureRow(icon, gesture.short, label(id, calls = true), usual, paused = paused != null, hint = pausedWhere(paused)) {
                        pick(gesture, null)
                    })
                }
            })
        }
    }

    /** "Middle hold is Controls on/off; …": the gestures that are band controls now. */
    fun bandNote(band: BandControls): String? {
        val on = Builtin.entries.mapNotNull { b -> band[b]?.let { "${it.label} is ${b.label}" } }
        if (on.isEmpty()) return null
        return on.joinToString("; ", postfix = ".") + " Band controls work in every profile; what you set on those gestures here waits until they move."
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
                Action.AUTOMATION -> {
                    onChosen(action.id)
                    automationGuide(firstTime = true)
                }
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
    fun appName(pkg: String) = Labels.appName(this, pkg)

    /** The dial choices as the pickers list them: Nothing first (mockup 11). */
    val dialChoices get() = listOf(DialChoice.NONE) + DialChoice.entries.filter { it != DialChoice.NONE }

    /** What [part]'s pinch and turn does in [profile] (mockup 11: the middle dial says what it does to the hold). */
    fun pickDial(profile: String, part: Part) {
        val current = DialChoice.of(Profiles.load(this).profile(profile)?.dialOf(part))
        val note = if (part == Part.MIDDLE) {
            if (current != DialChoice.NONE) "Middle hold now acts when you let go." else "With a dial, the middle hold acts when you let go."
        } else null
        val title = if (part == Part.MIDDLE) "Middle dial" else "Index dial"
        val choices = dialChoices
        choose(title, choices.map { Option(it.label, it == current, it.icon) }, note = note) { index ->
            val choice = choices[index]
            if (part == Part.MIDDLE) BandService.setMiddleDial(applicationContext, profile, choice) else BandService.setDial(applicationContext, profile, choice)
            render()
            if (choice == DialChoice.AUTOMATION) automationGuide(firstTime = true)
        }
    }

    /** 0.5 s, 2 s, 1.2 s. */
    fun formatSeconds(ms: Long) = Labels.seconds(ms)

    /**
     * How Send to automation reaches Tasker, MacroDroid or Home Assistant: the broadcast (to
     * copy), what it carries, a recipe for each, a test, and the full guide. Shown once when
     * the action is first picked ([firstTime]), and from any row's (i).
     */
    fun automationGuide(firstTime: Boolean = false) {
        val prefs = getSharedPreferences("air-gestures", MODE_PRIVATE)
        if (firstTime && prefs.getBoolean(GUIDE_SEEN, false)) return
        prefs.edit().putBoolean(GUIDE_SEEN, true).apply()
        fun text(size: Float, value: String, color: Int = Cb.INK_700, font: Int = R.font.geist_regular) =
            Cb.text(this, size, font, color).apply {
                text = value
                setLineSpacing(0f, 1.3f)
            }
        val body = column(gap = 10f).apply {
            // Room under the last row, so its pills keep their shadow once scrolled to the end.
            setPadding(0, 0, 0, dpi(10f))
            addView(text(14f, "The gesture sends this to every app listening, and does nothing else itself:", Cb.INK_500))
            addView(row(gap = 8f).apply {
                setPadding(dpi(12f), dpi(8f), dpi(6f), dpi(8f))
                background = Cb.hairlineCard(context, 10f)
                // Shown breakable after its dots only (never mid-word); Copy copies it whole.
                addView(text(13f, Actions.AUTOMATION_ACTION.replace(".", ".\u200B"), Cb.INK_900, R.font.geist_mono_medium), weighted())
                addView(pill("Copy", R.drawable.ic_copy) {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Air Gestures broadcast", Actions.AUTOMATION_ACTION))
                    android.widget.Toast.makeText(this@GestureScreen, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }.first)
            })
            addView(text(14f, "With gesture = which one (swipe_left, middle_tap, … or dial for pinch and turn), and for the dial steps = how far it turned (+3, −2). An override also carries palm (down, sideways, up); the middle dial is middle_dial, a slider index_left_right and so on, and a drawing is drawing with its name.", Cb.INK_500))
            addView(Cb.label(context, "Tasker"))
            addView(text(14f, "New profile, Event, System, Intent Received. Action: paste the line above. In the task, %gesture and %steps hold the values."))
            addView(Cb.label(context, "MacroDroid"))
            addView(text(14f, "Trigger: Intent Received, the same action. Tick the extras gesture and steps to get them as variables."))
            addView(Cb.label(context, "Home Assistant"))
            addView(text(14f, "Needs the Home Assistant app 2026.8.4 or newer. In it: Settings, Companion app, Manage sensors, Last update trigger: enable it and turn on Add new intent, then check the new intent reads the line above (the switch saves the next broadcast it gets) and force-stop the app once. Automations then trigger on the event android.intent_received; the extras are in its data."))
            addView(row(gap = 10f).apply {
                addView(pill("Send a test") {
                    sendBroadcast(android.content.Intent(Actions.AUTOMATION_ACTION).putExtra("gesture", "test"))
                    android.widget.Toast.makeText(this@GestureScreen, "Sent gesture = test", android.widget.Toast.LENGTH_SHORT).show()
                }.first)
                addView(pill("Full guide") {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GUIDE_URL)))
                }.first)
            })
        }
        // Long on a small screen: the body scrolls inside the sheet.
        val scroll = object : android.widget.ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val most = (resources.displayMetrics.heightPixels * .66f).toInt()
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            // What the scroll's edge cuts fades out instead of ending in a hard line.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dpi(24f))
            addView(body)
        }
        sheet("Send to automation", scroll, "Close", cancel = false) { true }
    }

    companion object {
        private const val GUIDE_SEEN = "automation.guide_seen"

        /** The full guide on the site (with the release). */
        const val GUIDE_URL = "https://band.896.ro/automation/"
    }
}
