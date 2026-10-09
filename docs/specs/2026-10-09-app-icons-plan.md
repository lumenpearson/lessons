# The app's icon, and a choice of sixty-four: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the reader choose the launcher icon from all sixty-four «Пятёрка» variants on a page of
its own under «Оформление». The switch goes through `activity-alias` components, Essentials' way,
without the defects Essentials has.

**Architecture:**
- Each icon is an `<activity-alias>` of `MainActivity` with its own adaptive icon, and exactly one is
  enabled.
- The enabled component is the only record of the choice.
- A pure-Kotlin `LauncherAliases` decides which components to change, over a one-method-each port
  (`LauncherComponents`). `PackageManagerComponents` carries that out on the platform.
- A process-wide `AppIconStore` serializes the writes, keeps them off the main thread and publishes
  the icon in use.
- The page, the row on «Оформление», «О приложении» and the onboarding mark all read that store.

**Tech Stack:**
- Kotlin and Jetpack Compose in `:app`, `PackageManager` component states, Robolectric 4.17 (pinned
  to SDK 34) and JUnit 4.
- For the resources: Python 3 generators, headless Chrome and Pillow.

**Spec:** `docs/specs/2026-10-09-app-icons-design.md`

## Global Constraints

- **SDK levels:** `minSdk` 26 and `compileSdk`/`targetSdk` 37. No new Gradle dependency.
- **Default icon:** «Классика · Мята», which is `AppIconStyle.CLASSIC` × `AppIconPalette.MYATA`, alias
  `.launcher.classic_myata`.
- **`MainActivity`:** never a launcher entry, never disabled. It keeps `android:exported="true"`.
- **Resource names** (`<key>` is `<style>_<palette>`):
  - `ic_launcher_<key>` and `ic_launcher_<key>_round` in `mipmap-anydpi-v26`;
  - `ic_launcher_<key>_background` and `ic_launcher_<key>_foreground` in `drawable/` (vector) or
    `drawable-xxxhdpi/` (WebP, for `glass` and `oneui` only);
  - the shared monochrome layers `ic_launcher_monochrome_classic` and `ic_launcher_monochrome_edge`.
- **Alias names:** `.launcher.<key>`, which resolves against the namespace to
  `com.lumenpearson.lessons.launcher.<key>`. A `ComponentName` uses `context.packageName` (which
  carries `.debug` in debug builds) with that full class name.
- **Style keys, in this order:** `classic`, `edge`, `glow`, `dark`, `glass`, `matte`, `oneui`,
  `amoled`.
- **Palette keys, in this order:** `rassvet`, `indigo`, `myata`, `lavanda`, `rassvet_light`,
  `indigo_light`, `myata_light`, `lavanda_light`.
- **Threading:** no `PackageManager` call runs on the main thread. Every component write carries
  `PackageManager.DONT_KILL_APP`.
- **Strings:**
  - Russian in `values/`, with the English twin in `values-en/` under the same name and the same
    format arguments (`ResourceTranslationTest`).
  - On screen, every string goes through `correctedString`, and text through
    `com.lumenpearson.lessons.core.designsystem.text.Text`, never Material's `Text`.
- **Haptics:** every press calls `LessonsHaptics.press(rememberHapticView())`.
- **Language:** everything that is not product text is English (code, comments, KDoc, commit
  messages). A quotation of product text keeps its Russian, in guillemets: «Применить».
- **Comments** say why, not what.
- **detekt:** it runs on main and test sources and new code adds no baseline entry.
  - Write numbers as top-level `private val`/`private const val` constants or as named arguments,
    for example `accentTone(slot = 3)`.
  - No `!!`.
- **Compose tests:** a test that composes a `GroupItem` or a `SettingsGroup` either holds the clock
  or carries a `// marquee clock: <reason, 20+ chars>` comment (`MarqueeClockTest`). A test that
  scrolls with `performScrollTo` must not hold the clock.
- **Commits:**
  - One English sentence saying what the change makes the project do, with a body that explains the
    reasoning.
  - No Conventional Commits prefix, and no trailer of any kind.
- **Gradle:** run it from `android/`, and only one Gradle job at a time. A single test class runs as
  `./gradlew :app:testDebugUnitTest --tests '*ClassName*'`.

## Review Focus

These are inputs the spec implies but none of its own tests exercises. Each one's test is written
into the task that owns the code.

1. **A switch the platform refuses.** `PackageManager` throws `SecurityException` or
   `IllegalArgumentException`, for example for an alias missing from a bad build.
   - Expected: one snackbar «Не удалось сменить значок», the icon in use unchanged, and «Применить»
     usable again.
   - Tests: Task 4 (`AppIconStoreTest`) and Task 6 (`AppIconViewModelTest`).
2. **«Применить» pressed twice before the first switch returns.**
   - Expected: exactly one switch.
   - Test: Task 6 (`AppIconViewModelTest`).
3. **The page opened before the launcher has been read.** This happens on a cold start, while the
   reconcile is still running.
   - Expected: the default shown as current, then the real icon when the read lands, and no crash.
   - Tests: Task 4 (`AppIconStoreTest`) and Task 6 (`AppIconViewModelTest`).
4. **A catalog the owner has trimmed:** whole styles gone, and styles with fewer than eight palettes.
   - Expected: no empty group, no crash, and short rows aligned to the columns above.
   - Test: Task 6 (`AppIconRowsTest`).
5. **A tile read by TalkBack.**
   - Expected: it is announced as «Свечение · Мята», with whether it is the one chosen.
   - Test: Task 6 (`AppIconRowsTest`).

A sixth consequence cannot be tested here; say it in the pull request. A phone that updates from a
build where `MainActivity` was the launcher entry loses the home-screen shortcut that pointed at it.
The default alias appears in the app list instead. Nothing has been released, so this concerns
development installs only.

---

### Task 1: The logo's generators in the repository, and the sixty-four icons they write

**Files:**
- Create: `android/logo/gen.py`, `android/logo/clock.py`, `android/logo/build_pack.py` and
  `android/logo/build_styles.py`. Each is copied verbatim.
- Create: `android/logo/export_android.py` and `android/logo/README.md`.
- Modify: `.gitignore`, by appending a «Logo» block.
- Create, by running the script:
  - 128 files `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_<key>{,_round}.xml`;
  - 96 vector layers in `android/app/src/main/res/drawable/`;
  - 2 monochrome layers in `android/app/src/main/res/drawable/`;
  - 32 WebP layers in `android/app/src/main/res/drawable-xxxhdpi/`.

**Interfaces:**
- Consumes: nothing.
- Produces: the resource names in Global Constraints. Every later task refers to them.

- [ ] **Step 1: Copy the four generators verbatim**

Copy these four files, unchanged, from `C:\Users\lumen\OneDrive\Документы\Dnevnik-logo\logo\` to
`android/logo/`. That is the owner's design folder, outside the repository.

| File | Its job |
| --- | --- |
| `gen.py` | geometry primitives |
| `clock.py` | dial, hands, marks, trail, palettes |
| `build_pack.py` | the «Классика» kit |
| `build_styles.py` | the seven edge-to-edge styles |

Do not edit them. `export_android.py` imports them as they are.

```bash
mkdir -p android/logo
cp "/c/Users/lumen/OneDrive/Документы/Dnevnik-logo/logo/"{gen,clock,build_pack,build_styles}.py android/logo/
```

- [ ] **Step 2: Write `android/logo/export_android.py`**

```python
"""Write the app's launcher icons into android/app/src/main/res.

The app offers every «Пятёрка» variant (docs/specs/2026-10-09-app-icons-design.md): eight
styles by eight palettes. «Классика» is build_pack.py's mark at 90 % of the visible circle on
white; the other seven are build_styles.py's, drawn edge to edge. Each icon is four resources
named ic_launcher_<style>_<palette>:

    mipmap-anydpi-v26/<name>.xml, <name>_round.xml       the adaptive icon, twice
    drawable/<name>_background.xml, _foreground.xml      its two layers, as VectorDrawables
    drawable-xxxhdpi/<name>_background.webp, _foreground.webp
                                                         the layers instead, for the two styles
                                                         whose look is a blur: 432 px, rendered
                                                         by headless Chrome, encoded by Pillow

and every icon's monochrome layer is one of two shared drawables: the classic dial
(ic_launcher_monochrome_classic) or the edge-to-edge one (ic_launcher_monochrome_edge).

    python android/logo/export_android.py              everything; needs Chrome and Pillow
    python android/logo/export_android.py --no-raster  vectors only; the WebP layers stay

It writes all sixty-four. Once the owner has taken variants out of the app, the files of the
ones taken out have to be deleted again after a run; AppIconCatalogTest names any it finds.
"""
from __future__ import annotations

import argparse
import base64
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import build_pack as bp  # noqa: E402  (beside this file)
import build_styles as bs  # noqa: E402
import clock as c  # noqa: E402

RES = HERE.parent / "app" / "src" / "main" / "res"
# The order of the picker's groups, and of the catalog in AppIconCatalog.kt.
STYLES = ["classic", *bs.STYLES]
MONO_CLASSIC = "ic_launcher_monochrome_classic"
MONO_EDGE = "ic_launcher_monochrome_edge"
SIZE = 432  # 108 dp at xxxhdpi's 4 px per dp; the system scales it down for every other density
WHITE = "M0 0 H108 V108 H0 Z"
CHROMES = [
    os.environ.get("CHROME", ""),
    "chrome", "google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
]


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def adaptive(name: str, mono: str) -> str:
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="@drawable/{name}_background"/>\n'
            f'    <foreground android:drawable="@drawable/{name}_foreground"/>\n'
            f'    <monochrome android:drawable="@drawable/{mono}"/>\n'
            "</adaptive-icon>\n")


def classic_background(key: str) -> str:
    note = f"«Дневник», «Классика», palette «{c.PALETTES[key][0]}». Ground."
    return bs.vd_doc(note, [bp.vd_path(WHITE, "    ", fill="#FFFFFFFF")], grouped=False)


def clean(res: Path, raster: bool) -> None:
    """Deletes what a run writes, so a renamed or dropped icon leaves nothing behind."""
    folders = ["drawable", "mipmap-anydpi-v26"] + (["drawable-xxxhdpi"] if raster else [])
    for folder in folders:
        for style in STYLES:
            for path in (res / folder).glob(f"ic_launcher_{style}_*"):
                path.unlink()
    for mono in (MONO_CLASSIC, MONO_EDGE):
        (res / "drawable" / f"{mono}.xml").unlink(missing_ok=True)


def find_chrome() -> str:
    for candidate in CHROMES:
        if not candidate:
            continue
        found = candidate if os.path.isabs(candidate) and os.path.exists(candidate) else shutil.which(candidate)
        if found:
            return found
    sys.exit("No Chrome found. Set CHROME to its executable, or run with --no-raster.")


def stop(proc: subprocess.Popen) -> None:
    """Chrome keeps helper processes alive on Windows, so the whole tree goes."""
    if proc.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()


def render_png(chrome: str, svg: str, out: Path) -> None:
    """One layer's SVG to a transparent SIZE × SIZE PNG, through a headless Chrome screenshot."""
    data = base64.b64encode(svg.encode("utf-8")).decode("ascii")
    page = (f"<!doctype html><html><head><style>html,body{{margin:0;padding:0;background:transparent;"
            f"width:{SIZE}px;height:{SIZE}px;overflow:hidden}}img{{display:block;width:{SIZE}px;"
            f"height:{SIZE}px}}</style></head><body><img src='data:image/svg+xml;base64,{data}'></body></html>")
    tmp = Path(tempfile.mkdtemp())
    try:
        html = tmp / "layer.html"
        html.write_text(page, encoding="utf-8")
        shot = tmp / "shot.png"
        cmd = [chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
               "--no-default-browser-check", "--disable-extensions", "--force-device-scale-factor=1",
               "--default-background-color=00000000", f"--window-size={SIZE},{SIZE}",
               f"--screenshot={shot}", f"--user-data-dir={tmp / 'profile'}", html.as_uri()]
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline, last = time.time() + 60, -1
        while time.time() < deadline:
            if shot.exists():
                size = shot.stat().st_size
                if size > 0 and size == last:
                    break
                last = size
            if proc.poll() is not None and shot.exists():
                break
            time.sleep(0.25)
        stop(proc)
        if not shot.exists():
            raise RuntimeError(f"Chrome wrote no screenshot for {out.name}")
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(shot), out)
    finally:
        for _ in range(10):
            shutil.rmtree(tmp, ignore_errors=True)
            if not tmp.exists():
                break
            time.sleep(0.3)


