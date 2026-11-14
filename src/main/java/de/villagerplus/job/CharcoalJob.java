package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Burns real village logs and fuel in a placed vanilla furnace; it never creates charcoal directly. */
public final class CharcoalJob implements Job {
	private static final int SCAN_COOLDOWN = 1200;

	@Override
	public boolean indoorsDuringStorm() { return true; }

	@Override
	public Predicate<ItemStack> produce() { return stack -> stack.is(Items.CHARCOAL); }

	@Override
	public boolean primary(Ctx c) { return work(c); }

	@Override
	public boolean secondary(Ctx c) { return work(c); }

	private static boolean work(Ctx c) {
		if (c.village() == null) return false;
		Container storage = Lager.combined(c.level(), c.village());
		if (storage == null) return false;
		BlockPos kitchen = Specials.get(c.village(), Blueprints.KITCHEN);
		if (kitchen == null || !c.level().hasChunkAt(kitchen)) return false;
		BlockPos pos = c.st().target;
		FurnaceBlockEntity furnace = pos == null ? null : furnaceAt(c.level(), pos);
		if (furnace != null && !insideKitchen(pos, kitchen)) furnace = null;
		if (furnace != null && smithOwnsBatch(furnace)) furnace = null;
		if (furnace == null) {
			if (c.now() < c.st().buildCheckAt) return false;
			c.st().buildCheckAt = c.now() + SCAN_COOLDOWN;
			pos = findFurnace(c.level(), kitchen);
			furnace = pos == null ? null : furnaceAt(c.level(), pos);
			if (furnace == null) {
				c.setActivity("wartet auf einen Brennofen");
				return false;
			}
			c.st().target = pos;
		}

		c.setActivity("brennt Holzkohle im Ofen");
		if (!JobUtil.approach(c, pos, 3.0)) return true;

		int pending = pending(furnace);
		ItemStack output = furnace.getItem(2);
		if (pending > 0 && !output.isEmpty() && output.is(Items.CHARCOAL)) {
			int moved = deliverClaimedOutput(c.level(), c.village(), furnace, storage);
			if (moved == 0) return false; // A full public store leaves output and claim in place.
			Anim.hold(c, Items.CHARCOAL);
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 40;
			return true;
		}
		if (!output.isEmpty()) return false; // Never disturb another smelting job's result.
		if (pending == 0 && StockPolicy.deliveryRoom(c.level(), c.village(), Items.CHARCOAL) <= 0) return false;

		ItemStack input = furnace.getItem(0);
		if (pending > 0 && input.isEmpty()) {
			// The claimed batch vanished before it was deposited; release only the stale claim, never adopt remaining output.
			setPending(furnace, 0);
			pending = 0;
		}
		if (pending == 0 && input.isEmpty()) {
			ItemStack log = withdraw(storage, s -> s.is(ItemTags.LOGS) && hasCharcoalRecipe(c.level(), s.copyWithCount(1)));
			if (log.isEmpty()) {
				c.setIdle(0, 400);
				return false;
			}
			if (!claimLog(c.level(), furnace, log)) {
				HopperBlockEntity.addItem(null, storage, log, null);
				return false;
			}
			input = furnace.getItem(0);
			Anim.hold(c, input.getItem());
		}
		if (pending(furnace) == 0 || !hasCharcoalRecipe(c.level(), input.copyWithCount(1))) return false;
		Anim.hold(c, input.getItem());
		if (furnace.getItem(1).isEmpty()) {
			ItemStack fuel = withdrawFuel(c.level(), furnace, storage);
			if (fuel.isEmpty()) {
				c.setIdle(0, 400);
				return false;
			}
			furnace.setItem(1, fuel);
			Anim.hold(c, fuel.getItem());
		}
		furnace.setChanged();
		c.level().sendBlockUpdated(pos, c.level().getBlockState(pos), c.level().getBlockState(pos), 3);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 100;
		return true;
	}

