package de.villagerplus.client;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageMessages;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.resources.Identifier;

/** Animated toast for this mod's own system messages; normal player chat remains untouched. */
final class VillageMessageToast implements HudElement {
	private static final long LIFETIME_MS = 4200L;
	private static final int MAX_TOASTS = 3;
	private static final int MAX_WIDTH = 270;
	private static final Deque<Toast> QUEUE = new ArrayDeque<>();
	private static final VillageMessageToast INSTANCE = new VillageMessageToast();

	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	/** Off switch: -Dvillagerplus.toast=false, and automatic after the first failure so a modpack conflict never crashes the game. */
	private static volatile boolean disabled = "false".equalsIgnoreCase(System.getProperty("villagerplus.toast", "true"));

	private record Toast(String text, long createdAt) { }

	private static void fail(Throwable error) {
		if (!disabled) LOGGER.error("Villagers: Repurposed: Dorf-Toast ausgeschaltet nach Fehler (Chatnachricht bleibt erhalten)", error);
		disabled = true;
		synchronized (VillageMessageToast.class) { QUEUE.clear(); }
	}

	private VillageMessageToast() { }

	static void register() {
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (disabled) return true;
			try {
				String text = message.getString();
				if (text.startsWith(VillageMessages.CHAT_MARKER)) add(text.substring(VillageMessages.CHAT_MARKER.length()));
			} catch (Throwable error) {
				fail(error);
			}
			return true;
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "village_message_toast"), INSTANCE);
	}

	private static synchronized void add(String text) {
		while (QUEUE.size() >= MAX_TOASTS) QUEUE.removeFirst();
		QUEUE.addLast(new Toast(text, System.currentTimeMillis()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (disabled) return;
		try {
			render(graphics);
		} catch (Throwable error) {
			fail(error);
		}
	}

	private void render(GuiGraphicsExtractor graphics) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.font == null) return;
		List<Toast> active;
		long now = System.currentTimeMillis();
		synchronized (VillageMessageToast.class) {
			QUEUE.removeIf(toast -> now - toast.createdAt() >= LIFETIME_MS);
			active = new ArrayList<>(QUEUE);
		}
		int y = 18;
		for (Toast toast : active) {
			long age = now - toast.createdAt();
			draw(graphics, client.font, client.getWindow().getGuiScaledWidth(), y, toast.text(), age);
			y += 49;
		}
	}

	private static void draw(GuiGraphicsExtractor g, Font font, int screenWidth, int y, String message, long age) {
		int contentWidth = MAX_WIDTH - 36;
		List<String> lines = wrap(font, message, contentWidth, 2);
		int messageWidth = 0;
		for (String line : lines) messageWidth = Math.max(messageWidth, font.width(line));
		int width = Math.max(184, Math.min(MAX_WIDTH, messageWidth + 36));
		int height = lines.size() == 1 ? 43 : 53;

		double enter = Math.min(1.0, age / 220.0);
		double eased = 1.0 - Math.pow(1.0 - enter, 3.0);
		double fade = age > 3500 ? Math.max(0.0, (LIFETIME_MS - age) / 700.0) : 1.0;
		int alpha = (int)Math.round(255.0 * fade);
		int slide = (int)Math.round(22.0 * (1.0 - eased));
		int x = screenWidth - width - 12 + slide;
		int dark = color(0x111821, alpha * 0.92);
		int edge = color(0xD8AD55, alpha);
		int white = color(0xF3F1E9, alpha);
		int muted = color(0xB9C2C4, alpha);

		g.fill(x + 2, y + 3, x + width + 2, y + height + 3, color(0x000000, alpha * 0.34));
		g.fill(x, y, x + width, y + height, dark);
		g.outline(x, y, width, height, color(0x41515A, alpha));
		g.fill(x, y + 1, x + 3, y + height - 1, edge);
		g.fill(x + 10, y + 9, x + 31, y + 30, color(0x27332C, alpha));
		g.text(font, "◆", x + 15, y + 14, edge);
		g.text(font, "DORF", x + 38, y + 8, edge);
		g.text(font, "NEU", x + width - 34, y + 8, muted);
		for (int i = 0; i < lines.size(); i++) {
			g.text(font, lines.get(i), x + 11, y + 33 + i * 10, white);
		}
	}

	private static List<String> wrap(Font font, String message, int width, int maxLines) {
		List<String> lines = new ArrayList<>(maxLines);
		String remaining = message == null ? "" : message.strip();
		for (int line = 0; line < maxLines && !remaining.isEmpty(); line++) {
			String part = font.plainSubstrByWidth(remaining, width);
			if (part.isEmpty()) part = remaining.substring(0, 1);
			String rest = remaining.substring(part.length()).stripLeading();
			if (!rest.isEmpty()) {
				int wordBoundary = part.lastIndexOf(' ');
				if (wordBoundary > 0) {
					rest = remaining.substring(wordBoundary + 1).stripLeading();
					part = remaining.substring(0, wordBoundary);
				}
			}
			if (line == maxLines - 1 && !rest.isEmpty()) part = font.plainSubstrByWidth(part, Math.max(1, width - font.width("…"))) + "…";
			lines.add(part);
			remaining = rest;
		}
		if (lines.isEmpty()) lines.add("");
		return lines;
	}

	private static int color(int rgb, double alpha) {
		int a = Math.max(0, Math.min(255, (int)Math.round(alpha)));
		return (a << 24) | (rgb & 0x00FFFFFF);
	}
}
