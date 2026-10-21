package dev.kb896.airgestures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * An app saying it came to the front (`profile` = its package) or left (""). Its own profile
 * then applies, as when the accessibility service sees it in front, but from the app, which
 * knows: e.g. it keeps showing on an external screen while the phone is folded and the
 * launcher looks to be in front. It also gets the band's state ([BandService.ACTION_STATUS]).
 *
 * Exported, so anything on the phone can send it. The worst it can do is put one of this
 * app's own profiles in force: it can't read them, make one, or map a gesture to anything.
 */
class ProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BandService.ACTION_SET_PROFILE) return
        val pkg = intent.getStringExtra(BandService.EXTRA_PROFILE)?.trim().orEmpty()
        BandService.announce(context, pkg.ifEmpty { null })
    }
}
