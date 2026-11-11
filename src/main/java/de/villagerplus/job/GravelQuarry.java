package de.villagerplus.job;

import de.villagerplus.village.Roads;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import de.villagerplus.village.VillageData;
import de.villagerplus.build.Blueprints;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Safe, demand-led gravel supply plus local visual care for registered mine galleries. */
public final class GravelQuarry {
	// Mirrors the mine's bounded horizontal route and three-wide walk profile.
	private static final int MAX_GALLERY_DECORATION_SECTIONS = 56;
	private static final int MINE_SUPPORT_RADIUS = 2;
	private static final int MINE_WALK_LANE_HALF_WIDTH = 1;
	private static final Item[] SHOVELS = {Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL,
		Items.GOLDEN_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL};
	private GravelQuarry() { }

	/** Natural exposed gravel only; excludes road lanes, depot footprints, fluids, and unsafe drops. */
	public static boolean naturalSource(ServerLevel level, BlockPos gravel) {
		if (gravel == null || !level.hasChunkAt(gravel.below()) || !level.hasChunkAt(gravel)
			|| !level.hasChunkAt(gravel.above(2))) return false;
		BlockState state = level.getBlockState(gravel);
		if (!state.is(Blocks.GRAVEL) || !state.getFluidState().isEmpty() || !state.isFaceSturdy(level, gravel, Direction.UP)
			|| !level.getBlockState(gravel.above()).isAir() || !level.getBlockState(gravel.above(2)).isAir()
			|| !level.getBlockState(gravel.below()).isFaceSturdy(level, gravel.below(), Direction.UP)
			|| !level.getBlockState(gravel.below()).getFluidState().isEmpty() || Roads.isProtectedLaneCell(level, gravel)
			|| roadSurface(level, gravel) || depotFootprint(level, gravel)) return false;
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gravel.getX(), gravel.getZ()) - 1 == gravel.getY();
	}

	private static boolean roadSurface(ServerLevel level, BlockPos gravel) {
		int roadEdges = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockState adjacent = level.getBlockState(gravel.relative(direction));
			if (adjacent.is(Blocks.COBBLESTONE) || adjacent.is(Blocks.SANDSTONE)) roadEdges++;
		}
		return roadEdges >= 2;
	}

	private static boolean depotFootprint(ServerLevel level, BlockPos gravel) {
		for (int dy = -1; dy <= 2; dy++) {
			BlockPos pos = gravel.above(dy);
			if (!level.hasChunkAt(pos)) return true;
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
				|| state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock) return true;
		}
		return false;
	}

	public static BlockPos find(ServerLevel level, BlockPos origin, int radius) {
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -4, -radius), origin.offset(radius, 4, radius))) {
			if (naturalSource(level, pos)) return pos.immutable();
		}
		return null;
	}

	private record Demand(int target, boolean active, boolean stationPlan) { }

	private static Demand demand(Ctx c) {
		VillageData data = c.village().data();
		int planTarget = data.hasBuildProjectType(Blueprints.STATION)
			? requestedGravel(data.wanted) : 0;
		int target = planTarget;
		var state = c.st();
		var task = state.roadRepair;
		if (task != null && task.removal() == null && task.paving().is(Blocks.GRAVEL)) target = Math.max(target, 1);
		if (target == 0 && task == null && !state.roadBuilding && c.now() >= state.roadScanAt) {
			state.roadScanAt = c.now() + 200;
			task = Roads.nextRepair(c.level(), c.village());
			if (task != null) {
				state.roadRepair = task;
				if (task.removal() == null && task.paving().is(Blocks.GRAVEL)) target = 1;
			}
		}
		if (target == 0 && state.roadRepair == null && !state.roadBuilding && c.now() >= state.roadBuildScanAt) {
			state.roadBuildScanAt = c.now() + 200;
			task = Roads.nextConstruction(c.level(), c.village());
			if (task != null) {
				state.roadRepair = task;
				state.roadBuilding = true;
				if (task.removal() == null && task.paving().is(Blocks.GRAVEL)) target = 1;
			}
		}
		return new Demand(Math.min(4096, target), target > 0, planTarget > 0);
	}

	static int requestedGravel(List<String> wanted) {
		int target = 0;
		for (String entry : wanted) {
			int split = entry.lastIndexOf('#');
			if (split <= 0 || !entry.substring(0, split).equals("minecraft:gravel")) continue;
			try { target = Math.max(target, Math.max(0, Integer.parseInt(entry.substring(split + 1)))); }
			catch (NumberFormatException ignored) { }
		}
		return target;
	}

	private static Item shovel(Container inventory) {
		for (Item item : SHOVELS) if (JobUtil.count(inventory, stack -> stack.is(item) && !stack.isDamaged()) > 0) return item;
		return null;
	}

	private static boolean fits(Container inventory, List<ItemStack> drops) {
		SimpleContainer simulation = new SimpleContainer(inventory.getContainerSize());
		for (int i = 0; i < inventory.getContainerSize(); i++) simulation.setItem(i, inventory.getItem(i).copy());
		for (ItemStack drop : drops) if (!simulation.addItem(drop.copy()).isEmpty()) return false;
		return true;
	}

	public static boolean work(Ctx c) {
		if (c.village() == null) return false;
		Predicate<ItemStack> gravel = stack -> stack.is(Items.GRAVEL);
		Demand demand = demand(c);
		// Deliver carried gravel first, including after a previously interrupted trip. Gravel
		// requested by a station plan belongs in that station's public material depot when one
		// is already registered; road work continues to use the public village material stores.
		if (JobUtil.count(c.v().getInventory(), gravel) > 0) {
			if (demand.stationPlan() && deliverToStationDepot(c, demand)) return true;
			if (JobUtil.depositSurplus(c, gravel, 1, 0)) return true;
		}
		if (raiseGalleryLight(c)) return true;
		if (!demand.active()) return false;
		int available = StockPolicy.communalCount(c.level(), c.village(), Items.GRAVEL);
		int carried = JobUtil.count(c.v().getInventory(), gravel);
		if (available + carried >= demand.target()) return false;
		Item tool = shovel(c.v().getInventory());
		if (tool == null) return false;
		// If a mason is already working a live, lit mine, prefer a safe gravel face in
		// that supported gallery. Otherwise retain the existing surface-quarry fallback.
		if (inActiveGallery(c)) return workMineFace(c, tool);

		BlockPos pos = c.st().target;
		if (!naturalSource(c.level(), pos)) {
			pos = find(c.level(), c.v().blockPosition(), 12);
			c.st().target = pos;
		}
		if (pos == null) return false;
		c.setActivity("bricht Schotter");
		if (!JobUtil.approach(c, pos, 3.2)) return true;
		if (!naturalSource(c.level(), pos)) {
			c.st().target = null;
			Anim.cancelBreaking(c.v());
			return false;
		}
		BlockState state = c.level().getBlockState(pos);
		ItemStack toolStack = findTool(c.v().getInventory(), tool);
		if (toolStack.isEmpty()) { c.st().target = null; Anim.cancelBreaking(c.v()); return false; }
		List<ItemStack> drops = Block.getDrops(state, c.level(), pos, null, c.v(), toolStack.copy());
		if (!fits(c.v().getInventory(), drops)) {
			c.st().target = null;
			Anim.cancelBreaking(c.v());
			c.setActivity("wartet auf freien Inventarplatz");
			return false;
		}
		if (!Anim.breakStep(c, pos, state, tool)) return true;
		// Re-check capacity and the natural source at commit time; failed retries leave both intact.
		if (!naturalSource(c.level(), pos) || !fits(c.v().getInventory(), drops)
			|| !c.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
			Anim.cancelBreaking(c.v());
			c.st().target = null;
			return false;
		}
		for (ItemStack drop : drops) JobUtil.give(c.level(), c.v(), drop.copy());
		Anim.blockBreak(c.level(), pos, state);
		c.st().target = null;
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 10;
		// Start the communal delivery on the next work tick, so production never accumulates privately.
		return true;
	}

	/** Moves an existing floor-level mine torch into a clear wall mount at head height without consuming or creating items. */
	static boolean raiseGalleryLight(Ctx c) {
		VillageData.Mine mine = c.village() == null ? null : c.village().data().mine.orElse(null);
		if (mine == null || mine.done) return false;
		int descent = mine.entrance.getY() - mine.targetY;
		if (descent < 0 || mine.index <= descent) return false;
		int end = Math.min(mine.index, descent + MAX_GALLERY_DECORATION_SECTIONS);
		Direction direction = Direction.from2DDataValue(mine.dir);
		Direction lateral = direction.getClockWise();
		ServerLevel level = c.level();
		for (int index = Math.max(1, descent); index < end; index++) {
			if (!Mining.lightSection(index)) continue;
			BlockPos route = mine.entrance.relative(direction, index);
			BlockPos center = new BlockPos(route.getX(), mine.targetY, route.getZ());
			for (int sign : new int[] {-1, 1}) {
				BlockPos floorTorch = center.relative(lateral, sign * MINE_SUPPORT_RADIUS);
				if (!level.hasChunkAt(floorTorch) || !level.getBlockState(floorTorch).is(Blocks.TORCH)
					|| c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(floorTorch)) > 9.0
					|| Roads.isProtectedLaneCell(level, floorTorch)) continue;
				boolean walkSurfaceSupported = true;
				for (int lane = -MINE_WALK_LANE_HALF_WIDTH; lane <= MINE_WALK_LANE_HALF_WIDTH; lane++) {
					BlockPos floor = center.relative(lateral, lane).below();
					if (!level.hasChunkAt(floor) || !level.getBlockState(floor).getFluidState().isEmpty()
						|| !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
						walkSurfaceSupported = false;
						break;
					}
				}
				if (!walkSurfaceSupported) continue;
				Direction towardBacking = sign > 0 ? lateral : lateral.getOpposite();
				BlockPos wallTorch = center.relative(lateral, sign * MINE_WALK_LANE_HALF_WIDTH).above();
				BlockPos backing = wallTorch.relative(towardBacking);
				if (!level.hasChunkAt(wallTorch) || !level.hasChunkAt(backing)
					|| !level.getBlockState(wallTorch).isAir() || Roads.isProtectedLaneCell(level, wallTorch)) continue;
				BlockState backingState = level.getBlockState(backing);
				Direction facing = towardBacking.getOpposite();
				if (!backingState.isFaceSturdy(level, backing, facing)) continue;
				BlockState mounted = Blocks.WALL_TORCH.defaultBlockState()
					.setValue(HorizontalDirectionalBlock.FACING, facing);
				if (!mounted.canSurvive(level, wallTorch) || !mounted.getCollisionShape(level, wallTorch).isEmpty()
					|| !level.setBlock(wallTorch, mounted, 3) || !level.getBlockState(wallTorch).is(Blocks.WALL_TORCH)) continue;
				if (!level.setBlock(floorTorch, Blocks.AIR.defaultBlockState(), 3) || !level.getBlockState(floorTorch).isAir()) {
					level.setBlock(wallTorch, Blocks.AIR.defaultBlockState(), 3);
					continue;
				}
				c.setActivity("ordnet die Minenbeleuchtung");
				JobUtil.swing(c.v());
				c.mem().jobsDone++;
				c.st().cooldownUntil = c.now() + 10;
				return true;
			}
		}
		return false;
	}

	/** Delivers only the remaining station-plan shortage into a loaded, registered, public station store. */
	private static boolean deliverToStationDepot(Ctx c, Demand demand) {
		int remainingDemand = Math.max(0, demand.target() - StockPolicy.communalCount(c.level(), c.village(), Items.GRAVEL));
		int deliverable = Math.min(remainingDemand, StockPolicy.deliveryRoom(c.level(), c.village(), Items.GRAVEL));
		if (deliverable <= 0) return false;
		BlockPos depotPos = Specials.depotWithRoom(c.level(), c.village(), Blueprints.STATION, new ItemStack(Items.GRAVEL));
		if (depotPos == null) return false;

		if (!depotPos.equals(c.st().target) || c.st().targetEntity != null) {
			c.st().target = depotPos.immutable();
			c.st().targetEntity = null;
			c.st().walkStart = 0;
			c.st().bestDist = 0.0;
			c.st().lastMoveAt = 0;
			c.st().stuckCount = 0;
		}
		c.setActivity("bringt Schotter zum öffentlichen Bahnhofslager");
		if (!JobUtil.approachLong(c, depotPos, 3.0)) return true;

		Container depot = Lager.publicContainerAt(c.level(), depotPos);
		if (depot == null) return false;
		// Recount at arrival: another worker may have satisfied the plan while this mason
		// was walking, so only the still-missing demand is committed to the station store.
		remainingDemand = Math.max(0, demand.target() - StockPolicy.communalCount(c.level(), c.village(), Items.GRAVEL));
		deliverable = Math.min(remainingDemand, StockPolicy.deliveryRoom(c.level(), c.village(), Items.GRAVEL));
		if (deliverable <= 0) return false;
		Container inventory = c.v().getInventory();
		for (int i = 0; i < inventory.getContainerSize() && deliverable > 0; i++) {
			ItemStack carried = inventory.getItem(i);
			if (!carried.is(Items.GRAVEL)) continue;
			int planned = Math.min(deliverable, carried.getCount());
			ItemStack offered = carried.copyWithCount(planned);
			ItemStack rest = StockPolicy.insertInto(c.level(), c.village(), Blueprints.STATION, depot, offered);
			int moved = planned - rest.getCount();
			if (moved <= 0) return false;
			carried.shrink(moved);
			deliverable -= moved;
		}
		inventory.setChanged();
		JobUtil.swing(c.v());
		c.st().cooldownUntil = c.now() + 100;
		c.mem().jobsDone++;
		return true;
	}

	/** Mines an exposed wall block only beside the currently advanced horizontal mine gallery. */
	private static boolean workMineFace(Ctx c, Item shovel) {
		VillageData.Mine mine = c.village().data().mine.orElse(null);
		if (mine == null || mine.done) return false;
		int descent = mine.entrance.getY() - mine.targetY;
		if (mine.index <= descent) return false;
		Direction direction = Direction.from2DDataValue(mine.dir);
		int workerIndex = -1;
		double best = 3.2 * 3.2;
		for (int index = descent; index < mine.index; index++) {
			BlockPos center = mine.entrance.relative(direction, index);
			center = new BlockPos(center.getX(), mine.targetY, center.getZ());
			double distance = c.v().blockPosition().distSqr(center);
			if (distance < best) { best = distance; workerIndex = index; }
		}
		if (workerIndex < 0) return false;
		BlockPos center = new BlockPos(mine.entrance.relative(direction, workerIndex).getX(), mine.targetY,
			mine.entrance.relative(direction, workerIndex).getZ());
		if (!supportedAndLitGallery(c.level(), mine, direction, workerIndex)) return false;
		for (Direction side : new Direction[] {direction.getClockWise(), direction.getCounterClockWise()}) {
			for (int height = 0; height < 2; height++) {
				BlockPos pos = center.relative(side).above(height);
				if (!c.level().hasChunkAt(pos) || c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(pos)) > 9.0
					|| !c.level().getBlockState(pos).is(Blocks.GRAVEL)
					|| Roads.isProtectedLaneCell(c.level(), pos) || roadSurface(c.level(), pos) || depotFootprint(c.level(), pos)
					|| !c.level().getBlockState(pos.above()).getFluidState().isEmpty()
					|| c.level().getBlockState(pos.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock
					|| !c.level().getBlockState(pos.below()).isFaceSturdy(c.level(), pos.below(), Direction.UP)) continue;
				BlockState state = c.level().getBlockState(pos);
				List<ItemStack> drops = Block.getDrops(state, c.level(), pos, null, c.v(), findTool(c.v().getInventory(), shovel).copy());
				if (!fits(c.v().getInventory(), drops)) {
					c.setActivity("wartet auf freien Inventarplatz");
					return true;
				}
				c.setActivity("fördert Schotter aus der Mine");
				if (!Anim.breakStep(c, pos, state, shovel)) return true;
				if (!c.level().getBlockState(pos).is(Blocks.GRAVEL) || !supportedAndLitGallery(c.level(), mine, direction, workerIndex)
					|| !fits(c.v().getInventory(), drops) || !c.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
					Anim.cancelBreaking(c.v());
					return false;
				}
				for (ItemStack drop : drops) JobUtil.give(c.level(), c.v(), drop.copy());
				Anim.blockBreak(c.level(), pos, state);
				c.mem().jobsDone++;
				c.st().cooldownUntil = c.now() + 10;
				return true;
			}
		}
		return false;
	}

	private static boolean inActiveGallery(Ctx c) {
		VillageData.Mine mine = c.village().data().mine.orElse(null);
		if (mine == null || mine.done) return false;
		int descent = mine.entrance.getY() - mine.targetY;
		if (mine.index <= descent) return false;
		Direction direction = Direction.from2DDataValue(mine.dir);
		for (int i = descent; i < mine.index; i++) {
			BlockPos line = mine.entrance.relative(direction, i);
			BlockPos center = new BlockPos(line.getX(), mine.targetY, line.getZ());
			if (c.v().blockPosition().distSqr(center) <= 9.0) return true;
		}
		return false;
	}

	/** Requires nearby structural timber and an actual torch, not just a mine-data record. */
	private static boolean supportedAndLitGallery(ServerLevel level, VillageData.Mine mine, Direction direction, int index) {
		boolean support = false;
		boolean torch = false;
		for (int i = Math.max(0, index - 4); i <= Math.min(mine.index, index + 4); i++) {
			BlockPos center = mine.entrance.relative(direction, i);
			center = new BlockPos(center.getX(), mine.targetY, center.getZ());
			for (BlockPos p : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 3, 1))) {
				if (!level.hasChunkAt(p)) continue;
				BlockState state = level.getBlockState(p);
				if (state.is(Blocks.OAK_FENCE) || state.is(Blocks.OAK_PLANKS) || state.is(Blocks.SPRUCE_LOG)) support = true;
				if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.LANTERN)) torch = true;
			}
		}
		return support && torch;
	}

	private static ItemStack findTool(Container inventory, Item tool) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(tool) && !stack.isDamaged()) return stack;
		}
		return ItemStack.EMPTY;
	}
}
