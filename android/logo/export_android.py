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
