"""«Пятёрка» — final geometry, palettes and renderers (round 4).

Geometry lives on the 256 canvas, centre (128, 128):
  * dial      — 12-lobe M3 cookie, lobe circles r=18 at d=88, concave fillets r=7 (tips at 106);
  * hands     — tapered: each is the hull of a base circle (r=8.5) at the centre and a small tip
                circle, unioned with a hub (r=12); minute → 12, hour → 5;
  * marks     — quarter style: long at 12/3/6/9, short elsewhere, cut out of each lobe;
  * echoes    — five copies of the dial turned 3°…15° anticlockwise, 0.6 %…3 % larger,
                alpha 0.5…0.1 (the Essentials trail), clipped so every cut-out stays clean.
Every outline is built from lines and circular arcs, so it is exact at any size.
"""
import math

import gen

C = 128.0
N, D, RL, RC = 12, 88.0, 18.0, 7.0
R = D + RL                                    # lobe tip radius, 106
ECHOES = [(-3 * k, 1 + 0.006 * k, round(0.6 - 0.1 * k, 1)) for k in range(5, 0, -1)]
HUB = 12.0
HANDS = [(-90.0, 64.0, 8.5, 4.0),             # (screen angle, length, base r, tip r): minute → 12
         (60.0, 45.0, 8.5, 5.5)]              # hour → 5 (150° clockwise from 12)
HANDS_SMALL = [(-90.0, 60.0, 12.5, 6.5), (60.0, 42.0, 12.5, 8.0)]   # bolder cut for ≤ 32 px


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def pt(p):
    return f"{fmt(p[0])} {fmt(p[1])}"


class Outline:
    """Closed outline of line and arc segments; can be emitted in either direction."""

    def __init__(self, start):
        self.start, self.segs = start, []

    def L(self, p):
        self.segs.append(("L", p))

    def A(self, r, large, sweep, p):
        self.segs.append(("A", r, large, sweep, p))

    def d(self):
        s = f"M{pt(self.start)}"
        for g in self.segs:
            s += f" L{pt(g[1])}" if g[0] == "L" else f" A{fmt(g[1])} {fmt(g[1])} 0 {g[2]} {g[3]} {pt(g[4])}"
        return s + " Z"

    def reversed(self):
        pts = [self.start] + [g[-1] for g in self.segs]
        o = Outline(pts[-1])
        for i in range(len(self.segs) - 1, -1, -1):
            g = self.segs[i]
            if g[0] == "L":
                o.L(pts[i])
            else:
                o.A(g[1], g[2], 1 - g[3], pts[i])
        return o


def _rot(v, a):
    c, s = math.cos(a), math.sin(a)
    return (v[0] * c - v[1] * s, v[0] * s + v[1] * c)


def hands_outline(hands=HANDS, hub=HUB, cx=C, cy=C):
    """Exact union of tapered hands and the hub, traversed clockwise on screen."""
    items = []
    for phi, length, rb, rt in hands:
        u = (math.cos(math.radians(phi)), math.sin(math.radians(phi)))
        tip = (cx + length * u[0], cy + length * u[1])
        al = math.acos((rb - rt) / length)    # external tangent of base and tip circles
        sides = {}
        for sgn in (-1, 1):                   # -1: the hand's anticlockwise side
            n = _rot(u, sgn * al)
            b = (cx + rb * n[0], cy + rb * n[1])
            p = (tip[0] + rt * n[0], tip[1] + rt * n[1])
            dx, dy = p[0] - b[0], p[1] - b[1]
            ln = math.hypot(dx, dy)
            dx, dy = dx / ln, dy / ln
            wd = (b[0] - cx) * dx + (b[1] - cy) * dy
            t = -wd + math.sqrt(wd * wd - (rb * rb - hub * hub))   # where the side leaves the hub
            sides[sgn] = ((b[0] + t * dx, b[1] + t * dy), p)
        ang = math.atan2(sides[-1][0][1] - cy, sides[-1][0][0] - cx)
        items.append((ang, sides, rt, al))
    items.sort(key=lambda x: x[0])
    o = Outline(items[0][1][-1][0])
    for i, (_, sides, rt, al) in enumerate(items):
        (qm, pm), (qp, pp) = sides[-1], sides[1]
        o.L(pm)
        o.A(rt, 1 if 2 * al > math.pi else 0, 1, pp)
        o.L(qp)
        qn = items[(i + 1) % len(items)][1][-1][0]
        span = (math.atan2(qn[1] - cy, qn[0] - cx) - math.atan2(qp[1] - cy, qp[0] - cx)) % (2 * math.pi)
        o.A(hub, 1 if span > math.pi else 0, 1, qn)
    return o


