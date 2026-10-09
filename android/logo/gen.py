"""Generate black concept symbols for the «Дневник» logo (v1).

All marks live on a 256 x 256 canvas. Geometry is computed, not eyeballed.
"""
import math
import sys
from pathlib import Path

OUT = Path(__file__).parent
VER = sys.argv[1] if len(sys.argv) > 1 and __name__ == "__main__" else "v1"


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def svg(body, title):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256" '
        f'role="img" aria-labelledby="t"><title id="t">{title}</title>\n{body}\n</svg>\n'
    )


# ---------------------------------------------------------------- shared shapes

def pill(x1, y1, x2, y2, w):
    """Stadium between two points, width w, as a closed path (round caps)."""
    r = w / 2
    dx, dy = x2 - x1, y2 - y1
    L = math.hypot(dx, dy)
    nx, ny = -dy / L * r, dx / L * r
    return (
        f"M{f(x1+nx)} {f(y1+ny)} L{f(x2+nx)} {f(y2+ny)} "
        f"A{f(r)} {f(r)} 0 0 0 {f(x2-nx)} {f(y2-ny)} "
        f"L{f(x1-nx)} {f(y1-ny)} A{f(r)} {f(r)} 0 0 0 {f(x1+nx)} {f(y1+ny)} Z"
    )


def cookie(cx, cy, n, d, rl, rc, rot=0.0):
    """M3-style scalloped 'cookie': n lobe circles (radius rl at distance d)
    joined by concave fillets of radius rc. Built purely from arcs."""
    half = math.pi / n
    # fillet centre distance f on the bisector: f^2 + d^2 - 2 f d cos(half) = (rl+rc)^2
    a = 1.0
    b = -2 * d * math.cos(half)
    c = d * d - (rl + rc) ** 2
    fd = (-b + math.sqrt(b * b - 4 * a * c)) / 2
    pts = []
    for i in range(n):
        th = rot + i * 2 * math.pi / n - math.pi / 2
        C = (cx + d * math.cos(th), cy + d * math.sin(th))
        # fillets before and after this lobe
        fb = th - half
        fa = th + half
        Fb = (cx + fd * math.cos(fb), cy + fd * math.sin(fb))
        Fa = (cx + fd * math.cos(fa), cy + fd * math.sin(fa))

        def tp(F):
            vx, vy = F[0] - C[0], F[1] - C[1]
            L = math.hypot(vx, vy)
            return (C[0] + vx / L * rl, C[1] + vy / L * rl)

        pts.append((tp(Fb), tp(Fa)))
    d_ = f"M{f(pts[0][0][0])} {f(pts[0][0][1])} "
    for i in range(n):
        s, e = pts[i]
        d_ += f"A{f(rl)} {f(rl)} 0 0 1 {f(e[0])} {f(e[1])} "
        ns = pts[(i + 1) % n][0]
        d_ += f"A{f(rc)} {f(rc)} 0 0 0 {f(ns[0])} {f(ns[1])} "
    return d_ + "Z"


def sine_path(x0, y, wavelength, amp, periods, start_up=True):
    """Sine wave as cubic Béziers (one per quarter wave, standard approximation)."""
    q = wavelength / 4
    kx = q / (math.pi / 2)
    c1x, c1y = 0.512286623256592433 * kx, 0.512286623256592433
    c2x, c2y = 1.002313685767898599 * kx, 1.0
    sgn = -1 if start_up else 1  # SVG y grows downward
    d = f"M{f(x0)} {f(y)} "
    x = x0
    for k in range(periods * 4):
        quarter = k % 4
        s = sgn * (1 if quarter in (0, 1) else -1) * amp
        if quarter in (0, 2):  # from axis to peak
            d += (f"C{f(x + c1x)} {f(y + s * c1y)} {f(x + c2x)} {f(y + s * c2y)} "
                  f"{f(x + q)} {f(y + s)} ")
        else:  # from peak back to axis (mirror)
            d += (f"C{f(x + q - c2x)} {f(y + s * c2y)} {f(x + q - c1x)} {f(y + s * c1y)} "
                  f"{f(x + q)} {f(y)} ")
        x += q
    return d


# ---------------------------------------------------------------- A: bell

