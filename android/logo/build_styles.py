"""Styles for the «Пятёрка» dial, beside the pack's eight palettes (which stay as they are).

    python build_styles.py            # everything (PNGs need Chrome, via logo-design's render_png.py)
    python build_styles.py --no-png   # SVG and Android resources only

Every style is drawn edge to edge: the dial's lobe tips reach the visible circle of the
adaptive icon (36 dp of 108), so under a round mask the mark meets the edge, as Essentials'
does, and under a squircle or a square it sits centred with equal margins, with the background
in the corners.

Styles (each in all eight palettes):
  edge   — the pack's look (white ground, Essentials fill and trail), edge to edge
  glow   — iOS 26-like: a deep ground, the dial lit from above, a specular rim, a soft halo and
           luminous white hands
  dark   — dark ground, the dial dimmed, pale hands with a faint glow
  glass  — frosted glass: a soft blurred backdrop of the palette, the dial as frosted white glass
  matte  — matte glass: a pale tinted ground, an opaque white dial, hands in the palette's deep
  oneui  — Samsung One UI-like: a vivid blurred aurora, the dial as clear glass, white hands
  amoled — black ground, the dial dark with a glowing rim, white hands

Android: glow, dark, matte, amoled and edge are VectorDrawables (a blur cannot be expressed in
one, so their halos are radial gradients); glass and oneui are bitmaps (432 px, xxxhdpi), because
their look is the blur.
"""
import math
import shutil
import subprocess
import sys
from pathlib import Path

import build_pack as bp
import clock as c

HERE = Path(__file__).parent
OUT = bp.PACK / "styles"
KEYS = list(c.PALETTES)
STYLES = {
    "edge": "Край в край",
    "glow": "Свечение (iOS 26)",
    "dark": "Тёмная",
    "glass": "Матовое стекло (iOS)",
    "matte": "Матовая",
    "oneui": "Размытие (One UI)",
    "amoled": "AMOLED",
}
RASTER = {"glass", "oneui"}
RECOMMENDED = "myata"

K = 36.0 / c.R                     # 256-canvas units → dp, tips on the visible circle
T = 54 - 128 * K                   # translate so the dial's centre sits at (54, 54)
GROUP = f"translate({c.fmt(T)} {c.fmt(T)}) scale({K:.5f})"


def mix(a, b, t):
    return c._mix(a, b, t)


def cols(key):
    warm, mid, deep = c.PALETTES[key][1]
    return warm, mid, deep


def hands_d():
    """The cut-outs as positive shapes: the hands with their hub, and the marks."""
    return f"{c.hands_outline().d()} {c.marks()}"


# ---------------------------------------------------------------- SVG (108 canvas)
def blob(cx, cy, r, color, gid):
    return (f'<radialGradient id="{gid}" cx="{cx}" cy="{cy}" r="{r}" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{color}"/><stop offset="1" stop-color="{color}" stop-opacity="0"/>'
            f"</radialGradient>")


def aurora(key, p, vivid):
    """A soft backdrop of the palette, in 108 coordinates."""
    warm, mid, deep = cols(key)
    if vivid:
        base, a, b, d = mix(deep, "#000000", .35), warm, mid, mix(deep, "#000000", .1)
    else:
        base, a, b, d = mix(mid, "#FFFFFF", .35), warm, mid, mix(deep, "#FFFFFF", .1)
    defs = (blob(26, 20, 52, a, f"{p}a1") + blob(92, 36, 48, b, f"{p}a2") + blob(60, 104, 56, d, f"{p}a3"))
    body = (f'<rect width="108" height="108" fill="{base}"/>'
            f'<rect width="108" height="108" fill="url(#{p}a1)"/>'
            f'<rect width="108" height="108" fill="url(#{p}a2)"/>'
            f'<rect width="108" height="108" fill="url(#{p}a3)"/>')
    return defs, body


