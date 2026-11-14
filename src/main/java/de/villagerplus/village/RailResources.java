package de.villagerplus.village;

import de.villagerplus.build.Blueprints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Material ledger for rail unlocks and for placing each segment from a village's real stores. */
final class RailResources {
	static final int MIN_IRON_INGOTS = 8;
	static final int MIN_GOLD_INGOTS = 8;
	static final int MIN_REDSTONE = 16;

	private RailResources() { }

	static boolean hasCentralStorage(ServerLevel level, VillageRef village) {
		return hasVillageCenterChest(level, village) && !warehouseContainers(level, village).isEmpty();
	}

	private static boolean hasVillageCenterChest(ServerLevel level, VillageRef village) {
		if (village == null || village.data() == null || village.data().chest.isEmpty()) return false;
		BlockPos pos = village.data().chest.get();
		return level.hasChunkAt(pos) && village.chest(level) != null;
	}

	/** A registered milestone depot counts only when a public container is physically present in loaded chunks. */
	static boolean hasDepot(ServerLevel level, VillageRef village, String type) {
		BlockPos origin = village == null ? null : Specials.get(village, type);
		return origin != null && level.hasChunkAt(origin) && !specialContainers(level, village, type).isEmpty();
	}

	static boolean canPay(ServerLevel level, VillageRef village, boolean powered, boolean needsSupport) {
		List<Container> stores = stores(level, village);
		return has(stores, powered ? Items.POWERED_RAIL : Items.RAIL, 1)
			&& (!needsSupport || has(stores, Items.REDSTONE_BLOCK, 1));
	}

	/** Minimum locally available smithing/mining inputs required to unlock a bilateral rail project. */
	static boolean hasMetallurgyStock(ServerLevel level, VillageRef village) {
		List<Container> stores = stores(level, village);
		return has(stores, Items.IRON_INGOT, MIN_IRON_INGOTS)
			&& has(stores, Items.GOLD_INGOT, MIN_GOLD_INGOTS)
			&& has(stores, Items.REDSTONE, MIN_REDSTONE);
	}

	static boolean pay(ServerLevel level, VillageRef village, boolean powered, boolean needsSupport) {
		List<Container> stores = stores(level, village);
		if (!has(stores, powered ? Items.POWERED_RAIL : Items.RAIL, 1)
			|| (needsSupport && !has(stores, Items.REDSTONE_BLOCK, 1))) return false;
		remove(stores, powered ? Items.POWERED_RAIL : Items.RAIL, 1);
		if (needsSupport) remove(stores, Items.REDSTONE_BLOCK, 1);
		return true;
	}

	/** Position of a loaded, public rail-stock container that physically holds the requested item. */
	static BlockPos sourceFor(ServerLevel level, VillageRef village, Item item) {
		if (village == null || item == null) return null;
		Set<BlockPos> candidates = new LinkedHashSet<>();
		if (village.data() != null) village.data().chest.ifPresent(candidates::add);

		List<BlockPos> centers = Lager.centers(village.data());
		Set<BlockPos> registered = new LinkedHashSet<>(village.data().extra.lagerChests);
		if (registered.isEmpty()) {
			for (BlockPos center : centers) {
				if (!level.hasChunkAt(center)) continue;
				for (BlockEntity entity : loadedBlockEntities(level, center, 5, -1, 4, 5)) {
					BlockPos pos = entity.getBlockPos();
					if (publicStorage(level, pos)) registered.add(pos.immutable());
				}
			}
		}
		for (BlockPos pos : registered) if (registeredStorageContains(level, centers, pos) && publicStorage(level, pos)) {
			candidates.add(pos.immutable());
		}

		for (String type : new String[] {Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.STATION}) {
			BlockPos origin = Specials.get(village, type);
			if (origin == null || !level.hasChunkAt(origin)) continue;
			for (BlockEntity entity : loadedBlockEntities(level, origin, 9, -2, 8, 9)) {
				BlockPos pos = entity.getBlockPos();
				if (publicStorage(level, pos)) candidates.add(pos.immutable());
			}
		}

		for (BlockPos pos : candidates) {
			if (!publicStorage(level, pos)) continue;
			Container box = HopperBlockEntity.getContainerAt(level, pos);
			if (box != null && has(List.of(box), item, 1)) return pos.immutable();
		}
		return null;
	}

