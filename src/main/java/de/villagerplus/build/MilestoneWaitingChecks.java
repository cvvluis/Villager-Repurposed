package de.villagerplus.build;

import com.mojang.serialization.JsonOps;
import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.Development;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Exercises the real builder material-wait branch before the school milestone is available. */
public final class MilestoneWaitingChecks {
	private MilestoneWaitingChecks() { }

	private record Plan(String type, Optional<BlockPos> origin, int dir, int step, int variant,
		Optional<BlockPos> source, long progress, long nextBuild, List<String> wanted,
		Optional<VillageData.Extra.BuilderPlan> owner, Optional<String> materialWait) {
		static Plan of(VillageData d) {
			return new Plan(d.planType, d.planOrigin, d.planDir, d.planStep, d.planVariant, d.planSource,
				d.planProgress, d.nextBuild, List.copyOf(d.wanted),
				d.extra.planBuilder.map(p -> new VillageData.Extra.BuilderPlan(p.owner,p.unavailableSince)), d.extra.planMaterialWait);
		}
		void restore(VillageData d) {
			d.planType = type; d.planOrigin = origin; d.planDir = dir; d.planStep = step; d.planVariant = variant;
			d.planSource = source; d.planProgress = progress; d.nextBuild = nextBuild;
			d.wanted.clear(); d.wanted.addAll(wanted); d.extra.planBuilder = owner; d.extra.planMaterialWait = materialWait;
		}
	}
	private record FixtureBlock(BlockPos pos, BlockState state) { }
	private record FixtureCell(BlockState state, CompoundTag data) { }

