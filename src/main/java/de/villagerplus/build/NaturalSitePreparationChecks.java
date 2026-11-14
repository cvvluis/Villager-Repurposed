package de.villagerplus.build;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobState;
import de.villagerplus.village.NaturalSitePreparation;
import de.villagerplus.village.Sites;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Focused in-world regression checks for the persisted residential-lot clearing path. */
public final class NaturalSitePreparationChecks {
	private static final int FLAGS = 2 | 16 | 32;
	private record Cell(BlockState state, CompoundTag blockEntity) { }
	private record FixtureOrigin(BlockPos pos, boolean entityTicking, String reason) { }

	private NaturalSitePreparationChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		FixtureOrigin fixtureOrigin = entityIndexedFixtureOrigin(level, origin);
		origin = fixtureOrigin.pos();
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		List<Entity> fixtures = new ArrayList<>();
		BlockPos bellPos = origin.west(10);
		BlockPos regionMin = origin.offset(-11, -1, -11);
		BlockPos regionMax = origin.offset(11, 14, 11);
		boolean injectedPoi = false;
		BlockPos poiPos = origin.east(2).above();
		Villager worker = null;
		try {
			for (BlockPos pos : BlockPos.betweenClosed(regionMin, regionMax)) {
				BlockPos immutable = pos.immutable();
				level.getChunkAt(immutable);
				BlockEntity blockEntity = level.getBlockEntity(immutable);
				before.put(immutable, new Cell(level.getBlockState(immutable), blockEntity == null ? null
					: blockEntity.saveWithFullMetadata(level.registryAccess())));
				level.removeBlockEntity(immutable);
				level.setBlock(immutable, immutable.getY() == origin.getY() - 1
					? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), FLAGS);
			}
			loadStructureEnvelope(level, origin, 5);
			level.setBlock(bellPos.below(), Blocks.STONE.defaultBlockState(), FLAGS);
			level.setBlock(bellPos, Blocks.BELL.defaultBlockState(), FLAGS);
			if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) {
				throw new IllegalStateException("NATURAL SITE CHECK FAILED: fixture bell block entity missing");
			}
			VillageData data = bell.getAttachedOrCreate(VillagerPlus.VILLAGE);
			VillageRef ref = new VillageRef(bellPos, data, bell);
			int variant = HouseDesignCatalog.newPlanVariant(Architect.SizeTier.COMPACT, 12_345L);
			worker = EntityTypes.VILLAGER.spawn(level, origin.east(10), EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("NATURAL SITE CHECK FAILED: builder fixture did not spawn");
			fixtures.add(worker);
			worker.addTag("vp090_natural_site_fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.getInventory().clearContent();
			data.putBuildProject(new VillageData.BuildProject(Blueprints.HOUSE, origin, Direction.EAST.get2DDataValue(),
				0, variant, Optional.empty(), Math.max(1L, level.getGameTime()), Optional.of(worker.getUUID()), 0L,
				List.of(), VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(),
				Optional.of(new VillageData.PlanBounds(origin.getX() - 8, origin.getX() + 8,
					origin.getZ() - 8, origin.getZ() + 8)), false, false));
			data.syncPrimaryProjectView();
			Ctx context = new Ctx(level, worker, new JobState(), new VillagerMemory(), ref, Math.max(1L, level.getGameTime()));
			int checks = 0;

			NaturalSitePreparation.SitePlan savedPlan = new NaturalSitePreparation.SitePlan(origin, 5, 8, 3, 1024, false);
			var encoded = NaturalSitePreparation.SitePlan.CODEC.encodeStart(JsonOps.INSTANCE, savedPlan).getOrThrow();
			var restoredPlan = NaturalSitePreparation.SitePlan.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
			check(restoredPlan.equals(savedPlan) && restoredPlan.valid(),
				"the bounded lot plan survives codec save/reload with its fixed origin and work limits"); checks++;
			check(NaturalSitePreparation.inspect(level, savedPlan.cancel()).status() == NaturalSitePreparation.Status.CANCELLED,
				"a cancelled persisted lot never begins or resumes clearing"); checks++;

			resetSite(level, origin, 8, 12);
			NaturalSitePreparation.Scan normalSurface = NaturalSitePreparation.inspect(level, savedPlan);
			check(normalSurface.status() == NaturalSitePreparation.Status.READY,
				"a flat natural surface remains eligible after its complete structure-reference envelope is loaded"); checks++;
			BlockPos leaf = origin.above();
			BlockPos snow = leaf.above();
			level.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false), FLAGS);
			level.setBlock(snow, Blocks.SNOW.defaultBlockState(), FLAGS);
			NaturalSitePreparation.Scan snowOnLeaves = NaturalSitePreparation.inspect(level, origin, snow, 8);
			check(snowOnLeaves.status() == NaturalSitePreparation.Status.CLEARABLE && snow.equals(snowOnLeaves.next()),
				"snow on verified natural leaves is cleared before its supporting leaves"); checks++;
			level.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), FLAGS);
			check(NaturalSitePreparation.inspect(level, origin, snow, 8).status() == NaturalSitePreparation.Status.BLOCKED,
				"snow on persistent leaves does not permit clearing the protected foliage"); checks++;
			resetSite(level, origin, 8, 12);
			BlockPos unloadedSite = unloadedSite(level, origin);
			if (unloadedSite != null) {
				NaturalSitePreparation.Scan unloaded = NaturalSitePreparation.inspect(level,
					new NaturalSitePreparation.SitePlan(unloadedSite, 5, 8, 1, 128, false));
				check(unloaded.status() == NaturalSitePreparation.Status.WAITING_FOR_CHUNK && !level.hasChunkAt(unloadedSite),
					"an unloaded structure-reference envelope defers the lot without force-loading its candidate chunk"); checks++;
			} else {
				System.out.println("NATURAL SITE CHECK SKIPPED unloaded-envelope assertion: no unloaded candidate found within the bounded search");
			}

			resetSite(level, origin, 8, 12);
			BlockPos root = origin.east(1);
			placeTestTree(level, root);
			BlockPos snowOnTrunk = root.above(4);
			level.setBlock(snowOnTrunk, Blocks.SNOW.defaultBlockState(), FLAGS);
			NaturalSitePreparation.Scan trunkSnow = NaturalSitePreparation.inspect(level, origin.offset(-5, 0, -5), origin.offset(5, 8, 5), 1024);
			check(trunkSnow.status() == NaturalSitePreparation.Status.CLEARABLE && snowOnTrunk.equals(trunkSnow.next()),
				"snow on a verified in-lot natural trunk is cleared before the trunk"); checks++;
			level.removeBlock(snowOnTrunk, false);
			BlockPos grass = origin.west(1);
			level.setBlock(grass, Blocks.SHORT_GRASS.defaultBlockState(), FLAGS);
			NaturalSitePreparation.Scan beforeClear = Builder.inspectHouseClearance(level, ref, origin, variant, false);
			check(beforeClear.status() == NaturalSitePreparation.Status.CLEARABLE && beforeClear.obstacleCount() >= 6,
				"the saved Builder house plan accepts a rooted forest obstacle and supported grass for safe clearing"); checks++;

			int oakLogsBefore = count(worker, Items.OAK_LOG);
			BlockPos firstTarget = beforeClear.next();
			NaturalSitePreparation.Scan firstAction = Builder.clearHouseClearance(level, ref, origin, variant, false, origin, context);
			check(firstAction.status() == NaturalSitePreparation.Status.CLEARED && level.getBlockState(firstTarget).isAir()
				&& count(worker, Items.OAK_LOG) - oakLogsBefore == 1,
				"a Builder action removes exactly its next natural block and keeps its actual drop in the villager inventory"); checks++;

			var registryOps = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
			var encodedVillage = VillageData.CODEC.encodeStart(registryOps, data).getOrThrow();
			VillageData reloaded = VillageData.CODEC.parse(registryOps, encodedVillage).getOrThrow();
			VillageData.BuildProject resumedProject = reloaded.buildProject(origin).orElseThrow();
			check(resumedProject.origin().equals(origin) && resumedProject.variant() == variant
				&& resumedProject.step() == 0 && level.getBlockState(firstTarget).isAir(),
				"save/reload retains the residential site identity and variant while the cleared world cell is the restart cursor"); checks++;
			data.putBuildProject(resumedProject);
			data.syncPrimaryProjectView();

			int cleared = 1;
			int remainingLogsBefore = count(worker, Items.OAK_LOG);
			int guard = 0;
			while (guard++ < 16) {
				NaturalSitePreparation.Scan inspected = Builder.inspectHouseClearance(level, ref, origin, variant, false);
				if (inspected.status() == NaturalSitePreparation.Status.READY) break;
				if (inspected.status() != NaturalSitePreparation.Status.CLEARABLE) {
					throw new IllegalStateException("NATURAL SITE CHECK FAILED: expected clearable site, got " + inspected.status());
				}
				BlockPos target = inspected.next();
				NaturalSitePreparation.Scan action = Builder.clearHouseClearance(level, ref, origin, variant, false, origin, context);
				if (action.status() != NaturalSitePreparation.Status.CLEARED || !level.getBlockState(target).isAir()) {
					throw new IllegalStateException("NATURAL SITE CHECK FAILED: Builder action did not clear exactly its selected natural block: " + action);
				}
				cleared++;
			}
			check(Builder.inspectHouseClearance(level, ref, origin, variant, false).status() == NaturalSitePreparation.Status.READY
				&& cleared == 6 && count(worker, Items.OAK_LOG) - remainingLogsBefore == 3
				&& level.getBlockState(root).isAir() && level.getBlockState(grass).isAir(),
				"after reload, Builder clears one block per action, retains the remaining trunk drops, then reaches READY for normal house construction"); checks++;

			resetSite(level, origin, 8, 14);
			worker.getInventory().clearContent();
			placeTallTestTree(level, root, 13);
			NaturalSitePreparation.SitePlan boundedLot = new NaturalSitePreparation.SitePlan(origin, 5, 8, 3, 1024, false);
			NaturalSitePreparation.Scan tallStart = NaturalSitePreparation.inspect(level, boundedLot);
			check(tallStart.status() == NaturalSitePreparation.Status.CLEARABLE
				&& tallStart.next().equals(root.above(12)),
				"a verified tree rooted in the reserved lot can be felled top-down even when its trunk continues above construction clearance"); checks++;
			int tallLogsBefore = count(worker, Items.OAK_LOG);
			int tallActions = 0;
			while (tallActions < 20) {
				NaturalSitePreparation.Scan next = NaturalSitePreparation.inspect(level, boundedLot);
				if (next.status() == NaturalSitePreparation.Status.READY) break;
				if (next.status() != NaturalSitePreparation.Status.CLEARABLE) {
					throw new IllegalStateException("NATURAL SITE CHECK FAILED: tall reserved tree did not remain clearable: " + next);
				}
				NaturalSitePreparation.Scan action = NaturalSitePreparation.clearOne(level, boundedLot, worker);
				if (action.status() != NaturalSitePreparation.Status.CLEARED || !level.getBlockState(next.next()).isAir()) {
					throw new IllegalStateException("NATURAL SITE CHECK FAILED: tall reserved tree action failed: " + action);
				}
				tallActions++;
			}
			check(NaturalSitePreparation.inspect(level, boundedLot).status() == NaturalSitePreparation.Status.READY
				&& tallActions == 14 && count(worker, Items.OAK_LOG) - tallLogsBefore == 13
				&& level.getBlockState(root).isAir() && level.getBlockState(root.east().above(12)).is(Blocks.OAK_LEAVES),
				"the bounded tree continuation preserves all real trunk drops, clears the in-lot canopy, and leaves foliage above the reserved work volume alone"); checks++;
			BlockPos outsideRoot = origin.east(6);
			BlockPos overlappingLeaf = outsideRoot.west().above(4);
			for (int dy = 0; dy < 5; dy++) level.setBlock(outsideRoot.above(dy), Blocks.OAK_LOG.defaultBlockState(), FLAGS);
		level.setBlock(overlappingLeaf, Blocks.OAK_LEAVES.defaultBlockState(), FLAGS);
		NaturalSitePreparation.Scan outsideTreeScan = NaturalSitePreparation.inspect(level, boundedLot);
		NaturalSitePreparation.Scan trimmedCanopy = NaturalSitePreparation.clearOne(level, boundedLot, worker);
		check(outsideTreeScan.status() == NaturalSitePreparation.Status.CLEARABLE
				&& outsideTreeScan.next().equals(overlappingLeaf) && trimmedCanopy.status() == NaturalSitePreparation.Status.CLEARED
				&& level.getBlockState(outsideRoot).is(Blocks.OAK_LOG),
				"lot clearance may trim a colliding canopy cell but never follows or removes a tree trunk rooted beyond the reservation"); checks++;

			resetSite(level, origin, 8, 12);
			BlockPos gradeColumn = origin.east(2);
			for (int dy = 0; dy < 3; dy++) level.setBlock(gradeColumn.above(dy), Blocks.DIRT.defaultBlockState(), FLAGS);
			worker.getInventory().clearContent();
			NaturalSitePreparation.Grade threeLayerGrade = NaturalSitePreparation.inspectResidentialGrade(level, origin, 5, 3);
			NaturalSitePreparation.Scan threeLayerStart = Builder.inspectHouseClearance(level, ref, origin, variant, false);
			check(threeLayerGrade.status() == NaturalSitePreparation.Status.READY
				&& threeLayerGrade.lowSurfaceY() == origin.getY() && threeLayerGrade.highSurfaceY() == origin.getY() + 3
				&& threeLayerStart.status() == NaturalSitePreparation.Status.CLEARABLE
				&& gradeColumn.above(2).equals(threeLayerStart.next()),
				"a three-block natural dirt rise anchors the house at the lowest safe surface and selects only its exposed top layer"
					+ " [grade=" + threeLayerGrade + ", scan=" + threeLayerStart + ", surface="
					+ level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						gradeColumn.getX(), gradeColumn.getZ()) + ", top=" + level.getBlockState(gradeColumn.above(2))
					+ ", headroom=" + level.getBlockState(gradeColumn.above(3)) + "]"); checks++;
			NaturalSitePreparation.Scan missingShovel = Builder.clearHouseClearance(level, ref, origin, variant, false, origin, context);
			check(missingShovel.status() == NaturalSitePreparation.Status.WAITING_FOR_TOOL
				&& level.getBlockState(gradeColumn.above(2)).is(Blocks.DIRT),
				"earth removal waits without changing the lot when the assigned builder has no shovel"); checks++;
			worker.getInventory().setItem(0, new ItemStack(Items.WOODEN_SHOVEL));
			int dirtBefore = count(worker, Items.DIRT);
			NaturalSitePreparation.Scan firstEarthCut = Builder.clearHouseClearance(level, ref, origin, variant, false, origin, context);
			check(firstEarthCut.status() == NaturalSitePreparation.Status.CLEARED
				&& level.getBlockState(gradeColumn.above(2)).isAir()
				&& level.getBlockState(gradeColumn.above()).is(Blocks.DIRT)
				&& count(worker, Items.DIRT) == dirtBefore + 1,
				"one shovel action removes exactly the exposed natural layer and stores its real dirt drop on the worker"); checks++;

			var leveledVillage = VillageData.CODEC.parse(registryOps,
				VillageData.CODEC.encodeStart(registryOps, data).getOrThrow()).getOrThrow();
			data.putBuildProject(leveledVillage.buildProject(origin).orElseThrow());
			data.syncPrimaryProjectView();
			int dirtAfterSave = count(worker, Items.DIRT);
			for (int layer = 1; layer < 3; layer++) {
				NaturalSitePreparation.Scan resumed = Builder.inspectHouseClearance(level, ref, origin, variant, false);
				check(resumed.status() == NaturalSitePreparation.Status.CLEARABLE
					&& resumed.next().equals(gradeColumn.above(2 - layer)),
					"the next earth layer is reconstructed from the remaining world after project save/reload"); checks++;
				NaturalSitePreparation.Scan action = Builder.clearHouseClearance(level, ref, origin, variant, false, origin, context);
				if (action.status() != NaturalSitePreparation.Status.CLEARED || !level.getBlockState(resumed.next()).isAir()) {
					throw new IllegalStateException("NATURAL SITE CHECK FAILED: resumed shovel action did not clear its selected layer: " + action);
				}
			}
			check(Builder.inspectHouseClearance(level, ref, origin, variant, false).status() == NaturalSitePreparation.Status.READY
				&& count(worker, Items.DIRT) == dirtAfterSave + 2
				&& level.getBlockState(gradeColumn.below()).is(Blocks.GRASS_BLOCK),
				"after reload, two more shovel actions flatten the lot, retain every dirt drop, and leave the lowest natural floor intact"); checks++;

			resetSite(level, origin, 8, 12);
			BlockPos fourthLayerColumn = origin.east(2);
			for (int dy = 0; dy < 4; dy++) level.setBlock(fourthLayerColumn.above(dy), Blocks.DIRT.defaultBlockState(), FLAGS);
			NaturalSitePreparation.Scan tooSteep = Builder.inspectHouseClearance(level, ref, origin, variant, false);
			NaturalSitePreparation.Scan rejectedCut = Builder.clearHouseClearance(level, ref, origin, variant, false, origin, context);
			check(tooSteep.status() == NaturalSitePreparation.Status.BLOCKED
				&& rejectedCut.status() == NaturalSitePreparation.Status.BLOCKED
				&& java.util.stream.IntStream.range(0, 4).allMatch(dy -> level.getBlockState(fourthLayerColumn.above(dy)).is(Blocks.DIRT)),
				"a fourth natural earth layer rejects both plot inspection and shovel work without moving or removing any block"); checks++;

			resetSite(level, origin, 8, 12);
			BlockPos houseBlock = origin.above();
			level.setBlock(houseBlock, Blocks.OAK_PLANKS.defaultBlockState(), FLAGS);
			check(Builder.inspectHouseClearance(level, ref, origin, variant, false).status() == NaturalSitePreparation.Status.BLOCKED
				&& level.getBlockState(houseBlock).is(Blocks.OAK_PLANKS),
				"existing house material is protected and never counted as natural clearance"); checks++;

			resetSite(level, origin, 8, 12);
			BlockPos chest = origin.north(2).above();
			level.setBlock(chest, Blocks.CHEST.defaultBlockState(), FLAGS);
			check(level.getBlockEntity(chest) != null
				&& Builder.inspectHouseClearance(level, ref, origin, variant, false).status() == NaturalSitePreparation.Status.BLOCKED
				&& level.getBlockState(chest).is(Blocks.CHEST),
				"a chest block entity stays protected as a possible private-storage container"); checks++;

			resetSite(level, origin, 8, 12);
			var poiManager = level.getPoiManager();
			if (poiManager.getType(poiPos).isEmpty()) {
				poiManager.add(poiPos, BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(
					net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME));
				injectedPoi = true;
			}
			check(Builder.inspectHouseClearance(level, ref, origin, variant, false).status() == NaturalSitePreparation.Status.BLOCKED,
				"a HOME point of interest prevents residential-lot clearing at its occupied position"); checks++;
			if (injectedPoi) {
				poiManager.remove(poiPos);
				injectedPoi = false;
			}

			resetSite(level, origin, 8, 12);
			BlockPos road = origin.south(2).below();
			level.setBlock(road, Blocks.DIRT_PATH.defaultBlockState(), FLAGS);
			check(Builder.inspectHouseClearance(level, ref, origin, variant, false).status() == NaturalSitePreparation.Status.BLOCKED
				&& level.getBlockState(road).is(Blocks.DIRT_PATH),
				"a village road remains untouched and makes the lot unsuitable"); checks++;

			resetSite(level, origin, 8, 12);
			placeTestTree(level, root);
			NaturalSitePreparation.Scan occupiedTarget = Builder.inspectHouseClearance(level, ref, origin, variant, false);
			if (fixtureOrigin.entityTicking() && occupiedTarget.status() == NaturalSitePreparation.Status.CLEARABLE) {
				// A server spatial query can only validate this assertion when the fixture chunk is entity-ticking.
				var blocker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
				if (blocker != null) {
					blocker.setNoAi(true);
					blocker.setInvulnerable(true);
					blocker.setPos(occupiedTarget.next().getX() + 0.5, occupiedTarget.next().getY(), occupiedTarget.next().getZ() + 0.5);
					if (level.addFreshEntity(blocker)) {
						fixtures.add(blocker);
						boolean blockerIndexed = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
							new net.minecraft.world.phys.AABB(occupiedTarget.next()), Entity::isAlive).contains(blocker);
						if (blockerIndexed) {
							NaturalSitePreparation.Scan waiting = Builder.clearHouseClearance(level, ref, origin, variant, false, origin, context);
							check(waiting.status() == NaturalSitePreparation.Status.WAITING_FOR_ENTITY
								&& !level.getBlockState(occupiedTarget.next()).isAir(),
								"a living entity in the next tree cell pauses the Builder without removing its block [target="
									+ occupiedTarget.next() + ", waiting=" + waiting.status() + ", block=" + level.getBlockState(occupiedTarget.next())
									+ ", blocker=" + blocker.blockPosition() + ", alive=" + blocker.isAlive() + ", indexed=true]"); checks++;
						} else {
							var fixtureChunk = level.getChunkSource().getChunkNow(occupiedTarget.next().getX() >> 4,
								occupiedTarget.next().getZ() >> 4);
							System.out.println("NATURAL SITE CHECK SKIPPED living-entity AABB assertion: blocker was not queryable in entity-ticking fixture chunk"
								+ " [target=" + occupiedTarget.next() + ", blocker=" + blocker.blockPosition() + ", chunk="
								+ (fixtureChunk == null ? "UNLOADED" : fixtureChunk.getFullStatus()) + "]");
						}
						blocker.discard();
						fixtures.remove(blocker);
					} else {
						blocker.discard();
						System.out.println("NATURAL SITE CHECK SKIPPED living-entity AABB assertion: server rejected its blocker entity");
					}
				} else {
					System.out.println("NATURAL SITE CHECK SKIPPED living-entity AABB assertion: could not create blocker entity");
				}
			} else {
				String reason = !fixtureOrigin.entityTicking() ? fixtureOrigin.reason()
					: "no clearable tree target (scan=" + occupiedTarget.status() + ")";
				System.out.println("NATURAL SITE CHECK SKIPPED living-entity AABB assertion: " + reason);
			}

			resetSite(level, origin, 8, 12);
			placeTestTree(level, root);
			worker.getInventory().clearContent();
			for (int slot = 0; slot < worker.getInventory().getContainerSize(); slot++) {
				worker.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			}
			NaturalSitePreparation.Scan fullTarget = Builder.inspectHouseClearance(level, ref, origin, variant, false);
			NaturalSitePreparation.Scan inventoryWait = Builder.clearHouseClearance(level, ref, origin, variant, false, origin, context);
			check(fullTarget.status() == NaturalSitePreparation.Status.CLEARABLE
				&& inventoryWait.status() == NaturalSitePreparation.Status.WAITING_FOR_INVENTORY
				&& !level.getBlockState(fullTarget.next()).isAir(),
				"when trunk drops do not fit, the Builder leaves the tree in place instead of losing material"); checks++;
			return checks;
		} finally {
			for (Entity fixture : fixtures) if (fixture != null && fixture.isAlive()) fixture.discard();
			if (injectedPoi) level.getPoiManager().remove(poiPos);
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), FLAGS));
			before.forEach((pos, cell) -> {
				if (cell.blockEntity() == null) return;
				BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			});
		}
	}

	/** Uses a nearby entity-ticking chunk because the server spatial index excludes remote loaded-only chunks. */
	private static FixtureOrigin entityIndexedFixtureOrigin(ServerLevel level, BlockPos requested) {
		int fixtureY = Math.max(level.getMinY() + 1, Math.min(64, level.getMaxY() - 16));
		for (var player : level.players()) {
			if (!player.isAlive()) continue;
			var playerChunk = new net.minecraft.world.level.ChunkPos(
				player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
			for (int radius = 3; radius >= 1; radius--) {
				for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					var candidateChunk = new net.minecraft.world.level.ChunkPos(playerChunk.x() + dx, playerChunk.z() + dz);
					var chunk = level.getChunkSource().getChunkNow(candidateChunk.x(), candidateChunk.z());
					if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)) continue;
					BlockPos candidate = new BlockPos(candidateChunk.getMiddleBlockX(), fixtureY, candidateChunk.getMiddleBlockZ());
					BlockPos target = candidate.east(1).above(3);
					if (level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
						new net.minecraft.world.phys.AABB(target), Entity::isAlive).isEmpty()) {
						return new FixtureOrigin(candidate, true, "entity-ticking chunk selected at " + candidateChunk);
					}
				}
			}
			var playerChunkLevel = level.getChunkSource().getChunkNow(playerChunk.x(), playerChunk.z());
			if (playerChunkLevel != null && playerChunkLevel.getFullStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
				BlockPos candidate = new BlockPos(playerChunk.getMiddleBlockX(), fixtureY, playerChunk.getMiddleBlockZ());
				BlockPos target = candidate.east(1).above(3);
				if (level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
					new net.minecraft.world.phys.AABB(target), Entity::isAlive).isEmpty()) {
					return new FixtureOrigin(candidate, true, "player chunk is entity-ticking");
				}
			}
		}
		var requestedChunk = level.getChunkSource().getChunkNow(requested.getX() >> 4, requested.getZ() >> 4);
		if (requestedChunk != null && requestedChunk.getFullStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
			return new FixtureOrigin(new BlockPos(requested.getX(), fixtureY, requested.getZ()), true,
				"requested fixture chunk is entity-ticking");
		}
		return new FixtureOrigin(requested, false,
			level.players().isEmpty()
				? "no connected player and requested chunk is not entity-ticking"
				: "no suitable nearby entity-ticking chunk for the living blocker");
	}

	private static void resetSite(ServerLevel level, BlockPos origin, int half, int height) {
		for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) {
			for (int dy = 0; dy <= height; dy++) {
				BlockPos pos = origin.offset(dx, dy, dz);
				level.removeBlockEntity(pos);
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
			}
			BlockPos ground = origin.offset(dx, -1, dz);
			level.setBlock(ground, Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
		}
	}

	/** Test-only loading: production lot inspection must never request an unloaded structure-reference chunk. */
	private static void loadStructureEnvelope(ServerLevel level, BlockPos center, int half) {
		int minX = center.getX() - half - Sites.HOUSE_MARGIN;
		int maxX = center.getX() + half + Sites.HOUSE_MARGIN;
		int minZ = center.getZ() - half - Sites.HOUSE_MARGIN;
		int maxZ = center.getZ() + half + Sites.HOUSE_MARGIN;
		for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
				level.getChunkAt(new BlockPos((chunkX << 4) + 8, center.getY(), (chunkZ << 4) + 8));
			}
		}
	}

	private static BlockPos unloadedSite(ServerLevel level, BlockPos origin) {
		for (int offset = 512; offset <= 16_384; offset *= 2) {
			BlockPos candidate = origin.offset(offset, 0, offset).immutable();
			if (!level.hasChunkAt(candidate)) return candidate;
		}
		return null;
	}

	private static void placeTestTree(ServerLevel level, BlockPos root) {
		for (int dy = 0; dy < 4; dy++) level.setBlock(root.above(dy), Blocks.OAK_LOG.defaultBlockState(), FLAGS);
		level.setBlock(root.east().above(4), Blocks.OAK_LEAVES.defaultBlockState(), FLAGS);
	}

	private static void placeTallTestTree(ServerLevel level, BlockPos root, int height) {
		for (int dy = 0; dy < height; dy++) level.setBlock(root.above(dy), Blocks.OAK_LOG.defaultBlockState(), FLAGS);
		level.setBlock(root.east().above(height - 1), Blocks.OAK_LEAVES.defaultBlockState(), FLAGS);
		level.setBlock(root.west().above(7), Blocks.OAK_LEAVES.defaultBlockState(), FLAGS);
	}

	private static int count(Villager villager, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			ItemStack stack = villager.getInventory().getItem(slot);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("NATURAL SITE CHECK FAILED: " + message);
	}
}
