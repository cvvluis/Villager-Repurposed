package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.HouseTemplate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Die Karte auf der Dorftafel: eine farbige Draufsicht (8x8 Bloecke je Feld) mit Gebaeuden, Feldern, Wasser, Wegen, Wald,
 * wichtigen Orten und den Dorfbewohnern, dazu Balkendiagramme fuer Einwohner, Stimmung, Reichtum und die Vorraete.
 */
public final class VillageMap {
	public static final int CELLS = 21;
	public static final int NEIGHBOR_VILLAGE_MARKER = 16;
	private static final int SIZE = 8;

	private enum Kind { NATURAL, TREE, PATH, WATER, FARM, BUILT }
	private record Geometry(long until, BlockPos center, int gridScale, int overviewScale) { }
	private static final java.util.Map<String, Geometry> GEOMETRY = new java.util.HashMap<>();

	private VillageMap() {
	}

	public static List<Component> render(ServerLevel level, VillageRef ref) {
		VillageData d = ref.data();
		// Viewing the map also records valid, loaded neighboring villages through the
		// same reciprocal-link discovery path used by the relationship panel.
		List<String> neighborRows = Network.neighborRows(level, ref);
		List<Villager> people = Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> true);
		List<Villager> residents = people.stream().filter(v -> !Guests.isGuest(v) && !Expedition.isAway(v)).toList();

		BlockPos bell = ref.bell();
		int half = CELLS / 2;
		Geometry geometry = geometry(level, ref);
		BlockPos mapCenter = geometry.center();
		int overviewSize = geometry.overviewScale();
		int originX = mapCenter.getX() - half * overviewSize - overviewSize / 2;
		int originZ = mapCenter.getZ() - half * overviewSize - overviewSize / 2;

		// Marker und Dorfbewohner je Feld vormerken.
		char[][] mark = new char[CELLS][CELLS];
		ChatFormatting[][] markColor = new ChatFormatting[CELLS][CELLS];
		put(mark, markColor, originX, originZ, overviewSize, bell, '⌂', ChatFormatting.WHITE, true);
		BlockPos chestAt = ref.chestPos(level);
		if (chestAt != null) {
			put(mark, markColor, originX, originZ, overviewSize, chestAt, '▣', ChatFormatting.YELLOW, true);
		}
		d.extra.board.ifPresent(p -> put(mark, markColor, originX, originZ, overviewSize, p, '✉', ChatFormatting.AQUA, true));
		d.extra.school.ifPresent(p -> put(mark, markColor, originX, originZ, overviewSize, p, '✎', ChatFormatting.BLUE, true));
		for (BlockPos p : Lager.centers(d)) {
			put(mark, markColor, originX, originZ, overviewSize, p, '▤', ChatFormatting.GRAY, true);
		}
		d.extra.pen.ifPresent(p -> put(mark, markColor, originX, originZ, overviewSize, p, '❖', ChatFormatting.RED, true));
		d.mine.ifPresent(m -> put(mark, markColor, originX, originZ, overviewSize, m.entrance, '▼', ChatFormatting.DARK_RED, true));
		for (VillageData.Mine m : d.extra.oldMines) {
			put(mark, markColor, originX, originZ, overviewSize, m.entrance, '▽', ChatFormatting.DARK_GRAY, false);
		}
		for (BlockPos projectOrigin : constructionProjectOrigins(d)) {
			put(mark, markColor, originX, originZ, overviewSize, projectOrigin, '▧', ChatFormatting.GOLD, true);
		}
		for (Villager v : residents) {
			put(mark, markColor, originX, originZ, overviewSize, v.blockPosition(), '•', ChatFormatting.GREEN, false);
		}
		for (BlockPos neighbor : mapNeighborBells(level, ref)) {
			int col = Math.floorDiv(neighbor.getX() - originX, overviewSize);
			int row = Math.floorDiv(neighbor.getZ() - originZ, overviewSize);
			if (col < 0 || row < 0 || col >= CELLS || row >= CELLS) continue;
			if (mark[col][row] == 0 || mark[col][row] == '•') {
				put(mark, markColor, originX, originZ, overviewSize, neighbor, '◆', ChatFormatting.AQUA, true);
			}
		}

