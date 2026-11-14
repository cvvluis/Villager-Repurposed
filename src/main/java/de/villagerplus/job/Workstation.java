package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.crafting.CraftingRecipe;

/**
 * Berufe holen einen echten Arbeitsblock aus der Gemeinschaftskiste, craften ihn an einer Werkbank,
 * wenn die Zutaten reichen, und stellen ihn selbst auf.
 */
public final class Workstation {
	private Workstation() { }

	public static BlockPos find(net.minecraft.server.level.ServerLevel level, BlockPos origin, Block block, int radius) {
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius), origin.offset(radius, 4, radius))) {
			if (level.hasChunkAt(pos) && level.getBlockState(pos).is(block)) return pos.immutable();
		}
		return null;
	}

	/** @return true while crafting, walking or placing; false when the block is already available or impossible */
	public static boolean ensure(Ctx c, Block block) {
		if (c.village() == null || block == null || block == Blocks.AIR) return false;
		if (block == Blocks.ANVIL) return ensureAnvil(c);
		BlockPos chestPos = c.village().chestPos(c.level());
		return ensure(c, block, chestPos);
	}

	/**
	 * Ensures a station in the village region anchored at {@code anchor}, while all crafting inputs and results stay in
	 * the real communal depot. This lets a profession build a missing appliance in its marked kitchen rather than placing
	 * an unreachable duplicate beside the village chest.
	 */
	public static boolean ensure(Ctx c, Block block, BlockPos anchor) {
		if (c.village() == null || block == null || block == Blocks.AIR) return false;
		if (block == Blocks.ANVIL) return ensureAnvil(c);
		BlockPos chestPos = c.village().chestPos(c.level());
		if (chestPos == null) return false;
		BlockPos origin = anchor == null ? chestPos : anchor.immutable();
		if (!c.level().hasChunkAt(origin) || find(c.level(), origin, block, 8) != null) return false;
		Container chest = communal(c);
		if (chest == null) return false;
		Item item = block.asItem();
		if (item == null || item == Items.AIR) return false;
		BlockPos table = find(c.level(), origin, Blocks.CRAFTING_TABLE, 8);
		if (block != Blocks.CRAFTING_TABLE && table == null) {
			if (ensure(c, Blocks.CRAFTING_TABLE, origin)) return true;
			table = find(c.level(), origin, Blocks.CRAFTING_TABLE, 8);
			if (table == null) return false;
		}
		Predicate<ItemStack> match = stack -> stack.is(item);
		BlockState state = block.defaultBlockState();
		if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
			state = state.setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
		}
		if (JobUtil.count(chest, match) == 0) {
			if (block == Blocks.CRAFTING_TABLE) {
				if (JobUtil.count(chest, stack -> stack.is(net.minecraft.tags.ItemTags.PLANKS)) < 4
					&& RecipeCrafting.craft(c, chest, null, stack -> stack.is(net.minecraft.tags.ItemTags.PLANKS), 16)) return true;
				return RecipeCrafting.craft(c, chest, null, match, 1);
			}
			BlockPos plannedSite = site(c, origin, state);
			if (plannedSite == null) {
				c.setActivity("wartet auf einen freien Platz für den Arbeitsblock");
				return false;
			}
			return table != null && RecipeCrafting.craft(c, chest, table, match, 1);
		}
		BlockPos spot = site(c, origin, state);
		if (spot == null) return false;
		c.setActivity("stellt einen Arbeitsblock auf");
		Anim.hold(c, item);
		if (!JobUtil.approach(c, spot, 3.0)) return true;
		if (!canPlace(c, spot, state) || JobUtil.count(chest, match) <= 0) return false;
		if (!c.level().setBlock(spot, state, 3) || !c.level().getBlockState(spot).is(block)) return false;
		if (block == Blocks.FURNACE || block == Blocks.SMOKER || block == Blocks.BLAST_FURNACE) {
			var entity = c.level().getBlockEntity(spot);
			if (entity != null) entity.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
		}
		if (JobUtil.take(chest, match, 1) != 1) {
			// The server tick is single-threaded, but keep the world and the depot atomic if an inventory hook rejects the debit.
			if (c.level().getBlockState(spot).is(block)) c.level().setBlock(spot, Blocks.AIR.defaultBlockState(), 3);
			return false;
		}
		c.level().playSound(null, spot, net.minecraft.sounds.SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 20;
		return true;
	}

	/**
	 * An anvil is expensive enough that it must use the vanilla three-block/four-ingot recipe, and its materials must stay
	 * in the communal depot until both a real recipe transaction and a valid placement are possible. RecipeCrafting's
	 * commit is the reservation boundary: it removes the exact recipe inputs and inserts the crafted anvil atomically.
	 */
	private static final java.util.Map<Long, java.util.Map.Entry<java.util.UUID, Long>> ANVIL_CLAIMS = new java.util.HashMap<>();
	private static final long ANVIL_CLAIM_TICKS = 1200L;

	/** Only one resident per village fetches or crafts the shared anvil; the others keep their own work. */
	private static boolean claimAnvilWork(Ctx c) {
		long key = c.village().bell().asLong();
		var claim = ANVIL_CLAIMS.get(key);
		if (claim != null && c.now() < claim.getValue() && !claim.getKey().equals(c.v().getUUID())
			&& c.level().getEntity(claim.getKey()) != null) return false;
		ANVIL_CLAIMS.put(key, java.util.Map.entry(c.v().getUUID(), c.now() + ANVIL_CLAIM_TICKS));
		return true;
	}

	private static boolean ensureAnvil(Ctx c) {
		BlockPos chestPos = c.village().chestPos(c.level());
		if (chestPos == null || find(c.level(), chestPos, Blocks.ANVIL, 8) != null) return false;
		Container chest = communal(c);
		if (chest == null) return false;
		if (!claimAnvilWork(c)) return false;

		BlockPos table = find(c.level(), chestPos, Blocks.CRAFTING_TABLE, 8);
		if (table == null) {
			if (ensure(c, Blocks.CRAFTING_TABLE)) return true;
			table = find(c.level(), chestPos, Blocks.CRAFTING_TABLE, 8);
			if (table == null) return false;
		}

		// Do not commit scarce iron while the village has nowhere safe to place the result. The raw ingredients remain
		// untouched in the real depot and can be retried when terrain or an obstruction changes.
		BlockPos spot = site(c, chestPos);
		if (spot == null) {
			c.setActivity("wartet auf einen freien Platz für den Amboss");
			return false;
		}

		Predicate<ItemStack> anvil = stack -> stack.is(Items.ANVIL);
		if (JobUtil.count(chest, anvil) == 0) {
			int ironBlocks = JobUtil.count(chest, stack -> stack.is(Items.IRON_BLOCK));
			int ironIngots = JobUtil.count(chest, stack -> stack.is(Items.IRON_INGOT));
			if (ironBlocks < 3) {
				if (ironIngots >= 9) return craftVanillaIronBlock(c, chest, table);
				c.setActivity("wartet auf neun Eisenbarren für den nächsten Ambossblock");
				return false;
			}
			if (ironIngots < 4) {
				c.setActivity("wartet auf vier Eisenbarren für den Amboss");
				return false;
			}
			return craftVanillaAnvil(c, chest, table);
		}

		c.setActivity("bringt den Amboss zum freien Platz");
		Anim.hold(c, Items.ANVIL);
		if (!JobUtil.approachLong(c, spot, 3.0)) return true;
		BlockState state = Blocks.ANVIL.defaultBlockState();
		if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
			state = state.setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
		}
		if (!validAnvilSite(c, spot, state) || JobUtil.count(chest, anvil) <= 0) return false;
		if (!c.level().setBlock(spot, state, 3) || !c.level().getBlockState(spot).is(Blocks.ANVIL)) return false;
		if (JobUtil.take(chest, anvil, 1) != 1) {
			// The server tick is single-threaded, but roll back defensively if another inventory hook changed the depot.
			if (c.level().getBlockState(spot).is(Blocks.ANVIL)) c.level().setBlock(spot, Blocks.AIR.defaultBlockState(), 3);
			return false;
		}
		c.level().playSound(null, spot, net.minecraft.sounds.SoundEvents.ANVIL_PLACE,
			net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 20;
		return true;
	}

	private static boolean craftVanillaAnvil(Ctx c, Container chest, BlockPos table) {
		for (var holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)
				|| !holder.id().identifier().getNamespace().equals("minecraft")
				|| !holder.id().identifier().getPath().equals("anvil")) continue;
			var input = RecipeCrafting.inputFor(recipe, chest, c.level());
			if (input == null || input.size() != 9 || !vanillaAnvilPattern(input)) continue;
			ItemStack output = recipe.assemble(input);
			if (!output.is(Items.ANVIL) || output.getCount() != 1) continue;
			var remainder = recipe.getRemainingItems(input);
			c.setActivity("schmiedet einen Amboss aus drei Eisenblöcken und vier Eisenbarren");
			if (!JobUtil.approachLong(c, table, 3.0)) return true;
			if (!RecipeCrafting.commit(chest, input, output, remainder)) {
				c.setActivity("wartet auf die reservierten Eisenmaterialien und freien Lagerplatz");
				return false;
			}
			Anim.hold(c, Items.IRON_INGOT);
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 100;
			return true;
		}
		c.setActivity("wartet auf das Vanilla-Ambossrezept");
		return false;
	}

	private static boolean craftVanillaIronBlock(Ctx c, Container chest, BlockPos table) {
		for (var holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)
				|| !holder.id().identifier().getNamespace().equals("minecraft")
				|| !holder.id().identifier().getPath().equals("iron_block")) continue;
			var input = RecipeCrafting.inputFor(recipe, chest, c.level());
			if (input == null || input.size() != 9 || !vanillaIronBlockPattern(input)) continue;
			ItemStack output = recipe.assemble(input);
			if (!output.is(Items.IRON_BLOCK) || output.getCount() != 1) continue;
			var remainder = recipe.getRemainingItems(input);
			c.setActivity("schmiedet einen Eisenblock aus neun Eisenbarren für den Amboss");
			if (!JobUtil.approachLong(c, table, 3.0)) return true;
			if (!RecipeCrafting.commit(chest, input, output, remainder)) {
				c.setActivity("wartet auf die reservierten Eisenbarren und freien Lagerplatz");
				return false;
			}
			Anim.hold(c, Items.IRON_INGOT);
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 100;
			return true;
		}
		c.setActivity("wartet auf das Vanilla-Eisenblockrezept");
		return false;
	}

	private static boolean vanillaAnvilPattern(net.minecraft.world.item.crafting.CraftingInput input) {
		return input.getItem(0).is(Items.IRON_BLOCK) && input.getItem(1).is(Items.IRON_BLOCK)
			&& input.getItem(2).is(Items.IRON_BLOCK) && input.getItem(3).isEmpty()
			&& input.getItem(4).is(Items.IRON_INGOT) && input.getItem(5).isEmpty()
			&& input.getItem(6).is(Items.IRON_INGOT) && input.getItem(7).is(Items.IRON_INGOT)
			&& input.getItem(8).is(Items.IRON_INGOT);
	}

	private static boolean vanillaIronBlockPattern(net.minecraft.world.item.crafting.CraftingInput input) {
		for (int slot = 0; slot < 9; slot++) if (!input.getItem(slot).is(Items.IRON_INGOT)) return false;
		return true;
	}

	private static boolean validAnvilSite(Ctx c, BlockPos spot, BlockState state) {
		BlockPos floor = spot.below();
		return c.level().hasChunkAt(spot) && c.level().hasChunkAt(spot.above()) && c.level().hasChunkAt(floor)
			&& c.level().getBlockState(spot).isAir() && c.level().getBlockState(spot.above()).isAir()
			&& c.level().getBlockState(floor).isFaceSturdy(c.level(), floor, Direction.UP)
			&& state.canSurvive(c.level(), spot)
			&& c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
				new net.minecraft.world.phys.AABB(spot).expandTowards(0, 2, 0), entity -> entity.isAlive() && entity != c.v()).isEmpty();
	}

	/**
	 * Ensures a plan-processing furnace owned by this village. Unlike the general workstation lookup, this never adopts an
	 * unmarked structure furnace: it either reuses a marked communal furnace or crafts/places one from real depot stock.
	 */
	public static boolean ensurePlanStoneFurnace(Ctx c) {
		if (c.village() == null || !c.village().data().hasPlan()) return false;
		BlockPos chestPos = c.village().chestPos(c.level());
		if (chestPos == null || findMarkedFurnace(c, chestPos) != null) return false;
		Container chest = communal(c);
		if (chest == null) return false;
		BlockPos table = find(c.level(), chestPos, Blocks.CRAFTING_TABLE, 8);
		if (table == null) {
			if (ensure(c, Blocks.CRAFTING_TABLE)) return true;
			table = find(c.level(), chestPos, Blocks.CRAFTING_TABLE, 8);
			if (table == null) return false;
		}
		if (JobUtil.count(chest, stack -> stack.is(Items.FURNACE)) == 0) {
			return craftPlanFurnace(c, chest, table);
		}
		BlockPos spot = site(c, chestPos);
		if (spot == null) return false;
		c.setActivity("stellt einen eigenen Ofen für den Bauplan auf");
		Anim.hold(c, Items.FURNACE);
		if (!JobUtil.approachLong(c, spot, 3.0)) return true;
		if (!c.level().getBlockState(spot).isAir() || JobUtil.count(chest, stack -> stack.is(Items.FURNACE)) <= 0) return false;
		c.level().setBlock(spot, Blocks.FURNACE.defaultBlockState(), 3);
		if (!(c.level().getBlockEntity(spot) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace)) {
			c.level().setBlock(spot, Blocks.AIR.defaultBlockState(), 3);
			return false;
		}
		if (JobUtil.take(chest, stack -> stack.is(Items.FURNACE), 1) != 1) {
			c.level().setBlock(spot, Blocks.AIR.defaultBlockState(), 3);
			return false;
		}
		furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
		furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
		furnace.setChanged();
		c.level().playSound(null, spot, net.minecraft.sounds.SoundEvents.STONE_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 20;
		return true;
	}

	private static BlockPos findMarkedFurnace(Ctx c, BlockPos chest) {
		for (BlockPos pos : BlockPos.betweenClosed(chest.offset(-8, -2, -8), chest.offset(8, 4, 8))) {
			if (!c.level().hasChunkAt(pos) || !c.level().getBlockState(pos).is(Blocks.FURNACE)
				|| !(c.level().getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace)
				|| furnace.getAttached(VillagerPlus.COMMUNAL_FURNACE) == null) continue;
			if (furnace.getAttached(VillagerPlus.SMITH_FURNACE) == null) {
				furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
				furnace.setChanged();
			}
			return pos.immutable();
		}
		return null;
	}

	private static boolean craftPlanFurnace(Ctx c, Container chest, BlockPos table) {
		for (var holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			ItemStack preview = RecipeCrafting.preview(recipe);
			if (preview == null || !preview.is(Items.FURNACE) || preview.getCount() != 1) continue;
			var input = RecipeCrafting.inputFor(recipe, chest, c.level());
			if (input == null || input.size() != 9) continue;
			int cobble = 0;
			boolean exactRecipe = true;
			for (int i = 0; i < input.size(); i++) {
				ItemStack ingredient = input.getItem(i);
				if (ingredient.isEmpty()) continue;
				if (!ingredient.is(Items.COBBLESTONE) || ingredient.getCount() != 1) exactRecipe = false;
				else cobble++;
			}
			ItemStack output = recipe.assemble(input);
			if (!exactRecipe || cobble != 8 || !output.is(Items.FURNACE) || output.getCount() != 1) continue;
			if (!JobUtil.approachLong(c, table, 3.0)) return true;
			if (!RecipeCrafting.commit(chest, input, output, recipe.getRemainingItems(input))) return false;
			c.setActivity("stellt einen Ofen aus acht Bruchstein her");
			Anim.hold(c, Items.COBBLESTONE);
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 100;
			return true;
		}
		return false;
	}

	private static Container communal(Ctx c) {
		BlockPos pos = c.village().chestPos(c.level());
		if (pos == null) return null;
		var entity = c.level().getBlockEntity(pos);
		if (entity != null && entity.getAttached(VillagerPlus.OWNER) != null) return null;
		return c.village().chest(c.level());
	}

	private static BlockPos site(Ctx c, BlockPos chest) {
		for (int radius = 2; radius <= 4; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				BlockPos spot = chest.offset(dx, 0, dz);
				if (canPlace(c, spot)) {
					return spot;
				}
			}
		}
		return null;
	}

	private static BlockPos site(Ctx c, BlockPos center, BlockState state) {
		for (int radius = 2; radius <= 4; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				BlockPos spot = center.offset(dx, 0, dz);
				if (canPlace(c, spot, state)) return spot;
			}
		}
		return null;
	}

	private static boolean canPlace(Ctx c, BlockPos spot) {
		BlockPos floor = spot.below();
		return c.level().hasChunkAt(spot) && c.level().hasChunkAt(spot.above()) && c.level().hasChunkAt(floor)
			&& c.level().getBlockState(spot).isAir() && c.level().getBlockState(spot.above()).isAir()
			&& c.level().getBlockState(floor).isFaceSturdy(c.level(), floor, Direction.UP)
			&& c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
				new net.minecraft.world.phys.AABB(spot).expandTowards(0, 2, 0), entity -> entity.isAlive() && entity != c.v()).isEmpty();
	}

	private static boolean canPlace(Ctx c, BlockPos spot, BlockState state) {
		return canPlace(c, spot) && state.canSurvive(c.level(), spot);
	}
}
