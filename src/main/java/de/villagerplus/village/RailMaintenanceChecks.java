package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobState;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.RoadBuilderJob;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.Heightmap;

/** Synthetic in-world check for paid, physical repair of a previously built rail half. */
public final class RailMaintenanceChecks {
	private RailMaintenanceChecks() { }

	private record Cell(BlockState state, CompoundTag data) { }

	public static int run(ServerLevel level, BlockPos at) {
		level.getChunkAt(at);
		int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 1;
		at = new BlockPos(at.getX(), groundY + 1, at.getZ());
		Map<BlockPos, Cell> before = capture(level, at, groundY);
		Villager[] worker = new Villager[1];
		int checks;
		try {
			checks = runFixture(level, at, worker);
		} finally {
			if (worker[0] != null && worker[0].isAlive()) worker[0].discard();
			restore(level, before);
			Villages.resetAssignmentCache();
			if (worker[0] != null) Villages.find(level, worker[0]); // prune the two removed fixture bells from the chunk cache
		}
		List<String> differences = differences(level, before, 5);
		check(differences.isEmpty(), "fixture restores rail, depot, bell and attachment state"
			+ (differences.isEmpty() ? "" : "; " + String.join(" || ", differences)));
		return checks + 1;
	}

	private static int runFixture(ServerLevel level, BlockPos homeAt, Villager[] workerRef) {
		BlockPos peerAt = homeAt.south(8);
		level.getChunkAt(homeAt);
		level.getChunkAt(peerAt);
		var home = Checks090.fixtureForEquipment(level, homeAt);
		var peer = Checks090.fixtureForEquipment(level, peerAt);
		Container depot = home.chest(level);
		depot.clearContent();
		Specials.set(home, Blueprints.STATION, homeAt);
		Network.save(home.bellEntity(), new TradeNetwork(List.of(), List.of(
			new TradeNetwork.Link(Network.dim(level), peer.bell(), "Rail repair peer", Roads.METROPOLIS, true, 8, 0)), Map.of(), 0, 0));
		Network.save(peer.bellEntity(), new TradeNetwork(List.of(), List.of(
			new TradeNetwork.Link(Network.dim(level), home.bell(), "Rail repair home", Roads.METROPOLIS, true, 8, 0)), Map.of(), 0, 0));

		int length = 8;
		String routeKey = Network.dim(level) + peer.bell().asLong();
		String railKey = "rail" + peer.bell().asLong();
		Map<String, Long> roads = new java.util.HashMap<>();
		long low = Math.min(home.bell().asLong(), peer.bell().asLong());
		long high = Math.max(home.bell().asLong(), peer.bell().asLong());
		roads.put(routeKey + ":routeFrom", low);
		roads.put(routeKey + ":routeTo", high);
		roads.put(routeKey + ":routeGeometry", 3L);
		roads.put(routeKey + ":routeCount", (long) length + 1);
		for (int i = 0; i <= length; i++) roads.put(routeKey + ":routePoint:" + i,
			new BlockPos(homeAt.getX(), 0, homeAt.getZ() + i).asLong());
		roads.put(railKey, 2L);
		home.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(roads));

		// The peer village bell is the route endpoint and must stay intact for bilateral-link validation.
		for (int cursor = 1; cursor < length; cursor++) {
			BlockPos support = homeAt.south(cursor);
			level.setBlock(support.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(support, Blocks.GRAVEL.defaultBlockState(), 3);
			level.setBlock(support.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(support.above(2), Blocks.AIR.defaultBlockState(), 3);
			if (cursor == 1 || cursor == 4) {
				level.setBlock(support.above(), railState(RailShape.NORTH_SOUTH), 3);
			}
		}
		roads.put(railKey, 2L);
		home.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(roads));
		check(level.getBlockEntity(peerAt) == peer.bellEntity(), "rail route fixture preserves its linked peer bell");
		check(RailMaintenance.nextRepair(level, home) == null,
			"unbuilt rail cursor is not mistaken for damage");
		roads.put(railKey, 5L);
		home.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(roads));
		RailMaintenance.Repair first = RailMaintenance.nextRepair(level, home);
		boolean firstRepairExpected = first != null && first.cursor() == 2 && first.railItem() == Items.RAIL
			&& !first.needsRedstoneSupport();
		check(firstRepairExpected, "saved built route identifies the first missing ordinary rail"
			+ (firstRepairExpected ? "" : repairDiagnostics(level, home, peer, roads, routeKey, railKey, first, length)));

