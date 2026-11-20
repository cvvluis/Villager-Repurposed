package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.PlanSupplyOrder;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.Anim;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.PorterJob;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** K04: porter walks from the donkey to the granary and commits food without test teleports. */
public final class CaravanPorterDeliveryChecks {
	public static final int PREPARING = -1;
	private static final int OVERFLOW_FIXTURE_TICKET_RADIUS = 2;
	private record OverflowSite(ChunkPos fixtureChunk, BlockPos bell, BlockPos at) { }
	private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, OverflowSite> OVERFLOW_PREPARATIONS = new HashMap<>();

	private CaravanPorterDeliveryChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		if (!prepareOverflowFixture(level, at)) return PREPARING;
		try {
			return runPrepared(level, at);
		} finally {
			cleanupPendingOverflowTicket(level);
		}
	}

	private static int runPrepared(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
		var ref = Checks090.fixtureForEquipment(level, at);
		Container materialChest = ref.chest(level);
		BlockPos granary = ref.bell().north(20);
		BlockPos donkeyPad = ref.bell().east(14);
		BlockPos porterStart = ref.bell().east(10);
		carvePath(level, donkeyPad, porterStart);
		carvePath(level, porterStart, granary);
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			BlockPos p = granary.offset(dx, 0, dz);
			level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(p, dx == 0 && dz == 0 ? Blocks.CHEST.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
		}
		Specials.set(ref, Blueprints.GRANARY, granary);
		var porter = EntityTypes.VILLAGER.spawn(level, porterStart, EntitySpawnReason.COMMAND);
		porter.addTag("vp090_fixture");
		porter.setInvulnerable(true);
		porter.setNoAi(false);
		porter.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
		Villages.bindResident(level, ref, porter, ref.bell().south(6));
		var donkey = EntityTypes.DONKEY.create(level, EntitySpawnReason.COMMAND);
		donkey.setPos(donkeyPad.getX() + 0.5, donkeyPad.getY(), donkeyPad.getZ() + 0.5);
		donkey.setNoAi(false);
		donkey.addTag("vp090_fixture");
		// This intentionally models a village-registered cargo donkey after its transient
		// caravan state is gone; its free leash must still be transferred to the porter.
		donkey.addTag("vp_caravan_bell:" + ref.bell().asLong());
		int checks = 0;
		try {
			materialChest.clearContent();
			check(level.addFreshEntity(donkey), "donkey fixture loads on a cleared path"); checks++;
			donkey.setTamed(true);
			var chestLayout = donkey.getSlot(AbstractHorse.CHEST_SLOT_OFFSET);
			if (chestLayout != null) chestLayout.set(new ItemStack(Items.CHEST));
			var cargo = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + 2);
			check(cargo != null && cargo.set(new ItemStack(Items.WHEAT, 16)), "donkey cargo slot holds sixteen wheat"); checks++;
			var mem = porter.getAttachedOrCreate(VillagerPlus.MEMORY);
			var st = JobManager.state(porter);
			st.idleUntil[0] = 0;
			long now = level.getGameTime() + 30_000;
			PorterJob job = new PorterJob();
			boolean claimed = false;
			for (int tick = 0; tick < 400 && !claimed; tick++) {
				porter.tick();
				donkey.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, now + tick));
				Anim.apply(porter, st, now + tick);
				claimed = Caravan.carryingFor(porter) && donkey.getLeashHolder() == porter;
			}
			var heldCargo = porter.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
			var carriedDonkeyDisplays = level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,
				donkey.getBoundingBox().inflate(4.0), display -> display.entityTags().contains("vp_caravan_cargo_owner:" + donkey.getUUID()));
			var porterLeashHolder = donkey.getLeashHolder();
			check(claimed && heldCargo.is(Items.WHEAT) && heldCargo.getCount() == 1 && countDonkeyWheat(donkey) == 16
				&& carriedDonkeyDisplays.isEmpty(),
				"the leashed porter visibly holds one render copy of the real donkey wheat while the full cargo stays in the donkey"
					+ " [claimed=" + claimed + ", hand=" + heldCargo + " x" + heldCargo.getCount()
					+ ", donkeyWheat=" + countDonkeyWheat(donkey) + ", donkeyDisplayCount=" + carriedDonkeyDisplays.size()
					+ ", leashHolder=" + (porterLeashHolder == null ? "none" : porterLeashHolder.getUUID()) + "]"); checks++;
			Caravan.releasePorter(porter);
			Anim.apply(porter, st, now + 400);
			var restoredDonkeyDisplays = level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,
				donkey.getBoundingBox().inflate(4.0), display -> display.entityTags().contains("vp_caravan_cargo_owner:" + donkey.getUUID()));
			check(!Caravan.carryingFor(porter) && countDonkeyWheat(donkey) == 16
				&& porter.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).is(Items.STICK)
				&& donkey.getLeashHolder() == null
				&& restoredDonkeyDisplays.size() == 1,
				"interrupting a recovered escort restores the porter's original hand and the donkey cargo marker without moving goods"); checks++;
			claimed = false;
			for (int tick = 0; tick < 400 && !claimed; tick++) {
				porter.tick();
				donkey.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, now + 400 + tick));
				claimed = Caravan.carryingFor(porter);
			}
			check(claimed, "interrupted cargo can be claimed again"); checks++;
			var deliveryStart = porter.position();
			var donkeyStart = donkey.position();
			boolean observedLoadedTravel = false, observedVisibleHandWhileMoving = false;
			for (int tick = 0; tick < 2400 && Caravan.carryingFor(porter); tick++) {
				porter.tick();
				donkey.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, now + 800 + tick));
				job.primary(new Ctx(level, porter, st, mem, ref, now + 800 + tick));
				Anim.apply(porter, st, now + 800 + tick);
				int remaining = countDonkeyWheat(donkey);
				if (remaining == 16 && donkey.position().distanceToSqr(donkeyStart) > 4.0) {
					observedLoadedTravel = true;
					var hand = porter.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
					if (hand.is(Items.WHEAT) && hand.getCount() == 1 && donkey.getLeashHolder() == porter) observedVisibleHandWhileMoving = true;
				}
				if (remaining == 0) check(donkey.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(granary)) <= 25.0,
					"cargo is not transferred before the donkey reaches the depot");
			}
			check(observedLoadedTravel, "real wheat remains in the moving donkey"); checks++;
			check(observedVisibleHandWhileMoving, "porter keeps the visible one-item wheat handstack while escorting the loaded donkey"); checks++;
			check(!Caravan.carryingFor(porter), "porter finishes delivery by walking to the granary stand; porter="
				+ porter.blockPosition().toShortString() + ", donkey=" + donkey.blockPosition().toShortString()
				+ ", target=" + st.target + ", cooldown=" + st.cooldownUntil + ", wheat=" + countDonkeyWheat(donkey)
				+ ", " + Caravan.porterDeliveryDiagnostic(level,porter)); checks++;
			check(porter.position().distanceToSqr(deliveryStart) > 16.0,
				"porter actually travels from the donkey towards the granary"); checks++;
			double donkeyTravel=donkey.position().distanceToSqr(donkeyStart);
			double donkeyToDepot=donkey.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(granary));
			check(donkeyTravel > 16.0 && donkeyToDepot <= 25.0,
				"loaded donkey travels to the granary before delivery; start="+donkeyStart+", end="+donkey.position()+", travel="+donkeyTravel+", depot="+donkeyToDepot
				+", "+Caravan.porterDeliveryDiagnostic(level,porter)); checks++;
			Container granaryBox = (Container) level.getBlockEntity(granary);
			check(granaryBox != null && JobUtil.count(granaryBox, s -> s.is(Items.WHEAT)) == 16
				&& JobUtil.count(materialChest, s -> s.is(Items.WHEAT)) == 0,
				"sixteen wheat land in the granary, not the material crate"); checks++;
			check(countDonkeyWheat(donkey) == 0, "donkey cargo empties after successful delivery"); checks++;

			// A full category depot must leave all cargo in the donkey and become
			// eligible again once real capacity is made available.
			granaryBox.clearContent();
			for (int i = 0; i < granaryBox.getContainerSize(); i++) granaryBox.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
			check(cargo.set(new ItemStack(Items.WHEAT, 16)), "retry cargo is loaded"); checks++;
			for (int tick = 0; tick < 80; tick++) {
				porter.tick();
				donkey.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, now + 5000 + tick));
			}
			check(!Caravan.carryingFor(porter) && countDonkeyWheat(donkey) == 16
				&& JobUtil.count(granaryBox, s -> s.is(Items.WHEAT)) == 0,
				"full granary retains the entire shipment in the donkey"); checks++;
			granaryBox.setItem(0, ItemStack.EMPTY);
			boolean retryStarted = false;
			for (int tick = 0; tick < 400 && !retryStarted; tick++) {
				porter.tick();
				donkey.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, now + 5200 + tick));
				retryStarted = Caravan.carryingFor(porter) || (countDonkeyWheat(donkey) == 0
					&& JobUtil.count(granaryBox, s -> s.is(Items.WHEAT)) == 16
					&& JobUtil.count(materialChest, s -> s.is(Items.WHEAT)) == 0);
			}
			check(retryStarted, "porter retries after granary capacity becomes available; donkeyWheat="
				+ countDonkeyWheat(donkey) + ", granaryWheat=" + JobUtil.count(granaryBox, s -> s.is(Items.WHEAT))
				+ ", slot0=" + granaryBox.getItem(0) + ", cooldown=" + st.cooldownUntil + ", "
				+ Caravan.porterRetryDiagnostic(level, ref, porter, donkey, new ItemStack(Items.WHEAT, 16))); checks++;
			for (int tick = 0; tick < 2400 && Caravan.carryingFor(porter); tick++) {
				porter.tick();
				donkey.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, now + 5600 + tick));
			}
			check(!Caravan.carryingFor(porter) && countDonkeyWheat(donkey) == 0
				&& JobUtil.count(granaryBox, s -> s.is(Items.WHEAT)) == 16
				&& JobUtil.count(materialChest, s -> s.is(Items.WHEAT)) == 0,
				"retry delivers exactly sixteen wheat to the granary; donkeyWheat=" + countDonkeyWheat(donkey)
				+ ", granaryWheat=" + JobUtil.count(granaryBox, s -> s.is(Items.WHEAT))
				+ ", cooldown=" + st.cooldownUntil + ", "
				+ Caravan.porterRetryDiagnostic(level, ref, porter, donkey, new ItemStack(Items.WHEAT, 16))); checks++;

			granaryBox.clearContent();
			for (int slot = 0; slot < granaryBox.getContainerSize(); slot++) granaryBox.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			granaryBox.setItem(0, new ItemStack(Items.WHEAT, 60));
			check(cargo.set(new ItemStack(Items.WHEAT, 16)), "partial-capacity shipment stays in the real donkey inventory"); checks++;
			Caravan.syncCargoVisual(level, donkey);
			donkey.setPos(donkeyPad.getX() + 0.5, donkeyPad.getY(), donkeyPad.getZ() + 0.5);
			porter.setPos(porterStart.getX() + 0.5, porterStart.getY(), porterStart.getZ() + 0.5);
			st.cooldownUntil = 0;
			long partialNow = now + 10_000;
			for (int tick = 0; tick < 2400 && countDonkeyWheat(donkey) == 16; tick++) {
				porter.tick();
				donkey.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, partialNow + tick));
			}
			check(!Caravan.carryingFor(porter) && countDonkeyWheat(donkey) == 12
				&& JobUtil.count(granaryBox, s -> s.is(Items.WHEAT)) == 64,
				"porter delivers only the four wheat units accepted by the actual remaining granary capacity"); checks++;
			var cargoVisuals = level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,
				donkey.getBoundingBox().inflate(4.0), display -> display.entityTags().contains("vp_caravan_cargo_owner:" + donkey.getUUID()));
			check(cargoVisuals.size() == 1 && cargoVisuals.get(0).getItemStack().is(Items.WHEAT)
				&& countDonkeyWheat(donkey) == 12,
				"remaining real donkey wheat stays visibly represented after a partial unload"); checks++;
			for (int tick = 0; tick < 80; tick++) {
				porter.tick();
				donkey.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, partialNow + 2400 + tick));
			}
			check(!Caravan.carryingFor(porter) && countDonkeyWheat(donkey) == 12,
				"full depot does not consume or hide the twelve-item donkey remainder"); checks++;
			granaryBox.setItem(1, ItemStack.EMPTY);
			boolean partialRetry = false;
			for (int tick = 0; tick < 2400 && countDonkeyWheat(donkey) == 12; tick++) {
				porter.tick();
				donkey.tick();
				Caravan.porterDelivery(new Ctx(level, porter, st, mem, ref, partialNow + 2480 + tick));
				partialRetry = countDonkeyWheat(donkey) == 0;
			}
			check(partialRetry && countDonkeyWheat(donkey) == 0
				&& JobUtil.count(granaryBox, s -> s.is(Items.WHEAT)) == 76,
				"retained surplus retries when real public storage space returns"); checks++;
			checks += partialPaidFreightChecks(level, ref, materialChest, donkey, at);
			checks += overflowPackMuleChecks(level, at);
			return checks;
		} finally {
			porter.discard();
			donkey.discard();
			Villages.forget(porter.getUUID());
		}
	}

	/** A partial load is paid from real emeralds only for the quantity that fit in existing cargo. */
	private static int partialPaidFreightChecks(ServerLevel level, VillageRef ref, Container box,
			net.minecraft.world.entity.animal.equine.Donkey donkey, BlockPos at) {
		List<ItemStack> originalBox = new ArrayList<>();
		for (int slot = 0; slot < box.getContainerSize(); slot++) originalBox.add(box.getItem(slot).copy());
		Map<Integer, ItemStack> originalCargo = new LinkedHashMap<>();
		int cargoSlotLimit = Math.min(donkey.getInventorySize(), 17);
		for (int slot = 2; slot < cargoSlotLimit; slot++) {
			var access = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + slot);
			if (access != null) originalCargo.put(slot, access.get().copy());
		}
		long treasuryBefore = Network.treasuryEmeralds(ref);
		net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader trader = null;
		int checks = 0;
		try {
			box.clearContent();
			box.setItem(0, new ItemStack(Items.EMERALD, 2));
			if (treasuryBefore > 0 && !Network.spendTreasuryEmeralds(ref, treasuryBefore)) {
				throw new IllegalStateException("partial freight fixture could not isolate its physical emerald payment");
			}
			List<Integer> cargoSlots = new ArrayList<>();
			for (Map.Entry<Integer, ItemStack> entry : originalCargo.entrySet()) {
				var access = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + entry.getKey());
				if (access == null) continue;
				boolean acceptsBricks = access.set(new ItemStack(Items.BRICKS, 1))
					&& access.get().is(Items.BRICKS) && access.get().getCount() == 1;
				if (!access.set(entry.getValue().copy())) {
					throw new IllegalStateException("partial freight fixture could not restore probed donkey slot " + entry.getKey());
				}
				if (acceptsBricks) cargoSlots.add(entry.getKey());
			}
			if (cargoSlots.isEmpty()) throw new IllegalStateException("partial freight fixture found no usable donkey cargo slots");
			int brickStackLimit = new ItemStack(Items.BRICKS).getMaxStackSize();
			for (int index = 0; index < cargoSlots.size(); index++) {
				int slot = cargoSlots.get(index);
				var access = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + slot);
				int count = index == 0 ? brickStackLimit - 4 : brickStackLimit;
				if (access == null || !access.set(new ItemStack(Items.BRICKS, count))
					|| !access.get().is(Items.BRICKS) || access.get().getCount() != count) {
					throw new IllegalStateException("partial freight fixture could not seed usable donkey slot " + slot);
				}
			}
			int bricksBefore = countDonkeyItem(donkey, Items.BRICKS);
			int expectedBricksBefore = cargoSlots.size() * brickStackLimit - 4;
			if (bricksBefore != expectedBricksBefore) {
				throw new IllegalStateException("partial freight fixture did not leave exactly four brick capacity units"
					+ " (slots=" + cargoSlots + ", bricks=" + bricksBefore + ", expected=" + expectedBricksBefore + ")");
			}
			trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.COMMAND);
			if (trader == null) throw new IllegalStateException("partial freight fixture could not create a trader");
			trader.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
			if (!level.addFreshEntity(trader)) throw new IllegalStateException("partial freight fixture could not register its trader");
			Caravan.State state = new Caravan.State(trader, level, ref, at, 0);
			trader.discard(); // The import must use the four physically free slots if overflow placement is unavailable.
			state.llamas.add(donkey.getUUID());

			int loaded = Caravan.buyTradeFreight(level, state, ref, box, Items.BRICKS, 20, 8);
			check(loaded == 4 && countDonkeyItem(donkey, Items.BRICKS) == bricksBefore + 4
				&& JobUtil.count(box, stack -> stack.is(Items.BRICKS)) == 0,
				"a twenty-brick import loads only the four free donkey capacity units and creates no depot shortcut"); checks++;
			int expectedCost = PlanSupplyOrder.emeraldCost(4, 8);
			check(JobUtil.count(box, stack -> stack.is(Items.EMERALD)) == 2 - expectedCost
				&& Network.treasuryEmeralds(ref) == 0,
				"the partial load costs the established rounded price for four bricks (" + expectedCost
					+ " physical emerald), with no treasury debit"); checks++;
			return checks;
		} finally {
			if (trader != null) trader.discard();
			box.clearContent();
			for (int slot = 0; slot < Math.min(box.getContainerSize(), originalBox.size()); slot++) box.setItem(slot, originalBox.get(slot).copy());
			box.setChanged();
			for (Map.Entry<Integer, ItemStack> entry : originalCargo.entrySet()) {
				var access = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + entry.getKey());
				if (access != null) access.set(entry.getValue().copy());
			}
			Caravan.syncCargoVisual(level, donkey);
			long treasuryNow = Network.treasuryEmeralds(ref);
			if (treasuryNow > treasuryBefore) Network.spendTreasuryEmeralds(ref, treasuryNow - treasuryBefore);
			else if (treasuryNow < treasuryBefore) Network.creditTreasuryEmeralds(ref, treasuryBefore - treasuryNow);
		}
	}

	/** Request the isolated fixture chunk, then inspect it on a later command after server ticks. */
	private static boolean prepareOverflowFixture(ServerLevel level, BlockPos requested) {
		var dimension = level.dimension();
		OverflowSite site = OVERFLOW_PREPARATIONS.get(dimension);
		boolean firstPreparation = site == null;
		if (site == null) {
			site = selectOverflowSite(level, requested);
			OVERFLOW_PREPARATIONS.put(dimension, site);
		}
		try {
			// In 26.2, radius 2 maps the center ticket level from FULL (33) to
			// ENTITY_TICKING (31). This nonblocking call refreshes the same ticket on retries.
			level.getChunkSource().addTicketWithRadius(
				Anchor.WALL_WORK, site.fixtureChunk(), OVERFLOW_FIXTURE_TICKET_RADIUS);
		} catch (RuntimeException | Error failure) {
			cleanupPendingOverflowTicket(level);
			throw failure;
		}
		if (firstPreparation) {
			System.out.println("CARAVAN OVERFLOW PREPARING fixture=" + site.at() + " chunk=" + site.fixtureChunk()
				+ " status=TICKET_ADDED; retry /vptest090 caravandelivery after server ticks; no checks counted");
			return false;
		}
		// getChunkNow never initiates synchronous loading on the server thread.
		var chunk = level.getChunkSource().getChunkNow(site.fixtureChunk().x(), site.fixtureChunk().z());
		FullChunkStatus status = chunk == null ? null : chunk.getFullStatus();
		if (status == null || !status.isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
			System.out.println("CARAVAN OVERFLOW PREPARING fixture=" + site.at() + " chunk=" + site.fixtureChunk()
				+ " status=" + (status == null ? "UNLOADED" : status)
				+ "; retry /vptest090 caravandelivery after server ticks; no checks counted");
			return false;
		}
		return true;
	}

	private static OverflowSite selectOverflowSite(ServerLevel level, BlockPos requested) {
		BlockPos sharedSpawn = level.getRespawnData().pos();
		ChunkPos sharedSpawnChunk = ChunkPos.containing(sharedSpawn);
		var chunkSource = level.getChunkSource();
		var spawnChunkStatuses = new java.util.ArrayList<String>();
		ChunkPos entityTickingSpawnChunk = null;
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
			int chunkX = sharedSpawnChunk.x() + x;
			int chunkZ = sharedSpawnChunk.z() + z;
			var loaded = chunkSource.getChunkNow(chunkX, chunkZ);
			FullChunkStatus status = loaded == null ? null : loaded.getFullStatus();
			spawnChunkStatuses.add(chunkX + "," + chunkZ + "=" + (status == null ? "UNLOADED" : status));
			if (entityTickingSpawnChunk == null && status != null
				&& status.isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
				entityTickingSpawnChunk = new ChunkPos(chunkX, chunkZ);
			}
		}
		System.out.println("CARAVAN OVERFLOW sharedSpawn=" + sharedSpawn + " chunk=" + sharedSpawnChunk
			+ " nearbyStatuses=" + spawnChunkStatuses);
		ChunkPos fixtureChunk = entityTickingSpawnChunk == null ? sharedSpawnChunk : entityTickingSpawnChunk;
		BlockPos bell = new BlockPos(fixtureChunk.getMiddleBlockX(), requested.getY(), fixtureChunk.getMiddleBlockZ());
		// Keep the sandbox far from the porter route even if a world is configured to
		// place its shared spawn inside the synthetic test area.
		if (bell.distSqr(requested) < 256.0 * 256.0) {
			int offsetChunks = bell.getX() >= requested.getX() ? -32 : 32;
			fixtureChunk = new ChunkPos(fixtureChunk.x() + offsetChunks, fixtureChunk.z());
			bell = new BlockPos(fixtureChunk.getMiddleBlockX(), requested.getY(), fixtureChunk.getMiddleBlockZ());
		}
		return new OverflowSite(fixtureChunk, bell, bell.south(5));
	}

	private static void cleanupPendingOverflowTicket(ServerLevel level) {
		OverflowSite site = OVERFLOW_PREPARATIONS.remove(level.dimension());
		if (site != null) {
			level.getChunkSource().removeTicketWithRadius(
				Anchor.WALL_WORK, site.fixtureChunk(), OVERFLOW_FIXTURE_TICKET_RADIUS);
		}
	}

	/** A full pair of original cargo donkeys must gain safe, real capacity for incoming trade goods. */
	private static int overflowPackMuleChecks(ServerLevel level, BlockPos requested) {
		OverflowSite site = OVERFLOW_PREPARATIONS.get(level.dimension());
		if (site == null) throw new IllegalStateException("Caravan overflow fixture entered without a prepared chunk ticket");
		ChunkPos fixtureChunk = site.fixtureChunk();
		BlockPos bell = site.bell();
		BlockPos at = site.at();
		final String caravanBellTag = "vp_caravan_bell:" + bell.asLong();
		Map<BlockPos, WorldCell> before = new LinkedHashMap<>();
		var animals = new java.util.ArrayList<net.minecraft.world.entity.animal.equine.Donkey>();
		net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader trader = null;
		var fixtureBounds = new net.minecraft.world.phys.AABB(bell).inflate(16.0);
		int checks = 0;
		try {
			var fixtureChunkStatus = requireEntityTickingChunk(level, at, "overflow fixture");
			System.out.println("CARAVAN OVERFLOW fixture=" + at + " chunk=" + fixtureChunk
				+ " chunkStatus=" + fixtureChunkStatus + " requested=" + requested);
			// Save a one-block halo as well: neighbor updates from the 19x19x6 fixture
			// must not leave changes just outside its directly edited cells.
			for (BlockPos cursor : BlockPos.betweenClosed(bell.offset(-10, -2, -10), bell.offset(10, 5, 10))) {
				BlockPos pos = cursor.immutable();
				level.getChunkAt(pos);
				BlockEntity entity = level.getBlockEntity(pos);
				before.put(pos, new WorldCell(level.getBlockState(pos),
					entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
			}
			for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
				BlockPos floor = bell.offset(x, -1, z);
				level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
				for (int y = 0; y < 5; y++) level.setBlock(bell.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
			}
			level.setBlock(bell, Blocks.BELL.defaultBlockState(), 3);
			if (!(level.getBlockEntity(bell) instanceof BellBlockEntity bellEntity)) {
				throw new IllegalStateException("Caravan overflow: could not create the fixture bell block entity");
			}
			var ref = new VillageRef(bell, new VillageData(), bellEntity);
			// Create first and perform one explicit insertion. The previous fixture run showed
			// spawn() returning a trader that was not UUID-resolvable, then rejecting a second
			// addFreshEntity attempt; this keeps creation and registration outcomes distinct.
			trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.COMMAND);
			if (trader == null) throw new IllegalStateException("Caravan overflow: could not create trader fixture");
			trader.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
			var traderChunkStatusBeforeAdd = requireEntityTickingChunk(level, trader.blockPosition(), "trader fixture");
			if (!level.addFreshEntity(trader)) {
				throw new IllegalStateException("Caravan overflow: could not add created trader fixture (uuid="
					+ trader.getUUID() + ", chunkStatus=" + traderChunkStatusBeforeAdd
					+ ", removed=" + trader.isRemoved() + ", pos=" + trader.position() + ")");
			}
			var traderChunkStatus = requireEntityTickingChunk(level, trader.blockPosition(), "trader UUID lookup");
			var resolvedTrader = level.getEntity(trader.getUUID());
			if (resolvedTrader != trader || !trader.isAlive()) {
				throw new IllegalStateException("Caravan overflow: added trader fixture is not live and UUID-resolvable"
					+ " (uuid=" + trader.getUUID() + ", resolved=" + resolvedTrader + ", chunkStatus=" + traderChunkStatus
					+ ", presentInLevelEntities=" + containsEntityUuid(level, trader.getUUID())
					+ ", removed=" + trader.isRemoved() + ", pos=" + trader.position() + ")");
			}
			trader.addTag("vp090_fixture");
			var state = new Caravan.State(trader, level, ref, at, 0);
			int cargoSlotsPerDonkey = -1;
			for (int side : new int[] { -2, 2 }) {
				BlockPos pos = at.offset(side, 0, 2);
				var donkey = EntityTypes.DONKEY.create(level, EntitySpawnReason.COMMAND);
				if (donkey == null) throw new IllegalStateException("Caravan overflow: could not create original cargo donkey");
				donkey.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
				var donkeyChunkStatusBeforeAdd = requireEntityTickingChunk(level, donkey.blockPosition(), "original donkey fixture");
				if (!level.addFreshEntity(donkey)) {
					throw new IllegalStateException("Caravan overflow: could not add created original cargo donkey (uuid="
						+ donkey.getUUID() + ", chunkStatus=" + donkeyChunkStatusBeforeAdd
						+ ", removed=" + donkey.isRemoved() + ", pos=" + donkey.position() + ")");
				}
				var donkeyChunkStatus = requireEntityTickingChunk(level, donkey.blockPosition(), "original donkey UUID lookup");
				var resolvedDonkey = level.getEntity(donkey.getUUID());
				if (resolvedDonkey != donkey || !donkey.isAlive()) {
					throw new IllegalStateException("Caravan overflow: original donkey is not live and UUID-resolvable in the test world"
						+ " (uuid=" + donkey.getUUID() + ", resolved=" + resolvedDonkey + ", chunkStatus=" + donkeyChunkStatus
						+ ", presentInLevelEntities=" + containsEntityUuid(level, donkey.getUUID())
						+ ", removed=" + donkey.isRemoved() + ", pos=" + donkey.position() + ")");
				}
				animals.add(donkey);
				donkey.setTamed(true);
				// Mirror the working porter fixture: equip the chest through slot 499.
				var chest = donkey.getSlot(AbstractHorse.CHEST_SLOT_OFFSET);
				if (chest == null || !chest.set(new ItemStack(Items.CHEST)) || !donkey.hasChest()) {
					throw new IllegalStateException("Caravan overflow: could not equip original donkey chest (access=" + chest + ")");
				}
				donkey.addTag("vp_caravan");
				donkey.addTag("vp_caravan_bell:" + state.bell.asLong());
				state.llamas.add(donkey.getUUID());
				int cargoSlots = 0;
				for (int slot = 2; slot < 17; slot++) {
					int entitySlot = AbstractHorse.INVENTORY_SLOT_OFFSET + slot;
					var access = donkey.getSlot(entitySlot);
					if (access == null) continue;
					cargoSlots++;
					// Fill each real cargo stack; incoming cobblestone cannot merge into wheat.
					if (!access.set(new ItemStack(Items.WHEAT, 64))
						|| !access.get().is(Items.WHEAT) || access.get().getCount() != 64) {
						throw new IllegalStateException("Caravan overflow: could not fill original cargo slot " + slot
							+ " (entity slot " + entitySlot + ", hasChest=" + donkey.hasChest() + ", access=" + access + ")");
					}
				}
				if (cargoSlots == 0 || (cargoSlotsPerDonkey >= 0 && cargoSlots != cargoSlotsPerDonkey)) {
					throw new IllegalStateException("Caravan overflow: expected consistent cargo slots 2-16, found " + cargoSlots);
				}
				cargoSlotsPerDonkey = cargoSlots;
			}
			int originalWheat = animals.stream().mapToInt(d -> countDonkeyItem(d, Items.WHEAT)).sum();
			int expectedOriginalWheat = animals.size() * cargoSlotsPerDonkey * 64;
			check(originalWheat == expectedOriginalWheat,
				"both original pack donkeys have all " + cargoSlotsPerDonkey + " real cargo slots full ("
					+ expectedOriginalWheat + " wheat)"); checks++;

			ItemStack incoming = new ItemStack(Items.COBBLESTONE, 20);
			int packed = Caravan.packCargo(level, state, incoming);
			check(packed == 20 && incoming.isEmpty(), "cargo overflow creates a real pack mule instead of skipping porter delivery; packed="
				+ packed + ", incoming=" + incoming + ", members=" + state.llamas.size()
				+ ", registeredDonkeys=" + state.llamas.stream().map(level::getEntity).filter(java.util.Objects::nonNull).count()
				+ ", overflow=" + Caravan.overflowPackDonkeyDiagnostic()); checks++;
			check(state.llamas.size() == 3, "overflow mule joins the active caravan cargo group"); checks++;
			var extra = level.getEntity(state.llamas.getLast());
			var occupied = new java.util.ArrayList<net.minecraft.world.phys.AABB>();
			occupied.add(trader.getBoundingBox());
			for (var donkey : animals) occupied.add(donkey.getBoundingBox());
			check(extra instanceof net.minecraft.world.entity.animal.equine.Donkey d
				&& CaravanSites.canStand(level, d, d.blockPosition(), occupied),
				"overflow mule stands on supported, clear ground without overlapping caravan members"); checks++;
			check(extra instanceof net.minecraft.world.entity.animal.equine.Donkey d && d.hasChest()
				&& d.entityTags().contains("vp_caravan")
				&& d.entityTags().contains("vp_caravan_bell:" + state.bell.asLong()),
				"overflow mule has a chest and persistent village ownership tag"); checks++;
			check(extra instanceof net.minecraft.world.entity.animal.equine.Donkey d && countDonkeyItem(d, Items.COBBLESTONE) == 20,
				"all overflow cobblestone remains in the mule's real inventory for porter delivery"); checks++;
			var allCargoDonkeys = new java.util.ArrayList<net.minecraft.world.entity.animal.equine.Donkey>(animals);
			if (extra instanceof net.minecraft.world.entity.animal.equine.Donkey d) allCargoDonkeys.add(d);
			int allWheat = allCargoDonkeys.stream().mapToInt(d -> countDonkeyItem(d, Items.WHEAT)).sum();
			int allCobblestone = allCargoDonkeys.stream().mapToInt(d -> countDonkeyItem(d, Items.COBBLESTONE)).sum();
			check(allWheat == expectedOriginalWheat && allCobblestone == 20 && incoming.isEmpty(),
				"overflow preserves exactly " + expectedOriginalWheat + " original wheat and twenty incoming cobblestone"); checks++;
			return checks;
		} finally {
			try {
				var cleanupIds = new java.util.HashSet<java.util.UUID>();
				for (var donkey : animals) cleanupIds.add(donkey.getUUID());
				for (var donkey : level.getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Donkey.class, fixtureBounds,
					e -> e.entityTags().contains(caravanBellTag))) cleanupIds.add(donkey.getUUID());
				for (java.util.UUID id : cleanupIds) {
					String ownerTag = "vp_caravan_cargo_owner:" + id;
					for (var display : level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class, fixtureBounds,
						e -> e.entityTags().contains(ownerTag))) display.discard();
					if (level.getEntity(id) instanceof net.minecraft.world.entity.animal.equine.Donkey donkey) donkey.discard();
				}
				if (trader != null) trader.discard();
				restoreWorldCells(level, before);
			} finally {
				cleanupPendingOverflowTicket(level);
			}
		}
	}

	private static boolean containsEntityUuid(ServerLevel level, java.util.UUID uuid) {
		for (var entity : level.getAllEntities()) if (entity.getUUID().equals(uuid)) return true;
		return false;
	}

	private static FullChunkStatus requireEntityTickingChunk(ServerLevel level, BlockPos pos, String label) {
		FullChunkStatus status = level.getChunkAt(pos).getFullStatus();
		if (!status.isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
			throw new IllegalStateException("Caravan overflow: " + label + " requires an entity-ticking chunk"
				+ " (chunkStatus=" + status + ", pos=" + pos + ")");
		}
		return status;
	}

	private static void restoreWorldCells(ServerLevel level, Map<BlockPos, WorldCell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		for (BlockPos pos : before.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		for (var entry : before.entrySet()) {
			BlockPos pos = entry.getKey();
			BlockState state = entry.getValue().state();
			level.setBlock(pos, state, 3);
			if (!level.getBlockState(pos).equals(state)) {
				throw new IllegalStateException("Caravan overflow: could not restore block state at " + pos);
			}
		}
		for (var entry : before.entrySet()) {
			BlockPos pos = entry.getKey();
			WorldCell cell = entry.getValue();
			if (cell.data() == null) {
				level.removeBlockEntity(pos);
				continue;
			}
			BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
			if (restored == null) throw new IllegalStateException("Caravan overflow: could not restore block entity at " + pos);
			level.setBlockEntity(restored);
		}
		for (var entry : before.entrySet()) {
			BlockPos pos = entry.getKey();
			WorldCell cell = entry.getValue();
			if (!level.getBlockState(pos).equals(cell.state())) {
				throw new IllegalStateException("Caravan overflow: block state changed during restoration at " + pos);
			}
			BlockEntity restored = level.getBlockEntity(pos);
			if (cell.data() != null && (restored == null
				|| !cell.data().equals(restored.saveWithFullMetadata(level.registryAccess())))) {
				throw new IllegalStateException("Caravan overflow: block entity data changed during restoration at " + pos);
			}
			if (cell.data() == null && restored != null) {
				throw new IllegalStateException("Caravan overflow: unexpected block entity after restoration at " + pos);
			}
		}
	}

	private static int countDonkeyWheat(net.minecraft.world.entity.animal.equine.Donkey donkey) {
		int total = 0;
		for (int slot = 2; slot < 17; slot++) {
			var access = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + slot);
			if (access != null && access.get().is(Items.WHEAT)) total += access.get().getCount();
		}
		return total;
	}

	private static int countDonkeyItem(net.minecraft.world.entity.animal.equine.Donkey donkey, net.minecraft.world.item.Item item) {
		int total=0;
		for(int slot=2;slot<17;slot++) {
			var access=donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + slot);
			if(access!=null&&access.get().is(item)) total+=access.get().getCount();
		}
		return total;
	}

	private static void carvePath(ServerLevel level, BlockPos from, BlockPos to) {
		// Use an orthogonal corridor: diagonal-only cells do not form a reliable
		// navigable route for a villager.
		int x = from.getX(), z = from.getZ();
		while (true) {
			BlockPos floor = new BlockPos(x, from.getY() - 1, z);
			level.getChunkAt(floor);
			level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
			if (x != to.getX()) x += Integer.signum(to.getX() - x);
			else if (z != to.getZ()) z += Integer.signum(to.getZ() - z);
			else break;
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Caravan porter delivery: " + message);
		System.out.println("CARAVAN DELIVERY PASS " + message);
	}

	private record WorldCell(BlockState state, CompoundTag data) { }
}
