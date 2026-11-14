package de.villagerplus.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Pure plan-level coverage for the deterministic residential design catalog. */
public final class HouseDesignCatalogChecks {
	private HouseDesignCatalogChecks() { }

	public static int run() {
		int checks = 0;
		Palette palette = new Palette(Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.SPRUCE_LOG,
			Blocks.STONE_BRICKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB,
			Blocks.GLASS_PANE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE);
		Palette badlandsPalette = new Palette(Blocks.RED_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.ACACIA_LOG,
			Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.RED_SANDSTONE_STAIRS, Blocks.RED_SANDSTONE_SLAB,
			Blocks.GLASS_PANE, Blocks.CUT_SANDSTONE, Blocks.ACACIA_DOOR, Blocks.ACACIA_FENCE);
		long seed = 731_942_857L;
		BlockPos center = new BlockPos(0, 64, 0);
		Map<String, String> expectedFootprints = Map.ofEntries(
			Map.entry("compact_cottage", "2x2"),
			Map.entry("compact_porch", "2x2"),
			Map.entry("compact_lean_to", "2x2"),
			Map.entry("compact_garden_home", "2x3"),
			Map.entry("gable_porch", "3x2"),
			Map.entry("long_gable", "4x2"),
			Map.entry("standard_annex", "3x3"),
			Map.entry("standard_courtyard", "3x3"),
			Map.entry("two_storey_gable", "3x3"),
			Map.entry("tall_townhouse", "4x3"),
			Map.entry("multi_storey_annex", "3x3"),
			Map.entry("multi_storey_garden_house", "4x3"));
		Map<String, Integer> expectedBeds = Map.ofEntries(
			Map.entry("compact_cottage", 1),
			Map.entry("compact_porch", 1),
			Map.entry("compact_lean_to", 1),
			Map.entry("compact_garden_home", 1),
			Map.entry("gable_porch", 1),
			Map.entry("long_gable", 2),
			Map.entry("standard_annex", 1),
			Map.entry("standard_courtyard", 2),
			Map.entry("two_storey_gable", 1),
			Map.entry("tall_townhouse", 2),
			Map.entry("multi_storey_annex", 1),
			Map.entry("multi_storey_garden_house", 2));

		for (Architect.SizeTier tier : List.of(Architect.SizeTier.COMPACT, Architect.SizeTier.STANDARD,
			Architect.SizeTier.MULTI_STOREY)) {
			List<HouseDesignCatalog.Preset> choices = HouseDesignCatalog.presets(tier);
			check(choices.size() >= 4, "each residential size tier has four curated, visibly different choices"); checks++;
			check(HouseDesignCatalog.presetFor(tier, seed) == HouseDesignCatalog.presetFor(tier, seed)
				&& HouseDesignCatalog.design(tier, seed).equals(HouseDesignCatalog.design(tier, seed)),
				"preset choice and Architect.Design are deterministic for a tier and seed"); checks++;
			int variant = HouseDesignCatalog.newPlanVariant(tier, seed);
			long encodedSeed = Integer.toUnsignedLong(variant) >>> 2;
			check(HouseDesignCatalog.usesCatalog(variant) && (encodedSeed >>> 28) == 0L
				&& HouseDesignCatalog.designForPlanVariant(variant).equals(HouseDesignCatalog.design(tier, seed)),
				"versioned saved plan variants reconstruct the same catalog design"); checks++;
			int catalogOneVariant = (int) (Architect.withSizeTier(seed, tier) << 2) | 1;
			Architect.Design oldCatalogDesign = HouseDesignCatalog.designForPlanVariant(catalogOneVariant);
			check(HouseDesignCatalog.usesCatalog(catalogOneVariant) && oldCatalogDesign.wing() == 0
				&& oldCatalogDesign.doorOffset() == 0
				&& (tier == Architect.SizeTier.MULTI_STOREY || oldCatalogDesign.overhang() == 0),
				"catalog-1 saved plans keep their original silhouette and roofline after the catalog grows"); checks++;
		}
		checks += checkJobHutProfiles(seed, palette, center);

		long legacySeed = Architect.withSizeTier(seed, Architect.SizeTier.STANDARD);
		int legacyVariant = (int) (legacySeed << 2);
		int mirroredTemplateVariant = 1;
		check(!HouseDesignCatalog.usesCatalog(legacyVariant)
			&& HouseDesignCatalog.designForPlanVariant(legacyVariant).equals(
			Architect.designForPlan(Architect.Kind.HOUSE, legacyVariant))
			&& !HouseDesignCatalog.usesCatalog(mirroredTemplateVariant)
			&& HouseDesignCatalog.designForPlanVariant(mirroredTemplateVariant).equals(
			Architect.designForPlan(Architect.Kind.HOUSE, mirroredTemplateVariant))
			&& !HouseDesignCatalog.usesCatalog(mirroredTemplateVariant, true)
			&& HouseDesignCatalog.designForPlanVariant(mirroredTemplateVariant, true).equals(
			Architect.designForPlan(Architect.Kind.HOUSE, mirroredTemplateVariant)),
			"pre-catalog plans and mirrored template variants retain legacy reconstruction"); checks++;
		check(!HouseDesignCatalog.usesCatalog(2)
			&& HouseDesignCatalog.designForPlanVariant(2).equals(Architect.designForPlan(Architect.Kind.HOUSE, 2)),
			"legacy source-free material-substitution bit remains distinct from the catalog marker"); checks++;

		Set<String> observedPresets = new HashSet<>();
		for (long choiceSeed = 0; choiceSeed < 256; choiceSeed++) {
			for (Architect.SizeTier tier : List.of(Architect.SizeTier.COMPACT, Architect.SizeTier.STANDARD,
				Architect.SizeTier.MULTI_STOREY)) {
				observedPresets.add(HouseDesignCatalog.presetFor(tier, choiceSeed).id());
			}
		}
		check(observedPresets.size() == HouseDesignCatalog.presets().size(),
			"seed selection reaches every curated cottage, annex, longhouse and multi-storey form"); checks++;

		Set<Integer> observedPaletteAccents = new HashSet<>();
		boolean noHardCodedSprucePile = true;
		for (long decorationSeed = 0; decorationSeed < 256; decorationSeed++) {
			for (HouseDesignCatalog.Preset preset : HouseDesignCatalog.presets()) {
				int accent = preset.design(decorationSeed).deco() & 3;
				observedPaletteAccents.add(accent);
				noHardCodedSprucePile &= accent != 3;
			}
		}
		check(noHardCodedSprucePile && observedPaletteAccents.equals(Set.of(0, 1, 2)),
			"new catalog houses keep three deterministic accents while excluding Architect's hard-coded spruce-log pile"); checks++;
		Architect.Design badlandsAccentDesign = HouseDesignCatalog.Preset.COMPACT_COTTAGE.design(5L);
		List<Step> badlandsAccentSteps = Architect.build(badlandsAccentDesign, badlandsPalette,
			new BlockPos(0, 64, 0), Direction.SOUTH);
		check(badlandsAccentSteps.stream().noneMatch(step -> step.free() && step.linkedTo() == null
			&& freeState(step).is(Blocks.SPRUCE_LOG)),
			"a dry-biome house with the formerly spruce-pile seed keeps its yard accent palette-coherent"); checks++;

		Set<String> silhouetteSignatures = new HashSet<>();
		Set<String> rooflineSignatures = new HashSet<>();
		Set<String> facadeRhythms = new HashSet<>();
		Set<Integer> compactWallHeights = new HashSet<>();
		Set<Integer> standardWallHeights = new HashSet<>();
		Set<Integer> multiStoreyWallHeights = new HashSet<>();
		Set<Item> paletteItems = paletteItems(palette);
		for (HouseDesignCatalog.Preset preset : HouseDesignCatalog.presets()) {
			Architect.Design design = preset.design(seed);
			if (preset.tier() == Architect.SizeTier.COMPACT) compactWallHeights.add(design.wallH());
			if (preset.tier() == Architect.SizeTier.STANDARD) standardWallHeights.add(design.wallH());
			if (preset.tier() == Architect.SizeTier.MULTI_STOREY) multiStoreyWallHeights.add(design.wallH());
			String expectedFootprint = expectedFootprints.get(preset.id());
			Integer expectedBedCount = expectedBeds.get(preset.id());
			check(expectedFootprint != null && expectedBedCount != null
				&& (design.hw() + "x" + design.hd()).equals(expectedFootprint)
				&& design.beds() == expectedBedCount,
				preset.id() + " preserves its saved footprint and configured residential bed layout"); checks++;
			Set<Integer> entranceOffsets = new HashSet<>();
			for (long entranceSeed = 0; entranceSeed < 16; entranceSeed++) {
				entranceOffsets.add(preset.design(entranceSeed).doorOffset());
			}
			check(entranceOffsets.equals(Set.of(-1, 1)),
				preset.id() + " deterministically balances its entry between both sides of the facade"); checks++;
			rooflineSignatures.add(design.ridgeAlongPerp() + ":" + design.overhang());
			facadeRhythms.add(design.halfTimber() + ":" + design.windowSpacing() + ":" + design.secondWallUpper());
			List<Step> steps = Architect.build(design, palette, center, Direction.SOUTH);
			int doorI = Math.max(-design.hw() + 1, Math.min(design.hw() - 1, design.doorOffset()));
			Direction across = Direction.SOUTH.getClockWise();
			BlockPos leftPorchPost = center.relative(across, doorI - 1).relative(Direction.SOUTH, design.hd() + 2);
			BlockPos rightPorchPost = center.relative(across, doorI + 1).relative(Direction.SOUTH, design.hd() + 2);
			BlockPos porchHeader = center.relative(across, doorI).relative(Direction.SOUTH, design.hd() + 1).above(2);
			Set<BlockPos> positions = new HashSet<>();
			boolean insideSite = true;
			boolean fitsClearance = true;
			boolean paidMaterialsValid = true;
			boolean paidCostsValid = true;
			int minY = Integer.MAX_VALUE;
			int maxY = Integer.MIN_VALUE;
			long windowCount = 0;
			long frontWindowCount = 0;
			long upperFrontWindowCount = 0;
			long entryFramePostCount = 0;
			long porchPostCount = 0;
			long porchHeaderCount = 0;
			long bedFootCount = 0;
			boolean topFacadeBeam = false;
			boolean paidDoor = false;
			boolean freePath = false;
			boolean freeGarden = false;
			boolean alignedEntryPath = true;
			int entryPathCount = 0;
			for (Step step : steps) {
				positions.add(step.pos());
				int dx = step.pos().getX() - center.getX();
				int dz = step.pos().getZ() - center.getZ();
				minY = Math.min(minY, step.pos().getY());
				maxY = Math.max(maxY, step.pos().getY());
				insideSite &= Math.abs(dx) <= HouseDesignCatalog.siteHalf(design)
					&& Math.abs(dz) <= HouseDesignCatalog.siteHalf(design);
				fitsClearance &= step.pos().getY() < center.getY() + HouseDesignCatalog.siteHeadroom(design);
				if (!step.free() && step.linkedTo() == null) {
					paidCostsValid &= step.cost() == 1;
					paidMaterialsValid &= (step.item() != null && paletteItems.contains(step.item()))
						|| (step.item() == null && ItemTags.BEDS.equals(step.tag()));
					paidDoor |= step.item() == palette.door().asItem() && ItemTags.WOODEN_DOORS.equals(step.tag())
						&& projection(step.pos().subtract(center), Direction.SOUTH) == design.hd()
						&& projection(step.pos().subtract(center), across) == doorI;
					if (ItemTags.BEDS.equals(step.tag())) bedFootCount++;
					if (step.item() == palette.window().asItem()) {
						windowCount++;
						if (projection(step.pos().subtract(center), Direction.SOUTH) == design.hd()) {
							frontWindowCount++;
							if (step.pos().getY() >= center.getY() + 2) upperFrontWindowCount++;
						}
					}
					if (step.item() == palette.frame().asItem() && step.pos().getY() == center.getY()) {
						int front = projection(step.pos().subtract(center), Direction.SOUTH);
						int side = projection(step.pos().subtract(center), across);
						if (front == design.hd() && Math.abs(side - doorI) == 1) entryFramePostCount++;
					}
					if (step.item() == palette.fence().asItem()
						&& (step.pos().equals(leftPorchPost) || step.pos().equals(rightPorchPost))) porchPostCount++;
					if (step.item() != null && step.pos().equals(porchHeader)
						&& (step.item() == palette.roofSlab().asItem() || step.item() == palette.roofStair().asItem()
							|| step.item() == palette.roofFull().asItem())) porchHeaderCount++;
					if (step.item() == palette.frame().asItem() && step.pos().getY() == center.getY() + design.wallH() - 1
						&& projection(step.pos().subtract(center), Direction.SOUTH) == design.hd()
						&& projection(step.pos().subtract(center), across) == 0) topFacadeBeam = true;
				}
				if (step.free() && step.linkedTo() == null) {
					BlockState state = freeState(step);
					freePath |= state.is(Blocks.DIRT_PATH);
					if (state.is(Blocks.DIRT_PATH)) {
						entryPathCount++;
						alignedEntryPath &= projection(step.pos().subtract(center), across) == doorI;
					}
					freeGarden |= BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("potted_");
				}
			}
			silhouetteSignatures.add(silhouetteSignature(steps, center));
			check(!steps.isEmpty() && positions.size() == steps.size() && insideSite && fitsClearance
				&& minY < center.getY() && maxY < center.getY() + HouseDesignCatalog.siteHeadroom(design),
				preset.id() + " produces unique steps inside its reserved site and vertical clearance"); checks++;
			check(paidMaterialsValid && paidCostsValid,
				preset.id() + " charges one unit of local palette material or one real bed per paid step"); checks++;
			check(bedFootCount == design.beds(),
				preset.id() + " keeps exactly its configured, paid bed count"); checks++;
			check(paidDoor && windowCount >= 2,
				preset.id() + " has a paid wooden door and multiple palette windows"); checks++;
			boolean tierWindowAccent = preset.tier() == Architect.SizeTier.COMPACT
				? frontWindowCount >= 1 : upperFrontWindowCount >= 2;
			check(tierWindowAccent,
				preset.id() + (preset.tier() == Architect.SizeTier.COMPACT
					? " keeps a paid window visible beside its compact entry"
					: " shows paired, paid windows on its street-facing upper wall")); checks++;
			boolean entryTreatment = design.halfTimber() ? entryFramePostCount >= 1
				: porchPostCount == 2 && porchHeaderCount == 1;
			check(entryTreatment && topFacadeBeam,
				preset.id() + " gives the entry a porch or timber frame below the palette facade beam"); checks++;
			check(porchPostCount == 2 && porchHeaderCount == 1,
				preset.id() + " has two paid entrance posts and a palette lintel canopy"); checks++;
			check(freePath && entryPathCount == 3 && alignedEntryPath && freeGarden,
				preset.id() + " aligns its three-block approach with the chosen door and front garden"); checks++;
			check(steps.stream().anyMatch(step -> step.item() == palette.roofStair().asItem()
				|| step.item() == palette.roofFull().asItem()
				|| (palette.roofSlab() != null && step.item() == palette.roofSlab().asItem())),
				preset.id() + " builds the roof from the detected local roof palette"); checks++;
			if (design.porch()) {
				check(steps.stream().filter(step -> step.item() == palette.fence().asItem()).count() >= 4,
					preset.id() + " includes the paid palette-fence porch posts"); checks++;
			}
			if (design.wing() != 0) {
				check(steps.stream().filter(step -> step.item() == palette.window().asItem()).count() >= 4,
					preset.id() + " gives its side annex visible windows"); checks++;
			}
			if (preset.tier() == Architect.SizeTier.MULTI_STOREY) {
				check(Architect.multiStorey(design) && steps.stream().anyMatch(step -> step.item() == palette.floor().asItem()
					&& step.pos().getY() == center.getY() + 3),
					preset.id() + " includes a real, palette-paid upper floor"); checks++;
			}
			checks += checkRotations(design, palette, center, preset.id());

			List<Step> badlandsSteps = Architect.build(design, badlandsPalette, center, Direction.SOUTH);
			Set<Item> badlandsItems = paletteItems(badlandsPalette);
			boolean badlandsMaterialsValid = badlandsSteps.stream()
				.filter(step -> !step.free() && step.linkedTo() == null)
				.allMatch(step -> step.cost() == 1 && ((step.item() != null && badlandsItems.contains(step.item()))
					|| (step.item() == null && ItemTags.BEDS.equals(step.tag()))));
			check(badlandsMaterialsValid && badlandsSteps.stream().anyMatch(step -> step.item() == badlandsPalette.wall().asItem())
				&& badlandsSteps.stream().anyMatch(step -> step.item() == badlandsPalette.roofStair().asItem()),
				preset.id() + " uses the supplied biome palette for paid walls and roof details"); checks++;
		}
		check(silhouetteSignatures.size() == HouseDesignCatalog.presets().size(),
			"all curated presets have different complete material-and-state layouts, including footprints and rooflines"); checks++;
		check(rooflineSignatures.size() >= 4,
			"curated homes use distinct ridge directions and tight, standard, and broad eaves"); checks++;
		check(facadeRhythms.size() >= 4,
			"curated facades vary timber cadence, window spacing, and upper-wall color breaks"); checks++;
		check(compactWallHeights.containsAll(Set.of(3, 4)) && standardWallHeights.equals(Set.of(5))
			&& multiStoreyWallHeights.contains(6),
			"compact, standard and multi-storey homes keep distinct 3/4-, 5- and 6-course walls, with a full upper window course on every standard facade"); checks++;
		return checks;
	}

