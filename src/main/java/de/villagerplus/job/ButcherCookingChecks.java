package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
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
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Synthetic checks for the butcher's afternoon cooking of raw meat that lies in the public FOOD store (granary). The central
 * material chest refuses food, so slaughter loot sits in the granary; the butcher smokes it in the marked kitchen smoker with
 * fuel from public stock only and returns the cooked meat to the granary. The smoker is ticked with the real vanilla furnace
 * logic. Counts are conserved, no meat or fuel is created, private chests stay untouched. Raw meat in the village chest keeps
 * the existing path. Synthetic fixture: no natural evidence.
 */
public final class ButcherCookingChecks {
	private ButcherCookingChecks() { }

	/** @param chunkOrigin chunk aligned (multiple of 16 on X and Z) */
	public static int run(ServerLevel level, BlockPos chunkOrigin) {
		if (Math.floorMod(chunkOrigin.getX(), 16) != 0 || Math.floorMod(chunkOrigin.getZ(), 16) != 0) {
			throw new IllegalStateException("BUTCHER COOKING CHECK FAILED: origin must be chunk aligned");
		}
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		List<Entity> spawned = new ArrayList<>();
		int checks = 0;
		VillageRef ref = null;
		Map<String, BlockPos> oldSpecials = null;
		try {
			level.getChunkAt(chunkOrigin);
			BlockState air = Blocks.AIR.defaultBlockState();
			BlockState stone = Blocks.STONE.defaultBlockState();
			for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) for (int dy = -2; dy <= 5; dy++) {
				BlockPos p = chunkOrigin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.removeBlockEntity(p);
				level.setBlock(p, dy < 0 ? stone : air, 3);
			}
			BlockPos bell = chunkOrigin.offset(1, 0, 1);
			ref = Checks090.fixtureForEquipment(level, bell);
			oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
			Container village = ref.chest(level);
			require(village != null, "fixture village chest exists");
			village.clearContent();

			// Public food store: one barrel far enough from the village chest that only it belongs to the granary scan.
			BlockPos granaryPos = chunkOrigin.offset(12, 0, 12);
			level.setBlock(granaryPos, Blocks.BARREL.defaultBlockState(), 3);
			Container granary = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, granaryPos);
			require(granary != null, "fixture granary exists");
			granary.clearContent();
			Specials.set(ref, Blueprints.GRANARY, granaryPos);
			require(Specials.containers(level, ref, Blueprints.GRANARY).size() == 1
				&& Specials.containers(level, ref, Blueprints.GRANARY).contains(granary),
				"fixture: the granary scan sees exactly the barrel, not the village chest"); checks++;

			// A private chest inside the granary area holds raw meat and coal; the butcher must never touch it.
			BlockPos privatePos = chunkOrigin.offset(14, 0, 9);
			level.setBlock(privatePos, Blocks.CHEST.defaultBlockState(), 3);
			level.getBlockEntity(privatePos).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			Container privateBox = (Container) level.getBlockEntity(privatePos);
			privateBox.setItem(0, new ItemStack(Items.BEEF, 8));
			privateBox.setItem(1, new ItemStack(Items.COAL, 8));