def to_webp(png: Path, webp: Path) -> None:
    from PIL import Image  # only the raster path needs Pillow

    with Image.open(png) as image:
        if image.size != (SIZE, SIZE):
            raise RuntimeError(f"{png.name} is {image.size}, not {SIZE} × {SIZE}")
        image.save(webp, "WEBP", quality=90, alpha_quality=100, method=6)
    png.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description="Write the app's launcher icons.")
    parser.add_argument("--no-raster", action="store_true", help="leave the WebP layers as they are")
    parser.add_argument("--res", type=Path, default=RES, help="the res/ folder to write into")
    args = parser.parse_args()
    res, raster = args.res, not args.no_raster

    clean(res, raster)
    write(res / "drawable" / f"{MONO_CLASSIC}.xml", bp.vd_foreground(None))
    write(res / "drawable" / f"{MONO_EDGE}.xml", bs.monochrome_edge())
    jobs: list[tuple[str, Path]] = []
    for style in STYLES:
        mono = MONO_CLASSIC if style == "classic" else MONO_EDGE
        for key in bp.KEYS:
            palette = bp.res_name(key)
            name = f"ic_launcher_{style}_{palette}"
            if style == "classic":
                write(res / "drawable" / f"{name}_background.xml", classic_background(key))
                write(res / "drawable" / f"{name}_foreground.xml", bp.vd_foreground(key))
            elif style in bs.RASTER:
                for which in ("background", "foreground"):
                    inner = bs.layer(key, style, which, p=f"{style}_{palette}{which[0]}")
                    jobs.append((bs.doc(inner, view="0 0 108 108", size=SIZE),
                                 res / "drawable-xxxhdpi" / f"{name}_{which}.webp"))
            else:
                background, foreground = bs.vd_layers(key, style)
                write(res / "drawable" / f"{name}_background.xml", background)
                write(res / "drawable" / f"{name}_foreground.xml", foreground)
            text = adaptive(name, mono)
            write(res / "mipmap-anydpi-v26" / f"{name}.xml", text)
            write(res / "mipmap-anydpi-v26" / f"{name}_round.xml", text)

    if raster:
        chrome = find_chrome()
        for done, (svg, webp) in enumerate(jobs, 1):
            png = webp.with_suffix(".png")
            render_png(chrome, svg, png)
            to_webp(png, webp)
            print(f"[{done}/{len(jobs)}] {webp.name}")
    print("icons written to", res)


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Write `android/logo/README.md`**

```markdown
# The logo

«Пятёрка»: a twelve-lobe dial, shaped like the Material 3 Expressive cookie, whose hands read five
o'clock. Behind it runs a trail of turned, fading copies, the device Essentials' own icon uses. It was
designed on 9 October 2026; `docs/specs/2026-10-09-app-icons-design.md` says how the app offers it.

| File | What it is |
| --- | --- |
| `gen.py` | The geometry primitives the dial is built from (pills, arcs, outlines), and the first round's concepts |
| `clock.py` | The dial, the hands, the quarter marks, the trail and the eight palettes |
| `build_pack.py` | The «Классика» kit: the dial at 90 % of the visible circle on white, in every format |
| `build_styles.py` | The seven other styles, drawn edge to edge: the lobe tips on the visible circle |
| `export_android.py` | The app's sixty-four launcher icons, written into `android/app/src/main/res` |

## Rebuilding the app's icons

    python android/logo/export_android.py              # everything
    python android/logo/export_android.py --no-raster  # the vectors only

The two styles whose look is a blur, «Матовое стекло» and «Размытие», are bitmaps, because a
VectorDrawable cannot blur. They are rendered by headless Chrome and encoded as WebP by Pillow:
`pip install pillow`, and set `CHROME` if Chrome is not on the `PATH` or in its usual place. The run
takes about a minute, almost all of it Chrome. `--no-raster` needs neither, and leaves the WebP
layers as they are.

The script writes all sixty-four. Once variants have been taken out of the app (the spec, «Taking
variants out»), delete their files again after a run. Then

    ./gradlew :app:testDebugUnitTest --tests '*AppIconCatalogTest*'

from `android/` says whether the resources, the manifest's aliases and `AppIconCatalog` still agree,
and names whatever is left over.

`build_pack.py` and `build_styles.py` also run on their own. They write the whole kit (SVG, PNG and
previews) into `android/pack/`, which git ignores. Their PNGs need the logo-design skill's
`render_png.py`, whose path is `build_pack.RENDER`.
```

- [ ] **Step 4: Ignore what the kit generators write**

Append to `.gitignore`:

```gitignore

# ---- Logo ----
# build_pack.py and build_styles.py, run on their own, write the whole logo kit
# (SVG, PNG, previews) into android/pack, beside android/logo. Only
# export_android.py's output, under android/app/src/main/res, belongs in the
# repository.
android/pack/
```

- [ ] **Step 5: Run the export**

Pillow must be importable. If `python -c "import PIL"` fails, install it outside the repository, for
example:
- `python -m pip install --target <a scratch folder> pillow`;
- then prefix the run with `PYTHONPATH=<that folder>`.

Run from the repository root:

```bash
python android/logo/export_android.py
```

Expected: thirty-two lines `[1/32] ic_launcher_glass_rassvet_background.webp` … `[32/32] …`, then
`icons written to …android\app\src\main\res`.

- [ ] **Step 6: Check what was written**

```bash
ls android/app/src/main/res/mipmap-anydpi-v26 | wc -l    # 130: 128 new, plus the old ic_launcher.xml and ic_launcher_round.xml
ls android/app/src/main/res/drawable | wc -l             # 99: 96 layers, 2 monochrome, plus the old ic_launcher_foreground.xml
ls android/app/src/main/res/drawable-xxxhdpi | wc -l     # 32
git status --short android/logo .gitignore               # the six files and .gitignore, no __pycache__
```

- [ ] **Step 7: Prove every resource compiles**

From `android/`:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. aapt2 compiles all 258 new resources. The new resources are not used
yet, which is expected.

- [ ] **Step 8: Commit**

```bash
git add .gitignore android/logo android/app/src/main/res/mipmap-anydpi-v26 android/app/src/main/res/drawable android/app/src/main/res/drawable-xxxhdpi
git commit -m "Bring the logo's generators into the repository, and the sixty-four launcher icons they write" -m "The «Пятёрка» mark was designed outside the repository. Its generators come in verbatim so that the app's resources can be rebuilt from something here. export_android.py writes the eight styles by eight palettes the spec offers, under one naming scheme: vectors for six styles, and 432 px WebP for the two whose look is a blur. Nothing uses them yet; the next commits put them on the launcher."
```

---

### Task 2: The catalog, the aliases, and the default on the launcher

**Files:**
- Create: `android/app/src/main/kotlin/com/lumenpearson/lessons/appicon/AppIconCatalog.kt`
- Create: `android/app/src/main/res/values/strings_app_icon.xml`
- Create: `android/app/src/main/res/values-en/strings_app_icon.xml`
- Modify: `android/app/src/main/AndroidManifest.xml`. It gets the application icon, `MainActivity`
  without its launcher filter, and 64 aliases.
- Modify: `android/app/src/main/res/values-v31/themes.xml`, to drop the splash icon pin.
- Delete: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`.
- Test: `android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/AppIconCatalogTest.kt` (JVM)
- Test: `android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/AppIconResourcesTest.kt` (Robolectric)

**Interfaces:**
- Consumes: the Task 1 resources `R.mipmap.ic_launcher_<key>`.
- Produces, in package `com.lumenpearson.lessons.appicon`:
  - `enum class AppIconStyle(val key: String, @StringRes val labelRes: Int)` with entries `CLASSIC`,
    `EDGE`, `GLOW`, `DARK`, `GLASS`, `MATTE`, `ONEUI` and `AMOLED`;
  - `enum class AppIconPalette(val key: String, @StringRes val labelRes: Int)` with entries
    `RASSVET`, `INDIGO`, `MYATA`, `LAVANDA`, `RASSVET_LIGHT`, `INDIGO_LIGHT`, `MYATA_LIGHT` and
    `LAVANDA_LIGHT`;
  - `data class AppIconVariant(style, palette, @DrawableRes icon: Int)` with `key: String`,
    `resourceName: String` and `alias: String`;
  - `object AppIconCatalog` with `variants: List<AppIconVariant>`, `default: AppIconVariant` and
    `byKey(key: String?): AppIconVariant?`;
  - the strings `R.string.app_icon_style_*`, `R.string.app_icon_palette_*` and
    `R.string.app_icon_variant`.

- [ ] **Step 1: Write the failing JVM test `AppIconCatalogTest.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The catalog, the manifest's aliases and res/ name the same icons.
 *
 * Taking a variant out is three deletions in three places, and the owner is
 * going to take most of them out (docs/specs/2026-10-09-app-icons-design.md,
 * «Taking variants out»). A forgotten alias is a launcher entry the switch
 * never turns off; a forgotten catalog line is a tile whose alias does not
 * exist, which is an exception on «Применить»; forgotten files are dead weight
 * in every APK. Each of those fails here, by name.
 */
class AppIconCatalogTest {

    private val manifest: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .documentElement
    private val application = manifest.descendants("application").single()
    private val aliases = application.descendants("activity-alias")
    private val res = File("src/main/res")

    @Test
    fun `every catalog entry has an alias and every alias an entry, in catalog order`() {
        assertEquals(
            AppIconCatalog.variants.map { ".launcher.${it.key}" },
            aliases.map { it.getAttribute("android:name") },
        )
    }

    @Test
    fun `each alias opens MainActivity from the launcher under its own icon`() {
        for ((alias, variant) in aliases.zip(AppIconCatalog.variants)) {
            val name = variant.resourceName
            assertEquals(variant.key, ".MainActivity", alias.getAttribute("android:targetActivity"))
            assertEquals(variant.key, "true", alias.getAttribute("android:exported"))
            assertEquals(variant.key, "@mipmap/$name", alias.getAttribute("android:icon"))
            assertEquals(variant.key, "@mipmap/${name}_round", alias.getAttribute("android:roundIcon"))
            assertTrue("${variant.key} is not a launcher entry", alias.isLauncherEntry())
        }
    }

    @Test
    fun `only the default is enabled before any code runs`() {
        val enabled = aliases.zip(AppIconCatalog.variants)
            .filter { (alias, _) -> alias.getAttribute("android:enabled") == "true" }
            .map { (_, variant) -> variant }
        assertEquals(listOf(AppIconCatalog.default), enabled)
        // Said outright on every alias: one with no android:enabled is enabled,
        // and a fresh install would show two icons.
        assertTrue(aliases.all { it.getAttribute("android:enabled") in setOf("true", "false") })
    }

    @Test
    fun `MainActivity is never a launcher entry and never disabled`() {
        val main = application.descendants("activity")
            .single { it.getAttribute("android:name") == ".MainActivity" }
        assertFalse(main.isLauncherEntry())
        assertNotEquals("false", main.getAttribute("android:enabled"))
    }

    @Test
    fun `the application's own icon is the default`() {
        val name = AppIconCatalog.default.resourceName
        assertEquals("@mipmap/$name", application.getAttribute("android:icon"))
        assertEquals("@mipmap/${name}_round", application.getAttribute("android:roundIcon"))
    }

    @Test
    fun `each entry's adaptive icons point at its own layers`() {
        for (variant in AppIconCatalog.variants) {
            val name = variant.resourceName
            for (file in listOf("$name.xml", "${name}_round.xml")) {
                val icon = File(res, "mipmap-anydpi-v26/$file")
                assertTrue("missing ${icon.path}", icon.isFile)
                val text = icon.readText()
                assertTrue("$file: not its own background", "@drawable/${name}_background\"" in text)
                assertTrue("$file: not its own foreground", "@drawable/${name}_foreground\"" in text)
                assertTrue("$file: no shared monochrome layer", MonochromeLayers.any { "@drawable/$it\"" in text })
            }
            for (layer in listOf("background", "foreground")) {
                assertTrue("no $layer layer for ${variant.key}", layerExists("${name}_$layer"))
            }
        }
    }

    private fun layerExists(stem: String): Boolean =
        listOf("drawable/$stem.xml", "drawable-xxxhdpi/$stem.webp").any { File(res, it).isFile }

    private companion object {
        /** The two monochrome layers every icon shares: the classic dial, and the dial edge to edge. */
        val MonochromeLayers = listOf("ic_launcher_monochrome_classic", "ic_launcher_monochrome_edge")

        fun Element.descendants(tag: String): List<Element> =
            getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

        fun Element.isLauncherEntry(): Boolean = descendants("category")
            .any { it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }
    }
}
```

- [ ] **Step 2: Write the failing Robolectric test `AppIconResourcesTest.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the JVM test cannot see: that each catalog line's `R.mipmap` id is the
 * resource its style and palette name, and that it loads.
 *
 * Sixty-four lines of `AppIconVariant(GLOW, MYATA, R.mipmap.ic_launcher_glow_myata)`
 * are sixty-four chances to pair a tile's name with another tile's picture, and
 * the compiler is satisfied by any id.
 */
