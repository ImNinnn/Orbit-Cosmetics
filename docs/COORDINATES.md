# Blockbench to Minecraft coordinate mapping

This is exactly how Orbit Cosmetic turns a `.bbmodel` into geometry on the player. The implementation is
`common/src/main/java/dev/orbit/cosmetic/common/model/BBModelParser.java`; unit tests in
`BBModelParserTest` check every rule below against the vanilla player model.

## Units

| | Blockbench | Minecraft model space |
|---|---|---|
| Unit | 1 pixel | 1 pixel (1/16 block) |
| Up | +Y | **-Y** (model space is Y-down) |
| Player faces | north (-Z) | -Z before the body rotation is applied |
| Player's right | +X | -X |
| Origin | between the feet | 24 px above the feet (the vanilla `root` part) |

The two systems differ by a **180° rotation about Z**. It is a rotation, not a mirror, so handedness,
winding and left/right are preserved:

```
position:  (x, y, z)    ->  (-x, -y, z)
rotation:  (rx, ry, rz) ->  (-rx, -ry, rz)     degrees -> radians
```

Both use Euler order **Z, then Y, then X** (Blockbench's default `ZYX`, Minecraft's `rotationZYX`), so the
converted angles are applied exactly like `ModelPart.translateAndRotate`.

## Anchors and pivots

Every recognised group is attached to the matching part of the player model being rendered. The anchor
pivots are the vanilla (wide-arm) joint positions, in Blockbench coordinates:

| Group | Pivot (Blockbench) | Follows |
|---|---|---|
| `HEAD` | 0, 24, 0 | head (look direction, nodding, sneaking) |
| `BODY` | 0, 24, 0 | torso |
| `RIGHT_ARM` | 5, 22, 0 | right arm |
| `LEFT_ARM` | -5, 22, 0 | left arm |
| `RIGHT_LEG` | 1.9, 12, 0 | right leg |
| `LEFT_LEG` | -1.9, 12, 0 | left leg |
| anything else | 0, 24, 0 | player root (position and body yaw only) |

A cosmetic group is placed **relative to its anchor pivot**, then the body part's live transform is
applied. If you keep the anchor group's origin on the pivot (the template does), what you see on the
reference player in Blockbench is what you get in game. If you move an anchor group's origin or rotate it,
that offset and rotation are kept, as in Blockbench.

Names are exact and case-sensitive. An anchor group nested inside another group still follows its body
part; the parent's transform is ignored and a warning is shown.

Geometry outside all anchor groups (loose cubes, or groups that are not anchors) is **player-root
geometry**: it is positioned relative to the player origin `(0, 0, 0)` at the feet and turns with the
body, but does not follow any limb.

## What is excluded

* The `REFERENCE_PLAYER` group and everything inside it (the template's guide body).
* Elements and groups with **Export** turned off in Blockbench.
* Faces with no texture.
* Locators and null objects (no geometry).

## Cubes

* `from`/`to`, `inflate`, and the cube's own `origin`/`rotation` (any angle on all three axes) are baked
  into vertices once, when the model loads.
* Face corners follow Blockbench's layout; `uv` rectangles, flipped UVs (u2 < u1) and face `rotation`
  (0/90/180/270) are honoured.
* Box UV cubes use Blockbench's box layout from `uv_offset` (sizes floored, except in the Modded Entity
  format), including `mirror_uv`.
* UVs are divided by the texture's UV size in formats with per-texture UV size (Generic Model,
  OptiFine JEM), otherwise by the project resolution.

## Meshes

Mesh elements with triangle and quad faces are supported. Quads are reordered with Blockbench's own
algorithm, so non-planar or out-of-order vertex lists render as they do in Blockbench. Faces with more
than four vertices are skipped with a warning.

## Animations

Bone keyframes for **rotation, position and scale** are supported, with `linear`, `step`, `catmullrom`
(three.js spline, as Blockbench draws it) and `bezier` (per-axis handles) interpolation, and pre/post
values for discontinuous keyframes.

Keyframe values are converted the same way as rest values, with one subtlety: Blockbench changed the
stored sign convention in format **5.0**.

| Format | Rotation stored as | Position stored as |
|---|---|---|
| 5.0 and newer | Blockbench axes: model space = (-x, -y, z) | Blockbench axes: model space = (-x, -y, z) |
| older than 5.0 | X and Y already negated: model space = (x, y, z) | X already negated: model space = (x, -y, z) |

Animated rotation is added to the rest rotation, position is added to the pivot, and scale multiplies
around the pivot, exactly as in Blockbench's animator.

Keyframe values may be Molang expressions. A sandboxed subset is evaluated: arithmetic, comparisons,
`?:`, `math.*` (trigonometry in degrees, as in Bedrock), `query.anim_time`, `query.life_time`,
`query.ground_speed`, `query.vertical_speed`, `query.modified_distance_moved`, `query.modified_move_speed`,
`query.head_x_rotation`, `query.head_y_rotation`, `query.is_on_ground`, `query.is_sneaking`,
`query.is_sprinting`, `query.is_swimming`, `query.is_flying`, `query.is_in_water`, `query.is_moving`,
plus `variable.*`/`temp.*` assignments. Unknown queries are 0. `math.random` returns the midpoint so that
every client computes the same pose.

## Not reproduced (with warnings)

| Feature | Behaviour |
|---|---|
| Animated (multi-frame) textures | The whole strip is mapped as one image |
| Sound, particle and instruction keyframes | Ignored |
| Animation controllers, IK, armatures | Ignored |
| Quaternion rotation interpolation | Rotations interpolate per axis |
| Unsupported Molang functions | That value evaluates to its default (0, or 1 for scale) |
| Texture render modes (emissive, layered) | Rendered as normal textures; partial transparency is detected automatically |
