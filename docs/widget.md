# The widget

The product's headline feature. The widget answers one question — "what now?" — and when
the lessons end it switches by itself to "what is set".

## Sizes

`SizeMode.Responsive` with twelve breakpoints. Glance renders all twelve once, and the
launcher picks the right one locally — resizing wakes no process and reads no database.

| Class | Breakpoint | Cells | What it shows |
| --- | --- | --- | --- |
| `TINY` | 110×40 dp | 2×1 | one line: the state and the countdown |
| `WIDE` | 250×60 dp | 4×1 | the same, with room for the subject |
| `SMALL` | 110×110 dp | 2×2 | + the subject and the progress |
| `SMALL_TALL` | 110×190 dp | 2×3 | + today's homework |
| `NARROW` | 110×300 dp | 2×5 | a column: the day's timetable with no metadata |
| `MEDIUM` | 250×110 dp | 4×2 | + the next two lessons |
| `MEDIUM_TALL` | 250×180 dp | 4×3 | + the week's strip |
| `LARGE` | 250×250 dp | 4×4 | the rest of the day's timetable |
| `LARGE_TALL` | 250×300 dp | 4×5 | the same with the homework beside it |
| `XLARGE` | 320×320 dp | 5×5 | the timetable plus the homework |
| `TALL` | 320×400 dp | 5×6 | + the next school day |
| `HUGE` | 320×560 dp | 5×8 | everything at once, eight rows |

Twelve rather than five, for one reason: both the framework (API 31+) and Glance's own
`findBestSize` pick, among the breakpoints that fit, the **nearest by squared distance**
rather than the largest. While there were five rungs, a widget four cells wide and taller
than about 471 dp came out closer to the narrow 110×300 column than to 250×250, and drew
itself as one column over half a screen. The intermediate rungs — `SMALL_TALL`,
`MEDIUM_TALL`, `LARGE_TALL`, `TALL` — exist so that every plausible shape has a neighbour
closer than a rung of the other orientation. `WidgetSizeClassTest` holds this: it
reproduces the launcher's rule rather than trusting `of()`.

`resizeMode="horizontal|vertical"` with no upper bound: the widget stretches to any size,
and `WidgetSizeClass.of()` maps the actual size to the nearest rung that fits.

## States

Computed by `ScheduleEngine.stateAt(timetable, now)` — a pure function covered by 18 tests
in `:core:model`. The time inside it is the school's, not the phone's: a class in
Vladivostok and a class in Kaliningrad can hang off one server, and the widget takes "now"
from `timetable.nowAtSchool()`.

| State | When | What is on the screen |
| --- | --- | --- |
| `BeforeSchool` | before the first bell | the first lesson and how long until it |
| `InLesson` | a lesson is running | subject, room, time left, progress |
| `OnBreak` | between lessons | the next lesson and how long until it |
| `DuringEvent` | canteen, assembly, excursion | the event's name and how long is left |
| `AfterSchool` | after the last bell | **homework for the next school day** |
| `DayOff` | day off, holidays | the same |
| `NoData` | no cache for this date | a hint to open the app |

The priority when they overlap: an event with `coversLesson` beats a running lesson; an
event without it is shown only during a break. That is why «Обед» is visible on a break but
does not hide a lesson.

The homework heading names the day the way a person would: «на завтра», «на понедельник»,
«на 15 сентября» — depending on how far away it is.

## The school's time, not the phone's

A timetable is stored as the school's wall clock. The widget takes "now" through
`Timetable.nowAtSchool()`, that is, in the class's time zone rather than the device's. For
Russia this is not a detail: there are ten hours between Kaliningrad and Kamchatka, and a
parent in Moscow following a school in Novosibirsk has to see that school's bells.

## Updates

Two bad options: `updatePeriodMillis` with a floor of 30 minutes (the countdown does not
work) and an alarm once a minute (around 400 wake-ups a day).

`TickCadence` takes a third: one alarm, set for the moment the text on the screen will
actually change.

| Situation | The next wake-up |
| --- | --- |
| less than 10 minutes left | +1 minute |
| less than an hour left | +5 minutes |
| further out | +15 minutes |
| the nearest bell | exactly on the bell |
| no data at all | +1 hour |

The nearest of three wins: the countdown tick, `ScheduleEngine.nextTransition` (the bell)
and `state.validUntil`. On a tie the bell wins — being late for a change of state is worse
than being a second late refreshing a digit.

Bells are set through `setExactAndAllowWhileIdle`, everything else through `setWindow`. The
`SCHEDULE_EXACT_ALARM` permission is **not requested**: Google Play grants it to alarm
clocks and calendars, and a school diary is neither. `WidgetTickScheduler` checks
`canScheduleExactAlarms()` and degrades calmly to a one-minute window.

Alarms do not survive a reboot, so `WidgetTickReceiver` listens for `BOOT_COMPLETED`,
`MY_PACKAGE_REPLACED`, `TIME_SET` and `TIMEZONE_CHANGED`.

## Offline

The widget reads only Room and DataStore — there is no network on the drawing path. That is
exactly why the countdown keeps running in a school basement.

After a successful sync, `SyncWorker` sends the internal broadcast
`com.lumenpearson.lessons.action.DATA_SYNCED`, which `LessonsWidgetReceiver` subscribes to.
That is why `:core:data` does not depend on `:widget` — the dependency would otherwise be
circular.

A sync that came back `304` does **not** send the broadcast: the phone sends `If-None-Match`
with the tag of the window it already has, and a `304` means there is nothing new to draw —
redrawing on every poll would cost exactly what the conditional request is made to avoid.
The "updated N ago" mark still moves: the check did happen.

In summer the widget no longer asks to be pulled down. A sync window starting on 1 June is
a school year about to open, so today is not in the cache by construction;
`ScheduleEngine` now answers such a date with `DayOff(HOLIDAY)` («Каникулы») instead of
`NoData`, and shows the homework for the first day of September.
