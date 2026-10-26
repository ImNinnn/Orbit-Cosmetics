package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.OrbitClient;
import dev.orbit.cosmetic.client.anim.Wearer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Every Orbit player on the current server, with options to hide their cosmetics or report them. */
public final class PlayersScreen extends Screen {
	/** A player as listed here. */
	record OrbitPlayer(UUID id, String name, Wearer wearer) {
	}

	private final @Nullable Screen parent;
	private PlayerList list;
	private Button hideButton;
	private Button reportButton;
	private OrbitPlayer selected;

	public PlayersScreen(@Nullable Screen parent) {
		super(Component.translatable("orbit_cosmetic.players.title"));
		this.parent = parent;
	}

	/** Whether the menu has anything to show: connected somewhere Orbit players can see each other. */
	public static boolean available() {
		OrbitClient orbit = OrbitClient.get();
		return Minecraft.getInstance().player != null && (orbit.session().isOrbitServer() || orbit.backend().isActive());
	}

	@Override
	protected void init() {
		int listWidth = Math.min(320, width - 20);
		int left = (width - listWidth) / 2;
		list = new PlayerList(minecraft, listWidth, height - 32 - 64, 32);
		list.setX(left);
		addRenderableWidget(list);
		int buttonWidth = (listWidth - 8) / 3;
		int y = height - 52;
		hideButton = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.players.hide"), b -> toggleHidden())
			.bounds(left, y, buttonWidth, 20).build());
		reportButton = addRenderableWidget(Button.builder(Component.translatable("orbit_cosmetic.players.report"), b -> {
			if (selected != null) {
				minecraft.gui.setScreen(new ReportScreen(this, selected.id(), selected.name(), selected.wearer()));
			}
		}).bounds(left + buttonWidth + 4, y, buttonWidth, 20).tooltip(Tooltip.create(Component.translatable("orbit_cosmetic.players.report.tooltip"))).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(left + (buttonWidth + 4) * 2, y, buttonWidth, 20).build());
		refresh();
	}

	private void refresh() {
		List<OrbitPlayer> players = new ArrayList<>();
		ClientPacketListener connection = minecraft.getConnection();
		if (connection != null) {
			for (Wearer wearer : OrbitClient.get().session().remoteWearers().values()) {
				PlayerInfo info = connection.getPlayerInfo(wearer.id);
				if (info != null) {
					players.add(new OrbitPlayer(wearer.id, info.getProfile().name(), wearer));
				}
			}
		}
		players.sort(Comparator.comparing(player -> player.name().toLowerCase(Locale.ROOT)));
		list.show(players, selected == null ? null : selected.id());
		if (selected != null && players.stream().noneMatch(player -> player.id().equals(selected.id()))) {
			selected = null;
		}
		updateButtons();
	}

	private void select(OrbitPlayer player) {
		selected = player;
		updateButtons();
	}

	private void updateButtons() {
		boolean has = selected != null;
		hideButton.active = has;
		reportButton.active = has;
		hideButton.setMessage(Component.translatable(has && OrbitClient.get().config().isHidden(selected.id()) ? "orbit_cosmetic.players.show" : "orbit_cosmetic.players.hide"));
	}

	private void toggleHidden() {
		if (selected != null) {
			var config = OrbitClient.get().config();
			config.setHidden(selected.id(), !config.isHidden(selected.id()));
			updateButtons();
		}
	}

	@Override
	public void tick() {
		// Players come and go while the menu is open.
		if (minecraft.player != null && minecraft.player.tickCount % 20 == 0) {
			refresh();
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
		Component source = OrbitClient.get().session().isOrbitServer()
			? Component.translatable("orbit_cosmetic.players.source.server")
			: Component.translatable("orbit_cosmetic.players.source.cloud");
		graphics.centeredText(font, source.copy().withStyle(ChatFormatting.GRAY), width / 2, 20, 0xFFFFFFFF);
		if (list.children().isEmpty()) {
			graphics.centeredText(font, Component.translatable("orbit_cosmetic.players.empty"), width / 2, 70, 0xFFAAAAAA);
		}
		graphics.centeredText(font, Component.translatable("orbit_cosmetic.players.hint").withStyle(ChatFormatting.DARK_GRAY), width / 2, height - 26, 0xFFFFFFFF);
	}

	private final class PlayerList extends ObjectSelectionList<PlayerList.Entry> {
		PlayerList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, 24);
		}

		void show(List<OrbitPlayer> players, UUID keep) {
			List<Entry> entries = new ArrayList<>();
			Entry keepEntry = null;
			for (OrbitPlayer player : players) {
				Entry entry = new Entry(player);
				entries.add(entry);
				if (player.id().equals(keep)) {
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
				PlayersScreen.this.select(entry.player);
			}
		}

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final OrbitPlayer player;

			Entry(OrbitPlayer player) {
				this.player = player;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX() + 4;
				int y = getContentY() + 2;
				graphics.text(font, Component.literal(player.name()), x, y, 0xFFFFFFFF);
				int count = player.wearer().worn().size();
				Component status = Component.translatable("orbit_cosmetic.players.cosmetics", count).withStyle(ChatFormatting.GRAY);
				if (OrbitClient.get().config().isHidden(player.id())) {
					status = status.copy().append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
						.append(Component.translatable("orbit_cosmetic.players.hidden").withStyle(ChatFormatting.GOLD));
				}
				graphics.text(font, status, x, y + 10, 0xFFFFFFFF);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				PlayerList.this.setSelected(this);
				return true;
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", player.name());
			}
		}
	}
}
