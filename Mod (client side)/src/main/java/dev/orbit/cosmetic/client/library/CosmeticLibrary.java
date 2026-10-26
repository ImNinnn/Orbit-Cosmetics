package dev.orbit.cosmetic.client.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.OrbitPaths;
import dev.orbit.cosmetic.common.anim.AnimationClip;
import dev.orbit.cosmetic.common.anim.AnimationSettings;
import dev.orbit.cosmetic.common.anim.AnimationTrigger;
import dev.orbit.cosmetic.common.anim.AxisDrive;
import dev.orbit.cosmetic.common.anim.PhysicsSource;
import dev.orbit.cosmetic.common.protocol.OrbitProtocol;
import dev.orbit.cosmetic.common.anim.LoopMode;
import dev.orbit.cosmetic.common.model.BBModelParser;
import dev.orbit.cosmetic.common.model.Issue;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.model.TemplateGenerator;
import dev.orbit.cosmetic.common.model.TextureEmbedder;
import dev.orbit.cosmetic.common.protocol.AssetId;
import dev.orbit.cosmetic.common.util.PngInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The player's own cosmetics: every {@code .bbmodel} in the orbit folder. Files are validated in the background
 * and nothing in them is executed. Each cosmetic's animation settings, physics and details live in a JSON file
 * of the same name next to the model (see {@link CosmeticSettingsFile}), so a configured cosmetic can be shared
 * as two files. Which cosmetics are loaded (worn) is kept in {@code orbit/orbit-state.json}.
 */
public final class CosmeticLibrary {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_SCAN_DEPTH = 4;
	private static final int MAX_FILES = 512;

	/** Persisted form of orbit-state.json. */
	private static final class StateFile {
		List<String> loaded = new ArrayList<>();
		/** Legacy (before per-cosmetic settings files): migrated into the settings files, then removed. */
		Map<String, Map<String, SavedSettings>> animations = new LinkedHashMap<>();
		/** Legacy: per cosmetic, part key (group uuid, or "name:" + group name) to its six axes. */
		Map<String, Map<String, List<SavedAxis>>> physics = new LinkedHashMap<>();
	}

	private static final class SavedSettings {
		String trigger;
		String loop;
		float speed = 1;
		int priority;
		boolean interrupt;
		Float minWait;
		Float maxWait;
	}

	private static final class SavedAxis {
		String source;
		float strength;
		float max;
		float resistance = 0.5f;
	}

	private final OrbitPaths paths;
	private final Map<String, LocalCosmetic> entries = new LinkedHashMap<>();
	private final List<Runnable> listeners = new ArrayList<>();
	private StateFile state = new StateFile();
	private boolean scanning;
	/** Scans finished so far; failures found by the first one (game start) are not announced. */
	private int scans;

	public CosmeticLibrary(OrbitPaths paths) {
		this.paths = paths;
		loadState();
	}

	public void addListener(Runnable listener) {
		listeners.add(listener);
	}

	public void removeListener(Runnable listener) {
		listeners.remove(listener);
	}

	private void changed() {
		for (Runnable listener : List.copyOf(listeners)) {
			listener.run();
		}
	}

	public List<LocalCosmetic> entries() {
		return List.copyOf(entries.values());
	}

	public LocalCosmetic find(AssetId asset) {
		for (LocalCosmetic entry : entries.values()) {
			if (asset.equals(entry.asset) && entry.bytes != null) {
				return entry;
			}
		}
		return null;
	}

	/** Usable loaded cosmetics in a stable order. */
	public List<LocalCosmetic> loaded() {
		return entries.values().stream().filter(e -> e.loaded && e.isUsable()).toList();
	}

	public boolean isScanning() {
		return scanning;
	}

	// ---------------------------------------------------------------- scanning

	/** Rescans the orbit folder; changed files are re-validated in the background. */
	public void reload() {
		if (scanning) {
			return;
		}
		scanning = true;
		ModelLimits limits = OrbitClient.get().config().modelLimits();
		Util.ioPool().execute(() -> {
			List<Path> files = findFiles();
			Minecraft.getInstance().execute(() -> applyScan(files, limits));
		});
	}

