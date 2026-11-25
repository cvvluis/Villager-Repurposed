package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Fixture checks for the mine kit rule: a paved walkway strip never stops the digging (a village without oak planks must not
 * wait for one), a trip to the depot also carries the next complete structures, and the ten-minute fallback is per section and
 * runs no restock trip. Synthetic: green results are no proof of natural behaviour.
 */
public final class MineKitChecks {
	private MineKitChecks() { }

	public static int run(ServerLevel level, VillageRef ref, BlockPos bell) {
		Villager worker = null;
		int checks = 0;
		VillageData data = ref.data();
		String oldPlanType = data.planType;
		Optional<BlockPos> oldPlanOrigin = data.planOrigin;
		List<String> oldWanted = new ArrayList<>(data.wanted);
		Mining.resetKitWaitForTest();
		try {
			BlockPos entrance = bell.offset(30, 0, 0);
			level.getChunk(entrance);
			level.getChunk(bell);
			Container chest = ref.chest(level);
			if (chest == null) throw new IllegalStateException("MINE KIT CHECK FAILED: fixture chest missing");
			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("MINE KIT CHECK FAILED: villager missing");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			BlockPos chestPos = ref.chestPos(level);
			worker.setPos(chestPos.getX() - 0.5, chestPos.getY(), chestPos.getZ() + 0.5);
			worker.setOnGround(true);
			level.addFreshEntity(worker);
			JobState state = new JobState();
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			VillageData.Mine mine = new VillageData.Mine(entrance, Direction.EAST.get2DDataValue(), 0, entrance.getY() - 3, false);
			ref.data().mine = Optional.of(mine);
			long now = level.getGameTime() + 1_000L;
			Ctx probe = new Ctx(level, worker, state, memory, ref, now);
			Mining.MineFrameMaterials materials = Mining.mineFrameMaterials(probe, mine);
			Item fence = materials.fenceItem();
			Item planks = materials.plankItem();
			boolean oak = planks == Items.OAK_PLANKS;

			// 1. Sections without structure (0 and 1) need nothing: not even the oak walkway plank a spruce village cannot make.
			chest.clearContent();
			worker.getInventory().clearContent();
			for (int index = 0; index <= 1; index++) {
				mine.index = index;
				var status = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now), ref, mine);
				check(status == Mining.SupplyStatus.READY && count(worker.getInventory(), Items.OAK_PLANKS) == 0 && isEmpty(chest),
					"section " + index + " needs no structure: digging starts at once without a walkway plank, got " + status); checks++;
			}