	private static int pending(FurnaceBlockEntity furnace) {
		Integer value = furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE);
		return value == null ? 0 : Math.max(0, value);
	}

	private static void setPending(FurnaceBlockEntity furnace, int value) {
		if (furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null) return;
		furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, Math.max(0, value));
		furnace.setChanged();
	}

	/** Records the one-log batch claim before placing its log in the furnace input. */
	static boolean claimLog(ServerLevel level, FurnaceBlockEntity furnace, ItemStack log) {
		int batchOutput = charcoalRecipeOutputCount(level, log);
		if (furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null || pending(furnace) != 0
			|| smithOwnsBatch(furnace)
			|| !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty() || batchOutput <= 0 || log.getCount() != 1
			|| !furnace.canPlaceItem(0, log)) return false;
		furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, batchOutput);
		furnace.setChanged();
		furnace.setItem(0, log);
		return true;
	}

	/** Only consumes a real tagged log when the currently loaded smelting recipes turn it into charcoal. */
	static boolean hasCharcoalRecipe(ServerLevel level, ItemStack log) {
		return charcoalRecipeOutputCount(level, log) > 0;
	}

	/** Returns the actual charcoal count from the loaded smelting recipe for this one-log batch. */
	static int charcoalRecipeOutputCount(ServerLevel level, ItemStack log) {
		if (log.isEmpty() || !log.is(ItemTags.LOGS)) return 0;
		SingleRecipeInput input = new SingleRecipeInput(log);
		AbstractCookingRecipe recipe = SmithSmelting.matchingRecipe(level, log, RecipeType.SMELTING);
		if (recipe == null) return 0;
		ItemStack result = recipe.assemble(input);
		return result.is(Items.CHARCOAL) ? Math.max(0, result.getCount()) : 0;
	}

	/** A non-empty smith claim owns the furnace even when its visible input/output was interrupted. */
	private static boolean smithOwnsBatch(FurnaceBlockEntity furnace) {
		ItemStack smithClaim = furnace.getAttached(VillagerPlus.SMITH_FURNACE);
		return smithClaim != null && !smithClaim.isEmpty();
	}

	/** Selects reusable wood scraps first; only coal is a non-wood fallback, so stored charcoal remains available downstream. */
	static ItemStack withdrawFuel(ServerLevel level, FurnaceBlockEntity furnace, Container storage) {
		Predicate<ItemStack> usable = stack -> level.fuelValues().isFuel(stack) && furnace.canPlaceItem(1, stack);
		ItemStack scraps = withdraw(storage, stack -> isWoodScrap(stack) && usable.test(stack));
		return scraps.isEmpty() ? withdraw(storage, stack -> stack.is(Items.COAL) && usable.test(stack)) : scraps;
	}

	static boolean isWoodScrap(ItemStack stack) {
		return !stack.isEmpty() && (stack.is(ItemTags.PLANKS) || stack.is(ItemTags.WOODEN_SLABS)
			|| stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK) || stack.is(Items.BAMBOO)
			|| stack.is(ItemTags.BAMBOO_BLOCKS));
	}

	/** Transfers no more than the persisted own-batch claim; excess charcoal stays in the furnace. */
	static int deliverClaimedOutput(ServerLevel level, de.villagerplus.village.VillageRef ref, FurnaceBlockEntity furnace, Container storage) {
		int budget = pending(furnace);
		ItemStack output = furnace.getItem(2);
		if (budget <= 0 || smithOwnsBatch(furnace) || !output.is(Items.CHARCOAL) || storage == null) return 0;
		int amount = Math.min(Math.min(budget, output.getCount()), StockPolicy.deliveryRoom(level, ref, Items.CHARCOAL));
		if (amount <= 0) return 0;
		ItemStack moving = output.copyWithCount(amount);
		int left = HopperBlockEntity.addItem(null, storage, moving, null).getCount();
		int moved = amount - left;
		if (moved > 0) {
			output.shrink(moved);
			setPending(furnace, budget - moved);
			furnace.setChanged();
		}
		return moved;
	}

	private static ItemStack withdraw(Container storage, Predicate<ItemStack> match) {
		for (int i = 0; i < storage.getContainerSize(); i++) {
			ItemStack stack = storage.getItem(i);
			if (!stack.isEmpty() && match.test(stack)) return storage.removeItem(i, 1);
		}
		return ItemStack.EMPTY;
	}

	private static FurnaceBlockEntity furnaceAt(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.FURNACE)) return null;
		return level.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace
			&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) != null && !smithOwnsBatch(furnace) ? furnace : null;
	}

	private static BlockPos findFurnace(ServerLevel level, BlockPos center) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -4, -16), center.offset(16, 8, 16))) {
			if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)
				|| furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null) continue;
			double distance = pos.distSqr(center);
			if (distance < bestDistance) { best = pos.immutable(); bestDistance = distance; }
		}
		return best;
	}

	private static boolean insideKitchen(BlockPos furnace, BlockPos origin) {
		return Math.abs(furnace.getX() - origin.getX()) <= 16 && Math.abs(furnace.getZ() - origin.getZ()) <= 16
			&& furnace.getY() >= origin.getY() - 4 && furnace.getY() <= origin.getY() + 16;
	}
}
