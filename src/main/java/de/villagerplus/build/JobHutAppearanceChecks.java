package de.villagerplus.build;

import de.villagerplus.village.VillageStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Focused deterministic appearance and material checks for the main profession work sites. */
public final class JobHutAppearanceChecks {
	private JobHutAppearanceChecks() { }

	public static int run() {
		Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.COBBLESTONE,
			Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.OAK_PLANKS,
			Blocks.OAK_DOOR, Blocks.OAK_FENCE);
		VillageStyle style = new VillageStyle(Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.OAK_PLANKS,
			Blocks.OAK_STAIRS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
		BlockPos origin = new BlockPos(120, 64, -80);
		long seed = 0x234567L;
		int checks = 0;
		checks += checkJobHutProfiles(palette, origin, seed);

		for (Direction facing : Direction.Plane.HORIZONTAL) {
			checks += checkArchitectCue(Architect.Kind.TOOLSHED, Blocks.ANVIL, Blocks.CRAFTING_TABLE,
				palette, origin, facing, seed);
			checks += checkArchitectCue(Architect.Kind.SCHOOL, Blocks.STONE_BRICKS, null, // paid stone lintel; shelves stay inside the classroom
				palette, origin, facing, seed);
			checks += checkSchoolFront(palette, origin, facing, seed);
			checks += checkSchoolPaletteSanitization(origin, facing, seed);
			checks += checkArchitectCue(Architect.Kind.GRANARY, Blocks.HAY_BLOCK, null,
				palette, origin, facing, seed);
			checks += checkArchitectCue(Architect.Kind.KITCHEN, Blocks.SMOKER, null,
				palette, origin, facing, seed);
			checks += checkArchitectCue(Architect.Kind.STATION, Blocks.RAIL, Blocks.POWERED_RAIL,
				palette, origin, facing, seed);
			checks += checkStorageLoadingBay(palette, origin, facing, seed);
			checks += checkField(style, origin, facing);
			checks += checkPen(style, origin, facing);
		}
		checks += checkStorageSizeTiers(palette, origin, seed);
		return checks;
	}

