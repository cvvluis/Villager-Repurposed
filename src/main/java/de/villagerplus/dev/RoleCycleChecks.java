package de.villagerplus.dev;

import de.villagerplus.job.Anim;
import de.villagerplus.job.ButcherChecks;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.StationShed;
import de.villagerplus.job.CookJob;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.FarmerJob;
import de.villagerplus.job.FreightLoading;
import de.villagerplus.job.GravelQuarry;
import de.villagerplus.job.GuardDuty;
import de.villagerplus.job.HealerBrewing;
import de.villagerplus.job.HealerJob;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobState;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Lighting;
import de.villagerplus.job.PenExpansion;
import de.villagerplus.job.Workstation;
import de.villagerplus.village.Network;
import de.villagerplus.village.RailTransport;
import de.villagerplus.village.Specials;
import de.villagerplus.village.TradeNetwork;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Wellbeing;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Headless world checks for the previously open job, depot and station behaviours. */
public final class RoleCycleChecks {
	private static int checks;

	private RoleCycleChecks() { }
	private record FixtureCell(BlockState state, CompoundTag blockEntity) { }

	public static int run(ServerLevel level, BlockPos origin) {
		checks = 0;
		pace();
		lighting(level, origin);
		brewing(level, origin.south(48));
		guard(level, origin.east(48));
		if (!pen(level, origin.south(96))) return -1;
		freight(level, origin.east(96));
		gravel(level, origin.west(48));
		workstation(level, origin.west(96));
		station(level, origin.north(48));
		sign(level, origin.north(96));
		return checks;
	}

	private static void pace() {
		var hungry = new Wellbeing.Status(10, 80, 80, 100, 80, 0, true, true);
		var fed = new Wellbeing.Status(90, 90, 90, 100, 80, 0, true, true);
		check(Wellbeing.workQuarters(hungry, false) == 2, "hunger directly caps a cheerful worker at half pace");
		check(Wellbeing.workQuarters(hungry, true) == 3, "a prepared meal restores one work quarter");
		check(Wellbeing.workQuarters(fed, false) == 4, "a fed worker keeps full pace");
		check(new CookJob().indoorsDuringStorm() && new HealerJob().indoorsDuringStorm(), "cook and healer keep indoor work in a storm");
		check(!new FarmerJob().indoorsDuringStorm(), "farmer stops outdoor work in a storm");
	}

	private static void lighting(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			BlockPos air = isolatedLampSite(level, at);
			var villager = villager(level, air.north(), spawned);
			ref.chest(level).setItem(0, new net.minecraft.world.item.ItemStack(Items.TORCH, 2));
			BlockPos owned = at.west(2);
			column(level, owned.below());
			level.setBlock(owned, Blocks.CHEST.defaultBlockState(), 3);
			level.getBlockEntity(owned).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			var privateChest = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, owned);
			privateChest.setItem(0, new net.minecraft.world.item.ItemStack(Items.TORCH, 4));
			check(Lighting.placeable(level, air), "open stone pad with block light 0 can take a lamp");
			JobState state = JobManager.state(villager);
			state.target = air;
			Ctx ctx = context(level, ref, villager, state);
			check(Lighting.work(ctx) && level.getBlockState(air).is(Blocks.TORCH), "lamplighter places one real torch");
			check(JobUtil.count(ref.chest(level), stack -> stack.is(Items.TORCH)) == 1, "communal torch stock decreases by one");
			check(JobUtil.count(privateChest, stack -> stack.is(Items.TORCH)) == 4, "private chest torches stay untouched");
			check(!Lighting.placeable(level, air), "a placed torch is no longer a dark cell");
			level.setBlock(air.east(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(air.east().below(), Blocks.FARMLAND.defaultBlockState(), 3);
			check(!Lighting.placeable(level, air.east()), "farmland is not a lamp site");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	/** Repeated role runs leave their own lights in place; select a clean remote test pad without deleting them. */
	private static BlockPos isolatedLampSite(ServerLevel level, BlockPos village) {
		int spacing = 64;
		// Search all four directions and a wide band: every earlier run of this check leaves lights behind in a reused dev world.
		for (int step = 0; step < 4 * 14; step++) {
			int offset = spacing * (2 + step / 4);
			BlockPos candidate = switch (step % 4) {
				case 0 -> village.east(offset);
				case 1 -> village.south(offset);
				case 2 -> village.west(offset);
				default -> village.north(offset);
			};
			candidate = candidate.above(3);
			// The production spacing rule requires a fully loaded disk and checks the actual world lights.
			loadSpacingDisk(level, candidate, spacing);
			if (!Lighting.spaced(level, candidate)) continue;
			BlockPos floor = candidate.below();
			BlockPos head = candidate.above();
			if (!level.getBlockState(candidate).isAir() || !level.getBlockState(head).isAir()
				|| level.getBlockEntity(candidate) != null || level.getBlockEntity(head) != null) continue;
			BlockState floorState = level.getBlockState(floor);
			if (level.getBlockEntity(floor) != null || floorState.hasBlockEntity()) continue;
			if (!floorState.isAir() && !floorState.isFaceSturdy(level, floor, Direction.UP)) continue;
			if (floorState.isAir()) level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
			return candidate;
		}
		throw new IllegalStateException("Role lighting fixture found no empty, loaded pad outside existing lamp spacing");
	}

	private static void loadSpacingDisk(ServerLevel level, BlockPos center, int radius) {
		int minChunkX = Math.floorDiv(center.getX() - radius, 16);
		int maxChunkX = Math.floorDiv(center.getX() + radius, 16);
		int minChunkZ = Math.floorDiv(center.getZ() - radius, 16);
		int maxChunkZ = Math.floorDiv(center.getZ() + radius, 16);
		long radiusSquared = (long) radius * radius;
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			int chunkMinX = chunkX << 4;
			int closestX = Math.max(chunkMinX, Math.min(chunkMinX + 15, center.getX()));
			long dx = (long) closestX - center.getX();
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				int chunkMinZ = chunkZ << 4;
				int closestZ = Math.max(chunkMinZ, Math.min(chunkMinZ + 15, center.getZ()));
				long dz = (long) closestZ - center.getZ();
				if (dx * dx + dz * dz < radiusSquared) {
					level.getChunkAt(new BlockPos(chunkMinX, center.getY(), chunkMinZ));
				}
			}
		}
	}

