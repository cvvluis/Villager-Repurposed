package de.villagerplus.job;

import de.villagerplus.village.Lager;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

/** Real, restart-safe movement of public construction stock from village storage to one site's persisted stock. */
public final class BuildHaul {
	private static final int MAX_CHEST_CANDIDATES = 64;
	private static final long BARREL_SCAN_INTERVAL = 20L;
	private static final String PROJECT = "vp_build_haul_project";
	private static final String BELL = "vp_build_haul_bell";
	private static final String DIMENSION = "vp_build_haul_dimension";
	private static final String SOURCE = "vp_build_haul_source";
	private static final String RETURNING = "vp_build_haul_returning";
	private static final String CARRIER = "vp_build_haul_carrier";
	private static final String MANIFEST_SEPARATOR = "|";
	private static final Map<ServerLevel, ManifestCache> MANIFEST_CACHE = new WeakHashMap<>();
	private static final Map<ServerLevel, Map<Long, BarrelScan>> BARREL_CACHE = new WeakHashMap<>();
	private static boolean registered;

	private record Cargo(long bell, long project, String dimension, BlockPos source, boolean returning, UUID carrier) { }
	private record Demand(Item item, int count) { }
	private record Pickup(BlockPos source, Item item, int count) { }
	private record ProjectKey(long bell, long project, String dimension) { }
	private record CargoKey(long bell, long project, String dimension, Item item, long source) { }
	private record BarrelScan(long scannedAt, List<BlockPos> centers, List<BlockPos> positions) { }
	private static final class ManifestCache {
		final long gameTime;
		final Map<ProjectKey, List<String>> rows;

		ManifestCache(long gameTime, Map<ProjectKey, List<String>> rows) {
			this.gameTime = gameTime;
			this.rows = rows;
		}
	}

	private BuildHaul() { }

