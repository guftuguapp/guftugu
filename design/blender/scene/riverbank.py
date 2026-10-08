"""
Guftugu riverbank: a realistic render of the app's landscape (trees, a wide winding river with a paper
boat and stepping stones, grassy banks with small flowers, a blue sky full of clouds) for the chat-list
banner and the app icon. The owner asked for "a more realistic image" made in Blender instead of the flat
vector art. Assets: CC0 from Poly Haven (../assets, fetched by ../fetch_assets.py).

Run from scene/ (factory settings, so Blender's own setup is never read or changed):
  $BLENDER -b --factory-startup --python riverbank.py -- banner|icon OUT.png preview|final
"""
import bpy, math, os, random, sys
from mathutils import Vector, noise
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trees

argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
SHOT = argv[0] if argv else 'banner'
OUT = argv[1] if len(argv) > 1 else '/tmp/riverbank.png'
QUALITY = argv[2] if len(argv) > 2 else 'preview'
FINAL = QUALITY == 'final'
A = os.environ.get('GUFTUGU_ASSETS') or os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'assets')  # fetch_assets.py fills it

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.context.preferences.use_preferences_save = False
scene = bpy.context.scene
rnd = random.Random(11)


def smoothstep(a, b, x):
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


# ---------------------------------------------------------------- the land and the river
def river_cx(y):
    """River centre line: a gentle meander towards the camera that bends away behind the tree line."""
    x = 2.6 * math.sin(y / 24.0 + 0.4) + 1.1 * math.sin(y / 10.0 + 1.3)
    if y > 30:  # a long, graceful S-arch into the distance: the owner wants to see the river flow and curve
        x += 11.0 * math.sin((y - 30) / 42.0)
    return x


def river_hw(y, x=0.0):
    """Half-width: a wide river (the owner asked twice for it to be wider), with ragged natural banks."""
    return 3.9 + 0.5 * math.sin(y / 15.0) + 0.6 * noise.noise(Vector((x / 7.0, y / 7.0, 21.0)))


WATER_Z = -0.32


def land_height(x, y):
    h = 0.32 * noise.noise(Vector((x / 16, y / 16, 0.0))) + 0.12 * noise.noise(Vector((x / 5, y / 5, 3.0)))
    far = smoothstep(110, 240, y) * smoothstep(12, 55, abs(x - river_cx(y)))   # a flat valley along the river
    h += far * (5 + 14 * (0.5 + 0.5 * noise.noise(Vector((x / 55, y / 55, 7.0)))))
    side = smoothstep(18, 70, abs(x - river_cx(y)))
    h += side * 2.5 * (0.5 + 0.5 * noise.noise(Vector((x / 30, y / 30, 11.0))))
    return h


def terrain_height(x, y):
    d = abs(x - river_cx(y)) / river_hw(y, x)
    land = smoothstep(0.85, 1.25, d)
    bed = -1.0 - 0.35 * (1 - min(1.0, d))
    return land * land_height(x, y) + (1 - land) * bed, land


