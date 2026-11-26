package de.villagerplus.job;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.block.ModItems;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Specials;
import de.villagerplus.village.Wellbeing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
		BlockPos privateHomeChestPos = home.south();
		BlockPos authorizedChestPos = at.south(10);
		net.minecraft.world.level.block.state.BlockState leftChest = Blocks.CHEST.defaultBlockState()
			.setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.NORTH)
			.setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
		BlockPos authorizedNeighborPos = authorizedChestPos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(leftChest));
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (BlockPos pos : new BlockPos[] { at.below(), at, at.east(3).below(), at.east(3),
			granary.below(), granary, home.below(), home, home.above(), privateHomeChestPos.below(), privateHomeChestPos,
			authorizedChestPos.below(), authorizedChestPos,
			authorizedNeighborPos.below(), authorizedNeighborPos,
			deliveryStart.below(), deliveryStart,
			deliveryStart.above() }) snapshot(level, before, pos);
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			for (int dy = -1; dy <= 1; dy++) snapshot(level, before, authorizedChestPos.offset(dx, dy, dz));
		}
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

			level.setBlock(authorizedChestPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(authorizedChestPos, leftChest, 3);
			var rightChest = leftChest.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
				net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
			level.setBlock(authorizedNeighborPos, rightChest, 3);
			Container authorizedChest = (Container) level.getBlockEntity(authorizedChestPos);
			var grantor = java.util.UUID.randomUUID();
			var grantorPlayer = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
				new com.mojang.authlib.GameProfile(grantor, "ChestAccessFixture"),
				net.minecraft.server.level.ClientInformation.createDefault()) {
				@Override public void sendOverlayMessage(Component message) { }
			};
			var secondPlayer = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
				new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ChestAccessFixtureB"),
				net.minecraft.server.level.ClientInformation.createDefault()) {
				@Override public void sendOverlayMessage(Component message) { }
			};
			level.setBlock(privateHomeChestPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privateHomeChestPos, Blocks.CHEST.defaultBlockState(), 3);
			grantorPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CHEST_ACCESS));
			grantorPlayer.setPos(authorizedChestPos.getX() + 1.5, authorizedChestPos.getY(), authorizedChestPos.getZ() + 0.5);
			diner.setPos(authorizedChestPos.getX() + 0.5, authorizedChestPos.getY(), authorizedChestPos.getZ() + 0.5);
			InteractionResult selectA = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
				.interact(grantorPlayer, level, InteractionHand.MAIN_HAND, diner, null);
			BlockHitResult authorizedHit = new BlockHitResult(Vec3.atCenterOf(authorizedChestPos), Direction.UP, authorizedChestPos, false);
			InteractionResult grantA = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker()
				.interact(grantorPlayer, level, InteractionHand.MAIN_HAND, authorizedHit);
			var grantedAccess = diner.getAttached(VillagerPlus.CHEST_ACCESS);
			check(selectA == InteractionResult.SUCCESS && grantA == InteractionResult.SUCCESS
				&& grantedAccess != null && grantedAccess.neighborInstance().isPresent()
				&& grantorPlayer.getAttached(VillagerPlus.CHEST_ACCESS_TARGET) == null,
				"the actual villager-then-double-chest clicks create a persistent grant for both chest halves");
			secondPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.CHEST_ACCESS));
			secondPlayer.setPos(privateHomeChestPos.getX() + 0.5, privateHomeChestPos.getY(), privateHomeChestPos.getZ() + 0.5);
			InteractionResult selectB = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
				.interact(secondPlayer, level, InteractionHand.MAIN_HAND, diner, null);
			BlockHitResult secondHit = new BlockHitResult(Vec3.atCenterOf(privateHomeChestPos), Direction.UP, privateHomeChestPos, false);
			InteractionResult overwriteB = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker()
				.interact(secondPlayer, level, InteractionHand.MAIN_HAND, secondHit);
			check(selectB == InteractionResult.SUCCESS && overwriteB == InteractionResult.FAIL
				&& diner.getAttached(VillagerPlus.CHEST_ACCESS).equals(grantedAccess)
				&& secondPlayer.getAttached(VillagerPlus.CHEST_ACCESS_TARGET) == null,
				"a second player's two-click attempt cannot replace or alter the original chest grant");
			secondPlayer.setShiftKeyDown(true);
			InteractionResult revokeB = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
				.interact(secondPlayer, level, InteractionHand.MAIN_HAND, diner, null);
			secondPlayer.setShiftKeyDown(false);
			check(revokeB == InteractionResult.FAIL && diner.getAttached(VillagerPlus.CHEST_ACCESS).equals(grantedAccess),
				"a non-grantor cannot revoke chest access by shift-clicking the villager");
			grantorPlayer.setShiftKeyDown(true);
			InteractionResult revokeA = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
				.interact(grantorPlayer, level, InteractionHand.MAIN_HAND, diner, null);
			grantorPlayer.setShiftKeyDown(false);
			check(revokeA == InteractionResult.SUCCESS && diner.getAttached(VillagerPlus.CHEST_ACCESS) == null,
				"the original grantor can revoke chest access by shift-clicking the villager");
			selectB = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
				.interact(secondPlayer, level, InteractionHand.MAIN_HAND, diner, null);
			InteractionResult grantB = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker()
				.interact(secondPlayer, level, InteractionHand.MAIN_HAND, secondHit);
			check(selectB == InteractionResult.SUCCESS && grantB == InteractionResult.SUCCESS
				&& diner.getAttached(VillagerPlus.CHEST_ACCESS).grantedBy().equals(secondPlayer.getUUID()),
				"a new grant is accepted after the original grantor explicitly revokes access");
			secondPlayer.setShiftKeyDown(true);
			InteractionResult revokeB2 = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
				.interact(secondPlayer, level, InteractionHand.MAIN_HAND, diner, null);
			secondPlayer.setShiftKeyDown(false);
			check(revokeB2 == InteractionResult.SUCCESS && diner.getAttached(VillagerPlus.CHEST_ACCESS) == null,
				"the current grantor can revoke the replacement grant");
			grantorPlayer.setPos(authorizedChestPos.getX() + 1.5, authorizedChestPos.getY(), authorizedChestPos.getZ() + 0.5);
			selectA = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker()
				.interact(grantorPlayer, level, InteractionHand.MAIN_HAND, diner, null);
			grantA = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker()
				.interact(grantorPlayer, level, InteractionHand.MAIN_HAND, authorizedHit);
			grantedAccess = diner.getAttached(VillagerPlus.CHEST_ACCESS);
			if (grantedAccess == null) throw new IllegalStateException("J02 chest-access click fixture could not restore the original grant");
			var access = diner.getAttached(VillagerPlus.CHEST_ACCESS);
			// A tiny loaded floor gives the recipe route a deterministic interaction stand beside this assigned chest.
			for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
				BlockPos floor = authorizedChestPos.offset(dx, -1, dz);
				level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
				if (floor.above().equals(authorizedChestPos) || floor.above().equals(authorizedNeighborPos)) continue;
				level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			authorizedChest.setItem(0, new ItemStack(Items.OAK_PLANKS));
			authorizedChest.setItem(1, new ItemStack(Items.BREAD, 5));
			var recipeStock = new net.minecraft.world.SimpleContainer(9);
			Ctx recipeCtx = new Ctx(level, diner, JobManager.state(diner), memory, village, now + 1);
			BlockPos recipeStand = JobUtil.sharedStandSpot(recipeCtx, authorizedChestPos, 3.0);
			check(recipeStand != null, "assigned chest has a loaded interaction stand for recipe work");
			diner.setPos(recipeStand.getX() + 0.5, recipeStand.getY(), recipeStand.getZ() + 0.5);
			var aliasedStock = new net.minecraft.world.CompoundContainer(recipeStock,
				de.villagerplus.village.ChestAccess.container(level, diner));
			check(!RecipeCrafting.craft(recipeCtx, aliasedStock, null, stack -> stack.is(Items.STICK), 4)
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.OAK_PLANKS)) == 1,
				"two nested inventory views cannot supply the same input item twice");
			authorizedChest.setItem(2, new ItemStack(Items.STICK, 5));
			var fullStock = new net.minecraft.world.SimpleContainer(1);
			fullStock.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
			authorizedChest.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
			check(!RecipeCrafting.craft(recipeCtx, fullStock, null, stack -> stack.is(Items.STICK), 64)
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.OAK_PLANKS)) == 2
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.STICK)) == 5,
				"a full destination leaves all ingredients untouched and cannot redirect output to the assigned chest");
			authorizedChest.setItem(0, new ItemStack(Items.OAK_PLANKS));
			recipeStock.setItem(0, new ItemStack(Items.OAK_PLANKS));
			check(RecipeCrafting.craft(recipeCtx, recipeStock, null, stack -> stack.is(Items.STICK), 4)
				&& JobUtil.count(recipeStock, stack -> stack.is(Items.STICK)) == 4
				&& JobUtil.count(recipeStock, stack -> stack.is(Items.OAK_PLANKS)) == 0
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.OAK_PLANKS)) == 0
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.STICK)) == 5
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.BREAD)) == 5,
				"recipe consumes exact inputs from both sources and puts output in destination independently of private stock");
			var cakeStock = new net.minecraft.world.SimpleContainer(9);
			for (int slot = 0; slot < 3; slot++) cakeStock.setItem(slot, new ItemStack(Items.MILK_BUCKET));
			cakeStock.setItem(3, new ItemStack(Items.SUGAR, 2));
			cakeStock.setItem(4, new ItemStack(Items.EGG));
			authorizedChest.setItem(0, new ItemStack(Items.WHEAT, 3));
			check(RecipeCrafting.craft(recipeCtx, cakeStock, null, stack -> stack.is(Items.CAKE), 1)
				&& JobUtil.count(cakeStock, stack -> stack.is(Items.CAKE)) == 1
				&& JobUtil.count(cakeStock, stack -> stack.is(Items.BUCKET)) == 3
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.WHEAT) || stack.is(Items.BUCKET)) == 0,
				"crafting with assigned ingredients puts recipe output and container remainders in destination stock");
			authorizedChest.clearContent();
			authorizedChest.setItem(0, new ItemStack(Items.POTATO));
			authorizedChest.setItem(1, new ItemStack(Items.BREAD, 2));
			var campfireStock = new net.minecraft.world.SimpleContainer(9);
			check(RecipeCrafting.craft(recipeCtx, campfireStock, null, stack -> stack.is(Items.BAKED_POTATO), 4)
				&& JobUtil.count(campfireStock, stack -> stack.is(Items.BAKED_POTATO)) == 1
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.POTATO)) == 0
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.BREAD)) == 2,
				"a concrete campfire meal recipe takes only its matching item from the assigned chest and returns output to stock");
			authorizedChest.clearContent();
			authorizedChest.setItem(0, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
			authorizedChest.setItem(1, new ItemStack(Items.NETHERITE_INGOT));
			var partialSmithingStock = new net.minecraft.world.SimpleContainer(4);
			partialSmithingStock.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			check(!RecipeCrafting.craft(recipeCtx, partialSmithingStock, null, stack -> stack.is(Items.NETHERITE_SWORD), 1)
				&& JobUtil.count(partialSmithingStock, stack -> stack.is(Items.DIAMOND_SWORD)) == 1
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)
					|| stack.is(Items.NETHERITE_INGOT)) == 2,
				"smithing fallback cannot silently charge public stock for the missing private recipe input");
			authorizedChest.setItem(2, new ItemStack(Items.DIAMOND_SWORD));
			var privateSmithingStock = new net.minecraft.world.SimpleContainer(4);
			privateSmithingStock.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			boolean privateSmithingCrafted = RecipeCrafting.craft(recipeCtx, privateSmithingStock, null,
				stack -> stack.is(Items.NETHERITE_SWORD), 1);
			check(privateSmithingCrafted
				&& JobUtil.count(privateSmithingStock, stack -> stack.is(Items.NETHERITE_SWORD)) == 1
				&& JobUtil.count(privateSmithingStock, stack -> stack.is(Items.DIAMOND_SWORD)) == 1
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)
					|| stack.is(Items.NETHERITE_INGOT) || stack.is(Items.DIAMOND_SWORD)) == 0,
				"smithing fallback consumes an exact recipe fully supplied by the assigned chest (crafted=" + privateSmithingCrafted
					+ ", output=" + JobUtil.count(privateSmithingStock, stack -> stack.is(Items.NETHERITE_SWORD))
					+ ", publicSword=" + JobUtil.count(privateSmithingStock, stack -> stack.is(Items.DIAMOND_SWORD))
					+ ", private=" + authorizedChest.getItem(0) + "/" + authorizedChest.getItem(1) + "/" + authorizedChest.getItem(2)
					+ ", grant=" + diner.getAttached(VillagerPlus.CHEST_ACCESS) + ")");
			level.setBlock(privateHomeChestPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privateHomeChestPos, Blocks.CHEST.defaultBlockState(), 3);
			memory.homeChest = java.util.Optional.of(privateHomeChestPos);
			check(Routine.homeSpot(new Ctx(level, diner, JobManager.state(diner), memory, village, now + 1)).equals(authorizedChestPos),
				"household meal routing prefers the explicitly authorized chest over a different private home chest");
			memory.homeChest = java.util.Optional.empty();
			check(de.villagerplus.village.ChestAccess.CODEC.parse(JsonOps.INSTANCE,
				de.villagerplus.village.ChestAccess.CODEC.encodeStart(JsonOps.INSTANCE, access).getOrThrow()).getOrThrow().equals(access),
				"authorized chest and grantor persist through codec round trip");
			memory.lastMeal = now - de.villagerplus.Config.HUNGER_AFTER;
			memory.lastSleep = now;
			memory.lastChat = now;
			int initialReputation = diner.getGossips().getReputation(grantor,
				type -> type == net.minecraft.world.entity.ai.gossip.GossipType.MINOR_POSITIVE);
			diner.setHealth(diner.getMaxHealth());
			var hungryPrice = Wellbeing.priceModifier(Wellbeing.of(level, diner, memory));
			authorizedChest.setItem(0, new ItemStack(Items.MUSHROOM_STEW));
			diner.getInventory().clearContent();
			diner.setPos(authorizedChestPos.getX() + 4.5, authorizedChestPos.getY(), authorizedChestPos.getZ() + 0.5);
			check(!Meals.eat(new Ctx(level, diner, JobManager.state(diner), memory, village, now + 1), null)
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.MUSHROOM_STEW)) == 1,
				"resident cannot withdraw from an assigned chest outside close range");
			JobState mealRoute = JobManager.state(diner);
			mealRoute.routinePhase = Phase.EVENING;
			mealRoute.stage = 1;
			mealRoute.clearTarget();
			Routine.run(new Ctx(level, diner, mealRoute, memory, village, now + 2), logDelivery(0), Phase.EVENING);
			var walkTarget = diner.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
			check(walkTarget.isPresent() && walkTarget.get().getTarget().currentBlockPosition().equals(authorizedChestPos),
				"evening meal routine walks toward the explicitly assigned chest from outside eating range");
			diner.setPos(authorizedChestPos.getX() + 2.5, authorizedChestPos.getY(), authorizedChestPos.getZ() + 0.5);
			Routine.run(new Ctx(level, diner, mealRoute, memory, village, now + 3), logDelivery(0), Phase.EVENING);
			check(memory.lastMeal == now + 3 && JobUtil.count(authorizedChest, stack -> stack.is(Items.BOWL)) == 1,
				"after reaching its assigned chest, the evening routine consumes food and returns its container");
			authorizedChest.setItem(0, new ItemStack(Items.MUSHROOM_STEW));
			memory.lastMeal = 0L;
			check(!Meals.eatFromAssignedChest(new Ctx(level, diner, JobManager.state(diner), memory, village, now + 1))
				&& memory.lastMeal > 0 && JobUtil.count(authorizedChest, stack -> stack.is(Items.MUSHROOM_STEW)) == 1,
				"a resident with no meal history starts its hunger clock instead of disabling chest feeding");
			check(Meals.eatFromAssignedChest(new Ctx(level, diner, JobManager.state(diner), memory, village,
				now + de.villagerplus.Config.HUNGER_AFTER / 2L + 2)),
				"resident can take food from its explicitly authorized chest");
			check(JobUtil.count(authorizedChest, stack -> stack.is(Items.BOWL)) == 1,
				"meal remainder is deposited back into the authorized chest");
			check(diner.getGossips().getReputation(grantor,
				type -> type == net.minecraft.world.entity.ai.gossip.GossipType.MINOR_POSITIVE) > initialReputation,
				"feeding the resident improves its reputation with the player who granted access");
			var fedPrice = Wellbeing.priceModifier(Wellbeing.of(level, diner, memory));
			check(fedPrice < hungryPrice,
				"feeding restores wellbeing and moves the existing trade-price modifier toward a discount");
			diner.setPos(authorizedChestPos.getX() + 2.5, authorizedChestPos.getY(), authorizedChestPos.getZ() + 0.5);
			diner.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
			JobState storageState = JobManager.state(diner);
			storageState.routinePhase = Phase.EVENING;
			storageState.stage = 2;
			storageState.clearTarget();
			Routine.run(new Ctx(level, diner, storageState, memory, village, now + 3), logDelivery(0), Phase.EVENING);
			check(JobUtil.count(authorizedChest, stack -> stack.is(Items.EMERALD)) == 2
				&& JobUtil.count(diner.getInventory(), stack -> stack.is(Items.EMERALD)) == 0,
				"resident stores personal surplus in the assigned chest");
			diner.removeAttached(VillagerPlus.CHEST_ACCESS);
			memory.lastMeal = now - de.villagerplus.Config.HUNGER_AFTER;
			diner.getInventory().clearContent();
			authorizedChest.setItem(0, new ItemStack(Items.BREAD));
			int remainingBread = JobUtil.count(authorizedChest, stack -> stack.is(Items.BREAD));
			check(!Meals.eat(new Ctx(level, diner, JobManager.state(diner), memory, village, now + 2), null)
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.BREAD)) == remainingBread,
				"removing chest permission prevents further withdrawals");
			authorizedChest.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
			var revokedStock = new net.minecraft.world.SimpleContainer(9);
			check(!RecipeCrafting.craft(new Ctx(level, diner, JobManager.state(diner), memory, village, now + 3),
				revokedStock, null, stack -> stack.is(Items.STICK), 64)
				&& JobUtil.count(authorizedChest, stack -> stack.is(Items.OAK_PLANKS)) == 2,
				"revoked chest permission prevents recipe input withdrawal");
			diner.setAttached(VillagerPlus.CHEST_ACCESS, access);
			authorizedChest.clearContent();
			level.setBlock(authorizedChestPos, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(authorizedChestPos, Blocks.CHEST.defaultBlockState(), 3);
			Container replacementChest = (Container) level.getBlockEntity(authorizedChestPos);
			replacementChest.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
			check(de.villagerplus.village.ChestAccess.container(level, diner) == null,
				"a replacement chest at the same position does not inherit old access");
			var replacementStock = new net.minecraft.world.SimpleContainer(9);
			check(!RecipeCrafting.craft(new Ctx(level, diner, JobManager.state(diner), memory, village, now + 4),
				replacementStock, null, stack -> stack.is(Items.STICK), 64)
				&& JobUtil.count(replacementChest, stack -> stack.is(Items.OAK_PLANKS)) == 2,
				"a replacement chest cannot supply recipe inputs under the old grant");

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
				for (int dy = 0; dy <= 2; dy++) snapshot(level, before, floor.above(dy));
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