		BlockPos chest = home.data().chest.orElseThrow();
		workerRef[0] = EntityTypes.VILLAGER.spawn(level, chest.south(2), EntitySpawnReason.COMMAND);
		Villager worker = workerRef[0];
		worker.setNoAi(true);
		worker.setInvulnerable(true);
		worker.addTag("vp090_fixture");
		JobState state = new JobState();
		var memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
		long now = level.getGameTime() + 5000;
		Ctx context = new Ctx(level, worker, state, memory, home, now);
		BlockPos firstTarget = first.rail();
		check(!RoadBuilderJob.maintain(context) && level.getBlockState(firstTarget).isAir(),
			"missing depot stock leaves damaged track untouched");

		depot.setItem(0, new ItemStack(Items.RAIL, 1));
		context = new Ctx(level, worker, state, memory, home, now + 201);
		check(RoadBuilderJob.maintain(context) && JobUtil.count(depot, s -> s.is(Items.RAIL)) == 0
			&& JobUtil.count(worker.getInventory(), s -> s.is(Items.RAIL)) == 1,
			"rail builder physically collects one rail from public storage");
		worker.setPos(firstTarget.getX() + .5, firstTarget.getY() + 1, firstTarget.getZ() + .5);
		context = new Ctx(level, worker, state, memory, home, now + 202);
		check(RoadBuilderJob.maintain(context) && level.getBlockState(firstTarget).getBlock() instanceof BaseRailBlock
			&& JobUtil.count(worker.getInventory(), s -> s.is(Items.RAIL)) == 0,
			"rail builder restores the gap and consumes the carried rail");

		BlockPos poweredTarget = homeAt.south(3).above();
		depot.setItem(0, new ItemStack(Items.POWERED_RAIL, 1));
		depot.setItem(1, new ItemStack(Items.REDSTONE_BLOCK, 1));
		context = new Ctx(level, worker, state, memory, home, now + 203);
		check(RoadBuilderJob.maintain(context), "rail builder discovers the committed powered-rail gap");
		worker.setPos(chest.getX() + .5, chest.getY(), chest.getZ() + .5);
		context = new Ctx(level, worker, state, memory, home, now + 204);
		check(RoadBuilderJob.maintain(context) && JobUtil.count(worker.getInventory(), s -> s.is(Items.POWERED_RAIL)) == 1,
			"powered rail is physically collected before repair");
		context = new Ctx(level, worker, state, memory, home, now + 205);
		check(RoadBuilderJob.maintain(context) && JobUtil.count(worker.getInventory(), s -> s.is(Items.REDSTONE_BLOCK)) == 1,
			"powered rail repair also collects its missing redstone support");
		worker.setPos(poweredTarget.getX() + .5, poweredTarget.getY() + 1, poweredTarget.getZ() + .5);
		context = new Ctx(level, worker, state, memory, home, now + 206);
		check(RoadBuilderJob.maintain(context) && level.getBlockState(poweredTarget).is(Blocks.POWERED_RAIL)
			&& level.getBlockState(poweredTarget.below()).is(Blocks.REDSTONE_BLOCK)
			&& depot.getItem(0).isEmpty() && depot.getItem(1).isEmpty()
			&& worker.getInventory().isEmpty(),
			"powered rail and support are placed and both real materials are consumed");
		check(RailMaintenance.nextRepair(level, home) == null,
			"an intact committed half no longer schedules rail repairs");