def bell(gap=10, rib=26):
    cx = 128
    domec, R = 92, 52          # dome circle centre y and radius -> top at y=40
    g = rib / 2 + gap          # half-distance from axis to each half's inner edge
    ytop = domec - math.sqrt(R * R - g * g)
    lip_y0, lip_y1 = 176, 196
    halves = []
    for s in (1, -1):
        X = lambda x: cx + s * x  # noqa: E731
        sw = 1 if s == 1 else 0
        sw2 = 0 if s == 1 else 1
        d = (
            f"M{f(X(g))} {f(ytop)} "
            f"A{R} {R} 0 0 {sw} {f(X(R))} {domec} "
            f"L{f(X(R))} 120 "
            f"C{f(X(R))} 160 {f(X(R + 14))} 182 {f(X(R + 30))} 182 "
            f"A7 7 0 0 {sw} {f(X(R + 30))} {lip_y1} "
            f"L{f(X(g))} {lip_y1} Z"
        )
        halves.append(d)
    r = rib / 2
    top = 26
    bot = 236
    notch = 14
    ribbon = (
        f"M{f(cx - r)} {top + r} A{f(r)} {f(r)} 0 0 1 {f(cx + r)} {top + r} "
        f"L{f(cx + r)} {bot} L{cx} {bot - notch} L{f(cx - r)} {bot} Z"
    )
    body = "".join(f'<path d="{h}"/>' for h in halves) + f'<path d="{ribbon}"/>'
    return svg(f'<g fill="#000">{body}</g>', "Дневник — концепция A, Звонок")


# ---------------------------------------------------------------- B: page + wavy line

def page_wave():
    x0, x1, y0, y1, r = 44, 212, 32, 224, 48
    page = (
        f"M{x0 + r} {y0} H{x1 - r} A{r} {r} 0 0 1 {x1} {y0 + r} V{y1 - r} "
        f"A{r} {r} 0 0 1 {x1 - r} {y1} H{x0 + r} A{r} {r} 0 0 1 {x0} {y1 - r} "
        f"V{y0 + r} A{r} {r} 0 0 1 {x0 + r} {y0} Z"
    )
    sw = 16
    lines = [
        f'<path d="M84 84 H180" />',
        f'<path d="{sine_path(84, 128, 40, 6.5, 2)}" />',
        f'<path d="M84 172 H136" />',
    ]
    dot = '<circle cx="180" cy="128" r="8" fill="#fff"/>'
    body = (
        f'<path fill="#000" d="{page}"/>'
        f'<g fill="none" stroke="#fff" stroke-width="{sw}" stroke-linecap="round" '
        f'stroke-linejoin="round">{"".join(lines)}</g>{dot}'
    )
    return svg(body, "Дневник — концепция B, Урок идёт")


# ---------------------------------------------------------------- C: twelve-lobe clock at five

def clock_five():
    cx, cy = 128, 128
    dial = cookie(cx, cy, 12, 82, 24, 10)
    ang = math.radians(150)  # 5 o'clock, clockwise from 12
    hx, hy = cx + 50 * math.sin(ang), cy - 50 * math.cos(ang)
    hands = pill(cx, cy, cx, cy - 70, 22) + " " + pill(cx, cy, hx, hy, 22)
    body = (
        f'<path fill="#000" d="{dial}"/>'
        f'<path fill="#fff" d="{hands}"/><circle cx="{cx}" cy="{cy}" r="17" fill="#fff"/>'
    )
    return svg(body, "Дневник — концепция C, Пятёрка")


if __name__ == "__main__":
  for name, fn in (("a-bell", bell), ("b-wave", page_wave), ("c-five", clock_five)):
    (OUT / f"{name}-{VER}.svg").write_text(fn(), encoding="utf-8")
    print("wrote", name)


# ---------------------------------------------------------------- v3 explorations

def bell_outline(cx=128, top=44, crown_r=46, lip_w=88, lip_y=184, lip_h=14):
    """Whole bell silhouette: crown arc, concave waist, flared sound-bow, round lip ends."""
    cy = top + crown_r
    R = crown_r
    h = lip_h / 2
    d = f"M{cx - R} {cy} A{R} {R} 0 0 1 {cx + R} {cy} "
    d += (f"C{cx + R} {cy + 58} {cx + R + 10} {lip_y - h - 4} {cx + lip_w - h} {lip_y - h} "
          f"A{h} {h} 0 0 1 {cx + lip_w - h} {lip_y + h} "
          f"H{cx - lip_w + h} A{h} {h} 0 0 1 {cx - lip_w + h} {lip_y - h} "
          f"C{cx - R - 10} {lip_y - h - 4} {cx - R} {cy + 58} {cx - R} {cy} Z")
    return d