	private static int checkJobHutProfiles(long seed, Palette palette, BlockPos center) {
		Set<Architect.Kind> kinds = new HashSet<>();
		Set<String> ids = new HashSet<>();
		Set<Architect.Design> designs = new HashSet<>();
		boolean profilesResolveDeterministically = true;
		boolean accentsAvoidHardCodedSprucePiles = true;
		boolean paidRoleMarksVisible = true;
		boolean everyTierHasSixShells = true;
		boolean workshopEavesKeepItsLaneEntryAndReservation = false;
		Architect.Design grainBarn = HouseDesignCatalog.jobHutDesign(Architect.Kind.GRANARY, seed,
			Architect.design(Architect.Kind.GRANARY, seed));
		List<Step> grainBarnSteps = Architect.build(grainBarn, palette, center, Direction.SOUTH);
		boolean grainBarnKeepsLowFrontGableAndPaidHayCrest = grainBarn.hw() == 4 && grainBarn.hd() == 3
			&& grainBarn.wallH() == 3 && !grainBarn.ridgeAlongPerp() && !grainBarn.porch()
			&& !grainBarn.chimney() && !Architect.multiStorey(grainBarn)
			&& hasPaidRoleMark(HouseDesignCatalog.JobHutProfile.GRAIN_BARN, grainBarnSteps);
		for (HouseDesignCatalog.JobHutProfile profile : HouseDesignCatalog.jobHutProfiles()) {
			Architect.Design base = Architect.design(profile.kind(), seed);
			Architect.Design designed = HouseDesignCatalog.jobHutDesign(profile.kind(), seed, base);
			profilesResolveDeterministically &= HouseDesignCatalog.jobHutProfile(profile.kind()) == profile
				&& designed.equals(HouseDesignCatalog.jobHutDesign(profile.kind(), seed, base))
				&& designed.kind() == profile.kind() && designed != base
				&& (designed.deco() & (1 << 3)) != 0;
			kinds.add(profile.kind());
			ids.add(profile.id());
			designs.add(designed);
			if (profile == HouseDesignCatalog.JobHutProfile.WORKSHOP) {
				workshopEavesKeepItsLaneEntryAndReservation = checkWorkshopEavesFront(designed, palette, center);
			}
			for (long accentSeed = 0; accentSeed < 256; accentSeed++) {
				Architect.Design accented = HouseDesignCatalog.jobHutDesign(profile.kind(), accentSeed,
					Architect.design(profile.kind(), accentSeed));
				accentsAvoidHardCodedSprucePiles &= (accented.deco() & 3) != 3;
			}
		}
		List<String> missingRoleMarks = new ArrayList<>();
		for (Architect.SizeTier tier : List.of(Architect.SizeTier.COMPACT, Architect.SizeTier.STANDARD,
			Architect.SizeTier.MULTI_STOREY)) {
			long tierSeed = Architect.withSizeTier(seed, tier);
			Set<String> shellSignatures = new HashSet<>();
			for (HouseDesignCatalog.JobHutProfile profile : HouseDesignCatalog.jobHutProfiles()) {
				Architect.Design base = Architect.design(profile.kind(), tierSeed);
				Architect.Design designed = HouseDesignCatalog.jobHutDesign(profile.kind(), tierSeed, base);
				shellSignatures.add(jobHutShellSignature(designed));
				boolean markOk = hasPaidRoleMark(profile,
					Architect.build(designed, palette, center, Direction.SOUTH));
				if (!markOk) missingRoleMarks.add(profile + "@" + tier);
				paidRoleMarksVisible &= markOk;
			}
			everyTierHasSixShells &= shellSignatures.size() == HouseDesignCatalog.jobHutProfiles().size();
		}
		check(profilesResolveDeterministically && accentsAvoidHardCodedSprucePiles && kinds.size() == 6 && ids.size() == 6
			&& designs.size() == HouseDesignCatalog.jobHutProfiles().size()
			&& paidRoleMarksVisible && everyTierHasSixShells
			&& grainBarnKeepsLowFrontGableAndPaidHayCrest
			&& workshopEavesKeepItsLaneEntryAndReservation
			&& HouseDesignCatalog.jobHutProfile(Architect.Kind.HOUSE) == null
			&& HouseDesignCatalog.jobHutDesign(Architect.Kind.HOUSE, seed, Architect.design(Architect.Kind.HOUSE, seed))
				.equals(Architect.design(Architect.Kind.HOUSE, seed)),
			"all six work-building kinds keep unique silhouettes and paid role marks; the granary stays a low hay-crested front gable and the workshop keeps its low 7x5 eaves-front profile"
				+ " [deterministic=" + profilesResolveDeterministically + ", accents=" + accentsAvoidHardCodedSprucePiles + ", kinds=" + kinds.size()
				+ ", ids=" + ids.size() + ", designs=" + designs.size() + "/" + HouseDesignCatalog.jobHutProfiles().size()
				+ ", roleMarks=" + paidRoleMarksVisible + missingRoleMarks + ", sixShells=" + everyTierHasSixShells
				+ ", granary=" + grainBarnKeepsLowFrontGableAndPaidHayCrest + ", workshop=" + workshopEavesKeepItsLaneEntryAndReservation + "]");
		return 1;
	}

