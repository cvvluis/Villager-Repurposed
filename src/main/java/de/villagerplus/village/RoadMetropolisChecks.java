package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;

/** D06 gate boundaries: reciprocal metropolis, civic progress, population, public stores and rail inputs. */
public final class RoadMetropolisChecks {
	private RoadMetropolisChecks() { }

	private record Endpoint(VillageRef ref, Container center, Container granary, Container warehouse) { }
	private record Materials(int iron, int gold, int redstone, int rails) { }

	public static int run(ServerLevel level, BlockPos at) {
		int homeY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
		BlockPos homeBell = new BlockPos(at.getX(), homeY, at.getZ());
		BlockPos peerXZ = homeBell.offset(256, 0, 0);
		BlockPos peerBell = new BlockPos(peerXZ.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
			peerXZ.getX(), peerXZ.getZ()), peerXZ.getZ());
		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> originalBlocks = new LinkedHashMap<>();
		captureFixtureBlocks(level, originalBlocks, homeBell);
		captureFixtureBlocks(level, originalBlocks, peerBell);
		List<Villager> residents = new ArrayList<>();
		VillageRef home = null;
		VillageRef peer = null;
		TradeNetwork oldHomeNetwork = null;
		TradeNetwork oldPeerNetwork = null;
		Map<String, BlockPos> oldHomeSpecials = Map.of();
		Map<String, BlockPos> oldPeerSpecials = Map.of();
		Optional<BlockPos> oldHomeSchool = Optional.empty();
		Optional<BlockPos> oldPeerSchool = Optional.empty();
		Optional<BlockPos> oldHomeChest = Optional.empty();
		Optional<BlockPos> oldPeerChest = Optional.empty();
		Optional<BlockPos> oldHomeLager = Optional.empty();
		Optional<BlockPos> oldPeerLager = Optional.empty();
		List<BlockPos> oldHomeLagerMore = List.of();
		List<BlockPos> oldPeerLagerMore = List.of();
		List<BlockPos> oldHomeLagerChests = List.of();
		List<BlockPos> oldPeerLagerChests = List.of();
		Suite suite = new Suite();
		try {
			home = Checks090.fixtureForEquipment(level, homeBell);
			peer = Checks090.fixtureForEquipment(level, peerBell);
			oldHomeNetwork = Network.of(home.bellEntity());
			oldPeerNetwork = Network.of(peer.bellEntity());
			oldHomeSpecials = Map.copyOf(Specials.all(home));
			oldPeerSpecials = Map.copyOf(Specials.all(peer));
			oldHomeSchool = home.data().extra.school;
			oldPeerSchool = peer.data().extra.school;
			oldHomeChest = home.data().chest;
			oldPeerChest = peer.data().chest;
			oldHomeLager = home.data().extra.lager;
			oldPeerLager = peer.data().extra.lager;
			oldHomeLagerMore = List.copyOf(home.data().extra.lagerMore);
			oldPeerLagerMore = List.copyOf(peer.data().extra.lagerMore);
			oldHomeLagerChests = List.copyOf(home.data().extra.lagerChests);
			oldPeerLagerChests = List.copyOf(peer.data().extra.lagerChests);
			Endpoint a = prepareEndpoint(level, home, originalBlocks);
			Endpoint b = prepareEndpoint(level, peer, originalBlocks);
			stockMetallurgy(a);
			stockMetallurgy(b);
			suite.check(RailResources.hasCentralStorage(level, home) && RailResources.hasCentralStorage(level, peer),
				"both villages have a real center chest and a reachable chest in registered STORAGE");
			setLinks(level, home, peer, Roads.METROPOLIS, Roads.METROPOLIS, true, true,
				Network.dim(level));
			List<Container> watched = watchedStores(level, a, b);

			for (int i = 0; i < Roads.RAIL_POP - 1; i++) residents.add(spawnResident(level, home, i, originalBlocks));
			for (int i = 0; i < Roads.RAIL_POP - 1; i++) residents.add(spawnResident(level, peer, i, originalBlocks));
			suite.check(!Roads.railReady(level, home) && !Roads.railReady(level, peer),
				"seven assigned residents at either endpoint stay below the population boundary of eight");
			suite.rejectProject(level, home, peer, watched,
				"either endpoint below eight residents denies rail without spending materials");
			residents.add(spawnResident(level, home, Roads.RAIL_POP - 1, originalBlocks));
			suite.check(Roads.railReady(level, home) && !Roads.railReady(level, peer),
				"eight home residents pass while a seven-resident peer remains closed");
			suite.rejectProject(level, home, peer, watched,
				"peer population below eight denies the bilateral project without spending materials");
			residents.add(spawnResident(level, peer, Roads.RAIL_POP - 1, originalBlocks));
			suite.check(Roads.railReady(level, home) && Roads.railReady(level, peer),
				"both local endpoints pass at exactly eight residents");
			suite.check(RailResources.hasMetallurgyStock(level, home) && RailResources.hasMetallurgyStock(level, peer)
				&& materials(watched).rails() == 0
				&& !RailResources.canPay(level, home, false, false) && !RailResources.canPay(level, peer, false, false),
				"exact iron, gold and redstone minimums pass without any manufactured rail stock");
			suite.check(Roads.railProjectReady(level, home, peer),
				"fully prepared endpoints unlock with a reciprocal relation of exactly ninety");

			setLinks(level, home, peer, Roads.METROPOLIS - 1, Roads.METROPOLIS - 1, true, true,
				Network.dim(level));
			suite.rejectProject(level, home, peer, watched,
				"a reciprocal relation of eighty-nine remains below metropolis unlock");
			setLinks(level, home, peer, Roads.METROPOLIS, Roads.METROPOLIS, false, true,
				Network.dim(level));
			suite.rejectProject(level, home, peer, watched,
				"a missing home-to-peer metropolis link cannot unlock rail");
			setLinks(level, home, peer, Roads.METROPOLIS, Roads.METROPOLIS, true, false,
				Network.dim(level));
			suite.rejectProject(level, home, peer, watched,
				"a missing peer-to-home metropolis link cannot unlock rail");
			setLinks(level, home, peer, Roads.METROPOLIS, Roads.METROPOLIS, true, true, "minecraft:the_nether");
			suite.rejectProject(level, home, peer, watched,
				"metropolis links in a different dimension cannot unlock rail");
			setLinks(level, home, peer, Roads.METROPOLIS, Roads.METROPOLIS, true, true,
				Network.dim(level));
			setApproval(home, peer, false, true);
			suite.rejectProject(level, home, peer, watched,
				"both endpoints must approve the large project; home approval is required");
			setApproval(home, peer, true, false);
			suite.rejectProject(level, home, peer, watched,
				"both endpoints must approve the large project; peer approval is required");
			setApproval(home, peer, true, true);

			for (String type : new String[] {Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.KITCHEN, Blueprints.STATION}) {
				BlockPos origin = Specials.get(home, type);
				Specials.remove(home, type);
				suite.rejectProject(level, home, peer, watched, "missing " + type + " milestone keeps rail locked");
				Specials.set(home, type, origin);
			}
			Optional<BlockPos> school = home.data().extra.school;
			home.data().extra.school = Optional.empty();
			home.dirty();
			suite.rejectProject(level, home, peer, watched, "missing school milestone keeps rail locked");
			home.data().extra.school = school;
			home.dirty();

		for (String type : new String[] {Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.STATION}) {
			BlockPos origin = Specials.get(home, type);
			BlockPos emptyOrigin = origin.above(32);
			level.getChunkAt(emptyOrigin);
			Specials.set(home, type, emptyOrigin);
			suite.rejectProject(level, home, peer, watched,
				"a " + type + " marker without a real public container does not count as a depot");
			Specials.set(home, type, origin);
		}

			Optional<BlockPos> center = home.data().chest;
			BlockPos emptyCenter = home.bell().offset(24, 0, 0);
			level.getChunkAt(emptyCenter);
			home.data().chest = Optional.of(emptyCenter);
			home.dirty();
			suite.rejectProject(level, home, peer, watched,
				"a registered center position without a real chest keeps rail locked");
			home.data().chest = center;
			home.dirty();

		Optional<BlockPos> lager = home.data().extra.lager;
		home.data().extra.lager = Optional.empty();
		home.dirty();
		suite.rejectProject(level, home, peer, watched,
			"a center chest and milestone depots cannot substitute for the registered STORAGE structure");
		home.data().extra.lager = lager;
		home.dirty();

		BlockPos realLager = lager.orElseThrow();
		BlockPos emptyLager = realLager.above(32);
		level.getChunkAt(emptyLager);
		home.data().extra.lager = Optional.of(emptyLager);
		home.dirty();
		suite.rejectProject(level, home, peer, watched,
			"a registered STORAGE origin without its reachable container does not unlock rail");
		home.data().extra.lager = lager;
		home.dirty();

		List<BlockPos> lagerChests = List.copyOf(home.data().extra.lagerChests);
		BlockPos emptyWarehouseCell = realLager.north(4).east(4);
		level.getChunkAt(emptyWarehouseCell);
		home.data().extra.lagerChests.clear();
		home.data().extra.lagerChests.add(emptyWarehouseCell);
		home.dirty();
		suite.rejectProject(level, home, peer, watched,
			"a STORAGE origin with no public container at its registered chest position stays locked");
		home.data().extra.lagerChests.clear();
		home.data().extra.lagerChests.addAll(lagerChests);
		home.dirty();

			testResourceBoundaries(level, suite, a, b, watched);
			ItemStack peerRedstone = b.center().getItem(2).copy();
			b.center().setItem(2, new ItemStack(Items.REDSTONE, peerRedstone.getCount() - 1));
			suite.rejectProject(level, home, peer, watched,
				"a peer short of its redstone reserve cannot unlock the pair");
			b.center().setItem(2, peerRedstone);
			return suite.passed;
		} finally {
			for (Villager villager : residents) {
				villager.discard();
				Villages.forget(villager.getUUID());
			}
			if (home != null) {
				if (oldHomeNetwork != null) Network.save(home.bellEntity(), oldHomeNetwork);
				home.bellEntity().setAttached(VillagerPlus.SPECIALS, oldHomeSpecials);
				home.data().extra.school = oldHomeSchool;
				home.data().chest = oldHomeChest;
				home.data().extra.lager = oldHomeLager;
				home.data().extra.lagerMore.clear();
				home.data().extra.lagerMore.addAll(oldHomeLagerMore);
				home.data().extra.lagerChests.clear();
				home.data().extra.lagerChests.addAll(oldHomeLagerChests);
				home.dirty();
			}
			if (peer != null) {
				if (oldPeerNetwork != null) Network.save(peer.bellEntity(), oldPeerNetwork);
				peer.bellEntity().setAttached(VillagerPlus.SPECIALS, oldPeerSpecials);
				peer.data().extra.school = oldPeerSchool;
				peer.data().chest = oldPeerChest;
				peer.data().extra.lager = oldPeerLager;
				peer.data().extra.lagerMore.clear();
				peer.data().extra.lagerMore.addAll(oldPeerLagerMore);
				peer.data().extra.lagerChests.clear();
				peer.data().extra.lagerChests.addAll(oldPeerLagerChests);
				peer.dirty();
			}
			restoreBlocks(level, originalBlocks);
		}
	}

	private static Endpoint prepareEndpoint(ServerLevel level, VillageRef ref,
			Map<BlockPos, net.minecraft.world.level.block.state.BlockState> originalBlocks) {
		Map<String, BlockPos> origins = new LinkedHashMap<>();
		origins.put(Blueprints.GRANARY, ref.bell().north(16));
		origins.put(Blueprints.TOOLSHED, ref.bell().north(40));
		origins.put(Blueprints.KITCHEN, ref.bell().north(64));
		origins.put(Blueprints.STATION, ref.bell().north(88));
		BlockPos warehouseOrigin = ref.bell().north(136);
		BlockPos school = ref.bell().north(112);
		Map<String, BlockPos> depotChests = new LinkedHashMap<>();
		for (String type : new String[] {Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.STATION}) {
			BlockPos origin = origins.get(type);
			level.getChunkAt(origin);
			Specials.set(ref, type, origin);
			BlockPos chest = origin.east(2);
			placeChest(level, chest, originalBlocks);
			depotChests.put(type, chest);
		}
		level.getChunkAt(warehouseOrigin);
		BlockPos warehouseChest = warehouseOrigin.east(2);
		placeChest(level, warehouseChest, originalBlocks);
		ref.data().extra.lager = Optional.of(warehouseOrigin);
		ref.data().extra.lagerMore.clear();
		ref.data().extra.lagerChests.clear();
		ref.data().extra.lagerChests.add(warehouseChest);
		level.getChunkAt(origins.get(Blueprints.KITCHEN));
		level.getChunkAt(school);
		Specials.set(ref, Blueprints.KITCHEN, origins.get(Blueprints.KITCHEN));
		ref.data().extra.school = Optional.of(school);
		ref.dirty();
		Container center = ref.chest(level);
		Container granary = HopperBlockEntity.getContainerAt(level, depotChests.get(Blueprints.GRANARY));
		Container warehouse = HopperBlockEntity.getContainerAt(level, warehouseChest);
		if (center == null || granary == null || warehouse == null) {
			throw new IllegalStateException("D06 check fixture has no real center, granary, or STORAGE container");
		}
		return new Endpoint(ref, center, granary, warehouse);
	}

	private static void placeChest(ServerLevel level, BlockPos pos,
			Map<BlockPos, net.minecraft.world.level.block.state.BlockState> originalBlocks) {
		level.getChunkAt(pos);
		rememberBlock(level, originalBlocks, pos.below());
		rememberBlock(level, originalBlocks, pos);
		level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
		if (HopperBlockEntity.getContainerAt(level, pos) == null) {
			throw new IllegalStateException("D06 check fixture could not create a public chest at " + pos.toShortString());
		}
	}

	private static void stockMetallurgy(Endpoint endpoint) {
		endpoint.center().clearContent();
		endpoint.warehouse().clearContent();
		endpoint.center().setItem(0, new ItemStack(Items.IRON_INGOT, RailResources.MIN_IRON_INGOTS / 2));
		endpoint.center().setItem(1, new ItemStack(Items.GOLD_INGOT, RailResources.MIN_GOLD_INGOTS / 2));
		endpoint.center().setItem(2, new ItemStack(Items.REDSTONE, RailResources.MIN_REDSTONE / 2));
		endpoint.warehouse().setItem(0, new ItemStack(Items.IRON_INGOT, RailResources.MIN_IRON_INGOTS / 2));
		endpoint.warehouse().setItem(1, new ItemStack(Items.GOLD_INGOT, RailResources.MIN_GOLD_INGOTS / 2));
		endpoint.warehouse().setItem(2, new ItemStack(Items.REDSTONE, RailResources.MIN_REDSTONE / 2));
	}

	private static void testResourceBoundaries(ServerLevel level, Suite suite, Endpoint a, Endpoint b, List<Container> watched) {
		String[] labels = {"iron", "gold", "redstone"};
		Item[] items = {Items.IRON_INGOT, Items.GOLD_INGOT, Items.REDSTONE};
		int[] slots = {0, 1, 2};
		for (Endpoint endpoint : new Endpoint[] {a, b}) {
			for (int i = 0; i < items.length; i++) {
				ItemStack saved = endpoint.center().getItem(slots[i]).copy();
				endpoint.center().setItem(slots[i], new ItemStack(items[i], saved.getCount() - 1));
				Materials before = materials(watched);
				suite.check(!RailResources.hasMetallurgyStock(level, endpoint.ref()) && !Roads.railReady(level, endpoint.ref()),
					labels[i] + " reserve one below its minimum denies this endpoint's unlock");
				suite.check(before.equals(materials(watched)), labels[i] + " threshold rejection leaves every village's rail stock untouched");
				endpoint.center().setItem(slots[i], saved);
			}
		}
	}

	private static void setLinks(ServerLevel level, VillageRef home, VillageRef peer, int homeRelation,
			int peerRelation, boolean homeLinks, boolean peerLinks, String dimension) {
		List<TradeNetwork.Link> homeLinksList = homeLinks ? List.of(link(dimension, peer.bell(), "Rail peer", homeRelation)) : List.of();
		List<TradeNetwork.Link> peerLinksList = peerLinks ? List.of(link(dimension, home.bell(), "Rail home", peerRelation)) : List.of();
		Network.save(home.bellEntity(), new TradeNetwork(List.of(), homeLinksList, Map.of(), 0, 0));
		Network.save(peer.bellEntity(), new TradeNetwork(List.of(), peerLinksList, Map.of(), 0, 0));
		setApproval(home, peer, true, true);
	}

	private static TradeNetwork.Link link(String dimension, BlockPos bell, String name, int relation) {
		return new TradeNetwork.Link(dimension, bell, name, relation, true, 0, 0);
	}

	private static void setApproval(VillageRef home, VillageRef peer, boolean approveHome, boolean approvePeer) {
		String key = Network.pairKey(home, peer, "large_project_approval");
		Network.save(home.bellEntity(), Network.of(home.bellEntity()).withCooldown(key, approveHome ? 1L : 0L));
		Network.save(peer.bellEntity(), Network.of(peer.bellEntity()).withCooldown(key, approvePeer ? 1L : 0L));
	}

	private static Villager spawnResident(ServerLevel level, VillageRef ref, int index,
			Map<BlockPos, net.minecraft.world.level.block.state.BlockState> originalBlocks) {
		int chunkX = ref.bell().getX() >> 4;
		int chunkZ = ref.bell().getZ() >> 4;
		BlockPos spawn = new BlockPos((chunkX << 4) + 2 + (index % 4), ref.bell().getY(),
			(chunkZ << 4) + 2 + (index / 4));
		level.getChunkAt(spawn);
		for (BlockPos pos : new BlockPos[] {spawn.below(), spawn, spawn.above()}) rememberBlock(level, originalBlocks, pos);
		level.setBlock(spawn.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(spawn, Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(spawn.above(), Blocks.AIR.defaultBlockState(), 3);
		Villager villager = EntityTypes.VILLAGER.spawn(level, spawn, EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("D06 check fixture resident did not spawn");
		if (level.getEntity(villager.getUUID()) != villager && !level.addFreshEntity(villager)) {
			throw new IllegalStateException("D06 check fixture resident was not added to the server level");
		}
		villager.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		villager.addTag("vp090_fixture");
		villager.setInvulnerable(true);
		villager.setNoAi(true);
		villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
		villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), ref.bell().north(4 + index)));
		return villager;
	}

	private static List<Container> watchedStores(ServerLevel level, Endpoint a, Endpoint b) {
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Container> out = new ArrayList<>();
		for (Container box : RailResources.stores(level, a.ref())) if (seen.add(box)) out.add(box);
		for (Container box : RailResources.stores(level, b.ref())) if (seen.add(box)) out.add(box);
		return out;
	}

	private static Materials materials(List<Container> boxes) {
		int iron = 0, gold = 0, redstone = 0, rails = 0;
		for (Container box : boxes) {
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (stack.is(Items.IRON_INGOT)) iron += stack.getCount();
				else if (stack.is(Items.GOLD_INGOT)) gold += stack.getCount();
				else if (stack.is(Items.REDSTONE)) redstone += stack.getCount();
				else if (stack.is(Items.RAIL)) rails += stack.getCount();
			}
		}
		return new Materials(iron, gold, redstone, rails);
	}

	private static void captureFixtureBlocks(ServerLevel level,
			Map<BlockPos, net.minecraft.world.level.block.state.BlockState> originalBlocks, BlockPos bell) {
		BlockPos center = bell.east(3);
		for (BlockPos pos : new BlockPos[] {bell.below(), bell, center.below(), center}) rememberBlock(level, originalBlocks, pos);
	}

	private static void rememberBlock(ServerLevel level,
			Map<BlockPos, net.minecraft.world.level.block.state.BlockState> originalBlocks, BlockPos pos) {
		if (!level.hasChunkAt(pos)) level.getChunkAt(pos);
		if (!originalBlocks.containsKey(pos)) {
			if (level.getBlockEntity(pos) != null) {
				throw new IllegalStateException("D06 check fixture will not overwrite a block entity at " + pos.toShortString());
			}
			originalBlocks.put(pos.immutable(), level.getBlockState(pos));
		}
	}

	private static void restoreBlocks(ServerLevel level,
			Map<BlockPos, net.minecraft.world.level.block.state.BlockState> originalBlocks) {
		List<Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState>> entries = new ArrayList<>(originalBlocks.entrySet());
		Collections.reverse(entries);
		for (Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> entry : entries) {
			if (level.hasChunkAt(entry.getKey())) level.setBlock(entry.getKey(), entry.getValue(), 3);
		}
	}

	private static final class Suite {
		private int passed;

		void check(boolean ok, String message) {
			if (!ok) throw new IllegalStateException("D06 rail unlock: " + message);
			passed++;
			System.out.println("D06 RAIL PASS " + message);
		}

		void rejectProject(ServerLevel level, VillageRef home, VillageRef peer, List<Container> watched, String message) {
			Materials before = materials(watched);
			check(!Roads.railProjectReady(level, home, peer), message);
			check(before.equals(materials(watched)), message + " (no iron, gold, redstone or rails consumed)");
		}
	}
}
