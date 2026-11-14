package de.villagerplus.build;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Focused invariants for role-facade versioning and saved-plan reconstruction. */
final class ArchitectRoleMarkerChecks {
	private record StepShape(BlockPos pos, Item item, TagKey<Item> tag, int cost, boolean optional, BlockPos linkedTo) { }

	private ArchitectRoleMarkerChecks() { }

	static int run() {
		int checks = 0;
		Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.COBBLESTONE,
			Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.OAK_PLANKS,
			Blocks.OAK_DOOR, Blocks.OAK_FENCE);
		BlockPos origin = new BlockPos(100, 64, 100);
		Direction facing = Direction.SOUTH;
		long seed = 0x234567L;

		List<Architect.Kind> markedKinds = List.of(Architect.Kind.SCHOOL, Architect.Kind.STORAGE, Architect.Kind.GRANARY,
			Architect.Kind.TOOLSHED, Architect.Kind.KITCHEN, Architect.Kind.STATION);
		Set<String> coreRoleRoofProfiles = new HashSet<>();
		Set<String> roleMarkerIdentities = new HashSet<>();
		for (Architect.Kind kind : markedKinds) {
			int legacyVariant = (int) (seed << 2);
			Architect.Design legacy = Architect.design(kind, seed);
			Architect.Design restoredLegacy = Architect.designForPlan(kind, legacyVariant);
			List<StepShape> legacySteps = shapes(Architect.build(legacy, palette, origin, facing));
			List<StepShape> restoredLegacySteps = shapes(Architect.build(restoredLegacy, palette, origin, facing));
			check(legacy.equals(restoredLegacy) && legacySteps.equals(restoredLegacySteps),
				"unmarked saved " + kind + " plan reconstructs its legacy design and exact step order");
			checks++;

			int markedVariant = Architect.withRoleMarkerVersion(kind, legacyVariant);
			Architect.Design marked = Architect.designForPlan(kind, markedVariant);
			check(Architect.hasRoleMarkerVersion(kind, markedVariant)
				&& marked.equals(Architect.design(kind, seed, true)),
				"marked " + kind + " plan saves and reconstructs the same facade version");
			checks++;

			List<Step> markedSteps = Architect.build(marked, palette, origin, facing);
			roleMarkerIdentities.add(markerIdentity(kind, markedSteps));
			check(hasPitchedRoleRoof(markedSteps, marked, palette, origin, facing),
				"new " + kind + " plan has a real paid roof ridge and sloped roof blocks");
			checks++;
			check(hasPaidRooflineBeam(markedSteps, marked, palette, origin, facing),
				"new " + kind + " plan has a paid palette beam below the roofline");
			checks++;
			check(hasPaidEntry(markedSteps, marked, palette, origin, facing),
				"new " + kind + " plan keeps its paid doorway and covered entry where configured");
			checks++;
			check(allPaidStepsCharged(markedSteps), "new " + kind + " plan charges every non-linked structural or facade block");
			checks++;
			if (kind == Architect.Kind.SCHOOL || kind == Architect.Kind.GRANARY
				|| kind == Architect.Kind.TOOLSHED || kind == Architect.Kind.KITCHEN) {
				coreRoleRoofProfiles.add(roofSignature(markedSteps, marked, palette, origin, facing));
			}
			int chimneyTop = marked.wallH() + (marked.ridgeAlongPerp() ? marked.hd() : marked.hw()) + marked.overhang() + 1;
			BlockPos campfirePos = origin.relative(facing.getClockWise(), marked.hw() + 1)
				.relative(facing, -marked.hd() + 1).above(chimneyTop);
			boolean cuePresent = switch (kind) {
				case SCHOOL -> hasPaidItem(markedSteps, schoolMarkerStone());
				case STORAGE -> hasPaidItem(markedSteps, Blocks.BARREL);
				case GRANARY -> hasPaidItem(markedSteps, Blocks.HAY_BLOCK);
				case TOOLSHED -> hasPaidItem(markedSteps, Blocks.ANVIL) && hasPaidItem(markedSteps, Blocks.CRAFTING_TABLE);
				case KITCHEN -> marked.chimney() && hasPaidItem(markedSteps, Blocks.SMOKER)
					&& markedSteps.stream().anyMatch(step -> step.pos().equals(campfirePos) && step.free()
						&& step.make().apply(Blocks.AIR).is(Blocks.CAMPFIRE));
				case STATION -> hasPaidItem(markedSteps, Blocks.RAIL) && hasPaidItem(markedSteps, Blocks.POWERED_RAIL);
				default -> false;
			};
			check(cuePresent, "new " + kind + " plan includes its paid, visible role cue");
			checks++;
			if (kind == Architect.Kind.STORAGE) {
				check(hasTwoWideStorageEntry(markedSteps, marked, palette, origin, facing)
					&& hasStorageBarrelCrest(markedSteps, marked, origin, facing),
					"new storage facade has two paid outward-facing barrels above the unobstructed paired loading doors");
				checks++;
			}
			if (kind == Architect.Kind.SCHOOL) {
				int doorI = Math.max(-marked.hw() + 1, Math.min(marked.hw() - 1, marked.doorOffset()));
				BlockPos lowerDoor = origin.relative(facing.getClockWise(), doorI).relative(facing, marked.hd());
				BlockPos badge = lowerDoor.above(Math.min(2, marked.wallH() - 1));
				boolean aboveEntrance = hasPaidBlock(markedSteps, badge, schoolMarkerStone());
				boolean lintelHasTimberBrackets = hasPaidBlock(markedSteps, badge.relative(facing.getClockWise()), palette.frame())
					&& hasPaidBlock(markedSteps, badge.relative(facing.getCounterClockWise()), palette.frame());
				boolean doorwayClear = markedSteps.stream().anyMatch(step -> step.pos().equals(lowerDoor)
					&& step.tag() != null && step.linkedTo() == null)
					&& markedSteps.stream().anyMatch(step -> step.pos().equals(lowerDoor.above())
						&& step.linkedTo() != null && step.linkedTo().equals(lowerDoor));
				check(aboveEntrance && lintelHasTimberBrackets && doorwayClear,
					"paid stone school lintel stays between timber brackets above the clear two-block entrance");
				checks++;
				long bookshelfFacadeBlocks = markedSteps.stream().filter(step -> (step.item() == Blocks.BOOKSHELF.asItem()
					|| step.item() == Blocks.CHISELED_BOOKSHELF.asItem())
					&& (Math.abs(localPos(step.pos(), origin, facing).i()) == marked.hw()
						|| Math.abs(localPos(step.pos(), origin, facing).j()) == marked.hd())).count();
				boolean shelteredFootprintFits = marked.half() == 5 && markedSteps.stream().allMatch(step -> {
					LocalPos local = localPos(step.pos(), origin, facing);
					return Math.abs(local.i()) <= marked.half() && Math.abs(local.j()) <= marked.half();
				});
				check(marked.wallH() == 4 && !marked.ridgeAlongPerp() && marked.porch() && !marked.chimney()
					&& !Architect.multiStorey(marked) && bookshelfFacadeBlocks == 0 && shelteredFootprintFits,
					"new school has a front gable, sheltered entrance and keeps all bookshelves off the exterior walls");
				checks++;
			}
			if (kind == Architect.Kind.TOOLSHED) {
				check(marked.hw() == 3 && marked.hd() == 2 && marked.wallH() == 3
					&& marked.ridgeAlongPerp() && !marked.porch() && !marked.chimney()
					&& !Architect.multiStorey(marked),
					"new tool sheds keep a compact low eaves-first workshop silhouette distinct from the school gable");
				checks++;
			}
			if (kind == Architect.Kind.KITCHEN) {
				int kitchenChimneyTop = marked.wallH() + marked.hd() + marked.overhang() + 1;
				BlockPos chimneyFire = origin.relative(facing.getClockWise(), marked.hw() + 1)
					.relative(facing, -marked.hd() + 1).above(kitchenChimneyTop);
				check(marked.hw() == 4 && marked.hd() == 3 && marked.wallH() == 4
					&& marked.ridgeAlongPerp() && marked.porch() && marked.chimney()
					&& !Architect.multiStorey(marked) && marked.half() > marked.hw()
					&& marked.headroom() > chimneyFire.getY() - origin.getY(),
					"new kitchen keeps a broad eaves-first cooking-house shape and reserves its chimney and serving porch");
				checks++;
			}
			if (kind == Architect.Kind.GRANARY) {
				check(marked.hw() == 4 && marked.hd() == 3 && marked.wallH() == 3
					&& !marked.ridgeAlongPerp() && !marked.porch() && !marked.chimney()
					&& !Architect.multiStorey(marked)
					&& hasPaidItem(markedSteps, Blocks.HAY_BLOCK),
					"new granary keeps a low front-gabled storage silhouette with a paid hay crest");
				checks++;
			}
		}
		check(coreRoleRoofProfiles.size() == 4,
			"school, granary, tool shed and kitchen have four distinct roof geometries in their actual paid steps");
		checks++;
		check(roleMarkerIdentities.size() == markedKinds.size(),
			"school, storage, granary, tool shed, kitchen and station each have a distinct outside marker identity");
		checks++;

