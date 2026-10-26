package dev.orbit.cosmetic.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.common.model.BBModelParser;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.ModelLimits;
import dev.orbit.cosmetic.common.model.TextureData;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shares baked cosmetics between everyone wearing the same asset. Parsing and image decoding run on a
 * background thread; texture upload happens on the render thread. Unused cosmetics are released after a delay.
 */
public final class CosmeticRegistry {
	private static final long RELEASE_DELAY_MS = 30_000;

	public enum State {
		LOADING,
		READY,
		FAILED
	}

	private static final class Holder {
		State state = State.LOADING;
		BakedCosmetic baked;
		CosmeticModel model;
		String failure;
		long lastUsed = System.currentTimeMillis();
	}

	private final Map<AssetId, Holder> holders = new HashMap<>();

	/** Called on the main thread. */
	public BakedCosmetic get(AssetId id) {
		Holder holder = holders.get(id);
		if (holder == null || holder.state != State.READY) {
			return null;
		}
		holder.lastUsed = System.currentTimeMillis();
		return holder.baked;
	}

	public State state(AssetId id) {
		Holder holder = holders.get(id);
		return holder == null ? null : holder.state;
	}

	public String failure(AssetId id) {
		Holder holder = holders.get(id);
		return holder == null ? null : holder.failure;
	}

	public CosmeticModel model(AssetId id) {
		Holder holder = holders.get(id);
		return holder == null ? null : holder.model;
	}

	public boolean isKnown(AssetId id) {
		return holders.containsKey(id);
	}

	/** Bakes an already parsed model (local library files). */
	public void prepare(AssetId id, CosmeticModel model) {
		if (holders.containsKey(id)) {
			return;
		}
		Holder holder = new Holder();
		holder.model = model;
		holders.put(id, holder);
		Util.backgroundExecutor().execute(() -> decode(id, holder, model));
	}

	/** Parses and bakes verified asset bytes (downloads and the download cache). */
	public void prepare(AssetId id, byte[] bytes, ModelLimits limits) {
		if (holders.containsKey(id)) {
			return;
		}
		Holder holder = new Holder();
		holders.put(id, holder);
		Util.backgroundExecutor().execute(() -> {
			BBModelParser.Result result = BBModelParser.parse(bytes, null, limits);
			if (!result.ok()) {
				String reason = result.errors().isEmpty() ? "invalid model" : result.errors().getFirst().message();
				Minecraft.getInstance().execute(() -> fail(id, holder, reason));
				return;
			}
			holder.model = result.model();
			decode(id, holder, result.model());
		});
	}

	private void decode(AssetId id, Holder holder, CosmeticModel model) {
		NativeImage[] images = new NativeImage[model.textures().size()];
		boolean[] translucent = new boolean[images.length];
		try {
			for (int i = 0; i < images.length; i++) {
				TextureData texture = model.textures().get(i);
				images[i] = NativeImage.read(texture.png());
				translucent[i] = hasPartialAlpha(images[i]);
			}
		} catch (IOException | RuntimeException e) {
			closeAll(images);
			OrbitClient.LOGGER.warn("Could not decode textures of cosmetic {}", id.shortHex(), e);
			Minecraft.getInstance().execute(() -> fail(id, holder, "A texture could not be decoded"));
			return;
		}
		Minecraft.getInstance().execute(() -> {
			if (holders.get(id) != holder) {
				closeAll(images); // released while decoding
				return;
			}
			try {
				holder.baked = BakedCosmetic.create(id, model, images, translucent);
				holder.state = State.READY;
			} catch (RuntimeException e) {
				closeAll(images);
				OrbitClient.LOGGER.warn("Could not upload cosmetic {}", id.shortHex(), e);
				fail(id, holder, "Could not upload textures");
			}
		});
	}

	private static boolean hasPartialAlpha(NativeImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int alpha = image.getPixel(x, y) >>> 24;
				if (alpha > 0 && alpha < 255) {
					return true;
				}
			}
		}
		return false;
	}

	private static void closeAll(NativeImage[] images) {
		for (NativeImage image : images) {
			if (image != null) {
				image.close();
			}
		}
	}

	private void fail(AssetId id, Holder holder, String reason) {
		if (holders.get(id) == holder) {
			holder.state = State.FAILED;
			holder.failure = reason;
		}
	}

	/** Releases cosmetics nobody has used recently. Main thread. */
	public void collect(Set<AssetId> inUse) {
		long now = System.currentTimeMillis();
		holders.entrySet().removeIf(entry -> {
			Holder holder = entry.getValue();
			if (inUse.contains(entry.getKey())) {
				holder.lastUsed = now;
				return false;
			}
			if (holder.state == State.LOADING || now - holder.lastUsed < RELEASE_DELAY_MS) {
				return false;
			}
			if (holder.baked != null) {
				holder.baked.release();
			}
			return true;
		});
	}

	/** Forgets a cosmetic immediately (e.g. the local file changed). */
	public void invalidate(AssetId id) {
		Holder holder = holders.remove(id);
		if (holder != null && holder.baked != null) {
			holder.baked.release();
		}
	}

	public void releaseAll() {
		for (Holder holder : holders.values()) {
			if (holder.baked != null) {
				holder.baked.release();
			}
		}
		holders.clear();
	}

	public int loadedCount() {
		return (int) holders.values().stream().filter(h -> h.state == State.READY).count();
	}
}
