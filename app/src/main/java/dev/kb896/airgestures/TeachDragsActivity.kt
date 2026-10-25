package dev.kb896.airgestures

import android.os.Bundle
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * Teach your drags: the turn and the six drags, one pinch each, in the user's own directions
 * (band-core's Prototypes::taught checks them); saved per band and wrist like the palm
 * positions. While this is open, pinches teach and nothing acts.
 */
class TeachDragsActivity : Screen() {
    private lateinit var content: LinearLayout
    private val teach = DragTeach()
    private var saved = false
    private var message: String? = null
    /** The wrist picked here, when the band hasn't said and the app is set to "band". */
    private var picked: String? = null

    private val prefs get() = Prefs(this)
    private val address get() = prefs.bandAddress
    private val hand get() = prefs.handInUse() ?: picked

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = column(gap = 20f)
        show(pageColumn().apply { addView(content) }, title = "Teach your drags")
    }

    override fun onResume() {
        super.onResume()
        // The listener runs on the main thread (BandService.onActions posts there).
        BandService.instance?.startDragTeaching { _, direction, roll ->
            if (address != null && hand != null && !saved) {
                teach.sample(direction, roll)
                teach.json()?.let(::check)
                render()
            }
        }
        render()
    }

    override fun onPause() {
        BandService.instance?.endDragTeaching()
        super.onPause()
    }

    private fun check(samples: String) {
        val result = JSONObject(Bridge.dragPrototypes(samples))
        val error = result.optString("error")
        if (error.isEmpty()) {
            Calibrations.update(this, address ?: return, hand ?: return) { it.copy(drags = result.toString()) }
            BandService.instance?.remap()
            saved = true
            message = null
        } else {
            message = teach.refused(error).second
        }
    }

    private fun render() {
        content.removeAllViews()
        val address = address
        if (address == null) {
            content.addView(note("Connect the band first: drags are taught per band."))
            return
        }
        val hand = hand
        if (hand == null) {
            content.addView(card(null, column(gap = 12f).apply {
                addView(note("Which wrist is the band on? The band hasn't said yet."))
                val left = segment("Left") { picked = "left"; render() }
                val right = segment("Right") { picked = "right"; render() }
                addView(row().apply { addView(segmented(left, right)) })
            }))
            return
        }
        content.addView(note("For this band on your $hand wrist. Pinch thumb and index or middle finger, do the move, let go: one pinch each, in order."))
        val current = teach.current
        content.addView(card(null, column(gap = 4f).apply {
            for (step in DragTeach.Step.entries) {
                val done = teach.done(step)
                addView(row(gap = 12f).apply {
                    setPadding(0, dpi(8f), 0, dpi(8f))
                    addView(column(gap = 3f).apply {
                        addView(Cb.text(context, 15f, R.font.geist_medium, if (done || step == current) Cb.INK_900 else Cb.INK_400).apply { text = step.title })
                        if (step == current && !saved) {
                            addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                                text = step.how
                                setLineSpacing(0f, 1.3f)
                            })
                        }
                    }, weighted())
                    when {
                        done -> {
                            addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = "Done ✓" })
                            addView(pill("Redo") {
                                teach.redo(step)
                                saved = false
                                message = null
                                render()
                            }.first)
                        }
                        step == current -> addView(Cb.chip(context, Cb.Signal.INFO, "Now"))
                    }
                })
            }
        }))
        message?.let { content.addView(note(it).apply { setTextColor(Cb.INK_900) }) }
        if (saved) content.addView(note("Saved for this band and your $hand wrist."))
        val stored = Calibrations.get(this, address, hand).drags != null
        content.addView(row(gap = 10f).apply {
            if (saved) addView(pill("Done", R.drawable.ic_check) { finish() }.first)
            // Back to band-core's measured drags (the owner's right hand).
            if (stored) {
                addView(pill("Use the measured ones") {
                    Calibrations.update(this@TeachDragsActivity, address, hand) { it.copy(drags = null) }
                    BandService.instance?.remap()
                    saved = false
                    render()
                }.first)
            }
        })
    }

    private fun note(text: String) = Cb.text(this, 13f, color = Cb.INK_500).apply {
        this.text = text
        setLineSpacing(0f, 1.3f)
    }
}
