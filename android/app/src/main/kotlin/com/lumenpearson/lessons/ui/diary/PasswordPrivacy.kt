package com.lumenpearson.lessons.ui.diary

import androidx.compose.runtime.Composable
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.text.correctedString

/**
 * What a sign-in form says about where the password goes, in the order it is
 * said, for both forms that take one — the diary's own and the first run's.
 *
 * With the diary's host known, the sentence that names it also says it goes
 * nowhere else, and the rest is only what happens to the session. The two
 * forms used to show that sentence beside the general notice, which opened by
 * saying it all again: «по HTTPS, прямо в дневник» twice, one paragraph after
 * the other (#175). Without a host, which a region handed off to its own site
 * never reaches this far to have, the general notice stands alone.
 */
@Composable
internal fun passwordPrivacyParagraphs(host: String?): List<String> =
    if (host != null) {
        listOf(
            correctedString(R.string.diary_password_destination, host),
            correctedString(R.string.diary_password_session),
        )
    } else {
        listOf(correctedString(R.string.diary_password_notice))
    }
