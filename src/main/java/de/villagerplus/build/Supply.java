package de.villagerplus.build;

import de.villagerplus.village.VillageStyle;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Beschaffbarkeit: Das Dorf kann nur Material liefern, das seine Handwerker herstellen oder abbauen koennen (Holz, Stein,
 * Erde, Sand, Glas, Wolle). Fremde Vorlagen-Bloecke (z.B. bunte Terrakotta oder Backstein aus Weltgenerator-Doerfern) wuerden den
 * Bau sonst fuer immer blockieren. Sie werden durch das passendste beschaffbare Gegenstueck ersetzt (Treppe -> Treppe des Dorfstils,
 * Vollblock -> Wandmaterial, Laterne -> Fackel, sonstige Deko -> Bodenmaterial), damit keine Planposition verloren geht.
 */
public final class Supply {
	private static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove", "bamboo", "pale_oak");
	private static final Set<String> STONES = Set.of("stone", "cobblestone", "stone_brick", "mossy_cobblestone", "andesite", "diorite", "granite",
		"sandstone", "smooth_sandstone", "cut_sandstone", "red_sandstone");
	// Chiseled bookshelves are craftable from vanilla planks and slabs for the versioned school facade badge.
	// Vanilla 26.2 barrel recipe: six planks plus two wooden slabs; both ingredient tags are obtainable below.
	private static final Set<String> SIMPLE = Set.of("dirt", "coarse_dirt", "sand", "red_sand", "gravel", "cobblestone", "stone", "stone_bricks", "andesite",
		"diorite", "granite", "sandstone", "smooth_sandstone", "cut_sandstone", "red_sandstone", "smooth_red_sandstone", "cut_red_sandstone",
		"glass", "glass_pane", "torch", "wall_torch", "mossy_cobblestone", "barrel", "chiseled_bookshelf", "crafting_table", "scaffolding",
		// A chest is eight planks; the public storage shed pays for its first one.
		"chest");
	private static final String[] WOOD_PARTS = {"planks", "log", "wood", "stairs", "slab", "fence", "fence_gate", "door", "trapdoor", "pressure_plate", "button"};
	private static final String[] SHAPES = {"_stairs", "_slab", "_wall"};

	private Supply() {
	}

	private static String path(Block b) {
		return BuiltInRegistries.BLOCK.getKey(b).getPath();
	}

	public static boolean obtainable(Block b) {
		if (b == null) return false;
		// Holz aus anderen Mods: ueber die Vanilla-Tags erkannt (jede Holzart, die sich normal verarbeiten laesst).
		BlockState st = b.defaultBlockState();
		if (st.is(BlockTags.PLANKS) || st.is(BlockTags.LOGS) || st.is(BlockTags.WOODEN_STAIRS) || st.is(BlockTags.WOODEN_SLABS)
			|| st.is(BlockTags.WOODEN_FENCES) || st.is(BlockTags.FENCE_GATES) || st.is(BlockTags.WOODEN_DOORS) || st.is(BlockTags.WOODEN_TRAPDOORS)
			|| st.is(BlockTags.WOODEN_BUTTONS) || st.is(BlockTags.WOODEN_PRESSURE_PLATES) || st.is(BlockTags.WOOL)) {
			return true;
		}
		String p = path(b);
		if (SIMPLE.contains(p) || p.endsWith("_wool")) {
			return true;
		}
		String bare = p.startsWith("stripped_") ? p.substring("stripped_".length()) : p;
		for (String wood : WOODS) {
			if (bare.startsWith(wood + "_")) {
				String rest = bare.substring(wood.length() + 1);
				for (String part : WOOD_PARTS) {
					if (rest.equals(part)) {
						return true;
					}
				}
			}
		}
		for (String shape : SHAPES) {
			if (p.endsWith(shape) && STONES.contains(p.substring(0, p.length() - shape.length()))) {
				return true;
			}
		}
		return false;
	}

