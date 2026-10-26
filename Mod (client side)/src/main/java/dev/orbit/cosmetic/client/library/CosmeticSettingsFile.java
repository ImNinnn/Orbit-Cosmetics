package dev.orbit.cosmetic.client.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AnimationTrigger;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.LoopMode;
import dev.orbit.cosmetic.common.anim.PhysicsLink;
import dev.orbit.cosmetic.common.anim.PhysicsSource;
import dev.orbit.cosmetic.common.model.BBModelParser;
import dev.orbit.cosmetic.common.model.Bone;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.protocol.OrbitProtocol;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shareable settings of one cosmetic, stored next to the model with the same name:
 * {@code top_hat.bbmodel} has {@code top_hat.json}. It holds optional details (name, author, description), the
 * animation settings by animation name and the movement physics by part. Send both files to share a
 * configured cosmetic.
 *
 * <pre>{@code
 * {
 *   "orbitCosmetic": 1,
 *   "model": "top_hat.bbmodel",
 *   "name": "Top hat", "author": "...", "description": "...",
 *   "animations": { "IDLE": { "trigger": "IDLE", "loop": "LOOP", "speed": 1.0, "priority": 10, "interrupt": false } },
 *   "physics": { "<group uuid>": { "part": "brim", "rotation_x": { "source": "FORWARD", "strength": 8, "max": 40, "resistance": 0.6 } } }
 * }
 * }</pre>
 *
 * Files may come from other people, so they are read as untrusted data: size-limited, every value sanitized,
 * unknown keys kept untouched. A same-named JSON without the {@code orbitCosmetic} key belongs to another
 * program (Blockbench can export one) and is never overwritten.
 */
final class CosmeticSettingsFile {
	static final String MARKER = "orbitCosmetic";
	static final int FORMAT = 1;
	private static final int MAX_BYTES = 256 * 1024;
	private static final int MAX_TEXT = 512;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** What was read from disk. {@code root} keeps unknown keys so rewriting does not lose them. */
	record Data(
		JsonObject root,
		boolean exists,
		boolean foreign,
		String problem,
		long modified,
		String name,
		String author,
		String description,
		Map<String, AnimationSettings> animations,
		Map<String, List<AxisDrive>> physics
	) {
		static Data empty() {
			return new Data(new JsonObject(), false, false, null, -1, null, null, null, Map.of(), new LinkedHashMap<>());
		}
	}

	private CosmeticSettingsFile() {
	}

	static Path pathFor(Path model) {
		String file = model.getFileName().toString();
		String base = file.toLowerCase(Locale.ROOT).endsWith(".bbmodel") ? file.substring(0, file.length() - ".bbmodel".length()) : file;
		return model.resolveSibling(base + ".json");
	}

	static long modified(Path model) {
		try {
			Path file = pathFor(model);
			return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.getLastModifiedTime(file).toMillis() : -1;
		} catch (IOException e) {
			return -1;
		}
	}

