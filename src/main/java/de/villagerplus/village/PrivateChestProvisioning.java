package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.HouseTemplate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;

/** Pays for one owner-marked house chest from the village's real, public stock. */
public final class PrivateChestProvisioning {
	private PrivateChestProvisioning() { }

	private static final int HOME_INTERIOR_SCAN_RADIUS = 5;
	private static final int MAX_HOME_WALL_RAY = HOME_INTERIOR_SCAN_RADIUS + 2;
	private enum ExistingChest { FOUND, NONE, INCOMPLETE_SCAN, UNSAFE }
	private record HomeInterior(Set<BlockPos> cells, boolean bounded, HouseTemplate.Footprint footprint) { }

	/**
	 * Attempts a bounded home scan and chest placement. Housing.update calls this on its normal resident interval;
	 * a persistent Fabric attachment records an unfulfilled request even before a valid home exists. It survives entity
	 * unload/save, and the same housing path retries after a bed, safe space, or public chest stock becomes available.
	 * False means no chest stock was consumed; the pending marker may still have been updated.
	 */
	public static boolean ensure(ServerLevel level, Villager villager, VillagerMemory memory, VillageRef village) {
		if (!Config.PRIVATE_CHESTS || level == null || villager == null || memory == null || !villager.isAlive()) return false;
		Optional<GlobalPos> home = villager.getBrain().getMemory(MemoryModuleType.HOME);
		BlockPos bed = memory.homeBed.orElse(null);
		if (home.isEmpty() || !home.get().dimension().equals(level.dimension()) || bed == null || !home.get().pos().equals(bed)) {
			// Keep a loaded, verified owner chest as the migration source while HOME is temporarily absent
			// or being reassigned. Once a valid anchor returns, rememberExisting either reuses it or moves
			// it into that home's proven interior. Never let an unverified pointer survive this branch.
			BlockPos remembered = memory.homeChest.orElse(null);
			if (remembered != null && level.hasChunkAt(remembered)
				&& (!(level.getBlockState(remembered).getBlock() instanceof ChestBlock)
					|| !Ownership.isOwnedBy(level, remembered, villager.getUUID()))) {
				memory.homeChest = Optional.empty();
			}
			setPending(villager, true);
			return false;
		}
		// Unlike a transient JobState timer, this attachment survives entity unload/save.
		setPending(villager, true);
		BlockPos door = memory.homeDoor.orElse(null);
		if (!level.hasChunkAt(bed) || (door != null && !level.hasChunkAt(door))) return false;
		if (!(level.getBlockState(bed).getBlock() instanceof BedBlock)
			|| (door != null && (!(level.getBlockState(door).getBlock() instanceof DoorBlock)
				|| level.getBlockState(door).getValue(DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER))) {
			memory.homeChest = Optional.empty();
			return false;
		}
		ExistingChest existingChest = rememberExisting(level, villager.getUUID(), memory, bed, door);
		if (existingChest == ExistingChest.FOUND) {
			setPending(villager, false);
			return true;
		}
		// A loaded subset cannot prove that no owned chest exists in the home's nearby chunk columns.
		// Keep both the saved location and the paid public stock untouched until the whole bounded scan is readable.
		if (existingChest != ExistingChest.NONE) return false;
		if (village == null) return false;
		UUID owner = villager.getUUID();

		BlockPos placement = findPlacement(level, bed, door);
		if (placement == null) return false;
		Source source = takePublicChest(level, village);
		if (source == null) return false;

		// Recheck after reserving stock. Server-thread execution makes this transaction atomic with respect to other mod ticks.
		if (!validPlacement(level, placement, bed, door)) {
			source.refund();
			return false;
		}
		Direction facing = placement.equals(bed) ? Direction.NORTH : Sites.facingTowards(placement, bed);
		BlockState chest = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
		if (!level.setBlock(placement, chest, 3)) {
			source.refund();
			return false;
		}
		BlockEntity entity = level.getBlockEntity(placement);
		if (entity == null || !(level.getBlockState(placement).getBlock() instanceof ChestBlock)) {
			level.setBlock(placement, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
			source.refund();
			return false;
		}
		entity.setAttached(VillagerPlus.OWNER, owner);
		entity.setChanged();
		// Validate the actual owner-marked block in the world before clearing the saved request.
		// If placement or a world update changed the candidate, remove it and restore the paid stock.
		if (!validOwnedHomeChest(level, placement, bed, door, owner)) {
			discardMigrationTarget(level, placement);
			memory.homeChest = Optional.empty();
			setPending(villager, true);
			source.refund();
			return false;
		}
		memory.homeChest = Optional.of(placement.immutable());
		setPending(villager, false);
		return true;
	}

	public static boolean isPending(Villager villager) {
		return villager != null && villager.getAttachedOrElse(VillagerPlus.PRIVATE_CHEST_PENDING, false);
	}

	/** Number of loaded residents in this village with an outstanding persisted private-chest request. */
	public static int pendingResidents(ServerLevel level, VillageRef village) {
		if (level == null || village == null) return 0;
		return Villages.residents(level, village, Config.VILLAGE_RADIUS,
			v -> isPending(v) && !Guests.isGuest(v)).size();
	}

	/** Chests already available to housing in the real, public central depot and material shed. */
	public static int publicChestStock(ServerLevel level, VillageRef village) {
		if (level == null || village == null) return 0;
		Map<BlockPos, Container> containers = new LinkedHashMap<>();
		for (BlockPos pos : Lager.materialChests(level, village)) {
			Container container = Lager.publicContainerAt(level, pos);
			if (container == null) continue;
			BlockPos key = pos;
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof ChestBlock
				&& state.getValue(ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.RIGHT) {
				key = pos.relative(ChestBlock.getConnectedDirection(state));
			}
			containers.putIfAbsent(key.immutable(), container);
		}
		int total = 0;
		for (Container container : containers.values()) {
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (!stack.isEmpty() && stack.is(Items.CHEST)) total += stack.getCount();
			}
		}
		return total;
	}

	/** Chest target in the primary depot after accounting for stock already present in other public depots. */
	public static int targetPrimaryStock(int pendingResidents, int otherPublicChests) {
		if (pendingResidents <= 0) return 0;
		return Math.max(0, pendingResidents - Math.max(0, otherPublicChests));
	}

	private static void setPending(Villager villager, boolean pending) {
		villager.setAttached(VillagerPlus.PRIVATE_CHEST_PENDING, pending);
	}

	/** Finds or safely migrates the resident's one owned chest before any public stock can be charged. */
	private static ExistingChest rememberExisting(ServerLevel level, UUID owner, VillagerMemory memory, BlockPos currentBed,
			BlockPos door) {
		BlockPos remembered = memory.homeChest.orElse(null);
		if (remembered != null) {
			// Without a loaded source we cannot distinguish a real owner chest from a stale position.
			// Keep the pointer so the persisted request can retry rather than pay for a duplicate.
			if (!level.hasChunkAt(remembered)) return ExistingChest.INCOMPLETE_SCAN;
			if (level.getBlockState(remembered).getBlock() instanceof ChestBlock
				&& Ownership.isOwnedBy(level, remembered, owner)) {
				if (nearCurrentHome(remembered, currentBed)
					&& validOwnedHomeChest(level, remembered, currentBed, door, owner)) {
					memory.homeChest = Optional.of(remembered.immutable());
					return ExistingChest.FOUND;
				}
				// A remembered owner chest can outlive its old home or sit outside the short local scan.
				// Migrate only that verified source; unsafe or unavailable destinations leave it retryable.
				BlockPos migrated = migrateOwnedChest(level, remembered, currentBed, door, owner);
				if (migrated == null) return ExistingChest.UNSAFE;
				memory.homeChest = Optional.of(migrated.immutable());
				return ExistingChest.FOUND;
			}
			memory.homeChest = Optional.empty();
		}
		boolean complete = homeScanComplete(currentBed, level::hasChunkAt);
		if (!complete) return ExistingChest.INCOMPLETE_SCAN;
		List<BlockPos> ownedChests = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(currentBed.offset(-HOME_INTERIOR_SCAN_RADIUS, -1, -HOME_INTERIOR_SCAN_RADIUS),
				currentBed.offset(HOME_INTERIOR_SCAN_RADIUS, 2, HOME_INTERIOR_SCAN_RADIUS))) {
			if (level.hasChunkAt(pos) && level.getBlockState(pos).getBlock() instanceof ChestBlock
				&& nearCurrentHome(pos, currentBed)
				&& Ownership.isOwnedBy(level, pos, owner)) {
				ownedChests.add(pos.immutable());
			}
		}
		if (ownedChests.isEmpty()) return ExistingChest.NONE;
		if (ownedChests.size() > 1) {
			// More than one owner-marked chest makes the source of truth ambiguous. Never migrate or charge stock.
			memory.homeChest = Optional.empty();
			return ExistingChest.UNSAFE;
		}

		BlockPos existing = ownedChests.getFirst();
		if (validOwnedHomeChest(level, existing, currentBed, door, owner)) {
			memory.homeChest = Optional.of(existing.immutable());
			return ExistingChest.FOUND;
		}
		BlockPos migrated = migrateOwnedChest(level, existing, currentBed, door, owner);
		if (migrated == null) {
			memory.homeChest = Optional.empty();
			return ExistingChest.UNSAFE;
		}
		memory.homeChest = Optional.of(migrated.immutable());
		return ExistingChest.FOUND;
	}

