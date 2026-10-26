package dev.orbit.cosmetic.common.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.common.TestModels;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.molang.MolangContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static dev.orbit.cosmetic.common.TestModels.v;
import static org.junit.jupiter.api.Assertions.*;

class BBModelParserTest {
	private static final float EPS = 1e-4f;

	private static BBModelParser.Result parse(TestModels model) {
		return BBModelParser.parse(model.bytes(), "fallback", ModelLimits.DEFAULT);
	}

	private static Bone bone(CosmeticModel model, String name) {
		return model.bones().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
	}

	private static float[] range(Bone bone, int axis) {
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		for (Quad quad : bone.quads()) {
			for (int i = 0; i < 4; i++) {
				float value = quad.positions()[i * 3 + axis];
				min = Math.min(min, value);
				max = Math.max(max, value);
			}
		}
		return new float[] {min, max};
	}

	private static Quad faceWithNormal(Bone bone, float nx, float ny, float nz) {
		for (Quad quad : bone.quads()) {
			float[] n = quad.normal();
			if (Math.abs(n[0] - nx) < EPS && Math.abs(n[1] - ny) < EPS && Math.abs(n[2] - nz) < EPS) {
				return quad;
			}
		}
		throw new AssertionError("no face with normal " + nx + "," + ny + "," + nz);
	}

	@Test
	void headAnchorConvertsToModelSpace() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject head = m.group("HEAD", 0, 24, 0);
		m.outliner.add(head);
		// A flat cap resting on top of the 8x8x8 head (head top is at y = 32 in Blockbench).
		m.cubeIn(head, v(-4, 32, -4), v(4, 33, 4));
		BBModelParser.Result result = parse(m);
		assertTrue(result.ok(), result.issues().toString());

		Bone bone = bone(result.model(), "HEAD");
		assertEquals(Anchor.HEAD, bone.anchor());
		assertEquals(-1, bone.parent());
		assertArrayEquals(new float[] {0, 0, 0}, new float[] {bone.pivotX(), bone.pivotY(), bone.pivotZ()}, EPS);
		// Model space is Y down: 8 to 9 pixels above the head pivot.
		assertArrayEquals(new float[] {-9, -8}, range(bone, 1), EPS);
		assertArrayEquals(new float[] {-4, 4}, range(bone, 0), EPS);
		assertArrayEquals(new float[] {-4, 4}, range(bone, 2), EPS);
		assertEquals(6, bone.quads().size());

