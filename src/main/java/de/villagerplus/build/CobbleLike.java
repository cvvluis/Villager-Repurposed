package de.villagerplus.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import de.villagerplus.job.JobUtil;

/**
 * Natural stone variants (andesite, diorite, granite) as stand-ins for plain cobblestone in plan blocks. The village mine breaks
 * them out of the same rock as cobblestone but they used to pile up unused in the chest; a full cobblestone block of a plan may
 * be paid with one of them and then shows that variant. Stairs, slabs and walls are not covered (they need their own recipes),
 * and a foundation that needs cobblestone itself is never served with a variant.
 */
public final class CobbleLike {
	private CobbleLike() { }

	public static final List<Item> VARIANTS = List.of(Items.ANDESITE, Items.DIORITE, Items.GRANITE);

	public static boolean variant(ItemStack stack) {
		return !stack.isEmpty() && VARIANTS.contains(stack.getItem());
	}

	/** Cobblestone or one of its stand-ins. */
	public static boolean stone(ItemStack stack) {
		return stack.is(Items.COBBLESTONE) || variant(stack);
	}

	/** A plain cobblestone block step that a variant may pay for. */
	public static boolean substitutable(Step step) {
		return step != null && step.item() == Items.COBBLESTONE && step.tag() == null && !step.free() && step.linkedTo() == null;
	}

	public static int variantCount(Container box) {
		return box == null ? 0 : JobUtil.count(box, CobbleLike::variant);
	}

	/** The variant with the biggest stock that covers {@code cost} (one kind per wall keeps it uniform), or null. */
	public static Item variantFor(Container box, int cost) {
		if (box == null) return null;
		Item best = null;
		int bestCount = 0;
		for (Item item : VARIANTS) {
			int count = JobUtil.count(box, stack -> stack.is(item));
			if (count >= cost && count > bestCount) {
				best = item;
				bestCount = count;
			}
		}
		return best;
	}

	/**
	 * What to ask the public stock for when {@code left} blocks of substitutable cobblestone steps are still uncovered: the
	 * variants it actually holds first, the rest as cobblestone (which the mine then has to supply).
	 */
	public static Map<Item, Integer> publicDemand(Container publicStock, int left) {
		Map<Item, Integer> result = new LinkedHashMap<>();
		int remaining = Math.max(0, left);
		if (remaining == 0) return result;
		if (publicStock != null) {
			// Most plentiful variant first.
			List<Item> byStock = VARIANTS.stream().sorted((a, b) -> Integer.compare(
				JobUtil.count(publicStock, s -> s.is(b)), JobUtil.count(publicStock, s -> s.is(a)))).toList();
			for (Item item : byStock) {
				int amount = Math.min(remaining, JobUtil.count(publicStock, s -> s.is(item)));
				if (amount <= 0) continue;
				result.put(item, amount);
				remaining -= amount;
				if (remaining == 0) break;
			}
		}
		if (remaining > 0) result.put(Items.COBBLESTONE, remaining);
		return result;
	}

	/**
	 * Cobblestone still to be requested for one demand row of plain cobblestone: {@code total} is everything the window needs,
	 * {@code variantSteps} how much of that is cobblestone block steps (the rest is foundation and only cobblestone serves it).
	 * {@code available} (site stock and cargo) is consumed: cobblestone first for the foundation, then variants and the
	 * remaining cobblestone for the steps. The steps' rest is asked from the public stock (variants it holds, else cobblestone).
	 */
	public static Map<Item, Integer> demand(Map<Item, Integer> available, int total, int variantSteps, Container publicStock) {
		int stepsNeed = Math.max(0, Math.min(total, variantSteps));
		int hardLeft = consume(available, Items.COBBLESTONE, total - stepsNeed);
		int stepsLeft = stepsNeed;
		for (Item variant : VARIANTS) stepsLeft = consume(available, variant, stepsLeft);
		stepsLeft = consume(available, Items.COBBLESTONE, stepsLeft);
		Map<Item, Integer> requests = new LinkedHashMap<>();
		if (hardLeft > 0) requests.put(Items.COBBLESTONE, hardLeft);
		publicDemand(publicStock, stepsLeft).forEach((item, count) -> requests.merge(item, count, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b)));
		return requests;
	}

	private static int consume(Map<Item, Integer> available, Item item, int requested) {
		if (requested <= 0) return 0;
		int have = available.getOrDefault(item, 0);
		int used = Math.min(have, requested);
		if (used == have) available.remove(item);
		else available.put(item, have - used);
		return requested - used;
	}

	/** The state to place: a cobblestone step paid with a stand-in stone shows that stone. */
	public static BlockState placed(Step step, Block paid, BlockState made) {
		if (made != null && substitutable(step) && paid != Blocks.COBBLESTONE && made.is(Blocks.COBBLESTONE)) return paid.defaultBlockState();
		return made;
	}
}
