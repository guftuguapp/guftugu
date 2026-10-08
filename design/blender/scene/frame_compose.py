"""Lay the rendered riverbank picture under the rendered gilded frame (see frame.py).
  python3 frame_compose.py FRAME.png PICTURE.png OUT.png"""
import sys
from PIL import Image
frame_path, picture_path, out_path = sys.argv[1:4]
frame = Image.open(frame_path).convert('RGBA')
x0, y0, x1, y1 = map(float, open(frame_path + '.opening').read().split())
W, H = frame.size
box = (round(x0 * W), round(y0 * H), round(x1 * W), round(y1 * H))
pic = Image.open(picture_path).convert('RGBA').resize((box[2] - box[0], box[3] - box[1]), Image.LANCZOS)
out = Image.new('RGBA', frame.size, (0, 0, 0, 0))
out.paste(pic, box[:2])
out.alpha_composite(frame)
out.save(out_path)
print('framed ->', out_path, 'opening', box)