	/** Beschaffbarer Ersatz fuer {@code b} im Stil des Dorfes; null wird nur fuer null-Eingaben zurueckgegeben. */
	public static Block substitute(Block b, VillageStyle style) {
		if (b == null) {
			return null;
		}
		if (obtainable(b)) {
			return b;
		}
		style = withBiomeFallback(style, null);
		String p = path(b);
		if (p.contains("lantern")) {
			return Blocks.TORCH;
		}
		if (p.contains("leaves") || p.contains("vine") || p.contains("banner") || p.contains("carpet") || p.contains("pot")) {
			// Preserve the source-template position and its paid step even when its decorative item is not producible.
			return obtainable(style.floor()) ? style.floor() : Blocks.OAK_PLANKS;
		}
		if (b instanceof StairBlock) {
			return stairsFor(style);
		}
		if (b instanceof SlabBlock) {
			return slabOf(style.floor());
		}
		if (b instanceof WallBlock) {
			return wallFor(style);
		}
		if (b instanceof FenceBlock) {
			return style.fence() instanceof FenceBlock && obtainable(style.fence()) ? style.fence() : Blocks.OAK_FENCE;
		}
		if (b instanceof FenceGateBlock) {
			return gateFor(style);
		}
		if (b instanceof DoorBlock) {
			return doorFor(style);
		}
		if (b instanceof TrapDoorBlock) {
			return trapdoorFor(style);
		}
		if (b instanceof IronBarsBlock || p.contains("glass")) {
			return p.contains("pane") || b instanceof IronBarsBlock ? Blocks.GLASS_PANE : Blocks.GLASS;
		}
		if (b.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
			// Das Wandmaterial des Dorfes selbst kann exotisch sein (z. B. Terrakotta): dann gibt es schlichten Bruchstein.
			return obtainable(style.wall()) ? style.wall() : Blocks.COBBLESTONE;
		}
		// Preserve arbitrary non-full decorative source blocks as a paid, locally available filler.
		return obtainable(style.floor()) ? style.floor() : Blocks.OAK_PLANKS;
	}

	/**
	 * Biome-aware source-template fallback. Existing CTOV/village materials win when obtainable;
	 * the biome palette is used only for style components the village cannot supply.
	 */
	public static Block substitute(Block b, VillageStyle style, Identifier biomeId) {
		return substitute(b, withBiomeFallback(style, biomeId));
	}

	/** Resolve an unavailable detected style component against a simple palette for this biome. */
	public static VillageStyle withBiomeFallback(VillageStyle detected, Identifier biomeId) {
		VillageStyle fallback = biomePalette(biomeId);
		if (detected == null) return fallback;
		return new VillageStyle(
			availableOr(detected.wall(), fallback.wall()),
			availableOr(detected.floor(), fallback.floor()),
			availableOr(detected.roof(), fallback.roof()),
			availableOr(detected.stairs(), fallback.stairs()),
			availableOr(detected.door(), fallback.door()),
			availableOr(detected.fence(), fallback.fence()));
	}

	/**
	 * Biome-aware material resolution for architect palettes. Existing obtainable CTOV components remain intact;
	 * unavailable roles receive matching local materials while the palette's block shapes stay the same.
	 */
	public static Palette obtainablePalette(Palette detected, Identifier biomeId) {
		if (detected == null) return null;
		VillageStyle style = withBiomeFallback(new VillageStyle(detected.wall(), detected.floor(), detected.roofFull(),
			detected.roofStair(), detected.door(), detected.fence()), biomeId);
		Block frameFallback = logFor(style.floor());
		Block slabFallback = detected.roofSlab() == null ? null : slabOf(style.floor());
		return new Palette(
			availableOr(detected.wall(), style.wall()),
			availableOr(detected.wall2(), style.wall()),
			detected.frame() == null || obtainable(detected.frame()) ? detected.frame() : frameFallback,
			availableOr(detected.base(), style.wall()),
			availableOr(detected.roofFull(), style.roof()),
			detected.roofStair() instanceof StairBlock && obtainable(detected.roofStair())
				? detected.roofStair() : stairsFor(style),
			detected.roofSlab() instanceof SlabBlock && obtainable(detected.roofSlab())
				? detected.roofSlab() : slabFallback,
			availableOr(detected.window(), Blocks.GLASS_PANE),
			availableOr(detected.floor(), style.floor()),
			detected.door() instanceof DoorBlock && obtainable(detected.door())
				? detected.door() : doorFor(style),
			detected.fence() instanceof FenceBlock && obtainable(detected.fence())
				? detected.fence() : style.fence());
	}

