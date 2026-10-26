package dev.orbit.cosmetic.common.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.BoneTrack;
import dev.orbit.cosmetic.common.anim.Channel;
import dev.orbit.cosmetic.common.anim.Interpolation;
import dev.orbit.cosmetic.common.anim.Keyframe;
import dev.orbit.cosmetic.common.anim.LoopMode;
import dev.orbit.cosmetic.common.anim.molang.Molang;
import dev.orbit.cosmetic.common.anim.molang.MolangException;
import dev.orbit.cosmetic.common.anim.molang.MolangExpression;
import dev.orbit.cosmetic.common.util.DataUrls;
import dev.orbit.cosmetic.common.util.PngInfo;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates Blockbench {@code .bbmodel} files (format versions 3.x to 5.x) into a {@link CosmeticModel}.
 *
 * <p>All input is treated as untrusted: sizes and counts are checked against {@link ModelLimits} before work
 * proportional to them is done, numbers are checked for finiteness and magnitude, and nothing in the file can
 * cause code execution or filesystem access. Problems are reported as {@link Issue}s instead of exceptions.
 *
 * <p>Coordinate conversion (see docs/COORDINATES.md): Blockbench uses pixels with Y up and the model facing
 * -Z; Minecraft model space uses pixels with Y down and the model facing -Z. The two differ by a 180 degree
 * rotation about Z, so positions map {@code (x, y, z) -> (-x, -y, z)} and Euler rotations (Z, Y, X order in
 * both) map {@code (rx, ry, rz) -> (-rx, -ry, rz)}.
 */
public final class BBModelParser {
	/** Group name whose subtree is reference geometry: shown in Blockbench, never rendered in game. */
	public static final String REFERENCE_GROUP = "REFERENCE_PLAYER";

	private static final float MAX_COORDINATE = 4096f;
	private static final int MAX_NAME_LENGTH = 64;
	private static final String[] FACE_NAMES = {"north", "south", "east", "west", "up", "down"};

	public record Result(CosmeticModel model, List<Issue> issues) {
		public boolean ok() {
			return model != null;
		}

		public List<Issue> errors() {
			return issues.stream().filter(Issue::isError).toList();
		}

		public List<Issue> warnings() {
			return issues.stream().filter(issue -> !issue.isError()).toList();
		}
	}

	private final ModelLimits limits;
	private final List<Issue> issues = new ArrayList<>();
	private final Set<String> reportedCodes = new LinkedHashSet<>();

	private boolean perTextureUvSize;
	private boolean boxUvFloatSize;
	private boolean defaultBoxUv;
	private boolean animationsInBlockbenchAxes;
	private float projectUvWidth = 16;
	private float projectUvHeight = 16;

	private final List<TextureSlot> textureSlots = new ArrayList<>();
	private final Map<String, JsonObject> elementsByUuid = new HashMap<>();
	private final Map<String, JsonObject> groupsByUuid = new HashMap<>();
	private final List<BoneBuilder> bones = new ArrayList<>();
	private final Map<String, Integer> boneByUuid = new HashMap<>();
	private final Set<String> excludedGroupUuids = new LinkedHashSet<>();
	private final Set<String> placedElements = new LinkedHashSet<>();
	private BoneBuilder implicitRoot;
	private int quadCount;
	private boolean tooManyQuads;

	private BBModelParser(ModelLimits limits) {
		this.limits = limits.clampToHard();
	}

	/**
	 * @param fallbackName used when the file does not name its model
	 */
	public static Result parse(byte[] bytes, String fallbackName, ModelLimits limits) {
		BBModelParser parser = new BBModelParser(limits);
		CosmeticModel model;
		try {
			model = parser.run(bytes, fallbackName);
		} catch (InvalidModelException e) {
			parser.issues.add(Issue.error(e.code, e.getMessage()));
			model = null;
		} catch (RuntimeException e) {
			// Defensive: malformed input must never crash the caller.
			parser.issues.add(Issue.error("internal", "Unexpected error while reading model: " + e.getClass().getSimpleName()));
			model = null;
		}
		if (parser.issues.stream().anyMatch(Issue::isError)) {
			model = null;
		}
		return new Result(model, List.copyOf(parser.issues));
	}

