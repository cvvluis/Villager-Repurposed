package de.villagerplus.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * Checks for the early palette substitution (P4e): a plan must start with material the village can obtain instead of
 * waiting 3000 ticks for deepslate bricks that nobody can produce. The pure part covers the look-alike mapping and the
 * granary plan built with it; the fixture part detects the palette from a real (deepslate-brick) house in the world.
 */
public final class PaletteFallbackChecks {
	private PaletteFallbackChecks() { }

	private record Cell(BlockState state, CompoundTag blockEntity) { }

	public static int run(ServerLevel level, BlockPos bed) {
		int checks = lookAlikeChecks();
		checks += planChecks();
		checks += fenceWindowChecks();
		checks += houseSupplyChecks();
		checks += fixtureChecks(level, bed);
		return checks;
	}

	private static int houseSupplyChecks() {
		List<String> wanted = List.of("minecraft:spruce_stairs#8");
		SimpleContainer stock = new SimpleContainer(3);
		require(!Builder.hasHouseWoodSupply(wanted, stock, null, List.of(), false),
			"a house with no wood supply can still use the stone fallback");
		require(Builder.hasHouseWoodSupply(wanted, stock, null, List.of(), true),
			"an active house supplier keeps the timber palette even after a long stall");
		stock.setItem(0, new ItemStack(Items.SPRUCE_LOG, 4));
		require(Builder.hasHouseWoodSupply(wanted, stock, null, List.of(), false)
			&& Builder.hasHouseWoodSupply(wanted, null, stock, List.of(), false),
			"matching logs in public stock or in construction stock keep the timber palette");
		stock.setItem(0, new ItemStack(Items.OAK_LOG, 4));
		require(!Builder.hasHouseWoodSupply(wanted, stock, null, List.of(), false),
			"an unrelated log species does not indefinitely defer the last-resort fallback");
		stock.setItem(0, new ItemStack(Items.SPRUCE_STAIRS, 4));
		require(Builder.hasHouseWoodSupply(wanted, stock, null, List.of(), false),
			"crafted wood already waiting for a hauler keeps the planned roof material");
		Step wall = Step.block(BlockPos.ZERO, Blocks.COBBLESTONE);
		stock.setItem(0, new ItemStack(Items.GRANITE, 4));
		require(Builder.paymentFor(Blueprints.HOUSE, wall, stock) == null
			&& Builder.paymentFor(Blueprints.STORAGE, wall, stock).item() == Items.GRANITE,
			"a house cannot get a pink spot from unrelated granite stock while utility plans still use it");
		stock.setItem(1, new ItemStack(Items.COBBLESTONE));
		require(Builder.paymentFor(Blueprints.HOUSE, wall, stock).item() == Items.COBBLESTONE,
			"the selected house wall material is requested and paid exactly");
		return 7;
	}

	private static int lookAlikeChecks() {
		int checks = 0;
		require(Supply.lookAlike(Blocks.DEEPSLATE_BRICKS) == Blocks.STONE_BRICKS, "deepslate bricks look like stone bricks"); checks++;
		require(Supply.lookAlike(Blocks.DEEPSLATE_BRICK_STAIRS) == Blocks.STONE_BRICK_STAIRS
			&& Supply.lookAlike(Blocks.DEEPSLATE_BRICK_SLAB) == Blocks.STONE_BRICK_SLAB,
			"deepslate brick shapes keep their shape"); checks++;
		require(Supply.lookAlike(Blocks.COBBLED_DEEPSLATE) == Blocks.COBBLESTONE
			&& Supply.lookAlike(Blocks.POLISHED_DEEPSLATE_SLAB) == Blocks.STONE_SLAB,
			"cobbled and polished deepslate map to cobblestone and stone"); checks++;
		require(Supply.lookAlike(Blocks.BRICKS) == Blocks.STONE_BRICKS && Supply.lookAlike(Blocks.BRICK_STAIRS) == Blocks.STONE_BRICK_STAIRS,
			"fired bricks look like stone bricks"); checks++;
		require(Supply.lookAlike(Blocks.MOSSY_STONE_BRICKS) == Blocks.STONE_BRICKS, "mossy stone bricks fold into stone bricks"); checks++;
		require(Supply.lookAlike(Blocks.COBBLESTONE) == Blocks.COBBLESTONE && Supply.lookAlike(Blocks.SPRUCE_PLANKS) == Blocks.SPRUCE_PLANKS,
			"obtainable blocks are returned unchanged"); checks++;
		require(Supply.lookAlike(Blocks.TERRACOTTA) == null && Supply.lookAlike(Blocks.POLISHED_DEEPSLATE_WALL) == null,
			"blocks without an obtainable counterpart or shape return null (biome fallback applies)"); checks++;
		return checks;
	}

