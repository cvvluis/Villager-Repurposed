package de.villagerplus.build;

import de.villagerplus.village.VillageStyle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Bauplaene. {@code c} ist immer die erste freie Luftschicht ueber dem Boden, in der Mitte des Grundrisses. */
public final class Blueprints {
	public static final String HOUSE = "house";
	public static final String FARM = "farm";
	public static final String WELL = "well";
	public static final String TOWNHALL = "townhall";
	public static final String WALL = "wall";
	public static final String PEN = "pen";
	public static final String DECOR = "decor";
	public static final String STORAGE = "storage";
	public static final String SCHOOL = "school";
	/** Kornspeicher (Lebensmittel), Werkzeug- und Ruestungskammer, Dorfkueche/Baeckerei: Pflicht-Meilensteine jedes Dorfes. */
	public static final String GRANARY = "granary";
	public static final String TOOLSHED = "toolshed";
	/** Optionale Funktionsblöcke der Dorfküche; ohne Mod werden sie nicht als Vanilla-Block getarnt. */
	public static final String FARMERS_DELIGHT_COOKING_POT = "farmersdelight:cooking_pot";
	public static final String FARMERS_DELIGHT_CUTTING_BOARD = "farmersdelight:cutting_board";
	public static final String KITCHEN = "kitchen";
	/** Bahnhof (Spaetphase): Frachthalle mit Lagerkisten fuer den Schienenverkehr. */
	public static final String STATION = "station";

	public static boolean special(String type) {
		return type.equals(GRANARY) || type.equals(TOOLSHED) || type.equals(KITCHEN) || type.equals(STATION);
	}

	/** Stable compact → standard → multi-storey rotation, kept separate for homes and civic buildings by the planner. */
	public static Architect.SizeTier sizeTierForOrdinal(int completedInGroup) {
		return switch (Math.floorMod(completedInGroup, 3)) {
			case 0 -> Architect.SizeTier.COMPACT;
			case 1 -> Architect.SizeTier.STANDARD;
			default -> Architect.SizeTier.MULTI_STOREY;
		};
	}

