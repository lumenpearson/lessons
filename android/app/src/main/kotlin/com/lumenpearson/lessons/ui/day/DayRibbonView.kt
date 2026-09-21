package com.lumenpearson.lessons.ui.day

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.HomeworkRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.modifier.RibbonDepthLevel
import com.lumenpearson.lessons.core.designsystem.modifier.progressiveBlur
import com.lumenpearson.lessons.core.designsystem.modifier.rememberRibbonDepthLevel
import com.lumenpearson.lessons.core.designsystem.modifier.ribbonSheen
import com.lumenpearson.lessons.core.designsystem.state.formatCountdown
import com.lumenpearson.lessons.core.designsystem.state.formatLength
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone
import com.lumenpearson.lessons.core.designsystem.theme.subjectTone
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.Ribbon
import com.lumenpearson.lessons.core.model.RibbonEntry
import com.lumenpearson.lessons.core.model.RibbonFlow
import com.lumenpearson.lessons.core.model.RibbonProgress
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.progressOf
import com.lumenpearson.lessons.core.model.ribbonOf
import com.lumenpearson.lessons.ui.common.asClock
import java.time.LocalTime
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The day as a ribbon: every entry in order, with the gaps drawn as rows.
 *
 * The hour ruler this replaced was right about one thing and wrong about the
 * rest. Right that a gap is information; wrong that the way to show it is empty
 * space, because empty space scrolls past with nothing in it and a
 * twenty-minute break and a forty-minute one look like two heights nobody
 * measures. Here every minute of the day is a row somebody can read: the
 * break says how long it is, and so does the lesson on either side of it.
 *
 * It owns its height rather than scrolling inside the calendar page, which is
 * what lets the scroll be magnetic and what lets a row know where it is in the
 * viewport. That is the whole reason the calendar stops being one long column
 * when this view is chosen.
 *
 * Events are rows here whatever «показывать события» says, and that is the one
 * place this view does not follow the calendar's switches. Under the week that
 * setting hides a block of extras; here an event *is* the day — a trip that
 * replaces the third lesson, dropped, would leave the third lesson on screen as
 * if it were going ahead. Homework still honours its switch, because homework
 * is genuinely an extra: it belongs to the day without belonging to a minute
 * of it.
 */
