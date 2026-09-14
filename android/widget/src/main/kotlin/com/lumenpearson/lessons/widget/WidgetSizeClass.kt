package com.lumenpearson.lessons.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The layouts the widget can be, and the content budget of each.
 *
 * ### Why a ladder rather than a fluid layout
 *
 * Glance renders through `RemoteViews`. There is no measure pass we can react
 * to, no `onSizeChanged`, no text auto-sizing — the only size information
 * available is `LocalSize.current`, which under [androidx.glance.appwidget
 * .SizeMode.Responsive] reports whichever declared breakpoint the launcher
 * matched. So "resizable to any size" has to be implemented as a small number of
 * layouts that each look deliberate, with the launcher snapping between them.
 *
 * ### What each rung is for
 *
 * A class earns its place by changing what the widget can *say*, and the ladder
 * has to cover every shape a user can actually drag out — a surplus no class
 * claims is drawn as empty background, which is what a widget two cells wide and
 * five tall used to be: the 110 × 110 layout, three lines of text, and four rows
 * of nothing under it.
 *
 * | Class        | Breakpoint  | Cells | What it adds                          |
 * |--------------|-------------|-------|---------------------------------------|
 * | [TINY]       | 110 × 40dp  | 2×1   | state word + how long left            |
 * | [WIDE]       | 250 × 60dp  | 4×1   | + the subject, on the same line       |
 * | [SMALL]      | 110 × 110dp | 2×2   | + progress and the length of it       |
 * | [SMALL_TALL] | 110 × 190dp | 2×3   | + what is next, + homework count      |
 * | [NARROW]     | 110 × 300dp | 2×5   | + the rest of the day, narrow rows    |
 * | [MEDIUM]     | 250 × 110dp | 4×2   | + a "Дальше" column                   |
 * | [MEDIUM_TALL]| 250 × 180dp | 4×3   | + the rest of the day                 |
 * | [LARGE]      | 250 × 250dp | 4×4   | + the week strip                      |
 * | [LARGE_TALL] | 250 × 400dp | 4×6   | + homework, in a four-cell column     |
 * | [XLARGE]     | 320 × 320dp | 5×5   | + homework                            |
 * | [TALL]       | 320 × 400dp | 5×6   | + the next school day and its homework|
 * | [HUGE]       | 320 × 560dp | 5×8   | + more of all of it                   |
 *
 * The widths are the three that matter: 110dp is two cells on a typical 4- or
 * 5-column launcher grid, 250dp is four, 320dp is five or a tablet column. The
 * heights step 40 → 60 → 110 → 190 → 250 → 300 → 320 → 400 → 560 because those
 * are roughly one through eight rows.
 *
 * ### How a real size becomes a rung, and why it matters which rungs exist
 *
 * Not "the largest breakpoint that fits", which is what this comment used to
 * say. Both the framework (API 31+, `RemoteViews` with a size map) and Glance's
 * own `findBestSize` keep the breakpoints that fit inside the real size and then
 * take the one at the smallest *squared distance* from it — nearest, not
 * largest. A rung missing from one branch of the ladder is therefore not merely
 * an unused size: it hands its widgets to a rung on another branch. With nothing
 * above [LARGE] at 250dp wide, a four-cell widget taller than about 471dp was
 * closer to [NARROW]'s 110 × 300 than to [LARGE]'s 250 × 250 — so the largest
 * widget on a four-column home screen drew the two-cell column layout: no week
 * strip, five timeline rows, and homework clipped to the 24 characters that fit
 * in 110dp, in more than twice that width. [LARGE_TALL] is the rung that band
 * was missing.
 *
 * 40dp is the floor because it is the smallest height in which a 13sp label and
 * a 13sp countdown still clear the launcher's own widget padding.
 *
 * @property breakpoint the size handed to `SizeMode.Responsive`.
 * @property timelineRows how many lessons the rest-of-day list shows.
 * @property homeworkItems how many homework subjects a homework block shows.
 * @property homeworkChars per-subject character budget before truncation.
 * @property showsSubject whether the subject gets a line of its own.
 * @property showsMeta whether there is room for "каб. 30 · из 40 мин".
 * @property showsProgressBar whether there is vertical room for a progress bar.
 * @property showsNextUp whether "what comes after this" is worth a line.
 * @property showsTodayHomework whether today's own homework gets a line.
 * @property showsWeekStrip whether the seven day chips fit.
 * @property showsHomework whether a homework block is drawn beside the timeline.
 * @property showsNextDay whether the next school day gets a block of its own.
 * @property titleSp size of the subject line.
 * @property bodySp size of list rows.
 * @property captionSp size of the state label and countdown.
 * @property paddingDp inner padding; small sizes cannot afford much.
 */
