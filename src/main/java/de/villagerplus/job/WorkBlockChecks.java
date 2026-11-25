package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** B01: every vanilla profession without its work block procures materials, crafts it at a table and places it. */
public final class WorkBlockChecks {
	private WorkBlockChecks() { }

	/** Raw materials per profession (as a player would put them into the village chest). */
	private static Map<net.minecraft.resources.ResourceKey<VillagerProfession>, Object[]> materials() {
		return Map.ofEntries(
			Map.entry(VillagerProfession.FARMER, new Object[] {Items.OAK_SLAB, 7}),
			Map.entry(VillagerProfession.SHEPHERD, new Object[] {Items.STRING, 2, Items.OAK_PLANKS, 2}),
			Map.entry(VillagerProfession.BUTCHER, new Object[] {Items.FURNACE, 1, Items.OAK_LOG, 4}),
			Map.entry(VillagerProfession.ARMORER, new Object[] {Items.IRON_INGOT, 5, Items.FURNACE, 1, Items.SMOOTH_STONE, 3}),
			Map.entry(VillagerProfession.WEAPONSMITH, new Object[] {Items.STICK, 2, Items.STONE_SLAB, 1, Items.OAK_PLANKS, 2}),
			Map.entry(VillagerProfession.TOOLSMITH, new Object[] {Items.IRON_INGOT, 2, Items.OAK_PLANKS, 4}),
			Map.entry(VillagerProfession.CLERIC, new Object[] {Items.BLAZE_ROD, 1, Items.COBBLESTONE, 3}),
			Map.entry(VillagerProfession.MASON, new Object[] {Items.STONE, 3, Items.IRON_INGOT, 1}),
			Map.entry(VillagerProfession.FISHERMAN, new Object[] {Items.OAK_PLANKS, 6, Items.OAK_SLAB, 2}),
			Map.entry(VillagerProfession.FLETCHER, new Object[] {Items.FLINT, 2, Items.OAK_PLANKS, 4}),
			Map.entry(VillagerProfession.LEATHERWORKER, new Object[] {Items.IRON_INGOT, 7}),
			Map.entry(VillagerProfession.CARTOGRAPHER, new Object[] {Items.PAPER, 2, Items.OAK_PLANKS, 4}),
			Map.entry(VillagerProfession.LIBRARIAN, new Object[] {Items.BOOKSHELF, 1, Items.OAK_SLAB, 4}));
	}