@Composable
internal fun DayRibbonView(
    day: SchoolDay?,
    nowAt: LocalTime?,
    flow: RibbonFlow,
    snap: Boolean,
    depth: Boolean,
    showTeacher: Boolean,
    showHomework: Boolean,
    onLessonClick: (Lesson) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // Hoisted, because the shell's toolbar fades on how far the current screen
    // has scrolled and this list *is* the screen's scroll. A state created here
    // would leave `ReportScrollOffset` with nothing to report and the title
    // opaque over a ribbon four rows down.
    listState: LazyListState = rememberLazyListState(),
    header: @Composable () -> Unit = {},
) {
    val ribbon = remember(day) { ribbonOf(day) }
    val view = rememberHapticView()
    val scope = rememberCoroutineScope()

    val level = rememberRibbonDepthLevel(depth)
    val now = rememberTickingClock(nowAt)
    val focus = remember(ribbon, now) { ribbonFocusIndex(ribbon, now) }

    if (day == null || ribbon.entries.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
            header()
            EmptyState(
                title = correctedString(
                    if (day == null) R.string.week_no_data_title else R.string.week_day_off_title,
                ),
                description = correctedString(
                    if (day == null) {
                        R.string.week_no_data_description
                    } else {
                        R.string.week_day_off_description
                    },
                ),
            )
        }
        return
    }

    // A tick per settled row rather than per frame. `firstVisibleItemIndex`
    // changes once a row has actually taken the top, so the ribbon answers a
    // fling with the same number of taps as the number of rows it passed —
    // which is what makes the scroll feel like it has detents in it even on a
    // phone where the snapping is switched off.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1)
            .collect { LessonsHaptics.tick(view) }
    }

    val edgeHeight = with(LocalDensity.current) { EdgeHeight.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Flipped, not sorted: an entry's index stays its place in the day
            // either way, so «вернуться к текущему» scrolls to the same row
            // whichever way the progress runs. See [RibbonFlow].
            reverseLayout = flow == RibbonFlow.UPWARD,
            flingBehavior = if (snap) {
                rememberSnapFlingBehavior(listState)
            } else {
                ScrollableDefaults.flingBehavior()
            },
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(RowGap),
            // The soft top and bottom, which is where the immersion of this
            // screen actually comes from: rows arrive and leave through a blur
            // instead of being cut off at a line. `progressiveBlur` carries its
            // own ladder — the blur needs Android 13 and is skipped in
            // battery-saver mode and on the Samsung builds it makes flicker,
            // and the gradient is drawn either way — so the edge degrades on
            // exactly the devices the sheen does.
            modifier = Modifier
                .fillMaxSize()
                .progressiveBlur(
                    blurRadius = if (level.drawsShader) EdgeBlurRadius else 0f,
                    topHeight = edgeHeight,
                    bottomHeight = edgeHeight,
                ),
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        // The header rides with the ribbon rather than sitting
                        // above it: pinned, it would eat the height on a phone
                        // held in landscape, where this screen has four rows to
                        // give away and no space to give them from.
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    header()
                }
            }

            itemsIndexed(
                items = ribbon.entries,
                // Keyed on what the row *is* rather than on where it sits: a
                // ribbon rebuilt after a sync keeps each row's identity, so a
                // substitution arriving mid-scroll moves one card instead of
                // restarting every animation on the screen.
                key = { _, entry -> entry.identity() },
            ) { position, entry ->
                // The list's own index, which is the entry's place in the day
                // plus the header above it — and the number `ribbonFocusIndex`
                // is answering in, minus that header.
                val index = position + 1
                RibbonRow(
                    entry = entry,
                    progress = now?.let { progressOf(entry, it) },
                    showTeacher = showTeacher,
                    level = level,
                    index = index,
                    listState = listState,
                    onClick = (entry as? RibbonEntry.OfLesson)?.let { { onLessonClick(it.lesson) } },
                )
            }

            // Homework closes the ribbon rather than sitting on it. It is the
            // only thing about a day that has no time of its own: «Алгебра, до
            // четверга» belongs to a day without belonging to a minute of it,
            // and giving it a row between two lessons would put it at an hour
            // nobody set.
            val homework = if (showHomework) day.homework else emptyList()
            if (homework.isNotEmpty()) {
                item(key = "homework") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SectionHeader(title = correctedString(R.string.schedule_homework))
                        RoundedCardContainer {
                            homework.forEach { item -> HomeworkRow(item = item) }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = ScreenPadding, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallFloatingActionButton(onClick = {
                LessonsHaptics.press(view)
                onSettings()
            }) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = correctedString(R.string.ribbon_settings),
                )
            }

            // Only while the row it would scroll to is off screen. A button that
            // is always there is a button that does nothing half the time, and
            // this one's whole promise is that pressing it moves something.
            val adrift by remember(focus) {
                derivedStateOf {
                    focus != null &&
                        listState.layoutInfo.visibleItemsInfo.none { it.index == focus + 1 }
                }
            }
            AnimatedVisibility(
                visible = adrift,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val target = focus ?: return@ExtendedFloatingActionButton
                        LessonsHaptics.press(view)
                        // +1 for the header, which is item 0 of the list and not
                        // an entry of the day.
                        scope.launch { listState.animateScrollToItem(target + 1) }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MyLocation,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(text = correctedString(R.string.ribbon_return))
                }
            }
        }
    }
}

/**
 * The school's clock, second by second.
 *
 * [nowAt] is minute-true and arrives from the calendar's own thirty-second
 * tick, which is as fine as every other view on that screen needs. A progress
 * bar needs more: one that moves in thirty-second steps does not creep, it
 * lurches. So this counts the seconds locally and is corrected by every value
 * the view model sends, which bounds the drift at one tick and keeps the zone
 * the class's rather than the device's.
 *
 * Null stays null. A date that is not today has no "now" on it, and inventing
 * one here would run a progress bar across yesterday.
 */