	private static final Item[] SEEDS = {Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS};
	private static final Block[] CROPS = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};

	private Blueprints() {
	}

	public static Item seed(int variant) {
		return SEEDS[variant];
	}

	public static int seedKinds() {
		return SEEDS.length;
	}

	public static List<Step> create(String type, VillageStyle style, BlockPos c, Direction dir, int variant) {
		return switch (type) {
			case HOUSE -> house(style, c, dir);
			case FARM -> farm(style, c, dir, variant);
			case WELL -> well(style, c);
			case TOWNHALL -> hall(style, c, dir);
			case PEN -> pen(style, c, dir);
			case DECOR -> decor(style, c, dir, variant);
			case STORAGE -> storage(style, c, dir);
			case SCHOOL -> school(style, c, dir);
			default -> List.of();
		};
	}

	public static int half(String type) {
		return switch (type) {
			case HOUSE -> 2;
			case FARM -> 3;
			case TOWNHALL, PEN, STORAGE, SCHOOL, GRANARY, TOOLSHED, KITCHEN, STATION -> 4;
			case DECOR -> 3;
			default -> 1;
		};
	}

	public static int headroom(String type) {
		return type.equals(HOUSE) ? 6 : type.equals(TOWNHALL) ? 9 : type.equals(PEN) ? 3 : type.equals(DECOR) ? 7 : type.equals(STORAGE) || type.equals(SCHOOL) || special(type) ? 6 : 2;
	}

	// --- Schule (7x5, Bankreihen, Regal-Wand mit Buechern, Tafel, flaches Dach) -----------------------

	public static List<Step> school(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		Direction perp = dir.getClockWise();
		BlockPos doorLower = c.relative(dir, 2);
		BlockPos doorUpper = doorLower.above();
		for (int i = -3; i <= 3; i++) {
			for (int j = -2; j <= 2; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).below(), style.floor()));
			}
		}
		for (int dy = 0; dy < 3; dy++) {
			for (int i = -3; i <= 3; i++) {
				for (int j = -2; j <= 2; j++) {
					if (Math.abs(i) != 3 && Math.abs(j) != 2) {
						continue;
					}
					BlockPos p = c.relative(perp, i).relative(dir, j).above(dy);
					boolean window = dy == 1 && j == 0 && Math.abs(i) == 3;
					if (p.equals(doorLower) || p.equals(doorUpper)) {
						continue;
					}
					if (window) {
						steps.add(Step.free(p, Blocks.GLASS_PANE.defaultBlockState()));
					} else {
						steps.add(Step.block(p, style.wall()));
					}
				}
			}
		}
		for (int i = -3; i <= 3; i++) {
			for (int j = -2; j <= 2; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).above(3), style.roof()));
			}
		}
		Direction doorFacing = dir.getOpposite();
		steps.add(new Step(doorLower, style.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing)));
		steps.add(Step.linked(doorUpper, doorLower,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
		steps.add(Step.free(c.above(2), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true)));
		return steps;
	}

	// --- Lagerschuppen (7x5, 3 Bloecke hohe Waende, flaches Dach, 6 Truhen an der Rueckwand) ----------

	/**
	 * Zweckbauten (Lager, Schule) bestehen aus Material, das das Dorf selbst beschaffen kann (Bruchstein, Bretter, Holztuer):
	 * Passt der Dorfstil dazu, wird er uebernommen, sonst gibt es die schlichte Standardvariante.
	 */
	static VillageStyle localStyle(VillageStyle style) {
		Block wall = Supply.obtainable(style.wall()) ? style.wall() : Blocks.COBBLESTONE;
		Block floor = Supply.obtainable(style.floor()) ? style.floor() : Blocks.OAK_PLANKS;
		Block roof = Supply.obtainable(style.roof()) ? style.roof() : Blocks.OAK_PLANKS;
		Block door = Supply.obtainable(style.door()) ? style.door() : Blocks.OAK_DOOR;
		Block stairs = style.stairs() != null && Supply.obtainable(style.stairs()) ? style.stairs() : Blocks.OAK_STAIRS;
		return new VillageStyle(wall, floor, roof, stairs, door, style.fence());
	}

	public static List<Step> storage(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		Direction perp = dir.getClockWise();
		BlockPos doorLower = c.relative(dir, 2);
		BlockPos doorUpper = doorLower.above();
		for (int i = -3; i <= 3; i++) {
			for (int j = -2; j <= 2; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).below(), style.floor()));
			}
		}
		for (int dy = 0; dy < 3; dy++) {
			for (int i = -3; i <= 3; i++) {
				for (int j = -2; j <= 2; j++) {
					if (Math.abs(i) != 3 && Math.abs(j) != 2) {
						continue;
					}
					BlockPos p = c.relative(perp, i).relative(dir, j).above(dy);
					if (!p.equals(doorLower) && !p.equals(doorUpper)) {
						steps.add(Step.block(p, style.wall()));
					}
				}
			}
		}
		for (int i = -3; i <= 3; i++) {
			for (int j = -2; j <= 2; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).above(3), style.roof()));
			}
		}
		Direction doorFacing = dir.getOpposite();
		steps.add(new Step(doorLower, style.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing)));
		steps.add(Step.linked(doorUpper, doorLower,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
		steps.add(Step.free(c.above(2), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true)));
		return steps;
	}

	// --- Haus (5x5, 3 Bloecke hohe Waende, Satteldach) -------------------------------------------

	public static List<Step> house(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		BlockPos doorLower = c.relative(dir, 2);
		BlockPos doorUpper = doorLower.above();
		Direction perp = dir.getClockWise();

		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				steps.add(Step.block(c.offset(dx, -1, dz), style.floor()));
			}
		}
		for (int dy = 0; dy < 3; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) {
						continue;
					}
					BlockPos p = c.offset(dx, dy, dz);
					if (!p.equals(doorLower) && !p.equals(doorUpper)) {
						steps.add(Step.block(p, style.wall()));
					}
				}
			}
		}

		// Dach: Traufe und erste Reihe als Treppen, Mitte/First als Vollbloecke (ohne Treppen-Stil: flaches Dach).
		boolean pitched = style.stairs() != null && style.stairs() instanceof StairBlock;
		for (int i = -2; i <= 2; i++) {
			BlockPos along = c.relative(perp, i);
			for (int j = -2; j <= 2; j++) {
				BlockPos base = along.relative(dir, j);
				if (!pitched || Math.abs(j) <= 1) {
					steps.add(Step.block(base.above(3), style.roof()));
				} else {
					steps.add(stair(base.above(3), style, j > 0 ? dir : dir.getOpposite()));
				}
			}
			if (pitched) {
				steps.add(stair(along.relative(dir, 1).above(4), style, dir));
				steps.add(stair(along.relative(dir, -1).above(4), style, dir.getOpposite()));
				steps.add(Step.block(along.above(4), style.roof()));
				steps.add(Step.block(along.above(5), style.roof()));
			}
		}

		Direction doorFacing = dir.getOpposite();
		steps.add(new Step(doorLower, style.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing)));
		steps.add(Step.linked(doorUpper, doorLower,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));

		BlockPos foot = c.relative(dir.getOpposite()).relative(perp.getOpposite());
		BlockPos head = foot.relative(perp);
		steps.add(new Step(foot, null, ItemTags.BEDS, 1, false, null,
			b -> b.defaultBlockState().setValue(BedBlock.FACING, perp).setValue(BedBlock.PART, BedPart.FOOT)));
		steps.add(Step.linked(head, foot,
			b -> b.defaultBlockState().setValue(BedBlock.FACING, perp).setValue(BedBlock.PART, BedPart.HEAD)));
		steps.add(Step.free(head.relative(perp), Blocks.TORCH.defaultBlockState()));
		return steps;
	}

	private static Step stair(BlockPos pos, VillageStyle style, Direction facing) {
		return Step.block(pos, style.stairs(), b -> b.defaultBlockState().setValue(StairBlock.FACING, facing));
	}

	// --- Feld (3x3 starter plot or larger irrigated field) ------------------------------------------

	/**
	 * Feld mit Wasserstellen. {@code variant} = Saatgut-Art + 10 * halbe Kantenlaenge. A half-size of 1 gives a
	 * compact 3x3 starter plot (8 crop blocks); size 3 is 7x7. Larger fields (9x9, 11x11) use four water blocks.
	 */
	public static List<Step> farm(BlockPos c, int variant) {
		return farm(null, c, Direction.NORTH, variant);
	}

	/** Farm layout oriented toward {@code dir}; the paid fence ring uses the village palette and a matching gate. */
	public static List<Step> farm(VillageStyle style, BlockPos c, Direction dir, int variant) {
		List<Step> steps = new ArrayList<>();
		int seedKind = variant % 10;
		int half = variant / 10 == 0 ? 3 : variant / 10;
		Direction facing = dir == null || dir.getAxis() == Direction.Axis.Y ? Direction.NORTH : dir;
		Block fence = style == null || style.fence() == null ? Blocks.OAK_FENCE : style.fence();
		Block gate = gateFor(fence);
		if (!(gate instanceof FenceGateBlock)) {
			fence = Blocks.OAK_FENCE;
			gate = Blocks.OAK_FENCE_GATE;
		}
		Item seed = SEEDS[seedKind];
		Block crop = CROPS[seedKind];
		java.util.Set<BlockPos> water = new java.util.HashSet<>();
		if (half <= 3) {
			water.add(c);
		} else {
			water.add(c.offset(-2, 0, -2));
			water.add(c.offset(2, 0, -2));
			water.add(c.offset(-2, 0, 2));
			water.add(c.offset(2, 0, 2));
		}
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				BlockPos p = c.offset(dx, 0, dz);
				if (!water.contains(p)) {
					steps.add(Step.free(p.below(), Blocks.FARMLAND.defaultBlockState()));
				}
			}
		}
		for (BlockPos w : water) {
			steps.add(Step.free(w.below(), Blocks.WATER.defaultBlockState()));
		}
		// Enclose the complete crop bed. The field decorations on the three corners reuse these paid perimeter blocks.
		int fenceRadius = half + 1;
		BlockPos gatePos = farmOffset(c, facing, 0, fenceRadius);
		for (int dx = -fenceRadius; dx <= fenceRadius; dx++) {
			for (int dz = -fenceRadius; dz <= fenceRadius; dz++) {
				if (Math.abs(dx) != fenceRadius && Math.abs(dz) != fenceRadius) continue;
				BlockPos p = farmOffset(c, facing, dx, dz);
				if (p.equals(gatePos)) {
					steps.add(Step.block(p, gate,
						b -> b.defaultBlockState().setValue(FenceGateBlock.FACING, facing)
							.setValue(FenceGateBlock.OPEN, true)));
				} else {
					steps.add(Step.block(p, fence));
				}
			}
		}
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				BlockPos p = c.offset(dx, 0, dz);
				if (!water.contains(p)) {
					steps.add(new Step(p, seed, null, 1, true, null, b -> crop.defaultBlockState()));
				}
			}
		}
		// Corner lanterns and scarecrow stay on the paid fence ring; flowers remain outside the walkable entrance area.
		int e = fenceRadius;
		for (int sx = -1; sx <= 1; sx += 2) {
			BlockPos post = farmOffset(c, facing, sx * e, e);
			steps.add(Step.free(post.above(), fence.defaultBlockState()));
			steps.add(Step.free(post.above(2), Blocks.LANTERN.defaultBlockState()));
		}
		BlockPos scare = farmOffset(c, facing, e, -e);
		steps.add(Step.free(scare.above(), Blocks.HAY_BLOCK.defaultBlockState()));
		steps.add(Step.free(scare.above(2), Blocks.CARVED_PUMPKIN.defaultBlockState()));
		steps.add(Step.free(farmOffset(c, facing, -e - 1, -e), Blocks.POPPY.defaultBlockState()));
		steps.add(Step.free(farmOffset(c, facing, -e - 1, -e + 1), Blocks.CORNFLOWER.defaultBlockState()));
		return steps;
	}

	private static BlockPos farmOffset(BlockPos center, Direction facing, int across, int forward) {
		return center.relative(facing.getClockWise(), across).relative(facing, forward);
	}

	// --- Brunnen (3x3) -----------------------------------------------------------------------------

	public static List<Step> well(VillageStyle style, BlockPos c) {
		List<Step> steps = new ArrayList<>();
		steps.add(Step.free(c.below(), Blocks.WATER.defaultBlockState()));
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					steps.add(Step.block(c.offset(dx, 0, dz), style.wall()));
				}
			}
		}
		return steps;
	}

	// --- Rathaus (9x7, 4 Bloecke hohe Waende, grosses Satteldach) ----------------------------------

	public static List<Step> hall(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		Direction perp = dir.getClockWise();
		BlockPos doorLower = c.relative(dir, 3);
		BlockPos doorUpper = doorLower.above();
		for (int i = -4; i <= 4; i++) {
			for (int j = -3; j <= 3; j++) {
				steps.add(Step.block(c.relative(perp, i).relative(dir, j).below(), style.floor()));
			}
		}
		for (int dy = 0; dy < 4; dy++) {
			for (int i = -4; i <= 4; i++) {
				for (int j = -3; j <= 3; j++) {
					if (Math.abs(i) != 4 && Math.abs(j) != 3) {
						continue;
					}
					BlockPos p = c.relative(perp, i).relative(dir, j).above(dy);
					if (!p.equals(doorLower) && !p.equals(doorUpper)) {
						steps.add(Step.block(p, style.wall()));
					}
				}
			}
		}
		boolean pitched = style.stairs() != null && style.stairs() instanceof StairBlock;
		int half = 3;
		for (int level = 0; level <= (pitched ? half : 0); level++) {
			int reach = half - level;
			for (int i = -4; i <= 4; i++) {
				BlockPos along = c.relative(perp, i).above(4 + level);
				for (int j = -reach; j <= reach; j++) {
					BlockPos pos = along.relative(dir, j);
					if (pitched && level < half && Math.abs(j) == reach) {
						steps.add(stair(pos, style, j > 0 ? dir : dir.getOpposite()));
					} else if (!pitched && level == 0 || pitched) {
						steps.add(Step.block(pos, style.roof()));
					}
				}
			}
		}
		Direction doorFacing = dir.getOpposite();
		steps.add(new Step(doorLower, style.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing)));
		steps.add(Step.linked(doorUpper, doorLower,
			b -> b.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
		steps.add(Step.free(c.relative(perp, -3).relative(dir, -2), Blocks.LANTERN.defaultBlockState()));
		steps.add(Step.free(c.relative(perp, 3).relative(dir, -2), Blocks.LANTERN.defaultBlockState()));
		return steps;
	}

	// --- Tiergehege (9x9 Zaun mit Tor, offenem Wetterschutz und Trog) ---------------------------------

	public static List<Step> pen(VillageStyle style, BlockPos c, Direction dir) {
		List<Step> steps = new ArrayList<>();
		Block gate = gateFor(style.fence());
		BlockPos gatePos = c.relative(dir, 4);
		for (int i = -4; i <= 4; i++) {
			for (int j = -4; j <= 4; j++) {
				if (Math.abs(i) != 4 && Math.abs(j) != 4) {
					continue;
				}
				BlockPos p = c.relative(dir.getClockWise(), i).relative(dir, j);
				if (p.equals(gatePos) && gate instanceof net.minecraft.world.level.block.FenceGateBlock) {
					Direction facing = dir;
					steps.add(Step.block(p, gate, b -> b.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, facing)));
				} else {
					steps.add(Step.block(p, style.fence()));
				}
			}
		}
		// A paid wool silhouette sits on the two fence posts beside the entrance. The outline stays
		// within the 9-by-9 pen, leaves the gate and animal passage clear, and marks this as a
		// livestock yard rather than the crop field's hay-and-pumpkin scarecrow.
		BlockPos woolBody = penOffset(c, dir, -1, 4).above();
		BlockPos woolHead = penOffset(c, dir, -2, 4).above();
		steps.add(Step.block(woolBody, Blocks.WOOL.white()));
		steps.add(Step.block(woolHead, Blocks.WOOL.black()));

		// Open-sided shelter at the back of the pen: pay the supports before the roof so the work appears stable as built.
		for (int across : new int[] {-2, 0}) {
			for (int forward : new int[] {-3, -1}) {
				BlockPos post = penOffset(c, dir, across, forward);
				steps.add(Step.block(post, style.wall()));
				steps.add(Step.block(post.above(), style.wall()));
			}
		}
		for (int across = -2; across <= 0; across++) {
			for (int forward = -3; forward <= -1; forward++) {
				steps.add(Step.block(penOffset(c, dir, across, forward).above(2), style.roof()));
			}
		}
		steps.add(Step.block(penOffset(c, dir, -2, -2), Blocks.HAY_BLOCK));

		// A shallow, two-block water trough sits opposite the shelter; its rim is paid village wall material.
		for (int forward = 1; forward <= 2; forward++) {
			steps.add(Step.block(penOffset(c, dir, 0, forward).below(), style.wall()));
			steps.add(Step.block(penOffset(c, dir, 2, forward).below(), style.wall()));
		}
		steps.add(Step.block(penOffset(c, dir, 1, 0).below(), style.wall()));
		steps.add(Step.block(penOffset(c, dir, 1, 3).below(), style.wall()));
		steps.add(Step.free(penOffset(c, dir, 1, 1).below(), Blocks.WATER.defaultBlockState()));
		steps.add(Step.free(penOffset(c, dir, 1, 2).below(), Blocks.WATER.defaultBlockState()));
		return steps;
	}

	private static BlockPos penOffset(BlockPos center, Direction facing, int across, int forward) {
		return center.relative(facing.getClockWise(), across).relative(facing, forward);
	}

	/** Passendes Tor zum Zaun ("oak_fence" -> "oak_fence_gate"), sonst ein normaler Zaun. */
	public static Block gateFor(Block fence) {
		net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(fence);
		net.minecraft.resources.Identifier gateId = net.minecraft.resources.Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_gate");
		Block gate = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(gateId).orElse(fence);
		return gate instanceof net.minecraft.world.level.block.FenceGateBlock ? gate : fence;
	}
	// --- Reichtums-Aufbauten --------------------------------------------------------------------------

	/** Stufe 0: Laternenpfaehle, 1: Marktstand, 2: Denkmal, 3: Springbrunnen, 4: Festplatz. */
	public static List<Step> decor(VillageStyle style, BlockPos c, Direction dir, int tier) {
		List<Step> steps = new ArrayList<>();
		Direction perp = dir.getClockWise();
		switch (tier % 5) {
			case 0 -> {
				for (int i = -2; i <= 2; i += 2) {
					BlockPos post = c.relative(perp, i);
					steps.add(Step.block(post, style.fence()));
					steps.add(Step.block(post.above(), style.fence()));
					steps.add(Step.free(post.above(2), Blocks.LANTERN.defaultBlockState()));
				}
			}
			case 1 -> {
				for (int i = -1; i <= 1; i += 2) {
					for (int j = 0; j <= 1; j++) {
						BlockPos post = c.relative(perp, i).relative(dir, j);
						steps.add(Step.block(post, style.fence()));
						steps.add(Step.block(post.above(), style.fence()));
						steps.add(Step.block(post.above(2), style.fence()));
					}
				}
				for (int i = -1; i <= 1; i++) {
					for (int j = 0; j <= 1; j++) {
						steps.add(Step.free(c.relative(perp, i).relative(dir, j).above(3), Blocks.CARPET.red().defaultBlockState()));
					}
				}
				steps.add(Step.free(c.relative(dir, 0), Blocks.CRAFTING_TABLE.defaultBlockState()));
				steps.add(Step.free(c.relative(dir, 1), Blocks.HAY_BLOCK.defaultBlockState()));
			}
			case 2 -> {
				for (int i = -1; i <= 1; i++) {
					for (int j = -1; j <= 1; j++) {
						steps.add(Step.block(c.relative(perp, i).relative(dir, j), style.wall()));
					}
				}
				for (int dy = 1; dy <= 4; dy++) {
					steps.add(Step.block(c.above(dy), style.wall()));
				}
				steps.add(Step.free(c.above(5), Blocks.GOLD_BLOCK.defaultBlockState()));
				steps.add(Step.free(c.relative(perp, 1).relative(dir, 1).above(), Blocks.LANTERN.defaultBlockState()));
				steps.add(Step.free(c.relative(perp, -1).relative(dir, -1).above(), Blocks.LANTERN.defaultBlockState()));
			}
			case 3 -> {
				for (int i = -2; i <= 2; i++) {
					for (int j = -2; j <= 2; j++) {
						if (Math.max(Math.abs(i), Math.abs(j)) == 2) {
							steps.add(Step.block(c.relative(perp, i).relative(dir, j), style.wall()));
						} else if (i != 0 || j != 0) {
							steps.add(Step.free(c.relative(perp, i).relative(dir, j).below(), Blocks.WATER.defaultBlockState()));
						}
					}
				}
				steps.add(Step.block(c, style.wall()));
				steps.add(Step.block(c.above(), style.wall()));
				steps.add(Step.free(c.above(2), Blocks.LANTERN.defaultBlockState()));
			}
			default -> {
				for (int i = -3; i <= 3; i++) {
					for (int j = -3; j <= 3; j++) {
						steps.add(Step.block(c.relative(perp, i).relative(dir, j).below(), style.floor()));
					}
				}
				for (int sx = -3; sx <= 3; sx += 6) {
					for (int sz = -3; sz <= 3; sz += 6) {
						BlockPos post = c.relative(perp, sx).relative(dir, sz);
						steps.add(Step.block(post, style.fence()));
						steps.add(Step.block(post.above(), style.fence()));
						steps.add(Step.free(post.above(2), Blocks.LANTERN.defaultBlockState()));
					}
				}
				steps.add(Step.free(c, Blocks.POPPY.defaultBlockState()));
				steps.add(Step.free(c.relative(perp, 2), Blocks.DANDELION.defaultBlockState()));
				steps.add(Step.free(c.relative(perp, -2), Blocks.CORNFLOWER.defaultBlockState()));
			}
		}
		return steps;
	}}