	private static Palette deepslatePalette() {
		return new Palette(Blocks.DEEPSLATE_BRICKS, Blocks.COBBLESTONE, Blocks.SPRUCE_LOG, Blocks.DEEPSLATE_BRICKS,
			Blocks.SPRUCE_PLANKS, Blocks.DEEPSLATE_BRICK_STAIRS, Blocks.DEEPSLATE_BRICK_SLAB, Blocks.GLASS_PANE,
			Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE);
	}

	/** Windows of a utility plan that nobody can glaze become wooden fences (or optional openings); everything else is untouched. */
	private static int fenceWindowChecks() {
		int checks = 0;
		BlockPos o = new BlockPos(0, 64, 0);
		Step cobble = Step.block(o, Blocks.COBBLESTONE);
		Step glass = Step.block(o.east(), Blocks.GLASS_PANE);
		Step fence = Step.block(o.east(2), Blocks.SPRUCE_FENCE);
		Step hay = Step.block(o.east(3), Blocks.HAY_BLOCK);
		Step doneGlass = Step.block(o.east(4), Blocks.GLASS_PANE);
		List<Step> plan = List.of(doneGlass, cobble, glass, fence, hay);
		List<Step> out = Builder.fenceWindows(plan, 1);
		require(out.size() == plan.size() && out.get(0) == doneGlass && out.get(1) == cobble && out.get(3) == fence,
			"steps before the cursor and other blocks stay as they are"); checks++;
		require(out.get(2).item() == Blocks.SPRUCE_FENCE.asItem() && !out.get(2).optional() && out.get(2).cost() == 1
			&& out.get(2).make().apply(Blocks.SPRUCE_FENCE).is(Blocks.SPRUCE_FENCE), "an unpaid glass pane becomes the plan's wooden fence"); checks++;
		require(out.get(4).item() == Blocks.HAY_BLOCK.asItem() && out.get(4).optional(), "a hay bale becomes an optional step"); checks++;
		List<Step> noFence = Builder.fenceWindows(List.of(glass), 0);
		require(noFence.get(0).item() == Blocks.GLASS_PANE.asItem() && noFence.get(0).optional(),
			"without a fence block in the plan the window is an optional opening"); checks++;
		Step fullGlass = Step.block(o.east(5), Blocks.GLASS);
		List<Step> savedStorage = new java.util.ArrayList<>();
		for (int i = 0; i < 266; i++) savedStorage.add(cobble);
		savedStorage.set(155, fullGlass);
		for (int i = 160; i < 164; i++) savedStorage.set(i, fullGlass);
		savedStorage.set(164, fence);
		List<Step> resumed = Builder.fenceWindowsForCheck(savedStorage, 160, 0, 0);
		require(resumed.size() == 266 && resumed.subList(0, 160).equals(savedStorage.subList(0, 160))
			&& resumed.get(160).item() == Blocks.SPRUCE_FENCE.asItem() && resumed.get(163).item() == Blocks.SPRUCE_FENCE.asItem(),
			"a reloaded storage cursor keeps its 160-step placed prefix and substitutes the four unpaid full-glass windows"); checks++;
		List<Step> stocked = Builder.fenceWindowsForCheck(savedStorage, 160, 2, 0);
		require(stocked.get(160).item() == Blocks.GLASS.asItem() && stocked.get(161).item() == Blocks.GLASS.asItem()
			&& stocked.get(162).item() == Blocks.SPRUCE_FENCE.asItem() && stocked.get(155) == fullGlass,
			"full glass already in public stock stays assigned to the first matching windows"); checks++;
		List<Step> fullyStocked = Builder.fenceWindowsForCheck(savedStorage, 160, 4, 0);
		require(fullyStocked.subList(160, 164).stream().allMatch(step -> step.item() == Blocks.GLASS.asItem()),
			"all four paid glass blocks remain glass when the village has four available"); checks++;
		Step costlyGlass = new Step(o.east(6), Blocks.GLASS.asItem(), null, 2, false, null,
			ignored -> Blocks.GLASS.defaultBlockState());
		List<Step> costAllocated = Builder.fenceWindowsForCheck(List.of(costlyGlass, fullGlass, fence), 0, 1, 0);
		require(costAllocated.get(0).item() == Blocks.SPRUCE_FENCE.asItem()
			&& costAllocated.get(1).item() == Blocks.GLASS.asItem(),
			"stock is allocated by each step's cost instead of incorrectly funding a partial block"); checks++;
		List<Step> legacyPaneFallback = Builder.fenceWindowsForCheck(List.of(glass, fullGlass, fence), 0, 0, 0, false);
		require(legacyPaneFallback.get(0).item() == Blocks.SPRUCE_FENCE.asItem()
			&& legacyPaneFallback.get(1).item() == Blocks.GLASS.asItem(),
			"existing pane fallback remains enabled while full-glass substitution stays disabled for sourced/repair paths"); checks++;
		require(Builder.utilityWindowFallbackAllowedForCheck(Blueprints.STORAGE, false, false)
			&& !Builder.utilityWindowFallbackAllowedForCheck(Blueprints.STORAGE, true, false)
			&& !Builder.utilityWindowFallbackAllowedForCheck(Blueprints.STORAGE, false, true)
			&& !Builder.utilityWindowFallbackAllowedForCheck(Blueprints.HOUSE, false, false),
			"the glass-to-fence fallback is limited to generated, non-repair utility plans"); checks++;
		require(Builder.fenceWindows(List.of(cobble, fence), 0).equals(List.of(cobble, fence)), "a plan without windows is unchanged"); checks++;
		de.villagerplus.village.VillageData wishes = new de.villagerplus.village.VillageData();
		wishes.wanted.add("minecraft:spruce_log#3");
		require(!Builder.stoneShort(wishes), "a timber wish list is no stone shortage"); checks++;
		wishes.wanted.add("minecraft:cobblestone_stairs#4");
		require(Builder.stoneShort(wishes), "cobblestone shapes count as a stone shortage (no woodless switch then)"); checks++;
		return checks;
	}