	/** Every new worksite plan resolves through its profession profile and remains within its reserved site. */
	private static int checkJobHutProfiles(Palette palette, BlockPos origin, long seed) {
		Map<Architect.Kind, String> profileIds = new HashMap<>();
		Set<String> geometryProfiles = new HashSet<>();
		Palette badlandsPalette = new Palette(Blocks.RED_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.ACACIA_LOG, Blocks.SANDSTONE,
			Blocks.RED_SANDSTONE, Blocks.RED_SANDSTONE_STAIRS, Blocks.RED_SANDSTONE_SLAB, Blocks.GLASS_PANE,
			Blocks.CUT_SANDSTONE, Blocks.ACACIA_DOOR, Blocks.ACACIA_FENCE);
		Set<Item> paletteItems = paletteItems(badlandsPalette);
		Set<Item> professionalMarks = Set.of(Blocks.CHISELED_BOOKSHELF.asItem(), Blocks.BOOKSHELF.asItem(),
			Blocks.LECTERN.asItem(),
			Blocks.HAY_BLOCK.asItem(), Blocks.ANVIL.asItem(), Blocks.CRAFTING_TABLE.asItem(), Blocks.SMOKER.asItem(),
			Blocks.RAIL.asItem(), Blocks.POWERED_RAIL.asItem());
		boolean plansResolveSafely = true;
		List<String> failedProfiles = new ArrayList<>();
		for (HouseDesignCatalog.JobHutProfile profile : HouseDesignCatalog.jobHutProfiles()) {
			Architect.Kind kind = profile.kind();
			String type = planType(kind);
			int versionedVariant = Builder.newPlanVariant(type, seed);
			long savedSeed = Integer.toUnsignedLong(versionedVariant) >>> 2;
			Architect.Design design = Builder.designForPlan(type, versionedVariant);
			Architect.Design expected = HouseDesignCatalog.jobHutDesign(kind, savedSeed, Architect.design(kind, savedSeed));
			int legacyVariant = versionedVariant & ~1;
			Architect.Design legacy = Builder.designForPlan(type, legacyVariant);
			List<Step> steps = Architect.build(design, palette, origin, Direction.SOUTH);
			List<Step> drySteps = Architect.build(design, badlandsPalette, origin, Direction.SOUTH);
			index(steps, profile.id());
			// Design.half() leaves one block outside the roof for the site's eaves margin.
			int siteHalf = Math.max(design.half(), Math.max(design.hw(), design.hd()) + design.overhang());
			boolean withinFootprint = steps.stream().allMatch(step -> {
				BlockPos relative = step.pos().subtract(origin);
				return Math.abs(relative.getX()) <= siteHalf && Math.abs(relative.getZ()) <= siteHalf;
			});
			boolean paidOnce = steps.stream().filter(step -> !step.free() && step.linkedTo() == null)
				.allMatch(step -> step.cost() == 1 && step.item() != null);
			// Barrels are the storage profile's paid loading-bay marker, and the school facade carries a paid
			// stone lintel (stone bricks, or cobblestone when bricks are unobtainable); neither is a biome-palette block.
			java.util.function.Predicate<Step> acceptsJobMark = step -> professionalMarks.contains(step.item())
				|| kind == Architect.Kind.STORAGE && step.item() == Blocks.BARREL.asItem()
				|| kind == Architect.Kind.SCHOOL && (step.item() == Blocks.STONE_BRICKS.asItem()
					|| step.item() == Blocks.COBBLESTONE.asItem());
			boolean dryPaletteCoherent = drySteps.stream().filter(step -> !step.free() && step.linkedTo() == null)
				.allMatch(step -> step.cost() == 1 && step.item() != null
					&& (paletteItems.contains(step.item()) || acceptsJobMark.test(step)))
				&& drySteps.stream().noneMatch(step -> step.free() && step.linkedTo() == null
					&& state(step).is(Blocks.SPRUCE_LOG));
			boolean profileResolves = HouseDesignCatalog.jobHutProfile(kind) == profile
				&& Architect.hasRoleMarkerVersion(kind, versionedVariant)
				&& design.equals(expected) && design.equals(Architect.design(kind, savedSeed, true))
				&& legacy.equals(Architect.design(kind, savedSeed))
				&& withinFootprint && paidOnce && dryPaletteCoherent;
			if (!profileResolves) failedProfiles.add(kind + "[marked=" + Architect.hasRoleMarkerVersion(kind, versionedVariant)
				+ ",expected=" + design.equals(expected) + ",versioned="
				+ design.equals(Architect.design(kind, savedSeed, true)) + ",legacy="
				+ legacy.equals(Architect.design(kind, savedSeed)) + ",footprint=" + withinFootprint
				+ ",paid=" + paidOnce + ",palette=" + dryPaletteCoherent
				+ ",outside=" + steps.stream().filter(step -> {
					BlockPos relative = step.pos().subtract(origin);
					return Math.abs(relative.getX()) > siteHalf || Math.abs(relative.getZ()) > siteHalf;
				}).map(step -> step.item() + "@" + step.pos() + "/free=" + step.free())
					.collect(java.util.stream.Collectors.joining(","))
				+ ",dryMismatch=" + drySteps.stream().filter(step -> !step.free() && step.linkedTo() == null
					&& (step.cost() != 1 || step.item() == null
						|| !(paletteItems.contains(step.item()) || acceptsJobMark.test(step))))
					.map(step -> step.item() + "@" + step.pos())
					.collect(java.util.stream.Collectors.joining(",")) + "]");
			plansResolveSafely &= profileResolves;
			profileIds.put(kind, profile.id());
			geometryProfiles.add(geometrySignature(design));
		}
		check(plansResolveSafely && profileIds.size() == 6
			&& new java.util.HashSet<>(profileIds.values()).size() == 6
			&& geometryProfiles.size() == HouseDesignCatalog.jobHutProfiles().size(),
			"all six profession plans reconstruct distinct paid profiles within their reserved footprints; failures=" + failedProfiles);
		return 1;
	}

	private static String geometrySignature(Architect.Design design) {
		return design.hw() + ":" + design.hd() + ":" + design.wallH() + ":" + design.ridgeAlongPerp()
			+ ":" + design.overhang() + ":" + design.porch() + ":" + design.chimney()
			+ ":" + design.halfTimber() + ":" + Architect.multiStorey(design);
	}

