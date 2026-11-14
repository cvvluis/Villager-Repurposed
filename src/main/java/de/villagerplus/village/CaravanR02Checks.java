package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Architect;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.ConstructionSite;
import de.villagerplus.build.HouseTemplate;
import de.villagerplus.build.Palette;
import de.villagerplus.build.PlanSupplyOrder;
import de.villagerplus.build.Step;
import de.villagerplus.build.Supply;
import de.villagerplus.build.SupplyChecks;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Focused R02 checks: shortage bounds, real emerald debit, rollback, depot routing and committed delivery. */
public final class CaravanR02Checks {
	private CaravanR02Checks() { }

	public static int run() {
		int checks = 0;
		VillageData data = new VillageData();
		check(PlanSupplyOrder.shortages(data).isEmpty(), "no saved plan means no R02 order"); checks++;
		data.planType = Blueprints.TOWNHALL;
		data.planOrigin = Optional.of(BlockPos.ZERO);
		data.wanted.add("minecraft:bricks#8");
		data.wanted.add("minecraft:bricks#4");
		data.wanted.add("minecraft:obsidian#4");
		data.wanted.add("minecraft:emerald#6");
		data.wanted.add("not an identifier#64");
		check(PlanSupplyOrder.shortages(data).getOrDefault(Items.BRICKS, 0) == 12,
			"duplicate build-material rows aggregate as the recorded shortage"); checks++;
		check(PlanSupplyOrder.remaining(data, Items.BRICKS, 7) == 5
			&& PlanSupplyOrder.remaining(data, Items.BRICKS, 20) == 0,
			"matching stock already held or in transit reduces the request without over-ordering"); checks++;
		check(PlanSupplyOrder.shortages(data).size() == 2, "non-block wishes and malformed rows are excluded from plan supply"); checks++;
		check(PlanSupplyOrder.shortages(data).keySet().iterator().next() == Items.BRICKS,
			"saved plan row order gives competing material requests a deterministic priority"); checks++;
		check(PlanSupplyOrder.shortages(List.of("minecraft:obsidian#2", "minecraft:obsidian#3", "bad#x"))
			.getOrDefault(Items.OBSIDIAN, 0) == 5,
			"an isolated project's saved demand can be parsed without consulting the aggregate village wishlist"); checks++;
		check(PlanSupplyOrder.emeraldCost(8, 8) == 1 && PlanSupplyOrder.emeraldCost(9, 8) == 2
			&& PlanSupplyOrder.emeraldCost(0, 8) == 0,
			"emerald cost rounds up exactly at the trade-price boundary"); checks++;
		return checks;
	}