def style_svg(key, style, p=""):
    """(defs, background, foreground) for one style, the foreground in 108 coordinates."""
    warm, mid, deep = cols(key)
    defs, bg, fg = [], "", []
    dial = c.dial()
    body = c.body_d()
    if style == "edge":
        bg = '<rect width="108" height="108" fill="#FFFFFF"/>'
        fg.append(f'<g transform="{GROUP}">{c.mark_svg(key, p)}</g>')
    elif style == "glow":
        top, bottom = mix(deep, "#000000", .45), mix(deep, "#000000", .7)
        defs += [
            f'<linearGradient id="{p}bg" x1="0" y1="0" x2="0" y2="108" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{top}"/><stop offset="1" stop-color="{bottom}"/></linearGradient>',
            f'<radialGradient id="{p}halo" cx="128" cy="128" r="{c.fmt(c.R * 1.32)}" gradientUnits="userSpaceOnUse">'
            f'<stop offset=".62" stop-color="{mid}" stop-opacity=".95"/><stop offset=".8" stop-color="{mid}" stop-opacity=".4"/>'
            f'<stop offset="1" stop-color="{mid}" stop-opacity="0"/></radialGradient>',
            f'<radialGradient id="{p}hl" cx="104" cy="58" r="{c.fmt(c.R * .95)}" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="#FFFFFF" stop-opacity=".5"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>',
            f'<linearGradient id="{p}rim" x1="40" y1="30" x2="216" y2="226" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="#FFFFFF" stop-opacity=".9"/><stop offset=".5" stop-color="#FFFFFF" stop-opacity=".06"/>'
            f'<stop offset="1" stop-color="{warm}" stop-opacity=".6"/></linearGradient>',
            f'<filter id="{p}hg" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="3.5"/></filter>',
        ]
        bg = f'<rect width="108" height="108" fill="url(#{p}bg)"/>'
        fg.append(
            f'<g transform="{GROUP}">'
            f'<circle cx="128" cy="128" r="{c.fmt(c.R * 1.32)}" fill="url(#{p}halo)"/>'
            f"{c.mark_svg(key, p)}"
            f'<path fill="url(#{p}hl)" fill-rule="evenodd" d="{body}"/>'
            f'<path fill="none" stroke="url(#{p}rim)" stroke-width="3.5" d="{dial}"/>'
            f'<path fill="#FFFFFF" opacity=".75" filter="url(#{p}hg)" d="{hands_d()}"/>'
            f'<path fill="#FFFFFF" d="{hands_d()}"/></g>'
        )
    elif style == "dark":
        defs += [
            f'<linearGradient id="{p}bg" x1="0" y1="0" x2="0" y2="108" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="#14162E"/><stop offset="1" stop-color="#07080F"/></linearGradient>',
            f'<linearGradient id="{p}rim" x1="40" y1="30" x2="216" y2="226" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{mix(warm, "#FFFFFF", .4)}" stop-opacity=".85"/>'
            f'<stop offset=".5" stop-color="{mid}" stop-opacity=".1"/><stop offset="1" stop-color="{mid}" stop-opacity=".55"/></linearGradient>',
            f'<filter id="{p}hg" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="3"/></filter>',
        ]
        pale = mix(warm, "#FFFFFF", .75)
        bg = f'<rect width="108" height="108" fill="url(#{p}bg)"/>'
        fg.append(
            f'<g transform="{GROUP}">{c.mark_svg(key, p)}'
            f'<path fill="#05060C" fill-opacity=".52" fill-rule="evenodd" d="{body}"/>'
            f'<path fill="none" stroke="url(#{p}rim)" stroke-width="3" d="{dial}"/>'
            f'<path fill="{warm}" opacity=".7" filter="url(#{p}hg)" d="{hands_d()}"/>'
            f'<path fill="{pale}" d="{hands_d()}"/></g>'
        )
    elif style in ("glass", "oneui"):
        vivid = style == "oneui"
        a_defs, a_body = aurora(key, p, vivid)
        defs += [a_defs,
                 f'<filter id="{p}bl" x="-10%" y="-10%" width="120%" height="120%"><feGaussianBlur stdDeviation="{5 if vivid else 6}"/></filter>',
                 f'<clipPath id="{p}dc"><path transform="{GROUP}" d="{dial}"/></clipPath>',
                 f'<linearGradient id="{p}rim" x1="40" y1="30" x2="216" y2="226" gradientUnits="userSpaceOnUse">'
                 f'<stop offset="0" stop-color="#FFFFFF" stop-opacity=".95"/><stop offset=".5" stop-color="#FFFFFF" stop-opacity=".15"/>'
                 f'<stop offset="1" stop-color="#FFFFFF" stop-opacity=".6"/></linearGradient>',
                 f'<linearGradient id="{p}fr" x1="0" y1="22" x2="0" y2="234" gradientUnits="userSpaceOnUse">'
                 f'<stop offset="0" stop-color="#FFFFFF" stop-opacity="{.26 if vivid else .42}"/>'
                 f'<stop offset="1" stop-color="#FFFFFF" stop-opacity="{.04 if vivid else .16}"/></linearGradient>',
                 f'<filter id="{p}sh" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="2.5"/></filter>',
                 f'<linearGradient id="{p}tint" x1="0" y1="22" x2="0" y2="234" gradientUnits="userSpaceOnUse">'
                 f'<stop offset="0" stop-color="{warm}" stop-opacity=".55"/><stop offset=".55" stop-color="{mid}" stop-opacity=".45"/>'
                 f'<stop offset="1" stop-color="{deep}" stop-opacity=".6"/></linearGradient>']
        bg = a_body
        trail = "".join(f'<path fill="#FFFFFF" fill-opacity="{round(alpha * .45, 3)}" d="{c.dial(rot, sc)}"/>'
                        for rot, sc, alpha in c.ECHOES)
        ink = "#FFFFFF" if vivid else mix(deep, "#000000", .15)
        fg.append(
            f'<g clip-path="url(#{p}dc)"><g filter="url(#{p}bl)">{a_body}</g></g>'
            f'<g transform="{GROUP}">'
            f'<g clip-path="url(#{p}holes2)">{trail}</g>'
            + (f'<path fill="url(#{p}tint)" d="{dial}"/>' if vivid else '')
            + f'<path fill="url(#{p}fr)" d="{dial}"/>'
            f'<path fill="none" stroke="url(#{p}rim)" stroke-width="3" d="{dial}"/>'
            f'<path fill="#000000" opacity=".18" filter="url(#{p}sh)" d="{hands_d()}"/>'
            f'<path fill="{ink}" d="{hands_d()}"/></g>'
        )
        defs.append(f'<clipPath id="{p}holes2"><path d="{c.holes_clip_d()}"/></clipPath>')
    elif style == "matte":
        ground = mix(mid, "#FFFFFF", .7)
        defs += [
            f'<linearGradient id="{p}bg" x1="0" y1="0" x2="0" y2="108" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{mix(warm, "#FFFFFF", .72)}"/><stop offset="1" stop-color="{ground}"/></linearGradient>',
            f'<linearGradient id="{p}fr" x1="0" y1="22" x2="0" y2="234" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="#FFFFFF" stop-opacity=".97"/><stop offset="1" stop-color="#FFFFFF" stop-opacity=".8"/></linearGradient>',
            f'<radialGradient id="{p}sh" cx="128" cy="140" r="{c.fmt(c.R * 1.18)}" gradientUnits="userSpaceOnUse">'
            f'<stop offset=".75" stop-color="{deep}" stop-opacity=".28"/><stop offset="1" stop-color="{deep}" stop-opacity="0"/></radialGradient>',
        ]
        bg = f'<rect width="108" height="108" fill="url(#{p}bg)"/>'
        trail = "".join(f'<path fill="{mid}" fill-opacity="{round(alpha * .8, 3)}" d="{c.dial(rot, sc)}"/>'
                        for rot, sc, alpha in c.ECHOES)
        defs.append(f'<clipPath id="{p}holes2"><path d="{c.holes_clip_d()}"/></clipPath>')
        fg.append(
            f'<g transform="{GROUP}">'
            f'<circle cx="128" cy="140" r="{c.fmt(c.R * 1.18)}" fill="url(#{p}sh)"/>'
            f'<g clip-path="url(#{p}holes2)">{trail}</g>'
            f'<path fill="url(#{p}fr)" d="{dial}"/>'
            f'<path fill="none" stroke="#FFFFFF" stroke-width="2.5" d="{dial}"/>'
            f'<path fill="{deep}" d="{hands_d()}"/></g>'
        )
    elif style == "amoled":
        defs += [f'<filter id="{p}hg" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="4"/></filter>']
        bg = '<rect width="108" height="108" fill="#000000"/>'
        trail = "".join(f'<path fill="none" stroke="{mid}" stroke-opacity="{round(alpha * .7, 3)}" stroke-width="2" d="{c.dial(rot, sc)}"/>'
                        for rot, sc, alpha in c.ECHOES)
        fg.append(
            f'<g transform="{GROUP}">{trail}'
            f'<path fill="#0B0B10" d="{dial}"/>'
            f'<path fill="none" stroke="{mid}" stroke-width="6" opacity=".8" filter="url(#{p}hg)" d="{dial}"/>'
            f'<path fill="none" stroke="{mix(mid, "#FFFFFF", .25)}" stroke-width="3.5" d="{dial}"/>'
            f'<path fill="#FFFFFF" d="{hands_d()}"/></g>'
        )
    return "".join(defs), bg, "".join(fg)


