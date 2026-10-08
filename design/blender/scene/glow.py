"""Sunlight around the sun, after rendering: a warm atmospheric halo centred where the sun landed in the frame
(the render writes OUT.png.sun) plus a soft bloom on the brightest pixels, so the sun reads as real sunlight
rather than a disc pasted on the sky (the owner's note)."""
import os, sys
import numpy as np
from PIL import Image, ImageFilter
src, dst = sys.argv[1], sys.argv[2]
img = np.asarray(Image.open(src).convert('RGB')).astype(np.float32) / 255.0
h, w, _ = img.shape
warm = np.array([1.0, 0.9, 0.72], dtype=np.float32)
out = img.copy()
sun = src + '.sun'
if os.path.exists(sun):
    sx, sy, vis = map(float, open(sun).read().split())
    if vis > 0:
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        r = np.hypot(xx - sx * w, yy - sy * h) / w
        halo = 0.55 * np.exp(-(r / 0.035) ** 2) + 0.28 * np.exp(-(r / 0.12) ** 2) + 0.12 * np.exp(-(r / 0.32) ** 2)
        out = 1 - (1 - out) * (1 - halo[..., None] * warm)           # screen
# bloom: blur the brightest pixels and screen them back
lum = out.mean(axis=2)
bright = np.where(lum[..., None] > 0.93, out, 0)
b8 = Image.fromarray((bright * 255).astype(np.uint8))
near = np.asarray(b8.filter(ImageFilter.GaussianBlur(w * 0.01))).astype(np.float32) / 255.0
far = np.asarray(b8.filter(ImageFilter.GaussianBlur(w * 0.04))).astype(np.float32) / 255.0
for layer, k in ((near, 1.6), (far, 1.4)):
    out = 1 - (1 - out) * (1 - np.clip(layer * k, 0, 1) * warm)
Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8)).save(dst)
print('glow ->', dst)
