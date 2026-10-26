package dev.orbit.cosmetic.client.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Blocking calls to the Orbit backend (see backend/README.md). Call from a background thread only.
 * Responses are untrusted: sizes are capped and every value is checked by the caller.
 */
final class BackendClient {
	private static final Gson GSON = new Gson();
	private static final int MAX_JSON_BYTES = 512 * 1024;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** A player's presence as stored by the backend. */
	record PresenceEntry(UUID player, String name, int protocol, String equip) {
	}

	/** A signed-in player session. {@code bannedUntil} is 0 for permanent bans. */
	record Session(String token, UUID player, String name, boolean banned, double bannedUntil) {
	}

	static final class BackendException extends IOException {
		final int status;
		/** The backend's error code, such as "blocked" or "too_large"; empty if the body had none. */
		final String code;
		/** The backend's human-readable message, or a generic one. */
		final String detail;
		/** For "blocked": when the ban ends (Unix seconds), 0 if permanent or unknown. */
		final double banExpires;

		BackendException(int status, String code, String detail) {
			this(status, code, detail, 0);
		}

		BackendException(int status, String code, String detail, double banExpires) {
			super(detail.isEmpty() ? "HTTP " + status : detail);
			this.status = status;
			this.code = code;
			this.detail = detail;
			this.banExpires = banExpires;
		}
	}

	private final String baseUrl;
	private final String userAgent;
	/** Bearer token for writes when the backend requires sign-in; null otherwise. */
	private volatile String token;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