def ribbon_path(cx=128, w=28, top=24, bot=236, notch=14):
    r = w / 2
    return (f"M{cx - r} {top + r} A{r} {r} 0 0 1 {cx + r} {top + r} "
            f"L{cx + r} {bot} L{cx} {bot - notch} L{cx - r} {bot} Z")


def bell_whole():
    """Mono: bell with the ribbon cut through it (gap), ribbon continues outside."""
    b = bell_outline()
    rib = ribbon_path()
    gap = 9
    # channel = ribbon widened by the gap, cut out of the bell with evenodd
    ch = ribbon_path(w=28 + 2 * gap, top=0, bot=256, notch=0)
    body = (f'<path fill="#000" fill-rule="evenodd" d="{b} {ch}"/>'
            f'<path fill="#000" d="{rib}"/>')
    return svg(body, "Дневник — A, Звонок (v3)")


def lines_wave():
    """B without the page: three lessons, the current one is a wavy progress line."""
    sw = 24
    body = (
        f'<g fill="none" stroke="#000" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round">'
        f'<path d="M44 68 H212"/>'
        f'<path d="{sine_path(44, 128, 52, 9, 2)}"/>'
        f'<path d="M44 188 H156"/></g>'
        f'<circle cx="200" cy="128" r="12" fill="#000"/>'
    )
    return svg(body, "Дневник — B, Урок идёт (v3)")


# ---------------------------------------------------------------- v4

def bell_half(s, g, cx=128, domec=98, R=58, lip_x=98, lip_y=188, lip_h=14):
    """One half of the bell (s=+1 right, -1 left), cut at distance g from the axis."""
    X = lambda x: cx + s * x  # noqa: E731
    sw = 1 if s == 1 else 0
    ytop = domec - math.sqrt(R * R - g * g)
    h = lip_h / 2
    return (f"M{f(X(g))} {f(ytop)} A{R} {R} 0 0 {sw} {f(X(R))} {domec} "
            f"L{f(X(R))} 120 "
            f"C{f(X(R))} 160 {f(X(R + 10))} {lip_y - h} {f(X(lip_x - h))} {lip_y - h} "
            f"A{h} {h} 0 0 {sw} {f(X(lip_x - h))} {lip_y + h} "
            f"L{f(X(g))} {lip_y + h} Z")


def bell_v4(rib=24, gap=8):
    g = rib / 2 + gap
    halves = bell_half(1, g) + " " + bell_half(-1, g)
    rib_d = ribbon_path(w=rib, top=22, bot=238, notch=12)
    return svg(f'<path fill="#000" d="{halves}"/><path fill="#000" d="{rib_d}"/>',
               "Дневник — A, Звонок")


def bell_whole_v4():
    """Colour version base: the whole bell (both halves meeting at the axis)."""
    return bell_half(1, 0.001) + " " + bell_half(-1, 0.001)


def rrect(x0, y0, x1, y1, r_tl, r_tr, r_br, r_bl):
    return (f"M{x0 + r_tl} {y0} H{x1 - r_tr} A{r_tr} {r_tr} 0 0 1 {x1} {y0 + r_tr} "
            f"V{y1 - r_br} A{r_br} {r_br} 0 0 1 {x1 - r_br} {y1} H{x0 + r_bl} "
            f"A{r_bl} {r_bl} 0 0 1 {x0} {y1 - r_bl} V{y0 + r_tl} A{r_tl} {r_tl} 0 0 1 {x0 + r_tl} {y0} Z")


SPREAD = dict(x0=24, x1=232, y0=48, y1=208, spine=12, ro=40, ri=10)


def spread_parts():
    p = SPREAD
    mid = 128
    lx1 = mid - p["spine"] / 2
    rx0 = mid + p["spine"] / 2
    left = rrect(p["x0"], p["y0"], lx1, p["y1"], p["ro"], p["ri"], p["ri"], p["ro"])
    right = rrect(rx0, p["y0"], p["x1"], p["y1"], p["ri"], p["ro"], p["ro"], p["ri"])
    rows_y = (92, 128, 164)
    pad_o, pad_i = 26, 18
    rows = []  # (x0, x1, y, kind)
    for y in rows_y:
        rows.append((p["x0"] + pad_o, lx1 - pad_i, y, "line"))
    for i, y in enumerate(rows_y):
        rows.append((rx0 + pad_i, p["x1"] - pad_o, y, "wave" if i == 1 else "line"))
    return left, right, rows


def spread_row_d(x0, x1, y, kind):
    if kind == "wave":
        return sine_path(x0, y, (x1 - x0) / 2, 5, 2)
    return f"M{x0} {y} H{x1}"