	/**
	 * Moves one verified owner chest into the bounded room. The source remains intact until the destination has
	 * received and verified every stack and the owner attachment; every failure path rolls back the destination.
	 */
	private static BlockPos migrateOwnedChest(ServerLevel level, BlockPos sourcePos, BlockPos bed, BlockPos door, UUID owner) {
		ChestBlockEntity source = ownedSingleChest(level, sourcePos, owner);
		if (source == null) return null;
		BlockState sourceState = level.getBlockState(sourcePos);
		CompoundTag sourceData = source.saveWithoutMetadata(level.registryAccess());
		List<ItemStack> sourceItems = snapshotContents(source);
		BlockPos targetPos = findPlacement(level, bed, door);
		if (targetPos == null || targetPos.equals(sourcePos) || !validPlacement(level, targetPos, bed, door)) return null;

		Direction facing = Sites.facingTowards(targetPos, bed);
		BlockState targetState = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
			.setValue(ChestBlock.FACING, facing);
		if (!level.setBlock(targetPos, targetState, 3)) return null;
		BlockEntity targetEntity = level.getBlockEntity(targetPos);
		if (!(targetEntity instanceof ChestBlockEntity target)) {
			discardMigrationTarget(level, targetPos);
			return null;
		}

		try {
			target.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), sourceData.copy()));
			target.setAttached(VillagerPlus.OWNER, owner);
			target.setChanged();
		} catch (RuntimeException transferFailure) {
			discardMigrationTarget(level, targetPos);
			return null;
		}

		if (level.getBlockEntity(targetPos) != target
			|| !validOwnedHomeChest(level, targetPos, bed, door, owner)
			|| !contentsMatch(target, sourceItems)
			|| ownedSingleChest(level, sourcePos, owner) != source
			|| !contentsMatch(source, sourceItems)) {
			discardMigrationTarget(level, targetPos);
			return null;
		}

		// Empty before removing the block so the source chest cannot drop a second copy of its inventory.
		for (int slot = 0; slot < source.getContainerSize(); slot++) source.setItem(slot, ItemStack.EMPTY);
		source.setChanged();
		if (!contentsEmpty(source) || !level.setBlock(sourcePos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3)
			|| !level.getBlockState(sourcePos).isAir() || level.getBlockEntity(sourcePos) != null) {
			restoreMigrationSource(level, sourcePos, sourceState, sourceData, sourceItems, owner);
			discardMigrationTarget(level, targetPos);
			return null;
		}
		if (level.getBlockEntity(targetPos) != target || !validOwnedHomeChest(level, targetPos, bed, door, owner)
			|| !contentsMatch(target, sourceItems)) {
			restoreMigrationSource(level, sourcePos, sourceState, sourceData, sourceItems, owner);
			discardMigrationTarget(level, targetPos);
			return null;
		}
		return targetPos.immutable();
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

	private static boolean contentsEmpty(Container container) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (!container.getItem(slot).isEmpty()) return false;
		}
		return true;
	}

	private static void restoreMigrationSource(ServerLevel level, BlockPos pos, BlockState originalState, CompoundTag data,
			List<ItemStack> items, UUID owner) {
		if (level.getBlockState(pos).isAir()) level.setBlock(pos, originalState, 3);
		BlockEntity entity = level.getBlockEntity(pos);
		if (entity instanceof ChestBlockEntity chest) {
			try {
				chest.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data.copy()));
			} catch (RuntimeException ignored) {
				// Preserve item stacks even if ancillary chest metadata cannot be restored.
				for (int slot = 0; slot < Math.min(chest.getContainerSize(), items.size()); slot++) {
					chest.setItem(slot, items.get(slot).copy());
				}
			}
			chest.setAttached(VillagerPlus.OWNER, owner);
			chest.setChanged();
		}
	}

	private static void discardMigrationTarget(ServerLevel level, BlockPos pos) {
		BlockEntity entity = level.getBlockEntity(pos);
		if (entity instanceof Container container) {
			container.clearContent();
			container.setChanged();
		}
		level.removeBlockEntity(pos);
		if (level.getBlockState(pos).getBlock() instanceof ChestBlock) {
			level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
		}
	}

	/** All chunk columns that could contain a nearby saved chest must be readable before a new one is paid for. */
	static boolean homeScanComplete(BlockPos bed, Predicate<BlockPos> hasChunkAt) {
		int minX = bed.getX() - HOME_INTERIOR_SCAN_RADIUS, maxX = bed.getX() + HOME_INTERIOR_SCAN_RADIUS;
		int minZ = bed.getZ() - HOME_INTERIOR_SCAN_RADIUS, maxZ = bed.getZ() + HOME_INTERIOR_SCAN_RADIUS;
		for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
				BlockPos sample = new BlockPos(Math.max(minX, chunkX << 4), bed.getY(), Math.max(minZ, chunkZ << 4));
				if (!hasChunkAt.test(sample)) return false;
			}
		}
		return true;
	}

	private static boolean nearCurrentHome(BlockPos chest, BlockPos bed) {
		return Math.abs(chest.getX() - bed.getX()) <= HOME_INTERIOR_SCAN_RADIUS
			&& Math.abs(chest.getZ() - bed.getZ()) <= HOME_INTERIOR_SCAN_RADIUS
			&& chest.getY() >= bed.getY() - 1 && chest.getY() <= bed.getY() + 2;
	}

	static BlockPos findPlacement(ServerLevel level, BlockPos bed, BlockPos door) {
		HomeInterior interior = scanHomeInterior(level, bed);
		if (!interior.bounded()) return null;
		BlockPos preferred = findPlacement(level, bed, door, interior, bed.offset(-3, 0, -3), bed.offset(3, 0, 3));
		if (preferred != null) return preferred;
		// Homes can have wider rooms or split levels. Keep the fallback bounded and never query an unloaded chunk.
		return findPlacement(level, bed, door, interior,
			bed.offset(-HOME_INTERIOR_SCAN_RADIUS, -1, -HOME_INTERIOR_SCAN_RADIUS),
			bed.offset(HOME_INTERIOR_SCAN_RADIUS, 2, HOME_INTERIOR_SCAN_RADIUS));
	}

	private static BlockPos findPlacement(ServerLevel level, BlockPos bed, BlockPos door, HomeInterior interior,
			BlockPos min, BlockPos max) {
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			if (!validPlacement(level, pos, bed, door, interior)) continue;
			int walls = 0;
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos neighbor = pos.relative(direction);
				BlockState state = level.getBlockState(neighbor);
				if (state.getBlock() instanceof ChestBlock) {
					walls = -100;
					break;
				}
				if (state.isFaceSturdy(level, neighbor, direction.getOpposite())) walls++;
			}
			if (walls < 0) continue;
			double score = pos.distSqr(bed) - walls * 3.0;
			if (score < bestScore) {
				best = pos.immutable();
				bestScore = score;
			}
		}
		return best;
	}

	static boolean validPlacement(ServerLevel level, BlockPos pos, BlockPos bed, BlockPos door) {
		return validPlacement(level, pos, bed, door, scanHomeInterior(level, bed));
	}

	private static boolean validPlacement(ServerLevel level, BlockPos pos, BlockPos bed, BlockPos door, HomeInterior interior) {
		BlockPos above = pos.above(), below = pos.below();
		if (!level.hasChunkAt(pos) || !level.hasChunkAt(above) || !level.hasChunkAt(below)) return false;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = pos.relative(direction);
			if (!level.hasChunkAt(neighbor) || level.getBlockState(neighbor).getBlock() instanceof ChestBlock) return false;
		}
		if (!level.getBlockState(pos).isAir() || !level.getBlockState(above).isAir() || occupied(level, pos)) return false;
		if (level.getBlockEntity(pos) != null || level.getBlockEntity(below) != null) return false;
		if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
		return hasNearbyRoof(level, pos) && hasAdjacentWall(level, pos) && insideRoom(level, pos, bed, door, interior)
			&& hasSafeRoomAccess(level, pos, bed, interior) && (door == null || pos.distSqr(door) >= 3.0);
	}

	/** A roof overhang and one outside wall do not constitute the resident's room. */
	private static boolean insideRoom(ServerLevel level, BlockPos pos, BlockPos bed, BlockPos door, HomeInterior interior) {
		if (pos.getY() != bed.getY() || !interior.bounded() || !roomCell(level, pos)
			|| !interior.cells().contains(pos.immutable())
			|| !verifiedHomeInteriorPosition(level, bed, pos, door, interior)) return false;
		return onHomeSideOfDoor(level, pos, bed, door) && boundedByHomeWalls(level, pos);
	}

	/** A coordinate is in a house only when a bounded house envelope has actually been resolved. */
	static boolean withinHomeFootprint(BlockPos pos, HouseTemplate.Footprint footprint) {
		return footprint != null && pos.getX() >= footprint.minX() && pos.getX() <= footprint.maxX()
			&& pos.getY() >= footprint.minY() && pos.getY() <= footprint.maxY()
			&& pos.getZ() >= footprint.minZ() && pos.getZ() <= footprint.maxZ();
	}

	/** A chest must sit inside a resolved house envelope, with the outer block layer reserved for walls and eaves. */
	static boolean withinHomeInteriorFootprint(BlockPos pos, HouseTemplate.Footprint footprint) {
		return footprint != null && pos.getX() > footprint.minX() && pos.getX() < footprint.maxX()
			&& pos.getY() > footprint.minY() && pos.getY() < footprint.maxY()
			&& pos.getZ() > footprint.minZ() && pos.getZ() < footprint.maxZ();
	}

	/** A chest is eligible only when both its bed and candidate lie in a resolved house envelope. */
	private static boolean verifiedHomeInteriorPosition(ServerLevel level, BlockPos bed, BlockPos pos, BlockPos door,
			HomeInterior interior) {
		HouseTemplate.Footprint footprint = interior.footprint();
		// A structure footprint can include porches, connecting pieces, or other exterior space. Without the
		// resident's verified home door there is no reliable way to identify which side belongs to this room.
		// Keep provisioning pending instead of treating a nearby roofed cell as an interior by default.
		if (footprint == null || door == null) return false;
		return withinHomeInteriorFootprint(bed, footprint) && withinHomeInteriorFootprint(pos, footprint)
			&& verifiedHomeDoor(level, door, footprint) && doorConnectsToInterior(level, bed, door, interior);
	}

	private static boolean verifiedHomeDoor(ServerLevel level, BlockPos door, HouseTemplate.Footprint footprint) {
		if (door == null) return true;
		if (footprint == null || !withinHomeFootprint(door, footprint) || !withinHomeFootprint(door.above(), footprint)
			|| !level.hasChunkAt(door.above())) return false;
		BlockState lower = level.getBlockState(door), upper = level.getBlockState(door.above());
		if (!(lower.getBlock() instanceof DoorBlock) || lower.getValue(DoorBlock.HALF)
				!= net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
			|| upper.getBlock() != lower.getBlock() || upper.getValue(DoorBlock.HALF)
				!= net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) return false;
		return true;
	}

	/** A nearby door counts only when its bed-facing threshold is part of the bed-reachable interior component. */
	private static boolean doorConnectsToInterior(ServerLevel level, BlockPos bed, BlockPos door, HomeInterior interior) {
		if (door == null) return true;
		BlockState state = level.getBlockState(door);
		Direction facing = state.getValue(DoorBlock.FACING);
		int bedSide = (bed.getX() - door.getX()) * facing.getStepX() + (bed.getZ() - door.getZ()) * facing.getStepZ();
		if (bedSide == 0) return false;
		BlockPos threshold = door.relative(bedSide > 0 ? facing : facing.getOpposite());
		return interior.cells().contains(threshold.immutable());
	}

	private static boolean onHomeSideOfDoor(ServerLevel level, BlockPos pos, BlockPos bed, BlockPos door) {
		if (door == null) return true;
		if (!level.hasChunkAt(door) || !(level.getBlockState(door).getBlock() instanceof DoorBlock)
			|| level.getBlockState(door).getValue(DoorBlock.HALF)
				!= net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) return false;
		if (door != null) {
			Direction facing = level.getBlockState(door).getValue(DoorBlock.FACING);
			int bedSide = (bed.getX() - door.getX()) * facing.getStepX() + (bed.getZ() - door.getZ()) * facing.getStepZ();
			int chestSide = (pos.getX() - door.getX()) * facing.getStepX() + (pos.getZ() - door.getZ()) * facing.getStepZ();
			if (bedSide == 0 || chestSide == 0 || Integer.signum(bedSide) != Integer.signum(chestSide)) return false;
		}
		return true;
	}

	private static boolean boundedByHomeWalls(ServerLevel level, BlockPos pos) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			boolean boundary = false;
			// Only accept walls close to the proven room. A distant fence or the wall of a
			// neighboring building must not turn a roofed porch/eave into an interior cell.
			for (int distance = 1; distance <= MAX_HOME_WALL_RAY && !boundary; distance++) {
				BlockPos wall = pos.relative(direction, distance);
				if (!level.hasChunkAt(wall)) return false;
				for (int height = 0; height <= 1 && !boundary; height++) {
					BlockPos part = wall.above(height);
					BlockState state = level.getBlockState(part);
					if (state.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
					boundary = state.getBlock() instanceof DoorBlock || state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
						|| state.isFaceSturdy(level, part, direction.getOpposite());
				}
			}
			if (!boundary) return false;
		}
		return true;
	}

	/** A saved chest is valid only when its position is adjacent to the proven room component. */
	static boolean validOwnedHomeChest(ServerLevel level, BlockPos pos, BlockPos bed, BlockPos door, UUID owner) {
		ChestBlockEntity chest = ownedSingleChest(level, pos, owner);
		if (chest == null || pos.getY() != bed.getY() || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.below())
			|| !level.getBlockState(pos.above()).isAir()
			|| !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
			|| level.getBlockEntity(pos.above()) != null || level.getBlockEntity(pos.below()) != null || occupied(level, pos)) return false;
		HomeInterior interior = scanHomeInterior(level, bed);
		if (!interior.bounded() || !verifiedHomeInteriorPosition(level, bed, pos, door, interior)) return false;
		boolean connected = false;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (interior.cells().contains(pos.relative(direction).immutable())) {
				connected = true;
				break;
			}
		}
		return connected && hasSafeRoomAccess(level, pos, bed, interior) && hasNearbyRoof(level, pos) && hasAdjacentWall(level, pos)
			&& onHomeSideOfDoor(level, pos, bed, door) && boundedByHomeWalls(level, pos);
	}

	/** Accepts only an owner-marked, single, unopened chest with no deferred loot or adjacent half. */
	private static ChestBlockEntity ownedSingleChest(ServerLevel level, BlockPos pos, UUID owner) {
		if (pos == null || !level.hasChunkAt(pos)) return null;
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)
			|| state.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE
			|| !Ownership.isOwnedBy(level, pos, owner)) return null;
		BlockEntity entity = level.getBlockEntity(pos);
		if (!(entity instanceof ChestBlockEntity chest) || chest.getLootTable() != null || chest.getLootTableSeed() != 0L
			|| chest.getContainerSize() != 27 || ChestBlockEntity.getOpenCount(level, pos) != 0) return null;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = pos.relative(direction);
			if (!level.hasChunkAt(neighbor) || level.getBlockState(neighbor).getBlock() instanceof ChestBlock) return null;
		}
		return chest;
	}

	/** The chest must share a roofed, walkable interior with the resident's bed, not just nearby walls/eaves. */
	static boolean connectedInterior(ServerLevel level, BlockPos bed, BlockPos chest) {
		if (!level.hasChunkAt(bed) || bed.getY() != chest.getY() || !(level.getBlockState(bed).getBlock() instanceof BedBlock)
			|| !roomCell(level, chest) || touchesInteriorScanBoundary(bed, chest)) return false;
		HomeInterior interior = scanHomeInterior(level, bed);
		return interior.bounded() && interior.footprint() != null && interior.cells().contains(chest.immutable())
			&& withinHomeInteriorFootprint(bed, interior.footprint())
			&& withinHomeInteriorFootprint(chest, interior.footprint());
	}

	private static HomeInterior scanHomeInterior(ServerLevel level, BlockPos bed) {
		if (!level.hasChunkAt(bed) || !(level.getBlockState(bed).getBlock() instanceof BedBlock))
			return new HomeInterior(Set.of(), false, null);
		HouseTemplate.Footprint footprint = HouseTemplate.homeSpacingFootprint(level, bed);
		if (footprint == null || !withinHomeInteriorFootprint(bed, footprint))
			return new HomeInterior(Set.of(), false, footprint);
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		Set<BlockPos> cells = new HashSet<>();
		BlockState bedState = level.getBlockState(bed);
		Direction bedFacing = bedState.getValue(BedBlock.FACING);
		BlockPos otherBedPart = bed.relative(bedState.getValue(BedBlock.PART) == BedPart.FOOT
			? bedFacing : bedFacing.getOpposite());
		addBedExitCells(level, bed, bed, footprint, open, visited, cells);
		addBedExitCells(level, otherBedPart, bed, footprint, open, visited, cells);
		while (!open.isEmpty() && visited.size() <= 512) {
			BlockPos current = open.removeFirst();
			// A reachable floor cell at the search edge means the room may continue outside
			// the known home area. Do not treat that incomplete footprint as a safe interior.
			if (touchesInteriorScanBoundary(bed, current)) return new HomeInterior(Set.of(), false, footprint);
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos next = current.relative(direction);
				if (Math.abs(next.getX() - bed.getX()) > HOME_INTERIOR_SCAN_RADIUS
					|| Math.abs(next.getZ() - bed.getZ()) > HOME_INTERIOR_SCAN_RADIUS
					|| !withinHomeInteriorFootprint(next, footprint)
					|| !visited.add(next.immutable())) continue;
				if (!roomCell(level, next)) continue;
				if (touchesInteriorScanBoundary(bed, next)) return new HomeInterior(Set.of(), false, footprint);
				cells.add(next.immutable());
				open.addLast(next.immutable());
			}
		}
		return open.isEmpty() && visited.size() <= 512
			? new HomeInterior(Set.copyOf(cells), true, footprint)
			: new HomeInterior(Set.of(), false, footprint);
	}

	private static boolean touchesInteriorScanBoundary(BlockPos bed, BlockPos pos) {
		return Math.abs(pos.getX() - bed.getX()) >= HOME_INTERIOR_SCAN_RADIUS
			|| Math.abs(pos.getZ() - bed.getZ()) >= HOME_INTERIOR_SCAN_RADIUS;
	}

	/** Starts from ordinary floor cells beside either half of the bed, since the bed itself is not a walk cell. */
	private static void addBedExitCells(ServerLevel level, BlockPos bedPart, BlockPos bedAnchor,
			HouseTemplate.Footprint footprint, ArrayDeque<BlockPos> open, Set<BlockPos> visited, Set<BlockPos> cells) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos exit = bedPart.relative(direction);
			if (Math.abs(exit.getX() - bedAnchor.getX()) > HOME_INTERIOR_SCAN_RADIUS
				|| Math.abs(exit.getZ() - bedAnchor.getZ()) > HOME_INTERIOR_SCAN_RADIUS
				|| !withinHomeInteriorFootprint(exit, footprint) || !roomCell(level, exit)) continue;
			BlockPos immutable = exit.immutable();
			if (visited.add(immutable)) {
				cells.add(immutable);
				open.addLast(immutable);
			}
		}
	}

	/** The chosen cell must leave at least one bed-reachable walk cell beside the chest after placement. */
	private static boolean hasSafeRoomAccess(ServerLevel level, BlockPos chest, BlockPos bed, HomeInterior interior) {
		BlockState bedState = level.getBlockState(bed);
		if (!(bedState.getBlock() instanceof BedBlock)) return false;
		Direction facing = bedState.getValue(BedBlock.FACING);
		BlockPos otherBedPart = bed.relative(bedState.getValue(BedBlock.PART) == BedPart.FOOT
			? facing : facing.getOpposite());
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		addAccessStarts(bed, chest, interior.cells(), open, visited);
		addAccessStarts(otherBedPart, chest, interior.cells(), open, visited);
		while (!open.isEmpty()) {
			BlockPos current = open.removeFirst();
			if (current.distManhattan(chest) == 1) return true;
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos next = current.relative(direction);
				if (!next.equals(chest) && interior.cells().contains(next.immutable()) && visited.add(next.immutable())) {
					open.addLast(next.immutable());
				}
			}
		}
		return false;
	}

	private static void addAccessStarts(BlockPos bedPart, BlockPos chest, Set<BlockPos> interior,
			ArrayDeque<BlockPos> open, Set<BlockPos> visited) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos start = bedPart.relative(direction);
			if (!start.equals(chest) && interior.contains(start.immutable()) && visited.add(start.immutable())) {
				open.addLast(start.immutable());
			}
		}
	}

	/** A room cell has a solid floor, clear body space, and an actual ceiling within the home's bounded height. */
	private static boolean roomCell(ServerLevel level, BlockPos feet) {
		BlockPos head = feet.above();
		BlockPos floor = feet.below();
		if (!level.hasChunkAt(feet) || !level.hasChunkAt(head) || !level.hasChunkAt(floor)
			|| !clearRoomBlock(level.getBlockState(feet)) || !clearRoomBlock(level.getBlockState(head))) return false;
		BlockState floorState = level.getBlockState(floor);
		if (!floorState.isFaceSturdy(level, floor, Direction.UP)) return false;
		for (int height = 2; height <= 7; height++) {
			BlockPos roof = feet.above(height);
			if (!level.hasChunkAt(roof)) return false;
			BlockState roofState = level.getBlockState(roof);
			if (!roofState.is(net.minecraft.tags.BlockTags.LEAVES)
				&& roofState.isFaceSturdy(level, roof, Direction.DOWN)) return true;
		}
		return false;
	}

	private static boolean clearRoomBlock(BlockState state) {
		return state.isAir() || state.canBeReplaced();
	}

	/** Never place a resident chest into the body space of a living entity. */
	private static boolean occupied(ServerLevel level, BlockPos pos) {
		return !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(pos), entity -> entity.isAlive()).isEmpty();
	}

	/** Uses block geometry, not sky-light values that can lag behind block changes or leak under eaves. */
	private static boolean hasNearbyRoof(ServerLevel level, BlockPos pos) {
		for (int y = 2; y <= 8; y++) {
			BlockPos roof = pos.above(y);
			if (!level.hasChunkAt(roof)) return false;
			if (level.getBlockState(roof).isFaceSturdy(level, roof, Direction.DOWN)) return true;
		}
		return false;
	}

	/** Private storage belongs against an actual room wall instead of a freestanding outdoor overhang. */
	private static boolean hasAdjacentWall(ServerLevel level, BlockPos pos) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = pos.relative(direction);
			if (level.getBlockState(neighbor).isFaceSturdy(level, neighbor, direction.getOpposite())) return true;
		}
		return false;
	}

	private static Source takePublicChest(ServerLevel level, VillageRef village) {
		for (BlockPos pos : Lager.materialChests(level, village)) {
			Container container = Lager.publicContainerAt(level, pos);
			if (container == null) continue;
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty() || !stack.is(Items.CHEST)) continue;
				ItemStack before = stack.copy();
				ItemStack reserved = container.removeItem(slot, 1);
				container.setChanged();
				if (!reserved.isEmpty() && reserved.is(Items.CHEST)) return new Source(container, slot, before);
				if (!reserved.isEmpty()) container.setItem(slot, before);
			}
		}
		return null;
	}

	private record Source(Container container, int slot, ItemStack before) {
		private void refund() {
			container.setItem(slot, before.copy());
			container.setChanged();
		}
	}
}