	/** @throws IllegalArgumentException if the URL is not an http(s) URL */
	BackendClient(String baseUrl, String modVersion) {
		this.userAgent = "OrbitCosmetic/" + modVersion + " (+https://orbitcosmetics.qd.je)";
		String trimmed = baseUrl == null ? "" : baseUrl.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		URI uri = URI.create(trimmed);
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
			throw new IllegalArgumentException("The backend URL must start with http:// or https://");
		}
		this.baseUrl = trimmed;
	}

	String baseUrl() {
		return baseUrl;
	}

	/** @return the backend's authentication scheme ("mojang" or "none"), or null if it is not healthy */
	String health() throws IOException, InterruptedException {
		JsonObject body = json(send(request("/v1/health").GET(), MAX_JSON_BYTES));
		if (!"ok".equals(text(body, "status"))) {
			return null;
		}
		String auth = text(body, "auth");
		return auth == null ? "none" : auth;
	}

	String challenge() throws IOException, InterruptedException {
		String serverId = text(json(send(request("/v1/auth/challenge").POST(HttpRequest.BodyPublishers.noBody()), MAX_JSON_BYTES)), "server_id");
		if (serverId == null || !serverId.matches("[0-9a-f]{40}")) {
			throw new IOException("Invalid challenge from the backend");
		}
		return serverId;
	}

	/** Exchanges a challenge the game has joined (through Mojang) for a session. */
	Session createSession(String username, String serverId) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("username", username);
		body.addProperty("server_id", serverId);
		JsonObject response = json(send(request("/v1/auth/session")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))), MAX_JSON_BYTES));
		String sessionToken = text(response, "token");
		String player = text(response, "player_uuid");
		if (sessionToken == null || player == null) {
			throw new IOException("Invalid session from the backend");
		}
		Session session = session(response, sessionToken);
		token = session.token();
		return session;
	}

	/** The current session's player and ban state. */
	Session me() throws IOException, InterruptedException {
		return session(json(send(request("/v1/auth/me").GET(), MAX_JSON_BYTES)), token);
	}

	private static Session session(JsonObject response, String sessionToken) throws IOException {
		String player = text(response, "player_uuid");
		if (player == null) {
			throw new IOException("Invalid session from the backend");
		}
		JsonElement banned = response.get("banned");
		JsonElement until = response.get("banned_until");
		boolean isBanned = banned != null && banned.isJsonPrimitive() && banned.getAsJsonPrimitive().isBoolean() && banned.getAsBoolean();
		double bannedUntil = until != null && until.isJsonPrimitive() && until.getAsJsonPrimitive().isNumber() ? until.getAsDouble() : 0;
		try {
			return new Session(sessionToken, UUID.fromString(player), text(response, "player_name"), isBanned, bannedUntil);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid session from the backend");
		}
	}

	boolean signedIn() {
		return token != null;
	}

	void signOut() {
		token = null;
	}

	/** @return true if the backend already has an asset with this SHA-256 */
	boolean hasAsset(String sha256) throws IOException, InterruptedException {
		try {
			send(request("/v1/cosmetics/by-sha256/" + sha256).GET(), MAX_JSON_BYTES);
			return true;
		} catch (BackendException e) {
			if (e.status == 404) {
				return false;
			}
			throw e;
		}
	}

	/** Uploads a cosmetic. A 409 (already uploaded) counts as success; returns false in that case. */
	boolean upload(String sha256, String displayName, byte[] bytes) throws IOException, InterruptedException {
		String boundary = "orbit-" + UUID.randomUUID();
		byte[] body = multipart(boundary, sha256.substring(0, 16) + ".bbmodel", displayName, bytes);
		try {
			send(request("/v1/cosmetics/upload")
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)), MAX_JSON_BYTES);
			return true;
		} catch (BackendException e) {
			if (e.status != 409) {
				throw e;
			}
			return false;
		}
	}

	/** The featured catalog (at most 200 entries). Malformed entries are skipped. */
	List<FeaturedCosmetic> featured() throws IOException, InterruptedException {
		JsonObject response = json(send(request("/v1/featured?limit=200").GET(), MAX_JSON_BYTES));
		List<FeaturedCosmetic> result = new ArrayList<>();
		JsonElement items = response.get("items");
		if (items == null || !items.isJsonArray()) {
			return result;
		}
		for (JsonElement element : items.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject item = element.getAsJsonObject();
			String id = text(item, "id");
			String sha = text(item, "sha256");
			String name = text(item, "display_name");
			if (id == null || name == null || sha == null || !sha.matches("[0-9a-f]{64}")) {
				continue;
			}
			result.add(new FeaturedCosmetic(id, name.length() > 64 ? name.substring(0, 64) : name, sha, number(item, "size_bytes"),
				limited(text(item, "author"), 64), limited(text(item, "description"), 300), number(item, "animation_count")));
		}
		return result;
	}

	/**
	 * Reports a player.
	 *
	 * @param reporter only sent to development backends, which have no accounts; null otherwise
	 * @return true if this is a new report, false if it updated an earlier one
	 */
	boolean report(UUID player, String name, String reason, String note, List<String> cosmetics, UUID reporter) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("player_uuid", player.toString());
		body.addProperty("player_name", name);
		body.addProperty("reason", reason);
		body.addProperty("note", note);
		JsonArray list = new JsonArray();
		cosmetics.forEach(list::add);
		body.add("cosmetics", list);
		if (reporter != null) {
			body.addProperty("reporter_uuid", reporter.toString());
		}
		JsonObject response = json(send(request("/v1/reports")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))), MAX_JSON_BYTES));
		JsonElement isNew = response.get("new");
		return isNew == null || !isNew.isJsonPrimitive() || isNew.getAsBoolean();
	}

	private static int number(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber() ? element.getAsInt() : 0;
	}

	private static String limited(String value, int max) {
		if (value == null) {
			return "";
		}
		String clean = value.replaceAll("\\p{Cntrl}", " ").trim();
		return clean.length() > max ? clean.substring(0, max) : clean;
	}

	byte[] download(String sha256, int maxBytes) throws IOException, InterruptedException {
		return send(request("/v1/cosmetics/by-sha256/" + sha256 + "/download").GET(), maxBytes);
	}

	void putPresence(UUID player, String name, int protocol, String equip) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("player_name", name);
		body.addProperty("protocol", protocol);
		body.addProperty("equip", equip);
		send(request("/v1/presence/" + player)
			.header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))), MAX_JSON_BYTES);
	}

	void deletePresence(UUID player) throws IOException, InterruptedException {
		try {
			send(request("/v1/presence/" + player).DELETE(), MAX_JSON_BYTES);
		} catch (BackendException e) {
			if (e.status != 404) {
				throw e;
			}
		}
	}

	/** @param names player names as well, so offline-mode servers (whose UUIDs differ) still match */
	List<PresenceEntry> queryPresence(List<UUID> players, List<String> names) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		JsonArray list = new JsonArray();
		players.forEach(player -> list.add(player.toString()));
		body.add("players", list);
		JsonArray nameList = new JsonArray();
		names.forEach(nameList::add);
		body.add("names", nameList);
		JsonObject response = json(send(request("/v1/presence/query")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))), MAX_JSON_BYTES));
		List<PresenceEntry> result = new ArrayList<>();
		JsonElement entries = response.get("players");
		if (entries == null || !entries.isJsonArray()) {
			return result;
		}
		for (JsonElement element : entries.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject entry = element.getAsJsonObject();
			try {
				UUID player = UUID.fromString(text(entry, "player_uuid"));
				JsonElement protocol = entry.get("protocol");
				String equip = text(entry, "equip");
				String name = text(entry, "player_name");
				if (protocol != null && protocol.isJsonPrimitive() && protocol.getAsJsonPrimitive().isNumber() && equip != null) {
					result.add(new PresenceEntry(player, name == null ? "" : name, protocol.getAsInt(), equip));
				}
			} catch (IllegalArgumentException | NullPointerException ignored) {
				// skip malformed entries
			}
		}
		return result;
	}

	private HttpRequest.Builder request(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.timeout(TIMEOUT)
			.header("Accept", "application/json, */*")
			.header("User-Agent", userAgent);
		String current = token;
		if (current != null) {
			builder.header("Authorization", "Bearer " + current);
		}
		return builder;
	}

	private byte[] send(HttpRequest.Builder builder, int maxBytes) throws IOException, InterruptedException {
		HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		byte[] body;
		try (InputStream stream = response.body()) {
			body = stream.readNBytes(maxBytes + 1);
		}
		if (body.length > maxBytes) {
			throw new BackendException(response.statusCode(), "", "Response larger than " + maxBytes + " bytes");
		}
		if (response.statusCode() / 100 != 2) {
			throw error(response.statusCode(), body);
		}
		return body;
	}

	/** Reads the backend's {"error": {"code", "message"}} body, if there is one. */
	private static BackendException error(int status, byte[] body) {
		try {
			JsonObject error = json(body).getAsJsonObject("error");
			if (error != null) {
				String code = text(error, "code");
				String message = text(error, "message");
				double expires = 0;
				JsonElement details = error.get("details");
				if (details != null && details.isJsonObject()) {
					JsonElement value = details.getAsJsonObject().get("expires_at");
					if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
						expires = value.getAsDouble();
					}
				}
				return new BackendException(status, code == null ? "" : code, message == null ? "" : limited(message, 200), expires);
			}
		} catch (IOException | ClassCastException ignored) {
			// not a JSON error body
		}
		return new BackendException(status, "", "");
	}

	private static JsonObject json(byte[] body) throws IOException {
		try {
			JsonElement element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
			if (!element.isJsonObject()) {
				throw new IOException("Unexpected response");
			}
			return element.getAsJsonObject();
		} catch (JsonParseException e) {
			throw new IOException("Invalid JSON from the backend", e);
		}
	}

	private static String text(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static byte[] multipart(String boundary, String filename, String displayName, byte[] file) {
		String name = displayName.replaceAll("[\\r\\n\"]", " ");
		String head = "--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"display_name\"\r\n\r\n" + name + "\r\n"
			+ "--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
			+ "Content-Type: application/octet-stream\r\n\r\n";
		byte[] start = head.getBytes(StandardCharsets.UTF_8);
		byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] body = new byte[start.length + file.length + end.length];
		System.arraycopy(start, 0, body, 0, start.length);
		System.arraycopy(file, 0, body, start.length, file.length);
		System.arraycopy(end, 0, body, start.length + file.length, end.length);
		return body;
	}
}