	/** Reads the settings file of a model. Blocking; never throws. */
	static Data read(Path model) {
		Path file = pathFor(model);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			return Data.empty();
		}
		long modified = modified(model);
		JsonObject root;
		try {
			if (Files.size(file) > MAX_BYTES) {
				return new Data(new JsonObject(), true, true, file.getFileName() + " is larger than " + MAX_BYTES / 1024 + " KiB and was ignored", modified, null, null, null, Map.of(), new LinkedHashMap<>());
			}
			root = parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | JsonParseException | IllegalStateException | StackOverflowError e) {
			return new Data(new JsonObject(), true, true, file.getFileName() + " could not be read; it is left untouched and defaults are used", modified, null, null, null, Map.of(), new LinkedHashMap<>());
		}
		if (root == null || !root.has(MARKER)) {
			return new Data(new JsonObject(), true, true, file.getFileName() + " is not an Orbit settings file; settings changes are not saved to it", modified, null, null, null, Map.of(), new LinkedHashMap<>());
		}
		return new Data(root, true, false, null, modified, text(root, "name"), text(root, "author"), text(root, "description"),
			readAnimations(root), readPhysics(root));
	}

	/** Parses settings JSON (also used for imported files). Returns null if it is not a JSON object. */
	static JsonObject parse(String text) {
		JsonReader reader = new JsonReader(new StringReader(text));
		reader.setStrictness(Strictness.LENIENT);
		reader.setNestingLimit(32);
		JsonElement element = JsonParser.parseReader(reader);
		return element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static Map<String, AnimationSettings> readAnimations(JsonObject root) {
		Map<String, AnimationSettings> result = new LinkedHashMap<>();
		JsonObject animations = object(root, "animations");
		if (animations == null) {
			return result;
		}
		for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
			if (result.size() >= OrbitProtocol.MAX_ANIMATIONS || !entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject s = entry.getValue().getAsJsonObject();
			AnimationTrigger trigger = parseEnum(AnimationTrigger.class, text(s, "trigger"), AnimationTrigger.NONE);
			result.put(BBModelParser.sanitizeName(entry.getKey()), new AnimationSettings(
				trigger,
				parseEnum(LoopMode.class, text(s, "loop"), trigger.defaultLoop()),
				number(s, "speed", 1),
				(int) number(s, "priority", trigger.defaultPriority()),
				bool(s, "interrupt"),
				number(s, "minWait", AnimationSettings.DEFAULT_MIN_WAIT),
				number(s, "maxWait", AnimationSettings.DEFAULT_MAX_WAIT)
			));
		}
		return result;
	}

	private static Map<String, List<AxisDrive>> readPhysics(JsonObject root) {
		Map<String, List<AxisDrive>> result = new LinkedHashMap<>();
		JsonObject physics = object(root, "physics");
		if (physics == null) {
			return result;
		}
		for (Map.Entry<String, JsonElement> entry : physics.entrySet()) {
			if (result.size() >= OrbitProtocol.MAX_PHYSICS_LINKS || !entry.getValue().isJsonObject() || entry.getKey().length() > MAX_TEXT) {
				continue;
			}
			JsonObject part = entry.getValue().getAsJsonObject();
			List<AxisDrive> axes = new ArrayList<>(PhysicsLink.AXES);
			for (int axis = 0; axis < PhysicsLink.AXES; axis++) {
				JsonObject drive = object(part, PhysicsLink.AXIS_NAMES[axis]);
				axes.add(drive == null ? AxisDrive.OFF : new AxisDrive(
					parseEnum(PhysicsSource.class, text(drive, "source"), PhysicsSource.NONE),
					number(drive, "strength", 0),
					number(drive, "max", 0),
					number(drive, "resistance", 0.5f)
				).clamped(axis >= 3));
			}
			result.put(entry.getKey(), List.copyOf(axes));
		}
		return result;
	}

	/**
	 * Writes the settings, keeping details and unknown keys already in the file.
	 *
	 * @return the new modification time, or -1 if writing failed
	 */
	static long write(Path model, JsonObject root, CosmeticModel cosmetic, List<AnimationSettings> settings, Map<String, List<AxisDrive>> physics) {
		JsonObject out = root.deepCopy();
		out.addProperty(MARKER, FORMAT);
		out.addProperty("model", model.getFileName().toString());
		JsonObject animations = new JsonObject();
		for (int i = 0; i < cosmetic.animations().size() && i < settings.size(); i++) {
			AnimationClip clip = cosmetic.animations().get(i);
			AnimationSettings s = settings.get(i);
			JsonObject json = new JsonObject();
			json.addProperty("trigger", s.trigger().name());
			json.addProperty("loop", s.loop().name());
			json.addProperty("speed", s.speed());
			json.addProperty("priority", s.priority());
			json.addProperty("interrupt", s.interrupt());
			if (s.trigger() == AnimationTrigger.RANDOM) {
				json.addProperty("minWait", s.minWait());
				json.addProperty("maxWait", s.maxWait());
			}
			animations.add(clip.name(), json);
		}
		out.add("animations", animations);
		JsonObject parts = new JsonObject();
		for (Map.Entry<String, List<AxisDrive>> entry : physics.entrySet()) {
			JsonObject part = new JsonObject();
			String name = partName(cosmetic, entry.getKey());
			if (name != null) {
				part.addProperty("part", name);
			}
			for (int axis = 0; axis < PhysicsLink.AXES && axis < entry.getValue().size(); axis++) {
				AxisDrive drive = entry.getValue().get(axis);
				if (drive.source() == PhysicsSource.NONE && drive.strength() == 0 && drive.max() == 0) {
					continue;
				}
				JsonObject json = new JsonObject();
				json.addProperty("source", drive.source().name());
				json.addProperty("strength", drive.strength());
				json.addProperty("max", drive.max());
				json.addProperty("resistance", drive.resistance());
				part.add(PhysicsLink.AXIS_NAMES[axis], json);
			}
			parts.add(entry.getKey(), part);
		}
		out.add("physics", parts);
		Path file = pathFor(model);
		try {
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(out), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			root.entrySet().clear();
			out.entrySet().forEach(e -> root.add(e.getKey(), e.getValue()));
			return modified(model);
		} catch (IOException e) {
			return -1;
		}
	}

	/** Whether a file is an Orbit settings file (checked before importing a JSON next to a model). */
	static boolean isSettingsFile(Path file) {
		try {
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
				return false;
			}
			JsonObject root = parse(Files.readString(file, StandardCharsets.UTF_8));
			return root != null && root.has(MARKER);
		} catch (IOException | JsonParseException | IllegalStateException | StackOverflowError e) {
			return false;
		}
	}

	private static String partName(CosmeticModel model, String key) {
		for (Bone bone : model.bones()) {
			String boneKey = bone.uuid() != null ? bone.uuid() : "name:" + bone.name();
			if (boneKey.equals(key)) {
				return bone.name();
			}
		}
		return null;
	}

	private static JsonObject object(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static String text(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		String value = element.getAsString();
		if (value.length() > MAX_TEXT) {
			value = value.substring(0, MAX_TEXT);
		}
		// Strip control characters and formatting codes so shared text cannot mess up the UI.
		return value.replaceAll("[\\p{Cntrl}§]", " ").trim();
	}

	private static float number(JsonObject parent, String key, float fallback) {
		JsonElement element = parent.get(key);
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			float value = element.getAsFloat();
			return Float.isFinite(value) ? value : fallback;
		}
		return fallback;
	}

	private static boolean bool(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
		if (name == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