	private static int planChecks() {
		int checks = 0;
		Identifier taiga = Identifier.withDefaultNamespace("taiga");
		Palette raw = deepslatePalette();
		require(raw.unobtainableRoles().toString().contains("base=minecraft:deepslate_bricks")
			&& raw.unobtainableRoles().size() == 4, "the deepslate palette reports exactly its four unobtainable roles: " + raw.unobtainableRoles()); checks++;
		Palette local = Supply.lookAlikePalette(raw, taiga);
		require(local.unobtainableRoles().isEmpty(), "the substitute palette is fully obtainable: " + local.unobtainableRoles()); checks++;
		require(local.wall() == Blocks.STONE_BRICKS && local.base() == Blocks.STONE_BRICKS
			&& local.roofStair() == Blocks.STONE_BRICK_STAIRS && local.roofSlab() == Blocks.STONE_BRICK_SLAB,
			"deepslate roles keep their look: " + local.ids()); checks++;
		require(local.wall2() == Blocks.COBBLESTONE && local.frame() == Blocks.SPRUCE_LOG && local.floor() == Blocks.SPRUCE_PLANKS
			&& local.door() == Blocks.SPRUCE_DOOR && local.fence() == Blocks.SPRUCE_FENCE,
			"already obtainable roles are untouched"); checks++;
		Palette rustic = Supply.lookAlikePalette(raw, taiga, false);
		require(rustic.unobtainableRoles().isEmpty() && rustic.base() == Blocks.COBBLESTONE && rustic.wall() == Blocks.COBBLESTONE
			&& rustic.roofStair() == Blocks.COBBLESTONE_STAIRS && rustic.roofSlab() == Blocks.COBBLESTONE_SLAB,
			"without smelting the stone-brick look-alikes become cobblestone of the same shape: " + rustic.ids()); checks++;
		Palette ownBricks = Supply.lookAlikePalette(new Palette(Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.SPRUCE_LOG,
			Blocks.DEEPSLATE_BRICKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.GLASS_PANE,
			Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE), taiga, false);
		require(ownBricks.wall() == Blocks.STONE_BRICKS && ownBricks.base() == Blocks.COBBLESTONE,
			"roles the village already builds from stay as they are; only the substituted role goes without smelting"); checks++;
		Palette sameAfterSave = Palette.ofIds(local.ids());
		require(sameAfterSave != null && sameAfterSave.ids().equals(local.ids()), "the substitute palette survives the saved palette ids"); checks++;
		Palette idempotent = Supply.obtainablePalette(local, taiga);
		require(idempotent.ids().equals(local.ids()),
			"bit-1 plans re-apply obtainablePalette on restart without changing the substitute palette"); checks++;

		Architect.Design design = Architect.designForPlan(Architect.Kind.GRANARY,
			Architect.withRoleMarkerVersion(Architect.Kind.GRANARY, 0x2A5D1 << 2));
		BlockPos origin = new BlockPos(100, 70, 100);
		List<Step> rawSteps = Architect.build(design, raw, origin, Direction.NORTH);
		require(paidUnobtainable(rawSteps).contains(Blocks.DEEPSLATE_BRICKS.asItem()),
			"the granary built from the deepslate palette asks for deepslate bricks: " + paidUnobtainable(rawSteps)); checks++;
		List<Step> localSteps = Builder.substituteUnobtainableFullBlocks(Architect.build(design, local, origin, Direction.NORTH), 0);
		require(paidUnobtainable(localSteps).isEmpty(), "the granary built from the substitute palette needs nothing unobtainable: "
			+ paidUnobtainable(localSteps)); checks++;
		List<Step> rusticSteps = Builder.substituteUnobtainableFullBlocks(Architect.build(design, rustic, origin, Direction.NORTH), 0);
		require(paidUnobtainable(rusticSteps).isEmpty() && rusticSteps.stream().noneMatch(step -> step.item() == net.minecraft.world.item.Items.STONE_BRICKS),
			"the granary without smelting needs neither unobtainable blocks nor stone bricks"); checks++;
		require(localSteps.size() == rawSteps.size()
			&& java.util.stream.IntStream.range(0, rawSteps.size()).allMatch(i -> rawSteps.get(i).pos().equals(localSteps.get(i).pos())
				&& rawSteps.get(i).cost() == localSteps.get(i).cost()),
			"the substitute plan keeps every position, order and price of the raw plan (cursor stays valid)"); checks++;
		return checks;
	}