		// The Blockbench "up" face points up, which is -Y in model space; "north" stays -Z (the face side).
		Quad up = faceWithNormal(bone, 0, -1, 0);
		for (int i = 0; i < 4; i++) {
			assertEquals(-9, up.positions()[i * 3 + 1], EPS);
		}
		Quad north = faceWithNormal(bone, 0, 0, -1);
		for (int i = 0; i < 4; i++) {
			assertEquals(-4, north.positions()[i * 3 + 2], EPS);
		}
	}

	@Test
	void armAndLegAnchorsMatchVanillaPivots() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject right = m.group("RIGHT_ARM", 5, 22, 0);
		JsonObject left = m.group("LEFT_ARM", -5, 22, 0);
		JsonObject rightLeg = m.group("RIGHT_LEG", 1.9f, 12, 0);
		m.outliner.add(right);
		m.outliner.add(left);
		m.outliner.add(rightLeg);
		// Exactly the vanilla arm boxes in Blockbench coordinates.
		m.cubeIn(right, v(4, 12, -2), v(8, 24, 2));
		m.cubeIn(left, v(-8, 12, -2), v(-4, 24, 2));
		m.cubeIn(rightLeg, v(-0.1f, 0, -2), v(3.9f, 12, 2));
		CosmeticModel model = parse(m).model();
		assertNotNull(model);

		// Vanilla: right_arm box is x -3..1, y -2..10 around its pivot; left_arm is x -1..3.
		assertArrayEquals(new float[] {-3, 1}, range(bone(model, "RIGHT_ARM"), 0), EPS);
		assertArrayEquals(new float[] {-2, 10}, range(bone(model, "RIGHT_ARM"), 1), EPS);
		assertArrayEquals(new float[] {-1, 3}, range(bone(model, "LEFT_ARM"), 0), EPS);
		// Vanilla: right_leg box is x -2..2, y 0..12 around its pivot.
		assertArrayEquals(new float[] {-2, 2}, range(bone(model, "RIGHT_LEG"), 0), EPS);
		assertArrayEquals(new float[] {0, 12}, range(bone(model, "RIGHT_LEG"), 1), EPS);
		assertEquals(java.util.Set.of(Anchor.RIGHT_ARM, Anchor.LEFT_ARM, Anchor.RIGHT_LEG), model.anchorsUsed());
	}

	@Test
	void unanchoredGeometryIsPlayerRoot() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject ring = m.group("halo_group", 0, 36, 0);
		m.outliner.add(ring);
		m.cubeIn(ring, v(-1, 36, -1), v(1, 37, 1));
		m.cubeIn(null, v(-1, 0, -1), v(1, 1, 1)); // loose cube at the feet
		CosmeticModel model = parse(m).model();
		assertNotNull(model);

		Bone halo = bone(model, "halo_group");
		assertEquals(Anchor.ROOT, halo.anchor());
		// Pivot 12 px above the root pivot (24 px above the feet), Y down.
		assertEquals(-12, halo.pivotY(), EPS);

		Bone root = bone(model, "root");
		assertEquals(Anchor.ROOT, root.anchor());
		// The feet are 24 px below the model origin.
		assertArrayEquals(new float[] {23, 24}, range(root, 1), EPS);
	}

	@Test
	void nestedGroupPivotAndRotationAreConverted() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject head = m.group("HEAD", 0, 24, 0);
		JsonObject horn = m.group("horn", 2, 30, 1);
		horn.add("rotation", TestModels.array(10, 20, 30));
		head.getAsJsonArray("children").add(horn);
		m.outliner.add(head);
		m.cubeIn(horn, v(2, 30, 1), v(3, 33, 2));
		CosmeticModel model = parse(m).model();
		assertNotNull(model);

		Bone bone = bone(model, "horn");
		assertEquals(bone(model, "HEAD").index(), bone.parent());
		assertEquals(Anchor.HEAD, bone.anchor());
		assertArrayEquals(new float[] {-2, -6, 1}, new float[] {bone.pivotX(), bone.pivotY(), bone.pivotZ()}, EPS);
		assertEquals(Math.toRadians(-10), bone.rotX(), EPS);
		assertEquals(Math.toRadians(-20), bone.rotY(), EPS);
		assertEquals(Math.toRadians(30), bone.rotZ(), EPS);
		// Geometry is relative to the horn pivot: x 2..3 -> -1..0, y 30..33 -> -3..0, z 1..2 -> 0..1
		assertArrayEquals(new float[] {-1, 0}, range(bone, 0), EPS);
		assertArrayEquals(new float[] {-3, 0}, range(bone, 1), EPS);
		assertArrayEquals(new float[] {0, 1}, range(bone, 2), EPS);
	}

	@Test
	void cubeRotationIsBakedAroundItsOrigin() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject cube = m.cubeIn(null, v(0, 0, 0), v(2, 1, 1));
		cube.add("rotation", TestModels.array(0, 90, 0));
		CosmeticModel model = parse(m).model();
		assertNotNull(model);
		// Blockbench +90 degrees about Y turns +X into -Z: the 2px long side now runs from z = 0 to z = -2.
		Bone root = bone(model, "root");
		assertArrayEquals(new float[] {-2, 0}, range(root, 2), EPS);
		assertArrayEquals(new float[] {-1, 0}, range(root, 0), EPS);
	}

	@Test
	void faceUvsAndRotationFollowBlockbench() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject cube = m.cubeIn(null, v(0, 0, 0), v(1, 1, 1));
		JsonObject north = cube.getAsJsonObject("faces").getAsJsonObject("north");
		north.add("uv", TestModels.array(0, 0, 8, 4));
		CosmeticModel model = parse(m).model();
		Quad face = faceWithNormal(bone(model, "root"), 0, 0, -1);
		// Corner order: top-left, top-right, bottom-right, bottom-left.
		assertArrayEquals(new float[] {0, 0, 0.5f, 0, 0.5f, 0.25f, 0, 0.25f}, face.uvs(), EPS);
		// Top-left of the north face (seen from the front) is Blockbench (x=1, y=1, z=0) -> model (-1, 23, 0).
		assertArrayEquals(new float[] {-1, 23, 0}, new float[] {face.positions()[0], face.positions()[1], face.positions()[2]}, EPS);

		north.addProperty("rotation", 90);
		face = faceWithNormal(bone(parse(m).model(), "root"), 0, 0, -1);
		// Rotated 90 degrees: the top-left corner shows the texture's bottom-left.
		assertArrayEquals(new float[] {0, 0.25f, 0, 0, 0.5f, 0, 0.5f, 0.25f}, face.uvs(), EPS);
	}

	@Test
	void boxUvMatchesVanillaLayout() {
		TestModels m = TestModels.v4().texture(64);
		m.root.getAsJsonObject("resolution").addProperty("width", 64);
		m.root.getAsJsonObject("resolution").addProperty("height", 64);
		JsonObject texture = m.textures.get(0).getAsJsonObject();
		texture.addProperty("uv_width", 64);
		texture.addProperty("uv_height", 64);
		JsonObject body = m.cubeIn(null, v(-4, 12, -2), v(4, 24, 2));
		body.addProperty("box_uv", true);
		body.add("uv_offset", TestModels.array(16, 16));
		Bone root = bone(parse(m).model(), "root");
		// Vanilla body texture: front (north) at (20,20)-(28,32), top at (20,16)-(28,20).
		Quad north = faceWithNormal(root, 0, 0, -1);
		assertEquals(20 / 64f, north.uvs()[0], EPS);
		assertEquals(20 / 64f, north.uvs()[1], EPS);
		assertEquals(28 / 64f, north.uvs()[4], EPS);
		assertEquals(32 / 64f, north.uvs()[5], EPS);
		Quad down = faceWithNormal(root, 0, 1, 0);
		// Bottom face region (28,16)-(36,20), mirrored horizontally as in vanilla.
		assertEquals(36 / 64f, down.uvs()[0], EPS);
		assertEquals(28 / 64f, down.uvs()[2], EPS);
	}

	@Test
	void referenceAndNonExportedGeometryAreSkipped() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject reference = m.group(BBModelParser.REFERENCE_GROUP, 0, 0, 0);
		m.outliner.add(reference);
		m.cubeIn(reference, v(-4, 24, -4), v(4, 32, 4));
		JsonObject hidden = m.cubeIn(null, v(0, 0, 0), v(1, 1, 1));
		hidden.addProperty("export", false);
		JsonObject head = m.group("HEAD", 0, 24, 0);
		m.outliner.add(head);
		m.cubeIn(head, v(-1, 32, -1), v(1, 34, 1));
		CosmeticModel model = parse(m).model();
		assertNotNull(model);
		assertEquals(6, model.quadCount());
		assertEquals(java.util.Set.of(Anchor.HEAD), model.anchorsUsed());
	}

	@Test
	void format5GroupsArrayIsSupported() {
		TestModels m = new TestModels("5.0").texture(16);
		JsonObject head = m.group("HEAD", 0, 24, 0);
		m.cube(v(-1, 32, -1), v(1, 34, 1));
		JsonArray groups = new JsonArray();
		JsonObject groupProps = head.deepCopy();
		groupProps.remove("children");
		groups.add(groupProps);
		m.root.add("groups", groups);
		JsonObject reference = new JsonObject();
		reference.addProperty("uuid", head.get("uuid").getAsString());
		JsonArray children = new JsonArray();
		children.add(m.elements.get(0).getAsJsonObject().get("uuid").getAsString());
		reference.add("children", children);
		m.outliner.add(reference);
		CosmeticModel model = parse(m).model();
		assertNotNull(model);
		assertEquals(Anchor.HEAD, bone(model, "HEAD").anchor());
		assertEquals(6, bone(model, "HEAD").quads().size());
	}

	@Test
	void animationAxesDependOnFormatVersion() {
		for (String version : new String[] {"4.10", "5.0"}) {
			TestModels m = new TestModels(version).texture(16);
			JsonObject head = m.group("HEAD", 0, 24, 0);
			m.outliner.add(head);
			m.cubeIn(head, v(-1, 32, -1), v(1, 34, 1));
			m.animations.add(animation(head.get("uuid").getAsString(), "rotation", 10, 20, 30));
			m.animations.add(animation(head.get("uuid").getAsString(), "position", 1, 2, 3));
			CosmeticModel model = parse(m).model();
			assertNotNull(model, version);
			double[] rotation = sample(model.animations().get(0));
			double[] position = sample(model.animations().get(1));
			if (version.startsWith("5")) {
				// Format 5 stores Blockbench axes: X and Y flip in model space.
				assertArrayEquals(new double[] {-10, -20, 30}, rotation, 1e-6, version);
				assertArrayEquals(new double[] {-1, -2, 3}, position, 1e-6, version);
			} else {
				// Older files store X position/rotation and Y rotation already negated.
				assertArrayEquals(new double[] {10, 20, 30}, rotation, 1e-6, version);
				assertArrayEquals(new double[] {1, -2, 3}, position, 1e-6, version);
			}
		}
	}

	private static double[] sample(AnimationClip clip) {
		double[] result = new double[3];
		clip.sample(0, false, new MolangContext(), (bone, channel, x, y, z) -> {
			boolean rotation = channel == dev.orbit.cosmetic.common.anim.PoseBuffer.ROTATION;
			result[0] = rotation ? Math.toDegrees(x) : x;
			result[1] = rotation ? Math.toDegrees(y) : y;
			result[2] = rotation ? Math.toDegrees(z) : z;
		});
		return result;
	}

	private static JsonObject animation(String bone, String channel, float x, float y, float z) {
		JsonObject animation = new JsonObject();
		animation.addProperty("name", "IDLE");
		animation.addProperty("loop", "loop");
		animation.addProperty("length", 1);
		JsonObject animators = new JsonObject();
		JsonObject animator = new JsonObject();
		animator.addProperty("type", "bone");
		JsonArray keyframes = new JsonArray();
		JsonObject keyframe = new JsonObject();
		keyframe.addProperty("channel", channel);
		keyframe.addProperty("time", 0);
		JsonArray points = new JsonArray();
		JsonObject point = new JsonObject();
		point.addProperty("x", x);
		point.addProperty("y", String.valueOf(y));
		point.addProperty("z", z);
		points.add(point);
		keyframe.add("data_points", points);
		keyframes.add(keyframe);
		animator.add("keyframes", keyframes);
		animators.add(bone, animator);
		animation.add("animators", animators);
		return animation;
	}

	@Test
	void meshQuadsAreSortedAndConverted() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject mesh = new JsonObject();
		mesh.addProperty("type", "mesh");
		mesh.addProperty("uuid", m.uuid());
		mesh.add("origin", TestModels.array(0, 24, 0));
		mesh.add("rotation", TestModels.array(0, 0, 0));
		JsonObject vertices = new JsonObject();
		// A unit square in the XZ plane, listed in "bow-tie" order (a, b, c, d where a-c is an edge).
		vertices.add("a", TestModels.array(0, 0, 0));
		vertices.add("b", TestModels.array(1, 0, 1));
		vertices.add("c", TestModels.array(1, 0, 0));
		vertices.add("d", TestModels.array(0, 0, 1));
		mesh.add("vertices", vertices);
		JsonObject faces = new JsonObject();
		JsonObject face = new JsonObject();
		JsonArray keys = new JsonArray();
		for (String key : new String[] {"a", "b", "c", "d"}) {
			keys.add(key);
		}
		face.add("vertices", keys);
		JsonObject uv = new JsonObject();
		uv.add("a", TestModels.array(0, 0));
		uv.add("b", TestModels.array(16, 16));
		uv.add("c", TestModels.array(16, 0));
		uv.add("d", TestModels.array(0, 16));
		face.add("uv", uv);
		face.addProperty("texture", 0);
		faces.add("f", face);
		mesh.add("faces", faces);
		m.elements.add(mesh);
		m.outliner.add(mesh.get("uuid").getAsString());
		CosmeticModel model = parse(m).model();
		assertNotNull(model);
		Quad quad = bone(model, "root").quads().getFirst();
		// After sorting, consecutive vertices must share an edge (no diagonal between neighbours).
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			float dx = Math.abs(quad.positions()[i * 3] - quad.positions()[j * 3]);
			float dz = Math.abs(quad.positions()[i * 3 + 2] - quad.positions()[j * 3 + 2]);
			assertEquals(1, dx + dz, EPS, "vertices " + i + " and " + j + " are not adjacent");
		}
		// The mesh sits at the model origin (24 px above the feet), so y is 0 in model space.
		assertEquals(0, quad.positions()[1], EPS);
		assertEquals(1, Math.abs(quad.normal()[1]), EPS);
	}

	@Test
	void missingTexturesOnlyFailWhenUsed() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject unused = new JsonObject();
		unused.addProperty("name", "external.png");
		unused.addProperty("relative_path", "../textures/external.png");
		m.textures.add(unused);
		m.cubeIn(null, v(0, 0, 0), v(1, 1, 1));
		assertTrue(parse(m).ok());

		m.elements.get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north").addProperty("texture", 1);
		BBModelParser.Result result = parse(m);
		assertFalse(result.ok());
		assertTrue(result.errors().getFirst().message().contains("not embedded"));
	}

	@Test
	void texturesAboveTheSizeLimitAreRejected() {
		TestModels m = TestModels.v4().texture(64);
		m.cubeIn(null, v(0, 0, 0), v(1, 1, 1));
		ModelLimits small = new ModelLimits(1 << 20, 100, 100, 10, 100, 4, 32, 1 << 20, 4, 100, 100);
		BBModelParser.Result result = BBModelParser.parse(m.bytes(), "x", small);
		assertFalse(result.ok());
		assertEquals("texture", result.errors().getFirst().code());
	}

	@Test
	void limitsAreEnforced() {
		TestModels m = TestModels.v4().texture(16);
		for (int i = 0; i < 20; i++) {
			m.cubeIn(null, v(i, 0, 0), v(i + 1, 1, 1));
		}
		ModelLimits limits = new ModelLimits(1 << 20, 10, 100, 10, 1000, 4, 64, 1 << 20, 4, 100, 100);
		assertEquals("too_many_elements", BBModelParser.parse(m.bytes(), "x", limits).errors().getFirst().code());

		ModelLimits faces = new ModelLimits(1 << 20, 100, 100, 10, 30, 4, 64, 1 << 20, 4, 100, 100);
		assertEquals("too_many_quads", BBModelParser.parse(m.bytes(), "x", faces).errors().getFirst().code());

		ModelLimits bytes = new ModelLimits(1024, 100, 100, 10, 1000, 4, 64, 1 << 20, 4, 100, 100);
		assertEquals("too_large", BBModelParser.parse(m.bytes(), "x", bytes).errors().getFirst().code());
	}

	@Test
	void deepNestingIsRejectedWithoutStackOverflow() {
		TestModels m = TestModels.v4().texture(16);
		JsonObject current = m.group("g0", 0, 0, 0);
		m.outliner.add(current);
		for (int i = 1; i < 40; i++) {
			JsonObject child = m.group("g" + i, 0, 0, 0);
			current.getAsJsonArray("children").add(child);
			current = child;
		}
		BBModelParser.Result result = parse(m);
		assertFalse(result.ok());

		StringBuilder deepJson = new StringBuilder("{\"meta\":{},\"x\":");
		deepJson.append("[".repeat(5000)).append("]".repeat(5000)).append('}');
		BBModelParser.Result json = BBModelParser.parse(deepJson.toString().getBytes(StandardCharsets.UTF_8), "x", ModelLimits.DEFAULT);
		assertFalse(json.ok());
	}

	@Test
	void malformedInputNeverThrows() {
		Random random = new Random(42);
		List<byte[]> inputs = new ArrayList<>();
		inputs.add(new byte[0]);
		inputs.add("[]".getBytes());
		inputs.add("{\"meta\": 5}".getBytes());
		inputs.add("{\"meta\":{},\"elements\":[{\"from\":\"a\",\"to\":null,\"faces\":{\"north\":{\"uv\":[1]}}}],\"outliner\":[1,null,{}]}".getBytes());
		inputs.add("{\"meta\":{},\"textures\":[{\"source\":\"data:image/png;base64,!!!!\"}],\"elements\":[{\"faces\":{\"up\":{\"uv\":[0,0,1,1],\"texture\":0}}}]}".getBytes());
		inputs.add("{\"meta\":{},\"animations\":[{\"animators\":{\"x\":{\"keyframes\":[{\"channel\":\"rotation\",\"data_points\":[{\"x\":\"math.sin(\"}]}]}}}]}".getBytes());
		byte[] valid = TestModels.v4().texture(16).bytes();
		for (int i = 0; i < 300; i++) {
			byte[] mutated = valid.clone();
			for (int j = 0; j < 1 + random.nextInt(20); j++) {
				mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
			}
			inputs.add(mutated);
		}
		for (byte[] input : inputs) {
			assertDoesNotThrow(() -> BBModelParser.parse(input, "x", ModelLimits.DEFAULT));
		}
	}

	@Test
	void templateIsValidAndRendersNothingByDefault() {
		byte[] template = TemplateGenerator.generate("orbit_template");
		BBModelParser.Result result = BBModelParser.parse(template, "orbit_template", ModelLimits.DEFAULT);
		assertTrue(result.ok(), result.issues().toString());
		CosmeticModel model = result.model();
		assertEquals(0, model.quadCount(), "reference and example geometry must not render");
		for (Anchor anchor : Anchor.values()) {
			if (anchor.isBodyPart()) {
				Bone bone = bone(model, anchor.name());
				assertEquals(anchor, bone.anchor());
				assertEquals(0, bone.pivotX(), EPS);
				assertEquals(0, bone.pivotY(), EPS);
			}
		}
		assertEquals(1, model.animations().size());
		assertEquals("IDLE", model.animations().getFirst().name());
		assertFalse(model.animations().getFirst().tracks().isEmpty());

		// Turning export on for the example hat makes it a working head cosmetic.
		JsonObject json = new Gson().fromJson(new String(template, StandardCharsets.UTF_8), JsonObject.class);
		for (var element : json.getAsJsonArray("elements")) {
			JsonObject cube = element.getAsJsonObject();
			if (cube.get("name").getAsString().startsWith("example_hat")) {
				cube.addProperty("export", true);
			}
		}
		CosmeticModel hat = BBModelParser.parse(new Gson().toJson(json).getBytes(StandardCharsets.UTF_8), "x", ModelLimits.DEFAULT).model();
		assertNotNull(hat);
		assertEquals(12, hat.quadCount());
		assertEquals(java.util.Set.of(Anchor.HEAD), hat.anchorsUsed());
		Bone example = bone(hat, "example_top_hat");
		// The brim sits on the top of the head: 8 px above the head pivot in model space.
		assertEquals(-8, example.pivotY(), EPS);
	}

	@Test
	void textureEmbedderInlinesExternalTextures() throws Exception {
		TestModels m = TestModels.v4();
		JsonObject texture = new JsonObject();
		texture.addProperty("name", "skin.png");
		texture.addProperty("relative_path", "skin.png");
		m.textures.add(texture);
		m.cubeIn(null, v(0, 0, 0), v(1, 1, 1));
		assertFalse(parse(m).ok());

		byte[] png = dev.orbit.cosmetic.common.util.PngWriter.write(2, 2, new int[] {-1, -1, -1, -1});
		TextureEmbedder.Result embedded = TextureEmbedder.embed(m.bytes(), ModelLimits.DEFAULT, (relative, path, name) -> "skin.png".equals(relative) ? png : null);
		assertEquals(1, embedded.embedded());
		assertTrue(BBModelParser.parse(embedded.bytes(), "x", ModelLimits.DEFAULT).ok());
	}
}
