package dev.kb896.airgestures

import android.os.Bundle

/** Setup › Licenses: what the app and its fonts, icons and word data are used under (assets/licenses.txt). */
class LicensesActivity : Screen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(pageColumn().apply {
            for ((heading, text) in Sections.read(context, "licenses.txt")) {
                addView(card(null, column(gap = 6f).apply {
                    addView(Cb.text(context, 15f, R.font.geist_semibold, Cb.INK_900).apply { this.text = heading })
                    addView(Cb.text(context, 14f, color = Cb.INK_700).apply {
                        this.text = text
                        setLineSpacing(0f, 1.3f)
                    })
                }))
            }
        }, title = "Licenses")
    }
}
