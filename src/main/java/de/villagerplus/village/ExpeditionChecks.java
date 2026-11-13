package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.ButcherChecks;
import de.villagerplus.job.Skills;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Synthetic multi-tick checks for the adventurer loop in {@link Expedition}: the periodic timer and the minimum
 * crew/pool rules, departure (LEAVING walk to the real exit point, then parked as away), the away guards, the return
 * (stats, damage, experience, log, RETURNING walk home), the loot accounting (every rolled item is either stored in the
 * public village chest or left in front of it, never lost) and the fallen branch without loot. Real level ticks run the
 * walk handler between the call steps; only the walking itself is replaced by teleports because the fixture residents
 * have no AI. The group returns -1 while it waits for those ticks. Synthetic fixture: no natural evidence.
 */
public final class ExpeditionChecks {
	private ExpeditionChecks() { }

	private static final class Run {
		BlockPos chunkOrigin;
		BlockPos ticketPos;
		VillageRef ref;
		final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		final List<Villager> villagers = new ArrayList<>();
		Villager crew;
		String ids;
		long end;
		int dest;
		BlockPos exit;
		int phase;
		int checks;
		long clockAtWait;
	}

	private static Run run;

	/** @param chunkOrigin chunk aligned origin; returns -1 until the real walk ticks have processed the current step */
	public static int run(ServerLevel level, BlockPos chunkOrigin) throws Exception {
		try {
			if (run == null) {
				if (!ButcherChecks.prepareEntityTickingFixture(level, chunkOrigin.offset(8, 0, 8))) return -1;
				run = new Run();
				run.chunkOrigin = chunkOrigin;
				run.ticketPos = chunkOrigin.offset(8, 0, 8);
				setupAndDepart(level, run);
				return -1;
			}
			Integer result = step(level, run);
			if (result != null) {
				int checks = result;
				cleanup(level);
				return checks;
			}
			return -1;
		} catch (Throwable failure) {
			cleanup(level);
			throw failure;
		}
	}

	private static void setupAndDepart(ServerLevel level, Run r) throws Exception {
		BlockPos origin = r.chunkOrigin;
		level.getChunkAt(origin);
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState stone = Blocks.STONE.defaultBlockState();
		for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) for (int dy = -2; dy <= 5; dy++) {
			BlockPos p = origin.offset(dx, dy, dz);
			r.before.put(p, level.getBlockState(p));
			level.removeBlockEntity(p);
			level.setBlock(p, dy < 0 ? stone : air, 3);
		}
		r.ref = Checks090.fixtureForEquipment(level, origin);
		VillageRef ref = r.ref;
		VillageData.Extra ex = ref.data().extra;
		var librarian = ResourceKey.create(Registries.VILLAGER_PROFESSION, Identifier.withDefaultNamespace("librarian"));
		for (int i = 0; i < 5; i++) r.villagers.add(resident(level, ref, origin.offset(4 + i, 0, 5), librarian));
		long now = level.getGameTime();

		// Fewer than six residents: no expedition, but the timer is pushed out.
		check(r, !Expedition.debugStart(level, ref) && ex.expActive.isEmpty() && ex.expNext > now,
			"five residents are too few for an expedition and the timer is rescheduled");
		check(r, r.villagers.stream().noneMatch(Expedition::isAway), "nobody is away after the refused start");
		r.villagers.add(resident(level, ref, origin.offset(9, 0, 5), librarian));

