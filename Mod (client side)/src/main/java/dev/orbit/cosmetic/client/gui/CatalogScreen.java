package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.Notifications;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.client.net.FeaturedCosmetic;
import dev.orbit.cosmetic.common.model.BBModelParser;
import dev.orbit.cosmetic.common.protocol.AssetId;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The featured catalog: cosmetics the Orbit team checked by hand. "Get" downloads one into
 * {@code orbit/featured/} and loads it; downloads are verified against the catalog's SHA-256 and checked by the
 * same model validator as every other cosmetic.
 */
public final class CatalogScreen extends Screen {
	private final @Nullable Screen parent;
	private CatalogList list;
	private Button getButton;
	private FeaturedCosmetic selected;
	private List<FeaturedCosmetic> items;
	private Component status = Component.translatable("orbit_cosmetic.catalog.loading");
	private final Set<String> downloading = new HashSet<>();
	private final Runnable listener = this::updateButtons;
	private int panelLeft;

	public CatalogScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.catalog.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		OrbitClient.get().library().addListener(listener);
		int listWidth = Math.max(180, Math.min(300, width / 2 - 20));
		panelLeft = 10 + listWidth + 10;
		list = new CatalogList(minecraft, listWidth, height - 34 - 62, 34);
		list.setX(10);
		addRenderableWidget(list);
		getButton = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.catalog.get"), b -> getSelected())
			.bounds(10, height - 56, listWidth, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.catalog.refresh"), b -> load())
			.bounds(10, height - 30, listWidth / 2 - 2, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
			.bounds(10 + listWidth / 2 + 2, height - 30, listWidth / 2 - 2, 20).build());
		if (items == null) {
			load();
		} else {
			list.show(items, selected);
		}
		updateButtons();
	}

	@Override
	public void removed() {
		OrbitClient.get().library().removeListener(listener);
	}

	private void load() {
		status = Component.translatable("orbit_cosmetic.catalog.loading");
		OrbitClient.get().backend().loadFeatured(loaded -> {
			items = loaded;
			status = loaded.isEmpty() ? Component.translatable("orbit_cosmetic.catalog.empty") : null;
			if (list != null) {
				list.show(loaded, selected);
			}
			updateButtons();
		}, message -> status = Component.translatable("orbit_cosmetic.catalog.unreachable", message));
	}

	private void select(FeaturedCosmetic item) {
		selected = item;
		updateButtons();
	}

	private static LocalCosmetic installed(FeaturedCosmetic item) {
		AssetId id = AssetId.tryParse(item.sha256());
		return id == null ? null : OrbitClient.get().library().find(id);
	}

	private void updateButtons() {
		if (getButton == null) {
			return;
		}
		LocalCosmetic local = selected == null ? null : installed(selected);
		getButton.active = selected != null && !downloading.contains(selected.sha256());
		getButton.setMessage(Component.translatable(selected != null && downloading.contains(selected.sha256()) ? "orbit_cosmetic.catalog.downloading"
			: local == null ? "orbit_cosmetic.catalog.get" : local.isLoaded() ? "orbit_cosmetic.manager.unload" : "orbit_cosmetic.manager.load"));
	}