		for (Architect.Kind kind : markedKinds) {
			int markedVariant = Architect.withRoleMarkerVersion(kind, (int) (seed << 2));
			Architect.Design marked = Architect.designForPlan(kind, markedVariant);
			List<Step> referenceSteps = Architect.build(marked, palette, origin, Direction.SOUTH);
			for (Direction rotated : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
				List<Step> steps = Architect.build(marked, palette, origin, rotated);
				boolean geometryAndMaterialsRotate = rotatedGeometryMatches(referenceSteps, steps, origin, rotated)
					&& hasPitchedRoleRoof(steps, marked, palette, origin, rotated)
					&& hasPaidRooflineBeam(steps, marked, palette, origin, rotated)
					&& hasPaidEntry(steps, marked, palette, origin, rotated)
					&& allPaidStepsCharged(steps);
				check(geometryAndMaterialsRotate,
					rotated + " " + kind + " keeps its complete paid footprint, pitched roof and roofline beam");
				checks++;
				List<Step> cues = steps.stream().filter(step -> roleCue(kind, step.item())).toList();
				check(cues.size() == expectedCueCount(kind) && cues.stream().allMatch(step -> step.cost() > 0),
					rotated + " " + kind + " facade keeps its complete, paid role mark");
				checks++;
				check(markerIdentity(kind, steps).equals(markerIdentity(kind, referenceSteps)),
					rotated + " " + kind + " keeps the same role-specific outside mark after rotation");
				checks++;

				int doorI = Math.max(-marked.hw() + 1, Math.min(marked.hw() - 1, marked.doorOffset()));
				boolean clearFacade = cues.stream().allMatch(step -> {
					LocalPos local = localPos(step.pos(), origin, rotated);
					boolean aboveDoorBadge = local.y() == 2 && ((kind == Architect.Kind.SCHOOL || kind == Architect.Kind.GRANARY)
						|| kind == Architect.Kind.STORAGE && (local.i() == doorI - 1 || local.i() == doorI));
					boolean sideBadge = kind != Architect.Kind.SCHOOL && kind != Architect.Kind.GRANARY && kind != Architect.Kind.STORAGE
						&& local.y() == 1 && Math.abs(local.i() - doorI) > 1 && !windowColumn(marked, local.i());
					return local.j() == marked.hd() && (aboveDoorBadge || sideBadge);
				});
				check(clearFacade, rotated + " " + kind + " role mark stays on a solid facade cell outside the entry and windows");
				checks++;
				if (kind == Architect.Kind.TOOLSHED || kind == Architect.Kind.KITCHEN || kind == Architect.Kind.STATION) {
					Block trim = palette.frame() != null ? palette.frame() : palette.wall();
					BlockPos entranceLintel = worldPos(origin, rotated,
						new LocalPos(doorI, marked.hd(), Math.min(2, marked.wallH() - 1)));
					check(hasPaidBlock(steps, entranceLintel, trim), rotated + " " + kind
						+ " uses a paid palette lintel above the entrance to connect the visible work marker to the doorway");
					checks++;
				}
				if (kind == Architect.Kind.SCHOOL) {
					LocalPos lintel = localPos(worldPos(origin, rotated,
						new LocalPos(doorI, marked.hd(), Math.min(2, marked.wallH() - 1))), origin, rotated);
					BlockPos lintelPos = worldPos(origin, rotated, lintel);
					boolean paidStoneLintel = cues.size() == 1 && cues.getFirst().item() == schoolMarkerStone().asItem()
						&& cues.getFirst().cost() == 1 && cues.getFirst().pos().equals(lintelPos);
					boolean timberBrackets = hasPaidBlock(steps, worldPos(origin, rotated,
						new LocalPos(doorI - 1, marked.hd(), lintel.y())), palette.frame())
						&& hasPaidBlock(steps, worldPos(origin, rotated,
						new LocalPos(doorI + 1, marked.hd(), lintel.y())), palette.frame());
					boolean noBookshelfWall = steps.stream()
						.filter(step -> step.item() == Blocks.BOOKSHELF.asItem() || step.item() == Blocks.CHISELED_BOOKSHELF.asItem())
						.noneMatch(step -> {
							LocalPos local = localPos(step.pos(), origin, rotated);
						return Math.abs(local.i()) == marked.hw() || Math.abs(local.j()) == marked.hd();
						});
					check(paidStoneLintel && timberBrackets && noBookshelfWall,
						rotated + " school keeps its paid stone lintel between timber brackets and classroom shelves inside");
					checks++;
				}

				if (kind == Architect.Kind.STATION) {
					net.minecraft.world.level.block.state.properties.RailShape expected = rotated.getClockWise().getAxis() == Direction.Axis.X
						? net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST
						: net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH;
					boolean rotatedRails = cues.stream().allMatch(step -> {
						var state = step.make().apply(Block.byItem(step.item()));
						return step.item() == Blocks.RAIL.asItem()
							? state.getValue(net.minecraft.world.level.block.RailBlock.SHAPE) == expected
							: state.getValue(net.minecraft.world.level.block.PoweredRailBlock.SHAPE) == expected;
					});
					check(rotatedRails, rotated + " station rails rotate along the facade");
					checks++;
				}
				if (kind == Architect.Kind.STORAGE) {
					check(hasTwoWideStorageEntry(steps, marked, palette, origin, rotated)
						&& hasStorageBarrelCrest(steps, marked, origin, rotated),
						rotated + " storage keeps the paired doorway clear and barrels facing the road");
					checks++;
				}
			}
		}

