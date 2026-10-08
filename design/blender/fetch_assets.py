"""Download the CC0 Poly Haven assets the Guftugu renders use (blend + textures at 2k, the sky at 4k)."""
import hashlib, json, os, sys, urllib.request
UA = {'User-Agent': 'guftugu-art/1.0 (personal app art; CC0 assets)'}
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'assets')

def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return json.load(r)

def fetch(url, dest, md5):
    if os.path.exists(dest) and hashlib.md5(open(dest, 'rb').read()).hexdigest() == md5:
        return 'cached'
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300) as r, open(dest + '.part', 'wb') as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    if hashlib.md5(open(dest + '.part', 'rb').read()).hexdigest() != md5:
        raise SystemExit(f'checksum mismatch: {url}')
    os.replace(dest + '.part', dest)
    return 'ok'

for a in sys.argv[1:]:
    d = get_json(f'https://api.polyhaven.com/files/{a}')
    if 'blend' in d:
        e = d['blend']['2k']['blend']
        base = os.path.join(ROOT, a)
        print(a, fetch(e['url'], os.path.join(base, os.path.basename(e['url'])), e['md5']), flush=True)
        for rel, t in e.get('include', {}).items():
            fetch(t['url'], os.path.join(base, rel), t['md5'])
    else:
        e = d['hdri']['4k']['exr']
        print(a, fetch(e['url'], os.path.join(ROOT, 'hdri', os.path.basename(e['url'])), e['md5']), flush=True)
print('done')
