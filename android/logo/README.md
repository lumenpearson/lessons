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
