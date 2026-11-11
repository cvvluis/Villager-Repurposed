package de.villagerplus.build;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import de.villagerplus.village.VillageStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Focused selection checks for a visible, fully seeded village starter farm. */
public final class FarmPlanSizingChecks {
	private FarmPlanSizingChecks() { }

	public static int run() {
		int checks = 0;
		SimpleContainer stock = new SimpleContainer(9);
		check(FarmPlanSizing.chooseVariant(0, stock) == -1,
			"the first farm does not queue without a complete seed payment"); checks++;

		stock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		check(FarmPlanSizing.chooseVariant(0, stock) == 10
			&& cropCount(10, Items.WHEAT_SEEDS) == 8,
			"eight seeds still provide a safe 3x3 starter fallback"); checks++;

		stock.setItem(0, new ItemStack(Items.CARROT, 24));
		check(FarmPlanSizing.chooseVariant(0, stock) == -1,
			"the first real field stays wheat even when another crop has enough stock"); checks++;
		stock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));

		stock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 24));
		check(FarmPlanSizing.chooseVariant(0, stock) == 20
			&& cropCount(20, Items.WHEAT_SEEDS) == 24,
			"24 seeds provide a visibly larger, fully funded 5x5 starter field"); checks++;

		stock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 48));
		check(FarmPlanSizing.chooseVariant(0, stock) == 20,
			"the starter field grows to 5x5 without reserving the surplus seeds"); checks++;

		stock.setItem(1, ItemStack.EMPTY);
		stock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 23));
		check(FarmPlanSizing.chooseVariant(0, stock) == 10,
			"the starter field falls back to 3x3 below the 24-seed 5x5 threshold while retaining its eight-seed minimum"); checks++;

		stock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
		stock.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 53));
		check(FarmPlanSizing.chooseVariant(4, stock) == 50
			&& cropCount(50, Items.WHEAT_SEEDS) == 117,
			"population-scaled later fields retain the existing 11x11 maximum"); checks++;

		stock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 24));
		stock.setItem(1, new ItemStack(Items.CARROT, 48));
		check(FarmPlanSizing.chooseVariant(1, stock) == 30 + 1,
			"later crop selection chooses the largest fully payable seed type"); checks++;

		VillageStyle spruce = new VillageStyle(Blocks.COBBLESTONE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS,
			Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE);
		for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
			List<Step> steps = Blueprints.create(Blueprints.FARM, spruce, BlockPos.ZERO, direction, 10);
			checks += checkFarmBlueprint(steps, BlockPos.ZERO, direction, 10, spruce.fence());
		}
		List<Step> largeFarm = Blueprints.create(Blueprints.FARM, spruce, BlockPos.ZERO, Direction.WEST, 50);
		checks += checkFarmBlueprint(largeFarm, BlockPos.ZERO, Direction.WEST, 50, spruce.fence());
		return checks;
	}

	private static int checkFarmBlueprint(List<Step> steps, BlockPos center, Direction direction, int variant, Block fence) {
		int half = variant / 10;
		int radius = half + 1;
		Block gate = Blueprints.gateFor(fence);
		BlockPos gatePos = farmOffset(center, direction, 0, radius);
		Set<BlockPos> positions = new HashSet<>();
		boolean unique = steps.stream().allMatch(step -> positions.add(step.pos()));
		boolean perimeter = unique;
		int fenceCount = 0;
		int gateCount = 0;
		for (int across = -radius; across <= radius; across++) {
			for (int forward = -radius; forward <= radius; forward++) {
				if (Math.abs(across) != radius && Math.abs(forward) != radius) continue;
				BlockPos pos = farmOffset(center, direction, across, forward);
				Step step = at(steps, pos);
				if (step == null || step.free() || step.optional() || step.cost() != 1
					|| !Builder.farmStepRequiresPayment(Blueprints.FARM, step)) {
					perimeter = false;
					continue;
				}
				Block paid = Block.byItem(step.item());
				if (pos.equals(gatePos)) {
					gateCount++;
					BlockState state = step.make().apply(paid);
					perimeter &= paid == gate && paid instanceof FenceGateBlock
						&& state.getValue(FenceGateBlock.FACING) == direction;
				} else {
					fenceCount++;
					perimeter &= paid == fence && step.item().equals(fence.asItem());
				}
			}
		}
		int expectedFenceCount = radius * 8 - 1;
		check(perimeter && fenceCount == expectedFenceCount && gateCount == 1,
			"FARM encloses every edge with one paid matching fence gate facing " + direction);
		int waterCells = half <= 3 ? 1 : 4;
		boolean irrigated = countFree(steps, Blocks.WATER) == waterCells;
		if (half <= 3) {
			irrigated &= hasFree(steps, center.below(), Blocks.WATER);
		} else {
			for (int dx : new int[] {-2, 2}) {
				for (int dz : new int[] {-2, 2}) {
					irrigated &= hasFree(steps, center.offset(dx, -1, dz), Blocks.WATER);
				}
			}
		}
		long cropCells = (2L * half + 1) * (2L * half + 1) - waterCells;
		check(steps.stream().filter(step -> step.item() == Items.WHEAT_SEEDS).count() == cropCells
			&& countFree(steps, Blocks.FARMLAND) == cropCells && irrigated,
			"the farm perimeter leaves all paid crops, farmland, and irrigation steps intact");
		int decorRadius = radius;
		BlockPos leftPost = farmOffset(center, direction, -decorRadius, decorRadius);
		BlockPos rightPost = farmOffset(center, direction, decorRadius, decorRadius);
		BlockPos scarecrow = farmOffset(center, direction, decorRadius, -decorRadius);
		check(hasFree(steps, leftPost.above(), fence) && hasFree(steps, leftPost.above(2), Blocks.LANTERN)
			&& hasFree(steps, rightPost.above(), fence) && hasFree(steps, rightPost.above(2), Blocks.LANTERN)
			&& hasFree(steps, scarecrow.above(), Blocks.HAY_BLOCK)
			&& hasFree(steps, scarecrow.above(2), Blocks.CARVED_PUMPKIN)
			&& hasFree(steps, farmOffset(center, direction, -decorRadius - 1, -decorRadius), Blocks.POPPY)
			&& hasFree(steps, farmOffset(center, direction, -decorRadius - 1, -decorRadius + 1), Blocks.CORNFLOWER),
			"rotating the field preserves its lanterns, scarecrow, and flowers without overlapping the fence ring");
		return 3;
	}

	private static Step at(List<Step> steps, BlockPos pos) {
		return steps.stream().filter(step -> step.pos().equals(pos)).findFirst().orElse(null);
	}

	private static int countFree(List<Step> steps, Block block) {
		return (int) steps.stream().filter(Step::free)
			.filter(step -> state(step).is(block)).count();
	}

	private static boolean hasFree(List<Step> steps, BlockPos pos, Block block) {
		Step step = at(steps, pos);
		return step != null && step.free() && state(step).is(block);
	}

	private static BlockState state(Step step) {
		return step.make().apply(step.free() ? Blocks.AIR : Block.byItem(step.item()));
	}

	private static BlockPos farmOffset(BlockPos center, Direction facing, int across, int forward) {
		return center.relative(facing.getClockWise(), across).relative(facing, forward);
	}

	private static int cropCount(int variant, net.minecraft.world.item.Item seed) {
		return (int) Blueprints.farm(BlockPos.ZERO, variant).stream().filter(step -> seed.equals(step.item())).count();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Farm plan sizing: " + message);
	}
}
