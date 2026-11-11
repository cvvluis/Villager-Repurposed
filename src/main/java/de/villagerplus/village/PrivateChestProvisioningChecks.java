package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.HouseTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Focused synthetic server checks for paid private-resident chest provisioning. */
public final class PrivateChestProvisioningChecks {
	private PrivateChestProvisioningChecks() { }

	public static int run(ServerLevel level, VillageRef village, Villager villager) {
		if (village == null || villager == null) throw new IllegalArgumentException("Private chest checks need a village and resident");
		List<ContainerSnapshot> stocks = snapshotStocks(level, village);
		if (stocks.isEmpty()) throw new IllegalStateException("Private chest checks need a public village container");
		VillagerMemory originalMemory = villager.getAttachedOrCreate(VillagerPlus.MEMORY);
		VillagerMemory memory = originalMemory;
		Optional<BlockPos> oldBed = memory.homeBed, oldDoor = memory.homeDoor, oldChest = memory.homeChest;
		Optional<GlobalPos> oldHome = villager.getBrain().getMemory(MemoryModuleType.HOME);
		Optional<GlobalPos> oldMeeting = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
		boolean oldPending = PrivateChestProvisioning.isPending(villager);
		String oldName = memory.name, oldSignText = memory.signText;
		Optional<BlockPos> oldSignPos = memory.signPos;
		net.minecraft.network.chat.Component oldCustomName = villager.getCustomName();
		boolean oldNameVisible = villager.isCustomNameVisible();
		BlockPos bed = village.bell().offset(40, 0, 40);
		BlockPos door = bed.south(4);
		BlockPos movedBed = bed.east(14);
		BlockPos movedDoor = movedBed.south(4);
		BlockPos secondBed = bed.east(30);
		BlockPos secondDoor = secondBed.south(4);
		BlockPos thirdBed = bed.east(60);
		BlockPos thirdDoor = thirdBed.east(3);
		BlockPos doorlessBed = bed.west(14);
		BlockPos unverifiedBed = bed.north(16);
		BlockPos unverifiedDoor = unverifiedBed.south(4);
		List<BlockPos> fixture = new ArrayList<>(fixturePositions(bed));
		fixture.addAll(fixturePositions(movedBed));
		fixture.addAll(fixturePositions(secondBed));
		fixture.addAll(fixturePositions(thirdBed));
		fixture.addAll(fixturePositions(doorlessBed));
		fixture.addAll(fixturePositions(unverifiedBed));
		for (BlockPos pos : fixture) {
			level.getChunkAt(pos);
			if (!level.getBlockState(pos).isAir() || level.getBlockEntity(pos) != null) {
				restoreStocks(stocks);
				throw new IllegalStateException("Private chest fixture region must be empty at " + pos.toShortString());
			}
		}
		int[] passed = {0};
		try {
			BlockPos boundaryBed = new BlockPos(15, bed.getY(), 15);
			check(passed, PrivateChestProvisioning.homeScanComplete(boundaryBed, ignored -> true)
				&& !PrivateChestProvisioning.homeScanComplete(boundaryBed,
					pos -> (pos.getX() >> 4) == 0 && (pos.getZ() >> 4) == 0),
				"a home scan crossing an unloaded chunk edge is incomplete and cannot authorize a second paid chest");
			check(passed, PrivateChestProvisioning.targetPrimaryStock(3, 0) == 3
				&& PrivateChestProvisioning.targetPrimaryStock(3, 1) == 2
				&& PrivateChestProvisioning.targetPrimaryStock(3, 3) == 0
				&& PrivateChestProvisioning.targetPrimaryStock(0, 0) == 0,
				"woodworker chest production requests only the public-stock shortfall for pending residents");
			HouseTemplate.Footprint ctovPiece = new HouseTemplate.Footprint(
				thirdBed.getX() - 3, thirdBed.getX() + 3, thirdBed.getY() - 1, thirdBed.getY() + 3,
				thirdBed.getZ() - 4, thirdBed.getZ() + 4);
			check(passed, PrivateChestProvisioning.withinHomeFootprint(thirdBed, ctovPiece)
				&& !PrivateChestProvisioning.withinHomeFootprint(thirdBed.east(5), ctovPiece)
				&& !PrivateChestProvisioning.withinHomeFootprint(thirdBed.above(4), ctovPiece),
				"a CTOV piece bound rejects chest coordinates beyond the house's horizontal or vertical structure envelope");
			check(passed, PrivateChestProvisioning.withinHomeInteriorFootprint(thirdBed, ctovPiece)
				&& !PrivateChestProvisioning.withinHomeInteriorFootprint(thirdBed.west(3), ctovPiece)
				&& !PrivateChestProvisioning.withinHomeInteriorFootprint(thirdBed, null),
				"the exterior block layer of a resolved CTOV house is reserved for its shell, not chest placement");
			installRoom(level, bed, door);
			HouseTemplate.Footprint verifiedRoom = HouseTemplate.homeSpacingFootprint(level, bed);
			check(passed, verifiedRoom != null
				&& PrivateChestProvisioning.withinHomeInteriorFootprint(bed, verifiedRoom),
				"the positive house fixture has a resolved shell envelope before it can authorize private storage");
			villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
			villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), village.bell()));
			Villages.forget(villager.getUUID());
			memory.homeBed = Optional.of(bed);
			memory.homeDoor = Optional.of(door);
			memory.homeChest = Optional.empty();
			check(passed, Config.PRIVATE_CHESTS && villager.isAlive()
				&& villager.getBrain().getMemory(MemoryModuleType.HOME).filter(home -> home.equals(GlobalPos.of(level.dimension(), bed))).isPresent()
				&& memory.homeBed.equals(Optional.of(bed)) && memory.homeDoor.equals(Optional.of(door))
				&& level.getBlockState(door).getBlock() instanceof DoorBlock
				&& level.getBlockState(door).getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER,
				"eligible live resident, HOME memory and house door are valid before provisioning");
			check(passed, PrivateChestProvisioning.connectedInterior(level, bed, bed.east(1)),
				"a candidate reachable from the bed through the roofed room is recognized as interior");
			check(passed, PrivateChestProvisioning.findPlacement(level, bed, null) == null
				&& !PrivateChestProvisioning.validPlacement(level, bed.east(1), bed, null),
				"a room without its assigned, verified home door cannot authorize a private chest position");
			installUnverifiedRoom(level, unverifiedBed, unverifiedDoor);
			check(passed, HouseTemplate.homeSpacingFootprint(level, unverifiedBed) == null
				&& PrivateChestProvisioning.findPlacement(level, unverifiedBed, unverifiedDoor) == null
				&& !PrivateChestProvisioning.validPlacement(level, unverifiedBed.east(1), unverifiedBed, unverifiedDoor),
				"a roofed room with an unverified door-bearing shell cannot authorize placement in an ambiguous interior or outside nook");
			for (int y = 0; y <= 1; y++) for (int z = -3; z <= 3; z++) {
				level.setBlock(bed.offset(1, y, z), Blocks.STONE.defaultBlockState(), 3);
			}
			check(passed, !PrivateChestProvisioning.connectedInterior(level, bed, bed.east(2)),
				"a roofed candidate behind a solid interior partition is not reachable from the resident's bed");
			for (int y = 0; y <= 1; y++) for (int z = -3; z <= 3; z++) {
				level.setBlock(bed.offset(1, y, z), Blocks.AIR.defaultBlockState(), 3);
			}
			// A side awning can become reachable and even fall inside a generated piece's
			// bounding box. It is still outside storage space when the house's opposite wall
			// is the only thing that bounds it at long range.
			BlockPos sideEave = bed.west(4);
			for (int y = 0; y <= 1; y++) level.setBlock(bed.west(4).above(y), Blocks.AIR.defaultBlockState(), 3);
			for (int z = -1; z <= 1; z++) {
				level.setBlock(bed.offset(-5, -1, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
				level.setBlock(bed.offset(-5, 3, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
			}
			for (int y = 0; y <= 2; y++) level.setBlock(bed.west(5).above(y), Blocks.STONE_BRICKS.defaultBlockState(), 3);
			HouseTemplate.Footprint sideEaveFootprint = HouseTemplate.homeSpacingFootprint(level, bed);
			BlockPos interiorAfterEave = PrivateChestProvisioning.findPlacement(level, bed, door);
			check(passed, sideEaveFootprint != null
				&& PrivateChestProvisioning.withinHomeInteriorFootprint(sideEave, sideEaveFootprint)
				&& PrivateChestProvisioning.connectedInterior(level, bed, sideEave)
				&& !PrivateChestProvisioning.validPlacement(level, sideEave, bed, door)
				&& interiorAfterEave != null && !interiorAfterEave.equals(sideEave)
				&& PrivateChestProvisioning.withinHomeInteriorFootprint(interiorAfterEave, sideEaveFootprint)
				&& PrivateChestProvisioning.validPlacement(level, interiorAfterEave, bed, door),
				"a connected roofed side eave inside the coarse piece envelope is rejected while an ordinary interior location remains valid");
			for (int y = 0; y <= 1; y++) level.setBlock(bed.west(4).above(y), Blocks.STONE_BRICKS.defaultBlockState(), 3);
			for (int z = -1; z <= 1; z++) {
				level.setBlock(bed.offset(-5, -1, z), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(bed.offset(-5, 3, z), Blocks.AIR.defaultBlockState(), 3);
			}
			for (int y = 0; y <= 2; y++) level.setBlock(bed.west(5).above(y), Blocks.AIR.defaultBlockState(), 3);
			BlockPos detachedPorch = bed.east(5);
			level.getChunkAt(detachedPorch.east());
			level.setBlock(detachedPorch.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(detachedPorch.above(3), Blocks.STONE.defaultBlockState(), 3);
			for (int y = 0; y <= 2; y++) {
				level.setBlock(detachedPorch.east().above(y), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(detachedPorch.north().above(y), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(detachedPorch.south().above(y), Blocks.STONE.defaultBlockState(), 3);
			}
			check(passed, !PrivateChestProvisioning.connectedInterior(level, bed, detachedPorch),
				"a roofed, walled outdoor nook beyond the home's exterior wall is disconnected from the bed's interior");
			level.setBlock(detachedPorch.below(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(detachedPorch.above(3), Blocks.AIR.defaultBlockState(), 3);
			for (int y = 0; y <= 2; y++) {
				level.setBlock(detachedPorch.east().above(y), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(detachedPorch.north().above(y), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(detachedPorch.south().above(y), Blocks.AIR.defaultBlockState(), 3);
			}
			removeChestItems(stocks);
			Housing.update(level, villager, memory);
			check(passed, memory.homeChest.isEmpty() && chestCount(stocks) == 0 && PrivateChestProvisioning.isPending(villager),
				"normal housing update leaves an unprovisioned resident pending when public chest stock is missing");
			BlockPos openWall = bed.east(4);
			for (int y = 0; y <= 2; y++) level.setBlock(openWall.above(y), Blocks.AIR.defaultBlockState(), 3);
			BlockPos roofedExtension = bed.east(5);
			level.setBlock(roofedExtension.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(roofedExtension.above(3), Blocks.STONE.defaultBlockState(), 3);
			addChest(stocks.getFirst());
			BlockPos safeInteriorPlacement = PrivateChestProvisioning.findPlacement(level, bed, door);
			boolean extensionProvisioned = PrivateChestProvisioning.ensure(level, villager, memory, village);
			BlockPos safeHomeChest = memory.homeChest.orElse(null);
			check(passed, !PrivateChestProvisioning.connectedInterior(level, bed, roofedExtension)
				&& safeInteriorPlacement != null && !safeInteriorPlacement.equals(roofedExtension)
				&& PrivateChestProvisioning.withinHomeInteriorFootprint(safeInteriorPlacement, verifiedRoom)
				&& extensionProvisioned && safeHomeChest != null && safeHomeChest.equals(safeInteriorPlacement)
				&& PrivateChestProvisioning.validOwnedHomeChest(level, safeHomeChest, bed, door, villager.getUUID())
				&& chestCount(stocks) == 0 && !PrivateChestProvisioning.isPending(villager)
				&& level.getBlockState(roofedExtension).isAir(),
				"a roofed outdoor extension stays ineligible while a verified interior cell can receive the paid, owner-marked chest");
			if (safeHomeChest != null) {
				BlockEntity placedEntity = level.getBlockEntity(safeHomeChest);
				if (placedEntity instanceof Container placedContainer) {
					placedContainer.clearContent();
					placedContainer.setChanged();
				}
				level.setBlock(safeHomeChest, Blocks.AIR.defaultBlockState(), 3);
			}
			memory.homeChest = Optional.empty();
			removeChestItems(stocks);
			level.setBlock(roofedExtension.below(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(roofedExtension.above(3), Blocks.AIR.defaultBlockState(), 3);
			for (int y = 0; y <= 2; y++) level.setBlock(openWall.above(y), Blocks.STONE.defaultBlockState(), 3);
			villager.getBrain().eraseMemory(MemoryModuleType.HOME);
			memory.homeBed = Optional.empty();
			memory.homeDoor = Optional.empty();
			Housing.update(level, villager, memory);
			check(passed, memory.homeChest.isEmpty() && PrivateChestProvisioning.isPending(villager) && chestCount(stocks) == 0,
				"housing keeps the private-chest request pending while a resident has no assigned bed yet");
			TagValueOutput noHomeSave = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			villager.saveWithoutId(noHomeSave);
			Villager noHomeResident = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (noHomeResident == null) throw new IllegalStateException("Could not create the no-home resident reload fixture");
			try {
				noHomeResident.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), noHomeSave.buildResult()));
				VillagerMemory noHomeMemory = noHomeResident.getAttachedOrCreate(VillagerPlus.MEMORY);
				check(passed, PrivateChestProvisioning.isPending(noHomeResident) && noHomeMemory.homeBed.isEmpty()
					&& noHomeMemory.homeChest.isEmpty(),
					"an unresolved no-home private-chest request survives villager entity save/load");
				noHomeResident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
				Housing.update(level, noHomeResident, noHomeMemory);
				check(passed, noHomeMemory.homeBed.equals(Optional.of(bed)) && noHomeMemory.homeChest.isEmpty()
					&& PrivateChestProvisioning.isPending(noHomeResident) && chestCount(stocks) == 0,
					"the normal housing retry discovers a later HOME assignment and retains demand until stock arrives");
			} finally {
				noHomeResident.discard();
			}
			villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
			memory.homeBed = Optional.of(bed);
			memory.homeDoor = Optional.of(door);
			Housing.update(level, villager, memory);
			TagValueOutput pendingSave = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			villager.saveWithoutId(pendingSave);
			Villager pendingResident = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (pendingResident == null) throw new IllegalStateException("Could not create the pending-resident reload fixture");
			try {
				pendingResident.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), pendingSave.buildResult()));
				VillagerMemory pendingMemory = pendingResident.getAttachedOrCreate(VillagerPlus.MEMORY);
				check(passed, PrivateChestProvisioning.isPending(pendingResident)
					&& pendingMemory.homeBed.equals(Optional.of(bed)) && pendingMemory.homeDoor.equals(Optional.of(door))
					&& pendingMemory.homeChest.isEmpty(),
					"unfulfilled housing demand and home coordinates survive a villager entity save/load");
				Housing.update(level, pendingResident, pendingMemory);
				check(passed, PrivateChestProvisioning.isPending(pendingResident) && pendingMemory.homeChest.isEmpty()
					&& chestCount(stocks) == 0,
					"the normal housing update keeps the saved request pending while public chest stock is unavailable");
			} finally {
				pendingResident.discard();
			}

			addChest(stocks.getFirst());
			removeRoof(level, bed);
			Housing.update(level, villager, memory);
			check(passed, memory.homeChest.isEmpty() && chestCount(stocks) == 1 && PrivateChestProvisioning.isPending(villager),
				"late public supply waits without consuming its chest while the home has no roof");
			installRoof(level, bed);

			// A covered outside eave shares a wall with the house but is not inside it.
			fillRoom(level, bed, door);
			BlockPos porch = bed.east(5);
			level.getChunkAt(porch.east());
			level.setBlock(porch, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(porch.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(porch.above(3), Blocks.STONE.defaultBlockState(), 3);
			check(passed, PrivateChestProvisioning.findPlacement(level, bed, door) == null,
				"a covered outdoor cell next to the house wall is not a private chest location");
			Housing.update(level, villager, memory);
			check(passed, memory.homeChest.isEmpty() && chestCount(stocks) == 1,
				"an outdoor-only candidate leaves the public chest and private request intact");
			level.setBlock(porch.above(3), Blocks.AIR.defaultBlockState(), 3);
			clearRoom(level, bed, door);
			installRoom(level, bed, door);

			fillRoom(level, bed, door);
			int openPlacementCells = 0;
			for (BlockPos pos : BlockPos.betweenClosed(bed.offset(-5, -1, -5), bed.offset(5, 2, 5))) {
				if (!pos.equals(bed) && !pos.equals(bed.north()) && !pos.equals(door) && !pos.equals(door.above())
					&& level.getBlockState(pos).isAir()) openPlacementCells++;
			}
			check(passed, openPlacementCells == 0 && PrivateChestProvisioning.findPlacement(level, bed, door) == null,
				"blocked-room fixture has no safe bounded chest-placement cell");
			Housing.update(level, villager, memory);
			check(passed, memory.homeChest.isEmpty() && chestCount(stocks) == 1 && PrivateChestProvisioning.isPending(villager)
				&& chestBlocksNear(level, bed) == 0 && level.getBlockState(bed.east(1)).is(Blocks.STONE),
				"blocked home placement preserves public stock and existing blocks for a later housing retry");

			clearRoom(level, bed, door);
			installRoom(level, bed, door);
			int nonChestStockBeforePayment = totalItemsExcept(stocks, Items.CHEST);
			BlockPos protectedContainerPos = PrivateChestProvisioning.findPlacement(level, bed, door);
			if (protectedContainerPos == null) throw new IllegalStateException("Private chest container fixture has no safe candidate");
			level.setBlock(protectedContainerPos, Blocks.CHEST.defaultBlockState(), 3);
			Container protectedContainer = HopperBlockEntity.getContainerAt(level, protectedContainerPos);
			if (protectedContainer == null) throw new IllegalStateException("Private chest container fixture has no inventory");
			protectedContainer.setItem(0, new ItemStack(Items.BREAD, 5));
			protectedContainer.setChanged();
			check(passed, !PrivateChestProvisioning.validPlacement(level, protectedContainerPos, bed, door)
				&& level.getBlockState(protectedContainerPos).getBlock() instanceof ChestBlock
				&& HopperBlockEntity.getContainerAt(level, protectedContainerPos) == protectedContainer
				&& protectedContainer.getItem(0).is(Items.BREAD) && protectedContainer.getItem(0).getCount() == 5,
				"an existing container and its real inventory are never overwritten by a private chest candidate");
			protectedContainer.clearContent();
			protectedContainer.setChanged();
			level.setBlock(protectedContainerPos, Blocks.AIR.defaultBlockState(), 3);
			BlockPos occupiedCandidate = PrivateChestProvisioning.findPlacement(level, bed, door);
			if (occupiedCandidate == null) throw new IllegalStateException("Private chest occupancy fixture has no safe candidate");
			villager.setPos(occupiedCandidate.getX() + 0.5, occupiedCandidate.getY(), occupiedCandidate.getZ() + 0.5);
			check(passed, PrivateChestProvisioning.ensure(level, villager, memory, village)
				&& memory.homeChest.isPresent() && !memory.homeChest.get().equals(occupiedCandidate)
				&& Ownership.isOwnedBy(level, memory.homeChest.get(), villager.getUUID())
				&& chestCount(stocks) == 0,
				"placement skips an otherwise valid chest cell occupied by a living entity and uses another safe cell");
			// Keep the villager on the deliberately occupied candidate until assertions finish. Returning
			// them to the original position could put them inside the newly selected private chest.
			boolean provisioned = memory.homeChest.isPresent();
			check(passed, provisioned && !PrivateChestProvisioning.isPending(villager),
				"normal housing retry provisions after real stock and suitable home space become available");
			BlockPos placed = memory.homeChest.orElseThrow(() -> new IllegalStateException("Provisioning did not persist the chest location"));
			HouseTemplate.Footprint firstHomeFootprint = HouseTemplate.homeSpacingFootprint(level, bed);
			// connectedInterior validates an empty walk cell; the placed chest occupies that cell,
			// so validate its adjacency to the proven room through validOwnedHomeChest instead.
			check(passed, level.getBlockState(placed).getBlock() instanceof ChestBlock
				&& PrivateChestProvisioning.withinHomeFootprint(placed, firstHomeFootprint)
				&& PrivateChestProvisioning.withinHomeInteriorFootprint(placed, firstHomeFootprint)
				&& PrivateChestProvisioning.validOwnedHomeChest(level, placed, bed, door, villager.getUUID())
				&& Ownership.isOwnedBy(level, placed, villager.getUUID()) && chestCount(stocks) == 0
				&& totalItemsExcept(stocks, Items.CHEST) == nonChestStockBeforePayment,
				"placement stays in the proven house interior, consumes exactly one real public chest, and marks its owner");
			check(passed, Lager.publicContainerAt(level, placed) == null && !Lager.materialChests(level, village).contains(placed),
				"owner-marked private storage is excluded from public community containers");
			villager.getBrain().eraseMemory(MemoryModuleType.HOME);
			Housing.update(level, villager, memory);
			check(passed, memory.homeChest.equals(Optional.of(placed)) && PrivateChestProvisioning.isPending(villager)
				&& Ownership.isOwnedBy(level, placed, villager.getUUID()) && chestCount(stocks) == 0,
				"a temporarily absent HOME keeps only the verified owner-chest pointer and pending retry without charging stock");
			villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
			Housing.update(level, villager, memory);
			check(passed, memory.homeChest.equals(Optional.of(placed)) && !PrivateChestProvisioning.isPending(villager)
				&& chestCount(stocks) == 0 && chestBlocksNear(level, bed) == 1,
				"restoring HOME reuses the safe assigned interior chest instead of creating a duplicate");

			BlockEntity chestEntity = level.getBlockEntity(placed);
			var savedEntity = chestEntity.saveWithFullMetadata(level.registryAccess());
			BlockEntity loadedEntity = BlockEntity.loadStatic(placed, level.getBlockState(placed), savedEntity, level.registryAccess());
			TagValueOutput residentSave = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			villager.saveWithoutId(residentSave);
			Villager loadedResident = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (loadedResident == null) throw new IllegalStateException("Could not create the provisioned-resident reload fixture");
			VillagerMemory loadedMemory;
			try {
				loadedResident.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), residentSave.buildResult()));
				loadedMemory = loadedResident.getAttachedOrCreate(VillagerPlus.MEMORY);
				check(passed, loadedEntity != null && villager.getUUID().equals(loadedEntity.getAttached(VillagerPlus.OWNER))
					&& loadedMemory.homeChest.equals(Optional.of(placed)) && !PrivateChestProvisioning.isPending(loadedResident)
					&& loadedResident.getBrain().getMemory(MemoryModuleType.HOME).filter(home -> home.equals(GlobalPos.of(level.dimension(), bed))).isPresent(),
					"owner, home, and provisioned chest survive block-entity and villager entity save/load round trips");
				Housing.update(level, loadedResident, loadedMemory);
				check(passed, loadedMemory.homeChest.equals(Optional.of(placed)) && chestCount(stocks) == 0
					&& Ownership.isOwnedBy(level, placed, loadedResident.getUUID()) && !PrivateChestProvisioning.isPending(loadedResident),
					"normal housing update after entity reload reuses its owned chest without charging or duplicating it");
			} finally {
				loadedResident.discard();
			}

			Container oldPrivate = HopperBlockEntity.getContainerAt(level, placed);
			if (oldPrivate == null) throw new IllegalStateException("Original private chest container disappeared");
			ItemStack namedBread = new ItemStack(Items.BREAD, 3);
			namedBread.set(DataComponents.CUSTOM_NAME, Component.literal("Saved home provisions"));
			oldPrivate.setItem(0, namedBread);
			oldPrivate.setItem(1, new ItemStack(Items.CHEST));
			oldPrivate.setChanged();
			List<ItemStack> firstResidentContents = snapshotContents(oldPrivate);
			check(passed, Lager.publicContainerAt(level, placed) == null && !Lager.materialChests(level, village).contains(placed)
				&& total(oldPrivate, Items.BREAD) == 3 && total(oldPrivate, Items.CHEST) == 1,
				"a community scan cannot treat the resident's stored chest item as public supply");

			Villager secondResident = EntityTypes.VILLAGER.spawn(level, village.bell().east(8), EntitySpawnReason.COMMAND);
			if (secondResident == null) throw new IllegalStateException("Could not create the second resident fixture");
			try {
				installRoom(level, secondBed, secondDoor);
				secondResident.setNoAi(true);
				secondResident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), secondBed));
				secondResident.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), village.bell()));
				Villages.forget(secondResident.getUUID());
				VillagerMemory secondMemory = secondResident.getAttachedOrCreate(VillagerPlus.MEMORY);
				secondMemory.homeBed = Optional.of(secondBed);
				secondMemory.homeDoor = Optional.of(secondDoor);
				// Simulate a stale/copied pointer. The ownership check must not let a new resident inherit this chest.
				secondMemory.homeChest = Optional.of(placed);
				check(passed, secondBed.distManhattan(bed) > 10 && !secondDoor.equals(door)
					&& level.getBlockState(secondDoor).getBlock() instanceof DoorBlock
					&& level.getBlockState(secondDoor).getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
					&& level.getBlockState(secondDoor.above()).getBlock() instanceof DoorBlock
					&& level.getBlockState(secondBed.east(4)).is(Blocks.STONE_BRICKS)
					&& level.getBlockState(secondBed.west(4)).is(Blocks.STONE_BRICKS)
					&& level.getBlockState(secondBed.north(4)).is(Blocks.STONE_BRICKS)
					&& level.getBlockState(secondBed.above(3)).is(Blocks.STONE_BRICKS),
					"second resident has an independent enclosed home with its own lower and upper door blocks");
				addChest(stocks.getFirst());
				Housing.update(level, secondResident, secondMemory);
				BlockPos secondChest = secondMemory.homeChest.orElseThrow(() -> new IllegalStateException("Second resident was not provisioned"));
				check(passed, !secondChest.equals(placed) && near(secondChest, secondBed)
					&& Ownership.isOwnedBy(level, secondChest, secondResident.getUUID())
					&& Ownership.isOwnedBy(level, placed, villager.getUUID())
					&& contentsMatch(oldPrivate, firstResidentContents)
					&& chestCount(stocks) == 0
					&& total(oldPrivate, Items.CHEST) == 1,
					"a second resident receives a distinct paid chest without drawing from the first resident's storage");
				Housing.update(level, secondResident, secondMemory);
				check(passed, secondMemory.homeChest.equals(Optional.of(secondChest)) && chestCount(stocks) == 0
					&& Ownership.isOwnedBy(level, secondChest, secondResident.getUUID()),
					"repeated normal housing updates remain idempotent for each resident independently");
			} finally {
				Villages.forget(secondResident.getUUID());
				secondResident.discard();
			}

			installRoom(level, movedBed, movedDoor);
			villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), movedBed));
			// Keep the old bed/door and chest state deliberately. Housing.update must track the new HOME,
			// find its door, and preserve the old chest pointer for PrivateChestProvisioning to verify.
			memory.homeChest = Optional.of(placed);
			List<ItemStack> oldHomeContents = snapshotContents(oldPrivate);
			addChest(stocks.getFirst());
			int migrationStockBefore = chestCount(stocks);
			check(passed, !near(placed, movedBed) && level.hasChunkAt(placed) && level.hasChunkAt(movedBed)
				&& Ownership.isOwnedBy(level, placed, villager.getUUID()),
				"the remembered owner chest is loaded and verifiable beyond the new home's short scan radius");
			fillRoom(level, movedBed, movedDoor);
			Housing.update(level, villager, memory);
			check(passed, memory.homeBed.equals(Optional.of(movedBed)) && memory.homeDoor.equals(Optional.of(movedDoor))
				&& memory.homeChest.equals(Optional.of(placed)) && PrivateChestProvisioning.isPending(villager)
				&& chestCount(stocks) == migrationStockBefore
				&& level.getBlockState(placed).getBlock() instanceof ChestBlock
				&& Ownership.isOwnedBy(level, placed, villager.getUUID()) && contentsMatch(oldPrivate, oldHomeContents),
				"Housing.trackHome keeps the far owner chest pending when the new home has no safe destination, preserving every slot and public stock");
			clearRoom(level, movedBed, movedDoor);
			installRoom(level, movedBed, movedDoor);
			Housing.update(level, villager, memory);
			check(passed, memory.homeBed.equals(Optional.of(movedBed))
				&& memory.homeDoor.equals(Optional.of(movedDoor))
				&& memory.homeChest.isPresent() && !memory.homeChest.get().equals(placed),
				"a later retry moves the far remembered owner chest into the now-safe current home");
			BlockPos movedChest = memory.homeChest.orElseThrow(() -> new IllegalStateException("Moved resident has no current-home chest"));
			Container currentPrivate = HopperBlockEntity.getContainerAt(level, movedChest);
			check(passed, !movedChest.equals(placed) && near(movedChest, movedBed)
				&& PrivateChestProvisioning.validOwnedHomeChest(level, movedChest, movedBed, movedDoor, villager.getUUID())
				&& Ownership.isOwnedBy(level, movedChest, villager.getUUID()) && chestCount(stocks) == migrationStockBefore
				&& level.getBlockState(placed).isAir() && level.getBlockEntity(placed) == null
				&& currentPrivate != null && contentsMatch(currentPrivate, oldHomeContents)
				&& !PrivateChestProvisioning.isPending(villager),
				"migration preserves every item slot and component in a verified in-home chest without charging stock");
			Housing.update(level, villager, memory);
			check(passed, memory.homeChest.equals(Optional.of(movedChest))
				&& chestCount(stocks) == migrationStockBefore
				&& chestBlocksNear(level, movedBed) == 1 && contentsMatch(currentPrivate, oldHomeContents),
				"repeated housing retry is idempotent after migration and does not duplicate the chest or contents");
			removeChestItems(stocks);

			installRoomWithoutDoor(level, doorlessBed);
			memory.homeBed = Optional.of(doorlessBed);
			memory.homeDoor = Optional.empty();
			memory.homeChest = Optional.empty();
			villager.getBrain().eraseMemory(MemoryModuleType.HOME);
			addChest(stocks.getFirst());
			Villages.forget(villager.getUUID());
			BlockPos unassignedCandidate = PrivateChestProvisioning.findPlacement(level, doorlessBed, null);
			Housing.update(level, villager, memory);
			check(passed, Config.PRIVATE_CHESTS && unassignedCandidate == null
				&& memory.homeChest.isEmpty() && chestCount(stocks) == 1 && PrivateChestProvisioning.isPending(villager)
				&& chestBlocksNear(level, doorlessBed) == 0,
				"an unverified doorless shell reserves demand but has no valid placement until a house envelope is available");

			TagValueOutput unassignedSave = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			villager.saveWithoutId(unassignedSave);
			Villager unassignedResident = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (unassignedResident == null) throw new IllegalStateException("Could not create the unassigned-home retry fixture");
			try {
				unassignedResident.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), unassignedSave.buildResult()));
				VillagerMemory unassignedMemory = unassignedResident.getAttachedOrCreate(VillagerPlus.MEMORY);
				check(passed, unassignedResident.getBrain().getMemory(MemoryModuleType.HOME).isEmpty()
					&& PrivateChestProvisioning.isPending(unassignedResident)
					&& unassignedMemory.homeBed.equals(Optional.of(doorlessBed)) && unassignedMemory.homeChest.isEmpty()
					&& chestCount(stocks) == 1 && chestBlocksNear(level, doorlessBed) == 0,
					"the no-HOME request and recorded bed survive entity save/load without creating a chest");

				unassignedResident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), doorlessBed));
				unassignedResident.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), village.bell()));
				Villages.forget(unassignedResident.getUUID());
				Housing.update(level, unassignedResident, unassignedMemory);
				check(passed, unassignedMemory.homeBed.equals(Optional.of(doorlessBed)) && unassignedMemory.homeDoor.isEmpty()
					&& unassignedMemory.homeChest.isEmpty() && PrivateChestProvisioning.isPending(unassignedResident)
					&& chestCount(stocks) == 1 && chestBlocksNear(level, doorlessBed) == 0,
					"HOME assignment preserves the saved request while the resident house still has no verified envelope");
				Housing.update(level, unassignedResident, unassignedMemory);
				check(passed, unassignedMemory.homeChest.isEmpty() && PrivateChestProvisioning.isPending(unassignedResident)
					&& chestCount(stocks) == 1 && chestBlocksNear(level, doorlessBed) == 0,
					"repeated retry defers without consuming or duplicating stock while the home remains unverified");
			} finally {
				Villages.forget(unassignedResident.getUUID());
				unassignedResident.discard();
			}

			Villager displacedResident = EntityTypes.VILLAGER.spawn(level, village.bell().east(8), EntitySpawnReason.COMMAND);
			if (displacedResident == null) throw new IllegalStateException("Could not create the displaced-chest resident fixture");
			try {
				installCtovRoom(level, thirdBed, thirdDoor);
				for (int chunkX = (thirdBed.getX() - 6) >> 4; chunkX <= (thirdBed.getX() + 6) >> 4; chunkX++) {
					for (int chunkZ = (thirdBed.getZ() - 6) >> 4; chunkZ <= (thirdBed.getZ() + 6) >> 4; chunkZ++) {
						level.getChunkAt(new BlockPos(chunkX << 4, thirdBed.getY(), chunkZ << 4));
					}
				}
				displacedResident.setNoAi(true);
				displacedResident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), thirdBed));
				displacedResident.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), village.bell()));
				Villages.forget(displacedResident.getUUID());
				VillagerMemory displacedMemory = displacedResident.getAttachedOrCreate(VillagerPlus.MEMORY);
				displacedMemory.homeBed = Optional.of(thirdBed);
				displacedMemory.homeDoor = Optional.of(thirdDoor);
				HouseTemplate.Footprint offsetFootprint = HouseTemplate.homeSpacingFootprint(level, thirdBed);
				check(passed, offsetFootprint != null && offsetFootprint.minX() == thirdBed.getX() - 3
					&& offsetFootprint.maxX() == thirdBed.getX() + 3 && offsetFootprint.minZ() == thirdBed.getZ() - 4
					&& offsetFootprint.maxZ() == thirdBed.getZ() + 4
					&& !PrivateChestProvisioning.withinHomeFootprint(thirdBed.east(5), offsetFootprint),
					"an offset, non-square house resolves its connected footprint and rejects an exterior chest position [footprint="
						+ offsetFootprint + ", bed=" + thirdBed + "]");
				BlockPos shellOpening = thirdBed.west(3);
				for (int y = 0; y <= 1; y++) level.setBlock(shellOpening.above(y), Blocks.AIR.defaultBlockState(), 3);
				BlockPos interiorCandidate = PrivateChestProvisioning.findPlacement(level, thirdBed, thirdDoor);
				check(passed, PrivateChestProvisioning.withinHomeFootprint(shellOpening, offsetFootprint)
					&& !PrivateChestProvisioning.withinHomeInteriorFootprint(shellOpening, offsetFootprint)
					&& !PrivateChestProvisioning.connectedInterior(level, thirdBed, shellOpening)
					&& !PrivateChestProvisioning.validPlacement(level, shellOpening, thirdBed, thirdDoor)
					&& interiorCandidate != null && !interiorCandidate.equals(shellOpening)
					&& PrivateChestProvisioning.withinHomeInteriorFootprint(interiorCandidate, offsetFootprint)
					&& PrivateChestProvisioning.connectedInterior(level, thirdBed, interiorCandidate),
					"a wall opening at the house boundary is rejected while a safe connected room cell remains selectable");
				for (int y = 0; y <= 2; y++) level.setBlock(shellOpening.above(y), Blocks.STONE_BRICKS.defaultBlockState(), 3);
				BlockPos outsideChest = thirdBed.east(5);
				for (Direction direction : Direction.Plane.HORIZONTAL) level.getChunkAt(outsideChest.relative(direction));
				level.setBlock(outsideChest, Blocks.CHEST.defaultBlockState(), 3);
				BlockEntity outsideEntity = level.getBlockEntity(outsideChest);
				Container outsideContainer = HopperBlockEntity.getContainerAt(level, outsideChest);
				if (outsideEntity == null || outsideContainer == null) throw new IllegalStateException("Exterior chest fixture has no container");
				outsideEntity.setAttached(VillagerPlus.OWNER, displacedResident.getUUID());
				outsideContainer.setItem(0, new ItemStack(Items.BREAD, 7));
				outsideContainer.setItem(1, new ItemStack(Items.DIAMOND, 2));
				outsideEntity.setChanged();
				displacedMemory.homeChest = Optional.of(outsideChest);
				int chestStockBeforeMigration = chestCount(stocks);
				check(passed, !PrivateChestProvisioning.withinHomeFootprint(outsideChest, offsetFootprint)
					&& !PrivateChestProvisioning.validOwnedHomeChest(level, outsideChest, thirdBed, thirdDoor, displacedResident.getUUID())
					&& chestStockBeforeMigration == chestCount(stocks),
					"an owner-marked chest outside the resolved home envelope is invalid without changing public stock");
				Housing.update(level, displacedResident, displacedMemory);
				BlockPos migratedChest = displacedMemory.homeChest.orElseThrow(
					() -> new IllegalStateException("The exterior private chest was not moved into its home"));
				Container migratedContents = HopperBlockEntity.getContainerAt(level, migratedChest);
				check(passed, !migratedChest.equals(outsideChest) && Math.abs(migratedChest.getX() - thirdBed.getX()) <= 4
					&& PrivateChestProvisioning.withinHomeFootprint(migratedChest, offsetFootprint)
					&& PrivateChestProvisioning.validOwnedHomeChest(level, migratedChest, thirdBed, thirdDoor, displacedResident.getUUID())
					&& migratedContents != null && total(migratedContents, Items.BREAD) == 7
					&& total(migratedContents, Items.DIAMOND) == 2
					&& Ownership.isOwnedBy(level, migratedChest, displacedResident.getUUID())
					&& level.getBlockState(outsideChest).isAir() && level.getBlockEntity(outsideChest) == null
					&& chestCount(stocks) == chestStockBeforeMigration && !PrivateChestProvisioning.isPending(displacedResident),
					"a single owned exterior chest moves into the bounded home with all contents and without charging public stock");
			} finally {
				Villages.forget(displacedResident.getUUID());
				displacedResident.discard();
			}
			return passed[0];
		} finally {
			clearFixture(level, fixture);
			restoreStocks(stocks);
			originalMemory.homeBed = oldBed;
			originalMemory.homeDoor = oldDoor;
			originalMemory.homeChest = oldChest;
			originalMemory.name = oldName;
			originalMemory.signPos = oldSignPos;
			originalMemory.signText = oldSignText;
			villager.setAttached(VillagerPlus.MEMORY, originalMemory);
			villager.setAttached(VillagerPlus.PRIVATE_CHEST_PENDING, oldPending);
			Villages.forget(villager.getUUID());
			if (oldHome.isPresent()) villager.getBrain().setMemory(MemoryModuleType.HOME, oldHome.get());
			else villager.getBrain().eraseMemory(MemoryModuleType.HOME);
			if (oldMeeting.isPresent()) villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, oldMeeting.get());
			else villager.getBrain().eraseMemory(MemoryModuleType.MEETING_POINT);
			villager.setCustomName(oldCustomName);
			villager.setCustomNameVisible(oldNameVisible);
		}
	}

	private static void installRoom(ServerLevel level, BlockPos bed, BlockPos door) {
		installRoom(level, bed, door, Blocks.STONE_BRICKS);
	}

	private static void installUnverifiedRoom(ServerLevel level, BlockPos bed, BlockPos door) {
		installRoom(level, bed, door, Blocks.STONE);
	}

	private static void installRoom(ServerLevel level, BlockPos bed, BlockPos door, Block shell) {
		for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
			level.setBlock(bed.offset(x, -1, z), shell.defaultBlockState(), 3);
		}
		installRoof(level, bed, shell);
		for (int y = 0; y <= 2; y++) for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
			if ((Math.abs(x) == 4 || Math.abs(z) == 4) && !(x == 0 && z == 4 && y <= 1)) {
				level.setBlock(bed.offset(x, y, z), shell.defaultBlockState(), 3);
			}
		}
		installBed(level, bed);
		var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
		var upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
		level.setBlock(door, lower, 3);
		level.setBlock(door.above(), upper, 3);
	}

	private static void installRoomWithoutDoor(ServerLevel level, BlockPos bed) {
		for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
			level.setBlock(bed.offset(x, -1, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
		}
		installRoof(level, bed, Blocks.STONE_BRICKS);
		for (int y = 0; y <= 2; y++) for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
			if (Math.abs(x) == 4 || Math.abs(z) == 4) {
				level.setBlock(bed.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
			}
		}
		installBed(level, bed);
	}

	/** Offset doorway and rectangular footprint model a generated village piece instead of the default square test house.
	 * Stone bricks keep the fixture distinguishable from natural base stone in the bounded home flood. */
	private static void installCtovRoom(ServerLevel level, BlockPos bed, BlockPos door) {
		for (int x = -3; x <= 3; x++) for (int z = -4; z <= 4; z++) {
			level.setBlock(bed.offset(x, -1, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
			level.setBlock(bed.offset(x, 3, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
		}
		for (int y = 0; y <= 2; y++) for (int x = -3; x <= 3; x++) for (int z = -4; z <= 4; z++) {
			if ((Math.abs(x) == 3 || Math.abs(z) == 4) && !(x == 3 && z == 0 && y <= 1)) {
				level.setBlock(bed.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
			}
		}
		installBed(level, bed);
		var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
			.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
		level.setBlock(door, lower, 3);
		level.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
	}

	private static void installBed(ServerLevel level, BlockPos bed) {
		var foot = Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
		var head = foot.setValue(BedBlock.PART, BedPart.HEAD);
		level.setBlock(bed, foot, 3);
		level.setBlock(bed.north(), head, 3);
	}

	private static void installRoof(ServerLevel level, BlockPos bed) {
		installRoof(level, bed, Blocks.STONE_BRICKS);
	}

	private static void installRoof(ServerLevel level, BlockPos bed, Block roof) {
		for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
			level.setBlock(bed.offset(x, 3, z), roof.defaultBlockState(), 3);
		}
	}

	private static void removeRoof(ServerLevel level, BlockPos bed) {
		for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
			level.setBlock(bed.offset(x, 3, z), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static void fillRoom(ServerLevel level, BlockPos bed, BlockPos door) {
		for (BlockPos pos : BlockPos.betweenClosed(bed.offset(-5, -1, -5), bed.offset(5, 2, 5))) {
			if (!pos.equals(bed) && !pos.equals(bed.north()) && !pos.equals(door) && !pos.equals(door.above())) {
				level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
			}
		}
	}

	private static void clearRoom(ServerLevel level, BlockPos bed, BlockPos door) {
		for (BlockPos pos : BlockPos.betweenClosed(bed.offset(-5, -1, -5), bed.offset(5, 2, 5))) {
			if (!pos.equals(bed) && !pos.equals(bed.north()) && !pos.equals(door) && !pos.equals(door.above())) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	private static void clearFixture(ServerLevel level, List<BlockPos> fixture) {
		for (BlockPos pos : fixture) {
			Container container = HopperBlockEntity.getContainerAt(level, pos);
			if (container != null) {
				for (int slot = 0; slot < container.getContainerSize(); slot++) container.setItem(slot, ItemStack.EMPTY);
				container.setChanged();
			}
		}
		for (BlockPos pos : fixture) {
			if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static List<BlockPos> fixturePositions(BlockPos bed) {
		List<BlockPos> positions = new ArrayList<>();
		for (int y = -1; y <= 3; y++) for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
			positions.add(bed.offset(x, y, z).immutable());
		}
		return positions;
	}

	private static int totalItemsExcept(List<ContainerSnapshot> stocks, Item excluded) {
		int count = 0;
		for (ContainerSnapshot snapshot : stocks) {
			for (int slot = 0; slot < snapshot.container().getContainerSize(); slot++) {
				ItemStack stack = snapshot.container().getItem(slot);
				if (!stack.isEmpty() && !stack.is(excluded)) count += stack.getCount();
			}
		}
		return count;
	}

	private static int total(Container container, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	private static boolean near(BlockPos chest, BlockPos bed) {
		return Math.abs(chest.getX() - bed.getX()) <= 5 && Math.abs(chest.getZ() - bed.getZ()) <= 5
			&& chest.getY() >= bed.getY() - 1 && chest.getY() <= bed.getY() + 2;
	}

	private static int chestBlocksNear(ServerLevel level, BlockPos bed) {
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(bed.offset(-5, -1, -5), bed.offset(5, 2, 5))) {
			if (level.hasChunkAt(pos) && level.getBlockState(pos).getBlock() instanceof ChestBlock) count++;
		}
		return count;
	}

	private static List<ContainerSnapshot> snapshotStocks(ServerLevel level, VillageRef village) {
		List<ContainerSnapshot> out = new ArrayList<>();
		for (BlockPos pos : Lager.materialChests(level, village)) {
			Container container = Lager.publicContainerAt(level, pos);
			if (container == null) continue;
			List<ItemStack> items = new ArrayList<>();
			for (int slot = 0; slot < container.getContainerSize(); slot++) items.add(container.getItem(slot).copy());
			out.add(new ContainerSnapshot(container, items));
		}
		return out;
	}

	private static void removeChestItems(List<ContainerSnapshot> stocks) {
		for (ContainerSnapshot snapshot : stocks) {
			for (int slot = 0; slot < snapshot.container().getContainerSize(); slot++) {
				ItemStack stack = snapshot.container().getItem(slot);
				if (!stack.isEmpty() && stack.is(Items.CHEST)) snapshot.container().setItem(slot, ItemStack.EMPTY);
			}
			snapshot.container().setChanged();
		}
	}

	private static void addChest(ContainerSnapshot snapshot) {
		for (int slot = 0; slot < snapshot.container().getContainerSize(); slot++) {
			if (snapshot.container().getItem(slot).isEmpty()) {
				snapshot.container().setItem(slot, new ItemStack(Items.CHEST));
				snapshot.container().setChanged();
				return;
			}
		}
		throw new IllegalStateException("Public fixture container has no free slot for its chest item");
	}

	private static int chestCount(List<ContainerSnapshot> stocks) {
		int count = 0;
		for (ContainerSnapshot snapshot : stocks) for (int slot = 0; slot < snapshot.container().getContainerSize(); slot++) {
			ItemStack stack = snapshot.container().getItem(slot);
			if (!stack.isEmpty() && stack.is(Items.CHEST)) count += stack.getCount();
		}
		return count;
	}

	private static void restoreStocks(List<ContainerSnapshot> stocks) {
		for (ContainerSnapshot snapshot : stocks) {
			for (int slot = 0; slot < snapshot.items().size(); slot++) snapshot.container().setItem(slot, snapshot.items().get(slot).copy());
			snapshot.container().setChanged();
		}
	}

	private static void check(int[] passed, boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Private chest check failed: " + message);
		passed[0]++;
	}

	private static int chestCount(Container container) {
		int count = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (!stack.isEmpty() && stack.is(Items.CHEST)) count += stack.getCount();
		}
		return count;
	}

	private static List<ItemStack> snapshotContents(Container container) {
		List<ItemStack> contents = new ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) contents.add(container.getItem(slot).copy());
		return contents;
	}

	private static boolean contentsMatch(Container container, List<ItemStack> expected) {
		if (container.getContainerSize() != expected.size()) return false;
		for (int slot = 0; slot < expected.size(); slot++) {
			ItemStack actual = container.getItem(slot);
			ItemStack wanted = expected.get(slot);
			if (actual.getCount() != wanted.getCount() || !ItemStack.isSameItemSameComponents(actual, wanted)) return false;
		}
		return true;
	}

	private record ContainerSnapshot(Container container, List<ItemStack> items) { }
}
