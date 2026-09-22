package com.lumenpearson.lessons.widget.ui

import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.lumenpearson.lessons.core.designsystem.theme.AccentMath
import com.lumenpearson.lessons.core.designsystem.theme.concentricCorner
import com.lumenpearson.lessons.core.designsystem.theme.parseSubjectColor
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.WidgetOptions
import com.lumenpearson.lessons.widget.WidgetSizeClass
import com.lumenpearson.lessons.widget.format.WidgetStrings
import com.lumenpearson.lessons.widget.format.ellipsize
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The small building blocks every size class shares.
 *
 * Glance cannot use Compose Material3 — its composables emit `RemoteViews`, not
 * `LayoutNode`s — so none of `:core:designsystem`'s components are reachable
 * here and the widget has to own a miniature design system of its own. Colours
 * still come from [GlanceTheme] rather than the design system's palette, because
 * on API 31+ that is the system's Material You palette derived from the user's
 * wallpaper, which is what a home-screen widget is supposed to look like.
 */

/** The state word above the subject: small, bold, accent-coloured. */
@Composable
internal fun StateLabel(
    text: String,
    size: WidgetSizeClass,
    urgent: Boolean = false,
    // Upper case is right for a one-word state ("УРОК") and wrong for a sentence
    // ("ДЗ НА ЗАВТРА" reads as shouting), so the caller decides.
    uppercase: Boolean = true,
    modifier: GlanceModifier = GlanceModifier,
) {
    Text(
        text = if (uppercase) text.uppercase() else text,
        maxLines = 1,
        modifier = modifier,
        style = TextStyle(
            // Urgent states borrow the error role rather than a hardcoded red so
            // they still contrast correctly under a dark or a monochrome theme.
            color = if (urgent) GlanceTheme.colors.error else GlanceTheme.colors.primary,
            fontSize = size.captionSp.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/** The subject or event title — the largest thing on the widget. */
@Composable
internal fun SubjectText(
    text: String,
    size: WidgetSizeClass,
    maxLines: Int,
    modifier: GlanceModifier = GlanceModifier,
) {
    Text(
        text = text,
        maxLines = maxLines,
        modifier = modifier,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = size.titleSp.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

/** Secondary line: countdowns, room numbers, "Дальше". */
@Composable
internal fun CaptionText(
    text: String,
    size: WidgetSizeClass,
    modifier: GlanceModifier = GlanceModifier,
    emphasised: Boolean = false,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        maxLines = maxLines,
        modifier = modifier,
        style = TextStyle(
            color = if (emphasised) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
            fontSize = size.captionSp.sp,
            fontWeight = if (emphasised) FontWeight.Medium else FontWeight.Normal,
        ),
    )
}

/** Body line: homework text and timeline rows. */
@Composable
internal fun BodyText(
    text: String,
    size: WidgetSizeClass,
    modifier: GlanceModifier = GlanceModifier,
    maxLines: Int = 1,
    muted: Boolean = false,
) {
    Text(
        text = text,
        maxLines = maxLines,
        modifier = modifier,
        style = TextStyle(
            color = if (muted) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
            fontSize = size.bodySp.sp,
            fontWeight = FontWeight.Normal,
        ),
    )
}

/**
 * Progress through the running lesson, break or event.
 *
 * A number of minutes is precise; a bar is *glanceable*, which is the whole
 * point of a home-screen widget — you should be able to tell "nearly over" from
 * "just started" without reading.
 */
@Composable
internal fun StateProgress(
    progress: Float,
    modifier: GlanceModifier = GlanceModifier,
) {
    // fallback: if LinearProgressIndicator is unavailable in this Glance build,
    // draw a Row of two Boxes inside a fixed-width parent and set their widths to
    // (available * progress) / (available * (1 - progress)) — Glance has no
    // fractional weight, so the dp arithmetic has to be done from LocalSize.
    LinearProgressIndicator(
        progress = progress.coerceIn(0f, 1f),
        modifier = modifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
        color = GlanceTheme.colors.primary,
        backgroundColor = GlanceTheme.colors.surfaceVariant,
    )
}

/**
 * A block of the widget, as one rounded container.
 *
 * The widget's answer to `RoundedCardContainer` in `:core:designsystem`: the
 * same idea Essentials builds every screen from — group related lines inside one
 * clipped block rather than separating them with rules. Glance has no clip, so
 * the corner radius and the fill are set on the box itself, which comes to the
 * same thing for a container whose children are plain text.
 */
@Composable
internal fun WidgetCard(
    size: WidgetSizeClass,
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .cornerRadius(size.innerCorner())
            .background(GlanceTheme.colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        content()
    }
}

/**
 * The corner of anything drawn directly inside the widget's own surface.
 *
 * Derived rather than declared, and the reason is the twelve size classes. This
 * was a flat 18 dp — «one step tighter than the surface» — against a surface of
 * [WidgetSurfaceCorner] and a padding that is **not** one number: it runs from
 * 8 dp on the smallest widget to 16 dp on the largest. So the gap between the
 * two curves was right nowhere and worst where the widget is biggest: at 16 dp
 * of padding a concentric block wants 8 dp and it was drawing 18, more than
 * twice as round as the surface around it can carry.
 *
 * The floor keeps a block from going square on the widest sizes. A square block
 * inside a rounded surface is defensible on its own and wrong here, because the
 * rows beside it are rounded: one square corner among them reads as a rendering
 * fault rather than as a decision.
 *
 * Glance cannot take a `Shape`, only a [Dp] — which is why this is the
 * arithmetic form of the rule and not `ConcentricShape`.
 */
internal fun WidgetSizeClass.innerCorner(): Dp = innerCornerFor(paddingDp)

/**
 * [innerCorner] with the padding as a parameter, which is the only way to test it.
 *
 * Every rung on the ladder pads 16 dp or less, so none of the twelve can reach
 * the floor — and a test that walks the rungs therefore cannot tell this
 * expression from the same one with `minimum =` deleted. A first attempt at
 * that test kept its own copy of the arithmetic and stayed green against a
 * build with the argument dropped, which is a test of the test rather than of
 * the code. Splitting the function is what lets one be called with a padding
 * the enum does not have.
 */
internal fun innerCornerFor(paddingDp: Float): Dp =
    concentricCorner(WidgetSurfaceCorner, paddingDp.dp, minimum = InnerCornerFloor)

/**
 * The widget's own corner.
 *
 * 24 dp, the same radius `RoundedCardContainer` gives a group of rows in the
 * app, so the widget reads as one more block of the same design system. On API
 * 31+ it also sits close to the launcher's own widget rounding; below that
 * `cornerRadius` is a no-op on the surface and the launcher supplies square
 * edges, which is what pre-Material-You launchers draw anyway.
 *
 * Here rather than in `LessonsWidgetBody`, because it is now half of an
 * arithmetic the blocks inside depend on: two numbers that have to agree belong
 * in one place.
 */
internal val WidgetSurfaceCorner = 24.dp

/**
 * See [innerCorner]; 6 dp is the tightest corner this design language uses.
 *
 * `internal` rather than private so a test can assert the floor is reached at a
 * padding no rung has: every rung pads 16 dp or less, so walking the ladder
 * cannot tell a floored expression from an unfloored one.
 */
internal val InnerCornerFloor = 6.dp

/**
 * The colour mark at the head of a timeline row.
 *
 * Wider than it was. Three pixels of a muted grey was, in practice, invisible;
 * now that the bar actually carries the subject's hue it is worth seeing.
 */
private val AccentBarWidth = 4.dp
private val AccentBarHeight = 18.dp

/** Vertical rhythm helper, so the spacing constants live in one place. */
@Composable
internal fun VSpace(dp: Int) {
    Spacer(GlanceModifier.height(dp.dp))
}

/** Horizontal rhythm helper. */
@Composable
internal fun HSpace(dp: Int) {
    Spacer(GlanceModifier.width(dp.dp))
}

/**
 * The one thing a timeline row has room for after the subject.
 *
 * Pure, and outside the composable, because it is the half that can be wrong and
 * a Glance composable cannot be asked anything without a launcher.
 */
internal enum class RowDetail { REPLACED, ROOM, TEACHER, NONE }

/**
 * Which of them [lesson] gets, given what the reader asked for.
 *
 * The room and the teacher are one setting — only one of the two fits a
 * phone-width row, so `LessonsWidget` turns «Показывать учителя» on and the room
 * off together. The fallback is the part that had to be written down: a
 * timetable carrying a room and no teacher is the ordinary case (the teacher is
 * optional in the paste grammar and in a substitution), and with the teacher chosen
 * the row dropped the room for a name that was not there — an empty slot beside
 * a cache that held the answer.
 */
internal fun rowDetailFor(lesson: Lesson, options: WidgetOptions, compact: Boolean): RowDetail {
    val hasRoom = !lesson.room.isNullOrBlank()
    val hasTeacher = !lesson.teacher.isNullOrBlank()
    return when {
        // A substitution is the reason to look at the row at all.
        lesson.isReplaced -> RowDetail.REPLACED
        compact -> RowDetail.NONE
        options.showTeacher && hasTeacher -> RowDetail.TEACHER
        (options.showRoom || options.showTeacher) && hasRoom -> RowDetail.ROOM
        else -> RowDetail.NONE
    }
}

/**
 * One line of the remaining-day timeline: "▍08:30  Алгебра  каб. 214".
 *
 * [WidgetStrings.time] is always zero-padded to five characters, so the times
 * line up on their own advance width. They used to be forced into a 44dp column
 * instead, which truncated "09:00" to "09:…" as soon as the reader had enlarged
 * their system font, and left no gap at all before the subject when the text
 * filled the column exactly.
 *
 * @param isCurrent draws the row in the accent colour — this is the lesson the
 *   student is sitting in right now.
 * @param compact drops the trailing detail. A 110 dp column holds a time and a
 *   subject and nothing else; a room number there costs the subject its last
 *   five characters, and the subject is the half worth reading.
 */
@Composable
internal fun TimelineRow(
    lesson: Lesson,
    size: WidgetSizeClass,
    options: WidgetOptions,
    isCurrent: Boolean,
    compact: Boolean = false,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    val accent = subjectAccent(lesson.subject, lesson.colorHex)
    // The running lesson is filled rather than merely tinted — the same
    // inversion the floating toolbar uses for the selected tab, so "you are
    // here" reads the same way in the app and on the home screen.
    val rowModifier = if (isCurrent) {
        modifier
            .fillMaxWidth()
            // The same derivation as every other block on the surface: this pill
            // is inset by the widget's own padding and by nothing else.
            .cornerRadius(size.innerCorner())
            .background(GlanceTheme.colors.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    } else {
        modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        // Colour the eye can land on before it starts reading: without it every
        // row is the same grey shape and the list has to be read in order.
        Box(
            modifier = GlanceModifier
                .width(AccentBarWidth)
                .height(AccentBarHeight)
                .cornerRadius(2.dp)
                .background(
                    if (isCurrent) GlanceTheme.colors.onPrimaryContainer else accent,
                ),
        ) {}
        HSpace(8)
        Text(
            text = WidgetStrings.time(lesson.startsAt),
            maxLines = 1,
            style = TextStyle(
                color = if (isCurrent) {
                    GlanceTheme.colors.onPrimaryContainer
                } else {
                    GlanceTheme.colors.onSurfaceVariant
                },
                fontSize = size.bodySp.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            ),
        )
        HSpace(8)
        Text(
            text = lesson.subject.ellipsize(size.homeworkChars.coerceAtLeast(12)),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = if (isCurrent) {
                    GlanceTheme.colors.onPrimaryContainer
                } else {
                    GlanceTheme.colors.onSurface
                },
                fontSize = size.bodySp.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            ),
        )
        // Only one trailing detail fits on a phone-width widget; `rowDetailFor`
        // decides which, and this turns the answer into words.
        val trailing: String? = when (rowDetailFor(lesson, options, compact)) {
            RowDetail.REPLACED -> context.getString(R.string.widget_lesson_replaced)
            RowDetail.ROOM -> WidgetStrings.room(context, lesson)
            RowDetail.TEACHER -> lesson.teacher
            RowDetail.NONE -> null
        }
        if (trailing != null) {
            HSpace(6)
            // Weighted, and right-aligned inside its own share, rather than
            // sitting at whatever width the string happens to want.
            //
            // A `LinearLayout` measures its unweighted children first and hands
            // the *remainder* to the weighted ones — so a trailing detail with
            // no weight took the width it asked for and the subject, which has
            // the weight, got what was left. At the system's largest font that
            // remainder reached zero: «10:50 Надежда Петро.» drew a teacher and
            // no lesson, and the last row of the list came out as a bare colour
            // mark with nothing beside it at all. The subject is the half the
            // row exists for; it cannot be the half that loses.
            //
            // Two weights split the remainder instead, and because the box is
            // end-aligned the ordinary case is drawn exactly as before: the
            // detail still ends at the right edge, it simply can no longer
            // start further left than the middle.
            Box(
                modifier = GlanceModifier.defaultWeight(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                CaptionText(text = trailing.ellipsize(14), size = size)
            }
        }
    }
}

/**
 * One homework line: "Алгебра: §12, № 3–5", truncated to the size class's budget.
 *
 * Subject and text share a single [Text] rather than two, because a two-`Text`
 * row would let a long subject squeeze the text down to nothing on the SMALL
 * size; a single truncated string always spends its characters left to right.
 */
@Composable
internal fun HomeworkRow(
    subject: String,
    text: String,
    size: WidgetSizeClass,
    modifier: GlanceModifier = GlanceModifier,
    maxLines: Int = 1,
) {
    val context = LocalContext.current
    BodyText(
        // Through the formatter rather than the raw string: homework is
        // free-form teacher input and regularly carries a newline, and a Glance
        // Text at maxLines = 1 shows what is before it and silently drops the
        // rest with no ellipsis. `homeworkLine` exists for exactly this and was
        // not being called from anywhere.
        text = WidgetStrings.homeworkLine(
            context = context,
            subject = subject,
            text = text,
            maxChars = size.homeworkChars * maxLines,
        ),
        size = size,
        maxLines = maxLines,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A section heading inside the larger layouts ("Осталось сегодня", "Дальше").
 */
@Composable
internal fun SectionTitle(text: String, size: WidgetSizeClass) {
    Text(
        text = text.uppercase(),
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = (size.captionSp - 1f).sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

/**
 * The school week, as seven chips.
 *
 * As close to "scrollable weekdays" as a home-screen widget gets. `RemoteViews`
 * has no horizontally scrolling container — the platform offers `ListView`,
 * `GridView` and `StackView` and every one of them scrolls vertically — so the
 * week is fitted rather than scrolled: seven fixed columns, each carrying the
 * weekday letter, the date and a dot per lesson.
 *
 * Every chip is a link into the app on that day, which is the other half of what
 * scrolling would have been for. Tapping the widget already opened the app; now
 * it can open it somewhere.
 *
 * @param week seven days starting Monday, in order.
 */
@Composable
internal fun WeekStrip(
    week: List<DayLoad>,
    today: LocalDate,
    size: WidgetSizeClass,
    openDay: (LocalDate) -> Action,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    Row(modifier = modifier.fillMaxWidth()) {
        // `take` although `snapshotOf` always builds exactly seven: this is the
        // one variable-length list in the widget that was not clamped, and
        // Glance keeps the first ten children of a container and drops the rest
        // in silence — so the failure mode of an eighth day is Sunday going
        // missing with nothing logged.
        week.take(CHILD_LIMIT).forEach { day ->
            val isToday = day.date == today
            // Two boxes rather than one, and the outer one is the whole point.
            //
            // Compose reads a modifier chain in order, so `padding` before
            // `background` is margin and after it is inset. **Glance does not.**
            // `applyModifiers` folds every `PaddingModifier` in the chain into a
            // single `setViewPadding` on the same view the background and the
            // corner radius are applied to, so position in the chain is thrown
            // away — and the 1 dp written here as a gap between the chips was
            // being drawn *inside* their own colour. The seven chips met edge to
            // edge and the week read as one grey bar with a coloured segment in
            // it rather than as seven days.
            //
            // A `Spacer` between them would be the Compose answer and is the
            // wrong one here: seven chips and six spacers is thirteen children
            // of a container that keeps ten.
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .padding(horizontal = DayChipGap),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(DayChipCorner)
                        .background(
                            if (isToday) {
                                GlanceTheme.colors.primaryContainer
                            } else {
                                GlanceTheme.colors.surfaceVariant
                            },
                        )
                        .clickable(openDay(day.date))
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                ) {
                    val content = if (isToday) {
                        GlanceTheme.colors.onPrimaryContainer
                    } else {
                        GlanceTheme.colors.onSurfaceVariant
                    }
                    Text(
                        text = WidgetStrings.shortWeekday(context, day.date),
                        maxLines = 1,
                        style = TextStyle(color = content, fontSize = (size.captionSp - 1f).sp),
                    )
                    Text(
                        text = day.date.dayOfMonth.toString(),
                        maxLines = 1,
                        style = TextStyle(
                            color = content,
                            fontSize = size.bodySp.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        ),
                    )
                    Text(
                        // Dots rather than a number: how busy a day is only has three
                        // useful answers at this size, and a digit would be read while
                        // a row of dots is seen.
                        text = LoadDot.repeat(day.lessons.coerceAtMost(MaxLoadDots)),
                        maxLines = 1,
                        style = TextStyle(color = content, fontSize = (size.captionSp - 2f).sp),
                    )
                }
            }
        }
    }
}

/** How busy one day of [WeekStrip] is. */
data class DayLoad(val date: LocalDate, val lessons: Int)

/**
 * Deliberately a constant, and deliberately not [innerCorner].
 *
 * The concentric rule applies to a block whose corner nests inside the
 * surface's corner, and none of the seven chips has one: the strip sits in the
 * middle of the layout, so its corners are next to other rows rather than to
 * the surface's rounding, and a chip is about as tall as the radius the rule
 * would hand it — which is a pill, not a chip.
 */
private val DayChipCorner = 10.dp

/**
 * The gap between two weekday chips, carried by a box of its own.
 *
 * 1 dp each side, so 2 dp between neighbours — enough to read as seven chips
 * rather than one bar, and little enough that seven of them still divide a
 * 110 dp column evenly. It cannot live on the chip's own modifier: see
 * [WeekStrip] for what Glance does with a padding written there.
 */
private val DayChipGap = 1.dp
private const val LoadDot = "·"
private const val MaxLoadDots = 3

/**
 * One event on the timeline.
 *
 * Deliberately not a lesson row with different words. An event is what happens
 * *instead of* what the timetable says, so it is drawn as a filled chip rather
 * than a row with a coloured tick — the difference has to survive being glanced
 * at from across a room, which is the only way a home screen is ever read.
 */
@Composable
internal fun EventRow(
    event: SchoolEvent,
    size: WidgetSizeClass,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .cornerRadius(size.innerCorner())
            .background(GlanceTheme.colors.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = WidgetStrings.time(event.startsAt),
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSecondaryContainer,
                fontSize = size.bodySp.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        HSpace(8)
        Text(
            text = event.title.ellipsize(EventTitleChars),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = GlanceTheme.colors.onSecondaryContainer,
                fontSize = size.bodySp.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = WidgetStrings.eventLabel(context, event.kind),
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSecondaryContainer,
                fontSize = size.captionSp.sp,
            ),
        )
    }
}

/** Longest an event title may be before the kind label loses its room. */
private const val EventTitleChars = 18

/**
 * The colour of a subject, the same one the app gives it.
 *
 * The widget used to colour a row only when the server had sent a `colorHex` for
 * it, and no school sends one, so every row of the timeline fell back to the
 * same flat `surfaceVariant` — a list that had to be read in order because
 * nothing in it could be recognised at a glance. The hue now comes from the
 * subject name through the same [AccentMath] the app uses, so «Алгебра» is the
 * same green in the timetable, in the homework list and on the home screen.
 *
 * A colour the school *did* send still wins, but only its hue: re-derived
 * through the same formula rather than used raw, because one school's saturated
 * blue next to five soft pastels reads as a rendering fault.
 */
@Composable
internal fun subjectAccent(subject: String, colorHex: String?): ColorProvider {
    val context = LocalContext.current
    val primary = GlanceTheme.colors.primary.getColor(context)
    val background = GlanceTheme.colors.widgetBackground.getColor(context)
    val dark = AccentMath.brightnessOf(background) < 0.5f
    val explicit = parseSubjectColor(colorHex)
    val hue = if (explicit != null) {
        AccentMath.hueOf(explicit)
    } else {
        AccentMath.hueFor(subject, AccentMath.hueOf(primary))
    }
    return ColorProvider(AccentMath.mark(hue, dark))
}

/**
 * A countdown that actually counts down.
 *
 * Every other number on the widget is a snapshot: the process wakes on an alarm,
 * re-renders, and goes back to sleep, so "осталось 12 мин" stays 12 until the
 * next wake. A bell is the one thing where the seconds matter, and an alarm per
 * second is not something the platform will deliver.
 *
 * `Chronometer` solves it in the launcher's own process — it is given a moment
 * to count to and redraws itself once a second, forever, at no cost to us.
 * Glance has no such element, so it arrives as [AndroidRemoteViews]; that is the
 * documented way to put a plain `RemoteViews` inside a Glance tree.
 *
 * Two things about it are load-bearing and were both wrong:
 *
 *  * **It wraps its content.** An `AndroidRemoteViews` with no size modifier is
 *    laid out as "fill", so in a row beside a weighted state label it took the
 *    whole width and the label was measured at zero — which is why the widget
 *    showed a bare "05:57" and never once said "ПЕРЕМЕНА" beside it.
 *  * **It is labelled.** A `Chronometer` can only draw digits, and in count-down
 *    mode under an hour those digits are `MM:SS`. "05:57" over a subject reads
 *    as five minutes to six, not as six minutes of break left. The word goes
 *    beside it, from the state, because Russian and English disagree about
 *    which side of the number it belongs on.
 *
 * @param endsAt when the thing being counted ends, in the school's wall time.
 * @param now the same wall time the rest of this render used, so the offset from
 *   the device's own clock is applied once and consistently.
 * @param label "до звонка" / "до начала", or null where there is no room for it.
 */
@Composable
internal fun LiveCountdown(
    endsAt: LocalDateTime,
    now: LocalDateTime,
    size: WidgetSizeClass,
    urgent: Boolean,
    label: String? = null,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    val color = if (urgent) GlanceTheme.colors.error else GlanceTheme.colors.onSurface
    val remaining = Duration.between(now, endsAt).toMillis().coerceAtLeast(0L)

    val views = RemoteViews(context.packageName, R.layout.widget_countdown).apply {
        // Base is on the elapsed-realtime clock, which is what Chronometer
        // counts against — wall time would drift the moment the user changed
        // the clock or crossed a timezone.
        setChronometer(
            R.id.widget_countdown,
            SystemClock.elapsedRealtime() + remaining,
            null,
            true,
        )
        setChronometerCountDown(R.id.widget_countdown, true)
        setTextColor(R.id.widget_countdown, color.getColor(context).toArgb())
        setTextViewTextSize(R.id.widget_countdown, TypedValue.COMPLEX_UNIT_SP, size.captionSp + 2f)
    }

    Row(
        modifier = modifier.wrapContentSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (label != null) {
            CaptionText(text = label, size = size)
            HSpace(5)
        }
        AndroidRemoteViews(
            remoteViews = views,
            modifier = GlanceModifier.wrapContentSize(),
        )
    }
}

/**
 * "Дальше 12:45 · Вероятность" — the one line that answers the second question.
 *
 * A line rather than a block: on the sizes that show it there is no room for a
 * heading and a row, and the heading would be the half that carries no
 * information.
 */
@Composable
internal fun NextUpLine(
    at: LocalTime,
    subject: String,
    size: WidgetSizeClass,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        CaptionText(text = context.getString(R.string.widget_next_up), size = size)
        HSpace(6)
        BodyText(
            text = "${WidgetStrings.time(at)}  ${subject.ellipsize(NextUpSubjectChars)}",
            size = size,
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

/** Longest a "Дальше" subject may be on the narrowest size that draws one. */
private const val NextUpSubjectChars = 14
