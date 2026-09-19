package com.lumenpearson.lessons.core.designsystem.text

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import java.util.Locale

/**
 * The app's own copy, opened up for the reader to argue with.
 *
 * Correction mode is a proofreading tool: it is switched on from the settings
 * page, every piece of the app's own wording grows a thin outline, and a long
 * press on one of them opens an editor for that string. The point is that the
 * reader sees their wording *in the place it is used*, which is the only way to
 * find out that it no longer fits the row.
 *
 * The whole thing is declared here, in the design system, and implemented in
 * `:app`. That split is not tidiness — it is the only shape that works. The
 * rows, the cards, the sheets and the headers every screen is built from live
 * in this module, and they are where most of the app's text is actually drawn;
 * but the editor is a bottom sheet over a session that `:app` owns, and
 * `:core:designsystem` must not depend on `:app`. So this module declares what
 * it needs and defaults it to [NoCorrections], which does nothing at all: a
 * build that never provides an implementation — a Compose preview, a screenshot
 * test, the widget — behaves exactly as it did before this file existed.
 */
@Stable
interface Corrections {

    /**
     * Whether the reader is proofreading right now.
     *
     * This gates the outline, the gesture and the registry — not the words.
     * [correctionOf] answers the same thing either way, on purpose: switching
     * the mode off is how a reader takes the outlines away and reads the app in
     * their own wording to find out whether it still fits the rows. Reverting
     * every correction at that moment would take away the only way to check
     * them, while the session sheet went on listing them.
     */
    val enabled: Boolean

    /**
     * What should be drawn in place of the string [id] ships with, which is
     * [shipped] itself unless this session has corrected it.
     *
     * Asked of every string in the app on every composition, including in a
     * build where nobody has ever opened the settings row, so an
     * implementation answers the empty case before it does anything else.
     *
     * For a string with format arguments this is the *pattern*: the correction
     * is written against `Осталось %1$d мин`, not against the sentence one
     * particular screen made out of it.
     */
    fun correctionOf(@StringRes id: Int, shipped: String): String

    /**
     * Records that [shown] is on screen and came out of [id].
     *
     * This is how a long press finds its way back to a resource. The text that
     * reaches a row is a `String` — `GroupItem` takes a title, not a resource
     * id, and it is right to: half the titles in this app are a subject's name
     * out of the database. So the key cannot travel down with the words, and
     * looking it up afterwards by searching every string in the app for the one
     * that matches would find three: 204 of this app's 991 strings share their
     * text with another one («Назад» is `docs_back`, `action_back` and
     * `ds_action_back`), and 100 more are format patterns that match nothing
     * they produce.
     *
     * Registering what is actually on screen answers both. The match is exact,
     * it is against the sentence *after* the arguments were put in, and the
     * only ambiguity left is two strings with identical text visible at the
     * same moment — which is real, and which [keysBehind] reports rather than
     * guesses at.
     */
    fun noteOnScreen(@StringRes id: Int, shown: String)

    /** Undoes one [noteOnScreen]; the text has left the screen. */
    fun forget(@StringRes id: Int, shown: String)

    /**
     * Every string currently on screen whose text is exactly [shown] — empty
     * when the words are not the app's own copy, which is the usual answer for
     * a subject, a teacher or a note out of the diary.
     */
    fun keysBehind(shown: String): List<Int>

    /** Opens the editor on [ids], which is never empty. */
    fun edit(@StringRes ids: List<Int>)
}

/**
 * Correction mode switched off, and the default everywhere.
 *
 * Also what the editor itself is given: see the comment on its sheet. A long
 * press inside the editor must do nothing, and providing this over that subtree
 * is how that is arranged — rather than by remembering not to use the corrected
 * spelling of a string in one file.
 */
object NoCorrections : Corrections {
    override val enabled: Boolean get() = false
    override fun correctionOf(id: Int, shipped: String): String = shipped
    override fun noteOnScreen(id: Int, shown: String) = Unit
    override fun forget(id: Int, shown: String) = Unit
    override fun keysBehind(shown: String): List<Int> = emptyList()
    override fun edit(ids: List<Int>) = Unit
}

/**
 * Static rather than dynamic: the implementation is created once and never
 * swapped, and everything that changes underneath it — whether the mode is on,
 * what has been corrected, what is on screen — is snapshot state read through
 * it. A dynamic local would add a subscription to every text in the app to
 * observe a value that does not move.
 */
val LocalCorrections = staticCompositionLocalOf<Corrections> { NoCorrections }