	private static String jobHutShellSignature(Architect.Design design) {
		return design.hw() + ":" + design.hd() + ":" + design.wallH() + ":" + design.ridgeAlongPerp()
			+ ":" + design.overhang() + ":" + design.useBase() + ":" + design.halfTimber() + ":" + design.porch()
			+ ":" + design.chimney() + ":" + design.windowSpacing() + ":" + design.secondWallUpper();
	}

	/** Stone lintel item the school facade pays for: stone bricks when present, otherwise the cobblestone fallback. */
	private static Item paidStoneLintel(List<Step> steps) {
		for (Step step : steps) {
			if (step.item() == Blocks.STONE_BRICKS.asItem() && !step.free() && step.cost() == 1) return step.item();
		}
		return Blocks.COBBLESTONE.asItem();
	}

	private static boolean hasPaidRoleMark(HouseDesignCatalog.JobHutProfile profile, List<Step> steps) {
		List<Item> required = switch (profile) {
			// The facade mark is a paid stone lintel (stone bricks, or cobblestone when bricks are unobtainable);
			// bookshelves stay inside the classroom, see Architect.roleMarkers.
			case SCHOOLHOUSE -> List.of(paidStoneLintel(steps));
			case FREIGHT_SHED -> List.of(Blocks.BARREL.asItem(), Blocks.BARREL.asItem());
			case GRAIN_BARN -> List.of(Blocks.HAY_BLOCK.asItem());
			case WORKSHOP -> List.of(Blocks.ANVIL.asItem(), Blocks.CRAFTING_TABLE.asItem());
			case COOKHOUSE -> List.of(Blocks.SMOKER.asItem());
			case RAIL_DEPOT -> List.of(Blocks.RAIL.asItem(), Blocks.POWERED_RAIL.asItem());
		};
		Map<Item, Integer> counts = new HashMap<>();
		for (Step step : steps) {
			if (step.item() != null && !step.free() && step.cost() == 1) counts.merge(step.item(), 1, Integer::sum);
		}
		for (Item item : required) {
			int remaining = counts.getOrDefault(item, 0);
			if (remaining == 0) return false;
			counts.put(item, remaining - 1);
		}
		return true;
	}

