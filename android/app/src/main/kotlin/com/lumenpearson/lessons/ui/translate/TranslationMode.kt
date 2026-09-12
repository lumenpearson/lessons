package com.lumenpearson.lessons.ui.translate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the app is currently being proofread, and what has been found so far.
 *
 * Process-wide state in a plain object, the same shape `LessonsHaptics` uses
 * and for the same reason: every piece of text in the app has to be able to ask
 * "am I editable right now?" without a view model being threaded to it.
 *
 * **Nothing here is persisted, on purpose.** Correction mode turns every long
 * press in the app into an editor, which is not a state anybody should be able
 * to end up in without knowing how they got there. Making it die with the
 * process means the way out is always the same and always known — close the
 * app — and it means a session of corrections cannot rot in storage until it no
 * longer matches the strings it was written against. The cost is that an export
 * not taken before the app is closed is gone, which is why the session sheet
 * puts copy and share where they cannot be missed.
 */
internal object TranslationMode {

    /** Set from the settings row; read by every [TranslatableText] in the app. */
    var enabled by mutableStateOf(false)

    /** Everything corrected since [enabled] last became true. */
    var session by mutableStateOf(TranslationSession())
        private set

    /**
     * Records a correction, or drops it when it says nothing new — see
     * [TranslationSession.updated].
     */
    fun record(key: String, locale: String, original: String, corrected: String) {
        session = session.updated(TranslationEdit(key, locale, original, corrected))
    }

    /** Forgets one correction; the string goes back to what it ships as. */
    fun drop(key: String, locale: String) {
        session = session.without(key, locale)
    }

    /** Forgets all of them. Leaving the mode on its own does not: see [enabled]. */
    fun clear() {
        session = TranslationSession()
    }
}
