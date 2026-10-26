"""Generates examples/orbit_demo.bbmodel: one piece of geometry on every anchor, for checking placement.

Run with: python3 make_demo.py   (needs Pillow)
"""
import base64
import io
import json
import uuid

from PIL import Image

COLORS = {
    "gold": (240, 200, 40, 255),
    "blue": (60, 110, 230, 255),
    "red": (220, 50, 50, 255),
    "green": (60, 190, 70, 255),
    "orange": (240, 140, 30, 255),
    "purple": (150, 70, 200, 255),
    "white": (245, 245, 245, 255),
    "glass": (120, 220, 255, 120),
}
# 8 color swatches, each 8x8, in a 32x16 texture
SWATCH = {name: (i % 4 * 8, i // 4 * 8) for i, name in enumerate(COLORS)}


def texture():
    image = Image.new("RGBA", (32, 16), (0, 0, 0, 0))
    for name, (x, y) in SWATCH.items():
        for dx in range(8):
            for dy in range(8):
                r, g, b, a = COLORS[name]
                edge = dx in (0, 7) or dy in (0, 7)
                shade = 0.75 if edge else 1.0
                image.putpixel((x + dx, y + dy), (int(r * shade), int(g * shade), int(b * shade), a))
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()


def uid(seed):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "orbit-demo/" + seed))


elements = []


def cube(name, frm, to, color, rotation=(0, 0, 0), origin=(0, 0, 0)):
    x, y = SWATCH[color]
    faces = {f: {"uv": [x + 1, y + 1, x + 7, y + 7], "texture": 0} for f in ("north", "south", "east", "west", "up", "down")}
    element = {"name": name, "type": "cube", "uuid": uid("cube/" + name), "from": list(frm), "to": list(to),
               "origin": list(origin), "rotation": list(rotation), "box_uv": False, "faces": faces}
    elements.append(element)
    return element["uuid"]


groups = []


def group(name, origin, children, rotation=(0, 0, 0)):
    g = {"name": name, "uuid": uid("group/" + name), "origin": list(origin), "rotation": list(rotation)}
    groups.append(g)
    return {"uuid": g["uuid"], "isOpen": True, "children": children}


outliner = [
    group("HEAD", (0, 24, 0), [
        cube("crown_front", (-4.5, 32, -4.5), (4.5, 34, -3.5), "gold"),
        cube("crown_back", (-4.5, 32, 3.5), (4.5, 34, 4.5), "gold"),
        cube("crown_left", (-4.5, 32, -3.5), (-3.5, 34, 3.5), "gold"),
        cube("crown_right", (3.5, 32, -3.5), (4.5, 34, 3.5), "gold"),
        cube("visor", (-4, 27, -5), (4, 29, -4.4), "glass"),
        group("horn", (2.5, 32, 0), [cube("horn_cube", (2, 32, -0.5), (3, 36, 0.5), "white")], rotation=(0, 0, -25)),
    ]),
    group("BODY", (0, 24, 0), [
        group("backpack", (0, 18, 2), [cube("backpack_cube", (-3, 14, 2), (3, 22, 5), "blue")]),
    ]),
    group("RIGHT_ARM", (5, 22, 0), [cube("right_bracelet", (3.5, 13, -2.5), (8.5, 15, 2.5), "red")]),
    group("LEFT_ARM", (-5, 22, 0), [cube("left_bracelet", (-8.5, 13, -2.5), (-3.5, 15, 2.5), "green")]),
    group("RIGHT_LEG", (1.9, 12, 0), [cube("right_cuff", (-0.6, 0, -2.5), (4.4, 2.5, 2.5), "orange")]),
    group("LEFT_LEG", (-1.9, 12, 0), [cube("left_cuff", (-4.4, 0, -2.5), (0.6, 2.5, 2.5), "purple")]),
    group("halo", (0, 37, 0), [cube("halo_cube", (-2, 37, -2), (2, 37.5, 2), "gold")]),
]


def keyframe(channel, time, x, y, z, interpolation="linear"):
    return {"channel": channel, "data_points": [{"x": x, "y": y, "z": z}], "uuid": uid(f"kf/{channel}/{time}/{x}{y}{z}"),
            "time": time, "color": -1, "interpolation": interpolation}


def animation(name, loop, length, animators):
    return {"uuid": uid("anim/" + name), "name": name, "loop": loop, "override": False, "length": length, "snapping": 20,
            "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "", "loop_delay": "",
            "animators": animators}


halo = groups[-1]["uuid"]
backpack = next(g["uuid"] for g in groups if g["name"] == "backpack")
horn = next(g["uuid"] for g in groups if g["name"] == "horn")
animations = [
    animation("IDLE", "loop", 2, {halo: {"name": "halo", "type": "bone", "keyframes": [
        keyframe("rotation", 0, 0, 0, 0), keyframe("rotation", 2, 0, 360, 0)]}}),
    animation("WALK", "loop", 0.5, {backpack: {"name": "backpack", "type": "bone", "keyframes": [
        keyframe("position", 0, 0, 0, 0, "catmullrom"), keyframe("position", 0.25, 0, 1, 0, "catmullrom"), keyframe("position", 0.5, 0, 0, 0, "catmullrom")]}}),
    animation("horn_wiggle", "loop", 1, {horn: {"name": "horn", "type": "bone", "keyframes": [
        keyframe("rotation", 0, 0, 0, "math.sin(q.anim_time * 360) * 15")]}}),
]

model = {
    "meta": {"format_version": "5.0", "model_format": "free", "box_uv": False},
    "name": "Orbit demo",
    "model_identifier": "",
    "visible_box": [1, 1, 0],
    "resolution": {"width": 32, "height": 16},
    "elements": elements,
    "groups": groups,
    "outliner": outliner,
    "textures": [{"name": "demo.png", "id": "0", "width": 32, "height": 16, "uv_width": 32, "uv_height": 16,
                  "uuid": uid("texture"), "internal": True, "source": texture()}],
    "animations": animations,
}

with open("orbit_demo.bbmodel", "w") as out:
    json.dump(model, out, indent=1)
print("wrote orbit_demo.bbmodel")
