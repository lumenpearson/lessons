package com.lumenpearson.lessons.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.widget.R
import com.lumenpearson.lessons.widget.WidgetOptions
import com.lumenpearson.lessons.widget.WidgetSizeClass
import com.lumenpearson.lessons.widget.format.WidgetStrings
import com.lumenpearson.lessons.widget.format.ellipsize

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
 */
@Composable
internal fun TimelineRow(
    lesson: Lesson,
    size: WidgetSizeClass,
    options: WidgetOptions,
    isCurrent: Boolean,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    val accent = lessonAccent(lesson.colorHex)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        // Colour the eye can land on before it starts reading: without it every
        // row is the same grey shape and the list has to be read in order.
        Box(
            modifier = GlanceModifier
                .width(3.dp)
                .height(16.dp)
                .cornerRadius(2.dp)
                .background(
                    when {
                        isCurrent -> GlanceTheme.colors.primary
                        accent != null -> accent
                        else -> GlanceTheme.colors.surfaceVariant
                    },
                ),
        ) {}
        HSpace(8)
        Text(
            text = WidgetStrings.time(lesson.startsAt),
            maxLines = 1,
            style = TextStyle(
                color = if (isCurrent) {
                    GlanceTheme.colors.primary
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
                color = GlanceTheme.colors.onSurface,
                fontSize = size.bodySp.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            ),
        )
        // Only one trailing detail fits on a phone-width widget, so a substitution
        // notice outranks the room, which outranks the teacher.
        val room = if (options.showRoom) WidgetStrings.room(context, lesson) else null
        val trailing: String? = when {
            lesson.isReplaced -> context.getString(R.string.widget_lesson_replaced)
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
        text = context
            .getString(R.string.widget_homework_line, subject, text)
            .ellipsize(size.homeworkChars * maxLines),
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
 * A subtle divider. Glance has no `Divider` component in every version, so this
 * is a 1dp [Box] with a background — cheap and version-proof.
 */
@Composable
internal fun ThinDivider(modifier: GlanceModifier = GlanceModifier) {
    // fallback: androidx.glance.appwidget.components.Divider exists in some
    // Glance builds; this hand-rolled one avoids depending on that surface.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GlanceTheme.colors.outline),
    ) {}
}

/**
 * Wraps a hex colour from the timetable into a Glance colour provider.
 *
 * Returns null on anything unparseable: a malformed colour from the server must
 * never take the widget down, and falling back to the theme accent is invisible
 * to the user.
 */
internal fun lessonAccent(colorHex: String?): ColorProvider? {
    val hex = colorHex?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        // fallback: androidx.glance.unit.ColorProvider(Color) is the documented
        // factory; androidx.glance.color.ColorProvider(day, night) is the newer
        // two-tone one if this overload ever disappears.
        ColorProvider(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(hex)))
    }.getOrNull()
}
