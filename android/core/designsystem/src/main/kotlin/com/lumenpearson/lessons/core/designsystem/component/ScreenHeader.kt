package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.LessonsSans
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme

/**
 * A screen's title, as the first thing in its scrolling content.
 *
 * This is what replaced the collapsing top app bar. Essentials has no app bars
 * at all: a page starts at the top of the window, its title scrolls away with
 * everything else, and the name of where you are lives in the pill at the
 * bottom, which is always there. Three things follow from that, and all three
 * are why the change was worth making.
 *
 * The title is readable in one place. With an app bar the screen's name was in
 * the bar *and* the selected tab of the toolbar said the same word, one of them
 * scrolling away and one of them not.
 *
 * A sub-page gets a way back that is under the thumb. The app bar's back arrow
 * was at the top left corner of a 6.7-inch phone.
 *
 * And the content can pass under the status bar, which is what makes the fade up
 * there mean anything: an opaque app bar in that strip left nothing to soften.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            // Weight is what marks a heading as a heading. This asked for a
            // second, rounded family until the typeface changed: the face this
            // app ships has no rounded axis, and the old family drew none
            // either — it was registered at one weight, so Compose synthesised
            // the SemiBold rather than the font drawing it.
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = LessonsSans,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "ScreenHeader", showBackground = true)
@Composable
private fun ScreenHeaderPreview() {
    LessonsTheme {
        ScreenHeader(title = "Сегодня", subtitle = "8 «Б» · 5 уроков")
    }
}
