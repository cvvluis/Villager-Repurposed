package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Specials;
import de.villagerplus.village.Wellbeing;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.LinkedHashMap;
import java.util.Map;

/** J02 checks the route from cooked pantry stock through household eating to wellbeing. */
public final class HouseholdMealChecks {
	private static int checks;

	private HouseholdMealChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		checks = 0;
		level.getChunkAt(at);
		BlockPos granary = at.north(14), home = at.east(7), deliveryStart = at.west(12);
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos pos : new BlockPos[] { at.below(), at, at.east(3).below(), at.east(3),
			granary.below(), granary, home.below(), home, home.above(), deliveryStart.below(), deliveryStart,
			deliveryStart.above() }) snapshot(level, before, pos);
		net.minecraft.world.entity.npc.villager.Villager diner = null;
		try {
			var village = Checks090.fixtureForEquipment(level, at);
			level.setBlock(granary.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(granary, Blocks.CHEST.defaultBlockState(), 3);
			Specials.set(village, Blueprints.GRANARY, granary);
			Container pantry = (Container) level.getBlockEntity(granary);
			if (pantry == null) throw new IllegalStateException("J02 granary fixture did not create a container");
			ItemStack rawPotato = new ItemStack(Items.POTATO);
			var potatoRecipe = SmithSmelting.matchingRecipeForCheck(level, rawPotato);
			if (potatoRecipe == null) throw new IllegalStateException("J02 fixture needs the loaded vanilla potato smelting recipe");
			ItemStack cookedMeal = potatoRecipe.assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(rawPotato));
			check(Meals.isPreparedFood(level, cookedMeal) && cookedMeal.is(Items.BAKED_POTATO),
				"the loaded vanilla potato recipe output is recognized as a prepared meal");
			pantry.setItem(0, cookedMeal);
			level.setBlock(home.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(home, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(home.above(), Blocks.AIR.defaultBlockState(), 3);
			diner = EntityTypes.VILLAGER.spawn(level, home, EntitySpawnReason.COMMAND);
			if (diner == null) throw new IllegalStateException("J02 household meal villager did not spawn");
			diner.addTag("vp090_fixture");
			diner.setNoAi(true);
			diner.setInvulnerable(true);
			long now = level.getGameTime();
			VillagerMemory memory = diner.getAttachedOrCreate(VillagerPlus.MEMORY);
			memory.lastMeal = now - 4000L;
			memory.lastSleep = now;
			memory.lastChat = now;
			diner.setAttached(VillagerPlus.PREPARED_MEAL, 0L);
			diner.getInventory().setItem(0, new ItemStack(Items.APPLE, 2));
			Ctx ctx = new Ctx(level, diner, JobManager.state(diner), memory, village, now);

			check(Meals.eat(ctx, null), "household meal routine takes a prepared meal from the village pantry");
			check(JobUtil.count(pantry, stack -> stack.is(Items.BAKED_POTATO)) == 0
				&& JobUtil.count(diner.getInventory(), stack -> stack.is(Items.APPLE)) == 2,
				"a loaded-recipe meal in the pantry is served before ordinary food in the resident inventory");

			long preparedAt = diner.getAttachedOrElse(VillagerPlus.PREPARED_MEAL, 0L);
			check(preparedAt == now && memory.lastMeal == now,
				"eating a prepared household meal updates the persistent meal and prepared-meal timestamps");
			Wellbeing.Status withMeal = Wellbeing.of(level, diner, memory);
			diner.setAttached(VillagerPlus.PREPARED_MEAL, 0L);
			Wellbeing.Status withoutMeal = Wellbeing.of(level, diner, memory);
			diner.setAttached(VillagerPlus.PREPARED_MEAL, preparedAt);
			check(withMeal.mood() == Math.min(100, withoutMeal.mood() + 12),
				"the consumed prepared meal adds its configured mood bonus");

			boolean active = preparedAt != 0L && now >= preparedAt && now - preparedAt < 6000L;
			Wellbeing.Status hungryWorker = new Wellbeing.Status(10, 100, 100, 100, 20, 0, true, true);
			check(active && Wellbeing.workQuarters(hungryWorker, active) == 3,
				"eating the loaded-recipe meal grants the temporary work-pace quarter used by JobManager");
			diner.setAttached(VillagerPlus.PREPARED_MEAL, now - 6000L);
			Wellbeing.Status expiredMeal = Wellbeing.of(level, diner, memory);
			check(expiredMeal.mood() == withoutMeal.mood() && Wellbeing.workQuarters(hungryWorker, false) < 3,
				"the prepared-meal mood and work effects expire after six thousand ticks");

			level.setBlock(deliveryStart.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(deliveryStart, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(deliveryStart.above(), Blocks.AIR.defaultBlockState(), 3);
			diner.setPos(deliveryStart.getX() + 0.5, deliveryStart.getY(), deliveryStart.getZ() + 0.5);
			long routineNow = now + 1000;
			JobState routine = JobManager.state(diner);
			Job logDelivery = logDelivery(0);
			routine.routinePhase = Phase.MORNING;
			routine.stage = 1;
			routine.chatPartner = null;
			routine.chatCooldown = Long.MAX_VALUE;
			routine.clearTarget();
			routine.stuckCount = 0;
			routine.bestDist = 0;
			routine.lastMoveAt = 0;
			routine.walkStart = routineNow - 1;
			BlockPos deliverySpot = village.chestPos(level);
			if (deliverySpot == null) deliverySpot = village.bell();
			diner.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
				new net.minecraft.world.entity.ai.memory.WalkTarget(deliverySpot, 0.45F, 1));
			diner.getInventory().clearContent();
			// A resident mid-delivery has committed to a delivery stand beside the shared chest (JobState.target); the routine only
			// cancels an approach it recognises as a delivery (Routine.stopDeliveryApproachIfNeeded).
			routine.target = deliverySpot.immutable();
			Routine.run(new Ctx(level, diner, routine, memory, village, routineNow), logDelivery, Phase.MORNING);
			check(routine.stage == 2 && !hasWalkTarget(diner) && routine.walkStart == 0,
				"morning delivery skips the shared chest when the matching inventory is empty");

			routine.routinePhase = Phase.LUNCH;
			routine.stage = 0;
			routine.chatPartner = null;
			routine.chatCooldown = Long.MAX_VALUE;
			routine.clearTarget();
			diner.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			diner.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
			Routine.run(new Ctx(level, diner, routine, memory, village, routineNow + 1), logDelivery(2), Phase.LUNCH);
			check(routine.stage == 2 && !hasWalkTarget(diner) && routine.walkStart == 0,
				"lunch delivery skips the shared chest when carried logs only meet the keep reserve");

			routine.routinePhase = Phase.MORNING;
			routine.stage = 1;
			routine.chatPartner = null;
			routine.chatCooldown = Long.MAX_VALUE;
			routine.clearTarget();
			routine.stuckCount = 0;
			routine.bestDist = 0;
			routine.lastMoveAt = 0;
			routine.cooldownUntil = 0;
			diner.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			diner.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));
			// The delivery approach needs a supported, free stand around the chest (distributed stands, JobUtil.sharedStandSpot):
			// give the chest a small floor so the resident really starts a route that then stalls and is aborted.
			BlockPos chestCentre = deliverySpot;
			for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
				BlockPos floor = chestCentre.offset(dx, -1, dz);
				if (dx == 0 && dz == 0) continue;
				level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			long routeStart = routineNow + 100;
			for (int tick = 0; tick < 800 && routine.stage == 1; tick++) {
				Routine.run(new Ctx(level, diner, routine, memory, village, routeStart + tick), logDelivery(1), Phase.MORNING);
			}
			check(routine.stage == 2 && routine.idleUntil[0] > routeStart && routine.idleUntil[1] > routeStart
				&& !hasWalkTarget(diner) && JobUtil.count(diner.getInventory(), stack -> stack.is(Items.OAK_LOG)) == 3,
				"an aborted morning delivery route advances the stage while preserving carried stock");
			return checks;
		} finally {
			if (diner != null) diner.discard();
			restore(level, before);
		}
	}

	private static void snapshot(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
		BlockPos immutable = pos.immutable();
		if (before.containsKey(immutable)) return;
		level.getChunkAt(immutable);
		BlockState state = level.getBlockState(immutable);
		BlockEntity entity = level.getBlockEntity(immutable);
		before.put(immutable, new Cell(state, entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.nbt() == null) return;
			BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.nbt().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		});
	}

	private record Cell(BlockState state, CompoundTag nbt) { }

	private static Job logDelivery(int keep) {
		return new Job() {
			@Override public boolean primary(Ctx c) { return false; }
			@Override public java.util.function.Predicate<ItemStack> produce() { return stack -> stack.is(Items.OAK_LOG); }
			@Override public int keep() { return keep; }
		};
	}

	private static boolean hasWalkTarget(net.minecraft.world.entity.npc.villager.Villager villager) {
		return villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isPresent();
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("J02 household meal: " + message);
		checks++;
	}
}
