"""
Guftugu icon: the rendered riverbank (riverbank.py, square shot) set in a real gilded frame. Square moulding
(outer lip, cove, bead, sight edge) with a row of gold pearls, rosettes with blue cabochons at the corners and
diamond cartouches mid-edge; hammered gold lit by a warm studio HDRI. Transparent outside the frame so it
drops straight into the adaptive-icon foreground.

The frame is rendered alone: the opening is a shadow catcher, so the render holds the frame plus the shadow it
casts into the opening, and frame_compose.py lays the untouched picture underneath (passing the picture through
the renderer would tone-map it a second time and wash it out). OUT.opening holds the opening's box.

  $BLENDER -b --factory-startup --python frame.py -- OUT.png preview|final
  python3 frame_compose.py OUT.png PICTURE.png ICON.png
"""
import bpy, math, os, random, sys
from mathutils import Vector

argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
OUT = argv[0]
FINAL = len(argv) > 1 and argv[1] == 'final'
A = os.environ.get('GUFTUGU_ASSETS') or os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'assets')  # fetch_assets.py fills it

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.context.preferences.use_preferences_save = False
scene = bpy.context.scene
rnd = random.Random(5)

HO = 1.0      # outer half-size
BW = 0.17     # band width
HI = HO - BW  # sight (picture) half-size


def material(name):
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    return m


def gold_material():
    m = material('gold')
    n, l = m.node_tree.nodes, m.node_tree.links
    b = n['Principled BSDF']
    b.inputs['Base Color'].default_value = (1.0, 0.64, 0.22, 1)   # deep yellow gold
    b.inputs['Metallic'].default_value = 1.0
    rough = n.new('ShaderNodeTexNoise'); rough.inputs['Scale'].default_value = 40
    rr = n.new('ShaderNodeMapRange'); rr.inputs['To Min'].default_value = 0.16; rr.inputs['To Max'].default_value = 0.3
    l.new(rough.outputs['Fac'], rr.inputs['Value']); l.new(rr.outputs['Result'], b.inputs['Roughness'])
    hammer = n.new('ShaderNodeTexVoronoi'); hammer.inputs['Scale'].default_value = 90
    bump = n.new('ShaderNodeBump'); bump.inputs['Strength'].default_value = 0.08
    l.new(hammer.outputs['Distance'], bump.inputs['Height']); l.new(bump.outputs['Normal'], b.inputs['Normal'])
    return m


def gem_material():
    m = material('gem')
    b = m.node_tree.nodes['Principled BSDF']
    b.inputs['Base Color'].default_value = (0.03, 0.14, 0.7, 1)   # sapphire
    b.inputs['Roughness'].default_value = 0.02
    b.inputs['IOR'].default_value = 1.77
    b.inputs['Transmission Weight'].default_value = 1.0
    return m


GOLD, GEM = gold_material(), gem_material()


def link(ob, mat):
    scene.collection.objects.link(ob)
    if ob.type == 'MESH':
        ob.data.materials.append(mat)
    return ob


# ---- moulding: concentric square rings, one per profile point (u inwards from the outer edge, v up)
profile = [(0.0, 0.0), (0.0, 0.055), (0.004, 0.07), (0.013, 0.08), (0.025, 0.083), (0.037, 0.078),
           (0.047, 0.066), (0.058, 0.058), (0.072, 0.056), (0.085, 0.061), (0.095, 0.07), (0.104, 0.079),
           (0.113, 0.083), (0.122, 0.08), (0.13, 0.071), (0.138, 0.06), (0.15, 0.052), (0.16, 0.042),
           (0.166, 0.03), (0.17, 0.016), (0.17, -0.01)]
verts, faces = [], []
for (u, v) in profile:
    h = HO - u
    verts += [(-h, -h, v), (h, -h, v), (h, h, v), (-h, h, v)]
for k in range(len(profile) - 1):
    a, b = 4 * k, 4 * (k + 1)
    for i in range(4):
        j = (i + 1) % 4
        faces.append((a + i, a + j, b + j, b + i))
me = bpy.data.meshes.new('moulding'); me.from_pydata(verts, [], faces)
for p in me.polygons:
    p.use_smooth = True
mould = link(bpy.data.objects.new('moulding', me), GOLD)
mould.modifiers.new('ws', 'WEIGHTED_NORMAL')

# ---- a row of gold pearls along the sight edge
bpy.ops.mesh.primitive_uv_sphere_add(segments=14, ring_count=8, radius=0.0085)
pearl = bpy.context.object; pearl.data.materials.append(GOLD)
for p in pearl.data.polygons:
    p.use_smooth = True
u_pearl, v_pearl = 0.152, 0.052
h = HO - u_pearl
step = 0.025
n = int(2 * h / step)
for side in range(4):
    for i in range(n):
        t = -h + (i + 0.5) * (2 * h / n)
        x, y = [(t, -h), (h, t), (-t, h), (-h, -t)][side]
        c = pearl.copy(); c.location = (x, y, v_pearl); scene.collection.objects.link(c)
bpy.data.objects.remove(pearl)


