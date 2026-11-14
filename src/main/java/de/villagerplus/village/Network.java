package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.job.JobUtil;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Verbindungen zwischen echten Doerfern: Dorfkarte mitnehmen, an der Tafel eines anderen Dorfes einloesen, Aktionen im
 * Nachbarn-Menue. Alle Pruefungen laufen auf dem Server; der Client schickt nur Tafelposition, Aktion und einen Index in die
 * gespeicherte Link-Liste (keine Zielkoordinaten).
 */
public final class Network {
	public record ActionOutcome(boolean success, String message) {
		public ActionOutcome {
			message = message == null || message.isBlank() ? "Keine Rückmeldung vom Dorf erhalten." : message;
		}
		static ActionOutcome success(String message) { return new ActionOutcome(true, message); }
		static ActionOutcome failure(String message) { return new ActionOutcome(false, message); }
	}

	public static final String ACTION_SWAP = "swap";
	public static final String ACTION_ROUTE = "route";
	public static final String ACTION_INVITE = "invite";
	public static final String ACTION_GREET = "greet";
	public static final String ACTION_APPROVE = "approve";
	public static final String ACTION_CARD = "card";
	private static final double MAX_DISTANCE = 3000.0;
	private static final String TREASURY_EMERALDS = "village/treasury/minecraft:emerald";
	public static final int MAX_MANUAL_VILLAGE_NAME_CODE_POINTS = 24;

	private Network() {
	}

	// --- Zugriff ----------------------------------------------------------------------------------

	public static TradeNetwork of(BlockEntity bell) {
		TradeNetwork n = bell.getAttached(VillagerPlus.NETWORK);
		if (n == null || n.partners().isEmpty()) {
			String biome = bell.getLevel() == null ? "plains"
				: bell.getLevel().getBiome(bell.getBlockPos()).unwrapKey().map(key -> key.identifier().getPath()).orElse("plains");
			TradeNetwork generated = TradeNetwork.create(bell.getBlockPos(), biome);
			n = n == null ? generated : n.withPartners(generated.partners());
			bell.setAttached(VillagerPlus.NETWORK, n);
		}
		return n;
	}

	public static void save(BlockEntity bell, TradeNetwork n) {
		bell.setAttached(VillagerPlus.NETWORK, n);
		bell.setChanged();
	}

	/** Persistent village cash, separate from the material-only building depot. */
	public static long treasuryEmeralds(VillageRef ref) {
		return Math.max(0L, of(ref.bellEntity()).tradeBalance().getOrDefault(TREASURY_EMERALDS, 0L));
	}

	/** Credits real emeralds received at the village board; returns false rather than overflowing. */
	public static boolean creditTreasuryEmeralds(VillageRef ref, long count) {
		if (count <= 0) return false;
		long current = treasuryEmeralds(ref);
		if (current > Long.MAX_VALUE - count) return false;
		save(ref.bellEntity(), of(ref.bellEntity()).withTradeDelta(TREASURY_EMERALDS, count));
		return true;
	}

	/** Spends only cash already present in the village treasury. */
	public static boolean spendTreasuryEmeralds(VillageRef ref, long count) {
		if (count <= 0 || treasuryEmeralds(ref) < count) return false;
		save(ref.bellEntity(), of(ref.bellEntity()).withTradeDelta(TREASURY_EMERALDS, -count));
		return true;
	}

	public static String dim(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	public static String villageName(VillageRef ref) {
		String custom = ref.bellEntity().getAttached(VillagerPlus.VILLAGE_NAME);
		if (custom != null && !custom.isBlank()) {
			return custom;
		}
		String biome = "plains";
		if (ref.bellEntity().getLevel() != null) {
			biome = ref.bellEntity().getLevel().getBiome(ref.bell()).unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
		}
		return Names.village(biome, ref.bell().asLong());
	}

	/** Normalizes valid input; an empty normalized value means an explicit procedural-name reset. */
	static Optional<String> normalizeManualVillageName(String wanted) {
		if (wanted == null) return Optional.empty();

		String normalized = Normalizer.normalize(wanted, Normalizer.Form.NFC);
		StringBuilder clean = new StringBuilder(normalized.length());
		boolean pendingSpace = false;
		int codePoints = 0;
		for (int offset = 0; offset < normalized.length();) {
			int codePoint = normalized.codePointAt(offset);
			offset += Character.charCount(codePoint);

			int type = Character.getType(codePoint);
			if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
				|| codePoint == 0x00A7 || codePoint == 0x2028 || codePoint == 0x2029) {
				return Optional.empty();
			}
			if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
				pendingSpace = clean.length() > 0;
				continue;
			}

			if (pendingSpace) {
				clean.append(' ');
				codePoints++;
				if (codePoints > MAX_MANUAL_VILLAGE_NAME_CODE_POINTS) return Optional.empty();
				pendingSpace = false;
			}
			clean.appendCodePoint(codePoint);
			if (++codePoints > MAX_MANUAL_VILLAGE_NAME_CODE_POINTS) return Optional.empty();
		}

