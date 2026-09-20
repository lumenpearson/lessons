package com.lumenpearson.lessons.ui.translate

/**
 * Where an attempt to send the session has got to.
 *
 * It lives in the view model rather than in the sheet, and that is the whole
 * reason the type exists. Opening a pull request forks, polls until the fork
 * appears, writes a file per module and only then opens the request: half a
 * minute of network on a bad day. A reader who closes the sheet in the middle
 * of it has not cancelled anything they know about, and a
 * `rememberCoroutineScope` would cancel it for them — quite possibly after the
 * branch existed, leaving a stray branch on their account and no pull request.
 */
internal sealed interface TranslationSubmit {

    data object Idle : TranslationSubmit

    data object Sending : TranslationSubmit

    /**
     * @property htmlUrl the pull request, opened once and shown afterwards.
     * @property refused keys that were not in the file their prefix pointed at.
     */
    data class Opened(val htmlUrl: String, val refused: List<String>) : TranslationSubmit

    data object Failed : TranslationSubmit
}
