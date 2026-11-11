package de.villagerplus.village;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobUtil;
import de.villagerplus.net.Networking;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Targeted checks for neighbor-tab status, paired approvals, transaction balance, and saved network state. */
public final class NetworkNeighborChecks {
	private NetworkNeighborChecks() { }

	public static int run() {
		int checks = 0;
		require(Networking.networkActionAdmissionFailure(false, true, true, true, true) == null,
			"an in-range player at a loaded village board passes server admission"); checks++;
		require(Networking.networkActionAdmissionFailure(true, true, true, true, true) != null,
			"spectators cannot submit village actions"); checks++;
		require(Networking.networkActionAdmissionFailure(false, false, true, true, true) != null
			&& Networking.networkActionAdmissionFailure(false, true, false, true, true) != null,
			"actions are rejected when the player level or board chunk is unavailable"); checks++;
		require(Networking.networkActionAdmissionFailure(false, true, true, false, true) != null,
			"server rejects actions submitted outside the board's 24-block reach"); checks++;
		require(Networking.networkActionAdmissionFailure(false, true, true, true, false) != null,
			"server rejects requests after the board has been removed or replaced"); checks++;
		Network.ActionOutcome invalidActor = Network.act(null, null, BlockPos.ZERO, Network.ACTION_GREET, 0);
		require(!invalidActor.success() && !invalidActor.message().isBlank(),
			"invalid server context returns an explicit failure and performs no action"); checks++;
		BlockPos west = new BlockPos(120, 70, -45);
		BlockPos east = new BlockPos(320, 70, -45);
		String dimension = "minecraft:overworld";
		TradeNetwork.Link westLink = new TradeNetwork.Link(dimension, east, "Ostheim", 90, true, 9, 12);
		TradeNetwork.Link eastLink = new TradeNetwork.Link(dimension, west, "Westheim", 90, true, 8, 12);
		TradeNetwork westNet = new TradeNetwork(List.of(), List.of(westLink), Map.of(), 0, 0L, Map.of());
		TradeNetwork eastNet = new TradeNetwork(List.of(), List.of(eastLink), Map.of(), 0, 0L, Map.of());
		String voteKey = Network.pairKey(west, east, "large_project_approval");
		TradeNetwork.Link otherDimension = new TradeNetwork.Link("minecraft:the_nether", east, "Netherheim", 90, true, 4, 12);
		TradeNetwork reordered = westNet.withLinks(List.of(otherDimension, westLink));
		require(Network.selectedLinkIndex(reordered, 0, dimension, east) == 1,
			"a stale selected row resolves to the same neighbor after link order changes"); checks++;
		require(Network.selectedLinkIndex(reordered, 0, "minecraft:the_nether", east) == 0,
			"target dimension disambiguates matching bell coordinates"); checks++;
		require(Network.selectedLinkIndex(reordered, 0, "", east) == -1,
			"missing target identity cannot fall back to a stale or reused row index"); checks++;
		require(Network.selectedLinkIndex(reordered, 0, dimension, new BlockPos(900, 70, 900)) == -1,
			"a removed selected neighbor is rejected instead of retargeting the reused index"); checks++;
		require(Network.reciprocalCaravanRoute(westNet, eastNet, dimension, west, east)
			&& Caravan.reciprocalRouteEnabledBothWays(westNet, eastNet, dimension, west, east)
			&& !Caravan.reciprocalRouteEnabledBothWays(westNet, eastNet.withLinks(List.of(eastLink.withRoute(false))), dimension, west, east)
			&& !Caravan.reciprocalRouteEnabledBothWays(westNet.withLinks(List.of(westLink.withRoute(false))), eastNet, dimension, west, east)
			&& !Caravan.reciprocalRouteEnabledBothWays(westNet, eastNet, "minecraft:the_nether", west, east),
			"caravans require enabled route flags at both real reciprocal village bells in the same dimension"); checks++;
		checks += Caravan.routeAssignmentChecks();

		TradeNetwork namedOnly = new TradeNetwork(List.of(new TradeNetwork.Partner("Silberbach", 0, 45, 8L)), List.of(), Map.of(), 0, 0L, Map.of());
		require(Network.afterCaravanLinkVisit(namedOnly, dimension, east, 12L, 3).equals(namedOnly)
			&& Network.selectedLinkIndex(namedOnly, 0, dimension, east) == -1,
			"a name-only trade-history entry cannot be assigned a bell or made actionable"); checks++;

		TradeNetwork.Link siblingLink = new TradeNetwork.Link(dimension, new BlockPos(120, 70, 180), "Südheim", 55, true, 6, 11L, 4L);
		TradeNetwork multiNeighbor = westNet.withLinks(List.of(westLink, siblingLink));
		TradeNetwork visitedWest = Network.afterCaravanLinkVisit(multiNeighbor, dimension, east, 12L, 3);
		TradeNetwork.Link visitedTarget = visitedWest.links().get(0);
		require(visitedTarget.relation() == 93 && visitedTarget.lastVisitDay() == 12L
			&& visitedWest.links().get(1).equals(siblingLink)
			&& multiNeighbor.links().get(0).relation() == 90 && multiNeighbor.links().get(0).lastVisitDay() == -1L,
			"a successful caravan visit updates only the selected real neighbor relationship and day"); checks++;
		TradeNetwork restoredVisit = roundTrip(visitedWest);
		require(restoredVisit.equals(visitedWest) && restoredVisit.links().get(0).lastVisitDay() == 12L
			&& restoredVisit.links().get(1).lastVisitDay() == siblingLink.lastVisitDay()
			&& Network.linkVisitLabel(12L, 12L).equals("besucht heute"),
			"real neighbor last-visit data survives network persistence and has a readable map label"); checks++;

		require(!Network.projectApproved(westNet, eastNet, dimension, west, east), "neither village is presumed to approve a new project"); checks++;
		TradeNetwork westYes = Network.toggleProjectVote(westNet, west, east);
		require(!Network.projectApproved(westYes, eastNet, dimension, west, east), "one village's yes cannot approve a bilateral project"); checks++;
		require(Network.approvalSummary(westYes, eastNet, west, east, true).equals("dein Ja / Nachbar Nein"), "board approval status shows each village's actual vote"); checks++;
		require(Network.approvalSummary(westYes, null, west, east, false).equals("dein Ja / Nachbar unbekannt"), "a village's local vote remains visible while its distant counterpart is unloaded"); checks++;
		TradeNetwork eastYes = Network.toggleProjectVote(eastNet, east, west);
		require(Network.projectApproved(westYes, eastYes, dimension, west, east), "both reciprocal villages' affirmative votes approve the project"); checks++;
		require(Network.approvalSummary(westYes, eastYes, west, east, true).equals("dein Ja / Nachbar Ja"), "board status reports approval from both villages"); checks++;
		require(Network.approvalSummary(westYes, eastYes, west, east, false).equals("dein Ja / Nachbar unbekannt"), "an unavailable counterpart cannot hide the local vote or appear approved"); checks++;
		require(!Network.projectApproved(westYes, null, dimension, west, east), "a missing counterpart cannot approve a project"); checks++;
		require(!Network.projectApproved(westYes, eastYes.withLinks(List.of()), dimension, west, east), "stale yes votes are rejected if the reciprocal village link is gone"); checks++;

		TradeNetwork westNo = Network.toggleProjectVote(westYes, west, east);
		require(!Network.projectApproved(westNo, eastYes, dimension, west, east), "withdrawing one village's yes immediately removes bilateral approval"); checks++;
		require(westNo.cooldowns().get(voteKey) == 0L, "withdrawal is stored as an explicit no rather than a stale yes"); checks++;

		String pairTrade = "pair/" + Network.pairKey(west, east, "trade") + "/";
		String railFreight = "rail/" + Network.pairKey(west, east, "freight") + "/";
		String unrelatedTrade = "pair/" + Network.pairKey(west, new BlockPos(900, 70, 900), "trade") + "/";
		TradeNetwork balanced = westYes
			.withTradeDelta(pairTrade + "minecraft:iron_ingot", 12)
			.withTradeDelta(railFreight + "minecraft:iron_ingot", -3)
			.withTradeDelta(railFreight + "minecraft:bread", 5)
			.withTradeDelta(unrelatedTrade + "minecraft:dirt", 80);
		Map<String, Long> ledger = Network.bilateralLedger(balanced, west, east);
		require(ledger.equals(Map.of("minecraft:iron_ingot", 9L, "minecraft:bread", 5L)), "display balance aggregates only completed item transactions for this pair"); checks++;
		String balanceText = Network.bilateralBalance(balanced, west, east);
		require(balanceText.contains("+9") && balanceText.contains("+5"), "neighbor row formats net received/sent quantities for display"); checks++;

		require(Network.roadStatus(new TradeNetwork.Link(dimension, east, "Ostheim", 10, false, 9, 12), 0).equals("keine"), "unqualified and unrequested road is shown as none"); checks++;
		require(Network.roadStatus(westLink, 42).equals("im Bau 42 %"), "road progress is shown as a bounded percentage"); checks++;
		require(Network.roadStatus(westLink, 100).equals("fertig"), "completed road status is distinct from construction"); checks++;
		require(Network.railStatus(false, true, true, 0, false).equals("erst ab Metropolen-Verbund"), "rail status respects the metropolis threshold"); checks++;
		require(Network.railStatus(true, false, false, 0, false).equals("Zustimmung offen"), "rail status keeps a one-sided vote pending"); checks++;
		require(Network.railStatus(true, true, false, 0, false).equals("Voraussetzungen offen"), "rail status reports unmet endpoint prerequisites"); checks++;
		require(Network.railStatus(true, false, false, 38, false).equals("pausiert · Zustimmung fehlt"), "revoked consent pauses a partially built rail status"); checks++;
		require(Network.railStatus(true, true, true, 100, true).equals("fertig"), "rail status reports both completed halves"); checks++;
		require(Network.railStatus(true, false, false, 100, true).equals("fertig · Zustimmung widerrufen"), "completed track cannot imply current consent after withdrawal"); checks++;
		require(Network.railPercent(5, 2, 7) == 50 && Network.railPercent(1, 2, 7) == 0, "rail percentage is based on work past the station start"); checks++;

		TradeNetwork restoredWest = roundTrip(balanced);
		TradeNetwork restoredEast = roundTrip(eastYes);
		require(restoredWest.cooldowns().get(voteKey) == 1L && Network.projectApproved(restoredWest, restoredEast, dimension, west, east), "both persisted approvals survive network codec restoration"); checks++;
		TradeNetwork restoredLocalYes = roundTrip(westYes);
		require(restoredLocalYes.cooldowns().get(voteKey) == 1L
			&& !Network.projectApproved(restoredLocalYes, null, dimension, west, east)
			&& Network.projectApproved(restoredLocalYes, restoredEast, dimension, west, east),
			"one village's vote persists while the peer is absent and combines only when the peer later approves"); checks++;

		TradeNetwork routeOffAtSource = westNet.withLinks(List.of(westLink.withRoute(false)));
		TradeNetwork queuedRoute = Network.queueRemoteRoute(routeOffAtSource, dimension, east, 120L);
		require(queuedRoute != null && queuedRoute.links().getFirst().route()
			&& queuedRoute.hasPendingAction(Network.ACTION_ROUTE, dimension, east)
			&& roundTrip(queuedRoute).pendingActions().equals(queuedRoute.pendingActions()),
			"a route click changes the source immediately and its desired remote state survives persistence"); checks++;
		Network.DiscoveryPair noReverseYet = Network.reconcilePendingActions(queuedRoute,
			eastNet.withLinks(List.of()), dimension, west, east, 120L);
		require(!noReverseYet.changed() && noReverseYet.source().hasPendingAction(Network.ACTION_ROUTE, dimension, east),
			"a queued route is never applied or dropped until a real reciprocal target bell link exists"); checks++;
		Network.DiscoveryPair routeDelivered = Network.reconcilePendingActions(queuedRoute, eastNet.withLinks(List.of(eastLink.withRoute(false))),
			dimension, west, east, 120L);
		require(routeDelivered.changed() && routeDelivered.source().links().getFirst().route()
			&& routeDelivered.target().links().getFirst().route() && !routeDelivered.source().hasPendingAction(Network.ACTION_ROUTE, dimension, east),
			"the loaded reciprocal pair receives the queued route state exactly once and clears the pending request"); checks++;
		TradeNetwork opposingRouteSource = Network.queueRemoteRoute(routeOffAtSource, dimension, east, 120L);
		TradeNetwork opposingRouteTarget = Network.queueRemoteRoute(eastNet, dimension, west, 200L);
		Network.DiscoveryPair newestRouteWins = Network.reconcilePendingActions(opposingRouteSource, opposingRouteTarget,
			dimension, west, east, 220L);
		require(newestRouteWins.source().links().getFirst().route() == newestRouteWins.target().links().getFirst().route()
			&& !newestRouteWins.source().links().getFirst().route()
			&& newestRouteWins.source().pendingActions().isEmpty() && newestRouteWins.target().pendingActions().isEmpty(),
			"opposite deferred route clicks converge on the newest desired state at both bells and clear both requests"); checks++;

		TradeNetwork queuedGreeting = Network.queueRemoteGreeting(westNet, dimension, east, 500L);
		require(queuedGreeting != null && queuedGreeting.hasPendingAction(Network.ACTION_GREET, dimension, east)
			&& Network.queueRemoteGreeting(queuedGreeting, dimension, east, 501L) == null,
			"a remote greeting is persisted once per target while the destination is absent"); checks++;
		Network.DiscoveryPair heldGreeting = Network.reconcilePendingActions(queuedGreeting,
			eastNet.withCooldown(Network.pairKey(west, east, Network.ACTION_GREET), 5000L), dimension, west, east, 1200L);
		require(!heldGreeting.changed() && heldGreeting.source().hasPendingAction(Network.ACTION_GREET, dimension, east),
			"a queued greeting waits rather than bypassing the target village's active cooldown"); checks++;
		Network.DiscoveryPair greetingDelivered = Network.reconcilePendingActions(queuedGreeting, eastNet, dimension, west, east, 1200L);
		TradeNetwork.Link greetedSourceLink = greetingDelivered.source().links().getFirst();
		TradeNetwork.Link greetedTargetLink = greetingDelivered.target().links().getFirst();
		require(greetingDelivered.changed() && greetedSourceLink.relation() == westLink.relation() + 5
			&& greetedTargetLink.relation() == eastLink.relation() + 5
			&& greetingDelivered.source().moodBonus() == 3 && greetingDelivered.target().moodBonus() == 3
			&& !greetingDelivered.source().hasPendingAction(Network.ACTION_GREET, dimension, east)
			&& !Network.reconcilePendingActions(greetingDelivered.source(), greetingDelivered.target(), dimension, west, east, 1201L).changed(),
			"the reciprocal load applies one greeting to both relations and moods, persists cooldowns, and never repeats it"); checks++;
		require(Network.bilateralLedger(restoredWest, west, east).equals(ledger), "real transaction balance survives network codec restoration"); checks++;
		return checks;
	}

