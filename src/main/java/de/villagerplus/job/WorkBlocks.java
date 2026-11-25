package de.villagerplus.job;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * B01: a resident whose profession lost its own work block (no JOB_SITE memory) fetches the raw materials from the
 * public village chest, crafts the block at a real crafting table and places it himself, through the same
 * {@link Workstation#ensure} transaction the smith and the cook already use. A resident who still owns a job site is
 * never touched, so existing villages keep their layout.
 */
public final class WorkBlocks {
	private static final Map<net.minecraft.resources.ResourceKey<VillagerProfession>, Block> OWN = new LinkedHashMap<>();
	static {
		OWN.put(VillagerProfession.FARMER, Blocks.COMPOSTER);
		OWN.put(VillagerProfession.SHEPHERD, Blocks.LOOM);
		OWN.put(VillagerProfession.BUTCHER, Blocks.SMOKER);
		OWN.put(VillagerProfession.ARMORER, Blocks.BLAST_FURNACE);
		OWN.put(VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE);
		OWN.put(VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE);
		OWN.put(VillagerProfession.CLERIC, Blocks.BREWING_STAND);
		OWN.put(VillagerProfession.MASON, Blocks.STONECUTTER);
		OWN.put(VillagerProfession.FISHERMAN, Blocks.BARREL);
		OWN.put(VillagerProfession.FLETCHER, Blocks.FLETCHING_TABLE);
		OWN.put(VillagerProfession.LEATHERWORKER, Blocks.CAULDRON);
		OWN.put(VillagerProfession.CARTOGRAPHER, Blocks.CARTOGRAPHY_TABLE);
		OWN.put(VillagerProfession.LIBRARIAN, Blocks.LECTERN);
	}

	private WorkBlocks() { }

	/** The vanilla work block of the resident's profession, or null for unemployed, modded or unmapped professions. */
	public static Block blockFor(net.minecraft.world.entity.npc.villager.Villager v) {
		return v.getVillagerData().profession().unwrapKey().map(OWN::get).orElse(null);
	}

	/** @return true while fetching, crafting, walking or placing; false when nothing is missing or nothing can be done. */
	public static boolean ensure(Ctx c) {
		if (c == null || c.village() == null) return false;
		Block block = blockFor(c.v());
		if (block == null || c.v().getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)) return false;
		BlockPos chest = c.village().chestPos(c.level());
		if (chest == null || c.now() < c.st().workBlockIdleUntil) return false;
		if (Workstation.ensure(c, block)) return true;
		c.st().workBlockIdleUntil = c.now() + 600;
		return false;
	}
}
