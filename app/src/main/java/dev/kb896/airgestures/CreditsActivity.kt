package dev.kb896.airgestures

import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Credits (from the main screen): the people who built the app, each with what they made and their link. */
class CreditsActivity : Screen() {
    private class Person(val role: String, val name: String, val work: String, val site: String, val url: String)

    private val people = listOf(
        Person(
            "Firmware updates and handwriting", "David Ljung Madison Stellar",
            "Updating the band's firmware from the app, and handwriting: writing letters with the band, " +
                "typing into text fields, word suggestions and the edit menu.",
            "MarginalHacks.com", "https://marginalhacks.com",
        ),
        Person(
            "Made by", "Radu", "The app, its band protocol core and the Linux daemon.",
            "896.ro", "https://896.ro",
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(pageColumn().apply {
            for (person in people) {
                addView(card(null, column(gap = 8f).apply {
                    addView(Cb.label(context, person.role))
                    addView(Cb.text(context, 17f, R.font.geist_semibold, Cb.INK_900, -.015f).apply { text = person.name })
                    addView(Cb.text(context, 14f, color = Cb.INK_700).apply {
                        text = person.work
                        setLineSpacing(0f, 1.3f)
                    })
                    addView(row().apply {
                        setPadding(0, dpi(4f), 0, 0)
                        addView(pill(person.site, R.drawable.ic_link) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(person.url)))
                        }.first)
                    })
                }))
            }
        }, title = "Credits")
    }
}
