package com.lumenpearson.lessons.core.data.repository

/**
 * A diary login as the server cleans it — `_clean_login_value` in
 * `server/app/schemas.py`, held to it by the `login` cases of the shared
 * vectors.
 *
 * One cleaning, used everywhere a login leaves the phone: the diary's own
 * sign-in and our server's registration. The server stores the login only to
 * name the session — it keys nothing, corrections included, which belong to
 * the child — and it used to receive the password and clean the login itself
 * before any upstream call. When the phone signs in on
 * its own, a login pasted from a chat with a bidi mark or a zero-width space
 * around it went upstream exactly as pasted: the diary compared it to the
 * account's name, said no, and the screen asked for the password again,
 * however many times it was typed right.
 */
internal object DiaryLogin {

    /** The server's «at least 3 usable characters», counted as Python counts: in code points. */
    const val MIN_CODE_POINTS = 3

    /**
     * [typed] with everything Python's `str.isprintable` refuses taken out —
     * controls, format characters (U+200B, U+200E, U+2068, U+FEFF, the soft
     * hyphen), surrogates, private use, unassigned code points and every
     * separator but the plain space — then trimmed; or `null` when fewer than
     * [MIN_CODE_POINTS] are left.
     *
     * Walked by code point, not by `Char`: half of a pair is a surrogate on its
     * own, and dropping both halves of an emoji would count two characters
     * fewer than the server does.
     */
    fun clean(typed: String): String? {
        val kept = buildString(typed.length) {
            typed.codePoints().forEach { point -> if (point == SPACE || printable(point)) appendCodePoint(point) }
        }
        // Nothing but the plain space can be whitespace by now, so this is
        // Python's `.strip()` on what is left.
        val cleaned = kept.trim { it.code == SPACE }
        return cleaned.takeIf { it.codePointCount(0, it.length) >= MIN_CODE_POINTS }
    }

    private const val SPACE = 0x20

    private fun printable(point: Int): Boolean = when (Character.getType(point).toByte()) {
        Character.CONTROL,
        Character.FORMAT,
        Character.SURROGATE,
        Character.PRIVATE_USE,
        Character.UNASSIGNED,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.SPACE_SEPARATOR,
        -> false
        else -> true
    }
}