	/** Reads and validates JSON with a nesting limit. Exposed for callers that rewrite models (e.g. texture embedding). */
	public static JsonObject readJson(byte[] bytes, int maxBytes) throws InvalidModelException {
		if (bytes.length > maxBytes) {
			throw new InvalidModelException("too_large", "File is " + bytes.length / 1024 + " KiB, the limit is " + maxBytes / 1024 + " KiB");
		}
		String text = new String(bytes, StandardCharsets.UTF_8);
		if (!text.isEmpty() && text.charAt(0) == '﻿') {
			text = text.substring(1);
		}
		try {
			JsonReader reader = new JsonReader(new StringReader(text));
			reader.setStrictness(Strictness.LENIENT);
			reader.setNestingLimit(64);
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				throw new InvalidModelException("not_object", "The file is not a Blockbench model (expected a JSON object)");
			}
			return root.getAsJsonObject();
		} catch (JsonParseException | StackOverflowError | IllegalStateException e) {
			throw new InvalidModelException("bad_json", "The file is not valid JSON: " + shorten(String.valueOf(e.getMessage()), 120));
		}
	}

	private CosmeticModel run(byte[] bytes, String fallbackName) throws InvalidModelException {
		JsonObject root = readJson(bytes, limits.maxFileBytes());
		JsonObject meta = object(root, "meta");
		if (meta == null) {
			throw new InvalidModelException("no_meta", "Missing \"meta\" section; this does not look like a .bbmodel file");
		}
		String formatVersion = string(meta, "format_version", string(meta, "format", "4.0"));
		String modelFormat = string(meta, "model_format", "free");
		perTextureUvSize = modelFormat.equals("free") || modelFormat.equals("optifine_entity");
		boxUvFloatSize = modelFormat.equals("modded_entity");
		defaultBoxUv = bool(meta, "box_uv", false);
		animationsInBlockbenchAxes = majorVersion(formatVersion) >= 5;
		if (majorVersion(formatVersion) > 5) {
			warn("newer_format", "Model format version " + formatVersion + " is newer than this version of Orbit was tested with");
		}

		JsonObject resolution = object(root, "resolution");
		if (resolution != null) {
			projectUvWidth = positive(number(resolution, "width", 16), 16);
			projectUvHeight = positive(number(resolution, "height", 16), 16);
		}

		String name = sanitizeName(string(root, "name", null));
		if (name == null || name.isEmpty()) {
			name = sanitizeName(fallbackName);
		}
		if (name == null || name.isEmpty()) {
			name = "cosmetic";
		}

		readTextures(root);
		readElements(root);
		readGroups(root);

		JsonArray outliner = array(root, "outliner");
		if (outliner != null) {
			walk(outliner, null, 0);
		}
		for (Map.Entry<String, JsonObject> entry : elementsByUuid.entrySet()) {
			if (!placedElements.contains(entry.getKey())) {
				addElement(entry.getValue(), rootBone());
			}
		}
		if (tooManyQuads) {
			throw new InvalidModelException("too_many_quads", "The model has more than " + limits.maxQuads() + " faces");
		}

		List<AnimationClip> animations = readAnimations(root);
		return build(name, animations);
	}

	// ---------------------------------------------------------------- textures

	private record TextureSlot(String name, String uuid, byte[] png, PngInfo info, float uvWidth, float uvHeight, String problem) {
	}

	private void readTextures(JsonObject root) throws InvalidModelException {
		JsonArray textures = array(root, "textures");
		if (textures == null) {
			return;
		}
		if (textures.size() > limits.maxTextures()) {
			throw new InvalidModelException("too_many_textures", "The model has " + textures.size() + " textures, the limit is " + limits.maxTextures());
		}
		int totalBytes = 0;
		for (int i = 0; i < textures.size(); i++) {
			JsonObject texture = textures.get(i).isJsonObject() ? textures.get(i).getAsJsonObject() : new JsonObject();
			String textureName = sanitizeName(string(texture, "name", "texture_" + i));
			String uuid = string(texture, "uuid", null);
			float uvWidth = projectUvWidth;
			float uvHeight = projectUvHeight;
			if (perTextureUvSize) {
				uvWidth = positive(number(texture, "uv_width", projectUvWidth), projectUvWidth);
				uvHeight = positive(number(texture, "uv_height", projectUvHeight), projectUvHeight);
			}
			String source = string(texture, "source", null);
			byte[] png = null;
			PngInfo info = null;
			String problem = null;
			if (source == null || source.isEmpty()) {
				problem = "Texture \"" + textureName + "\" is not embedded in the model. Save it in Blockbench with textures embedded, or import the model through Orbit with its texture files next to it.";
			} else {
				png = DataUrls.decodePng(source, limits.maxTotalTextureBytes() - totalBytes);
				if (png == null) {
					problem = "Texture \"" + textureName + "\" is not a valid embedded PNG or exceeds the texture size limit";
				} else {
					info = PngInfo.read(png);
					if (info == null) {
						problem = "Texture \"" + textureName + "\" is not a valid PNG image";
						png = null;
					} else if (info.width() > limits.maxTextureSize() || info.height() > limits.maxTextureSize()) {
						problem = "Texture \"" + textureName + "\" is " + info.width() + "x" + info.height() + ", the limit is " + limits.maxTextureSize() + "x" + limits.maxTextureSize();
						png = null;
					} else {
						totalBytes += png.length;
					}
				}
			}
			if (texture.has("frame_time") && info != null && info.height() > info.width() && info.height() % info.width() == 0) {
				warnOnce("animated_texture", "Animated textures are not supported; the whole texture strip is mapped as one image");
			}
			textureSlots.add(new TextureSlot(textureName, uuid, png, info, uvWidth, uvHeight, problem));
		}
	}

	/** Resolves a face's texture reference (index, uuid, or missing) to a texture slot index, or -1. */
	private int resolveTexture(JsonElement reference) {
		if (reference == null || reference.isJsonNull()) {
			return -1;
		}
		if (reference.isJsonPrimitive()) {
			JsonPrimitive primitive = reference.getAsJsonPrimitive();
			if (primitive.isNumber()) {
				int index = primitive.getAsInt();
				return index >= 0 && index < textureSlots.size() ? index : -1;
			}
			if (primitive.isString()) {
				String value = primitive.getAsString();
				for (int i = 0; i < textureSlots.size(); i++) {
					if (value.equals(textureSlots.get(i).uuid())) {
						return i;
					}
				}
				try {
					int index = Integer.parseInt(value);
					return index >= 0 && index < textureSlots.size() ? index : -1;
				} catch (NumberFormatException ignored) {
					return -1;
				}
			}
			if (primitive.isBoolean() && !primitive.getAsBoolean()) {
				// false means "default texture" in single-texture formats
				return textureSlots.size() == 1 ? 0 : -1;
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------- outliner

	private static final class BoneBuilder {
		final int index;
		final String name;
		final String uuid;
		final int parent;
		final Anchor anchor;
		final float[] originBb;
		final float[] rotationBb;
		final List<Quad> quads = new ArrayList<>();

		BoneBuilder(int index, String name, String uuid, int parent, Anchor anchor, float[] originBb, float[] rotationBb) {
			this.index = index;
			this.name = name;
			this.uuid = uuid;
			this.parent = parent;
			this.anchor = anchor;
			this.originBb = originBb;
			this.rotationBb = rotationBb;
		}
	}

	private void readElements(JsonObject root) throws InvalidModelException {
		JsonArray elements = array(root, "elements");
		if (elements == null) {
			return;
		}
		if (elements.size() > limits.maxElements()) {
			throw new InvalidModelException("too_many_elements", "The model has " + elements.size() + " elements, the limit is " + limits.maxElements());
		}
		int anonymous = 0;
		for (JsonElement element : elements) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			String uuid = string(object, "uuid", null);
			if (uuid == null) {
				uuid = "__element_" + anonymous++;
			}
			elementsByUuid.put(uuid, object);
		}
	}

	private void readGroups(JsonObject root) {
		JsonArray groups = array(root, "groups");
		if (groups == null) {
			return;
		}
		for (JsonElement group : groups) {
			if (group.isJsonObject()) {
				String uuid = string(group.getAsJsonObject(), "uuid", null);
				if (uuid != null) {
					groupsByUuid.put(uuid, group.getAsJsonObject());
				}
			}
		}
	}

	private void walk(JsonArray nodes, BoneBuilder parent, int depth) throws InvalidModelException {
		if (depth > limits.maxDepth()) {
			throw new InvalidModelException("too_deep", "Groups are nested more than " + limits.maxDepth() + " levels deep");
		}
		for (JsonElement node : nodes) {
			if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
				String uuid = node.getAsString();
				JsonObject element = elementsByUuid.get(uuid);
				if (element != null && placedElements.add(uuid)) {
					addElement(element, parent != null ? parent : rootBone());
				}
			} else if (node.isJsonObject()) {
				walkGroup(node.getAsJsonObject(), parent, depth);
			}
		}
	}

	private void walkGroup(JsonObject node, BoneBuilder parent, int depth) throws InvalidModelException {
		String uuid = string(node, "uuid", null);
		JsonObject properties = node;
		if (!node.has("name") && uuid != null && groupsByUuid.containsKey(uuid)) {
			// Format 5: group properties live in the top-level "groups" array.
			properties = groupsByUuid.get(uuid);
		}
		String name = string(properties, "name", "group");
		JsonArray children = array(node, "children");

		if (REFERENCE_GROUP.equals(name) || !bool(properties, "export", true)) {
			markExcluded(node);
			return;
		}

		Anchor anchor = Anchor.fromGroupName(name);
		int parentIndex;
		Anchor boneAnchor;
		if (anchor != null) {
			if (parent != null && parent.anchor != Anchor.ROOT && parent.anchor != anchor) {
				warn("nested_anchor", "Anchor group " + name + " is inside the " + parent.anchor + " anchor; it follows " + name + " and ignores its parent's transforms");
			} else if (parent != null) {
				warnOnce("anchor_in_group", "Anchor group " + name + " is inside group \"" + parent.name + "\"; its parent's transforms are ignored");
			}
			parentIndex = -1;
			boneAnchor = anchor;
		} else if (parent != null) {
			parentIndex = parent.index;
			boneAnchor = parent.anchor;
		} else {
			parentIndex = -1;
			boneAnchor = Anchor.ROOT;
		}

		if (bones.size() >= limits.maxBones()) {
			throw new InvalidModelException("too_many_bones", "The model has more than " + limits.maxBones() + " groups");
		}
		float[] origin = vector(properties, "origin", 0);
		float[] rotation = vector(properties, "rotation", 0);
		BoneBuilder bone = new BoneBuilder(bones.size(), sanitizeName(name), uuid, parentIndex, boneAnchor, origin, rotation);
		bones.add(bone);
		if (uuid != null) {
			boneByUuid.put(uuid, bone.index);
		}
		if (children != null) {
			walk(children, bone, depth + 1);
		}
	}

	private void markExcluded(JsonObject node) {
		String uuid = string(node, "uuid", null);
		if (uuid != null) {
			excludedGroupUuids.add(uuid);
		}
		JsonArray children = array(node, "children");
		if (children == null) {
			return;
		}
		for (JsonElement child : children) {
			if (child.isJsonPrimitive()) {
				placedElements.add(child.getAsString());
			} else if (child.isJsonObject()) {
				markExcluded(child.getAsJsonObject());
			}
		}
	}

	private BoneBuilder rootBone() throws InvalidModelException {
		if (implicitRoot == null) {
			if (bones.size() >= limits.maxBones()) {
				throw new InvalidModelException("too_many_bones", "The model has more than " + limits.maxBones() + " groups");
			}
			Anchor root = Anchor.ROOT;
			implicitRoot = new BoneBuilder(bones.size(), "root", null, -1, root, new float[] {root.pivotX(), root.pivotY(), root.pivotZ()}, new float[3]);
			bones.add(implicitRoot);
		}
		return implicitRoot;
	}

	// ---------------------------------------------------------------- geometry

	private void addElement(JsonObject element, BoneBuilder bone) throws InvalidModelException {
		if (!bool(element, "export", true)) {
			return;
		}
		String type = string(element, "type", "cube");
		switch (type) {
			case "cube" -> addCube(element, bone);
			case "mesh" -> addMesh(element, bone);
			case "locator", "null_object", "bounding_box" -> {
				// Helpers without geometry.
			}
			default -> warnOnce("unsupported_" + type, "Elements of type \"" + type + "\" are not supported and were skipped");
		}
	}

	private void addCube(JsonObject cube, BoneBuilder bone) throws InvalidModelException {
		float[] from = vector(cube, "from", 0);
		float[] to = vector(cube, "to", 0);
		float[] origin = vector(cube, "origin", 0);
		float[] rotation = cubeRotation(cube);
		float inflate = clampCoordinate(number(cube, "inflate", 0));
		boolean boxUv = cube.has("box_uv") ? bool(cube, "box_uv", false) : defaultBoxUv;
		boolean mirrorUv = bool(cube, "mirror_uv", false);
		float[] uvOffset = vector2(cube, "uv_offset");
		JsonObject faces = object(cube, "faces");
		if (faces == null) {
			return;
		}

		float x0 = Math.min(from[0], to[0]) - inflate;
		float y0 = Math.min(from[1], to[1]) - inflate;
		float z0 = Math.min(from[2], to[2]) - inflate;
		float x1 = Math.max(from[0], to[0]) + inflate;
		float y1 = Math.max(from[1], to[1]) + inflate;
		float z1 = Math.max(from[2], to[2]) + inflate;

		Map<String, float[]> boxUvs = boxUv ? boxUv(from, to, uvOffset, mirrorUv) : null;
		double[][] matrix = rotationMatrix(rotation);

		for (String faceName : FACE_NAMES) {
			JsonObject face = object(faces, faceName);
			if (face == null) {
				continue;
			}
			int texture = face.has("texture") ? resolveTexture(face.get("texture")) : (textureSlots.size() == 1 ? 0 : -1);
			if (texture < 0) {
				continue;
			}
			float[] uv = boxUvs != null ? boxUvs.get(faceName) : vector4(face, "uv");
			if (uv == null) {
				continue;
			}
			float[][] corners = faceCorners(faceName, x0, y0, z0, x1, y1, z1);
			float[] normal = faceNormal(faceName);
			int faceRotation = Math.floorMod(Math.round(number(face, "rotation", 0) / 90f), 4);
			float[][] cornerUv = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
			float[] positions = new float[12];
			float[] uvs = new float[8];
			TextureSlot slot = textureSlots.get(texture);
			for (int i = 0; i < 4; i++) {
				float[] p = rotateAbout(corners[i], origin, matrix);
				toBone(p, bone, positions, i * 3);
				float[] c = cornerUv[Math.floorMod(i - faceRotation, 4)];
				uvs[i * 2] = c[0] / slot.uvWidth();
				uvs[i * 2 + 1] = c[1] / slot.uvHeight();
			}
			addQuad(bone, positions, uvs, toModelDirection(rotate(normal, matrix)), texture);
		}
	}

	/**
	 * The four corners of a cube face in Blockbench space, ordered top-left, top-right, bottom-right,
	 * bottom-left as seen from outside, matching how Blockbench maps {@code uv[0..3]} onto each face.
	 */
	private static float[][] faceCorners(String face, float x0, float y0, float z0, float x1, float y1, float z1) {
		return switch (face) {
			case "north" -> new float[][] {{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}};
			case "south" -> new float[][] {{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}};
			case "east" -> new float[][] {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}};
			case "west" -> new float[][] {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}};
			case "up" -> new float[][] {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
			default -> new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}};
		};
	}

	private static float[] faceNormal(String face) {
		return switch (face) {
			case "north" -> new float[] {0, 0, -1};
			case "south" -> new float[] {0, 0, 1};
			case "east" -> new float[] {1, 0, 0};
			case "west" -> new float[] {-1, 0, 0};
			case "up" -> new float[] {0, 1, 0};
			default -> new float[] {0, -1, 0};
		};
	}

	/** Blockbench's box UV layout ({@code Cube.preview_controller.updateUV}). */
	private Map<String, float[]> boxUv(float[] from, float[] to, float[] offset, boolean mirror) {
		float w = boxSize(to[0] - from[0]);
		float h = boxSize(to[1] - from[1]);
		float d = boxSize(to[2] - from[2]);
		// {fromU, fromV, sizeU, sizeV} per face, in Blockbench's order
		float[][] layout = {
			{0, d, d, h},              // east
			{d + w, d, d, h},          // west
			{d + w, d, -w, -d},        // up
			{d + w * 2, 0, -w, d},     // down
			{d * 2 + w, d, w, h},      // south
			{d, d, w, h},              // north
		};
		if (mirror) {
			for (float[] f : layout) {
				f[0] += f[2];
				f[2] = -f[2];
			}
			float[] east = layout[0];
			layout[0] = layout[1];
			layout[1] = east;
		}
		String[] order = {"east", "west", "up", "down", "south", "north"};
		Map<String, float[]> result = new HashMap<>();
		for (int i = 0; i < order.length; i++) {
			float[] f = layout[i];
			result.put(order[i], new float[] {f[0] + offset[0], f[1] + offset[1], f[0] + f[2] + offset[0], f[1] + f[3] + offset[1]});
		}
		return result;
	}

	private float boxSize(float size) {
		float abs = Math.abs(size);
		return boxUvFloatSize ? abs : (float) Math.floor(abs + 0.0000001f);
	}

	private void addMesh(JsonObject mesh, BoneBuilder bone) throws InvalidModelException {
		JsonObject vertices = object(mesh, "vertices");
		JsonObject faces = object(mesh, "faces");
		if (vertices == null || faces == null) {
			return;
		}
		if (vertices.size() > limits.maxQuads() * 4L || faces.size() > limits.maxQuads()) {
			throw new InvalidModelException("too_many_quads", "A mesh has more than " + limits.maxQuads() + " faces");
		}
		float[] origin = vector(mesh, "origin", 0);
		double[][] matrix = rotationMatrix(vector(mesh, "rotation", 0));
		Map<String, float[]> local = new HashMap<>();
		for (Map.Entry<String, JsonElement> entry : vertices.entrySet()) {
			float[] v = vectorOf(entry.getValue());
			if (v != null) {
				local.put(entry.getKey(), v);
			}
		}
		for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject face = entry.getValue().getAsJsonObject();
			int texture = face.has("texture") ? resolveTexture(face.get("texture")) : (textureSlots.size() == 1 ? 0 : -1);
			JsonArray keys = array(face, "vertices");
			JsonObject uvMap = object(face, "uv");
			if (texture < 0 || keys == null || uvMap == null || keys.size() < 3) {
				continue;
			}
			if (keys.size() > 4) {
				warnOnce("ngon", "Mesh faces with more than 4 vertices are not supported and were skipped");
				continue;
			}
			List<String> order = new ArrayList<>();
			for (JsonElement key : keys) {
				order.add(key.getAsString());
			}
			if (!local.keySet().containsAll(order)) {
				continue;
			}
			if (order.size() == 4) {
				order = sortQuad(order, local);
			}
			TextureSlot slot = textureSlots.get(texture);
			float[] positions = new float[12];
			float[] uvs = new float[8];
			float[][] world = new float[order.size()][];
			for (int i = 0; i < 4; i++) {
				String key = order.get(Math.min(i, order.size() - 1));
				float[] v = local.get(key);
				float[] rotated = rotate(v, matrix);
				float[] p = {rotated[0] + origin[0], rotated[1] + origin[1], rotated[2] + origin[2]};
				if (i < world.length) {
					world[i] = p;
				}
				toBone(p, bone, positions, i * 3);
				float[] uv = vector2(uvMap, key);
				uvs[i * 2] = uv[0] / slot.uvWidth();
				uvs[i * 2 + 1] = uv[1] / slot.uvHeight();
			}
			float[] a = sub(world[1], world[0]);
			float[] b = sub(world[2], world[0]);
			float[] normal = normalize(new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]});
			addQuad(bone, positions, uvs, toModelDirection(normal), texture);
		}
	}

	/** Blockbench's {@code MeshFace.getSortedVertices}: orders four vertices so they form a simple quad. */
	private static List<String> sortQuad(List<String> keys, Map<String, float[]> vertices) {
		float[] v0 = vertices.get(keys.get(0));
		float[] v1 = vertices.get(keys.get(1));
		float[] v2 = vertices.get(keys.get(2));
		float[] v3 = vertices.get(keys.get(3));
		if (oppositeSide(v1, v2, v0, v3)) {
			return List.of(keys.get(2), keys.get(0), keys.get(1), keys.get(3));
		}
		if (oppositeSide(v0, v1, v2, v3)) {
			return List.of(keys.get(0), keys.get(2), keys.get(1), keys.get(3));
		}
		return keys;
	}

	/** Whether {@code check} lies on the other side of the line base1-base2 than {@code top}. */
	private static boolean oppositeSide(float[] base1, float[] base2, float[] top, float[] check) {
		float[] line = sub(base2, base1);
		float lengthSq = dot(line, line);
		float t = lengthSq == 0 ? 0 : dot(sub(top, base1), line) / lengthSq;
		float[] closest = {base1[0] + line[0] * t, base1[1] + line[1] * t, base1[2] + line[2] * t};
		float[] normal = sub(closest, top);
		return dot(normal, sub(check, base2)) > 0;
	}

	private void addQuad(BoneBuilder bone, float[] positions, float[] uvs, float[] normal, int texture) {
		if (quadCount >= limits.maxQuads()) {
			tooManyQuads = true;
			return;
		}
		quadCount++;
		bone.quads.add(new Quad(positions, uvs, normal, texture));
	}

	/** Converts an absolute Blockbench position to model space relative to the bone's pivot. */
	private static void toBone(float[] p, BoneBuilder bone, float[] out, int offset) {
		out[offset] = -(p[0] - bone.originBb[0]);
		out[offset + 1] = -(p[1] - bone.originBb[1]);
		out[offset + 2] = p[2] - bone.originBb[2];
	}

	private static float[] toModelDirection(float[] direction) {
		return new float[] {-direction[0], -direction[1], direction[2]};
	}

	private float[] cubeRotation(JsonObject cube) {
		JsonElement rotation = cube.get("rotation");
		if (rotation != null && rotation.isJsonObject()) {
			// Java block format single-axis rotation: {"angle": 22.5, "axis": "y"}
			JsonObject r = rotation.getAsJsonObject();
			float angle = clampCoordinate(number(r, "angle", 0));
			return switch (string(r, "axis", "y")) {
				case "x" -> new float[] {angle, 0, 0};
				case "z" -> new float[] {0, 0, angle};
				default -> new float[] {0, angle, 0};
			};
		}
		return vector(cube, "rotation", 0);
	}

	/** Rotation matrix Rz * Ry * Rx for Blockbench Euler angles in degrees (three.js order "ZYX"). */
	private static double[][] rotationMatrix(float[] degrees) {
		double x = Math.toRadians(degrees[0]);
		double y = Math.toRadians(degrees[1]);
		double z = Math.toRadians(degrees[2]);
		double cx = Math.cos(x), sx = Math.sin(x);
		double cy = Math.cos(y), sy = Math.sin(y);
		double cz = Math.cos(z), sz = Math.sin(z);
		return new double[][] {
			{cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx},
			{sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx},
			{-sy, cy * sx, cy * cx},
		};
	}

	private static float[] rotate(float[] v, double[][] m) {
		return new float[] {
			(float) (m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2]),
			(float) (m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2]),
			(float) (m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]),
		};
	}

	private static float[] rotateAbout(float[] p, float[] pivot, double[][] m) {
		float[] r = rotate(new float[] {p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2]}, m);
		return new float[] {r[0] + pivot[0], r[1] + pivot[1], r[2] + pivot[2]};
	}

	// ---------------------------------------------------------------- animations

	private List<AnimationClip> readAnimations(JsonObject root) throws InvalidModelException {
		JsonArray animations = array(root, "animations");
		if (animations == null || animations.isEmpty()) {
			return List.of();
		}
		if (animations.size() > limits.maxAnimations()) {
			throw new InvalidModelException("too_many_animations", "The model has " + animations.size() + " animations, the limit is " + limits.maxAnimations());
		}
		// Format 5 stores keyframes in Blockbench's axes; older files negate X (position, rotation) and Y (rotation).
		float[] rotationSigns = animationsInBlockbenchAxes ? new float[] {-1, -1, 1} : new float[] {1, 1, 1};
		float[] positionSigns = animationsInBlockbenchAxes ? new float[] {-1, -1, 1} : new float[] {1, -1, 1};
		float[] scaleSigns = {1, 1, 1};

		List<AnimationClip> clips = new ArrayList<>();
		Set<String> names = new LinkedHashSet<>();
		int keyframeTotal = 0;
		for (JsonElement element : animations) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject animation = element.getAsJsonObject();
			String name = sanitizeName(string(animation, "name", "animation_" + clips.size()));
			if (!names.add(name)) {
				warn("duplicate_animation", "Two animations are named \"" + name + "\"; both are kept");
			}
			LoopMode loop = LoopMode.fromBlockbench(string(animation, "loop", "once"));
			float length = Math.max(0, clampCoordinate(number(animation, "length", 0)));
			float startDelay = Math.max(0, constantNumber(animation.get("start_delay")));
			float loopDelay = Math.max(0, constantNumber(animation.get("loop_delay")));

			List<BoneTrack> tracks = new ArrayList<>();
			JsonObject animators = object(animation, "animators");
			float lastKeyframe = 0;
			if (animators != null) {
				for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
					if (!entry.getValue().isJsonObject()) {
						continue;
					}
					JsonObject animator = entry.getValue().getAsJsonObject();
					String type = string(animator, "type", "bone");
					if (type.equals("effect")) {
						warnOnce("effect_animator", "Sound, particle and instruction keyframes are not supported and were ignored");
						continue;
					}
					if (!type.equals("bone")) {
						warnOnce("animator_" + type, "Animating elements of type \"" + type + "\" is not supported");
						continue;
					}
					Integer boneIndex = boneByUuid.get(entry.getKey());
					if (boneIndex == null) {
						if (!excludedGroupUuids.contains(entry.getKey())) {
							warnOnce("unknown_animator", "Animation \"" + name + "\" animates a group that does not exist in the model");
						}
						continue;
					}
					JsonArray keyframes = array(animator, "keyframes");
					if (keyframes == null) {
						continue;
					}
					keyframeTotal += keyframes.size();
					if (keyframeTotal > limits.maxKeyframes()) {
						throw new InvalidModelException("too_many_keyframes", "The model has more than " + limits.maxKeyframes() + " keyframes");
					}
					List<Keyframe> rotation = new ArrayList<>();
					List<Keyframe> position = new ArrayList<>();
					List<Keyframe> scale = new ArrayList<>();
					for (JsonElement keyframeElement : keyframes) {
						if (!keyframeElement.isJsonObject()) {
							continue;
						}
						JsonObject keyframe = keyframeElement.getAsJsonObject();
						String channel = string(keyframe, "channel", "");
						List<Keyframe> target = switch (channel) {
							case "rotation" -> rotation;
							case "position" -> position;
							case "scale" -> scale;
							default -> null;
						};
						if (target == null) {
							continue;
						}
						Keyframe parsed = readKeyframe(keyframe, channel.equals("scale") ? 1 : 0, name);
						if (parsed != null) {
							target.add(parsed);
							lastKeyframe = Math.max(lastKeyframe, parsed.time());
						}
					}
					BoneTrack track = new BoneTrack(boneIndex, channel(rotation, rotationSigns), channel(position, positionSigns), channel(scale, scaleSigns));
					if (track.rotation() != null || track.position() != null || track.scale() != null) {
						tracks.add(track);
					}
				}
			}
			if (length <= 0) {
				length = lastKeyframe;
			}
			clips.add(new AnimationClip(name, loop, length, startDelay, loopDelay, List.copyOf(tracks)));
		}
		return List.copyOf(clips);
	}

	private Keyframe readKeyframe(JsonObject keyframe, double defaultValue, String animationName) {
		float time = number(keyframe, "time", 0);
		if (!(time >= 0) || time > 3600) {
			return null;
		}
		JsonArray points = array(keyframe, "data_points");
		if (points == null || points.isEmpty() || !points.get(0).isJsonObject()) {
			return null;
		}
		MolangExpression[] pre = readDataPoint(points.get(0).getAsJsonObject(), defaultValue, animationName);
		boolean discontinuous = points.size() > 1 && points.get(1).isJsonObject();
		MolangExpression[] post = discontinuous ? readDataPoint(points.get(1).getAsJsonObject(), defaultValue, animationName) : pre;
		Interpolation interpolation = Interpolation.fromBlockbench(string(keyframe, "interpolation", "linear"));
		return new Keyframe(
			time,
			pre,
			post,
			discontinuous,
			interpolation,
			floats(keyframe, "bezier_left_time"),
			floats(keyframe, "bezier_left_value"),
			floats(keyframe, "bezier_right_time"),
			floats(keyframe, "bezier_right_value")
		);
	}

	private MolangExpression[] readDataPoint(JsonObject point, double defaultValue, String animationName) {
		String[] axes = {"x", "y", "z"};
		MolangExpression[] result = new MolangExpression[3];
		for (int i = 0; i < 3; i++) {
			JsonElement value = point.get(axes[i]);
			result[i] = MolangExpression.constant(defaultValue);
			if (value == null || !value.isJsonPrimitive()) {
				continue;
			}
			JsonPrimitive primitive = value.getAsJsonPrimitive();
			if (primitive.isNumber()) {
				double number = primitive.getAsDouble();
				result[i] = MolangExpression.constant(Double.isFinite(number) ? number : defaultValue);
			} else if (primitive.isString()) {
				try {
					result[i] = Molang.parse(primitive.getAsString(), limits.maxExpressionLength());
				} catch (MolangException e) {
					warnOnce("molang", "Animation \"" + animationName + "\" has an unsupported expression (" + shorten(e.getMessage(), 80) + "); it evaluates to " + defaultValue);
				}
			}
		}
		return result;
	}

	private static Channel channel(List<Keyframe> keyframes, float[] signs) {
		if (keyframes.isEmpty()) {
			return null;
		}
		keyframes.sort(Comparator.comparingDouble(Keyframe::time));
		return new Channel(keyframes.toArray(new Keyframe[0]), signs);
	}

	private static float constantNumber(JsonElement element) {
		if (element == null || !element.isJsonPrimitive()) {
			return 0;
		}
		try {
			float value = element.getAsJsonPrimitive().isNumber() ? element.getAsFloat() : Float.parseFloat(element.getAsString().trim());
			return Float.isFinite(value) ? Math.min(value, 3600) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// ---------------------------------------------------------------- assembly

	private CosmeticModel build(String name, List<AnimationClip> animations) throws InvalidModelException {
		// Keep only textures that rendered faces use, so reference textures cost nothing.
		Map<Integer, Integer> remap = new LinkedHashMap<>();
		for (BoneBuilder bone : bones) {
			for (Quad quad : bone.quads) {
				remap.putIfAbsent(quad.texture(), remap.size());
			}
		}
		List<TextureData> textures = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : remap.entrySet()) {
			TextureSlot slot = textureSlots.get(entry.getKey());
			if (slot.problem() != null) {
				throw new InvalidModelException("texture", slot.problem());
			}
			textures.add(new TextureData(slot.name(), slot.png(), slot.info().width(), slot.info().height(), false));
		}

		List<Bone> result = new ArrayList<>();
		for (BoneBuilder bone : bones) {
			float[] parentOrigin;
			if (bone.parent >= 0) {
				parentOrigin = bones.get(bone.parent).originBb;
			} else {
				parentOrigin = new float[] {bone.anchor.pivotX(), bone.anchor.pivotY(), bone.anchor.pivotZ()};
			}
			List<Quad> quads = new ArrayList<>(bone.quads.size());
			for (Quad quad : bone.quads) {
				quads.add(new Quad(quad.positions(), quad.uvs(), quad.normal(), remap.get(quad.texture())));
			}
			result.add(new Bone(
				bone.index,
				bone.name,
				bone.uuid,
				bone.parent,
				bone.anchor,
				-(bone.originBb[0] - parentOrigin[0]),
				-(bone.originBb[1] - parentOrigin[1]),
				bone.originBb[2] - parentOrigin[2],
				(float) Math.toRadians(-bone.rotationBb[0]),
				(float) Math.toRadians(-bone.rotationBb[1]),
				(float) Math.toRadians(bone.rotationBb[2]),
				List.copyOf(quads)
			));
		}
		if (quadCount == 0) {
			if (bones.isEmpty() && elementsByUuid.isEmpty()) {
				throw new InvalidModelException("empty", "The model contains no geometry");
			}
			warn("no_geometry", "The model has no visible faces (faces without a texture are not rendered)");
		}
		return new CosmeticModel(name, List.copyOf(result), List.copyOf(textures), animations, quadCount);
	}

	// ---------------------------------------------------------------- helpers

	/** Thrown for problems that make the whole model unusable. */
	public static final class InvalidModelException extends Exception {
		final String code;

		public InvalidModelException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	private void warn(String code, String message) {
		issues.add(Issue.warning(code, message));
	}

	private void warnOnce(String code, String message) {
		if (reportedCodes.add(code)) {
			warn(code, message);
		}
	}

	private static int majorVersion(String version) {
		try {
			int dot = version.indexOf('.');
			return Integer.parseInt(dot < 0 ? version.trim() : version.substring(0, dot).trim());
		} catch (NumberFormatException e) {
			return 4;
		}
	}

	private static JsonObject object(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static JsonArray array(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	private static String string(JsonObject parent, String key, String fallback) {
		JsonElement element = parent.get(key);
		if (element != null && element.isJsonPrimitive()) {
			return element.getAsString();
		}
		return fallback;
	}

	private static boolean bool(JsonObject parent, String key, boolean fallback) {
		JsonElement element = parent.get(key);
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
			return element.getAsBoolean();
		}
		return fallback;
	}

	private static float number(JsonObject parent, String key, float fallback) {
		JsonElement element = parent.get(key);
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			float value = element.getAsFloat();
			return Float.isFinite(value) ? value : fallback;
		}
		return fallback;
	}

	private static float positive(float value, float fallback) {
		return value > 0 && value <= 65536 ? value : fallback;
	}

	private static float clampCoordinate(float value) {
		return Math.max(-MAX_COORDINATE, Math.min(MAX_COORDINATE, value));
	}

	private static float[] vector(JsonObject parent, String key, float fallback) {
		float[] result = vectorOf(parent.get(key));
		if (result == null) {
			result = new float[] {fallback, fallback, fallback};
		}
		return result;
	}

	private static float[] vectorOf(JsonElement element) {
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < 3) {
			return null;
		}
		JsonArray array = element.getAsJsonArray();
		float[] result = new float[3];
		for (int i = 0; i < 3; i++) {
			JsonElement value = array.get(i);
			float f = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsFloat() : 0;
			result[i] = Float.isFinite(f) ? clampCoordinate(f) : 0;
		}
		return result;
	}

	private static float[] vector2(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		float[] result = new float[2];
		if (element != null && element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			for (int i = 0; i < Math.min(2, array.size()); i++) {
				JsonElement value = array.get(i);
				float f = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsFloat() : 0;
				result[i] = Float.isFinite(f) ? clampCoordinate(f) : 0;
			}
		}
		return result;
	}

	private static float[] vector4(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < 4) {
			return null;
		}
		JsonArray array = element.getAsJsonArray();
		float[] result = new float[4];
		for (int i = 0; i < 4; i++) {
			JsonElement value = array.get(i);
			float f = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsFloat() : 0;
			result[i] = Float.isFinite(f) ? clampCoordinate(f) : 0;
		}
		return result;
	}

	private static float[] floats(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		if (element == null || !element.isJsonArray()) {
			return null;
		}
		JsonArray array = element.getAsJsonArray();
		float[] result = new float[Math.min(3, array.size())];
		for (int i = 0; i < result.length; i++) {
			JsonElement value = array.get(i);
			float f = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsFloat() : 0;
			result[i] = Float.isFinite(f) ? clampCoordinate(f) : 0;
		}
		return result;
	}

	private static float[] sub(float[] a, float[] b) {
		return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static float dot(float[] a, float[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static float[] normalize(float[] v) {
		float length = (float) Math.sqrt(dot(v, v));
		return length == 0 ? new float[] {0, 1, 0} : new float[] {v[0] / length, v[1] / length, v[2] / length};
	}

	/** Strips control characters and limits length so names are safe to display and log. */
	public static String sanitizeName(String name) {
		if (name == null) {
			return null;
		}
		StringBuilder builder = new StringBuilder(Math.min(name.length(), MAX_NAME_LENGTH));
		for (int i = 0; i < name.length() && builder.length() < MAX_NAME_LENGTH; i++) {
			char c = name.charAt(i);
			if (!Character.isISOControl(c) && c != '§') {
				builder.append(c);
			}
		}
		return builder.toString().trim();
	}

	private static String shorten(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "...";
	}
}