def spread_v4(sw=16):
    left, right, rows = spread_parts()
    strokes = "".join(f'<path d="{spread_row_d(*r)}"/>' for r in rows)
    return svg(
        f'<path fill="#000" d="{left} {right}"/>'
        f'<g fill="none" stroke="#fff" stroke-width="{sw}" stroke-linecap="round" '
        f'stroke-linejoin="round">{strokes}</g>',
        "Дневник — B, Разворот")


def hands_union(cx=128, cy=128, w=22, rp=17, lm=70, lh=50, hour_deg=150):
    """Exact outline of minute pill (12) ∪ hour pill ∪ pivot disc, traversed clockwise."""
    h = w / 2
    t0 = math.sqrt(rp * rp - h * h)
    a = math.radians(hour_deg)
    dx, dy = math.sin(a), -math.cos(a)
    nx, ny = -dy, dx  # left normal of the hour direction
    P = lambda t, k: (cx + t * dx + k * nx, cy + t * dy + k * ny)  # noqa: E731
    m_l, m_r = (cx - h, cy - t0), (cx + h, cy - t0)
    h_a0, h_a1 = P(t0, -h), P(lh, -h)
    h_b1, h_b0 = P(lh, h), P(t0, h)
    pt = lambda p: f"{f(p[0])} {f(p[1])}"  # noqa: E731
    return (f"M{pt(m_l)} V{f(cy - lm)} A{f(h)} {f(h)} 0 0 1 {f(cx + h)} {f(cy - lm)} V{f(m_r[1])} "
            f"A{rp} {rp} 0 0 1 {pt(h_a0)} L{pt(h_a1)} A{f(h)} {f(h)} 0 0 1 {pt(h_b1)} "
            f"L{pt(h_b0)} A{rp} {rp} 0 0 1 {pt(m_l)} Z")


def clock_five_v6():
    dial = cookie(128, 128, 12, 82, 24, 10)
    return svg(f'<path fill="#000" fill-rule="evenodd" d="{dial} {hands_union()}"/>',
               "Дневник — C, Пятёрка")


def arc_wave_outline(x0, y, length, amp, halves, w):
    """Filled outline of a thick wave built from alternating circular arcs
    (G1-continuous), so its offsets are exact concentric arcs. Starts bulging up."""
    c = length / halves
    r = (c * c / 4 + amp * amp) / (2 * amp)
    h = w / 2
    cs = []
    for k in range(halves):
        s = 1 if k % 2 == 0 else -1          # +1: bulges up (centre below)
        cs.append((x0 + c * k + c / 2, y + s * (r - amp), s))
    J = [(x0 + c * k, y) for k in range(halves + 1)]

    def on(C, rad, P):
        ux, uy = (P[0] - C[0]) / r, (P[1] - C[1]) / r
        return (C[0] + rad * ux, C[1] + rad * uy)

    pt = lambda p: f"{f(p[0])} {f(p[1])}"  # noqa: E731
    C0 = cs[0]
    d = f"M{pt(on(C0, r + C0[2] * h, J[0]))} "
    for k, (cx_, cy_, s) in enumerate(cs):
        rad = r + s * h
        d += f"A{f(rad)} {f(rad)} 0 0 {1 if s == 1 else 0} {pt(on((cx_, cy_), rad, J[k + 1]))} "
    Cl = cs[-1]
    d += f"A{f(h)} {f(h)} 0 0 1 {pt(on(Cl, r - Cl[2] * h, J[-1]))} "
    for k in range(halves - 1, -1, -1):
        cx_, cy_, s = cs[k]
        rad = r - s * h
        d += f"A{f(rad)} {f(rad)} 0 0 {0 if s == 1 else 1} {pt(on((cx_, cy_), rad, J[k]))} "
    d += f"A{f(h)} {f(h)} 0 0 1 {pt(on(C0, r + C0[2] * h, J[0]))} Z"
    return d


def hpill(x0, x1, y, w):
    return pill(x0, y, x1, y, w)


WAVE_AMP, WAVE_HALVES = 5.5, 3


def spread_row_fill(x0, x1, y, kind, w=16):
    if kind == "wave":
        return arc_wave_outline(x0, y, x1 - x0, WAVE_AMP, WAVE_HALVES, w)
    return hpill(x0, x1, y, w)


def spread_v7(w=16):
    left, right, rows = spread_parts()
    holes = " ".join(spread_row_fill(*r, w=w) for r in rows)
    return svg(f'<path fill="#000" fill-rule="evenodd" d="{left} {right} {holes}"/>',
               "Дневник — B, Разворот")