def doc(inner, view="18 18 72 72", size=512, title="Дневник"):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}" width="{size}" height="{size}" role="img">'
            f"<title>{title}</title>{inner}</svg>\n")


def icon(key, style, p=""):
    """The visible square (72 dp) with ground and mark, as launchers and stores show it."""
    defs, bg, fg = style_svg(key, style, p)
    return f"<defs>{defs}</defs>{bg}{fg}"


def layer(key, style, which, p=""):
    """One adaptive layer on the full 108 canvas."""
    defs, bg, fg = style_svg(key, style, p)
    return f"<defs>{defs}</defs>" + (bg if which == "background" else fg)


# ---------------------------------------------------------------- Android
def vd_grad_attr(name, g, ind):
    return bp.vd_fill(g, ind).replace('name="android:fillColor"', f'name="android:{name}"')


def vd_stroke(d, ind, width, grad=None, color=None, alpha=None):
    a = f'\n{ind}    android:strokeAlpha="{alpha}"' if alpha is not None else ""
    if grad:
        return (f'{ind}<path\n{ind}    android:pathData="{d}"\n{ind}    android:strokeWidth="{width}"{a}>'
                f'{vd_grad_attr("strokeColor", grad, ind + "    ")}\n{ind}</path>')
    return (f'{ind}<path\n{ind}    android:pathData="{d}"\n{ind}    android:strokeWidth="{width}"'
            f'\n{ind}    android:strokeColor="{color}"{a}/>')


