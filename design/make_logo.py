#!/usr/bin/env python3
"""
Generates the Guftugu logo (design/logo.svg) and the adaptive-icon layers.

A square, bevelled gold frame with corner rosettes around a painted riverbank:
clear sky, low hills, broad spreading trees with open meadow between them, a
meandering stream widening towards the viewer, and a grassy landing with
stepping stones. Deterministic (seeded) so re-running gives the same art.
"""
import math, random

W = 1080
C = W / 2

def f(x):  # compact number formatting
    return f"{x:.1f}".rstrip('0').rstrip('.')

# ---------------------------------------------------------------- foliage
def canopy(cx, cy, w, h, seed, pal, n=78, rmin=0.11, rmax=0.22):
    """A broad, lumpy canopy: overlapping circles inside an ellipse, shaded in three passes."""
    rnd = random.Random(seed)
    blobs = []
    for _ in range(n):
        # sample inside a flattened ellipse, denser in the middle
        a = rnd.uniform(0, 2 * math.pi)
        rr = math.sqrt(rnd.uniform(0, 1)) * 0.92
        x = cx + math.cos(a) * w * rr
        y = cy + math.sin(a) * h * rr * 0.85
        r = rnd.uniform(rmin, rmax) * h * 1.25
        blobs.append((x, y, r))
    # top-heavy dome: push blobs near the bottom edge up a little
    blobs.sort(key=lambda b: b[1])
    dark, mid, light, glint = pal
    out = []
    out.append(f'<g>')
    # solid mass first so the canopy reads as one broad crown, not separate bubbles
    out.append(f'<ellipse cx="{f(cx)}" cy="{f(cy + h * 0.12)}" rx="{f(w * 0.96)}" ry="{f(h * 0.78)}" fill="{dark}"/>')
    for x, y, r in blobs:  # shadow mass
        out.append(f'<circle cx="{f(x)}" cy="{f(y + r * 0.18)}" r="{f(r)}" fill="{dark}"/>')
    for x, y, r in blobs:  # body
        out.append(f'<circle cx="{f(x - r * 0.08)}" cy="{f(y - r * 0.05)}" r="{f(r * 0.86)}" fill="{mid}"/>')
    lit = [b for b in blobs if b[1] < cy + h * 0.15]
    for x, y, r in lit:  # sunlit tops (sun is upper right)
        out.append(f'<circle cx="{f(x + r * 0.18)}" cy="{f(y - r * 0.28)}" r="{f(r * 0.52)}" fill="{light}"/>')
    for x, y, r in lit[: max(3, len(lit) // 4)]:
        out.append(f'<circle cx="{f(x + r * 0.28)}" cy="{f(y - r * 0.42)}" r="{f(r * 0.22)}" fill="{glint}" opacity="0.8"/>')
    out.append('</g>')
    return '\n'.join(out)

def broad_tree(x, ground, scale, seed, pal=None, branches=3):
    """Short, thick trunk splitting into wide limbs under a broad canopy + a soft ground shadow."""
    pal = pal or ("#2E6B2C", "#4E9A3A", "#8FCB5C", "#D2F09A")
    s = scale
    trunk_top = ground - 70 * s
    cw, ch = 175 * s, 78 * s
    ccx, ccy = x, trunk_top - ch * 0.62
    parts = []
    parts.append(f'<ellipse cx="{f(x + 14 * s)}" cy="{f(ground + 4 * s)}" rx="{f(cw * 0.95)}" ry="{f(16 * s)}" fill="#1F4F22" opacity="0.28"/>')
    # trunk
    parts.append(
        f'<path d="M{f(x - 16*s)} {f(ground)} C{f(x - 12*s)} {f(ground - 30*s)} {f(x - 14*s)} {f(trunk_top + 10*s)} {f(x - 6*s)} {f(trunk_top)} '
        f'L{f(x + 8*s)} {f(trunk_top)} C{f(x + 14*s)} {f(trunk_top + 12*s)} {f(x + 12*s)} {f(ground - 30*s)} {f(x + 18*s)} {f(ground)} Z" fill="url(#bark)"/>')
    # roots
    parts.append(f'<path d="M{f(x - 16*s)} {f(ground)} q{f(-14*s)} {f(2*s)} {f(-24*s)} {f(6*s)} M{f(x + 18*s)} {f(ground)} q{f(14*s)} {f(2*s)} {f(26*s)} {f(7*s)}" stroke="#4A3119" stroke-width="{f(5*s)}" stroke-linecap="round" fill="none"/>')
    # wide limbs
    rnd = random.Random(seed * 7 + 1)
    limbs = []
    spread = [-1, 1, -0.45, 0.5, 0][:branches + 2]
    for k in spread:
        ex = x + k * cw * rnd.uniform(0.55, 0.75)
        ey = ccy + ch * rnd.uniform(-0.1, 0.25)
        mx = x + k * cw * 0.25
        my = trunk_top - 18 * s
        limbs.append(f'<path d="M{f(x)} {f(trunk_top + 6*s)} Q{f(mx)} {f(my)} {f(ex)} {f(ey)}" stroke="#5A3E22" stroke-width="{f(9*s if abs(k) > 0.4 else 7*s)}" stroke-linecap="round" fill="none"/>')
    parts.extend(limbs)
    parts.append(canopy(ccx, ccy, cw, ch, seed, pal))
    # a few limbs peeking below the canopy edge
    parts.append(f'<path d="M{f(x - cw*0.35)} {f(ccy + ch*0.55)} q{f(-10*s)} {f(8*s)} {f(-26*s)} {f(6*s)}" stroke="#5A3E22" stroke-width="{f(4*s)}" stroke-linecap="round" fill="none" opacity="0.9"/>')
    return '\n'.join(parts)

def far_tree(x, ground, s, seed):
    pal = ("#4C7F5C", "#6A9E72", "#9CC78C", "#C8E6AE")
    return (f'<rect x="{f(x - 3*s)}" y="{f(ground - 20*s)}" width="{f(6*s)}" height="{f(20*s)}" fill="#6B5A44"/>' +
            canopy(x, ground - 34 * s, 46 * s, 20 * s, seed, pal, n=16))

def tuft(x, y, s=1.0, c1="#3E8A2E", c2="#6DBF45"):
    return (f'<g transform="translate({f(x)} {f(y)}) scale({f(s)})" fill="none" stroke-linecap="round">'
            f'<path d="M0 0 C-3 -12 -10 -22 -14 -30" stroke="{c1}" stroke-width="3.6"/>'
            f'<path d="M0 0 C1 -14 -2 -26 -4 -36" stroke="{c2}" stroke-width="3.6"/>'
            f'<path d="M0 0 C4 -12 10 -22 16 -30" stroke="{c1}" stroke-width="3.6"/>'
            f'<path d="M0 0 C6 -10 12 -14 22 -18" stroke="#9AD868" stroke-width="3"/></g>')

def flower(x, y, s, petal="#FFFFFF", heart="#F2B233"):
    p = ''.join(f'<circle cx="{f(math.cos(a)*5)}" cy="{f(math.sin(a)*5)}" r="3.6" fill="{petal}"/>' for a in [i * math.pi * 2 / 5 for i in range(5)])
    return f'<g transform="translate({f(x)} {f(y)}) scale({f(s)})">{p}<circle r="2.8" fill="{heart}"/></g>'

# ---------------------------------------------------------------- frame
def frame(x0, y0, size, band):
    x1, y1 = x0 + size, y0 + size
    r = 30
    ix0, iy0, ix1, iy1 = x0 + band, y0 + band, x1 - band, y1 - band
    out = []
    # main band (outer rect minus inner rect via evenodd)
    out.append(f'<path fill-rule="evenodd" fill="url(#goldBand)" d="M{x0+r} {y0} H{x1-r} Q{x1} {y0} {x1} {y0+r} V{y1-r} Q{x1} {y1} {x1-r} {y1} H{x0+r} Q{x0} {y1} {x0} {y1-r} V{y0+r} Q{x0} {y0} {x0+r} {y0} Z '
               f'M{ix0} {iy0} H{ix1} V{iy1} H{ix0} Z"/>')
    # bevels: outer highlight, outer dark edge
    out.append(f'<rect x="{x0}" y="{y0}" width="{size}" height="{size}" rx="{r}" fill="none" stroke="#5A4410" stroke-width="3"/>')
    out.append(f'<rect x="{x0+5}" y="{y0+5}" width="{size-10}" height="{size-10}" rx="{r-4}" fill="none" stroke="url(#goldBevelLight)" stroke-width="4"/>')
    # engraved channel in the band
    m = band * 0.5
    out.append(f'<rect x="{x0+m}" y="{y0+m}" width="{size-2*m}" height="{size-2*m}" rx="{r*0.5}" fill="none" stroke="#8A6818" stroke-width="7" opacity="0.55"/>')
    out.append(f'<rect x="{x0+m}" y="{y0+m}" width="{size-2*m}" height="{size-2*m}" rx="{r*0.5}" fill="none" stroke="#FFF1B8" stroke-width="1.6" opacity="0.8" transform="translate(-1.2 -1.2)"/>')
    # beads along the channel
    beads = []
    step = 21
    L = size - 2 * m
    n = int(L // step)
    for i in range(1, n):
        t = x0 + m + i * L / n
        for (bx, by) in [(t, y0 + m), (t, y1 - m), (x0 + m, t), (x1 - m, t)]:
            beads.append(f'<circle cx="{f(bx)}" cy="{f(by)}" r="3.3"/>')
    out.append(f'<g fill="url(#bead)" stroke="#6E5312" stroke-width="0.9">{"".join(beads)}</g>')
    # inner bevel down to the painting
    out.append(f'<rect x="{ix0-7}" y="{iy0-7}" width="{ix1-ix0+14}" height="{iy1-iy0+14}" rx="6" fill="none" stroke="url(#goldBevelDark)" stroke-width="10"/>')
    out.append(f'<rect x="{ix0-1.5}" y="{iy0-1.5}" width="{ix1-ix0+3}" height="{iy1-iy0+3}" rx="3" fill="none" stroke="#4A3708" stroke-width="3"/>')
    # corner rosettes
    for (cx, cy) in [(x0 + m, y0 + m), (x1 - m, y0 + m), (x0 + m, y1 - m), (x1 - m, y1 - m)]:
        out.append(rosette(cx, cy, band * 0.62))
    # mid-edge cartouches (small diamonds with a jewel)
    for (cx, cy, rot) in [(C, y0 + m, 0), (C, y1 - m, 0), (x0 + m, C, 90), (x1 - m, C, 90)]:
        out.append(cartouche(cx, cy, rot))
    return '\n'.join(out)

def rosette(cx, cy, R):
    out = [f'<g transform="translate({f(cx)} {f(cy)})">']
    out.append(f'<circle r="{f(R)}" fill="url(#goldBoss)" stroke="#5A4410" stroke-width="2"/>')
    for i in range(8):
        a = i * 45
        out.append(f'<ellipse rx="{f(R*0.26)}" ry="{f(R*0.58)}" cy="{f(-R*0.42)}" transform="rotate({a})" fill="url(#goldPetal)" stroke="#6E5312" stroke-width="1.1"/>')
    out.append(f'<circle r="{f(R*0.34)}" fill="url(#jewel)" stroke="#4A3708" stroke-width="1.6"/>')
    out.append(f'<circle cx="{f(-R*0.12)}" cy="{f(-R*0.12)}" r="{f(R*0.1)}" fill="#FFFFFF" opacity="0.85"/>')
    out.append('</g>')
    return ''.join(out)

def cartouche(cx, cy, rot):
    return (f'<g transform="translate({f(cx)} {f(cy)}) rotate({rot})">'
            f'<path d="M-44 0 L-14 -12 L0 -17 L14 -12 L44 0 L14 12 L0 17 L-14 12 Z" fill="url(#goldPetal)" stroke="#5A4410" stroke-width="1.6"/>'
            f'<circle r="7.5" fill="url(#jewel)" stroke="#4A3708" stroke-width="1.3"/>'
            f'<circle cx="-2.2" cy="-2.2" r="2.2" fill="#FFFFFF" opacity="0.85"/></g>')

# ---------------------------------------------------------------- scene
def scene(px0, py0, px1, py1):
    w = px1 - px0
    s = []
    def X(t): return px0 + t * w          # 0..1 -> x
    def Y(t): return py0 + t * (py1 - py0)
    s.append(f'<rect x="{px0}" y="{py0}" width="{w}" height="{py1-py0}" fill="url(#sky)"/>')
    # sun + glow (upper right)
    s.append(f'<circle cx="{f(X(0.76))}" cy="{f(Y(0.2))}" r="170" fill="url(#sunGlow)"/>')
    s.append(f'<circle cx="{f(X(0.76))}" cy="{f(Y(0.2))}" r="34" fill="#FFF8D2"/>')
    # a couple of thin, high clouds (clear sky)
    s.append(f'<g fill="#FFFFFF" opacity="0.8"><ellipse cx="{f(X(0.28))}" cy="{f(Y(0.2))}" rx="78" ry="12"/><ellipse cx="{f(X(0.33))}" cy="{f(Y(0.185))}" rx="44" ry="15"/>'
             f'<ellipse cx="{f(X(0.52))}" cy="{f(Y(0.31))}" rx="60" ry="8" opacity="0.7"/></g>')
    # birds
    for (bx, by, bs) in [(0.40, 0.14, 1.0), (0.45, 0.17, 0.75), (0.60, 0.11, 0.6)]:
        s.append(f'<path d="M{f(X(bx)-12*bs)} {f(Y(by))} q{f(6*bs)} {f(-7*bs)} {f(12*bs)} 0 q{f(6*bs)} {f(-7*bs)} {f(12*bs)} 0" stroke="#2B3A4A" stroke-width="{f(2.4*bs)}" fill="none" stroke-linecap="round"/>')
    # low, far hills (atmospheric)
    s.append(f'<path d="M{px0} {f(Y(0.53))} C{f(X(0.15))} {f(Y(0.47))} {f(X(0.3))} {f(Y(0.48))} {f(X(0.42))} {f(Y(0.515))} C{f(X(0.55))} {f(Y(0.55))} {f(X(0.66))} {f(Y(0.46))} {f(X(0.82))} {f(Y(0.48))} C{f(X(0.9))} {f(Y(0.49))} {f(X(0.96))} {f(Y(0.5))} {px1} {f(Y(0.51))} L{px1} {f(Y(0.62))} L{px0} {f(Y(0.62))} Z" fill="url(#farHills)"/>')
    # far broad trees on the horizon
    for i, (tx, ty, ts) in enumerate([(0.07, 0.565, 0.8), (0.18, 0.56, 0.9), (0.27, 0.57, 0.7), (0.66, 0.555, 0.85), (0.78, 0.55, 0.95), (0.9, 0.56, 0.8)]):
        s.append(far_tree(X(tx), Y(ty), ts, 100 + i))
    # nearer hills
    s.append(f'<path d="M{px0} {f(Y(0.58))} C{f(X(0.2))} {f(Y(0.55))} {f(X(0.38))} {f(Y(0.585))} {f(X(0.5))} {f(Y(0.575))} C{f(X(0.62))} {f(Y(0.565))} {f(X(0.8))} {f(Y(0.545))} {px1} {f(Y(0.57))} L{px1} {f(Y(0.7))} L{px0} {f(Y(0.7))} Z" fill="url(#nearHills)"/>')
    # meadow
    s.append(f'<path d="M{px0} {f(Y(0.62))} C{f(X(0.3))} {f(Y(0.605))} {f(X(0.7))} {f(Y(0.61))} {px1} {f(Y(0.6))} L{px1} {py1} L{px0} {py1} Z" fill="url(#meadow)"/>')
    # sunlit meadow patches
    s.append(f'<ellipse cx="{f(X(0.25))}" cy="{f(Y(0.7))}" rx="160" ry="26" fill="#B7E47C" opacity="0.45"/>')
    s.append(f'<ellipse cx="{f(X(0.8))}" cy="{f(Y(0.73))}" rx="140" ry="22" fill="#B7E47C" opacity="0.4"/>')

    # the stream: thin in the distance, widening towards us, one gentle S-curve
    L, R = [], []
    pts = [(0.52, 0.615, 0.008), (0.50, 0.64, 0.014), (0.44, 0.68, 0.024), (0.40, 0.72, 0.034), (0.43, 0.77, 0.048), (0.52, 0.82, 0.066), (0.57, 0.88, 0.09), (0.55, 0.94, 0.115), (0.50, 1.0, 0.14)]
    for (cx, cy, hw) in pts:
        L.append((X(cx) - hw * w, Y(cy)))
        R.append((X(cx) + hw * w, Y(cy)))
    def smooth(points):
        d = f"M{f(points[0][0])} {f(points[0][1])}"
        for i in range(1, len(points)):
            x0_, y0_ = points[i - 1]; x1_, y1_ = points[i]
            my = (y0_ + y1_) / 2
            d += f" C{f(x0_)} {f(my)} {f(x1_)} {f(my)} {f(x1_)} {f(y1_)}"
        return d
    bank = smooth([(x - 10, y) for x, y in L]) + ' L' + ' L'.join(f"{f(x + 10)} {f(y)}" for x, y in reversed(R)) + ' Z'
    water = smooth(L) + ' ' + smooth(list(reversed(R))).replace('M', 'L', 1) + ' Z'
    s.append(f'<path d="{bank}" fill="url(#earth)"/>')
    s.append(f'<path d="{water}" fill="url(#water)"/>')
    # sky reflection + ripples
    s.append(f'<path d="{water}" fill="url(#waterSheen)" opacity="0.6"/>')
    for (cx, cy, ww) in [(0.45, 0.70, 0.02), (0.41, 0.735, 0.03), (0.445, 0.78, 0.04), (0.53, 0.83, 0.055), (0.565, 0.885, 0.07), (0.55, 0.94, 0.09), (0.5, 0.985, 0.11)]:
        s.append(f'<path d="M{f(X(cx) - ww*w)} {f(Y(cy))} q{f(ww*w*0.5)} -6 {f(ww*w)} 0 q{f(ww*w*0.5)} 6 {f(ww*w)} 0" stroke="#FFFFFF" stroke-width="2.4" fill="none" opacity="0.55" stroke-linecap="round"/>')
    for (cx, cy) in [(0.43, 0.745), (0.54, 0.845), (0.58, 0.9), (0.47, 0.96), (0.6, 0.97)]:
        s.append(f'<circle cx="{f(X(cx))}" cy="{f(Y(cy))}" r="2.6" fill="#FFFFFF" opacity="0.9"/>')

    # a white paper boat drifting down the stream (Guftugu's motif: a message on its way)
    bx, by = X(0.47), Y(0.765)
    s.append(f'<g transform="translate({f(bx)} {f(by)}) scale(1.15)">'
             f'<ellipse cx="0" cy="9" rx="30" ry="4" fill="#0E3C73" opacity="0.25"/>'
             f'<path d="M-28 0 L28 0 L18 10 L-18 10 Z" fill="#F4F1E8" stroke="#8C8A80" stroke-width="1.2"/>'
             f'<path d="M-18 0 L0 -26 L6 0 Z" fill="#FFFFFF" stroke="#8C8A80" stroke-width="1.2"/>'
             f'<path d="M0 -26 L16 0 L6 0 Z" fill="#E4E0D4" stroke="#8C8A80" stroke-width="1.2"/>'
             f'<path d="M-30 12 q8 -3 16 0 M14 12 q8 -3 16 0" stroke="#FFFFFF" stroke-width="1.6" fill="none" opacity="0.8"/></g>')
    # two ducks further down
    for (dx, dy, dsc) in [(0.555, 0.875, 0.9), (0.585, 0.892, 0.75)]:
        x = X(dx); y = Y(dy)
        s.append(f'<g transform="translate({f(x)} {f(y)}) scale({dsc})"><ellipse cx="0" cy="0" rx="13" ry="7" fill="#8B6A4A"/>'
                 f'<circle cx="11" cy="-7" r="5.5" fill="#2E6B4A"/><path d="M15 -7 l7 1.5 l-7 1.5 z" fill="#F2B233"/>'
                 f'<circle cx="12.5" cy="-8.5" r="1.1" fill="#FFFFFF"/><path d="M-10 -2 q6 -5 12 0" stroke="#E8DCC8" stroke-width="2" fill="none"/></g>')
    # broad trees: spacious, one big on each side of the stream, one mid, open meadow between
    s.append(broad_tree(X(0.2), Y(0.74), 1.18, 11))
    s.append(broad_tree(X(0.8), Y(0.69), 0.9, 23, pal=("#2F6A33", "#4F983F", "#94CC62", "#D6F1A0")))
    s.append(broad_tree(X(0.64), Y(0.635), 0.48, 37, pal=("#3E7447", "#5E9B55", "#9CCA78", "#D2EDB2")))

    # grassy landing on the near-right bank: a flat, sunlit ledge by the water with stepping stones
    s.append(f'<path d="M{f(X(0.66))} {f(Y(0.86))} C{f(X(0.72))} {f(Y(0.835))} {f(X(0.86))} {f(Y(0.84))} {px1} {f(Y(0.83))} L{px1} {py1} L{f(X(0.75))} {py1} C{f(X(0.7))} {f(Y(0.95))} {f(X(0.66))} {f(Y(0.9))} {f(X(0.66))} {f(Y(0.86))} Z" fill="url(#landing)"/>')
    s.append(f'<path d="M{f(X(0.66))} {f(Y(0.86))} C{f(X(0.66))} {f(Y(0.9))} {f(X(0.7))} {f(Y(0.95))} {f(X(0.75))} {py1}" stroke="#7A5A36" stroke-width="7" fill="none" opacity="0.8"/>')
    s.append(f'<ellipse cx="{f(X(0.8))}" cy="{f(Y(0.875))}" rx="110" ry="24" fill="#C4EC86" opacity="0.75"/>')
    s.append(f'<ellipse cx="{f(X(0.79))}" cy="{f(Y(0.872))}" rx="70" ry="13" fill="#DDF7A8" opacity="0.7"/>')
    for (cx, cy, rx, ry) in [(0.62, 0.9, 20, 9), (0.595, 0.945, 24, 10), (0.635, 0.985, 26, 11)]:
        s.append(f'<ellipse cx="{f(X(cx))}" cy="{f(Y(cy))}" rx="{rx}" ry="{ry}" fill="#A99C86" stroke="#6E6252" stroke-width="1.6"/>'
                 f'<ellipse cx="{f(X(cx) - 4)}" cy="{f(Y(cy) - 3)}" rx="{rx*0.6}" ry="{ry*0.45}" fill="#CFC4AE" opacity="0.8"/>')
    # near-left bank with reeds
    s.append(f'<path d="M{px0} {f(Y(0.86))} C{f(X(0.12))} {f(Y(0.85))} {f(X(0.28))} {f(Y(0.87))} {f(X(0.36))} {f(Y(0.9))} C{f(X(0.4))} {f(Y(0.92))} {f(X(0.4))} {f(Y(0.97))} {f(X(0.37))} {py1} L{px0} {py1} Z" fill="url(#landing)"/>')
    for (rx_, ry_, rs) in [(0.35, 0.93, 0.8), (0.33, 0.96, 0.62), (0.37, 0.975, 0.5)]:
        x = X(rx_); y = Y(ry_)
        s.append(f'<g transform="translate({f(x)} {f(y)}) scale({rs})"><path d="M0 0 L0 -110" stroke="#4C8E33" stroke-width="5" stroke-linecap="round"/>'
                 f'<ellipse cx="0" cy="-118" rx="6" ry="20" fill="#6B4A2B"/><path d="M0 -30 C14 -50 20 -80 18 -120" stroke="#6DB44A" stroke-width="4" fill="none" stroke-linecap="round"/>'
                 f'<path d="M0 -20 C-12 -40 -22 -70 -20 -100" stroke="#3E7F2B" stroke-width="4" fill="none" stroke-linecap="round"/></g>')
    rnd = random.Random(5)
    for _ in range(26):
        tx = rnd.choice([rnd.uniform(0.02, 0.33), rnd.uniform(0.7, 0.98)])
        ty = rnd.uniform(0.86, 0.99)
        s.append(tuft(X(tx), Y(ty), rnd.uniform(0.7, 1.15)))
    for (tx, ty, sc, c) in [(0.78, 0.9, 1.0, "#FFFFFF"), (0.86, 0.93, 0.9, "#FFE27A"), (0.93, 0.88, 0.8, "#FFFFFF"), (0.72, 0.96, 0.9, "#F7A6B8"),
                            (0.1, 0.92, 0.9, "#FFFFFF"), (0.18, 0.95, 0.85, "#FFE27A"), (0.26, 0.9, 0.7, "#F7A6B8"), (0.05, 0.97, 0.8, "#FFFFFF"),
                            (0.35, 0.75, 0.55, "#FFFFFF"), (0.9, 0.76, 0.55, "#FFE27A"), (0.12, 0.8, 0.6, "#FFFFFF")]:
        s.append(flower(X(tx), Y(ty), sc, petal=c))
    # painterly grain + gentle vignette
    s.append(f'<rect x="{px0}" y="{py0}" width="{w}" height="{py1-py0}" fill="url(#vignette)"/>')
    return '\n'.join(s)

DEFS = '''<defs>
<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1767D6"/><stop offset="0.3" stop-color="#2F8BEA"/><stop offset="0.55" stop-color="#78C0F4"/><stop offset="0.75" stop-color="#CDEBFF"/><stop offset="0.85" stop-color="#EAF7FF"/></linearGradient>
<radialGradient id="sunGlow"><stop offset="0" stop-color="#FFF7C6" stop-opacity="0.95"/><stop offset="0.3" stop-color="#FFF0A8" stop-opacity="0.45"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>
<linearGradient id="farHills" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#9CC6BC"/><stop offset="1" stop-color="#6FA597"/></linearGradient>
<linearGradient id="nearHills" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8FC585"/><stop offset="1" stop-color="#6DAA5E"/></linearGradient>
<linearGradient id="meadow" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#A2D96E"/><stop offset="0.5" stop-color="#77BC4A"/><stop offset="1" stop-color="#4F9632"/></linearGradient>
<linearGradient id="landing" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8FD05A"/><stop offset="0.6" stop-color="#5DA83A"/><stop offset="1" stop-color="#3B7C27"/></linearGradient>
<linearGradient id="earth" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#B58B5E"/><stop offset="1" stop-color="#6A4B2E"/></linearGradient>
<linearGradient id="water" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#A4DAF6"/><stop offset="0.4" stop-color="#4AA4E2"/><stop offset="1" stop-color="#1B5FAA"/></linearGradient>
<linearGradient id="waterSheen" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0"/><stop offset="0.45" stop-color="#FFFFFF" stop-opacity="0.35"/><stop offset="0.6" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
<linearGradient id="bark" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#4A3119"/><stop offset="0.45" stop-color="#8B6238"/><stop offset="1" stop-color="#3E2913"/></linearGradient>
<radialGradient id="vignette" cx="0.5" cy="0.45" r="0.78"><stop offset="0.7" stop-color="#0A2540" stop-opacity="0"/><stop offset="1" stop-color="#0A2540" stop-opacity="0.14"/></radialGradient>
<filter id="grain" x="0" y="0" width="100%" height="100%"><feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="2" seed="7"/><feColorMatrix type="saturate" values="0"/></filter>
<linearGradient id="goldBand" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFF1B0"/><stop offset="0.18" stop-color="#E7C35A"/><stop offset="0.38" stop-color="#B48A2C"/><stop offset="0.52" stop-color="#F3D67C"/><stop offset="0.7" stop-color="#C39A35"/><stop offset="0.86" stop-color="#E9C865"/><stop offset="1" stop-color="#8A6518"/></linearGradient>
<linearGradient id="goldBevelLight" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFF6D0"/><stop offset="0.5" stop-color="#F0D27A" stop-opacity="0.5"/><stop offset="1" stop-color="#FFF6D0" stop-opacity="0.2"/></linearGradient>
<linearGradient id="goldBevelDark" x1="1" y1="1" x2="0" y2="0"><stop offset="0" stop-color="#FFF3C4"/><stop offset="0.45" stop-color="#C79E36"/><stop offset="1" stop-color="#6E5312"/></linearGradient>
<radialGradient id="bead" cx="0.35" cy="0.35" r="0.7"><stop offset="0" stop-color="#FFFBE6"/><stop offset="0.6" stop-color="#EBC862"/><stop offset="1" stop-color="#9C7A22"/></radialGradient>
<radialGradient id="goldBoss" cx="0.35" cy="0.3" r="0.8"><stop offset="0" stop-color="#FFF6CF"/><stop offset="0.5" stop-color="#D9B044"/><stop offset="1" stop-color="#8A6518"/></radialGradient>
<linearGradient id="goldPetal" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFF3C4"/><stop offset="0.5" stop-color="#E2BC55"/><stop offset="1" stop-color="#9C7A22"/></linearGradient>
<radialGradient id="jewel" cx="0.4" cy="0.35" r="0.75"><stop offset="0" stop-color="#9FE3FF"/><stop offset="0.5" stop-color="#2F86D2"/><stop offset="1" stop-color="#0E3C73"/></radialGradient>
</defs>'''

def logo_svg(frame_size=1000, band=78):
    x0 = (W - frame_size) / 2
    px0 = x0 + band
    px1 = x0 + frame_size - band
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {W}" width="{W}" height="{W}">{DEFS}'
            f'<clipPath id="paint"><rect x="{px0}" y="{px0}" width="{px1-px0}" height="{px1-px0}" rx="4"/></clipPath>'
            f'<rect x="{x0+6}" y="{x0+10}" width="{frame_size}" height="{frame_size}" rx="30" fill="#3A2A05" opacity="0.28"/>'
            f'<g clip-path="url(#paint)">{scene(px0, px0, px1, px1)}</g>'
            f'{frame(x0, x0, frame_size, band)}</svg>')

if __name__ == "__main__":
    import sys, os
    out = os.path.dirname(os.path.abspath(__file__))
    open(os.path.join(out, "logo.svg"), "w").write(logo_svg())
    print("wrote logo.svg")
