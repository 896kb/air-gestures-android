package dev.kb896.airgestures

import android.content.Context
import android.content.Intent

/**
 * The Handwriting switch on Home: writing with the band (debug builds' Write screen, which
 * turns the band's raw sEMG on while it runs). Builds without that screen show no switch.
 */
object Handwriting {
    private const val SCREEN = "dev.kb896.airgestures.WriteActivity"
    /** Set by the Write screen to start writing as it opens. */
    const val EXTRA_START = "start"

    /** Writing now; [stop] ends it (set by the Write screen while it writes). */
    @Volatile var on = false
    @Volatile var stop: (() -> Unit)? = null

    private fun intent(context: Context) = Intent().setClassName(context, SCREEN)

    fun available(context: Context) = intent(context).resolveActivity(context.packageManager) != null

    /** Off: open the Write screen writing. On: stop writing. */
    fun toggle(context: Context) {
        if (on) {
            stop?.invoke()
        } else {
            context.startActivity(intent(context).putExtra(EXTRA_START, true))
        }
    }
}
