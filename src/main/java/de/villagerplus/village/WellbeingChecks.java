package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;

/** Focused loaded-chunk checks for home-chest reads during villager status updates. */
public final class WellbeingChecks {
	private WellbeingChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		var ref = Checks090.fixtureForEquipment(level, at);
		BlockPos chest = ref.chestPos(level);
		if (chest == null) throw new IllegalStateException("WELLBEING CHECKS: fixture chest is missing");
		Villager villager = EntityTypes.VILLAGER.spawn(level, chest.above(), EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("WELLBEING CHECKS: fixture villager did not spawn");
		villager.addTag("vp090_fixture");
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		var memory = villager.getAttachedOrCreate(VillagerPlus.MEMORY);
		int checks = 0;
		try {
			memory.homeChest = java.util.Optional.of(chest);
			Wellbeing.forget(villager.getUUID());
			Wellbeing.Status loadedChest = Wellbeing.of(level, villager, memory);
			check(loadedChest.chest(), "a loaded home chest remains visible in wellbeing status");
			checks++;

			BlockPos unloadedChest = findUnloadedPosition(level, at);
			check(!level.hasChunkAt(unloadedChest), "remote home-chest fixture begins in an unloaded chunk");
			checks++;
			memory.homeChest = java.util.Optional.of(unloadedChest);
			Wellbeing.forget(villager.getUUID());
			Wellbeing.Status remoteChest = Wellbeing.of(level, villager, memory);
			check(!level.hasChunkAt(unloadedChest) && !remoteChest.chest(),
				"status sampling neither loads nor reports an unloaded home-chest chunk");
			checks++;
			return checks;
		} finally {
			Wellbeing.forget(villager.getUUID());
			villager.discard();
		}
	}

	private static BlockPos findUnloadedPosition(ServerLevel level, BlockPos origin) {
		for (int attempt = 0; attempt < 16; attempt++) {
			BlockPos candidate = origin.offset(512 + attempt * 512, 0, 768 + attempt * 256);
			if (!level.hasChunkAt(candidate)) return candidate;
		}
		throw new IllegalStateException("WELLBEING CHECKS: could not find an unloaded fixture chunk");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("WELLBEING CHECKS: " + message);
		System.out.println("WELLBEING PASS " + message);
	}
}
