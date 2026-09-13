package com.lumenpearson.lessons.ui.translate

/**
 * One string the reader says is wrong, and what they say it should be.
 *
 * [original] is kept beside [corrected] because the export is read by whoever
 * merges it, not by a machine: "this line replaces that line" is reviewable,
 * "this line is now X" is not. It is also what lets an edit be recognised as a
 * no-op — see [TranslationSession.updated].
 *
 * @param key the resource entry name, `settings_title` and not `R.string.settings_title`.
 * @param locale the language tag of the values folder being corrected, `ru` or `en`.
 */
data class TranslationEdit(
    val key: String,
    val locale: String,
    val original: String,
    val corrected: String,
)

/**
 * Every correction made since the mode was switched on.
 *
 * Immutable, and pure Kotlin with no Compose and no Android in it: the mode's
 * state holder keeps one of these in a `mutableStateOf`, so the list that the
 * screens read is a value that either is or is not the one they last saw. A
 * mutable list behind a snapshot would have the same behaviour only as long as
 * every mutation went through the snapshot, and the rule that matters here —
 * an edit that changes nothing is not an edit — is far easier to state and to
 * test on a value than on a list somebody else can also append to.
 *
 * Edits keep the order they were first made in, including when one is revised.
 * The session sheet is a list of what the reader has done so far, and a list
 * that reorders itself under the finger is a list nobody can cross items off.
 */
data class TranslationSession(val edits: List<TranslationEdit> = emptyList()) {

    /**
     * [edit] recorded, replacing any earlier correction of the same string.
     *
     * A correction that restores the original, or that is blank, removes the
     * edit instead of storing it: both mean "leave this string alone", and an
     * export that carried them would ask a reviewer to apply a change that is
     * not a change. Whitespace at the ends is ignored for that comparison but
     * kept in the stored value — a translator who added a trailing space did so
     * on purpose, and only they know it.
     */
    fun updated(edit: TranslationEdit): TranslationSession {
        if (edit.corrected.isBlank() || edit.corrected.trim() == edit.original.trim()) {
            return without(edit.key, edit.locale)
        }
        val existing = edits.indexOfFirst { it.key == edit.key && it.locale == edit.locale }
        return if (existing == -1) {
            TranslationSession(edits + edit)
        } else {
            TranslationSession(edits.toMutableList().also { it[existing] = edit })
        }
    }

    /** The session without the correction of one string, in one locale. */
    fun without(key: String, locale: String): TranslationSession =
        TranslationSession(edits.filterNot { it.key == key && it.locale == locale })

    /**
     * What should be drawn in place of the string, or `null` for the string as
     * it ships.
     */
    fun correctionOf(key: String, locale: String): String? =
        edits.firstOrNull { it.key == key && it.locale == locale }?.corrected

    val isEmpty: Boolean get() = edits.isEmpty()

    val size: Int get() = edits.size
}
