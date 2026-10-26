package dev.orbit.cosmetic.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locations inside {@code <minecraft instance>/orbit/}:
 * <ul>
 *   <li>{@code orbit/*.bbmodel} - the player's cosmetics (subfolders are scanned too)</li>
 *   <li>{@code orbit/templates/} - generated templates, never loaded as cosmetics</li>
 *   <li>{@code orbit/featured/} - cosmetics downloaded from the Orbit Cloud featured catalog</li>
 *   <li>{@code orbit/.cache/} - cosmetics downloaded from servers, named by hash</li>
 *   <li>{@code orbit/<name>.json} - the settings and details of {@code <name>.bbmodel}, for sharing</li>
 *   <li>{@code orbit/orbit-state.json} - which cosmetics are loaded</li>
 * </ul>
 */
public final class OrbitPaths {
	public static final String TEMPLATES = "templates";
	public static final String CACHE = ".cache";

	private final Path root;

	public OrbitPaths(Path gameDirectory) {
		this.root = gameDirectory.resolve("orbit").toAbsolutePath().normalize();
	}

	public Path root() {
		return root;
	}

	public Path templates() {
		return root.resolve(TEMPLATES);
	}

	public Path featured() {
		return root.resolve("featured");
	}

	public Path cache() {
		return root.resolve(CACHE);
	}

	public Path stateFile() {
		return root.resolve("orbit-state.json");
	}

	public void create() throws IOException {
		Files.createDirectories(root);
		Files.createDirectories(templates());
		Files.createDirectories(cache());
	}

	public boolean isReserved(Path path) {
		Path relative = root.relativize(path.toAbsolutePath().normalize());
		if (relative.getNameCount() == 0) {
			return false;
		}
		String first = relative.getName(0).toString();
		return first.equals(TEMPLATES) || first.equals(CACHE);
	}
}