	private void getSelected() {
		FeaturedCosmetic item = selected;
		if (item == null) {
			return;
		}
		LocalCosmetic local = installed(item);
		if (local != null) {
			OrbitClient.get().library().setLoaded(local, !local.isLoaded());
			return;
		}
		downloading.add(item.sha256());
		updateButtons();
		int maxBytes = OrbitClient.get().config().modelLimits().maxFileBytes();
		OrbitClient.get().backend().downloadFeatured(item, maxBytes, bytes -> {
			downloading.remove(item.sha256());
			try {
				// The same checks as any other model, before anything is written.
				BBModelParser.Result result = BBModelParser.parse(bytes, item.name(), OrbitClient.get().config().modelLimits());
				if (!result.ok()) {
					throw new IOException(result.issues().isEmpty() ? "Invalid model" : result.issues().getFirst().message());
				}
				OrbitClient.get().library().installFeatured(item.name(), bytes);
				Notifications.success(Component.translatable("orbit_cosmetic.notify.catalog_downloaded", item.name()), Component.translatable("orbit_cosmetic.notify.catalog_downloaded.detail"));
			} catch (IOException e) {
				Notifications.error(Component.translatable("orbit_cosmetic.notify.load_failed", item.name()), Component.literal(String.valueOf(e.getMessage())));
			}
			updateButtons();
		}, message -> {
			downloading.remove(item.sha256());
			Notifications.error(Component.translatable("orbit_cosmetic.notify.catalog_failed", item.name()), Component.literal(message));
			updateButtons();
		});
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable("orbit_cosmetic.catalog.subtitle").withStyle(ChatFormatting.GRAY), width / 2, 20, 0xFFFFFFFF);
		if (status != null) {
			int y = 70;
			for (FormattedCharSequence line : font.split(status, panelLeft - 30)) {
				graphics.centeredText(font, line, 10 + (panelLeft - 20) / 2, y, 0xFFAAAAAA);
				y += 10;
			}
		}
		int right = width - 10;
		graphics.fill(panelLeft, 34, right, height - 30, 0x66000000);
		int x = panelLeft + 6;
		int textWidth = right - panelLeft - 12;
		int y = 40;
		if (selected == null) {
			wrapped(graphics, Component.translatable("orbit_cosmetic.catalog.hint"), x, y, textWidth);
			return;
		}
		y = wrapped(graphics, Component.literal(selected.name()).withStyle(ChatFormatting.BOLD), x, y, textWidth);
		if (!selected.author().isEmpty()) {
			y = wrapped(graphics, Component.translatable("orbit_cosmetic.manager.author", selected.author()).withStyle(ChatFormatting.GRAY), x, y, textWidth);
		}
		y = wrapped(graphics, Component.translatable("orbit_cosmetic.catalog.verified").withStyle(ChatFormatting.GREEN), x, y, textWidth);
		if (!selected.description().isEmpty()) {
			y = wrapped(graphics, Component.literal(selected.description()).withStyle(ChatFormatting.ITALIC), x, y, textWidth);
		}
		y = wrapped(graphics, Component.translatable("orbit_cosmetic.catalog.details", Math.max(1, selected.sizeBytes() / 1024), selected.animations()).withStyle(ChatFormatting.GRAY), x, y, textWidth);
		LocalCosmetic local = installed(selected);
		if (local != null) {
			wrapped(graphics, Component.translatable(local.isLoaded() ? "orbit_cosmetic.catalog.installed_loaded" : "orbit_cosmetic.catalog.installed").withStyle(ChatFormatting.AQUA), x, y, textWidth);
		}
	}

	private int wrapped(GuiGraphicsExtractor graphics, Component text, int x, int y, int width) {
		for (FormattedCharSequence line : font.split(text, width)) {
			graphics.text(font, line, x, y, 0xFFFFFFFF);
			y += 10;
		}
		return y + 2;
	}

	private final class CatalogList extends ObjectSelectionList<CatalogList.Entry> {
		CatalogList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, 24);
		}

		void show(List<FeaturedCosmetic> cosmetics, FeaturedCosmetic keep) {
			List<Entry> entries = new ArrayList<>();
			Entry keepEntry = null;
			for (FeaturedCosmetic cosmetic : cosmetics) {
				Entry entry = new Entry(cosmetic);
				entries.add(entry);
				if (keep != null && cosmetic.sha256().equals(keep.sha256())) {
					keepEntry = entry;
				}
			}
			replaceEntries(entries);
			setSelected(keepEntry);
		}

		@Override
		public int getRowWidth() {
			return width - 12;
		}

		@Override
		public void setSelected(@Nullable Entry entry) {
			super.setSelected(entry);
			if (entry != null) {
				CatalogScreen.this.select(entry.cosmetic);
			}
		}

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final FeaturedCosmetic cosmetic;

			Entry(FeaturedCosmetic cosmetic) {
				this.cosmetic = cosmetic;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX() + 4;
				int y = getContentY() + 2;
				graphics.text(font, Component.literal(cosmetic.name()), x, y, 0xFFFFFFFF);
				Component line = cosmetic.author().isEmpty()
					? Component.translatable("orbit_cosmetic.catalog.verified_short")
					: Component.translatable("orbit_cosmetic.manager.author", cosmetic.author());
				LocalCosmetic local = installed(cosmetic);
				if (local != null) {
					line = line.copy().append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
						.append(Component.translatable(local.isLoaded() ? "orbit_cosmetic.status.loaded" : "orbit_cosmetic.catalog.in_library").withStyle(ChatFormatting.AQUA));
				}
				graphics.text(font, line.copy().withStyle(ChatFormatting.GRAY), x, y + 10, 0xFFFFFFFF);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				CatalogList.this.setSelected(this);
				if (doubleClick) {
					getSelected();
				}
				return true;
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", cosmetic.name());
			}
		}
	}
}
