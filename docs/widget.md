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
| `NARROW` | 110×300 dp | 2×5 | a column: the rest of the day in narrow rows, metadata and all |
| `MEDIUM` | 250×110 dp | 4×2 | + the next two lessons |
| `MEDIUM_TALL` | 250×180 dp | 4×3 | + the rest of the day |
| `LARGE` | 250×250 dp | 4×4 | + the week's strip above it |
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

`resizeMode="horizontal|vertical"`, and the manifest bounds it at 640 dp in both
directions (`maxResizeWidth`/`maxResizeHeight`, API 31+) — past the width of any phone and
most tablets, so in practice the widget stretches as far as a launcher will let it, and
`WidgetSizeClass.of()` maps the actual size to the nearest rung that fits.

**The reported height is divided by the font scale before a rung is chosen.** The
breakpoints are in `dp` and every type size on the ladder is in `sp`, so the two move apart
the moment a reader enlarges the system font, and on the largest setting they are about
twice apart. The box still measured its full size, so the largest rung was still chosen, and
the widget drew eight timeline rows, a week strip and two homework blocks in the room for
about half of that: the chips took a third of the surface and the last rows ran off the
bottom edge. `of()` therefore takes `Configuration.fontScale`, clamped to 1.0–2.0, and
divides the height by it — a larger font spends height about in proportion, so a widget at
1.5× holds about two thirds of the rows and the rung built for two thirds of the height is
the rung that fits.

The width is deliberately left alone, although a wider row at a larger font does hold fewer
characters. Width on this ladder decides *structure* — a second column, a week strip,
whether a row can carry a room number at all — and 348 dp is still 348 dp whatever the type
is doing; the horizontal half is already answered twice, by the rung's own character budget
and by weighting the trailing detail so it cannot take the subject's room. Dividing the
width as well was tried first and was worse than the defect: a four-cell widget at the
largest font fell past the narrow column onto a rung with no timeline at all, so a reader
who had asked for bigger type lost the rest of their school day. The floor of the clamp is
1.0 because a *smaller* font has not bought the widget a denser layout, and the ceiling is
2.0 because past it even `HUGE` falls below the height of a rung that can list one lesson —
a widget that says nothing is a worse answer than one whose last row is clipped.
`WidgetFontScaleTest` walks it.