enum class WidgetSizeClass(
    val breakpoint: DpSize,
    val timelineRows: Int,
    val homeworkItems: Int,
    val homeworkChars: Int,
    val showsSubject: Boolean,
    val showsMeta: Boolean,
    val showsProgressBar: Boolean,
    val showsNextUp: Boolean,
    val showsTodayHomework: Boolean,
    val showsWeekStrip: Boolean,
    val showsHomework: Boolean,
    val showsNextDay: Boolean,
    val titleSp: Float,
    val bodySp: Float,
    val captionSp: Float,
    val paddingDp: Float,
) {
    /**
     * One line. After school this degrades to "ДЗ на завтра · 5 предметов",
     * which is still a useful answer — the count tells the user whether opening
     * the app is worth it.
     */
    TINY(
        breakpoint = DpSize(110.dp, 40.dp),
        timelineRows = 0,
        homeworkItems = 0,
        homeworkChars = 0,
        showsSubject = false,
        showsMeta = false,
        showsProgressBar = false,
        showsNextUp = false,
        showsTodayHomework = false,
        showsWeekStrip = false,
        showsHomework = false,
        showsNextDay = false,
        titleSp = 13f,
        bodySp = 12f,
        captionSp = 12f,
        paddingDp = 8f,
    ),

    /**
     * Wide and one row tall.
     *
     * Without this rung a widget four cells wide and one cell high matched
     * [TINY], whose whole design assumes 110 dp of width — so two thirds of the
     * surface was background. The extra width buys the subject, which is the
     * word the user came for.
     */
    WIDE(
        breakpoint = DpSize(250.dp, 60.dp),
        timelineRows = 0,
        homeworkItems = 0,
        homeworkChars = 0,
        showsSubject = true,
        showsMeta = false,
        showsProgressBar = false,
        showsNextUp = false,
        showsTodayHomework = false,
        showsWeekStrip = false,
        showsHomework = false,
        showsNextDay = false,
        titleSp = 16f,
        bodySp = 13f,
        captionSp = 12f,
        paddingDp = 10f,
    ),

    /** The square 2×2 most launchers default to when a user drags from the picker. */
    SMALL(
        breakpoint = DpSize(110.dp, 110.dp),
        timelineRows = 0,
        homeworkItems = 2,
        homeworkChars = 24,
        showsSubject = true,
        showsMeta = false,
        showsProgressBar = true,
        showsNextUp = false,
        showsTodayHomework = false,
        showsWeekStrip = false,
        showsHomework = false,
        showsNextDay = false,
        titleSp = 16f,
        bodySp = 12f,
        captionSp = 11f,
        paddingDp = 10f,
    ),

    /**
     * Two cells wide, three tall — the shape a narrow column of a home screen
     * ends up as.
     *
     * It used to match [SMALL] and leave a third of itself empty. The height
     * buys the two lines a pupil asks for next: what comes after this, and
     * whether anything is set for the next school day.
     */
    SMALL_TALL(
        breakpoint = DpSize(110.dp, 190.dp),
        timelineRows = 0,
        homeworkItems = 2,
        homeworkChars = 24,
        showsSubject = true,
        showsMeta = true,
        showsProgressBar = true,
        showsNextUp = true,
        showsTodayHomework = true,
        showsWeekStrip = false,
        showsHomework = false,
        showsNextDay = false,
        titleSp = 17f,
        bodySp = 12f,
        captionSp = 11f,
        paddingDp = 10f,
    ),

    /**
     * A full-height narrow column.
     *
     * The same width as [SMALL] and five times the height, which before this
     * rung existed was five times the same three lines of text. Narrow rows —
     * time and subject, no room, no teacher — are what fits in 110 dp.
     */
    NARROW(
        breakpoint = DpSize(110.dp, 300.dp),
        timelineRows = 5,
        homeworkItems = 3,
        homeworkChars = 24,
        showsSubject = true,
        showsMeta = true,
        showsProgressBar = true,
        showsNextUp = false,
        showsTodayHomework = true,
        showsWeekStrip = false,
        showsHomework = true,
        showsNextDay = false,
        titleSp = 17f,
        bodySp = 12f,
        captionSp = 11f,
        paddingDp = 10f,
    ),

    /** The drop target from `targetCellWidth/Height`: wide enough for a second column. */
    MEDIUM(
        breakpoint = DpSize(250.dp, 110.dp),
        timelineRows = 2,
        homeworkItems = 3,
        homeworkChars = 40,
        showsSubject = true,
        showsMeta = false,
        showsProgressBar = true,
        showsNextUp = true,
        showsTodayHomework = false,
        showsWeekStrip = false,
        showsHomework = false,
        showsNextDay = false,
        titleSp = 19f,
        bodySp = 13f,
        captionSp = 12f,
        paddingDp = 12f,
    ),

    /**
     * Between [MEDIUM] and [LARGE], which is where a lot of widgets actually sit.
     *
     * The height ladder stepped 110 → 250, so every widget in between drew the
     * 110 dp layout and left up to 140 dp of empty background under it — the
     * single most visible thing wrong with the widget. This rung takes that band
     * and spends it on the rows [MEDIUM] had no room for.
     */
    MEDIUM_TALL(
        breakpoint = DpSize(250.dp, 180.dp),
        timelineRows = 3,
        homeworkItems = 4,
        homeworkChars = 48,
        showsSubject = true,
        showsMeta = true,
        showsProgressBar = true,
        showsNextUp = false,
        showsTodayHomework = true,
        showsWeekStrip = false,
        showsHomework = false,
        showsNextDay = false,
        titleSp = 20f,
        bodySp = 13f,
        captionSp = 12f,
        paddingDp = 12f,
    ),

    /** Four rows: the whole rest of the school day fits, with the week above it. */
    LARGE(
        breakpoint = DpSize(250.dp, 250.dp),
        timelineRows = 6,
        homeworkItems = 5,
        homeworkChars = 56,
        showsSubject = true,
        showsMeta = true,
        showsProgressBar = true,
        showsNextUp = false,
        showsTodayHomework = true,
        showsWeekStrip = true,
        showsHomework = false,
        showsNextDay = false,
        titleSp = 21f,
        bodySp = 14f,
        captionSp = 12f,
        paddingDp = 14f,
    ),

    /**
     * Four cells wide and three or more rows tall.
     *
     * Everything [LARGE] says plus the homework block, which is what the extra
     * height is for. Narrower rows than [XLARGE], because 250dp is 70dp less to
     * spend on a subject and a room number.
     *
     * **The 300dp threshold is where the ladder's one real inversion was.** It
     * used to be 400, so everything from 250dp to 399dp tall fell to [LARGE],
     * whose homework block is off — and a widget 250 wide and 300 tall
     * therefore drew *less* than the same widget 110 wide, which lands on
     * [NARROW] and has homework on. Wider and taller, and the homework block
     * disappeared. Fixed from this end rather than by turning homework on in
     * [LARGE], because [LARGE] is reached at 250dp of height and that is 50dp
     * less than [NARROW] has for a shorter list: the block had to go where
     * there is room for it, not where the inversion was noticed.
     */
    LARGE_TALL(
        breakpoint = DpSize(250.dp, 300.dp),
        timelineRows = 6,
        homeworkItems = 5,
        homeworkChars = 56,
        showsSubject = true,
        showsMeta = true,
        showsProgressBar = true,
        showsNextUp = false,
        showsTodayHomework = true,
        showsWeekStrip = true,
        showsHomework = true,
        showsNextDay = false,
        titleSp = 21f,
        bodySp = 14f,
        captionSp = 12f,
        paddingDp = 14f,
    ),

    /** Timeline *and* homework at once — the "I live on my home screen" size. */
    XLARGE(
        breakpoint = DpSize(320.dp, 320.dp),
        timelineRows = 6,
        homeworkItems = 5,
        homeworkChars = 72,
        showsSubject = true,
        showsMeta = true,
        showsProgressBar = true,
        showsNextUp = false,
        showsTodayHomework = true,
        showsWeekStrip = true,
        showsHomework = true,
        showsNextDay = false,
        titleSp = 23f,
        bodySp = 15f,
        captionSp = 13f,
        paddingDp = 16f,
    ),

    /**
     * Half a home screen.
     *
     * Its extra height is spent on the one thing the other sizes cannot afford:
     * the next school day. Late in the afternoon the rest of today is one row or
     * none, and without something after it the tall widget was mostly empty at
     * exactly the hour a pupil is deciding what to pack.
     */
    TALL(
        breakpoint = DpSize(320.dp, 400.dp),
        timelineRows = 6,
        homeworkItems = 6,
        homeworkChars = 72,
        showsSubject = true,
        showsMeta = true,
        showsProgressBar = true,
        showsNextUp = false,
        showsTodayHomework = true,
        showsWeekStrip = true,
        showsHomework = true,
        showsNextDay = true,
        titleSp = 23f,
        bodySp = 15f,
        captionSp = 13f,
        paddingDp = 16f,
    ),

    /**
     * Most of a home screen.
     *
     * The top of the ladder, and it exists because the ladder stopping at
     * [TALL] is visible: a widget dragged out to six or seven rows drew the
     * 400 dp layout and left two hundred more of background under it. There is
     * nothing new to say at this size, only more of everything — every lesson
     * left today rather than the first six, and the whole homework list rather
     * than a slice.
     */
    HUGE(
        breakpoint = DpSize(320.dp, 560.dp),
        timelineRows = 8,
        homeworkItems = 8,
        homeworkChars = 80,
        showsSubject = true,
        showsMeta = true,
        showsProgressBar = true,
        showsNextUp = false,
        showsTodayHomework = true,
        showsWeekStrip = true,
        showsHomework = true,
        showsNextDay = true,
        titleSp = 24f,
        bodySp = 15f,
        captionSp = 13f,
        paddingDp = 16f,
    ),
    ;

    /** True for the two widths that cannot hold a room number beside a subject. */
    val isNarrow: Boolean
        get() = breakpoint.width < MEDIUM.breakpoint.width

    companion object {

        /** The set handed to `SizeMode.Responsive`, in ascending order. */
        val breakpoints: Set<DpSize> = entries.map { it.breakpoint }.toSet()

        /**
         * Maps a reported size back to a class.
         *
         * In practice `LocalSize.current` is exactly one of [breakpoints], so a
         * lookup would do — but Glance has changed which size it reports at least
         * once across versions (matched breakpoint vs. real cell size), and a
         * widget that renders a blank box because of an equality miss is a bad
         * failure. Comparing against thresholds is right under either behaviour,
         * and it also lets a preview pass in an arbitrary size.
         *
         * Ordered widest-and-tallest first, so a size that satisfies several
         * classes gets the most generous one it actually fits.
         */
        fun of(size: DpSize): WidgetSizeClass {
            val w = size.width
            val h = size.height
            return when {
                w >= HUGE.breakpoint.width && h >= HUGE.breakpoint.height -> HUGE
                w >= TALL.breakpoint.width && h >= TALL.breakpoint.height -> TALL
                w >= XLARGE.breakpoint.width && h >= XLARGE.breakpoint.height -> XLARGE
                w >= LARGE_TALL.breakpoint.width && h >= LARGE_TALL.breakpoint.height -> LARGE_TALL
                w >= LARGE.breakpoint.width && h >= LARGE.breakpoint.height -> LARGE
                w >= MEDIUM_TALL.breakpoint.width &&
                    h >= MEDIUM_TALL.breakpoint.height -> MEDIUM_TALL

                w >= MEDIUM.breakpoint.width && h >= MEDIUM.breakpoint.height -> MEDIUM
                // Narrow and tall: checked after the wide classes, so a widget
                // that is both wide and tall never falls into the narrow column.
                h >= NARROW.breakpoint.height -> NARROW
                h >= SMALL_TALL.breakpoint.height -> SMALL_TALL
                h >= SMALL.breakpoint.height -> SMALL
                w >= WIDE.breakpoint.width && h >= WIDE.breakpoint.height -> WIDE
                else -> TINY
            }
        }
    }
}
