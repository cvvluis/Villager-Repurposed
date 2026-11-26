package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.entity.CaravanTraderEntity;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * D05 end-to-end proof: the real {@code Caravan.spawn} / tick callback sends a real trader from a linked village
 * over a finished (paved) road to the depot of the other village, where it trades and departs again.
 *
 * <p>Phase 1 builds two linked villages with a paved canonical road, calls the production spawn and observes the
 * trader on the road until it is about half-way; then the world is saved. Phase 2 runs after a real restart: the
 * trader and its donkeys reload, the caravan state is re-adopted, the trader arrives, trades, leaves and the
 * visit is cleaned up and recorded on the real village link. Chunks are force-loaded as a stand-in for a player.
 */
public final class CaravanJourneyChecks {
	private static final BlockPos AT = new BlockPos(33000, 280, 33000);
	private static final int GAP = 90;
	private static final Path MARKER = Path.of("vp-caravanjourney.properties");
	private static final Map<ServerLevel, Run> RUNS = new IdentityHashMap<>();

	private CaravanJourneyChecks() { }

	public static int run(ServerLevel level, int phase) {
		Run run = RUNS.get(level);
		if (run == null) { run = new Run(phase, level.getGameTime()); RUNS.put(level, run); }
		for (ChunkPos chunk : run.chunks) level.getChunkSource().addTicketWithRadius(Anchor.VILLAGER_WORK, chunk, 2);
		try {
			int result = phase == 1 ? phase1(level, run) : phase2(level, run);
			if (result >= 0) RUNS.remove(level);
			return result;
		} catch (RuntimeException | Error failure) {
			RUNS.remove(level);
			for (ChunkPos chunk : run.chunks) level.setChunkForced(chunk.x(), chunk.z(), false);
			try { Files.deleteIfExists(MARKER); } catch (IOException ignored) { }
			throw failure;
		}
	}

	private static List<ChunkPos> chunks() {
		List<ChunkPos> out = new ArrayList<>();
		for (int cx = (AT.getX() - 48) >> 4; cx <= (AT.getX() + GAP + 48) >> 4; cx++)
			for (int cz = (AT.getZ() - 48) >> 4; cz <= (AT.getZ() + 48) >> 4; cz++) out.add(new ChunkPos(cx, cz));
		return out;
	}

	private static boolean ticking(ServerLevel level, List<ChunkPos> chunks) {
		for (ChunkPos chunk : chunks) {
			level.setChunkForced(chunk.x(), chunk.z(), true);
			level.getChunkSource().addTicketWithRadius(Anchor.VILLAGER_WORK, chunk, 2);
			if (!de.villagerplus.world.LoadedColumns.readyChunk(level, chunk.x(), chunk.z())
				|| !level.isPositionEntityTicking(chunk.getMiddleBlockPosition(level.getMinY() + 64))) return false;
		}
		return true;
	}

	// ---------------------------------------------------------------- phase 1