	public static int run(ServerLevel level, VillageRef ref) {
		Container box = ref.chest(level);
		if (box == null) throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: village chest missing");
		VillageData d = ref.data();
		Plan oldPlan = Plan.of(d);
		var oldSchool = d.extra.school;
		var oldLager = d.extra.lager;
		var oldLagerMore = new ArrayList<>(d.extra.lagerMore);
		var oldLagerChests = new ArrayList<>(d.extra.lagerChests);
		var oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		var oldDemolition = ref.bellEntity().getAttached(VillagerPlus.DEMOLITION);
		var oldConstruction = ref.bellEntity().getAttached(VillagerPlus.CONSTRUCTION);
		List<ItemStack> stock = new ArrayList<>();
		for (int slot = 0; slot < box.getContainerSize(); slot++) stock.add(box.getItem(slot).copy());
		List<Villager> workers = new ArrayList<>();
		BlockPos origin = ref.bell().offset(-13, 0, 12);
		level.getChunkAt(origin);
		List<FixtureBlock> fixtureBlocks = new ArrayList<>();
		Map<BlockPos, FixtureCell> fixtureSite = new LinkedHashMap<>();
		BlockPos[] workerSpot = new BlockPos[1];
		long now = Math.max(level.getGameTime(), 1_000_000L);
		int checks = 0;
		try {
			workerSpot[0] = prepareFixtureSurface(level, ref.bell(), origin, fixtureBlocks);
			d.extra.school = Optional.empty(); d.extra.lager = Optional.empty();
			d.extra.lagerMore.clear(); d.extra.lagerChests.clear();
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, java.util.Map.of());
			ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);
			ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
			box.clearContent();
			for (int i = 0; i < Config.SCHOOL_POP; i++) {
				Villager villager = EntityTypes.VILLAGER.spawn(level, ref.bell().offset(4 + i, 0, 7), EntitySpawnReason.COMMAND);
				if (villager == null) throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: resident fixture did not spawn");
				workers.add(villager); villager.addTag("vp090_fixture"); villager.setNoAi(true); villager.setInvulnerable(true); villager.setAge(0);
				villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
				villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), ref.bell().north(12)));
			}
			Villager worker = workers.getFirst();
			worker.setPos(workerSpot[0].getX() + 0.5, workerSpot[0].getY(), workerSpot[0].getZ() + 0.5);
			Ctx c = new Ctx(level, worker, JobManager.state(worker), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
			snapshotPlanSite(level, ref.bell(), worker, origin, fixtureSite);
			check(Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> true).size() >= Config.SCHOOL_POP,
				"world fixture reaches the population that used to trigger premature school preemption"); checks++;

			waitingPlan(d, Blueprints.GRANARY, origin, now);
			int granaryWaitStep = tickUntilMaterialWait(level, ref, worker, c, now);
			check(granaryWaitStep >= 0 && Blueprints.GRANARY.equals(d.planType) && d.planOrigin.equals(Optional.of(origin)) && d.planStep == granaryWaitStep
				&& actionable(d) && box.isEmpty(),
				"missing granary materials preserve the prerequisite plan and release the cycle for suppliers"
					+ " [waitStep=" + granaryWaitStep + ", type=" + d.planType + ", step=" + d.planStep + ", activity=" + c.st().activity
					+ ", position=" + worker.blockPosition().toShortString() + ", wanted=" + d.wanted
					+ ", projects=" + d.buildProjects + ", actionable=" + actionable(d) + ", boxEmpty=" + box.isEmpty() + "]"); checks++;
			Builder.cancel(ref, now);

			Specials.set(ref, Blueprints.GRANARY, ref.bell().north(20));
			waitingPlan(d, Blueprints.TOOLSHED, origin, now + 200);
			c.st().materialWaitUntil = 0;
			Ctx toolshed = new Ctx(level, worker, c.st(), c.mem(), ref, now + 200);
			int toolshedWaitStep = tickUntilMaterialWait(level, ref, worker, toolshed, now + 200);
			check(toolshedWaitStep >= 0 && Blueprints.TOOLSHED.equals(d.planType) && d.planOrigin.equals(Optional.of(origin)) && d.planStep == toolshedWaitStep
				&& actionable(d) && box.isEmpty(),
				"missing toolshed materials release the cycle while retaining the prerequisite plan"
					+ " [waitStep=" + toolshedWaitStep + ", type=" + d.planType + ", step=" + d.planStep + ", activity=" + toolshed.st().activity
					+ ", position=" + worker.blockPosition().toShortString() + ", wanted=" + d.wanted + "]"); checks++;
			// Dispatch continues from the tick that reached the wait state, so the timed stock scan is not re-armed.
			// The public-stock scan is deliberately paged over several ticks; the resident must be released once it is done.
			boolean bootstrapBusy = true;
			long dispatchTick = lastWaitTick;
			for (int dispatch = 0; dispatch < 400 && bootstrapBusy; dispatch++) {
				dispatchTick++;
				bootstrapBusy = Builder.bootstrapBuildWork(new Ctx(level, worker, c.st(), c.mem(), ref, dispatchTick));
			}
			check(!bootstrapBusy && d.planStep == toolshedWaitStep && actionable(d),
				"when no source can advance the shortage, bootstrap dispatch releases the resident to its normal profession cycle"
					+ " [busy=" + bootstrapBusy + ", step=" + d.planStep + "/" + toolshedWaitStep + ", actionable=" + actionable(d)
					+ ", activity=" + toolshed.st().activity + ", wanted=" + d.wanted + ", owner=" + d.planBuilder()
					+ ", worker=" + worker.getUUID() + ", projects=" + d.buildProjects.size() + "]"); checks++;
			List<String> toolshedDemand = List.copyOf(d.wanted);
			d.wanted.clear(); d.wanted.add("minecraft:oak_planks#4");
			d.claimPlanBuilder(worker.getUUID());
			box.setItem(0, new ItemStack(Items.OAK_LOG, 1));
			BlockPos chestPos = ref.chestPos(level);
			worker.setPos(chestPos.getX() + 2.5, chestPos.getY(), chestPos.getZ() + 0.5);
			boolean supplierCrafted = Builder.supplyMissing(toolshed);
			check(supplierCrafted && JobUtil.count(box, stack -> stack.is(Items.OAK_PLANKS)) == 4
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_LOG)) == 0 && d.planStep == toolshedWaitStep,
				"owned plan supplier crafts requested planks from a real log without advancing the saved construction cursor"
					+ " [crafted=" + supplierCrafted + ", planks=" + JobUtil.count(box, stack -> stack.is(Items.OAK_PLANKS))
					+ ", logs=" + JobUtil.count(box, stack -> stack.is(Items.OAK_LOG)) + ", step=" + d.planStep + "]"); checks++;
			d.wanted.clear(); d.wanted.add("minecraft:oak_fence#1");
			box.clearContent(); box.setItem(0, new ItemStack(Items.OAK_LOG, 1));
			List<Boolean> recipeWork = new ArrayList<>();
			recipeWork.add(Builder.supplyMissing(new Ctx(level, worker, c.st(), c.mem(), ref, now + 220)));
			check(JobUtil.count(box, stack -> stack.is(Items.OAK_LOG)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_PLANKS)) == 4
				&& JobUtil.count(box, stack -> stack.is(Items.STICK)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE)) == 0
				&& d.planStep == toolshedWaitStep && d.planBuilder().filter(worker.getUUID()::equals).isPresent(),
				"one real log yields only the planks that the blocked fence still needs; the owner and cursor stay saved"); checks++;
			c.st().woodCheckAt = now + 1_200;
			boolean oneLogRetry = Builder.supplyMissing(new Ctx(level, worker, c.st(), c.mem(), ref, now + 221));
			recipeWork.add(oneLogRetry);
			check(!oneLogRetry && JobUtil.count(box, stack -> stack.is(Items.OAK_PLANKS)) == 4
				&& JobUtil.count(box, stack -> stack.is(Items.STICK)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE)) == 0
				&& d.planStep == toolshedWaitStep,
				"one-log wait reserves all four planks for the fence instead of converting them into unusable sticks"); checks++;
			box.setItem(1, new ItemStack(Items.OAK_LOG, 1));
			c.st().woodCheckAt = 0;
			for (int attempt = 0; attempt < 6 && JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE)) == 0; attempt++) {
				recipeWork.add(Builder.supplyMissing(new Ctx(level, worker, c.st(), c.mem(), ref, now + 222 + attempt)));
			}
			check(JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE)) > 0
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_LOG)) == 0
				&& JobUtil.count(box, stack -> stack.is(Items.OAK_PLANKS)) > 0
				&& JobUtil.count(box, stack -> stack.is(Items.STICK)) > 0
				&& d.planStep == toolshedWaitStep && d.planBuilder().filter(worker.getUUID()::equals).isPresent(),
				"missing fence is built over work cycles from two real logs through loaded plank/stick recipes"
					+ " [logs=" + JobUtil.count(box, stack -> stack.is(Items.OAK_LOG))
					+ ", planks=" + JobUtil.count(box, stack -> stack.is(Items.OAK_PLANKS))
					+ ", sticks=" + JobUtil.count(box, stack -> stack.is(Items.STICK))
					+ ", fences=" + JobUtil.count(box, stack -> stack.is(Items.OAK_FENCE))
					+ ", work=" + recipeWork + ", owner=" + d.planBuilder()
					+ ", wanted=" + d.wanted + "]"); checks++;
			d.wanted.clear(); d.wanted.add("minecraft:oak_sign#1");
			check(de.villagerplus.job.Woodcutting.planNeedsWood(d),
				"construction sign requests use the same wood supply rule as the woodcutter"); checks++;
			d.wanted.clear(); d.wanted.addAll(toolshedDemand);
			int deliveredUnits = deliverPlanDemand(box, toolshedDemand);
			VillageData reloadedPlan = VillageData.CODEC.parse(JsonOps.INSTANCE,
				VillageData.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow()).getOrThrow();
			check(reloadedPlan.hasPlan() && reloadedPlan.planStep == toolshedWaitStep
				&& reloadedPlan.wanted.equals(toolshedDemand)
				&& reloadedPlan.planBuilder().filter(worker.getUUID()::equals).isPresent()
				&& JobUtil.count(box, stack -> true) == deliveredUnits,
				"delivered bootstrap materials and the exact owner, cursor and demand survive a plan reload"); checks++;
			VillageRef reloadedRef = new VillageRef(ref.bell(), reloadedPlan, ref.bellEntity());
			int resumedStep = tickUntilStockConsumed(level, reloadedRef, worker, toolshed, toolshedWaitStep,
				deliveredUnits, now + 240);
			check(resumedStep > toolshedWaitStep && reloadedPlan.planStep == resumedStep
				&& JobUtil.count(box, stack -> true) < deliveredUnits,
				"the reloaded mandatory plan resumes from its saved cursor and spends only the delivered stock"
					+ " [waitStep=" + toolshedWaitStep + ", resumedStep=" + resumedStep
					+ ", delivered=" + deliveredUnits + ", remaining=" + JobUtil.count(box, stack -> true) + "]"); checks++;
			Plan.of(reloadedPlan).restore(d);
			Builder.cancel(ref, now + 200);
			Specials.set(ref, Blueprints.TOOLSHED, ref.bell().north(28));
			check(Blueprints.SCHOOL.equals(Builder.nextSpecial(ref, Config.SCHOOL_POP)),
				"school becomes the next milestone after the granary and toolshed registrations are complete"); checks++;
			long previousNextBuild = d.nextBuild;
			d.nextBuild = now + Config.BUILD_COOLDOWN;
			check(!Builder.cooldownBlocksBuild(ref, Config.SCHOOL_POP, 0, now),
				"mandatory school milestone bypasses the normal post-building cooldown"); checks++;
			d.nextBuild = previousNextBuild;

			Specials.remove(ref, Blueprints.GRANARY);
			Specials.remove(ref, Blueprints.TOOLSHED);
			d.extra.school = Optional.empty();
			box.clearContent();
			waitingPlan(d, Blueprints.GRANARY, origin, now + 400);
			Ctx waitingGranary = new Ctx(level, worker, c.st(), c.mem(), ref, now + 400);
			int expectedStep = tickUntilMaterialWait(level, ref, worker, waitingGranary, now + 400);
			List<String> expectedWanted = List.copyOf(d.wanted);
			long expectedNextBuild = d.nextBuild;
			check(expectedStep >= 0 && Blueprints.GRANARY.equals(Builder.nextSpecial(ref, 2))
				&& expectedStep == d.planStep && !expectedWanted.isEmpty(),
				"full-store case begins with a waiting, still-required granary and a concrete cursor/wishlist"
					+ " [expectedStep=" + expectedStep + ", actualStep=" + d.planStep + ", wanted=" + expectedWanted + "]"); checks++;
			Item filler = unrelatedConstructionMaterial(expectedWanted);
			for (int slot = 0; slot < box.getContainerSize(); slot++) box.setItem(slot, new ItemStack(filler, 64));
			c.st().materialWaitUntil = 0;
			Ctx full = new Ctx(level, worker, c.st(), c.mem(), ref, now + 600);
			boolean hasEmptySlot = JobUtil.hasEmptySlot(box);
			boolean needsStorage = Development.needsStorage(box, d, level);
			boolean storageFree = Builder.storageFree(full, ref);
			check(!hasEmptySlot && needsStorage && storageFree,
				"the public material store has real capacity pressure while storage expansion is eligible"
					+ " [emptySlot=" + hasEmptySlot + ", needsStorage=" + needsStorage + ", storageFree=" + storageFree + "]"); checks++;
			Builder.tick(full, true);
			check(d.hasPlan() && Blueprints.GRANARY.equals(d.planType) && d.planOrigin.equals(Optional.of(origin))
				&& d.planStep == expectedStep && d.wanted.equals(expectedWanted)
				&& d.planBuilder().filter(worker.getUUID()::equals).isPresent() && d.nextBuild == expectedNextBuild,
				"capacity-only storage expansion leaves the required granary, owner, cursor and wishlist intact"
					+ " [expected={plan=granary, step=" + expectedStep + ", wanted=" + expectedWanted + ", nextBuild=" + expectedNextBuild
					+ "}, actual={plan=" + d.planType + ", origin=" + d.planOrigin.map(BlockPos::toShortString).orElse("none")
					+ ", step=" + d.planStep + ", wanted=" + d.wanted + ", owner=" + d.planBuilder() + ", nextBuild=" + d.nextBuild
					+ "}, pressure={emptySlot=" + JobUtil.hasEmptySlot(box) + ", needsStorage=" + Development.needsStorage(box, d, level)
					+ ", storageFree=" + Builder.storageFree(full, ref) + ", unrelatedStock=" + filler + "}]"); checks++;
			return checks;
		} finally {
			if (d.hasPlan() && d.planOrigin.equals(Optional.of(origin))) Builder.cancel(ref, now + 600);
			workers.forEach(v -> { v.discard(); Villages.forget(v.getUUID()); });
			restorePlanSite(level, fixtureSite);
			for (int i = fixtureBlocks.size() - 1; i >= 0; i--) {
				FixtureBlock saved = fixtureBlocks.get(i);
				level.setBlock(saved.pos(), saved.state(), 3);
			}
			box.clearContent();
			for (int slot = 0; slot < stock.size(); slot++) box.setItem(slot, stock.get(slot));
			oldPlan.restore(d); d.extra.school = oldSchool; d.extra.lager = oldLager;
			d.extra.lagerMore.clear(); d.extra.lagerMore.addAll(oldLagerMore);
			d.extra.lagerChests.clear(); d.extra.lagerChests.addAll(oldLagerChests);
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS); else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			if (oldDemolition == null) ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION); else ref.bellEntity().setAttached(VillagerPlus.DEMOLITION, oldDemolition);
			if (oldConstruction == null) ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION); else ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION, oldConstruction);
			ref.dirty();
		}
	}

	/** Drive the real plan through stand selection and bounded terrain preparation until it reaches missing material. */
	/** Game time of the last tick executed by {@link #tickUntilMaterialWait}; later dispatch checks continue from it. */
	private static long lastWaitTick;

	private static int tickUntilMaterialWait(ServerLevel level, VillageRef ref, Villager worker, Ctx first, long now) {
		boolean busy = true;
		int waitStep = -1;
		for (int attempt = 0; attempt < 120 && busy; attempt++) {
			Ctx c = new Ctx(level, worker, first.st(), first.mem(), ref, now + attempt);
			lastWaitTick = now + attempt;
			busy = Builder.tick(c, true);
			if (busy) {
				BlockPos stand = Builder.constructionStand(c);
				if (stand != null) {
					worker.getNavigation().stop();
					worker.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
					worker.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
				}
			} else {
				waitStep = ref.data().planStep;
			}
		}
		return waitStep;
	}

	/** Material delivered from a source job is stored in the public chest before the plan is reloaded. */
	private static int deliverPlanDemand(Container box, List<String> demand) {
		Map<Item, Integer> required = new LinkedHashMap<>();
		for (String entry : demand) {
			String[] parts = entry.split("#", 2);
			if (parts.length != 2) throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: malformed demand " + entry);
			Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0])).orElse(Items.AIR);
			int count = Integer.parseInt(parts[1]);
			if (item == Items.AIR || count <= 0) throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: invalid demand " + entry);
			required.merge(item, count, Integer::sum);
		}
		box.clearContent();
		int slot = 0;
		for (var entry : required.entrySet()) {
			int remaining = entry.getValue();
			while (remaining > 0) {
				if (slot >= box.getContainerSize()) throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: demand exceeds chest capacity");
				ItemStack stack = new ItemStack(entry.getKey());
				int amount = Math.min(remaining, stack.getMaxStackSize());
				stack.setCount(amount);
				box.setItem(slot++, stack);
				remaining -= amount;
			}
		}
		return JobUtil.count(box, stack -> true);
	}

	/** Continue through free setup steps until the restored plan spends delivered stock. */
	private static int tickUntilStockConsumed(ServerLevel level, VillageRef ref, Villager worker, Ctx first,
		int startStep, int startingStock, long now) {
		Container box = ref.chest(level);
		for (int attempt = 0; attempt < 120; attempt++) {
			Ctx c = new Ctx(level, worker, first.st(), first.mem(), ref, now + attempt);
			Builder.tick(c, true);
			if (ref.data().planStep > startStep && JobUtil.count(box, stack -> true) < startingStock) {
				return ref.data().planStep;
			}
			BlockPos stand = Builder.constructionStand(c);
			if (stand != null) {
				worker.getNavigation().stop();
				worker.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
				worker.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			}
		}
		return ref.data().planStep;
	}

	/** A temporary, restored surface keeps this synthetic plan at its anchor height and gives the builder a nearby safe stand. */
	private static BlockPos prepareFixtureSurface(ServerLevel level, BlockPos bell, BlockPos origin, List<FixtureBlock> saved) {
		int floorY = bell.getY() - 1;
		int minX = Math.min(bell.getX(), origin.getX()) - 12;
		int maxX = Math.max(bell.getX(), origin.getX()) + 16;
		int minZ = Math.min(bell.getZ(), origin.getZ()) - 12;
		int maxZ = Math.max(bell.getZ(), origin.getZ()) + 12;
		for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) level.getChunk(chunkX, chunkZ);
		}
		// Read the natural height before laying the pad so the three fixture columns reach real ground.
		for (BlockPos column : List.of(bell.offset(2, 0, 0), origin, origin.offset(10, 0, 0))) {
			int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				column.getX(), column.getZ()) - 1;
			for (int y = groundY + 1; y < floorY; y++) {
				BlockPos support = new BlockPos(column.getX(), y, column.getZ());
				BlockState old = level.getBlockState(support);
				if (old.isAir()) {
					saved.add(new FixtureBlock(support.immutable(), old));
					level.setBlock(support, Blocks.COBBLESTONE.defaultBlockState(), 3);
				}
			}
		}
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				BlockPos floor = new BlockPos(x, floorY, z);
				BlockState old = level.getBlockState(floor);
				if (old.isAir()) {
					saved.add(new FixtureBlock(floor.immutable(), old));
					level.setBlock(floor, Blocks.COBBLESTONE.defaultBlockState(), 3);
				}
			}
		}
		for (int radius = 0; radius <= 4; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos feet = new BlockPos(origin.getX() + 10 + dx, floorY + 1, origin.getZ() + dz);
					if (level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
						|| !level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) continue;
					return feet;
				}
			}
		}
		throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: no clear worker stand on the fixture surface");
	}

	/** Snapshot the exact generated plan footprints and all columns Prep can level for both tested plans. */
	private static void snapshotPlanSite(ServerLevel level, BlockPos bell, Villager worker, BlockPos origin,
		Map<BlockPos, FixtureCell> saved) {
		for (Architect.Kind kind : List.of(Architect.Kind.GRANARY, Architect.Kind.TOOLSHED)) {
			Architect.Design design = Architect.design(kind, 64);
			Palette palette = Palette.detect(level, bell, worker, 64, origin);
			List<Step> steps = Architect.build(design, palette, origin, Direction.from2DDataValue(0));
			int maxY = steps.stream().mapToInt(step -> step.pos().getY() - origin.getY()).max().orElse(0);
			for (Step step : steps) {
				snapshot(level, step.pos(), saved);
				level.setBlock(step.pos(), Blocks.AIR.defaultBlockState(), 3);
				if (step.linkedTo() != null) {
					snapshot(level, step.linkedTo(), saved);
					level.setBlock(step.linkedTo(), Blocks.AIR.defaultBlockState(), 3);
				}
			}
			snapshotWorkStandCandidates(level, origin, steps, saved);
			Set<Long> footprint = Prep.footprint(steps, origin);
			for (long column : footprint) {
				int x = (int) (column >> 32);
				int z = (int) column;
				int height = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				int minY = Math.min(origin.getY(), height);
				int maxTouchedY = Math.max(height, origin.getY() + maxY + 1);
				for (int y = minY; y <= maxTouchedY; y++) snapshot(level, new BlockPos(x, y, z), saved);
			}
		}
	}

	/** WorkStand may clear foliage at a chosen stand; preserve those exact candidate cells as well. */
	private static void snapshotWorkStandCandidates(ServerLevel level, BlockPos origin, List<Step> steps,
		Map<BlockPos, FixtureCell> saved) {
		int minX = origin.getX(), maxX = minX, minZ = origin.getZ(), maxZ = minZ, ext = 2;
		Direction dir = Direction.from2DDataValue(0);
		for (Step step : steps) {
			BlockPos pos = step.pos();
			minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
			minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
			ext = Math.max(ext, (pos.getX() - origin.getX()) * dir.getStepX() + (pos.getZ() - origin.getZ()) * dir.getStepZ());
		}
		List<BlockPos> columns = new ArrayList<>();
		columns.add(origin.relative(dir, ext + 3));
		int midX = minX + (maxX - minX) / 2, midZ = minZ + (maxZ - minZ) / 2;
		for (int x : new int[] {midX, minX, maxX}) {
			columns.add(new BlockPos(x, origin.getY(), minZ - 3));
			columns.add(new BlockPos(x, origin.getY(), maxZ + 3));
		}
		for (int z : new int[] {midZ, minZ, maxZ}) {
			columns.add(new BlockPos(minX - 3, origin.getY(), z));
			columns.add(new BlockPos(maxX + 3, origin.getY(), z));
		}
		for (int gap = 4; gap <= 8; gap++) {
			columns.add(new BlockPos(midX, origin.getY(), minZ - gap));
			columns.add(new BlockPos(midX, origin.getY(), maxZ + gap));
			columns.add(new BlockPos(minX - gap, origin.getY(), midZ));
			columns.add(new BlockPos(maxX + gap, origin.getY(), midZ));
		}
		for (int gap : new int[] {4, 6, 8}) {
			columns.add(new BlockPos(minX - gap, origin.getY(), minZ - gap));
			columns.add(new BlockPos(maxX + gap, origin.getY(), minZ - gap));
			columns.add(new BlockPos(minX - gap, origin.getY(), maxZ + gap));
			columns.add(new BlockPos(maxX + gap, origin.getY(), maxZ + gap));
		}
		for (BlockPos column : columns) {
			BlockPos feet = Builder.standOnColumn(level, column.getX(), column.getZ(), origin.getY());
			if (feet == null) continue;
			snapshot(level, feet, saved);
			snapshot(level, feet.above(), saved);
			snapshot(level, feet.above(2), saved);
		}
	}

	private static void snapshot(ServerLevel level, BlockPos pos, Map<BlockPos, FixtureCell> saved) {
		BlockPos immutable = pos.immutable();
		if (saved.containsKey(immutable)) return;
		level.getChunkAt(immutable);
		BlockEntity blockEntity = level.getBlockEntity(immutable);
		saved.put(immutable, new FixtureCell(level.getBlockState(immutable),
			blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restorePlanSite(ServerLevel level, Map<BlockPos, FixtureCell> saved) {
		for (BlockPos pos : saved.keySet()) level.removeBlockEntity(pos);
		for (BlockPos pos : saved.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		saved.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		saved.forEach((pos, cell) -> {
			if (cell.data() == null) return;
			BlockEntity blockEntity = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
			if (blockEntity != null) level.setBlockEntity(blockEntity);
		});
	}

	private static void waitingPlan(VillageData d, String type, BlockPos origin, long now) {
		d.planType = type; d.planOrigin = Optional.of(origin); d.planDir = 0; d.planStep = 0; d.planVariant = 64 << 2;
		d.planSource = Optional.empty(); d.planProgress = now; d.wanted.clear();
		// A legacy single plan is only picked up while the village is not yet migrated to build projects; the shared dev
		// world may already be, so put the fixture village back into that pre-migration state.
		d.buildProjects.clear(); d.buildProjectVersion = 0;
	}
	private static Item unrelatedConstructionMaterial(List<String> wanted) {
		for (Item item : List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.OAK_LOG,
			Items.COBBLESTONE, Items.STONE_BRICKS, Items.BRICKS, Items.GLASS_PANE, Items.GRAVEL, Items.DIRT,
			Items.SANDSTONE, Items.RED_SANDSTONE, Items.OAK_SLAB, Items.OAK_STAIRS)) {
			String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
			boolean requested = wanted.stream().anyMatch(entry -> entry.startsWith(itemId + "#"));
			if (!requested && Supply.obtainable(Block.byItem(item))) return item;
		}
		throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: no supported construction material outside the granary wishlist");
	}
	private static boolean actionable(VillageData d) {
		boolean needed = false;
		if (d.wanted.isEmpty()) return false;
		for (String entry : d.wanted) {
			String[] parts = entry.split("#", 2);
			if (parts.length != 2) return false;
			try {
				var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(parts[0])).orElse(Items.AIR);
				if (item == Items.AIR || Integer.parseInt(parts[1]) <= 0) return false;
				needed |= Builder.planNeeds(d, new ItemStack(item));
			} catch (RuntimeException malformed) { return false; }
		}
		return needed;
	}
	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("MILESTONE WAIT CHECK FAILED: " + message);
		System.out.println("MILESTONE WAIT PASS " + message);
	}
}
