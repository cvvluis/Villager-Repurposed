package de.villagerplus.build;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.DoorBlock;

/** Invariants of the shipped building templates: readable, oriented to the door, complete, and bed-equipped houses. */
public final class TemplateLibraryChecks {
	private TemplateLibraryChecks() { }

	private static int checks;

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("TEMPLATE CHECK FAILED: " + message);
		checks++;
	}

	public static int run() {
		checks = 0;
		String[] types = {"dorfmitte", "gildenhaus_gross", "haus_klein", "lagerhaus", "marktplatz", "rathaus", "schmiede_mittel"};
		String[] families = {"desert", "plains", "taiga"};
		for (String type : types) {
			HouseTemplate shape = null;
			for (String family : families) {
				HouseTemplate t = TemplateLibrary.get(type, family);
				check(t != null, type + "_" + family + " lesbar");
				List<Step> steps = t.steps(new BlockPos(100, 64, 100), Direction.SOUTH, 100, false, b -> b, false);
				check(!steps.isEmpty(), type + "_" + family + " hat Schritte");
				Set<BlockPos> seen = new HashSet<>();
				for (Step s : steps) check(seen.add(s.pos()), type + "_" + family + " Position doppelt " + s.pos());
				if (shape != null) {
					List<Step> other = shape.steps(new BlockPos(100, 64, 100), Direction.SOUTH, 100, false, b -> b, false);
					check(other.size() == steps.size(), type + " Biomvarianten haben dieselbe Form (" + family + ")");
				}
				shape = t;
			}
		}
		BlockPos origin = new BlockPos(100, 64, 100);
		for (String family : families) {
			HouseTemplate house = TemplateLibrary.get("haus_klein", family);
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				for (boolean mirrored : new boolean[] {false, true}) {
					List<Step> steps = house.steps(origin, dir, 100, mirrored, b -> b, true);
					String label = "haus_klein_" + family + " " + dir + (mirrored ? " gespiegelt" : "");
					BlockPos doorLower = origin.relative(dir, 3);
					Step door = steps.stream().filter(s -> s.pos().equals(doorLower)).findFirst().orElse(null);
					check(door != null && door.item() != null && door.item().toString().contains("door"), label + ": Tuer an der Vorderseite");
					int minY = steps.stream().mapToInt(s -> s.pos().getY()).min().orElse(0);
					check(minY == origin.getY() - 1, label + ": Boden liegt unter dem Ursprung");
					check(steps.stream().allMatch(s -> Math.abs(s.pos().getX() - origin.getX()) <= 4 && Math.abs(s.pos().getZ() - origin.getZ()) <= 4),
						label + ": Grundflaeche hoechstens 9x9");
					long bedFoot = steps.stream().filter(s -> s.item() == null && ItemTags.BEDS.equals(s.tag()) || s.tag() != null && ItemTags.BEDS.equals(s.tag())).count();
					check(bedFoot == 2, label + ": zwei Betten (" + bedFoot + ")");
					long linked = steps.stream().filter(s -> s.linkedTo() != null).count();
					check(linked >= 3, label + ": Tuer und Bettkoepfe sind verknuepft");
				}
			}
		}
		check(TemplateLibrary.family(Identifier.withDefaultNamespace("badlands")).equals("desert"), "Badlands nutzt Wuestenmaterial");
		check(TemplateLibrary.family(Identifier.withDefaultNamespace("snowy_taiga")).equals("taiga"), "Taiga erkannt");
		check(TemplateLibrary.family(Identifier.withDefaultNamespace("plains")).equals("plains"), "Ebene erkannt");
		check(DoorBlock.class != null, "door");
		return checks;
	}

	/** The plan path in a real village: new houses take the template, it survives a restart, older plans keep their design. */
	public static int runBuild(net.minecraft.server.level.ServerLevel level, de.villagerplus.village.VillageRef ref) {
		checks = 0;
		var d = ref.data();
		BlockPos fresh = ref.bell().offset(24, 0, 0);
		BlockPos older = ref.bell().offset(-24, 0, 0);
		d.planType = Blueprints.HOUSE;
		d.planDir = Direction.SOUTH.get2DDataValue();
		d.planVariant = HouseDesignCatalog.newPlanVariant(Architect.SizeTier.COMPACT, 0x1234L);
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).stream().filter(marker -> marker != fresh.asLong()).toList());
		d.planOrigin = java.util.Optional.of(fresh);
		d.planStep = 0;
		Builder.forgetStepsForCheck(fresh);
		List<Long> markersBeforePreview = List.copyOf(ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()));
		List<Step> preview = Builder.previewTemplateSteps(level, ref, fresh, Direction.SOUTH, "haus_klein");
		check(preview != null && !preview.isEmpty()
			&& ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).equals(markersBeforePreview),
			"previewing a candidate does not persist a template plan");
		List<Step> unmarked = Builder.stepsForCheck(level, ref, fresh, Direction.SOUTH);
		Builder.forgetStepsForCheck(fresh);
		List<Step> unmarkedReloaded = Builder.stepsForCheck(level, ref, fresh, Direction.SOUTH);
		check(unmarked != null && unmarkedReloaded != null && unmarked.size() == unmarkedReloaded.size()
			&& !ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).contains(fresh.asLong()),
			"a saved unmarked zero-cursor plan keeps its generated design after a cache loss");
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.of(fresh.asLong()));
		Builder.forgetStepsForCheck(fresh);
		List<Step> first = Builder.stepsForCheck(level, ref, fresh, Direction.SOUTH);
		check(preview.size() == first.size() && java.util.stream.IntStream.range(0, first.size()).allMatch(i ->
			preview.get(i).pos().equals(first.get(i).pos()) && preview.get(i).item() == first.get(i).item()
				&& java.util.Objects.equals(preview.get(i).tag(), first.get(i).tag()) && preview.get(i).cost() == first.get(i).cost()
				&& java.util.Objects.equals(preview.get(i).linkedTo(), first.get(i).linkedTo())),
			"accepted template uses the previewed positions and material charges");
		BlockPos entrance = Builder.Paths.entrance(preview, fresh, Direction.SOUTH);
		check(entrance != null && entrance.distSqr(fresh) < 100,
			"template entrance lookup tolerates paid and linked bed steps before finding the door");
		BlockPos door = fresh.relative(Direction.SOUTH, 3);
		check(first != null && first.stream().anyMatch(s -> s.pos().equals(door) && s.item() != null && s.item().toString().contains("door")),
			"markierter Hausplan nutzt die Vorlage (Tuer an der Vorderseite)");
		check(ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).contains(fresh.asLong()), "Vorlagenwahl ist gespeichert");

		// Restart in the middle of the build: the cached steps are gone and the cursor is past zero.
		Builder.forgetStepsForCheck(fresh);
		d.planStep = 40;
		List<Step> again = Builder.stepsForCheck(level, ref, fresh, Direction.SOUTH);
		check(again != null && again.size() == first.size(), "nach Neustart bleibt die Vorlage (" + again.size() + " Schritte)");

		// A large village must never evict an existing template marker that an active or repair plan still needs.
		List<Long> manyMarkers = new java.util.ArrayList<>();
		for (int i = 0; i < 512; i++) manyMarkers.add(fresh.asLong() + i);
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.copyOf(manyMarkers));
		BlockPos another = fresh.offset(16, 0, 0);
		manyMarkers.add(another.asLong());
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.copyOf(manyMarkers));
		d.planOrigin = java.util.Optional.of(another);
		d.planStep = 0;
		Builder.forgetStepsForCheck(another);
		Builder.stepsForCheck(level, ref, another, Direction.SOUTH);
		List<Long> persisted = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of());
		check(persisted.size() == 513 && persisted.contains(fresh.asLong()) && persisted.contains(another.asLong()),
			"the attachment retains manually seeded markers beyond 512 entries");
		d.planOrigin = java.util.Optional.of(fresh);
		d.planStep = 40;
		Builder.forgetStepsForCheck(fresh);
		List<Step> resumedAfterManyPlans = Builder.stepsForCheck(level, ref, fresh, Direction.SOUTH);
		check(resumedAfterManyPlans != null && resumedAfterManyPlans.size() == first.size(),
			"an older active template plan resumes after the village exceeds 512 plans");
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.of(fresh.asLong()));

		// A plan that was already under way before templates existed keeps its generated design.
		d.planOrigin = java.util.Optional.of(older);
		d.planStep = 40;
		Builder.forgetStepsForCheck(older);
		List<Step> legacy = Builder.stepsForCheck(level, ref, older, Direction.SOUTH);
		check(legacy != null && !legacy.isEmpty() && legacy.size() != first.size(), "laufender Altplan behaelt den alten Entwurf");
		check(!ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).contains(older.asLong()), "Altplan wird nicht markiert");
		d.planStep = 0;
		return checks;
	}
}
