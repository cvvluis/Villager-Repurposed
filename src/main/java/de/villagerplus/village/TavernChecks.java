package de.villagerplus.village;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

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

		fixtureTavern(level, villager, markerBlock);
		Tavern.TavernSpot spot = Tavern.find(level, village, villager, level.getGameTime());
		if (spot == null) {
			System.out.println("E02 TAVERN natural world probe SKIP: no tavern marker was found inside a loaded valid village StructureStart (the fixture tavern above is proven separately).");
			return checks;
		}
		check(Tavern.isTavernMarker(level.getBlockState(spot.marker())),
			"a discovered destination contains the unique modded tavern marker");
		check(spot.stand() != null, "a discovered tavern has a safe reachable stand position");
		check(Tavern.reachableFloor(level, villager, spot.stand()),
			"a discovered tavern destination has a reachable, supported two-block stand position");
		return checks;
	}

	/**
	 * Builds a tavern-like fixture: a floor, a roofless room and the mod's real {@code village_taverns:barrel} marker, then runs the same marker
	 * scan and stand search that {@link Tavern#find} uses on a generated structure. A real {@code StructureStart} cannot be forged here, so the
	 * start lookup itself stays covered only by a natural world; the marker scan, the stand search and the service route are proven.
	 */
	private static void fixtureTavern(ServerLevel level, Villager villager, net.minecraft.world.level.block.Block markerBlock) {
		BlockPos feet = villager.blockPosition();
		int y = feet.getY();
		BlockPos marker = feet.offset(5, 0, 0);
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> restore = new java.util.LinkedHashMap<>();
		var stone = Blocks.STONE_BRICKS.defaultBlockState();
		var air = Blocks.AIR.defaultBlockState();
		for (int dx = -2; dx <= 9; dx++) for (int dz = -4; dz <= 4; dz++) {
			for (int dy = -1; dy <= 3; dy++) {
				BlockPos pos = feet.offset(dx, dy, dz);
				level.getChunkAt(pos);
				restore.putIfAbsent(pos, level.getBlockState(pos));
				level.setBlock(pos, dy < 0 ? stone : air, 2);
			}
		}
		// A freshly spawned no-AI fixture villager has not ticked yet, so it is not on the ground and navigation refuses to plan.
		villager.setOnGround(true);
		try {
			level.setBlock(marker, markerBlock.defaultBlockState(), 2);
			BoundingBox bounds = new BoundingBox(feet.getX() - 2, y - 1, feet.getZ() - 4, feet.getX() + 9, y + 3, feet.getZ() + 4);
			check(Tavern.isTavernMarker(level.getBlockState(marker)), "fixture tavern: the placed block is the exact village_taverns marker");
			check(marker.equals(Tavern.markerInLoadedPart(level, bounds, feet, markerBlock)),
				"fixture tavern: the loaded-part marker scan finds the mod barrel inside the structure bounds");
			BoundingBox elsewhere = new BoundingBox(feet.getX() - 2, y - 1, feet.getZ() - 4, feet.getX() + 2, y + 3, feet.getZ() + 4);
			check(Tavern.markerInLoadedPart(level, elsewhere, feet, markerBlock) == null,
				"fixture tavern: bounds that exclude the barrel report no tavern");
			BlockPos stand = Tavern.reachableStand(level, villager, marker);
			check(stand != null, "fixture tavern: a reachable stand position is found next to the marker");
			check(Math.max(Math.abs(stand.getX() - marker.getX()), Math.abs(stand.getZ() - marker.getZ())) <= 4,
				"fixture tavern: the stand lies within four blocks of the marker");
			check(Tavern.reachableFloor(level, villager, stand), "fixture tavern: the stand is a supported two-block position the villager can path to");
			// An ordinary vanilla barrel must not turn the room into a tavern: remove the marker and scan again.
			level.setBlock(marker, Blocks.BARREL.defaultBlockState(), 2);
			check(Tavern.markerInLoadedPart(level, bounds, feet, markerBlock) == null,
				"fixture tavern: a vanilla barrel in the same room is not reported as a tavern");
			System.out.println("E02 TAVERN fixture tavern PASS: marker scan, stand search and vanilla-barrel rejection proven on a built room; StructureStart lookup unproven without a natural tavern.");
		} finally {
			restore.forEach((pos, state) -> level.setBlock(pos, state, 2));
		}
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