def rosette(cx, cy, r):
    """Eight petals around a blue cabochon in a gold bezel."""
    bpy.ops.mesh.primitive_uv_sphere_add(segments=20, ring_count=10, radius=1)
    petal = bpy.context.object; petal.data.materials.append(GOLD)
    for p in petal.data.polygons:
        p.use_smooth = True
    for k in range(8):
        a = k * math.pi / 4 + math.pi / 8
        c = petal.copy()
        c.location = (cx + math.cos(a) * r * 0.55, cy + math.sin(a) * r * 0.55, 0.075)
        c.rotation_euler = (0, 0, a)
        c.scale = (r * 0.5, r * 0.22, r * 0.16)
        scene.collection.objects.link(c)
    bpy.data.objects.remove(petal)
    bpy.ops.mesh.primitive_torus_add(major_radius=r * 0.42, minor_radius=r * 0.08, location=(cx, cy, 0.09))
    bez = bpy.context.object; bez.data.materials.append(GOLD)
    bpy.ops.mesh.primitive_uv_sphere_add(segments=32, ring_count=16, radius=r * 0.38, location=(cx, cy, 0.085))
    gem = bpy.context.object; gem.scale = (1, 1, 0.62); gem.data.materials.append(GEM)
    for p in gem.data.polygons:
        p.use_smooth = True


def cartouche(cx, cy, rot, r):
    """A diamond-shaped ornament with a small blue stone, set mid-edge."""
    v = [(r, 0, 0.065), (0, r * 0.55, 0.065), (-r, 0, 0.065), (0, -r * 0.55, 0.065), (0, 0, 0.11)]
    f = [(0, 1, 4), (1, 2, 4), (2, 3, 4), (3, 0, 4)]
    me = bpy.data.meshes.new('cart'); me.from_pydata(v, [], f)
    ob = link(bpy.data.objects.new('cart', me), GOLD)
    ob.location = (cx, cy, 0); ob.rotation_euler = (0, 0, rot)
    bev = ob.modifiers.new('bev', 'BEVEL'); bev.width = 0.006; bev.segments = 3
    bpy.ops.mesh.primitive_uv_sphere_add(segments=24, ring_count=12, radius=r * 0.2, location=(cx, cy, 0.105))
    gem = bpy.context.object; gem.scale = (1, 1, 0.6); gem.data.materials.append(GEM)


rc = HO - BW / 2
for sx in (-1, 1):
    for sy in (-1, 1):
        rosette(sx * rc, sy * rc, 0.105)
for (cx, cy, rot) in ((0, -rc, 0), (0, rc, 0), (-rc, 0, math.pi / 2), (rc, 0, math.pi / 2)):
    cartouche(cx, cy, rot, 0.085)

# ---- the opening, recessed a little behind the sight edge: a shadow catcher (see the docstring)
PIC = HI + 0.01
bpy.ops.mesh.primitive_plane_add(size=2 * PIC, location=(0, 0, -0.004))
pic = bpy.context.object
pic.is_shadow_catcher = True

# ---- light, camera, render
world = bpy.data.worlds.new('studio'); scene.world = world
try:
    world.use_nodes = True
except Exception:
    pass
wn, wl = world.node_tree.nodes, world.node_tree.links
env = wn.new('ShaderNodeTexEnvironment'); env.image = bpy.data.images.load(f'{A}/hdri/brown_photostudio_02_4k.exr')
wmap = wn.new('ShaderNodeMapping'); wmap.inputs['Rotation'].default_value = (0, 0, math.radians(120))
wco = wn.new('ShaderNodeTexCoord')
wl.new(wco.outputs['Generated'], wmap.inputs['Vector']); wl.new(wmap.outputs['Vector'], env.inputs['Vector'])
wl.new(env.outputs['Color'], wn['Background'].inputs['Color'])
wn['Background'].inputs['Strength'].default_value = 1.2

key = bpy.data.lights.new('key', 'AREA'); key.energy = 180; key.size = 2.5; key.color = (1.0, 0.92, 0.8)
kob = bpy.data.objects.new('key', key); scene.collection.objects.link(kob)
kob.location = (-2.2, 2.6, 3.2); kob.rotation_euler = (Vector((0, 0, 0)) - kob.location).to_track_quat('-Z', 'Y').to_euler()

cam_data = bpy.data.cameras.new('cam'); cam_data.type = 'ORTHO'; cam_data.ortho_scale = 2.04
cam = bpy.data.objects.new('cam', cam_data); scene.collection.objects.link(cam); scene.camera = cam
cam.location = (0, 0, 6)

scene.render.engine = 'CYCLES'
scene.cycles.device = 'CPU'
scene.cycles.samples = 128 if FINAL else 32
scene.cycles.use_denoising = True
scene.cycles.denoiser = 'OPENIMAGEDENOISE'
scene.render.film_transparent = True
scene.render.resolution_x = scene.render.resolution_y = 1080
scene.render.resolution_percentage = 100 if FINAL else 50
scene.view_settings.view_transform = 'AgX'
scene.view_settings.look = 'AgX - Punchy'
scene.render.image_settings.file_format = 'PNG'
scene.render.image_settings.color_mode = 'RGBA'
scene.render.filepath = OUT
bpy.ops.render.render(write_still=True)
half = cam_data.ortho_scale / 2
lo, hi = (half - PIC) / (2 * half), (half + PIC) / (2 * half)
open(OUT + '.opening', 'w').write(f'{lo:.6f} {lo:.6f} {hi:.6f} {hi:.6f}')
print('rendered', OUT)
