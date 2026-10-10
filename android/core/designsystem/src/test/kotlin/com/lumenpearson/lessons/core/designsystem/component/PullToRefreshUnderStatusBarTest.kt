package com.lumenpearson.lessons.core.designsystem.component

import android.graphics.Insets
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pull-to-refresh loader under a status bar (#397).
 *
 * Material3 clips the loader at its own top edge and slides it into view
 * from there. Laid out below the status bar, as #224 once did it, it came
 * down cut off along the status bar's lower edge while every row of the page
 * passed under the status bar whole. Laid out from the window's top, the
 * clipping line is the window's edge, where nothing is lost.
 *
 * Robolectric reports no status bar of its own, which is why the old layout
 * passed here unseen: the test gives the window one.
 */
@RunWith(RobolectricTestRunner::class)
class PullToRefreshUnderStatusBarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the loader is laid out from the window's top, not below the status bar`() {
        var statusBar = 0.dp
        compose.runOnUiThread {
            // The platform's own calls: Robolectric runs this module at API 34 (robolectric.properties).
            compose.activity.window.setDecorFitsSystemWindows(false)
        }
        compose.setContent {
            statusBar = statusBarSpace()
            LessonsTheme {
                // Not refreshing: the refreshing loader morphs for ever, and the
                // clock would never be idle. Where the loader is laid out does not
                // depend on it.
                LessonsPullToRefreshBox(isRefreshing = false, onRefresh = {}, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.runOnUiThread {
            val insets = WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, StatusBarPx, 0, 0))
                .build()
            compose.activity.window.decorView.dispatchApplyWindowInsets(insets)
        }
        compose.waitForIdle()

        assertTrue("the window's status bar reached the composition, as $statusBar", statusBar > 0.dp)
        assertEquals(0.dp, compose.onNodeWithTag(PullToRefreshLoaderTag).getBoundsInRoot().top)
    }

    private companion object {
        /** A status bar about as tall as a phone's with a camera cutout in it. */
        const val StatusBarPx = 120
    }
}
