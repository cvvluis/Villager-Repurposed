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
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
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
		checks += fixtureChecks(level, bed);
		return checks;
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
		BlockPos head = bed.east();
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
			return 2;
		} finally {
			if (manuallyAddedPoi) poi.remove(bed);
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
