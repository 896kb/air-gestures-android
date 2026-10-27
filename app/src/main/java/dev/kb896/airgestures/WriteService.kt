package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Handwriting in text fields: while the setting is on and a text field has the focus, the band
 * goes into its handwriting state and what it writes is typed into that field (words picked
 * and corrected with the dictionary as the letters come: see rust/bridge/src/fieldtext.rs).
 *
 * The band goes back to gestures when the field loses the focus, another app comes up, the
 * screen goes off, nothing is written for [IDLE_MS], the band disconnects, or the setting or
 * this service is turned off. Within a field the pen lifts after 8 s without a confident letter
 * (so other hand movement types nothing); writing ".." or a double tap puts it down again, with
 * a buzz each way. Gestures edit instead of running their actions (see [gestures]): suggestions
 * for a word on a swipe up, the cursor and selections with swipes and taps. Password fields are
 * left alone. It reads and changes only the focused field's text. Only while the controls are on:
 * turning them off (from the band's middle hold too) goes back to gestures, and turning them on
 * with a text field focused writes into it again.
 */
class WriteService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    /** The field being written into, and its app; null when the band isn't writing for us. */
    private var field: AccessibilityNodeInfo? = null
    private var app: String? = null
    private var lastWritten = 0L

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = leave("the screen went off")
    }

    /** Unlocked: the controls that came on under the lock screen write into the field in focus now. */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (reenterUnlocked && !controlsOff) reenter()
        }
    }

    override fun onServiceConnected() {
        instance = this
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))
        Words.load(this)
        controlsOff = writerState().paused
        for ((slot, watch) in controls.withIndex()) BandService.stateOf(slot).listen(watch)
    }

    override fun onDestroy() {
        for ((slot, watch) in controls.withIndex()) BandService.stateOf(slot).remove(watch)
        leave("the service stopped")
        runCatching { unregisterReceiver(screenOff) }
        runCatching { unregisterReceiver(unlocked) }
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = leave("interrupted")

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                if (node.isEditable && !node.isPassword) enter(node, event.packageName?.toString())
                // Focus on another screen (a flip phone's cover screen) isn't the field's.
                else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && !elsewhere(event)) leave("the focus moved off the field")
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val node = field ?: return
                if (event.source == node) asLeft(node)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val from = event.packageName?.toString() ?: return
                val node = field
                // No field yet, and the keyboard came up: the app may have focused one without a focus event.
                if (node == null) {
                    if (FieldWriting.keyboard(from)) main.postDelayed(::catchFocused, KEYBOARD_SETTLE_MS)
                    // A field waiting for Handwriting on/off whose app went.
                    else if (waiting != null && !elsewhere(event) && FieldWriting.leaves(from, waitingApp, packageName, from == packageName && activity(event.className))
                        && Profiles.announced != waitingApp) clearWaiting()
                    return
                }
                if (elsewhere(event) || !FieldWriting.leaves(from, app, packageName, from == packageName && activity(event.className))) return
                // The field's app said it's in front (and says when it leaves): another app's window isn't it leaving.
                if (from != packageName && app != null && Profiles.announced == app) return
                val why = if (from == packageName) "a screen of this app came up" else "$from came up"
                // A panel or pop-up over the app (Samsung's edge panel comes up with the keyboard)
                // leaves the field focused: only once it isn't does writing end.
                if (stillFocused(node)) main.postDelayed({ if (field === node && !stillFocused(node)) leave(why) }, RECHECK_MS)
                else leave(why)
            }
        }
    }

    /** The field's screen, where it has one: a flip phone's cover screen is another. */
    private var fieldDisplay: Int? = null

    /** [event] happened on another screen than the field's. */
    private fun elsewhere(event: AccessibilityEvent) = fieldDisplay?.let { it != event.displayId } == true

    /** [node] is still the focused field, in a window still on screen. */
    private fun stillFocused(node: AccessibilityNodeInfo) = runCatching { node.refresh() && node.isFocused }.getOrDefault(false)

    /** The field with the input focus now, if the app focused one without saying so. */
    private fun catchFocused() {
        if (field != null) return
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }
            ?.let { enter(it, it.packageName?.toString()) }
    }

    /** Whether [name] is one of our screens (a lesson, say), not a pop-up or pad of ours over the field. */
    private fun activity(name: CharSequence?) =
        name != null && runCatching { android.app.Activity::class.java.isAssignableFrom(Class.forName(name.toString())) }.getOrDefault(false)

    /** The controls as last seen: off, then on again, writes into the field in focus. */
    private var controlsOff = false

    /** The band handwriting is on for ([Bands.writer]), else the one the home screen shows. */
    private fun writer() = Bands.writer(this) ?: Bands.selected(this)

    private fun writerState() = BandService.stateOf(writer()).value

    /** Each band's controls, heeded for the writing band only. */
    private val controls = List(Bands.MAX) { slot -> { state: BandService.State -> if (slot == writer()) controlsChanged(state) } }

    private fun controlsChanged(state: BandService.State) {
        if (state.paused != controlsOff) {
            controlsOff = state.paused
            main.post {
                if (controlsOff) {
                    reenterUnlocked = false
                    leave("the controls went off")
                } else {
                    reenter()
                }
            }
        }
    }

    /** The controls came on with the phone locked: [reenter] once it's unlocked. */
    private var reenterUnlocked = false

    /**
     * The controls came back on: write into the text field in focus, with the screen on and
     * unlocked only (a chat box under the lock screen isn't the band's); else once unlocked.
     */
    private fun reenter() {
        val awake = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != false
        reenterUnlocked = !awake || locked
        if (reenterUnlocked) return
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }
            ?.let { enter(it, it.packageName?.toString()) }
    }

    /** How handwriting starts in a field of [from]: as the app said when it announced itself, else as set; null: not at all. */
    private fun startFor(from: String?): WriteStart? {
        if (from != null && from == Profiles.announced) when (Profiles.announcedWriting) {
            "off" -> return null
            "on" -> return WriteStart.RIGHT_AWAY
            "wait" -> return WriteStart.DOTS
            "gesture" -> return WriteStart.GESTURE
        }
        return HandwritingLayer.start(this)
    }

    /** A focused field waiting for the Handwriting on/off gesture, and its app. */
    private var waiting: AccessibilityNodeInfo? = null
    private var waitingApp: String? = null

    private fun wait(node: AccessibilityNodeInfo, from: String?) {
        if (waiting == node) return
        waiting = node
        waitingApp = from
        fieldDisplay = runCatching { node.window?.displayId }.getOrNull()
        log("handwriting: waiting for Handwriting on/off in $from")
        // The gesture turns it on: it goes over every profile meanwhile.
        BandService.instance?.remap()
    }

    private fun clearWaiting() {
        if (waiting == null) return
        waiting = null
        waitingApp = null
        BandService.instance?.remap()
    }

    /** What goes over every profile while a field waits: Handwriting on/off's gestures turn it on. */
    fun toggleOverlay(): Map<String, String> =
        if (waiting == null) emptyMap() else HandwritingLayer.keysFor(this, WriteAction.TOGGLE).associateWith { WriteAction.TOGGLE.id }

    /** Handwriting on/off: off in the field being written into (it waits for the gesture again), or on in the one waiting. */
    fun toggle(): String? {
        val now = field
        // Listening with the pen up (After .., or dots that start it): on is the pen down.
        if (now != null && !pen) {
            BandService.instance?.fieldPenDown()
            return null
        }
        // Dots can bring it back: off is the pen up, the model still listening for them.
        if (now != null && HandwritingLayer.dotsStart(this)) {
            BandService.instance?.fieldPenUp()
            return null
        }
        if (now != null) {
            val from = app
            leave("turned off with a gesture")
            wait(now, from)
            return null
        }
        val node = waiting ?: return "no text field to write in"
        val from = waitingApp
        clearWaiting()
        enter(node, from, forced = true)
        return null
    }

    /**
     * A text field has the focus: write into it, if the setting is on, the controls are and the
     * band can, as its start says ([startFor]); [forced]: Handwriting on/off turned it on.
     */
    private fun enter(node: AccessibilityNodeInfo, from: String?, forced: Boolean = false) {
        if (FieldWriting.refusal(Bands.writer(this) != null, writerState()) != null) return
        val service = BandService.instance ?: return
        if (service.bandWrites(writer()) != true) return
        val start = if (forced) WriteStart.RIGHT_AWAY else startFor(from) ?: return
        // On a gesture, when dots can start it too: the handwriting model listens with the pen up (as After ..).
        val listening = start == WriteStart.DOTS || (start == WriteStart.GESTURE && HandwritingLayer.dotsStart(this))
        if (start == WriteStart.GESTURE && !listening && field?.let { it == node } != true) {
            if (field != null) leave("another field took the focus")
            return wait(node, from)
        }
        clearWaiting()
        val same = field?.let { it == node } == true
        // Moving to another field: the menu or word choices shown in the last one go first.
        if (!same) dropInline()
        field = node
        fieldDisplay = runCatching { node.window?.displayId }.getOrNull()
        app = from
        lastWritten = SystemClock.uptimeMillis()
        if (same) return
        restart(service, node)
        // Gestures edit the field meanwhile instead of running their actions.
        service.field = gestures
        pen = !listening
        if (!pen) service.fieldPenUp()
        log("handwriting: writing into a field in $from" + if (pen) "" else " (write .. to start)")
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** A field's text and selection ([start] = [end] for a cursor; [end] is the end that moves). */
    private data class Look(val text: String, val start: Int, val end: Int) {
        /** The same text and selection, whichever way round the selection's ends are given. */
        fun same(other: Look?) = other != null && text == other.text &&
            minOf(start, end) == minOf(other.start, other.end) && maxOf(start, end) == maxOf(other.start, other.end)
    }

    /** The field as we last left it, and when we last changed it: to tell others' changes from ours. */
    private var expected: Look? = null
    private var changedAt = 0L

    /** The field's text and selection now. */
    private fun looks(node: AccessibilityNodeInfo): Look {
        node.refresh()
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val end = node.textSelectionEnd.takeIf { it in 0..text.length } ?: text.length
        val start = node.textSelectionStart.takeIf { it in 0..text.length } ?: end
        return Look(text, start, end)
    }

    /**
     * Whether the field is as we left it, looked at on each change to it and before each edit
     * the band makes. If not, the keyboard typed, or a touch moved the cursor or selected: the
     * word under way ends and writing starts afresh at the cursor. A field that doesn't show
     * our own change yet, just after it, is looked at again once it has had [SETTLE_MS] to.
     */
    private fun asLeft(node: AccessibilityNodeInfo): Boolean {
        val now = looks(node)
        if (now.same(expected)) return true
        val since = SystemClock.uptimeMillis() - changedAt
        if (since < SETTLE_MS) {
            main.removeCallbacks(lookAgain)
            main.postDelayed(lookAgain, SETTLE_MS - since)
            return true
        }
        log("handwriting: the field changed outside the band, starting afresh at the cursor")
        anchor = null
        menu = null
        choosing = false
        popup.hide()
        if (inline.isNotEmpty()) setInline(node, "")
        BandService.instance?.let { restart(it, node) }
        return false
    }

    private val lookAgain = Runnable { field?.let(::asLeft) }

    /** A fresh start at the cursor: what's before it says whether a sentence begins. */
    private fun restart(service: BandService, node: AccessibilityNodeInfo, now: Look? = null) {
        val look = (now ?: looks(node)).also { expected = it }
        service.setFieldTyping(true, look.text.substring(0, look.end))
    }

    /** Take the menu or word choices out of the current field, and close the popup. */
    private fun dropInline() {
        menu = null
        choosing = false
        anchor = null
        field?.let { if (inline.isNotEmpty()) runCatching { setInline(it, "") } }
        inline = ""
        popup.hide()
    }

    /** A lesson or a teach screen takes the band's gestures: back to them (main thread). */
    fun lessonStarted() = leave("a lesson started")

    /** An app that announced itself in front ([ProfileReceiver]) said it left: writing into its field ends. */
    fun appLeft(pkg: String) {
        if (field != null && app == pkg) leave("$pkg said it left")
    }

    /** Back to gestures. */
    private fun leave(why: String) {
        // Word choices shown in the field go back to the word, and the last edits go in (the
        // bridge's, also when it ended the field itself as the controls went off).
        field?.let { node ->
            BandService.instance?.let { service ->
                service.fieldChooseCancel()
                val edits = service.fieldEdits()?.optJSONArray("edits")
                for (i in 0 until (edits?.length() ?: 0)) edits!!.getJSONObject(i).let { apply(node, it.optInt("delete"), it.optString("insert")) }
            }
        }
        dropInline()
        expected = null
        main.removeCallbacks(lookAgain)
        clearWaiting()
        if (field == null) return
        field = null
        app = null
        main.removeCallbacks(tick)
        BandService.instance?.let {
            it.setFieldTyping(false)
            if (it.field === gestures) it.field = null
        }
        log("handwriting: back to gestures ($why)")
    }

    /**
     * Gestures in a field, instead of their actions, as the Handwriting layer sets them
     * ([HandwritingLayer], [WriteAction]; by default):
     * - Word choices (swipe up): the word's suggestions, or after a space the likely next words
     *   (again turns to the last word's), in a popup or in the text; the cursor gestures turn
     *   through them, Select takes one, the edit menu's gesture cancels;
     * - otherwise Cursor left and right (swipes) move the cursor, and Select (a tap) starts
     *   selecting: then left and right grow the selection from where it was, until the next
     *   Select (writing replaces what's selected);
     * - Edit menu (swipe down): delete word, delete all, new line, and lists of punctuation
     *   and emoji, in a popup above the keyboard or in the text; the cursor gestures choose,
     *   Select does it, either menu's gesture closes (or goes back from a list);
     * - Del word and Del all at once; Pen down (a double tap), Pen up, Pen up/down, and
     *   Handwriting on/off (back to gestures in this field until it's made again).
     */
    private val gestures = object : BandService.Field {
        override fun gesture(label: String) = main.post { onGesture(label) }.let {}
        override fun ended() = main.post { leave("the controls went off") }.let {}
    }

    /** Choosing among a word's suggestions (a swipe up), and where a selection is anchored. */
    private var choosing = false
    private var anchor: Int? = null

    /** Where the word choices and the edit menu now open show: the app's popup, or in the field. */
    private var choicesPopup = true
    private var menuPopup = true

    /** A gesture the field took ([HandwritingLayer]): what it's set to do there. */
    private fun onGesture(label: String) {
        val node = field ?: return
        val service = BandService.instance ?: return
        lastWritten = SystemClock.uptimeMillis()
        val gesture = Gesture.entries.firstOrNull { it.label.lowercase() == label } ?: return
        // Nothing (or As usual, which the field doesn't take).
        val action = WriteAction.of(HandwritingLayer.load(this)[gesture.key]) ?: return
        act(service, node, action)
    }

    /**
     * Two (or [three]) dots on the surface, as a gesture of their own: with the pen up, all but
     * Nothing, Pen up, Pen up/down and on/off put it down first.
     */
    private fun onDots(three: Boolean = false) {
        val node = field ?: return
        val service = BandService.instance ?: return
        lastWritten = SystemClock.uptimeMillis()
        val action = WriteAction.of(if (three) HandwritingLayer.dots3(this) else HandwritingLayer.dots(this)) ?: return
        if (!pen && action != WriteAction.TOGGLE && action != WriteAction.STOP && action != WriteAction.PEN_TOGGLE) {
            service.fieldPenDown()
            if (action == WriteAction.PEN_DOWN) return
        }
        act(service, node, action)
    }

    /** Do [action] in the field (a gesture's, or two dots'). */
    private fun act(service: BandService, node: AccessibilityNodeInfo, action: WriteAction) {
        val open = menu
        when {
            action == WriteAction.PEN_DOWN -> service.fieldPenDown()
            action == WriteAction.STOP -> service.fieldPenUp()
            action == WriteAction.PEN_TOGGLE -> if (pen) service.fieldPenUp() else service.fieldPenDown()
            action == WriteAction.TOGGLE -> toggle()
            open != null -> onMenuAction(service, node, open, action)
            choosing -> when (action) {
                WriteAction.LEFT -> service.fieldChooseStep(false)
                WriteAction.RIGHT -> service.fieldChooseStep(true)
                WriteAction.SELECT -> {
                    service.fieldChoosePick()
                    choosing = false
                    popup.hide()
                }
                // Again on the next words turns to the last word's readings.
                WriteAction.CHOICES_POPUP, WriteAction.CHOICES_INLINE -> choosing = service.fieldChoose()
                else -> {
                    service.fieldChooseCancel()
                    choosing = false
                    popup.hide()
                    // A delete or a jump still happens, once the word is back as it was.
                    if (action in AFTER_CHOOSING) act(service, node, action)
                }
            }
            action == WriteAction.CHOICES_POPUP || action == WriteAction.CHOICES_INLINE -> {
                anchor = null
                choicesPopup = action == WriteAction.CHOICES_POPUP
                service.setFieldPopup(choicesPopup)
                choosing = service.fieldChoose()
                if (!choosing) buzz(false)
            }
            action == WriteAction.MENU_POPUP || action == WriteAction.MENU_INLINE -> {
                anchor = null
                menuPopup = action == WriteAction.MENU_POPUP
                menu = Menu(MAIN, 0)
                showMenu(node)
            }
            action == WriteAction.SELECT -> {
                anchor = if (anchor == null) cursor(node) else null
                log(if (anchor != null) "handwriting: selecting (left and right, tap to stop)" else "handwriting: selection kept")
            }
            action == WriteAction.LEFT -> moveCursor(service, node, -1)
            action == WriteAction.RIGHT -> moveCursor(service, node, 1)
            action == WriteAction.WORD_LEFT -> moveCursorTo(service, node) { FieldWriting.wordLeft(it, cursor(node)) }
            action == WriteAction.WORD_RIGHT -> moveCursorTo(service, node) { FieldWriting.wordRight(it, cursor(node)) }
            action == WriteAction.HOME -> moveCursorTo(service, node) { 0 }
            action == WriteAction.END -> moveCursorTo(service, node) { it.length }
            action == WriteAction.DELETE_WORD -> deleteWord(service, node)
            action == WriteAction.DELETE_ALL -> edit(service, node) { _, _ -> "" to "" }
        }
    }

    /** The word before the cursor (and the spaces after it) goes. */
    private fun deleteWord(service: BandService, node: AccessibilityNodeInfo) =
        edit(service, node) { before, after -> before.trimEnd().trimEnd { !it.isWhitespace() } to after }

    /** The edit menu (swipe down): which list, and which entry is picked. */
    private data class Menu(val items: List<String>, val pick: Int)
    private var menu: Menu? = null

    /** The menu as written into the field (word choices "In the field"); empty when it isn't. */
    private var inline = ""

    /** The menu in a popup, or in the field (the pick first, the rest in turn after it). */
    private fun showMenu(node: AccessibilityNodeInfo) {
        val open = menu
        if (open == null) {
            popup.hide()
            setInline(node, "")
        } else if (menuPopup) {
            popup.show(node, open.items, open.pick, MENU_HINT, aboveKeyboard = true)
        } else {
            // Set apart from the word before it by a space (one already there will do), which goes with it.
            val spaced = if (inline.isNotEmpty()) inline.startsWith(" ") else looks(node).let { now ->
                minOf(now.start, now.end).let { at -> at > 0 && !now.text[at - 1].isWhitespace() }
            }
            val list = open.items.indices.joinToString(" · ") { open.items[(open.pick + it) % open.items.size] }
            setInline(node, if (spaced) " $list" else list)
        }
    }

    /**
     * Put [text] where the in-field menu is (or at the cursor when none is); empty takes it out.
     * Found by its text, so it comes out right even if the field changed around it.
     */
    private fun setInline(node: AccessibilityNodeInfo, text: String) {
        if (inline == text) return
        val now = looks(node)
        val at = if (inline.isEmpty()) -1 else now.text.indexOf(inline)
        if (inline.isNotEmpty() && at < 0 && text.isEmpty()) return run { inline = "" }
        val start = if (at >= 0) at else minOf(now.start, now.end)
        val end = if (at >= 0) at + inline.length else start
        val changed = now.text.substring(0, start) + text + now.text.substring(end)
        val set = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, changed) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set)) return log("handwriting: the field didn't take the menu")
        val cursor = start + text.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        expected = Look(changed, cursor, cursor)
        changedAt = SystemClock.uptimeMillis()
        inline = text
    }

    /**
     * In the edit menu: the cursor gestures choose, Select does it, either menu's gesture goes
     * back from a list (or closes it), and the deletes close it and delete.
     */
    private fun onMenuAction(service: BandService, node: AccessibilityNodeInfo, open: Menu, action: WriteAction) {
        val n = open.items.size
        when (action) {
            WriteAction.LEFT -> menu = open.copy(pick = (open.pick + n - 1) % n)
            WriteAction.RIGHT -> menu = open.copy(pick = (open.pick + 1) % n)
            WriteAction.CHOICES_POPUP, WriteAction.CHOICES_INLINE, WriteAction.MENU_POPUP, WriteAction.MENU_INLINE ->
                menu = if (open.items === MAIN) null else Menu(MAIN, 0)
            WriteAction.DELETE_WORD, WriteAction.DELETE_ALL -> {
                menu = null
                setInline(node, "")
                if (action == WriteAction.DELETE_WORD) deleteWord(service, node) else edit(service, node) { _, _ -> "" to "" }
            }
            WriteAction.SELECT -> {
                val item = open.items[open.pick]
                menu = null
                setInline(node, "")
                when {
                    open.items === MAIN && item == PUNCTUATION_ITEM -> menu = Menu(PUNCTUATION, 0)
                    open.items === MAIN && item == EMOJI_ITEM -> menu = Menu(EMOJI, 0)
                    item == DELETE_WORD -> deleteWord(service, node)
                    item == DELETE_ALL -> edit(service, node) { _, _ -> "" to "" }
                    item == NEW_LINE -> edit(service, node) { before, after -> before + "\n" to after }
                    // A mark that ends a word or sentence goes straight after it, then a space.
                    item in CLOSING -> edit(service, node) { before, after ->
                        before.trimEnd(' ') + item + (if (after.startsWith(" ")) "" else " ") to after
                    }
                    else -> edit(service, node) { before, after -> before + item to after }
                }
            }
            else -> Unit
        }
        showMenu(node)
    }

    /**
     * Change the field around the cursor: [change] gets the text before and after it (a
     * selection goes) and gives them back; the cursor goes between. Writing then starts afresh.
     */
    private fun edit(service: BandService, node: AccessibilityNodeInfo, change: (String, String) -> Pair<String, String>) {
        node.refresh()
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val end = node.textSelectionEnd.takeIf { it in 0..text.length } ?: text.length
        val start = (node.textSelectionStart.takeIf { it in 0..text.length } ?: end).let { minOf(it, end) }
        val (before, after) = change(text.substring(0, start), text.substring(maxOf(start, end)))
        val set = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + after) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set)) return log("handwriting: the field didn't take the text")
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, before.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length)
        })
        anchor = null
        changedAt = SystemClock.uptimeMillis()
        restart(service, node, Look(before + after, before.length, before.length))
    }

    /** Where the cursor (the selection's moving end) is in [node]. */
    private fun cursor(node: AccessibilityNodeInfo): Int {
        node.refresh()
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        return node.textSelectionEnd.takeIf { it in 0..text.length } ?: text.length
    }

    /** The cursor a character left or right, or the selection's moving end while selecting. */
    private fun moveCursor(service: BandService, node: AccessibilityNodeInfo, by: Int) =
        moveCursorTo(service, node) { cursor(node) + by }

    /** The cursor (the selection's moving end while selecting) to where [to] says in the field's text: its start, its end. */
    private fun moveCursorTo(service: BandService, node: AccessibilityNodeInfo, to: (String) -> Int) {
        node.refresh()
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val at = to(text)
        if (at !in 0..text.length) return
        val from = anchor ?: at
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, from)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, at)
        })
        // Our word ends here: what's written next starts afresh at the new place.
        changedAt = SystemClock.uptimeMillis()
        restart(service, node, Look(text, from, at))
    }

    /** A word's suggestions over the field (swipe up, when the setting says popup). */
    private val popup by lazy { ChoicePopup(this) }

    /** Whether the pen was down at the last look (a buzz when it changes). */
    private var pen = true

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val node = field ?: return
            val service = BandService.instance ?: return leave("the band disconnected")
            FieldWriting.refusal(Bands.writer(this@WriteService) != null, writerState())?.let { return leave(it) }
            if (SystemClock.uptimeMillis() - lastWritten > IDLE_MS) return leave("nothing written for a while")
            val answer = service.fieldEdits() ?: return leave("the band disconnected")
            val edits = answer.optJSONArray("edits")
            // Edits made for a field someone else has since changed would land in the wrong
            // place: dropped, and writing starts afresh where the cursor now is.
            if (edits != null && edits.length() > 0 && !asLeft(node)) {
                log("handwriting: dropped ${edits.length()} edit(s) the change overtook")
            } else if (edits != null && edits.length() > 0) {
                lastWritten = SystemClock.uptimeMillis()
                // Writing while the menu is in the field: the menu goes first.
                if (inline.isNotEmpty()) {
                    menu = null
                    setInline(node, "")
                }
                for (i in 0 until edits.length()) {
                    val edit = edits.getJSONObject(i)
                    apply(node, edit.optInt("delete"), edit.optString("insert"))
                }
            }
            // Until the bridge has started this field's typing (it's asked from another thread);
            // it refuses with the controls off.
            if (!answer.optBoolean("active")) {
                if (answer.optBoolean("off")) return leave("the controls are off")
                return run { main.postDelayed(this, POLL_MS) }
            }
            choosing = answer.optBoolean("choosing")
            val list = answer.optJSONArray("suggestions")
            if (choosing && list != null && choicesPopup) {
                val hint = if (answer.optBoolean("next")) NEXT_HINT else CHOOSE_HINT
                popup.show(node, (0 until list.length()).map { list.getString(it) }, answer.optInt("highlight"), hint)
            } else if (menu == null) {
                popup.hide()
            }
            repeat(answer.optInt("dots")) { onDots() }
            repeat(answer.optInt("dots3")) { onDots(three = true) }
            val down = answer.optBoolean("pen")
            if (down != pen) {
                pen = down
                if (down) lastWritten = SystemClock.uptimeMillis()
                buzz(down)
                log(if (down) "handwriting: pen down" else "handwriting: pen up (write .. or double tap to go on)")
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** The Band cues once when the pen goes down and twice when it lifts, if feedback is enabled. */
    private fun buzz(down: Boolean) {
        BandService.instance?.typingHaptics(down)
    }

    /** Delete [delete] characters before the cursor, then insert [insert] there. */
    private fun apply(node: AccessibilityNodeInfo, delete: Int, insert: String) {
        node.refresh()
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var end = node.textSelectionEnd.takeIf { it in 0..current.length } ?: current.length
        var start = (end - delete).coerceAtLeast(0)
        // Writing over a selection replaces it.
        val other = node.textSelectionStart.takeIf { it in 0..current.length } ?: end
        if (other != end) {
            start = minOf(other, end)
            end = maxOf(other, end)
            anchor = null
        }
        val text = current.substring(0, start) + insert + current.substring(end)
        val set = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set)) {
            log("handwriting: the field didn't take the text")
            return
        }
        val cursor = start + insert.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        expected = Look(text, cursor, cursor)
        changedAt = SystemClock.uptimeMillis()
    }

    private fun log(line: String) {
        Log.i("AirGestures", line)
        BandService.log(line)
    }

    companion object {
        const val CHOOSE_HINT = "Swipe to choose · tap to take · down to cancel"
        const val MENU_HINT = "Swipe to choose · tap to do · up to close"
        const val NEXT_HINT = "Next word · swipe to choose · tap to take · up: change the last word · down to cancel"
        const val DELETE_WORD = "Del word"
        const val DELETE_ALL = "Del all"
        const val NEW_LINE = "↵"
        const val PUNCTUATION_ITEM = "!"
        const val EMOJI_ITEM = "🙂"
        /** The edit menu (swipe down), and its punctuation and emoji lists. */
        val MAIN = listOf(DELETE_WORD, DELETE_ALL, NEW_LINE, PUNCTUATION_ITEM, EMOJI_ITEM)
        /** What still happens after word choices it closes (the rest only close them). */
        private val AFTER_CHOOSING = setOf(
            WriteAction.DELETE_WORD, WriteAction.DELETE_ALL, WriteAction.HOME, WriteAction.END, WriteAction.WORD_LEFT, WriteAction.WORD_RIGHT,
        )
        /** Punctuation that closes up to the word before it. */
        val CLOSING = setOf(".", ",", "?", "!", ":", ";", "…")
        val PUNCTUATION = listOf(".", ",", "?", "!", "'", "\"", ":", ";", "-", "(", ")", "@", "#", "&", "/", "…", "*", "$", "%", "+", "=")
        val EMOJI = listOf("😀", "😂", "😊", "😍", "🥰", "😉", "😎", "🤔", "😮", "😢", "😭", "😡", "👍", "👎", "👏", "🙏",
            "👋", "💪", "❤️", "🔥", "🎉", "✅", "❌", "👀", "💯", "🤷", "🙌", "😴")

        /** Back to gestures after this long with nothing written (the pen lifts much sooner). */
        const val IDLE_MS = 5 * 60_000L
        /** After the keyboard comes up, how long until the focused field is looked for. */
        private const val KEYBOARD_SETTLE_MS = 300L
        /** A window over the field: how long until it's checked again that the field kept the focus. */
        private const val RECHECK_MS = 500L
        const val POLL_MS = 100L
        /** How long a field may take to show a change of ours before a difference is someone else's. */
        const val SETTLE_MS = 300L

        @Volatile var instance: WriteService? = null
            private set

        /** Whether the user has turned this service on in the system's accessibility settings. */
        fun enabled(context: Context): Boolean {
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return on.split(':').any { it.endsWith("/.WriteService") || it.endsWith("/dev.kb896.airgestures.WriteService") }
        }
    }
}

