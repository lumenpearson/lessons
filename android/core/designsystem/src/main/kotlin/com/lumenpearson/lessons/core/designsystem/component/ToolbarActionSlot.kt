package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.modifier.centreInRoot
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.tweenSpec

/**
 * The button beside the pill, growing in, shrinking out and changing its icon
 * in place (#264), with the gap Material keeps between the two.
 *
 * Keyed by what the button is, so that a new click handler for the same button
 * is not a change anybody sees, and the null state draws nothing at all: its
 * width is what the size transform shrinks the slot to.
 */
@Composable
internal fun ActionSlot(action: ToolbarAction?, floatingActionButton: (@Composable () -> Unit)?) {
    val motion = LocalMotion.current
    val slot: ActionFace? = when {
        action != null -> ActionFace.Button(action)
        floatingActionButton != null -> ActionFace.Custom(floatingActionButton)
        else -> null
    }
    AnimatedContent(
        targetState = slot,
        contentKey = { it?.key },
        transitionSpec = {
            val enter = fadeIn(motion.tweenSpec(ModeFadeMillis)) +
                scaleIn(motion.tweenSpec(ModeFadeMillis), ActionScaleFrom)
            val exit = fadeOut(motion.tweenSpec(ModeFadeMillis)) +
                scaleOut(motion.tweenSpec(ModeFadeMillis), ActionScaleFrom)
            (enter togetherWith exit).using(SizeTransform(clip = false) { _, _ -> motion.toolbarSizeSpring() })
        },
        contentAlignment = Alignment.CenterStart,
        label = "toolbar_action",
    ) { shown ->
        when (shown) {
            null -> Unit
            is ActionFace.Button -> Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(ActionGap))
                ToolbarActionButton(shown.action)
            }
            is ActionFace.Custom -> Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(ActionGap))
                shown.content()
            }
        }
    }
}

/** What [ActionSlot] holds; see there. */
@Immutable
private sealed interface ActionFace {
    val key: Any

    @Immutable
    data class Button(val action: ToolbarAction) : ActionFace {
        override val key: Any get() = action.contentDescription
    }

    @Immutable
    data class Custom(val content: @Composable () -> Unit) : ActionFace {
        override val key: Any get() = CustomActionKey
    }
}

private const val CustomActionKey = "custom"

/** Material's gap between the pill and the button beside it (`ToolbarToFabGap`). */
private val ActionGap: Dp = 8.dp

/** How small the button starts as it grows in, and ends as it shrinks out. */
private const val ActionScaleFrom = 0.6f

/**
 * Essentials' `fabAction` button: tonal, square-ish, and flat.
 *
 * No elevation on purpose — the pill beside it has none either, and a shadow
 * under one of the two would read as the button hovering above the bar rather
 * than sitting next to it.
 */
@Composable
internal fun ToolbarActionButton(action: ToolbarAction) {
    val scheme = MaterialTheme.colorScheme
    val view = rememberHapticView()
    var centre by remember { mutableStateOf(Offset.Unspecified) }

    FloatingActionButton(
        onClick = {
            LessonsHaptics.press(view)
            action.onClick(centre)
        },
        modifier = Modifier.centreInRoot { centre = it },
        containerColor = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        // Material's own FAB shape, kept on purpose. It is the FAB's
        // CornerLarge token, which this theme resolves to its `large` role:
        // 20 dp, the one corner the app draws off its four-value scale
        // (theme/Shape.kt says why, for this and the «День» FABs). Named
        // through FloatingActionButtonDefaults rather than read as a role, so
        // the exception stays tied to the component that carries it.
        shape = FloatingActionButtonDefaults.shape,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        Box {
            Icon(
                imageVector = action.icon,
                contentDescription = action.contentDescription,
                modifier = Modifier.size(24.dp),
            )
            if (action.badge) {
                Box(
                    modifier = Modifier
                        .size(BadgeSize)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(scheme.error),
                )
            }
        }
    }
}
