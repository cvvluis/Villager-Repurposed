package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Built;
import de.villagerplus.village.Development;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Market;
import de.villagerplus.village.Roads;
import de.villagerplus.village.Specials;
import de.villagerplus.village.ZonePolicy;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Laternenanzünder: sucht Blocklicht unter 1 im Dorf und auf den Ausfallachsen der Überlandrouten
 * und setzt eine echte Fackel oder Laterne aus der Gemeinschaftskiste.
 */
public final class Lighting {
	static final int MIN_LIGHT_SPACING = 64;
	private static final int MAX_LIGHT_VERTICAL_DELTA = MIN_LIGHT_SPACING - 1;
	static final int ROAD_LAMP_SPACING = 80;
	private static final int PATH_GAP_SEARCH_RADIUS = 12;
	private static final int MAX_PATH_GAP = 8;
	private static final int MAX_PATH_CUT_LAYERS = 2;
	private static final int MAX_PATH_CUT_SPAN = 2;
	private static final Item[] SHOVELS = {Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL,
		Items.GOLDEN_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL};
	private Lighting() { }

	public static BlockPos findDark(ServerLevel level, BlockPos origin, int radius) {
		BlockPos near = scanDisk(level, origin, radius);
		if (near != null) return near;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			for (int step = radius + 8; step <= radius + 40; step += 8) {
				BlockPos far = surface(level, origin.relative(direction, step));
				if (far != null && placeable(level, far)) return far;
			}
		}
		return null;
	}

	public static boolean placeable(ServerLevel level, BlockPos air) {
		if (!basicPlaceable(level, air)) return false;
		return level.getBrightness(LightLayer.BLOCK, air) < 1 && spaced(level, air);
	}

	private static boolean basicPlaceable(ServerLevel level, BlockPos air) {
		if (air == null || !level.hasChunkAt(air)) return false;
		BlockState here = level.getBlockState(air);
		BlockPos below = air.below();
		BlockState floor = level.getBlockState(below);
		if (!here.isAir() || !here.getFluidState().isEmpty() || !floor.getFluidState().isEmpty()) return false;
		if (!floor.isFaceSturdy(level, below, Direction.UP) || floor.is(Blocks.FARMLAND) || floor.hasBlockEntity()) return false;
		return true;
	}

	public static boolean spaced(ServerLevel level, BlockPos air) {
		return spacing(level, air, null, false, MIN_LIGHT_SPACING, MAX_LIGHT_VERTICAL_DELTA);
	}

	/** Spacing check for road lamps; a partially built post reserves its column until completed. */
	public static boolean spaced(ServerLevel level, BlockPos air, BlockPos ownPostBase) {
		return spacing(level, air, ownPostBase, true, ROAD_LAMP_SPACING, ROAD_LAMP_SPACING - 1);
	}

	private static boolean spacing(ServerLevel level, BlockPos air, BlockPos ownPostBase, boolean checkPosts,
		int horizontalRadius, int verticalRadius) {
		if (air == null || !spacingAreaLoaded(level, air, horizontalRadius, null)) return false;
		// Road lamps can be separated vertically by a steep, still-traversable grade. Their route-spacing
		// check must see the full height difference possible within the same horizontal spacing radius.
		return !visitNearbyBlocks(level, air, horizontalRadius, air.getY() - verticalRadius,
			air.getY() + verticalRadius, checkPosts,
			(pos, state) -> isSpacingLight(state) || (checkPosts && !sameColumn(pos, ownPostBase)
				&& unfinishedRoadLampPost(level, pos)));
	}

	private static boolean isSpacingLight(BlockState state) {
		return state.is(Blocks.LANTERN) || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
			|| state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
			|| state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH);
	}

	@FunctionalInterface
	private interface NearbyBlockVisitor {
		boolean visit(BlockPos pos, BlockState state);
	}

	/** Scans only loaded chunk sections whose palettes can contain a relevant light or unfinished post. */
	private static boolean visitNearbyBlocks(ServerLevel level, BlockPos center, int horizontalRadius, int minY, int maxY,
		boolean includePosts, NearbyBlockVisitor visitor) {
		minY = Math.max(level.getMinY(), minY);
		maxY = Math.min(level.getMaxY() - 1, maxY);
		if (horizontalRadius < 0 || minY > maxY) return false;
		long radiusSquared = (long) horizontalRadius * horizontalRadius;
		int minChunkX = Math.floorDiv(center.getX() - horizontalRadius, 16);
		int maxChunkX = Math.floorDiv(center.getX() + horizontalRadius, 16);
		int minChunkZ = Math.floorDiv(center.getZ() - horizontalRadius, 16);
		int maxChunkZ = Math.floorDiv(center.getZ() + horizontalRadius, 16);
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				var chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) continue;
				int chunkMinX = chunkX << 4;
				int chunkMinZ = chunkZ << 4;
				int fromX = Math.max(0, center.getX() - horizontalRadius - chunkMinX);
				int toX = Math.min(15, center.getX() + horizontalRadius - chunkMinX);
				int fromZ = Math.max(0, center.getZ() - horizontalRadius - chunkMinZ);
				int toZ = Math.min(15, center.getZ() + horizontalRadius - chunkMinZ);
				int fromSection = chunk.getSectionIndex(minY);
				int toSection = chunk.getSectionIndex(maxY);
				for (int sectionIndex = fromSection; sectionIndex <= toSection; sectionIndex++) {
					LevelChunkSection section = chunk.getSection(sectionIndex);
					if (!section.maybeHas(Lighting::isSpacingLight)
						&& (!includePosts || !section.maybeHas(state -> state.is(Blocks.SPRUCE_LOG)))) continue;
					int sectionMinY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
					int fromY = Math.max(0, minY - sectionMinY);
					int toY = Math.min(15, maxY - sectionMinY);
					for (int localX = fromX; localX <= toX; localX++) {
						int x = chunkMinX + localX;
						int dx = x - center.getX();
						for (int localZ = fromZ; localZ <= toZ; localZ++) {
							int z = chunkMinZ + localZ;
							int dz = z - center.getZ();
							if ((long) dx * dx + (long) dz * dz >= radiusSquared) continue;
							for (int localY = fromY; localY <= toY; localY++) {
								BlockState state = section.getBlockState(localX, localY, localZ);
								if (!isSpacingLight(state) && !(includePosts && state.is(Blocks.SPRUCE_LOG))) continue;
								BlockPos pos = new BlockPos(x, sectionMinY + localY, z);
								if (visitor.visit(pos, state)) return true;
							}
						}
					}
				}
			}
		}
		return false;
	}

	/** A missing chunk intersecting the spacing disk may contain a light; defer without loading it. */
	private static boolean spacingAreaLoaded(ServerLevel level, BlockPos center, int radius, Map<Long, Boolean> cache) {
		return spacingAreaLoaded(center.getX(), center.getZ(), radius, (chunkX, chunkZ) -> {
			long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
			return cache == null
				? level.getChunkSource().getChunkNow(chunkX, chunkZ) != null
				: cache.computeIfAbsent(key, ignored -> level.getChunkSource().getChunkNow(chunkX, chunkZ) != null);
		});
	}

	static boolean spacingAreaLoaded(int x, int z, int radius, BiPredicate<Integer, Integer> chunkLoaded) {
		if (radius < 0 || chunkLoaded == null) return false;
		long radiusSquared = (long) radius * radius;
		int minChunkX = Math.floorDiv(x - radius, 16);
		int maxChunkX = Math.floorDiv(x + radius, 16);
		int minChunkZ = Math.floorDiv(z - radius, 16);
		int maxChunkZ = Math.floorDiv(z + radius, 16);
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			int chunkMinX = chunkX << 4;
			int closestX = Math.max(chunkMinX, Math.min(chunkMinX + 15, x));
			long dx = (long) closestX - x;
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				int chunkMinZ = chunkZ << 4;
				int closestZ = Math.max(chunkMinZ, Math.min(chunkMinZ + 15, z));
				long dz = (long) closestZ - z;
				if (dx * dx + dz * dz >= radiusSquared) continue;
				if (!chunkLoaded.test(chunkX, chunkZ)) return false;
			}
		}
		return true;
	}

	private static boolean sameColumn(BlockPos a, BlockPos b) {
		return b != null && a.getX() == b.getX() && a.getZ() == b.getZ();
	}

	private static boolean unfinishedRoadLampPost(ServerLevel level, BlockPos log) {
		BlockPos base = log;
		for (int down = 0; down < 2 && level.getBlockState(base.below()).is(Blocks.SPRUCE_LOG); down++) base = base.below();
		if (level.getBlockState(base.below()).is(Blocks.SPRUCE_LOG)) return false;
		BlockState floor = level.getBlockState(base.below());
		if (floor.hasBlockEntity() || !floor.getFluidState().isEmpty()
			|| !floor.isFaceSturdy(level, base.below(), Direction.UP) || !naturalLampGround(floor)) return false;
		int height = 0;
		BlockPos top = base;
		while (height < 3 && level.getBlockState(top).is(Blocks.SPRUCE_LOG)) {
			height++;
			top = top.above();
		}
		return height > 0 && height <= 2 && level.getBlockState(top).isAir();
	}

	private static boolean naturalLampGround(BlockState state) {
		return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.COARSE_DIRT)
			|| state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT)
			|| state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)
			|| state.is(Blocks.SNOW_BLOCK);
	}

	/** @return true while the worker is fetching, walking or placing */
	public static boolean work(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || c.now() < c.st().lightAt) return false;
		if (repairVillagePath(c, ref)) return true;
		BlockPos spot = c.st().target != null && placeable(c.level(), c.st().target) ? c.st().target : findDark(c.level(), ref.bell(), 12);
		if (spot == null) {
			c.st().lightAt = c.now() + 200;
			c.st().clearTarget();
			return false;
		}
		Container chest = communal(c);
		if (chest == null) return false;
		boolean torch = JobUtil.count(chest, stack -> stack.is(Items.TORCH)) > 0;
		boolean lantern = JobUtil.count(chest, stack -> stack.is(Items.LANTERN)) > 0;
		boolean craftTorch = !torch && !lantern && canCraftTorchFromCharcoal(c.level(), chest);
		if (!torch && !lantern && !craftTorch) {
			c.st().lightAt = c.now() + 200;
			c.st().clearTarget();
			return false;
		}
		c.st().target = spot.immutable();
		var item = torch || craftTorch ? Items.TORCH : Items.LANTERN;
		c.setActivity(item == Items.TORCH ? "stellt eine Fackel auf" : "setzt eine Laterne");
		Anim.hold(c, item);
		if (!JobUtil.approach(c, spot, 3.0)) return true;
		if (!placeable(c.level(), spot) || !placeFromStock(c, spot)) {
			c.st().clearTarget();
			return false;
		}
		return true;
	}

	/** Places one stored light, crafting real torches from public charcoal stock only when a valid dark site needs one. */
	static boolean placeFromStock(Ctx c, BlockPos spot) {
		if (!placeable(c.level(), spot)) return false;
		Container stock = communal(c);
		if (stock == null) return false;
		boolean torch = JobUtil.count(stock, stack -> stack.is(Items.TORCH)) > 0;
		boolean lantern = JobUtil.count(stock, stack -> stack.is(Items.LANTERN)) > 0;
		if (!torch && !lantern) {
			if (!canCraftTorchFromCharcoal(c.level(), stock) || !placeable(c.level(), spot)
				|| !craftTorchFromCharcoal(c.level(), stock)) return false;
			torch = true;
		}
		Item item = torch ? Items.TORCH : Items.LANTERN;
		if (!placeable(c.level(), spot)
			|| !RecipeCrafting.commit(stock, List.of(new ItemStack(item)), List.of())) return false;
		BlockState state = torch ? Blocks.TORCH.defaultBlockState()
			: Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false);
		if (!c.level().setBlock(spot, state, 3)) {
			// The removed item opened the slot used to return it; a failed world placement never consumes stock.
			RecipeCrafting.commit(stock, List.of(), List.of(new ItemStack(item)));
			return false;
		}
		c.level().playSound(null, spot, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.7F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().lightAt = c.now() + 20;
		c.st().clearTarget();
		return true;
	}

	/** Dry-runs the loaded torch recipe against a copy so missing capacity never consumes public inputs. */
	static boolean canCraftTorchFromCharcoal(ServerLevel level, Container stock) {
		if (stock == null) return false;
		SimpleContainer simulated = new SimpleContainer(stock.getContainerSize());
		for (int slot = 0; slot < stock.getContainerSize(); slot++) simulated.setItem(slot, stock.getItem(slot).copy());
		return craftTorchFromCharcoal(level, simulated);
	}

	/** Crafts one actual loaded torch recipe, accepting charcoal and sticks as the only source ingredients. */
	static boolean craftTorchFromCharcoal(ServerLevel level, Container stock) {
		return craftTorchFromCharcoal(level, stock, level.recipeAccess().getRecipes());
	}

	static boolean craftTorchFromCharcoal(ServerLevel level, Container stock, Iterable<RecipeHolder<?>> recipes) {
		if (stock == null || JobUtil.count(stock, stack -> stack.is(Items.CHARCOAL)) == 0
			|| JobUtil.count(stock, stack -> stack.is(Items.STICK)) == 0) return false;
		SimpleContainer ingredients = new SimpleContainer(stock.getContainerSize());
		for (int slot = 0; slot < stock.getContainerSize(); slot++) {
			ItemStack stack = stock.getItem(slot);
			if (stack.is(Items.CHARCOAL) || stack.is(Items.STICK)) ingredients.setItem(slot, stack.copy());
		}
		for (RecipeHolder<?> holder : recipes) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
			var input = RecipeCrafting.inputFor(recipe, ingredients, level);
			if (input == null) continue;
			boolean charcoal = false;
			boolean stick = false;
			for (int slot = 0; slot < input.size(); slot++) {
				ItemStack ingredient = input.getItem(slot);
				charcoal |= ingredient.is(Items.CHARCOAL);
				stick |= ingredient.is(Items.STICK);
			}
			if (!charcoal || !stick) continue;
			ItemStack output = recipe.assemble(input);
			if (output.isEmpty() || !output.is(Items.TORCH)) continue;
			if (RecipeCrafting.commit(stock, input, output, recipe.getRemainingItems(input))) return true;
		}
		return false;
	}

	/** Repairs a bounded saved-path gap with one real dirt block, or cuts at most two natural-soil layers. */
	static boolean repairVillagePath(Ctx c, VillageRef ref) {
		BlockPos gap = c.st().target;
		if (gap == null || (!recognizedVillagePathCut(c.level(), ref, gap, c.v())
			&& !recognizedVillagePathGap(c.level(), ref, gap, c.v()))) {
			gap = findVillagePathGap(c.level(), ref, PATH_GAP_SEARCH_RADIUS, c.v());
		}
		if (gap == null) return false;
		SimpleContainer inventory = c.v().getInventory();
		int toolSlot = findShovelSlot(inventory);
		if (toolSlot < 0) return fetchPathShovel(c, ref, gap, inventory);
		ItemStack shovel = inventory.getItem(toolSlot);
		if (recognizedVillagePathCut(c.level(), ref, gap, c.v())) {
			return cutVillagePathObstruction(c, ref, gap, inventory, toolSlot, shovel);
		}
		if (JobUtil.count(inventory, stack -> stack.is(Items.DIRT)) < 1) {
			BlockPos stockPos = ref.chestPos(c.level());
			Container stock = communal(c);
			if (stock == null || stockPos == null || !c.level().hasChunkAt(stockPos)
				|| JobUtil.count(stock, stack -> stack.is(Items.DIRT)) < 1
				|| !inventory.canAddItem(new ItemStack(Items.DIRT))) {
				c.st().lightAt = c.now() + 200;
				c.st().clearTarget();
				return false;
			}
			c.st().target = gap.immutable();
			c.setActivity("holt Erde für die Wegreparatur");
			Anim.hold(c, Items.DIRT);
			if (!JobUtil.approachLong(c, stockPos, 3.0)) return true;
			if (!recognizedVillagePathGap(c.level(), ref, gap, c.v())
				|| findShovelSlot(inventory) != toolSlot || inventory.getItem(toolSlot) != shovel) {
				c.st().clearTarget();
				return false;
			}
			stock = communal(c);
			if (stock == null || !inventory.canAddItem(new ItemStack(Items.DIRT))
				|| !takeOneDirt(stock, inventory)) {
				c.st().lightAt = c.now() + 200;
				c.st().clearTarget();
				return false;
			}
		}
		c.st().target = gap.immutable();
		c.setActivity("schließt eine kleine Weglücke");
		Anim.hold(c, shovel.getItem());
		if (!JobUtil.approachLong(c, gap.above(), 2.0)) return true;
		if (!recognizedVillagePathGap(c.level(), ref, gap, c.v()) || findShovelSlot(inventory) != toolSlot
			|| inventory.getItem(toolSlot) != shovel || JobUtil.count(inventory, stack -> stack.is(Items.DIRT)) < 1) {
			c.st().clearTarget();
			return false;
		}
		BlockState before = c.level().getBlockState(gap);
		if (!c.level().setBlock(gap, Blocks.DIRT_PATH.defaultBlockState(), 3)) {
			c.st().clearTarget();
			return false;
		}
		if (JobUtil.take(inventory, stack -> stack.is(Items.DIRT), 1) != 1) {
			c.level().setBlock(gap, before, 3);
			c.st().clearTarget();
			return false;
		}
		shovel.hurtAndBreak(1, c.level(), null, ignored -> { });
		inventory.setChanged();
		c.level().playSound(null, gap, Blocks.DIRT_PATH.defaultBlockState().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.65F, 0.9F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().lightAt = c.now() + 20;
		c.st().clearTarget();
		return true;
	}

	/** A saved village-path repair is real work: fetch one public shovel instead of silently abandoning it. */
	private static boolean fetchPathShovel(Ctx c, VillageRef ref, BlockPos gap, SimpleContainer inventory) {
		PublicShovelSource source = cachedPathShovelSource(c, ref);
		if (source == null) {
			c.st().lightAt = c.now() + 200;
			c.st().pathShovelSource = null;
			c.st().clearTarget();
			return false;
		}
		if (!inventory.canAddItem(source.container().getItem(source.slot()).copyWithCount(1))) {
			c.st().lightAt = c.now() + 200;
			c.st().clearTarget();
			return false;
		}
		c.st().target = gap.immutable();
		c.setActivity("holt eine Schaufel für den Dorfweg");
		Anim.hold(c, source.container().getItem(source.slot()).getItem());
		if (!JobUtil.approachLong(c, source.pos(), 3.0)) return true;
		if (!recognizedVillagePathCut(c.level(), ref, gap, c.v())
			&& !recognizedVillagePathGap(c.level(), ref, gap, c.v())) {
			c.st().pathShovelSource = null;
			c.st().clearTarget();
			return false;
		}
		source = publicPathShovelSource(c.level(), ref, source.pos());
		if (source == null || !inventory.canAddItem(source.container().getItem(source.slot()).copyWithCount(1))) {
			c.st().pathShovelSource = null;
			c.st().lightAt = c.now() + 1;
			c.st().clearTarget();
			return false;
		}
		ItemStack stored = source.container().getItem(source.slot());
		ItemStack shovel = stored.copyWithCount(1);
		if (!inventory.canAddItem(shovel)) {
			c.st().clearTarget();
			return false;
		}
		ItemStack original = stored.copy();
		stored.shrink(1);
		if (stored.isEmpty()) source.container().setItem(source.slot(), ItemStack.EMPTY);
		ItemStack leftover = inventory.addItem(shovel);
		if (!leftover.isEmpty()) {
			source.container().setItem(source.slot(), original);
			source.container().setChanged();
			c.st().clearTarget();
			return false;
		}
		source.container().setChanged();
		inventory.setChanged();
		// Re-evaluate the saved target on the next worker turn with its newly acquired tool.
		c.st().pathShovelSource = null;
		c.st().lightAt = c.now() + 1;
		c.st().clearTarget();
		return true;
	}

	/** Searches the public tool shed once, then revalidates its cached chest while the villager walks there. */
	private static PublicShovelSource cachedPathShovelSource(Ctx c, VillageRef ref) {
		PublicShovelSource source = publicPathShovelSource(c.level(), ref, c.st().pathShovelSource);
		if (source == null) {
			source = publicPathShovelSource(c.level(), ref, null);
			c.st().pathShovelSource = source == null ? null : source.pos();
		}
		return source;
	}

	private static PublicShovelSource publicPathShovelSource(ServerLevel level, VillageRef ref, BlockPos preferred) {
		if (preferred != null) {
			PublicShovelSource cached = publicPathShovelSourceAt(level, ref, preferred);
			if (cached != null) return cached;
		}
		List<BlockPos> materialStores = Lager.materialChests(level, ref);
		for (Specials.LocatedContainer source : Specials.locatedContainers(level, ref, Blueprints.TOOLSHED)) {
			if (materialStores.contains(source.pos())) continue;
			int slot = findPublicShovelSlot(source.container());
			if (slot >= 0) return new PublicShovelSource(source.pos(), source.container(), slot);
		}
		// Older saves may still contain tools in the communal material chest from before direct inserts were filtered.
		BlockPos legacy = ref.chestPos(level);
		return legacy == null ? null : publicPathShovelSourceAt(level, ref, legacy);
	}

	private static PublicShovelSource publicPathShovelSourceAt(ServerLevel level, VillageRef ref, BlockPos pos) {
		if (pos == null || !level.hasChunkAt(pos)) return null;
		BlockPos toolShed = Specials.get(ref, Blueprints.TOOLSHED);
		boolean inToolShed = toolShed != null
			&& pos.getX() >= toolShed.getX() - 9 && pos.getX() <= toolShed.getX() + 9
			&& pos.getY() >= toolShed.getY() - 2 && pos.getY() <= toolShed.getY() + 8
			&& pos.getZ() >= toolShed.getZ() - 9 && pos.getZ() <= toolShed.getZ() + 9;
		if (inToolShed && Lager.materialChests(level, ref).contains(pos)) inToolShed = false;
		boolean legacyMaterialChest = ref.chestPos(level) != null && ref.chestPos(level).equals(pos);
		if (!inToolShed && !legacyMaterialChest) return null;
		Container stock = Lager.publicStorageAt(level, pos) ? Lager.publicContainerAt(level, pos) : null;
		int slot = findPublicShovelSlot(stock);
		return stock == null || slot < 0 ? null : new PublicShovelSource(pos.immutable(), stock, slot);
	}

	private static int findPublicShovelSlot(Container stock) {
		if (stock == null) return -1;
		for (Item shovel : SHOVELS) {
			for (int slot = 0; slot < stock.getContainerSize(); slot++) {
				ItemStack stack = stock.getItem(slot);
				if (stack.is(shovel) && usableShovel(stack)) return slot;
			}
		}
		return -1;
	}

	private static boolean usableShovel(ItemStack stack) {
		return stack != null && !stack.isEmpty() && (!stack.isDamageableItem()
			|| stack.getDamageValue() < stack.getMaxDamage());
	}

	private static boolean takeOneDirt(Container stock, SimpleContainer inventory) {
		if (stock == null || inventory == null || !inventory.canAddItem(new ItemStack(Items.DIRT))) return false;
		for (int slot = 0; slot < stock.getContainerSize(); slot++) {
			ItemStack stored = stock.getItem(slot);
			if (!stored.is(Items.DIRT)) continue;
			ItemStack original = stored.copy();
			stored.shrink(1);
			if (stored.isEmpty()) stock.setItem(slot, ItemStack.EMPTY);
			ItemStack leftover = inventory.addItem(new ItemStack(Items.DIRT));
			if (!leftover.isEmpty()) {
				stock.setItem(slot, original);
				stock.setChanged();
				return false;
			}
			stock.setChanged();
			inventory.setChanged();
			return true;
		}
		return false;
	}

	static BlockPos findVillagePathGap(ServerLevel level, VillageRef ref, int radius) {
		return findVillagePathGap(level, ref, radius, null);
	}

	static BlockPos findVillagePathGap(ServerLevel level, VillageRef ref, int radius, Entity ignored) {
		// Search the complete saved plaza-to-bell path first. The bell-centered local
		// radius below is only a fallback; longer stored paths can have walkable dirt
		// gaps outside that neighborhood, and Roads already records their exact cells.
		List<BlockPos> savedPath = new ArrayList<>(Roads.savedVillagePathGrounds(level, ref));
		Set<Long> savedPathXZ = new HashSet<>(savedPath.size());
		for (BlockPos ground : savedPath) savedPathXZ.add(pathXZ(ground));
		Predicate<BlockPos> savedPathCell = cell -> cell != null && savedPathXZ.contains(pathXZ(cell));
		savedPath.sort((a, b) -> Double.compare(
			ignored == null ? a.distSqr(ref.bell()) : a.distSqr(ignored.blockPosition()),
			ignored == null ? b.distSqr(ref.bell()) : b.distSqr(ignored.blockPosition())));
		for (BlockPos ground : savedPath) {
			for (int depth = MAX_PATH_CUT_LAYERS; depth >= 1; depth--) {
				BlockPos cutGround = ground.below(depth);
				if (recognizedVillagePathCut(level, ref, cutGround, ignored, savedPathCell)) return cutGround;
			}
			if (recognizedVillagePathGap(level, ref, ground, ignored, savedPathCell)) return ground;
		}
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radius * radius) continue;
				BlockPos column = ref.bell().offset(dx, 0, dz);
				BlockPos ground = pathSurface(level, column);
				if (ground == null) continue;
				for (int depth = MAX_PATH_CUT_LAYERS; depth >= 1; depth--) {
					BlockPos cutGround = ground.below(depth);
					if (recognizedVillagePathCut(level, ref, cutGround, ignored, savedPathCell)) return cutGround;
				}
				if (recognizedVillagePathGap(level, ref, ground, ignored, savedPathCell)) return ground;
			}
		}
		return null;
	}

	static boolean recognizedVillagePathGap(ServerLevel level, VillageRef ref, BlockPos ground) {
		return recognizedVillagePathGap(level, ref, ground, null);
	}

	static boolean recognizedVillagePathGap(ServerLevel level, VillageRef ref, BlockPos ground, Entity ignored) {
		return recognizedVillagePathGap(level, ref, ground, ignored, cell -> Roads.trustedVillagePathCell(level, ref, cell));
	}

	private static boolean recognizedVillagePathGap(ServerLevel level, VillageRef ref, BlockPos ground, Entity ignored,
		Predicate<BlockPos> trustedPathCell) {
		if (ground == null || ref == null || !trustedPathCell.test(ground)) return false;
		if (!ground.equals(pathSurface(level, ground)))
			return recognizedVillagePathCut(level, ref, ground, ignored, trustedPathCell);
		if (recognizedVillagePathCut(level, ref, ground.below(), ignored, trustedPathCell)) return false;
		if (!naturalPathBase(level.getBlockState(ground)) || !repairablePathCell(level, ground, ignored)
			|| protectedPathCell(level, ref, ground)) return false;
		for (Direction direction : List.of(Direction.EAST, Direction.SOUTH)) {
			int forward = pathEndpointDistance(level, ground, direction);
			int backward = pathEndpointDistance(level, ground, direction.getOpposite());
			if (forward < 0 || backward < 0 || forward + backward - 1 > MAX_PATH_GAP) continue;
			boolean corridor = true;
			BlockPos previous = pathSurface(level, ground.relative(direction, -backward));
			if (previous == null || !trustedPathCell.test(previous)
				|| !pathCellClear(level, previous) || !level.getBlockState(previous).is(Blocks.DIRT_PATH)) continue;
			for (int step = -backward + 1; step <= forward; step++) {
				BlockPos column = ground.relative(direction, step);
				BlockPos cell = pathSurface(level, column);
				if (cell == null || !trustedPathCell.test(cell)
					|| !pathCellClear(level, cell)
					|| Math.abs(cell.getY() - previous.getY()) > 1) {
					corridor = false;
					break;
				}
				BlockState state = level.getBlockState(cell);
				boolean endpoint = step == forward;
				if (!endpoint && occupiedPathClearance(level, cell, ignored)) {
					corridor = false;
					break;
				}
				if (endpoint && !state.is(Blocks.DIRT_PATH)) {
					corridor = false;
					break;
				}
				if (!endpoint && !state.is(Blocks.DIRT_PATH)
					&& (!naturalPathBase(state) || protectedPathCell(level, ref, cell))) {
					corridor = false;
					break;
				}
				previous = cell;
			}
			if (corridor) return true;
		}
		return false;
	}

	/** Up to two natural-soil layers may be cut only when the saved route has level path anchors on both sides. */
	static boolean recognizedVillagePathCut(ServerLevel level, VillageRef ref, BlockPos ground, Entity ignored) {
		return recognizedVillagePathCut(level, ref, ground, ignored,
			cell -> Roads.trustedVillagePathCell(level, ref, cell));
	}

	private static boolean recognizedVillagePathCut(ServerLevel level, VillageRef ref, BlockPos ground, Entity ignored,
		Predicate<BlockPos> trustedPathCell) {
		return pathCut(level, ref, ground, ignored, trustedPathCell) != null;
	}

	private static PathCut pathCut(ServerLevel level, VillageRef ref, BlockPos ground, Entity ignored) {
		return pathCut(level, ref, ground, ignored, cell -> Roads.trustedVillagePathCell(level, ref, cell));
	}

	private static PathCut pathCut(ServerLevel level, VillageRef ref, BlockPos ground, Entity ignored,
		Predicate<BlockPos> trustedPathCell) {
		PathCut column = pathCutColumn(level, ref, ground, ignored, trustedPathCell);
		if (column == null) return null;

		for (Direction direction : List.of(Direction.EAST, Direction.SOUTH)) {
			BlockPos before = ground.relative(direction.getOpposite());
			BlockPos after = ground.relative(direction);
			if (!isCutAnchor(level, ref, before, trustedPathCell) || !isCutAnchor(level, ref, after, trustedPathCell)) continue;
			return new PathCut(before.above().immutable(), column.obstruction(), column.obstructionState(), column.layers());
		}

		// A short natural-soil berm may cover two adjacent saved route cells. Accept
		// the first cut only when both cells are independently safe and saved, with
		// paved anchors immediately outside the complete two-cell span. Once the
		// first cell is paved, the ordinary one-cell rule safely handles the second.
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos before = ground.relative(direction.getOpposite());
			BlockPos second = ground.relative(direction);
			BlockPos after = ground.relative(direction, MAX_PATH_CUT_SPAN);
			if (!isCutAnchor(level, ref, before, trustedPathCell)
				|| !isCutAnchor(level, ref, after, trustedPathCell)) continue;
			PathCut secondColumn = pathCutColumn(level, ref, second, ignored, trustedPathCell);
			if (secondColumn == null) continue;
			return new PathCut(before.above().immutable(), column.obstruction(), column.obstructionState(), column.layers());
		}
		return null;
	}

	/** Validates one natural path column, without requiring its neighboring path anchors. */
	private static PathCut pathCutColumn(ServerLevel level, VillageRef ref, BlockPos ground, Entity ignored,
		Predicate<BlockPos> trustedPathCell) {
		if (ground == null || ref == null || !trustedPathCell.test(ground)
			|| protectedPathCell(level, ref, ground) || !level.hasChunkAt(ground) || !level.hasChunkAt(ground.below())
			|| !level.hasChunkAt(ground.above(2))) return null;
		BlockState base = level.getBlockState(ground);
		BlockPos support = ground.below();
		BlockState supportState = level.getBlockState(support);
		if ((!base.is(Blocks.DIRT_PATH) && !naturalPathBase(base)) || base.is(Blocks.FARMLAND)
			|| base.hasBlockEntity() || !base.getFluidState().isEmpty()
			|| supportState.hasBlockEntity() || supportState.is(Blocks.FARMLAND)
			|| !supportState.isFaceSturdy(level, support, Direction.UP) || !supportState.getFluidState().isEmpty()) return null;

		int layers = 0;
		BlockPos obstruction = null;
		BlockState obstructionState = null;
		for (int height = 1; height <= MAX_PATH_CUT_LAYERS; height++) {
			BlockPos layer = ground.above(height);
			if (!level.hasChunkAt(layer) || protectedPathCell(level, ref, layer)) return null;
			BlockState state = level.getBlockState(layer);
			if (!naturalPathBase(state) || state.hasBlockEntity() || !state.getFluidState().isEmpty()) {
				if (height == 1 || !state.isAir() || !state.getFluidState().isEmpty()) return null;
				break;
			}
			layers++;
			obstruction = layer;
			obstructionState = state;
		}
		if (layers == 0) return null;
		if (layers == MAX_PATH_CUT_LAYERS) {
			BlockPos headroom = ground.above(layers + 1);
			if (!level.hasChunkAt(headroom) || !level.getBlockState(headroom).isAir()
				|| !level.getBlockState(headroom).getFluidState().isEmpty()) return null;
		}
		// Check the completed two-block path clearance and the space directly above a soil hump,
		// where an entity can be standing while the upper layer is being removed.
		int occupiedSpan = Math.max(3, layers + 2);
		if (occupiedPathClearance(level, ground, ignored, occupiedSpan)) return null;
		return new PathCut(null, obstruction.immutable(), obstructionState, layers);
	}

	private static boolean isCutAnchor(ServerLevel level, VillageRef ref, BlockPos anchor, Predicate<BlockPos> trustedPathCell) {
		return trustedPathCell.test(anchor) && !protectedPathCell(level, ref, anchor)
			&& level.hasChunkAt(anchor) && level.getBlockState(anchor).is(Blocks.DIRT_PATH)
			&& pathCellClear(level, anchor);
	}

	private static long pathXZ(BlockPos pos) {
		return ((long) pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL);
	}

	private static boolean cutVillagePathObstruction(Ctx c, VillageRef ref, BlockPos ground, Container inventory,
		int toolSlot, ItemStack shovel) {
		PathCut cut = pathCut(c.level(), ref, ground, c.v());
		if (cut == null) return false;
		BlockPos obstruction = cut.obstruction();
		BlockState obstacleState = cut.obstructionState();
		BlockState baseState = c.level().getBlockState(ground);
		c.st().target = ground.immutable();
		c.setActivity("hebt einen Erdblock aus dem Weg");
		Anim.hold(c, shovel.getItem());
		if (!JobUtil.approach(c, cut.worksite(), 2.0)) return true;
		PathCut current = pathCut(c.level(), ref, ground, c.v());
		if (current == null || c.level().getBlockState(ground) != baseState) {
			Anim.cancelBreaking(c.v());
			c.st().lightAt = c.now() + 20;
			return true;
		}
		if (!current.obstruction().equals(obstruction) || current.obstructionState() != obstacleState) {
			Anim.cancelBreaking(c.v());
			return true;
		}
		if (findShovelSlot(inventory) != toolSlot || inventory.getItem(toolSlot) != shovel) {
			Anim.cancelBreaking(c.v());
			c.st().lightAt = c.now() + 20;
			return true;
		}
		List<ItemStack> drops = Block.getDrops(obstacleState, c.level(), obstruction, null, c.v(), shovel.copyWithCount(1));
		if (!Woodcutting.canFitAllDrops(inventory, drops)) {
			Anim.cancelBreaking(c.v());
			c.setActivity("wartet auf freien Inventarplatz für den Erdaushub");
			c.st().lightAt = c.now() + 200;
			return true;
		}
		if (!Anim.breakStep(c, obstruction, obstacleState, shovel.getItem())) return true;
		// The villager may have moved into the cut column while the break animation was running.
		// Recheck terrain, occupants, tool and capacity in the same call that would remove the block.
		PathCut ready = pathCut(c.level(), ref, ground, c.v());
		if (ready == null || c.level().getBlockState(ground) != baseState) {
			Anim.cancelBreaking(c.v());
			c.st().lightAt = c.now() + 20;
			return true;
		}
		if (!ready.obstruction().equals(obstruction) || ready.obstructionState() != obstacleState
			|| findShovelSlot(inventory) != toolSlot || inventory.getItem(toolSlot) != shovel) {
			Anim.cancelBreaking(c.v());
			c.st().lightAt = c.now() + 20;
			return true;
		}
		drops = Block.getDrops(obstacleState, c.level(), obstruction, null, c.v(), shovel.copyWithCount(1));
		if (!Woodcutting.canFitAllDrops(inventory, drops)) {
			Anim.cancelBreaking(c.v());
			c.setActivity("wartet auf freien Inventarplatz für den Erdaushub");
			c.st().lightAt = c.now() + 200;
			return true;
		}
		boolean completesCut = ready.layers() == 1;
		if (completesCut && !baseState.is(Blocks.DIRT_PATH)
			&& !c.level().setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), 3)) {
			Anim.cancelBreaking(c.v());
			c.st().lightAt = c.now() + 20;
			return true;
		}
		if (!c.level().setBlock(obstruction, Blocks.AIR.defaultBlockState(), 3)) {
			if (completesCut && !baseState.is(Blocks.DIRT_PATH)) c.level().setBlock(ground, baseState, 3);
			Anim.cancelBreaking(c.v());
			c.st().lightAt = c.now() + 20;
			return true;
		}
		for (ItemStack drop : drops) JobUtil.give(c.level(), c.v(), drop);
		Anim.blockBreak(c.level(), obstruction, obstacleState);
		shovel.hurtAndBreak(1, c.level(), null, ignored -> { });
		inventory.setChanged();
		c.level().playSound(null, obstruction, obstacleState.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.7F, 0.9F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().lightAt = c.now() + 20;
		if (completesCut) c.st().clearTarget();
		return true;
	}

	/** Returns the exposed ground in an already-loaded column; never requests a chunk load. */
	private static BlockPos pathSurface(ServerLevel level, BlockPos column) {
		if (column == null || !level.hasChunkAt(column)) return null;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1;
		BlockPos ground = new BlockPos(column.getX(), y, column.getZ());
		return level.hasChunkAt(ground) && level.hasChunkAt(ground.above(2)) ? ground : null;
	}

	private static boolean repairablePathCell(ServerLevel level, BlockPos ground, Entity ignored) {
		return pathCellClear(level, ground) && !occupiedPathClearance(level, ground, ignored);
	}

	private static boolean pathCellClear(ServerLevel level, BlockPos ground) {
		if (ground == null || !level.hasChunkAt(ground) || !level.hasChunkAt(ground.above(2))) return false;
		BlockState base = level.getBlockState(ground);
		BlockPos support = ground.below();
		return (base.is(Blocks.DIRT_PATH) || naturalPathBase(base)) && !base.hasBlockEntity()
			&& base.getFluidState().isEmpty() && !base.is(Blocks.FARMLAND)
			&& level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)
			&& level.getBlockState(support).getFluidState().isEmpty()
			&& level.getBlockState(ground.above()).isAir() && level.getBlockState(ground.above(2)).isAir()
			&& level.getBlockState(ground.above()).getFluidState().isEmpty()
			&& level.getBlockState(ground.above(2)).getFluidState().isEmpty();
	}

	private static boolean occupiedPathClearance(ServerLevel level, BlockPos ground, Entity ignored) {
		return occupiedPathClearance(level, ground, ignored, 3);
	}

	private static boolean occupiedPathClearance(ServerLevel level, BlockPos ground, Entity ignored, int verticalSpan) {
		AABB clearance = new AABB(ground.getX(), ground.getY(), ground.getZ(), ground.getX() + 1,
			ground.getY() + verticalSpan, ground.getZ() + 1);
		return !level.getEntities(ignored, clearance, Entity::isPushable).isEmpty();
	}

	private static int pathEndpointDistance(ServerLevel level, BlockPos gap, Direction direction) {
		for (int distance = 1; distance <= MAX_PATH_GAP; distance++) {
			BlockPos pos = pathSurface(level, gap.relative(direction, distance));
			if (pos == null || !pathCellClear(level, pos)) return -1;
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.DIRT_PATH)) {
				return distance;
			}
			if (!naturalPathBase(state)) return -1;
		}
		return -1;
	}

	private static boolean naturalPathBase(BlockState state) {
		return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.COARSE_DIRT)
			|| state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT);
	}

	static boolean protectedPathCell(ServerLevel level, VillageRef ref, BlockPos ground) {
		return protectedVillageCell(level, ref, ground);
	}

	/** True when a physical build footprint must not be placed in this village cell. */
	public static boolean protectedVillageCell(ServerLevel level, VillageRef ref, BlockPos ground) {
		if (ref == null || ground == null) return false;
		for (ZonePolicy.Zone zone : ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of())) {
			if (ground.getX() >= zone.minX() && ground.getX() <= zone.maxX()
				&& ground.getZ() >= zone.minZ() && ground.getZ() <= zone.maxZ()) return true;
		}
		if (ground.distSqr(ref.bell()) <= 9) return true;
		for (Built built : ref.bellEntity().getAttachedOrElse(VillagerPlus.BUILDINGS, List.of())) {
			int half = Blueprints.half(built.type()) + 1;
			if (Math.abs(ground.getX() - built.origin().getX()) <= half
				&& Math.abs(ground.getZ() - built.origin().getZ()) <= half
				&& ground.getY() >= built.origin().getY() - 2
				&& ground.getY() <= built.origin().getY() + Blueprints.headroom(built.type())) return true;
		}
		if (Development.nearOriginalStructure(level, ref, ground, 2)) return true;
		BlockPos chest = ref.chestPos(level);
		if (chest != null && ground.distSqr(chest) <= 9) return true;
		if (ref.data().planOrigin.filter(origin -> Math.abs(ground.getX() - origin.getX()) <= 10
			&& Math.abs(ground.getZ() - origin.getZ()) <= 10).isPresent()) return true;
		return ref.data().extra.board.filter(pos -> ground.distSqr(pos) <= 36).isPresent()
			|| ref.data().extra.school.filter(pos -> ground.distSqr(pos) <= 64).isPresent()
			|| ref.data().extra.lager.filter(pos -> ground.distSqr(pos) <= 64).isPresent()
			|| ref.data().extra.pen.filter(pos -> ground.distSqr(pos) <= 64).isPresent();
	}

	private static int findShovelSlot(Container inventory) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!usableShovel(stack)) continue;
			Item item = stack.getItem();
			for (Item shovel : SHOVELS) if (item == shovel) return slot;
		}
		return -1;
	}

	private record PathCut(BlockPos worksite, BlockPos obstruction, BlockState obstructionState, int layers) { }
	private record PublicShovelSource(BlockPos pos, Container container, int slot) { }

	private static BlockPos scanDisk(ServerLevel level, BlockPos origin, int radius) {
		List<BlockPos> candidates = new ArrayList<>();
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz > radius * radius) continue;
				BlockPos spot = surface(level, origin.offset(dx, 0, dz));
				if (spot == null || !basicPlaceable(level, spot) || level.getBrightness(LightLayer.BLOCK, spot) >= 1) continue;
				candidates.add(spot);
				minY = Math.min(minY, spot.getY());
				maxY = Math.max(maxY, spot.getY());
			}
		}
		if (candidates.isEmpty()) return null;
		List<BlockPos> lights = new ArrayList<>();
		visitNearbyBlocks(level, origin, radius + MIN_LIGHT_SPACING,
			minY - MAX_LIGHT_VERTICAL_DELTA, maxY + MAX_LIGHT_VERTICAL_DELTA, false, (pos, state) -> {
			lights.add(pos);
			return false;
		});
		Map<Long, Boolean> knownChunks = new HashMap<>();
		for (BlockPos candidate : candidates) {
			if (spacingAreaLoaded(level, candidate, MIN_LIGHT_SPACING, knownChunks)
				&& spacedFromLights(candidate, lights)) return candidate;
		}
		return null;
	}

	private static boolean spacedFromLights(BlockPos air, List<BlockPos> lights) {
		int radiusSquared = MIN_LIGHT_SPACING * MIN_LIGHT_SPACING;
		for (BlockPos light : lights) {
			int dx = light.getX() - air.getX();
			int dz = light.getZ() - air.getZ();
			if (dx * dx + dz * dz < radiusSquared
				&& Math.abs(light.getY() - air.getY()) <= MAX_LIGHT_VERTICAL_DELTA) return false;
		}
		return true;
	}

	private static BlockPos surface(ServerLevel level, BlockPos column) {
		if (!level.hasChunkAt(column)) return null;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
		return new BlockPos(column.getX(), y, column.getZ());
	}

	private static Container communal(Ctx c) {
		BlockPos pos = c.village().chestPos(c.level());
		if (pos == null) return null;
		var blockEntity = c.level().getBlockEntity(pos);
		if (blockEntity != null && blockEntity.getAttached(VillagerPlus.OWNER) != null) return null;
		return c.village().chest(c.level());
	}
}
