package dev.kb896.airgestures

import android.content.Context

/**
 * Text kept as an editable asset (the handwriting help, the licences): `## heading` lines and
 * the text under each. Lines next to each other are one paragraph, a blank line starts the
 * next, and other `#` lines are notes that aren't shown.
 */
object Sections {
    fun read(context: Context, asset: String): List<Pair<String, String>> =
        parse(context.assets.open(asset).bufferedReader().use { it.readText() })

    fun parse(text: String): List<Pair<String, String>> {
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in text.lines()) {
            when {
                line.startsWith("## ") -> sections += line.removePrefix("## ").trim() to mutableListOf()
                line.startsWith("#") -> Unit
                else -> sections.lastOrNull()?.second?.add(line.trim())
            }
        }
        return sections.map { (heading, lines) ->
            heading to lines.joinToString("\n").trim().split(Regex("\n\\s*\n")).joinToString("\n\n") { it.replace('\n', ' ') }
        }
    }
}