	static List<Container> stores(ServerLevel level, VillageRef village) {
		if (!hasVillageCenterChest(level, village)) return List.of();
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Container> stores = new ArrayList<>();
		add(stores, seen, village.chest(level));
		addAll(stores, seen, warehouseContainers(level, village));
		for (String type : new String[] {Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.STATION}) {
			addAll(stores, seen, specialContainers(level, village, type));
		}
		return stores;
	}

	private static List<Container> warehouseContainers(ServerLevel level, VillageRef village) {
		List<BlockPos> centers = Lager.centers(village.data());
		if (centers.isEmpty()) return List.of();
		Set<BlockPos> positions = new LinkedHashSet<>(village.data().extra.lagerChests);
		if (positions.isEmpty()) {
			for (BlockPos center : centers) {
				if (!level.hasChunkAt(center)) continue;
				for (BlockEntity entity : loadedBlockEntities(level, center, 5, -1, 4, 5)) {
					BlockPos pos = entity.getBlockPos();
					if (publicStorage(level, pos)) positions.add(pos.immutable());
				}
			}
		}
		List<Container> out = new ArrayList<>();
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (BlockPos pos : positions) {
			if (registeredStorageContains(level, centers, pos) && publicStorage(level, pos)) {
				add(out, seen, HopperBlockEntity.getContainerAt(level, pos));
			}
		}
		return out;
	}

	private static boolean registeredStorageContains(ServerLevel level, List<BlockPos> centers, BlockPos pos) {
		for (BlockPos center : centers) {
			if (level.hasChunkAt(center) && Math.abs(pos.getX() - center.getX()) <= 5
				&& Math.abs(pos.getY() - center.getY()) <= 4 && pos.getY() >= center.getY() - 1
				&& Math.abs(pos.getZ() - center.getZ()) <= 5) return true;
		}
		return false;
	}

	private static List<Container> specialContainers(ServerLevel level, VillageRef village, String type) {
		BlockPos origin = village == null ? null : Specials.get(village, type);
		if (origin == null || !level.hasChunkAt(origin)) return List.of();
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Container> out = new ArrayList<>();
		for (BlockEntity entity : loadedBlockEntities(level, origin, 9, -2, 8, 9)) {
			BlockPos pos = entity.getBlockPos();
			if (!publicStorage(level, pos)) continue;
			add(out, seen, HopperBlockEntity.getContainerAt(level, pos));
		}
		return out;
	}

	/** Reads only already-loaded chunks, so a remote or partially unloaded depot never forces a chunk load. */
	private static List<BlockEntity> loadedBlockEntities(ServerLevel level, BlockPos center,
			int horizontal, int below, int above, int otherHorizontal) {
		int minX = center.getX() - horizontal;
		int maxX = center.getX() + horizontal;
		int minY = center.getY() + below;
		int maxY = center.getY() + above;
		int minZ = center.getZ() - otherHorizontal;
		int maxZ = center.getZ() + otherHorizontal;
		List<BlockEntity> out = new ArrayList<>();
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (BlockEntity entity : chunk.getBlockEntities().values()) {
					BlockPos pos = entity.getBlockPos();
					if (pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
						&& pos.getZ() >= minZ && pos.getZ() <= maxZ) out.add(entity);
				}
			}
		}
		return out;
	}

	private static boolean publicStorage(ServerLevel level, BlockPos pos) {
		return Lager.publicStorageAt(level, pos);
	}

	private static void addAll(List<Container> out, Set<Container> seen, List<Container> additions) {
		for (Container container : additions) add(out, seen, container);
	}

	private static void add(List<Container> out, Set<Container> seen, Container container) {
		if (container != null && seen.add(container)) out.add(container);
	}

	private static boolean has(List<Container> stores, Item item, int amount) {
		int count = 0;
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack stack = box.getItem(slot);
			if (!stack.isEmpty() && stack.is(item)) {
				count += stack.getCount();
				if (count >= amount) return true;
			}
		}
		return false;
	}

	private static void remove(List<Container> stores, Item item, int amount) {
		int left = amount;
		for (Container box : stores) for (int slot = 0; slot < box.getContainerSize() && left > 0; slot++) {
			ItemStack stack = box.getItem(slot);
			if (stack.isEmpty() || !stack.is(item)) continue;
			int take = Math.min(stack.getCount(), left);
			stack.shrink(take);
			if (stack.isEmpty()) box.setItem(slot, ItemStack.EMPTY);
			box.setChanged();
			left -= take;
		}
	}
}
