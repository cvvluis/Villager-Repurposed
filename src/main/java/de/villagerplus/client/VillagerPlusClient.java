package de.villagerplus.client;

import de.villagerplus.net.InfoRequest;
import de.villagerplus.net.InfoResponse;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;

/**
 * Client-Teil: ein "Info"-Knopf im Handelsmenue, der neben dem Menue den Steckbrief des Villagers einblendet
 * (Name, Beruf, Taetigkeit, Befinden, Haus, Besitz, Ruf ...). Die Daten kommen live vom Server.
 */
public class VillagerPlusClient implements ClientModInitializer {
	private static final int GUI_WIDTH = 276;
	private static final int GUI_HEIGHT = 166;
	private static final int REFRESH_TICKS = 20;
	private static final int PANEL_MAX_WIDTH = 360;
	private static final int SIDE_PANEL_MIN_WIDTH = 210;
	private static final int BOARD_PANEL_WIDTH = 460;
	private static final int BOARD_LINE_WIDTH = BOARD_PANEL_WIDTH - 28;
	private static final int BOARD_LINE_HEIGHT = 11;

	private record Row(String label, String value, int percent) { }
	private record DisplayRow(List<String> labels, List<String> values, int height) { }
	private static final Field BOARD_TAB_FIELD = boardScreenField("tab");
	private static final Field BOARD_SCROLL_FIELD = boardScreenField("scroll");

	/** Pro Handelsmenue eigener Zustand; der schwache Schluessel haelt geschlossene Screens nicht fest. */
	private static final Map<Screen, InfoPanelState> INFO_PANELS = new WeakHashMap<>();
	/** The board already owns Talk content and scrolling; this only adds a clipped scroll-position affordance. */
	private static final Map<Screen, Boolean> BOARD_TALK_SCROLLBARS = new WeakHashMap<>();
	private static int nextInfoRequestId;

	private static final class InfoPanelState {
		List<Row> rows = List.of();
		List<DisplayRow> displayRows = List.of();
		boolean infoReceived;
		boolean visible;
		boolean closed;
		boolean compact;
		int ticks;
		int rowOffset;
		int rowLineOffset;
		int viewportHeight;
		int requestId;
		long requestStartedAt;
		int screenWidth;
		int screenHeight;
		int guiLeft;
		int guiTop;
		int buttonX;
		int buttonY;
		int panelX;
		int panelY;
		int panelWidth;
		int panelHeight;
		int maxRowOffset;
		int maxRowLineOffset;
	}

