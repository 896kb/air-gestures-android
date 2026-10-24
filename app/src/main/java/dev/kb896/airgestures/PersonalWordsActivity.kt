package dev.kb896.airgestures

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText

/**
 * Setup › Personal words: words handwriting should know besides the dictionary's (names,
 * places, jargon), one per line. They count as very common, so a close misreading becomes them.
 * Letters a–z only; the rest of a line is ignored.
 */
class PersonalWordsActivity : Screen() {
    private lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = EditText(this).apply {
            setText(Words.personal(context).joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            gravity = Gravity.TOP
            textSize = 16f
            typeface = Cb.font(context, R.font.geist_medium)
            setTextColor(Cb.INK_900)
            background = Cb.hairlineCard(context, 10f)
            setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
        }
        show(pageColumn().apply {
            addView(card(null, column(gap = 12f).apply {
                addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                    text = "Words for handwriting to know besides the dictionary's: names, places, jargon. One per " +
                        "line, letters a–z. Android doesn't let other apps read your keyboard's personal dictionary, " +
                        "so add them here."
                    setLineSpacing(0f, 1.3f)
                })
                addView(field)
            }))
            addView(row().apply {
                gravity = Gravity.END
                addView(pill("Save") { save() }.first)
            })
        }, title = "Personal words")
    }

    private fun save() {
        val words = field.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        Words.setPersonal(this, words)
        log("saved ${words.size} personal ${if (words.size == 1) "word" else "words"}")
        finish()
    }
}