	private static void brewing(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		BlockPos chestPos = at.east(3);
		Map<BlockPos, FixtureCell> fixtureBefore = new HashMap<>();
		List<BlockPos> fixtureCells = new ArrayList<>(List.of(at, at.below(), chestPos, chestPos.below()));
		for (Direction direction : Direction.Plane.HORIZONTAL) fixtureCells.add(chestPos.relative(direction));
		for (BlockPos pos : fixtureCells) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			fixtureBefore.put(pos.immutable(), new FixtureCell(level.getBlockState(pos), blockEntity == null ? null
				: blockEntity.saveWithFullMetadata(level.registryAccess())));
		}
		try {
			Checks090.fixtureForEquipment(level, at);
			if (!(level.getBlockEntity(at) instanceof BellBlockEntity bell)) {
				throw new IllegalStateException("Brewing fixture village bell was not created");
			}
			var data = new de.villagerplus.village.VillageData();
			bell.setAttached(VillagerPlus.VILLAGE, data);
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos neighbor = chestPos.relative(direction);
				if (level.getBlockState(chestPos).getBlock() instanceof ChestBlock chestBlock
					&& level.getBlockState(chestPos).getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE
					&& direction == ChestBlock.getConnectedDirection(level.getBlockState(chestPos))) {
					BlockEntity adjacent = level.getBlockEntity(neighbor);
					if (adjacent != null) adjacent.removeAttached(VillagerPlus.OWNER);
				}
			}
			BlockEntity chestEntity = level.getBlockEntity(chestPos);
			if (chestEntity != null) chestEntity.removeAttached(VillagerPlus.OWNER);
			var communalChest = de.villagerplus.village.Lager.publicContainerAt(level, chestPos);
			if (communalChest == null) throw new IllegalStateException("Brewing fixture public chest is unavailable");
			communalChest.clearContent();
			// Potion stacks are deliberately not accepted by registered material chests; seed the isolated fixture first.
			communalChest.setItem(0, PotionContents.createItemStack(Items.POTION, Potions.WATER));
			communalChest.setItem(1, new net.minecraft.world.item.ItemStack(Items.NETHER_WART));
			communalChest.setItem(2, new net.minecraft.world.item.ItemStack(Items.BLAZE_POWDER));
			communalChest.setItem(3, new net.minecraft.world.item.ItemStack(Items.GLISTERING_MELON_SLICE));
			if (!HealerBrewing.potionPath(communalChest.getItem(0)).equals("water")
				|| !communalChest.getItem(1).is(Items.NETHER_WART)
				|| !communalChest.getItem(2).is(Items.BLAZE_POWDER)
				|| !communalChest.getItem(3).is(Items.GLISTERING_MELON_SLICE)) {
				throw new IllegalStateException("Brewing fixture could not seed all ingredients before registering the chest");
			}
			data.chest = Optional.of(chestPos);
			VillageRef ref = new VillageRef(at, data, bell);
			BlockPos stand = at.north(2);
			column(level, stand.below());
			level.setBlock(stand, Blocks.BREWING_STAND.defaultBlockState(), 3);
			BlockPos owned = at.west(2);
			column(level, owned.below());
			level.setBlock(owned, Blocks.CHEST.defaultBlockState(), 3);
			level.getBlockEntity(owned).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			var privateChest = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, owned);
			privateChest.setItem(0, new net.minecraft.world.item.ItemStack(Items.NETHER_WART, 3));
			var healer = villager(level, stand.east(), spawned);
			Ctx ctx = context(level, ref, healer, JobManager.state(healer));
			for (int i = 0; i < 4; i++) HealerBrewing.work(ctx);
			var entity = level.getBlockEntity(stand);
			check(entity instanceof BrewingStandBlockEntity, "brewing stand block entity exists");
			BrewingStandBlockEntity brewing = (BrewingStandBlockEntity) entity;
			check(HealerBrewing.potionPath(brewing.getItem(0)).equals("water"), "water bottle moved into the stand");
			check(brewing.getItem(HealerBrewing.INGREDIENT).is(Items.NETHER_WART), "nether wart moved into the stand");
			check(brewing.getItem(HealerBrewing.FUEL).is(Items.BLAZE_POWDER), "blaze powder moved into the stand");
			check(JobUtil.count(privateChest, stack -> stack.is(Items.NETHER_WART)) == 3, "private brewing ingredients stay private");
			HealerBrewing.tickStand(level, stand, 450);
			check(HealerBrewing.potionPath(brewing.getItem(0)).equals("awkward"), "vanilla brewing stand turns water and nether wart into awkward potion");
			check(brewing.getItem(HealerBrewing.INGREDIENT).isEmpty(), "brewing consumes the nether wart");
			for (int i = 0; i < 2; i++) HealerBrewing.work(ctx);
			HealerBrewing.tickStand(level, stand, 450);
			check(HealerJob.isHealingPotion(brewing.getItem(0)) || HealerJob.isHealingPotion(healer.getInventory().getItem(0)),
				"glistering melon finishes a healing potion");
			for (int i = 0; i < 2 && !HealerJob.isHealingPotion(healer.getInventory().getItem(0)); i++) HealerBrewing.work(ctx);
			check(HealerJob.isHealingPotion(healer.getInventory().getItem(0)), "healer takes the finished potion out of the stand");
			var patient = villager(level, stand.west(), spawned);
			patient.setInvulnerable(false);
			patient.setHealth(4.0F);
			healer.teleportTo(patient.getX() + 1.0, patient.getY(), patient.getZ());
			float healthBefore = patient.getHealth();
			JobState healState = JobManager.state(healer);
			healState.clearTarget();
			healState.idleUntil[0] = 0;
			healState.idleUntil[1] = 0;
			healState.cooldownUntil = 0;
			healState.targetEntity = patient;
			new HealerJob().primary(context(level, ref, healer, healState));
			check(patient.getHealth() > healthBefore, "healer applies the brewed potion to a hurt villager; health "
				+ healthBefore + " -> " + patient.getHealth() + " activity=" + healState.activity
				+ " carried=" + healer.getInventory().getItem(0));
		} finally {
			spawned.forEach(Entity::discard);
			for (Map.Entry<BlockPos, FixtureCell> entry : fixtureBefore.entrySet()) {
				BlockPos pos = entry.getKey();
				FixtureCell cell = entry.getValue();
				level.setBlock(pos, cell.state(), 2);
				if (cell.blockEntity() == null) {
					if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
				} else {
					BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
					if (restored != null) level.setBlockEntity(restored);
				}
			}
		}
	}

	private static void guard(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		Raid raid = null;
		Difficulty originalDifficulty = level.getServer().getWorldData().getDifficulty();
		try {
			level.getServer().setDifficulty(Difficulty.NORMAL, true);
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			BlockPos post = at.north(4).above(6);
			column(level, post.below());
			level.setBlock(post, Blocks.OAK_FENCE.defaultBlockState(), 3);
			level.setBlock(post.above(), Blocks.LANTERN.defaultBlockState(), 3);
			BlockPos station = at.west(6);
			column(level, station.below());
			Specials.set(ref, Blueprints.STATION, station);
			BlockPos curve = curvedRoad(level, ref);
			List<BlockPos> railPath = railPath(level, station);
			var posts = GuardDuty.posts(level, ref);
			check(posts.stream().anyMatch(point -> point.getX() == post.getX() && point.getZ() == post.getZ()), "guard posts include the lantern tower");
			check(posts.stream().anyMatch(point -> point.distSqr(station) <= 16), "guard posts include the station");
			check(posts.stream().anyMatch(point -> Math.abs(point.getX() - curve.getX()) <= 1 && Math.abs(point.getZ() - curve.getZ()) <= 1),
				"guard patrol uses paved waypoints on a persisted curved street route");
			check(railPath.stream().anyMatch(rail -> posts.stream().anyMatch(point -> Math.abs(point.getY() - rail.getY()) <= 2
				&& Math.abs(point.getX() - rail.getX()) + Math.abs(point.getZ() - rail.getZ()) <= 1)),
				"guard patrol includes walkable posts beside real station rails");
			check(!GuardDuty.raidNearby(level, at), "an empty raid manager does not trigger a guard alarm");
			// The villager fixture clears its spawn column; keep it away from the village bell.
			var guard = villager(level, at.north(2).above(), spawned);
			JobState patrolState = JobManager.state(guard);
			int roadPost = -1;
			for (int i = 0; i < posts.size(); i++) {
				BlockPos point = posts.get(i);
				if (Math.abs(point.getX() - curve.getX()) <= 1 && Math.abs(point.getZ() - curve.getZ()) <= 1) { roadPost = i; break; }
			}
			check(roadPost >= 0, "curved road waypoint is available to the guard work cycle");
			patrolState.patrolIndex = roadPost;
			patrolState.lightAt = level.getGameTime() + 1000;
			BlockPos patrolTarget = posts.get(roadPost);
			guard.teleportTo(patrolTarget.getX() + 0.5, patrolTarget.getY(), patrolTarget.getZ() + 0.5);
			GuardDuty.work(context(level, ref, guard, patrolState));
			check(patrolState.patrolIndex == (roadPost + 1) % posts.size() && patrolState.activity.contains("patrouilliert"),
				"guard completes a real road waypoint and advances its patrol cursor");
			var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
			if (zombie == null) throw new IllegalStateException("Guard role-cycle check could not create a zombie fixture");
			zombie.setNoAi(true);
			zombie.setPos(at.east().getX() + 0.5, at.above().getY(), at.east().getZ() + 0.5);
			spawned.add(zombie);
			if (!level.addFreshEntity(zombie)) throw new IllegalStateException("Guard role-cycle check could not add its zombie fixture");
			guard.teleportTo(at.east().getX() + 0.5, at.above().getY(), at.east().getZ() + 0.5);
			JobState state = JobManager.state(guard);
			state.lightAt = level.getGameTime() + 1000;
			float health = zombie.getHealth();
			GuardDuty.work(context(level, ref, guard, state));
			check(zombie.getHealth() < health, "guard hurts a hostile at contact range");
			check(state.activity.contains("bekämpft"), "guard reports the fight");
			zombie.discard();
			spawned.remove(zombie);

			BlockPos raidSpawn = at.south(12);
			column(level, raidSpawn.below());
			raid = startRaidForCheck(level, at, raidSpawn);
			for (Raider raider : raid.getAllRaiders()) {
				raider.setNoAi(true);
				spawned.add(raider);
			}
			check(raid.isStarted() && raid.isActive() && !raid.isStopped() && !raid.getAllRaiders().isEmpty()
				&& level.getRaids().getNearbyRaid(at, 96 * 96) == raid && GuardDuty.raidNearby(level, at),
				"vanilla raid manager reports the spawned active raid at the village bell");
			check(GuardDuty.raidNearby(level, at.east(95)), "guard detects an active raid 95 blocks away");
			state.clearTarget();
			state.lightAt = level.getGameTime() + 1000;
			state.guardRaidAlarmAt = 0;
			long alarmAt = level.getGameTime();
			GuardDuty.work(context(level, ref, guard, state, alarmAt));
			var alarmBellEntity = level.getBlockEntity(at);
			boolean alarmActivity = state.activity.contains("Raid-Alarm");
			boolean raidCooldown = state.guardRaidAlarmAt == alarmAt + 200;
			boolean bellRinging = alarmBellEntity instanceof BellBlockEntity bell && bell.shaking;
			check(alarmActivity && raidCooldown && bellRinging,
				"guard rings the village bell immediately for a live raid despite the ordinary alert cooldown"
					+ " [activity=" + state.activity + ", alarmAt=" + state.guardRaidAlarmAt + ", expected=" + (alarmAt + 200)
					+ ", bellEntity=" + (alarmBellEntity == null ? "null" : alarmBellEntity.getClass().getSimpleName())
					+ ", shaking=" + bellRinging + "]");
			raid.stop();
			check(!GuardDuty.raidNearby(level, at), "a stopped raid no longer keeps the guard alarm active");
		} finally {
			try {
				if (raid != null) removeRaidForCheck(level, raid);
				for (Entity entity : spawned) if (entity != null) entity.discard();
			} finally {
				level.getServer().setDifficulty(originalDifficulty, true);
			}
		}
	}

	private static BlockPos curvedRoad(ServerLevel level, VillageRef ref) {
		BlockPos start = ref.bell();
		List<BlockPos> path = new ArrayList<>();
		for (int x = 0; x <= 6; x++) path.add(start.east(x));
		for (int z = 1; z <= 4; z++) path.add(start.east(6).south(z));
		for (int x = 7; x <= 14; x++) path.add(start.east(x).south(4));
		BlockPos end = path.get(path.size() - 1);
		TradeNetwork.Link link = new TradeNetwork.Link(Network.dim(level), end, "Kurvenort", 60, true, 12, -1);
		Network.save(ref.bellEntity(), Network.of(ref.bellEntity()).withLinks(List.of(link)));

		List<BlockPos> canonical = new ArrayList<>(path);
		if (canonical.get(0).asLong() > canonical.get(canonical.size() - 1).asLong()) Collections.reverse(canonical);
		String key = Network.dim(level) + end.asLong();
		var roads = new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, java.util.Map.<String, Long>of()));
		roads.put(key + ":routeGeometry", 3L);
		roads.put(key + ":routeFrom", canonical.get(0).asLong());
		roads.put(key + ":routeTo", canonical.get(canonical.size() - 1).asLong());
		roads.put(key + ":routeCount", (long) canonical.size());
		for (int i = 0; i < canonical.size(); i++) roads.put(key + ":routePoint:" + i, canonical.get(i).asLong());
		ref.bellEntity().setAttached(VillagerPlus.ROADS, java.util.Map.copyOf(roads));
		ref.dirty();
		for (int i = 1; i < path.size(); i++) {
			BlockPos surface = path.get(i);
			level.getChunkAt(surface);
			level.setBlock(surface.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(surface, Blocks.GRAVEL.defaultBlockState(), 3);
			level.setBlock(surface.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(surface.above(2), Blocks.AIR.defaultBlockState(), 3);
		}
		return path.get(8);
	}

	private static List<BlockPos> railPath(ServerLevel level, BlockPos station) {
		List<BlockPos> path = List.of(station.west(5), station.west(4), station.west(3), station.west(3).north(), station.west(3).north(2));
		for (BlockPos rail : path) {
			level.getChunkAt(rail);
			level.setBlock(rail.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(rail, Blocks.RAIL.defaultBlockState(), 3);
			level.setBlock(rail.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(rail.above(2), Blocks.AIR.defaultBlockState(), 3);
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos beside = rail.relative(direction);
				if (path.contains(beside)) continue;
				level.setBlock(beside.below(), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(beside, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(beside.above(), Blocks.AIR.defaultBlockState(), 3);
			}
		}
		return path;
	}

	private static Raid startRaidForCheck(ServerLevel level, BlockPos center, BlockPos spawn) {
		Raid raid = new Raid(center, level.getDifficulty());
		try {
			// Vanilla's creation path registers the raid separately from getOrCreateRaid.
			Method uniqueId = Raids.class.getDeclaredMethod("getUniqueId");
			uniqueId.setAccessible(true);
			raidMapForCheck(level).put((Integer) uniqueId.invoke(level.getRaids()), raid);
			// The normal tick sets started immediately before spawning the first group.
			Field started = Raid.class.getDeclaredField("started");
			started.setAccessible(true);
			started.setBoolean(raid, true);
			Method spawnGroup = Raid.class.getDeclaredMethod("spawnGroup", ServerLevel.class, BlockPos.class);
			spawnGroup.setAccessible(true);
			spawnGroup.invoke(raid, level, spawn);
			return raid;
		} catch (ReflectiveOperationException | RuntimeException e) {
			for (Raider raider : raid.getAllRaiders()) raider.discard();
			removeRaidForCheck(level, raid);
			throw new IllegalStateException("Could not create a vanilla raid for the guard role-cycle check", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<Integer, Raid> raidMapForCheck(ServerLevel level) throws ReflectiveOperationException {
		Field map = Raids.class.getDeclaredField("raidMap");
		map.setAccessible(true);
		return (Map<Integer, Raid>) map.get(level.getRaids());
	}

	private static void removeRaidForCheck(ServerLevel level, Raid raid) {
		raid.stop();
		try {
			var id = level.getRaids().getId(raid);
			if (id.isPresent()) raidMapForCheck(level).remove(id.getAsInt(), raid);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not remove the guard role-cycle raid", e);
		}
	}

	private static boolean pen(ServerLevel level, BlockPos at) {
		// Remote command fixtures are commonly only FULL-chunk loaded, not entity-ticking.
		BlockPos pen = at.east(24);
		if (!ButcherChecks.prepareEntityTickingFixture(level, pen)) {
			System.out.println("ROLE CYCLE PREPARING entity-ticking pen at " + pen.toShortString() + "; retry after server ticks");
			return false;
		}
		List<Entity> spawned = new ArrayList<>();
		Map<BlockPos, FixtureCell> before = new HashMap<>();
		VillageRef ref = Checks090.fixtureForEquipment(level, at);
		// Keep the pen floor separate from the bell block entity created by fixtureForEquipment.
		Optional<BlockPos> oldPen = ref.data().extra.pen;
		Container publicStock = ref.chest(level);
		if (publicStock == null) throw new IllegalStateException("Pen fixture needs its public material chest");
		List<net.minecraft.world.item.ItemStack> oldPublicStock = snapshotContainer(publicStock);
		try {
			// Base pasture plus the 3×4 annexe area are staged as a real, supported enclosure;
			// the existing five-block wall is shared by both spaces.
			for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 10; dz++) {
				BlockPos floor = pen.offset(dx, -1, dz);
				level.getChunkAt(floor);
				for (BlockPos cell : List.of(floor, floor.above(), floor.above(2))) {
					if (level.getBlockEntity(cell) != null || level.getBlockState(cell).hasBlockEntity()) {
						throw new IllegalStateException("Pen fixture overlaps a protected block entity at " + cell);
					}
				}
				captureFixtureCell(level, before, floor);
				captureFixtureCell(level, before, floor.above());
				captureFixtureCell(level, before, floor.above(2));
				level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
				if (Math.abs(dx) != 4 && Math.abs(dz) != 4) continue;
				BlockPos wall = pen.offset(dx, 0, dz);
				if (level.getBlockEntity(wall) != null || level.getBlockState(wall).hasBlockEntity()) {
					throw new IllegalStateException("Pen fixture overlaps a protected block entity at " + wall);
				}
				captureFixtureCell(level, before, wall);
				level.setBlock(wall, Blocks.OAK_FENCE.defaultBlockState(), 3);
			}
			BlockPos baseGate = pen.north(4);
			if (level.getBlockEntity(baseGate) != null || level.getBlockState(baseGate).hasBlockEntity()) {
				throw new IllegalStateException("Pen fixture overlaps a protected block entity at " + baseGate);
			}
			captureFixtureCell(level, before, baseGate);
			level.setBlock(baseGate, Blocks.OAK_FENCE_GATE.defaultBlockState()
				.setValue(FenceGateBlock.FACING, Direction.NORTH).setValue(FenceGateBlock.OPEN, false), 3);
			ref.data().extra.pen = Optional.of(pen);
			check(PenExpansion.capacity(level, ref) == de.villagerplus.Config.CULL_CAP,
				"an unbuilt annex retains the base livestock capacity");
			for (int i = 0; i < 9; i++) {
				var chicken = EntityTypes.CHICKEN.spawn(level, pen.above(), EntitySpawnReason.COMMAND);
				spawned.add(chicken);
				chicken.setNoAi(true);
				chicken.teleportTo(pen.getX() + 0.5, pen.above().getY(), pen.getZ() + 0.5);
			}
			boolean chickensOverCapacity = PenExpansion.overCapacity(level, pen);
			List<Entity> livePenEntities = level.getEntitiesOfClass(Entity.class,
				new net.minecraft.world.phys.AABB(pen).inflate(5, 3, 5), Entity::isAlive);
			List<Entity> liveChickens = livePenEntities.stream().filter(entity -> entity.getType() == EntityTypes.CHICKEN).toList();
			check(chickensOverCapacity, "nine chickens exceed the pen cap"
				+ " [overCapacity=" + chickensOverCapacity + ", registeredPen="
				+ ref.data().extra.pen.map(BlockPos::toShortString).orElse("none") + ", fixturePen=" + pen.toShortString()
				+ ", cap=" + PenExpansion.capacity(level, ref) + ", livePenEntities=" + livePenEntities.size()
				+ ", liveChickens=" + liveChickens.size() + ", chickenPositions="
				+ liveChickens.stream().map(entity -> entity.blockPosition().toShortString() + "/" + entity.position()).toList() + "]");
			publicStock.clearContent();
			publicStock.setItem(0, new net.minecraft.world.item.ItemStack(Items.OAK_FENCE, 12));
			publicStock.setItem(1, new net.minecraft.world.item.ItemStack(Items.OAK_FENCE_GATE, 1));
			BlockPos owned = pen.west(2);
			if (level.getBlockEntity(owned) != null || level.getBlockState(owned).hasBlockEntity()) {
				throw new IllegalStateException("Pen fixture overlaps a protected private chest at " + owned);
			}
			captureFixtureCell(level, before, owned.below());
			captureFixtureCell(level, before, owned);
			column(level, owned.below());
			level.setBlock(owned, Blocks.CHEST.defaultBlockState(), 3);
			level.getBlockEntity(owned).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			var privateChest = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, owned);
			privateChest.setItem(0, new net.minecraft.world.item.ItemStack(Items.OAK_FENCE, 6));
			BlockPos workStand = pen.east(3).south(5);
			var butcher = villager(level, workStand, spawned);
			check(PenExpansion.work(context(level, ref, butcher, JobManager.state(butcher))), "butcher extends the stall");
			int fences = 0;
			for (int z = 5; z <= 8; z++) for (int x : new int[] {-2, 2}) {
				if (level.getBlockState(pen.offset(x, 0, z)).is(Blocks.OAK_FENCE)) fences++;
			}
			for (int x : new int[] {-2, -1, 1, 2}) {
				if (level.getBlockState(pen.offset(x, 0, 9)).is(Blocks.OAK_FENCE)) fences++;
			}
			check(fences == 12 && level.getBlockState(pen.south(9)).getBlock() == Blocks.OAK_FENCE_GATE
				&& PenExpansion.capacity(level, ref) == de.villagerplus.Config.CULL_CAP + 4
				&& !PenExpansion.overCapacity(level, pen)
				&& JobUtil.count(publicStock, stack -> stack.is(Items.OAK_FENCE)) == 0
				&& JobUtil.count(publicStock, stack -> stack.is(Items.OAK_FENCE_GATE)) == 0,
				"twelve public fences and one gate close a physical annex before raising capacity by four");
			int fenceStockAfter = JobUtil.count(publicStock, stack -> stack.is(Items.OAK_FENCE));
			check(!PenExpansion.work(context(level, ref, butcher, JobManager.state(butcher)))
				&& JobUtil.count(publicStock, stack -> stack.is(Items.OAK_FENCE)) == fenceStockAfter
				&& fences == 12 && !PenExpansion.overCapacity(level, pen),
				"completed annex retries preserve its blocks, materials, and effective cap");
			check(JobUtil.count(privateChest, stack -> stack.is(Items.OAK_FENCE)) == 6, "private fences are not used for the stall");
		} finally {
			spawned.forEach(Entity::discard);
			restoreContainer(publicStock, oldPublicStock);
			restoreFixtureCells(level, before);
			ref.data().extra.pen = oldPen;
			ref.dirty();
			ButcherChecks.releaseEntityTickingFixture(level, pen);
		}
		return true;
	}

	private static void freight(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		VillageRef ref = Checks090.fixtureForEquipment(level, at);
		Map<String, BlockPos> oldSpecials = Specials.all(ref);
		Container villageStock = ref.chest(level);
		if (villageStock == null) throw new IllegalStateException("Freight fixture needs its public material chest");
		List<net.minecraft.world.item.ItemStack> oldVillageStock = snapshotContainer(villageStock);
		BlockPos station = at.east(24);
		BlockPos rail = station.south(2);
		BlockPos granaryPos = station.north(6);
		Map<BlockPos, FixtureCell> before = new HashMap<>();
		try {
			// Keep this isolated from stale carts left at the bell-side station by prior runs.
			for (BlockPos pos : List.of(rail.below(), rail, rail.above(), granaryPos.below(), granaryPos, granaryPos.above())) {
				level.getChunkAt(pos);
				captureFixtureCell(level, before, pos);
				if (level.getBlockEntity(pos) != null || level.getBlockState(pos).hasBlockEntity()) {
					throw new IllegalStateException("Freight fixture overlaps a protected block entity at " + pos.toShortString());
				}
			}
			column(level, rail.below());
			level.setBlock(rail, Blocks.RAIL.defaultBlockState(), 3);
			column(level, granaryPos.below());
			level.setBlock(granaryPos, Blocks.BARREL.defaultBlockState(), 3);
			Specials.set(ref, Blueprints.STATION, station);
			Specials.set(ref, Blueprints.GRANARY, granaryPos);
			Container granaryStock = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, granaryPos);
			if (granaryStock == null) throw new IllegalStateException("Freight fixture public granary was not created");
			granaryStock.setItem(0, new net.minecraft.world.item.ItemStack(Items.WHEAT, 20));
			if (JobUtil.count(granaryStock, stack -> stack.is(Items.WHEAT)) != 20) {
				throw new IllegalStateException("Freight fixture wheat was not accepted by the registered public granary");
			}
			villageStock.clearContent();
			villageStock.setItem(0, new net.minecraft.world.item.ItemStack(Items.OAK_LOG, 5));
			if (JobUtil.count(villageStock, stack -> stack.is(Items.OAK_LOG)) != 5) {
				throw new IllegalStateException("Freight fixture construction stock was not accepted by the public materials chest");
			}
			var cart = new MinecartChest(EntityTypes.CHEST_MINECART, level);
			spawned.add(cart);
			cart.setPos(rail.getX() + 0.5, rail.getY(), rail.getZ() + 0.5);
			level.addFreshEntity(cart);
			BlockPos other = at.east(40);
			cart.setAttached(RailTransport.TRIP, new RailTransport.Trip(ref.bell(), other, rail, other, false, true, false, 0, 0));
			var porter = villager(level, rail.east(), spawned);
			Ctx ctx = context(level, ref, porter, JobManager.state(porter));
			var nearbyCarts = level.getEntitiesOfClass(MinecartChest.class, new net.minecraft.world.phys.AABB(station).inflate(8, 4, 8));
			String nearbyState = nearbyCarts.stream().map(candidate -> {
				RailTransport.Trip trip = candidate.getAttached(RailTransport.TRIP);
				return candidate.getUUID() + "@" + candidate.blockPosition().toShortString()
					+ " trip=" + (trip == null ? "none" : "travelling=" + trip.travelling() + ",home="
						+ trip.home().toShortString() + ",other=" + trip.other().toShortString());
			}).toList().toString();
			if (nearbyCarts.size() != 1 || nearbyCarts.getFirst() != cart
				|| cart.getAttached(RailTransport.TRIP) == null || !cart.getAttached(RailTransport.TRIP).travelling()) {
				throw new IllegalStateException("Freight fixture precondition failed: expected only its travelling cart in station radius; found " + nearbyState);
			}
			boolean loadedWhileTravelling = FreightLoading.work(ctx);
			int wheatAfterTravelAttempt = JobUtil.count(granaryStock, stack -> stack.is(Items.WHEAT));
			check(!loadedWhileTravelling && wheatAfterTravelAttempt == 20,
				"a travelling minecart is not loaded [work=" + loadedWhileTravelling + ", wheat=" + wheatAfterTravelAttempt
					+ ", nearby=" + nearbyState + "]");
			cart.setAttached(RailTransport.TRIP, new RailTransport.Trip(ref.bell(), other, rail, other, false, false, true, 0, 0));
			check(FreightLoading.work(ctx), "porter loads a docked chest minecart");
			check(JobUtil.count(cart, stack -> stack.is(Items.WHEAT)) == 16, "sixteen wheat move into the cart");
			check(JobUtil.count(granaryStock, stack -> stack.is(Items.WHEAT)) == 4
				&& JobUtil.count(villageStock, stack -> stack.is(Items.OAK_LOG)) == 5,
				"food loads from the public granary while material stock stays in the central depot");
		} finally {
			spawned.forEach(Entity::discard);
			restoreContainer(villageStock, oldVillageStock);
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.dirty();
			restoreFixtureCells(level, before);
		}
	}

	private static void gravel(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		VillageRef ref = Checks090.fixtureForEquipment(level, at);
		var data = ref.data();
		int existingGravel = de.villagerplus.village.StockPolicy.communalCount(level, ref, Items.GRAVEL);
		if (existingGravel >= 4096) throw new IllegalStateException("Gravel fixture needs room for one additional public gravel item; found " + existingGravel);
		int requestedGravel = Math.min(4096, existingGravel + 1);
		var oldProjects = new ArrayList<>(data.buildProjects);
		String oldPlanType = data.planType;
		Optional<BlockPos> oldPlanOrigin = data.planOrigin;
		List<String> oldWanted = new ArrayList<>(data.wanted);
		try {
			BlockPos free = at.north(2);
			column(level, free.below());
			level.setBlock(free, Blocks.GRAVEL.defaultBlockState(), 3);
			BlockPos road = at.south(2);
			column(level, road.below());
			level.setBlock(road, Blocks.GRAVEL.defaultBlockState(), 3);
			level.setBlock(road.east(), Blocks.COBBLESTONE.defaultBlockState(), 3);
			level.setBlock(road.west(), Blocks.COBBLESTONE.defaultBlockState(), 3);
			// Quarrying is demand-led: give the fixture a real active station-plan shortage.
			data.buildProjects.clear();
			data.buildProjects.add(new de.villagerplus.village.VillageData.BuildProject(
				Blueprints.STATION, at, 0, 0, 0, Optional.empty(), 0L, Optional.empty(), 0L,
				List.of("minecraft:gravel#" + requestedGravel), de.villagerplus.village.VillageData.ProjectHaul.empty(), Optional.empty(),
				Optional.empty(), Optional.empty(), false, false));
			data.planType = Blueprints.STATION;
			data.planOrigin = Optional.of(at);
			data.wanted.clear();
			data.wanted.add("minecraft:gravel#" + requestedGravel);
			ref.dirty();
			var mason = villager(level, free.east(), spawned);
			mason.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(Items.IRON_SHOVEL));
			check(!GravelQuarry.naturalSource(level, road) && GravelQuarry.naturalSource(level, free), "paved road gravel is protected");
			check(GravelQuarry.find(level, mason.blockPosition(), 6).equals(free), "quarry selects exposed gravel");
			JobState state = JobManager.state(mason);
			long now = level.getGameTime();
			if (!data.hasBuildProjectType(Blueprints.STATION) || !data.wanted.contains("minecraft:gravel#" + requestedGravel) || state.cooldownUntil > now) {
				throw new IllegalStateException("Gravel fixture precondition failed: activeStation=" + data.hasBuildProjectType(Blueprints.STATION)
					+ " wanted=" + data.wanted + " stock=" + existingGravel + " cooldown=" + state.cooldownUntil + " now=" + now);
			}
			List<String> quarryTrace = new ArrayList<>();
			for (int i = 0; i < 16 && level.getBlockState(free).is(Blocks.GRAVEL); i++) {
				state.target = free;
				boolean worked = GravelQuarry.work(context(level, ref, mason, state, now + i * 6L));
				quarryTrace.add(i + ":work=" + worked + ",block=" + level.getBlockState(free)
					+ ",target=" + state.target + ",cooldown=" + state.cooldownUntil + ",stage=" + Anim.breakingStage(mason));
			}
			check(level.getBlockState(free).isAir() && level.getBlockState(road).is(Blocks.GRAVEL),
				"visible breaking removes only the free gravel [free=" + level.getBlockState(free) + ", road="
					+ level.getBlockState(road) + ", inventory=" + mason.getInventory().getItem(0)
					+ ", cooldown=" + state.cooldownUntil + ", stage=" + Anim.breakingStage(mason)
					+ ", activity=" + state.activity + ", target=" + state.target + ", trace=" + quarryTrace + "]");
			int gained = JobUtil.count(mason.getInventory(), stack -> stack.is(Items.GRAVEL) || stack.is(Items.FLINT));
			check(gained == 1, "the shovel drop is exactly one gravel or flint");
		} finally {
			spawned.forEach(Entity::discard);
			data.buildProjects.clear();
			data.buildProjects.addAll(oldProjects);
			data.planType = oldPlanType;
			data.planOrigin = oldPlanOrigin;
			data.wanted.clear();
			data.wanted.addAll(oldWanted);
			ref.dirty();
		}
	}

	private static void workstation(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			VillageRef ref = Checks090.fixtureForEquipment(level, at);
			BlockPos table = at.north(2);
			column(level, table.below());
			level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
			BlockPos chest = ref.chestPos(level);
			for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) column(level, chest.relative(direction, 2).below());
			ref.chest(level).setItem(0, new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 8));
			var smith = villager(level, table.east(), spawned);
			JobState state = JobManager.state(smith);
			Ctx ctx = context(level, ref, smith, state);
			check(Workstation.ensure(ctx, Blocks.FURNACE), "smith crafts a furnace at the workbench");
			check(JobUtil.count(ref.chest(level), stack -> stack.is(Items.FURNACE)) == 1
				&& JobUtil.count(ref.chest(level), stack -> stack.is(Items.COBBLESTONE)) == 0, "eight cobblestone become one furnace");
			state.cooldownUntil = 0;
			check(Workstation.ensure(context(level, ref, smith, state), Blocks.FURNACE), "smith places the crafted furnace");
			check(Workstation.find(level, at, Blocks.FURNACE, 8) != null
				&& JobUtil.count(ref.chest(level), stack -> stack.is(Items.FURNACE)) == 0, "the furnace leaves the chest and stands in the village");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static void station(ServerLevel level, BlockPos at) {
		int rails = 0;
		boolean powered = false;
		boolean redstone = false;
		boolean roof = false;
		for (int x = 0; x <= 6; x++) {
			BlockPos floor = at.offset(x, -1, 0);
			column(level, floor);
			column(level, floor.south());
		}
		int placed = StationShed.furnish(level, new BoundingBox(at.getX(), at.getY(), at.getZ(), at.getX() + 6, at.getY() + 4, at.getZ() + 1));
		for (int x = 0; x <= 6; x++) {
			var state = level.getBlockState(at.offset(x, 0, 0));
			if (state.is(Blocks.RAIL) || state.is(Blocks.POWERED_RAIL)) rails++;
			if (state.is(Blocks.POWERED_RAIL)) powered = true;
			if (level.getBlockState(at.offset(x, 3, 0)).is(Blocks.SPRUCE_SLAB)) roof = true;
		}
		for (int x = 0; x <= 6; x++) if (level.getBlockState(at.offset(x, 0, 1)).is(Blocks.REDSTONE_BLOCK)) redstone = true;
		check(placed > 0 && rails >= 5 && powered && redstone && roof, "station shed lays covered powered track");
	}

	private static void sign(ServerLevel level, BlockPos at) {
		List<Entity> spawned = new ArrayList<>();
		try {
			column(level, at.below());
			level.setBlock(at, de.villagerplus.block.ModBlocks.DORFSCHILD.defaultBlockState(), 3);
			var resident = villager(level, at.south(2), spawned);
			resident.setVillagerData(resident.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER));
			VillagerMemory memory = resident.getAttachedOrCreate(VillagerPlus.MEMORY);
			memory.name = "Elsa";
			memory.homeDoor = Optional.of(at.south());
			JobManager.state(resident).activity = "backt Brot";
			String text = String.join("\n", de.villagerplus.block.DorfschildBlock.describe(level, at));
			check(text.contains("Hier wohnt:") && text.contains("Elsa") && text.contains("Bauer")
				&& text.contains("backt Brot") && text.contains("Stimmung"), "house sign lists name, role, status and mood");
		} finally {
			spawned.forEach(Entity::discard);
		}
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager villager, JobState state) {
		return context(level, ref, villager, state, level.getGameTime());
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager villager, JobState state, long now) {
		return new Ctx(level, villager, state, villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
	}

	private static Villager villager(ServerLevel level, BlockPos pos, List<Entity> spawned) {
		column(level, pos.below());
		var villager = EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);
		spawned.add(villager);
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		villager.addTag("vp090_fixture");
		villager.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		return villager;
	}

	private static void column(ServerLevel level, BlockPos floor) {
		level.getChunk(floor);
		level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
	}

	private static void captureFixtureCell(ServerLevel level, Map<BlockPos, FixtureCell> before, BlockPos pos) {
		BlockEntity entity = level.getBlockEntity(pos);
		before.putIfAbsent(pos.immutable(), new FixtureCell(level.getBlockState(pos), entity == null ? null
			: entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restoreFixtureCells(ServerLevel level, Map<BlockPos, FixtureCell> before) {
		for (Map.Entry<BlockPos, FixtureCell> entry : before.entrySet()) {
			BlockPos pos = entry.getKey();
			FixtureCell cell = entry.getValue();
			level.setBlock(pos, cell.state(), 2);
			if (cell.blockEntity() == null) {
				if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
			} else {
				BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			}
		}
	}

	private static List<net.minecraft.world.item.ItemStack> snapshotContainer(Container container) {
		List<net.minecraft.world.item.ItemStack> snapshot = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) snapshot.add(container.getItem(slot).copy());
		return snapshot;
	}

	private static void restoreContainer(Container container, List<net.minecraft.world.item.ItemStack> snapshot) {
		container.clearContent();
		for (int slot = 0; slot < Math.min(container.getContainerSize(), snapshot.size()); slot++) {
			container.setItem(slot, snapshot.get(slot).copy());
		}
		container.setChanged();
	}

	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("CHECK FAILED: " + label);
		checks++;
		System.out.println("VP090 PASS " + label);
	}
}