@RunWith(RobolectricTestRunner::class)
class AppIconResourcesTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `each entry's icon is the resource its name says`() {
        for (variant in AppIconCatalog.variants) {
            assertEquals(variant.key, "mipmap", context.resources.getResourceTypeName(variant.icon))
            assertEquals(variant.key, variant.resourceName, context.resources.getResourceEntryName(variant.icon))
        }
    }

    @Test
    fun `each entry's icon loads as an adaptive icon with both layers`() {
        for (variant in AppIconCatalog.variants) {
            val icon = context.getDrawable(variant.icon)
            assertTrue("${variant.key} is ${icon?.javaClass}", icon is AdaptiveIconDrawable)
            val adaptive = icon as AdaptiveIconDrawable
            assertNotNull(variant.key, adaptive.background)
            assertNotNull(variant.key, adaptive.foreground)
        }
    }
}
```

- [ ] **Step 3: Run both and see them fail to compile**

```bash
./gradlew :app:testDebugUnitTest --tests '*AppIconCatalogTest*' --tests '*AppIconResourcesTest*'
```

Expected: compilation fails, because `AppIconCatalog` does not exist.

- [ ] **Step 4: Write the strings**

`android/app/src/main/res/values/strings_app_icon.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
      The launcher icon's looks and colours (docs/specs/2026-10-09-app-icons-design.md),
      named as the page «Значок приложения» shows them. A palette's light twin names
      its hue first, so the two sit together when read.
    -->
    <string name="app_icon_style_classic">Классика</string>
    <string name="app_icon_style_edge">Край в край</string>
    <string name="app_icon_style_glow">Свечение</string>
    <string name="app_icon_style_dark">Тёмная</string>
    <string name="app_icon_style_glass">Матовое стекло</string>
    <string name="app_icon_style_matte">Матовая</string>
    <string name="app_icon_style_oneui">Размытие</string>
    <string name="app_icon_style_amoled">AMOLED</string>

    <string name="app_icon_palette_rassvet">Рассвет</string>
    <string name="app_icon_palette_indigo">Индиго</string>
    <string name="app_icon_palette_myata">Мята</string>
    <string name="app_icon_palette_lavanda">Лаванда</string>
    <string name="app_icon_palette_rassvet_light">Рассвет, светлый</string>
    <string name="app_icon_palette_indigo_light">Индиго, светлый</string>
    <string name="app_icon_palette_myata_light">Мята, светлая</string>
    <string name="app_icon_palette_lavanda_light">Лаванда, светлая</string>

    <!-- A style and a palette: «Свечение · Мята». -->
    <string name="app_icon_variant">%1$s · %2$s</string>
</resources>
```

`android/app/src/main/res/values-en/strings_app_icon.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_icon_style_classic">Classic</string>
    <string name="app_icon_style_edge">Edge to edge</string>
    <string name="app_icon_style_glow">Glow</string>
    <string name="app_icon_style_dark">Dark</string>
    <string name="app_icon_style_glass">Frosted glass</string>
    <string name="app_icon_style_matte">Matte</string>
    <string name="app_icon_style_oneui">Blur</string>
    <string name="app_icon_style_amoled">AMOLED</string>

    <string name="app_icon_palette_rassvet">Dawn</string>
    <string name="app_icon_palette_indigo">Indigo</string>
    <string name="app_icon_palette_myata">Mint</string>
    <string name="app_icon_palette_lavanda">Lavender</string>
    <string name="app_icon_palette_rassvet_light">Dawn, light</string>
    <string name="app_icon_palette_indigo_light">Indigo, light</string>
    <string name="app_icon_palette_myata_light">Mint, light</string>
    <string name="app_icon_palette_lavanda_light">Lavender, light</string>

    <string name="app_icon_variant">%1$s · %2$s</string>
</resources>
```

- [ ] **Step 5: Write `AppIconCatalog.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.appicon.AppIconPalette.INDIGO
import com.lumenpearson.lessons.appicon.AppIconPalette.INDIGO_LIGHT
import com.lumenpearson.lessons.appicon.AppIconPalette.LAVANDA
import com.lumenpearson.lessons.appicon.AppIconPalette.LAVANDA_LIGHT
import com.lumenpearson.lessons.appicon.AppIconPalette.MYATA
import com.lumenpearson.lessons.appicon.AppIconPalette.MYATA_LIGHT
import com.lumenpearson.lessons.appicon.AppIconPalette.RASSVET
import com.lumenpearson.lessons.appicon.AppIconPalette.RASSVET_LIGHT
import com.lumenpearson.lessons.appicon.AppIconStyle.AMOLED
import com.lumenpearson.lessons.appicon.AppIconStyle.CLASSIC
import com.lumenpearson.lessons.appicon.AppIconStyle.DARK
import com.lumenpearson.lessons.appicon.AppIconStyle.EDGE
import com.lumenpearson.lessons.appicon.AppIconStyle.GLASS
import com.lumenpearson.lessons.appicon.AppIconStyle.GLOW
import com.lumenpearson.lessons.appicon.AppIconStyle.MATTE
import com.lumenpearson.lessons.appicon.AppIconStyle.ONEUI

/**
 * One look of the mark, in the order «Значок приложения» shows its groups.
 *
 * [key] is the first half of every name a variant has: its resources
 * (`ic_launcher_<style>_<palette>`), its alias (`.launcher.<style>_<palette>`)
 * and the generator's (android/logo/export_android.py).
 */
enum class AppIconStyle(val key: String, @param:StringRes val labelRes: Int) {
    CLASSIC("classic", R.string.app_icon_style_classic),
    EDGE("edge", R.string.app_icon_style_edge),
    GLOW("glow", R.string.app_icon_style_glow),
    DARK("dark", R.string.app_icon_style_dark),
    GLASS("glass", R.string.app_icon_style_glass),
    MATTE("matte", R.string.app_icon_style_matte),
    ONEUI("oneui", R.string.app_icon_style_oneui),
    AMOLED("amoled", R.string.app_icon_style_amoled),
}

/**
 * One colouring of the mark: four hues, saturated first and light after, so
 * that a group's two rows of tiles are the four hues twice.
 */
enum class AppIconPalette(val key: String, @param:StringRes val labelRes: Int) {
    RASSVET("rassvet", R.string.app_icon_palette_rassvet),
    INDIGO("indigo", R.string.app_icon_palette_indigo),
    MYATA("myata", R.string.app_icon_palette_myata),
    LAVANDA("lavanda", R.string.app_icon_palette_lavanda),
    RASSVET_LIGHT("rassvet_light", R.string.app_icon_palette_rassvet_light),
    INDIGO_LIGHT("indigo_light", R.string.app_icon_palette_indigo_light),
    MYATA_LIGHT("myata_light", R.string.app_icon_palette_myata_light),
    LAVANDA_LIGHT("lavanda_light", R.string.app_icon_palette_lavanda_light),
}

/** One launcher icon the app offers: a style in a palette, and the adaptive icon that draws it. */
data class AppIconVariant(
    val style: AppIconStyle,
    val palette: AppIconPalette,
    @param:DrawableRes val icon: Int,
) {
    val key: String get() = "${style.key}_${palette.key}"

    val resourceName: String get() = "ic_launcher_$key"

    /** The `<activity-alias>` that puts this icon on the launcher, as a class name. */
    val alias: String get() = "$AliasPackage.$key"
}

/**
 * Every icon the app offers, in the order the page shows them.
 *
 * Taking one out is three deletions: its line here, its `<activity-alias>` in
 * the manifest, and its four resources. `AppIconCatalogTest` fails until all
 * three are gone, and `LauncherAliases.reconcile` moves a phone that had chosen
 * it back to [default] on the update.
 */
object AppIconCatalog {

    val variants: List<AppIconVariant> = listOf(
        AppIconVariant(CLASSIC, RASSVET, R.mipmap.ic_launcher_classic_rassvet),
        AppIconVariant(CLASSIC, INDIGO, R.mipmap.ic_launcher_classic_indigo),
        AppIconVariant(CLASSIC, MYATA, R.mipmap.ic_launcher_classic_myata),
        AppIconVariant(CLASSIC, LAVANDA, R.mipmap.ic_launcher_classic_lavanda),
        AppIconVariant(CLASSIC, RASSVET_LIGHT, R.mipmap.ic_launcher_classic_rassvet_light),
        AppIconVariant(CLASSIC, INDIGO_LIGHT, R.mipmap.ic_launcher_classic_indigo_light),
        AppIconVariant(CLASSIC, MYATA_LIGHT, R.mipmap.ic_launcher_classic_myata_light),
        AppIconVariant(CLASSIC, LAVANDA_LIGHT, R.mipmap.ic_launcher_classic_lavanda_light),
        AppIconVariant(EDGE, RASSVET, R.mipmap.ic_launcher_edge_rassvet),
        AppIconVariant(EDGE, INDIGO, R.mipmap.ic_launcher_edge_indigo),
        AppIconVariant(EDGE, MYATA, R.mipmap.ic_launcher_edge_myata),
        AppIconVariant(EDGE, LAVANDA, R.mipmap.ic_launcher_edge_lavanda),
        AppIconVariant(EDGE, RASSVET_LIGHT, R.mipmap.ic_launcher_edge_rassvet_light),
        AppIconVariant(EDGE, INDIGO_LIGHT, R.mipmap.ic_launcher_edge_indigo_light),
        AppIconVariant(EDGE, MYATA_LIGHT, R.mipmap.ic_launcher_edge_myata_light),
        AppIconVariant(EDGE, LAVANDA_LIGHT, R.mipmap.ic_launcher_edge_lavanda_light),
        AppIconVariant(GLOW, RASSVET, R.mipmap.ic_launcher_glow_rassvet),
        AppIconVariant(GLOW, INDIGO, R.mipmap.ic_launcher_glow_indigo),
        AppIconVariant(GLOW, MYATA, R.mipmap.ic_launcher_glow_myata),
        AppIconVariant(GLOW, LAVANDA, R.mipmap.ic_launcher_glow_lavanda),
        AppIconVariant(GLOW, RASSVET_LIGHT, R.mipmap.ic_launcher_glow_rassvet_light),
        AppIconVariant(GLOW, INDIGO_LIGHT, R.mipmap.ic_launcher_glow_indigo_light),
        AppIconVariant(GLOW, MYATA_LIGHT, R.mipmap.ic_launcher_glow_myata_light),
        AppIconVariant(GLOW, LAVANDA_LIGHT, R.mipmap.ic_launcher_glow_lavanda_light),
        AppIconVariant(DARK, RASSVET, R.mipmap.ic_launcher_dark_rassvet),
        AppIconVariant(DARK, INDIGO, R.mipmap.ic_launcher_dark_indigo),
        AppIconVariant(DARK, MYATA, R.mipmap.ic_launcher_dark_myata),
        AppIconVariant(DARK, LAVANDA, R.mipmap.ic_launcher_dark_lavanda),
        AppIconVariant(DARK, RASSVET_LIGHT, R.mipmap.ic_launcher_dark_rassvet_light),
        AppIconVariant(DARK, INDIGO_LIGHT, R.mipmap.ic_launcher_dark_indigo_light),
        AppIconVariant(DARK, MYATA_LIGHT, R.mipmap.ic_launcher_dark_myata_light),
        AppIconVariant(DARK, LAVANDA_LIGHT, R.mipmap.ic_launcher_dark_lavanda_light),
        AppIconVariant(GLASS, RASSVET, R.mipmap.ic_launcher_glass_rassvet),
        AppIconVariant(GLASS, INDIGO, R.mipmap.ic_launcher_glass_indigo),
        AppIconVariant(GLASS, MYATA, R.mipmap.ic_launcher_glass_myata),
        AppIconVariant(GLASS, LAVANDA, R.mipmap.ic_launcher_glass_lavanda),
        AppIconVariant(GLASS, RASSVET_LIGHT, R.mipmap.ic_launcher_glass_rassvet_light),
        AppIconVariant(GLASS, INDIGO_LIGHT, R.mipmap.ic_launcher_glass_indigo_light),
        AppIconVariant(GLASS, MYATA_LIGHT, R.mipmap.ic_launcher_glass_myata_light),
        AppIconVariant(GLASS, LAVANDA_LIGHT, R.mipmap.ic_launcher_glass_lavanda_light),
        AppIconVariant(MATTE, RASSVET, R.mipmap.ic_launcher_matte_rassvet),
        AppIconVariant(MATTE, INDIGO, R.mipmap.ic_launcher_matte_indigo),
        AppIconVariant(MATTE, MYATA, R.mipmap.ic_launcher_matte_myata),
        AppIconVariant(MATTE, LAVANDA, R.mipmap.ic_launcher_matte_lavanda),
        AppIconVariant(MATTE, RASSVET_LIGHT, R.mipmap.ic_launcher_matte_rassvet_light),
        AppIconVariant(MATTE, INDIGO_LIGHT, R.mipmap.ic_launcher_matte_indigo_light),
        AppIconVariant(MATTE, MYATA_LIGHT, R.mipmap.ic_launcher_matte_myata_light),
        AppIconVariant(MATTE, LAVANDA_LIGHT, R.mipmap.ic_launcher_matte_lavanda_light),
        AppIconVariant(ONEUI, RASSVET, R.mipmap.ic_launcher_oneui_rassvet),
        AppIconVariant(ONEUI, INDIGO, R.mipmap.ic_launcher_oneui_indigo),
        AppIconVariant(ONEUI, MYATA, R.mipmap.ic_launcher_oneui_myata),
        AppIconVariant(ONEUI, LAVANDA, R.mipmap.ic_launcher_oneui_lavanda),
        AppIconVariant(ONEUI, RASSVET_LIGHT, R.mipmap.ic_launcher_oneui_rassvet_light),
        AppIconVariant(ONEUI, INDIGO_LIGHT, R.mipmap.ic_launcher_oneui_indigo_light),
        AppIconVariant(ONEUI, MYATA_LIGHT, R.mipmap.ic_launcher_oneui_myata_light),
        AppIconVariant(ONEUI, LAVANDA_LIGHT, R.mipmap.ic_launcher_oneui_lavanda_light),
        AppIconVariant(AMOLED, RASSVET, R.mipmap.ic_launcher_amoled_rassvet),
        AppIconVariant(AMOLED, INDIGO, R.mipmap.ic_launcher_amoled_indigo),
        AppIconVariant(AMOLED, MYATA, R.mipmap.ic_launcher_amoled_myata),
        AppIconVariant(AMOLED, LAVANDA, R.mipmap.ic_launcher_amoled_lavanda),
        AppIconVariant(AMOLED, RASSVET_LIGHT, R.mipmap.ic_launcher_amoled_rassvet_light),
        AppIconVariant(AMOLED, INDIGO_LIGHT, R.mipmap.ic_launcher_amoled_indigo_light),
        AppIconVariant(AMOLED, MYATA_LIGHT, R.mipmap.ic_launcher_amoled_myata_light),
        AppIconVariant(AMOLED, LAVANDA_LIGHT, R.mipmap.ic_launcher_amoled_lavanda_light),
    )