		List<Component> out = new ArrayList<>();
		out.add(Component.literal("┌" + "─".repeat(CELLS * 2) + "┐").withStyle(ChatFormatting.DARK_GRAY));
		for (int row = 0; row < CELLS; row++) {
			MutableComponent line = Component.literal("│").withStyle(ChatFormatting.DARK_GRAY);
			for (int col = 0; col < CELLS; col++) {
				char ch;
				ChatFormatting color;
				if (mark[col][row] != 0) {
					ch = mark[col][row];
					color = markColor[col][row];
				} else {
					Kind k = classify(level, originX + col * overviewSize, originZ + row * overviewSize, overviewSize);
					ch = switch (k) {
						case BUILT -> '■';
						case FARM -> '▒';
						case WATER -> '≈';
						case PATH -> '░';
						case TREE -> '♣';
						default -> '·';
					};
					color = switch (k) {
						case BUILT -> ChatFormatting.GOLD;
						case FARM -> ChatFormatting.GREEN;
						case WATER -> ChatFormatting.AQUA;
						case PATH -> ChatFormatting.YELLOW;
						case TREE -> ChatFormatting.DARK_GREEN;
						default -> ChatFormatting.DARK_GRAY;
					};
				}
				line.append(Component.literal(ch + " ").withStyle(color));
			}
			line.append(Component.literal("│").withStyle(ChatFormatting.DARK_GRAY));
			out.add(line);
		}
		out.add(Component.literal("└" + "─".repeat(CELLS * 2) + "┘  Norden oben, 1 Feld = " + overviewSize + "×" + overviewSize + " Blöcke").withStyle(ChatFormatting.DARK_GRAY));
		out.add(legend());