def vd_doc(note, inner, grouped=True):
    head = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- {note} Generated by build_styles.py: edit it, not this file. -->",
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            '    xmlns:aapt="http://schemas.android.com/aapt"',
            '    android:width="108dp"', '    android:height="108dp"',
            '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    if grouped:
        head.append(f'    <group\n        android:scaleX="{K:.5f}"\n        android:scaleY="{K:.5f}"\n'
                    f'        android:translateX="{T:.4f}"\n        android:translateY="{T:.4f}">')
        return "\n".join(head + inner + ["    </group>", "</vector>", ""])
    return "\n".join(head + inner + ["</vector>", ""])


def lin(x1, y1, x2, y2, stops):
    return dict(kind="linear", x1=x1, y1=y1, x2=x2, y2=y2, stops=stops)


def rad(cx, cy, r, stops):
    return dict(kind="radial", cx=cx, cy=cy, r=r, stops=stops)


def trail_vd(key, ind, mono=None, alpha_k=1.0):
    out = [f"{ind}<group>", f'{ind}    <clip-path android:pathData="{c.holes_clip_d()}"/>']
    _, _, ech = c.gradients(key)
    for i, (rot, sc, alpha) in enumerate(c.ECHOES):
        a = round(alpha * alpha_k, 3)
        out.append(bp.vd_path(c.dial(rot, sc), ind + "    ", fill=mono, alpha=a) if mono
                   else bp.vd_path(c.dial(rot, sc), ind + "    ", grad=ech[i], alpha=a))
    out.append(f"{ind}</group>")
    return out