	private List<Path> findFiles() {
		Path root = paths.root();
		try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH, new FileVisitOption[0])) {
			return stream
				.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bbmodel"))
				.filter(path -> !paths.isReserved(path))
				.sorted()
				.limit(MAX_FILES)
				.toList();
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not scan {}", root, e);
			return List.of();
		}
	}

	private void applyScan(List<Path> files, ModelLimits limits) {
		scanning = false;
		boolean announce = scans++ > 0;
		Set<String> seen = new LinkedHashSet<>();
		for (Path file : files) {
			String key = paths.root().relativize(file).toString().replace('\\', '/');
			seen.add(key);
			long modified;
			long size;
			try {
				modified = Files.getLastModifiedTime(file).toMillis();
				size = Files.size(file);
			} catch (IOException e) {
				continue;
			}
			LocalCosmetic entry = entries.get(key);
			if (entry != null && entry.modified == modified && entry.size == size && entry.status != LocalCosmetic.Status.CHECKING) {
				if (entry.status == LocalCosmetic.Status.READY && CosmeticSettingsFile.modified(file) != entry.settingsModified) {
					reloadSettings(entry);
				}
				continue;
			}
			if (entry == null) {
				entry = new LocalCosmetic(file, key);
				entry.loaded = state.loaded.contains(key);
				entries.put(key, entry);
			}
			entry.modified = modified;
			entry.size = size;
			entry.status = LocalCosmetic.Status.CHECKING;
			validate(entry, limits, announce);
		}
		entries.keySet().removeIf(key -> {
			if (!seen.contains(key)) {
				LocalCosmetic removed = entries.get(key);
				if (removed.asset != null) {
					OrbitClient.get().registry().invalidate(removed.asset);
				}
				return true;
			}
			return false;
		});
		updateDuplicates();
		changed();
	}

	private void validate(LocalCosmetic entry, ModelLimits limits, boolean announce) {
		Path file = entry.path;
		Util.backgroundExecutor().execute(() -> {
			List<Issue> issues = new ArrayList<>();
			byte[] canonical = null;
			BBModelParser.Result result = null;
			try {
				long size = Files.size(file);
				if (size > limits.maxFileBytes()) {
					issues.add(Issue.error("too_large", "The file is " + size / 1024 + " KiB; the limit is " + limits.maxFileBytes() / 1024 + " KiB"));
				} else {
					byte[] raw = Files.readAllBytes(file);
					TextureEmbedder.Result embedded = TextureEmbedder.embed(raw, limits, (relative, absolute, name) -> readTexture(file.getParent(), relative, absolute, name, limits));
					canonical = embedded.bytes();
					if (embedded.missing() > 0) {
						issues.add(Issue.warning("missing_texture", embedded.missing() + " texture file(s) could not be found next to the model. Save the model in Blockbench with textures embedded."));
					}
					result = BBModelParser.parse(canonical, stripExtension(file.getFileName().toString()), limits);
					issues.addAll(result.issues());
				}
			} catch (BBModelParser.InvalidModelException e) {
				issues.add(Issue.error(e.code(), e.getMessage()));
			} catch (IOException e) {
				issues.add(Issue.error("io", "Could not read the file: " + e.getMessage()));
			}
			byte[] bytes = canonical;
			BBModelParser.Result parsed = result;
			CosmeticSettingsFile.Data settings = CosmeticSettingsFile.read(file);
			Minecraft.getInstance().execute(() -> finishValidation(entry, parsed, bytes, issues, settings, announce));
		});
	}

	private void finishValidation(LocalCosmetic entry, BBModelParser.Result result, byte[] bytes, List<Issue> issues, CosmeticSettingsFile.Data settings, boolean announce) {
		if (entries.get(entry.key) != entry) {
			return;
		}
		AssetId previous = entry.asset;
		entry.issues = List.copyOf(issues);
		if (result != null && result.ok()) {
			entry.status = LocalCosmetic.Status.READY;
			entry.model = result.model();
			entry.bytes = bytes;
			entry.asset = AssetId.of(bytes);
			applySettings(entry, settings);
			if (!settings.exists() && (state.animations.containsKey(entry.key) || state.physics.containsKey(entry.key))) {
				// Settings saved by an older version: move them into the cosmetic's own file.
				entry.savedSettings = savedSettings(entry.key);
				entry.savedPhysics = savedPhysics(entry.key);
				if (writeSettings(entry)) {
					state.animations.remove(entry.key);
					state.physics.remove(entry.key);
					saveState();
				}
			}
			OrbitClient.get().registry().prepare(entry.asset, entry.model);
		} else {
			entry.status = LocalCosmetic.Status.INVALID;
			entry.model = null;
			entry.bytes = null;
			entry.asset = null;
			if (announce || entry.loaded) {
				String reason = issues.stream().filter(Issue::isError).map(Issue::message).findFirst().orElse("Not a valid Blockbench model")
					// Parser messages can quote Java exception names, which mean nothing to players.
					.replaceAll("(?:[a-z0-9_]+\\.)+[A-Za-z0-9_]*(?:Exception|Error): ", "");
				Notifications.error(Component.translatable("orbit_cosmetic.notify.load_failed", entry.displayName()), Component.literal(reason));
			}
		}
		entry.serverRejection = null;
		if (previous != null && !previous.equals(entry.asset)) {
			OrbitClient.get().registry().invalidate(previous);
		}
		updateDuplicates();
		changed();
	}

	private void applySettings(LocalCosmetic entry, CosmeticSettingsFile.Data data) {
		entry.settingsRoot = data.root();
		entry.settingsModified = data.modified();
		entry.settingsForeign = data.foreign();
		entry.settingsProblem = data.problem();
		entry.nameOverride = data.name() == null || data.name().isBlank() ? null : BBModelParser.sanitizeName(data.name());
		entry.author = data.author();
		entry.description = data.description();
		entry.savedSettings = Map.copyOf(data.animations());
		entry.savedPhysics = new LinkedHashMap<>(data.physics());
	}

	/** Re-reads a settings file edited outside the game. */
	private void reloadSettings(LocalCosmetic entry) {
		entry.settingsModified = CosmeticSettingsFile.modified(entry.path);
		Util.ioPool().execute(() -> {
			CosmeticSettingsFile.Data data = CosmeticSettingsFile.read(entry.path);
			Minecraft.getInstance().execute(() -> {
				if (entries.get(entry.key) == entry && entry.model != null) {
					applySettings(entry, data);
					changed();
				}
			});
		});
	}

	/** Writes the cosmetic's settings file. Returns false if it cannot be written (or belongs to another program). */
	private boolean writeSettings(LocalCosmetic entry) {
		if (entry.model == null || entry.settingsForeign) {
			return false;
		}
		long modified = CosmeticSettingsFile.write(entry.path, entry.settingsRoot, entry.model, entry.animationSettings(), entry.savedPhysics);
		if (modified < 0) {
			OrbitClient.LOGGER.warn("Could not save {}", CosmeticSettingsFile.pathFor(entry.path));
			return false;
		}
		entry.settingsModified = modified;
		return true;
	}

	/**
	 * Finds a texture referenced by a model. Only PNG files inside the model's own folder (or its subfolders)
	 * are accepted, so a model can never pull arbitrary files from the disk into an uploaded cosmetic.
	 */
	private static byte[] readTexture(Path modelDirectory, String relative, String absolute, String name, ModelLimits limits) {
		List<String> candidates = new ArrayList<>();
		if (relative != null) {
			candidates.add(relative);
		}
		for (String other : new String[] {absolute, name}) {
			if (other != null && !other.isBlank()) {
				String fileName = Path.of(other.replace('\\', '/').replaceAll(".*/", "")).toString();
				candidates.add(fileName);
				if (!fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
					candidates.add(fileName + ".png");
				}
			}
		}
		Path base = modelDirectory.toAbsolutePath().normalize();
		for (String candidate : candidates) {
			try {
				Path path = base.resolve(candidate.replace('\\', '/')).toAbsolutePath().normalize();
				if (!path.startsWith(base) || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
					continue;
				}
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limits.maxTotalTextureBytes()) {
					continue;
				}
				byte[] png = Files.readAllBytes(path);
				if (PngInfo.read(png) != null) {
					return png;
				}
			} catch (IOException | RuntimeException ignored) {
				// try the next candidate
			}
		}
		return null;
	}

	private void updateDuplicates() {
		Map<AssetId, LocalCosmetic> first = new HashMap<>();
		for (LocalCosmetic entry : entries.values()) {
			entry.duplicateOf = null;
			if (entry.asset == null) {
				continue;
			}
			LocalCosmetic original = first.putIfAbsent(entry.asset, entry);
			if (original != null) {
				entry.duplicateOf = original.key;
			}
		}
	}

	// ---------------------------------------------------------------- actions

	public void setLoaded(LocalCosmetic entry, boolean loaded) {
		if (entry.loaded == loaded) {
			return;
		}
		entry.loaded = loaded;
		state.loaded.remove(entry.key);
		if (loaded) {
			state.loaded.add(entry.key);
		}
		saveState();
		if (loaded) {
			Notifications.success(Component.translatable("orbit_cosmetic.notify.loaded", entry.displayName()), null);
		} else {
			Notifications.info(Component.translatable("orbit_cosmetic.notify.unloaded", entry.displayName()), null);
		}
		changed();
	}

	public void setAnimationSettings(LocalCosmetic entry, int index, AnimationSettings settings) {
		if (entry.model == null || index < 0 || index >= entry.model.animations().size()) {
			return;
		}
		AnimationClip clip = entry.model.animations().get(index);
		Map<String, AnimationSettings> updated = new LinkedHashMap<>(entry.savedSettings);
		updated.put(clip.name(), settings);
		entry.savedSettings = Map.copyOf(updated);
		writeSettings(entry);
		changed();
	}

	public void resetAnimationSettings(LocalCosmetic entry) {
		entry.savedSettings = Map.of();
		writeSettings(entry);
		changed();
	}

	/** Sets (or, with all axes off, keeps) the movement physics of one part. */
	public void setPhysics(LocalCosmetic entry, int bone, List<AxisDrive> axes) {
		String key = entry.boneKey(bone);
		if (key == null) {
			return;
		}
		Map<String, List<AxisDrive>> updated = new LinkedHashMap<>(entry.savedPhysics);
		if (!updated.containsKey(key) && updated.size() >= OrbitProtocol.MAX_PHYSICS_LINKS) {
			return;
		}
		updated.put(key, List.copyOf(axes));
		entry.savedPhysics = updated;
		writeSettings(entry);
		changed();
	}

	public void removePhysics(LocalCosmetic entry, int bone) {
		String key = entry.boneKey(bone);
		if (key == null || !entry.savedPhysics.containsKey(key)) {
			return;
		}
		Map<String, List<AxisDrive>> updated = new LinkedHashMap<>(entry.savedPhysics);
		updated.remove(key);
		entry.savedPhysics = updated;
		writeSettings(entry);
		changed();
	}

	public void setServerRejection(AssetId asset, String reason) {
		boolean any = false;
		for (LocalCosmetic entry : entries.values()) {
			if (asset.equals(entry.asset)) {
				entry.serverRejection = reason;
				any = true;
			}
		}
		if (any) {
			changed();
		}
	}

	public void clearServerRejections() {
		for (LocalCosmetic entry : entries.values()) {
			entry.serverRejection = null;
		}
		changed();
	}

	/** Deletes the model and its settings file. The caller confirms with the user first. */
	public boolean remove(LocalCosmetic entry) {
		try {
			Files.deleteIfExists(entry.path);
			if (!entry.settingsForeign) {
				Files.deleteIfExists(CosmeticSettingsFile.pathFor(entry.path));
			}
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not delete {}", entry.path, e);
			return false;
		}
		entries.remove(entry.key);
		state.loaded.remove(entry.key);
		state.animations.remove(entry.key);
		state.physics.remove(entry.key);
		saveState();
		if (entry.asset != null) {
			OrbitClient.get().registry().invalidate(entry.asset);
		}
		updateDuplicates();
		changed();
		return true;
	}

	/**
	 * Copies .bbmodel files into the orbit folder, embedding textures found next to them.
	 *
	 * @return one human-readable line per file
	 */
	public List<String> importFiles(List<Path> files) {
		List<String> report = new ArrayList<>();
		ModelLimits limits = OrbitClient.get().config().modelLimits();
		for (Path source : files) {
			String fileName = source.getFileName().toString();
			if (!fileName.toLowerCase(Locale.ROOT).endsWith(".bbmodel")) {
				report.add(fileName + ": not a .bbmodel file");
				continue;
			}
			try {
				if (!Files.isRegularFile(source) || Files.size(source) > limits.maxFileBytes()) {
					report.add(fileName + ": missing or larger than " + limits.maxFileBytes() / 1024 + " KiB");
					continue;
				}
				byte[] raw = Files.readAllBytes(source);
				TextureEmbedder.Result embedded = TextureEmbedder.embed(raw, limits, (relative, absolute, name) -> readTexture(source.getParent(), relative, absolute, name, limits));
				AssetId id = AssetId.of(embedded.bytes());
				LocalCosmetic existing = entries.values().stream().filter(e -> id.equals(e.asset)).findFirst().orElse(null);
				if (existing != null) {
					report.add(fileName + ": already in the library as " + existing.key);
					continue;
				}
				Path target = uniqueTarget(sanitizeFileName(fileName));
				Path temp = target.resolveSibling(target.getFileName() + ".tmp");
				Files.write(temp, embedded.bytes());
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
				// Bring the cosmetic's settings along when they were shared with it.
				Path settings = CosmeticSettingsFile.pathFor(source);
				boolean withSettings = CosmeticSettingsFile.isSettingsFile(settings);
				if (withSettings) {
					Files.copy(settings, CosmeticSettingsFile.pathFor(target), StandardCopyOption.REPLACE_EXISTING);
				}
				report.add(fileName + ": imported" + (withSettings ? " with its settings" : "") + (embedded.missing() > 0 ? " (some textures were not found)" : ""));
			} catch (IOException | BBModelParser.InvalidModelException e) {
				report.add(fileName + ": " + e.getMessage());
			}
		}
		reload();
		return report;
	}

	/**
	 * Saves a cosmetic from the featured catalog into {@code orbit/featured/} and loads it once it is checked.
	 * The bytes must already match the catalog's SHA-256; they are written unchanged so the cosmetic keeps the
	 * same identity as on Orbit Cloud.
	 *
	 * @return the existing entry if this cosmetic is already in the library (it is loaded), otherwise null
	 */
	public LocalCosmetic installFeatured(String name, byte[] bytes) throws IOException {
		AssetId id = AssetId.of(bytes);
		LocalCosmetic existing = entries.values().stream().filter(e -> id.equals(e.asset)).findFirst().orElse(null);
		if (existing != null) {
			setLoaded(existing, true);
			return existing;
		}
		Path directory = paths.featured();
		Files.createDirectories(directory);
		Path target = uniqueTarget(directory, sanitizeFileName(name + ".bbmodel"));
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(temp, bytes);
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		state.loaded.add(paths.root().relativize(target).toString().replace('\\', '/'));
		saveState();
		reload();
		return null;
	}

	private Path uniqueTarget(String fileName) {
		return uniqueTarget(paths.root(), fileName);
	}

	private static Path uniqueTarget(Path directory, String fileName) {
		String base = stripExtension(fileName);
		Path target = directory.resolve(base + ".bbmodel");
		for (int i = 2; Files.exists(target) || Files.exists(CosmeticSettingsFile.pathFor(target)); i++) {
			target = directory.resolve(base + "_" + i + ".bbmodel");
		}
		return target;
	}

	/** Writes a new template into orbit/templates and returns its path. */
	public Path createTemplate() throws IOException {
		Path directory = paths.templates();
		Files.createDirectories(directory);
		Path target = directory.resolve("orbit_template.bbmodel");
		for (int i = 2; Files.exists(target); i++) {
			target = directory.resolve("orbit_template_" + i + ".bbmodel");
		}
		Files.write(target, TemplateGenerator.generate(stripExtension(target.getFileName().toString())));
		Files.writeString(directory.resolve("README_TEMPLATE.txt"), TemplateGenerator.guide(), StandardCharsets.UTF_8);
		return target;
	}

	// ---------------------------------------------------------------- state file

	private void loadState() {
		Path file = paths.stateFile();
		if (!Files.exists(file)) {
			return;
		}
		try {
			StateFile loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), StateFile.class);
			if (loaded != null) {
				if (loaded.loaded == null) {
					loaded.loaded = new ArrayList<>();
				}
				if (loaded.animations == null) {
					loaded.animations = new LinkedHashMap<>();
				}
				if (loaded.physics == null) {
					loaded.physics = new LinkedHashMap<>();
				}
				state = loaded;
			}
		} catch (IOException | JsonParseException e) {
			OrbitClient.LOGGER.warn("Could not read {}", file, e);
		}
	}

	private void saveState() {
		Path file = paths.stateFile();
		try {
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			// Only the loaded list is current; legacy sections are written only while not yet migrated.
			StateFile out = new StateFile();
			out.loaded = state.loaded;
			out.animations = state.animations.isEmpty() ? null : state.animations;
			out.physics = state.physics.isEmpty() ? null : state.physics;
			Files.writeString(temp, GSON.toJson(out), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not save {}", file, e);
		}
	}

	private Map<String, AnimationSettings> savedSettings(String key) {
		Map<String, SavedSettings> saved = state.animations.get(key);
		if (saved == null) {
			return Map.of();
		}
		Map<String, AnimationSettings> result = new HashMap<>();
		for (Map.Entry<String, SavedSettings> entry : saved.entrySet()) {
			SavedSettings s = entry.getValue();
			if (s == null) {
				continue;
			}
			float minWait = s.minWait != null ? s.minWait : AnimationSettings.DEFAULT_MIN_WAIT;
			float maxWait = s.maxWait != null ? s.maxWait : AnimationSettings.DEFAULT_MAX_WAIT;
			result.put(entry.getKey(), new AnimationSettings(parse(AnimationTrigger.class, s.trigger, AnimationTrigger.NONE),
				parse(LoopMode.class, s.loop, LoopMode.LOOP), s.speed, s.priority, s.interrupt, minWait, maxWait));
		}
		return Map.copyOf(result);
	}

	private Map<String, List<AxisDrive>> savedPhysics(String key) {
		Map<String, List<SavedAxis>> saved = state.physics.get(key);
		Map<String, List<AxisDrive>> result = new LinkedHashMap<>();
		if (saved == null) {
			return result;
		}
		for (Map.Entry<String, List<SavedAxis>> entry : saved.entrySet()) {
			if (entry.getValue() == null) {
				continue;
			}
			List<AxisDrive> axes = new ArrayList<>();
			for (SavedAxis axis : entry.getValue()) {
				axes.add(axis == null ? AxisDrive.OFF : new AxisDrive(parse(PhysicsSource.class, axis.source, PhysicsSource.NONE), axis.strength, axis.max, axis.resistance));
			}
			result.put(entry.getKey(), List.copyOf(axes));
		}
		return result;
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
		if (name == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private static String stripExtension(String fileName) {
		return fileName.toLowerCase(Locale.ROOT).endsWith(".bbmodel") ? fileName.substring(0, fileName.length() - ".bbmodel".length()) : fileName;
	}

	private static String sanitizeFileName(String fileName) {
		String cleaned = fileName.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
		return cleaned.isEmpty() || cleaned.startsWith(".") ? "cosmetic" + cleaned + ".bbmodel" : cleaned;
	}

	public static Comparator<LocalCosmetic> byName() {
		return Comparator.comparing(entry -> entry.displayName().toLowerCase(Locale.ROOT));
	}
}
