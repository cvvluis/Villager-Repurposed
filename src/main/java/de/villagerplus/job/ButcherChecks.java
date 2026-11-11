package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Anchor;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Focused surplus and ownership checks for butcher culling in the registered village pen. */
public final class ButcherChecks {
	private static final int FIXTURE_TICKET_RADIUS = 2;
	private static final Map<ServerLevel, Set<ChunkPos>> ENTITY_TICKING_FIXTURES = new WeakHashMap<>();

	private ButcherChecks() { }

	/** Keeps a remote test chunk ticketed until its entity sections are ready; callers retry while false. */
	public static boolean prepareEntityTickingFixture(ServerLevel level, BlockPos center) {
		if (level.isPositionEntityTicking(center)) return true;
		ChunkPos chunk = new ChunkPos(center.getX() >> 4, center.getZ() >> 4);
		Set<ChunkPos> tickets = ENTITY_TICKING_FIXTURES.computeIfAbsent(level, ignored -> new LinkedHashSet<>());
		if (tickets.add(chunk)) level.getChunkSource().addTicketWithRadius(Anchor.WALL_WORK, chunk, FIXTURE_TICKET_RADIUS);
		level.getChunkAt(center);
		var loaded = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
		return loaded != null && loaded.getFullStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)
			&& level.isPositionEntityTicking(center);
	}

	public static void releaseEntityTickingFixture(ServerLevel level, BlockPos center) {
		Set<ChunkPos> tickets = ENTITY_TICKING_FIXTURES.get(level);
		ChunkPos chunk = new ChunkPos(center.getX() >> 4, center.getZ() >> 4);
		if (tickets == null || !tickets.remove(chunk)) return;
		level.getChunkSource().removeTicketWithRadius(Anchor.WALL_WORK, chunk, FIXTURE_TICKET_RADIUS);
		if (tickets.isEmpty()) ENTITY_TICKING_FIXTURES.remove(level);
	}

	public static int run(ServerLevel level, VillageRef ref) {
		List<ZonePolicy.Zone> oldZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		Optional<BlockPos> oldPen = ref.data().extra.pen;
		List<Animal> spawned = new ArrayList<>();
		Map<BlockPos, BlockState> stagedBlocks = new LinkedHashMap<>();
		// Keep all animals, including the annex herd, inside the same entity-ticking chunk.
		BlockPos pen = ref.bell().offset(-42, 0, 30);
		if (!prepareEntityTickingFixture(level, pen)) {
			System.out.println("BUTCHER PREPARING entity-ticking fixture at " + pen.toShortString() + "; retry after server ticks");
			return -1;
		}
		level.getChunkAt(pen);
		Villager worker = null;
		int checks = 0;
		try {
			ref.data().extra.pen = Optional.of(pen);
			stageBasePen(level, pen, stagedBlocks);
			worker = EntityTypes.VILLAGER.spawn(level, pen, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("butcher check worker did not spawn");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			Ctx ctx = new Ctx(level, worker, JobManager.state(worker), new VillagerMemory(), ref, level.getGameTime() + 1);
			setZones(ref, List.of(zone("whole-pen", pen, 5)));

			int cap = Livestock.PEN_CAPACITY_PER_SPECIES;
			spawn(level, pen, EntityTypes.COW, Math.max(0, cap - 1), spawned, 1.1);
			check(ButcherJob.pickVictim(ctx, spawned) == null, "a herd below its per-species cap is not culled"); checks++;
			spawn(level, pen, EntityTypes.COW, 1, spawned, 1.1);
			check(ButcherJob.pickVictim(ctx, spawned) == null && !PenExpansion.overCapacity(level, ref),
				"a herd exactly at its per-species cap is neither culled nor expanded"); checks++;
			spawn(level, pen, EntityTypes.COW, 1, spawned, 1.1);
			check(PenExpansion.overCapacity(pen, spawned, List.of(zone("whole-pen", pen, 5))),
				"a managed species above capacity requests a pen expansion"); checks++;
			Animal surplusCow = ButcherJob.pickVictim(ctx, spawned);
			check(surplusCow != null && surplusCow.getType() == EntityTypes.COW
				&& ButcherJob.isSurplusCandidate(ctx, surplusCow, spawned),
				"only the first cow above its species cap becomes a candidate"); checks++;
			Animal removedCow = spawned.stream().filter(a -> a != surplusCow).findFirst().orElseThrow();
			removedCow.discard();
			check(!ButcherJob.isSurplusCandidate(ctx, surplusCow, spawned),
				"a retained target is rejected when the live herd falls back to its cap"); checks++;

			clear(spawned);
			spawn(level, pen, EntityTypes.COW, cap, spawned, 1.1);
			spawn(level, pen, EntityTypes.PIG, 1, spawned, 1.1);
			check(ButcherJob.pickVictim(ctx, spawned) == null, "cows at capacity do not make a below-cap pig herd look surplus"); checks++;
			spawn(level, pen, EntityTypes.PIG, cap, spawned, 1.1);
			Animal surplusPig = ButcherJob.pickVictim(ctx, spawned);
			check(surplusPig != null && surplusPig.getType() == EntityTypes.PIG,
				"species capacity is counted independently and only surplus pigs are selected"); checks++;

			clear(spawned);
			setZones(ref, List.of(zone("small-pasture", pen, 2)));
			spawn(level, pen, EntityTypes.COW, cap, spawned, 1.1);
			Animal outsidePasture = animal(level, pen, EntityTypes.COW, 4.5, 0.5, spawned);
			check(ButcherJob.pickVictim(ctx, spawned) == null && !ButcherJob.isSurplusCandidate(ctx, outsidePasture, spawned)
				&& !PenExpansion.overCapacity(pen, spawned, List.of(zone("small-pasture", pen, 2))),
				"an animal outside this village's valid pasture cannot create surplus or expand its pen"); checks++;

			clear(spawned);
			ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			for (int i = 0; i < cap + 1; i++) {
				animal(level, pen, EntityTypes.COW, 6.5 + i * 0.6, 0.5, spawned);
			}
			check(ButcherJob.pickVictim(ctx, spawned) == null,
				"a nearby herd outside the registered pen is not adopted as this village's livestock"); checks++;

			clear(spawned);
			setZones(ref, List.of(zone("whole-pen", pen, 5)));
			spawn(level, pen, EntityTypes.COW, cap + 2, spawned, 1.1);
			Animal named = spawned.get(0);
			Animal leashed = spawned.get(1);
			Animal baby = spawned.get(2);
			Animal courting = spawned.get(3);
			Animal dead = spawned.get(4);
			named.setCustomName(Component.literal("Protected"));
			leashed.setLeashedTo(worker, true);
			baby.setAge(-24000);
			courting.setInLove(null);
			dead.discard();
			Animal protectedCheck = ButcherJob.pickVictim(ctx, spawned);
			check(protectedCheck != null && protectedCheck != named && protectedCheck != leashed
				&& protectedCheck != baby && protectedCheck != courting && protectedCheck != dead
				&& !ButcherJob.cullable(named) && !ButcherJob.cullable(leashed) && !ButcherJob.cullable(baby)
				&& !ButcherJob.cullable(courting) && !ButcherJob.cullable(dead),
				"named, leashed, baby, courting, and dead animals remain protected while eligible surplus can be selected"); checks++;

			clear(spawned);
			setZones(ref, List.of(zone("annex-pasture", pen, 9)));
			check(PenExpansion.capacity(level, ref) == cap,
				"a larger pasture zone alone does not raise the physical pen capacity"); checks++;
			stageAnnex(level, pen, stagedBlocks);
			check(PenExpansion.capacity(level, ref) == cap + 4 && PenExpansion.plan(level, pen).isEmpty(),
				"a complete, supported, pasture-covered physical annex raises capacity after block-state recomputation"); checks++;
			spawn(level, pen, EntityTypes.COW, cap, spawned, 1.0);
			for (int i = 0; i < 4; i++) {
				double x = i % 2 == 0 ? -0.5 : 0.5;
				double z = i < 2 ? 6.5 : 8.5;
				animal(level, pen, EntityTypes.COW, x, z, spawned);
			}
			check(spawned.size() == cap + 4 && ButcherJob.pickVictim(ctx, spawned) == null
				&& !PenExpansion.overCapacity(level, ref),
				"eight base cows plus four real annex cows share the same twelve-animal butcher cap"); checks++;
			Animal annexSurplus = animal(level, pen, EntityTypes.COW, 0.0, 7.5, spawned);
			Animal selected = ButcherJob.pickVictim(ctx, spawned);
			boolean revalidated = selected != null && ButcherJob.isSurplusCandidate(ctx, selected, spawned);
			boolean overCapacity = PenExpansion.overCapacity(level, ref);
			List<Animal> liveArea = level.getEntitiesOfClass(Animal.class, PenExpansion.area(level, pen), Animal::isAlive);
			List<Animal> liveCows = liveArea.stream().filter(animal -> animal.getType() == EntityTypes.COW).toList();
			long liveManagedCows = liveCows.stream().filter(animal -> PenExpansion.contains(level, pen, animal)
				&& Livestock.inPasture(animal, PenExpansion.area(level, pen), ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of())
					.stream().filter(zone -> "pasture".equals(zone.kind())).toList())).count();
			check(spawned.size() == cap + 5 && selected != null && revalidated && overCapacity,
				"the same cap selects and revalidates an actual surplus after the full annexed herd exceeds twelve"
					+ " [spawned=" + spawned.size() + ", cap=" + (cap + 4) + ", selectedNull=" + (selected == null)
					+ ", revalidated=" + revalidated + ", overCapacity=" + overCapacity
					+ ", pen=" + pen.toShortString() + ", extra=" + annexSurplus.blockPosition().toShortString()
					+ ", extraInAnnex=" + PenExpansion.inAnnex(level, pen, annexSurplus)
					+ ", registeredPen=" + ref.data().extra.pen.map(BlockPos::toShortString).orElse("none")
					+ ", queryArea=" + PenExpansion.area(level, pen) + ", liveAnimals=" + liveArea.size()
					+ ", liveCows=" + liveCows.size() + ", liveManagedCows=" + liveManagedCows
					+ ", liveCowPositions=" + liveCows.stream().map(animal -> animal.blockPosition().toShortString()
						+ "/pos=" + animal.position() + "/annex=" + PenExpansion.inAnnex(level, pen, animal)
						+ "/sectionCap=" + PenExpansion.sectionCapacity(level, pen, animal)).toList()
					+ ", spawnedMembership=" + spawned.stream().map(animal -> animal.getUUID() + "/sameLevel="
						+ (animal.level() == level) + "/sameEntity=" + (level.getEntity(animal.getUUID()) == animal)
						+ "/entityTicking=" + level.isPositionEntityTicking(animal.blockPosition())
						+ "/pos=" + animal.position()).toList() + "]"); checks++;
			annexSurplus.discard();
			return checks;
		} finally {
			spawned.forEach(Entity::discard);
			if (worker != null) worker.discard();
			stagedBlocks.forEach((pos, state) -> {
				if (level.hasChunkAt(pos)) level.setBlock(pos, state, 3);
			});
			releaseEntityTickingFixture(level, pen);
			ref.data().extra.pen = oldPen;
			if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
			ref.dirty();
		}
	}

	private static void stageBasePen(ServerLevel level, BlockPos pen, Map<BlockPos, BlockState> before) {
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			BlockPos floor = pen.offset(dx, -1, dz);
			stage(level, before, floor, Blocks.STONE.defaultBlockState());
			stage(level, before, floor.above(), Blocks.AIR.defaultBlockState());
			stage(level, before, floor.above(2), Blocks.AIR.defaultBlockState());
			if (Math.abs(dx) != 4 && Math.abs(dz) != 4) continue;
			BlockPos wall = floor.above();
			stage(level, before, wall, Blocks.OAK_FENCE.defaultBlockState());
		}
		BlockPos gate = pen.north(4);
		stage(level, before, gate, Blocks.OAK_FENCE_GATE.defaultBlockState()
			.setValue(FenceGateBlock.FACING, Direction.NORTH).setValue(FenceGateBlock.OPEN, false));
	}

	private static void stageAnnex(ServerLevel level, BlockPos pen, Map<BlockPos, BlockState> before) {
		for (int dx = -3; dx <= 3; dx++) for (int dz = 5; dz <= 9; dz++) {
			BlockPos floor = pen.offset(dx, -1, dz);
			stage(level, before, floor, Blocks.STONE.defaultBlockState());
			stage(level, before, floor.above(), Blocks.AIR.defaultBlockState());
			stage(level, before, floor.above(2), Blocks.AIR.defaultBlockState());
		}
		for (int dz = 5; dz <= 8; dz++) for (int dx : new int[] {-2, 2}) {
			stage(level, before, pen.offset(dx, 0, dz), Blocks.OAK_FENCE.defaultBlockState());
		}
		for (int dx : new int[] {-2, -1, 1, 2}) {
			stage(level, before, pen.offset(dx, 0, 9), Blocks.OAK_FENCE.defaultBlockState());
		}
		stage(level, before, pen.south(9), Blocks.OAK_FENCE_GATE.defaultBlockState()
			.setValue(FenceGateBlock.FACING, Direction.SOUTH).setValue(FenceGateBlock.OPEN, false));
	}

	private static void stage(ServerLevel level, Map<BlockPos, BlockState> before, BlockPos position, BlockState state) {
		level.getChunkAt(position);
		if (level.getBlockEntity(position) != null) {
			throw new IllegalStateException("BUTCHER CHECK FAILED: pen geometry overlaps a block entity at " + position);
		}
		before.putIfAbsent(position.immutable(), level.getBlockState(position));
		if (!level.getBlockState(position).equals(state) && !level.setBlock(position, state, 3)) {
			throw new IllegalStateException("BUTCHER CHECK FAILED: could not stage pen block at " + position);
		}
	}

	private static void spawn(ServerLevel level, BlockPos pen, EntityType<? extends Animal> type, int count,
		List<Animal> spawned, double spacing) {
		int offset = spawned.size();
		for (int i = 0; i < count; i++) {
			int position = offset + i;
			double x = ((position % 4) - 1.5) * spacing;
			double z = ((position / 4) - 1.5) * spacing;
			animal(level, pen, type, x, z, spawned);
		}
	}

	private static Animal animal(ServerLevel level, BlockPos pen, EntityType<? extends Animal> type,
		double x, double z, List<Animal> spawned) {
		Animal animal = type.create(level, EntitySpawnReason.COMMAND);
		if (animal == null) throw new IllegalStateException("butcher check animal did not spawn: " + type);
		animal.setNoAi(true);
		animal.setInvulnerable(true);
		animal.setAge(0);
		animal.setPos(pen.getX() + x, pen.getY(), pen.getZ() + z);
		if (!level.addFreshEntity(animal)) throw new IllegalStateException("butcher check animal was not admitted to the world: " + type);
		spawned.add(animal);
		return animal;
	}

	private static void clear(List<Animal> animals) {
		animals.forEach(Entity::discard);
		animals.clear();
	}

	private static ZonePolicy.Zone zone(String id, BlockPos pen, int radius) {
		return new ZonePolicy.Zone(id, "pasture", pen.getX() - radius, pen.getZ() - radius,
			pen.getX() + radius, pen.getZ() + radius);
	}

	private static void setZones(VillageRef ref, List<ZonePolicy.Zone> zones) {
		ref.bellEntity().setAttached(VillagerPlus.ZONES, zones);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("BUTCHER CHECK FAILED: " + message);
	}
}
