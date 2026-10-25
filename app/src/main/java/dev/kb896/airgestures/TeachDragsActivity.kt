package dev.kb896.airgestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    /** The band and wrist these samples are for: a change starts again. */
    private var flow: Pair<String, String>? = null
    /** The service teaching was turned on in: a new one (the link restarted) is asked again. */
    private var service: BandService? = null
    private val main = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() {
            if (BandService.instance !== service) {
                startTeaching()
                render()
            }
            main.postDelayed(this, WATCH_MS)
        }
    }

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
        startTeaching()
        main.post(watch)
        render()
    }

    override fun onPause() {
        main.removeCallbacks(watch)
        service?.endDragTeaching()
        service = null
        super.onPause()
    }

    /** Teaching on in the service running now (none: it says so, and asks again when one starts). */
    private fun startTeaching() {
        service = BandService.instance
        // The listener runs on the main thread (BandService.onActions posts there).
        service?.startDragTeaching { _, direction, roll -> sampled(direction, roll) }
    }

    private fun sampled(direction: DoubleArray, roll: Double) {
        val address = address ?: return
        val hand = hand ?: return
        if (saved) return
        val now = address to hand
        if (flow != null && flow != now) {
            teach.reset()
            message = "The band or the wrist changed: starting again."
        }
        flow = now
        // Only a sample that filled a step can complete the set.
        if (teach.sample(direction, roll)) teach.json()?.let(::check)
        render()
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
        if (service == null) content.addView(note("The band's service isn't running: teaching starts when it's back.").apply { setTextColor(Cb.INK_900) })
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
                    // A fresh start: the next pinch mustn't save the old set again.
                    teach.reset()
                    flow = null
                    saved = false
                    message = null
                    render()
                }.first)
            }
        })
    }

    companion object {
        /** How often it checks the service is the one teaching. */
        private const val WATCH_MS = 1000L
    }
}
