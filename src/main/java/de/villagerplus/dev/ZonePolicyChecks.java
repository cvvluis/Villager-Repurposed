package de.villagerplus.dev;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.net.SetZone;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Focused persistence and server-boundary checks for map zones. */
public final class ZonePolicyChecks {
	private ZonePolicyChecks() { }
	public static int run(VillageRef ref) {
		int passed = 0;
		check(ref != null && ZonePolicy.validBounds(ref, "farm", ref.bell().getX() - 2, ref.bell().getZ() - 3, ref.bell().getX() + 2, ref.bell().getZ() + 3), "accepts a bounded zone inside the village radius"); passed++;
		check(ref != null && ZonePolicy.validBounds(ref, "pasture", ref.bell().getX() - 4, ref.bell().getZ() - 4, ref.bell().getX() + 4, ref.bell().getZ() + 4), "accepts a bounded pasture zone"); passed++;
		check(ref != null && ZonePolicy.validBounds(ref, "work", ref.bell().getX() - 31, ref.bell().getZ() - 31, ref.bell().getX() + 32, ref.bell().getZ() + 32), "accepts the exact 4096-cell area limit within the village radius"); passed++;
		check(ref != null && !ZonePolicy.validBounds(ref, "farm", ref.bell().getX() - 32, ref.bell().getZ() - 32, ref.bell().getX() + 32, ref.bell().getZ() + 32), "rejects a 4225-cell zone even when all edges are in radius"); passed++;
		check(!ZonePolicy.validBounds(ref, "farm", ref.bell().getX() - 65, ref.bell().getZ(), ref.bell().getX(), ref.bell().getZ()), "rejects zones extending beyond the server radius"); passed++;
		check(!ZonePolicy.validBounds(ref, "unknown", ref.bell().getX(), ref.bell().getZ(), ref.bell().getX(), ref.bell().getZ()), "rejects unknown zone types"); passed++;
		check(ZonePolicy.accessFailure(new ZonePolicy.RequestAccess(false, true, true, true, true, true)) == null
			&& ZonePolicy.accessFailure(new ZonePolicy.RequestAccess(false, true, false, false, false, false)).contains("nicht geladen")
			&& ZonePolicy.accessFailure(new ZonePolicy.RequestAccess(false, true, true, false, false, false)).contains("gültige Dorftafel")
			&& ZonePolicy.accessFailure(new ZonePolicy.RequestAccess(false, true, true, true, false, false)).contains("zu weit")
			&& ZonePolicy.accessFailure(new ZonePolicy.RequestAccess(false, true, true, true, true, false)).contains("Dorfglocke")
			&& ZonePolicy.accessFailure(new ZonePolicy.RequestAccess(true, true, true, true, true, true)).contains("Zuschauermodus"),
			"every board access gate has a specific rejection reason for its failed precondition"); passed++;
		List<ZonePolicy.Zone> old = ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of());
		try {
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of());
			SetZone create = new SetZone(BlockPos.ZERO, ref.bell().getX() + 2, ref.bell().getZ() + 3,
				ref.bell().getX() - 2, ref.bell().getZ() - 3, "farm", false);
			check(ZonePolicy.actionFailure(ref, create) == null && ZonePolicy.apply(ref, create), "server action accepts a valid zone creation"); passed++;
			check(ZonePolicy.resultMessage(create, true).equals("Feldzone gespeichert: X " + (ref.bell().getX() - 2) + " bis "
				+ (ref.bell().getX() + 2) + ", Z " + (ref.bell().getZ() - 3) + " bis " + (ref.bell().getZ() + 3) + ".")
				&& ZonePolicy.resultMessage(create, false).startsWith("Zone abgelehnt:")
				&& ZonePolicy.resultMessage(null, false).startsWith("Zone abgelehnt:"),
				"zone creation confirms the exact persisted world bounds and rejection never claims success"); passed++;
			List<ZonePolicy.Zone> created = ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of());
			check(created.size() == 1 && created.getFirst().kind().equals("farm")
				&& created.getFirst().minX() == ref.bell().getX() - 2 && created.getFirst().minZ() == ref.bell().getZ() - 3
				&& created.getFirst().maxX() == ref.bell().getX() + 2 && created.getFirst().maxZ() == ref.bell().getZ() + 3
				&& ZonePolicy.rows(ref).stream().anyMatch(row -> row.endsWith("|farm|" + (ref.bell().getX() - 2) + "|"
					+ (ref.bell().getZ() - 3) + "|" + (ref.bell().getX() + 2) + "|" + (ref.bell().getZ() + 3))),
				"server action persists normalized world bounds and returns them to the map rows"); passed++;
			var zone = created.getFirst();
			var encoded = ZonePolicy.Zone.CODEC.encodeStart(JsonOps.INSTANCE, zone).getOrThrow();
			check(ZonePolicy.Zone.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(zone), "zone bounds and category survive persistent codec round-trip"); passed++;
			List<ZonePolicy.Zone> beforeInvalid = List.copyOf(created);
			SetZone invalid = new SetZone(BlockPos.ZERO, ref.bell().getX() - 65, ref.bell().getZ(),
				ref.bell().getX(), ref.bell().getZ(), "farm", false);
			SetZone oversized = new SetZone(BlockPos.ZERO, ref.bell().getX() - 32, ref.bell().getZ() - 32,
				ref.bell().getX() + 32, ref.bell().getZ() + 32, "farm", false);
			check(ZonePolicy.actionFailure(ref, invalid).contains("Radius")
				&& ZonePolicy.actionFailure(ref, oversized).contains("4096")
				&& !ZonePolicy.apply(ref, invalid)
				&& ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).equals(beforeInvalid),
				"server action explains radius and area violations and rejects them without mutating saved zones"); passed++;
			SetZone delete = new SetZone(BlockPos.ZERO, 0, 0, 0, 0, zone.id(), true);
			check(ZonePolicy.apply(ref, delete)
				&& ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).isEmpty(),
				"server delete action removes the saved zone identified by the map row"); passed++;
			check(ZonePolicy.resultMessage(delete, true).equals("Zone gelöscht: " + zone.id() + "."),
				"zone deletion visibly confirms the completed action"); passed++;
		check(ZonePolicy.actionFailure(ref, delete).contains("existiert nicht mehr") && !ZonePolicy.apply(ref, delete),
			"server delete action explains and rejects a stale zone id"); passed++;
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(zone));
			var codec = ZonePolicy.Zone.CODEC.listOf();
			var saved = codec.encodeStart(JsonOps.INSTANCE, ref.bellEntity().getAttached(VillagerPlus.ZONES)).getOrThrow();
			var loaded = codec.parse(JsonOps.INSTANCE, saved).getOrThrow();
			check(ref.bellEntity().getAttached(VillagerPlus.ZONES).equals(loaded), "village zone attachment persists across serialization"); passed++;
			List<ZonePolicy.Zone> full = java.util.stream.IntStream.range(0, ZonePolicy.MAX_ZONES)
				.mapToObj(i -> new ZonePolicy.Zone("capacity-" + i, "farm", ref.bell().getX(), ref.bell().getZ(),
					ref.bell().getX(), ref.bell().getZ())).toList();
			ref.bellEntity().setAttached(VillagerPlus.ZONES, full);
			check(ZonePolicy.actionFailure(ref, create).contains("Höchstzahl") && !ZonePolicy.apply(ref, create)
				&& ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).equals(full),
				"zone limit rejection names the capacity and leaves the stored list intact"); passed++;
		} finally { ref.bellEntity().setAttached(VillagerPlus.ZONES, old); ref.dirty(); }
		return passed;
	}
	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("ZONE POLICY CHECK FAILED: " + label);
		System.out.println("ZONE POLICY PASS " + label);
	}
}