def vd_layers(key, style):
    """(background VectorDrawable, foreground VectorDrawable) for a vector style."""
    warm, mid, deep = cols(key)
    base, glow, _ = c.gradients(key)
    i8 = " " * 8
    full = "M0 0 H108 V108 H0 Z"
    dial, body, hands = c.dial(), c.body_d(), hands_d()
    halo_c = f"M128 {c.fmt(128 - c.R * 1.32)} A{c.fmt(c.R * 1.32)} {c.fmt(c.R * 1.32)} 0 1 1 128 {c.fmt(128 + c.R * 1.32)} A{c.fmt(c.R * 1.32)} {c.fmt(c.R * 1.32)} 0 1 1 128 {c.fmt(128 - c.R * 1.32)} Z"
    name = f"«Дневник», {STYLES[style]}, {c.PALETTES[key][0]}."
    if style == "edge":
        bg = vd_doc(name + " Ground.", [bp.vd_path(full, "    ", fill="#FFFFFFFF")], grouped=False)
        fg = trail_vd(key, i8) + [bp.vd_path(body, i8, grad=base, evenodd=True), bp.vd_path(body, i8, grad=glow, evenodd=True)]
    elif style == "glow":
        bg = vd_doc(name + " Ground.", [bp.vd_path(full, "    ", grad=lin(54, 0, 54, 108, [(0, mix(deep, "#000000", .45), 1), (1, mix(deep, "#000000", .7), 1)]))], grouped=False)
        fg = ([bp.vd_path(halo_c, i8, grad=rad(128, 128, c.R * 1.32, [(.62, mid, .95), (.8, mid, .4), (1, mid, 0)]))]
              + trail_vd(key, i8)
              + [bp.vd_path(body, i8, grad=base, evenodd=True), bp.vd_path(body, i8, grad=glow, evenodd=True),
                 bp.vd_path(body, i8, grad=rad(104, 58, c.R * .95, [(0, "#FFFFFF", .5), (1, "#FFFFFF", 0)]), evenodd=True),
                 vd_stroke(dial, i8, 3.5, grad=lin(40, 30, 216, 226, [(0, "#FFFFFF", .9), (.5, "#FFFFFF", .06), (1, warm, .6)])),
                 bp.vd_path(hands, i8, fill="#FFFFFFFF")])
    elif style == "dark":
        bg = vd_doc(name + " Ground.", [bp.vd_path(full, "    ", grad=lin(54, 0, 54, 108, [(0, "#14162E", 1), (1, "#07080F", 1)]))], grouped=False)
        fg = (trail_vd(key, i8)
              + [bp.vd_path(body, i8, grad=base, evenodd=True), bp.vd_path(body, i8, grad=glow, evenodd=True),
                 bp.vd_path(body, i8, fill="#8505060C", evenodd=True),
                 vd_stroke(dial, i8, 3, grad=lin(40, 30, 216, 226, [(0, mix(warm, "#FFFFFF", .4), .85), (.5, mid, .1), (1, mid, .55)])),
                 bp.vd_path(hands, i8, fill=bp.vd_color(mix(warm, "#FFFFFF", .75)))])
    elif style == "matte":
        bg = vd_doc(name + " Ground.", [bp.vd_path(full, "    ", grad=lin(54, 0, 54, 108, [(0, mix(warm, "#FFFFFF", .72), 1), (1, mix(mid, "#FFFFFF", .7), 1)]))], grouped=False)
        shadow = f"M128 {c.fmt(140 - c.R * 1.18)} A{c.fmt(c.R * 1.18)} {c.fmt(c.R * 1.18)} 0 1 1 128 {c.fmt(140 + c.R * 1.18)} A{c.fmt(c.R * 1.18)} {c.fmt(c.R * 1.18)} 0 1 1 128 {c.fmt(140 - c.R * 1.18)} Z"
        fg = ([bp.vd_path(shadow, i8, grad=rad(128, 140, c.R * 1.18, [(.75, deep, .28), (1, deep, 0)]))]
              + trail_vd(key, i8, mono=bp.vd_color(mid), alpha_k=.8)
              + [bp.vd_path(dial, i8, grad=lin(128, 22, 128, 234, [(0, "#FFFFFF", .97), (1, "#FFFFFF", .8)])),
                 vd_stroke(dial, i8, 2.5, color="#FFFFFFFF"),
                 bp.vd_path(hands, i8, fill=bp.vd_color(deep))])
    elif style == "amoled":
        bg = vd_doc(name + " Ground.", [bp.vd_path(full, "    ", fill="#FF000000")], grouped=False)
        fg = ([vd_stroke(c.dial(rot, sc), i8, 2, color=bp.vd_color(mid), alpha=round(alpha * .7, 3)) for rot, sc, alpha in c.ECHOES]
              + [bp.vd_path(dial, i8, fill="#FF0B0B10"),
                 vd_stroke(dial, i8, 7, color=bp.vd_color(mid), alpha=.25),
                 vd_stroke(dial, i8, 3.5, color=bp.vd_color(mix(mid, "#FFFFFF", .25))),
                 bp.vd_path(hands, i8, fill="#FFFFFFFF")])
    return bg, vd_doc(name + " Mark, tips on the visible circle.", fg)


