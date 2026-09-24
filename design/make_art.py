#!/usr/bin/env python3
"""In-app illustrations derived from the logo's world (see make_logo.py)."""
import math, random
import make_logo as L

def panorama(w=1440, h=560):
    # Reuse the logo scene on a wide canvas: same sky, hills, broad trees, stream, landing.
    body = L.scene(0, 0, w, h)
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

if __name__ == "__main__":
    open("header_panorama.svg", "w").write(panorama())
    open("chat_footer.svg", "w").write(gold_footer())
    print("art written")
