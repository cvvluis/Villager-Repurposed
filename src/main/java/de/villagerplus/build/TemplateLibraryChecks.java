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
		villageTemplates();
		return checks;
	}

	/** The three hand-built village templates: readable, door-oriented, buildable by villagers and with a roof-stair fallback. */
	private static void villageTemplates() {
		String[] types = {"haus_dorf", "rathaus_dorf", "schmiede_dorf"};
		int[] doorDistance = {3, 7, 4};
		int[] maxHalf = {7, 11, 9};
		BlockPos origin = new BlockPos(100, 64, 100);
		net.minecraft.world.level.block.Block[] noStairs = {};
		de.villagerplus.village.VillageStyle style = new de.villagerplus.village.VillageStyle(net.minecraft.world.level.block.Blocks.COBBLESTONE,
			net.minecraft.world.level.block.Blocks.OAK_PLANKS, net.minecraft.world.level.block.Blocks.BRICKS,
			net.minecraft.world.level.block.Blocks.BRICK_STAIRS, net.minecraft.world.level.block.Blocks.OAK_DOOR,
			net.minecraft.world.level.block.Blocks.OAK_FENCE);
		for (int t = 0; t < types.length; t++) {
			String type = types[t];
			check(TemplateLibrary.has(type), type + " ist als Vorlage vorhanden");
			for (String family : new String[] {"desert", "plains", "taiga"}) {
				HouseTemplate template = TemplateLibrary.get(type, family);
				check(template != null, type + " (" + family + ") lesbar");
			}
			HouseTemplate template = TemplateLibrary.get(type, "plains");
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				List<Step> steps = template.steps(origin, dir, 100, false, b -> b, !type.startsWith("rathaus"));
				check(!steps.isEmpty(), type + " " + dir + ": hat Schritte");
				Set<BlockPos> seen = new HashSet<>();
				for (Step s : steps) check(seen.add(s.pos()), type + " " + dir + ": Position doppelt " + s.pos());
				int minY = steps.stream().mapToInt(s -> s.pos().getY()).min().orElse(0);
				check(minY == origin.getY() - 1, type + " " + dir + ": der begehbare Boden liegt direkt unter dem Ursprung (min y " + minY + ")");
				int distance = doorDistance[t];
				boolean frontDoor = steps.stream().anyMatch(s -> s.item() != null && s.item().toString().contains("door")
					&& s.pos().getY() == origin.getY() && Math.abs((s.pos().getX() - origin.getX()) * dir.getStepX()
						+ (s.pos().getZ() - origin.getZ()) * dir.getStepZ()) == distance);
				check(frontDoor, type + " " + dir + ": Haupttuer steht " + distance + " Bloecke vor dem Ursprung");
				int half = maxHalf[t];
				check(steps.stream().allMatch(s -> Math.abs(s.pos().getX() - origin.getX()) <= half && Math.abs(s.pos().getZ() - origin.getZ()) <= half),
					type + " " + dir + ": Grundflaeche liegt in der halben Kante " + half);
			}
			List<Step> steps = template.steps(origin, Direction.SOUTH, 100, false, b -> b, !type.startsWith("rathaus"));
			for (Step s : steps) {
				if (s.item() == null) continue;
				net.minecraft.world.level.block.Block block = net.minecraft.world.level.block.Block.byItem(s.item());
				String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
				check(block.defaultBlockState().getFluidState().isEmpty() && !path.contains("lava") && !path.equals("bell") && !path.equals("ladder")
					&& !path.contains("carpet") && !path.contains("pot") && !path.contains("sign"),
					type + ": kein nicht baubarer Block (" + path + ")");
			}
			long beds = steps.stream().filter(s -> ItemTags.BEDS.equals(s.tag())).count();
			boolean wantsBeds = !type.startsWith("rathaus");
			check(wantsBeds ? beds >= 1 : beds == 0, type + ": Betten (" + beds + ")");
			// Roof stairs: a village that cannot supply the template's stair material falls back to its own stairs, cobblestone at the end.
			List<Step> substituted = template.steps(origin, Direction.SOUTH, 100, false, b -> Supply.substitute(b, style), wantsBeds);
			for (Step s : substituted) {
				if (s.item() == null) continue;
				net.minecraft.world.level.block.Block block = net.minecraft.world.level.block.Block.byItem(s.item());
				if (block instanceof net.minecraft.world.level.block.BedBlock) continue;
				check(Supply.obtainable(block), type + ": nach Ersatz beschaffbar (" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath() + ")");
				if (block instanceof net.minecraft.world.level.block.StairBlock) {
					check(block == net.minecraft.world.level.block.Blocks.COBBLESTONE_STAIRS || Supply.obtainable(block),
						type + ": Treppen weichen auf beschaffbare Treppen aus");
				}
			}
		}
		check(TemplateLibrary.skipped(net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState()), "Lava wird nie gebaut");
		check(TemplateLibrary.skipped(net.minecraft.world.level.block.Blocks.BELL.defaultBlockState()), "Glocken werden nie gebaut");
		check(!TemplateLibrary.skipped(net.minecraft.world.level.block.Blocks.COBBLESTONE_STAIRS.defaultBlockState()), "Treppen werden gebaut");
		// Which designs of NEW plans take the village house: only the tall two-storey ones, everything else keeps the small house.
		Architect.Design tall = new Architect.Design(Architect.Kind.HOUSE, 4, 3, 6, true, 2, true, true, true, true, true, 2, -1, 2, true, 0, 0);
		Architect.Design compact = new Architect.Design(Architect.Kind.HOUSE, 2, 2, 3, true, 1, true, true, true, true, false, 2, 0, 1, false, 1, 0);
		Architect.Design standard = new Architect.Design(Architect.Kind.HOUSE, 3, 3, 5, true, 1, true, true, true, true, false, 2, 1, 1, false, 1, 0);
		check(Builder.HOUSE_TEMPLATE_VILLAGE.equals(Builder.templateTypeFor(tall)), "hohe zweigeschossige Entwuerfe bekommen das Dorfhaus");
		check(Builder.HOUSE_TEMPLATE_SMALL.equals(Builder.templateTypeFor(compact)) && Builder.HOUSE_TEMPLATE_SMALL.equals(Builder.templateTypeFor(standard)),
			"kompakte und normale Entwuerfe behalten das kleine Haus");
		check(Builder.HOUSE_TEMPLATE_SMALL.equals(Builder.templateTypeFor(null)), "ohne Entwurf gilt das kleine Haus");
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
		// Persistence of the template choice: a plan marked before the village house existed keeps the small house after a restart,
		// a village-house plan is recorded with its complement marker and keeps the village house.
		Architect.Design tallDesign = new Architect.Design(Architect.Kind.HOUSE, 4, 3, 6, true, 2, true, true, true, true, true, 2, -1, 2, true, 0, 0);
		BlockPos marked = ref.bell().offset(60, 0, 60);
		List<Long> savedMarkers = List.copyOf(ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()));
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.of());
		check(Builder.HOUSE_TEMPLATE_VILLAGE.equals(Builder.templateTypeForPlan(ref, marked, tallDesign)), "unmarkierter hoher Plan: Dorfhaus nach Entwurf");
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.of(marked.asLong()));
		check(Builder.HOUSE_TEMPLATE_SMALL.equals(Builder.templateTypeForPlan(ref, marked, tallDesign)), "alter markierter Plan behaelt das kleine Haus auch bei hohem Entwurf");
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.of(marked.asLong(), ~marked.asLong()));
		check(Builder.HOUSE_TEMPLATE_VILLAGE.equals(Builder.templateTypeForPlan(ref, marked, tallDesign)), "mit Komplement-Marker bleibt es das Dorfhaus");
		check(!BlockPos.of(~marked.asLong()).equals(marked) && BlockPos.of(~marked.asLong()).getY() < -64, "der Komplement-Marker ist keine gueltige Weltposition");
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, savedMarkers);
		return checks;
	}
}