		// The periodic path: timer not due -> nothing; due -> an expedition starts.
		long scheduled = ex.expNext;
		Expedition.tick(level, ref, now);
		check(r, ex.expActive.isEmpty() && ex.expNext == scheduled, "before the timer is due the periodic tick does nothing");
		ex.expNext = now;
		Expedition.tick(level, ref, now);
		check(r, ex.expActive.isPresent(), "a due timer with six residents starts an expedition");
		check(r, ex.expNext >= now + Config.EXPEDITION_INTERVAL, "the next expedition is scheduled one interval later");
		String[] parts = ex.expActive.get().split(";");
		r.ids = parts[0];
		r.end = Long.parseLong(parts[1]);
		r.dest = Integer.parseInt(parts[2]);
		check(r, !r.ids.contains(",") && r.end >= now + Config.EXPEDITION_TICKS && r.dest >= 0 && r.dest <= 2,
			"six residents send exactly one adventurer for the configured duration to a valid destination");
		UUID crewId = UUID.fromString(r.ids);
		r.crew = r.villagers.stream().filter(v -> v.getUUID().equals(crewId)).findFirst().orElseThrow();
		check(r, r.crew.entityTags().contains(Expedition.LEAVING) && Expedition.isAway(r.crew) && r.crew.isInvulnerable()
			&& r.villagers.stream().filter(Expedition::isAway).count() == 1 && stat(ex, 0) == 1 && walks().containsKey(crewId),
			"the adventurer is marked as leaving, protected and tracked by the walk handler; nobody else is touched");
		// The exit point is fixed when the walk is created; read it from the walk record (the loaded neighbourhood grows later).
		Object walk = walks().get(crewId);
		Method target = walk.getClass().getDeclaredMethod("target");
		target.setAccessible(true);
		r.exit = (BlockPos) target.invoke(walk);
		long dxExit = r.exit.getX() - ref.bell().getX(), dzExit = r.exit.getZ() - ref.bell().getZ();
		check(r, dxExit * dxExit + dzExit * dzExit >= 16L * 16L, "the exit point lies at least 16 blocks from the bell (" + r.exit.toShortString() + ")");
		check(r, !Expedition.debugStart(level, ref), "no second expedition starts while one is active");
		String active = ex.expActive.get();
		Expedition.tick(level, ref, now + 1);
		check(r, ex.expActive.isPresent() && ex.expActive.get().equals(active) && stat(ex, 1) == 0,
			"an active expedition is not finished before its end time");
		check(r, !Staffing.reassignIdle(level, ref, r.crew), "an away adventurer is not reassigned by the idle job rules");
		r.phase = 1;
	}

	private static Integer step(ServerLevel level, Run r) throws Exception {
		VillageRef ref = r.ref;
		VillageData.Extra ex = ref.data().extra;
		long now = level.getGameTime();
		switch (r.phase) {
			case 1 -> {
				// Wait for the real walk handler (every 10 ticks) to aim the leaving adventurer at the exit point.
				var target = r.crew.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
				if (target.isEmpty()) return null;
				check(r, target.get().getTarget().currentBlockPosition().equals(r.exit),
					"the walk handler aims the leaving adventurer at that exit point");
				r.crew.teleportTo(r.exit.getX() + 0.5, r.exit.getY(), r.exit.getZ() + 0.5);
				r.phase = 2;
				return null;
			}
			case 2 -> {
				if (r.crew.entityTags().contains(Expedition.LEAVING)) return null;
				check(r, r.crew.entityTags().contains(Expedition.TAG) && Expedition.isAway(r.crew) && r.crew.isInvisible()
					&& r.crew.isNoAi() && r.crew.isInvulnerable() && r.crew.isSilent() && r.crew.isNoGravity()
					&& r.crew.getY() >= level.getMaxY() - 4 && !walks().containsKey(r.crew.getUUID()),
					"on arrival at the exit the adventurer disappears: parked, invisible, away, walk finished");
				check(r, Expedition.lines(level, ref, now).stream().anyMatch(line -> line.startsWith("Unterwegs nach")),
					"the village board lists the active expedition");
				// Finish now; the adventurer must survive so the return branch is deterministic.
				r.crew.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
				r.crew.setHealth(1000.0F);
				VillagerMemory mem = r.crew.getAttachedOrCreate(VillagerPlus.MEMORY);
				int jobsBefore = mem.jobsDone;
				Round round = finishNow(level, r, now);
				check(r, ex.expActive.isEmpty() && stat(ex, 0) == 1 && stat(ex, 1) == 1 && stat(ex, 2) == 0,
					"finishing counts one start, one returned, none fallen");
				check(r, !r.crew.entityTags().contains(Expedition.TAG) && r.crew.entityTags().contains(Expedition.RETURNING)
					&& !r.crew.isInvisible() && !r.crew.isNoAi() && !r.crew.isInvulnerable() && !r.crew.isSilent() && !r.crew.isNoGravity()
					&& walks().containsKey(r.crew.getUUID()) && r.crew.getY() < level.getMaxY() - 40,
					"the returned adventurer is visible again, vulnerable, back at the village edge and walking home");
				check(r, r.crew.getHealth() < 1000.0F && r.crew.getHealth() > 0.0F && mem.jobsDone == jobsBefore + 5 * (r.dest + 1),
					"the journey cost health and gave experience by danger tier");
				check(r, !ex.expLog.isEmpty() && ex.expLog.getLast().contains("zurück:") && !ex.expLog.getLast().contains("nicht zurückgekehrt"),
					"the expedition report lists the returned adventurer");
				check(r, round.loot >= 2 && round.chestGain + round.groundGain == round.loot && stat(ex, 3) == round.loot,
					"every rolled loot item is accounted for in the village (chest " + round.chestGain + " + in front of it " + round.groundGain
						+ " = " + round.loot + ")");
				check(r, round.ground.stream().noneMatch(s -> StockPolicy.acceptsMaterialStoreItem(s.getItem())),
					"loot the public chest accepts never stays outside it (outside: " + round.ground + ")");
				r.crew.teleportTo(ref.bell().getX() + 3.5, ref.bell().getY(), ref.bell().getZ() + 0.5);
				r.phase = 3;
				return null;
			}
			case 3 -> {
				if (r.crew.entityTags().contains(Expedition.RETURNING)) return null;
				check(r, !Expedition.isAway(r.crew) && !walks().containsKey(r.crew.getUUID()),
					"arriving at the village chest ends the return walk: the adventurer is a resident again");
				// Further rounds: at least one real item must land in the public chest.
				int chestTotal = 0;
				int rounds = 0;
				for (; rounds < 14 && chestTotal == 0; rounds++) {
					for (Villager v : r.villagers) {
						v.removeTag(Expedition.RETURNING);
						v.teleportTo(r.chunkOrigin.getX() + 6.5, r.chunkOrigin.getY(), r.chunkOrigin.getZ() + 6.5);
						v.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
						v.setHealth(1000.0F);
					}
					Villages.noteChunk(level.getChunkAt(ref.bell()));
					ex.expNext = level.getGameTime();
					Expedition.tick(level, ref, level.getGameTime());
					check(r, ex.expActive.isPresent(), "another expedition can start once the earlier crew is home");
					Round round = finishNow(level, r, level.getGameTime());
					check(r, round.chestGain + round.groundGain == round.loot, "round loot is conserved");
					chestTotal += round.chestGain;
				}
				check(r, chestTotal > 0, "expedition loot reached the public village chest within " + rounds + " rounds");

				// The fallen branch: a crew member with almost no health does not come back and brings no loot.
				for (Villager v : r.villagers) {
					v.removeTag(Expedition.RETURNING);
					v.teleportTo(r.chunkOrigin.getX() + 6.5, r.chunkOrigin.getY(), r.chunkOrigin.getZ() + 6.5);
					v.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
					v.setHealth(20.0F);
				}
				ex.expNext = level.getGameTime();
				Expedition.tick(level, ref, level.getGameTime());
				check(r, ex.expActive.isPresent(), "a further expedition starts for the fallen branch");
				UUID doomed = UUID.fromString(ex.expActive.get().split(";")[0]);
				Villager victim = r.villagers.stream().filter(v -> v.getUUID().equals(doomed)).findFirst().orElseThrow();
				victim.setHealth(0.01F);
				int lootBefore = stat(ex, 3);
				int fallenBefore = stat(ex, 2);
				Round round = finishNow(level, r, level.getGameTime());
				check(r, stat(ex, 2) == fallenBefore + 1 && !victim.isAlive() && stat(ex, 3) == lootBefore && round.loot == 0
					&& round.chestGain == 0 && round.groundGain == 0,
					"a fallen adventurer is dead, is counted as fallen and brings no loot");
				check(r, ex.expLog.getLast().contains("nicht zurückgekehrt") && ex.expLog.getLast().contains("niemand")
					&& ex.expActive.isEmpty() && ex.expLog.size() <= 6, "the report names the fallen and the log stays bounded");
				return r.checks;
			}
			default -> throw new IllegalStateException("EXPEDITION CHECK FAILED: unknown phase " + r.phase);
		}
	}

	private record Round(int loot, int chestGain, int groundGain, List<ItemStack> ground) { }

	/** Ends the active expedition now (the end time is set to the current tick) and accounts for the loot it produced. */
	private static Round finishNow(ServerLevel level, Run r, long now) {
		VillageRef ref = r.ref;
		VillageData.Extra ex = ref.data().extra;
		String[] parts = ex.expActive.orElseThrow().split(";");
		ex.expActive = Optional.of(parts[0] + ";" + now + ";" + parts[2]);
		BlockPos chestPos = ref.chestPos(level);
		AABB spill = new AABB(chestPos).inflate(4.0, 3.0, 4.0);
		level.getEntitiesOfClass(ItemEntity.class, spill).forEach(Entity::discard);
		int chestBefore = ChestSum.total(ref.chest(level));
		int lootBefore = stat(ex, 3);
		Expedition.tick(level, ref, now);
		int loot = stat(ex, 3) - lootBefore;
		int chestGain = ChestSum.total(ref.chest(level)) - chestBefore;
		List<ItemEntity> onGround = level.getEntitiesOfClass(ItemEntity.class, spill, ItemEntity::isAlive);
		int groundGain = onGround.stream().mapToInt(item -> item.getItem().getCount()).sum();
		List<ItemStack> ground = onGround.stream().map(item -> item.getItem().copy()).toList();
		onGround.forEach(Entity::discard);
		return new Round(loot, chestGain, groundGain, ground);
	}

	private static final class ChestSum {
		static int total(net.minecraft.world.Container container) {
			return de.villagerplus.job.JobUtil.count(container, stack -> true);
		}
	}

	private static Villager resident(ServerLevel level, VillageRef ref, BlockPos at, ResourceKey<VillagerProfession> profession) {
		Villager villager = EntityTypes.VILLAGER.spawn(level, at, EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("EXPEDITION CHECK FAILED: could not spawn resident");
		villager.addTag("vp090_fixture");
		villager.setNoAi(true);
		villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), profession).withLevel(2));
		villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
		villager.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
		villager.setHealth(1000.0F);
		return villager;
	}

	private static int stat(VillageData.Extra ex, int index) {
		return ex.expStats.size() > index ? ex.expStats.get(index) : 0;
	}

	@SuppressWarnings("unchecked")
	private static Map<UUID, Object> walks() throws Exception {
		Field f = Expedition.class.getDeclaredField("WALKS");
		f.setAccessible(true);
		return (Map<UUID, Object>) f.get(null);
	}

	private static void check(Run r, boolean ok, String label) {
		if (!ok) throw new IllegalStateException("EXPEDITION CHECK FAILED: " + label);
		r.checks++;
	}

	private static void cleanup(ServerLevel level) {
		Run r = run;
		run = null;
		if (r == null) return;
		try {
			Map<UUID, Object> walks = walks();
			for (Villager v : r.villagers) walks.remove(v.getUUID());
		} catch (Exception ignored) {
			// reflection is only a dev aid
		}
		for (Villager v : r.villagers) {
			v.discard();
			Villages.forget(v.getUUID());
		}
		if (r.ref != null) {
			BlockPos chestPos = r.ref.chestPos(level);
			if (chestPos != null) level.getEntitiesOfClass(ItemEntity.class, new AABB(chestPos).inflate(6.0, 4.0, 6.0)).forEach(Entity::discard);
		}
		r.before.keySet().forEach(level::removeBlockEntity);
		r.before.forEach((p, s) -> level.setBlock(p, s, 3));
		ButcherChecks.releaseEntityTickingFixture(level, r.ticketPos);
	}
}
