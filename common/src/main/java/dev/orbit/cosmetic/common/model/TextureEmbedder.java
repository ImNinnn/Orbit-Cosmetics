package dev.orbit.cosmetic.common.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.orbit.cosmetic.common.util.DataUrls;
import dev.orbit.cosmetic.common.util.PngInfo;

import java.nio.charset.StandardCharsets;

/**
 * Embeds externally referenced textures into a model so it becomes a single self-contained asset.
 * Only models produced this way (or already self-contained) are ever shared over the network.
 */
public final class TextureEmbedder {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** Supplies texture files. Implementations must confine lookups to a trusted directory. */
	@FunctionalInterface
	public interface Resolver {
		/**
		 * @param relativePath Blockbench's {@code relative_path}, may be null
		 * @param path Blockbench's absolute {@code path}, may be null; only its file name should be trusted
		 * @param name the texture name, usually a file name
		 * @return PNG bytes, or null if the texture cannot be found
		 */
		byte[] read(String relativePath, String path, String name);
	}

	public record Result(byte[] bytes, int embedded, int missing) {
	}

	private TextureEmbedder() {
	}

	public static Result embed(byte[] bytes, ModelLimits limits, Resolver resolver) throws BBModelParser.InvalidModelException {
		ModelLimits clamped = limits.clampToHard();
		JsonObject root = BBModelParser.readJson(bytes, clamped.maxFileBytes());
		JsonElement texturesElement = root.get("textures");
		if (texturesElement == null || !texturesElement.isJsonArray()) {
			return new Result(bytes, 0, 0);
		}
		JsonArray textures = texturesElement.getAsJsonArray();
		int embedded = 0;
		int missing = 0;
		for (JsonElement element : textures) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject texture = element.getAsJsonObject();
			JsonElement source = texture.get("source");
			if (source != null && source.isJsonPrimitive() && !source.getAsString().isEmpty()) {
				continue;
			}
			byte[] png = resolver.read(string(texture, "relative_path"), string(texture, "path"), string(texture, "name"));
			PngInfo info = png == null ? null : PngInfo.read(png);
			if (info == null || png.length > clamped.maxTotalTextureBytes()) {
				missing++;
				continue;
			}
			texture.addProperty("source", DataUrls.encodePng(png));
			texture.addProperty("internal", true);
			embedded++;
		}
		if (embedded == 0) {
			return new Result(bytes, 0, missing);
		}
		return new Result(GSON.toJson(root).getBytes(StandardCharsets.UTF_8), embedded, missing);
	}

	private static String string(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}
}
