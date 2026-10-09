# The app's icon, and a choice of sixty-four

**Status:** agreed with the owner on 9 October 2026, before any of it was built. The plan beside it
says task by task how it is built.

**What the owner asked for:** «Добавь все варианты иконок в приложение в категорию оформление, сделай
это как в Essentials, беря код оттуда, но исправляя ошибки/дефекты/баги, которые найдешь и там, и у
нас здесь. Смену иконки с выбором ее стиля и настройкой». Then: «Потом я выберу нужные и ты уберешь
ненужные варианты».

## The mark

The mark is «Пятёрка»: a twelve-lobe dial, shaped like the Material 3 Expressive cookie, whose hands
read five o'clock. That is a «5» without a digit. Behind it runs a trail of turned, fading copies,
the device Essentials' own icon uses. It was designed on 9 October 2026, outside this repository,
and the old icon, an open book with a bookmark, goes. The sources are:

- `clock.py`: the geometry and the eight palettes;
- `build_pack.py`: the «Классика» set, the dial at 90 % of the visible circle on white;
- `build_styles.py`: seven styles drawn edge to edge, the lobe tips on the visible circle.

All three are copied into `android/logo/`, beside the app whose icons they draw, so that the resources in the app can be rebuilt from
something in the repository.

## What the owner decided

1. **Every variant goes in.** That is eight styles by eight palettes, sixty-four icons.
   - The styles: «Классика», «Край в край», «Свечение», «Тёмная», «Матовое стекло», «Матовая»,
     «Размытие» and «AMOLED».
   - The palettes: «Рассвет», «Индиго», «Мята» and «Лаванда», each in a saturated and a light version.
   - The owner will pick the ones to keep later, so taking variants out must be cheap and safe
     (see «Taking variants out»).
2. **The default is «Классика · Мята»**: the dial at 90 % on white, inside Android's safe zone, so no
   launcher mask clips a lobe. It is the icon at install, on the splash screen, in Google Play and in
   «О приложении».
3. **The choice lives on a page of its own.** «Оформление» gains a row, «Значок приложения», that
   shows the current icon and opens that page.

## How the icon changes

The launcher shows an app's `MAIN`/`LAUNCHER` components. One component per icon is the only way an
app can change its own launcher icon, so each of the sixty-four is an `<activity-alias>` of
`MainActivity` with its own `icon`, `roundIcon`, and the launcher intent filter. Exactly one of them
is enabled.

- **`MainActivity` is never a launcher entry, and it is never disabled.** Its `LAUNCHER` filter moves
  to the aliases. The widget (`LessonsWidget.MAIN_ACTIVITY`) and the alerts
  (`AlertNotifier.MAIN_ACTIVITY`) both start `MainActivity` by class name, so a disabled
  `MainActivity` would break every tap on either.
- **The alias for «Классика · Мята» is `enabled="true"` in the manifest. The other sixty-three are
  `enabled="false"`.** A fresh install shows the default without a line of code running.
- **The system is the record, not a preference.** The current icon is whichever catalog alias
  `PackageManager.getComponentEnabledSetting` reports as enabled. «Default» resolves to the
  manifest's `enabled`. Nothing is written to DataStore.
  - A preference can disagree with the launcher. Auto Backup restores preferences onto a new phone,
    but not component states, so the page would claim an icon the home screen does not show.
- **One switch is one change, as far as the platform allows.**
  - On API 33 and above, `setComponentEnabledSettings` takes the whole list in one call, with the
    target enabled and the one that was enabled disabled. Components whose state does not change are
    left out.
  - Below 33, the target is enabled first and the old one disabled second. There is never a moment
    with no launcher entry, which some launchers answer by dropping the app's place on the home
    screen.
  - Every call carries `DONT_KILL_APP`, and none runs on the main thread.
- **A reconciliation brings the components back to exactly one enabled catalog alias.** It runs on
  `ACTION_MY_PACKAGE_REPLACED`, from a receiver, and once per process start.
  - If none is enabled, it enables the default.
  - If more than one is, it keeps the first in catalog order that is not the default, and disables
    the rest. For a half-finished switch away from the default that is the new icon. For a switch
    back to the default, or between two other icons when the one being left comes first, it is the
    icon being left: the last choice is undone, but there is still exactly one launcher entry.
  - Both happen, and both are why it exists. A switch can die halfway: a process killed between two
    calls below API 33. An update can remove the alias a phone had chosen. Without this, that phone
    would be left with no launcher entry at all, and no way back into the app but the widget.

## The page

`SettingsSection` gains `APP_ICON`. It is not listed on the root, and it is reached from the row in
«Оформление», as `PERMISSIONS` is reached from «Уведомления».

- **At the top, a large preview of the variant being looked at.** It is drawn twice, under a circle
  and under a squircle, because that is where the two geometries differ. «Классика» sits inside both
  masks with margins. The edge-to-edge styles meet the circle's edge and sit centred in the squircle.
- **Below it, eight groups, one per style.** Each group shows its eight palettes as tiles.
  - A tap selects a tile, marks it with a ring and updates the preview. Nothing changes on the
    launcher yet.
  - Each tile draws the whole adaptive icon, background and foreground, under a circle.
  - Essentials draws only the foreground on a white disc. That is wrong for every style with a
    ground of its own: «Свечение», «Тёмная», «AMOLED», «Размытие» and «Матовое стекло».
