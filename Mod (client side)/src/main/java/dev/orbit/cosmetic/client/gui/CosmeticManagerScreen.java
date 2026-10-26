package dev.orbit.cosmetic.client.gui;

import com.mojang.blaze3d.Blaze3D;
import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.library.CosmeticLibrary;
import dev.orbit.cosmetic.client.library.LocalCosmetic;
import dev.orbit.cosmetic.common.model.Anchor;
import dev.orbit.cosmetic.common.model.CosmeticModel;
import dev.orbit.cosmetic.common.model.Issue;
import dev.orbit.cosmetic.common.protocol.ServerPolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The Cosmetic Manager: lists every cosmetic in the orbit folder with its state, previews it on the player,
 * and loads, unloads, configures or removes it. Changes apply immediately.
 */
public final class CosmeticManagerScreen extends Screen {
	private enum Sort {
		NAME,
		LOADED_FIRST,
		NEWEST
	}

	private final @Nullable Screen parent;
	private CosmeticList list;
	private EditBox search;
	private Sort sort = Sort.LOADED_FIRST;
	private Button loadButton;
	private Button animationsButton;
	private Button previewButton;
	private Button removeButton;
	private LocalCosmetic selected;
	private final Runnable listener = this::refresh;
	private int panelLeft;
	private int panelRight;
	private int listBottom;
	/** Set while a child screen (animations, settings, confirmation) is open, so the preview stays active. */
	private boolean childOpen;