	/** Install lifecycle listeners once from the common mod initializer. Unloads preserve manifest rows; deaths settle them. */
	public static void register() {
		if (registered) return;
		registered = true;
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Villager) invalidateManifest(level);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Villager) invalidateManifest(level);
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof Villager villager && villager.level() instanceof ServerLevel level) settleDeadCarrier(level, villager);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			MANIFEST_CACHE.clear();
			BARREL_CACHE.clear();
		});
	}

	/**
	 * Advances one physical haul for the selected project. The caller supplies the already validated construction stand,
	 * that project's remaining request (after site stock and persisted cargo reservations), and its persistent stock view.
	 * Returns true while this villager is fetching, carrying, delivering, or refunding project material.
	 */
	public static boolean tick(Ctx c, BlockPos projectOrigin, BlockPos deliveryStand, List<String> projectDemand,
			boolean projectActive, Container siteStock) {
		if (c == null || c.v() == null || c.level() == null || c.village() == null || projectOrigin == null
			|| siteStock == null) return false;
		if (!c.level().hasChunkAt(projectOrigin)) return false;

		ItemStack marked = firstCargo(c.v());
		if (marked != null) {
			Cargo cargo = cargo(marked);
			if (!belongsTo(cargo, c, projectOrigin)) {
				c.setActivity("wartet auf Rückgabe anderer Baustoffe");
				return true;
			}
			if (!projectActive && !cargo.returning()) {
				setCargoState(marked, cargo, true);
				cargo = cargo(marked);
			}
			cacheCargo(c.st(), projectOrigin, cargo.source());
			if (cargo.returning()) return returnInventoryCargo(c, projectOrigin, marked);
			return deliverInventoryCargo(c, projectOrigin, deliveryStand, siteStock, marked);
		}

		if (!projectActive) {
			clearHaulCache(c.st());
			return returnSiteStock(c, projectOrigin, deliveryStand, siteStock);
		}
		if (deliveryStand == null || !c.level().hasChunkAt(deliveryStand)) return false;
		Pickup pickup = cachedPickup(c, projectOrigin, projectDemand);
		if (pickup == null) pickup = selectPickup(c, projectDemand);
		if (pickup == null) {
			clearHaulCache(c.st());
			return false;
		}
		cachePickup(c.st(), projectOrigin, pickup);
		c.setActivity("holt Baumaterial aus dem Lager");
		if (!approachHaulTarget(c, projectOrigin, pickup.source(), 3.5)) return true;
		return takeFromPublicStorage(c, projectOrigin, pickup);
	}

	/**
	 * Settles a real carried load whose construction project has already been removed or completed.
	 * This is used by the porter's ordinary work path so a saved load is not stranded when no active
	 * builder cursor can claim it. Active project cargo stays reserved for that project's delivery.
	 */
	public static boolean returnOrphanedCargo(Ctx c) {
		if (c == null || c.v() == null || c.level() == null || c.village() == null) return false;
		ItemStack carried = firstCargo(c.v());
		Cargo cargo = cargo(carried);
		if (cargo == null || cargo.bell() != c.village().bell().asLong()
			|| !cargo.dimension().equals(c.level().dimension().identifier().toString())) return false;
		BlockPos origin = BlockPos.of(cargo.project());
		VillageData data = c.village().data();
		VillageData.BuildProject project = data.buildProject(origin).orElse(null);
		boolean legacyPlanStillActive = project == null && data.hasPlan() && data.planOrigin.filter(origin::equals).isPresent();
		if ((project != null && project.active()) || legacyPlanStillActive) return false;
		if (!cargo.returning()) {
			setCargoState(carried, cargo, true);
			refreshManifest(c.level(), c.v());
		}
		cacheCargo(c.st(), origin, cargo.source());
		return returnInventoryCargo(c, origin, carried);
	}

	/** True only for stacks reserved as real construction cargo. */
	static boolean isMarkedCargo(ItemStack stack) {
		return cargo(stack) != null;
	}

	/** Current loaded cargo, for diagnostics. Use the manifest overload for saved reservations. */
	public static Map<Item, Integer> inTransit(Ctx c, BlockPos projectOrigin) {
		return inTransit(manifest(c, projectOrigin, List.of()));
	}

	/**
	 * Serialized reservations use {@code carrierUuid|itemId|count|sourcePosAsLong}. Keep rows for an unloaded carrier;
	 * its villager inventory is the real item owner and will reconcile the row when that entity loads again.
	 */
	public static List<String> manifest(Ctx c, BlockPos projectOrigin, List<String> savedRows) {
		if (c == null || c.v() == null || c.level() == null || c.village() == null || projectOrigin == null) {
			return savedRows == null ? List.of() : List.copyOf(savedRows);
		}
		Map<UUID, List<String>> savedByCarrier = parseManifest(savedRows);
		ManifestCache snapshot = manifestSnapshot(c.level());
		ProjectKey key = projectKey(c, projectOrigin);
		List<String> result = new ArrayList<>();
		for (String row : snapshot.rows.getOrDefault(key, List.of())) {
			UUID carrier = manifestCarrier(row);
			if (carrier != null && c.level().getEntity(carrier) != null) result.add(row);
		}
		for (var entry : savedByCarrier.entrySet()) {
			Entity loaded = c.level().getEntity(entry.getKey());
			if (entry.getKey().equals(c.v().getUUID())) {
				result.removeIf(row -> row.startsWith(entry.getKey() + MANIFEST_SEPARATOR));
				result.addAll(carrierRows(c.v(), key));
			} else if (loaded == null) {
				result.addAll(entry.getValue());
			} else if (loaded instanceof Villager villager) {
				String prefix = entry.getKey() + MANIFEST_SEPARATOR;
				result.removeIf(row -> row.startsWith(prefix));
				result.addAll(carrierRows(villager, key));
			}
		}
		// The caller's inventory is authoritative even if the level-wide per-tick snapshot predates this mutation
		// or the entity has not yet joined the level's entity iterator after being loaded/spawned.
		String currentCarrierPrefix = c.v().getUUID() + MANIFEST_SEPARATOR;
		result.removeIf(row -> row.startsWith(currentCarrierPrefix));
		result.addAll(carrierRows(c.v(), key));
		return List.copyOf(result);
	}

	/** Sum persisted manifest rows. Invalid rows and unknown registry items fail closed. */
	public static Map<Item, Integer> inTransit(List<String> rows) {
		Map<Item, Integer> totals = new LinkedHashMap<>();
		for (List<String> carrierRows : parseManifest(rows).values()) {
			for (String row : carrierRows) {
				String[] fields = row.split("\\|", -1);
				Item item = item(fields[1]);
				int count = parsePositive(fields[2]);
				if (item != null && count > 0) totals.merge(item, count, BuildHaul::saturatedAdd);
			}
		}
		return Map.copyOf(totals);
	}

	private static void settleDeadCarrier(ServerLevel level, Villager villager) {
		UUID carrier = villager.getUUID();
		Map<Long, VillageRef> villages = new LinkedHashMap<>();
		VillageRef assigned = villageForDeadCarrier(level, villager, null);
		if (assigned != null) villages.put(assigned.bell().asLong(), assigned);
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			Cargo cargo = cargo(villager.getInventory().getItem(slot));
			if (cargo == null) continue;
			VillageRef ref = villageForDeadCarrier(level, villager, cargo);
			if (ref != null) villages.put(ref.bell().asLong(), ref);
		}

		AABB deathArea = villager.getBoundingBox().inflate(8.0);
		List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, deathArea);
		Map<CargoKey, Integer> droppedCounts = new HashMap<>();
		for (ItemEntity drop : drops) {
			Cargo cargo = cargo(drop.getItem());
			if (cargo == null || cargo.carrier() != null && !cargo.carrier().equals(carrier)) continue;
			VillageRef ref = villageForDeadCarrier(level, villager, cargo);
			if (ref == null) continue;
			villages.put(ref.bell().asLong(), ref);
			if (cargo.carrier() == null && manifestCount(ref.data().buildProject(BlockPos.of(cargo.project())).orElse(null),
				carrier, drop.getItem().getItem(), cargo.source()) <= 0) continue;
			droppedCounts.merge(cargoKey(cargo, drop.getItem().getItem()), drop.getItem().getCount(), BuildHaul::saturatedAdd);
		}

		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			ItemStack carried = villager.getInventory().getItem(slot);
			Cargo cargo = cargo(carried);
			if (cargo == null || cargo.carrier() != null && !cargo.carrier().equals(carrier)) continue;
			VillageRef ref = villageForDeadCarrier(level, villager, cargo);
			if (ref != null) villages.put(ref.bell().asLong(), ref);
			VillageData.BuildProject project = ref == null ? null : ref.data().buildProject(BlockPos.of(cargo.project())).orElse(null);
			int alreadyDropped = droppedCounts.getOrDefault(cargoKey(cargo, carried.getItem()), 0);
			int expected = manifestCount(project, carrier, carried.getItem(), cargo.source());
			int toRestore = carried.getCount();
			if (alreadyDropped > 0) {
				toRestore = expected > 0 ? Math.min(toRestore, Math.max(0, expected - alreadyDropped))
					: Math.max(0, toRestore - alreadyDropped);
			}
			if (toRestore > 0) {
				ItemStack offered = carried.copyWithCount(toRestore);
				clearCargoMarker(offered);
				ItemStack remainder = returnToPublicStorage(level, ref, cargo.source(), offered);
				if (!remainder.isEmpty()) {
					ItemEntity loose = new ItemEntity(level, villager.getX(), villager.getY() + 0.5, villager.getZ(), remainder);
					if (!level.addFreshEntity(loose)) {
						carried.setCount(remainder.getCount());
						clearCargoMarker(carried);
						villager.getInventory().setChanged();
						continue;
					}
				}
			}
			villager.getInventory().setItem(slot, ItemStack.EMPTY);
		}

		// Death drops are already real items. Strip only our route marker; never clone or reinsert them.
		for (ItemEntity drop : drops) {
			ItemStack stack = drop.getItem();
			Cargo cargo = cargo(stack);
			if (cargo == null || cargo.carrier() != null && !cargo.carrier().equals(carrier)) continue;
			clearCargoMarker(stack);
			drop.setItem(stack);
		}

		for (VillageRef ref : villages.values()) {
			VillageData data = ref.data();
			boolean changed = false;
			for (int i = 0; i < data.buildProjects.size(); i++) {
				VillageData.BuildProject project = data.buildProjects.get(i);
				List<String> remaining = new ArrayList<>();
				List<String> released = new ArrayList<>();
				for (String row : project.cargoManifest()) {
					List<String> fields = parseManifestRow(row);
					if (!fields.isEmpty() && carrier.toString().equals(fields.get(0))) released.add(row);
					else remaining.add(row);
				}
				if (released.isEmpty()) continue;
				List<String> wanted = project.active() ? restoreReleasedDemand(project.wanted(), released) : project.wanted();
				data.buildProjects.set(i, new VillageData.BuildProject(project.type(), project.origin(), project.dir(), project.step(),
					project.variant(), project.source(), project.progress(), project.owner(), project.ownerUnavailableSince(), wanted,
					new VillageData.ProjectHaul(project.siteStock(), remaining), project.materialWait(), project.construction(),
					project.bounds(), project.repair(), project.cleanupOnly()));
				changed = true;
			}
			if (changed) {
				data.refreshWanted();
				ref.dirty();
			}
		}
		villager.getInventory().setChanged();
		invalidateManifest(level);
	}

	private static VillageRef villageForDeadCarrier(ServerLevel level, Villager villager, Cargo cargo) {
		BlockPos bell = null;
		if (cargo != null && cargo.dimension().equals(level.dimension().identifier().toString())) {
			bell = BlockPos.of(cargo.bell());
		} else {
			var meeting = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
			if (meeting.isPresent() && meeting.get().dimension() == level.dimension()) bell = meeting.get().pos();
		}
		if (bell == null) return null;
		if (!level.hasChunkAt(bell)) {
			// A death is rare; load the single owning bell chunk so the manifest can be settled durably.
			level.getChunkAt(bell);
		}
		if (!(level.getBlockEntity(bell) instanceof BellBlockEntity bellEntity)) return null;
		return new VillageRef(bell.immutable(), bellEntity.getAttachedOrCreate(VillagerPlus.VILLAGE), bellEntity);
	}

	private static CargoKey cargoKey(Cargo cargo, Item item) {
		return new CargoKey(cargo.bell(), cargo.project(), cargo.dimension(), item, cargo.source().asLong());
	}

	private static int manifestCount(VillageData.BuildProject project, UUID carrier, Item item, BlockPos source) {
		if (project == null || carrier == null || item == null || source == null) return 0;
		int count = 0;
		for (String row : project.cargoManifest()) {
			List<String> fields = parseManifestRow(row);
			if (!fields.isEmpty() && carrier.toString().equals(fields.get(0)) && item(fields.get(1)) == item
				&& Long.toString(source.asLong()).equals(fields.get(3))) {
				count = saturatedAdd(count, parsePositive(fields.get(2)));
			}
		}
		return count;
	}

	private static List<String> restoreReleasedDemand(List<String> wanted, List<String> released) {
		Map<String, Integer> amounts = new java.util.TreeMap<>();
		if (wanted != null) {
			for (String row : wanted) {
				String[] fields = row.split("#", 2);
				if (fields.length != 2) continue;
				int amount = parsePositive(fields[1]);
				if (amount > 0) amounts.merge(fields[0], amount, BuildHaul::saturatedAdd);
			}
		}
		for (String row : released) {
			List<String> fields = parseManifestRow(row);
			if (!fields.isEmpty()) amounts.merge(fields.get(1), parsePositive(fields.get(2)), BuildHaul::saturatedAdd);
		}
		List<String> result = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : amounts.entrySet()) {
			if (entry.getValue() > 0 && result.size() < 256) result.add(entry.getKey() + "#" + entry.getValue());
		}
		return List.copyOf(result);
	}

	private static ItemStack returnToPublicStorage(ServerLevel level, VillageRef ref, BlockPos source, ItemStack offered) {
		List<Container> targets = new ArrayList<>();
		if (source != null) {
			Container original = Lager.publicContainerAt(level, source);
			if (original != null) targets.add(original);
		}
		if (ref != null) {
			Container villageChest = ref.chest(level);
			if (villageChest != null && !targets.contains(villageChest)) targets.add(villageChest);
			for (Container container : Lager.containers(level, ref.data())) {
				if (!targets.contains(container)) targets.add(container);
			}
		}
		ItemStack remainder = offered;
		for (Container target : targets) {
			if (remainder.isEmpty()) break;
			remainder = HopperBlockEntity.addItem(null, target, remainder, null);
		}
		return remainder;
	}

	/** One real item from a marked build load, for the synchronized villager main hand. */
	static ItemStack visibleCargo(Villager villager) {
		if (villager == null) return ItemStack.EMPTY;
		ItemStack stack = firstCargo(villager);
		if (stack == null) return ItemStack.EMPTY;
		ItemStack display = stack.copyWithCount(1);
		// The held copy is presentation only. If equipment drops on death it must not be counted as another cargo load.
		clearCargoMarker(display);
		return display;
	}

	static ItemStack markForCheck(ItemStack stack, Ctx c, BlockPos projectOrigin, BlockPos source) {
		ItemStack copy = stack.copy();
		markCargo(copy, c, projectOrigin, source, false);
		if (c != null && c.level() != null) invalidateManifest(c.level());
		return copy;
	}

	static ItemStack clearMarkerForCheck(ItemStack stack) {
		ItemStack copy = stack.copy();
		clearCargoMarker(copy);
		return copy;
	}

	static int deliverToStockForCheck(Container inventory, Container siteStock, Ctx c, BlockPos projectOrigin) {
		return transferToSiteStock(inventory, siteStock, c, projectOrigin);
	}

	static boolean selectsSourceForCheck(Ctx c, List<String> demand, BlockPos source, Item item) {
		Pickup pickup = selectPickup(c, demand);
		return pickup != null && pickup.source().equals(source) && pickup.item() == item;
	}

	private static boolean deliverInventoryCargo(Ctx c, BlockPos origin, BlockPos stand, Container siteStock, ItemStack cargoStack) {
		if (stand == null || !c.level().hasChunkAt(stand)) {
			c.setActivity("wartet auf den geladenen Baustellenstand");
			return true;
		}
		c.setActivity("bringt Baumaterial zur Baustelle");
		if (!approachHaulTarget(c, origin, stand, 2.5)) return true;
		int moved = transferStackToContainer(c.v().getInventory(), siteStock, c, origin, cargoStack);
		if (moved > 0) {
			c.v().getInventory().setChanged();
			siteStock.setChanged();
			refreshManifest(c.level(), c.v());
			noteHaulProgress(c, origin);
			c.st().clearTarget();
			clearHaulCache(c.st());
			JobUtil.swing(c.v());
			c.setActivity("liefert Baumaterial an die Baustelle");
		}
		return true;
	}

	private static boolean returnInventoryCargo(Ctx c, BlockPos origin, ItemStack cargoStack) {
		Cargo cargo = cargo(cargoStack);
		if (cargo == null) return false;
		BlockPos target = refundTarget(c, cargoStack, cargo.source());
		if (target == null) {
			c.setActivity("wartet auf öffentlichen Lagerplatz für Baustoffe");
			return true;
		}
		if (!target.equals(cargo.source())) {
			setCargoSource(cargoStack, c, origin, target, true);
			cargo = cargo(cargoStack);
			refreshManifest(c.level(), c.v());
		}
		if (!c.level().hasChunkAt(target)) {
			c.setActivity("wartet auf geladenes öffentliches Lager");
			return true;
		}
		c.setActivity("bringt Baustoffe zurück ins öffentliche Lager");
		if (!approachHaulTarget(c, origin, target, 3.5)) return true;
		Container storage = Lager.publicContainerAt(c.level(), target);
		if (storage == null) {
			clearHaulCache(c.st());
			return true;
		}
		ItemStack offered = cargoStack.copy();
		clearCargoMarker(offered);
		ItemStack remainder = HopperBlockEntity.addItem(null, storage, offered, null);
		int moved = cargoStack.getCount() - remainder.getCount();
		if (moved > 0) {
			cargoStack.shrink(moved);
			storage.setChanged();
			c.v().getInventory().setChanged();
			refreshManifest(c.level(), c.v());
			noteHaulProgress(c, origin);
			c.st().clearTarget();
			if (cargoStack.isEmpty()) clearHaulCache(c.st());
			JobUtil.swing(c.v());
		}
		return true;
	}

	private static boolean returnSiteStock(Ctx c, BlockPos origin, BlockPos stand, Container siteStock) {
		int slot = firstSiteStockSlot(siteStock, c, origin);
		if (slot < 0) return false;
		if (stand == null || !c.level().hasChunkAt(stand)) {
			c.setActivity("wartet auf den geladenen Baustellenstand zur Rückgabe");
			return true;
		}
		c.setActivity("holt überschüssiges Baumaterial von der Baustelle");
		if (!approachHaulTarget(c, origin, stand, 2.5)) return true;
		ItemStack stored = siteStock.getItem(slot);
		Cargo oldCargo = cargo(stored);
		BlockPos returnTo = refundTarget(c, stored, oldCargo == null ? null : oldCargo.source());
		if (returnTo == null) {
			c.setActivity("wartet auf öffentlichen Lagerplatz für Baustoffe");
			return true;
		}
		int amount = stored.getCount();
		ItemStack marked = stored.copyWithCount(amount);
		setCargoSource(marked, c, origin, returnTo, true);
		while (amount > 0 && !c.v().getInventory().canAddItem(marked.copyWithCount(amount))) {
			amount--;
		}
		if (amount <= 0) {
			c.setActivity("braucht freien Inventarplatz für Baustoffe");
			return true;
		}
		marked.setCount(amount);
		ItemStack before = stored.copy();
		ItemStack removed = siteStock.removeItem(slot, amount);
		if (removed.getCount() != amount) {
			siteStock.setItem(slot, before);
			return true;
		}
		setCargoSource(removed, c, origin, returnTo, true);
		ItemStack cargoPrototype = removed.copy();
		ItemStack remainder = c.v().getInventory().addItem(removed);
		if (!remainder.isEmpty()) {
			removeCargoCount(c.v(), c, origin, returnTo, cargoPrototype, amount - remainder.getCount());
			siteStock.setItem(slot, before);
			return true;
		}
		siteStock.setChanged();
		c.v().getInventory().setChanged();
		refreshManifest(c.level(), c.v());
		noteHaulProgress(c, origin);
		cacheCargo(c.st(), origin, returnTo);
		c.st().clearTarget();
		return returnInventoryCargo(c, origin, findCargoFor(c.v(), c, origin));
	}

	private static boolean takeFromPublicStorage(Ctx c, BlockPos origin, Pickup pickup) {
		if (!c.level().hasChunkAt(pickup.source())) return true;
		Container storage = Lager.publicContainerAt(c.level(), pickup.source());
		if (storage == null) {
			clearHaulCache(c.st());
			return false;
		}
		for (int slot = 0; slot < storage.getContainerSize(); slot++) {
			ItemStack source = storage.getItem(slot);
			if (source.isEmpty() || !source.is(pickup.item())) continue;
			int amount = Math.min(Math.min(source.getCount(), pickup.count()), source.getMaxStackSize());
			ItemStack before = source.copy();
			ItemStack tagged = before.copyWithCount(amount);
			markCargo(tagged, c, origin, pickup.source(), false);
			while (amount > 0 && !c.v().getInventory().canAddItem(tagged.copyWithCount(amount))) {
				amount--;
			}
			if (amount <= 0) {
				c.setActivity("braucht freien Inventarplatz für Baustoffe");
				return true;
			}
			tagged.setCount(amount);
			ItemStack extracted = storage.removeItem(slot, amount);
			if (extracted.getCount() != amount) {
				storage.setItem(slot, before);
				return false;
			}
			markCargo(extracted, c, origin, pickup.source(), false);
			ItemStack cargoPrototype = extracted.copy();
			ItemStack remainder = c.v().getInventory().addItem(extracted);
			if (!remainder.isEmpty()) {
				removeCargoCount(c.v(), c, origin, pickup.source(), cargoPrototype, amount - remainder.getCount());
				storage.setItem(slot, before);
				return false;
			}
			storage.setChanged();
			c.v().getInventory().setChanged();
			refreshManifest(c.level(), c.v());
			noteHaulProgress(c, origin);
			cacheCargo(c.st(), origin, pickup.source());
			c.st().clearTarget();
			JobUtil.swing(c.v());
			c.setActivity("trägt Baumaterial zur Baustelle");
			return true;
		}
		clearHaulCache(c.st());
		return false;
	}

	/**
	 * A persisted in-transit load is active construction work even when it has already cleared the
	 * project's wanted rows. Advance project clocks only when the existing navigator records actual
	 * movement (or the caller just transferred cargo), so idle retries do not hide a real stall.
	 */
	private static boolean approachHaulTarget(Ctx c, BlockPos origin, BlockPos target, double reach) {
		boolean reached = JobUtil.approachLong(c, target, reach);
		if (c.st().lastMoveAt == c.now()) noteHaulProgress(c, origin);
		return reached;
	}

	private static void noteHaulProgress(Ctx c, BlockPos origin) {
		if (c == null || c.village() == null || origin == null) return;
		if (touchProjectProgressForHaul(c.village().data(), origin, c.now())) c.village().dirty();
	}

	static boolean touchProjectProgressForHaul(VillageData data, BlockPos origin, long now) {
		if (data == null || origin == null || now <= 0L) return false;
		boolean changed = false;
		if (data.planOrigin.filter(origin::equals).isPresent() && data.planProgress < now) {
			data.planProgress = now;
			changed = true;
		}
		for (int i = 0; i < data.buildProjects.size(); i++) {
			VillageData.BuildProject project = data.buildProjects.get(i);
			if (!project.active() || !project.origin().equals(origin) || project.progress() >= now) continue;
			data.buildProjects.set(i, new VillageData.BuildProject(project.type(), project.origin(), project.dir(), project.step(),
				project.variant(), project.source(), now, project.owner(), project.ownerUnavailableSince(), project.wanted(),
				project.haul(), project.materialWait(), project.construction(), project.bounds(), project.repair(), project.cleanupOnly()));
			data.buildProjectVersion = 1;
			changed = true;
			break;
		}
		return changed;
	}

	private static Pickup cachedPickup(Ctx c, BlockPos origin, List<String> projectDemand) {
		JobState state = c.st();
		if (state.buildHaulProject == null || !state.buildHaulProject.equals(origin)
			|| state.buildHaulSource == null || state.buildHaulItemId.isEmpty()
			|| !c.level().hasChunkAt(state.buildHaulSource)
			|| !Lager.publicStorageAt(c.level(), state.buildHaulSource)) return null;
		Item cachedItem = item(state.buildHaulItemId);
		if (cachedItem == null || parseDemand(projectDemand).stream().noneMatch(demand -> demand.item() == cachedItem)) return null;
		Container storage = Lager.publicContainerAt(c.level(), state.buildHaulSource);
		if (storage == null) return null;
		int wanted = parseDemand(projectDemand).stream().filter(demand -> demand.item() == cachedItem)
			.mapToInt(Demand::count).max().orElse(0);
		int available = JobUtil.count(storage, stack -> stack.is(cachedItem));
		return wanted > 0 && available > 0 ? new Pickup(state.buildHaulSource, cachedItem, Math.min(wanted, available)) : null;
	}

	private static int transferToSiteStock(Container inventory, Container siteStock, Ctx c, BlockPos origin) {
		ItemStack carried = findCargoFor(c.v(), c, origin);
		if (carried == null || cargo(carried).returning()) return 0;
		return transferStackToContainer(inventory, siteStock, c, origin, carried);
	}

	private static int transferStackToContainer(Container source, Container destination, Ctx c, BlockPos origin, ItemStack marked) {
		Cargo cargo = cargo(marked);
		if (!belongsTo(cargo, c, origin)) return 0;
		int before = marked.getCount();
		// Markers are needed only while a carrier owns the stack. Project stock is already isolated by project,
		// so stripping them here lets same-item deliveries merge and avoids consuming one slot per source chest.
		ItemStack offered = withoutMarker(marked);
		ItemStack remainder = HopperBlockEntity.addItem(null, destination, offered, null);
		int moved = before - remainder.getCount();
		if (moved > 0) {
			marked.shrink(moved);
			source.setChanged();
			destination.setChanged();
		}
		return moved;
	}

	private static Pickup selectPickup(Ctx c, List<String> projectDemand) {
		List<Demand> demands = parseDemand(projectDemand);
		if (demands.isEmpty()) return null;
		List<BlockPos> candidates = publicSourceCandidates(c);
		for (Demand demand : demands) {
			for (BlockPos source : stockedSources(candidates, candidate -> {
				Container candidateStorage = Lager.publicContainerAt(c.level(), candidate);
				return candidateStorage != null
					&& JobUtil.count(candidateStorage, stack -> stack.is(demand.item())) > 0;
			})) {
				Container storage = Lager.publicContainerAt(c.level(), source);
				if (storage == null || JobUtil.count(storage, stack -> stack.is(demand.item())) <= 0) continue;
				int available = JobUtil.count(storage, stack -> stack.is(demand.item()));
				return new Pickup(source, demand.item(), Math.min(demand.count(), available));
			}
		}
		return null;
	}

	private static List<BlockPos> publicSources(Ctx c) {
		return publicSourceCandidates(c).stream().limit(MAX_CHEST_CANDIDATES).toList();
	}

	/** Material pickup caps stocked sources, not empty storage candidates, so distant stock remains discoverable. */
	static List<BlockPos> stockedSources(List<BlockPos> orderedCandidates,
			java.util.function.Predicate<BlockPos> containsDemandedStock) {
		if (orderedCandidates == null || containsDemandedStock == null) return List.of();
		return orderedCandidates.stream().filter(containsDemandedStock).limit(MAX_CHEST_CANDIDATES).toList();
	}

	private static List<BlockPos> publicSourceCandidates(Ctx c) {
		java.util.Set<BlockPos> candidates = new java.util.LinkedHashSet<>(Lager.materialChests(c.level(), c.village()));
		candidates.addAll(publicBarrels(c));
		return candidates.stream()
			.filter(pos -> c.level().hasChunkAt(pos) && Lager.publicStorageAt(c.level(), pos))
			.distinct().sorted(Comparator
				.comparing((BlockPos pos) -> pos.equals(c.village().chestPos(c.level())) ? 1 : 0)
				.thenComparingDouble(pos -> c.v().position().distanceToSqr(Vec3.atBottomCenterOf(pos))))
			.toList();
	}

	/** Registered storage buildings may use barrels as public stock even though Lager's legacy index stores chests only. */
	private static List<BlockPos> publicBarrels(Ctx c) {
		List<BlockPos> centers = Lager.centers(c.village().data()).stream().map(BlockPos::immutable).distinct().toList();
		if (centers.isEmpty()) return List.of();
		long villageKey = c.village().bell().asLong();
		long now = c.level().getGameTime();
		Map<Long, BarrelScan> byVillage = BARREL_CACHE.computeIfAbsent(c.level(), ignored -> new HashMap<>());
		BarrelScan cached = byVillage.get(villageKey);
		if (cached == null || !cached.centers().equals(centers) || now < cached.scannedAt()
			|| now - cached.scannedAt() >= BARREL_SCAN_INTERVAL) {
			java.util.Set<BlockPos> positions = new java.util.LinkedHashSet<>();
			for (BlockPos center : centers) {
				for (BlockPos pos : BlockPos.betweenClosed(center.offset(-5, -1, -5), center.offset(5, 4, 5))) {
					if (!c.level().hasChunkAt(pos) || !(c.level().getBlockState(pos).getBlock()
						instanceof net.minecraft.world.level.block.BarrelBlock)) continue;
					if (Lager.publicStorageAt(c.level(), pos)) positions.add(pos.immutable());
				}
			}
			cached = new BarrelScan(now, centers, List.copyOf(positions));
			byVillage.put(villageKey, cached);
		}
		return cached.positions();
	}

	private static List<Demand> parseDemand(List<String> requests) {
		if (requests == null || requests.isEmpty()) return List.of();
		Map<Item, Integer> amounts = new LinkedHashMap<>();
		for (String row : requests) {
			if (row == null) continue;
			String[] fields = row.split("#", 2);
			if (fields.length != 2) continue;
			Item item = item(fields[0]);
			int count = parsePositive(fields[1]);
			if (item != null && count > 0) amounts.merge(item, count, BuildHaul::saturatedAdd);
		}
		return amounts.entrySet().stream().map(entry -> new Demand(entry.getKey(), entry.getValue())).toList();
	}

	private static List<String> parseManifestRow(String row) {
		if (row == null) return List.of();
		String[] fields = row.split("\\|", -1);
		if (fields.length != 4 || parsePositive(fields[2]) <= 0 || item(fields[1]) == null
			|| !parseUuid(fields[0]) || !parseLong(fields[3])) return List.of();
		return List.of(fields);
	}

	private static Map<UUID, List<String>> parseManifest(List<String> rows) {
		Map<UUID, List<String>> result = new LinkedHashMap<>();
		if (rows == null) return result;
		for (String row : rows) {
			List<String> fields = parseManifestRow(row);
			if (fields.isEmpty()) continue;
			UUID carrier = UUID.fromString(fields.get(0));
			result.computeIfAbsent(carrier, ignored -> new ArrayList<>()).add(String.join(MANIFEST_SEPARATOR, fields));
		}
		return result;
	}

	private static String manifestRow(UUID carrier, Item item, int count, BlockPos source) {
		return carrier + MANIFEST_SEPARATOR + BuiltInRegistries.ITEM.getKey(item) + MANIFEST_SEPARATOR + count
			+ MANIFEST_SEPARATOR + source.asLong();
	}

	private static boolean parseUuid(String value) {
		try { UUID.fromString(value); return true; } catch (RuntimeException ignored) { return false; }
	}

	private static boolean parseLong(String value) {
		try { Long.parseLong(value); return true; } catch (RuntimeException ignored) { return false; }
	}

	private static int parsePositive(String value) {
		try { return Math.max(0, Math.min(4096, Integer.parseInt(value))); } catch (RuntimeException ignored) { return 0; }
	}

	private static Item item(String id) {
		Identifier identifier = Identifier.tryParse(id);
		return identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
	}

	private static int saturatedAdd(int left, int right) {
		return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
	}

	private static int firstSiteStockSlot(Container siteStock, Ctx c, BlockPos origin) {
		for (int slot = 0; slot < siteStock.getContainerSize(); slot++) {
			ItemStack stack = siteStock.getItem(slot);
			if (stack.isEmpty()) continue;
			Cargo cargo = cargo(stack);
			if (cargo == null || belongsTo(cargo, c, origin)) return slot;
		}
		return -1;
	}

	private static BlockPos refundTarget(Ctx c, ItemStack stack, BlockPos preferred) {
		List<BlockPos> sources = publicSources(c);
		if (preferred != null && sources.contains(preferred)
			&& storageCanAccept(Lager.publicContainerAt(c.level(), preferred), withoutMarker(stack))) return preferred;
		ItemStack plain = withoutMarker(stack);
		return sources.stream().filter(pos -> storageCanAccept(Lager.publicContainerAt(c.level(), pos), plain))
			.min(Comparator.comparingDouble(pos -> c.v().position().distanceToSqr(Vec3.atBottomCenterOf(pos)))).orElse(null);
	}

	private static boolean storageCanAccept(Container storage, ItemStack stack) {
		if (storage == null || stack == null || stack.isEmpty()) return false;
		SimpleContainer preview = new SimpleContainer(storage.getContainerSize());
		for (int slot = 0; slot < storage.getContainerSize(); slot++) preview.setItem(slot, storage.getItem(slot).copy());
		return HopperBlockEntity.addItem(null, preview, stack.copy(), null).getCount() < stack.getCount();
	}

	private static ItemStack withoutMarker(ItemStack stack) {
		ItemStack plain = stack.copy();
		clearCargoMarker(plain);
		return plain;
	}

	private static ItemStack firstCargo(Villager villager) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			ItemStack stack = villager.getInventory().getItem(slot);
			if (cargo(stack) != null) return stack;
		}
		return null;
	}

	private static ItemStack findCargoFor(Villager villager, Ctx c, BlockPos origin) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			ItemStack stack = villager.getInventory().getItem(slot);
			if (belongsTo(cargo(stack), c, origin)) return stack;
		}
		return null;
	}

	private static void removeCargoCount(Villager villager, Ctx c, BlockPos origin, BlockPos source,
			ItemStack prototype, int count) {
		int left = Math.max(0, count);
		for (int slot = 0; slot < villager.getInventory().getContainerSize() && left > 0; slot++) {
			ItemStack stack = villager.getInventory().getItem(slot);
			Cargo cargo = cargo(stack);
			if (!belongsTo(cargo, c, origin) || !source.equals(cargo.source())
				|| !ItemStack.isSameItemSameComponents(stack, prototype)) continue;
			int removed = Math.min(left, stack.getCount());
			stack.shrink(removed);
			left -= removed;
		}
		villager.getInventory().setChanged();
		if (villager.level() instanceof ServerLevel serverLevel) refreshManifest(serverLevel, villager);
	}

	private static ManifestCache manifestSnapshot(ServerLevel level) {
		long now = level.getGameTime();
		ManifestCache cached = MANIFEST_CACHE.get(level);
		if (cached != null && cached.gameTime == now) return cached;
		Map<ProjectKey, List<String>> rows = new LinkedHashMap<>();
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof Villager villager) || !villager.isAlive()) continue;
			cargoRows(villager).forEach((key, entries) -> rows.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(entries));
		}
		cached = new ManifestCache(now, rows);
		MANIFEST_CACHE.put(level, cached);
		return cached;
	}

	/** Updates the existing tick snapshot for one carrier, avoiding repeated full-level scans after a delivery. */
	private static void refreshManifest(ServerLevel level, Villager villager) {
		ManifestCache cached = MANIFEST_CACHE.get(level);
		if (cached == null || cached.gameTime != level.getGameTime()) return;
		String prefix = villager.getUUID() + MANIFEST_SEPARATOR;
		var entries = cached.rows.entrySet().iterator();
		while (entries.hasNext()) {
			var entry = entries.next();
			entry.getValue().removeIf(row -> row.startsWith(prefix));
			if (entry.getValue().isEmpty()) entries.remove();
		}
		cargoRows(villager).forEach((key, rows) -> cached.rows.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(rows));
	}

	private static Map<ProjectKey, List<String>> cargoRows(Villager villager) {
		Map<ProjectKey, List<String>> rows = new LinkedHashMap<>();
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			ItemStack stack = villager.getInventory().getItem(slot);
			Cargo cargo = cargo(stack);
			if (cargo == null) continue;
			ProjectKey key = new ProjectKey(cargo.bell(), cargo.project(), cargo.dimension());
			rows.computeIfAbsent(key, ignored -> new ArrayList<>())
				.add(manifestRow(villager.getUUID(), stack.getItem(), stack.getCount(), cargo.source()));
		}
		return rows;
	}

	private static List<String> carrierRows(Villager villager, ProjectKey key) {
		return cargoRows(villager).getOrDefault(key, List.of());
	}

	private static UUID manifestCarrier(String row) {
		String[] fields = row.split("\\|", 2);
		return fields.length == 2 && parseUuid(fields[0]) ? UUID.fromString(fields[0]) : null;
	}

	private static ProjectKey projectKey(Ctx c, BlockPos origin) {
		return new ProjectKey(c.village().bell().asLong(), origin.asLong(), c.level().dimension().identifier().toString());
	}

	private static void invalidateManifest(ServerLevel level) {
		MANIFEST_CACHE.remove(level);
	}

	private static Cargo cargo(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) return null;
		CompoundTag tag = custom.copyTag();
		if (!tag.contains(PROJECT) || !tag.contains(BELL) || !tag.contains(DIMENSION) || !tag.contains(SOURCE)) return null;
		try {
			long project = Long.parseLong(tag.getStringOr(PROJECT, ""));
			long bell = Long.parseLong(tag.getStringOr(BELL, ""));
			long source = Long.parseLong(tag.getStringOr(SOURCE, ""));
			String carrierText = tag.getStringOr(CARRIER, "");
			UUID carrier = carrierText.isBlank() ? null : UUID.fromString(carrierText);
			return new Cargo(bell, project, tag.getStringOr(DIMENSION, ""), BlockPos.of(source),
				tag.getIntOr(RETURNING, 0) != 0, carrier);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static boolean belongsTo(Cargo cargo, Ctx c, BlockPos origin) {
		return cargo != null && c != null && c.village() != null && origin != null
			&& cargo.bell() == c.village().bell().asLong() && cargo.project() == origin.asLong()
			&& cargo.dimension().equals(c.level().dimension().identifier().toString());
	}

	private static void markCargo(ItemStack stack, Ctx c, BlockPos origin, BlockPos source, boolean returning) {
		setCargoSource(stack, c, origin, source, returning);
	}

	private static void setCargoState(ItemStack stack, Cargo cargo, boolean returning) {
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = custom == null ? new CompoundTag() : custom.copyTag();
		tag.putInt(RETURNING, returning ? 1 : 0);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	private static void setCargoSource(ItemStack stack, Ctx c, BlockPos origin, BlockPos source, boolean returning) {
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = custom == null ? new CompoundTag() : custom.copyTag();
		tag.putString(PROJECT, Long.toString(origin.asLong()));
		tag.putString(BELL, Long.toString(c.village().bell().asLong()));
		tag.putString(DIMENSION, c.level().dimension().identifier().toString());
		tag.putString(SOURCE, Long.toString(source.asLong()));
		tag.putString(CARRIER, c.v().getUUID().toString());
		tag.putInt(RETURNING, returning ? 1 : 0);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	private static void clearCargoMarker(ItemStack stack) {
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) return;
		CompoundTag tag = custom.copyTag();
		tag.remove(PROJECT);
		tag.remove(BELL);
		tag.remove(DIMENSION);
		tag.remove(SOURCE);
		tag.remove(CARRIER);
		tag.remove(RETURNING);
		if (tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
		else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	private static void cachePickup(JobState state, BlockPos origin, Pickup pickup) {
		state.buildHaulProject = origin.immutable();
		state.buildHaulSource = pickup.source().immutable();
		state.buildHaulItemId = BuiltInRegistries.ITEM.getKey(pickup.item()).toString();
	}

	private static void cacheCargo(JobState state, BlockPos origin, BlockPos source) {
		state.buildHaulProject = origin.immutable();
		state.buildHaulSource = source.immutable();
		state.buildHaulItemId = "";
	}

	private static void clearHaulCache(JobState state) {
		state.buildHaulProject = null;
		state.buildHaulSource = null;
		state.buildHaulItemId = "";
	}
}
