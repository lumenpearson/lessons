package com.lumenpearson.lessons.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens [url] in whatever the phone picks for it; `false` when nothing could.
 *
 * One function for every external link the app opens — the legal documents,
 * the diary's own site, a Госуслуги handoff — because each of them has the same
 * two obligations. It is always `ACTION_VIEW` on the address and never an app
 * named by its package: which app takes a Госуслуги page is the phone's
 * decision, and nothing comes back from it. And it is guarded: a work profile
 * or a locked-down school phone may have no browser at all, and the about
 * card's links already learned that an unguarded `startActivity` takes the app
 * down on one.
 */
internal fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}
