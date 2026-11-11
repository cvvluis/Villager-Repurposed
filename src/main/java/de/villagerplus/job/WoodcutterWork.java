package de.villagerplus.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Persistent cursor for one woodcutter's active tree and its safely staged output. */
public record WoodcutterWork(Optional<BlockPos> root, Optional<BlockPos> log, int stage,
	boolean removalPending, boolean removalConfirmed, List<ItemStack> pendingDrops, String pendingBlock, String species, List<ReplantSite> replantSites) {
	private static final int MAX_REPLANT_SITES = 32;
	public static final Codec<WoodcutterWork> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.optionalFieldOf("root").forGetter(WoodcutterWork::root),
		BlockPos.CODEC.optionalFieldOf("log").forGetter(WoodcutterWork::log),
		Codec.INT.optionalFieldOf("stage", -1).forGetter(WoodcutterWork::stage),
		Codec.BOOL.optionalFieldOf("removal_pending", false).forGetter(WoodcutterWork::removalPending),
		Codec.BOOL.optionalFieldOf("removal_confirmed", false).forGetter(WoodcutterWork::removalConfirmed),
		ItemStack.CODEC.listOf().optionalFieldOf("pending_drops", List.of()).forGetter(WoodcutterWork::pendingDrops),
		Codec.STRING.optionalFieldOf("pending_block", "").forGetter(WoodcutterWork::pendingBlock),
		Codec.STRING.optionalFieldOf("species", "").forGetter(WoodcutterWork::species),
		ReplantSite.CODEC.listOf().optionalFieldOf("replant_sites", List.of()).forGetter(WoodcutterWork::replantSites)
	).apply(i, WoodcutterWork::new));

	public WoodcutterWork {
		root = root.map(BlockPos::immutable);
		log = log.map(BlockPos::immutable);
		stage = Math.max(-1, Math.min(9, stage));
		pendingDrops = pendingDrops.stream().filter(s -> !s.isEmpty()).map(ItemStack::copy).toList();
		replantSites = List.copyOf(replantSites);
		pendingBlock = pendingBlock == null ? "" : pendingBlock;
		species = species == null ? "" : species;
	}

	public static WoodcutterWork empty() {
		return new WoodcutterWork(Optional.empty(), Optional.empty(), -1, false, false, List.of(), "", "", List.of());
	}

	public WoodcutterWork start(BlockPos root, String species) {
		return new WoodcutterWork(Optional.of(root), Optional.empty(), -1, false, false, List.of(), "", species, replantSites);
	}

	public WoodcutterWork select(BlockPos log) {
		return new WoodcutterWork(root, Optional.of(log), -1, false, false, List.of(), "", species, replantSites);
	}

	public WoodcutterWork withStage(int stage) {
		return new WoodcutterWork(root, log, stage, false, false, List.of(), "", species, replantSites);
	}

	/** Persist drops and the claim before changing the block, so a restart can finish either half exactly once. */
	public WoodcutterWork claimRemoval(List<ItemStack> drops, net.minecraft.world.level.block.Block block) {
		String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
		return new WoodcutterWork(root, log, 9, true, false, drops, blockId, species, replantSites);
	}

	/** Records that this worker successfully removed the claimed block; only then may its cached drops be paid. */
	public WoodcutterWork confirmRemoval() {
		return new WoodcutterWork(root, log, 9, true, true, pendingDrops, pendingBlock, species, replantSites);
	}

	public WoodcutterWork afterRemovalDelivered() {
		return new WoodcutterWork(root, Optional.empty(), -1, false, false, List.of(), "", species, replantSites);
	}

	/** Abandon an active tree if construction has appeared beside it; preserve unrelated replant work. */
	public WoodcutterWork abandonTree() {
		return new WoodcutterWork(Optional.empty(), Optional.empty(), -1, false, false, List.of(), "", "", replantSites);
	}

	public WoodcutterWork finishTree() {
		List<ReplantSite> sites = new ArrayList<>(replantSites);
		if (root.isPresent() && !species.isBlank()) {
			BlockPos base = root.get();
			sites.removeIf(site -> site.pos().equals(base));
			sites.add(new ReplantSite(base, species, 0));
			while (sites.size() > MAX_REPLANT_SITES) sites.remove(0);
		}
		return new WoodcutterWork(Optional.empty(), Optional.empty(), -1, false, false, List.of(), "", "", sites);
	}

	public WoodcutterWork updateReplantSites(List<ReplantSite> sites) {
		return new WoodcutterWork(root, log, stage, removalPending, removalConfirmed, pendingDrops, pendingBlock, species, sites);
	}

	public record ReplantSite(BlockPos pos, String species, long retryAt) {
		public static final Codec<ReplantSite> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(ReplantSite::pos),
			Codec.STRING.fieldOf("species").forGetter(ReplantSite::species),
			Codec.LONG.optionalFieldOf("retry_at", 0L).forGetter(ReplantSite::retryAt)
		).apply(i, ReplantSite::new));

		public ReplantSite {
			pos = pos.immutable();
			species = species == null ? "" : species;
		}

		public ReplantSite retryAt(long next) {
			return new ReplantSite(pos, species, next);
		}
	}
}