		// Balkendiagramme.
		int pop = residents.size();
		int moodSum = 0;
		for (Villager v : residents) {
			VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
			moodSum += Wellbeing.of(level, v, mem).mood();
		}
		int mood = pop == 0 ? 0 : moodSum / pop;
		int score = Wealth.score(level, ref, pop);
		out.add(Component.empty());
		out.add(bar("Einwohner  ", pop / (double) Config.MAX_VILLAGERS, ChatFormatting.GREEN, pop + " von " + Config.MAX_VILLAGERS));
		out.add(bar("Stimmung   ", mood / 100.0, mood >= 60 ? ChatFormatting.GREEN : mood >= 35 ? ChatFormatting.YELLOW : ChatFormatting.RED, mood + " %"));
		out.add(bar("Reichtum   ", Math.min(1.0, score / 350.0), ChatFormatting.GOLD, score + " Punkte (Stufe " + Wealth.tier(score) + ")"));
		List<net.minecraft.world.Container> boxes = Balance.storage(level, ref);
		long[] stock = new long[Balance.Kind.values().length];
		long max = 1;
		for (Balance.Kind k : Balance.Kind.values()) {
			stock[k.ordinal()] = Balance.stock(boxes, k);
			max = Math.max(max, stock[k.ordinal()]);
		}
		String[] labels = {"Essen      ", "Holz       ", "Stein      ", "Metall     "};
		ChatFormatting[] colors = {ChatFormatting.RED, ChatFormatting.GOLD, ChatFormatting.GRAY, ChatFormatting.AQUA};
		for (Balance.Kind k : Balance.Kind.values()) {
			out.add(bar(labels[k.ordinal()], stock[k.ordinal()] / (double) max, colors[k.ordinal()], stock[k.ordinal()] + " Einheiten"));
		}
		out.add(Component.empty());
		out.add(Component.literal("Karawanenorte").withStyle(ChatFormatting.GOLD));
		out.addAll(partnerDisplayLines(Network.of(ref.bellEntity()), level.getGameTime() / 24000L));
		out.add(Component.empty());
		out.add(Component.literal("Nachbardörfer").withStyle(ChatFormatting.AQUA));
		List<String> visibleNeighbors = neighborSummaryRows(neighborRows);
		if (visibleNeighbors.isEmpty()) {
			out.add(Component.literal("Keine echten Nachbardörfer im bekannten Umfeld.").withStyle(ChatFormatting.GRAY));
		} else {
			for (String neighbor : visibleNeighbors) out.add(Component.literal("◆ " + neighbor).withStyle(ChatFormatting.AQUA));
		}
		return out;
	}

	static List<Component> partnerDisplayLines(TradeNetwork network, long day) {
		return Network.partnerRows(network, day).stream()
			.map(row -> (Component) Component.literal("• " + row.replace('|', '·')).withStyle(ChatFormatting.WHITE)).toList();
	}

	/** Readable map rows for persisted real-village links; dimension+bell identity deduplicates rows. */
	static List<String> neighborSummaryRows(List<String> rows) {
		if (rows == null || rows.isEmpty()) return List.of();
		Set<String> seen = new LinkedHashSet<>();
		List<String> summaries = new ArrayList<>();
		for (String row : rows) {
			if (row == null) continue;
			String[] fields = row.split("\\|", -1);
			if (fields.length < 13 || fields[0].isBlank() || fields[11].isBlank() || fields[12].isBlank()) continue;
			if (!seen.add(fields[11] + "|" + fields[12])) continue;
			String availability = fields[6].equals("1") ? "" : " · nicht geladen";
			summaries.add(fields[0] + " · " + fields[1] + " · Beziehung " + fields[4] + availability);
		}
		return List.copyOf(summaries);
	}

	private static Component legend() {
		MutableComponent l = Component.empty();
		String[][] items = {{"⌂", "Glocke", "WHITE"}, {"▣", "Kiste", "YELLOW"}, {"✉", "Tafel", "AQUA"}, {"✎", "Schule", "BLUE"}, {"▤", "Lager", "GRAY"},
			{"❖", "Gehege", "RED"}, {"▼", "Mine", "DARK_RED"}, {"▽", "Alte Mine", "DARK_GRAY"}, {"■", "Bebauung", "GOLD"}, {"▒", "Feld", "GREEN"}, {"≈", "Wasser", "AQUA"},
			{"░", "Weg", "YELLOW"}, {"♣", "Wald", "DARK_GREEN"}, {"•", "Dorfbewohner", "GREEN"}, {"▧", "Baustelle", "GOLD"},
			{"◆", "Nachbardorf", "AQUA"}};
		for (String[] it : items) {
			l.append(Component.literal(it[0] + " " + it[1] + "  ").withStyle(ChatFormatting.valueOf(it[2])));
		}
		return l;
	}

	private static Component bar(String label, double fraction, ChatFormatting color, String text) {
		int width = 20;
		int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * width);
		return Component.literal(label).withStyle(ChatFormatting.GRAY)
			.append(Component.literal("█".repeat(filled)).withStyle(color))
			.append(Component.literal("░".repeat(width - filled)).withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal(" " + text).withStyle(ChatFormatting.WHITE));
	}

	private static void put(char[][] mark, ChatFormatting[][] color, int originX, int originZ, int cellSize, BlockPos p, char ch, ChatFormatting c, boolean override) {
		int col = Math.floorDiv(p.getX() - originX, cellSize);
		int row = Math.floorDiv(p.getZ() - originZ, cellSize);
		if (col < 0 || row < 0 || col >= CELLS || row >= CELLS) {
			return;
		}
		if (mark[col][row] == 0 || override) {
			mark[col][row] = ch;
			color[col][row] = c;
		}
	}

	/** Haeufigste/wichtigste Nutzung eines Feldes (vier Stichproben). */
	private static Kind classify(ServerLevel level, int x0, int z0, int size) {
		Set<Kind> seen = new HashSet<>();
		for (int dx = 1; dx < size; dx += Math.max(1, size / 4)) {
			for (int dz = 1; dz < size; dz += Math.max(1, size / 4)) {
				int x = x0 + dx;
				int z = z0 + dz;
				if (!level.hasChunkAt(new BlockPos(x, 64, z))) {
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				BlockPos pos = new BlockPos(x, y, z);
				BlockState s = level.getBlockState(pos);
				if (!s.getFluidState().isEmpty()) {
					seen.add(Kind.WATER);
				} else if (s.is(Blocks.FARMLAND) || s.is(BlockTags.CROPS)) {
					seen.add(Kind.FARM);
				} else if (s.is(Blocks.DIRT_PATH)) {
					seen.add(Kind.PATH);
				} else if (s.is(BlockTags.LOGS)) {
					seen.add(Kind.TREE);
				} else if (HouseTemplate.built(level, pos)) {
					seen.add(Kind.BUILT);
				}
			}
		}
		for (Kind k : new Kind[] {Kind.BUILT, Kind.FARM, Kind.WATER, Kind.PATH, Kind.TREE}) {
			if (seen.contains(k)) {
				return k;
			}
		}
		return Kind.NATURAL;
	}

	/**
	 * Karte fuer die Dorftafel als zwei Gitter (Zeile fuer Zeile): [0] Gelaende (0 Land, 1 Wald, 2 Weg, 3 Wasser, 4 Feld, 5 Bebauung),
	 * [1] Markierung (0 keine, 1 Glocke, 2 Kiste, 3 Tafel, 4 Schule, 5 Lager, 6 Gehege, 7 Mine, 8 alte Mine, 9 Dorfbewohner, 10 Kornspeicher, 11 Werkzeugkammer, 12 Kueche, 13 Baustelle, 16 Nachbardorf).
	 */
	/** Weltkoordinaten (x, z) der linken oberen Ecke des Tafelrasters. */
	public static int[] gridOrigin(ServerLevel level, VillageRef ref) {
		Geometry geometry = geometry(level, ref);
		BlockPos center = geometry.center();
		int scale = geometry.gridScale();
		return new int[] {center.getX() - GRID * scale / 2, center.getZ() - GRID * scale / 2};
	}

	/** Fixed pixel-grid dimensions; each cell's world coverage adapts to the full village footprint. */
	public static final int GRID = 48;
	public static int gridCellSize(ServerLevel level, VillageRef ref) {
		return geometry(level, ref).gridScale();
	}
	private static Geometry geometry(ServerLevel level, VillageRef ref) {
		String key = level.dimension().identifier() + "/" + ref.bell().asLong();
		long now = level.getGameTime();
		Geometry cached = GEOMETRY.get(key);
		if (cached != null && now < cached.until()) return cached;
		var bounds = Development.bounds(level, ref);
		int span = Math.max(bounds.maxX() - bounds.minX() + 1, bounds.maxZ() - bounds.minZ() + 1);
		BlockPos center = new BlockPos(bounds.minX() + (bounds.maxX() - bounds.minX()) / 2, ref.bell().getY(), bounds.minZ() + (bounds.maxZ() - bounds.minZ()) / 2);
		Geometry updated = new Geometry(now + 200, center, Math.max(4, (int) Math.ceil(span * 1.10 / GRID)), Math.max(SIZE, (int) Math.ceil(span * 1.25 / CELLS)));
		GEOMETRY.put(key, updated);
		return updated;
	}

	/** Gelaendekarte je Dorf, nur alle 10 Sekunden neu berechnet (die Tafel fragt alle 2 Sekunden); Markierungen sind immer frisch. */
	private record TerrainCache(long until, int originX, int originZ, int scale, byte[] cells) { }
	private static final java.util.Map<String, TerrainCache> TERRAIN = new java.util.HashMap<>();

	/** Gelaendecodes: 0 Wiese, 1 Wald, 2 Weg/Strasse, 3 Wasser, 4 Feld, 5 Gebaeude, 6 Sand, 7 Stein, 8 Schnee, 9 Tonland, 10 Platz, 11 unbekannt. Bits 4-6: Hoehenschattierung (0..6, 3 = eben). */
	public static byte[][] grid(ServerLevel level, VillageRef ref) {
		VillageData d = ref.data();
		BlockPos bell = ref.bell();
		Geometry geometry = geometry(level, ref);
		BlockPos mapCenter = geometry.center();
		int GSIZE = geometry.gridScale();
		int originX = mapCenter.getX() - GRID * GSIZE / 2;
		int originZ = mapCenter.getZ() - GRID * GSIZE / 2;
		long now = level.getGameTime();
		String terrainKey = level.dimension().identifier() + "/" + bell.asLong();
		TerrainCache cache = TERRAIN.get(terrainKey);
		byte[] cells;
		if (cache != null && now < cache.until() && cache.originX() == originX && cache.originZ() == originZ && cache.scale() == GSIZE) {
			cells = cache.cells();
		} else {
			cells = new byte[GRID * GRID];
			int[] heights = new int[GRID * GRID];
			for (int row = 0; row < GRID; row++) {
				for (int col = 0; col < GRID; col++) {
					int x0 = originX + col * GSIZE, z0 = originZ + row * GSIZE;
					int cx = x0 + GSIZE / 2, cz = z0 + GSIZE / 2;
					if (!level.hasChunkAt(new BlockPos(cx, 64, cz))) {
						cells[row * GRID + col] = 11;
						heights[row * GRID + col] = -1;
						continue;
					}
					heights[row * GRID + col] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
					int kind = classifyFine(level, x0, z0, GSIZE);
					if (kind <= 1 && Plaza.inside(level, ref, new BlockPos(cx, bell.getY(), cz), 0)) {
						kind = 10;
					}
					cells[row * GRID + col] = (byte) kind;
				}
			}
			for (int row = 1; row < GRID; row++) {
				for (int col = 1; col < GRID; col++) {
					int h = heights[row * GRID + col], hn = heights[(row - 1) * GRID + col - 1];
					if (h < 0 || hn < 0 || cells[row * GRID + col] == 11) {
						continue;
					}
					int shade = Math.max(0, Math.min(6, 3 + Math.max(-3, Math.min(3, h - hn))));
					cells[row * GRID + col] |= (byte) (shade << 4);
				}
			}
			for (int i = 0; i < cells.length; i++) {
				if ((cells[i] & 0x70) == 0 && (cells[i] & 15) != 11) {
					cells[i] |= (byte) (3 << 4);
				}
			}
			TERRAIN.put(terrainKey, new TerrainCache(now + 200, originX, originZ, GSIZE, cells));
		}
		byte[] marks = new byte[GRID * GRID];
		for (Villager v : Villages.residents(level, ref, Config.VILLAGE_RADIUS, x -> !Guests.isGuest(x) && !Expedition.isAway(x))) {
			mark(marks, originX, originZ, GSIZE, v.blockPosition(), 9, false);
		}
		for (int index : nearbyVillageMarkerIndices(GRID, originX, originZ, GSIZE, bell, mapNeighborBells(level, ref))) {
			if (marks[index] == 0 || marks[index] == 9) marks[index] = (byte)NEIGHBOR_VILLAGE_MARKER;
		}
		for (VillageData.Mine m : d.extra.oldMines) {
			mark(marks, originX, originZ, GSIZE, m.entrance, 8, true);
		}
		d.mine.ifPresent(m -> mark(marks, originX, originZ, GSIZE, m.entrance, 7, true));
		d.extra.pen.ifPresent(p -> mark(marks, originX, originZ, GSIZE, p, 6, true));
		for (BlockPos p : Lager.centers(d)) {
			mark(marks, originX, originZ, GSIZE, p, 5, true);
		}
		d.extra.school.ifPresent(p -> mark(marks, originX, originZ, GSIZE, p, 4, true));
		Specials.all(ref).forEach((type, p) -> mark(marks, originX, originZ, GSIZE, p, type.equals("granary") ? 10 : type.equals("toolshed") ? 11 : type.equals("station") ? 15 : 12, true));
		for (MarketData.Shop shop : Market.of(ref).shops()) {
			if (shop.opened() > 0 && !shop.deceased()) {
				mark(marks, originX, originZ, GSIZE, shop.origin(), 14, true);
			}
		}
		for (BlockPos projectOrigin : constructionProjectOrigins(d)) {
			mark(marks, originX, originZ, GSIZE, projectOrigin, 13, true);
		}
		d.extra.board.ifPresent(p -> mark(marks, originX, originZ, GSIZE, p, 3, true));
		BlockPos chestAt = ref.chestPos(level);
		if (chestAt != null) {
			mark(marks, originX, originZ, GSIZE, chestAt, 2, true);
		}
		mark(marks, originX, originZ, GSIZE, bell, 1, true);
		return new byte[][] {cells, marks};
	}

	/** Loaded, previously recognized neighboring bells within the current map bounds. */
	static List<BlockPos> nearbyVillageBells(ServerLevel level, VillageRef ref) {
		if (level == null || ref == null) return List.of();
		Geometry map = geometry(level, ref);
		long halfExtent = (long)GRID * map.gridScale() / 2L;
		int searchRange = (int)Math.min(Integer.MAX_VALUE, Math.ceil(Math.sqrt(2.0) * halfExtent));
		return Villages.knownBellsNear(level, map.center(), searchRange);
	}

	/**
	 * Neighbor markers combine currently recognized loaded villages with persisted real links.
	 * This keeps discovered villages on the board after their chunks unload without mixing dimensions.
	 */
	static List<BlockPos> mapNeighborBells(ServerLevel level, VillageRef ref) {
		if (level == null || ref == null) return List.of();
		String dimension = level.dimension().identifier().toString();
		LinkedHashSet<BlockPos> bells = new LinkedHashSet<>(nearbyVillageBells(level, ref));
		bells.addAll(linkedNeighborBells(dimension, Network.of(ref.bellEntity()).links()));
		bells.removeIf(pos -> sameMapColumn(pos, ref.bell()));
		return List.copyOf(bells);
	}

	private static boolean sameMapColumn(BlockPos left, BlockPos right) {
		return left != null && right != null && left.getX() == right.getX() && left.getZ() == right.getZ();
	}

	/** Stable positions from saved, real-village links in the displayed dimension only. */
	static List<BlockPos> linkedNeighborBells(String dimension, List<TradeNetwork.Link> links) {
		if (dimension == null || dimension.isBlank() || links == null || links.isEmpty()) return List.of();
		LinkedHashSet<BlockPos> bells = new LinkedHashSet<>();
		for (TradeNetwork.Link link : links) {
			if (link != null && dimension.equals(link.dimension()) && link.bell() != null) bells.add(link.bell());
		}
		return List.copyOf(bells);
	}

	/** Raster cells for loaded, previously recognized neighboring bells; duplicate cells remain one stable marker. */
	public static List<Integer> nearbyVillageMarkerIndices(int gridSize, int originX, int originZ, int cellSize,
		BlockPos home, List<BlockPos> nearby) {
		if (gridSize <= 0 || cellSize <= 0 || home == null || nearby == null || nearby.isEmpty()) return List.of();
		Set<Integer> indices = new LinkedHashSet<>();
		for (BlockPos village : nearby) {
			if (village == null || sameMapColumn(village, home)) continue;
			int col = Math.floorDiv(village.getX() - originX, cellSize);
			int row = Math.floorDiv(village.getZ() - originZ, cellSize);
			if (col >= 0 && row >= 0 && col < gridSize && row < gridSize) indices.add(row * gridSize + col);
		}
		return List.copyOf(indices);
	}

	/** Active persisted project origins; pre-project saves retain their legacy singleton marker. */
	public static List<BlockPos> constructionProjectOrigins(VillageData data) {
		List<BlockPos> active = data.buildProjects.stream().filter(VillageData.BuildProject::active)
			.map(VillageData.BuildProject::origin).distinct().toList();
		if (!active.isEmpty()) return active;
		return data.hasPlan() ? data.planOrigin.stream().toList() : List.of();
	}

	/** Gelaendeart eines 4x4-Feldes aus vier Stichproben (Bebauung > Feld > Wasser > Weg > Wald > Oberflaeche). */
	private static int classifyFine(ServerLevel level, int x0, int z0, int GSIZE) {
		boolean built = false, farm = false, water = false, path = false, tree = false;
		int sand = 0, stone = 0, snow = 0, clay = 0;
		for (int dx = 1; dx < GSIZE; dx += 2) {
			for (int dz = 1; dz < GSIZE; dz += 2) {
				int x = x0 + dx, z = z0 + dz;
				BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
				BlockState s = level.getBlockState(pos);
				if (!s.getFluidState().isEmpty()) {
					water = true;
				} else if (s.is(Blocks.FARMLAND) || s.is(BlockTags.CROPS)) {
					farm = true;
				} else if (s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.SMOOTH_SANDSTONE) || s.is(Blocks.SPRUCE_PLANKS)) {
					path = true;
				} else if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) {
					tree = true;
				} else if (s.is(BlockTags.SAND) || s.is(Blocks.SANDSTONE) || s.is(Blocks.CUT_SANDSTONE) || s.is(Blocks.RED_SANDSTONE)) {
					sand++;
				} else if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE)) {
					snow++;
				} else if (s.is(BlockTags.TERRACOTTA)) {
					clay++;
				} else if (s.is(Blocks.STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.DIORITE) || s.is(Blocks.GRANITE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.TUFF)) {
					stone++;
				} else if (HouseTemplate.built(level, pos)) {
					built = true;
				}
			}
		}
		if (built) return 5;
		if (farm) return 4;
		if (water) return 3;
		if (path) return 2;
		if (tree) return 1;
		if (snow >= 2) return 8;
		if (clay >= 2) return 9;
		if (sand >= 2) return 6;
		if (stone >= 2) return 7;
		return 0;
	}

	private static void mark(byte[] marks, int originX, int originZ, int cellSize, BlockPos p, int code, boolean override) {
		int col = Math.floorDiv(p.getX() - originX, cellSize);
		int row = Math.floorDiv(p.getZ() - originZ, cellSize);
		if (col < 0 || row < 0 || col >= GRID || row >= GRID) {
			return;
		}
		int i = row * GRID + col;
		if (marks[i] == 0 || override) {
			marks[i] = (byte) code;
		}
	}

	/** Balken fuer die Dorftafel im Format {@code Label|Text|Prozent|RRGGBB}. */
	public static List<String> bars(ServerLevel level, VillageRef ref) {
		List<Villager> residents = Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v) && !Expedition.isAway(v));
		int pop = residents.size();
		int moodSum = 0;
		for (Villager v : residents) {
			moodSum += Wellbeing.of(level, v, v.getAttachedOrCreate(VillagerPlus.MEMORY)).mood();
		}
		int mood = pop == 0 ? 0 : moodSum / pop;
		int score = Wealth.score(level, ref, pop);
		List<String> out = new ArrayList<>();
		out.add("Einwohner|" + pop + "|" + Math.min(100, pop * 100 / Math.max(1, Math.max(pop, Config.MAX_VILLAGERS))) + "|3C9A3C");
		out.add("Stimmung|" + mood + " %|" + mood + "|" + (mood >= 60 ? "3C9A3C" : mood >= 35 ? "C9A52A" : "B83C3C"));
		out.add("Reichtum|" + score + " (Stufe " + Wealth.tier(score) + ")|" + Math.min(100, score * 100 / 350) + "|D08A1C");
		List<net.minecraft.world.Container> boxes = Balance.storage(level, ref);
		long[] stock = new long[Balance.Kind.values().length];
		long max = 1;
		for (Balance.Kind k : Balance.Kind.values()) {
			stock[k.ordinal()] = Balance.stock(boxes, k);
			max = Math.max(max, stock[k.ordinal()]);
		}
		String[] labels = {"Essen", "Holz", "Stein", "Metall"};
		String[] colors = {"C04A4A", "A86A2A", "7A7A7A", "3A9AB0"};
		for (Balance.Kind k : Balance.Kind.values()) {
			out.add(labels[k.ordinal()] + "|" + stock[k.ordinal()] + "|" + (int) (stock[k.ordinal()] * 100 / max) + "|" + colors[k.ordinal()]);
		}
		return out;
	}
}