def build_terrain():
    nx, ny = 300, 330
    xs = [-90 + 180 * i / (nx - 1) for i in range(nx)]
    ys = [-14 + 340 * (j / (ny - 1)) ** 1.7 for j in range(ny)]
    verts, faces, landness = [], [], []
    for y in ys:
        for x in xs:
            z, land = terrain_height(x, y)
            verts.append((x, y, z))
            landness.append(land)
    for j in range(ny - 1):
        for i in range(nx - 1):
            a = j * nx + i
            faces.append((a, a + 1, a + nx + 1, a + nx))
    me = bpy.data.meshes.new('terrain')
    me.from_pydata(verts, [], faces)
    me.update()
    for p in me.polygons:
        p.use_smooth = True
    # attributes that drive the material and the scattering
    land_attr = me.attributes.new('land', 'FLOAT', 'POINT')
    grass = me.attributes.new('grass_density', 'FLOAT', 'POINT')
    far_grass = me.attributes.new('far_grass_density', 'FLOAT', 'POINT')
    flowers = me.attributes.new('flower_density', 'FLOAT', 'POINT')
    reeds = me.attributes.new('reed_density', 'FLOAT', 'POINT')
    forest = me.attributes.new('forest_density', 'FLOAT', 'POINT')
    k = 1.0   # previews too: the owner judges the meadow's lushness on them
    for idx, (x, y, z) in enumerate(verts):
        land = landness[idx]
        land_attr.data[idx].value = land
        in_view = smoothstep(-6.0, 0.0, 10 + 0.75 * (y + 8) - abs(x))   # roughly inside the camera's cone
        near = 1 - smoothstep(65, 95, y)
        on_land = smoothstep(0.55, 0.8, land)
        grass.data[idx].value = 34 * k * in_view * near * on_land
        # beyond it the same grass in bigger, sparser clumps (cheap at that distance), over the far meadow
        # and the hills: bare ground read as pale felt (the owner: "make it match the green")
        far_grass.data[idx].value = 4.0 * k * in_view * smoothstep(75, 100, y) * on_land
        flowers.data[idx].value = 2.2 * k * in_view * (1 - smoothstep(10, 35, y)) * smoothstep(0.9, 1.0, land)
        edge = smoothstep(0.35, 0.6, land) * (1 - smoothstep(0.75, 0.95, land))
        reeds.data[idx].value = 9 * k * in_view * (1 - smoothstep(30, 60, y)) * edge
        # a forest over the far land, behind the river's bend (instances of the two trees: cheap)
        forest.data[idx].value = 0.015 * smoothstep(72, 95, y) * smoothstep(25, 45, abs(x - river_cx(y))) * smoothstep(0.98, 1.0, land) * (1 - smoothstep(240, 320, y))
    ob = bpy.data.objects.new('terrain', me)
    scene.collection.objects.link(ob)
    return ob


def build_water():
    nx, ny = 60, 220
    verts, faces = [], []
    for j in range(ny):
        y = -14 + 340 * (j / (ny - 1)) ** 1.7
        for i in range(nx):
            x = river_cx(y) + (i / (nx - 1) * 2 - 1) * river_hw(y) * 1.5
            verts.append((x, y, WATER_Z))
    for j in range(ny - 1):
        for i in range(nx - 1):
            a = j * nx + i
            faces.append((a, a + 1, a + nx + 1, a + nx))
    me = bpy.data.meshes.new('water')
    me.from_pydata(verts, [], faces)
    ob = bpy.data.objects.new('water', me)
    scene.collection.objects.link(ob)
    return ob


# ---------------------------------------------------------------- materials
def new_material(name):
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    return m


