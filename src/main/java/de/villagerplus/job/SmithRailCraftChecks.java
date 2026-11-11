package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Network;
import de.villagerplus.village.Roads;
import de.villagerplus.village.Specials;
import de.villagerplus.village.TradeNetwork;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Smith crafts rail materials and rolling stock for an approved metropolis link with low depot stock. */
public final class SmithRailCraftChecks {
	private SmithRailCraftChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		var ref = Checks090.fixtureForEquipment(level, at);
		BlockPos shed = at.east(24);
		BlockPos station = at.east(36);
		BlockPos bench = shed.east(3);
		BlockPos other = at.east(80);
		level.getChunkAt(bench);
		level.setBlock(bench, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
		level.setBlock(station.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(station, Blocks.RAIL.defaultBlockState(), 3);
		Specials.set(ref, Blueprints.TOOLSHED, shed);
		Specials.set(ref, Blueprints.STATION, station);
		Specials.set(ref, Blueprints.GRANARY, at.east(12));
		var box = ref.chest(level);
		List<ItemStack> originalStock = new java.util.ArrayList<>();
		for (int slot = 0; slot < box.getContainerSize(); slot++) originalStock.add(box.getItem(slot).copy());
		box.clearContent();
		box.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
		box.setItem(1, new ItemStack(Items.STICK, 16));
		box.setItem(2, new ItemStack(Items.GOLD_INGOT, 16));
		box.setItem(3, new ItemStack(Items.REDSTONE, 32));
		box.setItem(4, new ItemStack(Items.RAIL, 2));
		String key = Network.dim(level) + other.asLong();
		Network.save(ref.bellEntity(), new TradeNetwork(List.of(),
			List.of(new TradeNetwork.Link(Network.dim(level), other, "Rail craft check", Roads.METROPOLIS, true, 12, 0)),
			Map.of(), 0, 0));
		ref.bellEntity().setAttached(VillagerPlus.ROADS, Map.of(key, 40L));
		var villager = EntityTypes.VILLAGER.spawn(level, bench.south(), EntitySpawnReason.COMMAND);
		villager.addTag("vp090_fixture");
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		var state = new JobState();
		var memory = villager.getAttachedOrCreate(VillagerPlus.MEMORY);
		long now = level.getGameTime() + 1000;
		int checks = 0;
		try {
			Ctx ctx = new Ctx(level, villager, state, memory, ref, now);
			check(box.getItem(4).getCount() == 2 && JobUtil.count(villager.getInventory(), s -> s.is(Items.RAIL)) == 0,
				"distant smith does not craft or deliver rails remotely");
			checks++;
			villager.teleportTo(at.getX() + 2.5, at.getY(), at.getZ() + .5);
			for (int step = 0; step < 12 && JobUtil.count(villager.getInventory(), s -> s.is(Items.IRON_INGOT)) < 6; step++) {
				state = new JobState();
				ctx = new Ctx(level, villager, state, memory, ref, now + step);
				SmithRailCraft.work(ctx);
			}
			check(JobUtil.count(villager.getInventory(), s -> s.is(Items.IRON_INGOT)) == 6, "smith collects six iron ingots from communal storage");
			checks++;
			for (int step = 0; step < 4 && JobUtil.count(villager.getInventory(), s -> s.is(Items.STICK)) < 1; step++) {
				state = new JobState();
				ctx = new Ctx(level, villager, state, memory, ref, now + 20 + step);
				SmithRailCraft.work(ctx);
			}
			check(JobUtil.count(villager.getInventory(), s -> s.is(Items.STICK)) == 1, "smith collects one stick for the rail recipe");
			checks++;
			villager.teleportTo(bench.getX() + .5, bench.getY(), bench.getZ() + 2.5);
			state = new JobState();
			ctx = new Ctx(level, villager, state, memory, ref, now + 40);
			SmithRailCraft.work(ctx);
			check(JobUtil.count(villager.getInventory(), s -> s.is(Items.RAIL)) == 16
				&& JobUtil.count(villager.getInventory(), s -> s.is(Items.IRON_INGOT) || s.is(Items.STICK)) == 0,
				"loaded rail recipe assembles sixteen rails at the workbench");
			checks++;
			int railsBeforeDelivery = JobUtil.count(box, s -> s.is(Items.RAIL));
			villager.teleportTo(at.getX() + 2.5, at.getY(), at.getZ() + .5);
			for (int step = 0; step < 6 && JobUtil.count(villager.getInventory(), s -> s.is(Items.RAIL)) > 0; step++) {
				state = new JobState();
				ctx = new Ctx(level, villager, state, memory, ref, now + 80 + step);
				SmithRailCraft.work(ctx);
			}
			check(JobUtil.count(villager.getInventory(), s -> s.is(Items.RAIL)) == 0
				&& JobUtil.count(box, s -> s.is(Items.RAIL)) >= railsBeforeDelivery + 16,
				"finished rails return to village storage only after arrival");
			checks++;

			// The normal dispatch scheduler consumes actual rolling stock from each station. Build that stock
			// through the loaded recipes so a naturally developed rail link does not require a player to seed carts.
			box.clearContent();
			box.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
			box.setItem(1, new ItemStack(Items.GOLD_INGOT, 16));
			box.setItem(2, new ItemStack(Items.REDSTONE, 32));
			box.setItem(3, new ItemStack(Items.RAIL, 16));
			box.setItem(4, new ItemStack(Items.POWERED_RAIL, 8));
			box.setItem(5, new ItemStack(Items.REDSTONE_BLOCK, 4));
			box.setItem(6, new ItemStack(Items.CHEST, 2));
			box.setChanged();
			villager.getInventory().clearContent();
			check(SmithRailCraft.firstShortage(level, ref) == Items.MINECART,
				"smith prioritizes base minecarts before chest minecarts when a new rail link has no vehicles");
			checks++;
			for (int made = 1; made <= 4; made++) {
				craftAndDeliver(level, villager, ref, bench, at, Items.MINECART, now + 120 + made * 20);
				check(JobUtil.count(box, s -> s.is(Items.MINECART)) == made,
					"smith crafts and deposits real passenger/freight minecart stock " + made + "/4");
				checks++;
			}
			checkLoadedChestMinecartRecipe(level, box);
			checks += 3;
			check(SmithRailCraft.firstShortage(level, ref) == Items.CHEST_MINECART,
				"four base minecarts leave two passenger carts in reserve while chest-cart production begins");
			checks++;
			for (int made = 1; made <= 2; made++) {
				while (JobUtil.count(box, s -> s.is(Items.MINECART)) < 4) {
					craftAndDeliver(level, villager, ref, bench, at, Items.MINECART, now + 240 + made * 30);
				}
				craftAndDeliver(level, villager, ref, bench, at, Items.CHEST_MINECART, now + 260 + made * 30);
				check(JobUtil.count(box, s -> s.is(Items.CHEST_MINECART)) == made,
					"smith crafts and deposits real freight minecart stock " + made + "/2");
				checks++;
			}
			while (JobUtil.count(box, s -> s.is(Items.MINECART)) < 4) {
				craftAndDeliver(level, villager, ref, bench, at, Items.MINECART, now + 340);
			}
			check(SmithRailCraft.firstShortage(level, ref) == null
				&& JobUtil.count(box, s -> s.is(Items.MINECART)) == 4
				&& JobUtil.count(box, s -> s.is(Items.CHEST_MINECART)) == 2
				&& JobUtil.count(box, s -> s.is(Items.IRON_INGOT)) == 2
				&& JobUtil.count(box, s -> s.is(Items.CHEST)) == 0,
				"real loaded recipes fund two freight carts and retain two passenger carts without losing materials");
			checks++;
			return checks;
		} finally {
			villager.discard();
			for (int slot = 0; slot < originalStock.size(); slot++) box.setItem(slot, originalStock.get(slot));
			box.setChanged();
			Specials.remove(ref, Blueprints.TOOLSHED);
			Specials.remove(ref, Blueprints.STATION);
			Specials.remove(ref, Blueprints.GRANARY);
			for (BlockPos pos : new BlockPos[] {bench, station, station.below()}) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new IllegalStateException("SMITH RAIL CRAFT: " + message);
		}
	}

	private static void checkLoadedChestMinecartRecipe(ServerLevel level, net.minecraft.world.Container stock) {
		var holder = level.recipeAccess().getRecipes().stream()
			.filter(entry -> entry.id().identifier().toString().equals("minecraft:chest_minecart"))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("SMITH RAIL CRAFT: loaded minecraft:chest_minecart recipe is missing"));
		if (!(holder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe recipe)) {
			throw new IllegalStateException("SMITH RAIL CRAFT: loaded minecraft:chest_minecart is not a crafting recipe");
		}
		check(JobUtil.count(stock, stack -> stack.is(Items.CHEST)) > 0
			&& JobUtil.count(stock, stack -> stack.is(Items.MINECART)) > 0,
			"the public stock contains the real chest and minecart ingredients");
		checkApiRecipe(recipe, level, stock);
	}