@Composable
private fun rememberTickingClock(nowAt: LocalTime?): LocalTime? {
    val ticking by produceState(initialValue = nowAt, key1 = nowAt) {
        var at = nowAt ?: return@produceState
        while (true) {
            value = at
            delay(SecondMillis)
            at = at.plusSeconds(1)
        }
    }
    return ticking
}

/**
 * One entry of the day.
 *
 * Every row carries the same three facts, because the question this screen
 * exists to answer is asked of all of them equally: when it runs, how long it
 * runs for, and where inside it the clock is now. A break is not a lesser row
 * here — «Перемена, 20 минут» is the row somebody plans the next twenty minutes
 * around.
 */
@Composable
private fun RibbonRow(
    entry: RibbonEntry,
    progress: RibbonProgress?,
    showTeacher: Boolean,
    level: RibbonDepthLevel,
    index: Int,
    listState: LazyListState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tone = when (entry) {
        is RibbonEntry.OfLesson -> subjectTone(entry.lesson.subject, entry.lesson.colorHex)
        is RibbonEntry.OfEvent -> accentTone(entry.event.kind.ordinal)
        is RibbonEntry.OfBreak -> neutralTone()
    }

    val running = progress?.isRunning == true
    // Animated rather than set, so a lesson ending while somebody is looking at
    // it hands the highlight over instead of snapping it across the screen.
    val glow by animateFloatAsState(if (running) 1f else 0f, label = "ribbon_glow")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (level.drawsDepth) Modifier.ribbonDepth(index, listState) else Modifier)
            // Under the clip, so the light stops at the card's rounded corners
            // rather than at the square layer behind them.
            .ribbonSheen(
                level = level,
                progress = progress?.fraction ?: 0f,
                tint = tone.content,
                strength = glow,
            )
            .clip(LessonsShapeTokens.Hero)
            .background(
                Brush.verticalGradient(
                    listOf(
                        tone.container,
                        // The second stop is the row's own colour laid over the
                        // page, which is what makes a flat container read as a
                        // surface with a light on it rather than a coloured
                        // rectangle. At glow = 0 the two stops are close enough
                        // to be one colour, which is the point: the running row
                        // is the only one lit.
                        lerp(tone.container, scheme.surface, (0.35f - 0.25f * glow).coerceIn(0f, 1f)),
                    ),
                ),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.width(ClockColumnWidth)) {
                Text(
                    text = entry.startsAt.asClock(),
                    style = MaterialTheme.typography.titleMedium,
                    color = tone.content,
                )
                Text(
                    text = entry.endsAt.asClock(),
                    style = MaterialTheme.typography.labelMedium,
                    color = tone.content.copy(alpha = 0.7f),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(
                    text = entry.title(),
                    style = MaterialTheme.typography.titleMedium,
                    color = tone.content,
                )
                entry.subtitle(showTeacher)?.let { subtitle ->
                    MarqueeText(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = tone.content.copy(alpha = 0.7f),
                    )
                }
            }

            Text(
                text = entry.length.formatLength(),
                style = MaterialTheme.typography.labelLarge,
                color = tone.content,
            )
        }

        if (progress != null) {
            RibbonProgressLine(progress = progress, content = tone.content)
        }
    }
}

/**
 * The bar and the sentence under a row.
 *
 * The sentence changes with the answer rather than always saying all three
 * numbers: before it starts the only true thing is when it will, during it the
 * two halves are what somebody is actually counting, and afterwards nothing is
 * left to say but that it is over. A row that always printed «идёт 0 мин ·
 * осталось 45 мин» about a lesson two hours away would be three facts and one
 * lie.
 */
