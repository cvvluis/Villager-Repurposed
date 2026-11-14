package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Built;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Market;
import de.villagerplus.village.MarketData;
import de.villagerplus.village.Roads;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Focused in-world checks for spacing of automatically placed lights and repair of short path gaps. */
public final class LightingSpacingChecks {
	private LightingSpacingChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (int dx = -28; dx <= 28; dx++) for (int dz = -28; dz <= 28; dz++)
			for (int dy = -8; dy <= 8; dy++) {
				BlockPos pos = origin.offset(dx, dy, dz).immutable();
				level.getChunkAt(pos);
				BlockEntity entity = level.getBlockEntity(pos);
				before.put(pos, new Cell(level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
			}
		for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) {
			if (isLight(entry.getValue().state())) level.setBlock(entry.getKey(), Blocks.AIR.defaultBlockState(), 3);
		}
		var worker = EntityTypes.VILLAGER.spawn(level, origin, EntitySpawnReason.COMMAND);
		worker.setNoAi(true);
		worker.setInvulnerable(true);
		worker.addTag("vp090_fixture");
		MarketData oldMarket = null;
		Map<String, Long> oldRoads = null;
		List<ZonePolicy.Zone> oldZones = null;
		List<Built> oldBuildings = null;
		Map<String, BlockPos> oldSpecials = null;
		Container publicStock = null;
		List<ItemStack> oldStock = null;
		Container communalStock = null;
		List<ItemStack> oldCommunalStock = null;
		VillageRef ref = null;
		try {
			ref = Checks090.fixtureForEquipment(level, origin);
			oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
			Specials.set(ref, Blueprints.TOOLSHED, origin.immutable());
			// Tools belong in the public tool shed; common material chests intentionally reject tools.
			BlockPos testStockPos = origin.offset(8, 0, 8);
			level.getChunkAt(testStockPos);
			level.removeBlockEntity(testStockPos);
			level.setBlock(testStockPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(testStockPos, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(testStockPos, Blocks.CHEST.defaultBlockState(), 3);
			oldMarket = Market.of(ref);
			oldRoads = ref.bellEntity().getAttached(VillagerPlus.ROADS);
			oldZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
			oldBuildings = ref.bellEntity().getAttached(VillagerPlus.BUILDINGS);
			for (BlockPos center : List.of(origin, origin.west(24), origin.east(Lighting.MIN_LIGHT_SPACING), origin.offset(1, 0, 1)))
				loadSpacingRegion(level, center, Lighting.MIN_LIGHT_SPACING);
			publicStock = Lager.publicContainerAt(level, testStockPos);
			if (publicStock == null) throw new IllegalStateException("Path repair fixture needs public village storage");
			if (Specials.locatedContainers(level, ref, Blueprints.TOOLSHED).stream().noneMatch(source -> source.pos().equals(testStockPos)))
				throw new IllegalStateException("Path repair fixture needs the public shovel chest registered inside the tool shed");
			oldStock = new ArrayList<>();
			for (int slot = 0; slot < publicStock.getContainerSize(); slot++) {
				oldStock.add(publicStock.getItem(slot).copy());
			}
			publicStock.setChanged();
			communalStock = ref.chest(level);
			if (communalStock == null) throw new IllegalStateException("Path repair fixture needs communal material storage");
			oldCommunalStock = new ArrayList<>();
			for (int slot = 0; slot < communalStock.getContainerSize(); slot++) {
				ItemStack stored = communalStock.getItem(slot);
				oldCommunalStock.add(stored.copy());
				if (stored.is(Items.DIRT) || isShovel(stored)) communalStock.setItem(slot, ItemStack.EMPTY);
			}
			communalStock.setChanged();
			int checks = 0;
			int lightSpacing = Lighting.MIN_LIGHT_SPACING;
			check(Lighting.spacingAreaLoaded(15, 0, lightSpacing, (chunkX, chunkZ) -> true),
				"spacing accepts a fully known " + lightSpacing + "-block disk");
			checks++;
			check(!Lighting.spacingAreaLoaded(15, 0, lightSpacing, (chunkX, chunkZ) -> chunkX != 2 || chunkZ != 0),
				"an unloaded chunk intersecting the spacing disk defers placement without loading it");
			checks++;
			check(Lighting.spacingAreaLoaded(15, 0, lightSpacing, (chunkX, chunkZ) -> chunkX != 5 || chunkZ != 0),
				"an unloaded chunk outside the circular spacing disk does not block placement");
			checks++;
			BlockPos nearby = origin.east(lightSpacing - 1);
			capture(level, before, nearby);
			level.setBlock(nearby, Blocks.LANTERN.defaultBlockState(), 3);
			if (Lighting.spaced(level, origin)) throw new IllegalStateException("Lighting spacing: sub-minimum lantern gap was accepted");
			checks++;
			level.setBlock(nearby, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(origin.offset(8, 8, 0), Blocks.LANTERN.defaultBlockState(), 3);
			if (Lighting.spaced(level, origin)) throw new IllegalStateException("Lighting spacing: elevated hillside lantern was ignored");
			checks++;
			level.setBlock(origin.offset(8, 8, 0), Blocks.AIR.defaultBlockState(), 3);
			BlockPos steepLight = origin.offset(16, 9, 0);
			capture(level, before, steepLight);
			level.setBlock(steepLight, Blocks.LANTERN.defaultBlockState(), 3);
			check(!Lighting.spaced(level, origin),
				"a lantern nine blocks higher on a traversable hill still blocks a candidate only sixteen blocks away");
			checks++;
			level.setBlock(steepLight, Blocks.AIR.defaultBlockState(), 3);
			BlockPos highPostBase = origin.offset(8, 7, 0);
			Map<BlockPos, Cell> highPostBefore = new LinkedHashMap<>();
			for (BlockPos pos : List.of(highPostBase.below(), highPostBase, highPostBase.above(), highPostBase.above(2))) {
				capture(level, before, pos);
				BlockEntity entity = level.getBlockEntity(pos);
				highPostBefore.put(pos.immutable(), new Cell(level.getBlockState(pos),
					entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
			}
			level.setBlock(highPostBase.below(), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(highPostBase, Blocks.SPRUCE_LOG.defaultBlockState(), 3);
			level.setBlock(highPostBase.above(), Blocks.SPRUCE_LOG.defaultBlockState(), 3);
			level.setBlock(highPostBase.above(2), Blocks.LANTERN.defaultBlockState(), 3);
			check(!Lighting.spaced(level, origin, origin.below(2)),
				"road-lamp spacing still detects a nearby lantern nine blocks above on a steep grade");
			checks++;
			restore(level, highPostBefore);
			BlockPos highPostLantern = highPostBase.above(2);
			if (isLight(level.getBlockState(highPostLantern)))
				level.setBlock(highPostLantern, Blocks.AIR.defaultBlockState(), 3);
			BlockPos minimumGap = origin.east(lightSpacing);
			capture(level, before, minimumGap);
			level.setBlock(minimumGap, Blocks.LANTERN.defaultBlockState(), 3);
			if (!Lighting.spaced(level, origin)) throw new IllegalStateException("Lighting spacing: exact minimum was rejected");
			checks++;
			level.setBlock(minimumGap, Blocks.AIR.defaultBlockState(), 3);
			if (!Lighting.spaced(level, origin.west(24))) throw new IllegalStateException("Lighting spacing: distant site was blocked");
			checks++;
			BlockPos searchTarget = origin.offset(1, 0, 1);
			level.setBlock(searchTarget.below(), Blocks.STONE.defaultBlockState(), 3);
			for (int dy = 0; dy <= 2; dy++) level.setBlock(searchTarget.above(dy), Blocks.AIR.defaultBlockState(), 3);
			BlockPos nearSearchLight = searchTarget.east(lightSpacing - 1);
			capture(level, before, nearSearchLight);
			level.setBlock(nearSearchLight, Blocks.LANTERN.defaultBlockState(), 3);
			check(!searchTarget.equals(Lighting.findDark(level, searchTarget, 0)),
				"the cached local search skips a candidate one block inside the configured minimum");
			checks++;
			level.setBlock(nearSearchLight, Blocks.AIR.defaultBlockState(), 3);
			BlockPos exactSearchLight = searchTarget.east(lightSpacing);
			capture(level, before, exactSearchLight);
			level.setBlock(exactSearchLight, Blocks.LANTERN.defaultBlockState(), 3);
			check(searchTarget.equals(Lighting.findDark(level, searchTarget, 0)),
				"the cached local search accepts a candidate at the exact configured minimum");
			checks++;
			level.setBlock(exactSearchLight, Blocks.AIR.defaultBlockState(), 3);
			BlockPos steepSearchLight = searchTarget.offset(16, 9, 0);
			capture(level, before, steepSearchLight);
			level.setBlock(steepSearchLight, Blocks.LANTERN.defaultBlockState(), 3);
			check(!searchTarget.equals(Lighting.findDark(level, searchTarget, 0)),
				"findDark rejects a short hillside placement even when the existing light is more than eight blocks higher");
			checks++;
			level.setBlock(steepSearchLight, Blocks.AIR.defaultBlockState(), 3);

			// The remembered road corridor must include the repair. This prevents converting a garden or random dirt gap.
			Market.save(ref, new MarketData(Optional.of(origin.west(10)), oldMarket.shops(), oldMarket.nextPlan(), oldMarket.sales()));
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of("center", 11L));
			for (int dx = -10; dx <= -1; dx++) {
				BlockPos ground = origin.offset(dx, -1, 0);
				level.setBlock(ground.below(), Blocks.STONE.defaultBlockState(), 3);
				boolean path = dx <= -9 || dx >= -7;
				level.setBlock(ground, path ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 3);
				level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(ground.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			BlockPos candidate = origin.offset(-8, -1, 0);
			check(Roads.trustedVillagePathCell(level, ref, candidate)
				&& Lighting.findVillagePathGap(level, ref, 12).equals(candidate),
				"the single missing cell inside the remembered village road corridor is selected");
			checks++;
			for (int dx = -9; dx <= -5; dx++) {
				BlockPos ground = origin.offset(dx, -1, 0);
				level.setBlock(ground, dx == -9 || dx == -5 ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 3);
			}
			check(Lighting.recognizedVillagePathGap(level, ref, origin.offset(-7, -1, 0)),
				"a three-cell gap in the remembered road corridor is recognized");
			checks++;

			// The saved plaza-to-bell path can extend beyond the local twelve-block work scan.
			Market.save(ref, new MarketData(Optional.of(origin.west(20)), oldMarket.shops(), oldMarket.nextPlan(), oldMarket.sales()));
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of("center", 21L));
			for (int dx = -20; dx <= -1; dx++) {
				BlockPos ground = origin.offset(dx, -1, 0);
				level.setBlock(ground.below(), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(ground, dx == -15 ? Blocks.DIRT.defaultBlockState() : Blocks.DIRT_PATH.defaultBlockState(), 3);
				level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(ground.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			BlockPos distantGap = origin.offset(-15, -1, 0);
			BlockPos distantFound = Lighting.findVillagePathGap(level, ref, 12, worker);
			check(Roads.trustedVillagePathCell(level, ref, distantGap)
				&& Roads.savedVillagePathGrounds(level, ref).contains(distantGap)
				&& distantGap.equals(distantFound),
				"the lamplighter finds a walkable natural-soil gap on the saved route beyond twelve blocks from the bell");
			checks++;
			BlockPos unsavedGap = origin.offset(-3, -1, 0);
			level.setBlock(distantGap, Blocks.DIRT_PATH.defaultBlockState(), 3);
			level.setBlock(unsavedGap, Blocks.DIRT.defaultBlockState(), 3);
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of("center", 16L));
			check(!Roads.trustedVillagePathCell(level, ref, unsavedGap)
				&& !Roads.savedVillagePathGrounds(level, ref).contains(unsavedGap)
				&& Lighting.findVillagePathGap(level, ref, 12, worker) == null,
				"a walkable gap beyond the persisted built cursor is not treated as a saved village path cell");
			checks++;
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of("center", 21L));
			level.setBlock(distantGap, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(unsavedGap, Blocks.DIRT_PATH.defaultBlockState(), 3);
			ItemStack distantShovel = new ItemStack(Items.WOODEN_SHOVEL);
			worker.getInventory().setItem(0, distantShovel);
			worker.getInventory().setItem(1, new ItemStack(Items.DIRT));
			worker.teleportTo(distantGap.getX() + 0.5, distantGap.getY() + 1.0, distantGap.getZ() + 0.5);
			int distantDamageBefore = distantShovel.getDamageValue();
			Ctx distantContext = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY),
				ref, level.getGameTime() + 5);
			check(Lighting.repairVillagePath(distantContext, ref) && level.getBlockState(distantGap).is(Blocks.DIRT_PATH)
				&& distantShovel.getDamageValue() == distantDamageBefore + 1
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
				"the lamplighter closes that distant saved-path gap using and consuming carried Dirt");
			checks++;
			worker.getInventory().clearContent();
			Market.save(ref, new MarketData(Optional.of(origin.west(10)), oldMarket.shops(), oldMarket.nextPlan(), oldMarket.sales()));
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of("center", 11L));
			for (int dx = -10; dx <= -1; dx++) {
				BlockPos ground = origin.offset(dx, -1, 0);
				level.setBlock(ground, dx <= -9 || dx >= -7 ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 3);
			}

			level.setBlock(candidate, Blocks.FARMLAND.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathGap(level, ref, candidate), "a field cell is never converted into a road");
			checks++;
			level.setBlock(candidate, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(candidate, Blocks.STONE.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathGap(level, ref, candidate)
				&& level.getBlockState(candidate).is(Blocks.STONE),
				"a hard surface is never excavated or replaced to repair a road");
			checks++;
			level.setBlock(candidate, Blocks.DIRT.defaultBlockState(), 3);
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(new ZonePolicy.Zone("test", "work",
				candidate.getX(), candidate.getZ(), candidate.getX(), candidate.getZ())));
			check(!Lighting.recognizedVillagePathGap(level, ref, candidate), "a claimed village zone is never edited");
			checks++;
			if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,
				List.of(new Built(Blueprints.HOUSE, candidate, 0, 0, List.of())));
			check(!Lighting.recognizedVillagePathGap(level, ref, candidate),
				"a registered building footprint is never edited for a path");
			checks++;
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, oldBuildings == null ? List.of() : oldBuildings);

			ItemStack shovel = new ItemStack(Items.WOODEN_SHOVEL);
			worker.getInventory().setItem(0, shovel);
			worker.teleportTo(candidate.getX() + 0.5, candidate.getY() + 1.0, candidate.getZ() + 0.5);
			check(!Lighting.recognizedVillagePathGap(level, ref, candidate),
				"an occupied two-block walking clearance is not treated as empty");
			checks++;
			Ctx context = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + 1);
			BlockPos chestPos = candidate.above();
			level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
			var chest = (net.minecraft.world.Container) level.getBlockEntity(chestPos);
			chest.setItem(0, new ItemStack(Items.BREAD, 3));
			check(!Lighting.recognizedVillagePathGap(level, ref, candidate) && !Lighting.repairVillagePath(context, ref)
				&& level.getBlockState(chestPos).is(Blocks.CHEST) && chest.getItem(0).getCount() == 3,
				"a chest in the walking clearance is preserved and blocks path repair");
			checks++;
			chest.clearContent(); // Keep the fixture's test bread from dropping as a real entity into the path.
			level.setBlock(chestPos, Blocks.AIR.defaultBlockState(), 3);
			// The route worker should fetch its missing tool from public stock rather than silently
			// leaving an otherwise repairable saved path broken.
			ItemStack carriedShovel = new ItemStack(Items.WOODEN_SHOVEL);
			worker.getInventory().setItem(0, ItemStack.EMPTY);
			int storedShovelSlot = findStoredShovel(publicStock);
			if (storedShovelSlot < 0) {
				if (publicStock == null) throw new IllegalStateException("Path repair fixture needs public village storage");
				for (int slot = 0; slot < publicStock.getContainerSize(); slot++) {
					if (publicStock.getItem(slot).isEmpty()) {
						storedShovelSlot = slot;
						break;
					}
				}
				if (storedShovelSlot < 0) throw new IllegalStateException("Path repair fixture needs one public shovel slot");
				publicStock.setItem(storedShovelSlot, carriedShovel.copy());
				publicStock.setChanged();
			}
			int storedShovelsBefore = countStoredShovels(publicStock);
			BlockPos publicStockPos = ref.chestPos(level);
			check(storedShovelsBefore == 1 && publicStockPos != null
				&& de.villagerplus.village.Lager.publicContainerAt(level, publicStockPos) != null,
				"the fresh public chest fixture retains exactly one usable shovel before pickup (slot=" + storedShovelSlot
					+ ", count=" + storedShovelsBefore + ", item=" + publicStock.getItem(storedShovelSlot)
					+ ", pos=" + publicStockPos + ")");
			checks++;
			worker.teleportTo(testStockPos.getX() + 0.5, testStockPos.getY(), testStockPos.getZ() + 1.5);
			check(Lighting.recognizedVillagePathGap(level, ref, candidate),
				"the missing-tool scenario retains its saved, unoccupied road-gap target");
			checks++;
			JobState fetchShovelState = new JobState();
			fetchShovelState.target = candidate.immutable();
			Ctx fetchShovelContext = new Ctx(level, worker, fetchShovelState, worker.getAttachedOrCreate(VillagerPlus.MEMORY),
				ref, level.getGameTime() + 1);
			boolean fetchedPathShovel = Lighting.repairVillagePath(fetchShovelContext, ref);
			int workerShovelCount = countWorkerShovels(worker.getInventory());
			int storedShovelsAfter = countStoredShovels(publicStock);
			String pickupDiagnostic = " [fetched=" + fetchedPathShovel + ", workerShovels=" + workerShovelCount
				+ ", publicShovels=" + storedShovelsBefore + "->" + storedShovelsAfter + ", gap="
				+ level.getBlockState(candidate) + ", stockPos=" + publicStockPos + ", stockNow="
				+ publicStock.getItem(storedShovelSlot) + ", route=" + JobUtil.lastLongRouteDiagnostic(worker) + "]";
			check(fetchedPathShovel && workerShovelCount == 1
				&& storedShovelsAfter == storedShovelsBefore - 1
				&& level.getBlockState(candidate).is(Blocks.DIRT),
				"a path worker collects one real shovel from public stock before repairing a saved road gap" + pickupDiagnostic);
			checks++;
			int acquiredShovelSlot = findStoredShovel(worker.getInventory());
			if (acquiredShovelSlot < 0) throw new IllegalStateException("Fetched path shovel disappeared from worker inventory");
			shovel = worker.getInventory().getItem(acquiredShovelSlot);
			if (acquiredShovelSlot != 0) {
				worker.getInventory().setItem(acquiredShovelSlot, ItemStack.EMPTY);
				worker.getInventory().setItem(0, shovel);
			}
			worker.teleportTo(candidate.getX() + 0.5, candidate.getY() + 1.0, candidate.getZ() + 0.5);
			int damageBefore = shovel.getDamageValue();
			check(!Lighting.repairVillagePath(context, ref) && level.getBlockState(candidate).is(Blocks.DIRT)
				&& shovel.getDamageValue() == damageBefore
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
				"a natural saved-path gap is not filled for free when neither the worker nor public stock has Dirt");
			checks++;
			worker.getInventory().setItem(1, new ItemStack(Items.DIRT));
			check(Lighting.repairVillagePath(context, ref) && level.getBlockState(candidate).is(Blocks.DIRT_PATH)
				&& shovel.getDamageValue() == damageBefore + 1
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
				"the worker consumes one real Dirt block and shovel durability to restore one gap cell");
			checks++;
			worker.getNavigation().stop();
			worker.setPos(origin.getX() - 9 + 0.5, origin.getY(), origin.getZ() + 0.5);
			worker.setOnGround(true);
			BlockPos repairedDestination = origin.offset(-7, 0, 0);
			var repairedPath = worker.getNavigation().createPath(repairedDestination, 0);
			check(repairedPath != null && repairedPath.canReach(),
				"vanilla villager navigation crosses the paid, repaired path gap"
					+ " [path=" + (repairedPath == null ? "null" : "reachable=" + repairedPath.canReach())
					+ ", start=" + worker.blockPosition() + ", destination=" + repairedDestination
					+ ", startFloor=" + level.getBlockState(worker.blockPosition().below())
					+ ", gap=" + level.getBlockState(candidate) + ", destinationFloor=" + level.getBlockState(repairedDestination.below()) + "]");
			checks++;
			worker.getNavigation().stop();

			// A short route may climb one block per cell. Only its natural, supported and empty corridor is eligible.
			level.setBlock(origin.offset(-8, -1, 0), Blocks.AIR.defaultBlockState(), 3);
			BlockPos slopeGap = origin.offset(-8, 0, 0);
			BlockPos upperPath = origin.offset(-7, 1, 0);
			level.setBlock(slopeGap.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(slopeGap, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(upperPath.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(origin.offset(-7, -1, 0), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(upperPath, Blocks.DIRT_PATH.defaultBlockState(), 3);
			worker.teleportTo(slopeGap.getX() + 0.5, slopeGap.getY() + 1.0, slopeGap.getZ() + 0.5);
			check(Lighting.findVillagePathGap(level, ref, 12, worker).equals(slopeGap)
				&& Lighting.recognizedVillagePathGap(level, ref, slopeGap, worker),
				"the trusted corridor recognizes a one-block-per-cell slope between existing path endpoints");
			checks++;
			int slopeDamageBefore = shovel.getDamageValue();
			Ctx slopeContext = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY),
				ref, level.getGameTime() + 2);
			worker.getInventory().setItem(1, new ItemStack(Items.DIRT));
			check(Lighting.repairVillagePath(slopeContext, ref) && level.getBlockState(slopeGap).is(Blocks.DIRT_PATH)
				&& shovel.getDamageValue() == slopeDamageBefore + 1
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
				"the worker pays one Dirt block and shovel durability to close the sloped natural gap");
			checks++;
			level.setBlock(slopeGap, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(upperPath, Blocks.AIR.defaultBlockState(), 3);

			// Repair a three-cell break in a remembered road, one paid cell per work cycle.
			for (int dx = -10; dx <= -1; dx++) {
				BlockPos ground = origin.offset(dx, -1, 0);
				level.setBlock(ground.below(), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(ground, dx == -9 || dx == -5 ? Blocks.DIRT_PATH.defaultBlockState()
					: dx <= -6 && dx >= -8 ? Blocks.DIRT.defaultBlockState() : Blocks.DIRT_PATH.defaultBlockState(), 3);
				level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(ground.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			BlockPos shortGap = origin.offset(-7, -1, 0);
			worker.teleportTo(shortGap.getX() + 0.5, shortGap.getY() + 1.0, shortGap.getZ() + 0.5);
			BlockPos selectedGap = Lighting.findVillagePathGap(level, ref, 12, worker);
			check(Lighting.recognizedVillagePathGap(level, ref, shortGap, worker)
				&& selectedGap != null && selectedGap.getX() >= origin.getX() - 8
				&& selectedGap.getX() <= origin.getX() - 6 && selectedGap.getY() == shortGap.getY()
				&& selectedGap.getZ() == shortGap.getZ()
				&& Lighting.recognizedVillagePathGap(level, ref, selectedGap, worker),
				"a three-cell natural gap in the saved corridor is eligible");
			checks++;
			int longDamageBefore = shovel.getDamageValue();
			Ctx longContext = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY),
				ref, level.getGameTime() + 3);
			worker.getInventory().setItem(1, new ItemStack(Items.DIRT));
			check(Lighting.repairVillagePath(longContext, ref) && level.getBlockState(selectedGap).is(Blocks.DIRT_PATH)
				&& shovel.getDamageValue() == longDamageBefore + 1
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
				"the worker pays one Dirt block and shovel durability for a cell in a three-cell break");
			checks++;

			// Four natural cells are still a small gap; repair advances one paid cell per work cycle.
			for (int dx = -10; dx <= -1; dx++) {
				BlockPos ground = origin.offset(dx, -1, 0);
				level.setBlock(ground, dx <= -4 && dx >= -7 ? Blocks.DIRT.defaultBlockState()
					: Blocks.DIRT_PATH.defaultBlockState(), 3);
			}
			BlockPos fourGap = origin.offset(-5, -1, 0);
			BlockPos fourGapCandidate = Lighting.findVillagePathGap(level, ref, 12, worker);
			check(fourGapCandidate != null && fourGapCandidate.getX() >= origin.getX() - 7
				&& fourGapCandidate.getX() <= origin.getX() - 4
				&& Lighting.recognizedVillagePathGap(level, ref, fourGapCandidate, worker),
				"a four-cell natural gap in the saved corridor is selected within the repair bound");
			checks++;
			int fourGapDamageBefore = shovel.getDamageValue();
			Ctx fourGapContext = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY),
				ref, level.getGameTime() + 4);
			worker.getInventory().setItem(1, new ItemStack(Items.DIRT));
			check(Lighting.repairVillagePath(fourGapContext, ref)
				&& level.getBlockState(fourGapCandidate).is(Blocks.DIRT_PATH)
				&& shovel.getDamageValue() == fourGapDamageBefore + 1
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
				"the four-cell repair consumes Dirt and shovel durability for one natural cell");
			checks++;

			// A longer remembered-road gap remains repairable, but an open area beyond the cap does not.
			// Keep the entire gap beyond the bell's protected three-block radius.
			Market.save(ref, new MarketData(Optional.of(origin.west(14)), oldMarket.shops(), oldMarket.nextPlan(), oldMarket.sales()));
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of("center", 15L));
			for (int dx = -14; dx <= -5; dx++) {
				BlockPos ground = origin.offset(dx, -1, 0);
				level.setBlock(ground, dx == -14 || dx == -5 ? Blocks.DIRT_PATH.defaultBlockState()
					: Blocks.DIRT.defaultBlockState(), 3);
			}
			BlockPos eightGapCenter = origin.offset(-10, -1, 0);
			BlockPos eightGapCandidate = Lighting.findVillagePathGap(level, ref, 12, worker);
			boolean eightGapCenterRecognized = Lighting.recognizedVillagePathGap(level, ref, eightGapCenter, worker);
			check(eightGapCenterRecognized && eightGapCandidate != null && eightGapCandidate.getX() >= origin.getX() - 13
				&& eightGapCandidate.getX() <= origin.getX() - 6
				&& Lighting.recognizedVillagePathGap(level, ref, eightGapCandidate, worker),
				"an eight-cell natural gap between saved road endpoints remains eligible for bounded repair"
					+ " [center=" + eightGapCenterRecognized + ", selected=" + eightGapCandidate
					+ ", saved-cells=" + Roads.savedVillagePathGrounds(level, ref).size()
					+ ", trusted-center=" + Roads.trustedVillagePathCell(level, ref, eightGapCenter) + "]");
			checks++;
			worker.getInventory().setItem(1, new ItemStack(Items.DIRT));
			int eightGapDamageBefore = shovel.getDamageValue();
			Ctx eightGapContext = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY),
				ref, level.getGameTime() + 5);
			check(Lighting.repairVillagePath(eightGapContext, ref)
				&& level.getBlockState(eightGapCandidate).is(Blocks.DIRT_PATH)
				&& shovel.getDamageValue() == eightGapDamageBefore + 1
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
				"the eight-cell-gap repair still consumes one real Dirt block and shovel durability per work cycle");
			checks++;
			Market.save(ref, new MarketData(Optional.of(origin.west(15)), oldMarket.shops(), oldMarket.nextPlan(), oldMarket.sales()));
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of("center", 16L));
			for (int dx = -15; dx <= -5; dx++) {
				BlockPos ground = origin.offset(dx, -1, 0);
				level.setBlock(ground.below(), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(ground, dx == -15 || dx == -5 ? Blocks.DIRT_PATH.defaultBlockState()
					: Blocks.DIRT.defaultBlockState(), 3);
				level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(ground.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			BlockPos nineGapCandidate = origin.offset(-10, -1, 0);
			check(!Lighting.recognizedVillagePathGap(level, ref, nineGapCandidate, worker),
				"a nine-cell opening remains outside the bounded repair length");
			checks++;
			Market.save(ref, new MarketData(Optional.of(origin.west(10)), oldMarket.shops(), oldMarket.nextPlan(), oldMarket.sales()));
			ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of("center", 11L));

			// A single natural soil block over the saved route can be cut when path blocks anchor both sides.
			for (int dx = -10; dx <= -1; dx++) {
				BlockPos cell = origin.offset(dx, -1, 0);
				level.setBlock(cell.below(), Blocks.DIRT.defaultBlockState(), 3);
				level.setBlock(cell, Blocks.DIRT_PATH.defaultBlockState(), 3);
				for (int dy = 1; dy <= 3; dy++) level.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), 3);
			}
			BlockPos cutGround = origin.offset(-5, -1, 0);
			level.setBlock(cutGround, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(cutGround.above(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			BlockPos cutCandidate = Lighting.findVillagePathGap(level, ref, 12, worker);
			check(Lighting.recognizedVillagePathCut(level, ref, cutGround, worker)
				&& cutGround.equals(cutCandidate),
				"one natural soil obstruction between same-height saved path anchors is selected for a shallow cut");
			checks++;
			var clearanceOccupant = EntityTypes.VILLAGER.spawn(level, cutGround.west(3).above(), EntitySpawnReason.COMMAND);
			if (clearanceOccupant == null) throw new IllegalStateException("Lighting spacing: clearance-occupant fixture missing");
			clearanceOccupant.setNoAi(true);
			clearanceOccupant.teleportTo(cutGround.getX() + 0.5, cutGround.getY() + 2.0, cutGround.getZ() + 0.5);
			try {
				check(!Lighting.recognizedVillagePathCut(level, ref, cutGround, worker),
					"an entity in the upper two-block path clearance prevents a one-layer cut");
				checks++;
			} finally {
				clearanceOccupant.discard();
			}
			BlockPos worksite = cutGround.west().above();
			worker.teleportTo(worksite.getX() + 0.5, worksite.getY(), worksite.getZ() + 0.5);
			int cutDamageBefore = shovel.getDamageValue();
			JobState cutState = new JobState();
			var cutMemory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, shovel);
			for (int slot = 1; slot < worker.getInventory().getContainerSize(); slot++)
				worker.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			JobState fullInventoryState = new JobState();
			for (int stroke = 0; stroke < 16; stroke++) {
				Ctx fullInventoryContext = new Ctx(level, worker, fullInventoryState, cutMemory, ref,
					level.getGameTime() + 50 + stroke * 10L);
				Lighting.repairVillagePath(fullInventoryContext, ref);
			}
			check(level.getBlockState(cutGround).is(Blocks.DIRT)
				&& level.getBlockState(cutGround.above()).is(Blocks.GRASS_BLOCK)
				&& shovel.getDamageValue() == cutDamageBefore
				&& cutGround.equals(fullInventoryState.target)
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
				"a full villager inventory defers the soil cut, keeps its resumable target, and does not spill drops");
			checks++;
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, shovel);
			boolean cutFinished = false;
			for (int stroke = 0; stroke < 16 && !cutFinished; stroke++) {
				Ctx cutContext = new Ctx(level, worker, cutState, cutMemory, ref, level.getGameTime() + 100 + stroke * 10L);
				Lighting.repairVillagePath(cutContext, ref);
				cutFinished = level.getBlockState(cutGround).is(Blocks.DIRT_PATH)
					&& level.getBlockState(cutGround.above()).isAir();
			}
			check(cutFinished && shovel.getDamageValue() == cutDamageBefore + 1
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) > 0,
				"the worker cuts one block with the carried shovel, keeps its dirt drop, and leaves a level path cell");
			checks++;
			level.setBlock(cutGround, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(cutGround.above(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(cutGround.above(2), Blocks.AIR.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathCut(level, ref, cutGround, worker),
				"stone obstructions remain outside the shallow soil cut");
			checks++;
			level.setBlock(cutGround.above(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(cutGround.above(2), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(cutGround.above(3), Blocks.DIRT.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathCut(level, ref, cutGround, worker),
				"a third natural-soil layer exceeds the bounded hump cut");
			checks++;
			level.setBlock(cutGround.above(2), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(cutGround.above(3), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(cutGround.below(), Blocks.CHEST.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathCut(level, ref, cutGround, worker),
				"a block entity under the path keeps the obstruction protected");
			checks++;
			level.setBlock(cutGround.below(), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(cutGround.above(), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(cutGround.above(2), Blocks.WATER.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathCut(level, ref, cutGround, worker),
				"fluid in the hump clearance is never excavated for a path");
			checks++;
			level.setBlock(cutGround.above(), Blocks.WHEAT.defaultBlockState(), 3);
			level.setBlock(cutGround.above(2), Blocks.AIR.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathCut(level, ref, cutGround, worker),
				"crops in the route column are not treated as natural soil");
			checks++;
			level.setBlock(cutGround, Blocks.FARMLAND.defaultBlockState(), 3);
			level.setBlock(cutGround.above(), Blocks.DIRT.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathCut(level, ref, cutGround, worker),
				"farmland under a soil obstruction remains protected");
			checks++;
			level.setBlock(cutGround, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(cutGround.above(), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(cutGround.above(2), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(cutGround.above(3), Blocks.AIR.defaultBlockState(), 3);
			// Match the one-layer selection check above: nearest work is measured from the plaza,
			// not from the worker's adjacent cut-worksite after the first excavation.
			worker.teleportTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
			if (!Lighting.recognizedVillagePathCut(level, ref, cutGround, worker))
				throw new IllegalStateException("Lighting spacing: two-layer hump rejected: "
					+ pathCutDiagnostic(level, ref, cutGround, worker));
			BlockPos twoLayerCandidate = Lighting.findVillagePathGap(level, ref, 12, worker);
			check(cutGround.equals(twoLayerCandidate),
				"the saved-route scan selects the nearest two-layer hump; selected=" + twoLayerCandidate);
			checks++;
			var itemDrop = EntityTypes.ITEM.spawn(level, cutGround.west(3).above(), EntitySpawnReason.COMMAND);
			if (itemDrop == null) throw new IllegalStateException("Lighting spacing: hump-item fixture missing");
			itemDrop.setItem(new ItemStack(Items.DIRT));
			itemDrop.teleportTo(cutGround.getX() + 0.5, cutGround.getY() + 3.0, cutGround.getZ() + 0.5);
			try {
				check(!itemDrop.isPushable() && Lighting.recognizedVillagePathCut(level, ref, cutGround, worker),
					"a dropped item in the hump clearance does not block a two-layer cut");
				checks++;
			} finally {
				itemDrop.discard();
			}
			var humpOccupant = EntityTypes.VILLAGER.spawn(level, cutGround.west(3).above(), EntitySpawnReason.COMMAND);
			if (humpOccupant == null) throw new IllegalStateException("Lighting spacing: hump-occupant fixture missing");
			humpOccupant.setNoAi(true);
			humpOccupant.teleportTo(cutGround.getX() + 0.5, cutGround.getY() + 3.0, cutGround.getZ() + 0.5);
			try {
				check(!Lighting.recognizedVillagePathCut(level, ref, cutGround, worker),
					"an entity standing on the two-layer hump prevents excavation");
				checks++;
			} finally {
				humpOccupant.discard();
			}
			worker.teleportTo(worksite.getX() + 0.5, worksite.getY(), worksite.getZ() + 0.5);
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, shovel);
			int twoLayerDamageBefore = shovel.getDamageValue();
			JobState twoLayerState = new JobState();
			for (int slot = 1; slot < worker.getInventory().getContainerSize(); slot++)
				worker.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			Ctx twoLayerFullContext = new Ctx(level, worker, twoLayerState, cutMemory, ref, level.getGameTime() + 250);
			Lighting.repairVillagePath(twoLayerFullContext, ref);
			check(level.getBlockState(cutGround).is(Blocks.DIRT)
				&& level.getBlockState(cutGround.above()).is(Blocks.DIRT)
				&& level.getBlockState(cutGround.above(2)).is(Blocks.DIRT)
				&& shovel.getDamageValue() == twoLayerDamageBefore
				&& cutGround.equals(twoLayerState.target)
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 0,
				"a full inventory leaves both hump layers untouched and keeps the saved path cut target");
			checks++;
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, shovel);
			boolean firstHumpLayerRemoved = false;
			for (int stroke = 0; stroke < 16 && !firstHumpLayerRemoved; stroke++) {
				Ctx cutContext = new Ctx(level, worker, twoLayerState, cutMemory, ref,
					level.getGameTime() + 300 + stroke * 10L);
				Lighting.repairVillagePath(cutContext, ref);
				firstHumpLayerRemoved = level.getBlockState(cutGround.above(2)).isAir();
			}
			check(firstHumpLayerRemoved && level.getBlockState(cutGround).is(Blocks.DIRT)
				&& level.getBlockState(cutGround.above()).is(Blocks.DIRT)
				&& cutGround.equals(twoLayerState.target)
				&& cutGround.equals(Lighting.findVillagePathGap(level, ref, 12, worker))
				&& shovel.getDamageValue() == twoLayerDamageBefore + 1
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 1,
				"the first work action removes one animated layer and the remaining cut safely resumes with its exact drop");
			checks++;
			boolean twoLayerCutFinished = false;
			for (int stroke = 0; stroke < 16 && !twoLayerCutFinished; stroke++) {
				Ctx cutContext = new Ctx(level, worker, twoLayerState, cutMemory, ref,
					level.getGameTime() + 600 + stroke * 10L);
				Lighting.repairVillagePath(cutContext, ref);
				twoLayerCutFinished = level.getBlockState(cutGround).is(Blocks.DIRT_PATH)
					&& level.getBlockState(cutGround.above()).isAir()
					&& level.getBlockState(cutGround.above(2)).isAir();
			}
			check(twoLayerCutFinished && twoLayerState.target == null
				&& shovel.getDamageValue() == twoLayerDamageBefore + 2
				&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == 2,
				"the second work action clears the remaining layer, completes the path, and stores both drops exactly once");
			checks++;
			worker.getNavigation().stop();
			worker.setPos(cutGround.getX() - 1 + 0.5, cutGround.getY() + 1.0, cutGround.getZ() + 0.5);
			worker.setOnGround(true);
			BlockPos cutDestination = cutGround.east().above();
			var cutPath = worker.getNavigation().createPath(cutDestination, 0);
			check(cutPath != null && cutPath.canReach(),
				"vanilla villager navigation crosses the completed two-layer path cut"
					+ " [path=" + (cutPath == null ? "null" : "reachable=" + cutPath.canReach())
					+ ", start=" + worker.blockPosition() + ", destination=" + cutDestination
					+ ", startFloor=" + level.getBlockState(worker.blockPosition().below())
					+ ", cut=" + level.getBlockState(cutGround) + ", destinationFloor=" + level.getBlockState(cutDestination.below()) + "]");
			checks++;
			worker.getNavigation().stop();

			// A two-cell, two-block-high berm across all three saved road lanes blocks the
			// normal route. Each cell is still cut separately, then the new path
			// anchor lets the existing single-cell rule finish the next one.
			for (int side = -1; side <= 1; side++) for (int dx = -10; dx <= -1; dx++) {
				BlockPos cell = origin.offset(dx, -1, side);
				level.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(cell, Blocks.DIRT_PATH.defaultBlockState(), 3);
				for (int dy = 1; dy <= 3; dy++) level.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), 3);
			}
			BlockPos firstBermCell = origin.offset(-6, -1, 0);
			BlockPos secondBermCell = origin.offset(-5, -1, 0);
			BlockPos thirdBermCell = origin.offset(-4, -1, 0);
			for (int side = -1; side <= 1; side++) for (int dx = -6; dx <= -5; dx++) {
				BlockPos cell = origin.offset(dx, -1, side);
				level.setBlock(cell, Blocks.DIRT.defaultBlockState(), 3);
				level.setBlock(cell.above(), Blocks.DIRT.defaultBlockState(), 3);
				level.setBlock(cell.above(2), Blocks.DIRT.defaultBlockState(), 3);
			}
			check(Lighting.recognizedVillagePathCut(level, ref, firstBermCell, worker)
				&& Lighting.recognizedVillagePathCut(level, ref, secondBermCell, worker),
				"two adjacent natural-soil cells are eligible only when saved path anchors bound both ends");
			checks++;
			BlockPos selectedBerm = Lighting.findVillagePathGap(level, ref, 12, worker);
			check(selectedBerm != null && selectedBerm.getX() >= origin.getX() - 6
				&& selectedBerm.getX() <= origin.getX() - 5 && Math.abs(selectedBerm.getZ() - origin.getZ()) <= 1,
				"the saved-route scan selects one cell from the bounded two-by-three obstruction");
			checks++;
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(new ZonePolicy.Zone("berm", "field",
				secondBermCell.getX(), secondBermCell.getZ(), secondBermCell.getX(), secondBermCell.getZ())));
			check(!Lighting.recognizedVillagePathCut(level, ref, firstBermCell, worker),
				"a claimed adjacent cell prevents cutting the whole short route obstruction");
			checks++;
			if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,
				List.of(new Built(Blueprints.HOUSE, secondBermCell, 0, 0, List.of())));
			check(!Lighting.recognizedVillagePathCut(level, ref, firstBermCell, worker),
				"a registered building footprint protects the adjacent cell in a short route cut");
			checks++;
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, oldBuildings == null ? List.of() : oldBuildings);
			level.setBlock(secondBermCell, Blocks.FARMLAND.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathCut(level, ref, firstBermCell, worker),
				"farmland in either cell prevents cutting a short route obstruction");
			checks++;
			level.setBlock(secondBermCell, Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(secondBermCell.above(), Blocks.WHEAT.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathCut(level, ref, firstBermCell, worker),
				"a crop in either cell prevents cutting a short route obstruction");
			checks++;
			level.setBlock(secondBermCell.above(), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(secondBermCell.below(), Blocks.CHEST.defaultBlockState(), 3);
			check(!Lighting.recognizedVillagePathCut(level, ref, firstBermCell, worker),
				"a block entity under either berm cell prevents the short route cut");
			checks++;
			level.setBlock(secondBermCell.below(), Blocks.STONE.defaultBlockState(), 3);
			for (int side = -1; side <= 1; side++) {
				BlockPos cell = thirdBermCell.offset(0, 0, side);
				level.setBlock(cell, Blocks.DIRT.defaultBlockState(), 3);
				level.setBlock(cell.above(), Blocks.DIRT.defaultBlockState(), 3);
				level.setBlock(cell.above(2), Blocks.DIRT.defaultBlockState(), 3);
			}
			check(!Lighting.recognizedVillagePathCut(level, ref, firstBermCell, worker)
				&& !Lighting.recognizedVillagePathCut(level, ref, secondBermCell, worker)
				&& !Lighting.recognizedVillagePathCut(level, ref, thirdBermCell, worker),
				"a three-cell obstruction exceeds the short-cut bound and remains untouched");
			checks++;
			for (int side = -1; side <= 1; side++) {
				BlockPos cell = thirdBermCell.offset(0, 0, side);
				level.setBlock(cell, Blocks.DIRT_PATH.defaultBlockState(), 3);
				level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, shovel);
			int bermDamageBefore = shovel.getDamageValue();
			JobState bermState = new JobState();
			int bermCuts = 0;
			worker.teleportTo(origin.getX() - 7 + 0.5, origin.getY(), origin.getZ() + 0.5);
			for (int stroke = 0; stroke < 192 && bermCuts < 6; stroke++) {
				Ctx bermContext = new Ctx(level, worker, bermState, cutMemory, ref,
					level.getGameTime() + 700 + stroke * 10L);
				Lighting.repairVillagePath(bermContext, ref);
				int completed = 0;
				for (int side = -1; side <= 1; side++) for (int dx = -6; dx <= -5; dx++)
					if (level.getBlockState(origin.offset(dx, -1, side)).is(Blocks.DIRT_PATH)) completed++;
				if (completed > bermCuts) {
					check(completed == bermCuts + 1
						&& JobUtil.count(worker.getInventory(), stack -> stack.is(Items.DIRT)) == completed * 2
						&& shovel.getDamageValue() == bermDamageBefore + completed * 2,
						"each bounded berm cell is cut with a real shovel and its dirt drop stays in the villager inventory");
					checks++;
					bermCuts = completed;
				}
			}
			check(bermState.target == null && bermCuts == 6,
				"all six cells across the two-by-three natural-soil berm become ordinary path tiles");
			checks++;
			worker.getNavigation().stop();
			worker.setPos(origin.getX() - 7 + 0.5, origin.getY(), origin.getZ() + 0.5);
			worker.setOnGround(true);
			var bermPath = worker.getNavigation().createPath(origin.offset(-3, 0, 0), 0);
			check(bermPath != null && bermPath.canReach(),
				"vanilla villager navigation crosses the two-cell berm after all six cuts");
			checks++;
			worker.getNavigation().stop();
			Map<BlockPos, Cell> savedSideLanes = new LinkedHashMap<>();
			for (int side : new int[] {-1, 1}) for (int dx = -10; dx <= -1; dx++) for (int dy = -1; dy <= 3; dy++) {
				BlockPos pos = origin.offset(dx, -1, side).offset(0, dy, 0);
				Cell original = before.get(pos);
				if (original != null) savedSideLanes.put(pos, original);
			}
			restore(level, savedSideLanes);
			return checks;
		} finally {
			worker.discard();
			if (ref != null) {
				Market.save(ref, oldMarket == null ? MarketData.empty() : oldMarket);
				if (oldRoads == null) ref.bellEntity().removeAttached(VillagerPlus.ROADS);
				else ref.bellEntity().setAttached(VillagerPlus.ROADS, oldRoads);
				if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
				else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
				if (oldBuildings == null) ref.bellEntity().removeAttached(VillagerPlus.BUILDINGS);
				else ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, oldBuildings);
				if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS);
				else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
				ref.dirty();
			}
			if (publicStock != null && oldStock != null) {
				for (int slot = 0; slot < oldStock.size(); slot++) publicStock.setItem(slot, oldStock.get(slot));
				publicStock.setChanged();
			}
			if (communalStock != null && oldCommunalStock != null) {
				for (int slot = 0; slot < oldCommunalStock.size(); slot++) communalStock.setItem(slot, oldCommunalStock.get(slot));
				communalStock.setChanged();
			}
			restore(level, before);
		}
	}

	/** J09 consumer check for charcoal-to-torch crafting from the same public village stock used by the live job. */
	public static int charcoalTorchChecks(ServerLevel level, VillageRef ref, BlockPos origin) {
		if (ref == null || ref.chestPos(level) == null) throw new IllegalStateException("Lighting charcoal: public village chest missing");
		BlockPos stockPos = ref.chestPos(level);
		Container stock = ref.chest(level);
		if (stock == null) throw new IllegalStateException("Lighting charcoal: public village stock missing");
		List<ItemStack> oldStock = new ArrayList<>();
		for (int slot = 0; slot < stock.getContainerSize(); slot++) oldStock.add(stock.getItem(slot).copy());
		BlockEntity stockEntity = level.getBlockEntity(stockPos);
		UUID oldOwner = stockEntity.getAttached(VillagerPlus.OWNER);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		BlockPos site = origin.offset(120, 0, 120);
		BlockPos furnacePos = origin.offset(-120, 0, -120);
		loadSpacingRegion(level, site, 32);
		for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) for (int dy = -8; dy <= 8; dy++) {
			BlockPos pos = site.offset(dx, dy, dz).immutable();
			capture(level, before, pos);
			if (isLight(level.getBlockState(pos))) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
		capture(level, before, site.below());
		capture(level, before, site);
		capture(level, before, site.above());
		capture(level, before, site.above(2));
		capture(level, before, furnacePos.below());
		capture(level, before, furnacePos);
		capture(level, before, furnacePos.above());
		var worker = EntityTypes.VILLAGER.spawn(level, origin, EntitySpawnReason.COMMAND);
		worker.setNoAi(true);
		worker.setInvulnerable(true);
		worker.addTag("vp090_fixture");
		try {
			level.setBlock(site.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(site, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(site.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(site.above(2), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(furnacePos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
			if (!(level.getBlockEntity(furnacePos) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace))
				throw new IllegalStateException("Lighting charcoal: furnace fixture missing");
			furnace.setItem(1, new ItemStack(Items.COAL, 7));
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 1);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, new ItemStack(Items.IRON_INGOT));
			Ctx context = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY),
				ref, level.getGameTime() + 1);
			int checks = 0;

			stock.clearContent();
			stock.setItem(0, new ItemStack(Items.CHARCOAL, 3));
			stock.setItem(1, new ItemStack(Items.STICK, 2));
			checkTorch(Lighting.placeable(level, site) && Lighting.canCraftTorchFromCharcoal(level, stock)
				&& JobUtil.count(stock, stack -> stack.is(Items.CHARCOAL)) == 3
				&& JobUtil.count(stock, stack -> stack.is(Items.STICK)) == 2,
				"a loaded charcoal-and-stick torch recipe is available for a dark, valid site without changing stock"); checks++;
			checkTorch(Lighting.placeFromStock(context, site) && level.getBlockState(site).is(Blocks.TORCH)
				&& JobUtil.count(stock, stack -> stack.is(Items.CHARCOAL)) == 2
				&& JobUtil.count(stock, stack -> stack.is(Items.STICK)) == 1
				&& JobUtil.count(stock, stack -> stack.is(Items.TORCH)) > 0
				&& furnace.getItem(1).is(Items.COAL) && furnace.getItem(1).getCount() == 7
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).is(Items.IRON_INGOT),
				"the lamplighter places a recipe-crafted torch from public stock and leaves claimed smelter fuel untouched"); checks++;

			stock.clearContent();
			stock.setItem(0, new ItemStack(Items.STICK, 2));
			checkTorch(!Lighting.canCraftTorchFromCharcoal(level, stock) && !Lighting.craftTorchFromCharcoal(level, stock)
				&& JobUtil.count(stock, stack -> stack.is(Items.STICK)) == 2,
				"missing charcoal leaves the public sticks untouched"); checks++;
			stock.clearContent();
			stock.setItem(0, new ItemStack(Items.CHARCOAL, 2));
			checkTorch(!Lighting.canCraftTorchFromCharcoal(level, stock) && !Lighting.craftTorchFromCharcoal(level, stock)
				&& JobUtil.count(stock, stack -> stack.is(Items.CHARCOAL)) == 2,
				"missing sticks leave the public charcoal untouched"); checks++;
			stock.setItem(1, new ItemStack(Items.STICK, 2));
			checkTorch(!Lighting.craftTorchFromCharcoal(level, stock, List.of())
				&& JobUtil.count(stock, stack -> stack.is(Items.CHARCOAL)) == 2
				&& JobUtil.count(stock, stack -> stack.is(Items.STICK)) == 2,
				"an unavailable loaded torch recipe consumes neither ingredient"); checks++;

			stock.clearContent();
			for (int slot = 0; slot < stock.getContainerSize(); slot++) stock.setItem(slot, new ItemStack(Items.STONE, 64));
			stock.setItem(0, new ItemStack(Items.CHARCOAL, 64));
			stock.setItem(1, new ItemStack(Items.STICK, 64));
			checkTorch(!Lighting.canCraftTorchFromCharcoal(level, stock) && !Lighting.craftTorchFromCharcoal(level, stock)
				&& JobUtil.count(stock, stack -> stack.is(Items.CHARCOAL)) == 64
				&& JobUtil.count(stock, stack -> stack.is(Items.STICK)) == 64
				&& JobUtil.count(stock, stack -> stack.is(Items.TORCH)) == 0,
				"insufficient result capacity preserves charcoal and sticks exactly"); checks++;

			stock.clearContent();
			stock.setItem(0, new ItemStack(Items.CHARCOAL, 2));
			stock.setItem(1, new ItemStack(Items.STICK, 2));
			level.setBlock(site, Blocks.STONE.defaultBlockState(), 3);
			checkTorch(!Lighting.placeFromStock(context, site)
				&& JobUtil.count(stock, stack -> stack.is(Items.CHARCOAL)) == 2
				&& JobUtil.count(stock, stack -> stack.is(Items.STICK)) == 2
				&& JobUtil.count(stock, stack -> stack.is(Items.TORCH)) == 0,
				"an invalid target never triggers crafting or consumes stock"); checks++;

			level.setBlock(site, Blocks.AIR.defaultBlockState(), 3);
			stock.clearContent();
			stock.setItem(0, new ItemStack(Items.CHARCOAL, 2));
			stock.setItem(1, new ItemStack(Items.STICK, 2));
			stockEntity.setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			checkTorch(!Lighting.placeFromStock(context, site)
				&& JobUtil.count(stock, stack -> stack.is(Items.CHARCOAL)) == 2
				&& JobUtil.count(stock, stack -> stack.is(Items.STICK)) == 2
				&& JobUtil.count(stock, stack -> stack.is(Items.TORCH)) == 0,
				"a private chest is not treated as public torch-making stock"); checks++;
			return checks;
		} finally {
			worker.discard();
			stock.clearContent();
			for (int slot = 0; slot < oldStock.size(); slot++) stock.setItem(slot, oldStock.get(slot));
			if (oldOwner == null) stockEntity.removeAttached(VillagerPlus.OWNER);
			else stockEntity.setAttached(VillagerPlus.OWNER, oldOwner);
			stock.setChanged();
			restore(level, before);
		}
	}

	private static void capture(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		level.getChunkAt(pos);
		if (before.containsKey(pos)) return;
		BlockEntity entity = level.getBlockEntity(pos);
		before.put(pos.immutable(), new Cell(level.getBlockState(pos),
			entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void loadSpacingRegion(ServerLevel level, BlockPos center, int radius) {
		int minChunkX = Math.floorDiv(center.getX() - radius, 16);
		int maxChunkX = Math.floorDiv(center.getX() + radius, 16);
		int minChunkZ = Math.floorDiv(center.getZ() - radius, 16);
		int maxChunkZ = Math.floorDiv(center.getZ() + radius, 16);
		long radiusSquared = (long) radius * radius;
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			int minX = chunkX << 4;
			long dx = (long) Math.max(minX, Math.min(minX + 15, center.getX())) - center.getX();
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				int minZ = chunkZ << 4;
				long dz = (long) Math.max(minZ, Math.min(minZ + 15, center.getZ())) - center.getZ();
				if (dx * dx + dz * dz >= radiusSquared) continue;
				level.getChunkAt(new BlockPos(minX + 8, center.getY(), minZ + 8));
			}
		}
	}

	private static void checkTorch(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Lighting charcoal: " + message);
	}

	private static int findStoredShovel(Container inventory) {
		if (inventory == null) return -1;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (isShovel(inventory.getItem(slot))) return slot;
		}
		return -1;
	}

	private static int countStoredShovels(Container inventory) {
		if (inventory == null) return 0;
		int count = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (isShovel(stack)) count += stack.getCount();
		}
		return count;
	}

	private static int countWorkerShovels(Container inventory) {
		return countStoredShovels(inventory);
	}

	private static boolean isShovel(ItemStack stack) {
		return stack.is(Items.NETHERITE_SHOVEL) || stack.is(Items.DIAMOND_SHOVEL)
			|| stack.is(Items.IRON_SHOVEL) || stack.is(Items.GOLDEN_SHOVEL)
			|| stack.is(Items.STONE_SHOVEL) || stack.is(Items.WOODEN_SHOVEL);
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.nbt() == null) return;
			BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
			if (restored != null) level.setBlockEntity(restored);
		});
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Lighting spacing: " + message);
	}

	private static String pathCutDiagnostic(ServerLevel level, VillageRef ref, BlockPos ground,
		net.minecraft.world.entity.Entity ignored) {
		BlockPos support = ground.below();
		BlockPos before = ground.west();
		BlockPos after = ground.east();
		net.minecraft.world.phys.AABB clearance = new net.minecraft.world.phys.AABB(ground.getX(), ground.getY(),
			ground.getZ(), ground.getX() + 1, ground.getY() + 4, ground.getZ() + 1);
		String occupants = level.getEntities(ignored, clearance, net.minecraft.world.entity.Entity::isAlive).stream()
			.map(entity -> entity.getType() + "@" + entity.blockPosition())
			.collect(java.util.stream.Collectors.joining(","));
		return "trusted=" + Roads.trustedVillagePathCell(level, ref, ground)
			+ " protected=" + Lighting.protectedVillageCell(level, ref, ground)
			+ "/" + Lighting.protectedVillageCell(level, ref, ground.above())
			+ "/" + Lighting.protectedVillageCell(level, ref, ground.above(2))
			+ " blocks=" + level.getBlockState(ground) + "|" + level.getBlockState(support)
			+ "|" + level.getBlockState(ground.above()) + "|" + level.getBlockState(ground.above(2))
			+ "|" + level.getBlockState(ground.above(3))
			+ " sturdySupport=" + level.getBlockState(support).isFaceSturdy(level, support,
				net.minecraft.core.Direction.UP)
			+ " anchors=" + level.getBlockState(before) + "/" + level.getBlockState(after)
			+ " anchorProtected=" + Lighting.protectedVillageCell(level, ref, before) + "/"
			+ Lighting.protectedVillageCell(level, ref, after)
			+ " occupants=" + occupants;
	}

	private static boolean isLight(BlockState state) {
		return state.is(Blocks.LANTERN) || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
			|| state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
			|| state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH);
	}

	private record Cell(BlockState state, CompoundTag nbt) { }
}
