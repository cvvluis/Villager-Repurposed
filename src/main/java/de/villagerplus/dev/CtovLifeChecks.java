package de.villagerplus.dev;

import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Workstation;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** B01, B04 and B05 against the same generated village, without moving the worker by hand. */
public final class CtovLifeChecks {
	private CtovLifeChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		var residents = level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(112), villager -> {
			if (!villager.isAlive()) return false;
			var meeting = villager.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT);
			return meeting.isEmpty() || (meeting.get().dimension() == level.dimension() && meeting.get().pos().equals(ref.bell()));
		});
		if (residents.isEmpty()) throw new IllegalStateException("CTOV life: no resident");
		BlockPos origin = ref.chestPos(level);
		if (origin == null) origin = ref.bell();
		BlockPos workOrigin = origin;
		Villager worker = residents.stream().min(java.util.Comparator.comparingDouble(v -> v.blockPosition().distSqr(workOrigin))).orElseThrow();
		worker.setInvulnerable(true);
		worker.setNoAi(false);
		int checks = 0;
		checks += workstation(level, ref, worker);
		checks += sign(level, ref, worker);
		checks += privateChest(level, ref, worker);
		return checks;
	}

	static int workstation(ServerLevel level, VillageRef ref, Villager worker) {
		Villages.ensureCenter(level, ref, worker, level.getGameTime());
		Container chest = ref.chest(level);
		if (chest == null) throw new IllegalStateException("CTOV life: chest missing");
		BlockPos origin = ref.chestPos(level);
		int furnaceStock = -1;
		BlockPos workerAt = worker.blockPosition();
		String route = "";
		if (Workstation.find(level, origin, Blocks.FURNACE, 8) == null) {
			List<ItemStack> original = new ArrayList<>();
			for (int slot = 0; slot < chest.getContainerSize(); slot++) original.add(chest.getItem(slot).copy());
			try {
				// Reserve deterministic recipe inputs even when the natural communal chest is full.
				chest.clearContent();
				add(chest, new ItemStack(Items.COBBLESTONE, 8));
				// The smith must craft a missing crafting table before the furnace.
				add(chest, new ItemStack(Items.OAK_PLANKS, 4));
				var state = JobManager.state(worker);
				for (int tick = 0; tick < 2000 && Workstation.find(level, origin, Blocks.FURNACE, 8) == null; tick++) {
					Workstation.ensure(new Ctx(level, worker, state, worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + tick), Blocks.FURNACE);
					level.tick(() -> false);
				}
				furnaceStock = JobUtil.count(chest, stack -> stack.is(Items.FURNACE));
				workerAt = worker.blockPosition();
				route = JobUtil.lastLongRouteDiagnostic(worker);
			} finally {
				for (int slot = 0; slot < original.size(); slot++) chest.setItem(slot, original.get(slot));
				chest.setChanged();
			}
		}
		check(Workstation.find(level, origin, Blocks.FURNACE, 8) != null,
			"B01 smith crafts and places a furnace beside the village chest (table="
				+ Workstation.find(level, origin, Blocks.CRAFTING_TABLE, 8) + " chest=" + origin + " furnaceStock=" + furnaceStock
				+ " worker=" + workerAt.toShortString() + " route=" + route + ")");
		System.out.println("CTOV LIFE PASS furnace");
		return 1;
	}

	private static int sign(ServerLevel level, VillageRef ref, Villager worker) {
		BlockPos sign = ref.bell().north(2);
		Villager resident = level.getEntitiesOfClass(Villager.class, new AABB(sign).inflate(40.0), Villager::isAlive).stream()
			.min(java.util.Comparator.comparingDouble(v -> v.blockPosition().distSqr(sign))).orElse(null);
		if (resident == null) throw new IllegalStateException("CTOV life: no resident within the house-sign lookup radius");
		var memory = resident.getAttachedOrCreate(VillagerPlus.MEMORY);
		if (memory.name == null || memory.name.isBlank()) memory.name = "CTOV-Bewohner";
		level.setBlock(sign.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(sign, de.villagerplus.block.ModBlocks.DORFSCHILD.defaultBlockState(), 3);
		memory.homeDoor = java.util.Optional.of(sign.south());
		String text = String.join("\n", de.villagerplus.block.DorfschildBlock.describe(level, sign));
		check((text.contains("Hier wohnt:") || text.contains("Haus der Familie:")) && text.contains(memory.name),
			"B04 house sign names the resident (" + text.replace('\n', ' ') + ")");
		System.out.println("CTOV LIFE PASS sign");
		return 1;
	}

	private static int privateChest(ServerLevel level, VillageRef ref, Villager worker) {
		BlockPos owned = worker.blockPosition().above();
		level.setBlock(owned, Blocks.CHEST.defaultBlockState(), 3);
		var entity = level.getBlockEntity(owned);
		entity.setAttached(VillagerPlus.OWNER, UUID.randomUUID());
		Container box = (Container) entity;
		box.setItem(0, new ItemStack(Items.WHEAT, 5));
		var ctx = new Ctx(level, worker, JobManager.state(worker), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
		BlockPos communal = JobUtil.communalDeliveryChest(ctx);
		check(communal != null && !communal.equals(owned), "B05 porter delivery target is the communal chest, not the private one");
		check(JobUtil.count(box, stack -> stack.is(Items.WHEAT)) == 5, "B05 private wheat stays in the owned chest");
		System.out.println("CTOV LIFE PASS private chest");
		return 2;
	}

	private static void add(Container chest, ItemStack stack) {
		for (int slot = 0; slot < chest.getContainerSize() && !stack.isEmpty(); slot++) {
			ItemStack current = chest.getItem(slot);
			if (current.isEmpty()) {
				chest.setItem(slot, stack.copy());
				return;
			}
			if (ItemStack.isSameItemSameComponents(current, stack) && current.getCount() < current.getMaxStackSize()) {
				int move = Math.min(stack.getCount(), current.getMaxStackSize() - current.getCount());
				current.grow(move);
				stack.shrink(move);
			}
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("CTOV life: " + message);
	}
}