		for (Architect.SizeTier tier : List.of(Architect.SizeTier.COMPACT, Architect.SizeTier.STANDARD, Architect.SizeTier.MULTI_STOREY)) {
			int tierSeed = (int) (Architect.withSizeTier(seed, tier) << 2);
			for (Architect.Kind kind : markedKinds) {
				int versioned = Architect.withRoleMarkerVersion(kind, tierSeed);
				Architect.Design saved = Architect.designForPlan(kind, versioned);
				check(saved.equals(Architect.design(kind, Architect.withSizeTier(seed, tier), true))
					&& Architect.hasRoleMarkerVersion(kind, versioned)
					&& (kind == Architect.Kind.STATION || kind == Architect.Kind.STORAGE || !Architect.multiStorey(saved)),
					"saved " + tier + " " + kind + " plan reconstructs the new role silhouette without changing its tier marker");
				checks++;
			}
		}

		int houseMirrorVariant = ((int) (seed << 2)) | 1;
		check(Architect.withRoleMarkerVersion(Architect.Kind.HOUSE, houseMirrorVariant) == houseMirrorVariant
			&& !Architect.hasRoleMarkerVersion(Architect.Kind.HOUSE, houseMirrorVariant),
			"bit zero remains the house-template mirror flag");
		checks++;
		int storageLegacyVariant = (int) (seed << 2);
		int storageMarkedVariant = Architect.withRoleMarkerVersion(Architect.Kind.STORAGE, storageLegacyVariant);
		check(storageMarkedVariant == (storageLegacyVariant | 1)
			&& !Architect.hasRoleMarkerVersion(Architect.Kind.STORAGE, storageLegacyVariant)
			&& Architect.hasRoleMarkerVersion(Architect.Kind.STORAGE, storageMarkedVariant)
			&& Architect.designForPlan(Architect.Kind.STORAGE, storageLegacyVariant).equals(Architect.design(Architect.Kind.STORAGE, seed))
			&& Architect.designForPlan(Architect.Kind.STORAGE, storageMarkedVariant).equals(Architect.design(Architect.Kind.STORAGE, seed, true)),
			"new storage plans use role markers while unmarked saved plans retain their legacy design");
		return checks + 1;
	}

	private record LocalPos(int i, int j, int y) { }

	private static boolean hasPitchedRoleRoof(List<Step> steps, Architect.Design design, Palette palette,
		BlockPos origin, Direction facing) {
		if (!(palette.roofStair() instanceof StairBlock) || palette.roofFull() == null) return false;
		int slopeHalf = design.ridgeAlongPerp() ? design.hd() : design.hw();
		int peakY = design.wallH() + slopeHalf + design.overhang();
		int peakJ = design.ridgeAlongPerp() ? 0 : design.hd() + design.overhang();
		BlockPos peak = worldPos(origin, facing, new LocalPos(0, peakJ, peakY));
		boolean paidPeak = steps.stream().anyMatch(step -> step.pos().equals(peak)
			&& step.item() == palette.roofFull().asItem() && step.cost() == 1);
		boolean paidSlope = steps.stream().anyMatch(step -> step.item() == palette.roofStair().asItem()
			&& step.cost() == 1 && localPos(step.pos(), origin, facing).y() >= design.wallH());
		return paidPeak && paidSlope;
	}

	private static boolean hasPaidRooflineBeam(List<Step> steps, Architect.Design design, Palette palette,
		BlockPos origin, Direction facing) {
		return palette.frame() != null && steps.stream().anyMatch(step -> step.item() == palette.frame().asItem()
			&& step.cost() == 1 && localPos(step.pos(), origin, facing).j() == design.hd()
			&& localPos(step.pos(), origin, facing).y() == design.wallH() - 1);
	}

	private static boolean hasPaidEntry(List<Step> steps, Architect.Design design, Palette palette,
		BlockPos origin, Direction facing) {
		int doorI = Math.max(-design.hw() + 1, Math.min(design.hw() - 1, design.doorOffset()));
		BlockPos lowerDoor = worldPos(origin, facing, new LocalPos(doorI, design.hd(), 0));
		boolean doorway = steps.stream().anyMatch(step -> step.pos().equals(lowerDoor)
			&& step.item() == palette.door().asItem() && step.tag() != null && step.cost() == 1)
			&& steps.stream().anyMatch(step -> step.pos().equals(lowerDoor.above())
				&& lowerDoor.equals(step.linkedTo()));
		if (!doorway || !design.porch()) return doorway;

		for (int side : new int[] {-1, 1}) {
			BlockPos post = worldPos(origin, facing, new LocalPos(doorI + side, design.hd() + 2, 0));
			if (!hasPaidBlock(steps, post, palette.fence()) || !hasPaidBlock(steps, post.above(), palette.fence())) return false;
		}
		Block roofTrim = palette.roofSlab() instanceof net.minecraft.world.level.block.SlabBlock
			? palette.roofSlab() : palette.roofStair() instanceof StairBlock ? palette.roofStair() : palette.roofFull();
		BlockPos canopy = worldPos(origin, facing, new LocalPos(doorI, design.hd() + 1, 2));
		return hasPaidBlock(steps, canopy, roofTrim);
	}

	private static boolean hasPaidBlock(List<Step> steps, BlockPos pos, Block block) {
		return block != null && steps.stream().anyMatch(step -> step.pos().equals(pos)
			&& step.item() == block.asItem() && step.cost() == 1);
	}

	private static boolean allPaidStepsCharged(List<Step> steps) {
		return steps.stream().filter(step -> !step.free() && step.linkedTo() == null)
			.allMatch(step -> step.cost() == 1 && (step.item() != null || step.tag() != null));
	}

	private static String roofSignature(List<Step> steps, Architect.Design design, Palette palette,
		BlockPos origin, Direction facing) {
		return steps.stream().filter(step -> !step.free() && step.linkedTo() == null && step.cost() == 1
			&& step.pos().getY() >= origin.getY() + design.wallH()
			&& (step.item() == palette.roofFull().asItem() || step.item() == palette.roofStair().asItem()
				|| (palette.roofSlab() != null && step.item() == palette.roofSlab().asItem())))
			.map(step -> {
				LocalPos local = localPos(step.pos(), origin, facing);
				return local.i() + "," + local.j() + "," + local.y() + ":" + step.item();
			}).sorted().reduce((left, right) -> left + ";" + right).orElse("");
	}

	private static boolean rotatedGeometryMatches(List<Step> reference, List<Step> rotated, BlockPos origin,
		Direction facing) {
		Map<BlockPos, Step> targets = new HashMap<>();
		for (Step step : rotated) if (targets.put(step.pos(), step) != null) return false;
		if (targets.size() != reference.size()) return false;
		for (Step source : reference) {
			LocalPos local = localPos(source.pos(), origin, Direction.SOUTH);
			Step target = targets.get(worldPos(origin, facing, local));
			if (target == null || source.cost() != target.cost() || source.free() != target.free()
				|| source.optional() != target.optional() || !java.util.Objects.equals(source.item(), target.item())
				|| !java.util.Objects.equals(source.tag(), target.tag())) return false;
			BlockPos expectedLink = source.linkedTo() == null ? null
				: worldPos(origin, facing, localPos(source.linkedTo(), origin, Direction.SOUTH));
			if (!java.util.Objects.equals(expectedLink, target.linkedTo())) return false;
			if (source.item() != null && !directionalStateRotates(source, target, facing)) return false;
		}
		return true;
	}

	private static boolean directionalStateRotates(Step source, Step target, Direction facing) {
		if (source.item() == null || target.item() == null) return false;
		BlockState sourceState = source.make().apply(Block.byItem(source.item()));
		BlockState targetState = target.make().apply(Block.byItem(target.item()));
		if (sourceState.hasProperty(StairBlock.FACING) && targetState.hasProperty(StairBlock.FACING)
			&& targetState.getValue(StairBlock.FACING) != rotateDirectionFromSouth(
				sourceState.getValue(StairBlock.FACING), facing)) return false;
		if (sourceState.hasProperty(HorizontalDirectionalBlock.FACING)
			&& targetState.hasProperty(HorizontalDirectionalBlock.FACING)
			&& targetState.getValue(HorizontalDirectionalBlock.FACING) != rotateDirectionFromSouth(
				sourceState.getValue(HorizontalDirectionalBlock.FACING), facing)) return false;
		if (sourceState.hasProperty(DirectionalBlock.FACING) && targetState.hasProperty(DirectionalBlock.FACING)
			&& targetState.getValue(DirectionalBlock.FACING) != rotateDirectionFromSouth(
				sourceState.getValue(DirectionalBlock.FACING), facing)) return false;
		if (sourceState.hasProperty(RotatedPillarBlock.AXIS) && targetState.hasProperty(RotatedPillarBlock.AXIS)) {
			Direction.Axis sourceAxis = sourceState.getValue(RotatedPillarBlock.AXIS);
			Direction.Axis expectedAxis = quarterTurns(facing) % 2 == 0 ? sourceAxis
				: sourceAxis == Direction.Axis.X ? Direction.Axis.Z
					: sourceAxis == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Y;
			if (targetState.getValue(RotatedPillarBlock.AXIS) != expectedAxis) return false;
		}
		return true;
	}

	private static BlockPos worldPos(BlockPos origin, Direction facing, LocalPos local) {
		return origin.relative(facing.getClockWise(), local.i()).relative(facing, local.j()).above(local.y());
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

	private static LocalPos localPos(BlockPos pos, BlockPos origin, Direction facing) {
		int dx = pos.getX() - origin.getX();
		int dz = pos.getZ() - origin.getZ();
		Direction across = facing.getClockWise();
		return new LocalPos(dx * across.getStepX() + dz * across.getStepZ(),
			dx * facing.getStepX() + dz * facing.getStepZ(), pos.getY() - origin.getY());
	}

	private static boolean windowColumn(Architect.Design design, int i) {
		int a = Math.abs(i);
		return design.halfTimber() ? a % 3 == 1 : design.windowSpacing() == 2 ? a % 2 == 1 : a % 3 == 0;
	}

	private static int expectedCueCount(Architect.Kind kind) {
		return kind == Architect.Kind.TOOLSHED || kind == Architect.Kind.STATION || kind == Architect.Kind.STORAGE ? 2 : 1;
	}

	private static String markerIdentity(Architect.Kind kind, List<Step> steps) {
		return steps.stream().filter(step -> roleCue(kind, step.item()))
			.map(step -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(step.item()).toString())
			.sorted().toList().toString();
	}

	private static boolean roleCue(Architect.Kind kind, Item item) {
		return switch (kind) {
			case SCHOOL -> item == schoolMarkerStone().asItem();
			case STORAGE -> item == Blocks.BARREL.asItem();
			case GRANARY -> item == Blocks.HAY_BLOCK.asItem();
			case TOOLSHED -> item == Blocks.ANVIL.asItem() || item == Blocks.CRAFTING_TABLE.asItem();
			case KITCHEN -> item == Blocks.SMOKER.asItem();
			case STATION -> item == Blocks.RAIL.asItem() || item == Blocks.POWERED_RAIL.asItem();
			default -> false;
		};
	}

	private static boolean hasTwoWideStorageEntry(List<Step> steps, Architect.Design design, Palette palette,
		BlockPos origin, Direction facing) {
		int doorI = Math.max(-design.hw() + 1, Math.min(design.hw() - 1, design.doorOffset()));
		for (int index = 0; index < 2; index++) {
			BlockPos lower = worldPos(origin, facing, new LocalPos(doorI - 1 + index, design.hd(), 0));
			BlockPos upper = lower.above();
			boolean lowerDoor = steps.stream().anyMatch(step -> step.pos().equals(lower)
				&& step.item() == palette.door().asItem() && step.tag() != null && step.cost() == 1
				&& !step.free() && step.linkedTo() == null);
			boolean upperDoor = steps.stream().anyMatch(step -> step.pos().equals(upper) && lower.equals(step.linkedTo()));
			if (!lowerDoor || !upperDoor) return false;
		}
		return true;
	}

	private static boolean hasStorageBarrelCrest(List<Step> steps, Architect.Design design, BlockPos origin, Direction facing) {
		int doorI = Math.max(-design.hw() + 1, Math.min(design.hw() - 1, design.doorOffset()));
		int y = Math.min(2, design.wallH() - 1);
		for (int i : new int[] {doorI - 1, doorI}) {
			BlockPos position = worldPos(origin, facing, new LocalPos(i, design.hd(), y));
			boolean paidForwardBarrel = steps.stream().anyMatch(step -> step.pos().equals(position)
				&& step.item() == Blocks.BARREL.asItem() && step.cost() == 1 && !step.free()
				&& step.make().apply(Blocks.BARREL).getValue(DirectionalBlock.FACING) == facing);
			if (!paidForwardBarrel) return false;
		}
		return true;
	}

	private static List<StepShape> shapes(List<Step> steps) {
		return steps.stream().map(step -> new StepShape(step.pos(), step.item(), step.tag(), step.cost(), step.optional(), step.linkedTo())).toList();
	}

	private static boolean hasPaidItem(List<Step> steps, net.minecraft.world.level.block.Block block) {
		return steps.stream().anyMatch(step -> step.item() == block.asItem() && step.cost() > 0);
	}

	private static Block schoolMarkerStone() {
		return Supply.obtainable(Blocks.STONE_BRICKS) ? Blocks.STONE_BRICKS : Blocks.COBBLESTONE;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("ARCHITECT ROLE MARKER CHECK FAILED: " + message);
	}
}
