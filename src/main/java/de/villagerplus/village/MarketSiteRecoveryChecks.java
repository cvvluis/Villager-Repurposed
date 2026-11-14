package de.villagerplus.village;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Palette;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** In-world regression checks for a saved market stall whose reserved plot later becomes unsafe. */
public final class MarketSiteRecoveryChecks {
	private record Cell(BlockState state, net.minecraft.nbt.CompoundTag blockEntity) { }
	private record StorageSnapshot(BlockPos pos, Container original, List<ItemStack> items) { }

	private MarketSiteRecoveryChecks() { }

	public static int run(ServerLevel level, VillageRef ref, BlockPos ignoredOrigin) {
		MarketData originalMarket = ref.bellEntity().getAttached(VillagerPlus.MARKET);
		Map<String, Long> originalRoads = ref.bellEntity().getAttached(VillagerPlus.ROADS);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
	List<StorageSnapshot> storageBefore = snapshotStorage(level, Balance.storage(level, ref));
		Villager owner = null;
		Villager resident = null;
		BlockPos terracePoi = null;
		boolean terracePoiAdded = false;
		int checks = 0;
		try {
			BlockPos anchor = Development.center(level, ref);
			List<BlockPos> candidates = Market.marketSiteSearchCandidates(ref, anchor);
			if (candidates.size() < 2) throw new IllegalStateException("Market recovery check needs two bounded site candidates");
			BlockPos blockedOrigin = null;
			for (BlockPos candidate : candidates) {
				prepareFlatMarketSite(level, candidate, before);
				Direction candidateFacing = facingToward(anchor, candidate);
				BlockPos prepared = Market.prepareMarketSite(level, ref, candidate, candidateFacing);
				if (prepared != null) { blockedOrigin = prepared; break; }
			}
			if (blockedOrigin == null)
				throw new IllegalStateException("Market recovery check needs one safe blocked-site candidate");
			// Recovery without a plaza searches around the persisted blocked origin. Prepare a safe
			// fallback in that exact candidate sequence so the test exercises the production search.
			BlockPos reserveArea = null;
			for (BlockPos candidate : Market.marketSiteSearchCandidates(ref, blockedOrigin)) {
				if (candidate.distSqr(blockedOrigin) < 12L * 12L) continue;
				prepareFlatMarketSite(level, candidate, before);
				Direction candidateFacing = facingToward(blockedOrigin, candidate);
				BlockPos prepared = Market.prepareMarketSite(level, ref, candidate, candidateFacing);
				if (prepared != null && prepared.distSqr(blockedOrigin) >= 12L * 12L) {
					reserveArea = prepared;
					break;
				}
			}
			if (reserveArea == null)
				throw new IllegalStateException("Market recovery check needs a safe replacement from the persisted-origin search");

			long now = level.getGameTime() + 10_000L;
			owner = EntityTypes.VILLAGER.spawn(level, anchor, EntitySpawnReason.COMMAND);
			if (owner == null) throw new IllegalStateException("Could not create market recovery owner fixture");
			owner.addTag("vp090_fixture");
			owner.setInvulnerable(true);
			owner.setNoAi(true);
			UUID ownerId = owner.getUUID();
			Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_OAK_LOG,
				Blocks.COBBLESTONE, Blocks.BIRCH_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB,
				Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
			MarketData.Shop planned = new MarketData.Shop(ownerId, blockedOrigin,
				facingToward(anchor, blockedOrigin).get2DDataValue(), 2, -1, 0, now, palette.ids(), false, 0, true);
			Market.save(ref, new MarketData(Optional.empty(), List.of(planned), now + 1_200L, 7));
			BlockPos obstruction = Market.hut(planned, palette).getFirst().pos();
			level.setBlock(obstruction, Blocks.CHEST.defaultBlockState(), 3);
			BlockEntity obstructionEntity = level.getBlockEntity(obstruction);
			if (!(obstructionEntity instanceof Container obstructionInventory)) {
				throw new IllegalStateException("Market recovery check could not create the private obstruction chest");
			}
			obstructionInventory.setItem(0, new ItemStack(Items.DIAMOND, 3));
			List<ItemStack> obstructionContentsBefore = snapshotInventory(obstructionInventory);
			var blocked = Market.clearMarketSiteStep(level, ref, planned, null);
			checks = check(checks, blocked.status() == NaturalSitePreparation.Status.BLOCKED,
				"a private inventory block makes the saved market plot ineligible without clearing it");

			BlockPos ownerStand = Market.counter(planned);
			owner.setPos(ownerStand.getX() + 0.5, ownerStand.getY(), ownerStand.getZ() + 0.5);
			owner.getInventory().clearContent();
			owner.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
			List<ItemStack> walletBefore = snapshotInventory(owner.getInventory());
			long treasuryBeforeReplan = Network.treasuryEmeralds(ref);
			Ctx context = new Ctx(level, owner, new JobState(), owner.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
			boolean worked = Market.work(context);
			MarketData movedMarket = Market.of(ref);
			MarketData.Shop moved = movedMarket.shops().stream().filter(shop -> shop.owner().equals(ownerId)).findFirst().orElse(null);
			boolean movedSafe = moved != null && moved.origin().equals(Market.prepareMarketSite(level, ref, moved.origin(),
				Direction.from2DDataValue(moved.facing())));
			String replacementDetail = " [worked=" + worked + ", old=" + blockedOrigin.toShortString() + ", new="
				+ (moved == null ? "none" : moved.origin().toShortString()) + ", safe=" + movedSafe + "]";
			checks = check(checks, worked && movedSafe && moved != null && !moved.origin().equals(blockedOrigin)
				&& moved.origin().distSqr(blockedOrigin) >= 64,
				"work replans a private obstruction from the persisted READY marker to a safe replacement at least eight blocks away"
					+ replacementDetail);
			checks = check(checks, moved != null && moved.kind() == planned.kind() && moved.owner().equals(planned.owner())
				&& moved.palette().equals(planned.palette()) && moved.progress() == 0 && moved.opened() == 0
				&& !moved.deceased() && !moved.sitePrepared() && moved.retryAt() == 0 && movedMarket.nextPlan() == now + 1_200L
				&& movedMarket.sales() == 7,
				"replanning preserves the same owner/career/palette while resetting the abandoned site's prepared state");
			long treasuryAfterReplan = Network.treasuryEmeralds(ref);
			List<ItemStack> walletAfter = snapshotInventory(owner.getInventory());
			List<ItemStack> obstructionContentsAfter = snapshotInventory(obstructionInventory);
			boolean containerPreserved = level.getBlockState(obstruction).is(Blocks.CHEST)
				&& level.getBlockEntity(obstruction) == obstructionEntity
				&& sameInventory(obstructionContentsBefore, obstructionContentsAfter);
			boolean privateWalletPreserved = sameInventory(walletBefore, walletAfter);
			boolean treasuryPreserved = treasuryAfterReplan == treasuryBeforeReplan;
			String preservationDetails = " [container={state=" + level.getBlockState(obstruction)
				+ ", sameBlockEntity=" + (level.getBlockEntity(obstruction) == obstructionEntity)
				+ ", sameContents=" + sameInventory(obstructionContentsBefore, obstructionContentsAfter)
				+ ", before=" + inventorySummary(obstructionContentsBefore) + ", after="
				+ inventorySummary(obstructionContentsAfter) + "}; wallet={sameContents=" + privateWalletPreserved
				+ ", before=" + inventorySummary(walletBefore) + ", after=" + inventorySummary(walletAfter)
				+ "}; treasury={before=" + treasuryBeforeReplan + ", after=" + treasuryAfterReplan + "}]";
			checks = check(checks, containerPreserved,
				"replanning preserves the obstructing container and its private contents" + preservationDetails);
			checks = check(checks, privateWalletPreserved,
				"replanning preserves the owner's complete private inventory, including emerald components and counts"
					+ preservationDetails);
			checks = check(checks, treasuryPreserved,
				"replanning leaves the public treasury unchanged" + preservationDetails);
			long persistedOrigin = MarketData.CODEC.parse(JsonOps.INSTANCE,
				MarketData.CODEC.encodeStart(JsonOps.INSTANCE, movedMarket).getOrThrow()).getOrThrow()
				.shops().stream().filter(shop -> shop.owner().equals(ownerId)).findFirst().orElseThrow().origin().asLong();
			checks = check(checks, moved != null && persistedOrigin == moved.origin().asLong(),
				"replacement coordinates survive the market attachment codec round trip");

			MarketData.Shop partial = new MarketData.Shop(ownerId, moved.origin(), moved.facing(), moved.kind(), 1, 0,
				now, moved.palette(), false, 0);
			Market.save(ref, new MarketData(Optional.empty(), List.of(partial), now + 1_200L, 7));
			checks = check(checks, Market.alternativeUnbuiltShopSite(level, ref, partial, now) == null,
				"a shop with paid construction progress cannot be moved by the unbuilt-site recovery path");
			MarketData.Shop open = new MarketData.Shop(ownerId, moved.origin(), moved.facing(), moved.kind(), 0, now,
				now, moved.palette(), false, 0);
			Market.save(ref, new MarketData(Optional.empty(), List.of(open), now + 1_200L, 7));
			checks = check(checks, Market.alternativeUnbuiltShopSite(level, ref, open, now) == null,
				"an already-open shop cannot be moved by the unbuilt-site recovery path");
			MarketData.Shop stale = new MarketData.Shop(ownerId, blockedOrigin, planned.facing(), planned.kind(), 0, 0,
				now, planned.palette(), false, 0);
			checks = check(checks, Market.alternativeUnbuiltShopSite(level, ref, stale, now) == null,
				"a stale shop snapshot cannot overwrite the current persisted career");

			// Use the already selected safe replacement plot so this fixture exercises the
			// real market candidate rules, then introduce a bounded three-block ridge.
			owner.setPos(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
			BlockPos terraceCenter = moved.origin().immutable();
			prepareTerracedMarketSite(level, terraceCenter, before);
			BlockPos preparedTerrace = Market.prepareMarketSite(level, ref, terraceCenter, Direction.NORTH);
			checks = check(checks, terraceCenter.equals(preparedTerrace),
				"a safe market candidate with three blocks of relief anchors to its low surface for paid leveling");
			MarketData.Shop terrace = new MarketData.Shop(ownerId, terraceCenter,
				Direction.NORTH.get2DDataValue(), 2, 0, 0, now, planned.palette(), false, 0);
			MarketData currentMarket = Market.of(ref);
			Market.save(ref, new MarketData(currentMarket.plaza(), List.of(terrace), currentMarket.nextPlan(), currentMarket.sales()));
			MarketData terraceRoundTrip = MarketData.CODEC.parse(JsonOps.INSTANCE,
				MarketData.CODEC.encodeStart(JsonOps.INSTANCE, Market.of(ref)).getOrThrow()).getOrThrow();
			checks = check(checks, terraceRoundTrip.shops().size() == 1
				&& terraceRoundTrip.shops().getFirst().origin().equals(terraceCenter),
				"the low terrace origin remains the restart-safe shop work plan");

			var terraceSteps = Market.hut(terrace, palette);
			checks = check(checks, terraceSteps.stream().filter(step -> step.pos().getY() == terraceCenter.getY() - 2
				|| step.pos().getY() == terraceCenter.getY() - 1).allMatch(step -> !step.free()
				&& step.item() != null && step.cost() > 0),
				"both platform and floor remain paid materials; cut terrain does not replace the built base");

			BlockPos highCell = Market.at(terrace, 2, 0, 2);
			var poiManager = level.getPoiManager();
			terracePoi = highCell.immutable();
			if (poiManager.getType(terracePoi).isPresent()) {
				throw new IllegalStateException("Market terrace fixture overlaps an existing POI");
			}
			poiManager.add(terracePoi, net.minecraft.core.registries.BuiltInRegistries.POINT_OF_INTEREST_TYPE
				.getOrThrow(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME));
			terracePoiAdded = true;
			checks = check(checks, Market.prepareMarketSite(level, ref, terraceCenter, Direction.NORTH) == null
				&& level.getBlockState(highCell).is(Blocks.DIRT),
				"a HOME POI inside the terrace prevents candidate selection and leaves the hillside intact");
			poiManager.remove(terracePoi);
			terracePoiAdded = false;

			BlockPos chestCell = Market.at(terrace, 2, -1, 1);
			level.setBlock(chestCell, Blocks.CHEST.defaultBlockState(), 3);
			var chestCut = Market.clearMarketSiteStep(level, ref, terrace, owner);
			checks = check(checks, chestCut.status() == NaturalSitePreparation.Status.BLOCKED
				&& level.getBlockState(chestCell).is(Blocks.CHEST) && level.getBlockEntity(chestCell) != null,
				"a private chest block entity blocks terrain preparation and stays in place");
			level.setBlock(chestCell, Blocks.DIRT.defaultBlockState(), 3);

			BlockPos liquidCell = Market.at(terrace, 2, 1, 1);
			level.setBlock(liquidCell, Blocks.WATER.defaultBlockState(), 3);
			var liquidCut = Market.clearMarketSiteStep(level, ref, terrace, owner);
			checks = check(checks, liquidCut.status() == NaturalSitePreparation.Status.BLOCKED
				&& !level.getBlockState(liquidCell).getFluidState().isEmpty(),
				"water inside the planned cut prevents all clearing and is preserved");
			level.setBlock(liquidCell, Blocks.DIRT.defaultBlockState(), 3);

			NaturalSitePreparation.Scan expectedCut = Market.inspectMarketSite(level, terraceCenter,
				Market.marketSiteTargets(terraceCenter, Direction.NORTH));
			BlockPos firstCut = expectedCut.next();
			if (expectedCut.status() != NaturalSitePreparation.Status.CLEARABLE || firstCut == null)
				throw new IllegalStateException("Market recovery check needs a known highest natural cut block");
			BlockState firstCutState = level.getBlockState(firstCut);
			ItemStack correctTool = matchingDropTool(firstCutState);
			if (correctTool.isEmpty() || !correctTool.isCorrectToolForDrops(firstCutState))
				throw new IllegalStateException("Market recovery check could not equip the correct tool for " + firstCutState);
			owner.getInventory().clearContent();
			for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++)
				owner.getInventory().setItem(slot, new ItemStack(correctTool.getItem()));
			owner.getInventory().setChanged();
			List<Container> publicStorage = Balance.storage(level, ref);
			for (Container store : publicStorage) {
				store.clearContent();
				for (int slot = 0; slot < store.getContainerSize(); slot++)
					store.setItem(slot, new ItemStack(Items.DIRT, 64));
				store.setChanged();
			}
			List<ItemStack> fullInventoryBefore = snapshotInventory(owner.getInventory());
			List<ItemStack> expectedDrops = net.minecraft.world.level.block.Block.getDrops(
				firstCutState, level, firstCut, null, owner, correctTool.copy());
			List<Container> dropDestinations = new ArrayList<>();
			dropDestinations.add(owner.getInventory());
			for (Container store : publicStorage)
				if (store != owner.getInventory()) dropDestinations.add(store);
			List<StorageSnapshot> fullPublicStorageBefore = snapshotStorage(level, publicStorage);
			boolean inventoryFullOfCorrectTools = fullInventoryBefore.stream().allMatch(stack -> !stack.isEmpty()
				&& stack.is(correctTool.getItem()) && stack.isCorrectToolForDrops(firstCutState));
			boolean expectedDropsPresent = expectedDrops.stream().anyMatch(stack -> !stack.isEmpty());
			boolean dropsDoNotFit = !Market.canFitAllMarketDrops(dropDestinations, expectedDrops);
			var noCapacity = Market.clearMarketSiteStep(level, ref, terrace, owner);
			List<ItemStack> fullInventoryAfter = snapshotInventory(owner.getInventory());
			List<StorageSnapshot> fullPublicStorageAfter = snapshotStorage(level, Balance.storage(level, ref));
			boolean publicStorageFullOfDropMaterial = publicStorage.stream().allMatch(store -> {
				for (int slot = 0; slot < store.getContainerSize(); slot++) {
					ItemStack stack = store.getItem(slot);
					if (!stack.is(Items.DIRT) || stack.getCount() < stack.getMaxStackSize()) return false;
				}
				return true;
			});
			checks = check(checks, noCapacity.status() == NaturalSitePreparation.Status.WAITING_FOR_INVENTORY
				&& noCapacity.next() != null && noCapacity.next().equals(firstCut)
				&& level.getBlockState(firstCut).equals(firstCutState) && !level.getBlockState(firstCut).isAir()
				&& inventoryFullOfCorrectTools && publicStorageFullOfDropMaterial && expectedDropsPresent && dropsDoNotFit
				&& sameInventory(fullInventoryBefore, fullInventoryAfter)
				&& sameStorage(fullPublicStorageBefore, fullPublicStorageAfter)
				&& owner.getInventory().getItem(0).is(correctTool.getItem())
				&& owner.getInventory().getItem(0).isCorrectToolForDrops(firstCutState),
				"full worker and public storage of the correct tool waits for drop capacity without removing the highest block or changing tools"
					+ " [status=" + noCapacity.status() + ", tool=" + correctTool.getItem()
					+ ", drops=" + expectedDrops + ", doNotFit=" + dropsDoNotFit
					+ ", publicStorageFullOfDropMaterial=" + publicStorageFullOfDropMaterial + "]");

			resident = EntityTypes.VILLAGER.spawn(level, terraceCenter, EntitySpawnReason.COMMAND);
			if (resident == null) throw new IllegalStateException("Could not create market terrace resident fixture");
			resident.addTag("vp090_fixture");
			resident.setInvulnerable(true);
			resident.setNoAi(true);
			resident.setPos(firstCut.getX() + 0.5, firstCut.getY(), firstCut.getZ() + 0.5);
			var occupiedCut = Market.clearMarketSiteStep(level, ref, terrace, owner);
			checks = check(checks, occupiedCut.status() == NaturalSitePreparation.Status.WAITING_FOR_ENTITY
				&& level.getBlockState(firstCut).is(Blocks.DIRT),
				"an occupied terrain cell waits for its resident instead of cutting through them");
			resident.discard();
			resident = null;

			owner.getInventory().clearContent();
			long terraceTreasuryBefore = Network.treasuryEmeralds(ref);
			List<ItemStack> predictedDrops = net.minecraft.world.level.block.Block.getDrops(
				level.getBlockState(firstCut), level, firstCut, null, owner, ItemStack.EMPTY);
			var cut = Market.clearMarketSiteStep(level, ref, terrace, owner);
			int expectedDirt = predictedDrops.stream().filter(stack -> stack.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum();
			checks = check(checks, cut.status() == NaturalSitePreparation.Status.CLEARED && cut.next().equals(firstCut)
				&& level.getBlockState(firstCut).isAir() && expectedDirt > 0
				&& de.villagerplus.job.JobUtil.count(owner.getInventory(), stack -> stack.is(Items.DIRT)) == expectedDirt
				&& Network.treasuryEmeralds(ref) == terraceTreasuryBefore,
				"one high-side cut removes only its verified natural block, stores every dirt drop, and charges no money");

			BlockPos roadPlaza = ref.bell().east(30);
			BlockPos roadSite = new BlockPos(ref.bell().getX() + 15, anchor.getY(), ref.bell().getZ());
			level.getChunkAt(roadSite);
			Market.save(ref, new MarketData(Optional.of(roadPlaza), List.of(), now + 1_200L, 7));
			ref.bellEntity().setAttached(VillagerPlus.ROADS,
				Map.of("center", (long)Roads.routeLength(roadPlaza, ref.bell()) + 1L));
			checks = check(checks, Roads.trustedVillagePathCell(level, ref, roadSite.below())
				&& Market.marketOverlapsSavedRoad(level, ref, roadSite,
					roadSite.getX() - 2, roadSite.getX() + 2, roadSite.getZ() - 3, roadSite.getZ() + 3),
				"the market footprint guard rejects cells on the persisted public village road");

			return checks;
		} finally {
			if (owner != null) owner.discard();
			if (resident != null) resident.discard();
			if (terracePoiAdded && terracePoi != null) level.getPoiManager().remove(terracePoi);
			restoreSites(level, before);
			restoreStorage(level, storageBefore);
			if (originalMarket == null) ref.bellEntity().removeAttached(VillagerPlus.MARKET);
			else ref.bellEntity().setAttached(VillagerPlus.MARKET, originalMarket);
			if (originalRoads == null) ref.bellEntity().removeAttached(VillagerPlus.ROADS);
			else ref.bellEntity().setAttached(VillagerPlus.ROADS, originalRoads);
			ref.dirty();
		}
	}

	private static void prepareFlatMarketSite(ServerLevel level, BlockPos center, Map<BlockPos, Cell> before) {
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			BlockPos column = center.offset(dx, 0, dz);
			level.getChunkAt(column);
			for (int dy = -2; dy <= 6; dy++) {
				BlockPos pos = center.offset(dx, dy, dz).immutable();
				BlockEntity entity = level.getBlockEntity(pos);
				before.putIfAbsent(pos, new Cell(level.getBlockState(pos), entity == null ? null
					: entity.saveWithFullMetadata(level.registryAccess())));
			}
		}
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			BlockPos ground = center.offset(dx, -1, dz);
			level.setBlock(ground, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			for (int dy = 0; dy <= 6; dy++) level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static void prepareTerracedMarketSite(ServerLevel level, BlockPos center, Map<BlockPos, Cell> before) {
		prepareFlatMarketSite(level, center, before);
		// Five natural dirt blocks rise one, two, then three blocks above the low paid-floor plane.
		for (int z = -2; z <= 2; z++) for (int dy = 0; dy < 3; dy++)
			level.setBlock(center.offset(2, dy, z), Blocks.DIRT.defaultBlockState(), 3);
	}

	private static void restoreSites(ServerLevel level, Map<BlockPos, Cell> before) {
		before.keySet().forEach(level::removeBlockEntity);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.blockEntity() == null) return;
			BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
			if (restored != null) level.setBlockEntity(restored);
		});
	}