	@Override
	public void onInitializeClient() {
		net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
			de.villagerplus.block.ModBlocks.DORFTAFEL_NAME, DorftafelNameRenderer::new);
		VillageMessageToast.register();
		E04RoleRenderer.register();
		net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry.registerModelLayer(
			CaravanTraderModel.LAYER, CaravanTraderModel::createBodyLayer);
		net.minecraft.client.renderer.entity.EntityRenderers.register(
			de.villagerplus.entity.ModEntities.CARAVAN_TRADER, CaravanTraderRenderer::new);
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			// Nur Entwicklung: Datei "vpshot.txt" im Spielordner loest einen Screenshot aus (fuer automatische Sichtpruefung).
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				if (mc.player != null && mc.player.tickCount % 10 == 0) {
					java.io.File f = new java.io.File(mc.gameDirectory, "vpshot.txt");
					if (f.exists()) {
						String cmd = "";
						try { cmd = java.nio.file.Files.readString(f.toPath()).trim(); } catch (java.io.IOException ignored) { }
						if (f.delete()) {
							if (cmd.equals("mapcheck") && mc.gui.screen() instanceof BoardScreen board) {
								try {
									ClientChecks.map(board);
									java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("vp-client-checks.txt"),
										"PASS: scaled map, four-way zone drags, zoom lock, map markers, neighbor actions and panel layout");
								} catch (Exception error) {
									try { java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("vp-client-checks.txt"), "FAILED: " + error); } catch (java.io.IOException ignored) { }
								}
							} else if (cmd.equals("close")) {
								mc.gui.setScreen(null);
							} else {
								net.minecraft.client.Screenshot.grab(mc, false);
							}
						}
					}
				}
			});
		}
		ClientPlayNetworking.registerGlobalReceiver(de.villagerplus.net.StockPolicyData.TYPE, (payload, context) -> context.client().execute(() -> BoardScreen.stockRows = payload.rows()));
		ClientPlayNetworking.registerGlobalReceiver(InfoResponse.TYPE, (payload, context) -> context.client().execute(() -> {
			InfoPanelState state = INFO_PANELS.get(context.client().gui.screen());
			if (state == null || state.closed || !state.visible || state.requestId != payload.requestId()) {
				return;
			}
			state.rows = parse(payload.lines());
			state.infoReceived = true;
		}));
		ClientPlayNetworking.registerGlobalReceiver(de.villagerplus.net.NetworkActionResult.TYPE, (payload, context) -> context.client().execute(() -> {
			if (context.client().gui.screen() instanceof BoardScreen open && open.acceptNetworkActionResult(payload)) return;
			if (context.client().player != null) {
				String notice = BoardScreen.networkActionNotice(payload.success(), payload.message());
				context.client().player.sendSystemMessage(de.villagerplus.village.VillageMessages.format(Component.literal(notice)));
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(de.villagerplus.net.QuotaData.TYPE, (payload, context) -> context.client().execute(() -> BoardScreen.quota = payload.rows()));
		ClientPlayNetworking.registerGlobalReceiver(de.villagerplus.net.BoardData.TYPE, (payload, context) -> context.client().execute(() -> {
			if (context.client().gui.screen() instanceof BoardScreen open) {
				if (BoardScreen.autoPending > 0) BoardScreen.autoPending--;
				open.update(payload);
			} else if (BoardScreen.autoPending > 0) {
				BoardScreen.autoPending--; // Antwort auf eine Live-Abfrage, der Spieler hat die Tafel schon geschlossen
			} else {
				context.client().gui.setScreen(new BoardScreen(payload));
			}
		}));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof BoardScreen board) {
				registerBoardTalkScrollbar(board);
				return;
			}
			if (!(screen instanceof MerchantScreen)) {
				return;
			}
			InfoPanelState existing = INFO_PANELS.get(screen);
			if (existing == null) {
				existing = new InfoPanelState();
				INFO_PANELS.put(screen, existing);
				registerInfoPanelEvents(screen, existing);
			} else if (existing.closed) {
				resetInfoPanel(existing);
				existing.closed = false;
			}
			InfoPanelState state = existing;
			int left = (width - GUI_WIDTH) / 2;
			int top = (height - GUI_HEIGHT) / 2;
			state.screenWidth = width;
			state.screenHeight = height;
			state.guiLeft = left;
			state.guiTop = top;
			state.buttonX = Math.max(4, Math.min(left, width - 52));
			state.buttonY = Math.max(2, Math.min(top - 22, height - 22));
			Button info = Button.builder(Component.literal("Info"), b -> {
				state.visible = !state.visible;
				state.rowOffset = 0;
				state.rowLineOffset = 0;
				state.rows = List.of();
				state.infoReceived = false;
				state.ticks = 0;
				if (state.visible) {
					state.requestStartedAt = System.currentTimeMillis();
					requestInfo(state);
				} else {
					state.requestId = 0;
				}
			}).bounds(state.buttonX, state.buttonY, 48, 20).build();
			Screens.getWidgets(screen).add(info);
		});
	}

	private static Field boardScreenField(String name) {
		try {
			Field field = BoardScreen.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	private static void registerBoardTalkScrollbar(BoardScreen board) {
		if (BOARD_TALK_SCROLLBARS.put(board, Boolean.TRUE) != null) return;
		ScreenEvents.remove(board).register(removed -> BOARD_TALK_SCROLLBARS.remove(removed));
		ScreenEvents.afterExtract(board).register((screen, graphics, mouseX, mouseY, delta) ->
			drawBoardTalkScrollbar(board, graphics, Screens.getFont(screen)));
	}

	private static void drawBoardTalkScrollbar(BoardScreen board, GuiGraphicsExtractor graphics, Font font) {
		if (BOARD_TAB_FIELD == null || BOARD_SCROLL_FIELD == null) return;
		try {
			if (BOARD_TAB_FIELD.getInt(board) != 8) return;
			int scaleWidth = board.width;
			int scaleHeight = board.height;
			float scale = BoardScreen.panelUiScale(scaleWidth, scaleHeight);
			int[] origin = BoardScreen.panelOrigin(scaleWidth, scaleHeight, scale);
			List<String> lines = board.selectedResidentTalkDisplayLines();
			int wrappedLines = wrappedBoardLineCount(font, lines, BOARD_LINE_WIDTH);
			int[] thumb = talkScrollbarBounds(origin[0], origin[1], wrappedLines, BOARD_SCROLL_FIELD.getInt(board));
			if (thumb.length == 0) return;

			int trackX = thumb[0];
			int trackY = origin[1] + 52;
			int trackHeight = thumb[3];
			graphics.pose().pushMatrix();
			graphics.pose().scale(scale, scale);
			graphics.enableScissor(trackX, trackY, trackX + 3, trackY + trackHeight);
			graphics.fill(trackX, trackY, trackX + 3, trackY + trackHeight, 0xFFB79C6B);
			graphics.fill(trackX, thumb[1], trackX + 3, thumb[1] + thumb[2], 0xFF8C2A22);
			graphics.disableScissor();
			graphics.pose().popMatrix();
		} catch (IllegalAccessException ignored) {
			// If a future screen changes its private scroll fields, keep the existing Talk content usable.
		}
	}

	/** Mirrors the board's wrapping so the scrollbar follows its existing wheel-scroll offset exactly. */
	static int wrappedBoardLineCount(Font font, List<String> source, int maxWidth) {
		if (font == null || source == null || maxWidth <= 0) return 0;
		int count = 0;
		for (String line : source) {
			String raw = line == null ? "" : line.replace("===", "");
			boolean indented = raw.length() != raw.stripLeading().length();
			String rest = raw.strip();
			int indentWidth = indented ? 10 : 0;
			boolean first = true;
			if (rest.isEmpty()) count++;
			while (!rest.isEmpty()) {
				String part = font.plainSubstrByWidth(rest, maxWidth - indentWidth - (first ? 0 : 12));
				if (part.isEmpty()) part = rest.substring(0, rest.offsetByCodePoints(0, 1));
				if (part.length() < rest.length()) {
					int space = part.lastIndexOf(' ');
					if (space > 8) part = part.substring(0, space);
				}
				count++;
				rest = rest.substring(part.length()).stripLeading();
				first = false;
			}
		}
		return count;
	}

	/** Returns {trackX, thumbY, thumbHeight, trackHeight}; an empty array means the report fits without scrolling. */
	static int[] talkScrollbarBounds(int panelLeft, int panelTop, int totalLines, int scrollOffset) {
		int defaultVisibleRows = BoardScreen.listVisibleRows(0);
		if (totalLines <= defaultVisibleRows) return new int[0];
		int visibleRows = BoardScreen.listVisibleRows(1);
		if (visibleRows <= 0) return new int[0];
		int trackHeight = visibleRows * BOARD_LINE_HEIGHT;
		int maxOffset = Math.max(1, totalLines - visibleRows);
		int offset = Math.max(0, Math.min(scrollOffset, maxOffset));
		int thumbHeight = Math.max(12, trackHeight * visibleRows / totalLines);
		thumbHeight = Math.min(trackHeight, thumbHeight);
		int thumbY = panelTop + 52 + (trackHeight - thumbHeight) * offset / maxOffset;
		return new int[]{panelLeft + BOARD_PANEL_WIDTH - 12, thumbY, thumbHeight, trackHeight};
	}

	private static void registerInfoPanelEvents(Screen screen, InfoPanelState state) {
		ScreenEvents.remove(screen).register(s -> {
			state.closed = true;
			state.visible = false;
			resetInfoPanel(state);
		});
		ScreenEvents.afterTick(screen).register(s -> {
			if (state.visible && ++state.ticks % REFRESH_TICKS == 0) {
				requestInfo(state);
			}
		});
		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
			if (state.visible && !state.closed) {
				drawPanel(state, graphics, Screens.getFont(s));
			}
		});
		ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> !insidePanel(state, click.x(), click.y()));
		ScreenMouseEvents.allowMouseRelease(screen).register((s, release) -> !insidePanel(state, release.x(), release.y()));
		ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) -> {
			if (!insidePanel(state, mouseX, mouseY)) {
				return true;
			}
			int direction = (int) Math.signum(vertical);
			if (direction == 0) {
				return false;
			}
			if (direction < 0) {
				if (state.rowLineOffset < state.maxRowLineOffset) {
					state.rowLineOffset++;
				} else if (state.rowOffset < state.maxRowOffset) {
					state.rowOffset++;
					state.rowLineOffset = 0;
					state.maxRowLineOffset = maxRowLineOffset(state.displayRows, state.rowOffset,
						state.viewportHeight, state.compact);
				}
			} else {
				if (state.rowLineOffset > 0) {
					state.rowLineOffset--;
				} else if (state.rowOffset > 0) {
					state.rowOffset--;
					state.maxRowLineOffset = maxRowLineOffset(state.displayRows, state.rowOffset,
						state.viewportHeight, state.compact);
					state.rowLineOffset = state.maxRowLineOffset;
				}
			}
			return false;
		});
	}

	private static boolean insidePanel(InfoPanelState state, double mouseX, double mouseY) {
		return state.visible && !state.closed && state.panelWidth > 0 && state.panelHeight > 0
			&& mouseX >= state.panelX && mouseX < state.panelX + state.panelWidth
			&& mouseY >= state.panelY && mouseY < state.panelY + state.panelHeight;
	}

	private static void resetInfoPanel(InfoPanelState state) {
		state.rows = List.of();
		state.displayRows = List.of();
		state.infoReceived = false;
		state.ticks = 0;
		state.rowOffset = 0;
		state.rowLineOffset = 0;
		state.requestId = 0;
		state.requestStartedAt = 0L;
		state.panelX = 0;
		state.panelY = 0;
		state.panelWidth = 0;
		state.panelHeight = 0;
		state.maxRowOffset = 0;
		state.maxRowLineOffset = 0;
		state.viewportHeight = 0;
	}

	private static void requestInfo(InfoPanelState state) {
		nextInfoRequestId = nextInfoRequestId == Integer.MAX_VALUE ? 1 : nextInfoRequestId + 1;
		state.requestId = nextInfoRequestId;
		ClientPlayNetworking.send(new InfoRequest(state.requestId));
	}

	private static void drawPanel(InfoPanelState state, GuiGraphicsExtractor g, Font font) {
		int screenWidth = state.screenWidth;
		int screenHeight = state.screenHeight;
		int leftSpace = Math.max(0, state.guiLeft - 10);
		int rightX = state.guiLeft + GUI_WIDTH + 6;
		int rightSpace = Math.max(0, screenWidth - rightX - 4);
		int sideSpace = Math.max(leftSpace, rightSpace);
		boolean sidePanel = sideSpace >= SIDE_PANEL_MIN_WIDTH;
		int panelW = sidePanel ? Math.min(PANEL_MAX_WIDTH, sideSpace) : Math.min(PANEL_MAX_WIDTH, Math.max(1, screenWidth - 8));
		int x;
		if (sidePanel) {
			boolean useRight = rightSpace >= leftSpace;
			x = useRight ? rightX : 4;
			panelW = Math.max(1, panelW);
		} else {
			x = Math.max(0, (screenWidth - panelW) / 2);
		}

		int belowButton = state.buttonY + 24;
		int preferredY = sidePanel ? Math.max(state.guiTop, belowButton) : belowButton;
		int y = Math.max(4, Math.min(preferredY, Math.max(4, screenHeight - 48)));
		int maxHeight = Math.max(0, screenHeight - y - 4);
		if (maxHeight < 40) {
			y = 4;
			maxHeight = Math.max(0, screenHeight - 8);
		}

		state.panelX = x;
		state.panelY = y;
		state.panelWidth = panelW;
		state.panelHeight = maxHeight;
		if (panelW < 1 || maxHeight < 22) {
			state.panelX = 0;
			state.panelY = 0;
			state.panelWidth = 0;
			state.panelHeight = 0;
			return;
		}

		int innerW = Math.max(1, panelW - 12);
		boolean compact = panelW < SIDE_PANEL_MIN_WIDTH;
		List<DisplayRow> displayRows = layoutRows(state.rows, font, innerW, compact);
		state.displayRows = displayRows;
		state.compact = compact;
		int totalContentHeight = contentHeight(displayRows);
		List<String> statusLines = state.rows.isEmpty()
			? wrapText(font, emptyStatus(state), innerW)
			: List.of();
		if (state.rows.isEmpty()) {
			totalContentHeight = statusLines.size() * 10 + 2;
		}
		int titleHeight = 22;
		int bottomPadding = 6;
		int footerHeight = 12;
		boolean scrollable = !displayRows.isEmpty() && totalContentHeight > maxHeight - titleHeight - bottomPadding;
		int panelH = scrollable ? maxHeight : Math.min(maxHeight, titleHeight + totalContentHeight + bottomPadding);
		panelH = Math.max(Math.min(maxHeight, 32), panelH);
		int viewportHeight = Math.max(0, panelH - titleHeight - bottomPadding - (scrollable ? footerHeight : 0));
		state.viewportHeight = viewportHeight;
		state.maxRowOffset = maxRowOffset(displayRows, viewportHeight);
		state.rowOffset = Math.max(0, Math.min(state.rowOffset, state.maxRowOffset));
		state.maxRowLineOffset = maxRowLineOffset(displayRows, state.rowOffset, viewportHeight, compact);
		state.rowLineOffset = Math.max(0, Math.min(state.rowLineOffset, state.maxRowLineOffset));
		state.panelHeight = panelH;

		g.fill(x, y, x + panelW, y + panelH, 0xE0101018);
		g.outline(x, y, panelW, panelH, 0xFF6B6B8A);
		g.text(font, clipped(font, "Dorfbewohner-Steckbrief", innerW), x + 6, y + 5, 0xFFFFD27F);

		int rowY = y + titleHeight;
		if (state.rows.isEmpty()) {
			for (String line : statusLines) {
				g.text(font, line, x + 6, rowY, 0xFFAAAAAA);
				rowY += 10;
			}
			return;
		}

		int usedHeight = 0;
		int end = state.rowOffset;
		for (int i = state.rowOffset; i < displayRows.size(); i++) {
			DisplayRow row = displayRows.get(i);
			if (usedHeight + row.height() > viewportHeight) {
				if (end == state.rowOffset) {
					int visibleLines = visibleLineCount(row, viewportHeight, compact);
					drawRow(g, font, x, rowY, panelW, row, state.rows.get(i), compact,
						state.rowLineOffset, visibleLines, viewportHeight);
					end = i + 1;
				}
				break;
			}
			int lineCount = textLineCount(row, compact);
			drawRow(g, font, x, rowY, panelW, row, state.rows.get(i), compact, 0, lineCount, row.height());
			rowY += row.height();
			usedHeight += row.height();
			end = i + 1;
		}
		if (scrollable) {
			String footer = (state.rowOffset + 1) + "–" + end + " / " + state.rows.size() + "  ·  Mausrad";
			g.text(font, clipped(font, footer, innerW), x + 6, y + panelH - 10, 0xFFAAAAC0);
		}
	}

	private static String emptyStatus(InfoPanelState state) {
		if (state.infoReceived) {
			return "Keine Informationen verfügbar";
		}
		if (state.requestStartedAt > 0 && System.currentTimeMillis() - state.requestStartedAt > 4000L) {
			return "Keine Antwort vom Villager";
		}
		return "Lade ...";
	}

	private static List<DisplayRow> layoutRows(List<Row> rows, Font font, int innerWidth, boolean compact) {
		List<DisplayRow> out = new ArrayList<>(rows.size());
		int labelWidth = compact ? innerWidth : Math.max(72, Math.min(140, innerWidth * 42 / 100));
		int valueWidth = compact ? innerWidth : Math.max(1, innerWidth - labelWidth - 8);
		int barWidth = compact ? innerWidth : Math.max(0, Math.min(40, valueWidth - 48));
		int textValueWidth = compact ? valueWidth : Math.max(1, valueWidth - (barWidth > 0 ? barWidth + 4 : 0));
		for (Row row : rows) {
			List<String> labels = wrapText(font, row.label(), labelWidth);
			List<String> values = wrapText(font, displayValue(row), textValueWidth);
			int textLines = compact ? labels.size() + values.size() : Math.max(labels.size(), values.size());
			int height = textLines * 10 + (row.percent() >= 0 ? 6 : 2);
			out.add(new DisplayRow(labels, values, height));
		}
		return List.copyOf(out);
	}

	private static int contentHeight(List<DisplayRow> rows) {
		int height = 0;
		for (DisplayRow row : rows) {
			height += row.height();
		}
		return height;
	}

	private static int maxRowOffset(List<DisplayRow> rows, int viewportHeight) {
		int remaining = 0;
		for (int i = rows.size() - 1; i >= 0; i--) {
			remaining += rows.get(i).height();
			if (remaining > viewportHeight) {
				return Math.min(rows.size() - 1, i + 1);
			}
		}
		return 0;
	}

	private static int maxRowLineOffset(List<DisplayRow> rows, int rowOffset, int viewportHeight, boolean compact) {
		if (rowOffset < 0 || rowOffset >= rows.size()) {
			return 0;
		}
		DisplayRow row = rows.get(rowOffset);
		if (row.height() <= viewportHeight) {
			return 0;
		}
		return Math.max(0, textLineCount(row, compact) - visibleLineCount(row, viewportHeight, compact));
	}

	private static int visibleLineCount(DisplayRow row, int viewportHeight, boolean compact) {
		int lineHeightBudget = Math.max(10, viewportHeight - (compact && row.values().size() > 0 ? 6 : 0));
		return Math.max(1, Math.min(textLineCount(row, compact), lineHeightBudget / 10));
	}

	private static int textLineCount(DisplayRow row, boolean compact) {
		return compact ? row.labels().size() + row.values().size() : Math.max(row.labels().size(), row.values().size());
	}

	private static void drawRow(GuiGraphicsExtractor g, Font font, int x, int y, int panelWidth,
			DisplayRow display, Row row, boolean compact, int lineOffset, int visibleLines, int maxHeight) {
		int labelX = x + 6;
		int valueX = compact ? labelX : x + 6 + Math.max(72, Math.min(140, (panelWidth - 12) * 42 / 100)) + 8;
		int totalLines = textLineCount(display, compact);
		int startLine = Math.max(0, Math.min(lineOffset, Math.max(0, totalLines - 1)));
		int endLine = Math.min(totalLines, startLine + Math.max(1, visibleLines));
		boolean clippedAtEnd = endLine < totalLines;
		if (compact) {
			for (int line = startLine; line < endLine; line++) {
				boolean labelLine = line < display.labels().size();
				String text = labelLine ? display.labels().get(line) : display.values().get(line - display.labels().size());
				if (clippedAtEnd && line == endLine - 1) {
					text = withEllipsis(font, text, panelWidth - 12);
				}
				g.text(font, text, labelX, y + (line - startLine) * 10, labelLine ? 0xFFAAAAAA : 0xFFFFFFFF);
			}
			if (row.percent() >= 0 && startLine == 0 && endLine == totalLines) {
				int barTop = y + totalLines * 10 + 1;
				int barWidth = Math.max(0, panelWidth - 12);
				if (barTop + 3 <= y + maxHeight) {
					g.fill(valueX, barTop, valueX + barWidth, barTop + 3, 0xFF303040);
					g.fill(valueX, barTop, valueX + barWidth * Math.max(0, Math.min(100, row.percent())) / 100, barTop + 3, barColor(row));
				}
			}
			return;
		}

		int valueWidth = Math.max(1, panelWidth - 12 - (valueX - labelX));
		int barWidth = Math.max(0, Math.min(40, valueWidth - 48));
		int valueTextX = valueX + (row.percent() >= 0 && barWidth > 0 ? barWidth + 4 : 0);
		int valueTextWidth = Math.max(1, panelWidth - 6 - valueTextX);
		for (int line = startLine; line < endLine; line++) {
			if (line < display.labels().size()) {
				String label = display.labels().get(line);
				if (clippedAtEnd && line == endLine - 1 && line >= display.values().size()) {
					label = withEllipsis(font, label, Math.max(1, Math.min(140, (panelWidth - 12) * 42 / 100)));
				}
				g.text(font, label, labelX, y + (line - startLine) * 10, 0xFFAAAAAA);
			}
			if (line < display.values().size()) {
				String value = display.values().get(line);
				if (clippedAtEnd && line == endLine - 1) {
					value = withEllipsis(font, value, valueTextWidth);
				}
				g.text(font, value, valueTextX, y + (line - startLine) * 10, 0xFFFFFFFF);
			}
		}
		if (row.percent() >= 0 && barWidth > 0 && startLine == 0 && maxHeight >= 4) {
			g.fill(valueX, y + 1, valueX + barWidth, y + 4, 0xFF303040);
			g.fill(valueX, y + 1, valueX + barWidth * Math.max(0, Math.min(100, row.percent())) / 100, y + 4, barColor(row));
		}
	}

	private static String displayValue(Row row) {
		if (row.label().equals("Ziel") && row.value().equals("kein aktives Wegziel")) {
			return "Kein aktives Wegziel";
		}
		return row.value();
	}

	private static List<String> wrapText(Font font, String text, int width) {
		if (text.isEmpty()) {
			return List.of("");
		}
		List<String> lines = new ArrayList<>();
		String remaining = text.stripLeading();
		while (!remaining.isEmpty()) {
			String line = font.plainSubstrByWidth(remaining, Math.max(1, width));
			if (line.isEmpty()) {
				line = remaining.substring(0, remaining.offsetByCodePoints(0, 1));
			}
			if (line.length() < remaining.length()) {
				int breakAt = line.lastIndexOf(' ');
				if (breakAt > 0) {
					line = line.substring(0, breakAt);
				}
			}
			lines.add(line);
			remaining = remaining.substring(line.length()).stripLeading();
		}
		return List.copyOf(lines);
	}

	private static String withEllipsis(Font font, String text, int width) {
		String ellipsis = "…";
		if (width <= 0 || font.width(ellipsis) > width) {
			return "";
		}
		return font.plainSubstrByWidth(text, width - font.width(ellipsis)) + ellipsis;
	}

	private static String clipped(Font font, String text, int width) {
		if (width <= 0 || text.isEmpty()) return "";
		if (font.width(text) <= width) return text;
		return withEllipsis(font, text, width);
	}

	private static int barColor(Row row) {
		int percent = Math.max(0, Math.min(100, row.percent()));
		if (row.label().equals("Hunger")) percent = 100 - percent;
		if (percent >= 60) {
			return 0xFF4FB84F;
		} else if (percent >= 30) {
			return 0xFFD8B33A;
		}
		return 0xFFC84A4A;
	}

	private static List<Row> parse(List<String> raw) {
		List<Row> out = new ArrayList<>(raw.size());
		for (String row : raw) {
			int first = row.indexOf('|');
			int last = row.lastIndexOf('|');
			if (first > 0 && last > first) {
				out.add(new Row(row.substring(0, first), row.substring(first + 1, last), parseInt(row.substring(last + 1))));
			}
		}
		return List.copyOf(out);
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
