package dev.kb896.airgestures

import android.content.Context
import android.provider.UserDictionary
import java.io.File
import kotlin.concurrent.thread

/**
 * The dictionary handwriting picks words from: the bundled one (assets/words.txt), the
 * personal words kept here, and the system's personal dictionary when Android lets this app
 * read it (it lets only the keyboard and spell checker, so usually it's skipped). Personal
 * words count as very common, so they win over a close dictionary word.
 */
object Words {
    /** How common a personal word counts as (the bundled list's most common is ~3e7). */
    private const val PERSONAL_COUNT = 5_000_000L

    @Volatile private var loaded = 0

    private fun file(context: Context) = File(context.filesDir, "personal-words.txt")

    /** The personal words, one per line as entered. */
    fun personal(context: Context): List<String> =
        runCatching { file(context).readLines() }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }

    /** Replace the personal words and reload the dictionary. */
    fun setPersonal(context: Context, words: List<String>) {
        file(context).writeText(words.joinToString("\n") + "\n")
        loaded = 0
        load(context)
    }

    /** The dictionary into the bridge, once per process (and again after the personal words change). */
    fun load(context: Context) {
        if (loaded > 0) return
        val app = context.applicationContext
        thread(name = "words") {
            val bundled = app.assets.open("words.txt").bufferedReader().use { it.readText() }
            // What's written after each word, for next-word suggestions (lines of three or more fields).
            val pairs = app.assets.open("pairs.txt").bufferedReader().use { it.readText() }
            val extra = (personal(app) + systemWords(app))
                .map { it.lowercase() }
                .filter { word -> word.isNotEmpty() && word.all { it in 'a'..'z' } }
                .distinct()
                .joinToString("") { "$it $PERSONAL_COUNT\n" }
            loaded = Bridge.wordsLoad(bundled + pairs + extra)
        }
    }

    /** The system's personal dictionary, when readable (Android usually refuses: then none). */
    private fun systemWords(context: Context): List<String> = runCatching {
        context.contentResolver.query(UserDictionary.Words.CONTENT_URI, arrayOf(UserDictionary.Words.WORD), null, null, null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            .orEmpty()
    }.getOrDefault(emptyList())
}
