package de.villagerplus.job;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Pure checks for deterministic, spread-out social waiting positions. */
public final class RoutineGatherChecks {
	private RoutineGatherChecks() { }

	public static int run() {
		Set<BlockPos> unique = new HashSet<>();
		Set<BlockPos> fallbackUnique = new HashSet<>();
		Set<Integer> deliverySlots = new HashSet<>();
		int[] quadrants = new int[4];
		int phaseChanges = 0;
		for (int resident = 0; resident < 128; resident++) {
			UUID id = UUID.nameUUIDFromBytes(("villagerplus-gather-" + resident).getBytes(StandardCharsets.UTF_8));
			BlockPos offset = Routine.gatherOffset(id, Phase.BREAK);
			BlockPos fallback = Routine.gatherOffset(id, Phase.BREAK, false);
			check(offset.equals(Routine.gatherOffset(id, Phase.BREAK)), "gather stand slot is stable within a social period");
			int distanceSquared = offset.getX() * offset.getX() + offset.getZ() * offset.getZ();
			check(distanceSquared >= 36 && distanceSquared <= 24 * 24, "gather slot stays on the designated outer plaza ring");
			int fallbackDistanceSquared = fallback.getX() * fallback.getX() + fallback.getZ() * fallback.getZ();
			check(fallbackDistanceSquared >= 16 && fallbackDistanceSquared <= 12 * 12,
				"villages without a built plaza use a closer ring around the bell");
			check(fallback.equals(Routine.gatherOffset(id, Phase.BREAK, false)),
				"fallback stand slot is stable until the social period changes");
			if (!offset.equals(Routine.gatherOffset(id, Phase.LUNCH))) phaseChanges++;
			unique.add(offset);
			fallbackUnique.add(fallback);
			deliverySlots.add(JobUtil.deliveryStandSlot(id));
			quadrants[quadrant(offset)]++;
		}
		check(unique.size() >= 110, "128 residents receive widely distributed personal stand slots");
		check(fallbackUnique.size() >= 100, "128 residents disperse around the bell before a plaza is built");
		check(phaseChanges >= 110, "residents change social stand slots between routine periods");
		check(deliverySlots.size() >= 14, "shared storage deliveries use most of the 16 stable approach slots");
		for (int count : quadrants) check(count >= 20, "all four plaza sides receive residents");
		BlockPos bell = new BlockPos(20, 64, 20);
		BlockPos plaza = new BlockPos(44, 70, -8);
		check(Routine.gatherAnchor(bell, null).equals(bell), "unbuilt plaza falls back to the village bell as its social anchor");
		check(Routine.gatherAnchor(bell, plaza).equals(plaza), "built plaza remains the preferred social anchor");
		return 522;
	}

	private static int quadrant(BlockPos offset) {
		if (offset.getX() >= 0) return offset.getZ() >= 0 ? 0 : 1;
		return offset.getZ() >= 0 ? 2 : 3;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Routine gather: " + message);
	}
}