def monochrome_edge():
    i8 = " " * 8
    inner = trail_vd(KEYS[0], i8, mono="#FFFFFFFF") + [bp.vd_path(c.body_d(), i8, fill="#FFFFFFFF", evenodd=True)]
    return vd_doc("Monochrome layer for themed icons (Android 13+), edge to edge; alpha carries the trail.", inner)


def adaptive(rn, bg_ref, fg_ref):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="{bg_ref}"/>\n'
            f'    <foreground android:drawable="{fg_ref}"/>\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome_edge"/>\n'
            "</adaptive-icon>\n")


def build_android(png):
    res = OUT / "android" / "res"
    if res.exists():
        shutil.rmtree(res)
    bp.write(res / "drawable" / "ic_launcher_monochrome_edge.xml", monochrome_edge())
    raster_jobs = []
    for style in STYLES:
        for key in KEYS:
            rn = f"{style}_{bp.res_name(key)}"
            if style in RASTER:
                d = OUT / "work" / rn
                for which in ("background", "foreground"):
                    bp.write(d / f"{which}.svg", doc(layer(key, style, which, p=f"{rn}{which[0]}"), view="0 0 108 108", size=432))
                    raster_jobs.append((d / f"{which}.svg", res / "drawable-xxxhdpi" / f"ic_launcher_{rn}_{which}.png"))
                bg_ref, fg_ref = f"@drawable/ic_launcher_{rn}_background", f"@drawable/ic_launcher_{rn}_foreground"
            else:
                bg, fg = vd_layers(key, style)
                bp.write(res / "drawable" / f"ic_launcher_{rn}_background.xml", bg)
                bp.write(res / "drawable" / f"ic_launcher_{rn}_foreground.xml", fg)
                bg_ref, fg_ref = f"@drawable/ic_launcher_{rn}_background", f"@drawable/ic_launcher_{rn}_foreground"
            text = adaptive(rn, bg_ref, fg_ref)
            bp.write(res / "mipmap-anydpi-v26" / f"ic_launcher_{rn}.xml", text)
            bp.write(res / "mipmap-anydpi-v26" / f"ic_launcher_{rn}_round.xml", text)
    if png:
        for src, out in raster_jobs:
            out.parent.mkdir(parents=True, exist_ok=True)
            render_one(src, out, 432)


# ---------------------------------------------------------------- SVG, PNG, previews
def render_one(src, out, size):
    subprocess.run([sys.executable, str(bp.RENDER), str(src), "-o", str(out), "--width", str(size), "--height", str(size)],
                   check=True, capture_output=True)


SQUIRCLE = None


def squircle_d():
    pts = []
    for i in range(120):
        t = 2 * math.pi * i / 120
        x = 36 + 36 * math.copysign(abs(math.cos(t)) ** 0.4, math.cos(t))
        y = 36 + 36 * math.copysign(abs(math.sin(t)) ** 0.4, math.sin(t))
        pts.append(f"{x:.2f} {y:.2f}")
    return "M" + " L".join(pts) + " Z"


MASKS = {"circle": ("Круг", "M36 0 A36 36 0 1 1 36 72 A36 36 0 1 1 36 0 Z"),
         "squircle": ("Сквиркл", squircle_d()),
         "rounded": ("Скруглённый квадрат", "M12 0 H60 A12 12 0 0 1 72 12 V60 A12 12 0 0 1 60 72 H12 A12 12 0 0 1 0 60 V12 A12 12 0 0 1 12 0 Z")}