@Composable
private fun RibbonProgressLine(
    progress: RibbonProgress,
    content: Color,
    modifier: Modifier = Modifier,
) {
    val fraction by animateFloatAsState(progress.fraction, label = "ribbon_progress")

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (progress.isRunning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProgressHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(content.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(ProgressHeight)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(content),
                )
            }
        }

        Text(
            text = when {
                progress.isRunning -> correctedString(
                    R.string.ribbon_running,
                    progress.elapsed.formatLength(),
                    progress.remaining.formatCountdown(),
                )

                progress.isOver -> correctedString(R.string.ribbon_over)
                else -> correctedString(R.string.ribbon_starts_in, progress.remaining.formatCountdown())
            },
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = 0.75f),
        )
    }
}

/**
 * The tilt that makes the ribbon a ribbon rather than a list.
 *
 * Each row asks where it is in the viewport and leans away from the middle,
 * which turns a flat column into something with a near edge and a far one. Read
 * inside [graphicsLayer]'s block rather than outside it, so the answer is taken
 * at draw time: pulling `layoutInfo` into composition would recompose every
 * visible row on every frame of a fling, which is the version of this that
 * makes a mid-range phone drop half its frames.
 */
private fun Modifier.ribbonDepth(index: Int, listState: LazyListState): Modifier = graphicsLayer {
    val layout = listState.layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
    val viewportCentre = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
    val half = ((layout.viewportEndOffset - layout.viewportStartOffset) / 2f).coerceAtLeast(1f)
    val away = ((item.offset + item.size / 2f) - viewportCentre) / half

    val distance = abs(away).coerceIn(0f, 1f)
    scaleX = 1f - DepthScale * distance
    scaleY = 1f - DepthScale * distance
    alpha = 1f - DepthFade * distance
    rotationX = -DepthTilt * away
    // Without it the rotation is an orthographic squash rather than a
    // perspective one, and the row reads as a card being crushed instead of one
    // leaning back.
    cameraDistance = DepthCamera * density
    transformOrigin = TransformOrigin(0.5f, if (away < 0f) 1f else 0f)
}

/**
 * What makes this row this row, for the list's key.
 *
 * The start time and the kind together: two entries can begin at the same
 * minute — an event and the lesson it replaces do, deliberately — and a key
 * that was the time alone would be a duplicate, which a `LazyColumn` answers by
 * throwing rather than by drawing one of them.
 */
private fun RibbonEntry.identity(): String = when (this) {
    is RibbonEntry.OfLesson -> "lesson/${'$'}startsAt/${'$'}{lesson.index}"
    is RibbonEntry.OfEvent -> "event/${'$'}startsAt/${'$'}{event.title}"
    is RibbonEntry.OfBreak -> "break/${'$'}startsAt"
}

/** The row's own words. */
@Composable
private fun RibbonEntry.title(): String = when (this) {
    is RibbonEntry.OfLesson -> lesson.subject
    is RibbonEntry.OfEvent -> event.title
    is RibbonEntry.OfBreak -> correctedString(R.string.ribbon_break)
}

/** The line under the title, or null when there is nothing true to put there. */
@Composable
private fun RibbonEntry.subtitle(showTeacher: Boolean): String? = when (this) {
    is RibbonEntry.OfLesson -> listOfNotNull(
        lesson.room?.takeIf { it.isNotBlank() }
            ?.let { correctedString(R.string.schedule_room_short, it) },
        lesson.teacher?.takeIf { showTeacher && it.isNotBlank() },
        lesson.note?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }

    is RibbonEntry.OfEvent -> event.location?.takeIf { it.isNotBlank() }
    is RibbonEntry.OfBreak -> null
}

private val ClockColumnWidth: Dp = 56.dp
private val EdgeHeight: Dp = 48.dp
private const val EdgeBlurRadius = 12f
private val ProgressHeight: Dp = 6.dp
private val RowGap: Dp = 10.dp
private val GroupGap: Dp = 16.dp

private const val SecondMillis = 1_000L
private const val DepthScale = 0.10f
private const val DepthFade = 0.35f
private const val DepthTilt = 14f
private const val DepthCamera = 14f
