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
 * left alone. It reads and changes only the focused field's text.
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

    override fun onServiceConnected() {
        instance = this
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        Words.load(this)
    }

    override fun onDestroy() {
        leave("the service stopped")
        runCatching { unregisterReceiver(screenOff) }
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = leave("interrupted")

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                if (node.isEditable && !node.isPassword) enter(node, event.packageName?.toString())
                else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) leave("the focus moved off the field")
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val node = field ?: return
                if (event.source == node) asLeft(node)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val from = event.packageName?.toString() ?: return
                // The keyboard, system pop-ups and our own popup come up over the field: they don't end it.
                if (field != null && from != app && from != packageName && from != "com.android.systemui" &&
                    !from.contains("inputmethod") && !from.contains("keyboard")) {
                    leave("$from came up")
                }
            }
        }
    }

    /** A text field has the focus: write into it, if the setting is on and the band can. */
    private fun enter(node: AccessibilityNodeInfo, from: String?) {
        if (!Prefs(this).writeInFields) return
        val service = BandService.instance ?: return
        val state = BandService.state.value
        if (state.phase != Phase.CONNECTED || state.otaBusy || service.bandWrites() != true) return
        val same = field?.let { it == node } == true
        // Moving to another field: the menu or word choices shown in the last one go first.
        if (!same) dropInline()
        field = node
        app = from
        lastWritten = SystemClock.uptimeMillis()
        if (same) return
        restart(service, node)
        // Gestures edit the field meanwhile instead of running their actions.
        service.startPractice(gestures)
        pen = true
        log("handwriting: writing into a field in $from")
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

    /** Back to gestures. */
    private fun leave(why: String) {
        dropInline()
        expected = null
        main.removeCallbacks(lookAgain)
        if (field == null) return
        field = null
        app = null
        main.removeCallbacks(tick)
        BandService.instance?.let {
            it.setFieldTyping(false)
            if (it.practice === gestures) it.endPractice()
        }
        log("handwriting: back to gestures ($why)")
    }

    /**
     * Gestures in a field, instead of their actions:
     * - swipe up: the word's suggestions, or after a space the likely next words (up again
     *   turns to the last word's); swipe left and right turn through them, a tap takes one,
     *   swipe down cancels;
     * - otherwise swipe left and right move the cursor, and a tap starts selecting: then
     *   left and right grow the selection from where the tap was, until the next tap (writing
     *   replaces what's selected);
     * - swipe down: the edit menu above the keyboard (a popup whichever way word choices show): delete word, delete all,
     *   new line, and lists of punctuation and emoji; left and right choose, a tap does it, swipe up closes
     *   (or goes back from the list);
     * - a double tap puts the pen down.
     */
    private val gestures = object : BandService.Practice {
        override fun gesture(label: String) = main.post { onGesture(label) }.let {}
        override fun action(id: String) = Unit
    }

    /** Choosing among a word's suggestions (a swipe up), and where a selection is anchored. */
    private var choosing = false
    private var anchor: Int? = null

    private fun onGesture(label: String) {
        val node = field ?: return
        val service = BandService.instance ?: return
        lastWritten = SystemClock.uptimeMillis()
        val open = menu
        when {
            label == "index double tap" -> service.fieldPenDown()
            open != null -> onMenuGesture(service, node, open, label)
            choosing -> when (label) {
                "swipe left" -> service.fieldChooseStep(false)
                "swipe right" -> service.fieldChooseStep(true)
                "index tap" -> {
                    service.fieldChoosePick()
                    choosing = false
                    popup.hide()
                }
                // Up again on the next words turns to the last word's readings.
                "swipe up" -> choosing = service.fieldChoose()
                "swipe down" -> {
                    service.fieldChooseCancel()
                    choosing = false
                    popup.hide()
                }
            }
            label == "swipe up" -> {
                anchor = null
                choosing = service.fieldChoose()
                if (!choosing) buzz(false)
            }
            label == "swipe down" -> {
                anchor = null
                menu = Menu(MAIN, 0)
                showMenu(node)
            }
            label == "index tap" -> {
                anchor = if (anchor == null) cursor(node) else null
                log(if (anchor != null) "handwriting: selecting (left and right, tap to stop)" else "handwriting: selection kept")
            }
            label == "swipe left" -> moveCursor(service, node, -1)
            label == "swipe right" -> moveCursor(service, node, 1)
        }
    }

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
        } else if (Prefs(this).choicesPopup) {
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

    private fun onMenuGesture(service: BandService, node: AccessibilityNodeInfo, open: Menu, label: String) {
        val n = open.items.size
        when (label) {
            "swipe left" -> menu = open.copy(pick = (open.pick + n - 1) % n)
            "swipe right" -> menu = open.copy(pick = (open.pick + 1) % n)
            "swipe up" -> menu = if (open.items === MAIN) null else Menu(MAIN, 0)
            "index tap" -> {
                val item = open.items[open.pick]
                menu = null
                setInline(node, "")
                when {
                    open.items === MAIN && item == PUNCTUATION_ITEM -> menu = Menu(PUNCTUATION, 0)
                    open.items === MAIN && item == EMOJI_ITEM -> menu = Menu(EMOJI, 0)
                    item == DELETE_WORD -> edit(service, node) { before, after -> before.trimEnd().trimEnd { !it.isWhitespace() } to after }
                    item == DELETE_ALL -> edit(service, node) { _, _ -> "" to "" }
                    item == NEW_LINE -> edit(service, node) { before, after -> before + "\n" to after }
                    // A mark that ends a word or sentence goes straight after it, then a space.
                    item in CLOSING -> edit(service, node) { before, after ->
                        before.trimEnd(' ') + item + (if (after.startsWith(" ")) "" else " ") to after
                    }
                    else -> edit(service, node) { before, after -> before + item to after }
                }
            }
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
    private fun moveCursor(service: BandService, node: AccessibilityNodeInfo, by: Int) {
        node.refresh()
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val at = cursor(node) + by
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
            val service = BandService.instance
            val state = BandService.state.value
            when {
                service == null || state.phase != Phase.CONNECTED -> return leave("the band disconnected")
                !Prefs(this@WriteService).writeInFields -> return leave("the setting was turned off")
                SystemClock.uptimeMillis() - lastWritten > IDLE_MS -> return leave("nothing written for a while")
            }
            val answer = service!!.fieldEdits() ?: return leave("the band disconnected")
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
            // Until the bridge has started this field's typing (it's asked from another thread).
            if (!answer.optBoolean("active")) return run { main.postDelayed(this, POLL_MS) }
            choosing = answer.optBoolean("choosing")
            val list = answer.optJSONArray("suggestions")
            if (choosing && list != null && Prefs(this@WriteService).choicesPopup) {
                val hint = if (answer.optBoolean("next")) NEXT_HINT else CHOOSE_HINT
                popup.show(node, (0 until list.length()).map { list.getString(it) }, answer.optInt("highlight"), hint)
            } else if (menu == null) {
                popup.hide()
            }
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

    /** The phone's own short buzz: one when the pen goes down, two when it lifts. */
    private fun buzz(down: Boolean) {
        val vibrator = getSystemService(android.os.Vibrator::class.java) ?: return
        val pattern = if (down) longArrayOf(0, 30) else longArrayOf(0, 20, 80, 20)
        runCatching { vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1)) }
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
        BandService.state.update { copy(log = (log + line).takeLast(60)) }
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
        /** Punctuation that closes up to the word before it. */
        val CLOSING = setOf(".", ",", "?", "!", ":", ";", "…")
        val PUNCTUATION = listOf(".", ",", "?", "!", "'", "\"", ":", ";", "-", "(", ")", "@", "#", "&", "/", "…", "*", "$", "%", "+", "=")
        val EMOJI = listOf("😀", "😂", "😊", "😍", "🥰", "😉", "😎", "🤔", "😮", "😢", "😭", "😡", "👍", "👎", "👏", "🙏",
            "👋", "💪", "❤️", "🔥", "🎉", "✅", "❌", "👀", "💯", "🤷", "🙌", "😴")

        /** Back to gestures after this long with nothing written (the pen lifts much sooner). */
        const val IDLE_MS = 5 * 60_000L
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