	private static void checkApiRecipe(net.minecraft.world.item.crafting.CraftingRecipe recipe, ServerLevel level,
			net.minecraft.world.Container stock) {
		var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 1,
			List.of(oneCopy(stock, Items.CHEST), oneCopy(stock, Items.MINECART)));
		check(recipe.matches(input, level), "loaded chest-minecart recipe matches chest + minecart crafting input");
		check(recipe.assemble(input).is(Items.CHEST_MINECART),
			"loaded chest-minecart recipe assembles the chest-minecart result from those ingredients");
	}

	private static ItemStack oneCopy(net.minecraft.world.Container stock, Item item) {
		for (int slot = 0; slot < stock.getContainerSize(); slot++) {
			ItemStack stack = stock.getItem(slot);
			if (!stack.isEmpty() && stack.is(item)) return stack.copyWithCount(1);
		}
		return ItemStack.EMPTY;
	}

	private static void craftAndDeliver(ServerLevel level, Villager villager, de.villagerplus.village.VillageRef ref,
			BlockPos bench, BlockPos home, Item target, long now) {
		var inventory = villager.getInventory();
		inventory.setChanged();
		villager.teleportTo(home.getX() + 2.5, home.getY(), home.getZ() + .5);
		for (int attempt = 0; attempt < 8 && JobUtil.count(inventory, stack -> stack.is(target)) == 0; attempt++) {
			SmithRailCraft.work(new Ctx(level, villager,
				new JobState(), villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now + attempt));
		}
		if (JobUtil.count(inventory, stack -> stack.is(target)) == 0) {
			villager.teleportTo(bench.getX() + .5, bench.getY(), bench.getZ() + .5);
			for (int attempt = 0; attempt < 4 && JobUtil.count(inventory, stack -> stack.is(target)) == 0; attempt++) {
				SmithRailCraft.work(new Ctx(level, villager, new JobState(),
					villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now + 8 + attempt));
			}
		}
		if (JobUtil.count(inventory, stack -> stack.is(target)) == 0) {
			String cargo = target == Items.CHEST_MINECART
				? " [carriedChest=" + JobUtil.count(inventory, stack -> stack.is(Items.CHEST))
					+ ", carriedMinecart=" + JobUtil.count(inventory, stack -> stack.is(Items.MINECART))
					+ ", storedChest=" + SmithRailCraft.stock(level, ref, Items.CHEST)
					+ ", storedMinecart=" + SmithRailCraft.stock(level, ref, Items.MINECART)
					+ ", storedChestMinecart=" + SmithRailCraft.stock(level, ref, Items.CHEST_MINECART)
					+ ", nextShortage=" + SmithRailCraft.firstShortage(level, ref) + "]"
				: "";
			throw new IllegalStateException("SMITH RAIL CRAFT: loaded recipe did not produce " + target + cargo);
		}
		villager.teleportTo(home.getX() + 2.5, home.getY(), home.getZ() + .5);
		for (int attempt = 0; attempt < 4 && JobUtil.count(inventory, stack -> stack.is(target)) > 0; attempt++) {
			SmithRailCraft.work(new Ctx(level, villager, new JobState(),
				villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now + 16 + attempt));
		}
		if (JobUtil.count(inventory, stack -> stack.is(target)) > 0) {
			throw new IllegalStateException("SMITH RAIL CRAFT: produced vehicle was not deposited");
		}
	}
}
