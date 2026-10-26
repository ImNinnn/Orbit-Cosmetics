package dev.orbit.cosmetic.common.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keeps recent errors and security-relevant events (malformed packets, rejected uploads, blocked attempts)
 * in memory for {@code /orbit log} and appends them to {@code security.log}, rotating at 1 MiB.
 */
public final class SecurityLog {
	private static final int MEMORY_ENTRIES = 200;
	private static final long MAX_FILE_BYTES = 1024 * 1024;
	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	public record Entry(Instant time, String player, String message) {
		@Override
		public String toString() {
			return FORMAT.format(time) + " " + player + ": " + message;
		}
	}

	private final Path file;
	private final OrbitLogger logger;
	private final Deque<Entry> recent = new ArrayDeque<>();

	public SecurityLog(Path file, OrbitLogger logger) {
		this.file = file;
		this.logger = logger;
	}

	public synchronized void record(String player, String message) {
		Entry entry = new Entry(Instant.now(), player == null ? "-" : player, message);
		recent.addLast(entry);
		while (recent.size() > MEMORY_ENTRIES) {
			recent.removeFirst();
		}
		logger.warn("[security] " + entry.player() + ": " + message);
		try {
			Files.createDirectories(file.getParent());
			if (Files.exists(file) && Files.size(file) > MAX_FILE_BYTES) {
				Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.writeString(file, entry + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			// The in-memory log still has the entry; do not spam the console about the file.
		}
	}

	public synchronized List<Entry> recent(int count) {
		List<Entry> all = new ArrayList<>(recent);
		return all.subList(Math.max(0, all.size() - count), all.size());
	}
}