    /**
     * «Классика · Мята»: the dial at 90 % on white, inside every mask's safe
     * zone, so no launcher clips a lobe. The manifest enables its alias alone.
     */
    val default: AppIconVariant = variants.single { it.style == CLASSIC && it.palette == MYATA }

    /** `null` for a key this build does not have, `null` included. */
    fun byKey(key: String?): AppIconVariant? = variants.firstOrNull { it.key == key }
}

/**
 * Where the manifest's `.launcher.` aliases resolve: against the namespace,
 * not the application id, which carries `.debug` in a debug build.
 */
private const val AliasPackage = "com.lumenpearson.lessons.launcher"
```

- [ ] **Step 6: Rewrite the manifest with a one-off script (not committed)**

Save the following to a scratch file outside the repository and run it from the repository root.
It asserts the text it replaces. If an assertion fails, the manifest has moved, so stop and read it.

```python
from pathlib import Path

manifest = Path("android/app/src/main/AndroidManifest.xml")
text = manifest.read_text(encoding="utf-8")

styles = ["classic", "edge", "glow", "dark", "glass", "matte", "oneui", "amoled"]
palettes = ["rassvet", "indigo", "myata", "lavanda", "rassvet_light", "indigo_light", "myata_light", "lavanda_light"]

launcher_filter = """            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
"""
assert text.count(launcher_filter) == 1, "MainActivity's launcher filter is not where it was"

aliases = []
for style in styles:
    for palette in palettes:
        key = f"{style}_{palette}"
        enabled = "true" if key == "classic_myata" else "false"
        aliases.append(f"""        <activity-alias
            android:name=".launcher.{key}"
            android:enabled="{enabled}"
            android:exported="true"
            android:icon="@mipmap/ic_launcher_{key}"
            android:roundIcon="@mipmap/ic_launcher_{key}_round"
            android:targetActivity=".MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>
""")

comment = """
        <!--
          The launcher icons (docs/specs/2026-10-09-app-icons-design.md). Each is
          an alias of MainActivity under its own icon, and exactly one of them is
          enabled: the app changes its icon by changing which one
          (appicon/LauncherAliases.kt), and the component state is the only record
          of the choice. MainActivity itself is never a launcher entry and never
          disabled, because the widget and the alerts start it by class name.
          Only «Классика · Мята» is enabled here, so a fresh install shows it
          before a line of code runs. AppIconCatalogTest holds this list,
          AppIconCatalog and res/ to one another, in this order.
        -->
"""

text = text.replace(launcher_filter, '            android:windowSoftInputMode="adjustResize" />\n' + comment + "\n".join(aliases), 1)
for old, new in [
    ('android:icon="@mipmap/ic_launcher"', 'android:icon="@mipmap/ic_launcher_classic_myata"'),
    ('android:roundIcon="@mipmap/ic_launcher_round"', 'android:roundIcon="@mipmap/ic_launcher_classic_myata_round"'),
]:
    assert text.count(old) == 1, old
    text = text.replace(old, new, 1)

manifest.write_text(text, encoding="utf-8", newline="\n")
print("aliases:", text.count("<activity-alias"))
```

Expected output: `aliases: 64`.

- [ ] **Step 7: Stop pinning the splash icon**

Replace the whole of `android/app/src/main/res/values-v31/themes.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>

    <!--
      The window background is what the system paints between the tap and the
      first frame. The icon on it is left to the system, which draws the icon of
      the component that was started: the launcher alias the reader chose
      (docs/specs/2026-10-09-app-icons-design.md). Pinning
      windowSplashScreenAnimatedIcon here drew one drawable whatever the
      launcher showed. The attribute arrived in API 31, so this overrides the
      empty declaration in values/themes.xml rather than appearing beside it.
    -->
    <style name="Theme.Lessons.Starting" parent="Theme.Lessons">
        <item name="android:windowSplashScreenBackground">@color/window_background</item>
    </style>

</resources>
```

- [ ] **Step 8: Delete the old book's adaptive icons**

Nothing references them once the application icon is the default.

```bash
git rm android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
```

Keep `drawable/ic_launcher_foreground.xml` and the colour `ic_launcher_background` until Task 5.
«О приложении» and the onboarding still draw them.

- [ ] **Step 9: Run the tests and see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*AppIconCatalogTest*' --tests '*AppIconResourcesTest*' --tests '*MainActivityConfigChangesTest*' --tests '*ResourceTranslationTest*'
```

Expected: `BUILD SUCCESSFUL`. `AppIconCatalogTest` passes 6 tests and `AppIconResourcesTest` passes 2.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/kotlin/com/lumenpearson/lessons/appicon/AppIconCatalog.kt android/app/src/main/res/values/strings_app_icon.xml android/app/src/main/res/values-en/strings_app_icon.xml android/app/src/main/AndroidManifest.xml android/app/src/main/res/values-v31/themes.xml android/app/src/test/kotlin/com/lumenpearson/lessons/appicon
git commit -m "Put «Классика · Мята» on the launcher through an alias, beside sixty-three that wait" -m "Each icon is an activity-alias of MainActivity, and only the default is enabled in the manifest, so a fresh install shows it with no code running. MainActivity loses its launcher filter but is never disabled, because the widget and the alerts start it by class name; Essentials disables it and breaks exactly that. The splash screen stops pinning the old foreground and draws the started component's icon. AppIconCatalogTest holds the catalog, the aliases and the resources to one another, so taking a variant out cannot leave a piece behind."
```

---

### Task 3: Deciding which components to change

**Files:**
- Create: `android/app/src/main/kotlin/com/lumenpearson/lessons/appicon/LauncherAliases.kt`
- Create: `android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/FakeLauncherComponents.kt`
- Test: `android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/LauncherAliasesTest.kt`

**Interfaces:**
- Consumes: `AppIconCatalog`, `AppIconVariant.alias` (Task 2).
- Produces:
  - `interface LauncherComponents`, with `fun isEnabled(alias: String): Boolean` and
    `fun apply(changes: List<AliasChange>)`;
  - `data class AliasChange(val alias: String, val enabled: Boolean)`;
  - `class LauncherAliases(components: LauncherComponents, variants: List<AppIconVariant> = AppIconCatalog.variants, default: AppIconVariant = AppIconCatalog.default)`,
    with `fun current(): AppIconVariant`, `fun switchTo(target: AppIconVariant)` and
    `fun reconcile(): AppIconVariant`;
  - in the test source set, `internal class FakeLauncherComponents(explicit: Map<String, Boolean> = emptyMap(), failure: RuntimeException? = null)`.
    It has `states`, `calls: MutableList<List<AliasChange>>` and `enabled(): List<AppIconVariant>`.

- [ ] **Step 1: Write the fake**

```kotlin
package com.lumenpearson.lessons.appicon

/**
 * The launcher's component states, kept in a map.
 *
 * An alias nobody has set reads as the manifest declares it, as
 * PackageManager's COMPONENT_ENABLED_STATE_DEFAULT does: only the default
 * variant is enabled there. [failure], when set, is what the platform throws
 * at a write, before anything is written.
 */
internal class FakeLauncherComponents(
    explicit: Map<String, Boolean> = emptyMap(),
    private val failure: RuntimeException? = null,
) : LauncherComponents {

    val states: MutableMap<String, Boolean> = explicit.toMutableMap()

    /** Every write, one list per call, in the order it was asked for. */
    val calls: MutableList<List<AliasChange>> = mutableListOf()

    override fun isEnabled(alias: String): Boolean = states[alias] ?: (alias == AppIconCatalog.default.alias)

    override fun apply(changes: List<AliasChange>) {
        failure?.let { throw it }
        calls += changes
        changes.forEach { states[it.alias] = it.enabled }
    }

    /** The catalog's variants whose aliases are enabled now. */
    fun enabled(): List<AppIconVariant> = AppIconCatalog.variants.filter { isEnabled(it.alias) }
}
```

- [ ] **Step 2: Write the failing test `LauncherAliasesTest.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Which components a switch and a reconcile change, and in what order.
 *
 * The variants are picked by position rather than by name, so that this file
 * survives the owner taking most of the sixty-four out.
 */
class LauncherAliasesTest {

    private val default = AppIconCatalog.default
    private val others = AppIconCatalog.variants - default
    private val first = others[0]
    private val second = others[1]

    @Test
    fun `a fresh install shows the default and nothing is written`() {
        val components = FakeLauncherComponents()
        val aliases = LauncherAliases(components)

        assertEquals(default, aliases.current())
        assertEquals(default, aliases.reconcile())
        assertEquals(emptyList<List<AliasChange>>(), components.calls)
    }

    @Test
    fun `a switch enables the new icon before it disables the old one, in one write`() {
        val components = FakeLauncherComponents()

        LauncherAliases(components).switchTo(first)

        // One call, so that from Android 13 it is one batch; the new one first, so
        // that below 13, where it is two calls, there is never a moment with no
        // launcher entry at all.
        assertEquals(
            listOf(listOf(AliasChange(first.alias, enabled = true), AliasChange(default.alias, enabled = false))),
            components.calls,
        )
        assertEquals(listOf(first), components.enabled())
    }

    @Test
    fun `switching to the icon in use writes nothing`() {
        val components = FakeLauncherComponents(mapOf(first.alias to true, default.alias to false))

        LauncherAliases(components).switchTo(first)

        assertEquals(emptyList<List<AliasChange>>(), components.calls)
    }

    @Test
    fun `only the components whose state changes are written`() {
        val components = FakeLauncherComponents(mapOf(first.alias to true, default.alias to false))

        LauncherAliases(components).switchTo(second)

        // The default is already off and is not written again: every write is a
        // launcher re-index, and Essentials rewrites all of them every time.
        assertEquals(
            listOf(listOf(AliasChange(second.alias, enabled = true), AliasChange(first.alias, enabled = false))),
            components.calls,
        )
    }