			// A previously dug gallery may expose a small support gap in the next section. Keep the real paid repair block
			// with the miner before the long walk in, including for miners who otherwise work without supports or torches.
			mine.index = 28;
			worker.getInventory().clearContent();
			chest.clearContent();
			add(chest, Items.COBBLESTONE, 1);
			BlockPos terraceBase = new BlockPos(entrance.getX() + mine.index, mine.targetY, entrance.getZ());
			BlockPos terraceFloor = terraceBase.relative(Direction.SOUTH, 1).below();
			level.getChunk(terraceFloor);
			var oldFloor = level.getBlockState(terraceFloor);
			var oldSupport = level.getBlockState(terraceFloor.below());
			level.setBlock(terraceFloor.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(terraceFloor, Blocks.AIR.defaultBlockState(), 3);
			try {
				data.planType = "granary";
				data.planOrigin = Optional.of(bell.offset(0, 0, 20));
				data.wanted.clear();
				data.wanted.add("minecraft:cobblestone#1");
				var reservedKit = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 1L), ref, mine);
				check(PlanStoneSupply.availableCobble(data, chest) == 0 && count(chest, Items.COBBLESTONE) == 1
					&& count(worker.getInventory(), Items.COBBLESTONE) == 0,
					"terrace repair preserves cobblestone reserved for an active construction request (" + reservedKit + ")"); checks++;
				data.planType = "";
				data.planOrigin = Optional.empty();
				data.wanted.clear();
				var terraceKit = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 40L), ref, mine);
				boolean terraceRepaired = terraceKit == Mining.SupplyStatus.BUSY
					&& count(worker.getInventory(), Items.COBBLESTONE) == 1 && count(chest, Items.COBBLESTONE) == 0
					&& Mining.patchFloor(new Ctx(level, worker, state, memory, ref, now + 41L), terraceFloor);
				check(terraceRepaired && level.getBlockState(terraceFloor).is(Blocks.COBBLESTONE)
					&& count(worker.getInventory(), Items.COBBLESTONE) == 0,
					"a worker physically takes one unreserved public cobblestone and pays it to repair a detected gallery floor gap ("
						+ terraceKit + ", inventory " + count(worker.getInventory(), Items.COBBLESTONE) + ")"); checks++;
			} finally {
				level.setBlock(terraceFloor, oldFloor, 3);
				level.setBlock(terraceFloor.below(), oldSupport, 3);
			}

			// 2. A support section with an empty depot waits; the wait is per section and its fallback runs no restock trip.
			mine.index = 2;
			Mining.resetKitWaitForTest();
			var waiting = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now), ref, mine);
			check(waiting == Mining.SupplyStatus.WAITING && worker.getInventory().isEmpty(),
				"a support section without any depot stock waits, got " + waiting); checks++;
			check(Mining.kitDepotEmpty(chest), "an empty depot is recognised: no trip to it is made for a kit that is not there"); checks++;
			add(chest, Items.STICK, 1);
			check(!Mining.kitDepotEmpty(chest), "a depot holding sticks is not empty: the kit may still be crafted"); checks++;
			chest.clearContent();
			// A night between two work spells is not charged to the kit: a 20000 tick gap adds one capped step only.
			var afterNight = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 20000L), ref, mine);
			check(afterNight == Mining.SupplyStatus.WAITING,
				"a long pause between two work spells does not use up the kit limit, got " + afterNight); checks++;
			long spent = 20000L;
			while (spent < 20000L + Mining.KIT_WAIT_TICKS + 4L * Mining.KIT_WAIT_STEP_CAP) {
				spent += 200L;
				Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + spent), ref, mine);
			}
			worker.setPos(chestPos.getX() + 40.5, chestPos.getY(), chestPos.getZ() + 0.5);
			var expired = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + spent + 200L), ref, mine);
			check(expired == Mining.SupplyStatus.READY && Math.abs(worker.getX() - (chestPos.getX() + 40.5)) < 0.01
				&& worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET).isEmpty(),
				"after the working-time limit the section is released without sending the resident to the depot, got " + expired); checks++;
			mine.index = 3;
			var next = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + spent + 400L), ref, mine);
			check(next == Mining.SupplyStatus.READY,
				"the following structural section is not held up again for the same missing stock (hold-off), got " + next); checks++;
			mine.index = 6;
			var after = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + spent + Mining.KIT_SKIP_TICKS + 600L), ref, mine);
			check(after != Mining.SupplyStatus.READY,
				"once the hold-off is over a structural section asks for its kit again, got " + after); checks++;
			worker.setPos(chestPos.getX() - 0.5, chestPos.getY(), chestPos.getZ() + 0.5);

			// 2b. A light section whose torch cannot be made (no torch and no coal in the depot) is not waited for; with coal it still is.
			Mining.resetKitWaitForTest();
			mine.index = 3;
			worker.getInventory().clearContent();
			chest.clearContent();
			add(chest, planks, 10);
			var dark = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 5L), ref, mine);
			check(dark == Mining.SupplyStatus.READY && worker.getInventory().isEmpty(),
				"a light section that lacks only a torch nobody can make is dug at once, got " + dark); checks++;
			add(chest, Items.COAL, 1);
			Mining.resetKitWaitForTest();
			var lit = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 6L), ref, mine);
			check(lit != Mining.SupplyStatus.READY, "with coal in the depot the light section still asks for its torch, got " + lit); checks++;
			worker.getInventory().clearContent();
			mine.index = 2;

			// 3. One trip carries the next support, the following torch and the support after it: complete structures only.
			Mining.resetKitWaitForTest();
			mine.index = 2;
			worker.getInventory().clearContent();
			chest.clearContent();
			add(chest, fence, 12);
			add(chest, planks, 10);
			add(chest, Items.TORCH, 1);
			add(chest, Items.OAK_PLANKS, 3);
			var taken = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 10L), ref, mine);
			check(taken == Mining.SupplyStatus.BUSY && count(worker.getInventory(), fence) == 12
				&& count(worker.getInventory(), planks) >= 10 && count(worker.getInventory(), Items.TORCH) == 1
				&& count(worker.getInventory(), Items.OAK_PLANKS) >= 3 && count(chest, fence) == 0 && count(chest, Items.TORCH) == 0,
				"one depot trip takes the supports and the torch of the next three structural sections plus the walkway planks"
					+ " (fences " + count(worker.getInventory(), fence) + ", planks " + count(worker.getInventory(), planks)
					+ ", torches " + count(worker.getInventory(), Items.TORCH) + ", oak " + count(worker.getInventory(), Items.OAK_PLANKS) + ", " + taken + ")"); checks++;
			var ready = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 60L), ref, mine);
			check(ready == Mining.SupplyStatus.READY, "with the kit on board the section is dug, got " + ready); checks++;

			// 4. A partial second bay stays in the depot for the builders.
			Mining.resetKitWaitForTest();
			worker.getInventory().clearContent();
			chest.clearContent();
			add(chest, fence, 9);
			add(chest, planks, 7 + (oak ? 0 : 0));
			add(chest, Items.TORCH, 1);
			Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 100L), ref, mine);
			check(count(worker.getInventory(), fence) == 6 && count(worker.getInventory(), planks) == 5
				&& count(worker.getInventory(), Items.TORCH) == 1 && count(chest, fence) == 3 && count(chest, planks) == 2,
				"only complete structures are taken; the unfinished second bay stays in the depot (fences "
					+ count(worker.getInventory(), fence) + "/" + count(chest, fence) + ", planks " + count(worker.getInventory(), planks)
					+ "/" + count(chest, planks) + ")"); checks++;

			// 5. A miner who already carries the next section's structure does not leave.
			Mining.resetKitWaitForTest();
			worker.getInventory().clearContent();
			chest.clearContent();
			add(worker.getInventory(), fence, 6);
			add(worker.getInventory(), planks, 5);
			var carried = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 200L), ref, mine);
			check(carried == Mining.SupplyStatus.READY, "a miner that carries the next structure digs at once, got " + carried); checks++;

			// 6. Before the first walk out to the shaft the kit for the first support ahead is taken along (no turn-round at the
			// shaft later): at index 0 the next section needs nothing, the support at index 2 does.
			Mining.resetKitWaitForTest();
			mine.index = 0;
			worker.getInventory().clearContent();
			chest.clearContent();
			add(chest, fence, 6);
			add(chest, planks, 5);
			var outbound = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 300L), ref, mine);
			check(outbound == Mining.SupplyStatus.BUSY && count(worker.getInventory(), fence) == 6 && count(worker.getInventory(), planks) >= 5
				&& count(chest, fence) == 0,
				"a miner still outside the gallery takes the first support kit ahead from the depot before walking out, got " + outbound
					+ " (fences " + count(worker.getInventory(), fence) + ", planks " + count(worker.getInventory(), planks) + ")"); checks++;
			var outboundReady = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 360L), ref, mine);
			check(outboundReady == Mining.SupplyStatus.READY, "with that kit on board the plain sections are dug at once, got " + outboundReady); checks++;
			// A depot that holds nothing never holds the miner back (and no kit is invented): he digs the plain sections.
			Mining.resetKitWaitForTest();
			worker.getInventory().clearContent();
			chest.clearContent();
			worker.setPos(chestPos.getX() + 40.5, chestPos.getY(), chestPos.getZ() + 0.5);
			var bare = Mining.kitStatus(new Ctx(level, worker, state, memory, ref, now + 400L), ref, mine);
			check(bare == Mining.SupplyStatus.READY && worker.getInventory().isEmpty() && isEmpty(chest),
				"an empty depot does not send a miner out of his way or hold him back, got " + bare); checks++;
			worker.setPos(chestPos.getX() - 0.5, chestPos.getY(), chestPos.getZ() + 0.5);
			return checks;
		} finally {
			if (worker != null) worker.discard();
			ref.data().mine = Optional.empty();
			data.planType = oldPlanType;
			data.planOrigin = oldPlanOrigin;
			data.wanted.clear();
			data.wanted.addAll(oldWanted);
			Mining.resetKitWaitForTest();
		}
	}

	/** Cobblestone mined for a waiting plan reaches the public chest in useful loads instead of staying in the miner's pockets. */
	public static int runDelivery(ServerLevel level, VillageRef ref, BlockPos bell) {
		Villager worker = null;
		int checks = 0;
		VillageData data = ref.data();
		String oldType = data.planType;
		Optional<BlockPos> oldOrigin = data.planOrigin;
		var oldWanted = new java.util.ArrayList<>(data.wanted);
		try {
			Container chest = ref.chest(level);
			if (chest == null) throw new IllegalStateException("MINE KIT CHECK FAILED: fixture chest missing");
			chest.clearContent();
			// The stock floor (see PlanStoneSupply.STOCK_FLOOR) is filled with stand-ins, so the checks below see the batch shortage alone.
			add(chest, Items.ANDESITE, PlanStoneSupply.STOCK_FLOOR);
			data.planType = "granary";
			data.planOrigin = Optional.of(bell.offset(0, 0, 20));
			data.wanted.clear();
			data.wanted.add("minecraft:cobblestone#18");
			check(!PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 11), "eleven cobblestones do not yet justify the walk while 18 are missing"); checks++;
			check(PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 12), "a full load of twelve is delivered while the plan waits"); checks++;
			check(!PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 0), "nothing carried, nothing to deliver"); checks++;
			// Down in the gallery the way out is long: only a big load (or one that covers the whole shortage) sends the miner up.
			check(PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 12, false) && !PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 12, true),
				"twelve cobblestones are delivered from the surface but do not yet bring a miner out of the gallery"); checks++;
			check(PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 20, true),
				"a load that covers the whole shortage brings the miner out at once"); checks++;
			data.wanted.clear();
			data.wanted.add("minecraft:cobblestone#90");
			check(!PlanStoneSupply.cobbleDeliveryDue(level, data, chest, PlanStoneSupply.DEEP_DELIVERY_LOAD - 1, true)
				&& PlanStoneSupply.cobbleDeliveryDue(level, data, chest, PlanStoneSupply.DEEP_DELIVERY_LOAD, true)
				&& PlanStoneSupply.cobbleDeliveryDue(level, data, chest, PlanStoneSupply.DEEP_DELIVERY_LOAD - 1, false),
				"a deep miner turns back at the deep load, a resident on the surface at the ordinary load"); checks++;
			// The deeper the face, the dearer the walk out: the load that justifies it grows with the depth (up to the pocket-bound maximum).
			check(PlanStoneSupply.deepDeliveryLoad(0) == PlanStoneSupply.DEEP_DELIVERY_LOAD
				&& PlanStoneSupply.deepDeliveryLoad(30) > PlanStoneSupply.DEEP_DELIVERY_LOAD
				&& PlanStoneSupply.deepDeliveryLoad(500) == PlanStoneSupply.DEEP_DELIVERY_MAX,
				"the deep delivery load grows with the depth and is capped"); checks++;
			data.wanted.clear();
			data.wanted.add("minecraft:cobblestone#400");
			int deepLoad = PlanStoneSupply.deepDeliveryLoad(30);
			check(!PlanStoneSupply.cobbleDeliveryDue(level, data, chest, deepLoad - 1, true, 30, deepLoad - 1)
				&& PlanStoneSupply.cobbleDeliveryDue(level, data, chest, deepLoad, true, 30, deepLoad),
				"a miner thirty cells down turns back at the depth-scaled load, not at the shallow one"); checks++;
			check(PlanStoneSupply.cobbleDeliveryDue(level, data, chest, PlanStoneSupply.DEEP_DELIVERY_LOAD, true, 0, PlanStoneSupply.DEEP_DELIVERY_LOAD),
				"at the shaft the shallow deep load still applies"); checks++;
			data.wanted.clear();
			data.wanted.add("minecraft:cobblestone#100");
			check(PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 30, true, 30, 30 + 40 + 40)
				&& !PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 30, true, 30, 60)
				&& !PlanStoneSupply.cobbleDeliveryDue(level, data, chest, PlanStoneSupply.CREW_SHARE_MIN - 1, true, 30, 300),
				"the stone of the whole crew in the gallery may send a miner up once it covers the shortage, but only with a fair share on board"); checks++;
			data.wanted.clear();
			data.wanted.add("minecraft:cobblestone#18");
			data.wanted.clear();
			data.wanted.add("minecraft:cobblestone#5");
			check(PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 5 + PlanStoneSupply.COBBLE_RESERVE)
				&& !PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 4 + PlanStoneSupply.COBBLE_RESERVE),
				"a load that covers the whole remaining shortage (besides the miner's repair reserve) is delivered at once, a smaller one is not"); checks++;
			add(chest, Items.COBBLESTONE, 5);
			check(!PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 30), "nothing is delivered once the public chest already covers the plan"); checks++;
			// Stock floor: while a plan is under way the chest keeps a modest stock, so the next batches are served at once.
			chest.clearContent();
			check(PlanStoneSupply.stockGap(data, chest) == PlanStoneSupply.STOCK_FLOOR
				&& PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 12), "an empty chest is below the stock floor and a load of twelve is delivered"); checks++;
			add(chest, Items.COBBLESTONE, 40);
			check(PlanStoneSupply.stockGap(data, chest) == PlanStoneSupply.STOCK_FLOOR - 40 && Mining.needed(level, ref, data, chest, Mining.Need.STONE),
				"below the floor the mine keeps working although the plan's batch is covered"); checks++;
			add(chest, Items.DIORITE, PlanStoneSupply.STOCK_FLOOR);
			check(PlanStoneSupply.stockGap(data, chest) == 0 && !PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 30),
				"stand-ins count towards the floor, and nothing is delivered once the stock is there"); checks++;
			chest.clearContent();
			data.planType = "";
			data.planOrigin = Optional.empty();
			check(PlanStoneSupply.stockGap(data, chest) == 0, "without a plan or a project that needs cobblestone there is no stock floor"); checks++;
			check(!PlanStoneSupply.cobbleDeliveryDue(level, data, chest, 40), "without a plan the miner keeps its stone for the ordinary 64-item delivery"); checks++;

			// Real tick: a resident at the chest with a load and a pickaxe brings it to the public chest.
			data.planType = "granary";
			data.planOrigin = Optional.of(bell.offset(0, 0, 20));
			data.wanted.clear();
			data.wanted.add("minecraft:cobblestone#18");
			BlockPos entrance = bell.offset(30, 0, 0);
			level.getChunk(entrance);
			VillageData.Mine mine = new VillageData.Mine(entrance, Direction.EAST.get2DDataValue(), 0, entrance.getY() - 3, false);
			data.mine = Optional.of(mine);
			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("MINE KIT CHECK FAILED: villager missing");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			worker.addTag("vp090_fixture");
			BlockPos chestPos = ref.chestPos(level);
			for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
				if (dx == 0 && dz == 0) continue;
				level.setBlock(chestPos.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(chestPos.offset(dx, 0, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(chestPos.offset(dx, 1, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
			}
			worker.setPos(chestPos.getX() + 1.5, chestPos.getY(), chestPos.getZ() + 0.5);
			worker.setOnGround(true);
			level.addFreshEntity(worker);
			worker.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
			add(worker.getInventory(), Items.COBBLESTONE, 14);
			JobState state = new JobState();
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			long now = level.getGameTime() + 5_000L;
			for (int i = 0; i < 12 && count(chest, Items.COBBLESTONE) == 0; i++) {
				state.mineCheckAt = 0;
				state.cooldownUntil = 0;
				Mining.tick(new Ctx(level, worker, state, memory, ref, now + i * 30L), Mining.Need.STONE);
				// The fixture villager has no AI: set it onto the delivery stand the job chose (a real one walks there).
				if (state.target != null) worker.setPos(state.target.getX() + 0.5, state.target.getY(), state.target.getZ() + 0.5);
			}
			check(count(chest, Items.COBBLESTONE) == 14 - PlanStoneSupply.COBBLE_RESERVE
				&& count(worker.getInventory(), Items.COBBLESTONE) == PlanStoneSupply.COBBLE_RESERVE
				&& count(worker.getInventory(), Items.IRON_PICKAXE) == 1,
				"a miner carrying a load for the waiting plan puts it into the public chest (chest " + count(chest, Items.COBBLESTONE)
					+ ", carried " + count(worker.getInventory(), Items.COBBLESTONE) + ", activity " + state.activity
					+ ", due " + PlanStoneSupply.cobbleDeliveryDue(level, ref.data(), ref.chest(level), count(worker.getInventory(), Items.COBBLESTONE))
					+ ", needed " + Mining.needed(level, ref, ref.data(), ref.chest(level), Mining.Need.STONE)
					+ ", blocked " + (worker.level() == level ? state.depositBlockedUntil - now : 0) + ", cd " + (state.cooldownUntil - now) + ")"); checks++;
			// The one-block floor repair of the gallery works with any rock or soil the miner dug if no cobblestone is left.
			worker.getInventory().clearContent();
			add(worker.getInventory(), Items.DIRT, 1);
			BlockPos gap = chestPos.offset(2, 0, 2);
			level.setBlock(gap.below().below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(gap.below(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
			boolean patched = Mining.patchFloor(new Ctx(level, worker, state, memory, ref, now + 900L), gap.below());
			check(patched && level.getBlockState(gap.below()).is(net.minecraft.world.level.block.Blocks.DIRT) && count(worker.getInventory(), Items.DIRT) == 0,
				"a floor gap is closed with dug dirt when the miner holds no cobblestone (patched " + patched + ")"); checks++;
			return checks;
		} finally {
			if (worker != null) worker.discard();
			data.mine = Optional.empty();
			data.planType = oldType;
			data.planOrigin = oldOrigin;
			data.wanted.clear();
			data.wanted.addAll(oldWanted);
		}
	}

	private static void add(Container container, Item item, int count) {
		net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, container, new ItemStack(item, count), null);
	}

	private static int count(Container container, Item item) {
		return JobUtil.count(container, stack -> stack.is(item));
	}

	private static boolean isEmpty(Container container) {
		return container.isEmpty();
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("MINE KIT CHECK FAILED: " + message);
	}
}
