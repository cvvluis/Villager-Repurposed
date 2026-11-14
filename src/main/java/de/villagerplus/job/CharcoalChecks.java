package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.StockPolicy;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Focused ownership and conservation checks for village-owned furnace batches. */
public final class CharcoalChecks {
	private CharcoalChecks() {}

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos pos = ref.bell().offset(82, 0, 78);
		level.getChunkAt(pos);
		BlockSnapshot old = BlockSnapshot.capture(level, pos);
		BlockSnapshot oldBelow = BlockSnapshot.capture(level, pos.below());
		int checks = 0;
		try {
			level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
			if (!(level.getBlockEntity(pos) instanceof FurnaceBlockEntity)) {
				throw new IllegalStateException("CHARCOAL CHECK FAILED: furnace block entity missing");
			}
			FurnaceBlockEntity furnace = (FurnaceBlockEntity) level.getBlockEntity(pos);
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			SimpleContainer storage = new SimpleContainer(2);
			furnace.setItem(0, new ItemStack(Items.IRON_INGOT));
			check(!CharcoalJob.claimLog(level, furnace, new ItemStack(Items.OAK_LOG)) && furnace.getItem(0).is(Items.IRON_INGOT),
				"foreign furnace input cannot be claimed or overwritten"); checks++;
			furnace.setItem(0, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, new ItemStack(Items.IRON_INGOT));
			check(!CharcoalJob.claimLog(level, furnace, new ItemStack(Items.OAK_LOG)) && furnace.getItem(0).isEmpty()
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 0,
				"a persistent smith claim prevents charcoal from adopting an interrupted furnace batch"); checks++;
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			ItemStack spruceLog = new ItemStack(Items.SPRUCE_LOG);
			AbstractCookingRecipe spruceRecipe = SmithSmelting.matchingRecipe(level, spruceLog, RecipeType.SMELTING);
			int expectedSpruceOutput = spruceRecipe == null ? 0
				: spruceRecipe.assemble(new SingleRecipeInput(spruceLog)).getCount();
			check(expectedSpruceOutput > 0 && CharcoalJob.charcoalRecipeOutputCount(level, spruceLog) == expectedSpruceOutput
				&& CharcoalJob.claimLog(level, furnace, spruceLog.copy())
				&& furnace.getItem(0).is(Items.SPRUCE_LOG)
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == expectedSpruceOutput,
				"a marked furnace claims the same loaded recipe and exact output count the smelting path selects"); checks++;
			furnace.setItem(0, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			check(!CharcoalJob.hasCharcoalRecipe(level, new ItemStack(Items.DIRT))
				&& !CharcoalJob.claimLog(level, furnace, new ItemStack(Items.DIRT)),
				"untagged or unrecipeable inputs are never claimed as charcoal batches"); checks++;
			SimpleContainer fuels = new SimpleContainer(4);
			fuels.setItem(0, new ItemStack(Items.COAL, 2));
			fuels.setItem(1, new ItemStack(Items.BIRCH_PLANKS, 2));
			fuels.setItem(2, new ItemStack(Items.STICK, 2));
			fuels.setItem(3, new ItemStack(Items.CHARCOAL, 2));
			ItemStack woodFuel = CharcoalJob.withdrawFuel(level, furnace, fuels);
			check(woodFuel.is(Items.BIRCH_PLANKS) && woodFuel.getCount() == 1
				&& fuels.getItem(1).getCount() == 1
				&& fuels.getItem(0).getCount() == 2 && fuels.getItem(3).getCount() == 2,
				"registered wood scraps fuel the batch before coal, while stored charcoal is reserved for consumers"); checks++;
			ItemStack secondWoodFuel = CharcoalJob.withdrawFuel(level, furnace, fuels);
			check(secondWoodFuel.is(Items.BIRCH_PLANKS) && secondWoodFuel.getCount() == 1 && fuels.getItem(1).isEmpty(),
				"successive fuel withdrawals consume only one real item per batch"); checks++;
			ItemStack stickFuel = CharcoalJob.withdrawFuel(level, furnace, fuels);
			check(stickFuel.is(Items.STICK) && stickFuel.getCount() == 1,
				"small wood scraps such as sticks are accepted by the live fuel registry"); checks++;
			CharcoalJob.withdrawFuel(level, furnace, fuels);
			ItemStack fallbackCoal = CharcoalJob.withdrawFuel(level, furnace, fuels);
			check(fallbackCoal.is(Items.COAL) && fallbackCoal.getCount() == 1
				&& fuels.getItem(3).getCount() == 2,
				"coal is a fallback only after wood scraps, and charcoal stock is not consumed as fuel"); checks++;
			furnace.setItem(2, new ItemStack(Items.CHARCOAL, 3));
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 1);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, new ItemStack(Items.IRON_INGOT));
			check(CharcoalJob.deliverClaimedOutput(level, ref, furnace, storage) == 0
				&& furnace.getItem(2).getCount() == 3
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 1,
				"a persistent smith claim prevents charcoal delivery from a shared furnace"); checks++;
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			storage.setItem(0, new ItemStack(Items.CHARCOAL, 64));
			storage.setItem(1, new ItemStack(Items.CHARCOAL, 64));
			check(CharcoalJob.deliverClaimedOutput(level, ref, furnace, storage) == 0
				&& furnace.getItem(2).getCount() == 3
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 1,
				"full public storage leaves charcoal output and its batch claim untouched"); checks++;
			storage.clearContent();
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 3); // Simulates a loaded mod recipe yielding three charcoal from one log.
			check(CharcoalJob.deliverClaimedOutput(level, ref, furnace, storage) == 3
				&& furnace.getItem(2).isEmpty()
				&& storage.getItem(0).getCount() == 3
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 0,
				"a multi-charcoal recipe transfers its full claimed output without stranding remainder"); checks++;
			SimpleContainer batchStock = new SimpleContainer(2);
			batchStock.setItem(0, new ItemStack(Items.SPRUCE_LOG, 2));
			batchStock.setItem(1, new ItemStack(Items.BIRCH_PLANKS, 2));
			furnace.setItem(0, ItemStack.EMPTY);
			furnace.setItem(1, ItemStack.EMPTY);
			furnace.setItem(2, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			ItemStack stockedLog = batchStock.removeItem(0, 1);
			boolean logClaimed = CharcoalJob.hasCharcoalRecipe(level, stockedLog)
				&& CharcoalJob.claimLog(level, furnace, stockedLog);
			ItemStack stockedFuel = CharcoalJob.withdrawFuel(level, furnace, batchStock);
			furnace.setItem(1, stockedFuel);
			furnace.setChanged();
			check(logClaimed && batchStock.getItem(0).getCount() == 1
				&& stockedFuel.is(Items.BIRCH_PLANKS) && stockedFuel.getCount() == 1
				&& batchStock.getItem(1).getCount() == 1
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == expectedSpruceOutput,
				"one real stored log and wood-scrap fuel are reserved for a single charcoal batch"); checks++;
			CompoundTag savedBatch = furnace.saveWithFullMetadata(level.registryAccess());
			BlockEntity loadedBatch = BlockEntity.loadStatic(pos, level.getBlockState(pos), savedBatch, level.registryAccess());
			if (!(loadedBatch instanceof FurnaceBlockEntity loadedFurnace)) {
				throw new IllegalStateException("CHARCOAL CHECK FAILED: reloaded furnace block entity missing");
			}
			level.setBlockEntity(loadedFurnace);
			furnace = loadedFurnace;
			check(furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == expectedSpruceOutput
				&& furnace.getItem(0).is(Items.SPRUCE_LOG) && furnace.getItem(1).is(Items.BIRCH_PLANKS),
				"the pending real-log and wood-fuel batch survives furnace save/load"); checks++;
			for (int tick = 0; tick < 1200 && !furnace.getItem(0).isEmpty(); tick++) {
				AbstractFurnaceBlockEntity.serverTick(level, pos, level.getBlockState(pos), furnace);
			}
			ItemStack produced = spruceRecipe.assemble(new SingleRecipeInput(spruceLog));
			var usedRecipe = furnace.getRecipeUsed();
			check(furnace.getItem(0).isEmpty() && furnace.getItem(2).is(Items.CHARCOAL)
				&& furnace.getItem(2).getCount() == expectedSpruceOutput
				&& furnace.getItem(1).isEmpty() && batchStock.getItem(0).getCount() == 1
				&& batchStock.getItem(1).getCount() == 1
				&& (usedRecipe == null || usedRecipe.value() == spruceRecipe),
				"the saved furnace batch cooks through the loaded recipe into real charcoal (expected="
					+ expectedSpruceOutput + ", input=" + furnace.getItem(0) + ", fuel=" + furnace.getItem(1)
					+ ", output=" + furnace.getItem(2) + ", claimed="
					+ furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) + ", usedRecipe=" + usedRecipe + ")"); checks++;
			SimpleContainer deliveredStock = new SimpleContainer(2);
			int deliveredBatch = CharcoalJob.deliverClaimedOutput(level, ref, furnace, deliveredStock);
			check(deliveredBatch == produced.getCount() && deliveredStock.getItem(0).is(Items.CHARCOAL)
				&& deliveredStock.getItem(0).getCount() == produced.getCount()
				&& furnace.getItem(2).isEmpty()
				&& furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == 0,
				"the finished batch transfers exact charcoal output and releases its claim"); checks++;
			CharcoalJob job = new CharcoalJob();
			check(job.produce().test(new ItemStack(Items.CHARCOAL))
				&& !job.produce().test(new ItemStack(Items.COAL))
				&& new ItemStack(Items.CHARCOAL).is(ItemTags.COALS)
				&& StockPolicy.category(Items.CHARCOAL) == StockPolicy.Category.MATERIAL,
				"finished charcoal joins the shared material supply and remains usable as furnace coal for village jobs"); checks++;
			return checks;
		} finally {
			old.restore(level, pos);
			oldBelow.restore(level, pos.below());
		}
	}

	private record BlockSnapshot(BlockState state, CompoundTag entity) {
		private static BlockSnapshot capture(ServerLevel level, BlockPos pos) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			return new BlockSnapshot(level.getBlockState(pos),
				blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess()));
		}

		private void restore(ServerLevel level, BlockPos pos) {
			level.removeBlockEntity(pos);
			level.setBlock(pos, state, 3);
			if (entity != null) {
				BlockEntity restored = BlockEntity.loadStatic(pos, state, entity.copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("CHARCOAL CHECK FAILED: " + message);
	}
}
