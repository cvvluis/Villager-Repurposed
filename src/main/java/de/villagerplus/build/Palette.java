package de.villagerplus.build;

import de.villagerplus.village.VillageStyle;

import java.util.HashMap;

import java.util.List;

import java.util.Map;

import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;

import net.minecraft.server.level.ServerLevel;

import net.minecraft.tags.BlockTags;

import net.minecraft.world.entity.ai.village.poi.PoiManager;

import net.minecraft.world.entity.ai.village.poi.PoiTypes;

import net.minecraft.world.entity.npc.villager.Villager;

import net.minecraft.world.item.Items;

import net.minecraft.world.level.block.BedBlock;

import net.minecraft.world.level.block.Block;

import net.minecraft.world.level.block.Blocks;

import net.minecraft.world.level.block.DoorBlock;

import net.minecraft.world.level.block.FenceBlock;

import net.minecraft.world.level.block.IronBarsBlock;

import net.minecraft.world.level.block.SlabBlock;

import net.minecraft.world.level.block.StairBlock;

import net.minecraft.world.level.block.state.BlockState;

/**

 * Materialpalette eines Dorfes: Aus den vorhandenen Haeusern (um die Betten herum) werden die tatsaechlich benutzten Bloecke je

 * Rolle gelesen - Wand, zweite Wand, Fachwerk-Balken, Sockel, Dach (Vollblock, Treppe, Halbstufe), Fenster, Tuer, Zaun.

 * Daraus baut der {@code Architect} neue Gebaeude im gleichen Stil, aber nicht als Kopie. Das funktioniert auch bei Dorfern aus

 * Weltgenerator-Mods; fehlt etwas, greift der Standardstil des Dorftyps.

 */