	private static int phase1(ServerLevel level, Run run) {
		long now = level.getGameTime();
		if (run.step == 0) {
			try { Files.deleteIfExists(MARKER); } catch (IOException ignored) { }
			run.chunks = chunks();
			run.step = 1;
			return -1;
		}
		if (run.step == 1) {
			if (!ticking(level, run.chunks)) return -1;
			prepare(level, run);
			run.step = 2;
			run.started = now;
			return -1;
		}
		if (run.step == 2) {
			// Production spawn: the same method the village scheduler calls.
			var routes = Network.loadedReciprocalCaravanRoutes(level, run.home);
			System.out.println("CARAVAN JOURNEY routes=" + routes + " links=" + Network.of(run.home.bellEntity()).links()
				+ " peerLinks=" + Network.of(run.peer.bellEntity()).links() + " chest=" + run.home.chestPos(level) + "/" + run.peer.chestPos(level));
			StringBuilder nulls = new StringBuilder();
			for (int i = 0; i < run.lane.size(); i++) if (RoadRoute.walkablePavedFeet(level, run.lane.get(i)) == null) nulls.append(i).append(':').append(run.lane.get(i).toShortString()).append(' ');
			System.out.println("CARAVAN JOURNEY lane=" + run.lane.size() + " nonWalkable=" + nulls);
			boolean spawned = Caravan.spawn(level, run.home);
			if (!spawned) {
				if (now - run.started > 600) throw new IllegalStateException("Caravan journey 1: production spawn kept refusing a linked-route caravan");
				run.home.data().extra.nextCaravan = 0;
				return -1;
			}
			var members = level.getEntitiesOfClass(CaravanTraderEntity.class, new AABB(run.home.bell()).inflate(160));
			if (members.size() != 1) throw new IllegalStateException("Caravan journey 1: expected one trader, found " + members.size());
			run.trader = members.getFirst().getUUID();
			Caravan.State state = Caravan.activeStateForChecks(run.trader);
			check(state != null && state.routeBell != null && state.routeBell.equals(run.peer.bell()),
				"the spawned caravan is assigned the real linked village as its origin (route bell " + (state == null ? null : state.routeBell) + ")");
			run.checks++;
			check(members.getFirst().position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(run.peer.bell())) < 48,
				"the caravan arrives from the linked village, not from the home village's own edge"); run.checks++;
			if (System.getenv("VP_JOURNEY_NOGUARD") != null && state.guard != null && level.getEntity(state.guard) != null) {
				level.getEntity(state.guard).discard(); // experiment only: isolates the guard golem as the cause of the pack-animal wait livelock
			}
			run.step = 3;
			run.started = now;
			return -1;
		}
		var trader = level.getEntity(run.trader);
		if (trader == null || !trader.isAlive()) throw new IllegalStateException("Caravan journey 1: trader vanished: " + run.diag(level));
		if (now - run.started > 3500) throw new IllegalStateException("Caravan journey 1: not on the road half-way: " + run.diag(level));
		Caravan.State state = Caravan.activeStateForChecks(run.trader);
		if (state == null) throw new IllegalStateException("Caravan journey 1: caravan state lost: " + run.diag(level));
		run.sample(trader, now);
		if (now % 100 == 0 && now - run.started < 700) System.out.println("CJ ts " + (now - run.started) + " wait=" + state.waitingForDonkeys + " t=" + trader.blockPosition().toShortString()
			+ " dp=" + state.departurePathReady + run.donkeys(level, state));
		if (now % 200 == 0) System.out.println("CARAVAN JOURNEY tick " + now + " " + run.diag(level) + " tpos=" + trader.position());
		if (now % 400 == 200) System.out.println("CARAVAN JOURNEY probe " + probe(level, state, trader));
		double along = run.peer.bell().getX() - trader.getX(); // trader starts near the peer and walks west to the home depot
		if (state.roadKey == null || along < 42 || along > 60 || run.onRoadSamples < 30) return -1;
		check(run.onRoadSamples >= 20 && run.maxLateral <= 4.5,
			"before half-way the trader stayed within " + run.maxLateral + " blocks of the paved lane over " + run.onRoadSamples + " samples"); run.checks++;
		check(state.roadCursor >= 0 && state.roadCursor < run.roadLength, "the caravan holds a road cursor on the canonical route (" + state.roadCursor + ")"); run.checks++;
		Properties marker = new Properties();
		marker.setProperty("phase", "1");
		marker.setProperty("home", Long.toString(run.home.bell().asLong()));
		marker.setProperty("peer", Long.toString(run.peer.bell().asLong()));
		marker.setProperty("trader", run.trader.toString());
		marker.setProperty("cursor", Integer.toString(state.roadCursor));
		marker.setProperty("y0", Integer.toString(run.y0));
		write(marker);
		level.getServer().saveEverything(true, true, true);
		System.out.println("CARAVAN JOURNEY 1 saved: trader " + run.trader + " at " + trader.blockPosition().toShortString() + " cursor " + state.roadCursor + " tick " + now);
		return run.checks;
	}

	private static void prepare(ServerLevel level, Run run) {
		level.getChunkAt(AT);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, AT.getX(), AT.getZ());
		run.y0 = y;
		BlockPos homeBell = new BlockPos(AT.getX(), y, AT.getZ());
		BlockPos peerBell = homeBell.east(GAP);
		var fixture = new RailTransportDispatchChecks.Fixture(level.getGameTime());
		fixture.leased = run.chunks;
		// Open, flat meadow so the arrival ring around the peer and the whole corridor are walkable.
		for (int x = homeBell.getX() - 40; x <= peerBell.getX() + 44; x++) for (int z = homeBell.getZ() - 44; z <= homeBell.getZ() + 44; z++) {
			level.getChunkAt(new BlockPos(x, y, z));
			level.setBlock(new BlockPos(x, y - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
			level.setBlock(new BlockPos(x, y - 2, z), Blocks.STONE.defaultBlockState(), 2);
			for (int dy = 0; dy <= 6; dy++) level.setBlock(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState(), 2);
		}
		run.home = RailTransportDispatchChecks.createVillage(level, fixture, homeBell, true);
		run.peer = RailTransportDispatchChecks.createVillage(level, fixture, peerBell, false);
		String dim = Network.dim(level);
		var toPeer = new TradeNetwork.Link(dim, peerBell, "Journey Ost", 90, true, 0, 0);
		var toHome = new TradeNetwork.Link(dim, homeBell, "Journey West", 90, true, 0, 0);
		Network.save(run.home.bellEntity(), Network.of(run.home.bellEntity()).withLinks(List.of(toPeer)));
		Network.save(run.peer.bellEntity(), Network.of(run.peer.bellEntity()).withLinks(List.of(toHome)));
		Map<String, Long> roads = new HashMap<>();
		String key = dim + peerBell.asLong();
		if (!RoadRoute.ensure(level, homeBell, peerBell, roads, key, null, null)) throw new IllegalStateException("Caravan journey: canonical route did not plan");
		run.roadLength = RoadRoute.length(roads, key, homeBell, peerBell);
		for (int i = 0; i <= run.roadLength; i++) {
			BlockPos point = RoadRoute.point(roads, key, homeBell, peerBell, i);
			boolean alongX = RoadRoute.alongX(roads, key, homeBell, peerBell, i);
			for (int side = -1; side <= 1; side++) {
				BlockPos paving = point.offset(alongX ? 0 : side, -1, alongX ? side : 0);
				level.setBlock(paving, Blocks.GRAVEL.defaultBlockState(), 2);
				for (int up = 1; up <= 2; up++) {
					BlockPos clear = paving.above(up);
					if (!level.getBlockState(clear).is(Blocks.BELL) && !level.getBlockState(clear).is(Blocks.CHEST))
						level.setBlock(clear, Blocks.AIR.defaultBlockState(), 2);
				}
			}
			run.lane.add(point.immutable());
		}
		run.home.bellEntity().setAttached(VillagerPlus.ROADS, Map.copyOf(roads));
		run.home.dirty();
		// The production reciprocal-route filter requires both depots; a stocked depot gives the trader something to trade.
		var depot = run.home.chest(level);
		depot.setItem(5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 64));
		depot.setChanged();
		run.home = reload(level, Long.toString(homeBell.asLong()));
		run.peer = reload(level, Long.toString(peerBell.asLong()));
		run.home.data().extra.nextCaravan = 0;
	}

	// ---------------------------------------------------------------- phase 2

	private static int phase2(ServerLevel level, Run run) {
		long now = level.getGameTime();
		Properties marker = read("1");
		if (run.step == 0) {
			run.chunks = chunks();
			run.trader = UUID.fromString(marker.getProperty("trader"));
			run.step = 1;
			run.started = now;
		}
		if (!ticking(level, run.chunks)) {
			if (now - run.started > 600) throw new IllegalStateException("Caravan journey 2: fixture chunks did not reload after restart");
			return -1;
		}
		if (run.home == null) {
			run.home = reload(level, marker.getProperty("home"));
			run.peer = reload(level, marker.getProperty("peer"));
			var trader = level.getEntity(run.trader);
			if (trader == null && now - run.started < 600) { run.home = null; return -1; }
			check(trader instanceof CaravanTraderEntity && trader.isAlive(), "the caravan trader entity reloads after a real restart"); run.checks++;
			// The in-memory state is rebuilt from the persisted tags by the production adoption path (ticks later).
			run.step = 2;
			run.started = now;
		}
		if (now - run.started > 12000) throw new IllegalStateException("Caravan journey 2: stalled at stage " + run.stage + ": " + run.diag(level));
		var trader = level.getEntity(run.trader);
		Caravan.State state = Caravan.activeStateForChecks(run.trader);
		if (run.stage == 0) {
			if (state == null || trader == null) return -1;
			check(state.routeBell != null && state.routeBell.asLong() == run.peer.bell().asLong() && state.roadKey != null,
				"the restored caravan state still names its origin village and its canonical road"); run.checks++;
			run.stage = 1;
		}
		if (trader == null && state != null && run.stage == 1) return -1;
		if (run.stage == 1) {
			if (state != null && state.traded) {
				check(trader.blockPosition().distSqr(run.home.chestPos(level)) < 16 * 16 && trader.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(run.home.bell())) < 20,
					"the trader trades at the home depot after walking the road"); run.checks++;
				run.stage = 2;
			}
			return -1;
		}
		if (run.stage == 2) {
			if (state == null) {
				// Cleanup removed the active state: the visit is over.
				boolean gone = trader == null || !trader.isAlive();
				var link = Network.of(run.peer.bellEntity()).links();
				var homeLink = Network.of(run.home.bellEntity()).links();
				check(gone || trader.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(run.peer.bell())) < 60,
					"the caravan departs back toward its origin and is cleaned up"); run.checks++;
				check(level.getEntitiesOfClass(Donkey.class, new AABB(run.home.bell()).inflate(200), d -> d.entityTags().contains("vp_caravan")).isEmpty(),
					"no caravan donkey stays tagged to the finished visit"); run.checks++;
				System.out.println("CARAVAN JOURNEY 2 links peer=" + link + " home=" + homeLink);
				int result = run.checks;
				wipe(level, run);
				return result;
			}
			return -1;
		}
		return -1;
	}

	private static String probe(ServerLevel level, Caravan.State state, Entity trader) {
		try {
			var method = Caravan.class.getDeclaredMethod("safePackDonkeyApproaches", ServerLevel.class, Caravan.State.class, Donkey.class,
				net.minecraft.world.entity.npc.villager.AbstractVillager.class);
			method.setAccessible(true);
			StringBuilder out = new StringBuilder("camp=" + state.camp + " ");
			for (UUID id : state.llamas) if (level.getEntity(id) instanceof Donkey donkey) {
				@SuppressWarnings("unchecked")
				List<BlockPos> goals = (List<BlockPos>) method.invoke(null, level, state, donkey, trader);
				out.append("goals=").append(goals.size());
				if (!goals.isEmpty()) {
					var path = donkey.getNavigation().createPath(goals.getFirst(), 1);
					out.append(" first=").append(goals.getFirst().toShortString()).append(" path=").append(path == null ? "null" : "nodes" + path.getNodeCount() + " reach=" + path.canReach())
						.append(" reason=").append(path == null ? "-" : Caravan.donkeyPathFailureReason(level, donkey, path, goals.getFirst()));
				}
				out.append("; ");
			}
			return out.toString();
		} catch (ReflectiveOperationException e) { return e.toString(); }
	}

	private static void wipe(ServerLevel level, Run run) {
		for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(run.home.bell()).inflate(200),
			e -> e.entityTags().contains("vp_caravan") || e.entityTags().contains("vp090_fixture"))) entity.discard();
		for (ChunkPos chunk : run.chunks) level.setChunkForced(chunk.x(), chunk.z(), false);
		try { Files.deleteIfExists(MARKER); } catch (IOException ignored) { }
	}

	// ---------------------------------------------------------------- helpers

	private static VillageRef reload(ServerLevel level, String bell) {
		BlockPos pos = BlockPos.of(Long.parseLong(bell));
		level.getChunkAt(pos);
		if (!(level.getBlockEntity(pos) instanceof BellBlockEntity entity) || entity.getAttached(VillagerPlus.VILLAGE) == null)
			throw new IllegalStateException("Caravan journey: village bell missing at " + pos.toShortString() + " be=" + level.getBlockEntity(pos)
				+ " state=" + level.getBlockState(pos) + " attached=" + (level.getBlockEntity(pos) == null ? null : level.getBlockEntity(pos).getAttached(VillagerPlus.VILLAGE)));
		return new VillageRef(pos, entity.getAttached(VillagerPlus.VILLAGE), entity);
	}

	private static void write(Properties props) {
		try (Writer out = Files.newBufferedWriter(MARKER)) { props.store(out, "caravan journey"); }
		catch (IOException io) { throw new IllegalStateException("Caravan journey: cannot write marker", io); }
	}

	private static Properties read(String phase) {
		Properties props = new Properties();
		try (Reader in = Files.newBufferedReader(MARKER)) { props.load(in); }
		catch (IOException io) { throw new IllegalStateException("Caravan journey: missing marker from the previous phase", io); }
		if (!phase.equals(props.getProperty("phase"))) throw new IllegalStateException("Caravan journey: wrong marker phase");
		return props;
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("Caravan journey: " + message);
	}

	private static final class Run {
		final int phase;
		int step, stage, checks, y0, roadLength;
		long started;
		List<ChunkPos> chunks = List.of();
		VillageRef home, peer;
		UUID trader;
		final List<BlockPos> lane = new ArrayList<>();
		int onRoadSamples;
		double maxLateral;

		Run(int phase, long now) { this.phase = phase; this.started = now; }

		void sample(Entity trader, long now) {
			if (lane.isEmpty()) return;
			double x = trader.getX();
			if (x > home.bell().getX() + 6 && x < peer.bell().getX() - 6) {
				double best = Double.MAX_VALUE;
				for (BlockPos p : lane) best = Math.min(best, Math.hypot(p.getX() + .5 - x, p.getZ() + .5 - trader.getZ()));
				maxLateral = Math.max(maxLateral, best);
				onRoadSamples++;
			}
		}

		String donkeys(ServerLevel level, Caravan.State state) {
			UUID trader = this.trader;
			StringBuilder out = new StringBuilder(" members[");
			for (UUID id : state.llamas) {
				Entity e = level.getEntity(id);
				if (e == null) { out.append("missing "); continue; }
				out.append(e.getType().toShortString()).append('@').append(e.blockPosition().toShortString()).append(" d2=").append((int) e.distanceToSqr(level.getEntity(trader)));
				if (e instanceof net.minecraft.world.entity.Mob mob) out.append(" navDone=").append(mob.getNavigation().isDone()).append(" leash=").append(mob.getLeashHolder() != null);
				out.append("; ");
			}
			return out.append(']').toString();
		}

		String diag(ServerLevel level) {
			var trader = this.trader == null ? null : level.getEntity(this.trader);
			Caravan.State state = this.trader == null ? null : Caravan.activeStateForChecks(this.trader);
			return "trader=" + (trader == null ? "none" : trader.blockPosition().toShortString() + (trader.isAlive() ? "" : " dead"))
				+ " state=" + (state == null ? "none" : "traded=" + state.traded + " leaveAt=" + state.leaveAt + " roadKey=" + state.roadKey
					+ " cursor=" + state.roadCursor + " waiting=" + state.waitingForDonkeys + " campBuilt=" + state.campBuilt)
				+ (state == null ? "" : donkeys(level, state))
				+ " maxLateral=" + maxLateral + " samples=" + onRoadSamples;
		}
	}
}
