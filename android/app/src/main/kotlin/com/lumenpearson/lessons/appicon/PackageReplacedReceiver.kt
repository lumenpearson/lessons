package com.lumenpearson.lessons.appicon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the launcher back to exactly one icon after the app is updated.
 *
 * An update can remove the alias a phone had chosen, and the owner is going to
 * take most of the sixty-four out. Such a phone is then left with no launcher
 * entry at all, and no way back into the app but the widget. The system sends
 * MY_PACKAGE_REPLACED to the updated app alone, and the limits on implicit
 * broadcasts exempt it, so this runs on the update itself rather than the next
 * time somebody happens to open the app through the widget.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // goAsync keeps the process alive until the write is done, which happens
        // off the main thread this is called on. It is null when a test calls
        // onReceive directly, outside a real broadcast.
        val pending: PendingResult? = goAsync()
        AppIcons.store(context).reconcileInBackground { pending?.finish() }
    }
}