    @Test
    fun `switching back to the default leaves the default alone on`() {
        val components = FakeLauncherComponents(mapOf(first.alias to true, default.alias to false))

        LauncherAliases(components).switchTo(default)

        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `reconcile with nothing enabled turns the default on`() {
        val components = FakeLauncherComponents(mapOf(default.alias to false))

        assertEquals(default, LauncherAliases(components).reconcile())
        assertEquals(listOf(listOf(AliasChange(default.alias, enabled = true))), components.calls)
    }

    @Test
    fun `an update that removed the chosen alias lands on the default`() {
        // The phone had chosen an alias this build no longer declares. Its state
        // is still on record, the default is off, and nothing in the catalog is on.
        val components = FakeLauncherComponents(
            mapOf("com.lumenpearson.lessons.launcher.removed_variant" to true, default.alias to false),
        )
        val aliases = LauncherAliases(components)

        assertEquals(default, aliases.current())
        assertEquals(default, aliases.reconcile())
        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `reconcile after a switch that died halfway keeps the new icon`() {
        // Below Android 13 the new alias is enabled first; a process killed before
        // the second call leaves it and the default both on.
        val components = FakeLauncherComponents(mapOf(first.alias to true))
        val aliases = LauncherAliases(components)

        assertEquals(first, aliases.current())
        assertEquals(first, aliases.reconcile())
        assertEquals(listOf(listOf(AliasChange(default.alias, enabled = false))), components.calls)
    }

    @Test
    fun `reconcile with two chosen icons keeps the first in catalog order`() {
        val components = FakeLauncherComponents(mapOf(second.alias to true, first.alias to true, default.alias to false))

        assertEquals(first, LauncherAliases(components).reconcile())
        assertEquals(listOf(first), components.enabled())
    }

    @Test
    fun `a variant the catalog does not have is refused`() {
        val stranger = default.copy(icon = 0)

        assertThrows(IllegalArgumentException::class.java) {
            LauncherAliases(FakeLauncherComponents()).switchTo(stranger)
        }
    }
}
```

- [ ] **Step 3: Run it and see it fail to compile**

```bash
./gradlew :app:testDebugUnitTest --tests '*LauncherAliasesTest*'
```

Expected: compilation fails, because `LauncherComponents`, `AliasChange` and `LauncherAliases` do not
exist.

- [ ] **Step 4: Write `LauncherAliases.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

/**
 * What the switch needs of the platform: one question and one write.
 *
 * A port rather than PackageManager itself, so that every decision below is
 * tested on the JVM, and the platform adapter is left with nothing to decide.
 */
interface LauncherComponents {

    /** Whether [alias] is enabled now, with the manifest's own `android:enabled` for one never set. */
    fun isEnabled(alias: String): Boolean

    /** Sets every change, as one call where the platform allows it and in the order given where not. */
    fun apply(changes: List<AliasChange>)
}

/** One alias turned on or off. */
data class AliasChange(val alias: String, val enabled: Boolean)

/**
 * Which launcher alias is on, and how to make exactly one of them be.
 *
 * The component states are the record of the reader's choice, not a
 * preference beside them (docs/specs/2026-10-09-app-icons-design.md, «How the
 * icon changes»). Auto Backup restores a preference onto a new phone and does
 * not restore component states, so a stored choice is a page that names an
 * icon the home screen does not show.
 */
class LauncherAliases(
    private val components: LauncherComponents,
    private val variants: List<AppIconVariant> = AppIconCatalog.variants,
    private val default: AppIconVariant = AppIconCatalog.default,
) {

    /** The icon the launcher shows, which is the one [reconcile] would keep. */
    fun current(): AppIconVariant = keeper(enabled())

    /** Makes [target] the one launcher entry, writing only what changes. */
    fun switchTo(target: AppIconVariant) {
        require(target in variants) { "${target.key} is not in the catalog" }
        settle(target, enabled())
    }

    /**
     * Brings the launcher back to exactly one entry, after an update or a
     * process start, and says which.
     *
     * None enabled is an update that took the chosen alias away, and it gets
     * the default. Several is a switch that died between its two calls below
     * Android 13. It keeps the first that is not the default, because the
     * default is what such a switch was leaving.
     */
    fun reconcile(): AppIconVariant {
        val enabled = enabled()
        val keep = keeper(enabled)
        settle(keep, enabled)
        return keep
    }

    private fun enabled(): List<AppIconVariant> = variants.filter { components.isEnabled(it.alias) }

    private fun keeper(enabled: List<AppIconVariant>): AppIconVariant =
        enabled.firstOrNull { it != default } ?: default

    /** [keep] on first and the rest off after, so that no moment has no entry; one write, or none. */
    private fun settle(keep: AppIconVariant, enabled: List<AppIconVariant>) {
        val changes = buildList {
            if (keep !in enabled) add(AliasChange(keep.alias, enabled = true))
            enabled.filter { it != keep }.forEach { add(AliasChange(it.alias, enabled = false)) }
        }
        if (changes.isNotEmpty()) components.apply(changes)
    }
}
```

- [ ] **Step 5: Run the test and see it pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*LauncherAliasesTest*'
```

Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/com/lumenpearson/lessons/appicon/LauncherAliases.kt android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/FakeLauncherComponents.kt android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/LauncherAliasesTest.kt
git commit -m "Decide a launcher switch as one ordered write of only what changes, and how to recover from a broken one" -m "Essentials sets every alias on every switch, one call each, and has no way back from an update that removes the chosen alias or a process killed between two calls. Here the new alias goes on before the old goes off, components already in their state are left alone, and a reconcile brings any state back to exactly one launcher entry: the default when none is on, the newer choice when two are. The decisions live behind a two-method port so that all of them are tested on the JVM."
```

---

### Task 4: On the platform: the adapter, the store, and the reconcile after an update

**Files:**
- Create: `android/app/src/main/kotlin/com/lumenpearson/lessons/appicon/PackageManagerComponents.kt`
- Create: `android/app/src/main/kotlin/com/lumenpearson/lessons/appicon/AppIconStore.kt`
- Create: `android/app/src/main/kotlin/com/lumenpearson/lessons/appicon/PackageReplacedReceiver.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`, to add the receiver.
- Modify: `android/app/src/main/kotlin/com/lumenpearson/lessons/LessonsApplication.kt`, to reconcile
  at process start.
- Test: `android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/PackageManagerComponentsTest.kt` (Robolectric)
- Test: `android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/AppIconStoreTest.kt` (JVM)
- Test: `android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/PackageReplacedReceiverTest.kt` (Robolectric)

**Interfaces:**
- Consumes: `LauncherComponents`, `AliasChange` and `LauncherAliases` (Task 3); `AppIconCatalog`
  (Task 2); `FakeLauncherComponents` (Task 3 test sources).
- Produces:
  - `class PackageManagerComponents(context: Context, defaultAlias: String = AppIconCatalog.default.alias, sdkInt: Int = Build.VERSION.SDK_INT) : LauncherComponents`;
  - `class AppIconStore(aliases: LauncherAliases, io: CoroutineDispatcher = Dispatchers.IO, scope: CoroutineScope = CoroutineScope(SupervisorJob() + io))`,
    with:
    - `val current: StateFlow<AppIconVariant?>`;
    - `suspend fun refresh()`;
    - `suspend fun reconcile()`;
    - `suspend fun switchTo(target: AppIconVariant)`;
    - `fun reconcileInBackground(onDone: () -> Unit = {}): Job`;
  - `object AppIcons` with `fun store(context: Context): AppIconStore` and
    `@VisibleForTesting internal fun override(store: AppIconStore?)`;
  - `class PackageReplacedReceiver : BroadcastReceiver`.

- [ ] **Step 1: Write the failing Robolectric test `PackageManagerComponentsTest.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The adapter against Robolectric's PackageManager: what an alias never set
 * reads as, and that both ways of writing land the same states.
 *
 * Robolectric runs SDK 34 here, so the path below Android 13 is taken by
 * handing the adapter an older [Build.VERSION.SDK_INT], not by emulating one.
 */
@RunWith(RobolectricTestRunner::class)
class PackageManagerComponentsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val default = AppIconCatalog.default
    private val other = AppIconCatalog.variants.first { it != default }

    private fun stateOf(alias: String): Int =
        context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, alias))

    @Test
    fun `an alias never set reads as the manifest declares it`() {
        val components = PackageManagerComponents(context)

        assertTrue(components.isEnabled(default.alias))
        assertFalse(components.isEnabled(other.alias))
    }

    @Test
    fun `from Android 13 the changes go as one batch`() = switchesOn(Build.VERSION_CODES.TIRAMISU)

    @Test
    fun `below Android 13 they go one call each`() = switchesOn(Build.VERSION_CODES.S_V2)

    private fun switchesOn(sdkInt: Int) {
        val components = PackageManagerComponents(context, sdkInt = sdkInt)

        components.apply(listOf(AliasChange(other.alias, enabled = true), AliasChange(default.alias, enabled = false)))

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, stateOf(other.alias))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, stateOf(default.alias))
        assertTrue(components.isEnabled(other.alias))
        assertFalse(components.isEnabled(default.alias))
    }
}
```

- [ ] **Step 2: Write the failing JVM test `AppIconStoreTest.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the rest of the app is told about the icon, and when. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppIconStoreTest {

    private val default = AppIconCatalog.default
    private val other = AppIconCatalog.variants.first { it != default }

    private fun TestScope.store(components: FakeLauncherComponents): AppIconStore {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return AppIconStore(LauncherAliases(components), io = dispatcher, scope = CoroutineScope(dispatcher))
    }

    @Test
    fun `nothing is claimed until the launcher has been read`() = runTest {
        val store = store(FakeLauncherComponents(mapOf(other.alias to true, default.alias to false)))

        assertNull(store.current.value)
        store.refresh()
        assertEquals(other, store.current.value)
    }

    @Test
    fun `a switch is what the store says once it returns`() = runTest {
        val components = FakeLauncherComponents()
        val store = store(components)

        store.switchTo(other)

        assertEquals(other, store.current.value)
        assertEquals(listOf(other), components.enabled())
    }

    @Test
    fun `a switch the platform refuses changes nothing the store says`() = runTest {
        val store = store(FakeLauncherComponents(failure = SecurityException("refused")))
        store.refresh()

        val result = runCatching { store.switchTo(other) }

        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals(default, store.current.value)
    }

    @Test
    fun `the background reconcile settles the launcher and says it is done`() = runTest {
        val components = FakeLauncherComponents(mapOf(default.alias to false))
        val store = store(components)
        var done = false

        store.reconcileInBackground { done = true }

        assertTrue(done)
        assertEquals(default, store.current.value)
        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `a background reconcile that fails still says it is done`() = runTest {
        // The receiver finishes its broadcast from onDone; a reconcile that threw
        // and never called it would hold the process until the system killed it.
        val store = store(FakeLauncherComponents(mapOf(default.alias to false), failure = IllegalArgumentException("gone")))
        var done = false

        store.reconcileInBackground { done = true }

        assertTrue(done)
        assertNull(store.current.value)
    }
}
```

- [ ] **Step 3: Write the failing Robolectric test `PackageReplacedReceiverTest.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import android.content.Intent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The update broadcast reconciles, and reaches the receiver at all. */
@RunWith(RobolectricTestRunner::class)
class PackageReplacedReceiverTest {

    private val context = RuntimeEnvironment.getApplication()
    private val default = AppIconCatalog.default

    @After
    fun forgetTheStore() = AppIcons.override(null)

    private fun install(components: FakeLauncherComponents) = AppIcons.override(
        AppIconStore(LauncherAliases(components), io = Dispatchers.Unconfined, scope = CoroutineScope(Dispatchers.Unconfined)),
    )

    @Test
    fun `an update reconciles the launcher icons`() {
        val components = FakeLauncherComponents(mapOf(default.alias to false))
        install(components)

        PackageReplacedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `any other broadcast is left alone`() {
        val components = FakeLauncherComponents(mapOf(default.alias to false))
        install(components)

        PackageReplacedReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(emptyList<List<AliasChange>>(), components.calls)
    }

    @Test
    fun `the manifest hands it the update broadcast`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiver = Regex(
            """<receiver\s+android:name="\.appicon\.PackageReplacedReceiver".*?</receiver>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)

        val declared = checkNotNull(receiver) { "no PackageReplacedReceiver in the manifest" }.value
        assertTrue("android.intent.action.MY_PACKAGE_REPLACED" in declared)
    }
}
```

- [ ] **Step 4: Run the three and see them fail to compile**

```bash
./gradlew :app:testDebugUnitTest --tests '*PackageManagerComponentsTest*' --tests '*AppIconStoreTest*' --tests '*PackageReplacedReceiverTest*'
```

Expected: compilation fails, because `PackageManagerComponents`, `AppIconStore`, `AppIcons` and
`PackageReplacedReceiver` do not exist.

- [ ] **Step 5: Write `PackageManagerComponents.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ComponentEnabledSetting
import android.os.Build

/**
 * [LauncherComponents] over PackageManager.
 *
 * Every write carries DONT_KILL_APP: the switch is made from inside the app,
 * and without it the system ends the process that asked. From Android 13 the
 * whole list goes in one `setComponentEnabledSettings` call, which is one
 * launcher re-index instead of one per alias; below it, one call each, in the
 * order [LauncherAliases] gave them.
 *
 * @param defaultAlias the one alias the manifest enables, which is what a
 *   component never set reads as. `AppIconCatalogTest` holds the manifest to it.
 * @param sdkInt [Build.VERSION.SDK_INT], except in a test of the older path.
 */
class PackageManagerComponents(
    context: Context,
    private val defaultAlias: String = AppIconCatalog.default.alias,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : LauncherComponents {

    private val packageManager: PackageManager = context.packageManager
    private val packageName: String = context.packageName

    override fun isEnabled(alias: String): Boolean = when (packageManager.getComponentEnabledSetting(component(alias))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias == defaultAlias
        else -> false
    }

    // The version check is on sdkInt, which lint cannot follow back to SDK_INT.
    @SuppressLint("NewApi")
    override fun apply(changes: List<AliasChange>) {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(
                changes.map { ComponentEnabledSetting(component(it.alias), stateOf(it), PackageManager.DONT_KILL_APP) },
            )
        } else {
            changes.forEach {
                packageManager.setComponentEnabledSetting(component(it.alias), stateOf(it), PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun component(alias: String) = ComponentName(packageName, alias)

    private fun stateOf(change: AliasChange): Int =
        if (change.enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
}
```

- [ ] **Step 6: Write `AppIconStore.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The icon the launcher shows, for everything in the app that draws it, and
 * the one door every component write goes through.
 *
 * One at a time, behind [lock]: the reconcile a process start runs and a
 * switch somebody presses at that moment would otherwise read the same states
 * and each write half of an answer. Off the main thread, on [io]: each
 * component read and write is a call into the system server.
 */
class AppIconStore(
    private val aliases: LauncherAliases,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + io),
) {

    private val lock = Mutex()
    private val state = MutableStateFlow<AppIconVariant?>(null)

    /** The icon in use; `null` until it has been read once, which a reader shows as the default. */
    val current: StateFlow<AppIconVariant?> = state.asStateFlow()

    suspend fun refresh() {
        state.value = locked { aliases.current() }
    }

    suspend fun reconcile() {
        state.value = locked { aliases.reconcile() }
    }

    /** Throws whatever the platform throws, and then [current] is what it was. */
    suspend fun switchTo(target: AppIconVariant) {
        locked { aliases.switchTo(target) }
        state.value = target
    }

    /**
     * [reconcile] for a caller that cannot wait: a process start and a
     * broadcast. A failure is swallowed, because neither has anybody to tell,
     * and the next process start tries again; [onDone] runs either way.
     */
    fun reconcileInBackground(onDone: () -> Unit = {}): Job = scope.launch {
        try {
            runCatching { reconcile() }
        } finally {
            onDone()
        }
    }

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }
}

/**
 * The process's one [AppIconStore].
 *
 * Built on first use from whatever context asks: the application at start, a
 * receiver after an update, a screen. Not in `Graph`, which lives in
 * `:core:data` and cannot see this module's launcher aliases.
 */
object AppIcons {

    @Volatile
    private var instance: AppIconStore? = null

    fun store(context: Context): AppIconStore = instance ?: synchronized(this) {
        instance ?: AppIconStore(LauncherAliases(PackageManagerComponents(context.applicationContext)))
            .also { instance = it }
    }

    @VisibleForTesting
    internal fun override(store: AppIconStore?) {
        instance = store
    }
}
```

- [ ] **Step 7: Write `PackageReplacedReceiver.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the launcher back to exactly one icon after the app is updated.
 *
 * An update can remove the alias a phone had chosen, and the owner is going to
 * take most of the sixty-four out. Such a phone is then left with no launcher
 * entry at all, and no way back into the app but the widget. The system sends
 * MY_PACKAGE_REPLACED to the updated app alone, and the limits on implicit
 * broadcasts exempt it, so this runs on the update itself rather than the next
 * time somebody happens to open the app through the widget.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // goAsync keeps the process alive until the write is done, which happens
        // off the main thread this is called on. It is null when a test calls
        // onReceive directly, outside a real broadcast.
        val pending: PendingResult? = goAsync()
        AppIcons.store(context).reconcileInBackground { pending?.finish() }
    }
}
```

- [ ] **Step 8: Declare the receiver**

In `android/app/src/main/AndroidManifest.xml`, insert this block immediately before the comment that
begins `        <!--` / `          Shares a crash report with whatever the user picks`:

```xml
        <!--
          Reconciles the launcher icons after an update, which can have removed
          the alias this phone had chosen (appicon/PackageReplacedReceiver.kt).
          Not exported: MY_PACKAGE_REPLACED comes from the system, which reaches
          a receiver no other app can.
        -->
        <receiver
            android:name=".appicon.PackageReplacedReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

```

- [ ] **Step 9: Reconcile once per process start**

In `LessonsApplication.kt`:

1. Add `import com.lumenpearson.lessons.appicon.AppIcons`.
2. In the class KDoc, change «It exists for exactly three reasons, and deliberately does no more:» to
   «It exists for exactly four reasons, and deliberately does no more:».
3. After item 3 of that list, add:

```kotlin
 *  4. The launcher icon is reconciled once per process start
 *     (docs/specs/2026-10-09-app-icons-design.md): an update can have taken
 *     away the alias this phone had chosen, and a switch can have died between
 *     its two calls below Android 13, and either leaves the launcher with the
 *     wrong number of entries. The update's own broadcast does the same; this
 *     is the net under it.
```

4. In `onCreate()`, after the line `applicationScope.launch { SchoolAlerts.onAppStart(this@LessonsApplication) }`, add:

```kotlin
        // Off the main thread inside the store, like the alarms above.
        AppIcons.store(this).reconcileInBackground()
```

- [ ] **Step 10: Run the tests and see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*PackageManagerComponentsTest*' --tests '*AppIconStoreTest*' --tests '*PackageReplacedReceiverTest*' --tests '*LauncherAliasesTest*' --tests '*AppIconCatalogTest*'
```

Expected: `BUILD SUCCESSFUL`. That is 3 + 5 + 3 new tests, plus the 10 and 6 from before.

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/kotlin/com/lumenpearson/lessons/appicon android/app/src/main/AndroidManifest.xml android/app/src/main/kotlin/com/lumenpearson/lessons/LessonsApplication.kt android/app/src/test/kotlin/com/lumenpearson/lessons/appicon
git commit -m "Switch the launcher alias off the main thread, one write at a time, and reconcile after every update and start" -m "PackageManagerComponents writes the changes as one batch from Android 13 and one call each below, always with DONT_KILL_APP. AppIconStore serializes every read and write behind one lock, so that a process start's reconcile and a press at that moment cannot each write half an answer, and it publishes the icon in use for whatever draws it. PackageReplacedReceiver reconciles on the update itself, which is what saves a phone whose chosen alias an update removed; the process start does the same as a net."
```

---

### Task 5: «О приложении» and the onboarding draw the icon in use, and the old book goes

**Files:**
- Create: `android/app/src/main/kotlin/com/lumenpearson/lessons/appicon/AppIconImage.kt`
- Modify: `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/AboutCard.kt`. This is
  `AppMark` at about lines 154-178, and `AdaptiveCanvasRatio` at about lines 528-533.
- Modify: `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/onboarding/OnboardingParts.kt`.
  This is the KDoc and body of `SpinnableAppMark` at about lines 235-341, and `ForegroundScale` at
  about lines 346-353.
- Delete: `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Modify: `android/app/src/main/res/values/colors.xml`, to remove `ic_launcher_background`.
- Test: modify `android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/AppIconCatalogTest.kt`,
  adding the leftovers test.

**Interfaces:**
- Consumes: `AppIconVariant`, `AppIconCatalog`, `AppIcons` and `AppIconStore.current` /
  `refresh()` (Tasks 2 and 4).
- Produces:
  - `@Composable fun AppIconImage(variant: AppIconVariant, shape: Shape, modifier: Modifier = Modifier)`;
  - `@Composable fun rememberCurrentAppIcon(): AppIconVariant`.

- [ ] **Step 1: Add the failing leftovers test to `AppIconCatalogTest`**

Add this test to the class, after `each entry's adaptive icons point at its own layers`:

```kotlin
    @Test
    fun `no launcher resource is left that no catalog entry uses`() {
        val used = AppIconCatalog.variants.flatMap { variant ->
            val name = variant.resourceName
            listOf(name, "${name}_round", "${name}_background", "${name}_foreground")
        }.toSet() + MonochromeLayers
        val present = res.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { it.listFiles().orEmpty().toList() }
            .map { it.nameWithoutExtension }
            .filter { it.startsWith("ic_launcher") }
            .toSet()

        assertEquals("launcher resources no icon uses", emptySet<String>(), present - used)
        assertFalse(
            "the old book's colour is still declared",
            "ic_launcher_background" in File(res, "values/colors.xml").readText(),
        )
    }
```

- [ ] **Step 2: Run it and see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*AppIconCatalogTest*'
```

Expected: FAIL. The message names `ic_launcher_foreground` in «launcher resources no icon uses».

- [ ] **Step 3: Write `AppIconImage.kt`**

```kotlin
package com.lumenpearson.lessons.appicon

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * A launcher icon, drawn the way a launcher draws it. Both layers of the
 * adaptive icon are laid over its 108 dp canvas, the box shows the middle
 * 72 dp, and [shape] masks it.
 *
 * It draws the whole icon, not its foreground on a plate of our choosing.
 * Essentials' picker drew the foreground on white, and «О приложении» drew
 * the old book on a colour, and both are wrong for every style with a ground of
 * its own: «Свечение», «Тёмная», «AMOLED», «Размытие» and «Матовое стекло».
 */
@Composable
fun AppIconImage(
    variant: AppIconVariant,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val icon = remember(context, variant) { context.getDrawable(variant.icon) as? AdaptiveIconDrawable }
    Canvas(modifier.clip(shape)) {
        val layers = icon ?: return@Canvas
        val bleed = (size.minDimension * BleedFraction).roundToInt()
        val right = size.width.roundToInt() + bleed
        val bottom = size.height.roundToInt() + bleed
        drawIntoCanvas { canvas ->
            for (layer in listOfNotNull(layers.background, layers.foreground)) {
                layer.setBounds(-bleed, -bleed, right, bottom)
                layer.draw(canvas.nativeCanvas)
            }
        }
    }
}

/**
 * The icon the launcher shows, read off the main thread. Until the first read
 * lands it is the default, and on a cold start that is a few milliseconds.
 */
@Composable
fun rememberCurrentAppIcon(): AppIconVariant {
    val context = LocalContext.current
    val store = remember(context) { AppIcons.store(context) }
    LaunchedEffect(store) { runCatching { store.refresh() } }
    val current by store.current.collectAsStateWithLifecycle()
    return current ?: AppIconCatalog.default
}

/** What the 108 dp canvas has beyond the visible 72 dp on each side, as a fraction of the 72. */
private const val BleedFraction = (108f - 72f) / 2f / 72f
```

- [ ] **Step 4: «О приложении» draws the icon in use**

In `AboutCard.kt`, replace the KDoc and body of `AppMark` with the following. The KDoc today begins
`/**` / ` * The launcher icon, drawn the way a launcher draws it.`, and the function ends at the closing
brace after `Image(...)`.

```kotlin
/**
 * The launcher icon in use, as the home screen shows it.
 *
 * The current one rather than a fixed drawable: this card is where somebody
 * looks to see which app they have, and since «Значок приложения» the icon on
 * their home screen can be any of the catalog's.
 */
@Composable
private fun AppMark() {
    AppIconImage(
        variant = rememberCurrentAppIcon(),
        shape = RoundedCornerShape(MarkCorner),
        modifier = Modifier.size(MarkSize),
    )
}
```

Delete the constant `AdaptiveCanvasRatio` and its KDoc. That is the block from
`/**` / ` * 108 dp of adaptive canvas over the 72 dp a launcher actually shows.` down to
`private const val AdaptiveCanvasRatio = 108f / 72f`. Add these imports:
- `com.lumenpearson.lessons.appicon.AppIconImage`;
- `com.lumenpearson.lessons.appicon.rememberCurrentAppIcon`.

Remove any import the compiler or detekt reports as unused. The likely ones are
`androidx.compose.foundation.Image`, `androidx.compose.foundation.background`,
`androidx.compose.ui.draw.clip`, `androidx.compose.ui.res.colorResource` and
`androidx.compose.ui.res.painterResource`. Keep each one that another function in the file still
uses.

- [ ] **Step 5: The onboarding mark is the icon in use**

In `OnboardingParts.kt`, replace the last two paragraphs of `SpinnableAppMark`'s KDoc. They run from
` * The plate is the launcher icon's own background colour rather than a theme` through
` * same reason: tinting it would flatten three colours into one silhouette.`. The new text is:

```kotlin
 * It is the launcher icon in use, whole, ground and mark, because this *is*
 * the icon they just tapped, and the recognition is the whole job of the
 * screen. On a first run that is «Классика · Мята», the default.
```

Then replace the end of the function body:

```kotlin
            .graphicsLayer { rotationZ = rotation.value }
            .clip(MaterialTheme.shapes.extraLarge)
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(size * ForegroundScale),
        )
    }
}
```

with:

```kotlin
            .graphicsLayer { rotationZ = rotation.value },
        contentAlignment = Alignment.Center,
    ) {
        AppIconImage(
            variant = rememberCurrentAppIcon(),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
```

Delete `ForegroundScale` and its KDoc. That is the block from `/**` /
` * The foreground is drawn inside the 72 dp safe zone of a 108 dp adaptive` down to
`private const val ForegroundScale = 1.25f`.

Add these imports:
- `androidx.compose.foundation.layout.fillMaxSize`, unless it is already imported;
- `com.lumenpearson.lessons.appicon.AppIconImage`;
- `com.lumenpearson.lessons.appicon.rememberCurrentAppIcon`.

Remove only the imports nothing else in the file uses any more.

- [ ] **Step 6: Delete the old book**

```bash
git rm android/app/src/main/res/drawable/ic_launcher_foreground.xml
```

In `android/app/src/main/res/values/colors.xml`, delete the line
`    <color name="ic_launcher_background">#3B3E85</color>`. Then confirm nothing else names either:

```bash
grep -rn "ic_launcher_foreground\b\|ic_launcher_background\b\|R.drawable.ic_launcher\b" android --include=*.kt --include=*.xml | grep -v /build/
```

Expected: no output.

- [ ] **Step 7: Run the tests and see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*AppIconCatalogTest*' --tests '*AboutCardTest*' --tests '*Onboarding*'
```

Expected: `BUILD SUCCESSFUL`, with `AppIconCatalogTest` at 7 tests.

- [ ] **Step 8: Run detekt**

```bash
./gradlew detekt
```

Expected: `BUILD SUCCESSFUL`, with no new finding.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/kotlin/com/lumenpearson/lessons/appicon/AppIconImage.kt android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/AboutCard.kt android/app/src/main/kotlin/com/lumenpearson/lessons/ui/onboarding/OnboardingParts.kt android/app/src/main/res/values/colors.xml android/app/src/test/kotlin/com/lumenpearson/lessons/appicon/AppIconCatalogTest.kt
git commit -m "Draw the launcher icon in use on «О приложении» and the first run, and retire the old book" -m "Both drew the book's foreground on its colour, which stops being the icon the moment anybody picks another. AppIconImage draws the whole adaptive icon, both layers over the 108 dp canvas and cropped to the visible 72, under the caller's shape, and rememberCurrentAppIcon reads which one the launcher shows. The book's drawable and colour go, and AppIconCatalogTest now refuses any launcher resource that no catalog entry uses."
```

---

### Task 6: The page «Значок приложения», and its row on «Оформление»

**Files:**
- Modify: `android/app/src/main/res/values/strings_app_icon.xml` and
  `android/app/src/main/res/values-en/strings_app_icon.xml`, to add the page strings.
- Modify: `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/SettingsSection.kt`, to
  add `APP_ICON`.
- Create: `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/AppIconViewModel.kt`
- Create: `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/AppIconRows.kt`
- Modify: `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/SettingsScreen.kt`. It
  routes `APP_ICON` and makes `SettingsPage` internal.
- Modify: `android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/AppearanceRows.kt`, to
  add the row.
- Test: `android/app/src/test/kotlin/com/lumenpearson/lessons/ui/settings/AppIconViewModelTest.kt` (JVM)
- Test: `android/app/src/test/kotlin/com/lumenpearson/lessons/ui/settings/AppIconRowsTest.kt` (Robolectric Compose)
- Test: modify `android/app/src/test/kotlin/com/lumenpearson/lessons/ui/settings/SettingsSectionListingTest.kt`.

**Interfaces:**
- Consumes:
  - `AppIconStore`, `AppIcons` and `LauncherAliases` (Task 4);
  - `FakeLauncherComponents` (Task 3 test sources, package
    `com.lumenpearson.lessons.appicon`);
  - `AppIconImage` and `rememberCurrentAppIcon` (Task 5);
  - `AppIconCatalog`, `AppIconStyle` and `AppIconVariant` (Task 2).
- Produces:
  - `SettingsSection.APP_ICON`;
  - `data class AppIconUiState(current, applying, failed)`;
  - `class AppIconViewModel(store: AppIconStore)` with `uiState`, `apply(target)` and
    `consumeFailure()`;
  - `internal fun LazyListScope.appIconRows(state: AppIconUiState, selected: AppIconVariant, onSelect: (AppIconVariant) -> Unit, onApply: () -> Unit, variants: List<AppIconVariant> = AppIconCatalog.variants)`.
    It has five parameters, because detekt's `LongParameterList` flags six on a function that is
    not `@Composable`;
  - `@Composable internal fun AppIconLinkRow(current: AppIconVariant, onOpen: () -> Unit)`;
  - `@Composable internal fun AppIconScreen(modifier: Modifier = Modifier, viewModel: AppIconViewModel = …)`.

`FakeLauncherComponents` is `internal` in the same module's test source set, so the tests in
`ui.settings` import it as `com.lumenpearson.lessons.appicon.FakeLauncherComponents`.

- [ ] **Step 1: Add the page strings**

Append inside `<resources>` of `values/strings_app_icon.xml`, before `</resources>`:

```xml

    <!-- The page under «Оформление», and its row there. -->
    <string name="settings_app_icon">Значок приложения</string>
    <string name="settings_app_icon_summary">Стиль и цвет значка на главном экране</string>
    <string name="settings_app_icon_group">Значок</string>
    <string name="app_icon_mask_circle">Круг</string>
    <string name="app_icon_mask_rounded">Скруглённый квадрат</string>
    <string name="app_icon_apply">Применить</string>
    <string name="app_icon_apply_hint">Лаунчер может переставить значок на главном экране или попросить добавить его заново.</string>
    <string name="app_icon_failed">Не удалось сменить значок</string>
```

And to `values-en/strings_app_icon.xml`:

```xml

    <string name="settings_app_icon">App icon</string>
    <string name="settings_app_icon_summary">The style and colour of the home-screen icon</string>
    <string name="settings_app_icon_group">Icon</string>
    <string name="app_icon_mask_circle">Circle</string>
    <string name="app_icon_mask_rounded">Rounded square</string>
    <string name="app_icon_apply">Apply</string>
    <string name="app_icon_apply_hint">Your launcher may move the icon on the home screen or ask you to add it again.</string>
    <string name="app_icon_failed">Couldn't change the icon</string>
```

- [ ] **Step 2: Write the failing listing test**

Add to `SettingsSectionListingTest`:

```kotlin
    @Test
    fun `the app icon page is reached from appearance, never from the root`() {
        for (mode in ShellMode.entries) {
            assertFalse(
                "APP_ICON listed on the $mode home",
                SettingsSection.APP_ICON.listedOn(mode, manager = true, developer = true),
            )
        }
    }
```

Add `import org.junit.Assert.assertFalse` if the file does not import it already.

- [ ] **Step 3: Write the failing `AppIconViewModelTest.kt`**

```kotlin
package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.appicon.AppIconCatalog
import com.lumenpearson.lessons.appicon.AppIconStore
import com.lumenpearson.lessons.appicon.FakeLauncherComponents
import com.lumenpearson.lessons.appicon.LauncherAliases
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The page's one button, against a launcher that agrees, refuses, or is slow. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppIconViewModelTest {

    private val default = AppIconCatalog.default
    private val other = AppIconCatalog.variants.first { it != default }

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun TestScope.model(
        components: FakeLauncherComponents,
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): AppIconViewModel {
        val model = AppIconViewModel(AppIconStore(LauncherAliases(components), io = io))
        // uiState is shared while subscribed, as it is while the page is on screen.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        return model
    }

    @Test
    fun `the page opens on the icon the launcher shows`() = runTest {
        val model = model(FakeLauncherComponents(mapOf(other.alias to true, default.alias to false)))

        assertEquals(other, model.uiState.value.current)
    }

    @Test
    fun `until the launcher has been read the page shows the default`() = runTest {
        val model = model(FakeLauncherComponents(), io = StandardTestDispatcher(testScheduler))

        assertEquals(default, model.uiState.value.current)
        advanceUntilIdle()
        assertEquals(default, model.uiState.value.current)
    }

    @Test
    fun `apply switches the icon and the page follows`() = runTest {
        val components = FakeLauncherComponents()
        val model = model(components)

        model.apply(other)

        assertEquals(other, model.uiState.value.current)
        assertFalse(model.uiState.value.applying)
        assertEquals(listOf(other), components.enabled())
    }

    @Test
    fun `a refused switch is reported once and changes nothing`() = runTest {
        val model = model(FakeLauncherComponents(failure = SecurityException("refused")))

        model.apply(other)

        assertTrue(model.uiState.value.failed)
        assertEquals(default, model.uiState.value.current)
        assertFalse("«Применить» stays usable", model.uiState.value.applying)
        model.consumeFailure()
        assertFalse(model.uiState.value.failed)
    }

    @Test
    fun `a second press while the first is still switching does nothing`() = runTest {
        val components = FakeLauncherComponents()
        val model = model(components, io = StandardTestDispatcher(testScheduler))

        model.apply(other)
        model.apply(AppIconCatalog.variants.last())
        assertTrue(model.uiState.value.applying)
        advanceUntilIdle()

        assertEquals(1, components.calls.size)
        assertEquals(other, model.uiState.value.current)
    }

    @Test
    fun `pressing apply on the icon in use writes nothing`() = runTest {
        val components = FakeLauncherComponents()
        val model = model(components)

        model.apply(default)

        assertEquals(emptyList<Any>(), components.calls)
    }
}
```

- [ ] **Step 4: Write the failing `AppIconRowsTest.kt`**

```kotlin
package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.appicon.AppIconCatalog
import com.lumenpearson.lessons.appicon.AppIconStyle
import com.lumenpearson.lessons.appicon.AppIconVariant
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * «Значок приложения», composed and pressed: a tile selects, only «Применить»
 * applies, and a trimmed catalog draws no empty group.
 */
// marquee clock: it reaches the groups with `performScrollTo`, which holding the
// clock would stop. The longest row title it composes is «Значок приложения»,
// seventeen characters across a 411 dp row with nothing beside it.
@RunWith(RobolectricTestRunner::class)
// Russian, the source, for the reason `ClassRowsScreenTest` gives.
@Config(qualifiers = "ru-rRU-w411dp")
class AppIconRowsTest {

    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val default = AppIconCatalog.default
    private val other = AppIconCatalog.variants.first { it.style != default.style }

    private fun label(variant: AppIconVariant): String = context.getString(
        R.string.app_icon_variant,
        context.getString(variant.style.labelRes),
        context.getString(variant.palette.labelRes),
    )

    private fun show(
        state: AppIconUiState = AppIconUiState(current = default),
        selected: AppIconVariant = state.current,
        onSelect: (AppIconVariant) -> Unit = {},
        onApply: () -> Unit = {},
        variants: List<AppIconVariant> = AppIconCatalog.variants,
    ) = compose.setContent {
        LessonsTheme {
            LazyColumn {
                appIconRows(state, selected, onSelect, onApply, variants)
            }
        }
    }

    @Test
    fun `every style in the catalog is a group of its own`() {
        show()

        for (style in AppIconCatalog.variants.map { it.style }.distinct()) {
            compose.onNodeWithText(context.getString(style.labelRes)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `a tile selects its icon and applies nothing`() {
        var picked: AppIconVariant? = null
        var applied = false
        show(onSelect = { picked = it }, onApply = { applied = true })

        compose.onNodeWithContentDescription(label(other)).performScrollTo().performClick()

        assertEquals(other, picked)
        assertFalse(applied)
    }

    @Test
    fun `the chosen tile is announced as chosen and the others are not`() {
        show(selected = other)

        compose.onNodeWithContentDescription(label(other)).performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription(label(default)).performScrollTo().assertIsNotSelected()
    }

    @Test
    fun `apply waits for an icon other than the one in use`() {
        show()

        compose.onNodeWithText("Применить").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `apply applies the selection, once`() {
        var applied = 0
        show(selected = other, onApply = { applied++ })

        compose.onNodeWithText("Применить").performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, applied)
    }

    @Test
    fun `apply is held while a switch is running`() {
        show(state = AppIconUiState(current = default, applying = true), selected = other)

        compose.onNodeWithText("Применить").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a trimmed catalog draws only the styles it still has`() {
        show(variants = listOf(default, other))

        compose.onNodeWithText(context.getString(default.style.labelRes)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(other.style.labelRes)).performScrollTo().assertIsDisplayed()
        val gone = AppIconStyle.entries.first { it != default.style && it != other.style }
        compose.onNodeWithText(context.getString(gone.labelRes)).assertDoesNotExist()
    }

    @Test
    fun `the row on «Оформление» names the icon in use and opens the page`() {
        var opened = false
        compose.setContent { LessonsTheme { AppIconLinkRow(current = other, onOpen = { opened = true }) } }

        compose.onNodeWithText(label(other)).assertIsDisplayed()
        compose.onNodeWithText("Значок приложения").performClick()

        assertTrue(opened)
    }
}
```

- [ ] **Step 5: Run the three and see them fail to compile**

```bash
./gradlew :app:testDebugUnitTest --tests '*SettingsSectionListingTest*' --tests '*AppIconViewModelTest*' --tests '*AppIconRowsTest*'
```

Expected: compilation fails, because `SettingsSection.APP_ICON`, `AppIconViewModel`, `appIconRows` and
`AppIconLinkRow` do not exist.

- [ ] **Step 6: Add `APP_ICON` to `SettingsSection`**

1. Add `import androidx.compose.material.icons.rounded.Apps`.
2. Insert this entry after `PERMISSIONS(...)`, before the `DEVELOPER` KDoc:

```kotlin

    /**
     * Reached from «Оформление», never from the root list, as [PERMISSIONS] is
     * reached from the notifications page. Which icon the launcher shows is a
     * question about how the app looks, and one row on that page names the
     * icon in use and opens this.
     */
    APP_ICON(
        R.string.settings_app_icon,
        R.string.settings_app_icon_summary,
        Icons.Rounded.Apps,
        tone = 3,
    ),
```

3. In `listedOn`, change `PERMISSIONS -> false` to `PERMISSIONS, APP_ICON -> false`.

- [ ] **Step 7: Write `AppIconViewModel.kt`**

```kotlin
package com.lumenpearson.lessons.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.appicon.AppIconCatalog
import com.lumenpearson.lessons.appicon.AppIconStore
import com.lumenpearson.lessons.appicon.AppIconVariant
import com.lumenpearson.lessons.appicon.AppIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What «Значок приложения» shows.
 *
 * @param current the icon the launcher shows, and the default until it has
 *   been read.
 * @param applying a switch is running, so «Применить» is held.
 * @param failed the last switch was refused, which the page says once.
 */
data class AppIconUiState(
    val current: AppIconVariant = AppIconCatalog.default,
    val applying: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The page's state over [AppIconStore]. The tile the reader has selected is
 * the screen's own, saved with it; only an applied icon reaches here.
 */
class AppIconViewModel(private val store: AppIconStore) : ViewModel() {

    private val applying = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)

    val uiState: StateFlow<AppIconUiState> = combine(store.current, applying, failed) { current, applying, failed ->
        AppIconUiState(current = current ?: AppIconCatalog.default, applying = applying, failed = failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppIconUiState())

    init {
        // The launcher is the record, and it can have changed since the store
        // last looked: an update, and the reconcile after it.
        viewModelScope.launch { runCatching { store.refresh() } }
    }

    /** Switches to [target], unless a switch is already running or it is the icon in use. */
    fun apply(target: AppIconVariant) {
        if (applying.value || target == (store.current.value ?: AppIconCatalog.default)) return
        applying.value = true
        viewModelScope.launch {
            failed.value = runCatching { store.switchTo(target) }.isFailure
            applying.value = false
        }
    }

    fun consumeFailure() {
        failed.value = false
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AppIconViewModel(AppIcons.store(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])))
            }
        }
    }
}
```

- [ ] **Step 8: Write `AppIconRows.kt`**

```kotlin
package com.lumenpearson.lessons.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.appicon.AppIconCatalog
import com.lumenpearson.lessons.appicon.AppIconImage
import com.lumenpearson.lessons.appicon.AppIconVariant
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * «Значок приложения»: every launcher icon the app offers, one group per style.
 *
 * Essentials' picker is one row of five icons that applies on tap. Here a tap
 * only selects. The preview at the top shows the choice under a circle and a
 * rounded square, the two masks where edge-to-edge and inset styles differ,
 * and «Применить» switches once. Every switch is a launcher re-index, and
 * some launchers drop the icon's place on the home screen each time.
 */
@Composable
internal fun AppIconScreen(
    modifier: Modifier = Modifier,
    viewModel: AppIconViewModel = viewModel(factory = AppIconViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The key rather than the variant: it survives being saved, and a key this
    // build no longer has falls back to the icon in use instead of failing.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = AppIconCatalog.byKey(selectedKey) ?: state.current

    SettingsPage(
        modifier = modifier,
        message = if (state.failed) correctedString(R.string.app_icon_failed) else null,
        messageKey = state.failed,
        onMessageShown = viewModel::consumeFailure,
    ) {
        item(key = "header") {
            ScreenHeader(
                title = correctedString(SettingsSection.APP_ICON.titleRes),
                subtitle = correctedString(SettingsSection.APP_ICON.subtitleRes),
            )
        }
        appIconRows(
            state = state,
            selected = selected,
            onSelect = { selectedKey = it.key },
            onApply = { viewModel.apply(selected) },
        )
    }
}

/**
 * The preview, a group per style and «Применить», over whatever [variants]
 * the catalog still has. Grouped in catalog order, so a style that has been
 * taken out leaves no empty group, and one with fewer palettes leaves a short
 * row.
 */
internal fun LazyListScope.appIconRows(
    state: AppIconUiState,
    selected: AppIconVariant,
    onSelect: (AppIconVariant) -> Unit,
    onApply: () -> Unit,
    variants: List<AppIconVariant> = AppIconCatalog.variants,
) {
    item(key = "app-icon-preview") { AppIconPreview(selected) }
    variants.groupBy { it.style }.forEach { (style, inStyle) ->
        item(key = "app-icon-${style.key}") {
            SettingsGroup(title = correctedString(style.labelRes)) {
                AppIconTiles(variants = inStyle, selected = selected, onSelect = onSelect)
            }
        }
    }
    item(key = "app-icon-apply") {
        ApplyBlock(enabled = selected != state.current && !state.applying, onApply = onApply)
    }
}

/** The row on «Оформление» that opens the page, with the icon in use beside its name. */
@Composable
internal fun AppIconLinkRow(current: AppIconVariant, onOpen: () -> Unit) {
    GroupItem(
        title = correctedString(R.string.settings_app_icon),
        subtitle = variantLabel(current),
        icon = Icons.Rounded.Apps,
        tone = accentTone(slot = 3),
        onClick = onOpen,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LinkGap),
            ) {
                AppIconImage(variant = current, shape = CircleShape, modifier = Modifier.size(LinkIconSize))
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(ChevronSize),
                )
            }
        },
    )
}

/** «Свечение · Мята». */
@Composable
private fun variantLabel(variant: AppIconVariant): String = correctedString(
    R.string.app_icon_variant,
    correctedString(variant.style.labelRes),
    correctedString(variant.palette.labelRes),
)

@Composable
private fun AppIconPreview(variant: AppIconVariant) {
    RoundedCardContainer {
        GroupRow {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PreviewGap),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(PreviewGap)) {
                    MaskedPreview(variant, CircleShape, R.string.app_icon_mask_circle)
                    MaskedPreview(variant, SquircleShape, R.string.app_icon_mask_rounded)
                }
                Text(text = variantLabel(variant), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun MaskedPreview(variant: AppIconVariant, shape: Shape, @StringRes captionRes: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CaptionGap),
    ) {
        AppIconImage(variant = variant, shape = shape, modifier = Modifier.size(PreviewSize))
        Text(
            text = correctedString(captionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppIconTiles(
    variants: List<AppIconVariant>,
    selected: AppIconVariant,
    onSelect: (AppIconVariant) -> Unit,
) {
    GroupRow {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            variants.chunked(TilesPerRow).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { variant ->
                        AppIconTile(variant = variant, selected = variant == selected, onClick = { onSelect(variant) })
                    }
                    // A short last row keeps the columns of the full one above it.
                    repeat(TilesPerRow - row.size) { Spacer(Modifier.size(TileSize)) }
                }
            }
        }
    }
}

@Composable
private fun AppIconTile(variant: AppIconVariant, selected: Boolean, onClick: () -> Unit) {
    val view = rememberHapticView()
    val label = variantLabel(variant)
    // The ring sits in a gap every tile has, so the chosen icon does not shrink
    // when it is chosen; Essentials pads the selected tile alone, and it jumps.
    Box(
        modifier = Modifier
            .size(TileSize)
            .then(if (selected) Modifier.border(RingWidth, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .padding(RingGap)
            .clip(CircleShape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    LessonsHaptics.press(view)
                    onClick()
                },
            )
            .semantics { contentDescription = label },
    ) {
        AppIconImage(variant = variant, shape = CircleShape, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ApplyBlock(enabled: Boolean, onApply: () -> Unit) {
    val view = rememberHapticView()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HintGap)) {
        Button(
            onClick = {
                LessonsHaptics.press(view)
                onApply()
            },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(ApplyHeight),
        ) {
            Text(text = correctedString(R.string.app_icon_apply))
        }
        Text(
            text = correctedString(R.string.app_icon_apply_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = HintInset),
        )
    }
}

/**
 * A superellipse, |x|⁵ + |y|⁵ = 1. It stands for the rounded-square masks of
 * One UI and of Pixel's «Squircle», and android/logo/build_styles.py previews
 * the styles under the same one.
 */
private val SquircleShape: Shape = GenericShape { size, _ ->
    for (step in 0 until SquircleSteps) {
        val angle = 2 * PI * step / SquircleSteps
        val x = size.width / 2 * (1 + superellipse(cos(angle)))
        val y = size.height / 2 * (1 + superellipse(sin(angle)))
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun superellipse(value: Double): Float = (sign(value) * abs(value).pow(SquircleExponent)).toFloat()

private const val TilesPerRow = 4
private const val SquircleSteps = 120

/** 2 / n for the superellipse of degree n = 5. */
private const val SquircleExponent = 0.4

private val TileSize = 56.dp
private val RingWidth = 2.5.dp
private val RingGap = 4.dp
private val TileGap = 12.dp
private val PreviewSize = 112.dp
private val PreviewGap = 16.dp
private val CaptionGap = 8.dp
private val ApplyHeight = 52.dp
private val HintGap = 8.dp
private val HintInset = 16.dp
private val LinkIconSize = 32.dp
private val LinkGap = 8.dp
private val ChevronSize = 20.dp
```

- [ ] **Step 9: Route the section, and share the page scaffold**

In `SettingsScreen.kt`:

1. Change `private fun SettingsPage(` to `internal fun SettingsPage(`.
2. In `SettingsSectionScreen`, right after the `DEVELOPER` block (the `if` that ends with `return`),
   add:

```kotlin

    // The icon page holds a selection of its own until «Применить», and a
    // view model over the launcher's components rather than over preferences.
    if (section == SettingsSection.APP_ICON) {
        AppIconScreen(modifier = modifier)
        return
    }
```

3. Change `SettingsSection.APPEARANCE -> appearanceRows(state, viewModel)` to
   `SettingsSection.APPEARANCE -> appearanceRows(state, viewModel, onOpenSection)`.
4. Change `SettingsSection.DIARY, SettingsSection.DEVELOPER -> Unit` to
   `SettingsSection.DIARY, SettingsSection.DEVELOPER, SettingsSection.APP_ICON -> Unit`.

- [ ] **Step 10: The row on «Оформление»**

In `AppearanceRows.kt`:

1. Change the signature to:

```kotlin
internal fun LazyListScope.appearanceRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenSection: (SettingsSection) -> Unit,
) {
```

2. Insert, between the closing `}` of `item(key = "appearance") { … }` and `item(key = "typography")`:

```kotlin

    // A group of its own rather than a row of the theme's: the theme repaints
    // the app at a tap, and this changes the home screen, on a page of its own.
    item(key = "app-icon") {
        SettingsGroup(title = correctedString(R.string.settings_app_icon_group)) {
            AppIconLinkRow(
                current = rememberCurrentAppIcon(),
                onOpen = { onOpenSection(SettingsSection.APP_ICON) },
            )
        }
    }
```

3. Add `import com.lumenpearson.lessons.appicon.rememberCurrentAppIcon`.

- [ ] **Step 11: Run the tests and see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*SettingsSectionListingTest*' --tests '*AppIconViewModelTest*' --tests '*AppIconRowsTest*' --tests '*ResourceTranslationTest*' --tests '*MarqueeClockTest*' --tests '*SettingsTrailTest*'
```

Expected: `BUILD SUCCESSFUL`. `AppIconViewModelTest` passes 6 tests and `AppIconRowsTest` passes 8.

- [ ] **Step 12: The whole Android gate, as CI runs it**

From `android/`, one at a time:

```bash
./gradlew test
./gradlew assembleDebug assembleRelease
./gradlew detekt
```

Expected: each `BUILD SUCCESSFUL`, and detekt with no new finding. If `assembleRelease` fails on R8 or
on resource shrinking, read the message; do not suppress it.

- [ ] **Step 13: Commit**

```bash
git add android/app/src/main/res/values/strings_app_icon.xml android/app/src/main/res/values-en/strings_app_icon.xml android/app/src/main/kotlin/com/lumenpearson/lessons/ui/settings android/app/src/test/kotlin/com/lumenpearson/lessons/ui/settings
git commit -m "Let the reader choose the launcher icon on «Значок приложения», reached from «Оформление»" -m "A page of eight groups, one per style, with the eight palettes as tiles that draw the whole adaptive icon. A tap selects and the preview shows the choice under a circle and a rounded square; «Применить» switches once, and the row on «Оформление» names the icon in use and shows it. Essentials applies on every tap from one row of five foregrounds on white; that does not scale to sixty-four, misdraws every style with a ground of its own, and re-indexes the launcher on every tap."
```

---

### Task 7: The documents say what was built

**Files:**
- Modify: `docs/design.md`, the «Not carried over» paragraph (about line 167) and «The mark is drawn
  the way a launcher draws it» (about line 640).
- Modify: `CLAUDE.md`, the `android/` line of the repository tree near the top.

**Interfaces:**
- Consumes: everything above.
- Produces: no code.

- [ ] **Step 1: `docs/design.md`, what was not carried over**

Replace the paragraph that begins «Not carried over: choosing the app icon (Essentials switches it
through an `activity-alias`,» with:

```markdown
The app icon is carried over, and changed: «Значок приложения» under «Оформление» offers the
sixty-four «Пятёрка» variants (`docs/specs/2026-10-09-app-icons-design.md`). It switches through an
`activity-alias` as Essentials does, but `MainActivity` is never disabled, the enabled component is
the only record of the choice, a tap selects and «Применить» applies, and a reconcile after every
update and process start puts back exactly one launcher entry. Not carried over: the "ripple
animation" (the effect is drawn over the window by an overlay of Essentials' own) and the "online
help media" (we have no help). The language picker, on the contrary, was carried over — see below.
```

- [ ] **Step 2: `docs/design.md`, how the mark is drawn**

Replace the paragraph that begins «The mark is drawn the way a launcher draws it: an adaptive icon is a
108 dp canvas» with:

```markdown
The mark is the launcher icon in use, drawn the way a launcher draws it (`AppIconImage`). An adaptive
icon is a 108 dp canvas of which only the central 72 dp is visible, so both layers are laid over the
whole canvas and the box shows the middle — the same crop the home screen makes. Both layers, not the
foreground on a colour: five of the eight styles have a ground of their own.
```

- [ ] **Step 3: `CLAUDE.md`, the tree**

In the fenced block near the top, replace the line

```
android/     Kotlin / Compose / Glance, five Gradle modules
```

with

```
android/     Kotlin / Compose / Glance, five Gradle modules; logo/ beside them holds
             the logo's generators, which write the launcher icons into
             app/src/main/res (its README says how)
```

- [ ] **Step 4: Check that nothing else describes the old icon**

```bash
grep -rn -i "ic_launcher_foreground\|launcher icon's own background\|we have no alternative icons" docs README.md CLAUDE.md HANDOVER.md
```

Expected: no output outside `docs/history.md`, which is a record and is not edited.

- [ ] **Step 5: Commit**

```bash
git add docs/design.md CLAUDE.md
git commit -m "Say in the design notes that the app icon is chosen now, and where the logo's generators live" -m "design.md said the icon picker was not carried over from Essentials because there were no other icons, and described the About mark as a foreground on a colour; both stopped being true in this branch. CLAUDE.md's tree says where android/logo/ is."
```

---

## After the tasks: the pull request

This part is not an implementer's task. The controller does it after the final review.

- **Before the pull request:**
  - Any defect found during implementation gets its own issue (`type:bug`, `area:android`, milestone
    12) before its fix.
- **The pull request:**
  - Open it from `android/app-icons` to `main` on milestone 12, «v1.0.0 — A build somebody else can
    install». Put it on board 6 with Priority, Size, Estimate and dates.
  - Its body says what is not covered:
    - any real launcher (placement, re-indexing, clipping);
    - themed icons on a device;
    - the edge-to-edge styles under a mask smaller than the standard circle;
    - the lost home-screen shortcut on an update from a build where `MainActivity` was the launcher
      entry.
- **The close-out:**
  - `HANDOVER.md`, written per the `handover` skill while the pull request is still open. It is the
    batch's last commit.
- **The merge:**
  - Only after the five checks in the `github-pr` skill.