	private static boolean checkWorkshopEavesFront(Architect.Design design, Palette palette, BlockPos center) {
		if (design.hw() != 3 || design.hd() != 2 || design.wallH() != 3 || !design.ridgeAlongPerp()
			|| design.porch() || design.chimney() || Architect.multiStorey(design)
			|| design.doorOffset() != 0 || design.half() != 5 || HouseDesignCatalog.siteHeadroom(design) != 7) return false;

		for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
			List<Step> steps = Architect.build(design, palette, center, facing);
			Map<BlockPos, Step> byPosition = index(steps);
			BlockPos doorway = center.relative(facing, design.hd());
			Step lowerDoor = byPosition.get(doorway);
			Step upperDoor = byPosition.get(doorway.above());
			boolean centeredPaidDoor = lowerDoor != null && lowerDoor.item() == palette.door().asItem()
				&& lowerDoor.cost() == 1 && !lowerDoor.free() && upperDoor != null
				&& upperDoor.linkedTo() != null && upperDoor.linkedTo().equals(doorway);
			Step eavesHeader = byPosition.get(doorway.above(design.wallH()));
			Block eavesTrim = palette.frame() != null ? palette.frame() : palette.base();
			boolean paidEavesHeader = eavesHeader != null && eavesTrim != null
				&& eavesHeader.item() == eavesTrim.asItem() && !eavesHeader.free() && eavesHeader.cost() == 1;
			Step frontEave = byPosition.get(doorway.above(design.wallH() + 1));
			boolean paidEavesFront = frontEave != null && frontEave.item() == palette.roofStair().asItem()
				&& !frontEave.free() && frontEave.cost() == 1
				&& frontEave.make().apply(palette.roofStair()).getValue(StairBlock.FACING) == facing.getOpposite()
				&& paidEavesHeader;
			boolean paidWorkMarks = steps.stream().anyMatch(step -> step.item() == Blocks.ANVIL.asItem()
				&& !step.free() && step.cost() == 1)
				&& steps.stream().anyMatch(step -> step.item() == Blocks.CRAFTING_TABLE.asItem()
					&& !step.free() && step.cost() == 1);
			boolean withinReservedSite = steps.stream().allMatch(step -> {
				BlockPos relative = step.pos().subtract(center);
				return Math.abs(relative.getX()) <= design.half() && Math.abs(relative.getZ()) <= design.half();
			});
			if (!centeredPaidDoor || !paidEavesFront || !paidWorkMarks
				|| byPosition.size() != steps.size() || !withinReservedSite) return false;
		}
		return true;
	}

	private static Set<Item> paletteItems(Palette palette) {
		Set<Item> items = new HashSet<>();
		for (Block block : new Block[] {palette.wall(), palette.wall2(), palette.frame(), palette.base(), palette.roofFull(),
			palette.roofStair(), palette.roofSlab(), palette.window(), palette.floor(), palette.door(), palette.fence()}) {
			if (block != null) items.add(block.asItem());
		}
		return items;
	}

	private static String silhouetteSignature(List<Step> steps, BlockPos center) {
		return steps.stream().filter(step -> !step.free() && step.linkedTo() == null && step.pos().getY() >= center.getY())
			.map(step -> {
				Block block = step.item() != null ? Block.byItem(step.item())
					: ItemTags.BEDS.equals(step.tag()) ? Blocks.BED.white() : Blocks.AIR;
				BlockState state = step.make().apply(block);
				return relative(step.pos(), center) + "=" + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + ":" + state;
			}).sorted().reduce((left, right) -> left + ";" + right).orElse("");
	}

	private static String relative(BlockPos pos, BlockPos center) {
		return (pos.getX() - center.getX()) + "," + (pos.getY() - center.getY()) + "," + (pos.getZ() - center.getZ());
	}

	private static int projection(BlockPos relative, Direction direction) {
		return relative.getX() * direction.getStepX() + relative.getZ() * direction.getStepZ();
	}

	private static BlockState freeState(Step step) {
		return step.make().apply(Blocks.AIR);
	}

	private static BlockState rotationState(Map<BlockPos, Step> plan, Step step) {
		return step.make().apply(rotationBlock(plan, step));
	}

	private static Block rotationBlock(Map<BlockPos, Step> plan, Step step) {
		if (step.item() != null) return Block.byItem(step.item());
		if (ItemTags.BEDS.equals(step.tag())) {
			return BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:white_bed"));
		}
		if (step.linkedTo() != null) {
			Step linked = plan.get(step.linkedTo());
			if (linked == null) throw new IllegalStateException("Missing linked house step at " + step.linkedTo());
			return rotationBlock(plan, linked);
		}
		if (step.free()) return Blocks.AIR;
		throw new IllegalStateException("House rotation check has no representative block at " + step.pos());
	}

	private static int checkRotations(Architect.Design design, Palette palette, BlockPos center, String presetId) {
		List<Step> south = Architect.build(design, palette, center, Direction.SOUTH);
		Map<BlockPos, Step> southByPosition = index(south);
		int checks = 0;
		for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.WEST)) {
			List<Step> rotated = Architect.build(design, palette, center, facing);
			Map<BlockPos, Step> rotatedByPosition = index(rotated);
			boolean positionAndCostInvariant = rotatedByPosition.size() == southByPosition.size();
			boolean directionalStatesRotate = true;
			String directionMismatch = null;
			for (Step source : south) {
				BlockPos expected = rotatePositionFromSouth(source.pos(), center, facing);
				Step target = rotatedByPosition.get(expected);
				if (target == null || source.cost() != target.cost() || source.free() != target.free()
					|| source.optional() != target.optional()
					|| !java.util.Objects.equals(source.item(), target.item())
					|| !java.util.Objects.equals(source.tag(), target.tag())
					|| !java.util.Objects.equals(source.linkedTo() == null ? null : rotatePositionFromSouth(source.linkedTo(), center, facing),
						target.linkedTo())) {
					positionAndCostInvariant = false;
					continue;
				}
				BlockState sourceState = rotationState(southByPosition, source);
				BlockState targetState = rotationState(rotatedByPosition, target);
				Direction sourceFacing = horizontalFacing(sourceState);
				Direction targetFacing = horizontalFacing(targetState);
				if (sourceFacing != null && targetFacing != rotateDirectionFromSouth(sourceFacing, facing)) {
					directionalStatesRotate = false;
					if (directionMismatch == null) directionMismatch = source.pos() + " "
						+ BuiltInRegistries.BLOCK.getKey(sourceState.getBlock()) + " " + sourceFacing
						+ " -> " + target.pos() + " " + BuiltInRegistries.BLOCK.getKey(targetState.getBlock())
						+ " " + targetFacing + " (expected "
						+ rotateDirectionFromSouth(sourceFacing, facing) + ")";
				}
				if (sourceState.hasProperty(RotatedPillarBlock.AXIS) && targetState.hasProperty(RotatedPillarBlock.AXIS)) {
					Direction.Axis axis = sourceState.getValue(RotatedPillarBlock.AXIS);
					Direction.Axis expectedAxis = quarterTurns(facing) % 2 == 0 ? axis
						: axis == Direction.Axis.X ? Direction.Axis.Z : axis == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Y;
					if (targetState.getValue(RotatedPillarBlock.AXIS) != expectedAxis) {
						directionalStatesRotate = false;
						if (directionMismatch == null) directionMismatch = source.pos() + " axis " + axis
							+ " -> " + target.pos() + " axis " + targetState.getValue(RotatedPillarBlock.AXIS)
							+ " (expected " + expectedAxis + ")";
					}
				}
			}
			check(positionAndCostInvariant, presetId + " keeps its footprint, paid costs and linked blocks when facing " + facing);
			checks++;
			check(directionalStatesRotate, presetId + " rotates doors, roof stairs, shutters and timber axes toward " + facing
				+ (directionMismatch == null ? "" : ": " + directionMismatch));
			checks++;
		}
		return checks;
	}

	private static Map<BlockPos, Step> index(List<Step> steps) {
		Map<BlockPos, Step> indexed = new HashMap<>();
		for (Step step : steps) indexed.put(step.pos(), step);
		return indexed;
	}

	private static BlockPos rotatePositionFromSouth(BlockPos pos, BlockPos center, Direction facing) {
		int side = center.getX() - pos.getX();
		int forward = pos.getZ() - center.getZ();
		return center.relative(facing.getClockWise(), side).relative(facing, forward).above(pos.getY() - center.getY());
	}

	private static Direction horizontalFacing(BlockState state) {
		if (state.hasProperty(DoorBlock.FACING)) return state.getValue(DoorBlock.FACING);
		if (state.hasProperty(StairBlock.FACING)) return state.getValue(StairBlock.FACING);
		if (state.hasProperty(TrapDoorBlock.FACING)) return state.getValue(TrapDoorBlock.FACING);
		return null;
	}

	private static Direction rotateDirectionFromSouth(Direction direction, Direction facing) {
		Direction result = direction;
		for (int turn = 0; turn < quarterTurns(facing); turn++) result = result.getClockWise();
		return result;
	}

	private static int quarterTurns(Direction facing) {
		int turns = 0;
		for (Direction current = Direction.SOUTH; current != facing && turns < 4; current = current.getClockWise()) turns++;
		return turns;
	}

	private static void check(boolean result, String description) {
		if (!result) throw new IllegalStateException("House design catalog: " + description);
	}
}
