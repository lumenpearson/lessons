package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.tweenSpec

/**
 * Standard mode: the same inverted pill, holding a back button and a title.
 *
 * The arrow stays put and only the title changes when the page under it does
 * (#264) — settings to a section, a section to the one inside it — the words
 * crossing over each other while the pill resizes round them, in the direction
 * the page travels.
 */
@Composable
internal fun BackAndTitle(face: BackFace) {
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()
    val motion = LocalMotion.current

    IconButton(
        onClick = {
            LessonsHaptics.press(view)
            face.onBackClick()
        },
        modifier = Modifier.size(ItemSize),
        colors = IconButtonDefaults.filledIconButtonColors(
            contentColor = scheme.primary,
            containerColor = scheme.background,
        ),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            // Named, not null. The title beside it is a separate node, so a
            // screen reader announcing this button announced nothing at all.
            contentDescription = correctedString(R.string.ds_action_back),
            modifier = Modifier.size(24.dp),
        )
    }
    AnimatedContent(
        targetState = face,
        contentKey = { it.title },
        transitionSpec = {
            val forward = targetState.depth >= initialState.depth
            val enter = fadeIn(motion.tweenSpec(ModeFadeMillis)) +
                slideInHorizontally(motion.tweenSpec(ModeSlideMillis)) { width ->
                    if (forward) width / 3 else -width / 3
                }
            val exit = fadeOut(motion.tweenSpec(ModeFadeMillis)) +
                slideOutHorizontally(motion.tweenSpec(ModeSlideMillis)) { width ->
                    if (forward) -width / 3 else width / 3
                }
            (enter togetherWith exit).using(SizeTransform(clip = false) { _, _ -> motion.toolbarSizeSpring() })
        },
        contentAlignment = Alignment.CenterStart,
        label = "toolbar_title",
    ) { shown ->
        val title = shown.title ?: return@AnimatedContent
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(ItemGap))
            // The cap and the padding go on the box, so the title is measured
            // against the room it will actually be drawn in rather than against
            // the toolbar. Same reason as the label above: a title that fits
            // should sit still. No floor: a short title used to sit in at least
            // 100 dp, and «← Sync» was as wide a pill as «← Settings» (#242).
            MarqueeText(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.background,
                modifier = Modifier
                    .widthIn(max = shown.titleMax + TitlePadding * 2)
                    .padding(horizontal = TitlePadding),
            )
        }
    }
}
