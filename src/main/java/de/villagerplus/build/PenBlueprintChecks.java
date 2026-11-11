package de.villagerplus.build;

import de.villagerplus.village.VillageStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;

/** Focused geometry and material checks for the animal-pen blueprint. */
public final class PenBlueprintChecks {
	private PenBlueprintChecks() { }

	public static int run() {
		VillageStyle style = new VillageStyle(Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS,
			Blocks.OAK_STAIRS, Blocks.OAK_DOOR, Blocks.SPRUCE_FENCE);
		int checks = 0;
		for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
			checks += checkLayout(style, BlockPos.ZERO, direction);
		}
		return checks;
	}

	private static int checkLayout(VillageStyle style, BlockPos center, Direction direction) {
		List<Step> steps = Blueprints.pen(style, center, direction);
		Map<BlockPos, Step> byPosition = new HashMap<>();
		boolean unique = true;
		for (Step step : steps) {
			if (byPosition.putIfAbsent(step.pos(), step) != null) unique = false;
		}
		check(unique, "the rotated pen has no overlapping steps for " + direction);

		Block expectedGate = Blueprints.gateFor(style.fence());
		BlockPos gatePos = center.relative(direction, 4);
		BlockPos gateApproach = gatePos.relative(direction.getOpposite());
		int fenceCount = 0;
		int gateCount = 0;
		boolean ringComplete = unique;
		for (int across = -4; across <= 4; across++) {
			for (int forward = -4; forward <= 4; forward++) {
				if (Math.abs(across) != 4 && Math.abs(forward) != 4) continue;
				BlockPos pos = offset(center, direction, across, forward);
				Step step = byPosition.get(pos);
				if (!paid(step, style.fence())) {
					if (pos.equals(gatePos) && paid(step, expectedGate)) {
						gateCount++;
						Block gate = Block.byItem(step.item());
						if (gate instanceof FenceGateBlock) {
							var state = step.make().apply(gate);
							ringComplete &= state.getValue(FenceGateBlock.FACING) == direction
								&& state.hasProperty(FenceGateBlock.OPEN);
						} else {
							ringComplete = false;
						}
					} else {
						ringComplete = false;
					}
				} else if (pos.equals(gatePos)) {
					fenceCount++;
					ringComplete = false;
				} else {
					fenceCount++;
				}
			}
		}
		long gateSteps = steps.stream().filter(step -> !step.free() && step.item() != null)
			.filter(step -> Block.byItem(step.item()) instanceof FenceGateBlock).count();
		boolean gateApproachClear = !byPosition.containsKey(gateApproach) && !byPosition.containsKey(gateApproach.above());
		check(ringComplete && fenceCount == 31 && gateCount == 1 && gateSteps == 1 && gateApproachClear,
			"the 9-by-9 perimeter retains 31 paid village fences and one usable, correctly rotated gate");

		boolean shelter = true;
		for (int across = -2; across <= 0; across++) {
			for (int forward = -3; forward <= -1; forward++) {
				shelter &= paid(byPosition.get(offset(center, direction, across, forward).above(2)), style.roof());
			}
		}
		for (int across : new int[] {-2, 0}) {
			for (int forward : new int[] {-3, -1}) {
				BlockPos post = offset(center, direction, across, forward);
				shelter &= paid(byPosition.get(post), style.wall())
					&& paid(byPosition.get(post.above()), style.wall());
			}
		}
		BlockPos hay = offset(center, direction, -2, -2);
		shelter &= paid(byPosition.get(hay), Blocks.HAY_BLOCK)
			&& countPaid(steps, style.roof()) == 9 && countPaid(steps, style.wall()) == 14
			&& countPaid(steps, Blocks.HAY_BLOCK) == 1;
		for (int forward = -3; forward <= -1; forward++) {
			BlockPos passage = offset(center, direction, -1, forward);
			shelter &= !byPosition.containsKey(passage) && !byPosition.containsKey(passage.above());
		}
		check(shelter, "a paid palette-matched open shelter covers a clear animal passage and a paid hay place");

		boolean trough = true;
		for (int forward = 1; forward <= 2; forward++) {
			trough &= paid(byPosition.get(offset(center, direction, 0, forward).below()), style.wall())
				&& paid(byPosition.get(offset(center, direction, 2, forward).below()), style.wall());
		}
		trough &= paid(byPosition.get(offset(center, direction, 1, 0).below()), style.wall())
			&& paid(byPosition.get(offset(center, direction, 1, 3).below()), style.wall())
			&& free(byPosition.get(offset(center, direction, 1, 1).below()), Blocks.WATER)
			&& free(byPosition.get(offset(center, direction, 1, 2).below()), Blocks.WATER)
			&& steps.stream().filter(step -> step.free() && step.make().apply(Blocks.AIR).is(Blocks.WATER)).count() == 2;
		check(trough, "the rotated water trough keeps two free water cells inside a paid palette-matched rim");

		int openAnimalCells = 0;
		for (int across = -3; across <= 3; across++) {
			for (int forward = -3; forward <= 3; forward++) {
				BlockPos feet = offset(center, direction, across, forward);
				if (!byPosition.containsKey(feet) && !byPosition.containsKey(feet.above())) openAnimalCells++;
			}
		}
		check(openAnimalCells >= 40, "the shelter and trough leave at least 40 clear animal cells inside the pen");
		return 5;
	}

	private static BlockPos offset(BlockPos center, Direction facing, int across, int forward) {
		return center.relative(facing.getClockWise(), across).relative(facing, forward);
	}

	private static boolean paid(Step step, Block block) {
		return step != null && !step.free() && step.linkedTo() == null && !step.optional()
			&& step.cost() == 1 && step.item() != null && step.item().equals(block.asItem());
	}

	private static boolean free(Step step, Block block) {
		return step != null && step.free() && step.make().apply(Blocks.AIR).is(block);
	}

	private static long countPaid(List<Step> steps, Block block) {
		return steps.stream().filter(step -> paid(step, block)).count();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Pen blueprint: " + message);
	}
}