def masked(key, style, mask, size, p):
    m = MASKS[mask][1]
    return (f'<svg viewBox="18 18 72 72" width="{size}" height="{size}"><defs><clipPath id="{p}m">'
            f'<path transform="translate(18 18)" d="{m}"/></clipPath></defs>'
            f'<g clip-path="url(#{p}m)">{icon(key, style, p)}</g></svg>')


def build_svg():
    for style in STYLES:
        for key in KEYS:
            d = OUT / "svg" / style
            bp.write(d / f"dnevnik-{style}-{key}-icon.svg",
                     doc(icon(key, style, p="i"), title=f"Дневник — {STYLES[style]}, {c.PALETTES[key][0]}"))


def board():
    """All styles × all palettes, round masks; then the recommended palette under three masks."""
    rows = []
    for si, style in enumerate(STYLES):
        dark = style in ("glow", "dark", "amoled", "oneui")
        cells = "".join(
            f'<figure>{masked(key, style, "circle", 120, f"b{si}{ki}")}<figcaption>{c.PALETTES[key][0]}</figcaption></figure>'
            for ki, key in enumerate(KEYS))
        rows.append(f'<section class="{"dk" if dark else ""}"><h2>{STYLES[style]}</h2><div class="row">{cells}</div></section>')
    masks = []
    for si, style in enumerate(STYLES):
        cells = "".join(f'<figure>{masked(RECOMMENDED, style, m, 132, f"m{si}{mi}")}<figcaption>{MASKS[m][0]}</figcaption></figure>'
                        for mi, m in enumerate(MASKS))
        masks.append(f'<section class="mk"><h3>{STYLES[style]}</h3><div class="row">{cells}</div></section>')
    page = f"""<!doctype html><html><head><meta charset="utf-8"><style>
body{{margin:0;background:#EEF0F7;font-family:"Segoe UI",system-ui,sans-serif;color:#1E2340}}
h1{{font-size:34px;margin:30px 40px 4px}} p{{margin:0 40px 22px;color:#555B77;font-size:16px}}
section{{margin:0 28px 14px;padding:12px 18px 10px;border-radius:20px;background:#E1E5F2}}
section.dk{{background:#151728;color:#E8EAF6}} h2{{font-size:18px;margin:2px 0 8px}} h3{{font-size:15px;margin:2px 0 8px}}
.row{{display:flex;gap:16px;flex-wrap:wrap}} figure{{margin:0;text-align:center;font-size:12px;color:#7A809E}}
section.dk figure{{color:#9AA0C0}} .mks{{display:grid;grid-template-columns:repeat(2,1fr);margin:0 28px}} .mks section{{margin:0 0 14px}}
</style></head><body><h1>Пятёрка — стили</h1>
<p>Все стили во всю форму: кончики лепестков на краю видимого круга. В сквиркле и квадрате знак по центру с равными отступами.
Прежние восемь палитр на белом лежат в pack/ без изменений.</p>
{"".join(rows)}<h1 style="font-size:26px">Маски лаунчеров — {c.PALETTES[RECOMMENDED][0]}</h1><div class="mks">{"".join(masks)}</div>
</body></html>"""
    bp.write(OUT / "previews" / "styles.html", page)


def build_png():
    for style in STYLES:
        src_dir = OUT / "svg" / style
        out_dir = OUT / "png" / style
        out_dir.mkdir(parents=True, exist_ok=True)
        for key in KEYS:
            src = src_dir / f"dnevnik-{style}-{key}-icon.svg"
            render_one(src, out_dir / f"dnevnik-{style}-{key}-512.png", 512)
    subprocess.run([sys.executable, str(bp.RENDER), str(OUT / "previews" / "styles.html"), "-o",
                    str(OUT / "previews" / "styles.png"), "--width", "1560", "--height", "2560"], check=True, capture_output=True)


if __name__ == "__main__":
    png = "--no-png" not in sys.argv
    build_svg()
    board()
    build_android(png)
    if png:
        build_png()
    shutil.rmtree(OUT / "work", ignore_errors=True)
    print("styles built in", OUT)
