# The design system

The look of the app and the widget is built entirely on
[Essentials](https://github.com/sameerasw/essentials) (MIT, © sameerasw.com). This is not
"inspired by": the typeface, the typographic scale, the shape of the containers, the
components and almost all of the personalisation settings were carried over from that
repository rather than invented again.

## The grammar

The whole interface is three things:

* a **page** — `surfaceContainer`, 16 dp margins;
* a **group** — `RoundedCardContainer`: a 24 dp corner radius, a transparent background, 2 dp
  between rows. A group is a mask and nothing else. It has no fill of its own; it is the
  group that rounds the corners, not the rows;
* a **row** — a rectangle on `surfaceBright` with a round coloured tile on the left.

There are no dividers anywhere. Two blocks are separated by one of them becoming a group,
not by a line between them. `ThinDivider` was deleted from the widget for the same reason.

## What came from where

| Essentials | Here |
| --- | --- |
| `res/font/google_sans_flex.ttf` | the same file in `:core:designsystem` |
| `ui/theme/Type.kt` | `theme/Type.kt` — the scale verbatim |
| `ui/theme/Shapes.kt` | `theme/Shape.kt` |
| `ui/core/containers/RoundedCardContainer.kt` | `component/Group.kt` |
| `ui/components/EssentialsFloatingToolbar.kt` | `component/FloatingToolbar.kt` |
| `ui/core/sheets/EssentialsBottomSheet.kt` | `component/BottomSheet.kt` |
| `ui/core/pickers/SegmentedPicker.kt` | `component/SegmentedPicker.kt` |
| `ui/components/sliders/ConfigSliderItem.kt` | `component/SliderItem.kt` |
| `ui/core/cards/IconToggleItem.kt` | `component/Group.kt` (`GroupSwitchItem`) |
| `ui/modifiers/ScrollMotionBlurModifier.kt` | `modifier/ScrollMotionBlur.kt` |
| `ui/modifiers/ProgressiveBlurModifier.kt` | `modifier/ProgressiveBlur.kt` |
| `ui/components/modifiers/ShimmerModifier.kt` | `modifier/Shimmer.kt` |
| `utils/ui/HapticUtil.kt` | `haptic/LessonsHaptics.kt` |
| `MainActivity` (the tab pager) | `navigation/LessonsApp.kt` |
| `ui/activities/SettingsActivity.kt` | `ui/settings/SettingsScreen.kt` |
| `ui/composables/WelcomeScreen.kt` | `ui/onboarding/` — the four steps of first run |

The AGSL shaders — both the scroll blur and the blur under the sheet — were carried over
character for character. They were tuned by eye, and an "improved" constant in them breaks
the effect.

## What was fixed against the original

Three Essentials bugs were not carried over — they were fixed instead:

1. **The empty FAB slot.** `EssentialsFloatingToolbar` always passes
   `floatingActionButton`, substituting `{}` when there is no button.
   `HorizontalFloatingToolbar` lays the passed slot out anyway, so an empty one reserves the
   width of a button that does not exist and pushes the bar to the left edge with a hole
   beside it. Here the slot is passed only when there is something in it.
2. **The gaps in a collapsed bar.** The spacing between buttons animates to 8 dp for all of
   them but the last — including when the bar is collapsed and every unselected button
   already has zero width. Inside the collapsed "pill" there is `(n − 1) × 8 dp` of
   emptiness. Here the spacing collapses along with the buttons.
3. **The label threshold.** The selected tab's label is hidden when the screen is narrower
   than 400 dp. Most phones in portrait are 360 dp, meaning that the expanding label the
   component was written for never appeared on any of them. The real minimum is 328 dp; here
   it is 330.

4. **The shader compiles every frame.** `ProgressiveBlurModifier` creates the
   `RuntimeShader` *inside* the `graphicsLayer` block, so the AGSL source is parsed and
   compiled on every draw of the layer — and that layer, there and here, covers the whole
   screen. Here the shader is created once and remembered.

Essentials' open pull requests were reviewed: none of them fixes anything in the files that
were carried over (the last one that matched by name, #954 about the
`label_motion_blur_amount` string, is already in `main`).

The fourth bug is our own. The screens reserved a fixed 96 dp for the bar, while the bar
together with its margins and the gesture stripe is taller than that on almost every phone,
so the last row of any list ended up underneath it. The shell now measures the bar and
publishes its height through `LocalBottomBarSpace`.

## Personalisation

The «Взаимодействие» section repeats "Customizations" from Essentials: the theme, colours
from the wallpaper, the black theme, haptics and their strength, swiping between tabs, the
starting tab, the blur under the sheet, the scroll blur and its strength. All of it is kept
in `AppSettings`, so the widget and the background sync see the same values.

A switch that is on tints the whole row rather than only the toggle —
`rowSelectedContainer`, the row's colour mixed with `primaryContainer`. That is the cheapest
information on the page: the state of a dozen switches is read while scrolling, without
stopping on a single row. An action that happens immediately rather than saving a setting —
"refresh now" — sits as the last row of a group, as a filled full-width button
(`GroupActionItem`), the way "Check for updates" does in Essentials.

Not carried over: choosing the app icon (Essentials switches it through an `activity-alias`,
and we have no alternative icons), the "ripple animation" (the effect is drawn over the
window by an overlay of Essentials' own) and the "online help media" (we have no help). The
language picker, on the contrary, was carried over — see below.

## There are no top bars

Not on any screen. Essentials has none either: a page starts at the very top of the window,
the heading scrolls away with the content, and the name of where you are lives in the "pill"
at the bottom, which never goes anywhere. Three things follow from that, and all three are
why it was worth redoing.

The heading is read in one place. With a top bar, the screen's name was both in the bar and
in the selected tab at the bottom — one of them scrolled away and the other did not.

A nested page gained a way back under the thumb. A "back" arrow in the top-left corner of a
6.7-inch phone was not one.

And the content can run under the status bar — without which there is nothing for the top
blur to blur: an opaque bar in that stripe left nothing behind it.

The status-bar inset therefore lives in the list's `contentPadding` rather than in the
`padding` around it: an ordinary padding would stop the list exactly in front of the stripe
it is supposed to run under.

## The top blur appears with scrolling

At the bottom it is constant — there is always something under the "pill". At the top there
is not: at rest the first row stands under the status bar with nothing behind it, and
blurring an empty stripe simply puts the clock on a smudge. So the top edge gains strength
over the first ~72 px of scrolling and arrives together with the content it exists for.

The Essentials shader computes one radius for both edges. Here there are two — `topRadius`
and `bottomRadius` — or the bottom blur would appear and disappear with scrolling along with
the top one. The kernel, the dithering and the power curve stayed as the author wrote them.

How far a screen is scrolled is known only by that screen, while the shell holds several at
once: the pager's three pages plus an open settings page. So each is given its own
`ScrollOffsetHolder`, and the shell reads the one belonging to the screen in front —
otherwise a page standing off-stage would override the visible one.

## Settings are not a tab

The "pill" holds three tabs, and the gear is a separate button beside it, like Essentials'
`fabAction`. Settings used to be a fourth tab, and that was wrong: the other three are
places you live in and switch between, while settings are somewhere you go, change one thing
and come back. In the bar they took a quarter of the width from the screens that need it,
and a list of preferences could be made the "starting tab".

The settings themselves are split across six pages you descend into — exactly as
`SettingsActivity` opens `FeatureSettingsActivity`. Two dozen rows in one scroll means
scrolling past five sections you did not come for, and the row you want is never where you
left it, because the groups above it grow and shrink along with their switches.

The settings page is a layer over the pager rather than a separate navigation graph: the
tabs underneath stay alive and keep their scroll position. The layer does not try to
intercept touches. A layer that intercepted early enough to stop the pager cancelled presses
on its own rows with the same gesture — precisely the bug that kept any settings entry from
opening for two builds in a row.

With compose-bom 2026.09.00 that behaviour changed: the same layer over the same rows lets
presses through, and `OverlayLayerTest` now pins the new order (narrowed to the bom itself —
with navigation 2.10.1 and room 2.8.5, rolling back the bom alone restores the old one). We
are **not** going back to the layer, though: the delivery order between two subtrees is
promised nowhere, it has already moved under a dependency bump without saying a word — and a
finger on a row would just as silently stop working again. So instead of covering the tabs
we remove them from the composition once the page has arrived: there is nothing to block and
nothing to depend on. `rememberSaveableStateHolder` keeps their scroll position.

## First run is four screens, not one field

A new install used to open with a field for a class code. That is asking a stranger to type
a key they were given, on a screen that has said nothing about itself yet.

The order is now the same as in Essentials' `WelcomeScreen.kt`, and it is not accidental:

1. **Welcome** — the app's mark, its name, the theme picker and the language picker. The
   mark spins under a finger and springs back with haptics: it is the first thing the user
   touches, and it answers instantly. Essentials has the language picker in this slot, and
   now so do we — together with the theme, the second setting whose effect is visible on the
   next frame. The language sits on the very first step rather than on "Properties" two
   screens later because between them lies the acknowledgement: four paragraphs on what this
   timetable is and what it is not, the most useful thing a new user reads at all. Somebody
   who does not read Russian has to be able to switch the language before that, not after.
   The setter and the strings are the same ones the settings page uses. Below API 33,
   changing the language recreates the activity, so the step number lives in
   `rememberSaveable` rather than `remember`: `recreate()` saves and restores instance state
   exactly as a rotation does, so the step, the latched decision to show the introduction and
   the view model all survive — only the slide animation is lost, and that is no loss.
2. **Acknowledgement** — what the app shows, what it does not do, and the one thing it can
   record. Crash reports are off by default and are chosen right here, next to the text that
   explains them.
3. **Properties** — haptics, colours from the wallpaper, a black background, blur, the
   teacher on a lesson's row, the progress bar in the widget. Not every setting, but the
   ones that can be judged without having seen a single lesson.
4. **The class code** — the very same `JoinScreen` rather than a copy of it, with a back
   button.

The "introduction shown" flag is set on entering the fourth step rather than after a
successful connection: somebody who closed the app on the code field has already read the
introduction. The decision whether to show the introduction is latched on the first
composition — otherwise writing the flag would swap the screen out in the middle of the
slide. Leaving the **last** class returns to the bare code field rather than to the four
screens again; while at least one other class remains on the phone, the app simply shows it.

## Notifications

Four occasions, each with its own switch, and all of them off on install. An app that starts
buzzing straight after installation is an app whose notifications get switched off at the
system level, and that channel does not come back.

| Occasion | When | Channel |
| --- | --- | --- |
| A lesson is coming | 5/10/15/30 minutes before the bell | «Уроки», High importance |
| The morning digest | at the chosen hour, if there are lessons today | «Расписание на день» |
| Tomorrow's homework | in the evening, if anything is set for the next school day | «Расписание на день» |
| Substitutions and cancellations | right after the sync that found them | «Изменения» |

Three channels rather than one: somebody who wants "algebra in 10 minutes" on the lock
screen and does not want a reminder at eight in the evening has to be able to say so, and in
Android that can only be said per channel.

The time is chosen with hour chips rather than a clock picker. The question is "roughly
when", the answers worth giving are whole hours, and a picker would offer 07:23 as though
that meant something.

The first row of the page is access. Turning a switch on while notifications are forbidden
at the system level means writing a setting that silently does nothing — the worst possible
answer to "why is it not working".

## Changing the theme — a circle from under the finger

The effect Telegram is known for, and it works because it answers a question an instant swap
cannot: *what exactly did I just change?* A screen that changed colour in one frame reads as
a glitch; the same change arriving as a wave from under a finger reads as an answer.

There is one composition, and after the setting is written it is already the new theme's —
there is nothing left to redraw the old one from. So what is animated is a snapshot of the
previous frame, laid over the live tree and erased by a growing circle. The order is the
whole trick:

1. take the snapshot while the old theme is still on the screen;
2. write the setting, so that what is under the snapshot is the new one;
3. open a hole in the snapshot from the point that was touched.

Hence also that the effect is launched from the call site rather than by observing the
settings flow: an observer learns about the change when there is nothing left to photograph.

The snapshot is a software redraw of the `View` rather than a `PixelCopy`: `PixelCopy` is
asynchronous, and one extra frame here means a snapshot of the new theme already — that is,
a circle opening into itself. The price of that is the blur shaders: they are hardware and
do not make it into the snapshot, so it has sharp edges where the live tree has soft ones.
Under a wave that crosses the screen in half a second, nobody has ever seen it.

The overlay is a sibling node drawn after the content and never a parent: it must not be
able to take a touch, and a `Canvas` with no pointer input cannot. In this project that is
not abstract caution — a full-screen layer over the settings once ate every press on every
row for two builds in a row.

The circle does not play if the user has turned animations off in the system.

### The wave leaves the screen rather than stopping at its edge

The first version took the radius exactly to the furthest corner and drove it with a curve
that slows towards the end. The arc of a circle crosses a corner most slowly of all, so the
last thing a person saw was a sliver of the old theme sitting in the corner of the screen
for a tenth of a second: the wave looked not finished but stuck. The radius is now a quarter
larger than needed and the curve accelerates towards the end rather than fading — the
visible edge leaves the screen noticeably before the clock stops, and the wave spends the
tail of the animation where nobody sees it.

### A second touch does not freeze the first

The clock and the snapshot now live in one object that is replaced whole, and the previous
animation is cancelled before the new one is published. The clock used to live on state and
be reset from the coroutine driving it: a touch that arrived mid-flight put a fresh snapshot
on the screen with the old animation's progress still underneath it — a hole already opened
across half the screen would open onto a frame taken a moment ago. Somebody flicking the
theme back and forth to compare gets one wave per touch, each from its own switch, and none
of them frozen.

## The wave when the debug button is pressed

The `liquidRipple` shader from Essentials: every pixel asks how far it is from the point of
touch, waits that distance divided by the wave's speed, and then travels
along a decaying sinusoid down the ray from the source. There are two waves — the second
slightly later, slower and smaller — and that is precisely what turns one ring into
something like a liquid.

It is launched from the bug button in the shell and applied to the whole shell, the bar
included: the wave comes out of the button, and a bar left motionless would give away that
it came from somewhere else. The point comes from the button itself — the bar floats and its
width changes with the tab, so nobody else knows where it is.

Four defects in the original are fixed:

1. **`uResolution` is declared and unused.** That is not untidiness: SkSL strips a uniform
   nobody references, and `setFloatUniform` for a name no longer in the compiled program
   throws. Here it does what it was evidently introduced for — clamping the sample so that a
   pixel displaced past the layer's edge reads the edge rather than the transparent nothing
   beyond it. That nothing was drawn as a dark border running round the screen ahead of the
   wave.
2. **A pixel at the source gives NaN.** `normalize` of a zero vector is undefined, and the
   pixel exactly under the finger is a zero vector. The direction is now a division guarded
   at one pixel, and the middle of the ripple stays put instead of becoming whatever the
   driver makes of a NaN coordinate.
3. **The highlight ignores alpha.** The original adds flat white to a premultiplied colour,
   and for a transparent pixel that yields a colour brighter than its own alpha — an invalid
   premultiplied value, visible as a grey haze over everything semi-transparent. Multiplying
   by alpha and clamping to it keeps the highlight a highlight on what is drawn rather than
   a fog over what is not.
4. **The shader runs three times as long as the wave.** The original sets three seconds at a
   decay of 4.5: `exp(−4.5 × 1)` is a hundredth of the amplitude, which on a 32 dp wave is a
   third of a pixel. The remaining two seconds are a full-screen runtime shader recomputing
   an invisible displacement for every pixel sixty times a second on top of the rest of the
   app; Essentials' own video shows what the frames pay for it. Here the time is what the
   last front needs: to cross a tall phone at 1400 dp a second and decay there.

The `RuntimeShader` itself is built inside a `runCatching`. The constructor throws if
somebody's driver did not accept the source, and a throw inside a composition takes the app
down — for a decoration. `null` here is a shell that simply does not ripple.

Below Android 13 there is no ripple: there is no AGSL there yet.

### What was broken in the chain

Four defects found by reviewing already-merged code, and all four silent.

**Rescheduling deleted the nearest alert.** `reschedule` always asked the planner for the
first event strictly after "now + 90 seconds". That offset is needed by exactly one caller —
the one that has just published a window and must not publish it twice. But rescheduling is
called after every sync, every settings change, every launch and every boot, and a new alarm
replaces the standing one. A sync that happened a minute before a bell overwrote that bell's
alarm with the next one, and the notification never arrived.

**The homework reminder arrived three times.** The planner walks every cached date, and "the
next school day" skips days with no lessons. Monday's homework was the answer on Friday, on
Saturday and on Sunday — three identical notifications under one id. The morning digest a
line above has a guard; the homework had none.

**A failed read broke the chain.** The chain is one alarm long: a locked database at the
moment of firing, and there is nothing left behind it. The function returned before the one
place where the next alarm is armed. There is now a retry on that path.

**An inexact fallback alarm was deferred by Doze.** The system holds a `setWindow` until the
next maintenance window, while an alarm that fires publishes only what falls within 90
seconds of it — one deferred by an hour publishes nothing. `setAndAllowWhileIdle` is just as
inexact but is not deferred.

A fifth: the timetable fingerprint outlived leaving a class, and the first sync in a new
class compared it against somebody else's and announced that the timetable had changed. That
same rule now extends to switching between classes on one phone: the fingerprint describes
the timetable that was on the screen a second ago, so it is cleared along with the choice of
class — otherwise "the timetable has changed" would arrive every time the user merely looked
at another of their classes.

## The sheet moves with the screen

The sheet used to be drawn once, over everything, and stood still while the page moved
underneath it: entering the settings slid the screen in under a bar that was already showing
its heading. The bar now belongs to the page — they move together, and each screen has its
own, even where the text repeats.

There turned out to be nothing to copy from Essentials for this. There, every settings page
is a separate Activity, and the bar "moves with it" simply because the page is a whole
window: the system moves it, not the app's code. There is no Compose transition for the
settings there at all. The equivalent had to be built here.

How it works: one `AnimatedContent` keyed on the shell's destination (tabs → settings root →
section), and each slot is a `Box { content; sheet }`. The bar is inside the slot, so it
inherits the slot's offset.

The discipline everything rests on: **everything the sheet shows is read from the slot
rather than from hoisted state.** During a transition both slots are in the composition at
once, and a heading read from outside would switch at the moment of navigation — the
departing page would leave carrying the arriving page's name. For the same reason the latch
that stored the last section is gone: a slot carries its own.

A destination's depth is declared rather than taken from `ordinal`. An ordinal is the order
of declaration, and reordering the lines would silently reverse the transition.

`SizeTransform(clip = false)`: by default the slot's size is animated *and* the content is
clipped to it, while settings pages differ a lot in height — the taller one would be clipped
for the whole transition. The slide and the fade run on one spring: with different curves the
arriving page reaches full opacity while still visibly moving, and the departing one
disappears before it reaches the edge.

The `tabsCovered` mechanism is gone. It existed because a layer on top could not intercept
touches without killing presses on its own rows; `OverlayLayerTest` measures that spot and
with compose-bom 2026.09.00 records the opposite — see "Settings are not a tab" above for
why the conclusion does not change. `AnimatedContent` removes the slot that is no longer
current, so the property now follows from the navigation rather than being maintained
alongside it.

Measuring the bar's height is now each page's own. During a transition there are two bars,
and one shared height would be written twice a frame by two different bars. The top blur
became per-page too: the shell used to pick the screen in front and hand one number to
everybody, and a departing page carried it for the whole transition.

## Permissions — a screen that exists only when something is broken

Three things, each of which can be taken back after installation and each of which breaks
notifications in its own way: the right to show notifications, the right to exact alarms,
and permission to work in the background. Any of them can be revoked in the system settings
a month and a half after installation, and the only way the app learns of it is by no longer
arriving on time.

So the notifications page has a red row at the top — «Разрешения — N не выдано» — which is
not drawn at all when everything is in place. It is not a permanent entry to a section but a
fault lamp: a permanent row would be one more item read past on every visit, and it would be
silent on most phones, where everything is granted. For the same reason the section is
hidden from the root settings list.

On the screen itself: a red banner with a "check again" button and one card per permission —
its name, one line about what does not work without it, and a button on the right. The button
stays even when the permission is granted: the page is not only for fixing things — a granted
permission is also something people come to switch off. What changes is the emphasis rather
than the presence: a filled button while something is wrong, an outlined one when all is
well.

The state is asked of the system afresh rather than stored: it changes outside the app, and
there is no event for it. It is recomputed on the screen's `ON_RESUME` plus a manual button.
A lifecycle observer rather than `Activity.onResume`, as in the original: here these are
screens inside one activity, and the activity's `onResume` would not fire moving between
them.

Four defects of the original were not carried over. Its `POST_NOTIFICATIONS` check routes
around its own guard and on Android 12 and below answers "not granted" for ever, because
that permission does not exist there. The request codes do not match the filter that
refreshes the state after an answer, so the refresh works by accident — because the dialog
pauses the activity by itself. Some cards compute grantedness from sources that are not in
their `remember` keys, and go stale. And the chain of fallback Intents there repeats the same
action without a URI: if the first threw because nothing handles the action, the second
throws too — and that one is not caught. Here the list of candidates ends with the app's own
page, which exists everywhere, and each is tried in turn.

The direct dialog for switching off battery optimisation is not used: it requires declaring
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and Google Play checks that permission strictly. A
school diary is not worth an argument with review, so the system list is opened instead — one
press more.

## The "About" footer

The last thing on the last page. The name with the version, a description, the app's mark, a
line about the author, links as pills two to a row, and a closing line.

The mark is drawn the way a launcher draws it: an adaptive icon is a 108 dp canvas of which
only the central 72 dp is visible, so drawing the mipmap directly would give a small mark
inside a field of its own background. The foreground layer is drawn at the inverse ratio and
clipped by a box — the same crop the home screen makes.

The main thing not carried over from the original: nine link buttons there are nine copies of
the same fifteen lines differing in icon, caption and address — so `ActivityNotFoundException`
is caught only by the one that opens mail, and on a device with no browser the other eight
crash the app. Here a link is a data record and there is one button, so both the catch and
the haptics live in one place. The version comes from `BuildConfig` rather than through
`PackageManager` on every recomposition: that call returns a nullable and prints a literal
"null" when it does.

## Blur on transitions too

The setting is called "scroll blur", and the largest scroll in the app — the swipe between
tabs — was not covered by it. It is now, and with it two transitions where the whole screen
moves: the settings page over the tabs, and a step of the first run. They have no scroll
state, so the blur is computed from the transition's own fraction: the very same animated
float the offset is drawn from, multiplied by the real length of the path. One definition of
the movement for both — the shader and the offset cannot drift apart.

## The sheet is no longer clipped while it morphs

The "pill"'s width during the morph from "three tabs" to "back and a heading" used to be
computed by `Modifier.animateContentSize`. That applies `clipToBounds` to the *animated*
box, while the pill inside it is already laid out at its final width — so the morph played
as an erasure of two rounded ends rather than as a change of size. There is no way to turn
that clip off.

It is now computed by `SizeTransform(clip = false)` inside `AnimatedContent`: that measures
both states during the transition and animates its own size between them, so the pill really
is narrower in the middle of the morph and nothing is cut on the way.

## The page's background is drawn by the app, not by the window

The tabs drew rows and nothing beneath them. Between the rows what showed through was the
**window background** — an Android resource that follows the system's night mode and cannot
see a setting inside the app.

While the system and the app agreed, that looked deliberate. A "light" theme on a phone in
dark mode gave white rows and black text on a black page, and the "black theme" seemed not
to work at all: the only surface in the app that painted itself was the settings layer, and
that is exactly where both settings "worked".

`LessonsApp` now paints `surfaceContainer` under everything at once. The same change fixed
the system bars: `enableEdgeToEdge()` by itself asks the *system's* night mode, while the
whole point of a theme setting is that the app is entitled to disagree with the system — a
light app under a dark system drew a white clock and battery on a white page. The activity
calls `enableEdgeToEdge` again with a `detectDarkMode` that looks at
`ThemeMode.resolvesToDark()`.

## There are no nested pagers any more

Inside the "Week" tab lay a second pager, of seven days: the inner one took the gesture
first, and the outer one got only what was left at the edge. A day is now chosen by tapping,
a period is paged with arrows, and the tab is called «Календарь» and shows three scales: the
week, the month, and the day by the hour. It is switched off in the settings, under "swipe
between tabs".

The hourly ribbon is the only one of the three where blocks stand by time rather than
following one another. A forty-minute gap between lessons looks like two consecutive rows in
a list; on a ruler it looks like a gap. Events get a column beside the lessons, because an
event overlapping a lesson is information, and a list cannot show an overlap.

## How much the widget says at each size

The size ladder is twelve rungs, and each declares its own portion with flags right on
`WidgetSizeClass` rather than with a separate copy of the layout. The body assembles four
blocks out of them, in the order they are asked about:

1. **now** — what is running, how long it lasts, how long is left and how far it has gone;
2. **next** — the next lesson and the week;
3. **today** — everything remaining and what was set for today;
4. **ahead** — the homework for the next school day, and what that day looks like.

| Rung | Point | What it adds |
| --- | --- | --- |
| TINY | 110×40 | the state and how long is left |
| WIDE | 250×60 | + the subject on the same line |
| SMALL | 110×110 | + the progress |
| SMALL_TALL | 110×190 | + "next" and a homework count |
| NARROW | 110×300 | + the rest of the day as narrow rows |
| MEDIUM | 250×110 | + a "next" column |
| MEDIUM_TALL | 250×180 | + the rest of the day |
| LARGE | 250×250 | + the week's strip |
| LARGE_TALL | 250×300 | + the homework |
| XLARGE | 320×320 | + wider and in more detail |
| TALL | 320×400 | + the next school day |
| HUGE | 320×560 | + more of all of it |

The "ahead" block appears earlier than its rung too, if there is one row left for today or
none at all: five in the afternoon is exactly when a tall widget turned out half empty, and
exactly when the only useful question is about tomorrow.

Two things in the countdown were broken. An `AndroidRemoteViews` with no size modifier lays
out as "fill", so on a row beside a weighted caption it took the whole width and the word
«ПЕРЕМЕНА» was measured to zero — the widget showed a bare «05:57» and never once said what
that number was for. And a `Chronometer` can only draw digits, which counting down from
under an hour is `MM:SS`, and above a subject that reads as "three minutes to six". It now
wraps its content and carries a word beside it — «до звонка», «до начала».

## What the widget cannot do

Three things from the brief run not into laziness but into `RemoteViews`, through which
Glance draws everything on a home screen.

**There are no animations.** A widget has no frame loop: the system takes the `RemoteViews`
tree away from the process and draws it in the launcher. The only moving things allowed are
a `Chronometer` and a `ProgressBar`, both with their own built-in logic. So the swinging bell
lives in the app, on the state card during a break: there it is Compose, two rotations about
the point of suspension (`rotationZ` for the swing and `rotationY` for moving away from the
viewer) at a short `cameraDistance`, so the far side of the rim really does narrow — that is
real perspective, not a painted-on highlight.

**There is no horizontal scrolling.** Of the scrollable containers `RemoteViews` offers
`ListView`, `GridView` and `StackView`, and all three are vertical. The week is therefore
fitted rather than scrolled: seven fixed columns with the day's letter, its date and a dot
per lesson. Each is a link into the app on that day, which was the other half of the point of
scrolling.

**There can be only one live number.** A widget is a snapshot: the process wakes on an alarm,
draws and goes back to sleep. The system will not deliver an alarm once a second, so the
countdown to the bell is given to a `Chronometer` in count-down mode — it lives in the
launcher's process and redraws itself. Glance has no such element; it arrives through
`AndroidRemoteViews`.

**And ten children per container.** Glance's prebuilt layouts hold variants for zero to ten
children, and the translator takes the first ten and silently throws the rest away. Hence
`CHILD_LIMIT` and the wrapping columns around each list.

## What of this list is already fixed

Six items sat here for several releases. Five of them are closed, and what closed each one
is written below — because a list that is not cleared as things are fixed starts being read a
month later as "everything here is broken" and stops being opened at all.

**The theme-change circle opened on the old theme.** The order "take the snapshot, write the
setting, open the hole" was described in the file itself but was not what happened:
`change()` is a `viewModelScope.launch { … }` over DataStore, so it returns at the first
suspension, and for several frames the old theme was still under the snapshot. The hole now
waits for the theme it is about to show: `ThemeRevealHost` publishes the palette's key from a
`SideEffect`, and the animation starts on the first value different from the one
photographed. A change that did not change the theme falls inside `SettleTimeoutMillis` and
is not seen — what is under the snapshot is the same thing.

**The system-bar icons were re-tinted before the snapshot.** The bars are drawn by the
window, over everything the circle erases, so re-tinting at the moment of the change put dark
icons on a snapshot that was still light. The tint now waits for the end of the wave
(`ThemeRevealState.revealing`, `MainActivity`): until the wave has reached the top of the
screen, the old tint is the correct one for what is drawn there.

**The sheet's morph did not play.** `AnimatedContent` reads `items` from `page` rather than
from hoisted state, so the departing slot keeps its tabs for the whole transition.

**A wide widget hid the homework.** This was the one real inversion in the ladder: 250×300
fell into LARGE and 110×300 into NARROW, and the first showed less than the second. It was
fixed **not** by turning `showsHomework` on in LARGE — in the code it is still off there, and
`timelineRows` stayed at six — but by a new rung, LARGE_TALL at 250×300: the homework block
had to move where there is room for it, not where the inversion was noticed. LARGE is reached
at 250 dp of height, that is, 50 dp less than NARROW for a shorter list. This paragraph spent
a year describing the exact opposite of what was done — and if somebody had brought the code
into line with it, the inversion would have come back. The test that let it through compared
rungs against each other, and rungs are comparable only when one is larger on both sides —
LARGE and NARROW are not comparable in either direction. A second test appeared beside it
that walks real sizes and compares what `WidgetSizeClass.of` picks for them.

**The widget asserted what it does not know.** `DayState.NoData` goes into an early return
(`LessonsWidgetBody.kt`) instead of taking the ordinary path and printing «Уроков больше
нет» under a heading reading «НЕТ РАСПИСАНИЯ».

## Limitations

**The screenshot is taken on the main thread, and it cannot be otherwise.** This is not an
item of the list above, though it used to sit there: `View.draw(Canvas)` is a view-hierarchy
operation, it is obliged to run on the UI thread, and there is nowhere to move it. There is
one asynchronous alternative, `PixelCopy`, and the order of the effect rules it out: the copy
arrives a frame later, by which time the new theme is already under the window and the circle
opens onto itself. The cost is reduced by what can reduce it — `SnapshotScale` halves the
frame on each side, that is, quarters it by area.

**The typeface in the widget.** The widget stays on the system font. Glance passes
`fontFamily` into `RemoteViews` as the name of a system family rather than as a font
resource, so `google_sans_flex.ttf` cannot be wired up the way it is in the app.

**The typeface's licence.** `google_sans_flex.ttf` is **SIL Open Font License 1.1**,
Copyright 2015 Google LLC. That is not an inference from where the file was copied: the
typeface declares its licence itself, in its `name` table, record 13 ("This Font Software is
licensed under the SIL Open Font License, Version 1.1"), with a link to it in record 14. This
document and `theme/Type.kt` used to say "MIT, from Essentials" and then guess that the
typeface was non-free and had to go. Wrong twice over: a repository's licence covers what its
author is entitled to license, and this typeface was released under OFL by Google itself.

OFL explicitly permits bundling the font with an application — "Copies of the Font Software
may be sold by themselves or bundled with software" — on one condition: a copy of the licence
and the copyright must accompany every copy of the font. So the text lives not in a document
but in `:core:designsystem/src/main/assets/licenses/google_sans_flex_OFL.txt`, that is, inside
the APK next to the typeface itself; and the licence is named in the app, on the «Лицензии»
sheet.

A test holds this rather than memory: `FontLicenceTest` in `:core:designsystem` parses the
`name` table of every font in the tree and requires that a notice with the same copyright and
the same licence be found under `assets/licenses/` — and that it be the licence text rather
than a link to it. Move the notice, swap the typeface, cut the file down to a URL, and it
fails naming exactly what diverged. Verified all three ways: with the notice missing, two of
five tests fail; with it truncated, four; with a different copyright, two.

Two clarifications, so as not to drift from the licence any further. No Reserved Font Name is
declared, and the file is not modified here — the `wght` and `ROND` axes are set at runtime —
so the requirement to rename derivatives does not apply to this project. And a trademark
lives separately from a licence: "Google Sans is a trademark of Google" means the typeface
may be bundled and redistributed, but a product may not be named after it or imply Google's
endorsement.

## Bold is a state

Essentials has no separate emphasis system for text: the selected segment of a picker is set
in bold, its neighbours are regular, and that is enough. Here the same rule is extended to
everything that has a "selected", a "current" or a "focused": bold goes only to the element
that is switched on right now, and its neighbours stay at the scale's weight. Weight works
where colour does not — on a `primary` fill, on a tinted row, in shades of grey — and one
heavy label in a row of seven is found before a single one has been read. The other side of
the rule: among equals, nothing is bold "for looks", or the state stops meaning anything.
Page headings and the first-run buttons are not in this family — they have no neighbours to
argue with.

The rule lives in one line — `TextStyle.emphasised(active)` in `theme/Emphasis.kt` — and what
goes through it is: the selected segment of a `SegmentedPicker`; the caption of the open tab
in the sheet; the selected day and today in the week strip and in the month grid; the subject
of the lesson running now in `LessonRow` (its neighbours stay at `Medium` so as not to fall
out of the other rows); the "now" and "today" chips — a `PillChip` with `selected = true` in
its own colours; the labels of the class-code and server-address fields while the field has
focus. The selected hour in the notification settings gets the same through `PillChip`,
knowing nothing about the rule.

## Updates come from GitHub

The app is not in a store, and the only place a new version appears is a GitHub release with
a signed APK, built by the workflow on a `v*` tag. Checking for updates asks the API for the
list of releases and compares it with what is installed. The comparison is a separate type,
`AppVersion` in `:core:model`, following semantic versioning: `0.10.0` is newer than `0.9.0`
as a number rather than as a string; `1.0.0-rc.1` is older than `1.0.0`; `rc.10` comes after
`rc.2`. Drafts are never offered; pre-releases only under a separate switch. The `-debug`
suffix that a debug build adds to `versionName` is handled separately: semver reads it as a
pre-release, and without trimming it a debug install of the current version would offer
itself to itself for ever.

The check on launch runs no more than once a day — not to economise but because GitHub's
unauthenticated window, sixty requests an hour, is counted per address, and a classful of
phones behind one router shares it. A failed check counts too: a refusal spends the window
exactly as an answer does. The manual check on the «Обновления» page does not know about this
limit.

The answer is shown as a sheet — the same one for "an update is available" and for "you are
up to date", as in Essentials: in the second case with the notes of the installed release,
because "what have I got" is an answer too. The notes are Markdown from the release's body,
which needs not a renderer but a parse of headings, lists, `**bold**`, `` `code` `` and
links; everything else is a paragraph. The "update" button opens the APK in a browser and the
system installer takes over; the app downloads nothing and installs nothing itself. "Later"
remembers the tag, and the automatic check no longer raises the sheet for that release — the
manual one will.

Two answers give two waves. "Update" sends the ripple outwards from the button, like any
press in the app; "Later" sends the same ripple the other way, a ring converging on the
button. For that, `uReverse` was added to the shader: it changes both the order the front
arrives in (the far corners first) and the direction the pixels are displaced. The sheet is a
separate window, so the button's centre is measured in screen coordinates and the shell
translates it into its own.

## Signing in through GitHub is for one thing

Reporting a bug without leaving the app. Signing in is the device flow: a code on the screen,
a GitHub page in a browser, and the app polling for a token at the interval GitHub named plus
a second of slack. It is the only OAuth grant that works with no server and no secret — and
there is no secret here deliberately: an APK cannot keep one, and a leaked client secret
would let somebody revoke other people's tokens. The price is that the token cannot be
revoked from the app; "sign out" only forgets it, and it is removed from the list of
authorised applications on GitHub itself.

The client id is not a constant in the code but a build property, `lessons.github.clientId`
(or `LESSONS_GITHUB_CLIENT_ID`): whoever builds registers the OAuth App. Without it the
sign-in row is not shown at all — a row that opens a sheet saying "not configured" would be a
row about the build rather than about the user. The token and the login are written in one
transaction after the profile has been fetched, so a token never sits on disk without a name
beside it; fetching the token is not cancelled by closing the sheet — otherwise the user would
be typing a second code for a first token that is already in their list.

A bug report goes out three ways, and signing in is needed only for the first: an issue in
the user's name through the API; the "new issue" page in a browser with the title and body
already filled in; an email to the address from `lessons.contactEmail` — also a build
property, because an address in a public repository is an address in every spam list. The
body is the same in all three cases: the description and the table about the device, in the
same shape Essentials uses.

## Two switches for two decorations

The wave and the circular theme change are both full-screen, and both can now be switched
off separately under «Эффекты». The wave is a shader on every frame for a second and a half
over the whole app, the one decoration here a cheap phone feels; it shares its availability
with the blur. The circular change is a raster snapshot, runs on anything and does not switch
itself off, but the people who ask for it off are the ones it makes queasy. Both now start
together on a theme change: the new theme spreads as a circle, and ahead of it a ring of
ripple from under the same finger. The switches are independent: a wave with no circle and a
circle with no wave are both honest states.

## A phone is linked, not logged in

The app has no sign-in, and the «Telegram» card on the class page is not one either. The bot
already knows who in the class is an editor and who is an observer; the phone lacks exactly
one thing — being somebody's. So the server hands the device a six-character code, the app
shows it in a row with a "copy" button and an «Открыть бота» button (the deep link
`t.me/<bot>?start=link_<code>`), and the user sends it to the bot, where they already are.
From that moment every write from the phone is judged by the server against that account's
role at the moment of the request: there is no second permission system on the phone, nothing
to go stale and nothing to synchronise. Revoke somebody in the bot and the phone is read-only
in the same second.

The card remembers the server's last answer. A refresh that did not arrive does not erase a
code the person may be typing into a chat right now; an error is shown on the line below
rather than in its place. The code is the same until it is used, so the page can be opened as
many times as you like — the server hands back the same one. «Отвязать» goes through a
confirmation, because the app becomes read-only immediately, and that is worth saying before
the press rather than after.

## A switch that switches off somebody else's screen

The class card has a row for "who may connect a phone". It does something most rows there do
not: the result of pressing it is **not visible from this phone**. Nothing on the screen
changes, not one already-connected device drops off — and who learns about the press is a
pupil across town typing a code that worked yesterday.

Three decisions follow.

**The confirmation stands in one direction only.** Moving to personal invitations asks;
going back to the class code does not. Going back gives access rather than taking it away,
and a question with only one sensible answer is an extra press, not a safeguard.

**There is no "are you sure?" in the confirmation.** There are three statements, because
those are exactly what a person cannot check for themselves: connected phones will stay
connected; only the class code stops working; everybody takes a personal code in the bot.
Remove the first and an admin who suspects otherwise either never switches, or switches and
spends the evening answering where the timetable went.

**The row's caption is about the phone, not about the setting.** «Режим: invite» tells
nothing to somebody who has not read the bot's help; «Код класса никого не подключает» tells
exactly one thing.

And the same from the other side — on the code entry screen. A class in invitation mode
answers its own code with a `403`, and showing that as "unknown code" would send the person
back to whoever dictated the code, that is, to somebody who cannot help. A separate sentence
names the bot. This screen used to draw `failure.message`, that is, "HTTP 404 Not Found" —
there was nothing to tell the codes apart with: `HttpException` does not leave `:core:data`.
They are now told apart by `JoinFailure`, in the same place Retrofit lives.

## What fourteen confirmed findings showed

The audit of the Android half confirmed all fourteen reported defects — that is not something
to be proud of, it is a report of where this project is thin. Three themes.

**The class's time zone was lost exactly where nobody checked it.** The widget draws by
`timetable.nowAtSchool()`, while the scheduler for its ticks took `LocalDateTime.now()` and
set an alarm in `ZoneId.systemDefault()`: two independent bugs in one file. The same on the
home screen — the ticker handed the device's local time straight into `ScheduleEngine`. An
`Instant` now crosses the "ticker → state" boundary, the one form of "now" that does not turn
into the phone's time on the way, and the tick calculation was moved into a pure function
with a test saying that the arming time does not depend on the device's zone.

**The timetable fingerprint moved by itself.** The hash was computed over seven days from
today and included the dates — so at midnight the window slid, the hash changed, and the app
announced "the timetable has changed" when nothing had. The hash is now per day, and only the
days present in both reads are compared. Next to it, the inexact alarm:
`setAndAllowWhileIdle` arrives when it arrives, while only notifications within 90 seconds of
the arrival were published and the next was armed after them. The alarm now carries the
moment it was set for, and a late one picks up what was missed over the preceding half hour.

**The decorations broke on their own lifecycle.** The wave restarted on re-entering the
composition with the old centre (switch the effect off and on in the settings, and a minute
later the screen splashes from a point you pressed long ago). `cameraDistance` was multiplied
by the density although Compose already treats that value as density-independent — the
perspective the component was written for was switched off. Power-saving mode was read once
when the activity was created, so the emergency exit from a full-screen shader worked only
for people who had switched saving on beforehand.

Plus the small things visible only up close: `values-en` existed in two modules out of three,
and Russian plural forms on an English phone were chosen by English rules; trimming a bug
report's title cut an emoji in half; `goAsync()` was called twice in a row and the second
returned null; the tick chain was re-armed even with no widget on the screen; and
`CONFIGURATION_CHANGED` is not delivered to manifest receivers at all, so the redraw-on-theme-
change branch was dead code.

And two things the widget lied about: at `NoData` it wrote «Уроков больше нет» and «Ничего не
задано» — statements about a day it had never seen; and the number of subjects was counted in
one place by rows and in another by distinct subjects, so one and the same strip could say
"2 subjects" and "1 subject" at the same time.

## The Petersburg diary is held behind one door

St Petersburg's electronic diary is not a documented API but the internal service of somebody
else's website. Its addresses and parameters are known from the open clients that talk to it;
the shapes of its answers only partly, and for lessons and the timetable not at all. You can
work with something like that, but only if you decide in advance exactly who suffers when it
changes.

The decision: one directory suffers. `app/providers/petersburg/` is the only place that knows
the words `p_educations[]`, `estimate_type_code` and `X-JWT-Token`. What comes out of it are
this project's models and typed errors. The app does not talk to dnevnik2.petersburgedu.ru at
all and does not know it exists: it asks our server about the timetable, the homework and the
marks.

Two asymmetric habits follow from the lack of documentation. On the way in the mapper is
lenient: a field is looked for under every name it has appeared under (a room is `office`,
`cabinet` or `room`, depending on the endpoint), and a row that could not be read is dropped.
A week of marks with one entry lost is more useful than a page with an error. On the way out
it is strict: a lesson with no date is not a lesson, and it is not in the answer.

The marks get a normalisation of their own. The service puts marks, absences, late arrivals
and remarks into one list and tells them apart by a numeric code: 30000 is "was absent". A
client should not have to know that, so a mark in our model has two fields: `value`, what is
written in the cell («5», «Н», «!»), and `kind`, what it actually is. An absence can be
coloured differently from a bad mark without knowing about the code.

The password is not stored — in any form. It is needed for one request, after which the
diary's own session lives on, refreshed from its own answers: the service hands back a fresh
token on almost every call, we overwrite ours with it, and an active session does not die of
age. When it does finally stop being accepted, any request answers 401 with an
`X-Diary-Reauth: required` header — a request to ask for the password again rather than "the
token is wrong": those are different screens.

The price is named honestly: a background sync of the diary does not outlive the session.
Reference implementations solve this by storing the password, so they can silently
re-authenticate once a day. Every family's password in a database to save one sign-in screen
every few days is a bad bargain, and we do not make it.

## The language is chosen in the app, and a test guards the translation

The language switch was not blocked by the switch. `app/build.gradle.kts` already had
`localeFilters += listOf("ru", "en")`, and `:widget`, `:core:designsystem` and `:core:data`
already had their own `values-en/` — while `:app`, which holds almost all of the app's copy,
had no `values-en/` at all. English was a filter with nothing behind it: choose it and all
four screens stayed Russian, because there was nowhere to take them from. The translation came
first — 412 strings in four files mirroring `strings.xml`, `strings_github.xml`,
`strings_telegram.xml` and `strings_updates.xml` — and only then the settings row.

The mechanism differs on either side of Android 13, because below it an app simply has no
language. On API 33+ the choice is handed to the system: `LocaleManager` keeps it outside the
app and applies the locale to the whole process — the activities, the application context,
the widget and `Locale.getDefault()` along with them; there is no appcompat in this project
and none was added for this — `LocaleManager` is a system service, not a library. Below 33 the
only lever is a context's configuration, so `MainActivity.attachBaseContext` wraps the base
context through `createConfigurationContext`, and changing the language recreates the
activity: the base context is set once, and there is no other way to change the language of a
screen that is already drawn. The choice itself lives, in both cases, in
`AppSettings.language` next to the theme and the typeface — it survives the process's death
and is read once at the activity's start, because `attachBaseContext` cannot wait.

Below 33 there are exactly three contexts the app draws text from: the activity's, the
notification's and the widget's. At first only the first was wrapped, and that meant exactly
what it sounds like: somebody who chose English read English screens while "algebra in 10
minutes" arrived in Russian, and the widget on the home screen was Russian too. All three are
wrapped.

The rule, meanwhile, lives in one place, and that place is not `:app`. `AppLocales` in
`ui/common/` describes the seam and wraps the activity's base context, but neither
`:core:data` nor `:widget` can see it — the dependency runs the other way. So the mechanism
itself lives in `:core:data/locale/`, which all three can see: `AppLocale.localized(context)`
hands back a context in the chosen language, and the decision it makes is factored out into a
pure function, `localeOverrideFor(language, deviceLocales)` — with no `Context` at all, so
that an ordinary JVM test can check it. The function answers with a list of locales or
`null` — "do not touch" — and its `null` has two different reasons. "System" is the absence
of an app locale rather than Russian: `AppLanguage.SYSTEM` has an empty `tag`, `values/` is
Russian, and a rule that read the tag without thinking would silently turn "as on the phone"
into "always Russian" on every English phone that never opened the settings. The second
reason is that the phone is already on exactly this language and only this one: there is
nothing to wrap, and this is computed on every redraw of the widget. The list, meanwhile, is
always exactly one locale rather than the chosen one plus the phone's behind it: a list is
what the resource resolver walks, and a second entry behind the chosen one would let a
Russian string into the middle of an English notification whenever it was missing from
`values-en/`.

The price is one blocking read of the setting per build-up, not per string. A notification
wraps the context once on entering `AlertNotifier` and passes the wrapped one down; the
widget takes the language from the same settings snapshot `provideGlance` already reads for
the progress bar and the teacher, wraps the context once per draw and publishes it as
Glance's own `LocalContext` — from where every `getString` in the tree takes its context
anyway, so not one of the widget's composables learned about languages. `WidgetStrings.
shortWeekday` was fixed along the way: the short weekday names came from `java.time` through
`Locale.getDefault()`, that is, from the phone, and an English widget captioned six English
words with Russian «пн вт ср».

What this does not cover is better named than left unsaid. Below 33, everything the app does
not draw itself stays in the phone's language: the widget's label and description in the
launcher's list, its preview (`widget_preview.xml`) and Glance's first frame
(`widget_loading.xml`). Another process inflates that XML with its own configuration, and
there is no way to reach in — on any version of Android, 33 and above included.

There was a separate hole where the text is drawn not by resources but by `java.time`: the
month and weekday names in `ui/common/Formatting.kt` come from `Locale.getDefault()`, while
`createConfigurationContext` moves the resource lookup and does not touch the process's
default — an English screen got a Russian «8 сентября». That is fixed in one place:
`AppLocale.localized` also calls `Locale.setDefault`, and it does so in both directions —
`processLocaleFor` returns the phone's original default when there is no override, or
"System" after English would leave the months English. Above 33 it never gets there: the
platform changes the process default itself when it applies a per-app locale.

What must not be done is translating halfway. Android allows names individually: if a string
is missing from `values-en/` it quietly takes it from `values/`, that is, the Russian one, in
the middle of an English screen. Nothing fails and nothing warns; it can only be found by
eye, rereading every screen twice. So `app/src/test/` holds `ResourceTranslationTest` — an
ordinary JVM test that reads both folders straight from the sources and checks four things:
every name in `values/` has a twin in `values-en/`; there is nothing extra the other way; the
set of format arguments matches (the order may change, but then through explicit
`%1$s`/`%2$s`); and every English `<plurals>` has exactly `one` and `other`, because `few`
and `many` are never selected in the English locale. The test looks at the files rather than
at `R` — by the time the resources are built, "the string is missing in English" has already
become "the string was substituted from Russian", which is precisely the behaviour being
checked.

## The correction mode covers all the text, not one tab

The mode appeared before this section and worked on five strings. The mechanism was written
whole — the switch in the settings, the editor on a long press, the sheet of accumulated
corrections, the export as a ready piece of `values/` — and wired to the help screen and to
two strings in «Задания». The setting, meanwhile, promised that "a long press on text opens a
correction", and the promise was untrue on every other screen: there the text differed from
the text in the help by nothing except that nobody had wrapped it by hand. It could only be
found by poking at every row in turn.

Wrapping 750 sites by hand is not a solution but the same mistake, deferred to the next
screen. Essentials, where half of this design system came from, solves the same thing
differently: `TranslatableText` is called five times there, and the coverage comes from the
cards and rows knowing about the mode themselves — `FeatureCard`, `ConfigPickerItem`,
`PermissionCard` and a dozen more. The same move is taken here, one level lower: it is `Text`
that knows about the mode.

`:core:designsystem/text/Text.kt` is Material's `Text` plus one line,
`modifier.correctable(text)`. The signature is repeated parameter for parameter, because
substituting for something means agreeing with it: a parameter not declared here would
silently stop working on every screen at once, and the compiler would say nothing — the call
still resolves. `CorrectableTextTest` holds this, comparing the two signatures by reflection
against the version of Material actually on the classpath — `autoSize` arrived in 1.4, and
the signature will grow again. There is deliberately no wrapper around the text: the outline
is hung by a modifier on the same layout node, so turning the mode on changes neither the
measurement, nor the weights, nor the alignment. A `Box` around every label in the app would
change all three.

The other half is the key. `GroupItem` takes a `title: String` rather than an
`@StringRes Int`, and that is right: half the headings in this app are a subject's name out
of the database. So by the time of drawing, the resource id is already lost, and it cannot be
looked up backwards by text: 204 strings of 991 match another string's text («Назад» is
`docs_back`, `action_back` and `ds_action_back`), and another 100 are patterns with arguments
that will match none of the sentences they produced. A lookup by value would silently export
a correction for a different string from the one the reader was correcting.

So the key is not looked up, it is remembered. `correctedString` — the replacement for
`stringResource` in all 750 sites — hands back the corrected text and, for as long as those
words are on the screen, records "this text was drawn by this resource". A long press asks
the registry rather than `values/`. The match is exact, it happens after the arguments are
substituted, and exactly one ambiguity is left: two different strings with the same text
visible at the same time. `AppCorrections` does not guess at that one — it opens the editor on
both at once and names both keys: the reader looks at a phrase and says it is wrong, and a
phrase that is wrong in `docs_back` is wrong in the other two places as well. Keys whose
original text differs are dropped in the process — one field cannot honestly stand for two
different `values/` strings.

The recording happens in an effect rather than during composition: a composition runs
speculatively and may be thrown away, and a string recorded by a composition that never
happened would stay in the registry for ever. The price is that the outlines appear a frame
after the mode is switched on; the registry is read through snapshot state, so the texts are
invalidated at exactly the moment it fills up.

The snapshot state in the registry is **per string of text, not one for the whole registry**.
A `mutableStateMapOf` records a read against the entire map: every `Text` in the app would end
up subscribed to every other, and with the mode on, scrolling registers and forgets strings
frame by frame — so the whole visible tree would be redrawn several times a frame exactly
when the reader is trying to read. So the map is an ordinary one and a `MutableState` sits in
its values: a text is invalidated only when the entry about its own words changes. The cell is
created by whoever asks first, reader or writer — otherwise a `Text` whose words nobody has
drawn yet would not be subscribed to their appearance.

Turning the mode off **does not undo the corrections**. That is not an oversight: removing the
outlines and rereading the app in your own words is the only way to see that a corrected
heading no longer fits on its line. So `enabled` closes the outline, the gesture and the
registry, but not the words; `correctionOf` is asked of every string on every composition in
every build, so the first thing it does is answer for an empty session — one snapshot read and
nothing more.

The export knows about modules. `Resources.getResourceName` does not: by the time an id
exists, every module's resources are merged under the app's package, and `ds_state_break` is
indistinguishable from `settings_title`. And a correction to a `ds_` string pasted into
`:app` does not fix the library string — it declares a second one that shadows it: it looks
right, the original stays wrong, and `ResourceTranslationTest` starts demanding an English
twin for a string that should not exist. So the routing goes by prefix — not one invented
here: all 38 of the design system's strings, all 18 of `:core:data`'s and all 77 of the
widget's already carry it, and no other module uses them. `TranslationXmlTest` reads the
source tree and holds that rule, because a prefix nobody checks is a prefix until the next
string.

The editor and the corrections sheet are given `NoCorrections` — the rule that "inside the
editor a long press does nothing" is not remembered but enforced: both draw the originals and
the corrections themselves, and without this the card showing the current value would become
a target for a correction, and a long press inside the editor would open another editor.

All of this is guarded by `CorrectionReachTest`: a file importing Material's `Text` or
`stringResource` is a future screen the proofreader will not be able to touch, and both
imports are still on the classpath and still compile. There are three exceptions, each named
by path and checked to exist — an exception for a deleted file is a hole the next one moves
into. Separately it checks that `MainActivity` still wraps the app in `CorrectionHost`:
without it everything builds, everything draws, every string is still read through
`correctedString` — and the mode simply ceases to exist, because the default implementation
does nothing. There is no other symptom.

What this does not cover: `<plurals>` (they cannot be written out as one `<string>`, so they
are not registered and pressing them does nothing), the widget (Glance has no Compose
gestures) and text the app does not draw itself — the widget's label in the launcher and the
system dialogs.

And one more case, narrow but real: a call site that glued an app string together with data.
`"${schedule.name} · ${correctedString(R.string.admin_bells_default)}"` draws words that are
not in the registry — what is recorded there is «по умолчанию» while the screen shows
«Первая смена · по умолчанию» — so that string gets neither an outline nor a gesture. There
are two such places in the app, both in the bell schedules, and both mix the app's copy with a
name out of the database, where "correct the whole line" would not have meant anything
anyway. There is no general solution to this: the value is no longer equal to any resource
string.

And a correction lives exactly as long as the process: that is a deliberate decision, and
`TranslationMode` explains it on the spot.

## A second app was borrowed from, for two things

Everything above comes from Essentials. Two things do not:
[GMS Flags Reborn](https://github.com/polodarb/GMS-Flags-Reborn) (Apache 2.0, © polodarb)
is where the shape-changing loader and the transformation between first-run steps come from.
It is named on the «Лицензии» sheet beside Essentials, and unlike every other Apache 2.0 row
there its licence text also rides inside the APK, under `assets/notices/` — the other rows
are dependencies, this one is source that was adapted, and section 4(a) asks whoever passes
the work on to pass the licence with it. `CodeNoticeTest` holds the file in place the way
`FontLicenceTest` holds the font's.

### The loader

`LessonsLoadingIndicator` is Material 3 Expressive's `LoadingIndicator`: a shape that becomes
another shape rather than an arc that sweeps. There was nothing to port — in that project it
is the same androidx component, and what was taken is the decision to make it the default
everywhere a wait is shown. The reason is that an arc reads as one unchanging object, so two
seconds and ten seconds look identical from the first frame, while a shape that has visibly
become a different shape says time has passed.

**It did not replace the skeletons, and was not meant to.** The two answer different
questions. `SkeletonGroup` says what is about to appear and keeps the page from changing shape
when it does, so it stays wherever the layout is already known — every list in the admin
sheets, the diary's sections, the homework screen. The loader says only that something is
happening, which is all that can honestly be said where the shape of the answer is not known
yet: the session being restored before the first screen is chosen, and a sheet that has not
read its first response. Pull-to-refresh moved to it as well, through
`LessonsPullToRefreshBox`, so the one gesture that shows a wait on top of content shows the
same wait as everywhere else.

### The transformation between steps

The first-run flow had a horizontal slide and nothing else: five screens, each arriving whole.
What GMS Flags Reborn does instead is keep one shape *above* the transition and let it become
the next step's shape while the body underneath is exchanged. That is the whole mechanism, and
the position is the mechanism: `AnimatedContent` builds a fresh composable per step, so a badge
drawn inside a step can only ever fade in as a new object — held above the swap, it has a
previous shape to morph from.

So `OnboardingHero` sits above the existing slide and carries two things: a `Morph` between two
`MaterialShapes` polygons with the step's glyph held in the middle, and a row of dots where the
current one is stretched into a bar. The glyph is exchanged rather than morphed: a glyph is a
picture of a thing, and half of one picture blended into half of another is not a picture of
anything. `OnboardingReveal` is the third piece — a fade-and-rise that replays on every step
swap, so a step's title arrives a beat before the rest of it.

What was left behind is the rest of that screen: the orbiting accent shapes, the parallax, the
depth maths. They belong to an app whose first run is four screens of its own artwork; this one
is a school timetable, and one shape is the whole decoration it wants.

**One rule was given up for it.** `StepScaffold` used to carry the status-bar inset itself, so
that a long step scrolled up under the clock rather than stopping short of it. The strip is now
the top of the page, so the strip carries the inset and it is the strip that passes under the
clock. The inset sits *inside* the strip's animated height rather than around it, because the
strip collapses to nothing on the join step — held outside, it would survive the collapse and
leave the join screen pushed down by a header that is no longer there.
