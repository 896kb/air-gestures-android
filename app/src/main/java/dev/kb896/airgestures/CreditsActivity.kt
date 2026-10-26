package dev.kb896.airgestures

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout

/** Credits (from the main screen): the people who built the app, each with their picture, what they made and their link. */
class CreditsActivity : Screen() {
    private class Person(val image: Int, val role: String, val name: String, val work: String, val site: String, val url: String)

    private val people = listOf(
        // Dave's own M-Hacks button, from his site.
        Person(
            R.drawable.credits_marginalhacks, "Firmware updates and handwriting", "David Ljung Madison Stellar",
            "Updating the band's firmware from the app, and handwriting: writing letters with the band, " +
                "typing into text fields, word suggestions and the edit menu.",
            "MarginalHacks.com", "https://marginalhacks.com",
        ),
        Person(
            R.drawable.credits_eight, "Made by", "Radu", "The app, its band protocol core and the Linux daemon.",
            "896.ro", "https://896.ro",
        ),
        Person(
            R.drawable.credits_iinsaane, "Band vibration and Bluetooth music controls", "Tudor Palasan (iinsaane)",
            "Band vibration feedback, volume detents, per-profile Bluetooth music controls and reconnect stability.",
            "iinsaane on GitHub", "https://github.com/iinsaane",
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(pageColumn().apply {
            for (person in people) {
                addView(card(null, column(gap = 8f).apply {
                    // The picture beside the role and name, both 40 dp tall and 46 wide so the names line up.
                    addView(row(gap = 14f).apply {
                        addView(ImageView(context).apply {
                            setImageResource(person.image)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }, LinearLayout.LayoutParams(dpi(46f), dpi(40f)))
                        addView(column(gap = 4f).apply {
                            addView(Cb.label(context, person.role))
                            addView(Cb.text(context, 17f, R.font.geist_semibold, Cb.INK_900, -.015f).apply { text = person.name })
                        }, weighted())
                    })
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