	private static java.util.Set<net.minecraft.world.item.Item> paidUnobtainable(List<Step> steps) {
		java.util.Set<net.minecraft.world.item.Item> out = new java.util.LinkedHashSet<>();
		for (Step step : steps) {
			if (step.free() || step.optional() || step.linkedTo() != null || step.cost() <= 0 || step.tag() != null || step.item() == null) continue;
			if (!Supply.obtainable(Block.byItem(step.item()))) out.add(step.item());
		}
		return out;
	}

	private static int fixtureChecks(ServerLevel level, BlockPos bed) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		PoiManager poi = level.getPoiManager();
		boolean manuallyAddedPoi = false;
		boolean manuallyAddedTimberPoi = false;
		BlockPos head = bed.east();
		BlockPos timberBed = bed.east(20);
		BlockPos timberHead = timberBed.east();
		try {
			if (poi.getType(bed).isPresent() || poi.getType(head).isPresent()) {
				throw new IllegalStateException("Palette fallback check needs an unused HOME POI fixture at " + bed.toShortString());
			}
			set(level, before, bed.below(), Blocks.DIRT.defaultBlockState());
			BlockState foot = Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
				.setValue(BedBlock.PART, BedPart.FOOT);
			set(level, before, bed, foot);
			set(level, before, head, foot.setValue(BedBlock.PART, BedPart.HEAD));
			for (int y = 0; y <= 3; y++) {
				for (int x = -4; x <= 4; x++) {
					for (int z = -4; z <= 4; z++) {
						if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
						set(level, before, bed.offset(x, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
					}
				}
			}
			if (poi.getType(bed).isEmpty()) {
				poi.add(bed, BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME));
				manuallyAddedPoi = true;
			}
			Palette detected = Palette.detect(level, bed, null, 0x51E21, bed);
			require(detected.wall() == Blocks.DEEPSLATE_BRICKS && !detected.unobtainableRoles().isEmpty(),
				"Palette.detect takes the deepslate-brick wall of the neighbouring house: " + detected.ids());
			Palette local = Supply.lookAlikePalette(detected, Identifier.withDefaultNamespace("taiga"));
			require(local.wall() == Blocks.STONE_BRICKS && local.unobtainableRoles().isEmpty(),
				"the early palette turns the detected deepslate-brick wall into obtainable stone bricks: " + local.ids());
			set(level, before, timberBed.below(), Blocks.OAK_PLANKS.defaultBlockState());
			set(level, before, timberHead.below(), Blocks.OAK_PLANKS.defaultBlockState());
			set(level, before, timberBed, foot);
			set(level, before, timberHead, foot.setValue(BedBlock.PART, BedPart.HEAD));
			for (int y = 0; y <= 3; y++) {
				for (int x = -2; x <= 2; x++) {
					for (int z = -2; z <= 2; z++) {
						if (Math.abs(x) != 2 && Math.abs(z) != 2) continue;
						Block material = Math.abs(x) == 2 && Math.abs(z) == 2 ? Blocks.STRIPPED_OAK_LOG : Blocks.OAK_PLANKS;
						set(level, before, timberBed.offset(x, y, z), material.defaultBlockState());
					}
				}
			}
			set(level, before, timberBed.above(4), Blocks.SPRUCE_STAIRS.defaultBlockState());
			set(level, before, timberBed.above(5), Blocks.SPRUCE_SLAB.defaultBlockState());
			set(level, before, timberBed.south(2), Blocks.OAK_DOOR.defaultBlockState());
			if (poi.getType(timberBed).isEmpty()) {
				poi.add(timberBed, BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME));
				manuallyAddedTimberPoi = true;
			}
			Palette adjacent = Palette.detectHouse(level, bed, null, 0x51E21, timberBed.east(7));
			require(adjacent.wall() == Blocks.OAK_PLANKS && adjacent.wall2() == Blocks.OAK_PLANKS
				&& adjacent.frame() == Blocks.STRIPPED_OAK_LOG && adjacent.roofStair() == Blocks.SPRUCE_STAIRS
				&& adjacent.roofSlab() == Blocks.SPRUCE_SLAB && adjacent.floor() == Blocks.OAK_PLANKS,
				"a new house gets its walls, posts and roof from the nearer timber home instead of the larger stone shell: " + adjacent.ids());
			Palette coherent = Palette.detect(level, bed, null, 0x51E21, timberBed.east(7));
			require(coherent.wall() == Blocks.OAK_PLANKS && coherent.base() == Blocks.OAK_PLANKS
				&& coherent.frame() == Blocks.STRIPPED_OAK_LOG && coherent.roofStair() == Blocks.SPRUCE_STAIRS,
				"general building palettes use one nearest facade instead of mixing roles from the larger stone house: " + coherent.ids());
			return 4;
		} finally {
			if (manuallyAddedPoi) poi.remove(bed);
			if (manuallyAddedTimberPoi) poi.remove(timberBed);
			for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
			before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
			before.forEach((pos, cell) -> {
				if (cell.blockEntity() == null) return;
				BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
				if (entity != null) level.setBlockEntity(entity);
			});
		}
	}

	private static void set(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos, BlockState state) {
		level.getChunkAt(pos);
		before.computeIfAbsent(pos, p -> {
			BlockEntity entity = level.getBlockEntity(p);
			return new Cell(level.getBlockState(p), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
		});
		level.setBlock(pos, state, 3);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Palette fallback check failed: " + message);
	}
}
