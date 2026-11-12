package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Fixture checks for the mine kit rule: a paved walkway strip never stops the digging (a village without oak planks must not
 * wait for one), a trip to the depot also carries the next complete structures, and the ten-minute fallback is per section and
 * runs no restock trip. Synthetic: green results are no proof of natural behaviour.
 */
public final class MineKitChecks {
	private MineKitChecks() { }

	public static int run(ServerLevel level, VillageRef ref, BlockPos bell) {
		Villager worker = null;
		int checks = 0;
		Mining.resetKitWaitForTest();
		try {
			BlockPos entrance = bell.offset(30, 0, 0);
			level.getChunk(entrance);
			level.getChunk(bell);
			Container chest = ref.chest(level);
			if (chest == null) throw new IllegalStateException("MINE KIT CHECK FAILED: fixture chest missing");
			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("MINE KIT CHECK FAILED: villager missing");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			BlockPos chestPos = ref.chestPos(level);
			worker.setPos(chestPos.getX() - 0.5, chestPos.getY(), chestPos.getZ() + 0.5);
			worker.setOnGround(true);
			level.addFreshEntity(worker);
			JobState state = new JobState();
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			VillageData.Mine mine = new VillageData.Mine(entrance, Direction.EAST.get2DDataValue(), 0, entrance.getY() - 3, false);
			ref.data().mine = Optional.of(mine);
			long now = level.getGameTime() + 1_000L;
			Ctx probe = new Ctx(level, worker, state, memory, ref, now);
			Mining.MineFrameMaterials materials = Mining.mineFrameMaterials(probe, mine);
			Item fence = materials.fenceItem();
			Item planks = materials.plankItem();
			boolean oak = planks == Items.OAK_PLANKS;

			// 1. Sections without structure (0 and 1) need nothing: not even the oak walkway plank a spruce village cannot make.
			chest.clearContent();
			worker.getInventory().clearContent();
			for (int index = 0; index <= 1; index++) {
				mine.index = index;
				var status = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now), ref, mine);
				check(status == Mining.SupplyStatus.READY && count(worker.getInventory(), Items.OAK_PLANKS) == 0 && isEmpty(chest),
					"section " + index + " needs no structure: digging starts at once without a walkway plank, got " + status); checks++;
			}

			// 2. A support section with an empty depot waits; the wait is per section and its fallback runs no restock trip.
			mine.index = 2;
			Mining.resetKitWaitForTest();
			var waiting = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now), ref, mine);
			check(waiting == Mining.SupplyStatus.WAITING && worker.getInventory().isEmpty(),
				"a support section without any depot stock waits, got " + waiting); checks++;
			Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 6000L), ref, mine);
			worker.setPos(chestPos.getX() + 40.5, chestPos.getY(), chestPos.getZ() + 0.5);
			var expired = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + Mining.KIT_WAIT_TICKS + 1L), ref, mine);
			check(expired == Mining.SupplyStatus.READY && Math.abs(worker.getX() - (chestPos.getX() + 40.5)) < 0.01
				&& worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET).isEmpty(),
				"after the fallback the section is released without sending the resident to the depot, got " + expired); checks++;
			mine.index = 3;
			var next = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + Mining.KIT_WAIT_TICKS + 2L), ref, mine);
			check(next != Mining.SupplyStatus.READY,
				"the fallback does not unlock the next structural section (a torch needs coal), got " + next); checks++;
			worker.setPos(chestPos.getX() - 0.5, chestPos.getY(), chestPos.getZ() + 0.5);

			// 3. One trip carries the next support, the following torch and the support after it: complete structures only.
			Mining.resetKitWaitForTest();
			mine.index = 2;
			worker.getInventory().clearContent();
			chest.clearContent();
			add(chest, fence, 12);
			add(chest, planks, 10);
			add(chest, Items.TORCH, 1);
			add(chest, Items.OAK_PLANKS, 3);
			var taken = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 10L), ref, mine);
			check(taken == Mining.SupplyStatus.BUSY && count(worker.getInventory(), fence) == 12
				&& count(worker.getInventory(), planks) >= 10 && count(worker.getInventory(), Items.TORCH) == 1
				&& count(worker.getInventory(), Items.OAK_PLANKS) >= 3 && count(chest, fence) == 0 && count(chest, Items.TORCH) == 0,
				"one depot trip takes the supports and the torch of the next three structural sections plus the walkway planks"
					+ " (fences " + count(worker.getInventory(), fence) + ", planks " + count(worker.getInventory(), planks)
					+ ", torches " + count(worker.getInventory(), Items.TORCH) + ", oak " + count(worker.getInventory(), Items.OAK_PLANKS) + ", " + taken + ")"); checks++;
			var ready = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 60L), ref, mine);
			check(ready == Mining.SupplyStatus.READY, "with the kit on board the section is dug, got " + ready); checks++;

			// 4. A partial second bay stays in the depot for the builders.
			Mining.resetKitWaitForTest();
			worker.getInventory().clearContent();
			chest.clearContent();
			add(chest, fence, 9);
			add(chest, planks, 7 + (oak ? 0 : 0));
			add(chest, Items.TORCH, 1);
			Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 100L), ref, mine);
			check(count(worker.getInventory(), fence) == 6 && count(worker.getInventory(), planks) == 5
				&& count(worker.getInventory(), Items.TORCH) == 1 && count(chest, fence) == 3 && count(chest, planks) == 2,
				"only complete structures are taken; the unfinished second bay stays in the depot (fences "
					+ count(worker.getInventory(), fence) + "/" + count(chest, fence) + ", planks " + count(worker.getInventory(), planks)
					+ "/" + count(chest, planks) + ")"); checks++;

			// 5. A miner who already carries the next section's structure does not leave.
			Mining.resetKitWaitForTest();
			worker.getInventory().clearContent();
			chest.clearContent();
			add(worker.getInventory(), fence, 6);
			add(worker.getInventory(), planks, 5);
			var carried = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 200L), ref, mine);
			check(carried == Mining.SupplyStatus.READY, "a miner that carries the next structure digs at once, got " + carried); checks++;
			return checks;
		} finally {
			if (worker != null) worker.discard();
			ref.data().mine = Optional.empty();
			Mining.resetKitWaitForTest();
		}
	}

	private static void add(Container container, Item item, int count) {
		net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, container, new ItemStack(item, count), null);
	}

	private static int count(Container container, Item item) {
		return JobUtil.count(container, stack -> stack.is(item));
	}

	private static boolean isEmpty(Container container) {
		return container.isEmpty();
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("MINE KIT CHECK FAILED: " + message);
	}
}
