# Riverbank renders (Blender)

The realistic app icon and chat-list banner. The approved results are `../rendered/logo.png` and
`../rendered/banner.png`; `../export.sh` turns them into the app's launcher icon, medallion and header art.
Re-render only when the scene changes. See *Rendering the riverbank* in `docs/DESIGN.md`.

Needs Blender 5.2 (Cycles runs on the CPU) and Python 3 with Pillow and NumPy.

```bash
python3 fetch_assets.py                 # CC0 assets from Poly Haven into assets/ (about 1 GB, not committed)
cd scene
B="blender -b --factory-startup"        # factory settings: never reads or changes your own Blender setup
$B --python riverbank.py -- banner out/banner.png preview     # about 3-4 minutes; 'final' about 30 minutes
$B --python riverbank.py -- icon out/icon.png preview
python3 glow.py out/banner.png out/banner_glow.png            # the warm light around the sun
python3 glow.py out/icon.png out/icon_glow.png
$B --python frame.py -- out/frame.png final                    # the gilded frame, rendered alone
python3 frame_compose.py out/frame.png out/icon_glow.png out/logo.png
cp out/logo.png ../../rendered/logo.png; cp out/banner_glow.png ../../rendered/banner.png
cd ../.. && bash export.sh
```

Set `GUFTUGU_ASSETS` to use an asset folder somewhere else.
