package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Exercises real breeding and feed consumption against live pasture edits in the remote dev fixture. */
public final class LivestockZoneChecks {
	private LivestockZoneChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		List<ZonePolicy.Zone> oldZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		Optional<BlockPos> oldPen = ref.data().extra.pen;
		long oldCare = ref.data().extra.nextCare;
		Container box = ref.chest(level);
		Container feedBox = Specials.containers(level, ref, Blueprints.GRANARY).stream().findFirst().orElse(null);
		if (box == null) throw new IllegalStateException("LIVESTOCK ZONE CHECK FAILED: registered public material reservoir is unavailable");
		if (feedBox == null) throw new IllegalStateException("LIVESTOCK ZONE CHECK FAILED: registered public granary feed reservoir is unavailable");
		List<Container> stockViews = new ArrayList<>(Livestock.feedContainers(level, ref));
		addContainerView(stockViews, feedBox);
		addContainerView(stockViews, box);
		List<ContainerSnapshot> oldStock = stockViews.stream().map(LivestockZoneChecks::snapshot).toList();
		List<Entity> spawned = new ArrayList<>();
		Map<BlockPos, BlockState> stagedBlocks = new LinkedHashMap<>();
		BlockPos pen = oldPen.orElse(ref.bell().offset(-38, 0, 38));
		if (!level.isPositionEntityTicking(pen)) {
			throw new IllegalStateException("LIVESTOCK ZONE CHECK FAILED: registered pen fixture is not entity-ticking at " + pen);
		}
		level.getChunkAt(pen);
		int checks = 0;
		try {
			// Isolate the fixture from wheat left in the herding test's granary, restoring every real store afterward.
			for (Container store : Livestock.feedContainers(level, ref)) store.clearContent();
			ref.data().extra.pen = Optional.of(pen);
			Villager worker = EntityTypes.VILLAGER.spawn(level, pen, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("livestock worker did not spawn");
			worker.setNoAi(true); worker.setInvulnerable(true); spawned.add(worker);
			Ctx ctx = new Ctx(level, worker, JobManager.state(worker), new VillagerMemory(), ref, level.getGameTime() + 1);
			Animal first = cow(level, pen, -.5, .5, spawned), second = cow(level, pen, 1.5, .5, spawned);

			ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			prepare(ref, feedBox, first, second);
			List<Container> firstFeedStoresBefore = Livestock.feedContainers(level, ref);
			int firstFeedWheatBefore = firstFeedStoresBefore.stream()
				.mapToInt(store -> JobUtil.count(store, stack -> stack.is(Items.WHEAT))).sum();
			boolean firstTick = Livestock.tick(ctx);
			List<Container> firstFeedStores = Livestock.feedContainers(level, ref);
			int firstFeedWheat = firstFeedStores.stream().mapToInt(store -> JobUtil.count(store, stack -> stack.is(Items.WHEAT))).sum();
			String feedStoreState = firstFeedStores.stream().map(store -> store.getClass().getSimpleName()
				+ "@" + Integer.toHexString(System.identityHashCode(store)) + ":wheat="
				+ JobUtil.count(store, stack -> stack.is(Items.WHEAT)) + "/sameAsFixture=" + (store == feedBox)).toList().toString();
			check(firstTick && first.isInLove() && second.isInLove() && wheat(feedBox) == 18,
				"a world without pasture zones retains real two-feed breeding at its registered pen"
					+ " [tick=" + firstTick + ", fixtureWheat=" + wheat(feedBox) + ", workerWheat="
					+ JobUtil.count(worker.getInventory(), stack -> stack.is(Items.WHEAT)) + ", productionFeedWheat="
					+ firstFeedWheatBefore + "->" + firstFeedWheat
					+ ", stores=" + feedStoreState + ", worker=" + worker.position() + ", first=" + first.position()
					+ "/age=" + first.getAge() + "/love=" + first.isInLove() + ", second=" + second.position()
					+ "/age=" + second.getAge() + "/love=" + second.isInLove() + ", nextCare=" + ref.data().extra.nextCare
					+ ", now=" + ctx.now() + ", cooldown=" + ctx.st().cooldownUntil + "]"); checks++;

			zones(ref, List.of(zone("housing", pen, 2)));
			prepare(ref, feedBox, first, second);
			check(Livestock.tick(ctx) && first.isInLove() && second.isInLove() && wheat(feedBox) == 18,
				"unrelated zone categories preserve the legacy livestock area"); checks++;

			zones(ref, List.of(new ZonePolicy.Zone("partial", "pasture", pen.getX() - 2, pen.getZ() - 2,
				pen.getX(), pen.getZ() + 2)));
			prepare(ref, feedBox, first, second);
			check(!Livestock.tick(ctx) && !first.isInLove() && !second.isInLove() && wheat(feedBox) == 20,
				"a pasture with only one adult cannot feed an unzoned second adult"); checks++;

			second.teleportTo(pen.getX() + 1.1, pen.getY(), pen.getZ() + .5);
			prepare(ref, feedBox, first, second);
			check(!Livestock.tick(ctx) && !second.isInLove() && wheat(feedBox) == 20,
				"an animal whose bounding box crosses the pasture edge remains outside by its center"); checks++;

			second.teleportTo(pen.getX() + 1.5, pen.getY(), pen.getZ() + .5);
			List<Animal> neighbors = new ArrayList<>();
			for (int i = 0; i < Livestock.PEN_CAPACITY_PER_SPECIES; i++) neighbors.add(cow(level, pen, 4.5, 4.5, spawned));
			zones(ref, List.of(zone("pasture", pen, 2)));
			prepare(ref, feedBox, first, second);
			check(Livestock.tick(ctx) && first.isInLove() && second.isInLove() && wheat(feedBox) == 18
				&& neighbors.stream().noneMatch(Animal::isInLove),
				"neighboring unzoned cattle are neither fed nor counted toward this pasture's breeding capacity"); checks++;
			neighbors.forEach(Entity::discard);

			zones(ref, List.of(zone("pasture", ref.bell().offset(50, 0, 50), 2)));
			prepare(ref, feedBox, first, second);
			check(!Livestock.tick(ctx) && wheat(feedBox) == 20 && !first.isInLove() && !second.isInLove(),
				"moving the pasture away stops a previously eligible pen without consuming food"); checks++;

			zones(ref, List.of(zone("pasture", ref.bell().east(80), 2)));
			prepare(ref, feedBox, first, second);
			check(Livestock.tick(ctx) && first.isInLove() && second.isInLove() && wheat(feedBox) == 18,
				"malformed persisted pasture bounds are ignored consistently with other zone planners"); checks++;

			zones(ref, List.of(zone("pasture", pen, 32)));
			// Keep the second adult beyond the pen plus the normal 24-block herd intake radius.
			second.teleportTo(pen.getX() + 28.5, pen.getY(), pen.getZ() + .5);
			prepare(ref, feedBox, first, second);
			boolean distantHerdTick = Livestock.tick(ctx);
			check(!distantHerdTick && wheat(feedBox) == 20 && !first.isInLove() && !second.isInLove(),
				"a large pasture cannot expand the registered pen's care radius to a distant herd"
					+ " [tick=" + distantHerdTick + ", feed=" + wheat(feedBox) + ", second=" + second.position()
					+ ", activity=" + JobManager.state(worker).activity + "]"); checks++;

			// The registered 9×9 pen reaches its base cap before breeding deadlocks.
			List<Animal> fullHerd = new ArrayList<>(List.of(first, second));
			double[][] herdSpots = {{-1.5, -1.5}, {-.5, -1.5}, {.5, -1.5}, {1.5, -1.5}, {-.5, 1.5}, {.5, 1.5}};
			for (double[] spot : herdSpots) fullHerd.add(cow(level, pen, spot[0], spot[1], spawned));
			second.teleportTo(pen.getX() + 1.5, pen.getY(), pen.getZ() + .5);
			zones(ref, List.of(zone("pasture", pen, 9)));
			prepare(ref, feedBox, fullHerd.toArray(Animal[]::new));
			List<ZonePolicy.Zone> activePasture = List.of(zone("pasture", pen, 9));

			stageExpansion(level, pen, stagedBlocks);
			check(PenExpansion.capacity(level, ref) == Livestock.PEN_CAPACITY_PER_SPECIES
				&& PenExpansion.dueAtCapacity(level, ref) && !PenExpansion.overCapacity(level, ref)
				&& PenExpansion.plan(level, pen).size() == 12,
				"a full genuine herd remains at base capacity until a supported, pasture-covered 12-fence annex is closed"); checks++;
			box.setItem(1, new ItemStack(Items.OAK_FENCE, 11));
			box.setItem(2, new ItemStack(Items.OAK_FENCE_GATE, 1));
			BlockPos workStand = PenExpansion.workStand(level, pen, worker);
			check(workStand != null, "the annex has a safe reachable construction stand"); checks++;
			worker.setPos(workStand.getX() + .5, workStand.getY(), workStand.getZ() + .5);
			Ctx expansionCtx = new Ctx(level, worker, JobManager.state(worker), new VillagerMemory(), ref, ctx.now() + 1);
			check(!PenExpansion.work(expansionCtx) && PenExpansion.capacity(level, ref) == Livestock.PEN_CAPACITY_PER_SPECIES
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE)) == 11
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE_GATE)) == 1
				&& PenExpansion.plan(level, pen).size() == 12,
				"short public stock pauses before placing any annex block or losing material"); checks++;

			box.setItem(1, new ItemStack(Items.OAK_FENCE, 12));
			worker.setPos(workStand.getX() + .5, workStand.getY(), workStand.getZ() + .5);
			boolean expanded = PenExpansion.work(expansionCtx);
			check(expanded && PenExpansion.capacity(level, ref) == Livestock.PEN_CAPACITY_PER_SPECIES + 4
				&& PenExpansion.plan(level, pen).isEmpty()
				&& level.getBlockState(pen.south(9)).getBlock() == Blocks.OAK_FENCE_GATE
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE_GATE)) == 0,
				"the butcher pays twelve public fences and one gate before the completed 3×4 annex raises herd capacity"); checks++;
			int completedCapacity = PenExpansion.capacity(level, ref);
			boolean repeatedExpansion = PenExpansion.work(expansionCtx);
			check(!repeatedExpansion && PenExpansion.capacity(level, ref) == completedCapacity
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE)) == 0
				&& level.getBlockState(pen.south(9)).getBlock() == Blocks.OAK_FENCE_GATE,
				"a reload-style recomputation and repeated retry preserve the physical annex without duplicate payment"); checks++;

			BlockPos missingFence = PenExpansion.plan(level, pen).isEmpty()
				? pen.south(5).east(2) : PenExpansion.plan(level, pen).getFirst();
			stage(level, stagedBlocks, missingFence, Blocks.AIR.defaultBlockState());
			check(PenExpansion.capacity(level, ref) == Livestock.PEN_CAPACITY_PER_SPECIES
				&& PenExpansion.plan(level, pen).size() == 1,
				"an interrupted or damaged closure immediately returns to base capacity and exposes one retry cell"); checks++;
			box.setItem(1, new ItemStack(Items.OAK_FENCE, 1));
			worker.setPos(workStand.getX() + .5, workStand.getY(), workStand.getZ() + .5);
			check(PenExpansion.work(expansionCtx) && PenExpansion.capacity(level, ref) == Livestock.PEN_CAPACITY_PER_SPECIES + 4
				&& PenExpansion.plan(level, pen).isEmpty()
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE)) == 0,
				"a restart-style retry pays only the missing fence and restores the effective cap after full closure"); checks++;

			Animal annexFirst = cow(level, pen, -0.5, 8.5, spawned);
			Animal annexSecond = cow(level, pen, 0.5, 8.5, spawned);
			List<Animal> expandedHerd = new ArrayList<>(fullHerd);
			expandedHerd.add(annexFirst);
			expandedHerd.add(annexSecond);
			prepare(ref, feedBox, expandedHerd.toArray(Animal[]::new));
			check(Livestock.tick(expansionCtx) && annexFirst.isInLove() && annexSecond.isInLove()
				&& !first.isInLove() && !second.isInLove() && wheat(feedBox) == 18
				&& PenExpansion.capacity(level, ref) == Livestock.PEN_CAPACITY_PER_SPECIES + 4,
				"breeding uses a real adult pair inside the completed annex and the same effective herd cap"); checks++;
			return checks;
		} finally {
			spawned.forEach(Entity::discard);
			oldStock.forEach(LivestockZoneChecks::restore);
			stagedBlocks.forEach((pos, state) -> {
				if (level.hasChunkAt(pos)) level.setBlock(pos, state, 3);
			});
			ref.data().extra.pen = oldPen;
			ref.data().extra.nextCare = oldCare;
			if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
			ref.dirty();
		}
	}

	private static void stageExpansion(ServerLevel level, BlockPos pen, Map<BlockPos, BlockState> before) {
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			if (Math.abs(dx) != 4 && Math.abs(dz) != 4) continue;
			BlockPos wall = pen.offset(dx, 0, dz);
			stage(level, before, wall.below(), Blocks.STONE.defaultBlockState());
			stage(level, before, wall, Blocks.OAK_FENCE.defaultBlockState());
		}
		BlockPos baseGate = pen.north(4);
		stage(level, before, baseGate.below(), Blocks.STONE.defaultBlockState());
		stage(level, before, baseGate, Blocks.OAK_FENCE_GATE.defaultBlockState()
			.setValue(FenceGateBlock.FACING, Direction.NORTH).setValue(FenceGateBlock.OPEN, false));
		for (int dx = -3; dx <= 3; dx++) for (int dz = 5; dz <= 9; dz++) {
			BlockPos floor = pen.offset(dx, -1, dz);
			stage(level, before, floor, Blocks.STONE.defaultBlockState());
			BlockPos cell = floor.above();
			stage(level, before, cell, Blocks.AIR.defaultBlockState());
			stage(level, before, cell.above(), Blocks.AIR.defaultBlockState());
		}
	}

	private static void stage(ServerLevel level, Map<BlockPos, BlockState> before, BlockPos pos, BlockState state) {
		level.getChunkAt(pos);
		if (level.getBlockEntity(pos) != null) {
			throw new IllegalStateException("LIVESTOCK ZONE CHECK FAILED: stall fixture overlaps a block entity at " + pos);
		}
		before.putIfAbsent(pos.immutable(), level.getBlockState(pos));
		if (!level.getBlockState(pos).equals(state) && !level.setBlock(pos, state, 3)) {
			throw new IllegalStateException("LIVESTOCK ZONE CHECK FAILED: could not stage stall work at " + pos);
		}
	}

	private static Animal cow(ServerLevel level, BlockPos pen, double x, double z, List<Entity> spawned) {
		Animal animal = EntityTypes.COW.spawn(level, pen, EntitySpawnReason.COMMAND);
		if (animal == null) throw new IllegalStateException("livestock cow did not spawn");
		animal.setNoAi(true); animal.setInvulnerable(true); animal.setAge(0);
		animal.teleportTo(pen.getX() + x, pen.getY(), pen.getZ() + z);
		spawned.add(animal); return animal;
	}

	private static void prepare(VillageRef ref, Container box, Animal... animals) {
		ref.data().extra.nextCare = 0;
		for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
		box.setItem(0, new ItemStack(Items.WHEAT, 20));
		for (Animal animal : animals) animal.resetLove();
	}

	private record ContainerSnapshot(Container container, List<ItemStack> contents) { }

	private static ContainerSnapshot snapshot(Container container) {
		List<ItemStack> contents = new ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) contents.add(container.getItem(slot).copy());
		return new ContainerSnapshot(container, List.copyOf(contents));
	}

	private static void restore(ContainerSnapshot snapshot) {
		for (int slot = 0; slot < Math.min(snapshot.container().getContainerSize(), snapshot.contents().size()); slot++) {
			snapshot.container().setItem(slot, snapshot.contents().get(slot).copy());
		}
		snapshot.container().setChanged();
	}

	private static void addContainerView(List<Container> containers, Container candidate) {
		if (candidate != null && containers.stream().noneMatch(existing -> existing == candidate)) containers.add(candidate);
	}
	private static int wheat(Container box) { return JobUtil.count(box, s -> s.is(Items.WHEAT)); }
	private static void zones(VillageRef ref, List<ZonePolicy.Zone> zones) { ref.bellEntity().setAttached(VillagerPlus.ZONES, zones); }
	private static ZonePolicy.Zone zone(String kind, BlockPos center, int radius) {
		return new ZonePolicy.Zone("livestock-zone-check", kind, center.getX() - radius, center.getZ() - radius,
			center.getX() + radius, center.getZ() + radius);
	}
	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("LIVESTOCK ZONE CHECK FAILED: " + message);
	}
}