def ground_material():
    m = new_material('ground')
    nt = m.node_tree
    n, l = nt.nodes, nt.links
    bsdf = n['Principled BSDF']
    attr = n.new('ShaderNodeAttribute'); attr.attribute_name = 'land'
    tex = n.new('ShaderNodeTexNoise'); tex.inputs['Scale'].default_value = 0.6; tex.inputs['Detail'].default_value = 8
    grass_ramp = n.new('ShaderNodeValToRGB')   # the soil under the grass: warm, like the meadow
    grass_ramp.color_ramp.elements[0].color = (0.03, 0.055, 0.008, 1)
    grass_ramp.color_ramp.elements[1].color = (0.075, 0.14, 0.018, 1)
    l.new(tex.outputs['Fac'], grass_ramp.inputs['Fac'])
    mud = n.new('ShaderNodeRGB'); mud.outputs[0].default_value = (0.034, 0.027, 0.019, 1)
    mix = n.new('ShaderNodeMix'); mix.data_type = 'RGBA'
    l.new(attr.outputs['Fac'], mix.inputs['Factor'])
    l.new(mud.outputs[0], mix.inputs['A'])
    l.new(grass_ramp.outputs['Color'], mix.inputs['B'])
    # beyond the grass the land itself is meadow, the same warm green in gentle patches, so the far hills
    # match the grass (the owner: "if its a hill, make it match the green")
    patches = n.new('ShaderNodeTexNoise'); patches.inputs['Scale'].default_value = 7; patches.inputs['Detail'].default_value = 6
    far_ramp = n.new('ShaderNodeValToRGB')
    far_ramp.color_ramp.elements[0].color = (0.07, 0.11, 0.012, 1)
    far_ramp.color_ramp.elements[1].color = (0.13, 0.18, 0.02, 1)
    l.new(patches.outputs['Fac'], far_ramp.inputs['Fac'])
    cam = n.new('ShaderNodeCameraData')
    fr = n.new('ShaderNodeMapRange'); fr.inputs['From Min'].default_value = 45; fr.inputs['From Max'].default_value = 140
    l.new(cam.outputs['View Distance'], fr.inputs['Value'])
    on_land = n.new('ShaderNodeMath'); on_land.operation = 'MULTIPLY'   # never on the river bed
    l.new(fr.outputs['Result'], on_land.inputs[0]); l.new(attr.outputs['Fac'], on_land.inputs[1])
    meadow = n.new('ShaderNodeMix'); meadow.data_type = 'RGBA'
    l.new(on_land.outputs['Value'], meadow.inputs['Factor'])
    l.new(mix.outputs['Result'], meadow.inputs['A'])
    l.new(far_ramp.outputs['Color'], meadow.inputs['B'])
    # only a little aerial perspective, towards a sunlit yellow-green: blue haze turned the hills grey
    mr = n.new('ShaderNodeMapRange'); mr.inputs['From Min'].default_value = 90; mr.inputs['From Max'].default_value = 420
    mr.inputs['To Max'].default_value = 0.14
    l.new(cam.outputs['View Distance'], mr.inputs['Value'])
    haze = n.new('ShaderNodeMix'); haze.data_type = 'RGBA'
    haze.inputs['B'].default_value = (0.42, 0.5, 0.26, 1)
    l.new(mr.outputs['Result'], haze.inputs['Factor'])
    l.new(meadow.outputs['Result'], haze.inputs['A'])
    l.new(haze.outputs['Result'], bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = 0.95
    if 'Specular IOR Level' in bsdf.inputs:   # no grey sheen where the low sun grazes the far slopes
        bsdf.inputs['Specular IOR Level'].default_value = 0.08
    bump = n.new('ShaderNodeBump'); bump.inputs['Strength'].default_value = 0.3
    t2 = n.new('ShaderNodeTexNoise'); t2.inputs['Scale'].default_value = 25
    l.new(t2.outputs['Fac'], bump.inputs['Height'])
    l.new(bump.outputs['Normal'], bsdf.inputs['Normal'])
    return m


def water_material():
    m = new_material('water')
    nt = m.node_tree
    n, l = nt.nodes, nt.links
    bsdf = n['Principled BSDF']
    bsdf.inputs['Base Color'].default_value = (0.75, 0.88, 0.9, 1)
    bsdf.inputs['Roughness'].default_value = 0.03
    bsdf.inputs['IOR'].default_value = 1.333
    bsdf.inputs['Transmission Weight'].default_value = 1.0
    coord = n.new('ShaderNodeTexCoord')
    mp = n.new('ShaderNodeMapping'); mp.inputs['Scale'].default_value = (1.0, 0.35, 1.0)
    l.new(coord.outputs['Object'], mp.inputs['Vector'])
    ripple = n.new('ShaderNodeTexNoise'); ripple.inputs['Scale'].default_value = 3.5; ripple.inputs['Detail'].default_value = 10
    l.new(mp.outputs['Vector'], ripple.inputs['Vector'])
    bump = n.new('ShaderNodeBump'); bump.inputs['Strength'].default_value = 0.18; bump.inputs['Distance'].default_value = 0.05
    l.new(ripple.outputs['Fac'], bump.inputs['Height'])
    l.new(bump.outputs['Normal'], bsdf.inputs['Normal'])
    vol = n.new('ShaderNodeVolumeAbsorption')
    vol.inputs['Color'].default_value = (0.35, 0.55, 0.5, 1)
    vol.inputs['Density'].default_value = 0.9
    l.new(vol.outputs['Volume'], n['Material Output'].inputs['Volume'])
    return m


def paper_material():
    m = new_material('paper')
    b = m.node_tree.nodes['Principled BSDF']
    b.inputs['Base Color'].default_value = (0.95, 0.95, 0.93, 1)
    b.inputs['Roughness'].default_value = 0.6
    return m


# ---------------------------------------------------------------- assets
def append(blend, names):
    with bpy.data.libraries.load(f'{A}/{blend}', link=False) as (src, dst):
        dst.objects = [n for n in src.objects if n in names]
    out = []
    for ob in dst.objects:
        if ob is not None:
            out.append(ob)
    return out


def hidden_collection(name, objects):
    c = bpy.data.collections.new(name)
    scene.collection.children.link(c)
    for ob in objects:
        c.objects.link(ob)
        ob.location = (0, 0, 0)
    c.hide_render = True
    c.hide_viewport = True
    return c


def scatter(target, name, coll, density_attr, scale_range, seed, reset=True):
    """Geometry Nodes: scatter the collection's objects over the target where `density_attr` says."""
    ng = bpy.data.node_groups.new(name, 'GeometryNodeTree')
    ng.interface.new_socket('Geometry', in_out='INPUT', socket_type='NodeSocketGeometry')
    ng.interface.new_socket('Geometry', in_out='OUTPUT', socket_type='NodeSocketGeometry')
    n, l = ng.nodes, ng.links
    gi, go = n.new('NodeGroupInput'), n.new('NodeGroupOutput')
    dens = n.new('GeometryNodeInputNamedAttribute'); dens.data_type = 'FLOAT'; dens.inputs['Name'].default_value = density_attr
    dist = n.new('GeometryNodeDistributePointsOnFaces'); dist.distribute_method = 'RANDOM'
    dist.inputs['Seed'].default_value = seed
    l.new(gi.outputs[0], dist.inputs['Mesh'])
    l.new(dens.outputs['Attribute'], dist.inputs['Density'])
    ci = n.new('GeometryNodeCollectionInfo')
    ci.inputs['Collection'].default_value = coll
    ci.inputs['Separate Children'].default_value = True
    ci.inputs['Reset Children'].default_value = reset
    iop = n.new('GeometryNodeInstanceOnPoints')
    iop.inputs['Pick Instance'].default_value = True
    l.new(dist.outputs['Points'], iop.inputs['Points'])
    l.new(ci.outputs[0], iop.inputs['Instance'])
    idx = n.new('FunctionNodeRandomValue'); idx.data_type = 'INT'
    idx.inputs['Min'].default_value = 0; idx.inputs['Max'].default_value = 99; idx.inputs['Seed'].default_value = seed + 1
    l.new(idx.outputs['Value'], iop.inputs['Instance Index'])
    rot = n.new('FunctionNodeRandomValue'); rot.data_type = 'FLOAT_VECTOR'
    rot.inputs['Min'].default_value = (-0.12, -0.12, 0.0); rot.inputs['Max'].default_value = (0.12, 0.12, 6.283)
    rot.inputs['Seed'].default_value = seed + 2
    l.new(rot.outputs['Value'], iop.inputs['Rotation'])
    sc = n.new('FunctionNodeRandomValue'); sc.data_type = 'FLOAT'
    sc.inputs['Min'].default_value = scale_range[0]; sc.inputs['Max'].default_value = scale_range[1]; sc.inputs['Seed'].default_value = seed + 3
    l.new(sc.outputs['Value'], iop.inputs['Scale'])
    join = n.new('GeometryNodeJoinGeometry')
    l.new(gi.outputs[0], join.inputs['Geometry'])
    l.new(iop.outputs['Instances'], join.inputs['Geometry'])
    l.new(join.outputs['Geometry'], go.inputs[0])
    mod = target.modifiers.new(name, 'NODES')
    mod.node_group = ng
    return mod


def tint(mat, sat=1.0, val=1.0, hue_jitter=0.0):
    """Adjust an appended material's colour in this render only (the asset files stay as downloaded):
    saturation/value after its colour textures, and an optional per-object hue jitter so copies differ."""
    nt = mat.node_tree
    n, l = nt.nodes, nt.links
    for tex in [x for x in n if x.type == 'TEX_IMAGE' and x.image and any(k in x.image.name.lower() for k in ('diff', 'albedo', 'col'))]:
        links = list(tex.outputs['Color'].links)
        if not links:
            continue
        hsv = n.new('ShaderNodeHueSaturation')
        hsv.inputs['Saturation'].default_value = sat
        hsv.inputs['Value'].default_value = val
        l.new(tex.outputs['Color'], hsv.inputs['Color'])
        if hue_jitter:
            oi = n.new('ShaderNodeObjectInfo')
            mr = n.new('ShaderNodeMapRange')
            mr.inputs['To Min'].default_value = 0.5 - hue_jitter
            mr.inputs['To Max'].default_value = 0.5 + hue_jitter
            l.new(oi.outputs['Random'], mr.inputs['Value'])
            l.new(mr.outputs['Result'], hsv.inputs['Hue'])
            vr = n.new('ShaderNodeMapRange')
            vr.inputs['To Min'].default_value = val * 0.88
            vr.inputs['To Max'].default_value = val * 1.1
            l.new(oi.outputs['Random'], vr.inputs['Value'])
            l.new(vr.outputs['Result'], hsv.inputs['Value'])
        for lk in links:
            l.new(hsv.outputs['Color'], lk.to_socket)


def place(ob_src, loc, rz, scale):
    ob = ob_src.copy()  # shares mesh data: an instance, not a second copy in memory
    ob.location = loc
    ob.rotation_euler = (0, 0, rz)
    ob.scale = scale
    scene.collection.objects.link(ob)
    return ob


def paper_boat():
    """A folded paper boat: a V-shaped hull with pointed ends and a tent-shaped sail, ~40 cm long."""
    v = [(-0.20, 0.0, 0.07), (0.20, 0.0, 0.07),                    # 0,1 bow and stern tips
         (-0.10, -0.05, 0.07), (0.10, -0.05, 0.07),                # 2,3 near gunwale
         (-0.10, 0.05, 0.07), (0.10, 0.05, 0.07),                  # 4,5 far gunwale
         (-0.10, 0.0, 0.0), (0.10, 0.0, 0.0),                      # 6,7 keel
         (-0.10, -0.012, 0.07), (0.10, -0.012, 0.07),              # 8,9 sail base, near
         (-0.10, 0.012, 0.07), (0.10, 0.012, 0.07),                # 10,11 sail base, far
         (0.0, 0.0, 0.21)]                                         # 12 sail apex
    f = [(6, 7, 3, 2), (0, 6, 2), (7, 1, 3),                       # near side of the hull
         (7, 6, 4, 5), (6, 0, 4), (1, 7, 5),                       # far side
         (8, 9, 12), (11, 10, 12)]                                 # the two faces of the sail
    me = bpy.data.meshes.new('boat')
    me.from_pydata(v, [], f)
    ob = bpy.data.objects.new('boat', me)
    ob.data.materials.append(paper_material())
    sol = ob.modifiers.new('thick', 'SOLIDIFY'); sol.thickness = 0.003
    scene.collection.objects.link(ob)
    return ob


# ---------------------------------------------------------------- build
terrain = build_terrain()
terrain.data.materials.append(ground_material())
water = build_water()
water.data.materials.append(water_material())

grass_objs = append('grass_medium_02/grass_medium_02_2k.blend', {f'grass_medium_02_{c}' for c in 'abcde'})
grass_coll = hidden_collection('grass', grass_objs)
flower_objs = append('celandine_01/celandine_01_2k.blend', {f'celandine_01_{c}_LOD1' for c in 'abcde'})
flower_coll = hidden_collection('flowers', flower_objs)
reed_objs = []
for g in grass_objs:  # reeds: the same clumps drawn tall and slender at the water's edge
    r = g.copy(); r.scale = (0.7, 0.7, 3.4); reed_objs.append(r)
reed_coll = hidden_collection('reeds', reed_objs)
for r in reed_objs:
    r.scale = (0.7, 0.7, 3.4)
scatter(terrain, 'grass', grass_coll, 'grass_density', (1.3, 2.4), 3)
scatter(terrain, 'far_grass', grass_coll, 'far_grass_density', (2.4, 3.8), 31)
scatter(terrain, 'flowers', flower_coll, 'flower_density', (1.4, 2.2), 9)
scatter(terrain, 'reeds', reed_coll, 'reed_density', (0.9, 1.5), 17, reset=False)

island = append('island_tree_01/island_tree_01_2k.blend', {'island_tree_01_LOD0', 'island_tree_01_leaves_a_LOD0', 'island_tree_01_leaves_b_LOD0', 'island_tree_01_leaves_c_LOD0'})
leaf_objs = [o for o in island if 'leaves' in o.name]
bark = next(m for o in island if o.name == 'island_tree_01_LOD0' for m in o.data.materials if m and m.name == 'island_tree_01')
rocks = append('rock_moss_set_01/rock_moss_set_01_2k.blend', {f'rock_moss_set_01_rock0{i}' for i in range(1, 7)})
hidden_collection('sources', [o for o in island if 'leaves' not in o.name] + rocks)
leaf_coll = hidden_collection('leaf_clusters', leaf_objs)
for m in {m for ob in grass_objs for m in ob.data.materials if m}:
    # less backlit glow and sheen (they made the blades look frosty), richer green
    for g in [x for x in m.node_tree.nodes if x.type == 'GROUP']:
        for name, v in (('Translucency', 0.18), ('Specular', 0.06), ('Hue', 0.545), ('Saturation', 1.75), ('Value', 0.95)):
            if name in g.inputs and not g.inputs[name].is_linked:
                g.inputs[name].default_value = v
for m in {m for ob in leaf_objs for m in ob.data.materials if m and 'leaves' in m.name}:
    tint(m, sat=1.3, val=1.0, hue_jitter=0.025)
variants, _ = trees.build_variants(scene, leaf_coll, bark, count=6, seed=100, density=20.0)
# the far forest: low, full canopies (long bare trunks in rows read as a plantation)
forest_variants, forest_holder = trees.build_variants(scene, leaf_coll, bark, count=4, seed=300, density=14.0, fork_range=(0.16, 0.24), name='forest_variants')
scatter(terrain, 'forest', forest_holder, 'forest_density', (0.8, 1.3), 23)


def on_land(x, y, margin=2.0):
    return abs(x - river_cx(y)) > river_hw(y, x) + margin


def tree_at(x, y, big=1.0):
    s = rnd.uniform(0.62, 1.08) * big
    trees.place_tree(scene, rnd.choice(variants), (x, y, terrain_height(x, y)[0] - 0.1), rnd.uniform(0, 6.283), (s, s, s * rnd.uniform(1.0, 1.12)))


# groves on both banks (2-4 trees each), taller than wide (the owner's taste)
# groves on the sides, further out with distance: the centre stays open for the river's flow and arch
groves = [(6, -1, 10), (9, 1, 9), (14, -1, 16), (18, 1, 14), (28, -1, 13), (34, 1, 16), (46, -1, 19),
          (52, 1, 21), (64, -1, 23), (70, 1, 25), (84, -1, 28), (92, 1, 30)]
if SHOT == 'banner':
    groves += [(24, 1, 23), (40, -1, 27), (58, 1, 31), (76, -1, 34)]
for (y0, side, off) in groves:
    cx = river_cx(y0) + side * (river_hw(y0) + off + rnd.uniform(-2.0, 2.0))
    for _ in range(rnd.randint(2, 3)):
        x, y = cx + rnd.uniform(-4.0, 4.0), y0 + rnd.uniform(-3.5, 3.5)
        if on_land(x, y, 5.0):
            tree_at(x, y)
            if rnd.random() < 0.6:
                # bushes used to stand here; the owner wants none. The random draws stay so that every tree
                # keeps the place the owner approved ("Magnificent")
                rnd.uniform(-2.5, 2.5); rnd.uniform(-2.5, 2.5); rnd.uniform(0.8, 1.4); rnd.randrange(4); rnd.uniform(0, 6.283)
# no dark tree reflections in the river (the owner: the water should mirror the sky and the sun)
for coll in [c for c in bpy.data.collections if c.name.startswith(('tree_variants', 'forest_variants', 'leaf_clusters'))]:
    for ob in coll.all_objects:
        ob.visible_glossy = False

# stepping stones near the right bank, rocks along both banks
for i, y in enumerate((3.5, 5.0, 6.6)):
    x = river_cx(y) + river_hw(y) * (0.55 - 0.22 * i)
    s = rnd.uniform(0.17, 0.22)
    place(rnd.choice(rocks), (x, y, WATER_Z - 0.12), rnd.uniform(0, 6.283), (s, s, s * 0.8))
for y in (2, 9, 13, 17, 22, 28, 35):
    for side in (-1, 1):
        x = river_cx(y) + side * (river_hw(y, river_cx(y) + side * river_hw(y)) * rnd.uniform(0.98, 1.12))
        s = rnd.uniform(0.2, 0.4)
        place(rnd.choice(rocks), (x, y, terrain_height(x, y)[0] - 0.22), rnd.uniform(0, 6.283), (s, s, s * 0.7))

boat = paper_boat()
by = 9.0 if SHOT == 'banner' else 6.0
boat.location = (river_cx(by) - 0.9, by, WATER_Z - 0.02)
boat.rotation_euler = (0, 0, math.radians(24))
boat.scale = (2.3, 2.3, 2.3)

# ---------------------------------------------------------------- sky, sun, camera, render
world = bpy.data.worlds.new('sky')
scene.world = world
try:
    world.use_nodes = True
except Exception:
    pass
wn, wl = world.node_tree.nodes, world.node_tree.links
env = wn.new('ShaderNodeTexEnvironment')
env.image = bpy.data.images.load(f'{A}/hdri/kloofendal_48d_partly_cloudy_puresky_4k.exr')
wcoord = wn.new('ShaderNodeTexCoord'); wmap = wn.new('ShaderNodeMapping')
wmap.inputs['Rotation'].default_value = (0, 0, math.radians(200))
wl.new(wcoord.outputs['Generated'], wmap.inputs['Vector'])
wl.new(wmap.outputs['Vector'], env.inputs['Vector'])
# the camera sees the photographed sky as it is; light and reflections use it with its (high) sun clipped,
# so the only direct sunlight is the low sun placed in view below: shadows match the sun you see
sep = wn.new('ShaderNodeSeparateColor'); comb = wn.new('ShaderNodeCombineColor')
wl.new(env.outputs['Color'], sep.inputs['Color'])
for ch in ('Red', 'Green', 'Blue'):
    mn = wn.new('ShaderNodeMath'); mn.operation = 'MINIMUM'; mn.inputs[1].default_value = 1.6
    wl.new(sep.outputs[ch], mn.inputs[0]); wl.new(mn.outputs[0], comb.inputs[ch])
lpath = wn.new('ShaderNodeLightPath')
pick = wn.new('ShaderNodeMix'); pick.data_type = 'RGBA'
wl.new(lpath.outputs['Is Camera Ray'], pick.inputs['Factor'])
boost = wn.new('ShaderNodeVectorMath'); boost.operation = 'SCALE'; boost.inputs['Scale'].default_value = 1.6  # more sky fill light
wl.new(comb.outputs['Color'], boost.inputs[0])
wl.new(boost.outputs['Vector'], pick.inputs['A']); wl.new(env.outputs['Color'], pick.inputs['B'])
wl.new(pick.outputs['Result'], wn['Background'].inputs['Color'])
wn['Background'].inputs['Strength'].default_value = 1.1
# afternoon sun from the left and slightly behind the trees: sparkle on the water, real shadows
sun_az, sun_el = (math.radians(76), math.radians(8.5)) if SHOT == 'banner' else (math.radians(88), math.radians(15))
sun_dir = Vector((math.cos(sun_el) * math.cos(sun_az), math.cos(sun_el) * math.sin(sun_az), math.sin(sun_el)))
sun_data = bpy.data.lights.new('sun', 'SUN'); sun_data.energy = 5.0; sun_data.angle = math.radians(0.8)
sun_data.color = (1.0, 0.87, 0.68)  # a low, golden afternoon sun
sun = bpy.data.objects.new('sun', sun_data); scene.collection.objects.link(sun)
sun.rotation_euler = (-sun_dir).to_track_quat('-Z', 'Y').to_euler()   # light travels away from the visible sun
# a soft fill from behind the camera (open sky / a photographer's reflector): lifts the shaded sides of the
# backlit trees without a second hard shadow
fill_data = bpy.data.lights.new('fill', 'SUN'); fill_data.energy = 1.1; fill_data.angle = math.radians(25)
fill_data.color = (0.93, 0.97, 1.0)
fill = bpy.data.objects.new('fill', fill_data); scene.collection.objects.link(fill)
fill.rotation_euler = Vector((0.1, 0.9, -0.42)).to_track_quat('-Z', 'Y').to_euler()
bpy.ops.mesh.primitive_uv_sphere_add(segments=48, ring_count=24, radius=3000 * math.tan(math.radians(0.55)), location=tuple(3000 * sun_dir))
disc = bpy.context.object
dm = new_material('sun_disc'); dn = dm.node_tree.nodes
em = dn.new('ShaderNodeEmission'); em.inputs['Color'].default_value = (1.0, 0.94, 0.82, 1); em.inputs['Strength'].default_value = 400
dm.node_tree.links.new(em.outputs[0], dn['Material Output'].inputs['Surface'])
disc.data.materials.append(dm)
for attr in ('visible_diffuse', 'visible_shadow', 'visible_transmission', 'visible_volume_scatter'):
    setattr(disc, attr, False)   # seen by the camera and glinting on the water; the lamp does the lighting

cam_data = bpy.data.cameras.new('cam'); cam_data.clip_end = 5000
cam = bpy.data.objects.new('cam', cam_data)
scene.collection.objects.link(cam)
scene.camera = cam
if SHOT == 'banner':
    cam_data.lens = 24
    loc = Vector((river_cx(-8) + 0.5, -8.0, 2.6))
    target = Vector((river_cx(60) + 6.0, 60.0, 3.0))
    scene.render.resolution_x, scene.render.resolution_y = 1440, 560
else:
    # standing on the right bank: grass in front, the river curving through, trees and the sun beyond
    cam_data.lens = 26
    loc = Vector((river_cx(-3) + 7.5, -3.0, 2.1))
    target = Vector((river_cx(45) - 3.0, 45.0, 4.0))
    scene.render.resolution_x, scene.render.resolution_y = 1080, 1080
cam.location = loc
cam.rotation_euler = (target - loc).to_track_quat('-Z', 'Y').to_euler()
if SHOT == 'icon':
    # the boat where it reads in the square picture: on the water, left of centre, in the lower third
    from bpy_extras.object_utils import world_to_camera_view
    bpy.context.view_layer.update()
    best = None
    for yy in [i * 0.5 for i in range(80)]:
        for dx in (-1.5, -0.75, 0.0, 0.75, 1.5):
            p = Vector((river_cx(yy) + dx, yy, WATER_Z - 0.02))
            px, py, _ = world_to_camera_view(scene, cam, p)
            err = (px - 0.3) ** 2 + (py - 0.3) ** 2
            if best is None or err < best[0]:
                best = (err, p)
    boat.location = best[1]
    print('icon boat at', tuple(round(c, 2) for c in best[1]))

scene.render.engine = 'CYCLES'
scene.cycles.device = 'CPU'
scene.cycles.samples = 128 if FINAL else 24
scene.cycles.use_adaptive_sampling = True
scene.cycles.adaptive_threshold = 0.02 if FINAL else 0.05
scene.cycles.use_denoising = True
scene.cycles.denoiser = 'OPENIMAGEDENOISE'
scene.cycles.transparent_max_bounces = 24
scene.cycles.max_bounces = 8
scene.render.resolution_percentage = 100 if FINAL else 50   # final: the app's own sizes
scene.view_settings.view_transform = 'AgX'
scene.view_settings.look = 'AgX - Punchy'
scene.view_settings.exposure = 0.55
scene.render.image_settings.file_format = 'PNG'
if len(argv) > 3 and argv[3].startswith('border='):  # check a region at final quality without a full render
    x0, y0, x1, y1 = map(float, argv[3][7:].split(','))
    scene.render.use_border = True; scene.render.use_crop_to_border = True
    scene.render.border_min_x, scene.render.border_min_y, scene.render.border_max_x, scene.render.border_max_y = x0, y0, x1, y1
scene.render.filepath = OUT
bpy.ops.render.render(write_still=True)
from bpy_extras.object_utils import world_to_camera_view
sx, sy, sz = world_to_camera_view(scene, cam, disc.location)
open(OUT + '.sun', 'w').write(f'{sx:.4f} {1 - sy:.4f} {1 if sz > 0 else 0}')
print('rendered', OUT, 'sun at', round(sx, 3), round(1 - sy, 3))
