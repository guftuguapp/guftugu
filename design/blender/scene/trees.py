"""
Well-shaped trees for the Guftugu renders. The scanned trees' crowns looked wild ("like Kramer's hair", the
owner said), so these are built: a rounded, slightly lobed canopy (taller than wide), densely covered with
real leaf clusters from Poly Haven's island_tree_01 over a dark leafy core, on a tapered trunk with a few
main branches reaching into the crown. Each variant is drawn from its own seed.
"""
import math, random
import bmesh, bpy
from mathutils import Vector, noise


def _crown_mesh(name, rw, rh, seed):
    r = random.Random(seed)
    bm = bmesh.new()
    bmesh.ops.create_icosphere(bm, subdivisions=4, radius=1.0)
    lobes = []
    for _ in range(r.randint(5, 8)):  # big cloud-like masses (natural canopy), not a clipped ball
        a, el = r.uniform(0, 2 * math.pi), r.uniform(-0.1, 1.2)
        lobes.append((Vector((math.cos(a) * math.cos(el), math.sin(a) * math.cos(el), math.sin(el))).normalized(), r.uniform(0.22, 0.42)))
    for v in bm.verts:
        d = v.co.normalized()
        bump = sum(amp * math.exp(-((1 - d.dot(c)) / 0.13)) for c, amp in lobes) - 0.18
        bump += 0.08 * noise.noise(d * 3.5 + Vector((seed, seed, seed)))
        z = d.z * (0.62 if d.z < -0.15 else 1.0)  # a flatter underside, lifted off the trunk
        v.co = Vector((d.x * rw, d.y * rw * 0.95, z * rh)) * (1 + bump)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for p in me.polygons:
        p.use_smooth = True
    return me


def _limb(name, base, tip, r0, r1, bark, segs=12):
    """A tapered branch or trunk between two points (a cone, bent slightly)."""
    length = (tip - base).length
    bpy.ops.mesh.primitive_cone_add(vertices=segs, radius1=r0, radius2=r1, depth=length, location=(0, 0, 0))
    ob = bpy.context.object
    ob.name = name
    me = ob.data
    for v in me.vertices:  # a gentle bow
        t = (v.co.z + length / 2) / length
        v.co.x += 0.06 * length * math.sin(t * math.pi)
    ob.location = (base + tip) / 2
    ob.rotation_euler = (tip - base).to_track_quat('Z', 'Y').to_euler()
    me.materials.append(bark)
    for p in me.polygons:
        p.use_smooth = True
    return ob


def _canopy_nodes(name, leaf_coll, core_mat, density, seed):
    ng = bpy.data.node_groups.new(name, 'GeometryNodeTree')
    ng.interface.new_socket('Geometry', in_out='INPUT', socket_type='NodeSocketGeometry')
    ng.interface.new_socket('Geometry', in_out='OUTPUT', socket_type='NodeSocketGeometry')
    n, l = ng.nodes, ng.links
    gi, go = n.new('NodeGroupInput'), n.new('NodeGroupOutput')
    # leaves on the crown surface and on a slightly smaller inner shell, for depth
    pts = []
    for src, dens, s in ((gi.outputs[0], density, 0),):
        dist = n.new('GeometryNodeDistributePointsOnFaces'); dist.distribute_method = 'RANDOM'
        dist.inputs['Density'].default_value = dens
        dist.inputs['Seed'].default_value = seed + s
        l.new(src, dist.inputs['Mesh'])
        pts.append(dist)
    joinp = n.new('GeometryNodeJoinGeometry')
    for d in pts:
        l.new(d.outputs['Points'], joinp.inputs['Geometry'])
    ci = n.new('GeometryNodeCollectionInfo')
    ci.inputs['Collection'].default_value = leaf_coll
    ci.inputs['Separate Children'].default_value = True
    ci.inputs['Reset Children'].default_value = True
    iop = n.new('GeometryNodeInstanceOnPoints'); iop.inputs['Pick Instance'].default_value = True
    l.new(joinp.outputs['Geometry'], iop.inputs['Points'])
    l.new(ci.outputs[0], iop.inputs['Instance'])
    idx = n.new('FunctionNodeRandomValue'); idx.data_type = 'INT'
    idx.inputs['Min'].default_value = 0; idx.inputs['Max'].default_value = 99; idx.inputs['Seed'].default_value = seed + 3
    l.new(idx.outputs['Value'], iop.inputs['Instance Index'])
    rot = n.new('FunctionNodeRandomValue'); rot.data_type = 'FLOAT_VECTOR'
    rot.inputs['Min'].default_value = (0.0, 0.0, 0.0); rot.inputs['Max'].default_value = (6.283, 6.283, 6.283)
    rot.inputs['Seed'].default_value = seed + 4
    l.new(rot.outputs['Value'], iop.inputs['Rotation'])
    sc = n.new('FunctionNodeRandomValue'); sc.data_type = 'FLOAT'
    sc.inputs['Min'].default_value = 2.4; sc.inputs['Max'].default_value = 3.8; sc.inputs['Seed'].default_value = seed + 5
    l.new(sc.outputs['Value'], iop.inputs['Scale'])
    l.new(iop.outputs['Instances'], go.inputs[0])
    return ng


def core_material():
    m = bpy.data.materials.new('crown_core')
    try:
        m.use_nodes = True
    except Exception:
        pass
    b = m.node_tree.nodes['Principled BSDF']
    b.inputs['Base Color'].default_value = (0.012, 0.035, 0.008, 1)
    b.inputs['Roughness'].default_value = 0.9
    return m


