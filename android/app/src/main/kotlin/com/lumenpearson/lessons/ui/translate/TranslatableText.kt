package com.lumenpearson.lessons.ui.translate

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView

/**
 * A piece of the app's own copy that the reader is allowed to argue with.
 *
 * Drawn as an ordinary [Text] until correction mode is on, at which point it
 * grows a thin outline and answers a long press with the editor for its own
 * string. Nothing else changes: the text is still laid out, measured and
 * styled by the same call, so wrapping a label in this costs nothing when the
 * mode is off, which is every build anybody ships.
 */
@Composable
fun TranslatableText(
    @StringRes textRes: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Correctable(textRes = textRes, modifier = modifier) { text ->
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * The same offer for something that is not a bare [Text]: a header, a row, a
 * button — anything that takes its words from one string resource and draws
 * them itself.
 *
 * It hands the corrected text to [content] rather than letting the caller read
 * the resource, which is what keeps the live preview honest: the reader sees
 * their own wording in the row, in the place the row sits, and can tell
 * immediately that it is now too long for it.
 *
 * A wrapper with a slot rather than a plain modifier, because the editor is a
 * bottom sheet and a modifier has nowhere to put one. Everything else about it
 * — the outline, the gesture — is in [correctionTarget], which is a modifier
 * and can be applied on its own by anything that already hosts a sheet.
 */
@Composable
fun Correctable(
    @StringRes textRes: Int,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit,
) {
    // LocalResources rather than the context's own: it is re-provided when the
    // configuration changes, and the whole point of the key is that it is read
    // back in whichever language the string was just drawn in.
    val resources = LocalResources.current
    val locale = currentLocale()
    val key = remember(textRes, resources) { TranslationKeys.of(resources, textRes) }
    val original = stringResource(textRes)
    val shown = if (key == null) {
        original
    } else {
        TranslationMode.session.correctionOf(key, locale) ?: original
    }

    var editing by remember { mutableStateOf(false) }

    val correctable = TranslationMode.enabled && key != null
    Box(
        modifier = modifier.correctionTarget(enabled = correctable) { editing = true },
    ) {
        content(shown)
    }

    if (editing && key != null) {
        TranslationEditorSheet(
            stringKey = key,
            locale = locale,
            original = original,
            onDismiss = { editing = false },
        )
    }
}

/**
 * The gesture and the outline, without the editor.
 *
 * The long press is read on the [PointerEventPass.Initial] pass, which is the
 * only way it can win over what is underneath it. Every row this is likely to
 * be wrapped around — a settings row, a lesson, a chip — is already clickable,
 * and a clickable child consumes the press long before an outer
 * `combinedClickable` hears about it. So the press is watched from the outside
 * in: nothing is consumed while the finger might still be doing something
 * ordinary, and everything is consumed the moment the press has lasted long
 * enough to be a correction, which is what cancels the tap the row was about to
 * report.
 */
@Composable
fun Modifier.correctionTarget(enabled: Boolean, onLongPress: () -> Unit): Modifier {
    if (!enabled) return this

    val view = rememberHapticView()
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val outline = MaterialTheme.colorScheme.tertiary

    return this
        .border(
            width = OutlineWidth,
            color = outline.copy(alpha = OutlineAlpha),
            shape = RoundedCornerShape(OutlineCorner),
        )
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                if (!awaitLongPress(down)) return@awaitEachGesture
                LessonsHaptics.press(view)
                currentOnLongPress()
                swallowRestOfGesture(down.id)
            }
        }
}

/**
 * Waits out the long-press timeout, and says whether the finger was still there
 * at the end of it.
 *
 * A lift or a drag past the touch slop ends the wait early and reports `false`,
 * so a tap and a scroll started on translatable text behave exactly as they do
 * anywhere else.
 */
private suspend fun AwaitPointerEventScope.awaitLongPress(down: PointerInputChange): Boolean {
    val settled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var done = false
        while (!done) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            done = change == null ||
                !change.pressed ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        }
    }
    return settled == null
}

/**
 * Eats what is left of the press after the editor has been asked for, so that
 * the row underneath sees a cancelled gesture instead of reporting a tap when
 * the finger finally lifts.
 */
private suspend fun AwaitPointerEventScope.swallowRestOfGesture(pointer: PointerId) {
    var pressed = true
    while (pressed) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
        pressed = event.changes.any { it.id == pointer && it.pressed }
    }
}

/**
 * The language the app is currently drawn in, which is the folder a correction
 * belongs to.
 *
 * Read from the configuration rather than from the stored preference: on
 * "system" the preference names no language at all, and it is the resolved one
 * that decided which `values-` folder the string on screen came out of.
 */
@Composable
internal fun currentLocale(): String {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language ?: FallbackLocale
    }
}

/** The app's default locale, for the impossible case of an empty locale list. */
private const val FallbackLocale = "ru"

private val OutlineWidth: Dp = 1.dp

private val OutlineCorner: Dp = 8.dp

/** Enough to see where the targets are, faint enough to read the page through. */
private const val OutlineAlpha = 0.5f