	private static Set<Item> paletteItems(Palette palette) {
		Set<Item> items = new HashSet<>();
		for (Block block : new Block[] {palette.wall(), palette.wall2(), palette.frame(), palette.base(), palette.roofFull(),
			palette.roofStair(), palette.roofSlab(), palette.window(), palette.floor(), palette.door(), palette.fence()}) {
			if (block != null) items.add(block.asItem());
		}
		return items;
	}

	private static int checkArchitectCue(Architect.Kind kind, Block firstCue, Block secondCue, Palette palette,
		BlockPos origin, Direction facing, long seed) {
		String type = planType(kind);
		int variant = Builder.newPlanVariant(type, seed);
		Architect.Design design = Builder.designForPlan(type, variant);
		List<Step> steps = Architect.build(design, palette, origin, facing);
		Map<BlockPos, Step> byPosition = index(steps, kind + " " + facing);
		boolean cuePaid = hasPaid(steps, firstCue)
			&& (secondCue == null || hasPaid(steps, secondCue));
		boolean cueOnFacade = steps.stream().filter(step -> step.item() == firstCue.asItem()
			|| (secondCue != null && step.item() == secondCue.asItem()))
			.allMatch(step -> projection(step.pos().subtract(origin), facing) == design.hd());
		boolean uniquePaidSteps = steps.stream().filter(step -> !step.free() && step.linkedTo() == null)
			.allMatch(step -> step.cost() == 1 && step.item() != null);
		check(cuePaid && cueOnFacade && uniquePaidSteps,
			kind + " retains its distinct paid facade cue after rotation to " + facing);
		check(byPosition.size() == steps.size(), kind + " has no overlapping facade or interior steps for " + facing);
		return 2;
	}

	/** The school front keeps its doorway centered, its paired windows clear, and its sole book crest paid. */
	private static int checkSchoolFront(Palette palette, BlockPos origin, Direction facing, long seed) {
		int variant = Builder.newPlanVariant(Blueprints.SCHOOL, seed);
		Architect.Design design = Builder.designForPlan(Blueprints.SCHOOL, variant);
		List<Step> steps = Architect.build(design, palette, origin, facing);
		Map<BlockPos, Step> byPosition = index(steps, "school front " + facing);
		int doorI = Math.max(-design.hw() + 1, Math.min(design.hw() - 1, design.doorOffset()));
		BlockPos lowerDoor = offset(origin, facing, doorI, design.hd());
		Step lower = byPosition.get(lowerDoor);
		Step upper = byPosition.get(lowerDoor.above());
		boolean centeredDoor = doorI == 0 && paid(lower, palette.door()) && upper != null
			&& upper.linkedTo() != null && upper.linkedTo().equals(lowerDoor)
			&& lower.make().apply(palette.door()).getValue(DoorBlock.FACING) == facing.getOpposite()
			&& upper.make().apply(palette.door()).getValue(DoorBlock.FACING) == facing.getOpposite()
			&& upper.make().apply(palette.door()).getValue(DoorBlock.HALF)
				== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
		boolean pairedJambs = true;
		for (int side : new int[] {-1, 1}) for (int y = 0; y <= 1; y++) {
			pairedJambs &= paid(byPosition.get(offset(origin, facing, side, design.hd()).above(y)), palette.frame());
		}
		check(centeredDoor && pairedJambs,
			"school " + facing + " keeps a centered two-block doorway with matching paid side frames");

		boolean pairedWindows = design.hw() == 3;
		Step firstHead = null;
		for (int side : new int[] {-1, 1}) {
			BlockPos pane = offset(origin, facing, side * (design.hw() - 1), design.hd()).above(1);
			Step window = byPosition.get(pane);
			pairedWindows &= paid(window, palette.window()) && state(window).is(palette.window());
			Step head = byPosition.get(pane.above());
			// Window heads are paid stone (stone bricks, or cobblestone when bricks are unobtainable).
			pairedWindows &= (paid(head, Blocks.STONE_BRICKS) || paid(head, Blocks.COBBLESTONE))
				&& (firstHead == null || head.item() == firstHead.item());
			if (firstHead == null) firstHead = head;
		}
		long frontWindowCount = steps.stream().filter(step -> step.item() == palette.window().asItem()
			&& projection(step.pos().subtract(origin), facing) == design.hd()).count();
		pairedWindows &= frontWindowCount == 3; // two paired lower windows plus the gable window
		check(pairedWindows,
			"school " + facing + " has symmetric, unobstructed front windows with solid paid heads above"
				+ " [hw=" + design.hw() + ", frontWindows=" + frontWindowCount + ", firstHead=" + (firstHead == null ? "none" : String.valueOf(firstHead.item())) + "]");

		BlockPos crestPos = lowerDoor.above(2);
		// The facade mark is a paid, centered stone lintel above the entrance; bookshelves stay inside the classroom.
		boolean singlePaidCrest = (paid(byPosition.get(crestPos), Blocks.STONE_BRICKS) || paid(byPosition.get(crestPos), Blocks.COBBLESTONE))
			&& steps.stream().noneMatch(step -> step.item() == Blocks.CHISELED_BOOKSHELF.asItem()
				&& projection(step.pos().subtract(origin), facing) == design.hd());
		check(singlePaidCrest,
			"school " + facing + " has a paid, centered stone lintel above the entrance and no facade bookshelf tower");

		int back = -design.hd() + 1;
		List<Step> shelves = steps.stream().filter(step -> step.item() == Blocks.BOOKSHELF.asItem()).toList();
		boolean interiorShelves = shelves.size() == 2;
		for (int side : new int[] {-1, 1}) {
			BlockPos shelfPos = offset(origin, facing, side * 2, back);
			Step shelf = byPosition.get(shelfPos);
			interiorShelves &= paid(shelf, Blocks.BOOKSHELF)
				&& Math.abs(projection(shelfPos.subtract(origin), facing.getClockWise())) < design.hw()
				&& Math.abs(projection(shelfPos.subtract(origin), facing)) < design.hd();
		}
		interiorShelves &= shelves.stream().noneMatch(step -> projection(step.pos().subtract(origin), facing) == design.hd());
		check(interiorShelves,
			"school " + facing + " keeps both paid classroom shelves inside and off the exterior wall");
		return 4;
	}