	private static List<ItemStack> snapshotInventory(Container inventory) {
		List<ItemStack> snapshot = new ArrayList<>(inventory.getContainerSize());
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) snapshot.add(inventory.getItem(slot).copy());
		return List.copyOf(snapshot);
	}

	private static List<StorageSnapshot> snapshotStorage(ServerLevel level, List<Container> stores) {
		List<StorageSnapshot> snapshots = new ArrayList<>();
		for (Container store : stores) {
			BlockPos pos = store instanceof BlockEntity entity ? entity.getBlockPos().immutable() : null;
			snapshots.add(new StorageSnapshot(pos, store, snapshotInventory(store)));
		}
		return List.copyOf(snapshots);
	}

	private static void restoreStorage(ServerLevel level, List<StorageSnapshot> snapshots) {
		for (StorageSnapshot snapshot : snapshots) {
			Container store = snapshot.pos() != null && level.getBlockEntity(snapshot.pos()) instanceof Container restored
				? restored : snapshot.original();
			store.clearContent();
			for (int slot = 0; slot < Math.min(store.getContainerSize(), snapshot.items().size()); slot++)
				store.setItem(slot, snapshot.items().get(slot).copy());
			store.setChanged();
		}
	}

	private static boolean sameStorage(List<StorageSnapshot> first, List<StorageSnapshot> second) {
		if (first.size() != second.size()) return false;
		for (int index = 0; index < first.size(); index++) {
			StorageSnapshot expected = first.get(index), actual = second.get(index);
			if (expected.pos() != null && !expected.pos().equals(actual.pos())) return false;
			if (!sameInventory(expected.items(), actual.items())) return false;
		}
		return true;
	}

	private static boolean sameInventory(List<ItemStack> first, List<ItemStack> second) {
		if (first.size() != second.size()) return false;
		for (int slot = 0; slot < first.size(); slot++) {
			ItemStack expected = first.get(slot), actual = second.get(slot);
			if (expected.isEmpty() || actual.isEmpty()) {
				if (expected.isEmpty() != actual.isEmpty()) return false;
			} else if (expected.getCount() != actual.getCount()
				|| !ItemStack.isSameItemSameComponents(expected, actual)) {
				return false;
			}
		}
		return true;
	}

	private static String inventorySummary(List<ItemStack> inventory) {
		List<String> entries = new ArrayList<>();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.get(slot);
			if (!stack.isEmpty()) entries.add("slot" + slot + "=" + stack + " components=" + stack.getComponents());
		}
		return entries.toString();
	}

	private static ItemStack matchingDropTool(BlockState state) {
		for (ItemStack candidate : List.of(new ItemStack(Items.DIAMOND_SHOVEL),
			new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.DIAMOND_AXE), new ItemStack(Items.SHEARS))) {
			if (candidate.isCorrectToolForDrops(state)) return candidate;
		}
		return ItemStack.EMPTY;
	}

	private static Direction facingToward(BlockPos center, BlockPos site) {
		int dx = site.getX() - center.getX(), dz = site.getZ() - center.getZ();
		Direction outward = Math.abs(dx) >= Math.abs(dz)
			? (dx < 0 ? Direction.WEST : Direction.EAST)
			: (dz < 0 ? Direction.NORTH : Direction.SOUTH);
		return outward.getOpposite();
	}

	private static int check(int checks, boolean passed, String description) {
		if (!passed) throw new IllegalStateException("Market site recovery: " + description);
		return checks + 1;
	}
}