public record Palette(Block wall, Block wall2, Block frame, Block base, Block roofFull, Block roofStair, Block roofSlab, Block window,

	Block floor, Block door, Block fence) {

	/**

	 * Wie {@link #detect(ServerLevel, BlockPos, Villager)}, aber Vanilla-Doerfer (Wand aus Bruchstein, Brettern oder Sandstein ...)

	 * bekommen je nach Biom eine stimmige, abwechslungsreiche Holz-/Steinmischung (Birke, Fichte, Akazie, Schwarzeiche ...), die

	 * vom Seed abhaengt. Doerfer von Weltgenerator-Mods (andere Waende, z. B. Terrakotta) behalten ihren erkannten Stil.

	 */

	public static Palette detect(ServerLevel level, BlockPos bell, Villager hint, long seed) {

		return detect(level, bell, hint, seed, null);

	}

	/** Mit {@code site}: Die Baustoffe werden aus den Haeusern in der Naehe des Bauplatzes gelesen (passt zur Nachbarschaft). */

	public static Palette detect(ServerLevel level, BlockPos bell, Villager hint, long seed, BlockPos site) {

		Palette found = layered(level, bell, detectAt(level, bell, hint, site), site);

		if (houses(level, site != null ? site : bell, 8).size() >= 3 || !VANILLA_WALLS.contains(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(found.wall).toString())) {

			return found;

		}

		return curate(found, biomeKind(level, bell), seed);

	}

	/** Kuratierte Mischung fuer die Stilart (desert, savanna, snow, taiga, plains) nach Seed; unbekannte Art = unveraendert. */

	public static Palette curate(Palette found, String biome, long seed) {

		String[][] options = CURATED.get(biome);

		if (options == null) {

			return found;

		}

		String wallId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(found.wall).getPath();

		// Passt eine Mischung zu den tatsaechlich im Dorf benutzten Waenden, darf sie (je nach Seed) die Holzart variieren.

		java.util.List<String[]> fits = new java.util.ArrayList<>();

		for (String[] o : options) {

			if (o[0].equals(wallId) || o[1].equals(wallId)) {

				fits.add(o);

			}

		}

		String[] o = fits.isEmpty() ? options[0] : fits.get((int) Math.floorMod(seed, (long) fits.size()));

		boolean vary = !fits.isEmpty() && Math.floorMod(seed, 3L) != 0;

		Block wall = found.wall;

		Block wall2 = vary ? named(o[1], found.wall2) : found.wall2;

		Block frame = found.frame != null ? found.frame : named(o[2], null);

		Block baseB = found.base;

		Block roofFull = found.roofFull;

		Block stair = found.roofStair;

		Block slab = found.roofSlab;

		if (vary) {

			// Dach in einer zur Wand passenden Holzart des Dorfes tauschen (nur wenn das Dorf diese Treppe sonst auch haette).

			stair = named(o[5], found.roofStair);

			roofFull = named(o[4], found.roofFull);

			slab = named(o[6], found.roofSlab);

		}

		if (slab == null && stair instanceof net.minecraft.world.level.block.StairBlock) {

			String sp = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(stair).getPath();

			slab = named(sp.substring(0, sp.length() - "_stairs".length()) + "_slab", null);

		}

		return new Palette(wall, wall2, frame, baseB, roofFull, stair, slab, found.window, found.floor, found.door, found.fence);

	}

	/**

	 * Stilprofil der Haeuser rund um die Glocke als 6-Bit-Code (fuer den Entwurfs-Seed): Bit 5 = Profil vorhanden, Bits 0-1 =

	 * Wandhoehe-3 (wo die Dachtreppen beginnen), Bit 2 = Fachwerk (viele Staemme in den Waenden), Bit 3 = zweifarbig (oberes Geschoss

	 * aus anderem Material als das untere), Bit 4 = Sockel (unterste Reihe anders als die Wand). So werden neue Haeuser im Bau wie die

	 * Haeuser des Dorfes (z. B. Overhauled Villages): gleiche Geschosshoehe, gleiches Fachwerk, gleiche Materialfolge.

	 */

	/** Letztes ermitteltes Stilprofil als Text (Diagnose). */

	public static String lastProfile = "";

	public static int profileCode(ServerLevel level, BlockPos bell) {

		List<BlockPos> beds = houses(level, bell, 12);

		if (beds.size() < 2) {

			return 0;

		}

		int logs = 0;

		int walls = 0;

		int roofStartSum = 0;

		int roofStartN = 0;

		Map<Block, Integer> row0 = new HashMap<>();

		Map<Block, Integer> low = new HashMap<>();

		Map<Block, Integer> high = new HashMap<>();

		for (BlockPos bed : beds) {

			int roofStart = -1;

			for (int rel = -1; rel <= 7; rel++) {

				for (int dx = -6; dx <= 6; dx++) {

					for (int dz = -6; dz <= 6; dz++) {

						BlockPos p = bed.offset(dx, rel, dz);

						BlockState s = level.getBlockState(p);

						if (s.isAir() || furnishingOnly(s.getBlock()) || VillageStyle.natural(s) || s.getBlock() instanceof BedBlock) {

							continue;

						}

						if (s.getBlock() instanceof StairBlock && rel >= 2 && roofStart < 0) {

							roofStart = rel;

						}

						if (!s.isCollisionShapeFullBlock(level, p) || s.getBlock() instanceof StairBlock) {

							continue;

						}

						boolean isLog = s.is(BlockTags.LOGS);

						if (rel >= 0 && rel <= 4) {

							if (isLog) {

								logs++;

							} else {

								walls++;

							}

						}

						if (!isLog) {

							if (rel <= 0) {

								if (outerRing(level, p)) {

									row0.merge(s.getBlock(), 1, Integer::sum);

								}

							} else if (rel <= 2) {

								low.merge(s.getBlock(), 1, Integer::sum);

							} else if (rel <= 5) {

								high.merge(s.getBlock(), 1, Integer::sum);

							}

						}

					}

				}

			}

			if (roofStart > 0) {

				roofStartSum += roofStart;

				roofStartN++;

			}

		}

		int wallH = roofStartN == 0 ? 3 : Math.max(3, Math.min(6, Math.round(roofStartSum / (float) roofStartN)));

		boolean timber = logs * 100 >= (logs + walls) * 12;

		Block l = top(low, null, null);

		Block h = top(high, null, null);

		Block r0 = top(row0, null, null);

		boolean twoTone = l != null && h != null && l != h;

		boolean base = r0 != null && l != null && r0 != l;

		lastProfile = "Wandhoehe " + wallH + ", Staemme " + logs + "/" + (logs + walls) + ", Sockel " + r0 + ", unten " + l + ", oben " + h;

		return 32 | Math.min(3, wallH - 3) | (timber ? 4 : 0) | (twoTone ? 8 : 0) | (base ? 16 : 0);

	}

	/**

	 * Materialfolge wie bei den Nachbarhaeusern: unteres Geschoss (Wand), oberes Geschoss (zweite Wand), unterste Reihe (Sockel).

	 * Ersetzt die pauschale Terrakotta-Daempfung: Terrakotta erscheint genau dort, wo das Dorf sie selbst benutzt.

	 */

	private static Palette layered(ServerLevel level, BlockPos bell, Palette p, BlockPos site) {

		BlockPos at = site != null ? site : bell;

		List<BlockPos> beds = houses(level, at, 8);

		Map<Block, Integer> row0 = new HashMap<>();

		Map<Block, Integer> low = new HashMap<>();

		Map<Block, Integer> high = new HashMap<>();

		for (BlockPos bed : beds) {

			for (BlockPos q : BlockPos.betweenClosed(bed.offset(-6, -1, -6), bed.offset(6, 5, 6))) {

				BlockState s = level.getBlockState(q);

				int rel = q.getY() - bed.getY();

				if (s.isAir() || furnishingOnly(s.getBlock()) || VillageStyle.natural(s) || s.is(BlockTags.LOGS) || s.getBlock() instanceof StairBlock

					|| s.getBlock() instanceof BedBlock || !s.isCollisionShapeFullBlock(level, q)) {

					continue;

				}

				if (rel <= 0) {

					if (outerRing(level, q)) {

						row0.merge(s.getBlock(), 1, Integer::sum);

					}

				} else {

					(rel <= 2 ? low : high).merge(s.getBlock(), 1, Integer::sum);

				}

			}

		}

		Block lower = top(low, null, p.wall);

		Block upper = top(high, lower, lower);

		Block sock = top(row0, null, p.base);

		if (high.getOrDefault(upper, 0) * 3 < high.getOrDefault(lower, 0)) {

			upper = lower;

		}

		return new Palette(lower, upper, p.frame, sock, p.roofFull, p.roofStair, p.roofSlab, p.window, p.floor, p.door, p.fence);

	}

	/** Interior shelves must never become the wall, foundation, or roof material for new buildings. */
	private static boolean furnishingOnly(Block block) {
		return block == Blocks.BOOKSHELF || block == Blocks.CHISELED_BOOKSHELF;
	}

	/** Aussenkante (Sockel): neben dem Block liegt gewachsener Boden oder Luft ueber Boden - kein Innenboden. */

	private static boolean outerRing(ServerLevel level, BlockPos p) {

		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {

			BlockState n = level.getBlockState(p.relative(d));

			if (VillageStyle.natural(n) || (n.isAir() && VillageStyle.natural(level.getBlockState(p.relative(d).below())))) {

				return true;

			}

		}

		return false;

	}

	/** Connected groups of beds within six blocks count as a single building. */

	public static List<BlockPos> houses(ServerLevel level, BlockPos center, int limit) {

		java.util.List<BlockPos> pending = level.getPoiManager().getInRange(h -> h.is(PoiTypes.HOME), center, 64, PoiManager.Occupancy.ANY)

			.map(p -> p.getPos().immutable()).sorted(java.util.Comparator.comparingDouble(p -> p.distSqr(center))).collect(Collectors.toCollection(java.util.ArrayList::new));

		java.util.List<BlockPos> result = new java.util.ArrayList<>();

		while (!pending.isEmpty() && result.size() < limit) {

			BlockPos first = pending.remove(0);

			result.add(first);

			java.util.List<BlockPos> group = new java.util.ArrayList<>(); group.add(first);

			for (int k = 0; k < group.size(); k++) {

				BlockPos current = group.get(k);

				for (var it = pending.iterator(); it.hasNext();) {

					BlockPos b = it.next();

					if (current.distSqr(b) <= 36) { group.add(b); it.remove(); }

				}

			}

		}

		return result;

	}

	private static boolean terracotta(Block b) {

		return b != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath().contains("terracotta");

	}

	/**

	 * Doerfer mit Terrakotta-Waenden (z. B. Overhauled Villages): Neue Haeuser bekommen Waende aus dem Holz des Fachwerks und

	 * einen Steinsockel; die Terrakotta bleibt nur als Akzent (oberes Geschoss, Giebel) - sonst wirkt alles einfarbig.

	 */

	private static Palette lessTerracotta(Palette p) {

		if (!terracotta(p.wall)) {

			return p;

		}

		Block planks = Blocks.SPRUCE_PLANKS;

		if (p.frame != null) {

			String fp = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.frame).getPath().replace("stripped_", "");

			for (String suffix : new String[] {"_log", "_wood", "_stem", "_hyphae"}) {

				if (fp.endsWith(suffix)) {

					planks = named(fp.substring(0, fp.length() - suffix.length()) + "_planks", planks);

				}

			}

		}

		Block stone = Supply.obtainable(Blocks.STONE_BRICKS) ? Blocks.STONE_BRICKS : Blocks.COBBLESTONE;

		Block base = terracotta(p.base) ? stone : p.base;

		Block roofFull = terracotta(p.roofFull) ? fullOf(p.roofStair, planks) : p.roofFull;

		Block floor = terracotta(p.floor) ? planks : p.floor;

		return new Palette(planks, p.wall, p.frame, base, roofFull, p.roofStair, p.roofSlab, p.window, floor, p.door, p.fence);

	}

	private static Block named(String id, Block fallback) {

		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse("minecraft:" + id)).orElse(fallback);

	}

	private static final java.util.Set<String> VANILLA_WALLS = java.util.Set.of("minecraft:cobblestone", "minecraft:mossy_cobblestone",

		"minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks",

		"minecraft:jungle_planks", "minecraft:sandstone", "minecraft:smooth_sandstone", "minecraft:cut_sandstone", "minecraft:stone_bricks",

		"minecraft:stone", "minecraft:snow_block");

	/** Stilart des Dorfes nach dem Biom an der Glocke: desert, savanna, snow, taiga oder plains. */

	static String biomeKind(ServerLevel level, BlockPos bell) {

		String path = level.getBiome(bell).unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");

		if (path.contains("desert") || path.contains("badlands") || path.contains("beach")) {

			return path.contains("beach") ? "plains" : "desert";

		}

		if (path.contains("savanna")) {

			return "savanna";

		}

		if (path.contains("snow") || path.contains("frozen") || path.contains("ice") || path.contains("grove")) {

			return "snow";

		}

		if (path.contains("taiga") || path.contains("pine") || path.contains("spruce")) {

			return "taiga";

		}

		return "plains";

	}

	/**

	 * Je Biom mehrere Mischungen: {wall, wall2, frame, base, roofFull, roofStairs, roofSlab, door, fence, floor} (ohne "minecraft:").

	 */

	private static final Map<String, String[][]> CURATED = Map.of(

		"plains", new String[][] {

			{"oak_planks", "birch_planks", "oak_log", "cobblestone", "spruce_planks", "spruce_stairs", "spruce_slab", "oak_door", "oak_fence", "oak_planks"},

			{"birch_planks", "birch_planks", "birch_log", "stone_bricks", "dark_oak_planks", "dark_oak_stairs", "dark_oak_slab", "birch_door", "birch_fence", "birch_planks"},

			{"cobblestone", "oak_planks", "dark_oak_log", "mossy_cobblestone", "spruce_planks", "spruce_stairs", "spruce_slab", "oak_door", "oak_fence", "oak_planks"},

			{"oak_planks", "oak_planks", "birch_log", "stone_bricks", "dark_oak_planks", "dark_oak_stairs", "dark_oak_slab", "birch_door", "birch_fence", "oak_planks"}},

		"desert", new String[][] {

			{"smooth_sandstone", "cut_sandstone", "acacia_log", "sandstone", "red_sandstone", "red_sandstone_stairs", "red_sandstone_slab", "jungle_door", "jungle_fence", "cut_sandstone"},

			{"sandstone", "smooth_sandstone", "jungle_log", "cut_sandstone", "smooth_sandstone", "smooth_sandstone_stairs", "smooth_sandstone_slab", "acacia_door", "acacia_fence", "smooth_sandstone"},

			{"cut_sandstone", "smooth_sandstone", "dark_oak_log", "sandstone", "acacia_planks", "acacia_stairs", "acacia_slab", "acacia_door", "acacia_fence", "cut_sandstone"}},

		"savanna", new String[][] {

			{"acacia_planks", "birch_planks", "dark_oak_log", "cobblestone", "dark_oak_planks", "dark_oak_stairs", "dark_oak_slab", "acacia_door", "acacia_fence", "acacia_planks"},

			{"birch_planks", "acacia_planks", "acacia_log", "stone_bricks", "acacia_planks", "acacia_stairs", "acacia_slab", "acacia_door", "acacia_fence", "birch_planks"},

			{"acacia_planks", "acacia_planks", "dark_oak_log", "cobblestone", "spruce_planks", "spruce_stairs", "spruce_slab", "dark_oak_door", "dark_oak_fence", "acacia_planks"}},

		"snow", new String[][] {

			{"spruce_planks", "birch_planks", "spruce_log", "cobblestone", "dark_oak_planks", "dark_oak_stairs", "dark_oak_slab", "spruce_door", "spruce_fence", "spruce_planks"},

			{"stone_bricks", "spruce_planks", "spruce_log", "cobblestone", "spruce_planks", "spruce_stairs", "spruce_slab", "spruce_door", "spruce_fence", "spruce_planks"},

			{"birch_planks", "spruce_planks", "birch_log", "stone_bricks", "spruce_planks", "spruce_stairs", "spruce_slab", "birch_door", "birch_fence", "birch_planks"}},

		"taiga", new String[][] {

			{"cobblestone", "spruce_planks", "spruce_log", "mossy_cobblestone", "spruce_planks", "spruce_stairs", "spruce_slab", "spruce_door", "spruce_fence", "spruce_planks"},

			{"spruce_planks", "cobblestone", "dark_oak_log", "cobblestone", "dark_oak_planks", "dark_oak_stairs", "dark_oak_slab", "spruce_door", "spruce_fence", "spruce_planks"},

			{"birch_planks", "spruce_planks", "spruce_log", "cobblestone", "spruce_planks", "spruce_stairs", "spruce_slab", "birch_door", "birch_fence", "birch_planks"}});

	public static Palette detect(ServerLevel level, BlockPos bell, Villager hint) {

		return detectAt(level, bell, hint, null);

	}

	private static Palette detectAt(ServerLevel level, BlockPos bell, Villager hint, BlockPos site) {

		VillageStyle def = VillageStyle.detect(level, bell, hint);
		// VillageStyle supplies fallbacks as well as detected values. A bookshelf can still be returned by that
		// fallback path when the sampled home contains shelves but no other valid wall, so sanitize it here too.
		Block fallbackWall = furnishingOnly(def.wall()) ? Blocks.COBBLESTONE : def.wall();
		Block fallbackFloor = furnishingOnly(def.floor()) ? Blocks.OAK_PLANKS : def.floor();

		Map<Block, Integer> walls = new HashMap<>();

		Map<Block, Integer> frames = new HashMap<>();

		Map<Block, Integer> bases = new HashMap<>();

		Map<Block, Integer> roofs = new HashMap<>();

		Map<Block, Integer> stairs = new HashMap<>();

		Map<Block, Integer> slabs = new HashMap<>();

		Map<Block, Integer> windows = new HashMap<>();

		Map<Block, Integer> fences = new HashMap<>();

		List<BlockPos> beds = houses(level, site != null ? site : bell, 8);

		for (BlockPos bed : beds) {

			for (BlockPos p : BlockPos.betweenClosed(bed.offset(-8, -1, -8), bed.offset(8, 8, 8))) {

				BlockState s = level.getBlockState(p);

				if (s.isAir()) {

					continue;

				}

				Block b = s.getBlock();

				int rel = p.getY() - bed.getY();

				if (b instanceof BedBlock || furnishingOnly(b) || VillageStyle.natural(s) || b == Blocks.RED_MUSHROOM_BLOCK || b == Blocks.BROWN_MUSHROOM_BLOCK || b == Blocks.MUSHROOM_STEM) {

					continue;

				}

				if (b instanceof StairBlock) {

					if (rel >= 2) {

						stairs.merge(b, 1, Integer::sum);

					}

				} else if (b instanceof SlabBlock) {

					if (rel >= 2) {

						slabs.merge(b, 1, Integer::sum);

					}

				} else if (b instanceof IronBarsBlock || b == Blocks.GLASS) {

					windows.merge(b, 1, Integer::sum);

				} else if (b instanceof FenceBlock) {

					fences.merge(b, 1, Integer::sum);

				} else if (s.is(BlockTags.LOGS)) {

					if (rel >= 0 && rel <= 6) {

						frames.merge(b, 1, Integer::sum);

					}

				} else if (!(b instanceof DoorBlock) && s.isCollisionShapeFullBlock(level, p)) {

					if (rel <= 0) {

						bases.merge(b, 1, Integer::sum);

					} else if (rel <= 3) {

						walls.merge(b, 1, Integer::sum);

					} else {

						roofs.merge(b, 1, Integer::sum);

					}

				}

			}

		}

		Block wall = top(walls, null, fallbackWall);

		Block wall2 = top(walls, wall, wall);

		Block frame = top(frames, null, null);

		Block base = top(bases, null, wall);

		Block roofStair = top(stairs, null, def.stairs());

		Block roofFull = fullOf(roofStair, top(roofs, null, wall));

		Block roofSlab = top(slabs, null, null);

		Block window = top(windows, null, Blocks.GLASS_PANE);

		return new Palette(wall, wall2, frame, base, roofFull, roofStair, roofSlab, window, fallbackFloor, def.door(), def.fence());

	}

	/**

	 * Dieselbe Palette, aber nur mit Material, das das Dorf selbst herstellen oder abbauen kann (Ersatz fuer exotische Bloecke wie

	 * bunte Terrakotta). Wird erst benutzt, wenn der Haendler das Original nicht liefern konnte.

	 */

	/** Block ids of the roles this village cannot obtain itself (empty = the whole palette can be built locally). */
	public List<String> unobtainableRoles() {
		String[] names = {"wall", "wall2", "frame", "base", "roofFull", "roofStair", "roofSlab", "window", "floor", "door", "fence"};
		Block[] all = {wall, wall2, frame, base, roofFull, roofStair, roofSlab, window, floor, door, fence};
		List<String> out = new java.util.ArrayList<>();
		for (int i = 0; i < all.length; i++) {
			if (all[i] != null && !Supply.obtainable(all[i])) {
				out.add(names[i] + "=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(all[i]));
			}
		}
		return out;
	}

	public Palette obtainable() {

		return new Palette(

			Supply.obtainable(wall) ? wall : Blocks.COBBLESTONE,

			Supply.obtainable(wall2) ? wall2 : Blocks.COBBLESTONE,

			frame == null || Supply.obtainable(frame) ? frame : Blocks.OAK_LOG,

			Supply.obtainable(base) ? base : Blocks.COBBLESTONE,

			Supply.obtainable(roofFull) ? roofFull : Blocks.OAK_PLANKS,

			roofStair instanceof StairBlock && Supply.obtainable(roofStair) ? roofStair : Blocks.COBBLESTONE_STAIRS,

			roofSlab instanceof SlabBlock && Supply.obtainable(roofSlab) ? roofSlab : null,

			Supply.obtainable(window) ? window : Blocks.GLASS_PANE,

			Supply.obtainable(floor) ? floor : Blocks.OAK_PLANKS,

			Supply.obtainable(door) ? door : Blocks.OAK_DOOR,

			fence != null && Supply.obtainable(fence) ? fence : Blocks.OAK_FENCE);

	}

	/** Registrierungsnamen der elf Rollen (leer = nicht vorhanden) - fuer die Instandhaltung gespeichert. */

	public List<String> ids() {

		Block[] all = {wall, wall2, frame, base, roofFull, roofStair, roofSlab, window, floor, door, fence};

		List<String> out = new java.util.ArrayList<>();

		for (Block b : all) {

			out.add(b == null ? "" : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString());

		}

		return out;

	}

	/** Gegenstueck zu {@link #ids()}; null, wenn die Liste unvollstaendig ist oder ein Block fehlt (z. B. Mod entfernt). */

	public static Palette ofIds(List<String> ids) {

		if (ids == null || ids.size() != 11) {

			return null;

		}

		Block[] b = new Block[11];

		for (int i = 0; i < 11; i++) {

			if (ids.get(i).isEmpty()) {

				b[i] = null;

			} else {

				b[i] = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(ids.get(i))).orElse(null);

				if (b[i] == null && i != 2 && i != 6 && i != 10) {

					return null;

				}

			}

		}

		return new Palette(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10]);

	}

	/** Vollblock passend zur Dachtreppe (spruce_stairs -> spruce_planks, brick_stairs -> bricks ...), sonst {@code fallback}. */

	private static Block fullOf(Block stair, Block fallback) {

		if (!(stair instanceof StairBlock)) {

			return fallback;

		}

		var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(stair);

		String base = key.getPath().substring(0, key.getPath().length() - "_stairs".length());

		for (String cand : new String[] {base + "_planks", base + "s", base, base + "_block"}) {

			Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.fromNamespaceAndPath(key.getNamespace(), cand)).orElse(null);

			if (b != null && b != Blocks.AIR) {

				return b;

			}

		}

		return fallback;

	}

	/** Haeufigster Block (ohne {@code not}); gibt es keinen, {@code fallback}. Der zweite Aufruf liefert so die "zweite Wand". */

	static Block top(Map<Block, Integer> tally, Block not, Block fallback) {

		Block best = null;

		int bestCount = 0;

		int total = tally.values().stream().mapToInt(Integer::intValue).sum();

		for (Map.Entry<Block, Integer> e : tally.entrySet()) {

			Block b = e.getKey();

			if (b == not || b.asItem() == Items.AIR || Block.byItem(b.asItem()) != b) {

				continue;

			}

			// Nur wirklich ins Gewicht fallende Bloecke (mind. 12 % aller Zaehlungen) zaehlen als Stilmerkmal.

			String candidateId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString();
			String bestId = best == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(best).toString();
			if (e.getValue() * 100 >= total * 12
				&& (e.getValue() > bestCount || (e.getValue() == bestCount && (bestId == null || candidateId.compareTo(bestId) < 0)))) {

				best = b;

				bestCount = e.getValue();

			}

		}

		return best != null ? best : fallback;

	}

}