	/** Protects old saved palettes that may still name Bookshelf as a wall, floor, roof, or window block. */
	private static int checkSchoolPaletteSanitization(BlockPos origin, Direction facing, long seed) {
		Palette stale = new Palette(Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF, Blocks.BOOKSHELF, Blocks.BOOKSHELF,
			Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF, Blocks.BOOKSHELF, Blocks.BOOKSHELF,
			Blocks.BOOKSHELF, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
		int variant = Builder.newPlanVariant(Blueprints.SCHOOL, seed);
		Architect.Design design = Builder.designForPlan(Blueprints.SCHOOL, variant);
		List<Step> steps = Architect.build(design, stale, origin, facing);
		Map<BlockPos, Step> byPosition = index(steps, "restored school palette " + facing);
		List<Step> ordinaryShelves = steps.stream().filter(step -> step.item() == Blocks.BOOKSHELF.asItem()).toList();
		// The facade mark is a paid stone lintel (stone bricks, or cobblestone when bricks are unobtainable);
		// a stale palette must neither leak bookshelves onto the street side nor drop the mark.
		List<Step> emblems = steps.stream().filter(step -> step.item() == Blocks.CHISELED_BOOKSHELF.asItem()).toList();
		Step lintel = byPosition.get(offset(origin, facing, 0, design.hd()).above(2));
		int back = -design.hd() + 1;
		boolean onlyInteriorShelves = ordinaryShelves.size() == 2 && emblems.isEmpty()
			&& ordinaryShelves.stream().allMatch(step -> paid(step, Blocks.BOOKSHELF)
				&& (step.pos().equals(offset(origin, facing, -2, back))
					|| step.pos().equals(offset(origin, facing, 2, back))))
			&& (paid(lintel, Blocks.STONE_BRICKS) || paid(lintel, Blocks.COBBLESTONE));
		boolean safeWindows = true;
		for (int side : new int[] {-1, 1}) {
			Step pane = byPosition.get(offset(origin, facing, side * (design.hw() - 1), design.hd()).above(1));
			safeWindows &= pane != null && pane.item() == Blocks.GLASS_PANE.asItem() && paid(pane, Blocks.GLASS_PANE);
		}
		boolean charged = steps.stream().filter(step -> !step.free() && step.linkedTo() == null)
			.allMatch(step -> step.cost() == 1 && step.item() != null)
			&& ordinaryShelves.stream().noneMatch(Step::free) && emblems.stream().noneMatch(Step::free)
			&& Supply.obtainable(Blocks.COBBLESTONE) && Supply.obtainable(Blocks.OAK_LOG)
			&& Supply.obtainable(Blocks.OAK_PLANKS) && Supply.obtainable(Blocks.OAK_STAIRS)
			&& Supply.obtainable(Blocks.OAK_SLAB) && Supply.obtainable(Blocks.GLASS_PANE)
			&& Supply.obtainable(Blocks.CHISELED_BOOKSHELF);
		check(onlyInteriorShelves && safeWindows && charged,
			"school " + facing + " sanitizes restored shelf palettes while keeping only paid interior shelves and one exterior stone lintel"
				+ " [shelves=" + onlyInteriorShelves + ", windows=" + safeWindows + ", charged=" + charged
				+ ", ordinary=" + ordinaryShelves.size() + ", emblems=" + emblems.size()
				+ ", lintel=" + (lintel == null ? "none" : String.valueOf(lintel.item())) + "]");
		return 1;
	}

	/** Exercises the newly versioned storage shape through the same saved-variant route used by Builder. */
	private static int checkStorageLoadingBay(Palette palette, BlockPos origin, Direction facing, long seed) {
		int variant = Builder.newPlanVariant(Blueprints.STORAGE, seed);
		Architect.Design design = Builder.designForPlan(Blueprints.STORAGE, variant);
		List<Step> steps = Architect.build(design, palette, origin, facing);
		Map<BlockPos, Step> byPosition = index(steps, "storage " + facing);
		List<Step> lowerDoors = steps.stream().filter(step -> step.item() == palette.door().asItem())
			.filter(step -> state(step).getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
			.toList();
		List<Step> upperDoors = steps.stream().filter(step -> step.linkedTo() != null).toList();
		java.util.Set<net.minecraft.world.level.block.state.properties.DoorHingeSide> hinges = lowerDoors.stream()
			.map(step -> state(step).getValue(DoorBlock.HINGE)).collect(java.util.stream.Collectors.toSet());
		boolean entrance = lowerDoors.size() == 2 && upperDoors.size() == 2
			&& lowerDoors.stream().allMatch(step -> !step.free() && step.cost() == 1
				&& state(step).getValue(DoorBlock.FACING) == facing.getOpposite()
				&& projection(step.pos().subtract(origin), facing) == design.hd())
			&& hinges.size() == 2
			&& upperDoors.stream().allMatch(step -> lowerDoors.stream().anyMatch(lower -> lower.pos().equals(step.linkedTo()))
				&& step.cost() == 0 && step.make().apply(Blocks.OAK_DOOR).getValue(DoorBlock.HALF)
					== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);

		boolean reinforcedOpening = true;
		for (int i : new int[]{-2, 1}) for (int y = 0; y <= 1; y++) {
			reinforcedOpening &= paid(byPosition.get(offset(origin, facing, i, design.hd()).above(y)), palette.frame());
		}
		// The two paid barrels occupy the crest row directly above the doors; the roof's
		// under-eave timber beam is the actual continuous lintel one block above them.
		for (int i : new int[]{-1, 0}) {
			reinforcedOpening &= paid(byPosition.get(offset(origin, facing, i, design.hd()).above(design.wallH())), palette.frame());
		}
		boolean paidStorageCrest = true;
		for (int i : new int[]{-1, 0}) {
			Step barrel = byPosition.get(offset(origin, facing, i, design.hd()).above(2));
			paidStorageCrest &= paid(barrel, Blocks.BARREL)
				&& state(barrel).getValue(net.minecraft.world.level.block.DirectionalBlock.FACING) == facing;
		}

		boolean shape = design.hw() == 4 && design.hd() == 2 && design.wallH() == 3
			&& design.ridgeAlongPerp() && design.halfTimber() && !design.porch() && !design.chimney()
			&& !Architect.multiStorey(design)
			&& steps.stream().filter(step -> step.item() == palette.roofStair().asItem())
				.anyMatch(step -> step.pos().getY() >= origin.getY() + design.wallH());
		boolean footprint = steps.stream().allMatch(step -> {
			BlockPos relative = step.pos().subtract(origin);
			int across = projection(relative, facing.getClockWise());
			int forward = projection(relative, facing);
			return Math.abs(across) <= design.hw() + design.overhang()
				&& Math.abs(forward) <= design.hd() + design.overhang()
				&& Math.abs(across) <= design.half() + 1 && Math.abs(forward) <= design.half() + 1;
		});
		boolean charged = steps.stream().filter(step -> !step.free() && step.linkedTo() == null)
			.allMatch(step -> step.cost() == 1 && step.item() != null)
			&& Supply.obtainable(palette.frame()) && Supply.obtainable(palette.door())
			&& Supply.obtainable(palette.roofStair()) && Supply.obtainable(palette.base())
			&& Supply.obtainable(palette.floor()) && Supply.obtainable(palette.wall());
		check(Architect.hasRoleMarkerVersion(Architect.Kind.STORAGE, variant)
			&& Builder.designForPlan(Blueprints.STORAGE, variant).equals(Architect.design(Architect.Kind.STORAGE, seed, true)),
			"new storage projects persist and reconstruct the marked Architect design");
		check(entrance && reinforcedOpening && paidStorageCrest,
			"storage " + facing + " has a charged, hinged double loading door with a palette timber frame");
		check(shape && footprint && charged,
			"storage " + facing + " keeps a low broad warehouse roof inside its reserved plot and charges each paid step");

		int legacyVariant = (int) (seed << 2);
		Architect.Design legacy = Builder.designForPlan(Blueprints.STORAGE, legacyVariant);
		List<Step> legacySteps = Architect.build(legacy, palette, origin, facing);
		List<Step> directLegacySteps = Architect.build(Architect.design(Architect.Kind.STORAGE, seed), palette, origin, facing);
		check(!Architect.hasRoleMarkerVersion(Architect.Kind.STORAGE, legacyVariant)
			&& legacy.equals(Architect.design(Architect.Kind.STORAGE, seed)) && samePlan(legacySteps, directLegacySteps),
			"legacy saved storage projects keep their original single-door shape and step ordering");
		return 4;
	}

	private static int checkStorageSizeTiers(Palette palette, BlockPos origin, long seed) {
		int checks = 0;
		for (Architect.SizeTier tier : List.of(Architect.SizeTier.COMPACT, Architect.SizeTier.STANDARD,
			Architect.SizeTier.MULTI_STOREY)) {
			long tierSeed = Architect.withSizeTier(seed, tier);
			int variant = Builder.newPlanVariant(Blueprints.STORAGE, tierSeed);
			Architect.Design design = Builder.designForPlan(Blueprints.STORAGE, variant);
			List<Step> steps = Architect.build(design, palette, origin, Direction.SOUTH);
			index(steps, "storage " + tier);
			int expectedHalfWidth = tier == Architect.SizeTier.COMPACT ? 3 : 4;
			int expectedHalfDepth = tier == Architect.SizeTier.MULTI_STOREY ? 3 : 2;
			int expectedWallHeight = tier == Architect.SizeTier.MULTI_STOREY ? 6 : 3;
			long loadingDoors = steps.stream().filter(step -> step.item() == palette.door().asItem())
				.filter(step -> state(step).getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
				.count();
			// Walls and gable blocks can share the floor item in a test or CTOV palette.
			// Probe the clear interior center, which only the real upper floor occupies.
			boolean centerLoftLanding = steps.stream().anyMatch(step -> step.item() == palette.floor().asItem()
				&& step.pos().equals(origin.above(3)));
			boolean tierShape = design.hw() == expectedHalfWidth && design.hd() == expectedHalfDepth
				&& design.wallH() == expectedWallHeight
				&& Architect.multiStorey(design) == (tier == Architect.SizeTier.MULTI_STOREY)
				&& centerLoftLanding == (tier == Architect.SizeTier.MULTI_STOREY)
				&& loadingDoors == 2 && Architect.hasRoleMarkerVersion(Architect.Kind.STORAGE, variant)
				&& design.equals(Architect.design(Architect.Kind.STORAGE, tierSeed, true));
			boolean withinLot = steps.stream().allMatch(step -> {
				BlockPos relative = step.pos().subtract(origin);
				int across = projection(relative, Direction.SOUTH.getClockWise());
				int forward = projection(relative, Direction.SOUTH);
				return Math.abs(across) <= design.hw() + design.overhang()
					&& Math.abs(forward) <= design.hd() + design.overhang()
					&& Math.abs(across) <= design.half() + 1 && Math.abs(forward) <= design.half() + 1;
			});
			boolean paidOnce = steps.stream().filter(step -> !step.free() && step.linkedTo() == null)
				.allMatch(step -> step.cost() == 1 && step.item() != null)
				&& Supply.obtainable(palette.frame()) && Supply.obtainable(palette.door())
				&& Supply.obtainable(palette.roofStair()) && Supply.obtainable(palette.base())
				&& Supply.obtainable(palette.floor()) && Supply.obtainable(palette.wall());
			check(tierShape && withinLot && paidOnce,
				"saved " + tier + " storage variant keeps the loading facade, size tier, reserved footprint, and charged materials");
			checks++;
		}
		return checks;
	}

	private static String planType(Architect.Kind kind) {
		return switch (kind) {
			case SCHOOL -> Blueprints.SCHOOL;
			case STORAGE -> Blueprints.STORAGE;
			case GRANARY -> Blueprints.GRANARY;
			case TOOLSHED -> Blueprints.TOOLSHED;
			case KITCHEN -> Blueprints.KITCHEN;
			case STATION -> Blueprints.STATION;
			default -> throw new IllegalArgumentException("No job-hut blueprint for " + kind);
		};
	}

	private static boolean samePlan(List<Step> first, List<Step> second) {
		if (first.size() != second.size()) return false;
		for (int i = 0; i < first.size(); i++) {
			Step a = first.get(i), b = second.get(i);
			if (!a.pos().equals(b.pos()) || a.item() != b.item() || a.tag() != b.tag() || a.cost() != b.cost()
				|| a.optional() != b.optional() || !java.util.Objects.equals(a.linkedTo(), b.linkedTo())
				|| a.free() != b.free()) return false;
		}
		return true;
	}

	private static int checkField(VillageStyle style, BlockPos center, Direction facing) {
		int variant = 10; // Eight wheat seeds produce the compact, fully irrigated starter field.
		List<Step> steps = Blueprints.farm(style, center, facing, variant);
		Map<BlockPos, Step> byPosition = index(steps, "field " + facing);
		int radius = 2;
		BlockPos gate = offset(center, facing, 0, radius);
		BlockPos scarecrow = offset(center, facing, radius, -radius);
		boolean cropsPaid = steps.stream().filter(step -> step.item() == net.minecraft.world.item.Items.WHEAT_SEEDS)
			.count() == 8 && steps.stream().filter(step -> step.item() == net.minecraft.world.item.Items.WHEAT_SEEDS)
			.allMatch(step -> !step.free() && step.cost() == 1 && step.optional());
		boolean gateUsable = paidGate(byPosition.get(gate), facing)
			&& passableFarmApproach(byPosition.get(gate.relative(facing.getOpposite())))
			&& !byPosition.containsKey(gate.relative(facing.getOpposite()).above());
		boolean scarecrowDistinct = freeAt(byPosition.get(scarecrow.above()), Blocks.HAY_BLOCK)
			&& freeAt(byPosition.get(scarecrow.above(2)), Blocks.CARVED_PUMPKIN)
			&& byPosition.get(scarecrow) != null && byPosition.get(scarecrow).item() == style.fence().asItem()
			&& !scarecrow.equals(gate);
		check(cropsPaid && gateUsable && scarecrowDistinct,
			"field " + facing + " has paid crops, a clear gate, and its hay-and-pumpkin scarecrow marker"
				+ " [crops=" + cropsPaid + ", gate=" + gateUsable + ", scarecrow=" + scarecrowDistinct
				+ ", gateStep=" + byPosition.get(gate) + ", scareBase=" + byPosition.get(scarecrow)
				+ ", hay=" + byPosition.get(scarecrow.above()) + ", pumpkin=" + byPosition.get(scarecrow.above(2)) + "]");
		return 1;
	}

	private static int checkPen(VillageStyle style, BlockPos center, Direction facing) {
		List<Step> steps = Blueprints.pen(style, center, facing);
		Map<BlockPos, Step> byPosition = index(steps, "livestock pen " + facing);
		BlockPos gate = offset(center, facing, 0, 4);
		BlockPos bodyPost = offset(center, facing, -1, 4);
		BlockPos headPost = offset(center, facing, -2, 4);
		boolean woolCue = paid(byPosition.get(bodyPost.above()), Blocks.WOOL.white())
			&& paid(byPosition.get(headPost.above()), Blocks.WOOL.black())
			&& Supply.obtainable(Blocks.WOOL.white()) && Supply.obtainable(Blocks.WOOL.black())
			&& paid(byPosition.get(bodyPost), style.fence()) && paid(byPosition.get(headPost), style.fence());
		boolean containedAndClear = List.of(bodyPost.above(), headPost.above()).stream().allMatch(pos ->
			Math.abs(pos.getX() - center.getX()) <= Blueprints.half(Blueprints.PEN)
				&& Math.abs(pos.getZ() - center.getZ()) <= Blueprints.half(Blueprints.PEN)
				&& pos.getY() - center.getY() < Blueprints.headroom(Blueprints.PEN))
			&& !byPosition.containsKey(gate.relative(facing.getOpposite()))
			&& !byPosition.containsKey(gate.relative(facing.getOpposite()).above());
		check(woolCue && containedAndClear,
			"livestock pen " + facing + " has a paid sheep-profile cue on existing gate-side posts without narrowing the entrance");
		check(steps.stream().filter(step -> !step.free() && step.linkedTo() == null)
			.allMatch(step -> step.cost() == 1 && step.item() != null),
			"livestock pen " + facing + " charges every new wool cue and every existing material step exactly once");
		return 2;
	}

	private static Map<BlockPos, Step> index(List<Step> steps, String label) {
		Map<BlockPos, Step> byPosition = new HashMap<>();
		for (Step step : steps) {
			if (byPosition.putIfAbsent(step.pos(), step) != null) {
				throw new IllegalStateException("Job-hut appearance: overlapping " + label + " step at " + step.pos());
			}
		}
		return byPosition;
	}

	private static boolean hasPaid(List<Step> steps, Block block) {
		return steps.stream().anyMatch(step -> paid(step, block));
	}

	private static boolean paid(Step step, Block block) {
		return step != null && !step.free() && step.linkedTo() == null && !step.optional()
			&& step.cost() == 1 && step.item() == block.asItem();
	}

	private static boolean freeAt(Step step, Block block) {
		return step != null && step.free() && step.linkedTo() == null && state(step).is(block);
	}

	private static boolean paidGate(Step step, Direction facing) {
		if (!paid(step, Blueprints.gateFor(Blocks.OAK_FENCE))) return false;
		Block gate = Block.byItem(step.item());
		BlockState state = step.make().apply(gate);
		return gate instanceof FenceGateBlock && state.getValue(FenceGateBlock.FACING) == facing;
	}

	/** Crop plants may occupy the entry cell; their collision stays low and the villager can walk through. */
	private static boolean passableFarmApproach(Step step) {
		if (step == null) return true;
		return step.optional() && step.cost() == 1
			&& (step.item() == net.minecraft.world.item.Items.WHEAT_SEEDS
				|| step.item() == net.minecraft.world.item.Items.CARROT
				|| step.item() == net.minecraft.world.item.Items.POTATO
				|| step.item() == net.minecraft.world.item.Items.BEETROOT_SEEDS);
	}

	private static BlockState state(Step step) {
		Block base = step.item() == null ? Blocks.AIR : Block.byItem(step.item());
		return step.make().apply(base);
	}

	private static int projection(BlockPos relative, Direction direction) {
		return relative.getX() * direction.getStepX() + relative.getZ() * direction.getStepZ();
	}

	private static BlockPos offset(BlockPos center, Direction facing, int across, int forward) {
		return center.relative(facing.getClockWise(), across).relative(facing, forward);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Job-hut appearance: " + message);
	}
}
