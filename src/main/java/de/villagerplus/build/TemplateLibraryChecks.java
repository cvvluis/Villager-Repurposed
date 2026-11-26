package de.villagerplus.build;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.item.Items;

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
				if ("lagerhaus".equals(type)) {
					HouseTemplate.Bounds bounds = t.bounds(Direction.SOUTH, false);
					check(bounds.maxX() - bounds.minX() + 1 >= 12 && bounds.maxZ() - bounds.minZ() + 1 >= 9
						&& bounds.maxY() >= 6, type + "_" + family + " ist ein vollwertiges Lagerhaus-Grundriss-NBT");
				}
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
					Set<BlockPos> bedFeet = steps.stream().filter(s -> s.tag() != null && s.tag().equals(ItemTags.BEDS))
						.map(Step::pos).collect(java.util.stream.Collectors.toSet());
					long pairedHeads = steps.stream().filter(s -> s.linkedTo() != null && bedFeet.contains(s.linkedTo()))
						.filter(head -> {
							Step foot = steps.stream().filter(candidate -> candidate.pos().equals(head.linkedTo())).findFirst().orElse(null);
							if (foot == null) return false;
							BlockState footState = foot.make().apply(Blocks.BED.white());
							BlockState headState = head.make().apply(Blocks.BED.white());
							return footState.is(Blocks.BED.white()) && footState.getValue(BedBlock.PART) == BedPart.FOOT
								&& headState.is(Blocks.BED.white()) && headState.getValue(BedBlock.PART) == BedPart.HEAD
								&& footState.getValue(BedBlock.FACING) == headState.getValue(BedBlock.FACING);
						}).count();
					check(bedFeet.size() == 2 && pairedHeads == 2, label + ": zwei vollstaendige Bed-POI-Paare");
					long linked = steps.stream().filter(s -> s.linkedTo() != null).count();
					check(linked >= 3, label + ": Tuer und Bettkoepfe sind verknuepft");
				}
			}
		}
		check(TemplateLibrary.family(Identifier.withDefaultNamespace("badlands")).equals("desert"), "Badlands nutzt Wuestenmaterial");
		check(TemplateLibrary.family(Identifier.withDefaultNamespace("snowy_taiga")).equals("taiga"), "Taiga erkannt");
		check(TemplateLibrary.family(Identifier.withDefaultNamespace("plains")).equals("plains"), "Ebene erkannt");
		check(DoorBlock.class != null, "door");
		localPalettes(origin, families);
		villageTemplates();
		return checks;
	}

	private static Palette localPalette() {
		return new Palette(Blocks.ACACIA_PLANKS, Blocks.STONE_BRICKS, Blocks.BIRCH_LOG, Blocks.ANDESITE,
			Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB, Blocks.GLASS_PANE,
			Blocks.BIRCH_PLANKS, Blocks.BIRCH_DOOR, Blocks.BIRCH_FENCE);
	}

	private static void localPalettes(BlockPos origin, String[] families) {
		Palette local = localPalette();
		for (String family : families) {
			for (String type : List.of("haus_klein", "lagerhaus", "haus_dorf")) {
				boolean tall = type.equals("haus_dorf");
				HouseTemplate template = TemplateLibrary.get(type, family);
				Identifier biome = Identifier.withDefaultNamespace(family);
				List<Step> source = template.steps(origin, Direction.EAST, 100, true);
				List<Step> styled = template.steps(origin, Direction.EAST, 100, true,
					(pos, block) -> TemplatePalette.material(pos, block, local, biome, tall), true);
				check(source.size() == styled.size() && java.util.stream.IntStream.range(0, source.size()).allMatch(index -> {
					Step before = source.get(index), after = styled.get(index);
					return before.pos().equals(after.pos()) && before.cost() == after.cost()
						&& java.util.Objects.equals(before.linkedTo(), after.linkedTo())
						&& java.util.Objects.equals(before.tag(), after.tag());
				}), type + " " + family + " retains all positions, paid steps and linked parts");
				check(styled.stream().anyMatch(step -> step.item() == Items.ACACIA_PLANKS)
					&& styled.stream().anyMatch(step -> step.item() == Items.BIRCH_LOG)
					&& styled.stream().anyMatch(step -> step.item() == Items.BIRCH_DOOR)
					&& styled.stream().anyMatch(step -> step.item() == Items.DARK_OAK_SLAB)
					&& (tall || styled.stream().anyMatch(step -> step.item() == Items.GLASS)),
					type + " " + family + " gets its local walls, beams, doors and roof while retaining glazed openings");
				for (int index = 0; index < styled.size(); index++) {
					Step before = source.get(index), after = styled.get(index);
					if (after.item() != Items.BIRCH_DOOR) continue;
					var original = net.minecraft.world.level.block.Block.byItem(before.item());
					check(after.make().apply(Blocks.BIRCH_DOOR).getValue(DoorBlock.FACING)
						== before.make().apply(original).getValue(DoorBlock.FACING),
						type + " " + family + " retains the rotated door direction");
				}
			}
		}
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
		Built completedTemplateHouse = new Built(Blueprints.HOUSE, fresh, Direction.SOUTH.get2DDataValue(), d.planVariant, List.of());
		List<Step> templateHouseRepair = Builder.stepsOfBuilt(level, ref, completedTemplateHouse, null);
		check(samePlan(first, templateHouseRepair),
			"repair and demolition reconstruct the saved NBT house rather than the generated Architect outline");
		List<List<Step>> templateHouseBeds = Builder.houseBedPairsOfBuilt(level, ref, completedTemplateHouse);
		check(templateHouseBeds.size() == 2 && templateHouseBeds.stream().allMatch(pair -> pair.size() == 2
			&& first.stream().anyMatch(step -> step.pos().equals(pair.getFirst().pos()))
			&& first.stream().anyMatch(step -> step.pos().equals(pair.get(1).pos()))),
			"marked NBT houses expose both saved bed pairs to the safe retrofit path");
		int legacyHouseVariant = Builder.newPlanVariant(Blueprints.HOUSE, 0x5678L);
		Palette legacyHousePalette = Palette.detect(level, ref.bell(), null, legacyHouseVariant >>> 2, older);
		Built legacyHouse = new Built(Blueprints.HOUSE, older, Direction.SOUTH.get2DDataValue(), legacyHouseVariant, legacyHousePalette.ids());
		List<Step> legacyArchitectHouse = Architect.build(Builder.designForPlan(Blueprints.HOUSE, legacyHouseVariant),
			Palette.ofIds(legacyHouse.palette()), older, Direction.SOUTH);
		List<Long> legacyMarkers = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of());
		check(!legacyMarkers.contains(older.asLong()) && !legacyMarkers.contains(~older.asLong())
			&& samePlan(legacyArchitectHouse, Builder.stepsOfBuilt(level, ref, legacyHouse, null)),
			"an unmarked legacy house still repairs from its saved Architect design");

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
		BlockPos reused = ref.bell().offset(68, 0, 60);
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.of(reused.asLong()));
		Builder.markTemplatePlan(ref, reused, tallDesign);
		List<Long> reusedLargeMarkers = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of());
		check(reusedLargeMarkers.contains(reused.asLong()) && reusedLargeMarkers.contains(~reused.asLong())
			&& Builder.HOUSE_TEMPLATE_VILLAGE.equals(Builder.templateTypeForPlan(ref, reused, tallDesign)),
			"reusing a small/storage origin for a large house replaces its old positive marker and persists the village template");
		Builder.markStorageTemplatePlan(ref, reused);
		List<Long> reusedStorageMarkers = ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of());
		check(reusedStorageMarkers.contains(reused.asLong()) && !reusedStorageMarkers.contains(~reused.asLong()),
			"reusing a large-house origin for storage clears the stale village-template marker");
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, savedMarkers);

		// New storage plans opt into the shipped warehouse NBT. Unmarked saves retain their exact Architect cursor shape.
		String priorType = d.planType;
		int priorVariant = d.planVariant;
		int priorDir = d.planDir;
		int priorStep = d.planStep;
		var priorOrigin = d.planOrigin;
		var priorSource = d.planSource;
		BlockPos oldStorage = ref.bell().offset(-44, 0, 40);
		BlockPos newStorage = ref.bell().offset(44, 0, 40);
		long compactSeed = Builder.civicDesignSeed(Blueprints.STORAGE, true, 0x76543, Architect.SizeTier.COMPACT);
		int storageVariant = Builder.newPlanVariant(Blueprints.STORAGE, compactSeed);
		d.planType = Blueprints.STORAGE;
		d.planVariant = storageVariant;
		d.planDir = Direction.SOUTH.get2DDataValue();
		d.planStep = 0;
		d.planSource = java.util.Optional.empty();
		d.planOrigin = java.util.Optional.of(oldStorage);
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS,
			savedMarkers.stream().filter(marker -> marker != oldStorage.asLong() && marker != newStorage.asLong()).toList());
		Builder.forgetStepsForCheck(oldStorage);
		List<Step> oldStorageSteps = Builder.stepsForCheck(level, ref, oldStorage, Direction.SOUTH);
		Builder.forgetStepsForCheck(oldStorage);
		List<Step> oldStorageReloaded = Builder.stepsForCheck(level, ref, oldStorage, Direction.SOUTH);
		Architect.Design legacyStorageDesign = Builder.designForPlan(Blueprints.STORAGE, storageVariant);
		check(oldStorageSteps != null && !oldStorageSteps.isEmpty() && samePlan(oldStorageSteps, oldStorageReloaded)
			&& oldStorageSteps.stream().allMatch(step -> Math.abs(step.pos().getX() - oldStorage.getX()) <= legacyStorageDesign.half() + 2
				&& Math.abs(step.pos().getZ() - oldStorage.getZ()) <= legacyStorageDesign.half() + 2)
			&& !ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).contains(oldStorage.asLong()),
			"unmarkierter Alt-Lagerplan bleibt beim Architect-Grundriss nach Cacheverlust");

		d.planOrigin = java.util.Optional.of(newStorage);
		Builder.markStorageTemplatePlan(ref, newStorage);
		List<Step> storagePreview = Builder.storageTemplatePreviewForCheck(level, ref, newStorage, Direction.SOUTH);
		Builder.forgetStepsForCheck(newStorage);
		List<Step> firstStorage = Builder.stepsForCheck(level, ref, newStorage, Direction.SOUTH);
		List<Step> storageChests = firstStorage == null ? List.of()
			: firstStorage.stream().filter(step -> step.item() == Items.CHEST).toList();
		check(ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).contains(newStorage.asLong())
			&& samePlan(storagePreview, firstStorage) && storageChests.size() == 1 && storageChests.getFirst().cost() == 1
			&& storageChests.getFirst().make().apply(Blocks.CHEST).getValue(ChestBlock.FACING) == Direction.SOUTH
			&& firstStorage.stream().anyMatch(step -> Math.abs(step.pos().getX() - newStorage.getX()) > legacyStorageDesign.half()
				|| Math.abs(step.pos().getZ() - newStorage.getZ()) > legacyStorageDesign.half()),
			"neues Lager nutzt die groessere NBT-Form mit einer separat bezahlten, funktionsfaehigen Kiste"
				+ " [marked=" + ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).contains(newStorage.asLong())
				+ ",preview=" + (storagePreview == null ? -1 : storagePreview.size()) + ",built=" + (firstStorage == null ? -1 : firstStorage.size())
				+ ",same=" + samePlan(storagePreview, firstStorage) + ",chests=" + storageChests.size()
				+ ",facing=" + (storageChests.isEmpty() ? "missing" : storageChests.getFirst().make().apply(Blocks.CHEST).getValue(ChestBlock.FACING))
				+ ",diff=" + firstDifference(storagePreview, firstStorage)
				+ ",outside=" + (firstStorage != null && firstStorage.stream().anyMatch(step -> Math.abs(step.pos().getX() - newStorage.getX()) > legacyStorageDesign.half()
					|| Math.abs(step.pos().getZ() - newStorage.getZ()) > legacyStorageDesign.half())) + "]");
		Builder.forgetStepsForCheck(newStorage);
		d.planStep = 40;
		List<Step> resumedStorage = Builder.stepsForCheck(level, ref, newStorage, Direction.SOUTH);
		check(samePlan(firstStorage, resumedStorage), "markierter Lagerbau setzt nach Cacheverlust dieselbe NBT-Form am gespeicherten Cursor fort");
		Built completedTemplateStorage = new Built(Blueprints.STORAGE, newStorage, Direction.SOUTH.get2DDataValue(), storageVariant, List.of());
		List<Step> repairTemplate = Builder.stepsOfBuilt(level, ref, completedTemplateStorage, null);
		Map<BlockPos, Step> builtByPosition = new HashMap<>();
		for (Step step : firstStorage) builtByPosition.put(step.pos(), step);
		Step chestToDamage = repairTemplate.stream().filter(step -> step.item() == Items.CHEST).findFirst().orElse(null);
		Step wallToDamage = repairTemplate.stream().filter(step -> step.pos().getY() >= newStorage.getY() && step.item() != null
			&& step.item() != Items.CHEST && !storageWindow(step) && step.tag() == null).findFirst().orElse(null);
		Step windowToDamage = repairTemplate.stream().filter(TemplateLibraryChecks::storageWindow).findFirst().orElse(null);
		check(chestToDamage != null && wallToDamage != null && windowToDamage != null
			&& repairTemplate.size() == firstStorage.size()
			&& repairTemplate.stream().allMatch(step -> builtByPosition.containsKey(step.pos())),
			"completed storage retains the same NBT footprint, windows and paid chest");
		Set<BlockPos> missingStorageParts = Set.of(chestToDamage.pos(), wallToDamage.pos(), windowToDamage.pos());
		java.util.function.Function<BlockPos, BlockState> builtState = pos -> {
			if (missingStorageParts.contains(pos)) return Blocks.AIR.defaultBlockState();
			Step step = builtByPosition.get(pos);
			if (step == null || step.item() == null) return Blocks.AIR.defaultBlockState();
			return step.make().apply(net.minecraft.world.level.block.Block.byItem(step.item()));
		};
		Builder.RepairDamage templateDamage = Builder.storageTemplateRepairDamage(completedTemplateStorage, repairTemplate, builtState, 0, 0);
		check(templateDamage.damaged().size() == missingStorageParts.size()
			&& templateDamage.damaged().stream().map(Step::pos).collect(java.util.stream.Collectors.toSet()).equals(missingStorageParts)
			&& templateDamage.damaged().stream().anyMatch(step -> step.pos().equals(windowToDamage.pos())
				&& step.cost() == windowToDamage.cost() && !step.optional()
				&& net.minecraft.world.level.block.Block.byItem(step.item()).defaultBlockState().is(net.minecraft.tags.BlockTags.WOODEN_FENCES)),
			"storage repair finds the missing wall, paid chest and window, using the build's fence fallback without changing intact windows");
		Builder.RepairDamage glassRepair = Builder.storageTemplateRepairDamage(completedTemplateStorage, repairTemplate, builtState,
			windowToDamage.cost(), windowToDamage.cost());
		check(glassRepair.damaged().size() == missingStorageParts.size()
			&& glassRepair.damaged().stream().anyMatch(step -> step.pos().equals(windowToDamage.pos()) && step.item() == windowToDamage.item()),
			"stocked glass is reserved for the missing window before any fence substitution");
		checkStorageRepairReload(level, ref, completedTemplateStorage, firstStorage, chestToDamage.pos(), windowToDamage.pos());
		Built legacyStorageBuilding = new Built(Blueprints.STORAGE, oldStorage, Direction.SOUTH.get2DDataValue(), storageVariant,
			Palette.detect(level, ref.bell(), null, storageVariant >>> 2, oldStorage).ids());
		List<Step> legacyRepair = Builder.stepsOfBuilt(level, ref, legacyStorageBuilding, null);
		List<Step> legacyStorageShape = Architect.build(legacyStorageDesign, Palette.ofIds(legacyStorageBuilding.palette()), oldStorage, Direction.SOUTH);
		check(!legacyRepair.isEmpty() && samePlan(legacyStorageShape, legacyRepair)
			&& !ref.bellEntity().getAttachedOrElse(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, List.<Long>of()).contains(oldStorage.asLong()),
			"Reparatur eines unmarkierten Alt-Lagers bleibt beim gespeicherten Architect-Grundriss");
		Builder.forgetStepsForCheck(oldStorage);
		Builder.forgetStepsForCheck(newStorage);
		d.planType = priorType;
		d.planVariant = priorVariant;
		d.planDir = priorDir;
		d.planStep = priorStep;
		d.planOrigin = priorOrigin;
		d.planSource = priorSource;
		ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, savedMarkers);
		persistedTemplatePalette(level, ref, fresh, newStorage);
		return checks;
	}

	private static void persistedTemplatePalette(net.minecraft.server.level.ServerLevel level,
			de.villagerplus.village.VillageRef ref, BlockPos house, BlockPos storage) {
		var data = ref.data();
		var savedConstruction = ref.bellEntity().getAttached(de.villagerplus.VillagerPlus.CONSTRUCTION);
		var savedMarkers = ref.bellEntity().getAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS);
		String savedType = data.planType;
		int savedVariant = data.planVariant, savedDir = data.planDir, savedStep = data.planStep;
		var savedOrigin = data.planOrigin;
		var savedSource = data.planSource;
		Palette local = localPalette();
		check(TemplatePalette.saved(local.ids()) == null, "existing eleven-role palettes retain the legacy NBT material rules");
		try {
			for (boolean warehouse : new boolean[] {false, true}) {
				BlockPos origin = warehouse ? storage : house;
				String type = warehouse ? Blueprints.STORAGE : Blueprints.HOUSE;
				var dir = Direction.SOUTH;
				int variant = Builder.newPlanVariant(type, 0x1234L);
				var design = Builder.designForPlan(type, variant);
				if (warehouse) Builder.markStorageTemplatePlan(ref, origin);
				else Builder.markTemplatePlan(ref, origin, design);
				data.planType = type;
				data.planOrigin = java.util.Optional.of(origin);
				data.planDir = dir.get2DDataValue();
				data.planVariant = variant;
				data.planStep = 0;
				data.planSource = java.util.Optional.empty();
				ConstructionSite planned = new ConstructionSite(origin, List.of(), List.of(),
					true, TemplatePalette.ids(local));
				var ops = com.mojang.serialization.JsonOps.INSTANCE;
				ConstructionSite decoded = ConstructionSite.CODEC.parse(ops,
					ConstructionSite.CODEC.encodeStart(ops, planned).getOrThrow()).getOrThrow();
				ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, decoded);
				Builder.forgetStepsForCheck(origin);
				List<Step> initial = Builder.stepsForCheck(level, ref, origin, dir);
				data.planStep = 40;
				Builder.forgetStepsForCheck(origin);
				List<Step> resumed = Builder.stepsForCheck(level, ref, origin, dir);
				check(initial != null && initial.stream().anyMatch(step -> step.item() == Items.BIRCH_DOOR)
					&& initial.stream().anyMatch(step -> step.item() == Items.ACACIA_PLANKS)
					&& samePlan(initial, resumed)
					&& TemplatePalette.saved(decoded.paletteIds()).equals(local),
					type + " retains the persisted NBT wall, door and cursor on reconstruction");
				Built built = new Built(type, origin, dir.get2DDataValue(), variant, decoded.paletteIds());
				Built decodedBuilt = Built.CODEC.parse(ops, Built.CODEC.encodeStart(ops, built).getOrThrow()).getOrThrow();
				List<Step> completed = Builder.stepsOfBuilt(level, ref, decodedBuilt, null);
				check(completed.stream().map(Step::pos).toList().equals(initial.stream().map(Step::pos).toList())
					&& completed.stream().anyMatch(step -> step.item() == Items.BIRCH_DOOR)
					&& completed.stream().anyMatch(step -> step.item() == Items.ACACIA_PLANKS),
					type + " reconstructs completed buildings with the same NBT palette");
			}
		} finally {
			data.planType = savedType;
			data.planDir = savedDir;
			data.planVariant = savedVariant;
			data.planStep = savedStep;
			data.planOrigin = savedOrigin;
			data.planSource = savedSource;
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.CONSTRUCTION, savedConstruction);
			ref.bellEntity().setAttached(de.villagerplus.VillagerPlus.TEMPLATE_PLANS, savedMarkers);
			Builder.forgetStepsForCheck(house);
			Builder.forgetStepsForCheck(storage);
		}
	}

	private static boolean storageWindow(Step step) {
		return step.item() == Items.GLASS || step.item() == Items.GLASS_PANE;
	}

	private static void checkStorageRepairReload(ServerLevel level, VillageRef ref, Built storage, List<Step> asBuilt,
			BlockPos missingChest, BlockPos missingWindow) {
		VillageData data = ref.data();
		Set<BlockPos> stillMissing = Set.of(missingChest, missingWindow);
		List<Built> savedBuildings = Builder.builtOf(ref);
		Map<BlockPos, BlockState> oldStates = new HashMap<>();
		Set<Long> loaded = new HashSet<>();
		try {
			for (Step step : asBuilt) {
				BlockPos pos = step.pos();
				if (loaded.add(net.minecraft.world.level.ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4))) level.getChunkAt(pos);
				oldStates.put(pos, level.getBlockState(pos));
				BlockState state = stillMissing.contains(pos) || step.item() == null ? Blocks.AIR.defaultBlockState()
					: step.make().apply(net.minecraft.world.level.block.Block.byItem(step.item()));
				level.setBlock(pos, state, 2);
			}
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, List.of(storage));
			var project = new VillageData.BuildProject(storage.type(), storage.origin(), storage.dir(), 1, storage.variant(),
				Optional.empty(), level.getGameTime(), Optional.empty(), 0, List.of(), VillageData.ProjectHaul.empty(),
				Optional.empty(), Optional.empty(), Optional.empty(), true, false);
			data.putBuildProject(project);
			data.planStep = project.step();
			Builder.forgetStepsForCheck(storage.origin());
			List<Step> resumed = Builder.stepsForCheck(level, ref, storage.origin(), Direction.from2DDataValue(storage.dir()));
			check(resumed != null && data.planStep == 0 && resumed.size() == stillMissing.size()
				&& resumed.stream().map(Step::pos).collect(java.util.stream.Collectors.toSet()).equals(stillMissing),
				"a warehouse repair reload rescans its missing chest and window, without replaying the finished structure or skipping unresolved damage");
		} finally {
			for (var original : oldStates.entrySet()) level.setBlock(original.getKey(), original.getValue(), 2);
			data.removeBuildProject(storage.origin());
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, savedBuildings);
			Builder.forgetStepsForCheck(storage.origin());
		}
	}

	private static boolean samePlan(List<Step> first, List<Step> second) {
		return first != null && second != null && first.size() == second.size()
			&& java.util.stream.IntStream.range(0, first.size()).allMatch(index -> {
				Step a = first.get(index), b = second.get(index);
				return a.pos().equals(b.pos()) && a.item() == b.item() && java.util.Objects.equals(a.tag(), b.tag())
					&& a.cost() == b.cost() && a.optional() == b.optional() && java.util.Objects.equals(a.linkedTo(), b.linkedTo());
			});
	}

	private static String firstDifference(List<Step> first, List<Step> second) {
		if (first == null || second == null) return "null-list";
		if (first.size() != second.size()) return "size:" + first.size() + "/" + second.size();
		for (int i = 0; i < first.size(); i++) {
			Step a = first.get(i), b = second.get(i);
			if (!a.pos().equals(b.pos()) || a.item() != b.item() || !java.util.Objects.equals(a.tag(), b.tag())
				|| a.cost() != b.cost() || a.optional() != b.optional() || !java.util.Objects.equals(a.linkedTo(), b.linkedTo())) {
				return i + ":" + a.pos() + "/" + a.item() + "/" + a.cost() + " vs " + b.pos() + "/" + b.item() + "/" + b.cost();
			}
		}
		return "none";
	}
}
