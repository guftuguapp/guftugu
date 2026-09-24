#!/usr/bin/env bash
# Regenerate the logo, launcher icon layers and in-app illustrations from their sources.
# Needs: python3 + Pillow (with WebP), google-chrome (headless) for SVG rendering.
set -euo pipefail
cd "$(dirname "$0")"
RES=../android/app/src/main/res
python3 make_logo.py
python3 make_art.py
python3 - <<'PY'
import make_logo, math
logo = open('logo.svg').read()
i = logo.index('>', logo.index('<svg')) + 1
open('icon_foreground.svg', 'w').write(logo[:i] + '<g transform="translate(540 540) scale(0.6) translate(-540 -540)">' + logo[i:logo.rindex('</svg>')] + '</g></svg>')
PY
render() { # name width height
  echo "<html><body style='margin:0;background:transparent'><img src='$1.svg' width=$2 height=$3></body></html>" > "r_$1.html"
  google-chrome --headless=new --disable-gpu --no-sandbox --hide-scrollbars --default-background-color=00000000 \
    --window-size="$2,$3" --screenshot="$1.png" "file://$PWD/r_$1.html" >/dev/null 2>&1
}
render logo 1080 1080; render icon_foreground 1080 1080; render icon_background 1080 1080
render header_panorama 1440 560; render chat_footer 1080 380
python3 - "$RES" <<'PY'
import sys, os
from PIL import Image, ImageDraw
R = sys.argv[1]
fg = Image.open('icon_foreground.png').convert('RGBA'); bg = Image.open('icon_background.png').convert('RGBA')
for d, m in {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}.items():
    px = int(108 * m); os.makedirs(f'{R}/mipmap-{d}', exist_ok=True)
    fg.resize((px, px), Image.LANCZOS).save(f'{R}/mipmap-{d}/ic_launcher_foreground.webp', 'WEBP', quality=92, method=6)
    bg.resize((px, px), Image.LANCZOS).convert('RGB').save(f'{R}/mipmap-{d}/ic_launcher_background.webp', 'WEBP', quality=88, method=6)
    comp = Image.alpha_composite(bg, fg); lp = int(48 * m); c = int(1080 * 18 / 108)
    legacy = comp.crop((c, c, 1080 - c, 1080 - c)).resize((lp, lp), Image.LANCZOS)
    for name, shape in (('ic_launcher', 'rr'), ('ic_launcher_round', 'circle')):
        mask = Image.new('L', (lp, lp), 0); dr = ImageDraw.Draw(mask)
        (dr.rounded_rectangle((0, 0, lp - 1, lp - 1), radius=int(lp * .22), fill=255) if shape == 'rr' else dr.ellipse((0, 0, lp - 1, lp - 1), fill=255))
        img = legacy.copy(); img.putalpha(mask); img.save(f'{R}/mipmap-{d}/{name}.webp', 'WEBP', quality=92, method=6)
logo = Image.open('logo.png').convert('RGBA'); logo.crop(logo.getbbox()).resize((900, 900), Image.LANCZOS).save(f'{R}/drawable-nodpi/logo_medallion.webp', 'WEBP', quality=90, method=6)
Image.open('header_panorama.png').convert('RGB').save(f'{R}/drawable-nodpi/art_panorama.webp', 'WEBP', quality=86, method=6)
Image.open('chat_footer.png').convert('RGBA').save(f'{R}/drawable-nodpi/art_chat_footer.webp', 'WEBP', quality=90, method=6)
print('exported to', R)
PY