/** When the band may write into text fields. Free of Android, so it's unit-tested. */
object FieldWriting {
    /** Why it can't now ([setting]: Write in text fields), or null when it can. */
    fun refusal(setting: Boolean, state: BandService.State): String? = when {
        !setting -> "the setting was turned off"
        state.phase != Phase.CONNECTED -> "the band disconnected"
        state.otaBusy -> "the band is updating"
        state.paused -> "the controls are off"
        else -> null
    }

    /**
     * Whether a window of [from] coming up ends writing into a field of [app]: another app's, or
     * a screen of ours ([own]: our package; [ownScreen]: an activity, a lesson say, whose gestures
     * aren't the field's); not the keyboard, system pop-ups, or our pop-up and pads over the field.
     */
    fun leaves(from: String, app: String?, own: String, ownScreen: Boolean): Boolean = when (from) {
        app -> false
        own -> ownScreen
        "com.android.systemui" -> false
        else -> !keyboard(from)
    }

    /** Where a word left of [at] in [text] starts (spaces before it skipped). */
    fun wordLeft(text: String, at: Int): Int {
        var i = at.coerceIn(0, text.length)
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }

    /** Where a word right of [at] in [text] ends (spaces before it skipped). */
    fun wordRight(text: String, at: Int): Int {
        var i = at.coerceIn(0, text.length)
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }

    /** A keyboard's package (Gboard's `…inputmethod…`, Samsung's honeyboard, …). */
    fun keyboard(from: String) = "inputmethod" in from || "keyboard" in from || "honeyboard" in from
}
