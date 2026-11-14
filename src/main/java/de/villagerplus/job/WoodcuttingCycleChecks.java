package de.villagerplus.job;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Deterministic checks for requested log species and the persisted woodcutting cursor. */
public final class WoodcuttingCycleChecks {
	private WoodcuttingCycleChecks() {}

	public static int run() {
		BlockPos root = new BlockPos(17, 72, -9);
		BlockPos log = root.above(4);
		WoodcutterWork ready = WoodcutterWork.empty().start(root, "minecraft:oak")
			.select(log).withStage(6).claimRemoval(List.of(new ItemStack(Items.OAK_LOG, 1)), Blocks.OAK_LOG);
		var encoded = WoodcutterWork.CODEC.encodeStart(JsonOps.INSTANCE, ready).getOrThrow();
		WoodcutterWork restored = WoodcutterWork.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
		check(restored.root().orElseThrow().equals(root), "tree root survives the persistent codec");
		check(restored.log().orElseThrow().equals(log) && restored.stage() == 9,
			"claimed block and completed crack stage resume without replaying earlier strokes");
		check(restored.removalPending() && restored.pendingDrops().size() == 1
			&& restored.pendingDrops().getFirst().is(Items.OAK_LOG) && restored.pendingBlock().equals("minecraft:oak_log"),
			"the exact drop claim survives while block removal is pending");
		WoodcutterWork confirmed = WoodcutterWork.CODEC.parse(JsonOps.INSTANCE,
			WoodcutterWork.CODEC.encodeStart(JsonOps.INSTANCE, restored.confirmRemoval()).getOrThrow()).getOrThrow();
		check(confirmed.removalPending() && confirmed.removalConfirmed() && confirmed.pendingDrops().size() == 1,
			"only a persisted successful block removal may authorize delivering the cached drops");
		WoodcutterWork complete = confirmed.afterRemovalDelivered().finishTree();
		check(complete.root().isEmpty() && complete.replantSites().size() == 1
			&& complete.replantSites().getFirst().pos().equals(root)
			&& complete.replantSites().getFirst().species().equals("minecraft:oak"),
			"finished tree queues its own species at the real stump for later replanting");
		check(Woodcutting.species(Blocks.OAK_LOG).equals("minecraft:oak")
			&& Woodcutting.species(Blocks.SPRUCE_LOG).equals("minecraft:spruce")
			&& Woodcutting.matchingSapling("minecraft:oak", new ItemStack(Items.OAK_SAPLING))
			&& Woodcutting.matchingSapling("minecraft:spruce", new ItemStack(Items.SPRUCE_SAPLING))
			&& !Woodcutting.matchingSapling("minecraft:oak", new ItemStack(Items.SPRUCE_SAPLING)),
			"oak and spruce cycles resolve distinct exact sapling costs");
		check(WoodcutterWork.empty().start(root, "minecraft:oak").select(log).withStage(12).stage() == 9,
			"persisted crack stages are clamped to the visible 0–9 range");
		WoodcutterWork protectedTree = complete.start(root, "minecraft:oak").select(log).withStage(4).abandonTree();
		check(protectedTree.root().isEmpty() && protectedTree.pendingDrops().isEmpty()
			&& protectedTree.replantSites().equals(complete.replantSites()),
			"a newly built feature abandons the active tree without losing unrelated replant work");
		Set<String> requestedLogs = Woodcutting.demandedLogIds(List.of(
			"minecraft:oak_planks#19", "minecraft:oak_fence#6", "minecraft:spruce_stairs#89", "minecraft:spruce_log#72",
			"minecraft:dark_oak_log#3", "minecraft:stone_bricks#31", "not a valid item id#1"));
		check(requestedLogs.equals(Set.of("minecraft:oak_log", "minecraft:spruce_log", "minecraft:dark_oak_log")),
			"wood recipe requests derive only their matching log species while explicit log requests stay exact");
		check(Woodcutting.matchesRequestedLog(Blocks.OAK_LOG, requestedLogs)
			&& Woodcutting.matchesRequestedLog(Blocks.SPRUCE_LOG, requestedLogs)
			&& Woodcutting.matchesRequestedLog(Blocks.DARK_OAK_LOG, requestedLogs),
			"requested natural log species are accepted for construction supply");
		check(!Woodcutting.matchesRequestedLog(Blocks.BIRCH_LOG, requestedLogs)
			&& !Woodcutting.matchesRequestedLog(Blocks.OAK_WOOD, requestedLogs),
			"unrequested species and a different log item variant cannot satisfy an exact request");
		check(Woodcutting.matchesRequestedLog(new ItemStack(Items.OAK_LOG), requestedLogs)
			&& Woodcutting.matchesRequestedLog(new ItemStack(Items.SPRUCE_LOG), requestedLogs)
			&& !Woodcutting.matchesRequestedLog(new ItemStack(Items.BIRCH_LOG), requestedLogs),
			"carried logs are deliverable only when their exact natural species is requested");
		check(!Woodcutting.matchesRequestedLog(new ItemStack(Items.STRIPPED_OAK_LOG), requestedLogs)
			&& Woodcutting.demandedLogIds(List.of("minecraft:stripped_oak_log#8", "minecraft:oak_wood#3"))
				.equals(Set.of("minecraft:oak_log")),
			"stripped-log shortages now source their exact natural log while wood blocks remain outside tree supply");
		check(Woodcutting.demandedLogIds(List.of("minecraft:stone_bricks#31", "minecraft:glass_pane#8")).isEmpty(),
			"non-wood shortages do not create a wood source request");
		check(Woodcutting.strippingSource(Items.STRIPPED_BIRCH_LOG) == Items.BIRCH_LOG
			&& Woodcutting.strippingSource(Items.BIRCH_PLANKS) == null,
			"only a registered stripped-log target resolves to its exact natural-log input");
		ItemStack usableAxe = new ItemStack(Items.IRON_AXE);
		ItemStack exhaustedAxe = new ItemStack(Items.IRON_AXE);
		exhaustedAxe.setDamageValue(exhaustedAxe.getMaxDamage());
		check(Woodcutting.usableStrippingAxe(usableAxe) && !Woodcutting.usableStrippingAxe(exhaustedAxe)
			&& !Woodcutting.usableStrippingAxe(new ItemStack(Items.IRON_PICKAXE)),
			"stripping requires a real damageable axe with durability remaining");
		SimpleContainer constructionChest = new SimpleContainer(2);
		SimpleContainer publicLager = new SimpleContainer(1);
		publicLager.setItem(0, new ItemStack(Items.BIRCH_LOG, 16));
		int stripped = Woodcutting.supplyStrippedPlanLogs(
			List.of("minecraft:stripped_birch_log#13", "minecraft:birch_planks#2"), constructionChest,
			List.<net.minecraft.world.Container>of(constructionChest, publicLager));
		check(stripped == 13 && constructionChest.getItem(0).is(Items.STRIPPED_BIRCH_LOG)
			&& constructionChest.getItem(0).getCount() == 13 && publicLager.getItem(0).is(Items.BIRCH_LOG)
			&& publicLager.getItem(0).getCount() == 3,
			"the active shortage consumes thirteen real birch-log items into thirteen exact outputs and reserves a log for two requested planks");
		SimpleContainer fullChest = new SimpleContainer(1);
		fullChest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		SimpleContainer unspentLogs = new SimpleContainer(1);
		unspentLogs.setItem(0, new ItemStack(Items.BIRCH_LOG, 1));
		check(Woodcutting.supplyStrippedPlanLogs(List.of("minecraft:stripped_birch_log#1"), fullChest,
			List.<net.minecraft.world.Container>of(fullChest, unspentLogs)) == 0
			&& fullChest.getItem(0).is(Items.COBBLESTONE) && fullChest.getItem(0).getCount() == 64
			&& unspentLogs.getItem(0).is(Items.BIRCH_LOG) && unspentLogs.getItem(0).getCount() == 1,
			"a full construction chest rejects stripped-log production without consuming its raw-log input");
		check(Woodcutting.searchRadius(true) > Woodcutting.searchRadius(false)
			&& Woodcutting.searchRadius(true) == 48,
			"construction wood supply searches farther for exact requested timber without widening ordinary forestry");
		List<BlockPos> fallback = Woodcutting.fallbackPatchCandidates(root);
		check(fallback.size() == 4 && fallback.stream().allMatch(site -> site.distManhattan(root) == 2),
			"the persisted stump fallback is restricted to four deterministic sites two blocks from the harvested tree");
		check(Woodcutting.reservedCanopySizeAllowed(64, 32)
			&& !Woodcutting.reservedCanopySizeAllowed(65, 32)
			&& !Woodcutting.reservedCanopySizeAllowed(64, 33)
			&& !Woodcutting.reservedCanopySizeAllowed(0, 1),
			"expansion clearing accepts large natural trees but remains capped by log count and canopy height");
		WoodcutterWork reservedTree = complete.start(root, "vp_reserved_canopy:minecraft:oak");
		WoodcutterWork clearedParcel = Woodcutting.finishTrackedTree(reservedTree);
		check(Woodcutting.isReservedCanopyWork(reservedTree) && clearedParcel.root().isEmpty()
			&& clearedParcel.replantSites().equals(complete.replantSites()),
			"a felled expansion tree leaves the cleared parcel open without discarding unrelated replant work");
		SimpleContainer nearlyFull = new SimpleContainer(2);
		nearlyFull.setItem(0, new ItemStack(Items.OAK_LOG, 63));
		List<ItemStack> competingDrops = List.of(new ItemStack(Items.OAK_LOG, 2), new ItemStack(Items.APPLE, 1));
		check(nearlyFull.canAddItem(competingDrops.get(0)) && nearlyFull.canAddItem(competingDrops.get(1))
			&& !Woodcutting.canFitAllDrops(nearlyFull, competingDrops)
			&& nearlyFull.getItem(0).getCount() == 63 && nearlyFull.getItem(1).isEmpty(),
			"aggregate drop simulation rejects batches competing for the same remaining slot without mutating villager inventory");
		SimpleContainer enoughRoom = new SimpleContainer(2);
		enoughRoom.setItem(0, new ItemStack(Items.OAK_LOG, 62));
		check(Woodcutting.canFitAllDrops(enoughRoom, competingDrops)
			&& enoughRoom.getItem(0).getCount() == 62 && enoughRoom.getItem(1).isEmpty(),
			"aggregate drop simulation accepts a complete batch when all stacks fit and leaves the source inventory unchanged");
		return 24;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new IllegalStateException("Woodcutting cycle: " + message);
	}
}
