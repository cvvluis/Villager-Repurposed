package de.villagerplus.dev;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Built;
import de.villagerplus.build.HouseDesignCatalog;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.village.Development;
import de.villagerplus.village.VillageExtent;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Focused source-level assertions for stable village housing and remembered footprint behavior. */
public final class DevelopmentChecks {
	private DevelopmentChecks() {}

	/** Isolated G15 assertion for the expanded wall-discovery radius. */
	public static int wallStructureDiscoveryChecks() {
		check(Development.WALL_STRUCTURE_DISCOVERY_RADIUS >= Development.MAX_WALL_RADIUS + 16
			&& Development.WALL_STRUCTURE_DISCOVERY_RADIUS >= de.villagerplus.Config.VILLAGE_RADIUS + 16,
			"wall footprint discovery scans structure starts through the supported radius plus a chunk margin");
		return 1 + expansionPreparationChecks();
	}

	/** Pure eligibility and boundary checks for the persisted outer-ring housing pad. */
	public static int expansionPreparationChecks() {
		int checks=0;
		check(Development.needsExpansionPreparation(2,2,false),"a full two-resident hamlet requests one expansion parcel");checks++;
		check(Development.needsExpansionPreparation(1,2,false),"a real bed deficit requests an expansion parcel");checks++;
		check(!Development.needsExpansionPreparation(2,2,true)
			&& !Development.needsExpansionPreparation(128,128,false)
			&& !Development.needsExpansionPreparation(0,0,false),
			"active construction, empty villages, and capped populations do not start forest clearing");checks++;
		BlockPos bell=new BlockPos(100,70,100);
		check(new BlockPos(100,70,80).equals(Development.expansionParcelCenter(bell,0,0,0,8,88))
			&& new BlockPos(120,70,100).equals(Development.expansionParcelCenter(bell,0,0,1,8,88)),
			"the first saved pad rotates around the bell outside the largest supported house footprint");checks++;
		BlockPos firstParcel=Development.expansionParcelCenter(bell,2,0,0L,0,8,88);
		BlockPos secondParcel=Development.expansionParcelCenter(bell,3,20,1L,1,8,88);
		BlockPos thirdParcel=Development.expansionParcelCenter(bell,4,40,2L,2,8,88);
		check(firstParcel!=null&&secondParcel!=null&&thirdParcel!=null
			&&Math.max(Math.abs(firstParcel.getX()-bell.getX()),Math.abs(firstParcel.getZ()-bell.getZ()))==20
			&&Math.max(Math.abs(secondParcel.getX()-bell.getX()),Math.abs(secondParcel.getZ()-bell.getZ()))==40
			&&Math.max(Math.abs(thirdParcel.getX()-bell.getX()),Math.abs(thirdParcel.getZ()-bell.getZ()))==60,
			"successive required homes advance the persisted outer housing parcel through first, second, and third plots");checks++;
		BlockPos fourthParcel=Development.expansionParcelCenter(bell,5,60,3L,3,8,88);
		BlockPos finalParcel=Development.expansionParcelCenter(bell,5,60,13L,13,8,88);
		check(parcelCornersInsideSearchRadius(bell,fourthParcel,8,Development.EXPANSION_PARCEL_MAX_RADIUS)
			&& parcelCornersInsideSearchRadius(bell,finalParcel,8,Development.EXPANSION_PARCEL_MAX_RADIUS),
			"the fourth and final outer-ring plots keep every footprint corner inside the house-search radius");checks++;
		int innerCount=Development.expansionParcelSideCount(Development.EXPANSION_PARCEL_BASE_COUNT,8,88);
		java.util.List<BlockPos> innerParcels=new java.util.ArrayList<>();
		for(int side=0;side<innerCount;side++) {
			BlockPos inner=Development.expansionParcelCenter(bell,5,60,
				Development.EXPANSION_PARCEL_BASE_COUNT+side,side,8,88);
			if(inner!=null&&parcelCornersInsideSearchRadius(bell,inner,8,Development.EXPANSION_PARCEL_MAX_RADIUS))innerParcels.add(inner);
		}
		check(innerCount==Development.EXPANSION_PARCEL_INNER_RING_DIRECTIONS
			&&innerParcels.size()==innerCount
			&&Development.expansionParcelCenter(bell,5,60,Development.expansionParcelLimit(8,88),0,8,88)==null,
			"a second staggered housing ring adds eight unique in-radius plots after the original fourteen");checks++;
		boolean ringsClear=true;
		for(BlockPos inner:innerParcels) {
			for(int direction=0;direction<Development.EXPANSION_PARCEL_OUTER_RING_DIRECTIONS;direction++) {
				BlockPos outer=Development.expansionParcelCenter(bell,5,60,
					4L+direction,4+direction,8,88);
				ringsClear &= Development.expansionParcelClearOfHome(inner,8,outer,8,Development.EXPANSION_PARCEL_CLEARANCE);
			}
		}
		for(int i=0;i<innerParcels.size();i++)for(int j=i+1;j<innerParcels.size();j++)
			ringsClear &= Development.expansionParcelClearOfHome(innerParcels.get(i),8,innerParcels.get(j),8,Development.EXPANSION_PARCEL_CLEARANCE);
		check(ringsClear,"the inner expansion ring stays clear of every outer-ring pad and its own neighbors");checks++;
		BlockPos clampedParcel=Development.expansionParcelCenter(bell,20,80,0L,0,6,88);
		check(parcelCornersInsideSearchRadius(bell,clampedParcel,6,Development.EXPANSION_PARCEL_MAX_RADIUS),
			"an oversized desired radius is clamped to a parcel that fits the supported house-search area");checks++;
		check(Development.expansionParcelCenter(bell,0,0,0,0,88)==null,
			"invalid parcel footprint sizes are rejected");checks++;
		check(Development.inExpansionParcel(bell,8,bell.offset(8,30,-8))
			&& !Development.inExpansionParcel(bell,8,bell.east(9)),
			"the persisted parcel is an exact 17-by-17 horizontal square");checks++;
		int largestHouseSite=HouseDesignCatalog.presets().stream()
			.mapToInt(preset->HouseDesignCatalog.siteHalf(preset.design(1L))).max().orElse(Integer.MAX_VALUE);
		check(Development.EXPANSION_PARCEL_HALF>=largestHouseSite+1,
			"the cleared pad contains every catalog house plus a block of yard/approach space");checks++;
		check(Development.expansionReliefSupported(70,70)&&Development.expansionReliefSupported(70,71)
			&&Development.expansionReliefSupported(70,72)&&!Development.expansionReliefSupported(70,73)
			&&!Development.expansionReliefSupported(71,70),
			"only flat or up-to-two-block-high natural pads are eligible for levelling");checks++;
		return checks;
	}

