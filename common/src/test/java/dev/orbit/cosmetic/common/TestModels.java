package dev.orbit.cosmetic.common;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.common.util.DataUrls;
import dev.orbit.cosmetic.common.util.PngWriter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Builds small .bbmodel documents for tests. */
public final class TestModels {
	private static final Gson GSON = new Gson();

	public final JsonObject root = new JsonObject();
	public final JsonArray elements = new JsonArray();
	public final JsonArray outliner = new JsonArray();
	public final JsonArray textures = new JsonArray();
	public final JsonArray animations = new JsonArray();
	private int counter;

	public TestModels(String formatVersion) {
		JsonObject meta = new JsonObject();
		meta.addProperty("format_version", formatVersion);
		meta.addProperty("model_format", "free");
		meta.addProperty("box_uv", false);
		root.add("meta", meta);
		root.addProperty("name", "test");
		JsonObject resolution = new JsonObject();
		resolution.addProperty("width", 16);
		resolution.addProperty("height", 16);
		root.add("resolution", resolution);
		root.add("elements", elements);
		root.add("outliner", outliner);
		root.add("textures", textures);
		root.add("animations", animations);
	}

	public static TestModels v4() {
		return new TestModels("4.10");
	}

	public TestModels texture(int size) {
		int[] pixels = new int[size * size];
		java.util.Arrays.fill(pixels, 0xFFFFFFFF);
		JsonObject texture = new JsonObject();
		texture.addProperty("name", "t" + textures.size() + ".png");
		texture.addProperty("uuid", uuid());
		texture.addProperty("uv_width", 16);
		texture.addProperty("uv_height", 16);
		texture.addProperty("source", DataUrls.encodePng(PngWriter.write(size, size, pixels)));
		textures.add(texture);
		return this;
	}

	public JsonObject group(String name, float x, float y, float z) {
		JsonObject group = new JsonObject();
		group.addProperty("name", name);
		group.addProperty("uuid", uuid());
		group.add("origin", array(x, y, z));
		group.add("rotation", array(0, 0, 0));
		group.add("children", new JsonArray());
		return group;
	}

	public JsonObject cube(float[] from, float[] to) {
		JsonObject cube = new JsonObject();
		cube.addProperty("name", "cube" + counter);
		cube.addProperty("uuid", uuid());
		cube.add("from", array(from));
		cube.add("to", array(to));
		cube.add("origin", array(0, 0, 0));
		JsonObject faces = new JsonObject();
		for (String face : new String[] {"north", "south", "east", "west", "up", "down"}) {
			JsonObject f = new JsonObject();
			f.add("uv", array(0, 0, 16, 16));
			f.addProperty("texture", 0);
			faces.add(face, f);
		}
		cube.add("faces", faces);
		elements.add(cube);
		return cube;
	}

	/** Adds a cube as a child of the group (or the outliner root when group is null). */
	public JsonObject cubeIn(JsonObject group, float[] from, float[] to) {
		JsonObject cube = cube(from, to);
		(group == null ? outliner : group.getAsJsonArray("children")).add(cube.get("uuid").getAsString());
		return cube;
	}

	public byte[] bytes() {
		return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
	}

	public String uuid() {
		return UUID.nameUUIDFromBytes(("test" + counter++).getBytes(StandardCharsets.UTF_8)).toString();
	}

	public static JsonArray array(float... values) {
		JsonArray array = new JsonArray();
		for (float value : values) {
			array.add(value);
		}
		return array;
	}

	public static float[] v(float... values) {
		return values;
	}
}