	/** Rebuilds the wooden parts of a palette from one wood species (the one that actually grows around the village). */
	public static Palette withWoodStem(Palette p, String stem) {
		if (p == null || stem == null || stem.isBlank()) return p;
		Block planks = blockOptional(stem + "_planks"), log = blockOptional(stem + "_log");
		if (planks == null || log == null || !obtainable(planks)) return p;
		Block fence = blockOptional(stem + "_fence"), door = blockOptional(stem + "_door");
		Block stairs = blockOptional(stem + "_stairs"), slab = blockOptional(stem + "_slab");
		return new Palette(
			woodOr(p.wall(), planks), woodOr(p.wall2(), planks),
			p.frame() != null && p.frame().defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS) ? log : p.frame(),
			woodOr(p.base(), planks), woodOr(p.roofFull(), planks),
			woodShape(p.roofStair()) && stairs instanceof StairBlock ? stairs : p.roofStair(),
			woodShape(p.roofSlab()) && slab instanceof SlabBlock ? slab : p.roofSlab(),
			p.window(), woodOr(p.floor(), planks),
			p.door() instanceof DoorBlock && woodShape(p.door()) && door instanceof DoorBlock ? door : p.door(),
			p.fence() instanceof FenceBlock && woodShape(p.fence()) && fence instanceof FenceBlock ? fence : p.fence());
	}

	private static Block woodOr(Block block, Block planks) {
		return block != null && block.defaultBlockState().is(net.minecraft.tags.BlockTags.PLANKS) ? planks : block;
	}

	private static boolean woodShape(Block block) {
		if (block == null) return false;
		var state = block.defaultBlockState();
		return state.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS) || state.is(net.minecraft.tags.BlockTags.WOODEN_SLABS)
			|| state.is(net.minecraft.tags.BlockTags.WOODEN_FENCES) || state.is(net.minecraft.tags.BlockTags.WOODEN_DOORS);
	}

	private static Block availableOr(Block detected, Block fallback) {
		return detected != null && obtainable(detected) ? detected : fallback;
	}

	private static Block logFor(Block planks) {
		String path = path(planks);
		if (path.equals("bamboo_planks")) {
			Block bamboo = blockOptional("bamboo_block");
			return bamboo != null && obtainable(bamboo) ? bamboo : Blocks.OAK_LOG;
		}
		String stem = path.endsWith("_planks") ? path.substring(0, path.length() - "_planks".length()) : "oak";
		Block log = blockOptional(stem + "_log");
		return log != null && obtainable(log) ? log : Blocks.OAK_LOG;
	}

	private static VillageStyle biomePalette(Identifier biomeId) {
		String id = biomeId == null ? "" : biomeId.getPath();
		if (id.contains("badlands") || id.contains("mesa")) {
			return of("red_sandstone", "smooth_red_sandstone", "cut_red_sandstone", "red_sandstone_stairs", "dark_oak_door", "dark_oak_fence");
		}
		if (id.contains("desert")) {
			return of("sandstone", "smooth_sandstone", "cut_sandstone", "sandstone_stairs", "oak_door", "oak_fence");
		}
		if (id.contains("savanna")) {
			return of("acacia_planks", "acacia_planks", "acacia_planks", "acacia_stairs", "acacia_door", "acacia_fence");
		}
		if (id.contains("mangrove")) {
			return of("cobblestone", "mangrove_planks", "mangrove_planks", "mangrove_stairs", "mangrove_door", "mangrove_fence");
		}
		if (id.contains("swamp")) {
			return of("mossy_cobblestone", "oak_planks", "oak_planks", "oak_stairs", "oak_door", "oak_fence");
		}
		if (id.contains("cherry_grove")) {
			return of("cobblestone", "cherry_planks", "cherry_planks", "cherry_stairs", "cherry_door", "cherry_fence");
		}
		if (id.contains("bamboo_jungle")) {
			return of("cobblestone", "bamboo_planks", "bamboo_planks", "bamboo_stairs", "bamboo_door", "bamboo_fence");
		}
		if (id.contains("jungle")) {
			return of("cobblestone", "jungle_planks", "jungle_planks", "jungle_stairs", "jungle_door", "jungle_fence");
		}
		if (id.contains("pale_garden")) {
			return of("cobblestone", "pale_oak_planks", "pale_oak_planks", "pale_oak_stairs", "pale_oak_door", "pale_oak_fence");
		}
		if (id.contains("birch")) {
			return of("cobblestone", "birch_planks", "birch_planks", "birch_stairs", "birch_door", "birch_fence");
		}
		if (id.contains("dark_forest")) {
			return of("cobblestone", "dark_oak_planks", "dark_oak_planks", "dark_oak_stairs", "dark_oak_door", "dark_oak_fence");
		}
		if (id.contains("taiga") || id.contains("grove") || id.contains("snowy") || id.contains("frozen")) {
			return of("cobblestone", "spruce_planks", "spruce_planks", "spruce_stairs", "spruce_door", "spruce_fence");
		}
		return of("cobblestone", "oak_planks", "oak_planks", "oak_stairs", "oak_door", "oak_fence");
	}

	private static VillageStyle of(String wall, String floor, String roof, String stairs, String door, String fence) {
		return new VillageStyle(block(wall), block(floor), block(roof), block(stairs), block(door), block(fence));
	}

	private static Block block(String name) {
		return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name)).orElse(Blocks.COBBLESTONE);
	}

	private static Block blockOptional(String name) {
		return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name)).orElse(null);
	}

	private static Block stairsFor(VillageStyle style) {
		if (style.stairs() instanceof StairBlock && obtainable(style.stairs())) {
			return style.stairs();
		}
		Block derived = shapeOf(style.roof(), "_stairs", StairBlock.class);
		return derived instanceof StairBlock && obtainable(derived) ? derived : Blocks.COBBLESTONE_STAIRS;
	}

	private static Block wallFor(VillageStyle style) {
		if (style.wall() instanceof WallBlock && obtainable(style.wall())) {
			return style.wall();
		}
		Block derived = shapeOf(style.wall(), "_wall", WallBlock.class);
		return derived instanceof WallBlock && obtainable(derived) ? derived : Blocks.COBBLESTONE_WALL;
	}

	private static Block gateFor(VillageStyle style) {
		if (style.fence() == null) return Blocks.OAK_FENCE_GATE;
		String fencePath = path(style.fence());
		String stem = fencePath.endsWith("_fence") ? fencePath.substring(0, fencePath.length() - "_fence".length()) : fencePath;
		Block derived = blockOptional(stem + "_fence_gate");
		return derived instanceof FenceGateBlock && obtainable(derived) ? derived : Blocks.OAK_FENCE_GATE;
	}

	private static Block doorFor(VillageStyle style) {
		if (style.door() instanceof DoorBlock && obtainable(style.door())) {
			return style.door();
		}
		String stem = style.floor() == null ? null : woodStem(path(style.floor()));
		Block derived = stem == null ? null : blockOptional(stem + "_door");
		return derived instanceof DoorBlock && obtainable(derived) ? derived : Blocks.OAK_DOOR;
	}

	private static Block trapdoorFor(VillageStyle style) {
		String doorPath = path(doorFor(style));
		String stem = doorPath.endsWith("_door") ? doorPath.substring(0, doorPath.length() - "_door".length()) : null;
		Block derived = stem == null ? null : blockOptional(stem + "_trapdoor");
		return derived instanceof TrapDoorBlock && obtainable(derived) ? derived : Blocks.OAK_TRAPDOOR;
	}

	private static String woodStem(String path) {
		if (path.endsWith("_planks")) return path.substring(0, path.length() - "_planks".length());
		if (path.endsWith("_door")) return path.substring(0, path.length() - "_door".length());
		return null;
	}

	private static Block shapeOf(Block base, String suffix, Class<? extends Block> type) {
		if (base == null) return null;
		Block existing = blockOptional(path(base) + suffix);
		return existing != null && type.isInstance(existing) ? existing : null;
	}

	private static Block slabOf(Block floor) {
		if (floor == null) return Blocks.COBBLESTONE_SLAB;
		String p = path(floor);
		for (String cand : new String[] {p.replace("_planks", "") + "_slab", p + "_slab"}) {
			Block slab = blockOptional(cand);
			if (slab instanceof SlabBlock && obtainable(slab)) {
				return slab;
			}
		}
		return Blocks.COBBLESTONE_SLAB;
	}

	/** Uebertraegt Ausrichtung/Haelfte/Form von {@code from} auf den Ersatzblock. */
	public static BlockState transfer(BlockState from, Block to) {
		BlockState s = to.defaultBlockState();
		for (Property<?> p : from.getProperties()) {
			if (s.hasProperty(p)) {
				s = copy(s, from, p);
			}
		}
		return s;
	}

	private static <T extends Comparable<T>> BlockState copy(BlockState s, BlockState from, Property<T> p) {
		return s.setValue(p, from.getValue(p));
	}
}