		return Optional.of(clean.toString());
	}

	/** Stores a valid manual name or removes it for a reset; invalid input leaves the persisted name untouched. */
	public static Optional<String> rename(ServerLevel level, VillageRef ref, String wanted) {
		if (level == null || ref == null) return Optional.empty();
		Optional<String> accepted = normalizeManualVillageName(wanted);
		if (accepted.isEmpty()) return Optional.empty();
		String clean = accepted.get();
		String old = villageName(ref);
		if (clean.isEmpty()) {
			ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_NAME);
		} else {
			ref.bellEntity().setAttached(VillagerPlus.VILLAGE_NAME, clean);
		}
		ref.dirty();
		String now = villageName(ref);
		if (!now.equals(old)) {
			Journal.add(level, ref, "Handel", now, "Dorf umbenannt: " + old + " → " + now, 0);
		}
		return Optional.of(now);
	}

	public static String pairKey(VillageRef a, VillageRef b, String action) {
		return pairKey(a.bell(), b.bell(), action);
	}

	static String pairKey(BlockPos a, BlockPos b, String action) {
		long x = a.asLong();
		long y = b.asLong();
		return Math.min(x, y) + "|" + Math.max(x, y) + "|" + action;
	}

	/** Large projects require a separately stored affirmative vote at both linked villages. */
	public static boolean projectApproved(VillageRef a, VillageRef b) {
		if (a == null || b == null || a.bellEntity() == null || b.bellEntity() == null
			|| !(a.bellEntity().getLevel() instanceof ServerLevel level) || a.bellEntity().getLevel() != b.bellEntity().getLevel()
			|| !validVillageRef(level, a) || !validVillageRef(level, b) || !reciprocalLink(level, a, b)) {
			return false;
		}
		return projectApproved(of(a.bellEntity()), of(b.bellEntity()), dim(level), a.bell(), b.bell());
	}

	static boolean projectApproved(TradeNetwork a, TradeNetwork b, String dimension, BlockPos aBell, BlockPos bBell) {
		if (!reciprocalLink(a, b, dimension, aBell, bBell)) return false;
		return projectVote(a, aBell, bBell) && projectVote(b, aBell, bBell);
	}

	private static boolean projectVote(TradeNetwork network, BlockPos aBell, BlockPos bBell) {
		return network != null && network.cooldowns().getOrDefault(pairKey(aBell, bBell, "large_project_approval"), 0L) == 1L;
	}

	static TradeNetwork toggleProjectVote(TradeNetwork source, BlockPos sourceBell, BlockPos targetBell) {
		String key = pairKey(sourceBell, targetBell, "large_project_approval");
		boolean approve = !projectVote(source, sourceBell, targetBell);
		return source.withCooldown(key, approve ? 1L : 0L);
	}

	/** Resolve a click against its intended link so a stale row index cannot target another village. */
	static int selectedLinkIndex(TradeNetwork network, int submittedIndex, String expectedDimension, BlockPos expectedBell) {
		// Never let a stale/malformed row fall back to a reused index. The selected
		// neighbor must still be the same dimension + bell pair when the server handles it.
		if (network == null || expectedDimension == null || expectedDimension.isBlank() || expectedBell == null) return -1;
		if (expectedDimension.length() > 128) return -1;
		for (int i = 0; i < network.links().size(); i++) {
			TradeNetwork.Link link = network.links().get(i);
			if (expectedDimension.equals(link.dimension()) && expectedBell.equals(link.bell())) return i;
		}
		return -1;
	}

	static String approvalSummary(TradeNetwork source, TradeNetwork target, BlockPos sourceBell, BlockPos targetBell, boolean counterpartAvailable) {
		if (source == null) return "unbekannt";
		String ownVote = projectVote(source, sourceBell, targetBell) ? "dein Ja" : "dein Nein";
		if (!counterpartAvailable || target == null) return ownVote + " / Nachbar unbekannt";
		return ownVote + " / "
			+ (projectVote(target, sourceBell, targetBell) ? "Nachbar Ja" : "Nachbar Nein");
	}

	private static ActionOutcome voteProject(VillageRef source, BlockPos targetBell) {
		TradeNetwork updated = toggleProjectVote(of(source.bellEntity()), source.bell(), targetBell);
		boolean approve = projectVote(updated, source.bell(), targetBell);
		save(source.bellEntity(), updated);
		return ActionOutcome.success(approve ? "Deine Zustimmung für dieses Dorf ist gespeichert; auch das Nachbardorf muss separat zustimmen."
			: "Deine Zustimmung zum Großprojekt wurde zurückgenommen.");
	}

	private static boolean validVillageRef(ServerLevel level, VillageRef ref) {
		if (level == null || ref == null || ref.bell() == null || ref.data() == null || ref.bellEntity() == null
			|| !level.hasChunkAt(ref.bell()) || ref.bellEntity().getLevel() != level) return false;
		BlockEntity current = level.getBlockEntity(ref.bell());
		return current == ref.bellEntity() && current instanceof BellBlockEntity
			&& current.getAttachedOrCreate(VillagerPlus.VILLAGE) == ref.data();
	}

	private static boolean reciprocalLink(ServerLevel level, VillageRef a, VillageRef b) {
		return level != null && validVillageRef(level, a) && validVillageRef(level, b)
			&& reciprocalLink(of(a.bellEntity()), of(b.bellEntity()), dim(level), a.bell(), b.bell());
	}

	static boolean reciprocalLink(TradeNetwork a, TradeNetwork b, String dimension, BlockPos aBell, BlockPos bBell) {
		if (a == null || b == null || dimension == null || aBell == null || bBell == null || aBell.equals(bBell)) return false;
		return a.linkIndex(dimension, bBell) >= 0 && b.linkIndex(dimension, aBell) >= 0;
	}

	static boolean reciprocalCaravanRoute(TradeNetwork source, TradeNetwork target, String dimension,
		BlockPos sourceBell, BlockPos targetBell) {
		if (!reciprocalLink(source, target, dimension, sourceBell, targetBell)) return false;
		int index = source.linkIndex(dimension, targetBell);
		return index >= 0 && source.links().get(index).route();
	}

	/** Returns only real, loaded, reciprocal same-dimension neighbors with an explicitly enabled route and depot at both ends. */
	static List<TradeNetwork.Link> loadedReciprocalCaravanRoutes(ServerLevel level, VillageRef source) {
		if (!validVillageRef(level, source) || source.chestPos(level) == null) return List.of();
		NeighborDiscovery discovery = discoverLoadedNeighbors(level, source);
		String dimension = dim(level);
		List<TradeNetwork.Link> routes = new ArrayList<>();
		for (TradeNetwork.Link link : discovery.network().links()) {
			if (!link.route() || !dimension.equals(link.dimension())) continue;
			VillageRef target = resolve(level, link);
			if (target == null || !validVillageRef(level, target) || target.chestPos(level) == null
				|| !reciprocalCaravanRoute(discovery.network(), of(target.bellEntity()), dimension, source.bell(), target.bell())) continue;
			routes.add(link);
		}
		return List.copyOf(routes);
	}

	/** Pure targeted update; a name-only historical partner is never converted into a village link. */
	static TradeNetwork afterCaravanLinkVisit(TradeNetwork network, String dimension, BlockPos targetBell,
		long visitDay, int relationDelta) {
		if (network == null || dimension == null || dimension.isBlank() || targetBell == null || visitDay < 0) return network;
		int index = network.linkIndex(dimension, targetBell);
		if (index < 0) return network;
		List<TradeNetwork.Link> links = new ArrayList<>(network.links());
		TradeNetwork.Link link = links.get(index);
		links.set(index, link.withRelation(link.relation() + relationDelta).visited(visitDay));
		return network.withLinks(links);
	}

	/** Saves a successful physical caravan visit against exactly the real bell stored on its route. */
	static boolean recordCaravanLinkVisit(VillageRef source, String dimension, BlockPos targetBell,
		long visitDay, int relationDelta) {
		if (source == null || source.bellEntity() == null || visitDay < 0) return false;
		TradeNetwork current = of(source.bellEntity());
		TradeNetwork updated = afterCaravanLinkVisit(current, dimension, targetBell, visitDay, relationDelta);
		if (updated.equals(current)) return false;
		save(source.bellEntity(), updated);
		return true;
	}

	static record DiscoveryPair(TradeNetwork source, TradeNetwork target, boolean changed) { }

	/**
	 * Pure preflight for adding a link from a real, loaded bell with live residents. A null target network means its bell
	 * is not currently available; callers must not synthesize a reciprocal side or spend a link slot in that case.
	 */
	static boolean canDiscoverLoadedNeighbor(TradeNetwork source, TradeNetwork target, String dimension,
		BlockPos sourceBell, BlockPos targetBell, int targetPopulation) {
		if (source == null || target == null || dimension == null || dimension.isBlank() || dimension.length() > 128
			|| sourceBell == null || targetBell == null || sourceBell.equals(targetBell) || targetPopulation <= 0) return false;
		int sourceIndex = source.linkIndex(dimension, targetBell);
		int targetIndex = target.linkIndex(dimension, sourceBell);
		return (sourceIndex >= 0 || source.links().size() < TradeNetwork.MAX_LINKS)
			&& (targetIndex >= 0 || target.links().size() < TradeNetwork.MAX_LINKS);
	}

	/** Reconciles the reciprocal persistent link pair after both real villages have been resolved from loaded bells. */
	static DiscoveryPair discoverLoadedPair(TradeNetwork source, TradeNetwork target, String dimension,
		BlockPos sourceBell, BlockPos targetBell, String sourceName, String targetName,
		int sourcePopulation, int targetPopulation, long populationDay) {
		if (source == null || target == null || sourcePopulation < 0
			|| !canDiscoverLoadedNeighbor(source, target, dimension, sourceBell, targetBell, targetPopulation)) {
			return new DiscoveryPair(source, target, false);
		}

		int sourceIndex = source.linkIndex(dimension, targetBell);
		int targetIndex = target.linkIndex(dimension, sourceBell);
		int relation = sourceIndex >= 0 ? source.links().get(sourceIndex).relation()
			: targetIndex >= 0 ? target.links().get(targetIndex).relation() : 10;
		boolean route = sourceIndex >= 0 ? source.links().get(sourceIndex).route()
			: targetIndex >= 0 && target.links().get(targetIndex).route();

		List<TradeNetwork.Link> sourceLinks = new ArrayList<>(source.links());
		List<TradeNetwork.Link> targetLinks = new ArrayList<>(target.links());
		TradeNetwork.Link sourceLink = sourceIndex >= 0 ? sourceLinks.get(sourceIndex)
			: new TradeNetwork.Link(dimension, targetBell, targetName, relation, route, targetPopulation, populationDay);
		TradeNetwork.Link targetLink = targetIndex >= 0 ? targetLinks.get(targetIndex)
			: new TradeNetwork.Link(dimension, sourceBell, sourceName, relation, route, sourcePopulation, populationDay);
		TradeNetwork.Link refreshedSource = new TradeNetwork.Link(dimension, targetBell, targetName,
			sourceLink.relation(), sourceLink.route(), targetPopulation, populationDay, sourceLink.lastVisitDay());
		TradeNetwork.Link refreshedTarget = new TradeNetwork.Link(dimension, sourceBell, sourceName,
			targetLink.relation(), targetLink.route(), sourcePopulation, populationDay, targetLink.lastVisitDay());
		if (sourceIndex >= 0) sourceLinks.set(sourceIndex, refreshedSource);
		else sourceLinks.add(refreshedSource);
		if (targetIndex >= 0) targetLinks.set(targetIndex, refreshedTarget);
		else targetLinks.add(refreshedTarget);

		TradeNetwork updatedSource = source.withLinks(sourceLinks);
		TradeNetwork updatedTarget = target.withLinks(targetLinks);
		boolean changed = !updatedSource.equals(source) || !updatedTarget.equals(target);
		return new DiscoveryPair(updatedSource, updatedTarget, changed);
	}

	static TradeNetwork queueRemoteRoute(TradeNetwork source, String dimension, BlockPos targetBell, long now) {
		if (source == null || dimension == null || dimension.isBlank() || targetBell == null || now < 0) return null;
		int index = source.linkIndex(dimension, targetBell);
		if (index < 0) return null;
		TradeNetwork.Link link = source.links().get(index);
		boolean enabled = !link.route();
		List<TradeNetwork.Link> links = new ArrayList<>(source.links());
		links.set(index, link.withRoute(enabled));
		TradeNetwork withRoute = source.withLinks(links);
		TradeNetwork queued = withRoute.withPendingAction(new TradeNetwork.PendingAction("route", dimension, targetBell, enabled, now));
		return queued.pendingActions().stream().anyMatch(pending -> pending.action().equals("route")
			&& pending.dimension().equals(dimension) && pending.targetBell().equals(targetBell) && pending.routeEnabled() == enabled)
			? queued : null;
	}

	static TradeNetwork queueRemoteGreeting(TradeNetwork source, String dimension, BlockPos targetBell, long now) {
		if (source == null || dimension == null || dimension.isBlank() || targetBell == null || now < 0
			|| source.hasPendingAction("greet", dimension, targetBell)) return null;
		TradeNetwork queued = source.withPendingAction(new TradeNetwork.PendingAction("greet", dimension, targetBell, false, now));
		return queued.pendingActions().stream().anyMatch(pending -> pending.action().equals("greet")
			&& pending.dimension().equals(dimension) && pending.targetBell().equals(targetBell)) ? queued : null;
	}

	/** Applies durable route/greeting requests only after a real reciprocal bell pair is available. */
	static DiscoveryPair reconcilePendingActions(TradeNetwork source, TradeNetwork target, String dimension,
		BlockPos sourceBell, BlockPos targetBell, long now) {
		if (source == null || target == null || dimension == null || sourceBell == null || targetBell == null || now < 0
			|| !reciprocalLink(source, target, dimension, sourceBell, targetBell)) return new DiscoveryPair(source, target, false);
		TradeNetwork updatedSource = source;
		TradeNetwork updatedTarget = target;
		TradeNetwork.PendingAction sourceRoute = routeIntent(source, dimension, targetBell);
		TradeNetwork.PendingAction targetRoute = routeIntent(target, dimension, sourceBell);
		if (sourceRoute != null || targetRoute != null) {
			boolean useSource = sourceRoute != null && (targetRoute == null || newerRouteIntent(sourceRoute, sourceBell, targetRoute, targetBell));
			TradeNetwork.PendingAction chosen = useSource ? sourceRoute : targetRoute;
			boolean enabled = chosen.routeEnabled();
			updatedSource = withLinkRoute(updatedSource, dimension, targetBell, enabled)
				.withoutPendingAction(sourceRoute);
			updatedTarget = withLinkRoute(updatedTarget, dimension, sourceBell, enabled)
				.withoutPendingAction(targetRoute);
		}
		String greetingKey = pairKey(sourceBell, targetBell, ACTION_GREET);
		for (TradeNetwork.PendingAction pending : new ArrayList<>(updatedSource.pendingActions())) {
			if (!pending.dimension().equals(dimension) || !pending.targetBell().equals(targetBell)) continue;
			if (pending.action().equals(ACTION_GREET)) {
				long cooldownUntil = Math.max(updatedSource.cooldowns().getOrDefault(greetingKey, 0L),
					updatedTarget.cooldowns().getOrDefault(greetingKey, 0L));
				if (now < cooldownUntil) continue;
				updatedSource = bumpLocalRelation(updatedSource, dimension, targetBell, 5);
				updatedTarget = bumpLocalRelation(updatedTarget, dimension, sourceBell, 5);
				updatedSource = withGreetingMood(updatedSource, now).withCooldown(greetingKey, now + 24000L).withoutPendingAction(pending);
				updatedTarget = withGreetingMood(updatedTarget, now).withCooldown(greetingKey, now + 24000L);
			}
		}
		return new DiscoveryPair(updatedSource, updatedTarget, !updatedSource.equals(source) || !updatedTarget.equals(target));
	}

	private static TradeNetwork.PendingAction routeIntent(TradeNetwork network, String dimension, BlockPos targetBell) {
		return network.pendingActions().stream().filter(pending -> pending.action().equals(ACTION_ROUTE)
			&& pending.dimension().equals(dimension) && pending.targetBell().equals(targetBell)).findFirst().orElse(null);
	}

	private static boolean newerRouteIntent(TradeNetwork.PendingAction first, BlockPos firstOwner,
		TradeNetwork.PendingAction second, BlockPos secondOwner) {
		int time = Long.compare(first.requestedAt(), second.requestedAt());
		return time != 0 ? time > 0 : firstOwner.asLong() > secondOwner.asLong();
	}

	private static TradeNetwork withLinkRoute(TradeNetwork network, String dimension, BlockPos targetBell, boolean enabled) {
		int index = network.linkIndex(dimension, targetBell);
		if (index < 0 || network.links().get(index).route() == enabled) return network;
		List<TradeNetwork.Link> links = new ArrayList<>(network.links());
		links.set(index, links.get(index).withRoute(enabled));
		return network.withLinks(links);
	}

	/** Moves this village's relation to one neighbour by {@code delta} (clamped to -100..100 by the link itself). */
	public static void nudgeRelation(BlockEntity bell, String dimension, BlockPos targetBell, int delta) {
		TradeNetwork network = of(bell);
		TradeNetwork updated = bumpLocalRelation(network, dimension, targetBell, delta);
		if (updated != network) {
			bell.setAttached(VillagerPlus.NETWORK, updated);
			bell.setChanged();
		}
	}

	private static TradeNetwork bumpLocalRelation(TradeNetwork network, String dimension, BlockPos targetBell, int delta) {
		int index = network.linkIndex(dimension, targetBell);
		if (index < 0) return network;
		List<TradeNetwork.Link> links = new ArrayList<>(network.links());
		links.set(index, links.get(index).withRelation(links.get(index).relation() + delta));
		return network.withLinks(links);
	}

	private static TradeNetwork withGreetingMood(TradeNetwork network, long now) {
		int oldBonus = now <= network.moodBonusUntil() ? network.moodBonus() : 0;
		return network.withMood(Math.min(8, oldBonus + 3), now + 24000L);
	}

	private record NeighborDiscovery(TradeNetwork network, Map<Long, Integer> populations) { }

	/** Counts residents assigned to actual bells in one pass over entities already loaded in this level. */
	private static Map<Long, Integer> loadedResidentCounts(ServerLevel level, List<BlockPos> bells) {
		Set<Long> candidates = new HashSet<>();
		Map<Long, Integer> populations = new HashMap<>();
		for (BlockPos position : bells) {
			if (position != null && level.hasChunkAt(position)
				&& level.getBlockEntity(position) instanceof BellBlockEntity) {
				long packed = position.asLong();
				candidates.add(packed);
				populations.putIfAbsent(packed, 0);
			}
		}
		for (Villager villager : Villages.residentIndex(level).villagers()) {
			if (!villager.isAlive() || Guests.isGuest(villager)) continue;
			Optional<net.minecraft.core.GlobalPos> meeting = villager.getBrain().getMemory(
				net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT);
			if (meeting.isEmpty() || !meeting.get().dimension().equals(level.dimension())) continue;
			long packed = meeting.get().pos().asLong();
			if (candidates.contains(packed)) populations.merge(packed, 1, Integer::sum);
		}
		return Map.copyOf(populations);
	}

	/** Discovers only actual nearby bell block entities whose loaded residents identify them as active villages. */
	private static NeighborDiscovery discoverLoadedNeighbors(ServerLevel level, VillageRef source) {
		if (!validVillageRef(level, source)) return new NeighborDiscovery(of(source.bellEntity()), Map.of());
		TradeNetwork original = of(source.bellEntity());
		TradeNetwork current = original;
		List<BlockPos> nearby = VillageMap.nearbyVillageBells(level, source);
		List<BlockPos> populationPositions = new ArrayList<>(nearby);
		populationPositions.add(source.bell());
		for (TradeNetwork.Link link : original.links()) {
			if (link.dimension().equals(dim(level))) populationPositions.add(link.bell());
		}
		Map<Long, Integer> populations = loadedResidentCounts(level, populationPositions);
		int sourcePopulation = populations.getOrDefault(source.bell().asLong(), 0);
		long day = level.getGameTime() / 24000L;
		boolean changed = false;
		for (BlockPos position : nearby) {
			if (position.equals(source.bell()) || source.bell().distSqr(position) > MAX_DISTANCE * MAX_DISTANCE
				|| !level.hasChunkAt(position)) continue;
			BlockEntity entity = level.getBlockEntity(position);
			if (!(entity instanceof BellBlockEntity bell)) continue;
			VillageRef neighbor = new VillageRef(position, bell.getAttachedOrCreate(VillagerPlus.VILLAGE), bell);
			if (!validVillageRef(level, neighbor)) continue;
			int population = populations.getOrDefault(position.asLong(), 0);
			if (population <= 0) continue;
			TradeNetwork peer = of(bell);
			DiscoveryPair pair = discoverLoadedPair(current, peer, dim(level), source.bell(), neighbor.bell(),
				villageName(source), villageName(neighbor), sourcePopulation, population, day);
			DiscoveryPair forward = reconcilePendingActions(pair.source(), pair.target(), dim(level), source.bell(), neighbor.bell(), level.getGameTime());
			DiscoveryPair reverse = reconcilePendingActions(forward.target(), forward.source(), dim(level), neighbor.bell(), source.bell(), level.getGameTime());
			TradeNetwork nextSource = reverse.target();
			TradeNetwork nextTarget = reverse.source();
			if (pair.changed() || forward.changed() || reverse.changed()) {
				current = nextSource;
				save(neighbor.bellEntity(), nextTarget);
				changed = true;
			}
		}
		// Existing linked bells can be loaded outside the discovery radius. Reconcile their durable
		// requests too; the persisted link identity remains the authority for this deferred work.
		for (TradeNetwork.Link link : new ArrayList<>(current.links())) {
			if (!dim(level).equals(link.dimension()) || !level.hasChunkAt(link.bell())) continue;
			VillageRef neighbor = resolve(level, link);
			if (neighbor == null || !validVillageRef(level, neighbor)) continue;
			TradeNetwork peer = of(neighbor.bellEntity());
			DiscoveryPair forward = reconcilePendingActions(current, peer, dim(level), source.bell(), neighbor.bell(), level.getGameTime());
			DiscoveryPair reverse = reconcilePendingActions(forward.target(), forward.source(), dim(level), neighbor.bell(), source.bell(), level.getGameTime());
			if (forward.changed() || reverse.changed()) {
				current = reverse.target();
				save(neighbor.bellEntity(), reverse.source());
				changed = true;
			}
		}
		if (changed) save(source.bellEntity(), current);
		return new NeighborDiscovery(current, populations);
	}

	/** Das verlinkte Nachbardorf, falls Dimension und Chunk geladen und die Glocke noch da ist (sonst null). */
	public static VillageRef resolve(ServerLevel level, TradeNetwork.Link link) {
		if (!link.dimension().equals(dim(level)) || !level.hasChunkAt(link.bell())) {
			return null;
		}
		BlockEntity be = level.getBlockEntity(link.bell());
		return be instanceof BellBlockEntity ? new VillageRef(link.bell(), be.getAttachedOrCreate(VillagerPlus.VILLAGE), be) : null;
	}

	// --- Registrierung: Dorfkarte an einer Dorftafel einloesen -------------------------------------

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (player.isSpectator() || hand != InteractionHand.MAIN_HAND || !(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			ItemStack held = sp.getItemInHand(hand);
			if (!held.is(de.villagerplus.block.ModItems.DORFKARTE) || !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof DorftafelBlock)) {
				return InteractionResult.PASS;
			}
			redeem(level, sp, hit.getBlockPos(), held);
			return InteractionResult.SUCCESS_SERVER;
		});
	}

	/** Gibt dem Spieler eine Dorfkarte seines Dorfes (Dimension, Glocke, Name). */
	public static void giveCard(ServerLevel level, ServerPlayer player, VillageRef ref) {
		giveCardWithoutMessage(level, player, ref);
		VillageMessages.system(player, Component.literal("Du hast die Dorfkarte von " + villageName(ref) + " eingesteckt. Rechtsklick auf die Dorftafel eines anderen Dorfes verbindet beide."));
	}

	private static void giveCardWithoutMessage(ServerLevel level, ServerPlayer player, VillageRef ref) {
		ItemStack card = new ItemStack(de.villagerplus.block.ModItems.DORFKARTE);
		CompoundTag tag = new CompoundTag();
		tag.putString("dim", dim(level));
		tag.putInt("x", ref.bell().getX());
		tag.putInt("y", ref.bell().getY());
		tag.putInt("z", ref.bell().getZ());
		tag.putString("name", villageName(ref));
		card.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		card.set(DataComponents.CUSTOM_NAME, Component.literal("Dorfkarte: " + villageName(ref)));
		if (!player.getInventory().add(card)) {
			player.drop(card, false);
		}
	}

	private static void redeem(ServerLevel level, ServerPlayer player, BlockPos board, ItemStack card) {
		CustomData data = card.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
		if (!tag.contains("dim")) {
			VillageMessages.system(player, Component.literal("Diese Dorfkarte ist leer."));
			return;
		}
		String cardDim = tag.getStringOr("dim", "");
		BlockPos srcPos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
		VillageRef target = Villages.villageNear(level, board, 96);
		if (target == null) {
			VillageMessages.system(player, Component.literal("Zu dieser Tafel gehört (noch) kein Dorf."));
			return;
		}
		if (!cardDim.equals(dim(level))) {
			VillageMessages.system(player, Component.literal("Die Dorfkarte stammt aus einer anderen Dimension – Verbindung nicht möglich."));
			return;
		}
		if (srcPos.equals(target.bell())) {
			VillageMessages.system(player, Component.literal("Das ist dieselbe Dorfglocke – such ein anderes Dorf."));
			return;
		}
		if (!level.hasChunkAt(srcPos) || !(level.getBlockEntity(srcPos) instanceof BellBlockEntity srcBell)) {
			VillageMessages.system(player, Component.literal("Das Heimatdorf der Karte ist gerade nicht geladen oder existiert nicht mehr."));
			return;
		}
		if (Math.sqrt(srcPos.distSqr(target.bell())) > MAX_DISTANCE) {
			VillageMessages.system(player, Component.literal("Die Dörfer sind zu weit voneinander entfernt (max. 3000 Blöcke)."));
			return;
		}
		VillageRef source = new VillageRef(srcPos, srcBell.getAttachedOrCreate(VillagerPlus.VILLAGE), srcBell);
		TradeNetwork a = of(source.bellEntity());
		TradeNetwork b = of(target.bellEntity());
		String dim = dim(level);
		if (a.linkIndex(dim, target.bell()) >= 0 || b.linkIndex(dim, source.bell()) >= 0) {
			VillageMessages.system(player, Component.literal("Diese beiden Dörfer sind bereits verbunden – die Karte bleibt dir erhalten."));
			return;
		}
		if (a.links().size() >= TradeNetwork.MAX_LINKS || b.links().size() >= TradeNetwork.MAX_LINKS) {
			VillageMessages.system(player, Component.literal("Eines der Dörfer hat schon die maximale Zahl von " + TradeNetwork.MAX_LINKS + " Verbindungen."));
			return;
		}
		List<TradeNetwork.Link> la = new ArrayList<>(a.links());
		la.add(new TradeNetwork.Link(dim, target.bell(), villageName(target), 10, false, -1, -1L));
		List<TradeNetwork.Link> lb = new ArrayList<>(b.links());
		lb.add(new TradeNetwork.Link(dim, source.bell(), villageName(source), 10, false, -1, -1L));
		save(source.bellEntity(), a.withLinks(la));
		save(target.bellEntity(), b.withLinks(lb));
		card.shrink(1);
		Journal.add(level,source,"Handel",villageName(target),"Dorfverbindung eröffnet",0);
		Journal.add(level,target,"Handel",villageName(source),"Dorfverbindung eröffnet",0);
		VillageMessages.system(player, Component.literal("Die Dörfer " + villageName(source) + " und " + villageName(target) + " sind jetzt verbunden! Öffne die Dorftafel → Reiter „Nachbarn“."));
	}

	// --- Aktionen ---------------------------------------------------------------------------------

	/** Fuehrt eine Nachbaraktion aus; bei aktuellen Tafel-Paketen identifiziert Dimension+Glocke den Ziel-Link stabil. */
	public static ActionOutcome act(ServerLevel level, ServerPlayer player, BlockPos board, String action, int index) {
		VillageRef source = level == null || board == null ? null : Villages.villageNear(level, board, 96);
		if (source == null) return act(level, player, board, action, index, "", null);
		TradeNetwork network = of(source.bellEntity());
		if (index < 0 || index >= network.links().size()) return act(level, player, board, action, index, "", null);
		TradeNetwork.Link selected = network.links().get(index);
		return act(level, player, board, action, index, selected.dimension(), selected.bell());
	}

	public static ActionOutcome act(ServerLevel level, ServerPlayer player, BlockPos board, String action, int index,
		String expectedDimension, BlockPos expectedBell) {
		if (level == null || board == null || action == null) return ActionOutcome.failure("Dorfaktion konnte nicht geprüft werden.");
		if (player == null || player.isSpectator() || player.level() != level) {
			return ActionOutcome.failure("Diese Aktion ist nur als Spieler an der Dorftafel möglich.");
		}
		// The networking receiver independently checks that the board is a loaded Dorftafel within 24 blocks.
		// Keep this domain method usable by its isolated server fixtures, which invoke it directly at a village bell.
		if (!action.equals(ACTION_CARD) && !action.equals(ACTION_APPROVE)
			&& !action.equals(ACTION_SWAP) && !action.equals(ACTION_ROUTE)
			&& !action.equals(ACTION_INVITE) && !action.equals(ACTION_GREET)) {
			return ActionOutcome.failure("Unbekannte Dorfaktion.");
		}
		VillageRef source = Villages.villageNear(level, board, 96);
		if (source == null || !validVillageRef(level, source)) {
			return ActionOutcome.failure("Zu dieser Dorftafel gehört noch kein erreichbares Dorf.");
		}
		if (action.equals(ACTION_CARD)) {
			giveCardWithoutMessage(level, player, source);
			return ActionOutcome.success("Dorfkarte für " + villageName(source) + " ins Inventar gelegt.");
		}
		if (action.equals(ACTION_APPROVE)) {
			if (!validApprovalBoard(level, player, board, source)) {
				return ActionOutcome.failure("Die Zustimmung muss an einer erreichbaren Dorftafel des zugehörigen Dorfes abgegeben werden.");
			}
			TradeNetwork approvals = of(source.bellEntity());
			int selected = selectedLinkIndex(approvals, index, expectedDimension, expectedBell);
			if (selected < 0) {
				return ActionOutcome.failure("Die gewählte Nachbarverbindung hat sich geändert; aktualisiere die Dorftafel.");
			}
			TradeNetwork.Link approvedLink = approvals.links().get(selected);
			if (!dim(level).equals(approvedLink.dimension())) {
				return ActionOutcome.failure("Großprojekte können nur für ein Nachbardorf in derselben Dimension genehmigt werden.");
			}
			// Store this village's vote locally. The counterpart may be too far away to
			// have its bell chunk loaded; actual project work still requires reciprocal
			// links and both persisted votes through projectApproved().
			return voteProject(source, approvedLink.bell());
		}
		TradeNetwork net = of(source.bellEntity());
		int selected = selectedLinkIndex(net, index, expectedDimension, expectedBell);
		if (selected < 0) {
			return ActionOutcome.failure("Dieses Dorf ist nicht mehr verknüpft. Wähle ein echtes Nachbardorf aus der Liste.");
		}
		TradeNetwork.Link link = net.links().get(selected);
		VillageRef target = resolve(level, link);
		long now = level.getGameTime();
		String key = pairKey(source.bell(), link.bell(), action);
		if (target == null) {
			if (!dim(level).equals(link.dimension())) {
				return ActionOutcome.failure("Fernaktionen in eine andere Dimension werden nicht vorgemerkt.");
			}
			if (level.hasChunkAt(link.bell())) {
				return ActionOutcome.failure("Die gespeicherte Nachbarglocke fehlt. Aktualisiere die Dorfverbindung.");
			}
			if (action.equals(ACTION_ROUTE)) {
				TradeNetwork queued = queueRemoteRoute(net, link.dimension(), link.bell(), now);
				if (queued == null) return ActionOutcome.failure("Die Route konnte nicht vorgemerkt werden. Prüfe die Verbindung und versuche es erneut.");
				save(source.bellEntity(), queued);
				int nextIndex = queued.linkIndex(link.dimension(), link.bell());
				boolean enabled = nextIndex >= 0 && queued.links().get(nextIndex).route();
				return ActionOutcome.success((enabled ? "Route lokal eingeschaltet" : "Route lokal ausgeschaltet")
					+ "; der Abgleich am Gegenort ist vorgemerkt und erfolgt, sobald dessen Glocke geladen und die Rückverbindung bestätigt ist.");
			}
			if (action.equals(ACTION_GREET)) {
				long until = net.cooldowns().getOrDefault(key, 0L);
				if (now < until) return ActionOutcome.failure("Das geht erst wieder in " + ((until - now) / 1200 + 1) + " Minuten Spielzeit.");
				TradeNetwork queued = queueRemoteGreeting(net, link.dimension(), link.bell(), now);
				if (queued == null) return ActionOutcome.failure("Für dieses Dorf ist bereits ein Gruß vorgemerkt oder die Warteliste ist voll.");
				save(source.bellEntity(), queued);
				return ActionOutcome.success("Gruß dauerhaft vorgemerkt; Beziehung und Stimmung steigen, sobald Gegenort und Rückverbindung geladen sind.");
			}
			return ActionOutcome.failure("Diese Aktion braucht den geladenen Gegenort. Öffne beide Dörfer im geladenen Bereich und versuche es erneut.");
		}
		if (!validVillageRef(level, target)) {
			return ActionOutcome.failure("Das verknüpfte Nachbardorf ist gerade nicht geladen oder seine Glocke fehlt.");
		}
		if (!reciprocalLink(level, source, target)) {
			return ActionOutcome.failure("Die Dorfverbindung ist unvollständig. Beide Dörfer müssen miteinander verknüpft sein.");
		}
		DiscoveryPair reconciled = reconcilePendingActions(net, of(target.bellEntity()), dim(level), source.bell(), target.bell(), now);
		if (reconciled.changed()) {
			net = reconciled.source();
			save(source.bellEntity(), reconciled.source());
			save(target.bellEntity(), reconciled.target());
		}
		if (action.equals(ACTION_GREET) && net.hasPendingAction(ACTION_GREET, dim(level), target.bell())) {
			return ActionOutcome.failure("Ein Gruß ist bereits vorgemerkt und wartet auf die Abklingzeit am Gegenort.");
		}
		selected = selectedLinkIndex(net, index, expectedDimension, expectedBell);
		if (selected < 0) return ActionOutcome.failure("Die gewählte Nachbarverbindung hat sich geändert; aktualisiere die Dorftafel.");
		link = net.links().get(selected);
		key = pairKey(source, target, action);
		TradeNetwork tnet = reconciled.changed() ? reconciled.target() : of(target.bellEntity());
		long until = Math.max(net.cooldowns().getOrDefault(key, 0L), tnet.cooldowns().getOrDefault(key, 0L));
		if (!action.equals(ACTION_ROUTE) && now < until) {
			return ActionOutcome.failure("Das geht erst wieder in " + ((until - now) / 1200 + 1) + " Minuten Spielzeit.");
		}
		return switch (action) {
			case ACTION_SWAP -> swap(level, source, target, key, now);
			case ACTION_ROUTE -> route(source, target, link);
			case ACTION_INVITE -> invite(level, source, target, key, now);
			case ACTION_GREET -> greet(level, source, target, key, now);
			default -> ActionOutcome.failure("Unbekannte Dorfaktion.");
		};
	}

	private static boolean validApprovalBoard(ServerLevel level, ServerPlayer player, BlockPos board, VillageRef source) {
		if (level == null || player == null || board == null || player.isSpectator() || player.level() != level
			|| !level.hasChunkAt(board) || player.blockPosition().distSqr(board) > 24L * 24L
			|| !(level.getBlockState(board).getBlock() instanceof DorftafelBlock) || !validVillageRef(level, source)) return false;
		VillageRef boardVillage = Villages.villageNear(level, board, 96);
		return boardVillage != null && boardVillage.bell().equals(source.bell()) && validVillageRef(level, boardVillage);
	}

	private static void bumpRelation(VillageRef a, VillageRef b, int delta) {
		for (VillageRef[] pair : new VillageRef[][] {{a, b}, {b, a}}) {
			TradeNetwork n = of(pair[0].bellEntity());
			List<TradeNetwork.Link> l = new ArrayList<>(n.links());
			for (int k = 0; k < l.size(); k++) {
				if (l.get(k).bell().equals(pair[1].bell())) {
					l.set(k, l.get(k).withRelation(l.get(k).relation() + delta));
				}
			}
			save(pair[0].bellEntity(), n.withLinks(l));
		}
	}

	private static void markCooldown(VillageRef a, VillageRef b, String key, long until) {
		save(a.bellEntity(), of(a.bellEntity()).withCooldown(key, until));
		save(b.bellEntity(), of(b.bellEntity()).withCooldown(key, until));
	}

	private static ActionOutcome greet(ServerLevel level, VillageRef source, VillageRef target, String key, long now) {
		bumpRelation(source, target, 5);
		Journal.add(level,source,"Befinden",villageName(target),"Gruß erhalten: Stimmung +3",0);
		markCooldown(source, target, key, now + 24000L);
		for (VillageRef v : new VillageRef[] {source, target}) {
			TradeNetwork n = of(v.bellEntity());
			save(v.bellEntity(), n.withMood(Math.min(8, (now <= n.moodBonusUntil() ? n.moodBonus() : 0) + 3), now + 24000L));
		}
		return ActionOutcome.success("Gruß an " + villageName(target) + " übermittelt; die Beziehung und Stimmung sind gestiegen.");
	}

	private static ActionOutcome route(VillageRef source, VillageRef target, TradeNetwork.Link link) {
		boolean on = !link.route();
		for (VillageRef[] pair : new VillageRef[][] {{source, target}, {target, source}}) {
			TradeNetwork n = of(pair[0].bellEntity());
			List<TradeNetwork.Link> l = new ArrayList<>(n.links());
			for (int k = 0; k < l.size(); k++) {
				if (l.get(k).bell().equals(pair[1].bell())) {
					l.set(k, l.get(k).withRoute(on));
				}
			}
			save(pair[0].bellEntity(), n.withLinks(l));
		}
		return ActionOutcome.success(on ? "Handelsroute zwischen " + villageName(source) + " und " + villageName(target) + " eingerichtet."
			: "Handelsroute zwischen " + villageName(source) + " und " + villageName(target) + " aufgegeben.");
	}

	private static ActionOutcome invite(ServerLevel level, VillageRef source, VillageRef target, String key, long now) {
		Container box = source.chest(level);
		if (box == null || JobUtil.count(box, s -> s.is(Items.EMERALD)) < 2) {
			return ActionOutcome.failure("Für die Einladung braucht die Dorfkiste von " + villageName(source) + " mindestens 2 Smaragde.");
		}
		List<Villager> hosts = level.getEntitiesOfClass(Villager.class, new AABB(target.bell()).inflate(48), v -> v.isAlive() && !Guests.isGuest(v));
		if (hosts.isEmpty()) {
			return ActionOutcome.failure("Im Nachbardorf lebt niemand, der Gäste empfangen könnte.");
		}
		int n = Guests.invite(level, target, hosts.get(0), villageName(source), 2);
		if (n == 0) return ActionOutcome.failure("Die Gäste fanden im Nachbardorf keinen freien Ankunftsplatz.");
		JobUtil.take(box, s -> s.is(Items.EMERALD), n);
		markCooldown(source, target, key, now + 12000L);
		bumpRelation(source, target, 2);
		return ActionOutcome.success(n + " Gäste aus " + villageName(source) + " reisen nach " + villageName(target) + ".");
	}

	// --- Warentausch ------------------------------------------------------------------------------

	private static boolean loadedStores(ServerLevel level,VillageRef ref) {
		List<BlockPos> positions = new ArrayList<>(); ref.data().chest.ifPresent(positions::add);
		positions.addAll(ref.data().extra.lagerChests);
		for (BlockPos p:positions) {
			// A double chest may cross a chunk boundary in any horizontal direction.
			if (!level.hasChunkAt(p)) return false;
			var state=level.getBlockState(p);
			if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
				var facing=state.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
				var dir=state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)==net.minecraft.world.level.block.state.properties.ChestType.LEFT?facing.getClockWise():facing.getCounterClockWise();
				if(!level.hasChunkAt(p.relative(dir))) return false;
			}
		}
		return true;
	}

	private static List<Container> stores(ServerLevel level, VillageRef v) {
		List<Container> out = new ArrayList<>();
		Container box = v.chest(level);
		if (box != null) {
			out.add(box);
		}
		out.addAll(Lager.containers(level, v.data()));
		return out;
	}

	/** Wieviele Stueck dieser Sorte nehmen die Behaelter noch sicher auf? */
	private static int capacity(List<Container> stores, ItemStack proto) {
		int cap = 0;
		for (Container c : stores) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (s.isEmpty()) {
					cap += proto.getMaxStackSize();
				} else if (ItemStack.isSameItemSameComponents(s, proto)) {
					cap += Math.max(0, s.getMaxStackSize() - s.getCount());
				}
			}
		}
		return cap;
	}

	/** Legt direkt in die Behaelter (nie als ItemEntity); true, wenn alles untergebracht wurde. */
	private static boolean insertAll(List<Container> stores, ItemStack stack) {
		for (Container c : stores) {
			for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
				ItemStack s = c.getItem(i);
				if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < s.getMaxStackSize()) {
					int move = Math.min(stack.getCount(), s.getMaxStackSize() - s.getCount());
					s.grow(move);
					stack.shrink(move);
					c.setChanged();
				}
			}
		}
		for (Container c : stores) {
			for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
				if (c.getItem(i).isEmpty()) {
					int move = Math.min(stack.getCount(), stack.getMaxStackSize());
					c.setItem(i, stack.copyWithCount(move));
					stack.shrink(move);
					c.setChanged();
				}
			}
		}
		return stack.isEmpty();
	}

	/** Ueberschuesse eines Dorfes (Sorte -> Menge), absteigend nach Wert. */
	private static Map<Item, Integer> surplus(List<Container> stores) {
		Map<Item, Integer> totals = new HashMap<>();
		for (Container c : stores) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (!s.isEmpty() && Caravan.value(s.getItem()) > 0 && ItemStack.isSameItemSameComponents(s, new ItemStack(s.getItem()))) {
					totals.merge(s.getItem(), s.getCount(), Integer::sum);
				}
			}
		}
		Map<Item, Integer> out = new HashMap<>();
		for (Map.Entry<Item, Integer> e : totals.entrySet()) {
			int extra = e.getValue() - Caravan.keep(e.getKey());
			if (extra >= 8) {
				out.put(e.getKey(), extra);
			}
		}
		return out;
	}

	/** Waehlt aus dem Ueberschuss bis zu {@code unitCap} Wert-Einheiten (max. 3 Sorten). */
	private static List<ItemStack> pick(Map<Item, Integer> surplus, long unitCap) {
		List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(surplus.entrySet());
		sorted.sort(Comparator.comparingLong((Map.Entry<Item, Integer> e) -> -(long) e.getValue() * Caravan.value(e.getKey())));
		List<ItemStack> out = new ArrayList<>();
		long units = 0;
		for (Map.Entry<Item, Integer> e : sorted) {
			if (out.size() >= 3 || units >= unitCap) {
				break;
			}
			int v = Caravan.value(e.getKey());
			int n = (int) Math.min(Math.min(e.getValue(), 64), Math.max(1, (unitCap - units) / v));
			if (n > 0) {
				out.add(new ItemStack(e.getKey(), n));
				units += (long) n * v;
			}
		}
		return out;
	}

	private static long units(List<ItemStack> list) {
		long u = 0;
		for (ItemStack s : list) {
			u += (long) s.getCount() * Caravan.value(s.getItem());
		}
		return u;
	}

	private static ActionOutcome swap(ServerLevel level, VillageRef a, VillageRef b, String key, long now) {
		if (!loadedStores(level,a) || !loadedStores(level,b)) return ActionOutcome.failure("Das Nachbardorf ist nicht erreichbar; ein Kisten-Chunk ist nicht geladen.");
		List<Container> sa = stores(level, a);
		List<Container> sb = stores(level, b);
		if (sa.isEmpty() || sb.isEmpty()) {
			return ActionOutcome.failure("Eines der Dörfer hat keine erreichbare Dorfkiste.");
		}
		Map<Item, Integer> surA = surplus(sa);
		Map<Item, Integer> surB = surplus(sb);
		if (surA.isEmpty() || surB.isEmpty()) {
			return ActionOutcome.failure("Kein Tausch möglich: Mindestens ein Dorf hat keinen freien Warenüberschuss.");
		}
		// Choose equal-value lots, capped at 64 items per direction. No rounding creates value.
		Item bestA = null, bestB = null; int countA = 0, countB = 0; long best = 0;
		for (var ea : surA.entrySet()) for (var eb : surB.entrySet()) {
			if (ea.getKey() == eb.getKey()) continue;
			int va = Caravan.value(ea.getKey()), vb = Caravan.value(eb.getKey());
			int x = va, y = vb; while (y != 0) { int t = x % y; x = y; y = t; }
			int na = vb / x, nb = va / x;
			int lots = Math.min(Math.min(64, ea.getValue()) / na, Math.min(64, eb.getValue()) / nb);
			long score = (long) lots * na * va;
			if (score > best) { best = score; bestA = ea.getKey(); bestB = eb.getKey(); countA = lots * na; countB = lots * nb; }
		}
		if (best == 0) return ActionOutcome.failure("Kein gleichwertiger Tausch aus den Überschüssen möglich.");
		List<ItemStack> giveA = List.of(new ItemStack(bestA, countA)), giveB = List.of(new ItemStack(bestB, countB));
		if (giveB.getFirst().getCount() > StockPolicy.deliveryRoom(level, a, giveB.getFirst().getItem())
			|| giveA.getFirst().getCount() > StockPolicy.deliveryRoom(level, b, giveA.getFirst().getItem())) {
			return ActionOutcome.failure("Der Tausch würde einen Sollbestand überschreiten; beide Lagerbestände bleiben unverändert.");
		}
		// Simulate the complete transaction on copies, sharing empty slots across all item types.
		List<Container> copyA = copies(sa), copyB = copies(sb);
		if (!removeLots(copyA, giveA) || !removeLots(copyB, giveB)) return ActionOutcome.failure("Vorräte haben sich geändert; es wurde nichts getauscht.");
		for (ItemStack stack : giveB) if (!insertAll(copyA, stack.copy())) return ActionOutcome.failure("Kein Platz im Lager von " + villageName(a) + "; es wurde nichts getauscht.");
		for (ItemStack stack : giveA) if (!insertAll(copyB, stack.copy())) return ActionOutcome.failure("Kein Platz im Lager von " + villageName(b) + "; es wurde nichts getauscht.");
		commit(sa, copyA); commit(sb, copyB);
		TradeNetwork na = of(a.bellEntity()), nb = of(b.bellEntity());
		for (ItemStack stack : giveB) { String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); na = na.withTradeDelta("pair/" + pairKey(a,b,"trade") + "/" + id, stack.getCount()); nb = nb.withTradeDelta("pair/" + pairKey(a,b,"trade") + "/" + id, -stack.getCount()); }
		for (ItemStack stack : giveA) { String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); na = na.withTradeDelta("pair/" + pairKey(a,b,"trade") + "/" + id, -stack.getCount()); nb = nb.withTradeDelta("pair/" + pairKey(a,b,"trade") + "/" + id, stack.getCount()); }
		save(a.bellEntity(), na); save(b.bellEntity(), nb);
		List<ItemStack> gotA = giveA, gotB = giveB;

		Journal.add(level,a,"Handel",villageName(b),"Tausch: "+describe(gotA)+" gegen "+describe(gotB),best);
		Journal.add(level,b,"Handel",villageName(a),"Tausch: "+describe(gotB)+" gegen "+describe(gotA),best);
		markCooldown(a, b, key, now + 12000L);
		bumpRelation(a, b, 3);
		return ActionOutcome.success("Warentausch abgeschlossen: " + describe(gotA) + " gingen nach " + villageName(b) + ", dafür kamen " + describe(gotB) + ".");
	}

	private static List<Container> copies(List<Container> stores) {
		List<Container> out = new ArrayList<>();
		for (Container c : stores) {
			var copy = new net.minecraft.world.SimpleContainer(c.getContainerSize());
			for (int i = 0; i < c.getContainerSize(); i++) copy.setItem(i, c.getItem(i).copy());
			out.add(copy);
		}
		return out;
	}
	private static boolean removeLots(List<Container> stores, List<ItemStack> lots) {
		for (ItemStack stack : lots) { int remaining = stack.getCount();
			for (Container c : stores) { if (remaining <= 0) break; remaining -= JobUtil.take(c, s -> ItemStack.isSameItemSameComponents(s, stack), remaining); }
			if (remaining != 0) return false;
		}
		return true;
	}
	private static void commit(List<Container> stores, List<Container> copies) {
		for (int k = 0; k < stores.size(); k++) {
			Container c = stores.get(k), copy = copies.get(k);
			for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, copy.getItem(i).copy());
			c.setChanged();
		}
	}

	private static String describe(List<ItemStack> list) {
		List<String> parts = new ArrayList<>();
		for (ItemStack s : list) {
			parts.add(s.getCount() + "x " + s.getHoverName().getString());
		}
		return String.join(", ", parts);
	}

	// --- Anzeige ----------------------------------------------------------------------------------

	/** Zeilen fuer den Reiter "Nachbarn": Name, Beziehung, Strassen-/Bahnstatus, Bilanz und beide Zustimmungen. */
	public static List<String> neighborRows(ServerLevel level, VillageRef ref) {
		NeighborDiscovery discovery = discoverLoadedNeighbors(level, ref);
		TradeNetwork n = discovery.network();
		List<String> rows = new ArrayList<>();
		List<TradeNetwork.Link> links = new ArrayList<>(n.links());
		boolean changed = false;
		long day = level.getGameTime() / 24000L;
		for (int i = 0; i < links.size(); i++) {
			TradeNetwork.Link l = links.get(i);
			VillageRef other = resolve(level, l);
			if (other != null) {
				int pop = discovery.populations().getOrDefault(l.bell().asLong(), l.population());
				if (pop != l.population() || l.populationDay() != day) {
					l = l.withPopulation(pop, day);
					links.set(i, l);
					changed = true;
				}
			}
			int dx = l.bell().getX() - ref.bell().getX();
			int dz = l.bell().getZ() - ref.bell().getZ();
			String dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "Osten" : "Westen") : (dz > 0 ? "Süden" : "Norden");
			String pop = l.population() < 0 ? "?" : l.population() + (day - l.populationDay() > 0 ? " (vor " + (day - l.populationDay()) + " Tagen)" : "");
			boolean loaded = other != null;
			boolean linkedBothWays = loaded && reciprocalLink(level, ref, other);
			TradeNetwork peer = loaded ? of(other.bellEntity()) : null;
			String localBalance = bilateralBalance(n, ref.bell(), l.bell());
			String trade = !loaded ? "Gegenort nicht geladen · lokale Bilanz: " + localBalance
				: !linkedBothWays ? "Rückverbindung fehlt · lokale Bilanz: " + localBalance : localBalance;
			String approval = approvalSummary(n, peer, ref.bell(), l.bell(), linkedBothWays);
			int roadPercent = Roads.progress(ref, l, Market.of(ref).plaza().orElse(ref.bell()));
			String road = roadStatus(l, roadPercent);
			String rail = railStatus(level, ref, l, other, linkedBothWays);
			String neighborName = other == null ? l.name() : villageName(other);
			if (neighborName == null || neighborName.isBlank()) {
				neighborName = "Nachbardorf bei " + l.bell().getX() + ", " + l.bell().getZ();
			}
			String relation = l.relation() + " (" + stage(l.relation()) + ") · " + linkVisitLabel(day, l.lastVisitDay());
			String pending = pendingActionIds(n, l.dimension(), l.bell());
			rows.add(displayCell(neighborName) + "|" + (int) Math.sqrt((double) dx * dx + (double) dz * dz) + " Blöcke " + dir + "|" + dir + "|" + pop + "|" + relation + "|"
				+ road + "|" + (loaded ? "1" : "0") + "|" + trade + "|" + approval + "|" + rail + "|" + (linkedBothWays ? "1" : "0")
				+ "|" + l.dimension() + "|" + l.bell().asLong() + "||" + pending + "|" + (l.route() ? "an" : "aus"));
		}
		if (changed) {
			save(ref.bellEntity(), n.withLinks(links));
		}
		return rows;
	}

	private static String pendingActionIds(TradeNetwork network, String dimension, BlockPos bell) {
		List<String> actions = new ArrayList<>();
		for (TradeNetwork.PendingAction pending : network.pendingActions()) {
			if (!pending.dimension().equals(dimension) || !pending.targetBell().equals(bell)) continue;
			String label = pending.action().equals(ACTION_ROUTE) ? pending.routeEnabled() ? "route_an" : "route_aus" : pending.action();
			if (!actions.contains(label)) {
				actions.add(label);
			}
		}
		return String.join(",", actions);
	}

	static String linkVisitLabel(long currentDay, long lastVisitDay) {
		if (lastVisitDay < 0) return "noch nie besucht";
		long days = Math.max(0L, currentDay - lastVisitDay);
		return days == 0 ? "besucht heute" : "besucht vor " + days + (days == 1 ? " Tag" : " Tagen");
	}

	static Map<String, Long> bilateralLedger(TradeNetwork network, BlockPos a, BlockPos b) {
		if (network == null || a == null || b == null) return Map.of();
		String tradePrefix = "pair/" + pairKey(a, b, "trade") + "/";
		String freightPrefix = "rail/" + pairKey(a, b, "freight") + "/";
		Map<String, Long> values = new HashMap<>();
		for (var e : network.tradeBalance().entrySet()) {
			String prefix = e.getKey().startsWith(tradePrefix) ? tradePrefix : e.getKey().startsWith(freightPrefix) ? freightPrefix : null;
			if (prefix != null) values.merge(e.getKey().substring(prefix.length()), e.getValue(), Network::saturatingAdd);
		}
		return Map.copyOf(values);
	}

	private static long saturatingAdd(long a, long b) {
		try {
			return Math.addExact(a, b);
		} catch (ArithmeticException ignored) {
			return b >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}

	static String bilateralBalance(TradeNetwork network, BlockPos a, BlockPos b) {
		Map<String, Long> values = bilateralLedger(network, a, b);
		if (values.isEmpty()) return "keine Tausche";
		return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(3).map(e -> {
			var id = net.minecraft.resources.Identifier.tryParse(e.getKey());
			String name = id == null ? e.getKey() : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).map(i -> new ItemStack(i).getHoverName().getString()).orElse(e.getKey());
			return displayCell(name) + " " + (e.getValue() > 0 ? "+" : "") + e.getValue();
		}).reduce((x,y) -> x + ", " + y).orElse("keine Tausche");
	}

	private static String displayCell(String value) {
		return value == null ? "" : value.replace('|', '/');
	}

	/** Statusstufe der Beziehung: Unbekannt/Neutral -> Handelspartner (+30) -> Buendnispartner (+60) -> Metropolen-Verbund (+90). */
	public static String stage(int relation) {
		return relation >= 90 ? "Metropolen-Verbund" : relation >= 60 ? "Bündnispartner" : relation >= 30 ? "Handelspartner" : relation < 0 ? "Verfeindet" : "Neutral";
	}

	static String roadStatus(TradeNetwork.Link link, int percent) {
		if (link == null || !link.route() && link.relation() < Roads.TRADE_PARTNER) return "keine";
		int bounded = Math.max(0, Math.min(100, percent));
		return bounded >= 100 ? "fertig" : "im Bau " + bounded + " %";
	}

	private static String railStatus(ServerLevel level, VillageRef ref, TradeNetwork.Link link, VillageRef other, boolean linkedBothWays) {
		if (other == null) return "Nachbar nicht geladen";
		if (!linkedBothWays) return "Verbindung unvollständig";
		TradeNetwork peer = of(other.bellEntity());
		int reverseIndex = peer.linkIndex(dim(level), ref.bell());
		boolean metropolis = link.relation() >= Roads.METROPOLIS && reverseIndex >= 0
			&& peer.links().get(reverseIndex).relation() >= Roads.METROPOLIS;
		if (!metropolis) return railStatus(false, false, false, 0, false);
		boolean approved = projectApproved(ref, other);
		Map<String, Long> ownRoads = ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
		Map<String, Long> peerRoads = other.bellEntity().getAttachedOrElse(VillagerPlus.ROADS, Map.of());
		String ownRouteKey = dim(level) + other.bell().asLong();
		String peerRouteKey = dim(level) + ref.bell().asLong();
		int length = RoadRoute.length(ownRoads, ownRouteKey, ref.bell(), other.bell());
		int last = length / 2 + 1;
		int ownStart = stationStartForStatus(level, ref, other, ownRoads, ownRouteKey);
		int peerStart = stationStartForStatus(level, other, ref, peerRoads, peerRouteKey);
		int ownCursor = railCursor(ownRoads, ref.bell(), other.bell());
		int peerCursor = railCursor(peerRoads, other.bell(), ref.bell());
		boolean complete = length > 0 && ownCursor > last && peerCursor > last;
		int ownPercent = railPercent(ownCursor, ownStart, last);
		int peerPercent = railPercent(peerCursor, peerStart, last);
		int percent = Math.min(ownPercent, peerPercent);
		boolean ready = approved && Roads.railReady(level, ref) && Roads.railReady(level, other);
		return railStatus(metropolis, approved, ready, percent, complete);
	}

	private static int stationStartForStatus(ServerLevel level, VillageRef from, VillageRef to, Map<String, Long> state, String routeKey) {
		BlockPos station = Specials.get(from, de.villagerplus.build.Blueprints.STATION);
		if (station == null || !level.hasChunkAt(station)) return 0;
		return Math.max(0, Roads.stationRailStartForCheck(level, from.bell(), to.bell(), station, state, routeKey));
	}

	private static int railCursor(Map<String, Long> state, BlockPos from, BlockPos to) {
		String key = "rail" + to.asLong();
		if (state.getOrDefault(key + ":from", Long.MIN_VALUE) != from.asLong()
			|| state.getOrDefault(key + ":to", Long.MIN_VALUE) != to.asLong()
			|| state.getOrDefault(key + ":geometry", 0L) <= 0L) return 0;
		return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, state.getOrDefault(key, 0L)));
	}

	static int railPercent(int cursor, int stationStart, int last) {
		int start = Math.max(0, Math.min(last + 1, stationStart));
		int steps = Math.max(1, last + 1 - start);
		long advanced = Math.max(0L, (long) cursor - start);
		return (int) Math.min(100L, advanced * 100L / steps);
	}

	static String railStatus(boolean metropolis, boolean approved, boolean prerequisitesMet, int progress, boolean complete) {
		if (!metropolis) return "erst ab Metropolen-Verbund";
		if (complete) return approved ? "fertig" : "fertig · Zustimmung widerrufen";
		int bounded = Math.max(0, Math.min(100, progress));
		if (bounded > 0) return approved ? "im Bau " + bounded + " %" : "pausiert · Zustimmung fehlt";
		if (!approved) return "Zustimmung offen";
		return prerequisitesMet ? "bereit" : "Voraussetzungen offen";
	}

	/** Zeilen fuer die Partnerorte: {@code name|spezialitaet|beziehung|besuch}. */
	public static List<String> partnerRows(ServerLevel level, VillageRef ref) {
		return partnerRows(of(ref.bellEntity()), level.getGameTime() / 24000L);
	}

	static List<String> partnerRows(TradeNetwork n, long day) {
		List<String> rows = new ArrayList<>();
		for (TradeNetwork.Partner p : n.partners()) {
			String last = p.lastVisitDay() < 0 ? "noch nie" : "vor " + Math.max(0, day - p.lastVisitDay()) + " Tagen";
			String shown = partnerDisplayName(p);
			rows.add(shown + "|" + TradeNetwork.SPECIALTIES[p.specialty() % TradeNetwork.SPECIALTIES.length] + "|" + p.relation() + "|" + last);
		}
		return rows;
	}

	static String partnerDisplayName(TradeNetwork.Partner partner) {
		return partner.lastVisitDay() < 0 || partner.name().isBlank()
			? "Unbekannter Ort (noch kein Handelskontakt)" : partner.name();
	}

	/** Gruss-Stimmungsbonus fuer Einwohner (0, wenn abgelaufen). */
	public static int moodBonus(ServerLevel level, VillageRef ref) {
		TradeNetwork n = ref.bellEntity().getAttached(VillagerPlus.NETWORK);
		return n == null || level.getGameTime() > n.moodBonusUntil() ? 0 : n.moodBonus();
	}
}
