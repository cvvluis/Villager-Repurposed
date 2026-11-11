package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import de.villagerplus.job.JobUtil;

/**
 * Dorf-Erkennung ueber die Glocke (Meeting Point) der Dorfbewohner. Das funktioniert fuer jedes Dorf, egal ob
 * Vanilla oder aus einem Weltgenerator-Mod. Fehlt die Dorfmitte, wird sie nachtraeglich eingefuegt.
 */
public final class Villages {
	private static final Map<Long, Long> NEXT_CENTER_ATTEMPT = new HashMap<>();
	private static final Map<ServerLevel, VillagerIndexSnapshot> RESIDENT_INDEX = new IdentityHashMap<>();

	record VillagerIndexSnapshot(long gameTime, List<Villager> villagers) { }

	static {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(Villages::invalidateResidentIndex);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register(Villages::invalidateResidentIndex);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> RESIDENT_INDEX.clear());
	}

	private Villages() {
	}

	/** Zwischenspeicher: das Dorf eines Villagers aendert sich selten, die Suche lief vorher bei jedem Takt. */
	private record CachedVillage(VillageRef ref, long until) { }

	private static final Map<java.util.UUID, CachedVillage> CACHE = new HashMap<>();

	public static void forget(java.util.UUID id) {
		CACHE.remove(id);
	}

	/** Clears cached village lookups between isolated dev fixtures. */
	public static void resetAssignmentCache() {
		CACHE.clear();
	}

	/** Assigns meeting point (and optional home) so {@link #residents} and {@link #find} agree in dev checks. */
	public static void bindResident(ServerLevel level, VillageRef ref, Villager villager, BlockPos home) {
		GlobalPos bell = GlobalPos.of(level.dimension(), ref.bell());
		villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, bell);
		if (home != null) {
			villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), home));
		}
		level.getChunkAt(ref.bell());
		noteChunk(level.getChunkAt(ref.bell()));
		villager.addTag("vp_resident_bell:" + ref.bell().asLong());
		CACHE.put(villager.getUUID(), new CachedVillage(ref, level.getGameTime() + 400L));
		// bindResident can change membership after a same-tick resident snapshot was built (notably in live fixtures).
		RESIDENT_INDEX.remove(level);
	}

	public static VillageRef find(ServerLevel level, Villager v) {
		long now = level.getGameTime();
		CachedVillage cached = CACHE.get(v.getUUID());
		if (cached != null && now < cached.until() && !cached.ref().bellEntity().isRemoved()) {
			Optional<GlobalPos> meeting = v.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
			if (meeting.isEmpty() || (meeting.get().dimension() == level.dimension() && meeting.get().pos().equals(cached.ref().bell()))) {
				return cached.ref();
			}
			// Meeting points may change when vanilla reassigns a villager; do not keep it in the old village for the cache TTL.
			CACHE.remove(v.getUUID());
		}
		VillageRef found = lookup(level, v);
		if (found != null) {
			CACHE.put(v.getUUID(), new CachedVillage(found, now + 100L));
		} else {
			CACHE.remove(v.getUUID());
		}
		return found;
	}

	private static VillageRef lookup(ServerLevel level, Villager v) {
		Optional<GlobalPos> meeting = v.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
		if (meeting.isPresent() && meeting.get().dimension() == level.dimension()) {
			BlockPos pos = meeting.get().pos();
			if (level.hasChunkAt(pos)) {
				BlockEntity be = level.getBlockEntity(pos);
				if (be instanceof net.minecraft.world.level.block.entity.BellBlockEntity) {
					bells(level.dimension()).add(pos.asLong());
					return new VillageRef(pos, be.getAttachedOrCreate(VillagerPlus.VILLAGE), be);
				}
			}
			return null;
		}
		// Rueckfall: noch kein Treffpunkt (neuer Villager) -> naechste bekannte Glocke in der Naehe verwenden.
		BlockPos best = null;
		double bestDist = de.villagerplus.Config.VILLAGE_RADIUS * (double)de.villagerplus.Config.VILLAGE_RADIUS;
		java.util.Iterator<Long> known = bells(level.dimension()).iterator();
		while (known.hasNext()) {
			long l = known.next();
			BlockPos p = BlockPos.of(l);
			double d = p.distSqr(v.blockPosition());
			if (d < bestDist && level.hasChunkAt(p)) {
				BlockEntity candidate = level.getBlockEntity(p);
				if (candidate instanceof net.minecraft.world.level.block.entity.BellBlockEntity) {
					best = p;
					bestDist = d;
				} else {
					// A restored/removed bell must not hide a farther valid village from unassigned residents.
					known.remove();
				}
			}
		}
		if (best == null) {
			return null;
		}
		BlockEntity be = level.getBlockEntity(best);
		return be == null ? null : new VillageRef(best, be.getAttachedOrCreate(VillagerPlus.VILLAGE), be);
	}

	/** Residents assigned to this bell by village memory, excluding overlapping neighboring settlements. */
	public static java.util.List<Villager> residents(ServerLevel level, VillageRef ref, int range, java.util.function.Predicate<Villager> filter) {
		java.util.List<Villager> out = new java.util.ArrayList<>();
		for (Villager v : residentIndex(level).villagers()) {
			if (!v.isAlive() || !filter.test(v)) continue;
			Optional<GlobalPos> meeting = v.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
			if (meeting.isPresent() && meeting.get().dimension() == level.dimension()) {
				if (meeting.get().pos().equals(ref.bell())) out.add(v);
				continue;
			}
			if (v.blockPosition().distSqr(ref.bell()) > (long) range * range) continue;
			VillageRef assigned = find(level, v);
			if (assigned != null && assigned.bell().equals(ref.bell())) out.add(v);
		}
		return out;
	}

	/** Per-tick entity-kind index; membership is reevaluated by {@link #residents} on every lookup. */
	static VillagerIndexSnapshot residentIndex(ServerLevel level) {
		long now = level.getGameTime();
		VillagerIndexSnapshot cached = RESIDENT_INDEX.get(level);
		if (cached != null && cached.gameTime() == now) return cached;

		List<Villager> villagers = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof Villager villager) villagers.add(villager);
		}
		VillagerIndexSnapshot snapshot = new VillagerIndexSnapshot(now, List.copyOf(villagers));
		RESIDENT_INDEX.put(level, snapshot);
		return snapshot;
	}

	private static void invalidateResidentIndex(Entity entity, ServerLevel level) {
		if (entity instanceof Villager) RESIDENT_INDEX.remove(level);
	}

	private static final java.util.Map<Long, Long> SWEPT = new java.util.HashMap<>();
	/** Bekannte Glocken je Dimension (Dorfidentitaet ist dimensionsqualifiziert). */
	private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, java.util.Set<Long>> KNOWN_BELLS = new java.util.HashMap<>();

	private static java.util.Set<Long> bells(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
		return KNOWN_BELLS.computeIfAbsent(dim, k -> new java.util.HashSet<>());
	}

	/** Naechstes Dorf (Glocke) im Umkreis von {@code range} Bloecken um {@code pos}; null, wenn keins da ist. */
	public static VillageRef villageNear(ServerLevel level, BlockPos pos, int range) {
		BlockPos best = null;
		double bestDist = (double) range * range;
		for (long l : bells(level.dimension())) {
			BlockPos p = BlockPos.of(l);
			double d = p.distSqr(pos);
			if (d < bestDist && level.hasChunkAt(p) && level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.BellBlockEntity) {
				best = p;
				bestDist = d;
			}
		}
		if (best == null) {
			return null;
		}
		BlockEntity be = level.getBlockEntity(best);
		return be == null ? null : new VillageRef(best, be.getAttachedOrCreate(VillagerPlus.VILLAGE), be);
	}

	/** Merkt sich die Glocken eines geladenen Chunks - damit auch Villager ohne Treffpunkt-Gedaechtnis (z. B. frisch geladen) ihr Dorf finden. */
	public static void noteChunk(net.minecraft.world.level.chunk.LevelChunk chunk) {
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (be instanceof net.minecraft.world.level.block.entity.BellBlockEntity) {
				bells(chunk.getLevel().dimension()).add(be.getBlockPos().asLong());
			}
		}
	}

	/**
	 * Loaded, still-valid bells previously observed by the normal chunk index, ordered by horizontal distance.
	 * This is a read view over the existing index; it does not load chunks or create village identities.
	 */
	public static List<BlockPos> knownBellsNear(ServerLevel level, BlockPos center, int range) {
		if (level == null || center == null || range < 0) return List.of();
		long rangeSquared = (long)range * range;
		java.util.Set<Long> activeVillageMeetingPoints = new java.util.HashSet<>();
		for (Villager villager : residentIndex(level).villagers()) {
			if (!villager.isAlive() || Guests.isGuest(villager)) continue;
			Optional<GlobalPos> meeting = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
			if (meeting.isPresent() && meeting.get().dimension().equals(level.dimension())) {
				BlockPos meetingPos = meeting.get().pos();
				if (level.hasChunkAt(meetingPos) && level.getBlockEntity(meetingPos) instanceof BellBlockEntity) {
					activeVillageMeetingPoints.add(meetingPos.asLong());
				}
				continue;
			}
			// Match residents()' existing fallback for newly loaded villagers without a
			// same-dimension meeting point: their nearest loaded village bell still counts.
			long residentRange = (long)range + de.villagerplus.Config.VILLAGE_RADIUS;
			if (horizontalDistanceSquared(villager.blockPosition(), center) > residentRange * residentRange) continue;
			VillageRef assigned = find(level, villager);
			if (assigned != null && level.hasChunkAt(assigned.bell())
				&& level.getBlockEntity(assigned.bell()) instanceof BellBlockEntity) {
				activeVillageMeetingPoints.add(assigned.bell().asLong());
			}
		}
		List<BlockPos> nearby = new ArrayList<>();
		java.util.Iterator<Long> known = bells(level.dimension()).iterator();
		while (known.hasNext()) {
			long packed = known.next();
			BlockPos position = BlockPos.of(packed);
			if (horizontalDistanceSquared(position, center) > rangeSquared || position.equals(center) || !level.hasChunkAt(position)) continue;
			if (level.getBlockEntity(position) instanceof BellBlockEntity) {
				if (activeVillageMeetingPoints.contains(packed)) nearby.add(position.immutable());
			} else {
				// A loaded chunk proves this remembered bell is stale; unloaded entries remain harmless and lazy.
				known.remove();
			}
		}
		nearby.sort(Comparator.comparingLong((BlockPos position) -> horizontalDistanceSquared(position, center))
			.thenComparingLong(BlockPos::asLong));
		return List.copyOf(nearby);
	}

	private static long horizontalDistanceSquared(BlockPos a, BlockPos b) {
		long dx = (long)a.getX() - b.getX(), dz = (long)a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	/** Zu Beginn der Arbeitszeit laeutet die Dorfglocke einmal pro Tag den Arbeitsbeginn ein. */
	private static void morningBell(ServerLevel level, VillageRef ref) {
		long tod = Math.floorMod(level.getOverworldClockTime(), 24000L);
		long day = Math.floorDiv(level.getOverworldClockTime(), 24000L);
		if (tod >= de.villagerplus.Config.MORNING_END && tod < de.villagerplus.Config.MORNING_END + 600) {
			ringMorningBell(level, ref, day);
		}
	}

	/** Rings and animates the actual village bell once per day; stale village references stay silent. */
	public static boolean ringMorningBell(ServerLevel level, VillageRef ref, long day) {
		if (!level.getBlockState(ref.bell()).is(Blocks.BELL)
			|| !(ref.bellEntity() instanceof BellBlockEntity bell)
			|| level.getBlockEntity(ref.bell()) != bell) return false;
		if (!claimMorningBell(ref, day)) return false;
		bell.onHit(Direction.UP);
		level.playSound(null, ref.bell(), net.minecraft.sounds.SoundEvents.BELL_BLOCK,
			net.minecraft.sounds.SoundSource.BLOCKS, 2.0F, 1.0F);
		ref.dirty();
		return true;
	}

	/** Claim the daily cue before playing it, so repeated villager ticks and server restarts cannot duplicate it. */
	public static boolean claimMorningBell(VillageRef ref, long day) {
		long previous = ref.bellEntity().getAttachedOrElse(VillagerPlus.LAST_MORNING_BELL, Long.MIN_VALUE);
		if (previous == day) return false;
		ref.bellEntity().setAttached(VillagerPlus.LAST_MORNING_BELL, day);
		return true;
	}

	/** Generated residents without a meeting point belong to the nearest loaded bell. */
	public static void adoptUnassigned(ServerLevel level, VillageRef ref) {
		for (Villager villager : level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(ref.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS), Villager::isAlive)) {
			var meeting = villager.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT);
			if (meeting.isPresent()) continue;
			villager.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT,
				net.minecraft.core.GlobalPos.of(level.dimension(), ref.bell()));
		}
	}

	/** Stellt sicher, dass das Dorf eine Dorfmitte mit Doppelkiste hat. */
	public static void ensureCenter(ServerLevel level, VillageRef ref, Villager who, long now) {
		adoptUnassigned(level, ref);
		Plaza.ensure(level, ref, who, now);
		Plaza.refreshShrine(level, ref, who);
		morningBell(level, ref);
		Market.plan(level, ref, now);
		Roads.tick(level,ref,now);
		Journal.sample(level,ref,level.getEntitiesOfClass(Villager.class,new net.minecraft.world.phys.AABB(ref.bell()).inflate(de.villagerplus.Config.VILLAGE_RADIUS),v->v.isAlive()&&!Guests.isGuest(v)),now);
		BlockPos existing = ref.chestPos(level);
		if (existing != null) {
			fixPair(level, existing);
		}
		if (now >= SWEPT.getOrDefault(ref.bell().asLong(), 0L)) {
			SWEPT.put(ref.bell().asLong(), now + 24000L);
			int n = de.villagerplus.job.Mining.sweepLegacyMarkers(level, ref.bell());
			de.villagerplus.job.Mining.tidyOldMines(level, ref);
			{
				org.slf4j.LoggerFactory.getLogger("villagerplus").info("Villagers: Repurposed: {} alte Schilder (Mine/Tafel) aufgeraeumt bei {}", n, ref.bell().toShortString());
			}
		}
		if (ref.chestPos(level) != null) {
			compactWheat(level, ref);
			Board.ensure(level, ref);
			return;
		}
		if (ref.data().chest.isPresent()) {
			ref.data().chest = Optional.empty();
			ref.dirty();
		}
		Long next = NEXT_CENTER_ATTEMPT.get(ref.bell().asLong());
		if (next != null && now < next) {
			return;
		}
		NEXT_CENTER_ATTEMPT.put(ref.bell().asLong(), now + 1200L);

		BlockPos adopted = findExistingDoubleChest(level, ref.bell());
		if (adopted != null) {
			ref.data().chest = Optional.of(adopted);
			ref.dirty();
			return;
		}
		if (placeCenter(level, ref, who)) {
			JobUtil.tellNearbyPlayers(level, who, 40, Component.literal("Die Dorfbewohner haben eine Dorfmitte mit Dorfkiste angelegt."));
		}
	}

	/**
	 * Aeltere Welten: Die Dorfkiste wurde mit vertauschten Haelften gesetzt (sah aus wie eine Doppelkiste, war aber nur eine halbe: die rechte
	 * Haelfte verbindet sich gegen den Uhrzeigersinn). Ein vertauschtes Paar wird hier richtiggestellt, der Inhalt bleibt erhalten.
	 */
	private static void fixPair(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (!(s.getBlock() instanceof ChestBlock) || s.getValue(ChestBlock.TYPE) != ChestType.RIGHT) {
			return;
		}
		Direction facing = s.getValue(ChestBlock.FACING);
		BlockPos other = pos.relative(facing.getClockWise());
		BlockState o = level.getBlockState(other);
		if (o.getBlock() instanceof ChestBlock && o.getValue(ChestBlock.TYPE) == ChestType.LEFT && o.getValue(ChestBlock.FACING) == facing) {
			level.setBlock(pos, s.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
			level.setBlock(other, o.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
		}
	}

	/**
	 * Weizen platzsparend lagern: Ueber 64 lose Weizen hinaus wird jeweils 9 zu einem Heuballen gepresst; fehlt loses Weizen
	 * (unter 16), wird ein Ballen wieder aufgeschnuert. So braucht die Ernte wenig Platz und ist trotzdem verfuegbar.
	 */
	public static void compactWheat(ServerLevel level, VillageRef ref) {
		net.minecraft.world.Container box = ref.chest(level);
		if (box == null) {
			return;
		}
		int wheat = JobUtil.count(box, s -> s.is(net.minecraft.world.item.Items.WHEAT));
		int bales = JobUtil.count(box, s -> s.is(net.minecraft.world.item.Items.HAY_BLOCK));
		if (wheat >= 64 + 9) {
			int n = (wheat - 64) / 9;
			JobUtil.take(box, s -> s.is(net.minecraft.world.item.Items.WHEAT), n * 9);
			JobUtil.depositTo(box, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.HAY_BLOCK, n));
		} else if (wheat < 16 && bales > 0) {
			JobUtil.take(box, s -> s.is(net.minecraft.world.item.Items.HAY_BLOCK), 1);
			JobUtil.depositTo(box, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 9));
		}
	}

	private static BlockPos findExistingDoubleChest(ServerLevel level, BlockPos bell) {
		for (BlockPos p : BlockPos.betweenClosed(bell.offset(-12, -4, -12), bell.offset(12, 6, 12))) {
			if (!level.hasChunkAt(p)) continue;
			BlockState s = level.getBlockState(p);
			if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
				// Kisten mit Beute-Tabelle (Dorf-Loot, z.B. Overhauled Villages) gehoeren nicht den Villagern.
				BlockEntity be = level.getBlockEntity(p);
				if (be instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r && r.getLootTable() != null) {
					continue;
				}
				// Eine private Doppelkiste darf auch dann nicht als Dorfmitte registriert werden,
				// wenn sie im Suchradius neben einer Glocke steht.
				if (Lager.publicContainerAt(level, p) == null) continue;
				return p.immutable();
			}
		}
		return null;
	}

	private static boolean placeCenter(ServerLevel level, VillageRef ref, Villager who) {
		// CTOV can place the bell at an edge of the generated settlement. Anchor the
		// shared chest (and therefore its board) to the measured village midpoint.
		BlockPos villageCenter = Development.center(level, ref);
		BlockPos c = Sites.find(level, villageCenter, who.getRandom(), 3, 16, 2, 4, 160);
		if (c == null) {
			// Huegeliges oder aufgelockert gebautes Dorf: leicht unebenes Gelaende ebnen.
			// Groesserer Radius/mehr Stichproben hilft bei CTOV, ohne Chunks zu erzwingen.
			BlockPos anchor = Sites.findTolerant(level, villageCenter, who.getRandom(), 3, 32, -2, 2, -2, 2, 4, 320);
			if (anchor == null) {
				// In dicht bebauten CTOV-Ortskernen existiert manchmal kein freier 5x5-Platz.
				// Eine kompakte, nicht invasive Starter-Dorfkiste laesst Bau und Wirtschaft starten;
				// die eigenstaendige Suche nach einer vollwertigen Plaza laeuft weiter.
				if (placeCompactCenter(level, ref, villageCenter, who)) {
					org.slf4j.LoggerFactory.getLogger("villagerplus").info(
						"Villagers: Repurposed: kompakte Starter-Dorfkiste bei {} errichtet (Plaza folgt bei freier Flaeche)",
						ref.data().chest.orElse(ref.bell()).toShortString());
					JobUtil.tellNearbyPlayers(level, who, 40, Component.literal("Die Dorfbewohner haben eine Starter-Dorfkiste angelegt; der Dorfplatz folgt."));
					return true;
				}
				org.slf4j.LoggerFactory.getLogger("villagerplus").warn(
					"Villagers: Repurposed: kein sicherer Platz fuer Dorfmitte bei {}; neuer Versuch in 1200 Ticks",
					ref.bell().toShortString());
				return false;
			}
			java.util.List<de.villagerplus.build.Step> corners = java.util.List.of(
				de.villagerplus.build.Step.free(anchor.offset(-2, 0, -2), Blocks.AIR.defaultBlockState()),
				de.villagerplus.build.Step.free(anchor.offset(2, 0, 2), Blocks.AIR.defaultBlockState()));
			for (int i = 0; i < 20 && !de.villagerplus.build.Prep.run(level, anchor, corners, 64, null); i++) {
				// einebnen, bis nichts mehr zu tun ist
			}
			c = anchor;
		}
		VillageStyle style = VillageStyle.detect(level, ref.bell(), who);
		Direction face = Sites.facingTowards(c, ref.bell());
		Direction perp = face.getClockWise();

		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				level.setBlock(c.offset(dx, -1, dz), style.floor().defaultBlockState(), 3);
			}
		}
		BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face);
		BlockPos a = c;
		BlockPos b = c.relative(perp);
		// Doppelkiste: Die LINKE Haelfte verbindet sich mit dem Nachbarn im Uhrzeigersinn (facing.getClockWise), die rechte mit dem Gegenstueck.
		level.setBlock(a, chest.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
		level.setBlock(b, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
		for (int sx : new int[] {-2, 2}) {
			for (int sz : new int[] {-2, 2}) {
				BlockPos post = c.offset(sx, 0, sz);
				level.setBlock(post, style.fence().defaultBlockState(), 3);
				level.setBlock(post.above(), style.fence().defaultBlockState(), 3);
				level.setBlock(post.above(2), Blocks.LANTERN.defaultBlockState(), 3);
			}
		}
		// Dorfplatz-Schmuck: Muster im Pflaster, Baenke an den Seiten, Blumentoepfe neben der Kiste.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if ((Math.abs(dx) + Math.abs(dz)) % 2 == 1 && Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
					level.setBlock(c.offset(dx, -1, dz), Blocks.COBBLESTONE.defaultBlockState(), 3);
				}
			}
		}
		if (style.stairs() instanceof net.minecraft.world.level.block.StairBlock bench) {
			for (int side = -1; side <= 1; side += 2) {
				BlockPos seat = c.relative(perp, side * 2).relative(face.getOpposite());
				if (level.getBlockState(seat).isAir()) {
					level.setBlock(seat, bench.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, face), 3);
				}
			}
		}
		level.setBlock(c.relative(perp, -1), Blocks.POTTED_RED_TULIP.defaultBlockState(), 3);
		level.setBlock(c.relative(perp, 2), Blocks.POTTED_CORNFLOWER.defaultBlockState(), 3);
		ref.data().chest = Optional.of(a);
		ref.dirty();
		Board.ensure(level, ref);
		return true;
	}

	/** Place only a chest pair on existing clear terrain; never clears generated or player blocks. */
	static boolean placeCompactCenter(ServerLevel level, VillageRef ref, BlockPos villageCenter, Villager who) {
		int searchedColumns = 0;
		int unloadedColumns = 0;
		int unsafePairs = 0;
		int unevenPairs = 0;
		int levelPairs = 0;
		int heightRejectedPairs = 0;
		int homeBlockedPairs = 0;
		int minSurfaceY = Integer.MAX_VALUE;
		int maxSurfaceY = Integer.MIN_VALUE;
		for (int radius = 2; radius <= 12; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					BlockPos column = villageCenter.offset(dx, 0, dz);
					if (!level.hasChunkAt(column)) {
						unloadedColumns++;
						continue;
					}
					searchedColumns++;
					int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						column.getX(), column.getZ());
					minSurfaceY = Math.min(minSurfaceY, y);
					maxSurfaceY = Math.max(maxSurfaceY, y);
					BlockPos a = new BlockPos(column.getX(), y, column.getZ());
					for (Direction face : Direction.Plane.HORIZONTAL) {
						BlockPos b = a.relative(face.getClockWise());
						if (!compactCellFree(level, a) || !compactCellFree(level, b)) {
							unsafePairs++;
							continue;
						}
						if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
							b.getX(), b.getZ()) != y) {
							unevenPairs++;
							continue;
						}
						levelPairs++;
						// Compact fallback must stay near the residents' actual elevation. The bell Y
						// can be far below the village terrain, while heightmaps also include rooftops.
						if (Math.abs(y - who.blockPosition().getY()) > 3) {
							heightRejectedPairs++;
							continue;
						}
						if (level.getPoiManager().getInRange(h -> h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME), a, 5,
							net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).anyMatch(p -> p.getPos().distSqr(a) <= 16)) {
							homeBlockedPairs++;
							continue;
						}
						BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, face);
						level.setBlock(a, chest.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
						level.setBlock(b, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
						ref.data().chest = Optional.of(a);
						ref.dirty();
						Board.ensure(level, ref);
						return true;
					}
				}
			}
		}
		org.slf4j.LoggerFactory.getLogger("villagerplus").warn(
			"Villagers: Repurposed: kompakte Dorfmitte ohne Platz; Mitte {}, Bewohner {}, Spalten geladen/ungeladen {}/{}, Oberflaeche Y {}..{}, sichere Paare {}, Hoehendifferenz abgelehnt {}, uneben {}, unsicher {}, HOME-Schutz {}",
			villageCenter.toShortString(), who.blockPosition().toShortString(), searchedColumns, unloadedColumns,
			minSurfaceY == Integer.MAX_VALUE ? "-" : minSurfaceY, maxSurfaceY == Integer.MIN_VALUE ? "-" : maxSurfaceY,
			levelPairs, heightRejectedPairs, unevenPairs, unsafePairs, homeBlockedPairs);
		return false;
	}

	private static boolean compactCellFree(ServerLevel level, BlockPos feet) {
		BlockPos floor = feet.below();
		if (!level.hasChunkAt(feet) || !level.hasChunkAt(floor)) return false;
		BlockState ground = level.getBlockState(floor);
		if (!ground.isFaceSturdy(level, floor, Direction.UP) || !ground.getFluidState().isEmpty()
			|| ground.is(net.minecraft.tags.BlockTags.LEAVES) || !level.getBlockState(feet).isAir()
			|| !level.getBlockState(feet.above()).isAir()) return false;
		return level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
			new net.minecraft.world.phys.AABB(feet)).isEmpty();
	}
}
