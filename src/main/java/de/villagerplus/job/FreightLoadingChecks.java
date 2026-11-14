package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.RailTransport;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Synthetic checks for {@link FreightLoading#work}: a porter loads a docked chest minecart at the station from real
 * public inventories (food from the granary, then public material, then the station store), never from private chests,
 * and every item that leaves a store arrives in the cart (conservation across all containers). The terrain is a flat
 * platform with a fixture bell; everything is restored afterwards. Fixtures are synthetic, not natural evidence.
 */
public final class FreightLoadingChecks {
	private FreightLoadingChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		List<Entity> spawned = new ArrayList<>();
		int checks = 0;
		try {
			for (int dx = -4; dx <= 34; dx += 8) for (int dz = -14; dz <= 14; dz += 14) level.getChunk(origin.offset(dx, 0, dz));
			BlockState air = Blocks.AIR.defaultBlockState();
			BlockState stone = Blocks.STONE.defaultBlockState();
			for (int dx = -4; dx <= 34; dx++) for (int dz = -14; dz <= 14; dz++) for (int dy = -2; dy <= 5; dy++) {
				BlockPos p = origin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.removeBlockEntity(p);
				level.setBlock(p, dy < 0 ? stone : air, 3);
			}
			VillageRef ref = Checks090.fixtureForEquipment(level, origin);
			BlockPos station = origin.east(16);
			BlockPos rail = station.south(2);
			BlockPos granaryPos = station.north(6);
			BlockPos storePos = station.south(5);
			BlockPos privatePos = station.west(4);
			level.setBlock(rail, Blocks.RAIL.defaultBlockState(), 3);
			level.setBlock(granaryPos, Blocks.BARREL.defaultBlockState(), 3);
			level.setBlock(storePos, Blocks.CHEST.defaultBlockState(), 3);
			level.setBlock(privatePos, Blocks.CHEST.defaultBlockState(), 3);
			level.getBlockEntity(privatePos).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			Specials.set(ref, Blueprints.STATION, station);
			Specials.set(ref, Blueprints.GRANARY, granaryPos);

			Container village = ref.chest(level);
			Container granary = HopperBlockEntity.getContainerAt(level, granaryPos);
			Container store = HopperBlockEntity.getContainerAt(level, storePos);
			Container privateChest = HopperBlockEntity.getContainerAt(level, privatePos);
			require(village != null && granary != null && store != null && privateChest != null, "fixture containers exist");
			village.clearContent();
			granary.clearContent();
			store.clearContent();
			privateChest.clearContent();
			granary.setItem(0, new ItemStack(Items.BREAD, 40));
			village.setItem(1, new ItemStack(Items.OAK_LOG, 20));
			store.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
			privateChest.setItem(0, new ItemStack(Items.BREAD, 30));
			privateChest.setItem(1, new ItemStack(Items.IRON_INGOT, 20));
			Container[] all = {granary, village, store, privateChest};

			Villager porter = EntityTypes.VILLAGER.spawn(level, rail.east(2), EntitySpawnReason.COMMAND);
			if (porter == null) throw new IllegalStateException("FREIGHT CHECK FAILED: could not spawn porter");
			spawned.add(porter);
			porter.addTag("vp090_fixture");
			porter.setNoAi(true);
			porter.setInvulnerable(true);
			JobState state = JobManager.state(porter);
			var memory = porter.getAttachedOrCreate(VillagerPlus.MEMORY);
			long now = level.getGameTime() + 1;

			// A: only a foreign minecart (no trip) and a docked cart far beyond the 8 block station radius: nothing to load.
			MinecartChest foreign = cart(level, rail, spawned);
			MinecartChest far = cart(level, station.east(14).south(2), spawned);
			far.setAttached(RailTransport.TRIP, trip(ref, rail, false));
			porter.teleportTo(rail.getX() + 2.5, rail.getY(), rail.getZ() + 0.5);
			require(!FreightLoading.docked(foreign) && FreightLoading.docked(far), "docked() needs a stopped trip");
			checks++;
			require(!FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && stockSum(Items.BREAD, all) == 70
				&& count(far, Items.BREAD) == 0 && count(foreign, Items.BREAD) == 0,
				"a trip-less cart and a docked cart outside the station radius are not loaded"); checks++;
			foreign.discard();
			spawned.remove(foreign);
			far.discard();
			spawned.remove(far);

			// B: a travelling cart is not loaded.
			MinecartChest cart = cart(level, rail, spawned);
			cart.setAttached(RailTransport.TRIP, trip(ref, rail, true));
			require(!FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && count(granary, Items.BREAD) == 40 && countAll(cart) == 0,
				"a travelling minecart is not loaded"); checks++;

			// C: a docked cart with a far-away porter: the porter walks, nothing is moved yet.
			cart.setAttached(RailTransport.TRIP, trip(ref, rail, false));
			porter.teleportTo(rail.getX() + 30.5, rail.getY(), rail.getZ() + 0.5);
			require(FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && countAll(cart) == 0 && count(granary, Items.BREAD) == 40
				&& state.activity.contains("Güterlore"), "a distant porter heads for the cart and moves no item yet"); checks++;

			// D: near the cart the porter moves sixteen items; food comes from the public granary first.
			porter.teleportTo(rail.getX() + 1.5, rail.getY(), rail.getZ() + 0.5);
			state.clearTarget();
			int jobsBefore = memory.jobsDone;
			require(FreightLoading.work(ctx(level, porter, state, memory, ref, now))
				&& count(cart, Items.BREAD) == 16 && count(granary, Items.BREAD) == 24, "sixteen bread move from the granary into the cart"); checks++;
			require(count(village, Items.OAK_LOG) == 20 && count(store, Items.IRON_INGOT) == 10 && memory.jobsDone == jobsBefore + 1 && state.cooldownUntil == now + 20,
				"material stock stays put while food is available; job counter and cooldown advance [logs=" + count(village, Items.OAK_LOG)
					+ ", ingots=" + count(store, Items.IRON_INGOT)
					+ ", jobs=" + memory.jobsDone + "/" + jobsBefore + ", cooldown=" + state.cooldownUntil + "/" + (now + 20) + "]"); checks++;
			require(FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && count(cart, Items.BREAD) == 32
				&& count(granary, Items.BREAD) == 8, "second trip moves the next sixteen bread"); checks++;
			require(FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && count(cart, Items.BREAD) == 40
				&& count(granary, Items.BREAD) == 0, "the last eight bread move as a partial stack"); checks++;
			require(count(privateChest, Items.BREAD) == 30 && count(privateChest, Items.IRON_INGOT) == 20,
				"a private chest in the station area is never a cargo source"); checks++;

			// E: no public food left: public material is loaded next (central chest before the station store).
			require(FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && count(cart, Items.OAK_LOG) == 16
				&& count(village, Items.OAK_LOG) == 4 && count(cart, Items.IRON_INGOT) == 0,
				"public material comes from the central chest once the granary is empty"); checks++;
			require(FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && count(cart, Items.OAK_LOG) == 20
				&& count(village, Items.OAK_LOG) == 0, "the remaining logs follow"); checks++;
			require(FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && count(cart, Items.IRON_INGOT) == 10
				&& count(store, Items.IRON_INGOT) == 0, "the station store is the last public material source"); checks++;
			require(!FreightLoading.work(ctx(level, porter, state, memory, ref, now))
				&& count(privateChest, Items.BREAD) == 30
				&& count(privateChest, Items.IRON_INGOT) == 20 && countAll(cart) == 40 + 20 + 10,
				"with only private stock left nothing more is loaded"); checks++;
			require(stockSum(Items.BREAD, all) + count(cart, Items.BREAD) == 70 && stockSum(Items.OAK_LOG, all) + count(cart, Items.OAK_LOG) == 20
				&& stockSum(Items.IRON_INGOT, all) + count(cart, Items.IRON_INGOT) == 30,
				"every item is accounted for: nothing created, nothing lost"); checks++;

			// F: a cart without a free slot is skipped; with one free slot a loaded item still fits.
			cart.clearContent();
			for (int slot = 0; slot < cart.getContainerSize(); slot++) cart.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			granary.setItem(0, new ItemStack(Items.BREAD, 5));
			require(!FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && count(granary, Items.BREAD) == 5,
				"a cart without any free slot is not loaded"); checks++;
			cart.setItem(26, ItemStack.EMPTY);
			require(FreightLoading.work(ctx(level, porter, state, memory, ref, now)) && count(cart, Items.BREAD) == 5
				&& count(granary, Items.BREAD) == 0 && count(cart, Items.COBBLESTONE) == 26 * 64,
				"a cart with one free slot takes the cargo without disturbing its other contents"); checks++;
			return checks;
		} finally {
			spawned.forEach(Entity::discard);
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, 3));
		}
	}

	private static MinecartChest cart(ServerLevel level, BlockPos rail, List<Entity> spawned) {
		MinecartChest cart = new MinecartChest(EntityTypes.CHEST_MINECART, level);
		cart.setPos(rail.getX() + 0.5, rail.getY(), rail.getZ() + 0.5);
		if (!level.addFreshEntity(cart)) throw new IllegalStateException("FREIGHT CHECK FAILED: minecart was not admitted");
		spawned.add(cart);
		return cart;
	}

	private static RailTransport.Trip trip(VillageRef ref, BlockPos rail, boolean travelling) {
		BlockPos other = ref.bell().east(60);
		return new RailTransport.Trip(ref.bell(), other, rail, other, false, travelling, !travelling, 0, 0);
	}

	private static Ctx ctx(ServerLevel level, Villager worker, JobState state, de.villagerplus.VillagerMemory memory, VillageRef ref, long now) {
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static int count(Container container, Item item) {
		return JobUtil.count(container, stack -> stack.is(item));
	}

	private static int countAll(Container container) {
		return JobUtil.count(container, stack -> true);
	}

	private static int stockSum(Item item, Container... containers) {
		int sum = 0;
		for (Container container : containers) sum += count(container, item);
		return sum;
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("FREIGHT CHECK FAILED: " + label);
	}
}
