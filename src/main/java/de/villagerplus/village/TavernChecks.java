package de.villagerplus.village;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;

/** E02 checks for the exact Village Taverns templates and their generated marker block. */
public final class TavernChecks {
	private static int checks;

	private TavernChecks() { }

	/**
	 * Checks the integration surface and, when the supplied village already contains one, its real loaded structure start.
	 * The world probe is reported as skipped when the mod or a generated tavern is absent; this does not claim a live visit.
	 */
	public static int run(ServerLevel level, VillageRef village, Villager villager) {
		checks = 0;
		paceChecks();
		String[] biomes = {"plains", "desert", "savanna", "snowy", "taiga"};
		for (String biome : biomes) {
			Identifier template = Identifier.fromNamespaceAndPath("village_taverns", "village/" + biome + "/tavern");
			check(Tavern.isVillageTavernTemplate(template), "recognizes Village Taverns " + biome + " template");
		}
		check(!Tavern.isVillageTavernTemplate(Identifier.fromNamespaceAndPath("minecraft", "village/plains/houses")),
			"does not classify the vanilla village house pool as a Village Taverns template");
		check(!Tavern.isVillageTavernTemplate(Identifier.fromNamespaceAndPath("village_taverns", "village/plains/houses")),
			"does not classify an ordinary Village Taverns house-pool resource as its tavern template");
		check(!Tavern.isTavernMarker(Blocks.BARREL.defaultBlockState()),
			"ordinary minecraft:barrel cannot mark a tavern");

		boolean modLoaded = FabricLoader.getInstance().isModLoaded("village_taverns");
		check(!modLoaded || Tavern.isVillageTavernsLoaded(),
			"a loaded Village Taverns mod registers its exact village_taverns:barrel marker");
		if (!Tavern.isVillageTavernsLoaded()) {
			System.out.println("E02 TAVERN world probe SKIP: Village Taverns or its marker block is not loaded; fallback stays optional.");
			return checks;
		}

		var markerBlock = BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath("village_taverns", "barrel")).orElse(null);
		check(markerBlock != null && Tavern.isTavernMarker(markerBlock.defaultBlockState()),
			"the mod's registered barrel block is the exact tavern marker");
		if (level == null || village == null || villager == null) {
			System.out.println("E02 TAVERN world probe SKIP: no village and resident were supplied.");
			return checks;
		}

		Tavern.TavernSpot spot = Tavern.find(level, village, villager, level.getGameTime());
		if (spot == null) {
			System.out.println("E02 TAVERN world probe SKIP: no tavern marker was found inside a loaded valid village StructureStart.");
			return checks;
		}
		check(Tavern.isTavernMarker(level.getBlockState(spot.marker())),
			"a discovered destination contains the unique modded tavern marker");
		check(spot.stand() != null, "a discovered tavern has a safe reachable stand position");
		check(Tavern.reachableFloor(level, villager, spot.stand()),
			"a discovered tavern destination has a reachable, supported two-block stand position");
		return checks;
	}

	private static void paceChecks() {
		check(Wellbeing.workQuarters(new Wellbeing.Status(100, 100, 100, 100, 10, 0, true, true), false) == 1,
			"very low mood leaves one work quarter");
		check(Wellbeing.workQuarters(new Wellbeing.Status(100, 100, 100, 100, 20, 0, true, true), false) == 2,
			"low mood leaves two work quarters");
		check(Wellbeing.workQuarters(new Wellbeing.Status(100, 100, 100, 100, 35, 0, true, true), false) == 3,
			"neutral mood leaves three work quarters");
		var hungry = new Wellbeing.Status(10, 100, 100, 100, 80, 0, true, true);
		check(Wellbeing.workQuarters(hungry, false) == 2,
			"hunger caps even a cheerful villager at half work pace");
		check(Wellbeing.workQuarters(hungry, true) == 3,
			"a prepared meal restores one work quarter while hungry");
		check(Wellbeing.workQuarters(new Wellbeing.Status(100, 100, 100, 100, 80, 0, true, true), false) == 4,
			"a fed cheerful villager keeps full work pace");
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("E02 tavern: " + message);
		checks++;
	}
}
