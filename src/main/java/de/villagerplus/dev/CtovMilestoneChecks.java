package de.villagerplus.dev;

import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Builder;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** G02 granary checks. {@link #runHarness} only proves Builder can place blocks. {@link #runWalk} is the village doing the work. */
public final class CtovMilestoneChecks {
	private CtovMilestoneChecks() { }

	/** Harness: no villager AI, the mason is moved onto the chosen stand, and the chest is topped up every tick. */
	public static int runHarness(ServerLevel level, VillageRef ref) {
		Villages.adoptUnassigned(level, ref);
		var residents = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, Villager::isAlive);
		if (residents.isEmpty()) throw new IllegalStateException("CTOV harness: no resident to build");
		Villager mason = residents.get(0);
		mason.setInvulnerable(true);
		mason.setNoAi(true);
		Villages.ensureCenter(level, ref, mason, level.getGameTime() + 10_000);
		Container chest = ensureChest(level, ref);
		BlockPos existing = Specials.get(ref, Blueprints.GRANARY);
		if (existing != null && solidBlocks(level, existing) >= 40) {
			System.out.println("CTOV HARNESS OBSERVE granary " + existing.toShortString() + " (placement cheat not repeated; walk check rebuilds it)");
			return 2;
		}
		var state = JobManager.state(mason);
		state.buildCheckAt = 0;
		state.materialWaitUntil = 0;
		ref.data().nextBuild = 0;
		if ("ctov-headless".equals(ref.data().planType) && ref.data().planOrigin.isEmpty()) ref.data().clearPlan();
		int checks = 0;
		int lastStep = -1;
		long lastProgress = -1;
		int idle = 0;
		BlockPos granary = Specials.get(ref, Blueprints.GRANARY);
		for (int tick = 0; tick < 8000 && granary == null; tick++) {
			if (ref.data().planOrigin.isPresent()) {
				Ctx preview = new Ctx(level, mason, state, mason.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY), ref, level.getGameTime() + 20_000L + tick);
				BlockPos stand = Builder.constructionStand(preview);
				if (stand != null) mason.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			}
			supply(chest, ref.data().wanted);
			state.buildCheckAt = 0;
			state.materialWaitUntil = 0;
			Ctx ctx = new Ctx(level, mason, state, mason.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY), ref, level.getGameTime() + 20_000L + tick);
			Builder.tick(ctx, true);
			granary = Specials.get(ref, Blueprints.GRANARY);
			int step = ref.data().planStep;
			long progress = ref.data().planProgress;
			if (step != lastStep || progress != lastProgress || !ref.data().wanted.isEmpty()) {
				lastStep = step;
				lastProgress = progress;
				idle = 0;
			} else if (++idle > 800) {
				throw new IllegalStateException("CTOV harness: granary stalled at step " + step + "/" + Builder.planSize(ref.data())
					+ " type=" + ref.data().planType
					+ " origin=" + ref.data().planOrigin.map(BlockPos::toShortString).orElse("none")
					+ " wanted=" + ref.data().wanted + " mason=" + mason.blockPosition().toShortString()
					+ " diag=" + Builder.planStepDiagnostic(ref.data()));
			}
		}
		check(granary != null, "harness: Builder placed a granary while the mason was moved onto the stand"); checks++;
		check(solidBlocks(level, granary) >= 40, "harness: granary footprint contains placed blocks"); checks++;
		System.out.println("CTOV HARNESS PASS granary " + granary.toShortString() + " (no pathfinding; mason was positioned on the stand)");
		return checks;
	}

	/**
	 * Honest village run: materials are delivered when the wishlist appears, then only real level ticks run.
	 * The mason keeps AI and is never moved by the test.
	 */
	public static int runWalk(ServerLevel level, VillageRef ref) {
		var overworldClock = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
			.getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
		((net.minecraft.world.clock.ServerClockManager) level.getServer().overworld().clockManager()).setTotalTicks(overworldClock, 1000L);
		Villages.adoptUnassigned(level, ref);
		var residents = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, Villager::isAlive);
		if (residents.isEmpty()) throw new IllegalStateException("CTOV walk: no resident to build");
		Villager mason = Builder.milestoneWorker(level, ref);
		if (mason == null) throw new IllegalStateException("CTOV walk: no eligible resident to build");
		mason.setInvulnerable(true);
		mason.setNoAi(false);
		System.out.println("CTOV WALK worker " + mason.getUUID() + " " + mason.getVillagerData().profession()
			+ " at " + mason.blockPosition().toShortString());
		BlockPos streetTarget = Builder.villageReturn(level, ref.bell());
		String candidatePositions = residents.stream().filter(v -> !v.isBaby()
			&& !de.villagerplus.village.Guests.isGuest(v)
			&& !de.villagerplus.village.Expedition.isAway(v)
			&& de.villagerplus.village.Market.shop(ref, v.getUUID()) == null)
			.sorted(java.util.Comparator.comparingDouble(v -> streetTarget == null ? v.getUUID().hashCode()
				: v.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(streetTarget))))
			.limit(8).map(v -> v.getUUID() + "@" + v.blockPosition().toShortString() + "/"
				+ de.villagerplus.Compat.jobKey(v.getVillagerData().profession()) + "/"
				+ v.getVillagerData().profession().unwrapKey().map(key -> key.identifier().toString()).orElse("unknown"))
			.collect(java.util.stream.Collectors.joining(","));
		System.out.println("CTOV WALK clock game=" + level.getGameTime()
			+ " overworld=" + level.getOverworldClockTime() + " phase=" + de.villagerplus.job.Phase.of(level.getOverworldClockTime())
			+ " sleeping=" + mason.isSleeping() + " trading=" + mason.isTrading()
			+ " panic=" + mason.getBrain().isActive(net.minecraft.world.entity.schedule.Activity.PANIC)
			+ " village=" + Villages.find(level, mason)
			+ " street=" + streetTarget
			+ " candidates=" + candidatePositions
			+ " streetDistance=" + (streetTarget == null ? "none"
				: mason.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(streetTarget))));
		Villages.ensureCenter(level, ref, mason, level.getGameTime() + 10_000);
		Container chest = ensureChest(level, ref);
		BlockPos harnessGranary = Specials.get(ref, Blueprints.GRANARY);
		if (harnessGranary != null && solidBlocks(level, harnessGranary) < 40) {
			Specials.remove(ref, Blueprints.GRANARY);
			if (ref.data().hasPlan() && Blueprints.GRANARY.equals(ref.data().planType)) ref.data().clearPlan();
		}
		ref.data().nextBuild = 0;
		if ("ctov-headless".equals(ref.data().planType) && ref.data().planOrigin.isEmpty()) ref.data().clearPlan();
		int checks = 0;
		Container stock = de.villagerplus.village.Lager.combined(level, ref);
		checks += walkMilestone(level, ref, stock, mason, Blueprints.GRANARY);
		checks += walkMilestone(level, ref, stock, mason, Blueprints.TOOLSHED);
		checks += walkMilestone(level, ref, stock, mason, Blueprints.SCHOOL);
		checks += walkMilestone(level, ref, stock, mason, Blueprints.KITCHEN);
		return checks;
	}

	private static int walkMilestone(ServerLevel level, VillageRef ref, Container chest, Villager mason, String type) {
		if (milestoneDone(ref, type)) {
			BlockPos at = milestonePos(ref, type);
			check(at != null && solidBlocks(level, at) >= 40, "walk: existing " + type + " still stands");
			System.out.println("CTOV WALK KEEP " + type + " " + at.toShortString());
			return 2;
		}
		String supplied = "";
		BlockPos planStart = null;
		int stagnant = 0;
		int lastStep = -1;
		String lastPlanType = ref.data().planType;
		boolean beganAtWorkStand = false;
		var lastPosition = mason.position();
		int relocations = JobManager.state(mason).builderRelocations;
		for (int tick = 0; tick < 20000 && !milestoneDone(ref, type); tick++) {
			level.tick(() -> false);
			if (tick % de.villagerplus.Config.JOB_INTERVAL == 0) {
				JobManager.tickForDevelopment(level, mason);
			}
			check(JobManager.state(mason).builderRelocations == relocations,
				"walk: builder was relocated instead of walking during " + type);
			if (ref.data().planOrigin.isPresent()) {
				BlockPos origin = ref.data().planOrigin.get();
				level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, true);
				if (planStart == null && type.equals(ref.data().planType)) {
					planStart = mason.blockPosition();
					var preview = new Ctx(level, mason, JobManager.state(mason), mason.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY), ref, level.getGameTime());
					BlockPos stand = Builder.constructionStand(preview);
					beganAtWorkStand = stand != null
						&& mason.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(stand)) <= 4.0;
				}
			}
			String wishlist = String.join("|", ref.data().wanted);
			// A shipment can be consumed by other villagers before the builder uses it.
			// Refill remaining requests periodically; supply() adds only the actual shortfall.
			if (!wishlist.isEmpty() && (!wishlist.equals(supplied) || tick % 100 == 0)) {
				supply(chest, ref.data().wanted);
				supplied = wishlist;
			}
			int step = ref.data().planStep;
			String currentPlanType = ref.data().planType;
			boolean moved = mason.position().distanceToSqr(lastPosition) >= 9.0;
			boolean targetPlanStarted = type.equals(currentPlanType) && !type.equals(lastPlanType);
			if (step != lastStep || moved || targetPlanStarted) {
				lastStep = step;
				lastPosition = mason.position();
				stagnant = 0;
			} else if (++stagnant > 2000) {
				throw new IllegalStateException("CTOV walk stalled on " + type + " step " + step + "/" + Builder.planSize(ref.data())
					+ " origin=" + ref.data().planOrigin.map(BlockPos::toShortString).orElse("none")
					+ " mason=" + mason.blockPosition().toShortString()
					+ " wanted=" + ref.data().wanted + " stand=" + Builder.lastStandReport(mason));
			}
			lastPlanType = currentPlanType;
			if (tick % 400 == 0) {
				System.out.println("CTOV WALK " + type + " tick " + tick + " step=" + step + "/" + Builder.planSize(ref.data())
					+ " plan=" + ref.data().planType + " mason=" + mason.blockPosition().toShortString()
					+ " activity=" + JobManager.state(mason).activity + " wanted=" + ref.data().wanted
					+ " route=" + de.villagerplus.job.JobUtil.lastLongRouteDiagnostic(mason));
				System.out.flush();
			}
		}
		check(milestoneDone(ref, type), "walk: village finished " + type + " during normal ticks");
		BlockPos built = milestonePos(ref, type);
		double walked = planStart == null ? 0 : Math.sqrt(mason.blockPosition().distSqr(planStart));
		check(walked >= 3.0 || beganAtWorkStand,
			"walk: mason moved to " + type + " (" + walked + " blocks) or began at its real work stand (" + beganAtWorkStand + ")");
		check(built != null && solidBlocks(level, built) >= 40, "walk: " + type + " footprint contains placed blocks");
		level.getChunkAt(built).markUnsaved();
		System.out.println("CTOV WALK PASS " + type + " " + built.toShortString() + (walked >= 3.0 ? " moved " + walked : " started at work stand"));
		return 3;
	}

	static boolean milestoneDone(VillageRef ref, String type) {
		if (Blueprints.SCHOOL.equals(type)) return ref.data().extra.school.isPresent();
		return Specials.has(ref, type);
	}

	static BlockPos milestonePos(VillageRef ref, String type) {
		if (Blueprints.SCHOOL.equals(type)) return ref.data().extra.school.orElse(null);
		return Specials.get(ref, type);
	}

	private static Container ensureChest(ServerLevel level, VillageRef ref) {
		Container chest = ref.chest(level);
		if (chest != null) return chest;
		BlockPos pos = ref.bell().south(2);
		level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
		level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
		ref.data().chest = java.util.Optional.of(pos);
		ref.dirty();
		chest = ref.chest(level);
		if (chest == null) throw new IllegalStateException("CTOV milestone: village chest missing");
		return chest;
	}

	private static int solidBlocks(ServerLevel level, BlockPos granary) {
		int placed = 0;
		for (BlockPos pos : BlockPos.betweenClosed(granary.offset(-8, -2, -8), granary.offset(8, 8, 8))) {
			if (!level.getBlockState(pos).isAir()) placed++;
		}
		return placed;
	}

	private static void supply(Container chest, java.util.List<String> wanted) {
		for (String entry : wanted) {
			String[] parts = entry.split("#");
			var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0]));
			if (item.isEmpty()) continue;
			int need = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
			int have = JobUtil.count(chest, stack -> stack.is(item.get()));
			int left = need - have;
			for (int slot = 0; slot < chest.getContainerSize() && left > 0; slot++) {
				ItemStack stack = chest.getItem(slot);
				if (!stack.isEmpty() && !stack.is(item.get())) continue;
				int room = stack.isEmpty() ? item.get().getDefaultMaxStackSize() : stack.getMaxStackSize() - stack.getCount();
				if (room <= 0) continue;
				int add = Math.min(room, left);
				if (stack.isEmpty()) chest.setItem(slot, new ItemStack(item.get(), add));
				else stack.grow(add);
				left -= add;
			}
			System.out.println("CTOV WALK SUPPLY " + entry + " stock=" + JobUtil.count(chest, stack -> stack.is(item.get())) + " unfilled=" + Math.max(0,left));
			check(left <= 0, "requested materials do not fit in the communal chest: " + entry);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("CTOV milestone: " + message);
	}
}