**On the smallest sizes, what gives way is decided rather than left to the pixel** (#174).
Glance cannot ellipsize and cannot measure, so a line that runs out of room is cut wherever
the width ends. Two lines were being cut at their most useful end:

- The next day's plan, «4 урока · Алгебра в 08:30», was cut to the rung's character budget
  from the end, which is the time. `WidgetStrings.dayPlan` now spends the budget on the
  subject («4 урока · Алгеб… в 08:30»), and the narrow rungs let it take a second line
  rather than be clipped at 110 dp.
- On the 2×1, «ДЗ на завтра» is weighted and the count beside it is not, so at the largest
  font the label came out as «Д…» beside an intact «3 предмета». From the first step above
  the default font the count is drawn as the bare figure — «ДЗ на завтра 3», or «ДЗ на
  завтра нет» — and the day survives.

A 2×2 on a Pixel 10 Pro XL is about 195×226 dp, which is `SMALL_TALL`, not `LARGE`: `LARGE`
does not fit inside it. `SMALL_TALL` lists three homework subjects after school, where it
used to list two over an empty lower half.

**After school no size is left mostly background** (#221). The after-school layout used to be
the state, the plan and the homework block and nothing that grew with the height, so a tall
widget with nothing set for tomorrow was four lines over three quarters of a home screen of
empty surface. Every rung with a day timeline — and `SMALL_TALL` while its homework leaves
room — now lists the next school day's lessons under the homework, and the rungs with a week
strip draw it under them. The list is weighted to take whatever height is left and is
clipped at its own bottom edge rather than counted in advance, because Glance cannot measure
and a count is either too few on a tall widget or pushes the week off a short one
(`restDayLessons`, `RestDayLessonsTest`). The 4×2 draws the state and the plan above its
homework.

Every block drawn inside the widget takes its corner from the rung's own padding rather than
from a constant — `WidgetSizeClass.innerCorner()`, the surface's 24 dp less that padding.
The rule and the four places that deliberately do not follow it are in
[design.md](design.md#a-corner-inside-a-corner).

## States

Computed by `ScheduleEngine.stateAt(timetable, now)` — a pure function covered by the
thirty-one tests of `ScheduleEngineTest` and `ScheduleEngineEdgeCasesTest` in
`:core:model`. The time inside it is the school's, not the phone's: a class in
Vladivostok and a class in Kaliningrad can hang off one server, and the widget takes "now"
from `timetable.nowAtSchool()`.

| State | When | What is on the screen |
| --- | --- | --- |
| `BeforeSchool` | before the first bell | the first lesson and how long until it |
| `InLesson` | a lesson is running | subject, room, time left, progress |
| `OnBreak` | between lessons | the next lesson and how long until it |
| `DuringEvent` | canteen, assembly, excursion | the event's name and how long is left |
| `AfterSchool` | after the last bell | **homework for the next school day**, then that day's lessons |
| `DayOff` | day off, holidays | the same |
| `NoData` | no cache for this date | one of three sentences, by how the phone came in — see below |

The priority when they overlap: an event with `coversLesson` beats a running lesson; an
event without it is shown only during a break. That is why «Обед» is visible on a break but
does not hide a lesson.

The homework heading names the day the way a person would: «на завтра», «на понедельник»,
«на 15 сентября» — depending on how far away it is.

### When there is nothing to draw

With no timetable — no class, nothing cached, or `NoData` — the widget draws an instruction
rather than an error, and which instruction depends on how the phone came into the app. The
snapshot carries the phone's `ShellMode`, read from `container.shellMode.current()`: one
preferences read, and the same rule the app shell uses to choose its home.

| Mode | Wide rungs | Narrow rungs and `WIDE` |
| --- | --- | --- |
| no class, no diary | «Откройте приложение и подключитесь по коду класса или через свою школу» | «Откройте приложение» |
| a class, nothing synced | «Расписание ещё не загружено — откройте приложение и потяните вниз» | «Расписание не загружено» |
| a diary and no class | «Виджет показывает расписание класса. Дневник — в приложении» | «Дневник — в приложении» |

The first used to be «введите код класса», which is wrong for exactly the family the second
way in was built for, and it now names both ways. The third is new, and deliberately does
not say «потяните вниз»: no pull will ever bring a timetable to a phone that has no class.
A snapshot that cannot be read at all draws the class sentence, the least wrong of the three
when nothing is known. Which rungs get the short form is a width rule, `emptyTextIsCompact`,
because Glance text cannot ellipsize and a long sentence in a 110 dp column is clipped
mid-word.

**The widget never reads the diary** (#142). Outside a class it does not even ask Room for a
timetable, and `snapshotOf` ignores one handed to it, so the sentence and anything drawn
beside it come from one answer. A diary read from the widget would count as the family's
activity and keep a diary session alive on the server with nobody behind it;
`SyncWorkerSourceTest` fails if any widget source names the diary's repository, cache or
import. A diary-only phone therefore gets no widget content at all, and that is recorded as
the open question it is rather than patched here.

The widget learns that the mode changed the way it learns of a sync: registering a diary
session and signing out of one send the same `DATA_SYNCED` broadcast. None of this has been
seen on a launcher — whether the longer first sentence fits its rungs is judged by its
length, and the redraw on a mode change is not composed by any test.

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

A bell is set through `setExactAndAllowWhileIdle`, everything else through
`setAndAllowWhileIdle` — **not** `setWindow`, which is what it used to be. Doze holds a
plain window until the next maintenance pass, and only one alarm exists at a time: the exact
alarm for a bell is armed by the tick before it, so a countdown tick held by Doze means the
bell after it is never armed and the chain stops advancing. `SchoolAlerts` refuses
`setWindow` for the same reason.

The app does declare `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM`: it is installed as an APK
inside a school rather than through Google Play, where the policy reserving the second of
those for clocks and calendars would apply. `WidgetTickScheduler` still asks
`canScheduleExactAlarms()` before each bell and degrades to an inexact alarm, because the
grant can be absent on a sideloaded build or revoked by hand, and a bell a minute late is
better than a receiver that crashes and stops the chain for good.

Alarms do not survive a reboot, so `WidgetTickReceiver` listens for `BOOT_COMPLETED`,
`MY_PACKAGE_REPLACED`, `TIME_SET` and `TIMEZONE_CHANGED`.

**A redraw inside a live session reads again.** `LessonsWidget` reads one snapshot and
draws it — the body is a pure function of that snapshot. Glance, though, keeps a session
alive for most of a minute after it starts, and answers `updateAll` inside that minute by
recomposing alone, without running `provideGlance` again. So the snapshot read when the
session started used to be drawn for every redraw in that minute: two class switches ten
seconds apart left the widget on the class the phone had just left, and nothing redrew it
after, because the sync of the class on screen answered `304` (#168, seen on an emulator).
Every redraw now bumps `WidgetRedraws` before it asks, and a composition that is still alive
re-reads when the counter moves; a redraw that starts a session reads the counter with its
first snapshot and does not read twice.

**A settings change the widget draws redraws it** (#188). The widget draws three settings:
the app's language, «Прогресс в виджете» and the teacher switch. It reads them through
`AppSettings.drawnByWidget` and nothing wider, and `SettingsRepositoryImpl.update` sends
`DATA_SYNCED` when that value moves. Before, nothing did: on an emulator switched to Russian
the app redrew at once and the widget stayed English until a reinstall woke it, because a
settings change is neither a sync that changed the data nor a tick.

## The picker

`previewLayout` is what the launcher's widget picker draws, from XML, without running a line
of our code — and RemoteViews inflates only the classes marked `@RemoteView`. A plain
`<View>` drawing the preview's progress bar made the whole preview «Couldn't add widget.» on
every launcher from API 31 (#167); it is a `FrameLayout` now, and `RemoteViewsLayoutTest`
reads every layout in the module for a class the launcher would refuse.

## Offline

The widget reads only Room and DataStore — there is no network on the drawing path. That is
exactly why the countdown keeps running in a school basement.

After a successful sync, `SyncWorker` sends the internal broadcast
`com.lumenpearson.lessons.action.DATA_SYNCED`, which `WidgetTickReceiver` receives — not the
exported `LessonsWidgetReceiver`, which would take the action from any app on the device.
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
