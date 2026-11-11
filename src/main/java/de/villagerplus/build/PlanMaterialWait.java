package de.villagerplus.build;

import de.villagerplus.job.JobUtil;
import de.villagerplus.village.VillageData;
import java.util.Optional;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Persistent diagnostic only: this never claims an oven or changes the building plan. */
final class PlanMaterialWait {
	static final String REASON = "wartet auf planbezogene Ofenverarbeitung; eine registrierte Dorfküche fehlt";

	private PlanMaterialWait() { }

	static Optional<String> forPlan(VillageData data, Container chest, boolean hasKitchen) {
		if (!data.hasPlan() || chest == null || hasKitchen) return Optional.empty();
		boolean shortage = missing(data, chest, "minecraft:stone", Items.STONE)
			|| missing(data, chest, "minecraft:stone_bricks", Items.STONE_BRICKS)
			|| missing(data, chest, "minecraft:cracked_stone_bricks", Items.CRACKED_STONE_BRICKS);
		if (!shortage) return Optional.empty();
		return Optional.of(data.planType + "@" + data.planOrigin.orElseThrow().asLong() + ": " + REASON);
	}

	private static boolean missing(VillageData data, Container chest, String id, Item item) {
		for (String entry : data.wanted) {
			String[] parts = entry.split("#", 2);
			if (!parts[0].equals(id)) continue;
			try {
				int requested = parts.length == 2 ? Integer.parseInt(parts[1]) : 1;
				if (requested > JobUtil.count(chest, stack -> stack.is(item))) return true;
			} catch (NumberFormatException ignored) { return false; }
		}
		return false;
	}
}