- **At the bottom, «Применить».**
  - It is enabled only when the selection differs from the icon in use.
  - Under it is one line: the launcher may move the icon or ask for it to be placed again.
  - It applies the icon once, and the row in «Оформление» shows the new icon when the switch
    returns.
- **The text follows the rest of the app.** Every string is in `values/` and `values-en/`, and every
  one goes through `correctedString` like every other row, so the correction mode reaches it. Every
  press gives the same haptic as the other settings pages.

## What else changes in the app

- **The splash screen.** `values-v31/themes.xml` pins `windowSplashScreenAnimatedIcon` to the old
  foreground, so every start drew the book whatever the launcher showed. The item goes, and Android 12
  and later then draws the icon of the component that was started. A start from the launcher draws
  the icon of the alias that was started; a start from the widget or an alert draws the application
  icon, the default.
- **«О приложении» and the onboarding** drew `ic_launcher_foreground` on `ic_launcher_background`.
  They draw the current icon, the whole adaptive drawable, through the same reader the page uses.
- **The old book goes:** `ic_launcher_foreground.xml`, the `ic_launcher_background` colour and the
  two `mipmap-anydpi-v26` files that used them.
- **The application's `android:icon` and `roundIcon`** point at the default, «Классика · Мята». That
  is what the system shows in Settings › Apps whichever alias is enabled.

## Resources

- **Naming.** `ic_launcher_<style>_<palette>` for the `mipmap-anydpi-v26` adaptive icon and
  `…_round` beside it. Each icon has two drawables, `_background` and `_foreground`.
- **Monochrome.** Two layers serve every icon's `<monochrome>`: the classic dial at 90 %, and the dial
  edge to edge.
- **Vector or bitmap.**
  - «Матовое стекло» and «Размытие» are the two whose look is a blur. Their layers are bitmaps,
    432 px, in `drawable-xxxhdpi`, converted to WebP; the system scales them down.
  - Every other layer is a VectorDrawable. A blur cannot be expressed in one, so «Свечение»'s halo is
    a radial gradient.
- **No legacy bitmaps.** `minSdk` is 26, so every device that runs the app takes adaptive icons, and
  there are no `mipmap-*dpi` PNGs.

## Taking variants out

The owner will pick the variants to keep. Taking one out is three deletions:
- its entry in the catalog (`AppIconStyle × AppIconPalette`);
- its `<activity-alias>`;
- its resources.

A JVM test reads the catalog, the manifest file and the `res/` tree, and fails on an alias the
catalog does not know, on a catalog entry without an alias, on an alias whose icon is not that
entry's resource, and on a resource no alias uses. So none of the three can be forgotten.

The reconciliation is what makes it safe on phones that chose a variant that has since gone.

## What was wrong in Essentials, and is not repeated here

| In Essentials | Why it is wrong | Here |
| --- | --- | --- |
| The default icon is `MainActivity` itself, and choosing another disables it | Every explicit start of the activity fails while it is disabled. Its static shortcuts (`android.app.shortcuts` meta-data on `MainActivity`) disappear with it | `MainActivity` is never disabled and is never a launcher entry |
| The choice is a preference (`AppIcon.fromKey`) | Auto Backup restores the preference but not the component, so the screen and the launcher disagree | The component state is the record |
| One `setComponentEnabledSetting` per alias, every time, all aliases | Five launcher re-indexes per switch, rewriting states that did not change; a process death between calls can leave two or none | One batch on 33+, two ordered calls below, only the changed components, and a reconciliation |
| An update that removes an alias is not handled | A phone that chose it has no launcher entry after the update | The reconciliation on `MY_PACKAGE_REPLACED` |
| The picker is one `Row` of icons spread with `SpaceBetween` | It does not scale past a handful; at sixty-four it does not fit | A page of eight groups |
| The tile draws the foreground only, on a white disc | Wrong for any icon with a ground of its own | The whole adaptive icon, masked |
| A tap applies the icon immediately | Every tap is a launcher re-index, and some launchers drop the home-screen place each time | Select, preview, then «Применить» once |

## Testing

- **JVM.**
  - The catalog, manifest and resources agree, as «Taking variants out» describes.
  - The switch's logic runs against a fake port over `PackageManager`:
    - the target is enabled before the old one is disabled below 33;
    - one batch on 33 and above;
    - no call for a component already in its state;
    - the reconciliation's cases: none enabled, two enabled, one removed alias, default only.
  - Robolectric: the receiver reconciles on `ACTION_MY_PACKAGE_REPLACED`.
  - The strings' parity is the existing `ResourceTranslationTest`.
- **CI** is what it always is: `./gradlew test`, both assembles, `detekt`.
- **Not covered, and said so in the pull request:**
  - any real launcher: how Pixel, One UI and MIUI place, re-index or clip;
  - themed icons on a device;
  - the edge-to-edge styles under a launcher whose mask is smaller than the standard circle.

  These need a phone or the emulator, which CI does not have.

## What this is not

- **No icon follows the theme by itself.** Android has no dark-mode launcher icon. «Тёмная» is a
  choice like the others.
- **No change on the server, in the widget's own drawing, or in notifications' small icon.**
- **No in-app shortcut to «Значок приложения» outside settings.**

## Delivery

One branch, `android/app-icons`, and one pull request on milestone 12, «v1.0.0 — A build somebody
else can install». A defect found on the way gets an issue before its fix, as every defect here does.

The credit to Essentials (MIT) is already in «О приложении» and the licenses sheet. Any file adapted
from it keeps its copyright header.
