package com.lumenpearson.lessons.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The five layouts the widget can be, and the content budget of each.
 *
 * ### Why a ladder rather than a fluid layout
 *
 * Glance renders through `RemoteViews`. There is no measure pass we can react
 * to, no `onSizeChanged`, no text auto-sizing — the only size information
 * available is `LocalSize.current`, which under [androidx.glance.appwidget
 * .SizeMode.Responsive] reports whichever declared breakpoint the launcher
 * matched. So "resizable to any size" has to be implemented as a small number of
 * layouts that each look deliberate, with the launcher snapping between them.
 * A class earns its place only by changing what the widget can say — but the
 * ladder also has to reach the tallest size a user can actually drag out, since
 * a surplus no class claims is drawn as empty background.
 *
 * ### Why these six
 *
 * | Class    | Breakpoint  | Cells | What it can say                                    |
 * |----------|-------------|-------|----------------------------------------------------|
 * | [TINY]   | 110 x 40dp  | 2x1   | state word + countdown, on one line                 |
 * | [SMALL]  | 110 x 110dp | 2x2   | + subject and a progress bar                        |
 * | [MEDIUM] | 250 x 110dp | 4x2   | + the next two lessons                              |
 * | [LARGE]  | 250 x 250dp | 4x4   | + the whole remaining-day timeline                  |
 * | [XLARGE] | 320 x 320dp | 5x5   | + a homework block under the timeline               |
 * | [TALL]   | 320 x 460dp | 5x7   | + the rest of the day rather than blank background  |
 *
 * The widths are the two that matter on a phone: 110dp is two cells on a typical
 * 4- or 5-column launcher grid, 250dp is four, 320dp is five or a tablet column.
 * The heights step 40 → 110 → 250 → 320 because those are roughly one, two, four
 * and five rows; between them the launcher picks the largest breakpoint that
 * fits, which is exactly the behaviour we want (grow the widget, get more).
 *
 * 40dp is the floor because it is the smallest height in which a 13sp label and a
 * 13sp countdown still clear the launcher's own widget padding.
 *
 * @property breakpoint the size handed to `SizeMode.Responsive`.
 * @property homeworkItems how many homework subjects the after-school layout shows.
 * @property homeworkChars per-subject character budget before truncation.
 * @property timelineRows how many lessons the timeline / next-up list shows.
 * @property showsProgressBar whether there is vertical room for a progress bar.
 * @property titleSp size of the subject line.
 * @property bodySp size of list rows.
 * @property captionSp size of the state label and countdown.
 * @property padding inner padding; small sizes cannot afford much.
 */
enum class WidgetSizeClass(
    val breakpoint: DpSize,
    val homeworkItems: Int,
    val homeworkChars: Int,
    val timelineRows: Int,
    val showsProgressBar: Boolean,
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
        homeworkItems = 0,
        homeworkChars = 0,
        timelineRows = 0,
        showsProgressBar = false,
        titleSp = 13f,
        bodySp = 12f,
        captionSp = 12f,
        paddingDp = 8f,
    ),

    /** The square 2x2 most launchers default to when a user drags from the picker. */
    SMALL(
        breakpoint = DpSize(110.dp, 110.dp),
        homeworkItems = 2,
        homeworkChars = 24,
        timelineRows = 0,
        showsProgressBar = true,
        titleSp = 16f,
        bodySp = 12f,
        captionSp = 11f,
        paddingDp = 10f,
    ),

    /** The drop target from `targetCellWidth/Height`: wide enough for a second column. */
    MEDIUM(
        breakpoint = DpSize(250.dp, 110.dp),
        homeworkItems = 3,
        homeworkChars = 40,
        timelineRows = 2,
        showsProgressBar = true,
        titleSp = 19f,
        bodySp = 13f,
        captionSp = 12f,
        paddingDp = 12f,
    ),

    /** Four rows: the whole rest of the school day fits without scrolling. */
    LARGE(
        breakpoint = DpSize(250.dp, 250.dp),
        homeworkItems = 5,
        homeworkChars = 56,
        timelineRows = 5,
        showsProgressBar = true,
        titleSp = 21f,
        bodySp = 14f,
        captionSp = 12f,
        paddingDp = 14f,
    ),

    /** Timeline *and* homework at once — the "I live on my home screen" size. */
    XLARGE(
        breakpoint = DpSize(320.dp, 320.dp),
        homeworkItems = 6,
        homeworkChars = 72,
        timelineRows = 6,
        showsProgressBar = true,
        titleSp = 23f,
        bodySp = 15f,
        captionSp = 13f,
        paddingDp = 16f,
    ),

    /**
     * Half a home screen or more.
     *
     * Without this the ladder stopped at [XLARGE], so stretching the widget past
     * five rows bought nothing but empty background: the row budget is fixed per
     * class, and Glance gives no measure pass to distribute the surplus with.
     */
    TALL(
        breakpoint = DpSize(320.dp, 460.dp),
        homeworkItems = 8,
        homeworkChars = 72,
        timelineRows = 10,
        showsProgressBar = true,
        titleSp = 23f,
        bodySp = 15f,
        captionSp = 13f,
        paddingDp = 16f,
    ),
    ;

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
         */
        fun of(size: DpSize): WidgetSizeClass {
            val w = size.width
            val h = size.height
            return when {
                w >= TALL.breakpoint.width && h >= TALL.breakpoint.height -> TALL
                w >= XLARGE.breakpoint.width && h >= XLARGE.breakpoint.height -> XLARGE
                w >= LARGE.breakpoint.width && h >= LARGE.breakpoint.height -> LARGE
                w >= MEDIUM.breakpoint.width && h >= MEDIUM.breakpoint.height -> MEDIUM
                h >= SMALL.breakpoint.height -> SMALL
                else -> TINY
            }
        }
    }
}