def marks():
    """Quarter marks; each pill is traversed anticlockwise (gen.pill)."""
    out = []
    for i in range(12):
        a = math.radians(i * 30)
        sx, sy = math.sin(a), -math.cos(a)
        r0, r1, w = (82, 95, 9) if i % 3 == 0 else (88, 95, 7)
        out.append(gen.pill(C + r0 * sx, C + r0 * sy, C + r1 * sx, C + r1 * sy, w))
    return " ".join(out)


def dial(rot_deg=0.0, scale=1.0):
    return gen.cookie(C, C, N, D * scale, RL * scale, RC * scale, rot=math.radians(rot_deg))


def body_d(small=False):
    """Dial with every cut-out (use fill-rule evenodd)."""
    if small:
        return f"{dial()} {hands_outline(HANDS_SMALL, hub=15).d()}"
    return f"{dial()} {hands_outline().d()} {marks()}"


def holes_clip_d():
    """Everything except the cut-outs, as nonzero winding: big clockwise square,
    anticlockwise holes. Works as an SVG clipPath and as a VectorDrawable clip-path."""
    return f"M-64 -64 H320 V320 H-64 Z {hands_outline().reversed().d()} {marks()}"


# ---------------------------------------------------------------- palettes
# Three colours per palette, used the way Essentials' launcher uses its three:
#   warm — the centre of the radial and the top of the overlay (sits under the hands, so it is
#          kept mid-light: white hands keep about Essentials' 2:1 contrast on it);
#   mid  — the radial at 53 %; deep — the radial's rim.
PALETTES = {
    "rassvet":       ("Рассвет",           ["#FF9A6C", "#E86FB0", "#6A4FD8"]),
    "rassvet-light": ("Рассвет · светлый", ["#FFB38A", "#F28FC0", "#8A78EE"]),
    "indigo":        ("Индиго",            ["#A99BFF", "#6E7EEB", "#3443AE"]),
    "indigo-light":  ("Индиго · светлый",  ["#BFAEFF", "#8E9CF5", "#5868DC"]),
    "myata":         ("Мята",              ["#6CC48A", "#3AA6D9", "#3C55C8"]),
    "myata-light":   ("Мята · светлый",    ["#84D3A2", "#6BBDEA", "#5F79E0"]),
    "lavanda":       ("Лаванда",           ["#C58BFF", "#5E9CF5", "#2F5ACA"]),
    "lavanda-light": ("Лаванда · светлый", ["#D6A1FF", "#72B6FF", "#4072F6"]),   # Essentials' own
}
# Where a palette repeats a source exactly, its derived colours are pinned to the source's values.
EXACT = {"lavanda-light": dict(over_bottom="#4C79ED", echo0="#C497FF", echo2="#2F5ACA")}


