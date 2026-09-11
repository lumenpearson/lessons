package com.lumenpearson.lessons.widget.ui

import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
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
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .cornerRadius(WidgetCardCorner)
            .background(GlanceTheme.colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        content()
    }
}

/** Corner of an inner block; one step tighter than the widget's own surface. */
private val WidgetCardCorner = 18.dp

/** Corner of the pill drawn behind the lesson that is running right now. */
private val CurrentRowCorner = 12.dp

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
            .cornerRadius(CurrentRowCorner)
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
        // Only one trailing detail fits on a phone-width widget, so a substitution
        // notice outranks the room, which outranks the teacher.
        val room = if (options.showRoom) WidgetStrings.room(context, lesson) else null
        val trailing: String? = when {
            // A substitution is the one detail worth the width even here: it is
            // the reason to look at the row at all.
            lesson.isReplaced -> context.getString(R.string.widget_lesson_replaced)
            compact -> null
            room != null -> room
            options.showTeacher -> lesson.teacher?.takeIf { it.isNotBlank() }
            else -> null
        }
        if (trailing != null) {
            HSpace(6)
            CaptionText(text = trailing.ellipsize(14), size = size)
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
    Row(modifier = modifier.fillMaxWidth()) {
        week.forEach { day ->
            val isToday = day.date == today
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .padding(horizontal = 1.dp)
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
                    text = WidgetStrings.shortWeekday(day.date),
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

/** How busy one day of [WeekStrip] is. */
data class DayLoad(val date: LocalDate, val lessons: Int)

private val DayChipCorner = 10.dp
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
            .cornerRadius(CurrentRowCorner)
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
