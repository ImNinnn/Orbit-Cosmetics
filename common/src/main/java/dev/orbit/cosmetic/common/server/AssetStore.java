package dev.orbit.cosmetic.common.server;

import dev.orbit.cosmetic.common.protocol.AssetId;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content-addressed storage of accepted assets: {@code <dir>/<sha256>.bbmodel}. File names are always
 * derived from the hash, never from client input, so no path supplied by a player reaches the filesystem.
 * Contents are re-verified against the hash when read.
 */
public final class AssetStore {
	private static final String EXTENSION = ".bbmodel";

	private final Path directory;
	private final OrbitLogger logger;
	private final Map<AssetId, Long> sizes = new ConcurrentHashMap<>();
	private final Map<AssetId, Long> lastUsed = new ConcurrentHashMap<>();

	public AssetStore(Path directory, OrbitLogger logger) {
		this.directory = directory;
		this.logger = logger;
		try {
			Files.createDirectories(directory);
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
				for (Path file : stream) {
					String name = file.getFileName().toString();
					AssetId id = AssetId.tryParse(name.substring(0, name.length() - EXTENSION.length()));
					if (id != null && Files.isRegularFile(file)) {
						sizes.put(id, Files.size(file));
						lastUsed.put(id, Files.getLastModifiedTime(file).toMillis());
					}
				}
			}
		} catch (IOException e) {
			logger.error("Could not read asset store " + directory, e);
		}
	}

	public boolean has(AssetId id) {
		return sizes.containsKey(id);
	}

	public int count() {
		return sizes.size();
	}

	public long totalBytes() {
		return sizes.values().stream().mapToLong(Long::longValue).sum();
	}

	public Set<AssetId> ids() {
		return Set.copyOf(sizes.keySet());
	}

	/** Reads and verifies an asset; corrupted files are deleted. Safe to call off the main thread. */
	public byte[] read(AssetId id) {
		if (!has(id)) {
			return null;
		}
		Path file = path(id);
		try {
			byte[] bytes = Files.readAllBytes(file);
			if (!id.matches(bytes)) {
				logger.warn("Stored asset " + id.shortHex() + " is corrupted and was removed");
				delete(id);
				return null;
			}
			lastUsed.put(id, System.currentTimeMillis());
			return bytes;
		} catch (IOException e) {
			logger.error("Could not read asset " + id.shortHex(), e);
			return null;
		}
	}

	/** Writes an already validated asset. Safe to call off the main thread. */
	public boolean write(AssetId id, byte[] bytes) {
		Path file = path(id);
		try {
			Files.createDirectories(directory);
			Path temp = directory.resolve(id.hex() + ".tmp");
			Files.write(temp, bytes);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			sizes.put(id, (long) bytes.length);
			lastUsed.put(id, System.currentTimeMillis());
			return true;
		} catch (IOException e) {
			logger.error("Could not store asset " + id.shortHex(), e);
			return false;
		}
	}

	public void delete(AssetId id) {
		sizes.remove(id);
		lastUsed.remove(id);
		try {
			Files.deleteIfExists(path(id));
		} catch (IOException e) {
			logger.error("Could not delete asset " + id.shortHex(), e);
		}
	}

	public void touch(AssetId id) {
		if (has(id)) {
			lastUsed.put(id, System.currentTimeMillis());
		}
	}

	/**
	 * Deletes least recently used assets until the store fits {@code maxBytes}, never touching pinned ones.
	 *
	 * @return the evicted assets
	 */
	public List<AssetId> evict(long maxBytes, Set<AssetId> pinned) {
		List<AssetId> evicted = new ArrayList<>();
		long total = totalBytes();
		if (total <= maxBytes) {
			return evicted;
		}
		List<AssetId> candidates = new ArrayList<>(sizes.keySet());
		candidates.removeAll(pinned);
		candidates.sort(Comparator.comparingLong(id -> lastUsed.getOrDefault(id, 0L)));
		for (AssetId id : candidates) {
			if (total <= maxBytes) {
				break;
			}
			total -= sizes.getOrDefault(id, 0L);
			delete(id);
			evicted.add(id);
		}
		return evicted;
	}

	private Path path(AssetId id) {
		return directory.resolve(id.hex() + EXTENSION);
	}
}
