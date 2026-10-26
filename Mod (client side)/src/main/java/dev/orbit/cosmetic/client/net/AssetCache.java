package dev.orbit.cosmetic.client.net;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.common.protocol.AssetId;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cosmetics downloaded from servers, stored as {@code orbit/.cache/<sha256>.bbmodel}. File names come only
 * from verified hashes, and contents are verified again when read. Old files are trimmed to the size limit.
 */
public final class AssetCache {
	private final Path directory;

	public AssetCache(Path directory) {
		this.directory = directory;
	}

	private Path file(AssetId id) {
		return directory.resolve(id.hex() + ".bbmodel");
	}

	public boolean has(AssetId id) {
		return Files.isRegularFile(file(id));
	}

	/** @return verified bytes, or null. Blocking: call from an IO thread. */
	public byte[] read(AssetId id) {
		Path file = file(id);
		try {
			byte[] bytes = Files.readAllBytes(file);
			if (!id.matches(bytes)) {
				Files.deleteIfExists(file);
				return null;
			}
			Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
			return bytes;
		} catch (IOException e) {
			return null;
		}
	}

	/** Blocking: call from an IO thread. */
	public void write(AssetId id, byte[] bytes) {
		try {
			Files.createDirectories(directory);
			Path temp = directory.resolve(id.hex() + ".tmp");
			Files.write(temp, bytes);
			Files.move(temp, file(id), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not cache cosmetic {}", id.shortHex(), e);
		}
	}

	/** Deletes the least recently used files until the cache fits. Blocking. */
	public void trim(long maxBytes) {
		List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.bbmodel")) {
			stream.forEach(files::add);
		} catch (IOException e) {
			return;
		}
		files.sort(Comparator.comparingLong(AssetCache::modified));
		long total = files.stream().mapToLong(AssetCache::size).sum();
		for (Path file : files) {
			if (total <= maxBytes) {
				break;
			}
			total -= size(file);
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
				// try the next one
			}
		}
	}

	/** Deletes every cached file. Blocking. */
	public int clear() {
		int count = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
			for (Path file : stream) {
				if (Files.isRegularFile(file)) {
					Files.deleteIfExists(file);
					count++;
				}
			}
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not clear {}", directory, e);
		}
		return count;
	}

	public long sizeBytes() {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.bbmodel")) {
			long total = 0;
			for (Path file : stream) {
				total += size(file);
			}
			return total;
		} catch (IOException e) {
			return 0;
		}
	}

	private static long modified(Path file) {
		try {
			return Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private static long size(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			return 0;
		}
	}
}
