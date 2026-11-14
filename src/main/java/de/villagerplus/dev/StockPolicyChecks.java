package de.villagerplus.dev;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.StockPolicy;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Development-only checks for persistent communal stock caps and surplus delivery. */
public final class StockPolicyChecks {
	private StockPolicyChecks() { }

	public static int run(ServerLevel level, VillageRef ref, Villager villager) {
		Map<String, Integer> oldPolicies = ref.bellEntity().getAttachedOrElse(VillagerPlus.STOCK_POLICIES, Map.of());
		Map<String, BlockPos> oldSpecials = Specials.all(ref);
		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> oldStandSupport = new java.util.LinkedHashMap<>();
		Container communal = ref.chest(level);
		if (communal == null) throw new IllegalStateException("Stock policy checks need a village chest");
		List<ItemStack> oldCommunal = snapshot(communal);
		Container inventory = villager.getInventory();
		List<ItemStack> oldInventory = snapshot(inventory);
		BlockPos privatePos = ref.bell().south(8).above();
		var oldPrivateState = level.getBlockState(privatePos);
		var oldFootingState = level.getBlockState(privatePos.below());
		BlockPos granaryPos = ref.bell().offset(-80, 0, -80), toolshedPos = ref.bell().offset(-80, 0, 80);
		BlockPos privateDepotPos = granaryPos.east(2);
		BlockPos privatePairMatePos = privateDepotPos.east();
		var oldGranaryState = level.getBlockState(granaryPos); var oldGranaryFoot = level.getBlockState(granaryPos.below());
		var oldToolshedState = level.getBlockState(toolshedPos); var oldToolshedFoot = level.getBlockState(toolshedPos.below());
		var oldPrivateDepotState = level.getBlockState(privateDepotPos); var oldPrivateDepotFoot = level.getBlockState(privateDepotPos.below());
		var oldPrivatePairMateState = level.getBlockState(privatePairMatePos); var oldPrivatePairMateFoot = level.getBlockState(privatePairMatePos.below());
		var oldPosition = villager.position();
		var oldCenterChest = ref.data().chest;
		var oldLager = ref.data().extra.lager;
		var oldLagerMore = new ArrayList<>(ref.data().extra.lagerMore);
		var oldLagerChests = new ArrayList<>(ref.data().extra.lagerChests);
		boolean privatePlaced = false;
		boolean privateTouched = false;
		boolean depotBlocksTouched = false;
		boolean privatePairBlocksTouched = false;
		boolean unregisteredChestTouched = false;
		int[] passed = {0};
		try {
			if (!oldPrivateState.isAir() || !oldGranaryState.isAir() || !oldToolshedState.isAir()
				|| !oldPrivateDepotState.isAir() || level.getBlockEntity(privateDepotPos) != null
				|| !oldPrivatePairMateState.isAir() || level.getBlockEntity(privatePairMatePos) != null) {
				throw new IllegalStateException("Stock policy fixture positions must be empty and have no block entities");
			}
			if (oldCenterChest.filter(pos -> pos.equals(privatePos) || pos.equals(privateDepotPos) || pos.equals(privatePairMatePos)).isPresent()
				|| oldLagerChests.contains(privatePos) || oldLagerChests.contains(privateDepotPos) || oldLagerChests.contains(privatePairMatePos)) {
				throw new IllegalStateException("Stock policy fixture positions must not replace registered public or private storage");
			}
			for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos neighbor = privateDepotPos.relative(direction);
				if (neighbor.equals(privatePairMatePos)) continue;
				if (level.getBlockState(neighbor).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
					throw new IllegalStateException("Stock policy paired-depot fixture cannot touch a pre-existing neighboring chest");
				}
			}
			for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos neighbor = privatePairMatePos.relative(direction);
				if (neighbor.equals(privateDepotPos)) continue;
				if (level.getBlockState(neighbor).getBlock() instanceof net.minecraft.world.level.block.ChestBlock
					|| level.getBlockEntity(neighbor) != null) {
					throw new IllegalStateException("Stock policy paired-depot fixture cannot touch a neighboring chest or block entity");
				}
			}
			for (BlockPos footing : List.of(privatePos.below(), granaryPos.below(), toolshedPos.below(),
				privateDepotPos.below(), privatePairMatePos.below())) {
				if (level.getBlockEntity(footing) != null) {
					throw new IllegalStateException("Stock policy fixture footing must not contain a block entity");
				}
			}
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of());
			check(passed, StockPolicy.target(ref, Items.BREAD) == StockPolicy.UNLIMITED, "missing policy defaults to unlimited");
			Item unstockedTool = List.of(Items.NETHERITE_HOE, Items.DIAMOND_HOE, Items.GOLDEN_HOE).stream()
				.filter(item -> StockPolicy.communalCount(level, ref, item) == 0).findFirst().orElse(null);
			if (unstockedTool == null) throw new IllegalStateException("Stock policy fixture needs an unstocked targetable tool");
			String unstockedToolId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(unstockedTool).toString();
			check(passed, StockPolicy.data(level, ref).rows().contains(unstockedToolId + "|tools|0|-1"),
				"zero-stock tool appears as a selectable village target");
			setPolicy(ref, unstockedToolId, 12);
			check(passed, StockPolicy.data(level, ref).rows().contains(unstockedToolId + "|tools|0|12"),
				"a zero-stock tool can receive its first saved target");
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of());
			var codec = com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING, com.mojang.serialization.Codec.INT);
			Map<String, Integer> sample = Map.of("minecraft:bread", 17, "minecraft:iron_ingot", 0);
			var encoded = codec.encodeStart(JsonOps.INSTANCE, sample).getOrThrow();
			check(passed, codec.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(sample), "stock policy map persists by codec round trip");

			privateTouched = true;
			level.setBlock(privatePos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privatePos, Blocks.CHEST.defaultBlockState(), 3);
			var privateBe = level.getBlockEntity(privatePos);
			privateBe.setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			Container privateBox = HopperBlockEntity.getContainerAt(level, privatePos);
			if (privateBox == null) throw new IllegalStateException("Private stock fixture chest was not created");
			privatePlaced = true;
			privateBox.setItem(0, new ItemStack(Items.BREAD, 40));
			ref.data().chest = java.util.Optional.empty();
			check(passed, JobUtil.chest(new Ctx(level, villager, JobManager.state(villager), villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime())) == null,
				"village jobs never fall back to a nearby private chest");
			ref.data().chest = oldCenterChest;

			communal.clearContent();
			ref.data().chest = java.util.Optional.empty();
			try {
				communal.setItem(0, new ItemStack(Items.BREAD, 3));
			} finally {
				ref.data().chest = oldCenterChest;
			}
			check(passed, StockPolicy.communalCount(level, ref, Items.BREAD) == 3, "private chest contents are excluded from communal count");

			inventory.clearContent();
			inventory.setItem(0, new ItemStack(Items.OAK_LOG, 8));
			BlockPos communalPos = ref.data().chest.orElseThrow();
			villager.teleportTo(communalPos.getX() + 0.5, communalPos.getY(), communalPos.getZ() + 0.5);
			setPolicy(ref, "minecraft:oak_log", 0);
			Ctx ctx = new Ctx(level, villager, JobManager.state(villager), villager.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
			boolean didNotDeliver = JobUtil.depositSurplus(ctx, stack -> stack.is(Items.OAK_LOG), 1, 0);
			check(passed, !didNotDeliver && total(communal, Items.OAK_LOG) == 0 && total(inventory, Items.OAK_LOG) == 8,
				"zero target prevents delivery and retains carried goods");

			communal.clearContent();
			communal.setItem(0, new ItemStack(Items.OAK_LOG, 3));
			setPolicy(ref, "minecraft:oak_log", 5);
			BlockPos deliveryTarget = ref.data().chest.orElseThrow();
			int[][] deliveryOffsets = {{3,0},{2,2},{0,3},{-2,2},{-3,0},{-2,-2},{0,-3},{2,-2},
				{3,1},{1,3},{-1,3},{-3,1},{-3,-1},{-1,-3},{1,-3},{3,-1}};
			for (int[] offset : deliveryOffsets) {
				BlockPos support = deliveryTarget.offset(offset[0], -1, offset[1]);
				var supportState = level.getBlockState(support);
				if (level.getBlockEntity(support) != null || supportState.hasBlockEntity()) {
					throw new IllegalStateException("Stock policy delivery fixture overlaps a protected stand-footing block entity at " + support.toShortString());
				}
				if (supportState.isAir()) {
					oldStandSupport.putIfAbsent(support.immutable(), supportState);
					level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
				}
			}
			BlockPos deliveryStand = JobUtil.sharedStandSpot(ctx, deliveryTarget, 4.0);
			if (deliveryStand == null) throw new IllegalStateException("Stock policy delivery fixture has no safe shared stand");
			villager.teleportTo(deliveryStand.getX() + 0.5, deliveryStand.getY(), deliveryStand.getZ() + 0.5);
			boolean delivered = JobUtil.depositSurplus(ctx, stack -> stack.is(Items.OAK_LOG), 1, 0);
			check(passed, delivered && total(communal, Items.OAK_LOG) == 5 && total(inventory, Items.OAK_LOG) == 6,
				"finite target moves only the remaining communal capacity");
			check(passed, total(privateBox, Items.BREAD) == 40, "delivery never moves goods into or out of private stock");

			communal.clearContent(); communal.setItem(0, new ItemStack(Items.OAK_LOG, 3)); inventory.clearContent();
			JobUtil.insert(ctx, communal, new ItemStack(Items.OAK_LOG, 8));
			check(passed, total(communal, Items.OAK_LOG) == 5 && total(inventory, Items.OAK_LOG) == 6,
				"other communal insert paths also honor remaining target capacity and retain leftovers");

			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of(
				"minecraft:oak_log", StockPolicy.MAX_TARGET + 1,
				"bad%identifier", 4,
				"minecraft:air", 2));
			check(passed, StockPolicy.target(ref, Items.OAK_LOG) == StockPolicy.UNLIMITED, "out-of-range saved target is sanitized to unlimited");
			var rows = StockPolicy.data(level, ref).rows();
			check(passed, rows.stream().noneMatch(row -> row.startsWith("bad%identifier|") || row.startsWith("minecraft:air|")),
				"invalid identifiers and air are excluded from policy rows");

			// Dedicated stock filters: the village chest accepts materials, while milestone depots accept only their category.
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of());
			communal.clearContent();
			List<Item> vanillaWoodSigns = List.of(Items.OAK_SIGN, Items.SPRUCE_SIGN, Items.BIRCH_SIGN, Items.JUNGLE_SIGN,
				Items.ACACIA_SIGN, Items.DARK_OAK_SIGN, Items.MANGROVE_SIGN, Items.CHERRY_SIGN, Items.PALE_OAK_SIGN,
				Items.BAMBOO_SIGN, Items.CRIMSON_SIGN, Items.WARPED_SIGN);
			boolean everyVanillaWoodSignIsMaterial = vanillaWoodSigns.stream().allMatch(item -> {
				ItemStack sign = new ItemStack(item);
				return sign.is(ItemTags.SIGNS) && StockPolicy.category(item) == StockPolicy.Category.MATERIAL
					&& StockPolicy.acceptsMaterialStoreItem(item);
			});
			ItemStack realWoodSign = new ItemStack(Items.SPRUCE_SIGN);
			ItemStack signRemainder = HopperBlockEntity.addItem(null, communal, realWoodSign.copy(), null);
			boolean signCanUseActualMaterialStore = communal.canPlaceItem(0, realWoodSign)
				&& new net.minecraft.world.inventory.Slot(communal, 0, 0, 0).mayPlace(realWoodSign)
				&& signRemainder.isEmpty() && total(communal, Items.SPRUCE_SIGN) == 1;
			check(passed, everyVanillaWoodSignIsMaterial && signCanUseActualMaterialStore,
				"all vanilla wood signs in the signs item tag are accepted as real public material stock");
			communal.clearContent();
			ItemStack realBook = new ItemStack(Items.BOOK);
			ItemStack bookRemainder = HopperBlockEntity.addItem(null, communal, realBook.copy(), null);
			check(passed, StockPolicy.category(Items.BOOK) == StockPolicy.Category.MATERIAL
				&& StockPolicy.acceptsMaterialStoreItem(Items.BOOK)
				&& communal.canPlaceItem(0, realBook)
				&& new net.minecraft.world.inventory.Slot(communal, 0, 0, 0).mayPlace(realBook)
				&& bookRemainder.isEmpty() && total(communal, Items.BOOK) == 1,
				"real recipe books are accepted as public material stock for school bookshelf crafting");
			communal.clearContent();
			List<Item> vanillaWorkstationOutputs = List.of(Items.CRAFTING_TABLE, Items.FURNACE, Items.ANVIL,
				Items.BLAST_FURNACE, Items.BREWING_STAND);
			boolean workstationOutputsAreMaterials = vanillaWorkstationOutputs.stream().allMatch(item ->
				StockPolicy.category(item) == StockPolicy.Category.MATERIAL && StockPolicy.acceptsMaterialStoreItem(item));
			check(passed, workstationOutputsAreMaterials,
				"vanilla workstation results buffered and withdrawn by village jobs are public material stock");
			communal.setItem(0, new ItemStack(Items.COBBLESTONE, 8));
			int privateBreadBeforeStationRecipe = total(privateBox, Items.BREAD);
			boolean vanillaFurnaceRecipeLoaded = level.recipeAccess().getRecipes().stream()
				.anyMatch(holder -> holder.id().identifier().toString().equals("minecraft:furnace")
					&& holder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe);
			boolean committedFurnace = de.villagerplus.job.RecipeCrafting.craft(ctx, communal, null,
				stack -> stack.is(Items.FURNACE), 1);
			check(passed, vanillaFurnaceRecipeLoaded && committedFurnace
				&& total(communal, Items.COBBLESTONE) == 0 && total(communal, Items.FURNACE) == 1
				&& total(privateBox, Items.BREAD) == privateBreadBeforeStationRecipe
				&& total(privateBox, Items.FURNACE) == 0,
				"a loaded vanilla furnace recipe commits into public materials without changing a private chest");
			communal.clearContent();
			check(passed, StockPolicy.category(Items.SUGAR_CANE) == StockPolicy.Category.FOOD
				&& StockPolicy.category(Items.WHEAT_SEEDS) == StockPolicy.Category.MATERIAL
				&& StockPolicy.acceptsMaterialStoreItem(Items.WHEAT_SEEDS)
				&& Specials.depotType(new ItemStack(Items.WHEAT_SEEDS, 1)) == null
				&& StockPolicy.category(Items.WHEAT) == StockPolicy.Category.FOOD
				&& Specials.depotType(new ItemStack(Items.WHEAT, 1)) == de.villagerplus.build.Blueprints.GRANARY
				&& StockPolicy.category(Items.DEEPSLATE) == StockPolicy.Category.MATERIAL
				&& StockPolicy.category(Items.BRICKS) == StockPolicy.Category.MATERIAL
				&& StockPolicy.acceptsMaterialStoreItem(Items.BRICKS)
				&& StockPolicy.category(Items.IRON_INGOT) == StockPolicy.Category.MATERIAL
				&& StockPolicy.category(Items.FEATHER) == StockPolicy.Category.MATERIAL
				&& StockPolicy.category(Items.TRIPWIRE_HOOK) == StockPolicy.Category.MATERIAL
				&& StockPolicy.category(Items.CHEST) == StockPolicy.Category.MATERIAL
				&& StockPolicy.category(Items.RAW_IRON) == StockPolicy.Category.OTHER
				&& StockPolicy.category(Items.RAW_COPPER) == StockPolicy.Category.OTHER
				&& StockPolicy.category(Items.RAW_GOLD) == StockPolicy.Category.OTHER
				&& StockPolicy.category(Items.RAW_IRON_BLOCK) == StockPolicy.Category.OTHER
				&& StockPolicy.category(Items.EMERALD) == StockPolicy.Category.OTHER,
				"wheat seeds are Builder material while ordinary food remains Granary stock and currency remains other");
			ItemStack directFood = new ItemStack(Items.BREAD);
			ItemStack hopperFoodRemainder = HopperBlockEntity.addItem(null, communal, directFood.copy(), null);
			boolean playerSlotRejectsFood = !new net.minecraft.world.inventory.Slot(communal, 0, 0, 0).mayPlace(directFood);
			boolean containerPredicateAcceptsMaterial = communal.canPlaceItem(0, new ItemStack(Items.OAK_LOG));
			boolean playerSlotAcceptsMaterial = new net.minecraft.world.inventory.Slot(communal, 0, 0, 0)
				.mayPlace(new ItemStack(Items.OAK_LOG));
			int mergeSlotIndex = communal.getContainerSize() - 1;
			ref.data().chest = java.util.Optional.empty();
			try {
				communal.setItem(mergeSlotIndex, new ItemStack(Items.BREAD, 7));
			} finally {
				ref.data().chest = oldCenterChest;
			}
			var mergeSlot = new net.minecraft.world.inventory.Slot(communal, mergeSlotIndex, 0, 0);
			boolean shiftClickMergeRefused = mergeSlot.getMaxStackSize(directFood) == 7;
			communal.removeItemNoUpdate(mergeSlotIndex);
			boolean privateChestUntouched = privateBox.canPlaceItem(0, directFood)
				&& new net.minecraft.world.inventory.Slot(privateBox, 0, 0, 0).mayPlace(directFood)
				&& !StockPolicy.rejectsDirectMaterialStoreInsert(level, privatePos, directFood);
			check(passed, hopperFoodRemainder.getCount() == 1 && total(communal, Items.BREAD) == 0
				&& playerSlotRejectsFood && containerPredicateAcceptsMaterial && playerSlotAcceptsMaterial
				&& shiftClickMergeRefused && privateChestUntouched,
				"vanilla hopper, empty-slot and shift-click merge insertions refuse food at the registered public materials chest while a private chest remains unaffected");
			unregisteredChestTouched = true;
			level.setBlock(privateDepotPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privateDepotPos, Blocks.CHEST.defaultBlockState(), 3);
			Container unregisteredChest = HopperBlockEntity.getContainerAt(level, privateDepotPos);
			if (unregisteredChest == null) throw new IllegalStateException("Unregistered chest fixture was not created");
			boolean unrelatedPublicChestUntouched = !StockPolicy.rejectsDirectMaterialStoreInsert(level, privateDepotPos, directFood)
				&& unregisteredChest.canPlaceItem(0, directFood)
				&& new net.minecraft.world.inventory.Slot(unregisteredChest, 0, 0, 0).mayPlace(directFood);
			unregisteredChest.clearContent();
			level.setBlock(privateDepotPos, oldPrivateDepotState, 3);
			level.setBlock(privateDepotPos.below(), oldPrivateDepotFoot, 3);
			unregisteredChestTouched = false;
			check(passed, unrelatedPublicChestUntouched,
				"unregistered public chests keep their normal hopper and player-slot insertion behavior");
			Item farmersDelightCabinet = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getOptional(net.minecraft.resources.Identifier.fromNamespaceAndPath("farmersdelight", "oak_cabinet")).orElse(null);
			Item farmersDelightAppliance = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getOptional(net.minecraft.resources.Identifier.fromNamespaceAndPath("farmersdelight", "cooking_pot")).orElse(null);
			if (farmersDelightCabinet == null || farmersDelightAppliance == null) {
				throw new IllegalStateException("Stock policy fixture requires the installed Farmer's Delight cabinet and cooking-pot items");
			}
			String cabinetId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(farmersDelightCabinet).toString();
			boolean cabinetRecipeLoaded = level.recipeAccess().getRecipes().stream()
				.anyMatch(holder -> holder.id().identifier().toString().equals("farmersdelight:oak_cabinet"));
			int storedCabinets = StockPolicy.communalCount(level, ref, farmersDelightCabinet);
			check(passed, StockPolicy.category(farmersDelightCabinet) == StockPolicy.Category.MATERIAL
				&& StockPolicy.category(farmersDelightAppliance) == StockPolicy.Category.OTHER
				&& cabinetRecipeLoaded
				&& StockPolicy.data(level, ref).rows().contains(cabinetId + "|material|" + storedCabinets + "|-1"),
				"recipe-backed Farmer's Delight wood cabinets are selectable material stock while the cooking appliance remains excluded");
			ItemStack cabinetRemainder = StockPolicy.insertVillage(level, ref, new ItemStack(farmersDelightCabinet));
			check(passed, cabinetRemainder.isEmpty() && total(communal, farmersDelightCabinet) == 1,
				"normal village delivery stores an approved modded building block in the central materials depot");
			communal.clearContent();
			ItemStack retainedCurrency = StockPolicy.insertVillage(level, ref, new ItemStack(Items.EMERALD));
			check(passed, retainedCurrency.getCount() == 1 && total(communal, Items.EMERALD) == 0,
				"village routing never places emerald currency in the construction depot");
			ItemStack retainedOre = StockPolicy.insertVillage(level, ref, new ItemStack(Items.DEEPSLATE_IRON_ORE));
			check(passed, StockPolicy.category(Items.DEEPSLATE_IRON_ORE) == StockPolicy.Category.OTHER
				&& retainedOre.getCount() == 1 && total(communal, Items.DEEPSLATE_IRON_ORE) == 0,
				"unprocessed ore blocks remain with the carrier instead of entering the construction depot");
			int baselineRawIron = de.villagerplus.village.Balance.storage(level, ref).stream()
				.mapToInt(box -> total(box, Items.RAW_IRON)).sum();
			int baselineRawCopper = de.villagerplus.village.Balance.storage(level, ref).stream()
				.mapToInt(box -> total(box, Items.RAW_COPPER)).sum();
			ItemStack rawIronRemainder = StockPolicy.insertVillage(level, ref, new ItemStack(Items.RAW_IRON, 2));
			ItemStack rawCopperRemainder = StockPolicy.insertVillage(level, ref, new ItemStack(Items.RAW_COPPER));
			int publicRawIron = de.villagerplus.village.Balance.storage(level, ref).stream()
				.mapToInt(box -> total(box, Items.RAW_IRON)).sum();
			int publicRawCopper = de.villagerplus.village.Balance.storage(level, ref).stream()
				.mapToInt(box -> total(box, Items.RAW_COPPER)).sum();
			var ironInput = new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(Items.RAW_IRON));
			var copperInput = new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(Items.RAW_COPPER));
			boolean loadedSmithRecipe = level.recipeAccess().getRecipes().stream().anyMatch(holder ->
				holder.value() instanceof net.minecraft.world.item.crafting.AbstractCookingRecipe recipe
					&& recipe.getType() == net.minecraft.world.item.crafting.RecipeType.SMELTING
					&& recipe.matches(ironInput, level)
					&& recipe.assemble(ironInput).is(Items.IRON_INGOT));
			boolean loadedCopperRecipe = level.recipeAccess().getRecipes().stream().anyMatch(holder ->
				holder.value() instanceof net.minecraft.world.item.crafting.AbstractCookingRecipe recipe
					&& recipe.getType() == net.minecraft.world.item.crafting.RecipeType.SMELTING
					&& recipe.matches(copperInput, level)
					&& recipe.assemble(copperInput).is(Items.COPPER_INGOT));
			String rawIronId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.RAW_IRON).toString();
			check(passed, rawIronRemainder.isEmpty() && rawCopperRemainder.isEmpty()
				&& StockPolicy.category(Items.RAW_IRON) == StockPolicy.Category.OTHER
				&& StockPolicy.category(Items.RAW_COPPER) == StockPolicy.Category.OTHER
				&& publicRawIron == baselineRawIron + 2 && publicRawCopper == baselineRawCopper + 1
				&& loadedSmithRecipe && loadedCopperRecipe
				&& StockPolicy.data(level, ref).rows().contains(rawIronId + "|other|" + publicRawIron + "|-1"),
				"raw smelting inputs are hidden from building stock yet remain public recipe inputs for SmithSmelting");
			communal.clearContent();
			depotBlocksTouched = true;
			level.setBlock(granaryPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(toolshedPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privateDepotPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(granaryPos, Blocks.BARREL.defaultBlockState(), 3);
			level.setBlock(toolshedPos, Blocks.BARREL.defaultBlockState(), 3);
			level.setBlock(privateDepotPos, Blocks.CHEST.defaultBlockState(), 3);
			var privateDepotBe = level.getBlockEntity(privateDepotPos);
			if (privateDepotBe == null) throw new IllegalStateException("Private depot fixture chest is missing");
			privateDepotBe.setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			Container privateDepot = HopperBlockEntity.getContainerAt(level, privateDepotPos);
			if (privateDepot == null) throw new IllegalStateException("Private depot fixture container is missing");
			privateDepot.setItem(0, new ItemStack(Items.BREAD, 40));
			Specials.set(ref, de.villagerplus.build.Blueprints.GRANARY, granaryPos);
			Specials.set(ref, de.villagerplus.build.Blueprints.TOOLSHED, toolshedPos);
			Container granary = HopperBlockEntity.getContainerAt(level, granaryPos), toolshed = HopperBlockEntity.getContainerAt(level, toolshedPos);
			if (granary == null || toolshed == null) throw new IllegalStateException("Depot filter fixture barrels are missing");
			ItemStack food = new ItemStack(Items.BREAD, 4), foodRejected = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, communal, food);
			check(passed, foodRejected.getCount() == 4 && total(communal, Items.BREAD) == 0,
				"central materials depot rejects food without consuming the offered stack");
			ItemStack logs = new ItemStack(Items.OAK_LOG, 4), logsRejected = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, communal, logs);
			check(passed, logsRejected.isEmpty() && total(communal, Items.OAK_LOG) == 4,
				"central depot accepts construction materials");
			ItemStack foodToGranary = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.GRANARY, granary, new ItemStack(Items.BREAD, 4));
			ItemStack logsToGranary = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.GRANARY, granary, new ItemStack(Items.OAK_LOG, 4));
			check(passed, foodToGranary.isEmpty() && total(granary, Items.BREAD) == 4 && logsToGranary.getCount() == 4,
				"granary accepts food and refuses construction materials");
			check(passed, !Specials.containers(level, ref, de.villagerplus.build.Blueprints.GRANARY).contains(privateDepot)
				&& StockPolicy.communalCount(level, ref, Items.BREAD) == 4,
				"personal chests near a milestone building stay private and do not inflate communal stock");
			ItemStack toolToShed = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.TOOLSHED, toolshed, new ItemStack(Items.IRON_PICKAXE, 1));
			ItemStack foodToShed = StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.TOOLSHED, toolshed, new ItemStack(Items.BREAD, 1));
			check(passed, toolToShed.isEmpty() && total(toolshed, Items.IRON_PICKAXE) == 1 && foodToShed.getCount() == 1,
				"toolshed accepts durable equipment and refuses food");
			ItemStack routedFood = StockPolicy.insertVillage(level, ref, new ItemStack(Items.BREAD, 2));
			ItemStack routedTool = StockPolicy.insertVillage(level, ref, new ItemStack(Items.IRON_PICKAXE, 1));
			check(passed, routedFood.isEmpty() && routedTool.isEmpty() && total(granary, Items.BREAD) == 6
				&& total(toolshed, Items.IRON_PICKAXE) == 2 && total(communal, Items.BREAD) == 0,
				"village routing sends food and equipment only to their registered specialist depots");
			ItemStack routedFieldSeeds = StockPolicy.insertVillage(level, ref, new ItemStack(Items.WHEAT_SEEDS, 24));
			Container builderStock = de.villagerplus.village.Lager.combined(level, ref);
			check(passed, routedFieldSeeds.isEmpty() && builderStock != null
				&& JobUtil.count(builderStock, stack -> stack.is(Items.WHEAT_SEEDS)) == 24
				&& total(granary, Items.WHEAT_SEEDS) == 0,
				"all 24 paid starter-field seeds route into storage the Builder can reserve from");
			setPolicy(ref, "minecraft:oak_log", 4);
			ItemStack unknownDepotOffer = new ItemStack(Items.OAK_LOG, 5);
			ItemStack unknownDepotRest = StockPolicy.insert(level, ref, privateBox, unknownDepotOffer);
			check(passed, StockPolicy.deliveryRoom(level, ref, Items.OAK_LOG) == 0
				&& unknownDepotRest.getCount() == 5 && total(privateBox, Items.BREAD) == 40
				&& total(privateBox, Items.OAK_LOG) == 0 && total(communal, Items.OAK_LOG) == 4,
				"unknown private container receives no overflow when communal quota is full and all five logs remain with the caller");

			// A saved warehouse slot may be replaced by a villager's private owned chest. It must not
			// count toward the public target or accept a delivery when the actual village store is full.
			ref.data().extra.lager = java.util.Optional.empty();
			ref.data().extra.lagerMore.clear();
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(privatePos);
			privateBox.clearContent();
			privateBox.setItem(0, new ItemStack(Items.OAK_LOG, 63));
			communal.clearContent();
			for (int slot = 0; slot < communal.getContainerSize(); slot++) communal.setItem(slot, new ItemStack(Items.STONE, 64));
			setPolicy(ref, "minecraft:oak_log", 64);
			check(passed, StockPolicy.communalCount(level, ref, Items.OAK_LOG) == 0,
				"a privately owned chest at a saved lager position is excluded from public stock counts");
			check(passed, StockPolicy.deliveryRoom(level, ref, Items.OAK_LOG) == 64,
				"private lager-position stock cannot consume the public delivery allowance");
			ItemStack privateLagerRemainder = StockPolicy.insertVillage(level, ref, new ItemStack(Items.OAK_LOG, 1));
			check(passed, privateLagerRemainder.getCount() == 1 && total(privateBox, Items.OAK_LOG) == 63,
				"a privately owned chest at a saved lager position never receives public village deliveries");
			Specials.remove(ref, de.villagerplus.build.Blueprints.GRANARY);
			ItemStack retainedFood = StockPolicy.insertVillage(level, ref, new ItemStack(Items.BREAD, 3));
			check(passed, retainedFood.getCount() == 3 && total(communal, Items.BREAD) == 0,
				"food remains with its caller when the granary is not registered");
			privatePairBlocksTouched = true;
			level.setBlock(privateDepotPos, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(privateDepotPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(privatePairMatePos.below(), Blocks.STONE.defaultBlockState(), 3);
			var northFacing = Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.NORTH);
			level.setBlock(privateDepotPos, northFacing.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
				net.minecraft.world.level.block.state.properties.ChestType.LEFT), 3);
			level.setBlock(privatePairMatePos, northFacing.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
				net.minecraft.world.level.block.state.properties.ChestType.RIGHT), 3);
			var pairedPrivateBe = level.getBlockEntity(privatePairMatePos);
			Container depotHalf = HopperBlockEntity.getContainerAt(level, privateDepotPos);
			Container mateHalf = HopperBlockEntity.getContainerAt(level, privatePairMatePos);
			if (pairedPrivateBe == null
				|| level.getBlockState(privateDepotPos).getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
					!= net.minecraft.world.level.block.state.properties.ChestType.LEFT
				|| level.getBlockState(privatePairMatePos).getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
					!= net.minecraft.world.level.block.state.properties.ChestType.RIGHT
				|| depotHalf == null || mateHalf == null || depotHalf.getContainerSize() != 54 || mateHalf.getContainerSize() != 54) {
				throw new IllegalStateException("Stock policy paired-depot fixture did not form a double chest");
			}
			var beforePairChest = ref.data().chest;
			var beforePairLager = ref.data().extra.lager;
			var beforePairLagerMore = new ArrayList<>(ref.data().extra.lagerMore);
			var beforePairLagerChests = new ArrayList<>(ref.data().extra.lagerChests);
			ref.data().chest = java.util.Optional.of(privatePairMatePos); // Deliberately register RIGHT.
			ref.data().extra.lager = java.util.Optional.empty();
			ref.data().extra.lagerMore.clear();
			ref.data().extra.lagerChests.clear();
			ItemStack rightHalfRemainder = HopperBlockEntity.addItem(null, mateHalf, directFood.copy(), null);
			boolean rightRegisteredHalfFiltered = StockPolicy.rejectsDirectMaterialStoreInsert(level, privateDepotPos, directFood)
				&& StockPolicy.rejectsDirectMaterialStoreInsert(level, privatePairMatePos, directFood)
				&& !mateHalf.canPlaceItem(0, directFood)
				&& !new net.minecraft.world.inventory.Slot(mateHalf, 0, 0, 0).mayPlace(directFood)
				&& rightHalfRemainder.getCount() == directFood.getCount();
			ref.data().chest = beforePairChest;
			ref.data().extra.lager = beforePairLager;
			ref.data().extra.lagerMore.clear(); ref.data().extra.lagerMore.addAll(beforePairLagerMore);
			ref.data().extra.lagerChests.clear(); ref.data().extra.lagerChests.addAll(beforePairLagerChests);
			check(passed, rightRegisteredHalfFiltered,
				"a double material chest registered by its right half rejects hopper and player-slot food on both halves");
			pairedPrivateBe.setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			ref.data().extra.lager = java.util.Optional.empty();
			ref.data().extra.lagerMore.clear();
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(privateDepotPos);
			check(passed, !de.villagerplus.village.Lager.materialChests(level, ref).contains(privateDepotPos),
				"a public saved chest half is excluded when its double-chest partner is privately owned");
			return passed[0];
		} finally {
			ref.data().chest = oldCenterChest;
			ref.data().extra.lager = oldLager;
			ref.data().extra.lagerMore.clear(); ref.data().extra.lagerMore.addAll(oldLagerMore);
			ref.data().extra.lagerChests.clear(); ref.data().extra.lagerChests.addAll(oldLagerChests);
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, oldPolicies);
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.dirty();
			restore(communal, oldCommunal);
			restore(inventory, oldInventory);
			if (privatePlaced) {
				Container privateBox = HopperBlockEntity.getContainerAt(level, privatePos);
				if (privateBox != null) privateBox.clearContent();
			}
			if (privateTouched) {
				level.setBlock(privatePos, oldPrivateState, 3);
				level.setBlock(privatePos.below(), oldFootingState, 3);
			}
			if (unregisteredChestTouched) {
				Container unregisteredChest = HopperBlockEntity.getContainerAt(level, privateDepotPos);
				if (unregisteredChest != null) unregisteredChest.clearContent();
				level.setBlock(privateDepotPos, oldPrivateDepotState, 3);
				level.setBlock(privateDepotPos.below(), oldPrivateDepotFoot, 3);
			}
			if (depotBlocksTouched) {
				for (BlockPos pos : List.of(granaryPos, toolshedPos)) {
					Container depot = HopperBlockEntity.getContainerAt(level, pos);
					if (depot != null) depot.clearContent();
				}
				Container fixtureDepot = HopperBlockEntity.getContainerAt(level, privateDepotPos);
				if (fixtureDepot != null) fixtureDepot.clearContent();
				level.setBlock(granaryPos, oldGranaryState, 3); level.setBlock(granaryPos.below(), oldGranaryFoot, 3);
				level.setBlock(toolshedPos, oldToolshedState, 3); level.setBlock(toolshedPos.below(), oldToolshedFoot, 3);
				level.setBlock(privateDepotPos, oldPrivateDepotState, 3); level.setBlock(privateDepotPos.below(), oldPrivateDepotFoot, 3);
				if (privatePairBlocksTouched) {
					level.setBlock(privatePairMatePos, oldPrivatePairMateState, 3);
					level.setBlock(privatePairMatePos.below(), oldPrivatePairMateFoot, 3);
				}
			}
			oldStandSupport.forEach((pos, state) -> level.setBlock(pos, state, 3));
			villager.teleportTo(oldPosition.x, oldPosition.y, oldPosition.z);
		}
	}

	private static void setPolicy(VillageRef ref, String id, int target) {
		ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.of(id, target));
		ref.dirty();
	}

	private static void check(int[] passed, boolean ok, String label) {
		if (!ok) throw new IllegalStateException("STOCK POLICY CHECK FAILED: " + label);
		passed[0]++;
		System.out.println("STOCK POLICY PASS " + label);
	}

	private static int total(Container box, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(item)) count += box.getItem(i).getCount();
		return count;
	}

	private static List<ItemStack> snapshot(Container box) {
		List<ItemStack> copy = new ArrayList<>();
		for (int i = 0; i < box.getContainerSize(); i++) copy.add(box.getItem(i).copy());
		return copy;
	}

	private static void restore(Container box, List<ItemStack> contents) {
		box.clearContent();
		for (int i = 0; i < Math.min(box.getContainerSize(), contents.size()); i++) box.setItem(i, contents.get(i).copy());
		box.setChanged();
	}
}