/**
 * [stringResource], with the reader's corrections applied and the resource id
 * remembered for as long as the words are on screen.
 *
 * This is what every call site in `:app` and in this module uses instead of
 * `stringResource`, and it is the half of the feature that cannot be arranged
 * from one place: a string has to be read through *something* that knows which
 * string it was, and by the time the text reaches a `Text` that is gone.
 */
@Composable
fun correctedString(@StringRes id: Int): String {
    val corrections = LocalCorrections.current
    val shipped = stringResource(id)
    // Corrected first, gated second: the words follow the session and only the
    // registry follows the mode. See [Corrections.enabled].
    val shown = corrections.correctionOf(id, shipped)
    if (!corrections.enabled) return shown
    OnScreen(corrections, id, shown)
    return shown
}

/**
 * The same for a string with arguments, where the correction is on the pattern
 * and what is registered is the sentence.
 *
 * A corrected pattern that has lost an argument — `%1$d` typed over, or two
 * arguments swapped into a form Java's formatter refuses — throws
 * `IllegalFormatException`, which from inside a proofreading tool would take
 * down the screen being proofread. It falls back to the pattern as it ships,
 * so the worst a bad correction can do is not appear.
 */
@Composable
fun correctedString(@StringRes id: Int, vararg formatArgs: Any): String {
    val corrections = LocalCorrections.current
    val shipped = stringResource(id)
    val locale = LocalResources.current.configuration.locales[0] ?: Locale.getDefault()
    val corrected = corrections.correctionOf(id, shipped)
    val shown = format(locale, corrected, formatArgs)
        ?: format(locale, shipped, formatArgs)
        ?: shipped
    if (!corrections.enabled) return shown
    OnScreen(corrections, id, shown)
    return shown
}

/** `null` when [pattern] and [args] do not go together. */
private fun format(locale: Locale, pattern: String, args: Array<out Any>): String? =
    runCatching { String.format(locale, pattern, *args) }.getOrNull()

/**
 * Keeps the registry honest about what is drawn.
 *
 * An effect rather than a write during composition, because composition runs
 * speculatively and can be thrown away: a string noted by a composition that
 * never lands would stay on the list for ever, and a long press elsewhere would
 * be told it might be that one.
 */
@Composable
private fun OnScreen(corrections: Corrections, @StringRes id: Int, shown: String) {
    DisposableEffect(corrections, id, shown) {
        corrections.noteOnScreen(id, shown)
        onDispose { corrections.forget(id, shown) }
    }
}

/**
 * The outline and the long press over something drawing [text], when [text] is
 * the app's own copy.
 *
 * Applied by [Text] to every piece of text in the app, which is why it has to
 * cost nothing to ask: off, it is a local read and a branch; on, it is a map
 * lookup. Text that is not a string resource — a subject, a name, a homework
 * note — gets no outline and no gesture, because [Corrections.keysBehind] has
 * nothing to say about it.
 *
 * The outlines arrive one frame after the mode is switched on, and that is not
 * a bug to chase. The registry is filled by effects, which run after the
 * composition that read it; the read is a snapshot read, so the texts are
 * invalidated the moment it is filled and draw their outline on the next
 * frame. The alternative — noting the string during composition — would leave
 * strings on the list that a discarded composition drew and nobody saw.
 */
@Composable
fun Modifier.correctable(text: String?): Modifier {
    val corrections = LocalCorrections.current
    if (!corrections.enabled || text.isNullOrEmpty()) return this
    val keys = corrections.keysBehind(text)
    if (keys.isEmpty()) return this
    return correctionTarget { corrections.edit(keys) }
}

/**
 * The gesture and the outline, without the lookup.
 *
 * The long press is read on the [PointerEventPass.Initial] pass, which is the
 * only way it can win over what is underneath it. Most text in this app sits
 * inside something clickable — a settings row, a lesson, a chip — and a
 * clickable parent consumes the press long before an outer `combinedClickable`
 * hears about it. So the press is watched from the outside in: nothing is
 * consumed while the finger might still be doing something ordinary, and
 * everything is consumed the moment the press has lasted long enough to be a
 * correction, which is what cancels the tap the row was about to report.
 */
@Composable
private fun Modifier.correctionTarget(onLongPress: () -> Unit): Modifier {
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
 * so a tap and a scroll started on correctable text behave exactly as they do
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

private val OutlineWidth: Dp = 1.dp

private val OutlineCorner: Dp = 8.dp

/** Enough to see where the targets are, faint enough to read the page through. */
private const val OutlineAlpha = 0.5f
