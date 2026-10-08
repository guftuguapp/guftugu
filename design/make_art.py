#!/usr/bin/env python3
"""In-app illustrations derived from the logo's world (see make_logo.py)."""
import math, random
import make_logo as L

def panorama(w=1440, h=560):
    # Reuse the logo scene on a wide canvas: same sky, hills, trees, stream, landing, plus a
    # fuller grove (the owner wants more trees in the banner than on the icon).
    body = L.scene(0, 0, w, h, banner=True)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">{L.DEFS}'
            f'<clipPath id="c"><rect width="{w}" height="{h}"/></clipPath><g clip-path="url(#c)">{body}</g></svg>')

def gold_footer(w=1080, h=380):
    """A filigree landscape in gold line-art: rolling ground, broad trees, a stream with a paper boat.
    Drawn faintly at the bottom of chat backgrounds."""
    g = []
    stroke = 'stroke="#B08A2C" stroke-linecap="round" stroke-linejoin="round" fill="none"'
    # rolling ground lines
    g.append(f'<path d="M0 {h*0.72} C{w*0.18} {h*0.64} {w*0.34} {h*0.7} {w*0.5} {h*0.68} C{w*0.66} {h*0.66} {w*0.82} {h*0.6} {w} {h*0.66}" {stroke} stroke-width="3"/>')
    g.append(f'<path d="M0 {h*0.82} C{w*0.2} {h*0.78} {w*0.4} {h*0.84} {w*0.6} {h*0.8} C{w*0.75} {h*0.77} {w*0.9} {h*0.8} {w} {h*0.78}" {stroke} stroke-width="2" opacity="0.7"/>')
    # broad trees as outlined crowns (scalloped ellipses)
    def crown(cx, cy, rw, rh, n=11):
        pts = []
        for i in range(n + 1):
            a = math.pi + i * math.pi / n  # upper half only
            pts.append((cx + math.cos(a) * rw, cy + math.sin(a) * rh))
        d = f'M{cx - rw:.1f} {cy:.1f}'
        for i in range(1, len(pts)):
            x0, y0 = pts[i - 1]; x1, y1 = pts[i]
            mx, my = (x0 + x1) / 2, (y0 + y1) / 2
            nx, ny = mx - cx, my - cy
            l = math.hypot(nx, ny) or 1
            bump = 0.28 * math.hypot(x1 - x0, y1 - y0)
            d += f' Q{mx + nx / l * bump:.1f} {my + ny / l * bump:.1f} {x1:.1f} {y1:.1f}'
        d += f' Q{cx:.1f} {cy + rh * 0.25:.1f} {cx - rw:.1f} {cy:.1f} Z'
        return d
    for (tx, ty, s) in [(0.15, 0.66, 1.0), (0.83, 0.6, 0.85), (0.6, 0.64, 0.5)]:
        cx, gy = w * tx, h * ty
        rw, rh = 120 * s, 70 * s
        cy = gy - 70 * s
        g.append(f'<path d="M{cx:.1f} {gy:.1f} L{cx:.1f} {cy:.1f} M{cx:.1f} {cy + 30*s:.1f} Q{cx - 40*s:.1f} {cy + 10*s:.1f} {cx - 70*s:.1f} {cy - 10*s:.1f} M{cx:.1f} {cy + 24*s:.1f} Q{cx + 40*s:.1f} {cy + 6*s:.1f} {cx + 72*s:.1f} {cy - 12*s:.1f}" {stroke} stroke-width="{3*s:.1f}"/>')
        g.append(f'<path d="{crown(cx, cy, rw, rh)}" {stroke} stroke-width="{2.6*s:.1f}"/>')
        g.append(f'<path d="{crown(cx, cy + 6*s, rw * 0.62, rh * 0.55, 7)}" {stroke} stroke-width="{1.6*s:.1f}" opacity="0.7"/>')
    # stream: two flowing banks widening to the bottom
    g.append(f'<path d="M{w*0.47} {h*0.68} C{w*0.42} {h*0.76} {w*0.52} {h*0.84} {w*0.44} {h}" {stroke} stroke-width="2.4"/>')
    g.append(f'<path d="M{w*0.5} {h*0.68} C{w*0.5} {h*0.76} {w*0.62} {h*0.84} {w*0.6} {h}" {stroke} stroke-width="2.4"/>')
    for (x, y, ww) in [(0.48, 0.78, 0.03), (0.5, 0.86, 0.045), (0.51, 0.94, 0.06)]:
        g.append(f'<path d="M{w*x - w*ww/2:.1f} {h*y:.1f} q{w*ww/4:.1f} -5 {w*ww/2:.1f} 0 q{w*ww/4:.1f} 5 {w*ww/2:.1f} 0" {stroke} stroke-width="1.6" opacity="0.8"/>')
    # paper boat
    bx, by = w * 0.505, h * 0.83
    g.append(f'<path d="M{bx-22:.1f} {by:.1f} L{bx+22:.1f} {by:.1f} L{bx+14:.1f} {by+8:.1f} L{bx-14:.1f} {by+8:.1f} Z M{bx-14:.1f} {by:.1f} L{bx:.1f} {by-20:.1f} L{bx+12:.1f} {by:.1f}" {stroke} stroke-width="2"/>')
    # birds
    for (x, y, s) in [(0.3, 0.2, 1.0), (0.35, 0.26, 0.8), (0.72, 0.16, 0.7)]:
        g.append(f'<path d="M{w*x-12*s:.1f} {h*y:.1f} q{6*s:.1f} {-7*s:.1f} {12*s:.1f} 0 q{6*s:.1f} {-7*s:.1f} {12*s:.1f} 0" {stroke} stroke-width="{2*s:.1f}"/>')
    # a few grass tufts along the ground line
    rnd = random.Random(3)
    for _ in range(18):
        x = rnd.uniform(0.02, 0.98) * w
        y = h * 0.86 + rnd.uniform(-6, 30)
        g.append(f'<path d="M{x:.1f} {y:.1f} q-3 -12 -9 -18 M{x:.1f} {y:.1f} q1 -14 -1 -22 M{x:.1f} {y:.1f} q4 -12 10 -17" {stroke} stroke-width="1.8" opacity="0.8"/>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">{"".join(g)}</svg>'

def chat_leaves(W=1080, H=2400, sheet=False):
    """Chat-room background: one full-screen botanical drawing in gold line-art (outlines and veins,
    no fills), every leaf different. The owner rejected outline trees ("they all look like
    cactuses") and a coloured leaf print ("No color. Golden-lined leaves with no fill. Need more
    variety (no repeats)"). One picture, not a repeating tile (the background doesn't scroll);
    tinted gold and kept faint at runtime so messages stay readable."""
    rnd = random.Random(7)
    S = 'stroke="#B08A2C" stroke-linecap="round" stroke-linejoin="round" fill="none"'
    SW = 2.4

    def st(d, w, op=1.0):
        return f'<path d="{d}" {S} stroke-width="{w:.2f}"' + (f' opacity="{op:.2f}"' if op < 1 else '') + '/>'

    def inset(d, cx, cy, k=0.78):
        """A finer copy of an outline drawn inside it (scaled about (cx, cy)): the ornate double line."""
        return f'<g transform="translate({cx:.1f} {cy:.1f}) scale({k}) translate({-cx:.1f} {-cy:.1f})">' + st(d, SW * 0.5 / k, 0.85) + '</g>'

    def curl_d(x, y, a, r, turns, hand):
        """Path data for a decorative curl leaving (x, y) along heading a (degrees from up) and winding
        inwards towards the `hand` side (+1 clockwise, -1 anticlockwise)."""
        ra = math.radians(a)
        hx, hy = math.sin(ra), -math.cos(ra)
        cx, cy = x - hy * hand * r, y + hx * hand * r
        phi0 = math.atan2(y - cy, x - cx)
        n = int(40 * turns) + 2
        pts = []
        for k in range(n + 1):
            f = k / n
            phi = phi0 + hand * f * turns * 2 * math.pi
            rr = r * (1 - 0.82 * f)
            pts.append((cx + math.cos(phi) * rr, cy + math.sin(phi) * rr))
        return 'M' + ' L'.join(f'{u:.1f} {v:.1f}' for u, v in pts)

    def polar(a, r, cx=0.0, cy=0.0):  # angle in degrees from straight up, clockwise
        return cx + math.sin(math.radians(a)) * r, cy - math.cos(math.radians(a)) * r

    def cubic(p0, p1, p2, p3, s):
        return tuple((1 - s) ** 3 * a + 3 * (1 - s) ** 2 * s * b + 3 * (1 - s) * s * s * c + s ** 3 * d for a, b, c, d in zip(p0, p1, p2, p3))

    def edge(p0, p1, p2, p3):
        pts = [cubic(p0, p1, p2, p3, k / 80) for k in range(81)]
        def at(y):
            for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
                if (y0 - y) * (y1 - y) <= 0 and y0 != y1:
                    return x0 + (y - y0) / (y1 - y0) * (x1 - x0)
            return 0.0
        return at

    # ---------- leaves (drawn from the base (0,0) towards -y)
    def blade_edges(L, Wd, bend):
        R = ((0, 0), (Wd * 0.95, -L * 0.1), (Wd * 0.8 + bend * 0.4, -L * 0.62), (bend, -L))
        Lf = ((bend, -L), (-Wd * 0.62 + bend * 0.4, -L * 0.68), (-Wd * 0.95, -L * 0.16), (0, 0))
        return R, Lf

    def blade_veins(L, bend, veins, R, Lf):
        out = st(f'M0 {-L * 0.02:.1f} Q{bend * 0.35:.1f} {-L * 0.5:.1f} {bend * 0.95:.1f} {-L * 0.92:.1f}', SW * 0.6)
        if veins:
            right, left = edge(*R), edge(*Lf)
            side = ''
            for i in range(veins):
                t = 0.14 + 0.64 * i / max(1, veins - 1)
                px, py = bend * t * t * 0.95, -L * t
                ye = -L * (t + 0.11)
                for xe in (right(ye) * 0.84, left(ye) * 0.84):
                    side += f' M{px:.1f} {py:.1f} Q{px + (xe - px) * 0.45:.1f} {py - L * 0.035:.1f} {xe:.1f} {ye:.1f}'
            out += st(side, SW * 0.42, 0.85)
        return out

    def blade(L, Wd, bend=0.0, veins=5, petiole=0.0, mid=True):
        R, Lf = blade_edges(L, Wd, bend)
        d = (f'M0 0 C{R[1][0]:.1f} {R[1][1]:.1f} {R[2][0]:.1f} {R[2][1]:.1f} {bend:.1f} {-L:.1f} '
             f'C{Lf[1][0]:.1f} {Lf[1][1]:.1f} {Lf[2][0]:.1f} {Lf[2][1]:.1f} 0 0 Z')
        out = st(d, SW if L > 20 else SW * 0.75)
        if mid:
            out += blade_veins(L, bend, veins, R, Lf)
        if petiole:
            out += st(f'M0 0 Q{petiole * 0.1:.1f} {petiole * 0.5:.1f} 0 {petiole:.1f}', SW * 0.8)
        return out

    def serrated(L, Wd, bend=0.0, teeth=14, veins=6, petiole=14.0):
        """Birch/elm-like leaf with a finely toothed edge."""
        R, Lf = blade_edges(L, Wd, bend)
        def toothed(c, outward):
            pts, m = [], teeth * 2
            for k in range(m + 1):
                s = k / m
                x, y = cubic(*c, s)
                bump = Wd * 0.06 if (k % 2 == 1 and 0.05 < s < 0.95) else 0.0
                pts.append((x + outward * bump, y - bump * 0.7))
            return pts
        outline = toothed(R, 1) + toothed(Lf, -1)[1:]
        d = 'M' + ' L'.join(f'{x:.1f} {y:.1f}' for x, y in outline) + ' Z'
        out = st(d, SW) + blade_veins(L, bend, veins, R, Lf)
        if petiole:
            out += st(f'M0 0 Q{petiole * 0.1:.1f} {petiole * 0.5:.1f} 0 {petiole:.1f}', SW * 0.8)
        return out

    def heart(L, Wd, petiole, inner=False):
        d = (f'M0 {-L * 0.1:.1f} C{Wd * 0.25:.1f} {L * 0.06:.1f} {Wd:.1f} {L * 0.04:.1f} {Wd:.1f} {-L * 0.33:.1f} '
             f'C{Wd:.1f} {-L * 0.62:.1f} {Wd * 0.38:.1f} {-L * 0.84:.1f} 0 {-L:.1f} '
             f'C{-Wd * 0.38:.1f} {-L * 0.84:.1f} {-Wd:.1f} {-L * 0.62:.1f} {-Wd:.1f} {-L * 0.33:.1f} '
             f'C{-Wd:.1f} {L * 0.04:.1f} {-Wd * 0.25:.1f} {L * 0.06:.1f} 0 {-L * 0.1:.1f} Z')
        v = f'M0 {-L * 0.1:.1f} Q{Wd * 0.02:.1f} {-L * 0.55:.1f} 0 {-L * 0.92:.1f}'
        for s in (1, -1):
            v += (f' M0 {-L * 0.1:.1f} Q{s * Wd * 0.45:.1f} {-L * 0.12:.1f} {s * Wd * 0.78:.1f} {-L * 0.3:.1f}'
                  f' M0 {-L * 0.22:.1f} Q{s * Wd * 0.4:.1f} {-L * 0.3:.1f} {s * Wd * 0.7:.1f} {-L * 0.55:.1f}'
                  f' M0 {-L * 0.42:.1f} Q{s * Wd * 0.25:.1f} {-L * 0.52:.1f} {s * Wd * 0.42:.1f} {-L * 0.74:.1f}')
        return (st(d, SW) + (inset(d, 0, -L * 0.45) if inner else '') + st(v, SW * 0.45, 0.85)
                + st(f'M0 {-L * 0.1:.1f} Q{petiole * 0.12:.1f} {petiole * 0.4:.1f} 0 {petiole:.1f}', SW * 0.8))

    def maple(Rr, inner=False):
        lobes = [(-104, 0.52), (-52, 0.84), (0, 1.0), (52, 0.84), (104, 0.52)]
        pts = [(-Rr * 0.07, Rr * 0.14)]
        prev = -180
        for a, f in lobes:
            ln = f * Rr
            pts.append(polar((prev + a) / 2, Rr * (0.3 if prev != -180 else 0.2)))
            for da, k in ((-17, 0.56), (-11, 0.74), (-7, 0.66), (0, 1.0), (7, 0.66), (11, 0.74), (17, 0.56)):
                pts.append(polar(a + da * (1.2 if f < 0.6 else 1.0), ln * k))
            prev = a
        pts.append(polar(150, Rr * 0.2)); pts.append((Rr * 0.07, Rr * 0.14))
        d = 'M' + ' L'.join(f'{x:.1f} {y:.1f}' for x, y in pts) + ' Z'
        v = ''.join(f' M0 0 L{polar(a, f * Rr * 0.88)[0]:.1f} {polar(a, f * Rr * 0.88)[1]:.1f}' for a, f in lobes)
        for a, f in lobes[1:4]:
            for side in (-1, 1):
                p0 = polar(a, f * Rr * 0.45); p1 = polar(a + side * 24, f * Rr * 0.66)
                v += f' M{p0[0]:.1f} {p0[1]:.1f} L{p1[0]:.1f} {p1[1]:.1f}'
        return (st(d, SW) + (inset(d, 0, -Rr * 0.35) if inner else '') + st(v, SW * 0.45, 0.85)
                + st(f'M0 {Rr * 0.12:.1f} Q{Rr * 0.08:.1f} {Rr * 0.4:.1f} {Rr * 0.02:.1f} {Rr * 0.62:.1f}', SW * 0.8))

    def oak_leaf(L, Wd):
        k = 4
        ts = [0.08 + 0.2 * i for i in range(k + 1)]
        peaks = [0.62, 0.95, 0.88, 0.62]
        out_d = 'M0 0'
        for side in (1, -1):
            seq = []
            for i in range(k):
                y0, y1 = -L * ts[i], -L * ts[i + 1]
                sw0 = Wd * (0.28 if i else 0.12) * side
                sw1 = Wd * 0.3 * side
                pk = Wd * peaks[i] * side * (1.0 if side > 0 else 0.92)
                seq.append(f' L{sw0:.1f} {y0:.1f} C{pk * 1.25:.1f} {y0 + L * 0.01:.1f} {pk * 1.25:.1f} {y1 - L * 0.01:.1f} {sw1:.1f} {y1:.1f}')
            seq.append(f' Q{Wd * 0.42 * side:.1f} {-L * 0.96:.1f} 0 {-L:.1f}')
            if side > 0:
                out_d += ''.join(seq)
            else:
                left = 'M0 0' + ''.join(seq)
        d = out_d + ' ' + left
        v = f'M0 {L * 0.0:.1f} Q{Wd * 0.04:.1f} {-L * 0.5:.1f} 0 {-L * 0.95:.1f}'
        for i in range(k):
            ym = -L * (ts[i] + ts[i + 1]) / 2
            for side in (1, -1):
                v += f' M0 {ym + L * 0.04:.1f} Q{Wd * 0.4 * side:.1f} {ym:.1f} {Wd * peaks[i] * 0.95 * side:.1f} {ym - L * 0.03:.1f}'
        return st(d, SW) + st(v, SW * 0.45, 0.85) + st(f'M0 0 L0 {L * 0.1:.1f}', SW * 0.8)

    def ivy(Rr, inner=False):
        lobes = [(-110, 0.48), (-56, 0.74), (0, 1.0), (56, 0.74), (110, 0.48)]
        d = f'M0 {Rr * 0.12:.1f}'
        prev_s = (0.0, Rr * 0.12)
        for i, (a, f) in enumerate(lobes):
            tip = polar(a, f * Rr)
            c1 = polar(a - 16, f * Rr * 0.86)
            nxt = polar((a + lobes[i + 1][0]) / 2, Rr * 0.46) if i + 1 < len(lobes) else (0.0, Rr * 0.12)
            c2 = polar(a + 16, f * Rr * 0.86)
            d += f' Q{c1[0]:.1f} {c1[1]:.1f} {tip[0]:.1f} {tip[1]:.1f} Q{c2[0]:.1f} {c2[1]:.1f} {nxt[0]:.1f} {nxt[1]:.1f}'
        d += ' Z'
        v = ''.join(f' M0 0 L{polar(a, f * Rr * 0.85)[0]:.1f} {polar(a, f * Rr * 0.85)[1]:.1f}' for a, f in lobes)
        return (st(d, SW) + (inset(d, 0, -Rr * 0.35) if inner else '') + st(v, SW * 0.45, 0.85)
                + st(f'M0 {Rr * 0.1:.1f} Q{-Rr * 0.1:.1f} {Rr * 0.4:.1f} 0 {Rr * 0.7:.1f}', SW * 0.8))

    def ginkgo(size):
        stalk = size * 0.65
        apex = (0.0, -stalk)
        pts = []
        for k in range(29):
            th = -58 + 116 * k / 28
            r = size * (1 + 0.03 * math.sin(math.radians(th) * 13))
            if abs(th) < 7:
                r *= 0.82 + 0.18 * abs(th) / 7
            pts.append(polar(th, r, *apex))
        d = f'M{apex[0]:.1f} {apex[1]:.1f} L{pts[0][0]:.1f} {pts[0][1]:.1f}'
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            d += f' Q{(x0 + x1) / 2:.1f} {(y0 + y1) / 2 - 1.5:.1f} {x1:.1f} {y1:.1f}'
        d += ' Z'
        v = ''.join(f' M{apex[0]:.1f} {apex[1]:.1f} L{polar(a, size * 0.9, *apex)[0]:.1f} {polar(a, size * 0.9, *apex)[1]:.1f}' for a in range(-50, 51, 7))
        return st(f'M0 0 Q{size * 0.06:.1f} {-stalk * 0.5:.1f} 0 {-stalk:.1f}', SW * 0.8) + st(d, SW) + st(v, SW * 0.35, 0.7)

    # ---------- stems carrying leaves
    def stem(length, curl):
        c1, c2, end = (curl * 0.1, -length * 0.35), (curl * 0.9, -length * 0.7), (curl, -length)
        def pt(t):
            return cubic((0, 0), c1, c2, end, t)
        def ang(t):
            (x0, y0), (x1, y1) = pt(max(0.0, t - 0.01)), pt(min(1.0, t + 0.01))
            return math.degrees(math.atan2(x1 - x0, -(y1 - y0)))
        return pt, ang, f'M0 0 C{c1[0]:.1f} {c1[1]:.1f} {c2[0]:.1f} {c2[1]:.1f} {end[0]:.1f} {end[1]:.1f}'

    def sprig(length, ratio, veins, opposite=False, spread=(36, 54), n=None):
        pt, ang, path = stem(length, length * rnd.uniform(-0.28, 0.28))
        out = [st(path, SW * 0.85)]
        n = n or rnd.randint(5, 8)
        leafL = length * rnd.uniform(0.28, 0.34)
        for i in range(n):
            t = 0.12 + 0.8 * i / n
            x, y = pt(t)
            sides = (-1, 1) if opposite else ((-1,) if i % 2 == 0 else (1,))
            for side in sides:
                L = leafL * (1 - 0.42 * t) * rnd.uniform(0.88, 1.1)
                out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({ang(t) + side * rnd.uniform(*spread):.1f})">'
                           f'{blade(L, L * ratio * rnd.uniform(0.9, 1.1), side * L * 0.08, veins if L > 34 else 0)}</g>')
        ex, ey = pt(1.0)
        out.append(f'<g transform="translate({ex:.1f} {ey:.1f}) rotate({ang(1.0):.1f})">{blade(leafL * 0.62, leafL * 0.62 * ratio, 0, 0)}</g>')
        return ''.join(out)

    def pinna_divided(size, side):
        """A fern pinna made of tiny leaflets along its own little axis (bipinnate)."""
        out = st(f'M0 0 Q{side * size * 0.06:.1f} {-size * 0.5:.1f} 0 {-size:.1f}', SW * 0.5)
        k = max(3, int(size / 7))
        for j in range(k):
            u = 0.12 + 0.82 * j / k
            s2 = size * 0.24 * (1 - u * 0.6)
            for sd in (-1, 1):
                out += f'<g transform="translate(0 {-size * u:.1f}) rotate({sd * 58})">{blade(s2, s2 * 0.45, 0, 0, mid=False)}</g>'
        return out

    def fern(length):
        """No two fronds alike (the owner spotted a mirrored repeat): pinna count, shape (slim,
        rounded or finely divided), angle, fullness profile, alternate or opposite, curl, and
        sometimes a fiddlehead tip."""
        curl = length * rnd.uniform(-0.42, 0.42)
        pt, ang, path = stem(length, curl)
        out = [st(path, SW * 0.8)]
        n = rnd.randint(9, 17)
        style = rnd.choice(('slim', 'round'))  # finely divided pinnae blurred into a fuzzy blob
        angle = rnd.uniform(44, 74)
        peak = rnd.uniform(0.18, 0.45)
        alternate = rnd.random() < 0.4
        ratio = {'slim': rnd.uniform(0.18, 0.26), 'round': rnd.uniform(0.36, 0.48), 'divided': 0.3}[style]
        span = 0.84 if rnd.random() < 0.35 else 0.94
        for i in range(n):
            t = 0.05 + span * i / n
            x, y = pt(t)
            prof = (0.5 + (t / peak) * 0.5) if t < peak else max(0.12, 1 - (t - peak) / (1 - peak) * 0.95)
            size = max(length * 0.21 * prof, length * 0.03)
            for side in (((-1,) if i % 2 == 0 else (1,)) if alternate else (-1, 1)):
                a = ang(t) + side * (angle + rnd.uniform(-4, 4))
                body = pinna_divided(size, side) if (style == 'divided' and size > 22) else blade(size, size * ratio * rnd.uniform(0.85, 1.15), side * size * rnd.uniform(0.04, 0.16), 0, mid=size > 16)
                out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({a:.1f})">{body}</g>')
        if span < 0.9:  # fiddlehead: the young tip curls into a spiral
            ex, ey = pt(span + 0.02)
            out.append(tendril(ex, ey, ang(span), length * 0.035, 1.6, 1 if curl > 0 else -1))
        return ''.join(out)

    def clover(sz):
        """Three notched leaflets on a slender stalk."""
        out = st(f'M0 0 Q{sz * 0.15:.1f} {-sz * 0.8:.1f} 0 {-sz * 1.5:.1f}', SW * 0.7)
        for a in (-112, 0, 112):
            out += f'<g transform="translate(0 {-sz * 1.5:.1f}) rotate({a}) translate(0 {-sz * 0.62:.1f}) scale(1 -1)">{heart(sz * 0.62, sz * 0.38, 0)}</g>'
        return out

    def rose_leaf(length):
        """Compound leaf: a rachis with two or three pairs of toothed leaflets and one at the end."""
        pt, ang, path = stem(length, length * rnd.uniform(-0.15, 0.15))
        out = [st(path, SW * 0.75)]
        pairs = rnd.randint(2, 3)
        for i in range(pairs):
            t = 0.3 + 0.55 * i / pairs
            x, y = pt(t)
            L = length * rnd.uniform(0.28, 0.34) * (1 - 0.15 * i)
            for side in (-1, 1):
                out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({ang(t) + side * rnd.uniform(52, 66):.1f})">'
                           f'{serrated(L, L * 0.42, side * L * 0.06, teeth=10, veins=4, petiole=0)}</g>')
        ex, ey = pt(1.0)
        L = length * 0.34
        out.append(f'<g transform="translate({ex:.1f} {ey:.1f}) rotate({ang(1.0):.1f})">{serrated(L, L * 0.42, 0, teeth=10, veins=4, petiole=0)}</g>')
        return ''.join(out)

    def grass(h):
        """A tuft of long, gently curving blades."""
        out = ''
        for _ in range(rnd.randint(4, 7)):
            a, L = rnd.uniform(-28, 28), h * rnd.uniform(0.55, 1.0)
            bend, w = rnd.uniform(-0.35, 0.35) * L, rnd.uniform(5, 8)
            tip = polar(a, L); tip = (tip[0] + bend * 0.3, tip[1])
            c = polar(a, L * 0.5); c = (c[0] + bend, c[1])
            out += st(f'M{-w / 2:.1f} 0 Q{c[0] - w / 2:.1f} {c[1]:.1f} {tip[0]:.1f} {tip[1]:.1f} Q{c[0] + w / 2:.1f} {c[1]:.1f} {w / 2:.1f} 0', SW * 0.6)
        return out

    def tendril(x, y, a, r0, turns, hand):
        pts = []
        steps = int(36 * turns)
        for k in range(steps + 1):
            f = k / steps
            th = math.radians(a) + hand * f * turns * 2 * math.pi
            r = r0 * (1 - f * 0.85)
            pts.append((x + math.sin(th) * r * f * 2.2, y - math.cos(th) * r * f * 2.2))
        return st('M' + ' L'.join(f'{px:.1f} {py:.1f}' for px, py in pts), SW * 0.55)

    def vine(length):
        curl = length * rnd.uniform(0.2, 0.4) * rnd.choice((-1, 1))
        pt, ang, path = stem(length, curl)
        out = [st(path, SW * 0.8)]
        for i in range(5):
            t = 0.15 + 0.17 * i
            x, y = pt(t)
            side = -1 if i % 2 == 0 else 1
            L = length * rnd.uniform(0.15, 0.19) * (1 - 0.3 * t)
            # pointed ornate leaves: round heart leaves turned at odd angles read as amoebas (owner)
            out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({ang(t) + side * rnd.uniform(48, 70):.1f})">{ornate_leaf(L * 1.3, L * 0.38, side * L * 0.1)}</g>')
            if i in (1, 3):
                out.append(tendril(x, y, ang(t) - side * 60, L * 0.32, rnd.uniform(1.4, 2.0), -side))
        ex, ey = pt(1.0)
        out.append(tendril(ex, ey, ang(1.0), length * 0.05, 2.2, 1 if curl > 0 else -1))
        return ''.join(out)

    # ---------- flowers, buds, seeds, grains
    def flower(r):
        petals = ''.join(f'<g transform="rotate({k * 72 + rnd.uniform(-6, 6):.1f})">'
                         + st(f'M0 0 C{-r * 0.5:.1f} {-r * 0.35:.1f} {-r * 0.45:.1f} {-r:.1f} 0 {-r:.1f} C{r * 0.45:.1f} {-r:.1f} {r * 0.5:.1f} {-r * 0.35:.1f} 0 0', SW * 0.8)
                         + st(f'M0 {-r * 0.2:.1f} L0 {-r * 0.7:.1f}', SW * 0.35, 0.7) + '</g>' for k in range(5))
        dots = ''.join(f'<circle cx="{polar(a, r * 0.24)[0]:.1f}" cy="{polar(a, r * 0.24)[1]:.1f}" r="1.6" {S} stroke-width="1"/>' for a in range(0, 360, 45))
        return petals + f'<circle r="{r * 0.16:.1f}" {S} stroke-width="{SW * 0.7:.2f}"/>' + dots + st(f'M0 {r * 0.2:.1f} Q{r * 0.3:.1f} {r * 1.2:.1f} {r * 0.1:.1f} {r * 2.2:.1f}', SW * 0.7)

    def bud(L):
        return (st(f'M0 0 Q{L * 0.12:.1f} {-L * 0.45:.1f} 0 {-L * 0.9:.1f}', SW * 0.8)
                + st(f'M0 {-L * 0.9:.1f} C{L * 0.2:.1f} {-L * 0.95:.1f} {L * 0.17:.1f} {-L * 1.25:.1f} 0 {-L * 1.38:.1f} C{-L * 0.17:.1f} {-L * 1.25:.1f} {-L * 0.2:.1f} {-L * 0.95:.1f} 0 {-L * 0.9:.1f} Z', SW * 0.85)
                + st(f'M0 {-L * 0.88:.1f} q{-L * 0.14:.1f} {-L * 0.06:.1f} {-L * 0.16:.1f} {-L * 0.2:.1f} M0 {-L * 0.88:.1f} q{L * 0.14:.1f} {-L * 0.06:.1f} {L * 0.16:.1f} {-L * 0.2:.1f}', SW * 0.6))

    def berries(sz):
        out = [st(f'M0 0 Q{sz * 0.2:.1f} {-sz:.1f} {sz * 0.1:.1f} {-sz * 1.6:.1f}', SW * 0.75)]
        for (dx, dy, rr) in ((0.1, -1.75, 0.2), (0.55, -2.0, 0.17), (-0.32, -2.05, 0.18), (0.25, -2.35, 0.15)):
            out.append(st(f'M{sz * 0.1:.1f} {-sz * 1.6:.1f} L{dx * sz:.1f} {dy * sz + rr * sz:.1f}', SW * 0.5))
            out.append(f'<circle cx="{dx * sz:.1f}" cy="{dy * sz:.1f}" r="{rr * sz:.1f}" {S} stroke-width="{SW * 0.75:.2f}"/>'
                       + st(f'M{dx * sz - rr * sz * 0.5:.1f} {dy * sz - rr * sz * 0.1:.1f} q{rr * sz * 0.2:.1f} {-rr * sz * 0.45:.1f} {rr * sz * 0.6:.1f} {-rr * sz * 0.5:.1f}', SW * 0.4, 0.8))
        out.append(f'<g transform="translate({sz * 0.15:.1f} {-sz * 0.7:.1f}) rotate(58)">{blade(sz * 0.7, sz * 0.22, 0, 0)}</g>')
        return ''.join(out)

    def dandelion(length):
        hx, hy = length * 0.08, -length
        out = [st(f'M0 0 Q{length * 0.12:.1f} {-length * 0.5:.1f} {hx:.1f} {hy:.1f}', SW * 0.75)]
        rays = ''
        for k in range(26):
            a = k * 360 / 26 + rnd.uniform(-4, 4)
            r = length * rnd.uniform(0.2, 0.24)
            x1, y1 = polar(a, r, hx, hy)
            rays += f' M{hx:.1f} {hy:.1f} L{x1:.1f} {y1:.1f}'
            for da in (-14, 0, 14):
                x2, y2 = polar(a + da, r * 0.12, x1, y1)
                rays += f' M{x1:.1f} {y1:.1f} L{x2:.1f} {y2:.1f}'
        return ''.join(out) + st(rays, SW * 0.35, 0.85) + f'<circle cx="{hx:.1f}" cy="{hy:.1f}" r="{length * 0.025:.1f}" {S} stroke-width="{SW * 0.6:.2f}"/>'

    def seed(sz):
        d = f'M0 0 L0 {-sz:.1f}'
        for da in (-50, -25, 0, 25, 50):
            x, y = polar(da, sz * 0.32, 0, -sz)
            d += f' M0 {-sz:.1f} L{x:.1f} {y:.1f}'
        return st(d, SW * 0.4, 0.9) + st(f'M0 0 q{sz * 0.04:.1f} {sz * 0.08:.1f} 0 {sz * 0.14:.1f}', SW * 0.6)

    def wheat(length):
        pt, ang, path = stem(length, length * rnd.uniform(-0.12, 0.12))
        out = [st(path, SW * 0.75)]
        for i in range(9):
            t = 0.5 + 0.055 * i
            x, y = pt(t)
            side = -1 if i % 2 == 0 else 1
            g = length * 0.07
            body = (st(f'M0 0 C{g * 0.45:.1f} {-g * 0.2:.1f} {g * 0.4:.1f} {-g:.1f} 0 {-g * 1.25:.1f} C{-g * 0.4:.1f} {-g:.1f} {-g * 0.45:.1f} {-g * 0.2:.1f} 0 0 Z', SW * 0.7)
                    + st(f'M0 {-g * 1.25:.1f} L{g * 0.15:.1f} {-g * 2.6:.1f}', SW * 0.35, 0.8))
            out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({ang(t) + side * 28:.1f})">{body}</g>')
        return ''.join(out)

    def lavender(length):
        pt, ang, path = stem(length, length * rnd.uniform(-0.15, 0.15))
        out = [st(path, SW * 0.7)]
        for i in range(12):
            t = 0.58 + 0.035 * i
            x, y = pt(t)
            side = -1 if i % 2 == 0 else 1
            b = length * 0.045 * (1 - 0.3 * (i / 12))
            out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({ang(t) + side * 34:.1f})">'
                       + st(f'M0 0 C{b * 0.6:.1f} {-b * 0.3:.1f} {b * 0.5:.1f} {-b * 1.3:.1f} 0 {-b * 1.6:.1f} C{-b * 0.5:.1f} {-b * 1.3:.1f} {-b * 0.6:.1f} {-b * 0.3:.1f} 0 0 Z', SW * 0.6) + '</g>')
        for side in (-1, 1):
            x, y = pt(0.12)
            out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({ang(0.12) + side * 22:.1f})">{blade(length * 0.3, length * 0.035, 0, 0)}</g>')
        return ''.join(out)

    # ---------- scatter over the whole screen: large motifs first, small ones in the gaps
    # ---------- ornate pieces (Mughal/Persian gold-ornament spirit: double lines, beads, curls)
    def ornate_leaf(L, Wd=None, bend=None):
        """Every call draws a different leaf (the owner spotted one leaf repeated ~20 times): silhouette
        (aspect, widest point, tip, base, asymmetry, curve), margin, venation and ornament are all drawn
        at random, so no two leaves share a shape."""
        R_ = rnd
        # ranges kept to elegant, natural proportions: wide wavy or lopsided shapes read as amoebas
        aspect = R_.uniform(0.2, 0.3) if R_.random() < 0.4 else R_.uniform(0.3, 0.42)
        Wd = L * aspect if Wd is None else min(Wd, L * 0.42) * R_.uniform(0.9, 1.1)
        p = R_.uniform(0.34, 0.5)
        tip = R_.choices(('acute', 'acuminate', 'rounded'), (40, 45, 15))[0]
        if tip == 'rounded' and Wd > L * 0.33:
            tip = 'acute'
        base = R_.choices(('cuneate', 'rounded', 'cordate', 'oblique'), (40, 35, 15, 10))[0]
        margin = R_.choices(('entire', 'serrate', 'crenate', 'double', 'undulate'), (45, 25, 12, 10, 8))[0]
        bend = R_.uniform(-0.12, 0.12) * L if bend is None else max(-0.12 * L, min(0.12 * L, bend * R_.uniform(0.5, 1.3)))
        scurve = R_.uniform(-0.025, 0.025) * L
        asym = R_.uniform(0.94, 1.06)
        teeth = R_.randint(9, 24)
        waves = R_.randint(3, 6)
        tip_len = 1.06 if tip == 'acuminate' else 1.0
        N = 120

        def mid(t):
            return (bend * math.sin(min(t, 1.0) * math.pi / 2) ** 2 + scurve * math.sin(2 * math.pi * t), -L * t)

        def normal(t):
            (x0, y0), (x1, y1) = mid(t - 0.004), mid(t + 0.004)
            dx, dy = x1 - x0, y1 - y0
            n = math.hypot(dx, dy) or 1
            return -dy / n, dx / n  # points to the leaf's right

        def width(t):
            if t <= p:
                u = t / p
                w = math.sin(u * math.pi / 2) ** (1.5 if base == 'cuneate' else 0.75)
                if base == 'cordate':
                    w = max(w, 0.62 * math.sin(min(1.0, u * 2.2) * math.pi / 2) ** 0.4)
            else:
                u = min(1.0, (t - p) / (tip_len - p))
                if tip == 'rounded':
                    w = math.sqrt(max(0.0, 1 - u ** 2.3))
                elif tip == 'emarginate':
                    w = math.sqrt(max(0.0, 1 - u ** 2.6)) * (1 - 0.15 * u ** 6)
                elif tip == 'acuminate':  # rounded shoulders, then a slender drip tip
                    w = math.cos(u * math.pi / 2) ** 1.25 * (1 - 0.3 * u ** 3)
                else:  # acute: convex shoulders to a clean point (a straight taper looks like a kite)
                    w = max(0.0, 1 - u ** 1.6) ** 1.15
            if 0.07 < t < 0.95:
                fade = min(1.0, (t - 0.07) / 0.1, (0.95 - t) / 0.1)
                if margin == 'serrate':
                    ph = (t * teeth) % 1.0
                    w *= 1 + 0.07 * fade * (ph / 0.8 if ph < 0.8 else (1 - ph) / 0.2)
                elif margin == 'double':
                    ph, ph2 = (t * teeth * 0.5) % 1.0, (t * teeth * 1.5) % 1.0
                    w *= 1 + fade * (0.08 * (ph / 0.8 if ph < 0.8 else (1 - ph) / 0.2) + 0.03 * (ph2 / 0.8 if ph2 < 0.8 else (1 - ph2) / 0.2))
                elif margin == 'crenate':
                    w *= 1 + 0.06 * fade * abs(math.sin(math.pi * teeth * 0.7 * t))
                elif margin == 'undulate':
                    w *= 1 + 0.03 * fade * math.sin(2 * math.pi * waves * t)
            return Wd * w

        t0r, t0l = (0.0, R_.uniform(0.03, 0.07)) if base == 'oblique' else (0.0, 0.0)
        if R_.random() < 0.5:
            t0r, t0l = t0l, t0r

        def side_pts(sign, t0, k=1.0, t1=None):
            t1 = tip_len if t1 is None else t1
            pts = []
            for i in range(N + 1):
                t = t0 + (t1 - t0) * i / N
                (mx, my), (nx, ny) = mid(t), normal(t)
                w = width(t) * k * (asym if sign > 0 else 1 / asym)
                pts.append((mx + sign * nx * w, my + sign * ny * w))
            return pts

        right, left = side_pts(1, t0r), side_pts(-1, t0l)
        tipx, tipy = mid(tip_len)
        if tip == 'emarginate':
            tipx, tipy = mid(tip_len - 0.035)
        outline = [mid(t0r)] + right + [(tipx, tipy)] + left[::-1] + [mid(t0l), (0.0, 0.0)]
        if base == 'cordate':  # rounded lobes either side of the stalk, curving into the sides
            def lobe(sign, join):
                jx, jy = join
                return [(sign * (0.18 * (1 - (1 - f) ** 2) * Wd + f * f * (jx - sign * 0.18 * Wd)), L * 0.05 * math.sin(f * math.pi) * (1 - f) + f * f * jy) for f in (i / 8 for i in range(1, 8))]
            r0, l0 = right[min(6, len(right) - 1)], left[min(6, len(left) - 1)]
            outline = [(0.0, 0.0)] + lobe(1, r0) + right[6:] + [(tipx, tipy)] + left[6:][::-1] + lobe(-1, l0)[::-1] + [(0.0, 0.0)]
        d = 'M' + ' L'.join(f'{x:.1f} {y:.1f}' for x, y in outline) + ' Z'
        out = [st(d, SW)]

        # midrib (sometimes doubled)
        mid_pts = [mid(t) for t in [i / 40 * (tip_len - 0.05) for i in range(41)]]
        mid_d = 'M' + ' L'.join(f'{x:.1f} {y:.1f}' for x, y in mid_pts)
        out.append(st(mid_d, SW * 0.55))
        if R_.random() < 0.3:
            out.append(f'<g transform="translate({R_.choice((-1.8, 1.8))} 0)">{st(mid_d, SW * 0.3, 0.7)}</g>')

        # venation
        pairs = max(3, min(10, int(L / R_.uniform(14, 26))))
        style = R_.choice(('curved', 'curved', 'straight', 'looped', 'curled'))
        gap = R_.uniform(0.06, 0.15)
        reach = R_.uniform(0.78, 0.92)
        alternate = R_.random() < 0.4
        veins, ends = '', {1: [], -1: []}
        for i in range(pairs):
            ts = 0.1 + 0.72 * i / pairs
            for sign in (1, -1):
                t_s = ts + (0.36 / pairs if (alternate and sign < 0) else 0)
                t_e = min(t_s + gap, 0.97)
                (mx, my), (ex_n, ey_n) = mid(t_s), normal(t_e)
                (ex_m, ey_m) = mid(t_e)
                w = width(t_e) * reach * (asym if sign > 0 else 1 / asym)
                ex, ey = ex_m + sign * ex_n * w, ey_m + sign * ey_n * w
                if style == 'straight':
                    veins += f' M{mx:.1f} {my:.1f} L{ex:.1f} {ey:.1f}'
                else:
                    cx_, cy_ = mx + (ex - mx) * 0.5 + sign * ex_n * w * 0.08, my + (ey - my) * 0.25
                    veins += f' M{mx:.1f} {my:.1f} Q{cx_:.1f} {cy_:.1f} {ex:.1f} {ey:.1f}'
                if style == 'curled':
                    a = math.degrees(math.atan2(ex - mx, -(ey - my)))
                    veins += ' ' + curl_d(ex, ey, a, max(1.6, L * R_.uniform(0.014, 0.024)), R_.uniform(0.9, 1.4), -sign)
                ends[sign].append((ex, ey))
        if style == 'looped':
            for sign in (1, -1):
                pts = ends[sign]
                for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
                    veins += f' M{x0:.1f} {y0:.1f} Q{(x0 + x1) / 2 + sign * 3:.1f} {(y0 + y1) / 2:.1f} {x1:.1f} {y1:.1f}'
        out.append(st(veins, SW * R_.uniform(0.34, 0.46), 0.9))

        # ornament: a random mix, so leaves differ in dress as well as shape
        if R_.random() < 0.5:
            k = R_.uniform(0.7, 0.86)
            inner = side_pts(1, max(t0r, 0.06), k, tip_len - 0.07) + side_pts(-1, max(t0l, 0.06), k, tip_len - 0.07)[::-1]
            out.append(st('M' + ' L'.join(f'{x:.1f} {y:.1f}' for x, y in inner) + ' Z', SW * 0.45, 0.8))
        if R_.random() < 0.3:
            for i in range(R_.randint(3, 6)):
                bx, by = mid(0.2 + 0.12 * i)
                out.append(f'<circle cx="{bx:.1f}" cy="{by:.1f}" r="{R_.uniform(1.2, 2.2):.1f}" {S} stroke-width="{SW * 0.4:.2f}"/>')
        if R_.random() < 0.3:  # engraved shading on one half
            sign = R_.choice((1, -1))
            hatch = ''
            for i in range(R_.randint(8, 16)):
                t = R_.uniform(0.15, 0.85)
                (mx, my), (nx, ny) = mid(t), normal(t)
                w = width(t)
                a0, a1 = R_.uniform(0.35, 0.5), R_.uniform(0.65, 0.8)
                hatch += f' M{mx + sign * nx * w * a0:.1f} {my + sign * ny * w * a0:.1f} L{mx + sign * nx * w * a1:.1f} {my + sign * ny * w * a1 - L * 0.02:.1f}'
            out.append(st(hatch, SW * 0.3, 0.7))
        if R_.random() < 0.25:  # stippled dots
            dots = ''
            for _ in range(R_.randint(6, 16)):
                t = R_.uniform(0.15, 0.85)
                sign = R_.choice((1, -1))
                (mx, my), (nx, ny) = mid(t), normal(t)
                f_ = R_.uniform(0.3, 0.7) * width(t)
                dots += f'<circle cx="{mx + sign * nx * f_:.1f}" cy="{my + sign * ny * f_:.1f}" r="1" {S} stroke-width="0.9"/>'
            out.append(dots)
        if R_.random() < 0.18:  # tip curl
            (ax, ay), (bx2, by2) = mid(tip_len - 0.02), mid(tip_len)
            out.append(st(curl_d(tipx, tipy, math.degrees(math.atan2(bx2 - ax, -(by2 - ay))), L * 0.03, 1.2, R_.choice((-1, 1))), SW * 0.5))
        if R_.random() < 0.12:  # a little rosette at the heart of the leaf
            cx_, cy_ = mid(0.45)
            out.append(''.join(f'<g transform="translate({cx_:.1f} {cy_:.1f}) rotate({k_ * 90 + 45})">{blade(Wd * 0.22, Wd * 0.09, 0, 0, mid=False)}</g>' for k_ in range(4)))
        stalk = L * R_.uniform(0.04, 0.2)
        sb = R_.uniform(-0.4, 0.4) * stalk
        out.append(st(f'M0 0 Q{sb:.1f} {stalk * 0.5:.1f} {sb * 0.6:.1f} {stalk:.1f}', SW * 0.75))
        if R_.random() < 0.35:
            out.append(st(curl_d(sb * 0.6, stalk, 180 + R_.uniform(-30, 30), L * R_.uniform(0.03, 0.06), R_.uniform(1.0, 1.6), R_.choice((-1, 1))), SW * 0.6))
        return ''.join(out)

    def acanthus(L):
        """Classical acanthus: a curving, double-lined spine with pointed lobes on both sides whose
        tips curl outwards, the top folding over into a scroll."""
        curl = L * rnd.uniform(0.15, 0.3) * rnd.choice((-1, 1))
        pt, ang, path = stem(L, curl)
        out = [st(path, SW * 0.9), f'<g transform="translate(3.5 0)">{st(path, SW * 0.4, 0.7)}</g>']
        k = rnd.randint(3, 4)
        for i in range(k):
            t = 0.14 + 0.7 * i / k
            x, y = pt(t)
            size = L * 0.42 * (1 - 0.45 * t)
            for side in (-1, 1):
                a = ang(t) + side * rnd.uniform(46, 60)
                tip = curl_d(side * size * 0.22, -size, side * 28, size * 0.09, 1.25, side)
                out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({a:.1f})">{ornate_leaf(size, size * 0.34, side * size * 0.15)}{st(tip, SW * 0.6)}</g>')
        ex, ey = pt(1.0)
        out.append(st(curl_d(ex, ey, ang(1.0), L * 0.07, 1.5, 1 if curl > 0 else -1), SW * 0.85))
        return ''.join(out)

    def paisley(Rr):
        """Boteh (paisley): a teardrop with a curling tip, a finer inner contour, a ring of beads,
        a little rosette in the bowl and a fringe of tiny petals."""
        hand = rnd.choice((-1, 1))
        segs = [((1, 0), (1, -0.6), (0.55, -1.0), (0.45, -1.6)), ((0.45, -1.6), (0.4, -2.0), (0.55, -2.3), (0.9, -2.35)),
                ((0.9, -2.35), (0.4, -2.75), (-0.6, -2.4), (-0.95, -1.5)), ((-0.95, -1.5), (-1.15, -0.9), (-1.1, -0.3), (-1, 0))]
        def P(x, y):
            return f'{x * Rr * hand:.1f} {y * Rr:.1f}'
        d = f'M{P(-1, 0)} A{Rr:.1f} {Rr:.1f} 0 0 {0 if hand > 0 else 1} {P(1, 0)}'
        for (_, c1, c2, e) in segs:
            d += f' C{P(*c1)} {P(*c2)} {P(*e)}'
        d += ' Z'
        out = st(d, SW) + inset(d, 0, -Rr * 0.8, 0.72) + inset(d, 0, -Rr * 0.8, 0.5)
        out += st(curl_d(0.9 * Rr * hand, -2.35 * Rr, 120 * hand, Rr * 0.2, 1.25, hand), SW * 0.8)
        pts = [(math.cos(math.radians(th)), math.sin(math.radians(th))) for th in range(180, -1, -15)]
        for seg in segs:
            pts += [cubic(*seg, s) for s in (0.25, 0.5, 0.75, 1.0)]
        fringe = ''
        for (x, y) in pts:
            a = math.degrees(math.atan2(x * hand, -(y + 0.9)))
            fringe += f'<g transform="translate({x * Rr * hand * 1.04:.1f} {y * Rr * 1.04 - Rr * 0.04:.1f}) rotate({a:.1f})">{blade(Rr * 0.16, Rr * 0.05, 0, 0, mid=False)}</g>'
        beads = ''.join(f'<circle cx="{x * Rr * hand * 0.86:.1f}" cy="{(y + 0.8) * Rr * 0.86 - Rr * 0.8:.1f}" r="{Rr * 0.035:.1f}" {S} stroke-width="{SW * 0.4:.2f}"/>' for (x, y) in pts[::2])
        rosette = ''.join(f'<g transform="translate(0 {-Rr * 0.15:.1f}) rotate({k * 72})">{blade(Rr * 0.3, Rr * 0.12, 0, 0, mid=False)}</g>' for k in range(5))
        return out + fringe + beads + rosette + f'<circle cx="0" cy="{-Rr * 0.15:.1f}" r="{Rr * 0.06:.1f}" {S} stroke-width="{SW * 0.5:.2f}"/>'

    def rose_bloom(r):
        """An open rose in line-art: a spiralling heart, cupped inner petals, wavy outer petals,
        on a short stem with two ornate leaves."""
        out = [st(f'M0 {r * 0.6:.1f} Q{r * 0.15:.1f} {r * 1.4:.1f} 0 {r * 2.2:.1f}', SW * 0.85)]
        for side in (-1, 1):
            out.append(f'<g transform="translate({r * 0.04 * side:.1f} {r * 1.25:.1f}) rotate({side * 62})">{ornate_leaf(r * 0.95, r * 0.34, side * r * 0.05)}</g>')
        petals = ''
        for k in range(6):  # outer, wavy-topped
            a = k * 60 + rnd.uniform(-6, 6)
            p0, p1 = polar(a - 34, r * 0.6), polar(a + 34, r * 0.6)
            c1, m, c2 = polar(a - 30, r * 1.06), polar(a, r * 0.94), polar(a + 30, r * 1.06)
            petals += f' M{p0[0]:.1f} {p0[1]:.1f} Q{c1[0]:.1f} {c1[1]:.1f} {m[0]:.1f} {m[1]:.1f} Q{c2[0]:.1f} {c2[1]:.1f} {p1[0]:.1f} {p1[1]:.1f}'
        for k in range(5):  # middle, cupped
            a = k * 72 + 30
            p0, c, p1 = polar(a - 40, r * 0.36), polar(a, r * 0.8), polar(a + 40, r * 0.36)
            petals += f' M{p0[0]:.1f} {p0[1]:.1f} Q{c[0]:.1f} {c[1]:.1f} {p1[0]:.1f} {p1[1]:.1f}'
        for k in range(3):  # inner arcs
            a = k * 120 + 10
            p0, c, p1 = polar(a, r * 0.18), polar(a + 60, r * 0.42), polar(a + 115, r * 0.24)
            petals += f' M{p0[0]:.1f} {p0[1]:.1f} Q{c[0]:.1f} {c[1]:.1f} {p1[0]:.1f} {p1[1]:.1f}'
        out.append(st(petals, SW * 0.85))
        out.append(st(curl_d(r * 0.16, 0, 0, r * 0.14, 1.6, 1), SW * 0.7))
        return ''.join(out)

    def lotus(r):
        """A lotus: five pointed petals fanned upwards with back petals behind, a cupped base and two sepals."""
        out = []
        for a, f in ((-78, 0.62), (78, 0.62), (-52, 0.8), (52, 0.8)):  # back petals
            out.append(f'<g transform="rotate({a})">{blade(r * f, r * f * 0.32, 0, 0)}</g>')
        for a, f in ((-34, 0.9), (34, 0.9), (0, 1.0)):  # front petals, with an inner line
            out.append(f'<g transform="rotate({a})">{blade(r * f, r * f * 0.36, 0, 0)}'
                       f'{inset(blade_path(r * f, r * f * 0.36), 0, -r * f * 0.5, 0.62)}</g>')
        out.append(st(f'M{-r * 0.55:.1f} {r * 0.02:.1f} Q0 {r * 0.32:.1f} {r * 0.55:.1f} {r * 0.02:.1f}', SW * 0.85))
        out.append(st(f'M{-r * 0.5:.1f} {r * 0.08:.1f} q{-r * 0.2:.1f} {r * 0.05:.1f} {-r * 0.32:.1f} {-r * 0.08:.1f} M{r * 0.5:.1f} {r * 0.08:.1f} q{r * 0.2:.1f} {r * 0.05:.1f} {r * 0.32:.1f} {-r * 0.08:.1f}', SW * 0.6))
        out.append(st(f'M0 {r * 0.18:.1f} Q{r * 0.06:.1f} {r * 0.7:.1f} 0 {r * 1.2:.1f}', SW * 0.8))
        return ''.join(out)

    def blade_path(L, Wd, bend=0.0):
        R, Lf = blade_edges(L, Wd, bend)
        return (f'M0 0 C{R[1][0]:.1f} {R[1][1]:.1f} {R[2][0]:.1f} {R[2][1]:.1f} {bend:.1f} {-L:.1f} '
                f'C{Lf[1][0]:.1f} {Lf[1][1]:.1f} {Lf[2][0]:.1f} {Lf[2][1]:.1f} 0 0 Z')

    def tulip(r):
        """Ottoman-style tulip: an almond centre petal between two outer petals whose tips flare
        outwards and curl, on a stem with two long curving leaves."""
        out = [st(f'M0 0 Q{r * 0.1:.1f} {r * 0.9:.1f} 0 {r * 1.9:.1f}', SW * 0.85)]
        out.append(f'<g>{blade(r * 1.05, r * 0.3, 0, 0)}{inset(blade_path(r * 1.05, r * 0.3), 0, -r * 0.5, 0.6)}</g>')
        for side in (-1, 1):
            d = (f'M{side * r * 0.05:.1f} 0 C{side * r * 0.62:.1f} {-r * 0.05:.1f} {side * r * 0.6:.1f} {-r * 0.6:.1f} {side * r * 0.52:.1f} {-r * 0.86:.1f} '
                 f'Q{side * r * 0.36:.1f} {-r * 0.5:.1f} {side * r * 0.08:.1f} {-r * 0.32:.1f}')
            out.append(st(d, SW * 0.9))
            out.append(st(curl_d(side * r * 0.52, -r * 0.86, side * 20, r * 0.07, 1.1, side), SW * 0.6))
            out.append(f'<g transform="translate(0 {r * 1.25:.1f}) rotate({side * 38})">{blade(r * 1.1, r * 0.16, side * r * 0.25, 0)}</g>')
        return ''.join(out)

    def double_daisy(r):
        """Two rings of slim petals, offset, around a beaded centre."""
        out = ''
        for k in range(8):
            out += f'<g transform="rotate({k * 45})">{blade(r, r * 0.34, 0, 0, mid=False)}{inset(blade_path(r, r * 0.34), 0, -r * 0.5, 0.55)}</g>'
        for k in range(8):
            out += f'<g transform="rotate({k * 45 + 22.5})">{blade(r * 0.6, r * 0.3, 0, 0, mid=False)}</g>'
        out += f'<circle r="{r * 0.2:.1f}" {S} stroke-width="{SW * 0.8:.2f}"/>'
        out += ''.join(f'<circle cx="{polar(a, r * 0.11)[0]:.1f}" cy="{polar(a, r * 0.11)[1]:.1f}" r="1.3" {S} stroke-width="0.9"/>' for a in range(0, 360, 60))
        return out + st(f'M0 {r * 0.2:.1f} Q{r * 0.2:.1f} {r * 1.2:.1f} 0 {r * 2.2:.1f}', SW * 0.75)

    def scroll(Lc):
        """A small flourish: an S-curve ending in curls at both ends, with a leaflet at its waist."""
        hand = rnd.choice((-1, 1))
        d = f'M0 0 C{Lc * 0.35 * hand:.1f} {-Lc * 0.25:.1f} {-Lc * 0.35 * hand:.1f} {-Lc * 0.75:.1f} 0 {-Lc:.1f}'
        out = st(d, SW * 0.7)
        out += st(curl_d(0, -Lc, -40 * hand, Lc * 0.12, 1.3, -hand), SW * 0.6)
        out += st(curl_d(0, 0, 180 - 40 * hand, Lc * 0.1, 1.3, hand), SW * 0.6)
        out += f'<g transform="translate(0 {-Lc * 0.5:.1f}) rotate({60 * hand})">{blade(Lc * 0.32, Lc * 0.11, 0, 0)}</g>'
        return out

    # ---------- petalled flowers: petals listed front-to-back; each petal hides the lines of the
    # petals behind it (an SVG mask per petal), so overlapping petals read like a real drawing.
    mask_n = [0]

    def layered(front_to_back):
        out = []
        for i, (d, detail, w) in enumerate(front_to_back):
            body = st(d, w) + detail
            if i:
                mask_n[0] += 1
                mid_ = f'm{mask_n[0]}'
                holes = ''.join(f'<path d="{fd}" fill="#000"/>' for fd, _, _ in front_to_back[:i])
                body = (f'<mask id="{mid_}" maskUnits="userSpaceOnUse" x="-3000" y="-3000" width="6000" height="6000">'
                        f'<rect x="-3000" y="-3000" width="6000" height="6000" fill="#fff"/>{holes}</mask><g mask="url(#{mid_})">{body}</g>')
            out.append(body)
        return ''.join(out)

    def rot(d_or_svg, a, is_path=True):
        return d_or_svg  # (petals are rotated by wrapping, see place_petal)

    def petal_round(L, Wd, wave=0):
        """Broad petal from its base (0,0) outwards, with a rounded or ruffled (scalloped) top."""
        if not wave:
            return (f'M0 0 C{Wd * 0.9:.1f} {-L * 0.2:.1f} {Wd * 1.1:.1f} {-L * 0.86:.1f} 0 {-L:.1f} '
                    f'C{-Wd * 1.1:.1f} {-L * 0.86:.1f} {-Wd * 0.9:.1f} {-L * 0.2:.1f} 0 0 Z')
        cx, cy = 0.0, -L * 0.6
        rr = math.hypot(Wd * 0.85, L * 0.22)
        a0 = math.degrees(math.atan2(Wd * 0.85, L * 0.22))
        d = f'M0 0 C{Wd * 0.9:.1f} {-L * 0.2:.1f} {Wd * 1.05:.1f} {-L * 0.6:.1f} {Wd * 0.85:.1f} {-L * 0.82:.1f}'
        for k in range(wave):
            a1, a2 = a0 - 2 * a0 * k / wave, a0 - 2 * a0 * (k + 1) / wave
            m = polar((a1 + a2) / 2, rr * 1.16, cx, cy)
            e = polar(a2, rr, cx, cy)
            d += f' Q{m[0]:.1f} {m[1]:.1f} {e[0]:.1f} {e[1]:.1f}'
        d += f' C{-Wd * 1.05:.1f} {-L * 0.6:.1f} {-Wd * 0.9:.1f} {-L * 0.2:.1f} 0 0 Z'
        return d

    def petal_notched(L, Wd):
        return (f'M0 0 C{Wd * 0.8:.1f} {-L * 0.25:.1f} {Wd * 1.12:.1f} {-L * 0.9:.1f} {Wd * 0.26:.1f} {-L:.1f} '
                f'L0 {-L * 0.87:.1f} L{-Wd * 0.26:.1f} {-L:.1f} '
                f'C{-Wd * 1.12:.1f} {-L * 0.9:.1f} {-Wd * 0.8:.1f} {-L * 0.25:.1f} 0 0 Z')

    def petal_lines(L, Wd, n=3, reach=0.78):
        d = ''
        for k in range(n):
            x = (k - (n - 1) / 2) * Wd * 0.38
            d += f' M{x * 0.2:.1f} {-L * 0.14:.1f} Q{x * 0.6:.1f} {-L * 0.45:.1f} {x:.1f} {-L * reach:.1f}'
        return st(d, SW * 0.4, 0.75)

    def turned(d, a):
        """Rotate a petal outline about the flower centre (path data stays local; wrap in a group)."""
        return a, d

    def ring(n, L, Wd, offset, shape, w, lines=3, jitter=6):
        petals = []
        for k in range(n):
            a = offset + k * 360 / n + rnd.uniform(-jitter, jitter)
            petals.append((a, shape(L, Wd), petal_lines(L, Wd, lines), w))
        return petals

    def flower_from_rings(rings, centre_svg):
        """rings: front ring first. Each petal becomes a rotated copy; masks need the rotated outline,
        so petals are flattened into absolute coordinates by baking the rotation into a group."""
        flat = []
        for petals in rings:
            for (a, d, detail, w) in petals:
                flat.append((f'<g transform="rotate({a:.1f})">', d, detail, w))
        out = []
        for i, (open_g, d, detail, w) in enumerate(flat):
            body = open_g + st(d, w) + detail + '</g>'
            if i:
                mask_n[0] += 1
                mid_ = f'm{mask_n[0]}'
                holes = ''.join(f'{og}<path d="{fd}" fill="#000"/></g>' for og, fd, _, _ in flat[:i])
                body = (f'<mask id="{mid_}" maskUnits="userSpaceOnUse" x="-3000" y="-3000" width="6000" height="6000">'
                        f'<rect x="-3000" y="-3000" width="6000" height="6000" fill="#fff"/>{holes}</mask><g mask="url(#{mid_})">{body}</g>')
            out.append(body)
        return centre_svg + ''.join(out)

    def rose_top(r):
        """A rose from above: 2-4 rings of rounded petals (counts, widths and ruffles drawn at random)
        around a spiralling heart: no two roses alike."""
        nring = rnd.randint(2, 4)
        rings = []
        for i in range(nring):
            f = 0.42 + 0.58 * (i + 1) / nring
            n = rnd.randint(4, 7)
            wave = 0 if i == 0 else rnd.randint(0, 3 + i)
            rings.append(ring(n, r * f, r * f * rnd.uniform(0.55, 0.75), rnd.uniform(0, 360 / n),
                              (lambda w_: (lambda L, W: petal_round(L, W, w_)))(wave), SW * (0.8 + 0.2 * f), rnd.randint(1, 3)))
        centre = st(curl_d(r * 0.12, 0, 0, r * rnd.uniform(0.1, 0.15), rnd.uniform(1.4, 2.2), rnd.choice((-1, 1))), SW * 0.8)
        return flower_from_rings(rings, centre)

    def peony(r):
        """A full peony: 3-5 rings of ruffled petals and a beaded centre, every one different."""
        nring = rnd.randint(3, 5)
        rings = []
        for i in range(nring):
            f = 0.3 + 0.7 * (i + 1) / nring
            n = rnd.randint(4, 8)
            rings.append(ring(n, r * f, r * f * rnd.uniform(0.5, 0.68), rnd.uniform(0, 360 / n),
                              (lambda w_: (lambda L, W: petal_round(L, W, w_)))(rnd.randint(1, 5)), SW * (0.75 + 0.25 * f), rnd.randint(1, 3)))
        k = rnd.randint(5, 9)
        centre = ''.join(f'<circle cx="{polar(a, r * 0.08)[0]:.1f}" cy="{polar(a, r * 0.08)[1]:.1f}" r="{max(1.4, r * 0.028):.1f}" {S} stroke-width="{SW * 0.5:.2f}"/>' for a in [j * 360 / k for j in range(k)])
        return flower_from_rings(rings, centre)

    def dahlia(r):
        """A dahlia: 3-4 rings of pointed petals, each ring turned and overlapping the next."""
        rings = []
        nring = rnd.randint(3, 4)
        for i in range(nring):
            f = 0.35 + 0.65 * (i + 1) / nring
            n = rnd.randint(8, 13)
            wr = rnd.uniform(0.2, 0.28)
            rings.append(ring(n, r * f, r * f * wr, rnd.uniform(0, 360 / n), blade_path, SW * (0.75 + 0.25 * f), rnd.randint(1, 2), 4))
        return flower_from_rings(rings, f'<circle r="{r * 0.1:.1f}" {S} stroke-width="{SW * 0.7:.2f}"/>')

    def anemone(r):
        """An anemone: 5-7 broad petals with fine lines around a large beaded centre."""
        n = rnd.randint(5, 7)
        petals = ring(n, r, r * rnd.uniform(0.62, 0.8), rnd.uniform(0, 360 / n), petal_round, SW, rnd.randint(3, 5), 8)
        cr = r * rnd.uniform(0.2, 0.26)
        centre = f'<circle r="{cr:.1f}" {S} stroke-width="{SW * 0.9:.2f}"/><circle r="{cr * 0.55:.1f}" {S} stroke-width="{SW * 0.5:.2f}"/>'
        k = rnd.randint(10, 16)
        centre += ''.join(f'<circle cx="{polar(a, cr * 1.35)[0]:.1f}" cy="{polar(a, cr * 1.35)[1]:.1f}" r="1.6" {S} stroke-width="0.9"/>' for a in [j * 360 / k for j in range(k)])
        return flower_from_rings([petals], '') + centre

    def petal_toothed(L, Wd, teeth=3):
        """Cosmos-like petal widening towards a top edge cut into small teeth."""
        d = f'M0 0 C{Wd * 0.5:.1f} {-L * 0.3:.1f} {Wd:.1f} {-L * 0.7:.1f} {Wd * 0.9:.1f} {-L * 0.92:.1f}'
        for k in range(teeth * 2):
            x = Wd * 0.9 - Wd * 1.8 * (k + 1) / (teeth * 2)
            y = -L * (1.0 if k % 2 == 0 else 0.93)
            d += f' L{x:.1f} {y:.1f}'
        return d + f' L{-Wd * 0.9:.1f} {-L * 0.92:.1f} C{-Wd:.1f} {-L * 0.7:.1f} {-Wd * 0.5:.1f} {-L * 0.3:.1f} 0 0 Z'

    def cosmos(r):
        """Cosmos: 6-10 broad petals with toothed tips (tooth count varies) around a beaded centre."""
        n = rnd.randint(6, 10)
        teeth = rnd.randint(2, 4)
        wr = rnd.uniform(0.24, 0.34) * 8 / n
        off = rnd.uniform(0, 360 / n)
        cr = r * rnd.uniform(0.13, 0.2)
        out = ''
        for k in range(n):
            a = off + k * 360 / n + rnd.uniform(-4, 4)
            L = r * rnd.uniform(0.78, 0.88)
            out += f'<g transform="rotate({a:.1f})"><g transform="translate(0 {-cr:.1f})">{st(petal_toothed(L, r * wr, teeth), SW)}{petal_lines(L, r * wr, rnd.randint(1, 3), 0.7)}</g></g>'
        out += f'<circle r="{cr:.1f}" {S} stroke-width="{SW * 0.85:.2f}"/>'
        m = rnd.randint(6, 10)
        out += ''.join(f'<circle cx="{polar(a, cr * 0.55)[0]:.1f}" cy="{polar(a, cr * 0.55)[1]:.1f}" r="1.3" {S} stroke-width="0.9"/>' for a in [j * 360 / m for j in range(m)])
        return out

    def sakura(r):
        """Cherry blossom: five or six notched petals (notch depth and width vary) and a ring of stamens."""
        n = rnd.choice((5, 5, 6))
        off = rnd.uniform(0, 360 / n)
        wr = rnd.uniform(0.36, 0.48) * 5 / n
        notch = rnd.uniform(0.08, 0.18)
        out = ''
        for k in range(n):
            a = off + k * 360 / n + rnd.uniform(-5, 5)
            L = r * rnd.uniform(0.84, 0.94)
            d = (f'M0 0 C{r * wr * 0.8:.1f} {-L * 0.25:.1f} {r * wr * 1.12:.1f} {-L * 0.9:.1f} {r * wr * 0.26:.1f} {-L:.1f} '
                 f'L0 {-L * (1 - notch):.1f} L{-r * wr * 0.26:.1f} {-L:.1f} '
                 f'C{-r * wr * 1.12:.1f} {-L * 0.9:.1f} {-r * wr * 0.8:.1f} {-L * 0.25:.1f} 0 0 Z')
            out += f'<g transform="rotate({a:.1f})"><g transform="translate(0 {-r * 0.1:.1f})">{st(d, SW)}{petal_lines(L, r * wr, rnd.randint(2, 4), 0.6)}</g></g>'
        m = rnd.randint(8, 14)
        sl = r * rnd.uniform(0.26, 0.36)
        for j in range(m):
            x, y = polar(j * 360 / m + rnd.uniform(-6, 6), sl * rnd.uniform(0.85, 1.1))
            out += st(f'M0 0 L{x:.1f} {y:.1f}', SW * 0.4) + f'<circle cx="{x:.1f}" cy="{y:.1f}" r="1.6" {S} stroke-width="0.9"/>'
        return out

    def lily(r):
        """A lily from above: three broad petals in front of three narrower ones (widths, speckles and
        stamens drawn at random), sometimes with curled tips."""
        fw, bw = rnd.uniform(0.26, 0.34), rnd.uniform(0.2, 0.26)
        off = rnd.uniform(0, 120)
        curls = rnd.random() < 0.4
        def speck():
            return ''.join(f'<circle cx="{rnd.uniform(-1, 1) * r * 0.07:.1f}" cy="{-r * rnd.uniform(0.15, 0.45):.1f}" r="1.3" {S} stroke-width="0.9"/>' for _ in range(rnd.randint(2, 6)))
        def tipcurl(L):
            return st(curl_d(0, -L, 0, r * 0.06, 1.1, rnd.choice((-1, 1))), SW * 0.5) if curls else ''
        front = [(off + k * 120 + rnd.uniform(-8, 8), blade_path(r, r * fw), st(f'M0 {-r * 0.12:.1f} L0 {-r * 0.85:.1f}', SW * 0.4, 0.8) + speck() + tipcurl(r), SW) for k in range(3)]
        back = [(off + 60 + k * 120 + rnd.uniform(-8, 8), blade_path(r * 0.92, r * bw), st(f'M0 {-r * 0.12:.1f} L0 {-r * 0.8:.1f}', SW * 0.4, 0.8) + tipcurl(r * 0.92), SW * 0.9) for k in range(3)]
        sl = r * rnd.uniform(0.5, 0.64)
        stamens = ''.join(st(f'M0 0 Q{polar(a + 8, sl * 0.5)[0]:.1f} {polar(a + 8, sl * 0.5)[1]:.1f} {polar(a, sl)[0]:.1f} {polar(a, sl)[1]:.1f}', SW * 0.45)
                          + f'<ellipse cx="{polar(a, sl * 1.03)[0]:.1f}" cy="{polar(a, sl * 1.03)[1]:.1f}" rx="{r * 0.05:.1f}" ry="{r * 0.025:.1f}" transform="rotate({a + 90:.0f} {polar(a, sl * 1.03)[0]:.1f} {polar(a, sl * 1.03)[1]:.1f})" {S} stroke-width="{SW * 0.5:.2f}"/>'
                          for a in [off + 30 + j * 60 + rnd.uniform(-10, 10) for j in range(6)])
        return flower_from_rings([[(a, d, det, w) for (a, d, det, w) in front], [(a, d, det, w) for (a, d, det, w) in back]], '') + stamens

    def lotus2(r):
        """Lotus from the side: front petals hide the ones behind (counts and widths vary)."""
        front = [(rnd.uniform(-4, 4), blade_path(r, r * rnd.uniform(0.32, 0.4)), petal_lines(r, r * 0.36, 3, 0.8), SW)]
        ms = rnd.uniform(24, 34)
        mid_ = [(a, blade_path(r * 0.92, r * rnd.uniform(0.3, 0.37)), petal_lines(r * 0.92, r * 0.34, 2, 0.75), SW * 0.95) for a in (-ms, ms)]
        back = [(a, blade_path(r * rnd.uniform(0.7, 0.82), r * 0.3), '', SW * 0.85) for a in [s_ * rnd.uniform(50, 60) for s_ in (-1, 1)] + ([s_ * rnd.uniform(74, 84) for s_ in (-1, 1)] if rnd.random() < 0.7 else [])]
        cup = st(f'M{-r * 0.5:.1f} {r * 0.02:.1f} Q0 {r * rnd.uniform(0.25, 0.35):.1f} {r * 0.5:.1f} {r * 0.02:.1f}', SW * 0.85)
        return flower_from_rings([front, mid_, back], '') + cup

    HEADS = (rose_top, peony, lily, cosmos, sakura, dahlia, anemone)

    def spray(size):
        """A composed floral spray: a curving stem with ornate leaves, a petalled flower at its head,
        sometimes a side branch with a smaller flower."""
        curl = size * rnd.uniform(-0.22, 0.22)
        pt, ang, path = stem(size, curl)
        out = [st(path, SW)]
        n = rnd.randint(2, 4)
        for i in range(n):
            t = min(0.82, 0.16 + 0.6 * i / max(1, n - 1))
            x, y = pt(t)
            side = -1 if i % 2 == 0 else 1
            L = size * rnd.uniform(0.34, 0.44) * (1 - 0.28 * t)
            out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({ang(t) + side * rnd.uniform(38, 60):.1f})">{ornate_leaf(L, L * rnd.uniform(0.28, 0.34), side * L * 0.1)}</g>')
        if rnd.random() < 0.55:
            t = rnd.uniform(0.38, 0.58)
            x, y = pt(t)
            side = rnd.choice((-1, 1))
            a = ang(t) + side * rnd.uniform(30, 45)
            ex, ey = polar(a, size * 0.3, x, y)
            mx, my = polar(a - side * 12, size * 0.16, x, y)
            out.append(st(f'M{x:.1f} {y:.1f} Q{mx:.1f} {my:.1f} {ex:.1f} {ey:.1f}', SW * 0.8))
            rs = size * rnd.uniform(0.09, 0.11)
            cx2, cy2 = polar(a, rs * 0.85, ex, ey)
            out.append(f'<g transform="translate({cx2:.1f} {cy2:.1f}) rotate({a:.1f})">{rnd.choice((sakura, cosmos, rose_top, anemone))(rs)}</g>')
        ex, ey = pt(1.0)
        rh = size * rnd.uniform(0.17, 0.21)
        cx, cy = polar(ang(1.0), rh * 0.85, ex, ey)
        out.append(f'<g transform="translate({cx:.1f} {cy:.1f}) rotate({ang(1.0) + rnd.uniform(-20, 20):.1f})">{rnd.choice(HEADS)(rh)}</g>')
        return ''.join(out)

    if sheet == 'leaves':  # design aid: 20 leaves from the generator, to check that none repeat
        cells = ''.join(f'<g transform="translate({120 + (i % 5) * 210} {300 + (i // 5) * 340})">{ornate_leaf(rnd.uniform(150, 230))}</g>' for i in range(20))
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}"><rect width="{W}" height="{H}" fill="#fff"/>{cells}</svg>')
    if sheet:  # design aid: one of each motif on a numbered grid
        demo = [f for fn in (rose_top, peony, dahlia, anemone, cosmos, sakura, lily, lotus2) for f in (lambda fn=fn: fn(78), lambda fn=fn: fn(78))]
        cells = []
        for i, draw in enumerate(demo):
            cx, cy = 140 + (i % 4) * 265, 180 + (i // 4) * 300
            cells.append(f'<g transform="translate({cx} {cy})">{draw()}</g><text x="{cx - 160}" y="{cy - 190}" font-size="28" fill="#000">{i}</text>')
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
                f'<rect width="{W}" height="{H}" fill="#fff"/>{"".join(cells)}</svg>')

    # ---------- composition: sprays laid out in a staggered, gently jittered drift (upright-ish), then
    # the gaps filled with large single ornate leaves, acanthus, standalone tulips and lotus, and a few
    # flower heads. Every piece is drawn with its own random shape: nothing repeats.
    placed, items = [], []

    def free(circles, pad=4):
        for (cx, cy, r) in circles:
            for (px, py, pr) in placed:
                if math.hypot(cx - px, cy - py) < r + pr + pad:
                    return False
        return True

    def axis_circles(x, y, a, axis, r):
        ra = math.radians(a)
        fs = (0.15, 0.4, 0.65, 0.9) if axis > 3 * r else ((0.25, 0.75) if axis > 1.6 * r else (0.5,))
        return [(x + math.sin(ra) * axis * f, y - math.cos(ra) * axis * f, r) for f in fs]

    row_h = 330
    for row in range(-1, int(H / row_h) + 2):
        cols = (240, 800) if row % 2 == 0 else (-40, 520, 1080)
        for cx in cols:
            size = rnd.uniform(300, 370)
            x = cx + rnd.uniform(-60, 60)
            y = row * row_h + size * 0.75 + rnd.uniform(-40, 40)
            a = rnd.uniform(-28, 28)
            placed.extend(axis_circles(x, y, a, size, 46))
            placed.append((*polar(a, size * 1.05, x, y), size * 0.2))
            items.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({a:.1f})">{spray(size)}</g>')

    fill = [  # (draw, axis, radius, count, rotation range)
        (lambda: acanthus(rnd.uniform(190, 240)), 215, 44, 6, 60),
        (lambda: ornate_leaf(rnd.uniform(140, 190), rnd.uniform(42, 56), rnd.uniform(-14, 14)), 165, 44, 14, 75),
        (lambda: tulip(rnd.uniform(52, 62)), 150, 48, 4, 25),
        (lambda: lotus2(rnd.uniform(56, 66)), 80, 60, 3, 20),
        (lambda: rnd.choice(HEADS)(rnd.uniform(48, 62)), 0, 62, 6, 180),
        (lambda: ornate_leaf(rnd.uniform(100, 135), rnd.uniform(30, 40), rnd.uniform(-10, 10)), 118, 34, 14, 75),
        (lambda: fern(rnd.uniform(220, 270)), 245, 40, 2, 50),
        (lambda: ginkgo(rnd.uniform(52, 62)), 105, 46, 2, 50),
    ]
    for draw, axis, r, count, spin in fill:
        for _ in range(count):
            for _attempt in range(1500):
                x, y, a = rnd.uniform(-20, W + 20), rnd.uniform(-20, H + 20), rnd.uniform(-spin, spin)
                circles = axis_circles(x, y, a, axis, r) if axis else [(x, y, r)]
                if free(circles):
                    placed.extend(circles)
                    items.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({a:.1f})">{draw()}</g>')
                    break
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">'
            f'<clipPath id="c"><rect width="{W}" height="{H}"/></clipPath><g clip-path="url(#c)">{"".join(items)}</g></svg>')


if __name__ == "__main__":
    open("header_panorama.svg", "w").write(panorama())
    open("chat_footer.svg", "w").write(gold_footer())
    open("chat_leaves.svg", "w").write(chat_leaves())
    print("art written")
