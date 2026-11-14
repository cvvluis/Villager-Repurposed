package de.villagerplus.village;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Palette;
import de.villagerplus.build.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Focused planning checks for the first real, rent-paid market stall. */
public final class MarketPlanningChecks {
	private record Cell(BlockState state, CompoundTag blockEntity) { }
	private record StepShape(BlockPos position, Item item, int cost, boolean free, BlockState state) { }

	private MarketPlanningChecks() { }

	public static int run(ServerLevel level, VillageRef ref, BlockPos origin) {
		Container communal = ref.chest(level);
		if (communal == null) throw new IllegalStateException("Market planning checks need the public village chest");
		MarketData originalMarket = ref.bellEntity().getAttached(VillagerPlus.MARKET);
		List<ZonePolicy.Zone> originalZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		List<VillageData.BuildProject> originalProjects = new ArrayList<>(ref.data().buildProjects);
		long treasuryBefore = Network.treasuryEmeralds(ref);
		List<ItemStack> communalBefore = copy(communal);
		Map<BlockPos, Cell> siteBefore = new LinkedHashMap<>();
		Map<Villager, Optional<GlobalPos>> oldMeetings = new LinkedHashMap<>();
		List<Villager> fixtures = new ArrayList<>();
		int checks = 0;
		long now = level.getGameTime() + 10_000L;
		Palette stallPalette = new Palette(Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.STRIPPED_SPRUCE_LOG,
			Blocks.COBBLESTONE, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB,
			Blocks.GLASS_PANE, Blocks.BIRCH_PLANKS, Blocks.BIRCH_DOOR, Blocks.SPRUCE_FENCE);
		Block stallTimber = stallPalette.frame() == null ? stallPalette.base() : stallPalette.frame();
		Block stallRoof = stallPalette.roofSlab() instanceof net.minecraft.world.level.block.SlabBlock
			? stallPalette.roofSlab() : stallPalette.roofFull();
		Set<Item> paletteItems = new LinkedHashSet<>(List.of(stallPalette.wall().asItem(), stallPalette.wall2().asItem(),
			stallTimber.asItem(), stallPalette.base().asItem(), stallPalette.floor().asItem(), stallRoof.asItem(), Items.CHEST));
		for (Direction facing : Direction.Plane.HORIZONTAL) for (int kind = 0; kind < Market.KINDS.length; kind++) {
			MarketData.Shop geometryShop = new MarketData.Shop(java.util.UUID.randomUUID(), origin.offset(0, 8, 0),
				facing.get2DDataValue(), kind, 0, 0, now, stallPalette.ids(), false, 0);
			Block stallAccent = switch (kind) {
				case 0 -> stallPalette.base();
				case 1 -> stallPalette.wall2();
				default -> stallTimber;
			};
			List<Step> stall = Market.hut(geometryShop, stallPalette);
			Set<BlockPos> stallPositions = stall.stream().map(Step::pos).collect(java.util.stream.Collectors.toSet());
			checks = check(checks, stallPositions.size() == stall.size()
				&& stall.stream().allMatch(step -> !step.free() && step.item() != null && step.cost() > 0
					&& paletteItems.contains(step.item())),
				"market stall has unique, paid palette-backed steps for " + facing + "/kind " + kind);
			checks = check(checks, Market.counter(geometryShop).equals(Market.at(geometryShop, 0, 3, 0))
				&& java.util.stream.IntStream.rangeClosed(0, 2)
					.noneMatch(y -> stallPositions.contains(Market.at(geometryShop, 0, 3, y)))
				&& !stallPositions.contains(Market.at(geometryShop, 0, 1, 0)),
				"open counter approach and seller stand remain clear for " + facing + "/kind " + kind);
			checks = check(checks, java.util.stream.IntStream.rangeClosed(-1, 1)
				.allMatch(x -> stall.stream().anyMatch(step -> step.pos().equals(Market.at(geometryShop, x, 2, 0))
					&& step.item() == stallRoof.asItem()))
				&& java.util.stream.IntStream.rangeClosed(-2, 2)
					.anyMatch(x -> stall.stream().anyMatch(step -> step.pos().equals(Market.at(geometryShop, x, 3, 3))
						&& step.item() == stallRoof.asItem()))
				&& java.util.stream.IntStream.rangeClosed(-2, 2)
					.anyMatch(x -> stall.stream().anyMatch(step -> step.pos().equals(Market.at(geometryShop, x, 3, 3))
						&& step.item() == stallAccent.asItem()))
				&& stall.stream().anyMatch(step -> step.pos().equals(Market.stockPos(geometryShop))
					&& step.item() == Items.CHEST)
				&& java.util.stream.IntStream.rangeClosed(1, 2)
					.noneMatch(y -> stallPositions.contains(Market.stockPos(geometryShop).above(y))),
				"three-block serving counter, striped awning and visible stocked-chest display space are preserved for " + facing + "/kind " + kind);
			int minX=stall.stream().mapToInt(step->step.pos().getX()).min().orElseThrow();
			int maxX=stall.stream().mapToInt(step->step.pos().getX()).max().orElseThrow();
			int minZ=stall.stream().mapToInt(step->step.pos().getZ()).min().orElseThrow();
			int maxZ=stall.stream().mapToInt(step->step.pos().getZ()).max().orElseThrow();
			int expectedX=facing.getAxis()==Direction.Axis.Z?5:6;
			int expectedZ=facing.getAxis()==Direction.Axis.Z?6:5;
			checks = check(checks, maxX-minX+1==expectedX && maxZ-minZ+1==expectedZ
				&& (maxX-minX+1)*(maxZ-minZ+1)<49
				&& shape(stall).equals(shape(Market.hut(geometryShop,stallPalette))),
				"rotated market canopy stays deterministic and smaller than a minimum house for " + facing + "/kind " + kind);
		}
		BlockPos fallbackAnchor = Development.center(level, ref);
		try {
			BlockPos otherMeeting = ref.bell().offset(1_024, 0, 1_024);
			long residentRangeSquared = (long)de.villagerplus.Config.VILLAGE_RADIUS * de.villagerplus.Config.VILLAGE_RADIUS;
			for (var entity : level.getAllEntities()) if (entity instanceof Villager existing && existing.isAlive()) {
				Optional<GlobalPos> meeting = existing.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
				boolean assignedHere = meeting.filter(pos -> pos.dimension() == level.dimension() && pos.pos().equals(ref.bell())).isPresent();
				if (!assignedHere && existing.blockPosition().distSqr(ref.bell()) > residentRangeSquared) continue;
				oldMeetings.put(existing, meeting);
				existing.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), otherMeeting));
			}
			prepareViableSites(level, origin, siteBefore);
			prepareViableSites(level, fallbackAnchor, siteBefore);
			List<Villager> residents = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				Villager villager = EntityTypes.VILLAGER.spawn(level, origin.offset(i * 2, 0, 0), EntitySpawnReason.COMMAND);
				if (villager == null) throw new IllegalStateException("Could not create market planner resident " + i);
				villager.addTag("vp090_fixture");
				villager.setInvulnerable(true);
				villager.setNoAi(true);
				fixtures.add(villager);
				villager.getInventory().clearContent();
				villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
				residents.add(villager);
			}

			checks = check(checks, Market.targetShopCount(3) == 0 && Market.targetShopCount(4) == 1,
				"first stall threshold is exactly four residents");
			checks = check(checks, Market.targetShopCount(15) == 1 && Market.targetShopCount(16) == 2
				&& Market.targetShopCount(23) == 2 && Market.targetShopCount(24) == 3,
				"second and third stall thresholds remain sixteen and twenty-four residents");

			List<BlockPos> searchCandidates = Market.marketSiteSearchCandidates(ref, origin);
			long siteLimit = de.villagerplus.Config.VILLAGE_RADIUS - 4L;
			checks = check(checks, searchCandidates.size() <= 48 + 13 * 13
				&& new LinkedHashSet<>(searchCandidates).size() == searchCandidates.size()
				&& searchCandidates.equals(Market.marketSiteSearchCandidates(ref, origin)),
				"market site candidates remain unique, deterministic, and capped at the old spokes plus a 169-point grid");
			checks = check(checks, searchCandidates.stream().allMatch(candidate ->
				Math.abs((long)candidate.getX() - ref.bell().getX()) <= siteLimit
					&& Math.abs((long)candidate.getZ() - ref.bell().getZ()) <= siteLimit
					&& !Plaza.overlapsApproach(origin, candidate, 4)),
				"expanded market candidates stay inside village bounds and clear all four plaza approaches");
			int interleavedOffset = Math.toIntExact(2L * siteLimit / (13 - 1));
			BlockPos interleavedCandidate = new BlockPos(ref.bell().getX() - (int)siteLimit + interleavedOffset,
				origin.getY(), ref.bell().getZ() - (int)siteLimit + interleavedOffset);
			checks = check(checks, searchCandidates.contains(interleavedCandidate),
				"denser market grid samples open ground between the former nine-by-nine points");
			prepareFlatMarketSite(level, interleavedCandidate, siteBefore);
			MarketData.Shop interleavedFixture = new MarketData.Shop(java.util.UUID.randomUUID(), interleavedCandidate,
				facingToward(origin, interleavedCandidate).get2DDataValue(), 0, 0, 0, now, List.of(), false, 0);
			BlockPos interleavedPlant = Market.at(interleavedFixture, -2, -2, 0);
			level.setBlock(interleavedPlant, Blocks.SHORT_GRASS.defaultBlockState(), 3);
			List<MarketData.Shop> reserveOtherCandidates = new ArrayList<>();
			for (BlockPos candidate : searchCandidates) if (!candidate.equals(interleavedCandidate)) {
				reserveOtherCandidates.add(new MarketData.Shop(java.util.UUID.randomUUID(), candidate,
					Direction.NORTH.get2DDataValue(), 0, 0, 0, now, List.of(), false, 0));
			}
			BlockPos recoveredSite = Market.findSite(level, ref, origin, reserveOtherCandidates);
			checks = check(checks, level.getBlockState(interleavedPlant).is(Blocks.SHORT_GRASS),
				"market site planning does not clear the short-grass plant [state=" + level.getBlockState(interleavedPlant) + "]");
			checks = check(checks, interleavedCandidate.equals(recoveredSite),
				"market search recovers the interleaved clearable site [expected=" + interleavedCandidate + ", recovered=" + recoveredSite + "]");
			BlockPos farMarketCandidate = searchCandidates.stream()
				.max(java.util.Comparator.comparingDouble(candidate -> candidate.distSqr(origin))).orElseThrow();
			checks = check(checks, farMarketCandidate.distSqr(origin) > 37 * 37 + 10 * 10,
				"the distributed market grid reaches beyond every former radius-37 spoke");
			prepareFlatMarketSite(level, farMarketCandidate, siteBefore);
			List<MarketData.Shop> blockedSearchCandidates = new ArrayList<>();
			// findSite also checks the interstitial grid after ordinary samples fail. Reserve both
			// production batches so this fixture proves spacing across the full bounded search.
			List<BlockPos> allSearchCandidates = new ArrayList<>(searchCandidates);
			allSearchCandidates.addAll(Market.marketSiteFallbackCandidates(ref, origin));
			for (BlockPos candidate : allSearchCandidates) if (!candidate.equals(farMarketCandidate)) {
				blockedSearchCandidates.add(new MarketData.Shop(java.util.UUID.randomUUID(), candidate,
					Direction.NORTH.get2DDataValue(), 0, 0, 0, now, List.of(), false, 0));
			}
			Direction forestFacing = facingToward(origin, farMarketCandidate);
			MarketData.Shop forestFixture = new MarketData.Shop(java.util.UUID.randomUUID(), farMarketCandidate,
				forestFacing.get2DDataValue(), 0, 0, 0, now, List.of(), false, 0);
			residents.get(0).getInventory().setItem(0, new ItemStack(Items.IRON_AXE));
			residents.get(0).getInventory().setItem(1, new ItemStack(Items.SHEARS));
			for (int dx = -2; dx <= 2; dx += 2) for (int dz = -2; dz <= 2; dz += 2) {
				BlockPos groveRoot = Market.at(forestFixture, dx, dz, 0);
				for (int dy = 0; dy <= 3; dy++) level.setBlock(groveRoot.above(dy), Blocks.OAK_LOG.defaultBlockState(), 3);
				level.setBlock(groveRoot.above(4), Blocks.OAK_LEAVES.defaultBlockState()
					.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false), 3);
			}
			BlockPos expandedSite = Market.findSite(level, ref, origin, blockedSearchCandidates);
			checks = check(checks, farMarketCandidate.equals(expandedSite)
				&& level.getBlockState(Market.at(forestFixture, -2, 0, 0)).is(Blocks.OAK_LOG),
				"a distant nine-tree grove is accepted as a bounded, safely preparable market site before work starts");
			int groveLogsBefore = count(residents.get(0).getInventory(), Items.OAK_LOG);
			int groveLeavesBefore = count(residents.get(0).getInventory(), Items.OAK_LEAVES);
			var firstGroveCut = Market.clearMarketSiteStep(level, ref, forestFixture, residents.get(0));
			checks = check(checks, firstGroveCut.status() == NaturalSitePreparation.Status.CLEARED
				&& firstGroveCut.obstacleCount() == 45 && firstGroveCut.next() != null
				&& level.getBlockState(firstGroveCut.next()).isAir(),
				"the first worker visit clears one verified block from the forty-five-block grove");
			var groveShopSnapshot = MarketData.Shop.CODEC.encodeStart(JsonOps.INSTANCE, forestFixture).getOrThrow();
			MarketData.Shop resumedGrove = MarketData.Shop.CODEC.parse(JsonOps.INSTANCE, groveShopSnapshot).getOrThrow();
			checks = check(checks, resumedGrove.equals(forestFixture),
				"shop site and construction progress survive a save round trip during forest preparation");
			int groveCleared = 1;
			NaturalSitePreparation.Scan grovePreparation = firstGroveCut;
			while (grovePreparation.status() == NaturalSitePreparation.Status.CLEARED && groveCleared < 48) {
				grovePreparation = Market.clearMarketSiteStep(level, ref, resumedGrove, residents.get(0));
				if (grovePreparation.status() == NaturalSitePreparation.Status.CLEARED) groveCleared++;
			}
			checks = check(checks, groveCleared == 45 && grovePreparation.status() == NaturalSitePreparation.Status.READY
				&& count(residents.get(0).getInventory(), Items.OAK_LOG) == groveLogsBefore + 36
				&& count(residents.get(0).getInventory(), Items.OAK_LEAVES) == groveLeavesBefore + 9
				&& residents.get(0).getInventory().getItem(0).getDamageValue() > 0
				&& residents.get(0).getInventory().getItem(1).getDamageValue() > 0,
				"rechecking the restored shop clears the grove in bounded steps, preserves drops, and uses the resident's real tools");
			blockedSearchCandidates.add(forestFixture);
			checks = check(checks, Market.findSite(level, ref, origin, blockedSearchCandidates) == null,
				"an otherwise valid remote market footprint is rejected when existing shops reserve its spacing");

			MarketData noPlaza = new MarketData(Optional.empty(), List.of(), 0, 0);
			for (Villager villager : residents) {
				villager.getInventory().clearContent();
				villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER));
			}
			Market.save(ref, noPlaza);
			Market.plan(level, ref, now);
			MarketData noOwnerWithoutPlaza = Market.of(ref);
			checks = check(checks, noOwnerWithoutPlaza.plaza().isEmpty() && noOwnerWithoutPlaza.shops().isEmpty()
				&& noOwnerWithoutPlaza.nextPlan() == now + Market.PLAN_RETRY_TICKS,
				"a village without a plaza still retries market planning when it has no eligible owner");
			long noStockAt = noOwnerWithoutPlaza.nextPlan();
			Villager noStockOwner = residents.get(0);
			noStockOwner.setVillagerData(noStockOwner.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NONE));
			Market.save(ref, new MarketData(Optional.empty(), List.of(), noStockAt, 0));
			long treasuryBeforeNoStock = Network.treasuryEmeralds(ref);
			Market.plan(level, ref, noStockAt);
			MarketData noStockWithoutPlaza = Market.of(ref);
			checks = check(checks, noStockWithoutPlaza.plaza().isEmpty() && noStockWithoutPlaza.shops().isEmpty()
				&& noStockWithoutPlaza.nextPlan() == noStockAt + Market.PLAN_RETRY_TICKS,
				"an unfunded owner without a plaza leaves the stall unplanned and schedules another attempt");
			checks = check(checks, Network.treasuryEmeralds(ref) == treasuryBeforeNoStock
				&& Market.wallet(level, noStockOwner).stream().mapToInt(box -> count(box, Items.EMERALD)).sum() == 0,
				"the no-plaza/no-stock retry does not charge public or private funds");

			noStockOwner.getInventory().setItem(0, new ItemStack(Items.EMERALD));
			long noPlazaSuccessAt = noStockWithoutPlaza.nextPlan();
			BlockPos planningAnchor = Development.center(level, ref);
			List<BlockPos> fallbackCandidates = Market.marketSiteFallbackCandidates(ref, planningAnchor);
			List<BlockPos> regularCandidates = Market.marketSiteSearchCandidates(ref, planningAnchor);
			BlockPos fallbackOnlySite = null;
			for (BlockPos candidate : fallbackCandidates) {
				if (regularCandidates.stream().anyMatch(regular -> nearMarketPlot(candidate, regular, 4))
					|| fallbackCandidates.stream().filter(other -> !other.equals(candidate))
						.anyMatch(other -> nearMarketPlot(candidate, other, 4))) continue;
				prepareFlatMarketSite(level, candidate, siteBefore);
				if (Market.prepareMarketSite(level, ref, candidate, facingToward(planningAnchor, candidate)) != null) {
					fallbackOnlySite = candidate;
					break;
				}
			}
			if (fallbackOnlySite == null)
				throw new IllegalStateException("Market planning check needs an isolated, safe interstitial fallback site");
			VillageData.BuildProject foreignProject = new VillageData.BuildProject("market-check-foreign-project",
				fallbackOnlySite, 0, 0, 0, Optional.empty(), 0, Optional.empty(), 0, List.of(),
				VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(),
				Optional.of(new VillageData.PlanBounds(fallbackOnlySite.getX() - 4, fallbackOnlySite.getX() + 4,
					fallbackOnlySite.getZ() - 4, fallbackOnlySite.getZ() + 4)), false, false);
			ref.data().buildProjects.add(foreignProject);
			checks = check(checks, Market.prepareMarketSite(level, ref, fallbackOnlySite,
				facingToward(planningAnchor, fallbackOnlySite)) == null
				&& level.getBlockState(fallbackOnlySite.below()).is(Blocks.GRASS_BLOCK),
				"an active foreign construction reservation blocks the fallback without changing its natural ground");
			ref.data().buildProjects.removeIf(project -> project.id() == foreignProject.id());
			checks = check(checks, fallbackCandidates.size() <= 12 * 12
				&& new LinkedHashSet<>(fallbackCandidates).size() == fallbackCandidates.size()
				&& fallbackCandidates.equals(Market.marketSiteFallbackCandidates(ref, planningAnchor))
				&& fallbackCandidates.stream().noneMatch(regularCandidates::contains)
				&& fallbackCandidates.stream().allMatch(candidate ->
					Math.abs((long)candidate.getX() - ref.bell().getX()) <= siteLimit
						&& Math.abs((long)candidate.getZ() - ref.bell().getZ()) <= siteLimit
						&& !Plaza.overlapsApproach(planningAnchor, candidate, 4)),
				"interstitial market fallback candidates are unique, deterministic, bounded, and outside the regular grid");
			List<ZonePolicy.Zone> isolateFallback = isolateMarketFallbackPlot(fallbackOnlySite);
			ref.bellEntity().setAttached(VillagerPlus.ZONES, isolateFallback);
			BlockPos expectedFallbackSite = Market.findSite(level, ref, planningAnchor, List.of());
			checks = check(checks, fallbackOnlySite.equals(expectedFallbackSite),
				"the saved no-site fallback finds a safe interstitial plot after every regular candidate is protected");
			Market.save(ref, new MarketData(Optional.empty(), List.of(), noPlazaSuccessAt, 0));
			long treasuryBeforeNoPlazaSuccess = Network.treasuryEmeralds(ref);
			Market.plan(level, ref, noPlazaSuccessAt);
			MarketData noPlazaSuccess = Market.of(ref);
			MarketData.Shop fallbackShop = noPlazaSuccess.shops().isEmpty() ? null : noPlazaSuccess.shops().getFirst();
			checks = check(checks, noPlazaSuccess.plaza().isEmpty() && fallbackShop != null
				&& fallbackShop.owner().equals(noStockOwner.getUUID()) && fallbackShop.origin().equals(expectedFallbackSite)
				&& fallbackShop.progress() == 0 && fallbackShop.opened() == 0,
				"an eligible resident persists an unopened market work plan at the fallback site before the plaza exists");
			MarketData persistedFallback = MarketData.CODEC.parse(JsonOps.INSTANCE,
				MarketData.CODEC.encodeStart(JsonOps.INSTANCE, noPlazaSuccess).getOrThrow()).getOrThrow();
			checks = check(checks, fallbackShop != null && persistedFallback.shops().size() == 1
				&& persistedFallback.shops().getFirst().equals(fallbackShop),
				"the fallback plot, palette, and unopened work progress survive a market save round trip");
			if (fallbackShop != null) {
				BlockPos naturalCut = Market.at(fallbackShop, 0, 0, 2);
				level.setBlock(naturalCut, Blocks.DIRT.defaultBlockState(), 3);
				int dirtBeforeFallbackCut = count(noStockOwner.getInventory(), Items.DIRT);
				List<ItemStack> fallbackDrops = Block.getDrops(level.getBlockState(naturalCut), level, naturalCut, null, noStockOwner, ItemStack.EMPTY);
				int expectedDirt = fallbackDrops.stream().filter(stack -> stack.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum();
				var fallbackCut = Market.clearMarketSiteStep(level, ref, fallbackShop, noStockOwner);
				checks = check(checks, fallbackCut.status() == NaturalSitePreparation.Status.CLEARED
					&& level.getBlockState(naturalCut).is(Blocks.AIR) && expectedDirt > 0
					&& count(noStockOwner.getInventory(), Items.DIRT) == dirtBeforeFallbackCut + expectedDirt
					&& Market.shop(ref, noStockOwner.getUUID()).progress() == 0,
					"a resident clears one safe natural fallback obstacle, keeps its exact drops, and leaves paid build progress untouched");
				checks = check(checks, Market.hut(fallbackShop, stallPalette).stream()
					.allMatch(step -> !step.free() && step.item() != null && step.cost() > 0),
					"fallback preparation keeps every later stall construction step paid");
			}
		checks = check(checks, fallbackShop != null
				&& Market.reserved(level, ref, fallbackShop.origin().offset(20, 0, 0), 12)
				&& !Market.reserved(level, ref, fallbackShop.origin().offset(22, 0, 0), 12),
				"a future plaza footprint overlapping the planned stall is reserved while a distant plaza site remains available");
			checks = check(checks, Network.treasuryEmeralds(ref) == treasuryBeforeNoPlazaSuccess + Market.STALL_RENT
				&& Market.wallet(level, noStockOwner).stream().mapToInt(box -> count(box, Items.EMERALD)).sum() == 0,
				"the no-plaza plan takes exactly one real rent payment after finding its site");
			if (originalZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, originalZones);
			for (Villager villager : residents) villager.getInventory().clearContent();

			residents.get(3).getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), otherMeeting));
			MarketData planned = new MarketData(Optional.of(origin), List.of(), 0, 0);
			Market.save(ref, planned);
			checks = check(checks, Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v)).size() == 3,
				"below-target fixture contains exactly three assigned residents");
			Market.plan(level, ref, now + 1);
			MarketData belowTarget = Market.of(ref);
			checks = check(checks, belowTarget.shops().isEmpty() && belowTarget.nextPlan() == now + 1 + Market.PLAN_RETRY_TICKS,
				"three residents do not create a stall and retry after the short population wait");

			residents.get(3).getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
			checks = check(checks, Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v)).size() == 4,
				"at-target fixture contains exactly four assigned residents");

			long noOwnerAt = now + 500;
			for (Villager villager : residents) {
				villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER));
			}
			Market.save(ref, new MarketData(Optional.of(origin), List.of(), noOwnerAt, 0));
			Market.plan(level, ref, noOwnerAt);
			MarketData noOwner = Market.of(ref);
			checks = check(checks, noOwner.shops().isEmpty() && noOwner.nextPlan() == noOwnerAt + Market.PLAN_RETRY_TICKS,
				"no eligible adult owner leaves the stall unplanned and schedules a bounded retry");
			long noOwnerPersisted = MarketData.CODEC.parse(JsonOps.INSTANCE,
				MarketData.CODEC.encodeStart(JsonOps.INSTANCE, noOwner).getOrThrow()).getOrThrow().nextPlan();
			checks = check(checks, noOwnerPersisted == noOwner.nextPlan(),
				"short retry time persists through the market data codec");
			Market.plan(level, ref, noOwnerAt + Market.PLAN_RETRY_TICKS - 1);
			checks = check(checks, Market.of(ref).equals(noOwner), "planning does not retry before the saved short deadline");
			MarketData unbuiltMarket = new MarketData(Optional.of(origin), List.of(), noOwnerAt, 0);
			int firstStallThreshold = Market.professionReassignmentPopulation(unbuiltMarket, false);
			checks = check(checks, firstStallThreshold == Market.FIRST_STALL_POPULATION
				&& Market.professionReassignmentPopulation(unbuiltMarket, true) == Market.OWNER_REASSIGNMENT_POPULATION,
				"only the initial living market can recruit a secondary profession at the four-resident start threshold");
			residents.get(0).setVillagerData(residents.get(0).getVillagerData().withProfession(level.registryAccess(), VillagerProfession.SHEPHERD));
			checks = check(checks, Market.eligibleOwner(residents.get(0), Market.FIRST_STALL_POPULATION, false, firstStallThreshold)
				&& !Market.eligibleOwner(residents.get(0), Market.FIRST_STALL_POPULATION - 1, false, firstStallThreshold)
				&& !Market.eligibleOwner(residents.get(1), Market.FIRST_STALL_POPULATION, false, firstStallThreshold),
				"the first stall may recruit an adult with a secondary profession while preserving protected farmer roles and the three-resident gate");
			checks = check(checks, !Market.eligibleOwner(residents.get(0), Market.FIRST_STALL_POPULATION, false,
				Market.OWNER_REASSIGNMENT_POPULATION),
				"secondary profession reassignment remains reserved for larger villages after the first market exists");
			residents.get(0).getInventory().setItem(0, new ItemStack(Items.EMERALD));
			List<Villager> earlyMarketOwners = Market.ownerCandidates(level, ref, residents,
				Market.FIRST_STALL_POPULATION, true, false, firstStallThreshold);
			checks = check(checks, earlyMarketOwners.size() == 1 && earlyMarketOwners.getFirst() == residents.get(0),
				"the first market selects the eligible non-core worker only when their own wallet pays real rent");
			residents.get(0).getInventory().clearContent();

			long paymentFailureAt = now + 1_000;
			residents.get(0).setVillagerData(residents.get(0).getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NONE));
			communal.clearContent();
			communal.setItem(0, new ItemStack(Items.EMERALD, 2));
			long treasuryBeforeFailure = Network.treasuryEmeralds(ref);
			Market.save(ref, new MarketData(Optional.of(origin), List.of(), paymentFailureAt, 0));
			Market.plan(level, ref, paymentFailureAt);
			MarketData paymentFailure = Market.of(ref);
			checks = check(checks, paymentFailure.shops().isEmpty() && paymentFailure.nextPlan() == paymentFailureAt + Market.PLAN_RETRY_TICKS,
				"unfunded private owner cannot rent a stall despite emeralds in the public chest");
			checks = check(checks, Market.wallet(level, residents.get(0)).stream().mapToInt(box -> count(box, Items.EMERALD)).sum() == 0
				&& count(communal, Items.EMERALD) == 2 && Network.treasuryEmeralds(ref) == treasuryBeforeFailure,
				"failed rent leaves the private wallet, public chest, and village cash unchanged");

			long successAt = now + 2_000;
			Villager walletless = residents.get(0);
			Villager employed = residents.get(1);
			Villager payer = residents.get(2);
			Villager protectedWorker = residents.get(3);
			walletless.getInventory().clearContent(); walletless.setVillagerData(walletless.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NONE));
			employed.getInventory().clearContent(); employed.getInventory().setItem(0, new ItemStack(Items.EMERALD));
			employed.setVillagerData(employed.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.SHEPHERD));
			payer.getInventory().clearContent(); payer.getInventory().setItem(0, new ItemStack(Items.EMERALD));
			payer.setVillagerData(payer.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NONE));
			protectedWorker.getInventory().clearContent(); protectedWorker.setVillagerData(protectedWorker.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER));
			BlockPos viableSite = Market.findSite(level, ref, origin, List.of());
			checks = check(checks, viableSite != null, "planner finds a safe, preparable footprint before collecting rent");
			checks = check(checks, viableSite.equals(Market.findSite(level, ref, origin, List.of())),
				"market site search is deterministic across bounded candidates");
			MarketData.Shop prepFixture = new MarketData.Shop(payer.getUUID(), viableSite,
				Direction.NORTH.get2DDataValue(), 0, 0, 0, now, List.of(), false, 0);
			BlockPos unusedCorner = Market.at(prepFixture, 3, 4, 0);
			level.setBlock(unusedCorner, Blocks.OAK_LEAVES.defaultBlockState(), 3);
			checks = check(checks, viableSite.equals(Market.prepareMarketSite(level, ref, viableSite, Direction.NORTH))
				&& level.getBlockState(unusedCorner).is(Blocks.OAK_LEAVES),
				"a leaf in the unused bounding-box corner does not reject a valid market footprint");
			var unusedCornerPreparation = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
			checks = check(checks, unusedCornerPreparation.status() == NaturalSitePreparation.Status.READY
				&& level.getBlockState(unusedCorner).is(Blocks.OAK_LEAVES),
				"worker preparation ignores and preserves a leaf outside all hut target cells");
			level.setBlock(unusedCorner, Blocks.AIR.defaultBlockState(), 3);
			BlockPos wallTarget = Market.at(prepFixture, -2, -2, 0);
			level.setBlock(wallTarget, Blocks.SHORT_GRASS.defaultBlockState(), 3);
			var animal = EntityTypes.COW.spawn(level, wallTarget, EntitySpawnReason.COMMAND);
			if (animal == null) throw new IllegalStateException("Could not create market clearing safety animal");
			try {
				animal.setNoAi(true);
				var occupied = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
				checks = check(checks, occupied.status() == NaturalSitePreparation.Status.WAITING_FOR_ENTITY
					&& level.getBlockState(wallTarget).is(Blocks.SHORT_GRASS),
					"market preparation waits without clearing natural cover under an animal");
				checks = check(checks, Market.prepareMarketSite(level, ref, viableSite, Direction.NORTH) == null,
					"site planning rejects an otherwise clear footprint while a living entity occupies a paid target");
			} finally {
				animal.discard();
			}
			var cleared = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
			checks = check(checks, cleared.status() == NaturalSitePreparation.Status.CLEARED
				&& level.getBlockState(wallTarget).isAir(),
				"resident market preparation clears one safe plant on an actual wall target");
			var resumedPreparation = Market.clearMarketSiteStep(level, ref,
				new MarketData.Shop(payer.getUUID(), viableSite, Direction.NORTH.get2DDataValue(), 0, 0, 0, now, List.of(), false, 0), payer);
			checks = check(checks, resumedPreparation.status() == NaturalSitePreparation.Status.READY,
				"rechecking the saved stall after a reload recognizes an already-cleared obstacle");
			BlockPos protectedChest = wallTarget;
			level.setBlock(protectedChest, Blocks.CHEST.defaultBlockState(), 3);
			var protectedResult = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
			checks = check(checks, protectedResult.status() == NaturalSitePreparation.Status.BLOCKED
				&& level.getBlockState(protectedChest).is(Blocks.CHEST) && level.getBlockEntity(protectedChest) != null,
				"market preparation refuses and preserves a player chest with its block entity");
			level.setBlock(protectedChest, Blocks.AIR.defaultBlockState(), 3);

			level.setBlock(wallTarget, Blocks.DIRT_PATH.defaultBlockState(), 3);
			var pathResult = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
			checks = check(checks, pathResult.status() == NaturalSitePreparation.Status.BLOCKED
				&& level.getBlockState(wallTarget).is(Blocks.DIRT_PATH),
				"market preparation refuses and preserves an existing path instead of treating it as natural cover");
			level.setBlock(wallTarget, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 3);
			var persistentLeaf = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
			checks = check(checks, persistentLeaf.status() == NaturalSitePreparation.Status.BLOCKED
				&& level.getBlockState(wallTarget).getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT),
				"market preparation preserves persistent leaves, which may be player-placed");
			level.setBlock(wallTarget, Blocks.FARMLAND.defaultBlockState(), 3);
			var farmBlocker = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
			checks = check(checks, farmBlocker.status() == NaturalSitePreparation.Status.BLOCKED
				&& level.getBlockState(wallTarget).is(Blocks.FARMLAND),
				"market preparation leaves farm land untouched");
			level.setBlock(wallTarget, Blocks.AIR.defaultBlockState(), 3);

			BlockPos treeRoot = Market.at(prepFixture, -2, 0, 0);
			level.setBlock(treeRoot.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			for (int dy = 0; dy <= 2; dy++) level.setBlock(treeRoot.above(dy), Blocks.OAK_LOG.defaultBlockState(), 3);
			BlockPos treeCanopy = treeRoot.above(4);
			level.setBlock(treeCanopy, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false), 3);
			BlockPos treeTip = treeRoot.above(2);
			checks = check(checks, viableSite.equals(Market.prepareMarketSite(level, ref, viableSite, Direction.NORTH)),
				"a naturally rooted tree inside the hut targets is accepted as a preparable market site");
			List<ItemStack> inventoryBeforeCapacityWait = copy(payer.getInventory());
			for (int slot = 0; slot < payer.getInventory().getContainerSize(); slot++) payer.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			var fullInventory = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
			checks = check(checks, fullInventory.status() == NaturalSitePreparation.Status.WAITING_FOR_INVENTORY
				&& level.getBlockState(treeTip).is(Blocks.OAK_LOG),
				"tree removal stays pending when the worker cannot safely carry the real block drop");
			restore(payer.getInventory(), inventoryBeforeCapacityWait);
			payer.getInventory().setItem(1, new ItemStack(Items.IRON_AXE));
			payer.getInventory().setItem(2, new ItemStack(Items.SHEARS));
			int logsBefore = count(payer.getInventory(), Items.OAK_LOG);
			int leavesBefore = count(payer.getInventory(), Items.OAK_LEAVES);
			var topLogClear = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
			checks = check(checks, topLogClear.status() == NaturalSitePreparation.Status.CLEARED
				&& level.getBlockState(treeTip).isAir() && level.getBlockState(treeTip.below()).is(Blocks.OAK_LOG)
				&& count(payer.getInventory(), Items.OAK_LOG) == logsBefore + 1,
				"tree preparation removes only the top target log per visit and stores its drop in the worker inventory");
			int treeSteps = 1;
			NaturalSitePreparation.Scan treePreparation;
			do {
				treePreparation = Market.clearMarketSiteStep(level, ref, prepFixture, payer);
				if (treePreparation.status() == NaturalSitePreparation.Status.CLEARED) treeSteps++;
			} while (treePreparation.status() == NaturalSitePreparation.Status.CLEARED && treeSteps < 12);
			checks = check(checks, treeSteps == 4 && treePreparation.status() == NaturalSitePreparation.Status.READY
				&& level.getBlockState(treeCanopy).isAir() && count(payer.getInventory(), Items.OAK_LOG) == logsBefore + 3
				&& count(payer.getInventory(), Items.OAK_LEAVES) == leavesBefore + 1,
				"incremental tree cleanup reaches a ready saved-site state and conserves all log and leaf drops");

			BlockPos raisedGround = viableSite.east();
			level.setBlock(raisedGround, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			var grade = NaturalSitePreparation.inspectNaturalGrade(level, viableSite, 2, 1);
			BlockPos adjustedSite = Market.findSite(level, ref, origin, List.of());
			checks = check(checks, grade.status() == NaturalSitePreparation.Status.READY
				&& adjustedSite != null && grade.highSurfaceY() == viableSite.getY() + 1 && grade.lowSurfaceY() == viableSite.getY()
				&& adjustedSite.getX() == viableSite.getX() && adjustedSite.getZ() == viableSite.getZ()
				&& adjustedSite.getY() == grade.highSurfaceY(),
				"market pad accepts and levels one natural block of relief with its paid floor and support layer");
			MarketData.Shop terraceFixture = new MarketData.Shop(java.util.UUID.randomUUID(), adjustedSite,
				Direction.NORTH.get2DDataValue(), 0, 0, 0, now, stallPalette.ids(), false, 0);
			List<Step> terraceSteps = Market.hut(terraceFixture, stallPalette);
			checks = check(checks, paidStepAt(terraceSteps, Market.at(terraceFixture, 1, 0, -2))
				&& paidStepAt(terraceSteps, Market.at(terraceFixture, 1, 0, -1))
				&& paidStepAt(terraceSteps, Market.at(terraceFixture, 0, 0, -2))
				&& paidStepAt(terraceSteps, Market.at(terraceFixture, 0, 0, -1))
				&& Market.at(terraceFixture, 1, 0, -1).equals(raisedGround)
				&& Market.at(terraceFixture, 0, 0, -2).equals(viableSite.below()),
				"high and low terrace columns receive their paid base and floor layers instead of free earthwork");
			level.setBlock(raisedGround.above(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			var steepGrade = NaturalSitePreparation.inspectNaturalGrade(level, viableSite, 2, 1);
			checks = check(checks, steepGrade.status() == NaturalSitePreparation.Status.BLOCKED,
				"market pad rejects terrain relief greater than one block");
			level.setBlock(raisedGround.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(raisedGround, Blocks.AIR.defaultBlockState(), 3);
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				BlockPos column = viableSite.offset(dx, 0, dz);
				level.setBlock(column, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
				level.setBlock(column.above(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			}
			var offsetGrade = NaturalSitePreparation.inspectMarketGrade(level, viableSite, 2, 1);
			BlockPos offsetSite = Market.findSite(level, ref, origin, List.of());
			checks = check(checks, offsetGrade.status() == NaturalSitePreparation.Status.READY
				&& offsetGrade.lowSurfaceY() == viableSite.getY() + 2 && offsetGrade.highSurfaceY() == viableSite.getY() + 2
				&& offsetSite != null && offsetSite.getX() == viableSite.getX() && offsetSite.getZ() == viableSite.getZ()
				&& offsetSite.getY() == viableSite.getY() + 2
				&& offsetSite.equals(Market.prepareMarketSite(level, ref, viableSite, Direction.NORTH)),
				"a flat two-block terrace offset is accepted and the selected market site follows its real surface");
			adjustedSite = offsetSite;
			checks = check(checks, adjustedSite != null,
				"bounded surface adjustment leaves a concrete market plan location for persistence");
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				BlockPos column = viableSite.offset(dx, 0, dz);
				for (int dy = 2; dy < 5; dy++) level.setBlock(column.above(dy), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			}
			var remoteGrade = NaturalSitePreparation.inspectMarketGrade(level, viableSite, 2, 1);
			BlockPos remoteSite = Market.prepareMarketSite(level, ref, viableSite, Direction.NORTH);
			checks = check(checks, remoteGrade.status() == NaturalSitePreparation.Status.READY
				&& remoteGrade.highSurfaceY() == viableSite.getY() + 5
				&& remoteSite != null && remoteSite.getY() == viableSite.getY() + 5,
				"a flat natural site five blocks above the inherited anchor is rebased to its real surface");
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
				BlockPos column = viableSite.offset(dx, 0, dz);
				for (int dy = 2; dy < 5; dy++) level.setBlock(column.above(dy), Blocks.AIR.defaultBlockState(), 3);
			}
			// Keep the prepared terrace through Market.plan below so the saved Shop origin proves the adjusted site survives planning.
			communal.clearContent(); communal.setItem(0, new ItemStack(Items.EMERALD, 2));
			long treasuryBeforeSuccess = Network.treasuryEmeralds(ref);
			Market.save(ref, new MarketData(Optional.of(origin), List.of(), successAt, 0));
			Market.plan(level, ref, successAt);
			MarketData firstShop = Market.of(ref);
			MarketData.Shop shop = firstShop.shops().isEmpty() ? null : firstShop.shops().getFirst();
			checks = check(checks, shop != null && shop.owner().equals(payer.getUUID()) && shop.origin().equals(adjustedSite),
				"at four residents the eligible funded adult gets the first shop even after an unusable candidate");
			checks = check(checks, count(payer.getInventory(), Items.EMERALD) == 0
				&& count(employed.getInventory(), Items.EMERALD) == 1 && Network.treasuryEmeralds(ref) == treasuryBeforeSuccess + Market.STALL_RENT,
				"successful planning charges exactly one real emerald to the selected owner's private wallet");
			checks = check(checks, count(communal, Items.EMERALD) == 2 && firstShop.nextPlan() == successAt + Market.PLAN_INTERVAL_TICKS,
				"successful plan preserves public funds and uses the normal post-plan interval");
			var encoded = MarketData.CODEC.encodeStart(JsonOps.INSTANCE, firstShop).getOrThrow();
			checks = check(checks, MarketData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(firstShop),
				"first stall owner, site, and next plan survive codec persistence");
			Market.plan(level, ref, successAt);
			checks = check(checks, Market.of(ref).equals(firstShop), "the persisted post-plan deadline prevents a duplicate stall");
			return checks;
		} finally {
			Market.save(ref, originalMarket == null ? MarketData.empty() : originalMarket);
			long treasuryAfter = Network.treasuryEmeralds(ref);
			if (treasuryAfter > treasuryBefore) Network.spendTreasuryEmeralds(ref, treasuryAfter - treasuryBefore);
			else if (treasuryAfter < treasuryBefore) Network.creditTreasuryEmeralds(ref, treasuryBefore - treasuryAfter);
			communal.clearContent(); restore(communal, communalBefore);
			if (originalZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, originalZones);
			ref.data().buildProjects.clear();
			ref.data().buildProjects.addAll(originalProjects);
			for (Villager villager : fixtures) villager.discard();
			oldMeetings.forEach((villager, meeting) -> {
				if (meeting.isPresent()) villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, meeting.get());
				else villager.getBrain().eraseMemory(MemoryModuleType.MEETING_POINT);
			});
			restoreSites(level, siteBefore);
			if (originalMarket == null) ref.bellEntity().removeAttached(VillagerPlus.MARKET);
			ref.dirty();
		}
	}

	private static void prepareViableSites(ServerLevel level, BlockPos center, Map<BlockPos, Cell> before) {
		Set<BlockPos> columns = new LinkedHashSet<>();
		int groundY = center.getY() - 1;
		for (Direction face : Direction.Plane.HORIZONTAL) {
			BlockPos candidate = Plaza.clearApproachRoutes(center, center.relative(face, 13), 4);
			if (candidate == null) continue;
			for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++) {
				BlockPos column = candidate.offset(dx, 0, dz);
				level.getChunkAt(column);
				columns.add(column.immutable());
			}
		}
		for (BlockPos column : columns) {
			for (int dy = -1; dy <= 6; dy++) {
				BlockPos pos = column.offset(0, groundY - column.getY() + dy, 0).immutable();
				BlockEntity entity = level.getBlockEntity(pos);
				before.putIfAbsent(pos, new Cell(level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
			}
		}
		for (BlockPos column : columns) {
			BlockPos ground = new BlockPos(column.getX(), groundY, column.getZ());
			level.setBlock(ground, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			for (int dy = 1; dy <= 6; dy++) level.setBlock(ground.above(dy), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static boolean nearMarketPlot(BlockPos first, BlockPos second, int radius) {
		return Math.abs((long)first.getX() - second.getX()) <= radius
			&& Math.abs((long)first.getZ() - second.getZ()) <= radius;
	}

	/** Protect every search location outside one small synthetic fallback parcel. */
	private static List<ZonePolicy.Zone> isolateMarketFallbackPlot(BlockPos site) {
		int edge = 5;
		int far = 1_000_000;
		return List.of(
			new ZonePolicy.Zone("market-fallback-west", "housing", -far, -far, site.getX() - edge, far),
			new ZonePolicy.Zone("market-fallback-east", "housing", site.getX() + edge, -far, far, far),
			new ZonePolicy.Zone("market-fallback-north", "housing", -far, -far, far, site.getZ() - edge),
			new ZonePolicy.Zone("market-fallback-south", "housing", -far, site.getZ() + edge, far, far));
	}

	private static void prepareFlatMarketSite(ServerLevel level, BlockPos center, Map<BlockPos, Cell> before) {
		// Cover the entire site-protection margin too; otherwise a real structure just outside the
		// synthetic plot can correctly reject this fixture before it reaches the clearable plant.
		for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
			BlockPos column = center.offset(dx, 0, dz);
			level.getChunkAt(column);
			for (int dy = -3; dy <= 7; dy++) {
				BlockPos pos = column.offset(0, dy, 0).immutable();
				BlockEntity entity = level.getBlockEntity(pos);
				before.putIfAbsent(pos, new Cell(level.getBlockState(pos), entity == null ? null
					: entity.saveWithFullMetadata(level.registryAccess())));
			}
		}
		for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
			level.setBlock(center.offset(dx, -3, dz), Blocks.DIRT.defaultBlockState(), 3);
			level.setBlock(center.offset(dx, -2, dz), Blocks.DIRT.defaultBlockState(), 3);
			BlockPos ground = center.offset(dx, -1, dz);
			level.setBlock(ground, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
			for (int dy = 0; dy <= 7; dy++) level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
		}
	}

	private static void restoreSites(ServerLevel level, Map<BlockPos, Cell> before) {
		before.keySet().forEach(level::removeBlockEntity);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
		before.forEach((pos, cell) -> {
			if (cell.blockEntity() == null) return;
			BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
			if (entity != null) level.setBlockEntity(entity);
		});
	}

	private static List<ItemStack> copy(Container container) {
		List<ItemStack> result = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) result.add(container.getItem(i).copy());
		return result;
	}

	private static void restore(Container container, List<ItemStack> items) {
		for (int i = 0; i < Math.min(container.getContainerSize(), items.size()); i++) container.setItem(i, items.get(i).copy());
	}

	private static List<StepShape> shape(List<Step> steps) {
		return steps.stream().map(step -> new StepShape(step.pos().immutable(), step.item(), step.cost(), step.free(),
			step.make().apply(step.item() == null ? Blocks.AIR : Block.byItem(step.item())))).toList();
	}

	private static Direction facingToward(BlockPos center, BlockPos site) {
		int dx = site.getX() - center.getX();
		int dz = site.getZ() - center.getZ();
		Direction outward = Math.abs(dx) >= Math.abs(dz)
			? (dx < 0 ? Direction.WEST : Direction.EAST)
			: (dz < 0 ? Direction.NORTH : Direction.SOUTH);
		return outward.getOpposite();
	}

	private static boolean paidStepAt(List<Step> steps, BlockPos position) {
		return steps.stream().anyMatch(step -> step.pos().equals(position) && !step.free()
			&& step.item() != null && step.cost() > 0);
	}

	private static int count(Container container, net.minecraft.world.item.Item item) {
		int amount = 0;
		for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).is(item)) amount += container.getItem(i).getCount();
		return amount;
	}

	private static int check(int checks, boolean passed, String description) {
		if (!passed) throw new IllegalStateException("Market planning: " + description);
		return checks + 1;
	}
}