	/** World-backed check for the real invitation action; callers provide two reciprocal loaded fixtures. */
	public static int inviteAction(ServerLevel level, ServerPlayer player, VillageRef source, VillageRef target)
		throws ReflectiveOperationException {
		require(level != null && player != null && player.level() == level && source != null && target != null
			&& !source.bell().equals(target.bell()), "guest invitation fixture has distinct loaded villages and a same-level player");
		String dimension = Network.dim(level);
		TradeNetwork sourceNetwork = Network.of(source.bellEntity());
		TradeNetwork targetNetwork = Network.of(target.bellEntity());
		int sourceLink = Network.selectedLinkIndex(sourceNetwork, -1, dimension, target.bell());
		int targetLink = Network.selectedLinkIndex(targetNetwork, -1, dimension, source.bell());
		require(sourceLink >= 0 && targetLink >= 0, "guest invitation fixture is reciprocally linked");
		Container sourceChest = source.chest(level);
		require(sourceChest != null, "guest invitation fixture has a real source village chest");
		List<ItemStack> originalContents = snapshot(sourceChest);
		var sourceJournal = source.bellEntity().getAttached(VillagerPlus.JOURNAL);
		var targetJournal = target.bellEntity().getAttached(VillagerPlus.JOURNAL);
		AABB guestArea = new AABB(target.bell()).inflate(50);
		Set<UUID> existingGuests = new HashSet<>();
		for (Villager villager : level.getEntitiesOfClass(Villager.class, guestArea, Guests::isGuest)) existingGuests.add(villager.getUUID());
		Map<?, ?> activeGuests = activeGuestStates();
		Villager host = null;
		int checks = 0;
		try {
			host = EntityTypes.VILLAGER.spawn(level, target.bell().east(6), EntitySpawnReason.COMMAND);
			require(host != null, "guest invitation fixture can spawn a host villager");
			host.addTag("vp_network_invite_check");
			host.setNoAi(true);
			host.setInvulnerable(true);
			sourceChest.clearContent();
			Network.ActionOutcome unpaid = Network.act(level, player, source.bell(), Network.ACTION_INVITE, sourceLink, dimension, target.bell());
			require(!unpaid.success() && unpaid.message().contains("2 Smaragde")
				&& JobUtil.count(sourceChest, stack -> stack.is(Items.EMERALD)) == 0
				&& newGuests(level, guestArea, existingGuests).isEmpty(),
				"invitation without real emeralds is rejected without spending or spawning guests");
			checks++;

			sourceChest.setItem(0, new ItemStack(Items.EMERALD, 2));
			Network.ActionOutcome paid = Network.act(level, player, source.bell(), Network.ACTION_INVITE, sourceLink, dimension, target.bell());
			List<Villager> invited = newGuests(level, guestArea, existingGuests);
			int spent = 2 - JobUtil.count(sourceChest, stack -> stack.is(Items.EMERALD));
			require(paid.success() && !invited.isEmpty() && invited.size() <= 2 && spent == invited.size()
				&& paid.message().startsWith(invited.size() + " Gäste")
				&& invited.stream().allMatch(villager -> Guests.isGuest(villager)
					&& villager.getCustomName() != null && villager.getCustomName().getString().startsWith("Gast aus ")
					&& activeGuests.containsKey(villager.getUUID())),
				"paid invitation spends one real emerald per spawned guest and registers their guest state");
			checks++;
			return checks;
		} finally {
			for (Villager guest : newGuests(level, guestArea, existingGuests)) {
				activeGuests.remove(guest.getUUID());
				guest.discard();
			}
			if (host != null) host.discard();
			restore(sourceChest, originalContents);
			Network.save(source.bellEntity(), sourceNetwork);
			Network.save(target.bellEntity(), targetNetwork);
			if (sourceJournal == null) source.bellEntity().removeAttached(VillagerPlus.JOURNAL);
			else source.bellEntity().setAttached(VillagerPlus.JOURNAL, sourceJournal);
			if (targetJournal == null) target.bellEntity().removeAttached(VillagerPlus.JOURNAL);
			else target.bellEntity().setAttached(VillagerPlus.JOURNAL, targetJournal);
		}
	}

	private static List<Villager> newGuests(ServerLevel level, AABB guestArea, Set<UUID> existing) {
		return level.getEntitiesOfClass(Villager.class, guestArea,
			villager -> Guests.isGuest(villager) && !existing.contains(villager.getUUID()));
	}

	private static Map<?, ?> activeGuestStates() throws ReflectiveOperationException {
		Field active = Guests.class.getDeclaredField("ACTIVE");
		active.setAccessible(true);
		return (Map<?, ?>)active.get(null);
	}

	private static List<ItemStack> snapshot(Container container) {
		List<ItemStack> contents = new ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) contents.add(container.getItem(slot).copy());
		return contents;
	}

	private static void restore(Container container, List<ItemStack> contents) {
		for (int slot = 0; slot < contents.size(); slot++) container.setItem(slot, contents.get(slot).copy());
	}

	private static TradeNetwork roundTrip(TradeNetwork network) {
		return TradeNetwork.CODEC.parse(JsonOps.INSTANCE, TradeNetwork.CODEC.encodeStart(JsonOps.INSTANCE, network).getOrThrow()).getOrThrow();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Neighbor network check failed: " + message);
	}
}