	/** In-world fixture entry point for the grouped `caravanr02` run; root wires it into the dev runner. */
	public static int runWorld(ServerLevel level, BlockPos at) {
		int checks = run();
		Map<BlockPos, Cell> before = snapshotArea(level, at);
		List<Entity> spawned = new ArrayList<>();
		Map<BlockPos, List<Step>> priorPlanSteps = new LinkedHashMap<>();
		VillageRef fixtureRef = null;
		ConstructionSite originalConstruction = null;
		boolean capturedConstruction = false;
		BlockPos entityArea = at.east(9);
		WanderingSetup setup = null;
		try {
			level.getChunkAt(entityArea);
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			fixtureRef = ref;
			originalConstruction = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
			capturedConstruction = true;
			Container box = ref.chest(level);
			if (box == null) throw new IllegalStateException("R02 fixture has no public village chest");
			box.clearContent();
			ref.data().planType = Blueprints.TOWNHALL;
			ref.data().planOrigin = Optional.of(at.south(8));
			ref.data().planStep = 0;
			ref.data().planDir = Direction.SOUTH.get2DDataValue();
			ref.data().planVariant = 0;
			ref.data().wanted.clear();
			ref.data().wanted.add("minecraft:bricks#12");
			ref.data().wanted.add("minecraft:obsidian#4");
			ref.dirty();

			BlockPos lagerPos = at.west(4);
			level.setBlock(lagerPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(lagerPos, Blocks.CHEST.defaultBlockState(), 3);
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(lagerPos.immutable());
			Container lager = HopperBlockEntity.getContainerAt(level, lagerPos);
			if (lager == null) throw new IllegalStateException("R02 fixture has no secondary communal chest");
			lager.clearContent();

			BlockPos chestPos = ref.chestPos(level);
			for (int x = 1; x <= 12; x++) {
				BlockPos path = at.east(x);
				level.setBlock(path.below(), Blocks.STONE.defaultBlockState(), 3);
				if (!path.equals(chestPos)) level.setBlock(path, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(path.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(path.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			// Keep the public granary chest outside the porter spawn and its east-west approach.
			BlockPos granaryOrigin = at.north(8);
			BlockPos granaryPos = at.north(7);
			de.villagerplus.village.Specials.set(ref, Blueprints.GRANARY, granaryOrigin);
			level.setBlock(granaryPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(granaryPos, Blocks.CHEST.defaultBlockState(), 3);
			Container granary = HopperBlockEntity.getContainerAt(level, granaryPos);
			if (granary == null) throw new IllegalStateException("R02 fixture has no granary food chest");
			granary.clearContent();
			for (int slot = 0; slot < box.getContainerSize(); slot++) box.setItem(slot, new ItemStack(Items.STONE, 64));
			if (!lager.getItem(0).isEmpty()) lager.setItem(0, ItemStack.EMPTY);

			check(Caravan.planOrderCount(level, ref, Items.BRICKS) == 0
				&& Caravan.deliveryTarget(level, ref, new ItemStack(Items.BRICKS)) == null,
				"full public build depot blocks both purchase and delivery even when a lager has free space"); checks++;
			Caravan.DeliveryTarget rawMetalTarget = Caravan.deliveryTarget(level, ref, new ItemStack(Items.RAW_IRON));
			check(rawMetalTarget != null && rawMetalTarget.depot().equals(Blueprints.STORAGE)
				&& rawMetalTarget.pos().equals(lagerPos),
				"raw smelting metal reaches available public storage even though the plan chest is full"); checks++;
			long existingTreasury = Network.treasuryEmeralds(ref);
			if (existingTreasury > 0) Network.spendTreasuryEmeralds(ref, existingTreasury);
			Network.creditTreasuryEmeralds(ref, 2);
			check(Network.treasuryEmeralds(ref) == 2, "full-depot refusal leaves the village treasury unchanged"); checks++;

			Network.spendTreasuryEmeralds(ref, 2);
			setup = spawnCaravan(level, ref, at, spawned);
			ref.data().wanted.clear();
			ref.data().wanted.add("minecraft:bricks#12");
			ref.data().wanted.add("minecraft:stone_bricks#12");
			ref.data().wanted.add("minecraft:obsidian#4");
			ref.dirty();
			for (int slot = 0; slot < box.getContainerSize(); slot++) box.setItem(slot, new ItemStack(Items.STONE, 64));
			box.setItem(0, ItemStack.EMPTY);
			var oldBricks = setup.donkey().getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + 2);
			var otherCargo = setup.donkey().getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + 3);
			var transitSeeds = setup.donkey().getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + 4);
			if (oldBricks == null || otherCargo == null || !oldBricks.set(new ItemStack(Items.BRICKS, 5))
				|| !otherCargo.set(new ItemStack(Items.DIRT, 9)) || transitSeeds == null
				|| !transitSeeds.set(new ItemStack(Items.WHEAT_SEEDS, 5))) {
				throw new IllegalStateException("R02 fixture cannot seed unrelated cargo");
			}
			check(Caravan.ordinaryTradeMissing(level, ref, box, Items.WHEAT_SEEDS, 32) == 27,
				"ordinary import demand subtracts five real seeds already traveling in the tagged donkey"); checks++;
			// Wheat seeds are builder material stock, so their real public destination is the
			// storage depot rather than the food-only granary.
			ItemStack previousStoredSeedSlot = lager.getItem(0).copy();
			ItemStack rejectedSeed = StockPolicy.insertInto(level, ref, Blueprints.STORAGE, lager,
				new ItemStack(Items.WHEAT_SEEDS, 3));
			if (!rejectedSeed.isEmpty()) throw new IllegalStateException("R02 fixture could not seed public material stock");
			check(Caravan.ordinaryTradeMissing(level, ref, box, Items.WHEAT_SEEDS, 32) == 24,
				"ordinary import demand combines public material stock with matching real donkey freight"); checks++;
			Map<String, Integer> previousPolicies = ref.bellEntity().getAttachedOrElse(VillagerPlus.STOCK_POLICIES, Map.of());
			Map<String, Integer> limitedPolicies = new LinkedHashMap<>(previousPolicies);
			limitedPolicies.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.WHEAT_SEEDS).toString(), 12);
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.copyOf(limitedPolicies));
			try {
				check(Caravan.ordinaryTradeMissing(level, ref, box, Items.WHEAT_SEEDS, 32) == 4,
					"ordinary import is capped by real stock-policy room after the five in-transit seeds"); checks++;
			} finally {
				if (previousPolicies.isEmpty()) ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
				else ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, previousPolicies);
			}
			lager.setItem(0, previousStoredSeedSlot);
			List<ItemStack> oldWarehouseStock = new ArrayList<>();
			for (int slot = 0; slot < lager.getContainerSize(); slot++) oldWarehouseStock.add(lager.getItem(slot).copy());
			long treasuryBeforeWarehouseCheck = Network.treasuryEmeralds(ref);
			int warehouseAwareOrder = 0;
			try {
				ItemStack remainder = StockPolicy.insertInto(level, ref, Blueprints.STORAGE, lager, new ItemStack(Items.BRICKS, 5));
				if (!remainder.isEmpty()) throw new IllegalStateException("R02 fixture cannot seed public warehouse bricks");
				warehouseAwareOrder = Caravan.planOrderCount(level, ref, Items.BRICKS);
				check(warehouseAwareOrder == 2 && JobUtil.count(lager, stack -> stack.is(Items.BRICKS)) == 5
					&& Caravan.planInboundCargoCount(level, ref, Items.BRICKS) == 5
					&& count(setup.donkey(), Items.BRICKS) == 5
					&& JobUtil.count(box, stack -> stack.is(Items.BRICKS)) == 0
					&& Network.treasuryEmeralds(ref) == treasuryBeforeWarehouseCheck,
					"R02 order subtracts real public warehouse stock and in-transit cargo without moving items or spending emeralds"); checks++;
			} finally {
				for (int slot = 0; slot < oldWarehouseStock.size(); slot++) lager.setItem(slot, oldWarehouseStock.get(slot));
			}
			int wanted = Caravan.planOrderCount(level, ref, Items.BRICKS);
			int nextOrder = Caravan.planOrderCount(level, ref, Items.STONE_BRICKS);
			int inboundBricks = Caravan.planInboundCargoCount(level, ref, Items.BRICKS);
			check(wanted == 7 && nextOrder == 0 && inboundBricks == 5,
				"five matching real trade blocks reserve the shortage, leaving seven bricks to order"
					+ " [wanted=" + wanted + ", nextOrder=" + nextOrder + ", inboundBricks=" + inboundBricks
					+ ", markedBricks=" + Caravan.planFreightCount(level, ref, Items.BRICKS)
					+ ", depotBricks=" + JobUtil.count(box, stack -> stack.is(Items.BRICKS))
					+ ", taggedDonkeys=" + taggedDonkeySummary(level, ref.bell())
					+ ", fixtureDonkeyAlive=" + setup.donkey().isAlive()
					+ ", fixtureDonkeyTags=" + setup.donkey().entityTags()
					+ ", fixtureDonkeyPos=" + setup.donkey().blockPosition()
					+ ", fixtureDonkeyInWorld=" + (level.getEntity(setup.donkey().getUUID()) == setup.donkey())
					+ ", fixtureEntityTicking=" + level.isPositionEntityTicking(setup.donkey().blockPosition())
					+ ", fixtureChunkTicking=" + level.areEntitiesActuallyLoadedAndTicking(net.minecraft.world.level.ChunkPos.containing(setup.donkey().blockPosition()))
					+ ", fixtureDonkeyBricks=" + count(setup.donkey(), Items.BRICKS) + "]"); checks++;
			int beforeMembers = setup.state().llamas.size();
			check(Caravan.buyPlanFreight(level, setup.state(), ref, box, Items.BRICKS, wanted, 8) == 0
				&& count(setup.donkey(), Items.BRICKS) == 5 && countSlot(setup.donkey(), 2, Items.BRICKS) == 5
				&& countSlot(setup.donkey(), 3, Items.DIRT) == 9 && Caravan.planFreightCount(level, ref, Items.BRICKS) == 0
				&& setup.state().llamas.size() == beforeMembers
				&& JobUtil.count(box, stack -> stack.is(Items.EMERALD)) == 0
				&& Caravan.planOrderCount(level, ref, Items.BRICKS) == 7,
				"failed payment rolls back only staged plan freight and preserves matching trade cargo and other slots"); checks++;

			Network.creditTreasuryEmeralds(ref, 1);
			int acquired = Caravan.buyPlanFreight(level, setup.state(), ref, box, Items.BRICKS, wanted, 8);
			check(acquired == 7 && count(setup.donkey(), Items.BRICKS) == 12
				&& countSlot(setup.donkey(), 2, Items.BRICKS) == 5 && countSlot(setup.donkey(), 3, Items.DIRT) == 9
				&& !Caravan.isPlanFreightSlotForChecks(setup.donkey(), 2, Items.BRICKS)
				&& Caravan.planFreightCount(level, ref, Items.BRICKS) == 7
				&& Caravan.planInboundCargoCount(level, ref, Items.BRICKS) == 12
				&& Network.treasuryEmeralds(ref) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.EMERALD)) == 0,
				"seven paid plan bricks stay separate from five existing trade bricks and debit one treasury emerald"); checks++;
			int planSlot = -1;
			for (int slot = 2; slot < 17; slot++) {
				if (countSlot(setup.donkey(), slot, Items.BRICKS) > 0
					&& Caravan.isPlanFreightSlotForChecks(setup.donkey(), slot, Items.BRICKS)) planSlot = slot;
			}
			if (planSlot < 0) throw new IllegalStateException("R02 fixture cannot find its exact plan-freight slot");
			TagValueOutput savedDonkey = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			setup.donkey().saveWithoutId(savedDonkey);
			Donkey restoredDonkey = EntityTypes.DONKEY.create(level, EntitySpawnReason.COMMAND);
			if (restoredDonkey == null) throw new IllegalStateException("R02 fixture cannot create a reload donkey");
			try {
				restoredDonkey.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), savedDonkey.buildResult()));
				check(countSlot(restoredDonkey, planSlot, Items.BRICKS) == 7
					&& Caravan.isPlanFreightSlotForChecks(restoredDonkey, planSlot, Items.BRICKS)
					&& countSlot(restoredDonkey, 2, Items.BRICKS) == 5
					&& !Caravan.isPlanFreightSlotForChecks(restoredDonkey, 2, Items.BRICKS),
					"slot identity, plan quantity and old trade stack survive entity save/load"); checks++;
			} finally {
				restoredDonkey.discard();
			}
			var planStack = setup.donkey().getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + planSlot);
			ItemStack fullPlanStack = planStack.get().copy();
			planStack.set(new ItemStack(Items.BRICKS, 3));
			check(Caravan.planFreightCount(level, ref, Items.BRICKS) == 3
				&& Caravan.planInboundCargoCount(level, ref, Items.BRICKS) == 8
				&& PlanSupplyOrder.remaining(ref.data(), Items.BRICKS, 8) == 4
				&& countSlot(setup.donkey(), 2, Items.BRICKS) == 5 && countSlot(setup.donkey(), 3, Items.DIRT) == 9,
				"partial delivery tracks three paid bricks plus the five unmarked bricks still in transit"); checks++;
			planStack.set(fullPlanStack);
			check(Caravan.planOrderCount(level, ref, Items.BRICKS) == 0
				&& Caravan.planOrderCount(level, ref, Items.STONE_BRICKS) == 0 && ref.data().planVariant == 0,
				"slot-specific freight tags preserve the seven-item paid shipment and serialize a second request"); checks++;
			int extraSlot = -1;
			for (int slot = 2; slot < 17; slot++) {
				var candidate = setup.donkey().getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + slot);
				if (slot != planSlot && candidate != null && candidate.get().isEmpty()) { extraSlot = slot; break; }
			}
			if (extraSlot < 0) throw new IllegalStateException("R02 fixture has no separate slot for surplus trade freight");
			var extraBricks = setup.donkey().getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + extraSlot);
			if (extraBricks == null || !extraBricks.set(new ItemStack(Items.BRICKS, 4))) {
				throw new IllegalStateException("R02 fixture cannot seed surplus brick freight");
			}
			check(Caravan.planInboundCargoCount(level, ref, Items.BRICKS) == 16,
				"surplus same-item freight is visible in the real inbound inventory balance"); checks++;

		// Keep exactly one main-chest slot open; the unrelated warehouse remains available.
			box.setItem(1, new ItemStack(Items.STONE, 64));
			// This case verifies freight accounting with both entities at valid depot
			// stands; caravandelivery covers the real approach walk independently.
			setup.donkey().setPos(at.east(5).getX() + 0.5, at.getY(), at.east(5).getZ() + 0.5);
			setup.porter().setPos(at.east(4).getX() + 0.5, at.getY(), at.east(4).getZ() + 0.5);
			var memory = setup.porter().getAttachedOrCreate(VillagerPlus.MEMORY);
			var jobState = JobManager.state(setup.porter());
			jobState.cooldownUntil = 0;
			long now = level.getGameTime() + 30_000;
			Caravan.porterDelivery(new Ctx(level, setup.porter(), jobState, memory, ref, now));
			int deliveredFirstCall = JobUtil.count(box, stack -> stack.is(Items.BRICKS));
			for (int tick = 1; tick < 100 && JobUtil.count(box, stack -> stack.is(Items.BRICKS)) < 12; tick++) {
				Caravan.porterDelivery(new Ctx(level, setup.porter(), jobState, memory, ref, now + tick));
			}
			int delivered = JobUtil.count(box, stack -> stack.is(Items.BRICKS));
			Caravan.DeliveryTarget debugTarget = Caravan.deliveryTarget(level, ref, new ItemStack(Items.BRICKS));
			String deliveryState = " [carrying=" + Caravan.carryingFor(setup.porter()) + ", delivered=" + delivered
				+ ", firstCall=" + deliveredFirstCall + ", room=" + StockPolicy.deliveryRoom(level, ref, Items.BRICKS)
				+ ", destination=" + (debugTarget == null ? "none" : debugTarget.depot() + "@" + debugTarget.pos())
				+ ", canPlace=" + box.canPlaceItem(0, new ItemStack(Items.BRICKS))
				+ ", public0=" + box.getItem(0) + ", planFreight=" + Caravan.planFreightCount(level, ref, Items.BRICKS)
				+ ", inbound=" + Caravan.planInboundCargoCount(level, ref, Items.BRICKS)
				+ ", planSlot=" + countSlot(setup.donkey(), planSlot, Items.BRICKS)
				+ ", extraSlot=" + countSlot(setup.donkey(), extraSlot, Items.BRICKS)
				+ ", allDonkeyBricks=" + count(setup.donkey(), Items.BRICKS)
				+ ", granary=" + granaryPos + ", porter=" + setup.porter().blockPosition()
				+ ", delivery=" + Caravan.porterDeliveryDiagnostic(level, setup.porter())
				+ ", route=" + JobUtil.lastLongRouteDiagnostic(setup.porter()) + "]";
			check(!Caravan.carryingFor(setup.porter()) && delivered == 12
				&& box.getItem(0).is(Items.BRICKS) && box.getItem(0).getCount() == 12
				&& JobUtil.count(lager, stack -> stack.is(Items.BRICKS)) == 0
				&& Caravan.planFreightCount(level, ref, Items.BRICKS) == 0
				&& countSlot(setup.donkey(), extraSlot, Items.BRICKS) == 4
				&& Caravan.planInboundCargoCount(level, ref, Items.BRICKS) == 4
				&& Caravan.planOrderCount(level, ref, Items.STONE_BRICKS) == 0,
				"seven tagged plus five old bricks fill the public slot; four excess bricks remain on the donkey" + deliveryState); checks++;
			int deliveredBeforeCallback = JobUtil.count(box, stack -> stack.is(Items.BRICKS));
			int stoneBeforeCallback = JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS));
			Builder.caravanVisited(level, ref, box);
			check((ref.data().planVariant & 2) == 0 && ref.data().planStep == 0
				&& JobUtil.count(box, stack -> stack.is(Items.BRICKS)) == deliveredBeforeCallback
				&& JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS)) == stoneBeforeCallback,
				"an unmet locally obtainable stone-brick shortage blocks fallback after the first real delivery"); checks++;

			box.setItem(1, ItemStack.EMPTY);
			check(Caravan.planOrderCount(level, ref, Items.STONE_BRICKS) == 12,
				"the next material becomes orderable only after its own depot slot is freed"); checks++;
			Network.creditTreasuryEmeralds(ref, 2);
			int secondShipment = Caravan.buyPlanFreight(level, setup.state(), ref, box, Items.STONE_BRICKS, 12, 8);
			check(secondShipment == 12 && Caravan.planOrderCount(level, ref, Items.OBSIDIAN) == 0,
				"a second material shipment reserves the sole remaining slot until delivery"); checks++;
			int secondPlanSlot = -1;
			for (int slot = 2; slot < 17; slot++) {
				if (countSlot(setup.donkey(), slot, Items.STONE_BRICKS) > 0
					&& Caravan.isPlanFreightSlotForChecks(setup.donkey(), slot, Items.STONE_BRICKS)) secondPlanSlot = slot;
			}
			if (secondPlanSlot < 0) throw new IllegalStateException("R02 fixture cannot find its second plan-freight slot");
			box.setItem(1, new ItemStack(Items.STONE_BRICKS, 12));
			box.setItem(2, ItemStack.EMPTY);
			Caravan.DeliveryTarget lateTarget = Caravan.deliveryTarget(level, ref, new ItemStack(Items.STONE_BRICKS));
			check(!Caravan.isPlanFreightSlotForChecks(setup.donkey(), secondPlanSlot, Items.STONE_BRICKS)
				&& Caravan.planFreightCount(level, ref, Items.STONE_BRICKS) == 0
				&& lateTarget != null && lateTarget.pos().equals(ref.chestPos(level))
				&& Caravan.planOrderCount(level, ref, Items.OBSIDIAN) == 4,
				"late depot stock releases surplus plan tags and lets the next saved material order proceed"); checks++;
			setup.donkey().setPos(at.east(9).getX() + 0.5, at.getY(), at.east(9).getZ() + 0.5);
			setup.porter().setPos(at.east(7).getX() + 0.5, at.getY(), at.east(7).getZ() + 0.5);
			jobState.cooldownUntil = 0;
			for (int tick = 0; tick < 2400 && count(setup.donkey(), Items.STONE_BRICKS) > 0; tick++) {
				setup.porter().tick();
				setup.donkey().tick();
				Caravan.porterDelivery(new Ctx(level, setup.porter(), jobState, memory, ref, now + 3000 + tick));
			}
			check(JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS)) == 24
				&& Caravan.planFreightCount(level, ref, Items.STONE_BRICKS) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.BRICKS)) == deliveredBeforeCallback
				&& (ref.data().planVariant & 2) == 0 && ref.data().planStep == 0
				&& ref.data().planOrigin.equals(Optional.of(at.south(8))),
				"surplus paid freight reaches public storage without switching while the planned delivery tag was released"); checks++;

			// Isolate the fallback path from the prior late-stock test: two locally obtainable plan materials
			// are shipped in sequence while the remaining obsidian request cannot be supplied locally.
			for (int slot = 2; slot < 17; slot++) {
				var cargo = setup.donkey().getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + slot);
				if (cargo != null) cargo.set(ItemStack.EMPTY);
			}
			box.clearContent();
			ref.data().planType = Blueprints.GRANARY;
			ref.data().planOrigin = Optional.of(at.south(8));
			ref.data().planStep = 0;
			ref.data().planDir = Direction.SOUTH.get2DDataValue();
			ref.data().planVariant = 0;
			ref.data().wanted.clear();
			ref.data().wanted.add("minecraft:stone_bricks#1");
			ref.data().wanted.add("minecraft:cobblestone#1");
			ref.data().wanted.add("minecraft:obsidian#1");
			// The full plan has a locally producible wooden-door tag step after the current wish window.
			// Keep it stocked so this fixture isolates the late, genuinely unavailable obsidian role.
			box.setItem(0, new ItemStack(Items.OAK_DOOR, 1));
			check(JobUtil.count(box, stack -> stack.is(ItemTags.WOODEN_DOORS)) == 1,
				"late fallback fixture covers the generated plan's local wooden-door tag demand"); checks++;
			ref.dirty();
			BlockPos fallbackOrigin = ref.data().planOrigin.orElseThrow();
			Palette detected = Palette.detect(level, ref.bell(), null, 0, fallbackOrigin);
			List<Step> detectedPlan = Architect.build(Architect.design(Architect.Kind.GRANARY, 0), detected, fallbackOrigin, Direction.SOUTH);
			int lateUnavailableIndex = -1;
			for (int i = 48; i < detectedPlan.size(); i++) {
				Step step = detectedPlan.get(i);
				if (!step.free() && !step.optional() && step.linkedTo() == null && step.item() != null) {
					lateUnavailableIndex = i;
					break;
				}
			}
			if (lateUnavailableIndex < 48) throw new IllegalStateException("R02 caravan fixture plan has no paid step beyond the 48-step window");
			List<Step> withLateObsidian = new ArrayList<>(detectedPlan);
			Step lateDemand = withLateObsidian.get(lateUnavailableIndex);
			withLateObsidian.set(lateUnavailableIndex, new Step(lateDemand.pos(), Blocks.OBSIDIAN.asItem(), null,
				lateDemand.cost(), lateDemand.optional(), lateDemand.linkedTo(), ignored -> Blocks.OBSIDIAN.defaultBlockState()));
			priorPlanSteps.put(fallbackOrigin, Builder.replaceCachedPlanForCheck(fallbackOrigin, withLateObsidian));
			ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION,
				new ConstructionSite(fallbackOrigin, List.of(), List.of(), false, detected.ids()));
			long remainingFunds = Network.treasuryEmeralds(ref);
			if (remainingFunds > 0) Network.spendTreasuryEmeralds(ref, remainingFunds);
			Network.creditTreasuryEmeralds(ref, 1);
			int fallbackShipment = Caravan.buyPlanFreight(level, setup.state(), ref, box, Items.STONE_BRICKS, 1, 8);
			check(fallbackShipment == 1 && Caravan.planFreightCount(level, ref, Items.STONE_BRICKS) == 1
				&& Network.treasuryEmeralds(ref) == 0,
				"a fresh generated plan spends its last emerald on a real paid stone-brick shipment"); checks++;
			setup.donkey().setPos(at.east(9).getX() + 0.5, at.getY(), at.east(9).getZ() + 0.5);
			setup.porter().setPos(at.east(7).getX() + 0.5, at.getY(), at.east(7).getZ() + 0.5);
			jobState.cooldownUntil = 0;
			for (int tick = 0; tick < 2400 && JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS)) < 1; tick++) {
				setup.porter().tick();
				setup.donkey().tick();
				Caravan.porterDelivery(new Ctx(level, setup.porter(), jobState, memory, ref, now + 6000 + tick));
			}
			check(JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS)) == 1
				&& JobUtil.count(box, stack -> stack.is(Items.COBBLESTONE)) == 0
				&& (ref.data().planVariant & 2) == 0 && ref.data().planStep == 0,
				"a real first delivery does not switch while another locally obtainable plan material is still missing"); checks++;
			Network.creditTreasuryEmeralds(ref, 1);
			int secondFallbackShipment = Caravan.buyPlanFreight(level, setup.state(), ref, box, Items.COBBLESTONE, 1, 8);
			check(secondFallbackShipment == 1 && Caravan.planFreightCount(level, ref, Items.COBBLESTONE) == 1
				&& Network.treasuryEmeralds(ref) == 0,
				"the next real plan material consumes the remaining visit budget, leaving the unavailable stock unfunded"); checks++;
			setup.donkey().setPos(at.east(9).getX() + 0.5, at.getY(), at.east(9).getZ() + 0.5);
			setup.porter().setPos(at.east(7).getX() + 0.5, at.getY(), at.east(7).getZ() + 0.5);
			jobState.cooldownUntil = 0;
			for (int tick = 0; tick < 2400 && JobUtil.count(box, stack -> stack.is(Items.COBBLESTONE)) < 1; tick++) {
				setup.porter().tick();
				setup.donkey().tick();
				Caravan.porterDelivery(new Ctx(level, setup.porter(), jobState, memory, ref, now + 9000 + tick));
			}
			SupplyChecks.assertGeneratedFallbackSelected(ref.data().planVariant);
			checks++;
			check(JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS)) == 1
				&& JobUtil.count(box, stack -> stack.is(Items.COBBLESTONE)) >= 1
				&& JobUtil.count(box, stack -> stack.is(ItemTags.WOODEN_DOORS)) == 1
				&& JobUtil.count(box, stack -> stack.is(Items.OBSIDIAN)) == 0
				&& Caravan.planFreightCount(level, ref, Items.COBBLESTONE) == 0
				&& Network.treasuryEmeralds(ref) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.EMERALD)) == 0
				&& (ref.data().planVariant & 2) != 0 && ref.data().planStep == 0,
				"real paid delivery enables the generated biome palette only after every obtainable shortage is filled, preserving delivered stock and cursor despite an unfunded obsidian request"); checks++;

			int stockBeforeProgressGuard = JobUtil.count(box, stack -> stack.is(Items.COBBLESTONE))
				+ JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS));
			ref.data().planVariant &= ~2;
			ref.data().planStep = 1;
			ref.data().wanted.clear();
			ref.data().wanted.add("minecraft:obsidian#4");
			Builder.caravanVisited(level, ref, box);
			check((ref.data().planVariant & 2) == 0 && ref.data().planStep == 1
				&& JobUtil.count(box, stack -> stack.is(Items.COBBLESTONE))
					+ JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS)) == stockBeforeProgressGuard,
				"a progressed plan ignores a stale unavailable-material wish when the full remaining steps contain no such shortage"); checks++;
			checks += preparedLateFallbackChecks(level, ref, box, at);
			checks += caravanDonkeyFollowChecks(level, at, ref, spawned);
			return checks;
		} finally {
			for (Entity entity : spawned) {
				Villages.forget(entity.getUUID());
				entity.discard();
			}
			priorPlanSteps.forEach(Builder::restoreCachedPlanForCheck);
			if (capturedConstruction && fixtureRef != null) {
				if (originalConstruction == null) fixtureRef.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
				else fixtureRef.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, originalConstruction);
			}
			restoreArea(level, before);
		}
	}

	/** Regression coverage for a prepared cursor-zero plan with an unavailable role beyond the 48-step wish window. */
	private static int preparedLateFallbackChecks(ServerLevel level, VillageRef ref, Container box, BlockPos at) {
		var data = ref.data();
		String oldType = data.planType;
		Optional<BlockPos> oldOrigin = data.planOrigin;
		Optional<BlockPos> oldSource = data.planSource;
		int oldDir = data.planDir, oldStep = data.planStep, oldVariant = data.planVariant;
		List<String> oldWanted = new ArrayList<>(data.wanted);
		ConstructionSite oldSite = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
		List<ItemStack> oldInventory = new ArrayList<>();
		for (int slot = 0; slot < box.getContainerSize(); slot++) oldInventory.add(box.getItem(slot).copy());
		Map<BlockPos, Cell> terrain = new LinkedHashMap<>();
		BlockPos origin = at.south(8);
		BlockPos scaffold = origin.west(4), workbench = origin.west(5);
		snapshot(level, terrain, scaffold);
		snapshot(level, terrain, workbench);
		try {
			Palette palette = new Palette(Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.OAK_LOG,
				Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB,
				Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
			long seed = 19L;
			List<Step> original = Architect.build(Architect.design(Architect.Kind.GRANARY, seed), palette, origin, Direction.SOUTH);
			int lateIndex = -1;
			for (int i = 48; i < original.size(); i++) {
				Step step = original.get(i);
				if (!step.free() && !step.optional() && step.linkedTo() == null && step.item() != null) {
					lateIndex = i;
					break;
				}
			}
			if (lateIndex < 48) throw new IllegalStateException("R02 regression plan has no paid step beyond the 48-step window");
			List<Step> blocked = new ArrayList<>(original);
			Step late = blocked.get(lateIndex);
			blocked.set(lateIndex, new Step(late.pos(), Blocks.OBSIDIAN.asItem(), null, late.cost(), late.optional(),
				late.linkedTo(), ignored -> Blocks.OBSIDIAN.defaultBlockState()));

			level.setBlock(scaffold, Blocks.SCAFFOLDING.defaultBlockState(), 3);
			level.setBlock(workbench, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
			ConstructionSite prepared = new ConstructionSite(origin,
				List.of(new ConstructionSite.TemporaryBlock(scaffold, false), new ConstructionSite.TemporaryBlock(workbench, true)),
				List.of(), true, palette.ids());
			ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, prepared);
			data.planType = Blueprints.GRANARY;
			data.planOrigin = Optional.of(origin);
			data.planSource = Optional.empty();
			data.planDir = Direction.SOUTH.get2DDataValue();
			data.planStep = 0;
			data.planVariant = (int) (seed << 2);
			data.wanted.clear();
			data.wanted.add("minecraft:stone_bricks#1");
			box.clearContent();
			box.setItem(0, new ItemStack(Items.STONE_BRICKS, 1));
			box.setItem(1, new ItemStack(Items.OAK_DOOR, 1));
			List<Step> switched = Builder.caravanVisitedForCheck(level, ref, box, blocked);
			ConstructionSite after = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
			boolean laterMaterialSwitched = switched != null && switched.size() == blocked.size()
				&& switched.get(lateIndex).pos().equals(blocked.get(lateIndex).pos())
				&& switched.get(lateIndex).item() != Items.OBSIDIAN
				&& Supply.obtainable(net.minecraft.world.level.block.Block.byItem(switched.get(lateIndex).item()));
			check((data.planVariant & 2) != 0 && laterMaterialSwitched && data.planStep == 0
				&& JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS)) == 1
				&& JobUtil.count(box, stack -> stack.is(ItemTags.WOODEN_DOORS)) == 1
				&& JobUtil.count(box, stack -> stack.is(Items.OBSIDIAN)) == 0,
				"full-plan shortage scan switches for an unavailable paid material after step 48 without moving cursor or consuming paid stock");
			boolean preparedStateKept = after != null && after.prepared() && after.temporary().equals(prepared.temporary())
				&& after.phaseEnds().equals(prepared.phaseEnds())
				&& level.getBlockState(scaffold).is(Blocks.SCAFFOLDING)
				&& level.getBlockState(workbench).is(Blocks.CRAFTING_TABLE);
			check(preparedStateKept,
				"cursor-zero palette switch preserves an already prepared site's scaffolding and workbench ownership");

			// A later real cursor may switch materials only for future steps; its paid prefix and site remain owned.
			int progressedCursor = 5;
			data.planVariant = (int) (seed << 2);
			data.planStep = progressedCursor;
			ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, prepared);
			List<Step> progressed = Builder.caravanVisitedForCheck(level, ref, box, blocked);
			ConstructionSite progressedSite = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
			check((data.planVariant & 2) != 0 && data.planStep == progressedCursor
				&& progressed != null && progressed.size() == blocked.size()
				&& progressed.subList(0, progressedCursor).equals(blocked.subList(0, progressedCursor))
				&& progressed.get(lateIndex).item() != Items.OBSIDIAN,
				"an unavailable late material can switch an in-progress plan without changing its completed prefix or cursor");
			check(progressedSite != null && progressedSite.prepared()
				&& progressedSite.temporary().equals(prepared.temporary())
				&& level.getBlockState(scaffold).is(Blocks.SCAFFOLDING)
				&& level.getBlockState(workbench).is(Blocks.CRAFTING_TABLE)
				&& JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS)) == 1
				&& JobUtil.count(box, stack -> stack.is(ItemTags.WOODEN_DOORS)) == 1
				&& JobUtil.count(box, stack -> stack.is(Items.OBSIDIAN)) == 0,
				"an in-progress fallback preserves its prepared-site ownership and all already delivered materials");

			long houseSeed = 23L;
			List<Step> housePlan = Architect.build(Architect.design(Architect.Kind.HOUSE, houseSeed), palette, origin, Direction.SOUTH);
			if (housePlan.stream().noneMatch(step -> step.item() == null && ItemTags.BEDS.equals(step.tag()) && step.cost() > 0)) {
				throw new IllegalStateException("R02 mixed-residual fixture has no paid bed-tag step");
			}
			int mixedLateIndex = -1;
			for (int i = 48; i < housePlan.size(); i++) {
				Step step = housePlan.get(i);
				if (!step.free() && !step.optional() && step.linkedTo() == null && step.item() != null
					&& step.item() != Blocks.BED.white().asItem()) {
					mixedLateIndex = i;
					break;
				}
			}
			if (mixedLateIndex < 48) throw new IllegalStateException("R02 mixed-residual fixture has no late paid palette step");
			List<Step> mixed = new ArrayList<>(housePlan);
			Step mixedLate = mixed.get(mixedLateIndex);
			mixed.set(mixedLateIndex, new Step(mixedLate.pos(), Blocks.OBSIDIAN.asItem(), null, mixedLate.cost(),
				mixedLate.optional(), mixedLate.linkedTo(), ignored -> Blocks.OBSIDIAN.defaultBlockState()));
			int mixedVariant = (int) (houseSeed << 2);
			data.planType = Blueprints.HOUSE;
			data.planOrigin = Optional.of(origin);
			data.planSource = Optional.empty();
			data.planDir = Direction.SOUTH.get2DDataValue();
			data.planStep = 0;
			data.planVariant = mixedVariant;
			data.wanted.clear();
			data.wanted.add("minecraft:stone_bricks#1");
			box.clearContent();
			box.setItem(0, new ItemStack(Items.STONE_BRICKS, 1));
			box.setItem(1, new ItemStack(Items.OAK_DOOR, 1));
			ConstructionSite mixedSite = new ConstructionSite(origin, prepared.temporary(), List.of(), true, palette.ids());
			ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, mixedSite);
			List<Step> mixedAfter = Builder.caravanVisitedForCheck(level, ref, box, mixed);
			ConstructionSite mixedSiteAfter = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
			check(data.planVariant == mixedVariant && data.planStep == 0
				&& mixedAfter != null && mixedAfter.size() == mixed.size()
				&& mixedAfter.get(mixedLateIndex).item() == Items.OBSIDIAN
				&& JobUtil.count(box, stack -> stack.is(Items.STONE_BRICKS)) == 1
				&& JobUtil.count(box, stack -> stack.is(Items.OBSIDIAN)) == 0
				&& JobUtil.count(box, stack -> stack.is(ItemTags.WOODEN_DOORS)) == 1
				&& JobUtil.count(box, stack -> stack.is(ItemTags.BEDS)) == 0
				&& mixedSiteAfter != null && mixedSiteAfter.equals(mixedSite),
				"an actual locally recipeable bed-tag step blocks partial obsidian fallback, preserving variant, cursor, site and inventory");
			return 5 + sourceTemplateDecorationCheck(level, origin) + multiProjectFallbackCheck(level, ref, box, origin);
		} finally {
			restoreArea(level, terrain);
			box.clearContent();
			for (int slot = 0; slot < oldInventory.size(); slot++) box.setItem(slot, oldInventory.get(slot));
			data.planType = oldType;
			data.planOrigin = oldOrigin;
			data.planSource = oldSource;
			data.planDir = oldDir;
			data.planStep = oldStep;
			data.planVariant = oldVariant;
			data.wanted.clear();
			data.wanted.addAll(oldWanted);
			if (oldSite == null) ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
			else ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, oldSite);
			ref.dirty();
		}
	}

	/** A single caravan callback resolves every active cursor without mixing project state. */
	private static int multiProjectFallbackCheck(ServerLevel level, VillageRef ref, Container box, BlockPos near) {
		VillageData data = ref.data();
		List<VillageData.BuildProject> oldProjects = List.copyOf(data.buildProjects);
		int oldVersion = data.buildProjectVersion;
		String oldType = data.planType;
		Optional<BlockPos> oldOrigin = data.planOrigin, oldSource = data.planSource;
		int oldDir = data.planDir, oldStep = data.planStep, oldVariant = data.planVariant;
		long oldProgress = data.planProgress;
		List<String> oldWanted = List.copyOf(data.wanted);
		Optional<VillageData.Extra.BuilderPlan> oldBuilder = data.extra.planBuilder;
		Optional<String> oldWait = data.extra.planMaterialWait;
		ConstructionSite oldSite = ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null);
		List<ItemStack> oldBox = new ArrayList<>();
		for (int slot = 0; slot < box.getContainerSize(); slot++) oldBox.add(box.getItem(slot).copy());

		BlockPos firstOrigin = near.west(80).immutable();
		BlockPos secondOrigin = near.west(144).immutable();
		FallbackFixture first = fallbackFixture(level, ref, firstOrigin, 17L, 4);
		FallbackFixture second = fallbackFixture(level, ref, secondOrigin, 29L, 9);
		List<Step> priorFirst = Builder.replaceCachedPlanForCheck(firstOrigin, first.blocked());
		List<Step> priorSecond = Builder.replaceCachedPlanForCheck(secondOrigin, second.blocked());
		try {
			data.buildProjects.clear();
			data.buildProjects.add(first.project());
			data.buildProjects.add(second.project());
			data.buildProjectVersion = 1;
			data.refreshWanted();
			data.syncPrimaryProjectView();
			ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, first.site());
			box.clearContent();
			// Both generated granaries have a locally producible wooden-door tag step;
			// satisfy those before isolating the permanently unavailable obsidian rows.
			box.setItem(0, new ItemStack(Items.OAK_DOOR, 2));

			Builder.caravanVisited(level, ref, box);
			VillageData.BuildProject updatedFirst = data.buildProject(firstOrigin).orElseThrow();
			VillageData.BuildProject updatedSecond = data.buildProject(secondOrigin).orElseThrow();
			List<Step> firstAfter = Builder.cachedPlanForCheck(firstOrigin);
			List<Step> secondAfter = Builder.cachedPlanForCheck(secondOrigin);
			boolean independentCursors = updatedFirst.step() == first.project().step()
				&& updatedSecond.step() == second.project().step()
				&& firstAfter != null && firstAfter.subList(0, first.project().step()).equals(first.blocked().subList(0, first.project().step()))
				&& secondAfter != null && secondAfter.subList(0, second.project().step()).equals(second.blocked().subList(0, second.project().step()));
			boolean bothResolved = (updatedFirst.variant() & 2) != 0 && (updatedSecond.variant() & 2) != 0
				&& firstAfter != null && firstAfter.get(first.lateIndex()).item() != Items.OBSIDIAN
				&& secondAfter != null && secondAfter.get(second.lateIndex()).item() != Items.OBSIDIAN
				&& updatedFirst.wanted().isEmpty() && updatedSecond.wanted().isEmpty() && data.wanted.isEmpty();
			boolean projectStateKept = updatedFirst.owner().equals(first.project().owner())
				&& updatedSecond.owner().equals(second.project().owner())
				&& updatedFirst.haul().cargoManifest().equals(first.project().haul().cargoManifest())
				&& updatedSecond.haul().cargoManifest().equals(second.project().haul().cargoManifest())
				&& sameStock(updatedFirst.siteStock(), first.project().siteStock())
				&& sameStock(updatedSecond.siteStock(), second.project().siteStock())
				&& preservesConstruction(updatedFirst.construction(), first.site(), level, firstOrigin)
				&& preservesConstruction(updatedSecond.construction(), second.site(), level, secondOrigin)
				&& JobUtil.count(box, stack -> stack.is(ItemTags.WOODEN_DOORS)) == 2;
			boolean primaryRestored = data.planOrigin.equals(Optional.of(firstOrigin)) && data.planStep == first.project().step()
				&& (data.planVariant & 2) != 0
				&& ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION, null).equals(first.site());
			check(independentCursors,
				"R02 multi-project fallback preserves each project's cursor and already-paid plan prefix");
			check(bothResolved,
				"R02 multi-project fallback resolves each unavailable material and removes only its obsolete demand");
			check(projectStateKept,
				"R02 multi-project fallback preserves owner, site stock, cargo manifest, local door stock and construction phases");
			check(primaryRestored,
				"R02 multi-project fallback restores the primary plan and its construction attachment");
			return 4;
		} finally {
			data.buildProjects.clear();
			data.buildProjects.addAll(oldProjects);
			data.buildProjectVersion = oldVersion;
			data.planType = oldType;
			data.planOrigin = oldOrigin;
			data.planSource = oldSource;
			data.planDir = oldDir;
			data.planStep = oldStep;
			data.planVariant = oldVariant;
			data.planProgress = oldProgress;
			data.wanted.clear();
			data.wanted.addAll(oldWanted);
			data.extra.planBuilder = oldBuilder;
			data.extra.planMaterialWait = oldWait;
			if (oldSite == null) ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
			else ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, oldSite);
			for (int slot = 0; slot < oldBox.size(); slot++) box.setItem(slot, oldBox.get(slot));
			Builder.restoreCachedPlanForCheck(firstOrigin, priorFirst);
			Builder.restoreCachedPlanForCheck(secondOrigin, priorSecond);
			ref.dirty();
		}
	}

	private static FallbackFixture fallbackFixture(ServerLevel level, VillageRef ref, BlockPos origin, long seed, int cursor) {
		Palette palette = Palette.detect(level, ref.bell(), null, (int) seed, origin);
		List<Step> original = Architect.build(Architect.design(Architect.Kind.GRANARY, seed), palette, origin, Direction.SOUTH);
		int lateIndex = -1;
		for (int i = 48; i < original.size(); i++) {
			Step step = original.get(i);
			if (!step.free() && !step.optional() && step.linkedTo() == null && step.item() != null) {
				lateIndex = i;
				break;
			}
		}
		if (lateIndex < Math.max(48, cursor)) throw new IllegalStateException("R02 multi-project plan has no late paid material step");
		List<Step> blocked = new ArrayList<>(original);
		Step late = blocked.get(lateIndex);
		blocked.set(lateIndex, new Step(late.pos(), Items.OBSIDIAN, null, late.cost(), late.optional(), late.linkedTo(),
			ignored -> Blocks.OBSIDIAN.defaultBlockState()));
		ConstructionSite site = new ConstructionSite(origin, List.of(), List.of(), true, palette.ids());
		UUID owner = UUID.randomUUID();
		VillageData.ProjectHaul haul = new VillageData.ProjectHaul(List.of(new ItemStack(Items.COBBLESTONE, 7)),
			List.of("minecraft:oak_planks#3"));
		VillageData.BuildProject project = new VillageData.BuildProject(Blueprints.GRANARY, origin,
			Direction.SOUTH.get2DDataValue(), cursor, (int) (seed << 2), Optional.empty(), 123L,
			Optional.of(owner), 12L, List.of("minecraft:obsidian#1"), haul, Optional.of("waiting for caravan"),
			Optional.of(site), Optional.of(new VillageData.PlanBounds(origin.getX() - 8, origin.getX() + 8,
				origin.getZ() - 8, origin.getZ() + 8)), false, false);
		return new FallbackFixture(project, blocked, lateIndex, site);
	}

	private static boolean sameStock(List<ItemStack> left, List<ItemStack> right) {
		if (left.size() != right.size()) return false;
		for (int i = 0; i < left.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(left.get(i), right.get(i)) || left.get(i).getCount() != right.get(i).getCount()) return false;
		}
		return true;
	}

	private static boolean preservesConstruction(Optional<ConstructionSite> updated, ConstructionSite original,
			ServerLevel level, BlockPos origin) {
		if (updated.isEmpty()) return false;
		ConstructionSite site = updated.get();
		if (!site.origin().equals(original.origin()) || !site.temporary().equals(original.temporary())
			|| !site.phaseEnds().equals(original.phaseEnds()) || site.prepared() != original.prepared()) return false;
		Palette palette = Palette.ofIds(site.paletteIds());
		var biome = level.getBiome(origin).unwrapKey().map(key -> key.identifier())
			.orElse(net.minecraft.resources.Identifier.withDefaultNamespace("plains"));
		return palette != null && Supply.obtainablePalette(palette, biome).equals(palette);
	}

	private record FallbackFixture(VillageData.BuildProject project, List<Step> blocked, int lateIndex, ConstructionSite site) { }

	/** Capture and rebuild a real source template containing a lantern, proving fallback keeps its planned position. */
	private static int sourceTemplateDecorationCheck(ServerLevel level, BlockPos near) {
		BlockPos bed = near.west(40);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos p : BlockPos.betweenClosed(bed.offset(-2, -1, -2), bed.offset(14, 5, 5))) snapshot(level, before, p);
		try {
			for (BlockPos p : BlockPos.betweenClosed(bed.offset(0, -1, 0), bed.offset(3, -1, 3)))
				level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) {
				if ((x == 0 || x == 3 || z == 0 || z == 3) && !((x == 1 || x == 2) && z == 0))
					level.setBlock(bed.offset(x, 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
			}
			BlockPos door = bed.offset(1, 1, 0);
			BlockState spruceDoor = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
				.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
			level.setBlock(door, spruceDoor, 3);
			level.setBlock(door.above(), spruceDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
			BlockPos ironDoor = bed.offset(2, 1, 0);
			BlockState lowerIronDoor = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
				.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
			level.setBlock(ironDoor, lowerIronDoor, 3);
			level.setBlock(ironDoor.above(), lowerIronDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
			for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++)
				if ((x == 0 || x == 3) && (z == 0 || z == 3))
					level.setBlock(bed.offset(x, 2, z), Blocks.SPRUCE_STAIRS.defaultBlockState(), 3);
			BlockPos foot = bed.offset(1, 1, 2);
			BlockState bedState = Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
				.setValue(BedBlock.PART, BedPart.FOOT);
			level.setBlock(foot, bedState, 3);
			level.setBlock(foot.east(), bedState.setValue(BedBlock.PART, BedPart.HEAD), 3);
			BlockPos lantern = bed.offset(0, 2, 1);
			level.setBlock(lantern, Blocks.SOUL_LANTERN.defaultBlockState(), 3);

			HouseTemplate template = HouseTemplate.capture(level, foot, bed);
			if (template == null) throw new IllegalStateException("R02 decoration fixture was not captured as a house template");
			BlockPos target = bed.offset(8, 1, 0);
			List<Step> original = template.steps(target, Direction.SOUTH, 100, false, block -> block, true);
			de.villagerplus.village.VillageStyle unavailable = new de.villagerplus.village.VillageStyle(Blocks.BRICKS,
				Blocks.BRICKS, Blocks.BRICKS, Blocks.BRICK_STAIRS, Blocks.IRON_DOOR, Blocks.IRON_BARS);
			List<Step> fallback = template.steps(target, Direction.SOUTH, 100, false,
				block -> Supply.substitute(block, unavailable, net.minecraft.resources.Identifier.withDefaultNamespace("desert")), true);
			boolean samePlan = original.size() == fallback.size();
			for (int i = 0; samePlan && i < original.size(); i++) {
				Step a = original.get(i), b = fallback.get(i);
				samePlan = a.pos().equals(b.pos()) && a.cost() == b.cost() && a.free() == b.free()
					&& java.util.Objects.equals(a.linkedTo(), b.linkedTo());
			}
			check(samePlan && original.stream().anyMatch(step -> step.item() == Blocks.SOUL_LANTERN.asItem())
				&& fallback.stream().anyMatch(step -> step.item() == Blocks.TORCH.asItem()),
				"captured source-template lantern keeps the same paid step index and placement after biome fallback");
			return 1;
		} finally {
			restoreArea(level, before);
		}
	}

	/** Keeps two freight donkeys on safe, separate live-trader approaches and resumes their route after chunk lifecycle events. */
	private static int caravanDonkeyFollowChecks(ServerLevel level, BlockPos at, VillageRef ref, List<Entity> spawned) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		BlockPos origin = at.south(32).immutable();
		net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader trader = null;
		Caravan.State state = null;
		try {
			for (int x = -6; x <= 18; x++) for (int z = -6; z <= 6; z++) for (int y = -1; y <= 3; y++) {
				BlockPos pos = origin.offset(x, y, z);
				snapshot(level, before, pos);
				level.setBlock(pos, y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
			}
			int wallX = origin.getX() + 5;
			for (int z = -2; z <= 2; z++) for (int y = 0; y <= 3; y++) {
				level.setBlock(origin.offset(5, y, z), Blocks.OBSIDIAN.defaultBlockState(), 3);
			}
			trader = EntityTypes.WANDERING_TRADER.spawn(level, origin.east(12), EntitySpawnReason.COMMAND);
			if (trader == null) throw new IllegalStateException("R02 follow fixture cannot create its caravan porter");
			trader.setPos(origin.east(12).getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
			trader.setOnGround(true);
			spawned.add(trader);
			state = new Caravan.State(trader, level, ref, origin.west(3), -1);
			state.traded = true;
			state.leaveAt = level.getGameTime();
			state.routeDimension = Network.dim(level);
			state.routeBell = origin.west(80).immutable();
			state.routeRelationDelta = 5;
			state.routeVisitDay = level.getGameTime() / 24000L;
			state.roadKey = state.routeDimension + state.routeBell.asLong();
			state.roadCursor = 3;
			spawnFollowDonkey(level, origin.west(3).north(2), ref, trader, state, spawned, Items.OAK_PLANKS);
			spawnFollowDonkey(level, origin.west(3).south(2), ref, trader, state, spawned, Items.BRICKS);
			Caravan.registerActiveStateForChecks(state);
			// Keep the trader's own active route on the near side. The obsidian barrier is
			// reserved for the pack-animal approach assertions below; forcing the merchant
			// across it makes this fixture conflate two independent navigation checks.
			BlockPos traderRouteGoal = origin.east(8);
			var traderRoute = trader.getNavigation().createPath(traderRouteGoal, 1);
			if (traderRoute == null || !traderRoute.canReach() || !trader.getNavigation().moveTo(traderRoute, 0.5)) {
				throw new IllegalStateException("R02 follow fixture cannot start the caravan's active route [start="
					+ trader.blockPosition() + ", target=" + traderRouteGoal + ", path="
					+ (traderRoute == null ? "null" : "reachable=" + traderRoute.canReach()
						+ ", nodes=" + traderRoute.getNodeCount()) + "]");
			}

			long guidanceAt = Math.max(1L, level.getGameTime() + 1L);
			var first = (Donkey) level.getEntity(state.llamas.get(0));
			var second = (Donkey) level.getEntity(state.llamas.get(1));
			var firstBefore = first.position();
			var secondBefore = second.position();
			Caravan.guideDepartingDonkeys(level, state, trader, guidanceAt);
			var firstTarget = first.getNavigation().getTargetPos();
			var secondTarget = second.getNavigation().getTargetPos();
			var firstPath = first.getNavigation().getPath();
			var secondPath = second.getNavigation().getPath();
			check(firstTarget != null && secondTarget != null && !firstTarget.equals(secondTarget)
				&& trader.getNavigation().isInProgress() && state.routeBell != null && state.roadCursor == 3
				&& !CaravanSites.bounds(first, firstTarget).intersects(CaravanSites.bounds(second, secondTarget))
				&& Caravan.donkeyPathFailureReason(level, first, firstPath, firstTarget) == null
				&& Caravan.donkeyPathFailureReason(level, second, secondPath, secondTarget) == null
				&& pathAvoidsWall(firstPath, wallX, origin.getZ() - 2, origin.getZ() + 2)
				&& pathAvoidsWall(secondPath, wallX, origin.getZ() - 2, origin.getZ() + 2)
				&& first.position().equals(firstBefore) && second.position().equals(secondBefore)
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7,
				"two freight donkeys get separate validated paths around a solid boundary without teleporting or changing cargo");
			int checks = 1;
			Caravan.guideDepartingDonkeys(level, state, trader, guidanceAt + 20L);
			var firstPathAfterRefresh = first.getNavigation().getPath();
			var secondPathAfterRefresh = second.getNavigation().getPath();
			var firstTargetAfterRefresh = first.getNavigation().getTargetPos();
			var secondTargetAfterRefresh = second.getNavigation().getTargetPos();
			boolean firstRouteRetained = firstTarget.equals(firstTargetAfterRefresh)
				&& sameDonkeyRoute(firstPath, firstPathAfterRefresh);
			check(firstRouteRetained,
				"the first donkey retains the same safe approach across a follow refresh [target="
					+ firstTarget + "->" + firstTargetAfterRefresh + ", before=" + describeDonkeyPath(firstPath)
					+ ", after=" + describeDonkeyPath(firstPathAfterRefresh) + "]"); checks++;
			boolean secondRouteRetained = secondTarget.equals(secondTargetAfterRefresh)
				&& sameDonkeyRoute(secondPath, secondPathAfterRefresh);
			check(secondRouteRetained,
				"the second donkey retains the same safe approach across a follow refresh [target="
					+ secondTarget + "->" + secondTargetAfterRefresh + ", before=" + describeDonkeyPath(secondPath)
					+ ", after=" + describeDonkeyPath(secondPathAfterRefresh) + "]"); checks++;
		boolean refreshedRoutesRemainSafe = first.getNavigation().isInProgress()
			&& second.getNavigation().isInProgress()
			&& Caravan.donkeyPathFailureReason(level, first, firstPathAfterRefresh, firstTargetAfterRefresh) == null
			&& Caravan.donkeyPathFailureReason(level, second, secondPathAfterRefresh, secondTargetAfterRefresh) == null
			&& pathAvoidsWall(firstPathAfterRefresh, wallX, origin.getZ() - 2, origin.getZ() + 2)
			&& pathAvoidsWall(secondPathAfterRefresh, wallX, origin.getZ() - 2, origin.getZ() + 2)
			&& first.position().equals(firstBefore) && second.position().equals(secondBefore)
			&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
		check(refreshedRoutesRemainSafe,
			"both refreshed donkey approaches remain active, wall-safe, and cargo-preserving [firstFailure="
				+ Caravan.donkeyPathFailureReason(level, first, firstPathAfterRefresh, firstTargetAfterRefresh)
				+ ", secondFailure=" + Caravan.donkeyPathFailureReason(level, second, secondPathAfterRefresh, secondTargetAfterRefresh)
				+ ", firstWallSafe=" + pathAvoidsWall(firstPathAfterRefresh, wallX, origin.getZ() - 2, origin.getZ() + 2)
				+ ", secondWallSafe=" + pathAvoidsWall(secondPathAfterRefresh, wallX, origin.getZ() - 2, origin.getZ() + 2)
				+ ", cargo=" + count(first, Items.OAK_PLANKS) + "/" + count(second, Items.BRICKS) + "]"); checks++;
			Caravan.pausePackDonkeyBeforeUnload(level, first);
			boolean heldForUnload = state.waitingForDonkeys && !state.departurePathReady
				&& !Caravan.packAnimalsCanKeepUp(level, state, trader);
			Caravan.resumePackDonkeyAfterLoad(level, first);
			Caravan.guideDepartingDonkeys(level, state, trader, guidanceAt + 40L);
			check(heldForUnload && state.departurePathReady && Caravan.packAnimalsCanKeepUp(level, state, trader)
				&& count(first, Items.OAK_PLANKS) == 7 && state.routeBell.equals(origin.west(80)) && state.roadCursor == 3,
				"a reloaded pack donkey resumes the saved caravan route with its freight intact"); checks++;
			return checks;
		} finally {
			if (state != null) Caravan.removeActiveStateForChecks(state.trader);
			restoreArea(level, before);
		}
	}

	private static Donkey spawnFollowDonkey(ServerLevel level, BlockPos at, VillageRef ref,
		net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader trader, Caravan.State state,
		List<Entity> spawned, Item freight) {
		Donkey donkey = EntityTypes.DONKEY.spawn(level, at, EntitySpawnReason.COMMAND);
		if (donkey == null) throw new IllegalStateException("R02 follow fixture cannot create a pack donkey");
		donkey.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
		donkey.setOnGround(true);
		donkey.setTamed(true);
		var chest = donkey.getSlot(AbstractHorse.CHEST_SLOT_OFFSET);
		if (chest == null || !chest.set(new ItemStack(Items.CHEST)) || !donkey.hasChest()) {
			donkey.discard();
			throw new IllegalStateException("R02 follow fixture cannot equip a freight donkey chest");
		}
		// Equipping the chest rebuilds the donkey inventory in 26.2; resolve cargo slots only afterward.
		var cargo = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + 2);
		if (cargo == null || !cargo.set(new ItemStack(freight, 7))) {
			donkey.discard();
			throw new IllegalStateException("R02 follow fixture cannot seed the freight donkey cargo slot");
		}
		donkey.addTag("vp_caravan");
		donkey.addTag("vp_caravan_bell:" + ref.bell().asLong());
		donkey.setPersistenceRequired();
		donkey.setLeashedTo(trader, true);
		state.llamas.add(donkey.getUUID());
		spawned.add(donkey);
		return donkey;
	}

	private static boolean pathAvoidsWall(net.minecraft.world.level.pathfinder.Path path, int x, int minZ, int maxZ) {
		if (path == null || !path.canReach()) return false;
		for (int i = 0; i < path.getNodeCount(); i++) {
			BlockPos node = path.getNodePos(i);
			if (node.getX() == x && node.getZ() >= minZ && node.getZ() <= maxZ) return false;
		}
		return true;
	}

	private static boolean sameDonkeyRoute(net.minecraft.world.level.pathfinder.Path before,
		net.minecraft.world.level.pathfinder.Path after) {
		if (before == null || after == null || before.canReach() != after.canReach()
			|| !before.getTarget().equals(after.getTarget())
			|| before.getNodeCount() != after.getNodeCount()) return false;
		for (int i = 0; i < before.getNodeCount(); i++) {
			if (!before.getNodePos(i).equals(after.getNodePos(i))) return false;
		}
		return true;
	}

	private static String describeDonkeyPath(net.minecraft.world.level.pathfinder.Path path) {
		if (path == null) return "null";
		List<BlockPos> nodes = new ArrayList<>();
		for (int i = 0; i < path.getNodeCount(); i++) nodes.add(path.getNodePos(i));
		return "target=" + path.getTarget() + ",reachable=" + path.canReach()
			+ ",next=" + path.getNextNodeIndex() + ",nodes=" + nodes;
	}

	private static Map<BlockPos, Cell> snapshotArea(ServerLevel level, BlockPos at) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (int x = -4; x <= 12; x++) for (int y = -1; y <= 2; y++) snapshot(level, before, at.offset(x, y, 0));
		return before;
	}

	private static void snapshot(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		BlockPos immutable = pos.immutable();
		level.getChunkAt(immutable);
		BlockEntity entity = level.getBlockEntity(immutable);
		before.putIfAbsent(immutable, new Cell(level.getBlockState(immutable),
			entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restoreArea(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.nbt() == null) return;
			BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		});
	}

	private record Cell(BlockState state, CompoundTag nbt) { }

	private record WanderingSetup(net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader trader,
		Donkey donkey, Villager porter, Caravan.State state) { }

	private static WanderingSetup spawnCaravan(ServerLevel level, VillageRef ref, BlockPos at, List<Entity> spawned) {
		var trader = EntityTypes.WANDERING_TRADER.spawn(level, at.east(11), EntitySpawnReason.COMMAND);
		if (trader == null) throw new IllegalStateException("R02 fixture cannot create a trader");
		trader.setPos(at.east(11).getX() + 0.5, at.getY(), at.east(11).getZ() + 0.5);
		spawned.add(trader);
		var donkey = EntityTypes.DONKEY.spawn(level, at.east(9), EntitySpawnReason.COMMAND);
		if (donkey == null) throw new IllegalStateException("R02 fixture cannot create a pack donkey");
		donkey.setPos(at.east(9).getX() + 0.5, at.getY(), at.east(9).getZ() + 0.5);
		spawned.add(donkey);
		donkey.setTamed(true);
		var chestSlot = donkey.getSlot(AbstractHorse.CHEST_SLOT_OFFSET);
		if (chestSlot == null || !chestSlot.set(new ItemStack(Items.CHEST)) || !donkey.hasChest()) {
			throw new IllegalStateException("R02 fixture cannot equip the donkey");
		}
		donkey.addTag("vp_caravan");
		donkey.addTag("vp_caravan_bell:" + ref.bell().asLong());
		var state = new Caravan.State(trader, level, ref, at.east(11), 0);
		state.llamas.add(donkey.getUUID());
		var porter = EntityTypes.VILLAGER.spawn(level, at.east(7), EntitySpawnReason.COMMAND);
		if (porter == null) throw new IllegalStateException("R02 fixture cannot spawn a porter");
		spawned.add(porter);
		porter.setInvulnerable(true);
		porter.setNoAi(false);
		Villages.bindResident(level, ref, porter, ref.bell().south(6));
		return new WanderingSetup(trader, donkey, porter, state);
	}

	private static int count(Donkey donkey, Item item) {
		int count = 0;
		for (int slot = 2; slot < 17; slot++) {
			var access = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + slot);
			if (access != null && !access.get().isEmpty() && access.get().is(item)) count += access.get().getCount();
		}
		return count;
	}

	private static int countSlot(Donkey donkey, int slot, Item item) {
		var access = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + slot);
		return access != null && !access.get().isEmpty() && access.get().is(item) ? access.get().getCount() : 0;
	}

	private static List<String> taggedDonkeySummary(ServerLevel level, BlockPos bell) {
		String villageTag = "vp_caravan_bell:" + bell.asLong();
		return java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
			.filter(entity -> entity instanceof Donkey donkey && donkey.isAlive() && donkey.entityTags().contains(villageTag))
			.map(entity -> {
				Donkey donkey = (Donkey) entity;
				return donkey.getUUID() + ":bricks=" + count(donkey, Items.BRICKS)
					+ ":dirt=" + count(donkey, Items.DIRT)
					+ ":planBricks=" + java.util.stream.IntStream.range(2, 17)
						.filter(slot -> Caravan.isPlanFreightSlotForChecks(donkey, slot, Items.BRICKS))
						.map(slot -> countSlot(donkey, slot, Items.BRICKS)).sum();
			}).toList();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("CARAVAN R02 CHECK FAILED: " + message);
	}
}