def _mix(hex_a, hex_b, t):
    a = [int(hex_a[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(hex_b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(a, b))


def gradients(key):
    """The Essentials fill, position and shape included, for one palette:
      main    — radial from the dial's centre out to its lobe tips: warm 0 → mid .53 → deep 1;
      overlay — vertical, top tip to bottom tip: warm (opaque) → mid (transparent) at .53 →
                deep lifted a touch towards mid at .95 alpha;
      echoes  — each along its own turned axis: warm deepened → mid at .41 → deep darkened.
    For «Лаванда · светлый» these are Essentials' launcher values exactly."""
    warm, mid, deep = PALETTES[key][1]
    x = EXACT.get(key, {})
    main = dict(kind="radial", cx=C, cy=C, r=R, stops=[(0, warm, 1), (.53, mid, 1), (1, deep, 1)])
    over = dict(kind="linear", x1=C, y1=C - R, x2=C, y2=C + R,
                stops=[(0, warm, 1), (.53, mid, 0), (1, x.get("over_bottom", _mix(deep, mid, .1)), .95)])
    e0 = x.get("echo0", _mix(warm, deep, .1))
    e2 = x.get("echo2", _mix(deep, "#000000", .2))
    echoes = []
    for rot, sc, _ in ECHOES:
        a = math.radians(rot)
        r = R * sc
        echoes.append(dict(kind="linear",
                           x1=C + r * math.sin(a), y1=C - r * math.cos(a),
                           x2=C - r * math.sin(a), y2=C + r * math.cos(a),
                           stops=[(0, e0, 1), (.41, mid, 1), (1, e2, 1)]))
    return main, over, echoes


# ---------------------------------------------------------------- SVG
def _svg_grad(gid, g):
    stops = "".join(f'<stop offset="{o}" stop-color="{c}"' + (f' stop-opacity="{a}"' if a != 1 else "") + "/>"
                    for o, c, a in g["stops"])
    if g["kind"] == "linear":
        return (f'<linearGradient id="{gid}" x1="{fmt(g["x1"])}" y1="{fmt(g["y1"])}" x2="{fmt(g["x2"])}" '
                f'y2="{fmt(g["y2"])}" gradientUnits="userSpaceOnUse">{stops}</linearGradient>')
    return (f'<radialGradient id="{gid}" cx="{fmt(g["cx"])}" cy="{fmt(g["cy"])}" r="{fmt(g["r"])}" '
            f'gradientUnits="userSpaceOnUse">{stops}</radialGradient>')


def mark_svg(key=None, p="", mono=None, echoes=True, small=False):
    """The mark on the 256 canvas: echoes + dial. key=palette, or mono=one colour."""
    defs, parts = [], []
    if key:
        base, glow, ech = gradients(key)
        defs += [_svg_grad(f"{p}base", base), _svg_grad(f"{p}glow", glow)]
        defs += [_svg_grad(f"{p}e{i}", g) for i, g in enumerate(ech)]
    if echoes and not small:
        defs.append(f'<clipPath id="{p}holes"><path d="{holes_clip_d()}"/></clipPath>')
        parts.append(f'<g clip-path="url(#{p}holes)">')
        for i, (rot, sc, alpha) in enumerate(ECHOES):
            fill = mono or f"url(#{p}e{i})"
            parts.append(f'<path fill="{fill}" fill-opacity="{alpha}" d="{dial(rot, sc)}"/>')
        parts.append("</g>")
    d = body_d(small)
    if mono:
        parts.append(f'<path fill="{mono}" fill-rule="evenodd" d="{d}"/>')
    else:
        parts.append(f'<path fill="url(#{p}base)" fill-rule="evenodd" d="{d}"/>')
        parts.append(f'<path fill="url(#{p}glow)" fill-rule="evenodd" d="{d}"/>')
    return (f"<defs>{''.join(defs)}</defs>" if defs else "") + "".join(parts)


def svg_doc(inner, w=256, h=256, title="Дневник — логотип"):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" '
            f'role="img"><title>{title}</title>{inner}</svg>\n')


FILL = 0.9   # dial tips at 90 % of the visible icon radius (inside Android's 66 dp safe zone)


def icon_svg(key=None, p="", mono=None, bg="#FFFFFF", fill=FILL, small=False):
    """Full-bleed square icon, 256 canvas = the visible 72 dp of an adaptive icon."""
    k = fill * 128 / R
    g = (f'<g transform="translate(128 128) scale({fmt(k)}) translate(-128 -128)">'
         f'{mark_svg(key, p, mono, small=small)}</g>')
    return f'<rect width="256" height="256" fill="{bg}"/>{g}'