	public static int run(ServerLevel level, BlockPos at) {
		int passed = 0;
		List<Entity> spawned = new ArrayList<>();
		VillageRef ref = Checks090.fixtureForEquipment(level, at);
		BlockPos chestPos = ref.chestPos(level);
		Container chest = ref.chest(level);
		List<ItemStack> chestBefore = new ArrayList<>();
		for (int i = 0; i < chest.getContainerSize(); i++) chestBefore.add(chest.getItem(i).copy());
		List<BlockPos> placed = new ArrayList<>();
		try {
			for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
				BlockPos floor = chestPos.offset(dx, -1, dz);
				level.getChunk(floor);
				if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
				level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
			}
			BlockPos table = chestPos.north(2).west(2);
			level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
			chest.clearContent();
			chest.setChanged();
			Villager resident = EntityTypes.VILLAGER.spawn(level, chestPos.east(2), EntitySpawnReason.COMMAND);
			spawned.add(resident);
			resident.setNoAi(true);
			resident.setInvulnerable(true);
			resident.addTag("vp090_fixture");

			Block none = WorkBlocks.blockFor(resident);
			passed += check(none == null, "an unemployed resident has no work block to procure");

			for (var entry : materials().entrySet()) {
				var key = entry.getKey();
				Holder<VillagerProfession> holder = BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(key);
				resident.setVillagerData(resident.getVillagerData().withProfession(holder));
				resident.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
				Block block = WorkBlocks.blockFor(resident);
				String name = key.identifier().getPath();
				passed += check(block != null, name + " has a mapped vanilla work block");
				chest.clearContent();
				Object[] items = entry.getValue();
				for (int i = 0, slot = 0; i < items.length; i += 2, slot++) chest.setItem(slot, new ItemStack((Item) items[i], (Integer) items[i + 1]));
				chest.setChanged();
				int inputsBefore = total(chest);

				JobState state = JobManager.state(resident);
				boolean crafted = false, standing = false;
				StringBuilder trace = new StringBuilder();
				for (int tick = 0; tick < 12 && !standing; tick++) {
					state.cooldownUntil = 0;
					state.workBlockIdleUntil = 0;
					var walk = resident.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
					if (walk.isPresent()) {
						BlockPos goal = walk.get().getTarget().currentBlockPosition();
						resident.teleportTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5);
					} else {
						resident.teleportTo(chestPos.getX() + 2.5, chestPos.getY(), chestPos.getZ() + 0.5);
					}
					Ctx ctx = new Ctx(level, resident, state, resident.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + tick * 40L);
					boolean worked = WorkBlocks.ensure(ctx);
					trace.append(tick).append(':').append(worked).append('/').append(state.activity).append(';');
					if (JobUtil.count(chest, s -> s.is(block.asItem())) > 0) crafted = true;
					standing = Workstation.find(level, chestPos, block, 8) != null;
					if (!worked && !standing && !crafted) break;
				}
				BlockPos found = Workstation.find(level, chestPos, block, 8);
				if (found != null) placed.add(found);
				passed += check(crafted || standing, name + " crafts " + block.getName().getString() + " at the crafting table [" + trace + " chest=" + total(chest) + "]");
				passed += check(found != null && JobUtil.count(chest, s -> s.is(block.asItem())) == 0,
					name + " places the crafted block itself and removes it from the chest");
				passed += check(total(chest) < inputsBefore || inputsBefore == 0, name + " consumed raw materials from the public chest");

				// Having a work block now: nothing is crafted again, even when the JOB_SITE memory is gone.
				chest.clearContent();
				for (int i = 0, slot = 0; i < items.length; i += 2, slot++) chest.setItem(slot, new ItemStack((Item) items[i], (Integer) items[i + 1]));
				int again = total(chest);
				state.workBlockIdleUntil = 0;
				state.cooldownUntil = 0;
				boolean second = WorkBlocks.ensure(new Ctx(level, resident, state, resident.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime() + 999L));
				passed += check(!second && total(chest) == again, name + " does not craft a second work block while one stands");
				if (found != null) level.setBlock(found, Blocks.AIR.defaultBlockState(), 3);
				placed.remove(found);
			}

			// A resident with a valid JOB_SITE memory is never touched.
			resident.setVillagerData(resident.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.MASON)));
			chest.clearContent();
			chest.setItem(0, new ItemStack(Items.STONE, 3));
			chest.setItem(1, new ItemStack(Items.IRON_INGOT, 1));
			resident.getBrain().setMemory(MemoryModuleType.JOB_SITE,
				net.minecraft.core.GlobalPos.of(level.dimension(), chestPos.offset(20, 0, 20)));
			JobState state = JobManager.state(resident);
			state.workBlockIdleUntil = 0;
			passed += check(!WorkBlocks.ensure(new Ctx(level, resident, state, resident.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime()))
				&& JobUtil.count(chest, s -> s.is(Items.STONE)) == 3, "a resident that still owns a job site does not procure a second work block");
			return passed;
		} finally {
			spawned.forEach(Entity::discard);
			for (BlockPos pos : placed) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			chest.clearContent();
			for (int i = 0; i < chestBefore.size(); i++) chest.setItem(i, chestBefore.get(i));
			chest.setChanged();
		}
	}

	private static int total(Container c) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
		return n;
	}

	private static int check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("B01 work block: " + label);
		return 1;
	}
}