			Villager butcher = EntityTypes.VILLAGER.spawn(level, chunkOrigin.offset(9, 0, 7), EntitySpawnReason.COMMAND);
			if (butcher == null) throw new IllegalStateException("BUTCHER COOKING CHECK FAILED: butcher did not spawn");
			spawned.add(butcher);
			butcher.addTag("vp090_fixture");
			butcher.setNoAi(true);
			butcher.setInvulnerable(true);
			butcher.setVillagerData(butcher.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.BUTCHER));
			VillagerMemory memory = butcher.getAttachedOrCreate(VillagerPlus.MEMORY);
			ButcherJob job = new ButcherJob();
			long now = level.getGameTime() + 1;

			BlockPos kitchenPos = chunkOrigin.offset(8, 0, 4);
			BlockPos smokerPos = chunkOrigin.offset(9, 0, 5);
			butcher.teleportTo(smokerPos.getX() + 0.5, smokerPos.getY(), smokerPos.getZ() + 1.5);

			// 1. Raw meat only in the granary, fuel in public stock, but no kitchen at all: nothing is cooked.
			granary.setItem(0, new ItemStack(Items.BEEF, 6));
			village.setItem(0, new ItemStack(Items.COAL, 4));
			Snapshot start = Snapshot.of(granary, village, privateBox);
			boolean worked = job.secondary(ctx(level, butcher, new JobState(), memory, ref, now));
			require(!worked && Snapshot.of(granary, village, privateBox).same(start),
				"without a kitchen smoker the butcher cooks nothing and moves nothing"); checks++;

			// 2. A kitchen with only a plain furnace (no smoker): still nothing.
			Specials.set(ref, Blueprints.KITCHEN, kitchenPos);
			level.setBlock(smokerPos, Blocks.FURNACE.defaultBlockState(), 3);
			markFurnace(level, smokerPos);
			worked = job.secondary(ctx(level, butcher, new JobState(), memory, ref, now + 10));
			require(!worked && Snapshot.of(granary, village, privateBox).same(start)
				&& furnace(level, smokerPos).getItem(0).isEmpty(),
				"a plain furnace is not used for smoking: nothing is cooked and the furnace stays empty"); checks++;

			// 3. Smoker present, raw meat in the granary, but no public fuel: no batch is parked in the smoker.
			level.setBlock(smokerPos, Blocks.SMOKER.defaultBlockState(), 3);
			markFurnace(level, smokerPos);
			village.clearContent();
			Snapshot noFuel = Snapshot.of(granary, village, privateBox);
			worked = job.secondary(ctx(level, butcher, new JobState(), memory, ref, now + 20));
			AbstractFurnaceBlockEntity smoker = furnace(level, smokerPos);
			require(!worked && Snapshot.of(granary, village, privateBox).same(noFuel)
				&& smoker.getItem(0).isEmpty() && smoker.getItem(1).isEmpty() && smoker.getItem(2).isEmpty()
				&& smoker.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty(),
				"without public fuel the butcher takes no raw meat out of the granary (granary beef "
					+ count(granary, Items.BEEF) + ")"); checks++;
			require(count(privateBox, Items.COAL) == 8, "the coal in the private chest is not public fuel and stays untouched"); checks++;

			// 4. Granary completely full of other food: the cooked result would not fit, so nothing is started.
			village.setItem(0, new ItemStack(Items.COAL, 4));
			List<ItemStack> granaryBefore = copy(granary);
			for (int i = 1; i < granary.getContainerSize(); i++) granary.setItem(i, new ItemStack(Items.BREAD, 64));
			Snapshot full = Snapshot.of(granary, village, privateBox);
			worked = job.secondary(ctx(level, butcher, new JobState(), memory, ref, now + 30));
			require(!worked && Snapshot.of(granary, village, privateBox).same(full) && smoker.getItem(0).isEmpty(),
				"a granary without room for the cooked result starts no batch"); checks++;
			restore(granary, granaryBefore);

			// 5. Real cycle: granary raw beef + public coal. Every step keeps all items in real inventories.
			Snapshot ready = Snapshot.of(granary, village, privateBox);
			int rawTotal = count(granary, Items.BEEF);
			require(rawTotal == 6 && count(village, Items.COAL) == 4 && count(granary, Items.COOKED_BEEF) == 0, "cycle fixture is as expected");
			JobState state = new JobState();
			long t = now + 100;
			worked = job.secondary(ctx(level, butcher, state, memory, ref, t++));
			require(worked && count(granary, Items.BEEF) == 5 && smoker.getItem(0).is(Items.BEEF)
				&& smoker.getAttached(VillagerPlus.SMITH_FURNACE).is(Items.COOKED_BEEF)
				&& count(village, Items.COAL) == 4,
				"first work tick moves exactly one raw beef from the granary into the smoker and claims the cooked output"); checks++;
			worked = job.secondary(ctx(level, butcher, state, memory, ref, t++));
			require(worked && smoker.getItem(1).is(Items.COAL) && smoker.getItem(1).getCount() == 1 && count(village, Items.COAL) == 3,
				"second work tick takes exactly one coal from the public village stock into the smoker"); checks++;
			require(count(privateBox, Items.BEEF) == 8 && count(privateBox, Items.COAL) == 8,
				"the private chest keeps its raw meat and coal during the cycle"); checks++;
			tickFurnace(level, smokerPos, 160);
			smoker = furnace(level, smokerPos);
			require(smoker.getItem(2).is(Items.COOKED_BEEF) && smoker.getItem(2).getCount() == 1 && smoker.getItem(0).isEmpty(),
				"the real vanilla smoker logic cooked the beef (output " + smoker.getItem(2) + ")"); checks++;
			worked = job.secondary(ctx(level, butcher, state, memory, ref, t++));
			require(worked && count(granary, Items.COOKED_BEEF) == 1 && smoker.getItem(2).isEmpty()
				&& smoker.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty()
				&& count(granary, Items.BEEF) == 5 && count(village, Items.COOKED_BEEF) == 0,
				"the butcher delivers the cooked beef into the granary food store and releases the claim"); checks++;

			// 6. Run the remaining raw meat through the same production path; totals stay conserved at every step.
			boolean conserved = true;
			boolean fuelOnlyPublic = true;
			int guard = 0;
			while (count(granary, Items.BEEF) > 0 || !smoker.getItem(0).isEmpty() || !smoker.getItem(2).isEmpty()) {
				if (guard++ > 60) throw new IllegalStateException("BUTCHER COOKING CHECK FAILED: cycle did not finish within 60 steps");
				job.secondary(ctx(level, butcher, state, memory, ref, t++));
				state.idleUntil[1] = 0L;
				tickFurnace(level, smokerPos, 120);
				smoker = furnace(level, smokerPos);
				int inGranary = count(granary, Items.BEEF) + count(granary, Items.COOKED_BEEF);
				int inSmoker = smoker.getItem(0).is(Items.BEEF) ? smoker.getItem(0).getCount() : 0;
				inSmoker += smoker.getItem(2).is(Items.COOKED_BEEF) ? smoker.getItem(2).getCount() : 0;
				if (inGranary + inSmoker != rawTotal || count(village, Items.BEEF) + count(village, Items.COOKED_BEEF) != 0) conserved = false;
				if (count(privateBox, Items.BEEF) != 8 || count(privateBox, Items.COAL) != 8) fuelOnlyPublic = false;
			}
			require(conserved, "at every step raw beef plus cooked beef stays exactly " + rawTotal + " (nothing created, nothing lost)"); checks++;
			require(count(granary, Items.COOKED_BEEF) == rawTotal && count(granary, Items.BEEF) == 0,
				"all " + rawTotal + " raw beef ended up as cooked beef in the granary"); checks++;
			int coalPublic = count(village, Items.COAL);
			int coalSmoker = smoker.getItem(1).is(Items.COAL) ? smoker.getItem(1).getCount() : 0;
			require(coalPublic < 4 && coalPublic + coalSmoker <= 3 && fuelOnlyPublic,
				"fuel only ever came from public stock (village coal 4 -> " + coalPublic + ", in smoker slot " + coalSmoker
					+ ", private chest 8 -> " + count(privateBox, Items.COAL) + ")"); checks++;
			require(count(privateBox, Items.BEEF) == 8 && count(privateBox, Items.COAL) == 8, "the private chest is untouched at the end"); checks++;

			// 7. The central material chest cannot hold food (direct writes are refused by the material-store guard), so the
			// village-chest cooking path has nothing to do for slaughter loot; it stays as it was and is tried first.
			granary.clearContent();
			village.clearContent();
			village.setItem(0, new ItemStack(Items.PORKCHOP, 3));
			require(count(village, Items.PORKCHOP) == 0, "the guarded central chest refuses raw meat written directly"); checks++;
			return checks;
		} finally {
			spawned.forEach(Entity::discard);
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, 3));
			if (ref != null) {
				if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS);
				else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			}
		}
	}

	private static void markFurnace(ServerLevel level, BlockPos pos) {
		AbstractFurnaceBlockEntity furnace = furnace(level, pos);
		furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
		furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
		furnace.setChanged();
	}

	private static AbstractFurnaceBlockEntity furnace(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
			throw new IllegalStateException("BUTCHER COOKING CHECK FAILED: no furnace block entity at " + pos.toShortString());
		}
		return furnace;
	}

	/** Runs the real vanilla furnace/smoker tick; the butcher never advances cooking progress himself. */
	private static void tickFurnace(ServerLevel level, BlockPos pos, int ticks) {
		for (int i = 0; i < ticks; i++) {
			AbstractFurnaceBlockEntity furnace = furnace(level, pos);
			AbstractFurnaceBlockEntity.serverTick(level, pos, level.getBlockState(pos), furnace);
		}
	}

	private static List<ItemStack> copy(Container container) {
		List<ItemStack> out = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) out.add(container.getItem(i).copy());
		return out;
	}

	private static void restore(Container container, List<ItemStack> items) {
		for (int i = 0; i < items.size(); i++) container.setItem(i, items.get(i).copy());
		container.setChanged();
	}

	private record Snapshot(List<ItemStack> granary, List<ItemStack> village, List<ItemStack> privateBox) {
		static Snapshot of(Container granary, Container village, Container privateBox) {
			return new Snapshot(copy(granary), copy(village), copy(privateBox));
		}

		boolean same(Snapshot other) {
			return equal(granary, other.granary) && equal(village, other.village) && equal(privateBox, other.privateBox);
		}

		private static boolean equal(List<ItemStack> a, List<ItemStack> b) {
			if (a.size() != b.size()) return false;
			for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
			return true;
		}
	}

	private static Ctx ctx(ServerLevel level, Villager worker, JobState state, VillagerMemory memory, VillageRef ref, long now) {
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static int count(Container container, Item item) {
		return JobUtil.count(container, stack -> stack.is(item));
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("BUTCHER COOKING CHECK FAILED: " + label);
	}
}