		level.setBlock(poweredTarget, Blocks.AIR.defaultBlockState(), 3);
		depot.setItem(0, new ItemStack(Items.POWERED_RAIL, 1));
		RailMaintenance.Repair retainedSupport = RailMaintenance.nextRepair(level, home);
		check(retainedSupport != null && retainedSupport.cursor() == 3 && !retainedSupport.needsRedstoneSupport()
			&& level.getBlockState(retainedSupport.support()).is(Blocks.REDSTONE_BLOCK),
			"missing powered rail recognizes its retained redstone support over stone");
		level.setBlock(retainedSupport.support(), Blocks.GRAVEL.defaultBlockState(), 3);
		check(!RailMaintenance.repairable(level, retainedSupport) && JobUtil.count(depot, s -> s.is(Items.POWERED_RAIL)) == 1
			&& worker.getInventory().isEmpty(),
			"support change invalidates the saved task before material is taken");
		level.setBlock(retainedSupport.support(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
		context = new Ctx(level, worker, state, memory, home, now + 207);
		check(RoadBuilderJob.maintain(context), "rail builder resumes retained-support repair");
		worker.setPos(chest.getX() + .5, chest.getY(), chest.getZ() + .5);
		context = new Ctx(level, worker, state, memory, home, now + 208);
		check(RoadBuilderJob.maintain(context) && JobUtil.count(worker.getInventory(), s -> s.is(Items.POWERED_RAIL)) == 1
			&& JobUtil.count(worker.getInventory(), s -> s.is(Items.REDSTONE_BLOCK)) == 0,
			"retained support repair collects only the missing powered rail");
		worker.setPos(poweredTarget.getX() + .5, poweredTarget.getY() + 1, poweredTarget.getZ() + .5);
		context = new Ctx(level, worker, state, memory, home, now + 209);
		check(RoadBuilderJob.maintain(context) && level.getBlockState(poweredTarget).is(Blocks.POWERED_RAIL)
			&& level.getBlockState(poweredTarget.below()).is(Blocks.REDSTONE_BLOCK)
			&& depot.getItem(0).isEmpty() && worker.getInventory().isEmpty(),
			"retained redstone support is preserved and the powered rail alone is paid");
		check(RailMaintenance.nextRepair(level, home) == null,
			"retained-support route is complete after repair");
		return 17;
	}

	private static BlockState railState(RailShape shape) {
		BaseRailBlock rail = (BaseRailBlock) Blocks.RAIL;
		return rail.defaultBlockState().setValue(rail.getShapeProperty(), shape);
	}

	private static String repairDiagnostics(ServerLevel level, VillageRef home, VillageRef peer,
		Map<String, Long> roads, String routeKey, String railKey, RailMaintenance.Repair first, int length) {
		String dimension = Network.dim(level);
		int routeLength = RoadRoute.length(roads, routeKey, home.bell(), peer.bell());
		int last = routeLength / 2 + 1;
		long savedRailCursor = roads.getOrDefault(railKey, 0L);
		int built = (int) Math.max(0, Math.min((long) last + 1, savedRailCursor));
		BlockPos station = Specials.get(home, Blueprints.STATION);
		String found = first == null ? "null" : "cursor=" + first.cursor() + ",item=" + first.railItem()
			+ ",support=" + first.support().toShortString() + "/" + level.getBlockState(first.support())
			+ ",rail=" + first.rail().toShortString() + "/" + level.getBlockState(first.rail())
			+ ",needsRedstone=" + first.needsRedstoneSupport();
		String validHome;
		String validPeer;
		try {
			var method = RailMaintenance.class.getDeclaredMethod("validBell", ServerLevel.class, VillageRef.class);
			method.setAccessible(true);
			validHome = String.valueOf(method.invoke(null, level, home));
			validPeer = String.valueOf(method.invoke(null, level, peer));
		} catch (ReflectiveOperationException error) {
			validHome = "unavailable:" + error.getClass().getSimpleName();
			validPeer = "unavailable:" + error.getClass().getSimpleName();
		}
		StringBuilder homeLinks = new StringBuilder();
		for (TradeNetwork.Link link : Network.of(home.bellEntity()).links()) {
			VillageRef resolved = Network.resolve(level, link);
			if (homeLinks.length() > 0) homeLinks.append("; ");
			homeLinks.append("{dim=").append(link.dimension()).append(",bell=").append(link.bell().toShortString())
				.append(",relation=").append(link.relation()).append("(pass=").append(link.relation() >= Roads.METROPOLIS)
				.append("),route=").append(link.route()).append(",dimensionMatch=").append(dimension.equals(link.dimension()))
				.append(",targetPeer=").append(link.bell().equals(peer.bell())).append(",resolve=")
				.append(resolved == null ? "null" : resolved.bell().toShortString() + "/beSame="
					+ (resolved.bellEntity() == peer.bellEntity()) + "/dataSame=" + (resolved.data() == peer.data()))
				.append('}');
		}
		StringBuilder peerLinks = new StringBuilder();
		for (TradeNetwork.Link link : Network.of(peer.bellEntity()).links()) {
			if (peerLinks.length() > 0) peerLinks.append("; ");
			peerLinks.append("{dim=").append(link.dimension()).append(",bell=").append(link.bell().toShortString())
				.append(",relation=").append(link.relation()).append("(pass=").append(link.relation() >= Roads.METROPOLIS)
				.append("),route=").append(link.route()).append(",dimensionMatch=").append(dimension.equals(link.dimension()))
				.append(",targetHome=").append(link.bell().equals(home.bell())).append('}');
		}
		String reciprocal;
		try {
			var method = RailMaintenance.class.getDeclaredMethod("reciprocalRailLink", ServerLevel.class,
				VillageRef.class, VillageRef.class);
			method.setAccessible(true);
			reciprocal = String.valueOf(method.invoke(null, level, home, peer));
		} catch (ReflectiveOperationException error) {
			reciprocal = "unavailable:" + error.getClass().getSimpleName();
		}
		String storedRoute;
		try {
			var method = RailMaintenance.class.getDeclaredMethod("storedRoute", Map.class, String.class,
				BlockPos.class, BlockPos.class);
			method.setAccessible(true);
			storedRoute = String.valueOf(method.invoke(null, roads, routeKey, home.bell(), peer.bell()));
		} catch (ReflectiveOperationException error) {
			storedRoute = "unavailable:" + error.getClass().getSimpleName();
		}
		int startValue = Integer.MIN_VALUE;
		String stationStartError = "";
		try {
			var method = RailMaintenance.class.getDeclaredMethod("stationStart", ServerLevel.class, Map.class,
				String.class, BlockPos.class, BlockPos.class, BlockPos.class, int.class, int.class);
			method.setAccessible(true);
			startValue = (int) method.invoke(null, level, roads, routeKey, home.bell(), peer.bell(),
				station, routeLength, last);
		} catch (ReflectiveOperationException error) {
			stationStartError = ":" + error.getClass().getSimpleName();
		}
		StringBuilder candidates = new StringBuilder();
		for (int cursor = 0; cursor <= last; cursor++) {
			BlockPos point = RoadRoute.point(roads, routeKey, home.bell(), peer.bell(), cursor);
			boolean candidateLoaded = level.hasChunkAt(new BlockPos(point.getX(), home.bell().getY(), point.getZ()));
			String site;
			String siteConditions;
			try {
				var method = RailMaintenance.class.getDeclaredMethod("findSite", ServerLevel.class,
					int.class, int.class, int.class);
				method.setAccessible(true);
				Object value = method.invoke(null, level, point.getX(), point.getZ(), home.bell().getY());
				site = String.valueOf(value);
				if (value != null) {
					var supportAccessor = value.getClass().getDeclaredMethod("support");
					var railAccessor = value.getClass().getDeclaredMethod("rail");
					supportAccessor.setAccessible(true);
					railAccessor.setAccessible(true);
					BlockPos selectedSupport = (BlockPos) supportAccessor.invoke(value);
					BlockPos selectedRail = (BlockPos) railAccessor.invoke(value);
					BlockPos headroom = selectedRail.above();
					BlockState supportState = level.getBlockState(selectedSupport);
					BlockState target = level.getBlockState(selectedRail);
					BlockState head = level.getBlockState(headroom);
					BlockEntity targetEntity = level.getBlockEntity(selectedRail);
					boolean targetFluidEmpty = target.getFluidState().isEmpty();
					boolean headCollisionEmpty = head.getCollisionShape(level, headroom).isEmpty();
					boolean headFluidEmpty = head.getFluidState().isEmpty();
					String supportChecks;
					if (supportState.is(Blocks.REDSTONE_BLOCK)) {
						BlockPos foundation = selectedSupport.below();
						BlockState foundationState = level.getBlockState(foundation);
						supportChecks = "redstoneFoundation=" + foundationState.isFaceSturdy(level, foundation,
							net.minecraft.core.Direction.UP) + "/blockEntity=" + (level.getBlockEntity(foundation) != null);
					} else {
						supportChecks = "roadSupport=" + supportState;
					}
					try {
						var usableMethod = RailMaintenance.class.getDeclaredMethod("usableSite", ServerLevel.class,
							value.getClass());
						usableMethod.setAccessible(true);
						siteConditions = "usableSite=" + usableMethod.invoke(null, level, value);
					} catch (ReflectiveOperationException error) {
						siteConditions = "usableSite=unavailable:" + error.getClass().getSimpleName();
					}
					siteConditions += ",candidateLoaded=" + candidateLoaded
						+ ",siteLoaded=" + (level.hasChunkAt(selectedSupport) && level.hasChunkAt(selectedRail))
						+ ",supportBlockEntity=" + (level.getBlockEntity(selectedSupport) != null)
						+ "," + supportChecks + ",targetBlockEntity=" + (targetEntity != null)
						+ ",target=" + target + "/air=" + target.isAir() + "/replaceable=" + target.canBeReplaced()
						+ "/fluidEmpty=" + targetFluidEmpty + ",headroom=" + head + "/collisionEmpty="
						+ headCollisionEmpty + "/fluidEmpty=" + headFluidEmpty;
					site += " blocks=" + supportState + " -> " + target;
				} else {
					BlockPos expectedRail = new BlockPos(point.getX(), home.bell().getY() + 1, point.getZ());
					BlockPos expectedSupport = expectedRail.below();
					BlockPos expectedHeadroom = expectedRail.above();
					siteConditions = "usableSite=n/a,candidateLoaded=" + candidateLoaded
						+ ",expectedSiteLoaded=" + (level.hasChunkAt(expectedSupport) && level.hasChunkAt(expectedRail))
						+ ",expectedSupport=" + level.getBlockState(expectedSupport) + "/blockEntity="
						+ (level.getBlockEntity(expectedSupport) != null)
						+ ",expectedRail=" + level.getBlockState(expectedRail) + "/blockEntity="
						+ (level.getBlockEntity(expectedRail) != null) + "/fluidEmpty="
						+ level.getBlockState(expectedRail).getFluidState().isEmpty()
						+ ",expectedHeadroom=" + level.getBlockState(expectedHeadroom) + "/collisionEmpty="
						+ level.getBlockState(expectedHeadroom).getCollisionShape(level, expectedHeadroom).isEmpty()
						+ "/fluidEmpty=" + level.getBlockState(expectedHeadroom).getFluidState().isEmpty();
				}
			} catch (ReflectiveOperationException error) {
				site = "unavailable:" + error.getClass().getSimpleName();
				siteConditions = "unavailable:" + error.getClass().getSimpleName() + ",candidateLoaded=" + candidateLoaded;
			}
			if (cursor > 0) candidates.append("; ");
			BlockPos railPos = new BlockPos(point.getX(), home.bell().getY() + 1, point.getZ());
			BlockPos supportPos = railPos.below();
			candidates.append(cursor).append('@').append(point.toShortString()).append(" site=").append(site)
				.append(" checks={").append(siteConditions).append('}')
				.append(" blocks=").append(level.getBlockState(supportPos)).append(" -> ")
				.append(level.getBlockState(railPos));
		}
		return " [diagnostic: first={" + found + "}, home=" + home.bell().toShortString() + "/validBell=" + validHome
			+ ", peer=" + peer.bell().toShortString() + "/validBell=" + validPeer
			+ ", dimension=" + dimension + ", homeLinks={" + homeLinks + "}, peerLinks={" + peerLinks + "}"
			+ ", reciprocalRailLink=" + reciprocal + ", routeKey=" + routeKey + ", routeEndpoints="
			+ roads.get(routeKey + ":routeFrom") + ".." + roads.get(routeKey + ":routeTo")
			+ ", routeGeometry=" + roads.get(routeKey + ":routeGeometry") + ", routeCount="
			+ roads.get(routeKey + ":routeCount") + ", storedRoute=" + storedRoute
			+ ", routeLength=" + routeLength + " (passed=" + length + "), last=" + last + ", railKey=" + railKey
			+ ", savedRailCursor=" + savedRailCursor + ", built=" + built + ", station=" + station
			+ ", stationLoaded=" + (station != null && level.hasChunkAt(station))
			+ ", stationStart=" + (startValue == Integer.MIN_VALUE ? "unavailable" + stationStartError : startValue)
			+ ", startWithinBuilt=" + (startValue >= 0 && startValue < built) + ", candidates={" + candidates + "}]";
	}

	private static Map<BlockPos, Cell> capture(ServerLevel level, BlockPos at, int groundY) {
		Map<BlockPos, Cell> before = new LinkedHashMap<>();
		for (int x = at.getX() - 5; x <= at.getX() + 8; x++)
			for (int y = groundY - 3; y <= groundY + 7; y++)
				for (int z = at.getZ() - 5; z <= at.getZ() + 14; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					level.getChunkAt(pos);
					BlockEntity entity = level.getBlockEntity(pos);
					before.put(pos, new Cell(level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
				}
		return before;
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		for (BlockPos pos : before.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) level.setBlock(entry.getKey(), entry.getValue().state(), 3);
		for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) if (entry.getValue().data() != null) {
			BlockEntity entity = BlockEntity.loadStatic(entry.getKey(), entry.getValue().state(),
				entry.getValue().data().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		}
	}

	private static List<String> differences(ServerLevel level, Map<BlockPos, Cell> before, int limit) {
		List<String> result = new java.util.ArrayList<>();
		for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) {
			BlockEntity entity = level.getBlockEntity(entry.getKey());
			Cell current = new Cell(level.getBlockState(entry.getKey()), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
			if (!entry.getValue().equals(current)) {
				result.add(entry.getKey().toShortString() + " " + entry.getValue().state() + " -> " + current.state());
				if (result.size() >= limit) break;
			}
		}
		return result;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("Rail maintenance: " + message);
		System.out.println("VP090 PASS " + message);
	}
}