	public CosmeticManagerScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.manager.title"));
		this.parent = parent;
	}

	private static OrbitClient orbit() {
		return OrbitClient.get();
	}

	@Override
	protected void init() {
		childOpen = false;
		CosmeticLibrary library = orbit().library();
		library.addListener(listener);

		int listWidth = Math.max(180, Math.min(300, width / 2 - 20));
		int listLeft = 10;
		panelLeft = listLeft + listWidth + 10;
		panelRight = width - 10;
		int top = 46;
		listBottom = height - 58;

		search = new EditBox(font, listLeft, 24, listWidth - 84, 18, Component.translatable("orbit_cosmetic.manager.search"));
		search.setHint(Component.translatable("orbit_cosmetic.manager.search").withStyle(EditBox.SEARCH_HINT_STYLE));
		search.setResponder(text -> refresh());
		addRenderableWidget(search);
		addRenderableWidget(CycleButton.<Sort>builder(value -> Component.translatable("orbit_cosmetic.manager.sort." + value.name().toLowerCase(Locale.ROOT)), sort)
			.withValues(Sort.values())
			.displayOnlyValue()
			.create(listLeft + listWidth - 80, 23, 80, 20, Component.translatable("orbit_cosmetic.manager.sort"), (button, value) -> {
				sort = value;
				refresh();
			}));

		list = new CosmeticList(minecraft, listWidth, listBottom - top, top);
		list.setX(listLeft);
		addRenderableWidget(list);

		int actionWidth = (listWidth - 6) / 4;
		int actionY = listBottom + 4;
		loadButton = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.manager.load"), b -> toggleLoaded())
			.bounds(listLeft, actionY, actionWidth, 20).build());
		animationsButton = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.manager.animations"), b -> openAnimations())
			.bounds(listLeft + actionWidth + 2, actionY, actionWidth, 20).build());
		previewButton = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.manager.preview"), b -> togglePreview())
			.bounds(listLeft + (actionWidth + 2) * 2, actionY, actionWidth, 20).build());
		removeButton = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.manager.remove"), b -> confirmRemove())
			.bounds(listLeft + (actionWidth + 2) * 3, actionY, actionWidth, 20).build());

		int footerY = height - 28;
		Component[] labels = {
			Component.translatable("orbit_cosmetic.manager.import"),
			Component.translatable("orbit_cosmetic.manager.create"),
			Component.translatable("orbit_cosmetic.manager.catalog"),
			Component.translatable("orbit_cosmetic.manager.open_folder"),
			Component.translatable("orbit_cosmetic.manager.reload"),
			Component.translatable("orbit_cosmetic.manager.settings"),
			CommonComponents.GUI_DONE,
		};
		Button.OnPress[] actions = {
			b -> showImportHelp(),
			b -> createTemplate(),
			b -> openChild(new CatalogScreen(this)),
			b -> Blaze3D.openPath(orbit().paths().root()),
			b -> library.reload(),
			b -> openChild(new OrbitSettingsScreen(this)),
			b -> onClose(),
		};
		Tooltip[] tooltips = {
			Tooltip.create(Component.translatable("orbit_cosmetic.manager.import.tooltip")),
			Tooltip.create(Component.translatable("orbit_cosmetic.manager.create.tooltip")),
			Tooltip.create(Component.translatable("orbit_cosmetic.manager.catalog.tooltip")),
			null,
			null,
			null,
			null,
		};
		int footerWidth = Math.max(50, (width - 20 - 4 * (labels.length - 1)) / labels.length);
		for (int i = 0; i < labels.length; i++) {
			addRenderableWidget(Button.builder(labels[i], actions[i]).bounds(10 + i * (footerWidth + 4), footerY, footerWidth, 20).tooltip(tooltips[i]).build());
		}

		refresh();
	}

	@Override
	public void removed() {
		orbit().library().removeListener(listener);
		if (!childOpen) {
			orbit().setPreview(null);
		}
	}

	private void openChild(Screen screen) {
		childOpen = true;
		minecraft.gui.setScreen(screen);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ---------------------------------------------------------------- list content

	private void refresh() {
		if (list == null) {
			return;
		}
		String filter = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		Comparator<LocalCosmetic> order = switch (sort) {
			case NAME -> CosmeticLibrary.byName();
			case LOADED_FIRST -> Comparator.comparing((LocalCosmetic e) -> !e.isLoaded()).thenComparing(CosmeticLibrary.byName());
			case NEWEST -> Comparator.comparing((LocalCosmetic e) -> -e.path().toFile().lastModified()).thenComparing(CosmeticLibrary.byName());
		};
		List<LocalCosmetic> entries = orbit().library().entries().stream()
			.filter(e -> filter.isEmpty() || e.displayName().toLowerCase(Locale.ROOT).contains(filter) || e.key().toLowerCase(Locale.ROOT).contains(filter))
			.sorted(order)
			.toList();
		list.show(entries, selected);
		if (selected != null && !orbit().library().entries().contains(selected)) {
			select(null);
		}
		updateButtons();
	}

	private void select(LocalCosmetic entry) {
		selected = entry;
		if (orbit().preview() != null && orbit().preview() != entry) {
			orbit().setPreview(null);
		}
		updateButtons();
	}

	private void updateButtons() {
		boolean has = selected != null;
		boolean usable = has && selected.isUsable();
		loadButton.active = usable || has && selected.isLoaded();
		loadButton.setMessage(Component.translatable(has && selected.isLoaded() ? "orbit_cosmetic.manager.unload" : "orbit_cosmetic.manager.load"));
		animationsButton.active = usable;
		previewButton.active = usable && minecraft.player != null;
		previewButton.setMessage(Component.translatable(has && orbit().preview() == selected ? "orbit_cosmetic.manager.preview.stop" : "orbit_cosmetic.manager.preview"));
		removeButton.active = has;
	}

	// ---------------------------------------------------------------- actions

	private void toggleLoaded() {
		if (selected != null) {
			orbit().library().setLoaded(selected, !selected.isLoaded());
		}
	}

	private void togglePreview() {
		if (selected != null) {
			orbit().setPreview(orbit().preview() == selected ? null : selected);
			updateButtons();
		}
	}

	private void openAnimations() {
		if (selected != null && selected.isUsable()) {
			openChild(new AnimationSettingsScreen(this, selected));
		}
	}

	private void confirmRemove() {
		LocalCosmetic target = selected;
		if (target == null) {
			return;
		}
		openChild(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				if (orbit().library().remove(target)) {
					select(null);
				}
			}
			minecraft.gui.setScreen(this);
		}, Component.translatable("orbit_cosmetic.manager.remove.title"), Component.translatable("orbit_cosmetic.manager.remove.message", target.displayName(), target.key())));
	}

	private void createTemplate() {
		try {
			Path template = orbit().library().createTemplate();
			toast(Component.translatable("orbit_cosmetic.manager.create.done"), Component.literal(orbit().paths().root().relativize(template).toString()));
			Blaze3D.openPath(template);
		} catch (IOException e) {
			OrbitClient.LOGGER.warn("Could not create template", e);
			toast(Component.translatable("orbit_cosmetic.manager.create.failed"), Component.literal(String.valueOf(e.getMessage())));
		}
	}

	private void showImportHelp() {
		toast(Component.translatable("orbit_cosmetic.manager.import"), Component.translatable("orbit_cosmetic.manager.import.help"));
		Blaze3D.openPath(orbit().paths().root());
	}

	@Override
	public void onFilesDrop(List<Path> files) {
		List<String> report = orbit().library().importFiles(files);
		toast(Component.translatable("orbit_cosmetic.manager.import.result"), Component.literal(String.join("; ", report)));
	}

	private void toast(Component title, Component message) {
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, message);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		if (orbit().library().entries().isEmpty()) {
			int centerX = 10 + (panelLeft - 20) / 2;
			graphics.centeredText(font, Component.translatable(orbit().library().isScanning() ? "orbit_cosmetic.manager.scanning" : "orbit_cosmetic.manager.empty"), centerX, 70, 0xFFAAAAAA);
		}
		extractPanel(graphics, mouseX, mouseY);
	}

	private void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int top = 24;
		graphics.fill(panelLeft, top, panelRight, listBottom + 24, 0x66000000);
		int previewBottom = top + Math.min(120, (listBottom - top) / 2);
		if (minecraft.player != null) {
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, panelLeft + 4, top + 4, panelRight - 4, previewBottom, 45, 0.0625f, mouseX, mouseY, minecraft.player);
		} else {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.manager.no_player"), (panelLeft + panelRight) / 2, (top + previewBottom) / 2, 0xFFAAAAAA);
		}

		int x = panelLeft + 6;
		int y = previewBottom + 6;
		int textWidth = panelRight - panelLeft - 12;
		ServerPolicy policy = orbit().session().policy();
		if (orbit().session().isOrbitServer() && policy != null) {
			Component server = !policy.enabled() ? Component.translatable("orbit_cosmetic.manager.server.disabled").withStyle(ChatFormatting.RED)
				: !policy.canWear() ? Component.translatable("orbit_cosmetic.manager.server.blocked").withStyle(ChatFormatting.RED)
				: Component.translatable("orbit_cosmetic.manager.server.connected", policy.maxEquipped()).withStyle(ChatFormatting.GREEN);
			y = wrapped(graphics, server, x, y, textWidth);
		}
		if (selected == null) {
			wrapped(graphics, Component.translatable("orbit_cosmetic.manager.hint"), x, y, textWidth);
			return;
		}
		y = wrapped(graphics, Component.literal(selected.displayName()).withStyle(ChatFormatting.BOLD), x, y, textWidth);
		y = wrapped(graphics, Component.literal(selected.key()).withStyle(ChatFormatting.DARK_GRAY), x, y, textWidth);
		if (selected.author() != null && !selected.author().isEmpty()) {
			y = wrapped(graphics, Component.translatable("orbit_cosmetic.manager.author", selected.author()).withStyle(ChatFormatting.GRAY), x, y, textWidth);
		}
		if (selected.description() != null && !selected.description().isEmpty()) {
			y = wrapped(graphics, Component.literal(selected.description()).withStyle(ChatFormatting.ITALIC), x, y, textWidth);
		}
		CosmeticModel model = selected.model();
		if (model != null) {
			String anchors = model.anchorsUsed().stream().map(Anchor::name).collect(Collectors.joining(", "));
			y = wrapped(graphics, Component.translatable("orbit_cosmetic.manager.details", model.quadCount(), model.bones().size(), model.textures().size(), model.animations().size()), x, y, textWidth);
			if (!anchors.isEmpty()) {
				y = wrapped(graphics, Component.translatable("orbit_cosmetic.manager.anchors", anchors), x, y, textWidth);
			}
		}
		if (selected.duplicateOf() != null) {
			y = wrapped(graphics, Component.translatable("orbit_cosmetic.manager.duplicate", selected.duplicateOf()).withStyle(ChatFormatting.YELLOW), x, y, textWidth);
		}
		if (selected.serverRejection() != null) {
			y = wrapped(graphics, Component.translatable("orbit_cosmetic.manager.rejected", selected.serverRejection()).withStyle(ChatFormatting.RED), x, y, textWidth);
		}
		if (policy != null && selected.isLoaded() && orbit().library().loaded().indexOf(selected) >= policy.maxEquipped()) {
			y = wrapped(graphics, Component.translatable("orbit_cosmetic.manager.over_limit", policy.maxEquipped()).withStyle(ChatFormatting.YELLOW), x, y, textWidth);
		}
		for (Issue issue : selected.issues()) {
			if (y > listBottom + 10) {
				break;
			}
			y = wrapped(graphics, Component.literal((issue.isError() ? "✖ " : "⚠ ") + issue.message()).withStyle(issue.isError() ? ChatFormatting.RED : ChatFormatting.GOLD), x, y, textWidth);
		}
	}

	private int wrapped(GuiGraphicsExtractor graphics, Component text, int x, int y, int width) {
		for (FormattedCharSequence line : font.split(text, width)) {
			graphics.text(font, line, x, y, 0xFFFFFFFF);
			y += 10;
		}
		return y + 2;
	}

	// ---------------------------------------------------------------- list widget

	private final class CosmeticList extends ObjectSelectionList<CosmeticList.Entry> {
		CosmeticList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, 26);
		}

		void show(List<LocalCosmetic> cosmetics, LocalCosmetic keep) {
			List<Entry> entries = new ArrayList<>();
			Entry keepEntry = null;
			for (LocalCosmetic cosmetic : cosmetics) {
				Entry entry = new Entry(cosmetic);
				entries.add(entry);
				if (cosmetic == keep) {
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
				CosmeticManagerScreen.this.select(entry.cosmetic);
			}
		}

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final LocalCosmetic cosmetic;

			Entry(LocalCosmetic cosmetic) {
				this.cosmetic = cosmetic;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX() + 2;
				int y = getContentY() + 2;
				graphics.text(font, Component.literal(cosmetic.displayName()), x, y, 0xFFFFFFFF);
				graphics.text(font, statusLine(), x, y + 11, 0xFFFFFFFF);
				if (cosmetic.isLoaded()) {
					graphics.fill(getContentX() + getContentWidth() - 6, y + 2, getContentX() + getContentWidth() - 2, y + 6, cosmetic.serverRejection() == null ? 0xFF55FF55 : 0xFFFF5555);
				}
			}

			private Component statusLine() {
				return switch (cosmetic.status()) {
					case CHECKING -> Component.translatable("orbit_cosmetic.status.checking").withStyle(ChatFormatting.GRAY);
					case INVALID -> Component.translatable("orbit_cosmetic.status.invalid").withStyle(ChatFormatting.RED);
					case READY -> {
						Component state = Component.translatable(cosmetic.isLoaded() ? "orbit_cosmetic.status.loaded" : "orbit_cosmetic.status.unloaded")
							.withStyle(cosmetic.isLoaded() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
						Component line = Component.empty().append(state);
						if (cosmetic.hasAnimations()) {
							line = line.copy().append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY)).append(Component.translatable("orbit_cosmetic.status.animated").withStyle(ChatFormatting.AQUA));
						}
						long warnings = cosmetic.issues().stream().filter(issue -> !issue.isError()).count();
						if (cosmetic.duplicateOf() != null || cosmetic.serverRejection() != null || warnings > 0) {
							line = line.copy().append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY)).append(Component.translatable("orbit_cosmetic.status.warnings").withStyle(ChatFormatting.GOLD));
						}
						yield line;
					}
				};
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				CosmeticList.this.setSelected(this);
				if (doubleClick && (cosmetic.isUsable() || cosmetic.isLoaded())) {
					orbit().library().setLoaded(cosmetic, !cosmetic.isLoaded());
				}
				return true;
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", cosmetic.displayName());
			}
		}
	}
}