	/** Returns the number of passing checks; call from the explicitly gated Checks090 development runner. */
	public static int run(ServerLevel level, VillageRef ref) {
		int checks = 0;
		check(Development.needsHousing(2, 2) && Development.needsHousing(1, 2), "two-person hamlet reserves one house when beds are full or missing"); checks++;
		check(Development.needsHousing(3, 3) && Development.needsHousing(2, 3), "three-person hamlet reserves one house when beds are full or missing"); checks++;
		check(!Development.needsHousing(1, 0) && !Development.needsHousing(500, de.villagerplus.Config.MAX_VILLAGERS),
			"empty and capped villages do not queue housing"); checks++;
		var oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		var oldSchool = ref.data().extra.school;
		try {
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, java.util.Map.of());
			ref.data().extra.school = java.util.Optional.empty();
			check(Builder.nextSpecial(ref, 1) == null, "specialized milestone queue waits until the village has two residents"); checks++;
			check(Blueprints.GRANARY.equals(Builder.nextSpecial(ref, 2)), "two-resident hamlet starts with the granary milestone"); checks++;
			check(Development.deferStorageExpansion(Builder.nextSpecial(ref, 2)),
				"capacity-only storage expansion waits behind the hamlet's first granary milestone"); checks++;
			de.villagerplus.village.Specials.set(ref, Blueprints.GRANARY, ref.bell().north(8));
			check(Blueprints.TOOLSHED.equals(Builder.nextSpecial(ref, 2)), "granary is followed by the tool shed even at two residents"); checks++;
			check(Development.deferStorageExpansion(Builder.nextSpecial(ref, 2)),
				"capacity-only storage expansion cannot interrupt the hamlet's toolshed stage"); checks++;
			de.villagerplus.village.Specials.set(ref, Blueprints.TOOLSHED, ref.bell().north(16));
			check(Blueprints.SCHOOL.equals(Builder.nextSpecial(ref, 2)), "school follows the tool shed in a two-resident hamlet"); checks++;
			check(Development.deferStorageExpansion(Builder.nextSpecial(ref, 2)),
				"capacity-only storage expansion cannot interrupt the hamlet's school stage"); checks++;
			ref.data().extra.school = java.util.Optional.of(ref.bell().north(24));
			check(Blueprints.KITCHEN.equals(Builder.nextSpecial(ref, 2)), "kitchen follows school without a five-resident gate"); checks++;
			check(Development.deferStorageExpansion(Builder.nextSpecial(ref, 2)),
				"capacity-only storage expansion cannot interrupt the hamlet's kitchen stage"); checks++;
			de.villagerplus.village.Specials.set(ref, Blueprints.KITCHEN, ref.bell().north(32));
			check(Builder.nextSpecial(ref, 2) == null, "late railway milestone stays gated after the core hamlet milestones"); checks++;
			check(!Development.deferStorageExpansion(Builder.nextSpecial(ref, 2))
				&& !Development.deferStorageExpansion(Blueprints.STATION),
				"ordinary storage expansion resumes after the early infrastructure chain"); checks++;
		} finally {
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS);
			else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.data().extra.school = oldSchool;
		}
		var villagerType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse("minecraft:villager"));
		Villager firstBuilder = (Villager) villagerType.spawn(level, ref.bell().east(2), EntitySpawnReason.COMMAND);
		Villager secondBuilder = (Villager) villagerType.spawn(level, ref.bell().east(3), EntitySpawnReason.COMMAND);
		if (firstBuilder == null || secondBuilder == null) throw new IllegalStateException("DEVELOPMENT CHECK FAILED: unable to spawn builder fixtures");
		firstBuilder.addTag("vp090_fixture"); secondBuilder.addTag("vp090_fixture"); firstBuilder.setNoAi(true); secondBuilder.setNoAi(true);
		BlockPos adjacentBell = ref.bell().east(40);
		level.setBlock(adjacentBell.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(adjacentBell, net.minecraft.world.level.block.Blocks.BELL.defaultBlockState(), 3);
		var adjacentEntity = (net.minecraft.world.level.block.entity.BellBlockEntity) level.getBlockEntity(adjacentBell);
		var adjacentData = new de.villagerplus.village.VillageData();
		adjacentEntity.setAttached(VillagerPlus.VILLAGE, adjacentData);
		VillageRef adjacentRef = new VillageRef(adjacentBell, adjacentData, adjacentEntity);
		Villager awayMason = (Villager) villagerType.spawn(level, adjacentBell.east(2), EntitySpawnReason.COMMAND);
		if (awayMason == null) throw new IllegalStateException("DEVELOPMENT CHECK FAILED: unable to spawn away mason fixture");
		awayMason.addTag("vp090_fixture"); awayMason.addTag(de.villagerplus.village.Expedition.TAG); awayMason.setNoAi(true);
		var masonProfession = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
			net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "mason"));
		awayMason.setVillagerData(awayMason.getVillagerData().withProfession(level.registryAccess(), masonProfession).withLevel(2));
		de.villagerplus.village.Villages.noteChunk(level.getChunkAt(adjacentBell));
		// Prime both entries, then emulate vanilla changing their meeting point before the cache TTL expires.
		de.villagerplus.village.Villages.find(level, firstBuilder);
		de.villagerplus.village.Villages.find(level, secondBuilder);
		firstBuilder.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
			net.minecraft.core.GlobalPos.of(level.dimension(), ref.bell()));
		secondBuilder.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
			net.minecraft.core.GlobalPos.of(level.dimension(), adjacentBell));
		awayMason.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
			net.minecraft.core.GlobalPos.of(level.dimension(), adjacentBell));
		var firstAssignment = de.villagerplus.village.Villages.find(level, firstBuilder);
		var secondAssignment = de.villagerplus.village.Villages.find(level, secondBuilder);
		var awayAssignment = de.villagerplus.village.Villages.find(level, awayMason);
		check(firstAssignment != null && firstAssignment.bell().equals(ref.bell())
			&& secondAssignment != null && secondAssignment.bell().equals(adjacentRef.bell())
			&& awayAssignment != null && awayAssignment.bell().equals(adjacentRef.bell()),
			"meeting-point changes invalidate cached village assignments"); checks++;
		var firstResidents = de.villagerplus.village.Villages.residents(level, ref, 96, v -> v.entityTags().contains("vp090_fixture"));
		var secondResidents = de.villagerplus.village.Villages.residents(level, adjacentRef, 96, v -> v.entityTags().contains("vp090_fixture"));
		var firstIds = firstResidents.stream().map(Villager::getUUID).collect(java.util.stream.Collectors.toSet());
		var secondIds = secondResidents.stream().map(Villager::getUUID).collect(java.util.stream.Collectors.toSet());
		String assignments = java.util.stream.Stream.of(firstBuilder, secondBuilder, awayMason).map(v -> {
			var assigned = de.villagerplus.village.Villages.find(level, v);
			return v.getUUID() + "@" + v.blockPosition() + "->" + (assigned == null ? "none" : assigned.bell())
				+ " meeting=" + v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT).orElse(null);
		}).collect(java.util.stream.Collectors.joining("; "));
		check(firstIds.equals(java.util.Set.of(firstBuilder.getUUID()))
			&& secondIds.equals(java.util.Set.of(secondBuilder.getUUID(), awayMason.getUUID()))
			&& java.util.Collections.disjoint(firstIds, secondIds),
			"overlapping village radii keep residents assigned to their own bell (A=" + firstResidents.size()
				+ ", B=" + secondResidents.size() + "; IDs A=" + firstIds + ", B=" + secondIds + "; " + assignments + ")"); checks++;
		long now = level.getGameTime();
		boolean firstElected = Builder.bootstrapBuilder(new Ctx(level, firstBuilder, JobManager.state(firstBuilder), firstBuilder.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now));
		boolean secondElected = Builder.bootstrapBuilder(new Ctx(level, secondBuilder, JobManager.state(secondBuilder), secondBuilder.getAttachedOrCreate(VillagerPlus.MEMORY), adjacentRef, now));
		check(firstElected && secondElected, "villages elect an autonomous builder even when their only mason is away on expedition"); checks++;
		firstBuilder.discard(); secondBuilder.discard(); awayMason.discard();
		de.villagerplus.village.Villages.forget(firstBuilder.getUUID()); de.villagerplus.village.Villages.forget(secondBuilder.getUUID()); de.villagerplus.village.Villages.forget(awayMason.getUUID());
		level.setBlock(adjacentBell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(adjacentBell.below(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
		check(Development.needsHousing(21, 21), "stable population fills beds and queues one reserve house"); checks++;
		check(!Development.needsHousing(22, 21), "housing reserve prevents unnecessary house spam"); checks++;
		check(Development.ownsVillageStart(new BoundingBox(-20, 40, -20, 20, 90, 20), BlockPos.ZERO),
			"structure start containing bell is accepted"); checks++;
		check(!Development.ownsVillageStart(new BoundingBox(-20, 40, -20, 20, 90, 20), new BlockPos(21, 40, 0)),
			"adjacent independent structure start is excluded"); checks++;
		checks += wallStructureDiscoveryChecks();

		var oldMines = new java.util.ArrayList<>(ref.data().extra.oldMines);
		var oldMine = ref.data().mine;
		var oldChestPos = ref.data().chest;
		BlockPos exhaustedEntrance = ref.bell().offset(44, 0, 42);
		level.getChunkAt(exhaustedEntrance);
		level.setBlock(exhaustedEntrance.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
		de.villagerplus.village.VillageData.Mine exhausted = new de.villagerplus.village.VillageData.Mine(exhaustedEntrance, net.minecraft.core.Direction.EAST.get2DDataValue(), 20,
			exhaustedEntrance.getY() - 20, true);
			BlockPos stockPos = null;
			for (int radius = 8; radius <= 18 && stockPos == null; radius++) {
				for (int dx = -radius; dx <= radius && stockPos == null; dx++) for (int dz = -radius; dz <= radius && stockPos == null; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					int x = exhaustedEntrance.getX() + dx, z = exhaustedEntrance.getZ() + dz;
					if (!level.hasChunkAt(new BlockPos(x, exhaustedEntrance.getY(), z))) continue;
					int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
					BlockPos candidate = new BlockPos(x, y, z);
					if (level.hasChunkAt(candidate) && level.getBlockState(candidate).isAir()
						&& level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(), net.minecraft.core.Direction.UP)
						&& level.getBlockEntity(candidate) == null) stockPos = candidate;
				}
			}
			if (stockPos == null) throw new IllegalStateException("No empty supported fixture cell for public mine supplies");
			var oldStockState = level.getBlockState(stockPos);
			level.setBlock(stockPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 2);
			var mineStock = de.villagerplus.village.Lager.publicContainerAt(level, stockPos);
			if (mineStock == null) throw new IllegalStateException("Mine fixture public stock chest was not accessible");
			int exposedRoofCells = 0;
			int exposedSurfaceCells = 0;
			for (int section = 0; section < exhausted.index; section++) {
				BlockPos base = exhaustedEntrance.relative(net.minecraft.core.Direction.EAST, section).below(section);
				for (int lane = -1; lane <= 1; lane++) {
					BlockPos roof = base.relative(net.minecraft.core.Direction.SOUTH, lane).above(3);
					level.getChunkAt(roof);
					if (level.canSeeSky(roof) && level.getBlockState(roof).isAir()) exposedRoofCells++;
					if (section > 0) for (int height = 0; height < 3; height++) {
						BlockPos opening = base.relative(net.minecraft.core.Direction.SOUTH, lane).above(height);
						if (opening.getY() < exhaustedEntrance.getY()) continue;
						level.getChunkAt(opening);
						if (level.getBlockState(opening).isAir()) exposedSurfaceCells++;
					}
				}
			}
			int spruceNeeded = 9 + exposedRoofCells + exposedSurfaceCells;
			mineStock.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPRUCE_PLANKS, Math.min(64, spruceNeeded)));
			if (spruceNeeded > 64) mineStock.setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPRUCE_PLANKS, spruceNeeded - 64));
			mineStock.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPRUCE_SIGN, 1));
			ref.data().chest = java.util.Optional.of(stockPos);
			for (int section = 0; section < exhausted.index; section++) {
				BlockPos base = exhaustedEntrance.relative(net.minecraft.core.Direction.EAST, section).below(section);
				for (int lane = -1; lane <= 1; lane++) level.getChunkAt(base.relative(net.minecraft.core.Direction.SOUTH, lane));
			}
		try {
			ref.data().extra.oldMines.add(exhausted);
			ref.data().mine = java.util.Optional.empty();
			de.villagerplus.job.Mining.barricade(level, ref, exhausted, true);
			BlockPos signPos = exhaustedEntrance.above(1).west();
			var sign = level.getBlockEntity(signPos);
			check(sign instanceof net.minecraft.world.level.block.entity.SignBlockEntity,
				"exhausted mine receives a visible sign at the barricaded entrance"); checks++;
			check(mineStock.getItem(0).isEmpty() && mineStock.getItem(1).isEmpty() && mineStock.getItem(2).isEmpty(),
				"visible mine closure spends exactly its planned roof/portal planks and public sign"); checks++;
			if (sign instanceof net.minecraft.world.level.block.entity.SignBlockEntity signEntity) {
				var signText = signEntity.getFrontText();
				check(signText.getMessage(0, false).getString().equals("[VERSCHLOSSEN")
					&& signText.getMessage(1, false).getString().equals("- ERSCHÖPFT]")
					&& signText.getMessage(3, false).getString().equals("Alte Mine") && signEntity.isWaxed(),
					"exhausted mine sign preserves exact status, map label and waxed state"); checks++;
			}
			int[] mapOrigin = de.villagerplus.village.VillageMap.gridOrigin(level, ref);
			int cellSize = de.villagerplus.village.VillageMap.gridCellSize(level, ref);
			int col = Math.floorDiv(exhaustedEntrance.getX() - mapOrigin[0], cellSize);
			int row = Math.floorDiv(exhaustedEntrance.getZ() - mapOrigin[1], cellSize);
			byte[] mapMarks = de.villagerplus.village.VillageMap.grid(level, ref)[1];
			check(col >= 0 && col < de.villagerplus.village.VillageMap.GRID && row >= 0 && row < de.villagerplus.village.VillageMap.GRID
				&& mapMarks[row * de.villagerplus.village.VillageMap.GRID + col] == 8,
				"exhausted mine remains visible as old-mine marker on village map"); checks++;
		} finally {
			ref.data().extra.oldMines.clear();
			ref.data().extra.oldMines.addAll(oldMines);
			ref.data().mine = oldMine;
			ref.data().chest = oldChestPos;
			level.setBlock(stockPos, oldStockState, 2);
			ref.dirty();
		}

		VillageExtent original = new VillageExtent(-10, 40, -8, 12, 90, 9);
		BoundingBox extra = new BoundingBox(-30, 35, 5, 18, 100, 40);
		VillageExtent union = original.union(extra);
		check(union.minX() == -30 && union.minY() == 35 && union.minZ() == -8
			&& union.maxX() == 18 && union.maxY() == 100 && union.maxZ() == 40,
			"remembered footprint union only expands to cover both bounds"); checks++;
		VillageExtent decoded = VillageExtent.CODEC.parse(JsonOps.INSTANCE,
			VillageExtent.CODEC.encodeStart(JsonOps.INSTANCE, union).getOrThrow()).getOrThrow();
		check(decoded.equals(union), "remembered village bounds survive codec round trip"); checks++;

		VillageExtent old = ref.bellEntity().getAttachedOrElse(VillagerPlus.VILLAGE_EXTENT, null);
		try {
			VillageExtent remembered = new VillageExtent(ref.bell().getX() - 500, ref.bell().getY() - 5, ref.bell().getZ() - 400,
				ref.bell().getX() + 500, ref.bell().getY() + 50, ref.bell().getZ() + 400);
			ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT, remembered);
			BoundingBox afterScan = Development.bounds(level, ref);
			check(afterScan.minX() <= remembered.minX() && afterScan.minZ() <= remembered.minZ()
				&& afterScan.maxX() >= remembered.maxX() && afterScan.maxZ() >= remembered.maxZ(),
				"bounds retain previously discovered extent across unload/restart scans"); checks++;
		} finally {
			if (old == null) ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_EXTENT);
			else ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT, old);
		}
		checks += maintainedGrowthBoundsChecks(level, ref);
		checks += constructionProjectVisibilityChecks(level, ref);
		return checks;
	}

	/** G15: prepared outer plots and completed fields extend the saved bounds and the next wall radius. */
	private static int maintainedGrowthBoundsChecks(ServerLevel level, VillageRef ref) {
		var oldExtent = ref.bellEntity().getAttached(VillagerPlus.VILLAGE_EXTENT);
		var oldBuildings = ref.bellEntity().getAttached(VillagerPlus.BUILDINGS);
		var oldRoads = ref.bellEntity().getAttached(VillagerPlus.ROADS);
		BlockPos parcel = ref.bell().offset(79, 0, 31);
		BlockPos field = ref.bell().offset(-142, 0, 12);
		try {
			ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_EXTENT);
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,
				java.util.List.of(new Built(Blueprints.FARM, field, 0, 50, java.util.List.of())));
			// Mirrors the ready Roads cursor (current geometry version) consumed by preparedExpansionParcel.
			ref.bellEntity().setAttached(VillagerPlus.ROADS, java.util.Map.of(
				"vp_expansion:geometry", de.villagerplus.village.Roads.EXPANSION_GEOMETRY_VERSION,
				"vp_expansion:ready", 1L,
				"vp_expansion:x", (long) parcel.getX(),
				"vp_expansion:z", (long) parcel.getZ(),
				"vp_expansion:surface", (long) parcel.getY()));
			ref.dirty();

			BoundingBox expanded = Development.bounds(level, ref);
			int fieldHalf = 7; // half-five crops, the paid fence, and the outermost corner flowers
			check(expanded.minX() <= field.getX() - fieldHalf
				&& expanded.maxX() >= parcel.getX() + Development.EXPANSION_PARCEL_HALF
				&& expanded.maxZ() >= parcel.getZ() + Development.EXPANSION_PARCEL_HALF,
				"a saved outer housing pad and the full completed field footprint expand the village extent");

			int extent = Math.max(Math.max(Math.abs(expanded.minX() - ref.bell().getX()), Math.abs(expanded.maxX() - ref.bell().getX())),
				Math.max(Math.abs(expanded.minZ() - ref.bell().getZ()), Math.abs(expanded.maxZ() - ref.bell().getZ())));
			int requiredRadius = extent + 14;
			int wallRadius = Development.neededWallRadius(level, ref);
			check(requiredRadius > Development.MAX_WALL_RADIUS ? wallRadius == 0
					: wallRadius >= requiredRadius && wallRadius <= Development.MAX_WALL_RADIUS,
				"the wall path covers saved growth or safely refuses growth beyond its bounded radius");

			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, java.util.List.of());
			ref.bellEntity().setAttached(VillagerPlus.ROADS, java.util.Map.of());
			BoundingBox afterSourceRemoval = Development.bounds(level, ref);
			check(afterSourceRemoval.minX() <= field.getX() - fieldHalf
				&& afterSourceRemoval.maxX() >= parcel.getX() + Development.EXPANSION_PARCEL_HALF
				&& afterSourceRemoval.maxZ() >= parcel.getZ() + Development.EXPANSION_PARCEL_HALF,
				"saved village bounds retain growth after the temporary parcel and building sources disappear");
			return 3;
		} finally {
			if (oldBuildings == null) ref.bellEntity().removeAttached(VillagerPlus.BUILDINGS);
			else ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, oldBuildings);
			if (oldRoads == null) ref.bellEntity().removeAttached(VillagerPlus.ROADS);
			else ref.bellEntity().setAttached(VillagerPlus.ROADS, oldRoads);
			if (oldExtent == null) ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_EXTENT);
			else ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT, oldExtent);
			ref.dirty();
		}
	}

	/** Isolated U04 map/status check for multiple active projects and legacy single-plan saves. */
	public static int constructionProjectVisibilityChecks(ServerLevel level, VillageRef ref) {
		int[] origin = de.villagerplus.village.VillageMap.gridOrigin(level, ref);
		int cell = de.villagerplus.village.VillageMap.gridCellSize(level, ref);
		BlockPos firstOrigin = new BlockPos(origin[0] + 6 * cell + 1, ref.bell().getY(), origin[1] + 16 * cell + 1);
		BlockPos secondOrigin = new BlockPos(origin[0] + 30 * cell + 1, ref.bell().getY(), origin[1] + 16 * cell + 1);
		var site = new de.villagerplus.build.ConstructionSite(firstOrigin, java.util.List.of(), java.util.List.of(2, 4, 8), true);
		var first = new de.villagerplus.village.VillageData.BuildProject(Blueprints.SCHOOL, firstOrigin, 0, 3, 0,
			java.util.Optional.empty(), level.getGameTime() - 146_300L, java.util.Optional.empty(), 0,
			java.util.List.of("minecraft:birch_planks#16"),
			de.villagerplus.village.VillageData.ProjectHaul.empty(), java.util.Optional.empty(), java.util.Optional.of(site),
			java.util.Optional.empty(), false, false);
		var second = new de.villagerplus.village.VillageData.BuildProject(Blueprints.TOOLSHED, secondOrigin, 0, 1, 0,
			java.util.Optional.empty(), level.getGameTime(), java.util.Optional.empty(), 0, java.util.List.of(),
			de.villagerplus.village.VillageData.ProjectHaul.empty(), java.util.Optional.of("Werkzeug fehlt"), java.util.Optional.empty(),
			java.util.Optional.empty(), false, false);
		var cleanup = new de.villagerplus.village.VillageData.BuildProject("", secondOrigin.north(8), 0, 0, 0,
			java.util.Optional.empty(), level.getGameTime(), java.util.Optional.empty(), 0, java.util.List.of(),
			de.villagerplus.village.VillageData.ProjectHaul.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
			java.util.Optional.empty(), false, true);
		var multi = new de.villagerplus.village.VillageData();
		multi.putBuildProject(first);
		multi.putBuildProject(second);
		multi.putBuildProject(cleanup);
		VillageRef multiRef = new VillageRef(ref.bell(), multi, ref.bellEntity());
		byte[] marks = de.villagerplus.village.VillageMap.grid(level, multiRef)[1];
		int firstIndex = Math.floorDiv(firstOrigin.getZ() - origin[1], cell) * de.villagerplus.village.VillageMap.GRID
			+ Math.floorDiv(firstOrigin.getX() - origin[0], cell);
		int secondIndex = Math.floorDiv(secondOrigin.getZ() - origin[1], cell) * de.villagerplus.village.VillageMap.GRID
			+ Math.floorDiv(secondOrigin.getX() - origin[0], cell);
		check(marks[firstIndex] == 13 && marks[secondIndex] == 13,
			"map marks each independently active build project and ignores cleanup-only records");
		java.util.List<String> stats = de.villagerplus.village.VillageStats.lines(level, multiRef);
		boolean activeCountShown = stats.stream().anyMatch(line -> line.contains("2 aktive Baustellen"));
		boolean firstCursorShown = stats.stream().anyMatch(line -> line.contains("Schule") && line.contains("Schritt 3/8"));
		boolean secondWaitShown = projectStatusIncludesWait(stats, "Werkzeugschuppen", "Werkzeug fehlt");
		boolean friendlyMaterials = stats.stream().anyMatch(line -> line.startsWith("  Noch zu liefern: ")
			&& line.contains("×16") && !line.contains("minecraft:birch_planks"));
		boolean readablePause = stats.stream().anyMatch(line -> line.equals("  Stillstand: 2 Std. 1 Min. 55 Sek."));
		if (!(activeCountShown && firstCursorShown && secondWaitShown && friendlyMaterials && readablePause)) {
			String visibleBuildStatus = String.join(" || ", stats.stream()
				.filter(line -> line.startsWith("Aktueller Bau:") || line.startsWith("Baustelle")
					|| line.startsWith("  Ort:") || line.startsWith("  Wartet:") || line.startsWith("Bauplan:"))
				.toList());
			throw new IllegalStateException("DEVELOPMENT CHECK FAILED village info lists every active project's cursor and visible wait reason"
				+ " [activeCount=" + activeCountShown + ", firstCursor=" + firstCursorShown + ", secondWait=" + secondWaitShown
				+ ", friendlyMaterials=" + friendlyMaterials + ", readablePause=" + readablePause
				+ "; status=" + visibleBuildStatus + "]");
		}

		var legacy = new de.villagerplus.village.VillageData();
		legacy.planType = Blueprints.SCHOOL;
		legacy.planOrigin = java.util.Optional.of(firstOrigin);
		legacy.planStep = 5;
		VillageRef legacyRef = new VillageRef(ref.bell(), legacy, ref.bellEntity());
		byte[] legacyMarks = de.villagerplus.village.VillageMap.grid(level, legacyRef)[1];
		check(legacyMarks[firstIndex] == 13
			&& de.villagerplus.village.VillageStats.lines(level, legacyRef).stream()
				.anyMatch(line -> line.contains("Schule (Schritt 5)")),
			"pre-project saves keep their legacy single-plan map marker and info status");
		return 5;
	}

	private static boolean projectStatusIncludesWait(java.util.List<String> lines, String projectType, String reason) {
		for (int i = 0; i < lines.size(); i++) {
			if (!lines.get(i).startsWith("Baustelle LIVE: " + projectType + " – ")) continue;
			for (int next = i + 1; next < lines.size() && !lines.get(next).startsWith("Baustelle LIVE:"); next++) {
				if (lines.get(next).equals("  Wartet: " + reason)) return true;
			}
			return false;
		}
		return false;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("DEVELOPMENT CHECK FAILED: " + message);
	}

	private static boolean parcelCornersInsideSearchRadius(BlockPos bell, BlockPos center, int half, int radius) {
		if (bell == null || center == null || half < 1 || radius < 1) return false;
		long maxDistanceSquared = (long) radius * radius;
		for (int dx : new int[] {-half, half}) {
			for (int dz : new int[] {-half, half}) {
				long x = (long) center.getX() + dx - bell.getX();
				long z = (long) center.getZ() + dz - bell.getZ();
				if (x * x + z * z > maxDistanceSquared) return false;
			}
		}
		return true;
	}
}
