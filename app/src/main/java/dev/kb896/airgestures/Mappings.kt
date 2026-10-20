package dev.kb896.airgestures

import java.util.Locale

/** The plain logic behind the mapping and the new actions, kept free of Android so it's unit-tested. */
object Mappings {
    /** In the call layer: the gesture keeps its normal action. */
    const val AS_USUAL = ""
    /** In the call layer: the gesture does nothing during a call. */
    const val NOTHING = "-"
    const val AUTOMATION = "automation"

    /**
     * The `gesture=action;…;dial_up=…;dial_down=…;hand=…` string the bridge gets: [normal]
     * (gesture key → action id, in order), with [calls] on top while [inCall]. A stored
     * [AUTOMATION] becomes `automation:<gesture>`, since the bridge reports only action names.
     */
    fun effective(
        normal: Map<String, String>,
        calls: Map<String, String>,
        dialUp: String,
        dialDown: String,
        hand: String,
        inCall: Boolean,
    ): String {
        val gestures = normal.entries.joinToString(";") { (key, action) ->
            val during = if (inCall) calls[key] else null
            val id = when (during) {
                null, AS_USUAL -> action
                NOTHING -> ""
                else -> during
            }
            "$key=${if (id == AUTOMATION) "$AUTOMATION:$key" else id}"
        }
        return "$gestures;dial_up=$dialUp;dial_down=$dialDown;hand=$hand"
    }
}

/** A "tap here" spot: `tap:<x>,<y>`, as fractions of the screen's width and height. */
object Tap {
    const val PREFIX = "tap:"

    fun format(x: Float, y: Float) = String.format(Locale.ROOT, "%s%.4f,%.4f", PREFIX, x, y)

    /** The spot, or null for anything that isn't a tap on the screen. */
    fun parse(id: String): Pair<Float, Float>? {
        if (!id.startsWith(PREFIX)) return null
        val (x, y) = id.removePrefix(PREFIX).split(',').takeIf { it.size == 2 }
            ?.map { it.toFloatOrNull() ?: return null } ?: return null
        return if (x in 0f..1f && y in 0f..1f) x to y else null
    }
}

/** What "read the newest notification" says. */
object Speech {
    /** App, title and text; only app and title while [locked] (as the lock screen hides content). */
    fun sentence(app: String, title: String?, text: String?, locked: Boolean): String {
        val heading = title?.takeIf { it.isNotBlank() }?.trim()
        val body = text?.takeIf { it.isNotBlank() && !locked }?.trim()
        return when {
            heading != null && body != null -> "$app. $heading: $body"
            body != null -> "$app. $body"
            heading != null -> "$app. $heading."
            else -> "$app."
        }
    }
}

/** Which notification the dismiss and read gestures act on. */
object Notes {
    class Note(
        val key: String,
        val postTime: Long,
        val clearable: Boolean,
        val ongoing: Boolean,
        val ours: Boolean,
        /** A group's summary with no text of its own. */
        val emptySummary: Boolean,
    )

    /** The latest one the user could swipe away themselves: not ongoing, not ours, not an empty summary. */
    fun newest(notes: List<Note>) =
        notes.filter { it.clearable && !it.ongoing && !it.ours && !it.emptySummary }.maxByOrNull { it.postTime }
}
