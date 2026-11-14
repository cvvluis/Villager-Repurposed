package de.villagerplus.village;

import de.villagerplus.entity.ModEntities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Focused world fixture for the cargo-bearing pack-animal departure lifecycle. */
public final class CaravanDepartureChecks {
	private CaravanDepartureChecks() { }

	private record Cell(BlockState state, CompoundTag blockEntity) { }

	public static int run(ServerLevel level, BlockPos at) {
		BlockPos origin = at.immutable();
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		List<Entity> spawned = new ArrayList<>();
		Caravan.State state = null;
		try {
			stage(level, before, origin);
			var trader = ModEntities.CARAVAN_TRADER.spawn(level, origin, EntitySpawnReason.COMMAND);
			// Stay inside vanilla lead slack; the fixture ticks pack animals directly, not the stationary trader.
			Donkey first = EntityTypes.DONKEY.spawn(level, origin.east(4).north(4), EntitySpawnReason.COMMAND);
			Donkey second = EntityTypes.DONKEY.spawn(level, origin.east(4).south(4), EntitySpawnReason.COMMAND);
			if (trader == null || first == null || second == null) {
				if (trader != null) spawned.add(trader);
				if (first != null) spawned.add(first);
				if (second != null) spawned.add(second);
				throw new IllegalStateException("CARAVAN DEPARTURE CHECK FAILED: could not spawn porter and two pack donkeys");
			}
			spawned.add(trader);
			spawned.add(first);
			spawned.add(second);
			trader.setOnGround(true);
			first.setOnGround(true);
			second.setOnGround(true);
			// The real departure can outlive the chunk containing its village bell.
			// Keep the saved origin loaded while placing the bell far enough away to stay unloaded.
			BlockPos unloadedVillageBell = origin.offset(1024, 0, 0).immutable();
			state = new Caravan.State(trader.getUUID(), level.dimension(), unloadedVillageBell, origin, -1, level.getGameTime());
			state.traded = true;
			state.leaveAt = Long.MAX_VALUE;
			state.llamas.add(first.getUUID());
			state.llamas.add(second.getUUID());
			Caravan.registerActiveStateForChecks(state);
			preparePackDonkey(first, trader, unloadedVillageBell, Items.OAK_PLANKS);
			preparePackDonkey(second, trader, unloadedVillageBell, Items.BRICKS);

			long scheduledAt = level.getGameTime();
			boolean departureScheduled = Caravan.scheduleDepartureAfterTrade(state, scheduledAt);
			int checks = 1;
			require(departureScheduled && state.leaveAt == scheduledAt + 1200L
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7,
				"post-trade departure is scheduled with freight still on both donkeys [leaveAt="
					+ state.leaveAt + ", firstCargo=" + count(first, Items.OAK_PLANKS)
					+ ", secondCargo=" + count(second, Items.BRICKS) + "]");
			checks++;
			boolean scheduleStable = !Caravan.scheduleDepartureAfterTrade(state, scheduledAt + 40L)
				&& state.leaveAt == scheduledAt + 1200L;
			require(scheduleStable,
				"follow refreshes do not keep postponing the saved departure deadline [leaveAt=" + state.leaveAt + "]");
			checks++;
			var completedPath = first.getNavigation().createPath(first.blockPosition(), 0);
			boolean finishedPathAccepted = completedPath != null && completedPath.canReach()
				&& completedPath.getNodeCount() == 1
				&& Caravan.donkeyPathFailureReason(level, first, completedPath, first.blockPosition()) == null;
			require(finishedPathAccepted,
				"a safe one-node path is accepted when the donkey already occupies its exact target [path="
					+ (completedPath == null ? null : completedPath.getNodeCount()) + ", position="
					+ first.blockPosition() + "]");
			checks++;
			boolean missingVillageStartsReturn = Caravan.beginDepartureNow(state, scheduledAt)
				&& state.leaveAt == scheduledAt && count(first, Items.OAK_PLANKS) == 7
				&& count(second, Items.BRICKS) == 7;
			require(missingVillageStartsReturn,
				"a traded caravan with an unavailable village switches from its delay to an immediate physical return [leaveAt="
					+ state.leaveAt + ", cargo=" + count(first, Items.OAK_PLANKS) + "/"
					+ count(second, Items.BRICKS) + "]");
			checks++;
			var campLayout = Caravan.campLayout(origin);
			for (var entry : campLayout.entrySet()) {
				level.setBlock(entry.getKey(), entry.getValue(), 3);
				state.camp.add(entry.getKey().immutable());
				state.campBlocks.add(entry.getValue());
			}
			state.campBuilt = true;
			boolean campClearedBeforeFollowing = Caravan.clearCampForDepartureForChecks(level, state)
				&& !state.campBuilt && state.camp.isEmpty() && state.campBlocks.isEmpty()
				&& campLayout.keySet().stream().allMatch(pos -> level.getBlockState(pos).isAir())
				&& state.llamas.contains(first.getUUID()) && state.llamas.contains(second.getUUID())
				&& first.getLeashHolder() == trader && second.getLeashHolder() == trader
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
			require(campClearedBeforeFollowing,
				"departure dismantles caravan camp blocks before donkey routing while preserving registered animals, leads, and freight");
			checks++;
			boolean prematureCleanupBlocked = !Caravan.cleanupForChecks(level, state)
				&& first.isAlive() && second.isAlive() && state.llamas.size() == 2
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
			require(prematureCleanupBlocked,
				"cleanup retains the live visit and real cargo until both pack animals physically return [members="
					+ state.llamas + ", first=" + first.blockPosition() + ", second=" + second.blockPosition() + "]");
			checks++;
			Caravan.persistVisitState(level, state);
			var savedVisit = trader.visitData();
			require(savedVisit != null, "active departure has a saved merchant visit snapshot");
			var encodedVisit = de.villagerplus.entity.CaravanTraderEntity.VISIT_DATA_CODEC
				.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, savedVisit).getOrThrow();
			var decodedVisit = de.villagerplus.entity.CaravanTraderEntity.VISIT_DATA_CODEC
				.parse(com.mojang.serialization.JsonOps.INSTANCE, encodedVisit).getOrThrow();
			Caravan.removeActiveStateForChecks(state.trader);
			Caravan.restoreVisitState(level, trader, decodedVisit);
			// A repeated restore represents another save/load boundary in the same fixture.
			Caravan.restoreVisitState(level, trader, decodedVisit);
			state = Caravan.activeStateForChecks(trader.getUUID());
			boolean restoredDeparturePoint = savedVisit.equals(decodedVisit)
				&& decodedVisit.travel().spawn().equals(origin) && state != null && state.traded
				&& state.leaveAt == scheduledAt && state.spawn.equals(origin)
				&& state.llamas.size() == 2
				&& state.llamas.contains(first.getUUID()) && state.llamas.contains(second.getUUID())
				&& first.getLeashHolder() == trader && second.getLeashHolder() == trader
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
			require(restoredDeparturePoint,
				"a serialized due visit restores its saved return point, both real cargo animals, and their leads [restored="
					+ (state != null) + ", spawn=" + (state == null ? null : state.spawn)
					+ ", members=" + (state == null ? List.of() : state.llamas) + "]");
			checks++;
			boolean unloadedBellCanBeSkipped = !level.hasChunkAt(state.bell)
				&& Caravan.departureCanAdvanceWithoutVillage(level, state);
			require(unloadedBellCanBeSkipped,
				"a due departure remains eligible for updates while its saved village bell chunk is unloaded [bell="
					+ state.bell + ", loaded=" + level.hasChunkAt(state.bell) + ", now=" + level.getGameTime()
					+ ", leaveAt=" + state.leaveAt + "]");
			checks++;
			Caravan.pausePackDonkeyBeforeUnload(level, first);
			boolean unloadRetainsMembershipAndFreight = !state.departurePathReady && state.waitingForDonkeys
				&& state.llamas.contains(first.getUUID()) && state.llamas.contains(second.getUUID())
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
			require(unloadRetainsMembershipAndFreight,
				"a pack-donkey chunk unload pauses the trip without dropping membership or cargo [members="
					+ state.llamas + ", cargo=" + count(first, Items.OAK_PLANKS) + "/"
					+ count(second, Items.BRICKS) + "]");
			checks++;
			Caravan.resumePackDonkeyAfterLoad(level, first);
			boolean reloadRearmsFollowing = state.nextDonkeyFollowAt == 0L && state.llamas.size() == 2
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
			require(reloadRearmsFollowing,
				"loading a registered pack donkey schedules an immediate route retry and preserves both cargo inventories [next="
					+ state.nextDonkeyFollowAt + ", members=" + state.llamas + "]");
			checks++;
			boolean fallbackFinished = Caravan.continueDepartureWithoutVillage(level, state, trader);
			var fallbackVisit = trader.visitData();
			boolean unloadedVillageContinuesWithSavedAssets = !fallbackFinished && state.departurePathReady
				&& state.waitingForDonkeys && !Caravan.packAnimalsCanKeepUp(level, state, trader) && fallbackVisit != null
				&& fallbackVisit.travel().bell().equals(unloadedVillageBell)
				&& fallbackVisit.travel().spawn().equals(origin)
				&& fallbackVisit.travel().members().contains(first.getUUID().toString())
				&& fallbackVisit.travel().members().contains(second.getUUID().toString())
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
			require(unloadedVillageContinuesWithSavedAssets,
				"a due caravan holds at its saved origin while either pack animal is outside the short lead range, keeping both saved members and real cargo [ready="
					+ state.departurePathReady + ", waiting=" + state.waitingForDonkeys + ", visit=" + fallbackVisit
					+ ", members=" + state.llamas + ", cargo=" + count(first, Items.OAK_PLANKS) + "/"
					+ count(second, Items.BRICKS) + "]");
			checks++;

			long guidanceAt = Math.max(1L, level.getGameTime() + 1L);
			Caravan.guideDepartingDonkeys(level, state, trader, guidanceAt);
			boolean firstHasRoute = validActiveRoute(level, first);
			boolean secondHasRoute = validActiveRoute(level, second);
			BlockPos firstApproach = first.getNavigation().getTargetPos();
			BlockPos secondApproach = second.getNavigation().getTargetPos();
			boolean approachesStayOnTheirStartSides = firstApproach != null && secondApproach != null
				&& firstApproach.getX() >= origin.getX() && firstApproach.getZ() <= origin.getZ()
				&& secondApproach.getX() >= origin.getX() && secondApproach.getZ() >= origin.getZ()
				&& horizontalDistanceSqr(firstApproach, secondApproach) >= 4L
				&& safeHomeApproach(first, firstApproach, origin, trader)
				&& safeHomeApproach(second, secondApproach, origin, trader);
			require(approachesStayOnTheirStartSides,
				"home routes avoid crossing and reserve separate two-block arrival lanes beside the trader [first="
					+ firstApproach + ", second=" + secondApproach + "]");
			checks++;
			require(firstHasRoute && secondHasRoute && state.departurePathReady
				&& first.getLeashHolder() == trader && second.getLeashHolder() == trader,
				"both registered cargo donkeys receive validated normal routes under the caravan lead [first="
					+ routeSummary(level, first) + ", second=" + routeSummary(level, second) + "]");
			checks++;
			BlockPos secondStart = second.blockPosition().immutable();
			second.getNavigation().stop();
			second.setPos(origin.getX() + 1.5, origin.getY(), origin.getZ() + 1.5);
			boolean unsafeCellCannotFinishDeparture = !Caravan.safeHomeArrivalCell(second, second.blockPosition(), origin, trader)
				&& !Caravan.packAnimalsAtSpawn(level, state) && !Caravan.cleanupForChecks(level, state)
				&& state.llamas.contains(second.getUUID()) && second.getLeashHolder() == trader
				&& count(second, Items.BRICKS) == 7;
			require(unsafeCellCannotFinishDeparture,
				"a donkey one diagonal cell from the trader cannot finish cleanup or lose registration, leash, or cargo");
			checks++;
			Caravan.guideDepartingDonkeys(level, state, trader, guidanceAt + 20L);
			BlockPos recoveredTarget = second.getNavigation().getTargetPos();
			boolean unsafeCellGetsSafeRecoveryGoal = second.getNavigation().isInProgress() && recoveredTarget != null
				&& safeHomeApproach(second, recoveredTarget, origin, trader);
			require(unsafeCellGetsSafeRecoveryGoal,
				"guidance retries from the false-arrival cell and requests a goal beyond donkey waypoint tolerance [target="
					+ recoveredTarget + "]");
			checks++;
			second.getNavigation().stop();
			second.setPos(secondStart.getX() + 0.5, secondStart.getY(), secondStart.getZ() + 0.5);

			BlockPos cageCenter = second.blockPosition().immutable();
			for (int y = 0; y <= 3; y++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				if (y == 3 || Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
					level.setBlock(cageCenter.offset(dx, y, dz), Blocks.OBSIDIAN.defaultBlockState(), 3);
				}
			}
			var firstBeforeBlock = first.blockPosition();
			var secondBeforeBlock = second.blockPosition();
			Caravan.guideDepartingDonkeys(level, state, trader, guidanceAt + 40L);
			boolean blockedDonkeyWaits = !state.departurePathReady
				&& !Caravan.packAnimalsCanKeepUp(level, state, trader)
				&& state.llamas.contains(first.getUUID()) && state.llamas.contains(second.getUUID())
				&& first.getNavigation().isInProgress() && !second.getNavigation().isInProgress()
				&& first.blockPosition().equals(firstBeforeBlock) && second.blockPosition().equals(secondBeforeBlock)
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
			require(blockedDonkeyWaits,
				"one blocked pack route pauses departure without moving either animal or losing registration/cargo [ready="
					+ state.departurePathReady + ", first=" + routeSummary(level, first)
					+ ", second=" + routeSummary(level, second) + "]");
			checks++;
			boolean missingVillageKeepsBlockedPack = !Caravan.continueDepartureWithoutVillage(level, state, trader)
				&& first.isAlive() && second.isAlive() && state.llamas.contains(first.getUUID())
				&& state.llamas.contains(second.getUUID()) && count(first, Items.OAK_PLANKS) == 7
				&& count(second, Items.BRICKS) == 7;
			require(missingVillageKeepsBlockedPack,
				"a missing village cannot release or discard a blocked donkey before recovery [waiting="
					+ state.waitingForDonkeys + ", members=" + state.llamas + ", cargo="
					+ count(first, Items.OAK_PLANKS) + "/" + count(second, Items.BRICKS) + "]");
			checks++;

			for (int y = 0; y <= 3; y++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				if (y == 3 || Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
					level.setBlock(cageCenter.offset(dx, y, dz), Blocks.AIR.defaultBlockState(), 3);
				}
			}
			Caravan.guideDepartingDonkeys(level, state, trader, guidanceAt + 60L);
			boolean recoveredBothRoutes = state.departurePathReady && validActiveRoute(level, first)
				&& validActiveRoute(level, second) && state.llamas.contains(first.getUUID())
				&& state.llamas.contains(second.getUUID()) && count(first, Items.OAK_PLANKS) == 7
				&& count(second, Items.BRICKS) == 7;
			require(recoveredBothRoutes,
				"departure retries and resumes both real routes after the obstruction clears [first="
					+ routeSummary(level, first) + ", second=" + routeSummary(level, second) + "]");
			checks++;

			double firstStartDistance = first.distanceToSqr(trader);
			double secondStartDistance = second.distanceToSqr(trader);
			double largestStepBlocks = 0.0;
			String largestStepDetail = "none";
			var previousFirst = first.position();
			var previousSecond = second.position();
			int followTicks = 0;
			while (followTicks < 240 && !Caravan.packAnimalsAtSpawn(level, state)) {
				var beforeFirst = first.position();
				String firstBefore = motionSummary(first, trader);
				first.tick();
				double firstStepBlocks = Math.sqrt(first.position().distanceToSqr(previousFirst));
				if (firstStepBlocks > largestStepBlocks) {
					largestStepBlocks = firstStepBlocks;
					largestStepDetail = "first tick " + (followTicks + 1) + " from " + beforeFirst + " to "
						+ first.position() + " before={" + firstBefore + "} after={" + motionSummary(first, trader) + "}";
				}
				var beforeSecond = second.position();
				String secondBefore = motionSummary(second, trader);
				second.tick();
				double secondStepBlocks = Math.sqrt(second.position().distanceToSqr(previousSecond));
				if (secondStepBlocks > largestStepBlocks) {
					largestStepBlocks = secondStepBlocks;
					largestStepDetail = "second tick " + (followTicks + 1) + " from " + beforeSecond + " to "
						+ second.position() + " before={" + secondBefore + "} after={" + motionSummary(second, trader) + "}";
				}
				previousFirst = first.position();
				previousSecond = second.position();
				followTicks++;
				if (followTicks % 20 == 0) Caravan.guideDepartingDonkeys(level, state, trader, guidanceAt + 80L + followTicks);
			}
			boolean reachedOrigin = Caravan.packAnimalsAtSpawn(level, state);
			boolean bothMovedCloser = first.distanceToSqr(trader) < firstStartDistance
				&& second.distanceToSqr(trader) < secondStartDistance;
			boolean naturalStepBounded = largestStepBlocks < 1.5;
			boolean bothRoutesReady = state.departurePathReady;
			boolean bothLeadsRetained = first.getLeashHolder() == trader && second.getLeashHolder() == trader;
			boolean bothInventoriesRetained = count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
			require(reachedOrigin && bothMovedCloser && naturalStepBounded && bothRoutesReady
				&& bothLeadsRetained && bothInventoriesRetained,
				"both cargo donkeys physically reach the saved departure point on validated normal routes without teleporting or changing freight [ticks="
					+ followTicks + ", originGap=" + first.blockPosition().distSqr(origin) + "/"
					+ second.blockPosition().distSqr(origin) + " blocks squared, maxStep=" + largestStepBlocks
					+ " blocks, step=" + largestStepDetail + ", predicates={arrived=" + reachedOrigin
					+ ", closer=" + bothMovedCloser + ", bounded=" + naturalStepBounded + ", routesReady=" + bothRoutesReady
					+ ", leads=" + bothLeadsRetained + ", freight=" + bothInventoriesRetained + "}, routes="
					+ routeSummary(level, first) + "/" + routeSummary(level, second) + "]");
			checks++;
			UUID unavailableDonkey = UUID.randomUUID();
			state.llamas.add(unavailableDonkey);
			Caravan.guideDepartingDonkeys(level, state, trader, state.nextDonkeyFollowAt);
			boolean missingMemberWaits = !state.departurePathReady && !Caravan.packAnimalsCanKeepUp(level, state, trader)
				&& first.isAlive() && second.isAlive() && state.llamas.contains(first.getUUID())
				&& state.llamas.contains(second.getUUID()) && state.llamas.contains(unavailableDonkey)
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 7;
			require(missingMemberWaits,
				"an unavailable registered member pauses departure without loading, dropping registrations, or losing cargo [ready="
					+ state.departurePathReady + ", members=" + state.llamas + ", cargo="
					+ count(first, Items.OAK_PLANKS) + "/" + count(second, Items.BRICKS) + "]");
			checks++;
			state.llamas.remove(unavailableDonkey);
			Caravan.guideDepartingDonkeys(level, state, trader, state.nextDonkeyFollowAt);
			require(state.departurePathReady && Caravan.packAnimalsCanKeepUp(level, state, trader)
				&& state.llamas.size() == 2 && count(first, Items.OAK_PLANKS) == 7
				&& count(second, Items.BRICKS) == 7,
				"departure resumes after the unavailable member returns to the active record [ready="
					+ state.departurePathReady + ", members=" + state.llamas + "]");
			checks++;
			var secondCargo = second.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + 2);
			boolean secondEmptied = secondCargo != null && secondCargo.set(ItemStack.EMPTY);
			Caravan.syncCargoVisual(level, second);
			require(secondEmptied, "an arrived pack animal can finish with an empty real inventory");
			checks++;
			boolean safelyCleaned = Caravan.cleanupForChecks(level, state) && first.isAlive() && second.isAlive()
				&& count(first, Items.OAK_PLANKS) == 7 && count(second, Items.BRICKS) == 0
				&& first.getLeashHolder() == null && second.getLeashHolder() == null
				&& state.llamas.isEmpty()
				&& trader.visitData() != null && trader.visitData().travel().members().isEmpty()
				&& !first.entityTags().contains("vp_caravan") && !second.entityTags().contains("vp_caravan")
				&& !first.entityTags().contains("vp_caravan_bell:" + unloadedVillageBell.asLong())
				&& !second.entityTags().contains("vp_caravan_bell:" + unloadedVillageBell.asLong());
			require(safelyCleaned,
				"the arrived caravan releases cargo and empty donkeys, removes their membership tags, and keeps freight intact [alive="
					+ first.isAlive() + "/" + second.isAlive() + ", cargo=" + count(first, Items.OAK_PLANKS)
					+ "/" + count(second, Items.BRICKS) + ", leashed=" + (first.isLeashed() || second.isLeashed())
					+ ", remaining=" + state.llamas + "]");
			checks++;
			return checks;
		} finally {
			if (state != null) Caravan.removeActiveStateForChecks(state.trader);
			for (Entity entity : spawned) if (entity.isAlive()) entity.discard();
			restore(level, before);
		}
	}

	private static boolean validActiveRoute(ServerLevel level, Donkey donkey) {
		var navigation = donkey.getNavigation();
		return navigation.isInProgress() && Caravan.donkeyPathFailureReason(level, donkey,
			navigation.getPath(), navigation.getTargetPos()) == null;
	}

	private static boolean safeHomeApproach(Donkey donkey, BlockPos approach, BlockPos origin, Entity trader) {
		return horizontalDistanceSqr(approach, origin) >= 9L
			&& approach.distSqr(origin) <= Caravan.PACK_HOME_APPROACH_DISTANCE_SQR
			&& !CaravanSites.bounds(donkey, approach).intersects(trader.getBoundingBox().inflate(0.25));
	}

	private static long horizontalDistanceSqr(BlockPos first, BlockPos second) {
		long dx = first.getX() - second.getX();
		long dz = first.getZ() - second.getZ();
		return dx * dx + dz * dz;
	}

	private static String routeSummary(ServerLevel level, Donkey donkey) {
		var navigation = donkey.getNavigation();
		return "target=" + navigation.getTargetPos() + ",progress=" + navigation.isInProgress()
			+ ",failure=" + Caravan.donkeyPathFailureReason(level, donkey,
				navigation.getPath(), navigation.getTargetPos());
	}

	private static String motionSummary(Donkey donkey, Entity trader) {
		var navigation = donkey.getNavigation();
		Entity leashHolder = donkey.getLeashHolder();
		return "velocity=" + donkey.getDeltaMovement() + ",target=" + navigation.getTargetPos()
			+ ",progress=" + navigation.isInProgress() + ",leash="
			+ (leashHolder == null ? "none" : leashHolder.getUUID()) + ",leashDistance="
			+ Math.sqrt(donkey.distanceToSqr(trader));
	}

	private static void preparePackDonkey(Donkey donkey, Entity trader, BlockPos bell, net.minecraft.world.item.Item freight) {
		var chest = donkey.getSlot(AbstractHorse.CHEST_SLOT_OFFSET);
		if (chest == null || !chest.set(new ItemStack(Items.CHEST)) || !donkey.hasChest()) {
			throw new IllegalStateException("CARAVAN DEPARTURE CHECK FAILED: cannot equip a fixture donkey chest");
		}
		// Equipping a chest rebuilds the horse inventory in 26.2. Resolve cargo only afterward.
		var cargo = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + 2);
		if (cargo == null || !cargo.set(new ItemStack(freight, 7))) {
			throw new IllegalStateException("CARAVAN DEPARTURE CHECK FAILED: cannot load a fixture donkey cargo slot");
		}
		donkey.addTag("vp_caravan");
		donkey.addTag("vp_caravan_bell:" + bell.asLong());
		donkey.setPersistenceRequired();
		donkey.setLeashedTo(trader, true);
	}

	private static int count(Donkey donkey, net.minecraft.world.item.Item item) {
		var cargo = donkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + 2);
		return cargo == null || !cargo.get().is(item) ? 0 : cargo.get().getCount();
	}

	private static void stage(ServerLevel level, Map<BlockPos, Cell> before, BlockPos origin) {
		var box = new net.minecraft.world.phys.AABB(origin.getX() - 16, origin.getY() - 1, origin.getZ() - 12,
			origin.getX() + 17, origin.getY() + 4, origin.getZ() + 13);
		if (!level.getEntitiesOfClass(Entity.class, box, Entity::isAlive).isEmpty()) {
			throw new IllegalStateException("CARAVAN DEPARTURE CHECK FAILED: fixture area is occupied at " + origin);
		}
		for (int x = -16; x <= 16; x++) for (int z = -12; z <= 12; z++) for (int y = -1; y <= 4; y++) {
			BlockPos pos = origin.offset(x, y, z).immutable();
			snapshot(level, before, pos);
			level.setBlock(pos, y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static void snapshot(ServerLevel level, Map<BlockPos, Cell> before, BlockPos position) {
		BlockPos pos = position.immutable();
		level.getChunkAt(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		before.putIfAbsent(pos, new Cell(level.getBlockState(pos),
			blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.blockEntity() == null) return;
			BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
			if (restored != null) level.setBlockEntity(restored);
		});
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("CARAVAN DEPARTURE CHECK FAILED: " + message);
	}
}