def _masses_mesh(name, masses, seed, shells=(1.0,)):
    """One mesh holding several overlapping leafy masses (each a noisy ellipsoid), optionally with smaller
    shells inside each mass (scaled about that mass's own centre)."""
    bm = bmesh.new()
    for (c, rx0, ry0, rz0), f in [(m, f) for m in masses for f in shells]:
        rx, ry, rz = rx0 * f, ry0 * f, rz0 * f
        tmp = bmesh.new()
        bmesh.ops.create_icosphere(tmp, subdivisions=3, radius=1.0)
        for v in tmp.verts:
            d = v.co.normalized()
            k = 1 + 0.1 * noise.noise(d * 3.0 + c * 0.3 + Vector((seed, 0, 0)))
            z = d.z * (0.7 if d.z < -0.2 else 1.0)
            v.co = Vector((c.x + d.x * rx * k, c.y + d.y * ry * k, c.z + z * rz * k))
        me_t = bpy.data.meshes.new('t')
        tmp.to_mesh(me_t)
        tmp.free()
        bm.from_mesh(me_t)
        bpy.data.meshes.remove(me_t)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for p in me.polygons:
        p.use_smooth = True
    return me


def build_variants(scene, leaf_coll, bark, count=6, seed=100, density=34.0, fork_range=(0.34, 0.42), name='tree_variants'):
    """Returns tree collections (trunk with root flare, a fork into 3-5 big limbs, each carrying a leafy
    mass; together a full, natural canopy, taller than wide), each a different shape, ready to be placed as
    collection instances or scattered."""
    holder = bpy.data.collections.new(name)
    scene.collection.children.link(holder)
    core = core_material()
    out = []
    for k in range(count):
        r = random.Random(seed + k)
        height = r.uniform(9.0, 11.0)
        fork_z = height * r.uniform(*fork_range)
        W, H = r.uniform(2.4, 3.0), r.uniform(3.0, 3.7)           # canopy envelope: taller than wide
        cz = height - H
        lean = Vector((r.uniform(-0.35, 0.35), r.uniform(-0.35, 0.35), 0))
        fork = Vector((0, 0, fork_z)) + lean * 0.4
        masses = [(Vector((0, 0, cz + H * 0.35)) + lean, W * 0.62, W * 0.6, H * 0.5)]   # the crown's top
        n = r.randint(3, 5)
        for i in range(n):
            a = i * 2 * math.pi / n + r.uniform(-0.5, 0.5)
            rr = r.uniform(0.45, 0.62)
            c = Vector((math.cos(a) * W * rr, math.sin(a) * W * rr, cz + r.uniform(-0.25, 0.35) * H)) + lean
            s = r.uniform(0.75, 1.0)
            masses.append((c, W * 0.5 * s, W * 0.48 * s, H * 0.4 * s))
        # leaves on each mass's surface and on a shell inside it (depth); a dark core so no sky shows through
        crown = bpy.data.objects.new(f'crown_{name}_{k}', _masses_mesh(f'crown_{k}', masses, seed + k, shells=(1.0, 0.84)))
        scene.collection.objects.link(crown)
        mod = crown.modifiers.new('canopy', 'NODES')
        mod.node_group = _canopy_nodes(f'canopy_{k}', leaf_coll, core, density, seed * 10 + k)
        core_ob = bpy.data.objects.new(f'core_{k}', _masses_mesh(f'core_{k}', masses, seed + k, shells=(0.7,)))
        core_ob.data.materials.append(core)
        scene.collection.objects.link(core_ob)
        parts = [crown, core_ob]
        r0 = r.uniform(0.34, 0.42)
        parts.append(_limb(f'trunk_{k}', Vector((0, 0, -0.4)), fork, r0, r0 * 0.68, bark, 18))
        parts.append(_limb(f'flare_{k}', Vector((0, 0, -0.3)), Vector((0, 0, 0.45)), r0 * 1.22, r0 * 0.97, bark, 18))  # a slight root flare
        for i, (c, rx, ry, rz) in enumerate(masses):  # a big limb up into every leafy mass
            tip = c + Vector((0, 0, -rz * 0.3))
            parts.append(_limb(f'limb_{k}_{i}', fork, tip, r0 * 0.5, 0.06, bark, 12))
            for j in range(2):  # and a couple of smaller branches off it
                mid = fork.lerp(tip, r.uniform(0.45, 0.7))
                off = Vector((r.uniform(-1, 1), r.uniform(-1, 1), r.uniform(0.3, 0.9))) * rx * 0.6
                parts.append(_limb(f'twig_{k}_{i}_{j}', mid, mid + off, r0 * 0.2, 0.025, bark, 8))
        root = bpy.data.objects.new(f'tree_{k}', None)
        scene.collection.objects.link(root)
        for p in parts:
            p.parent = root
        tc = bpy.data.collections.new(f'{name}_{k}')
        holder.children.link(tc)
        for ob in [root] + parts:
            for c in list(ob.users_collection):
                c.objects.unlink(ob)
            tc.objects.link(ob)
        out.append(tc)
    # keep the sources out of the image without hiding their copies: exclude from the view layer
    lc = bpy.context.view_layer.layer_collection.children.get(holder.name)
    if lc is not None:
        lc.exclude = True
    return out, holder


def place_tree(scene, tree_coll, loc, rz, scale):
    """A collection instance of one tree variant (shares all geometry with the other copies)."""
    inst = bpy.data.objects.new(tree_coll.name + '_inst', None)
    inst.instance_type = 'COLLECTION'
    inst.instance_collection = tree_coll
    inst.location = loc
    inst.rotation_euler = (0, 0, rz)
    inst.scale = scale
    scene.collection.objects.link(inst)
    return inst
