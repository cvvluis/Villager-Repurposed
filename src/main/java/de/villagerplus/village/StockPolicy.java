package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.net.RequestStockPolicy;
import de.villagerplus.net.SetStockPolicy;
import de.villagerplus.net.StockPolicyData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.tags.ItemTags;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Persistent per-item targets for items delivered into a village's communal storage. */
public final class StockPolicy {
	public static final int MAX_TARGET = 4096;
	public static final int MAX_POLICIES = 256;
	public static final int MAX_ROWS = 256;
	public static final int UNLIMITED = -1;
	private static final int MAX_ID_LENGTH = 255;
	private static final int EQUIPMENT_REPLACEMENT_DAMAGE_LIMIT_PERCENT = 75;
	private static final List<String> RAW_SMELTING_SUPPLIES = List.of("raw_iron", "raw_copper", "raw_gold");
	private static final List<String> FARMERS_DELIGHT_WOOD_CABINETS = List.of(
		"acacia_cabinet", "bamboo_cabinet", "birch_cabinet", "cherry_cabinet", "crimson_cabinet",
		"dark_oak_cabinet", "jungle_cabinet", "mangrove_cabinet", "oak_cabinet", "pale_oak_cabinet",
		"spruce_cabinet", "warped_cabinet");
	private static final List<String> OPTIONAL_MATERIAL_TARGET_IDS = FARMERS_DELIGHT_WOOD_CABINETS.stream()
		.map(path -> "farmersdelight:" + path).toList();
	private static final List<Item> TARGETABLE_ITEMS = List.of(
		Items.BREAD, Items.CARROT, Items.POTATO, Items.WHEAT, Items.WHEAT_SEEDS, Items.BEETROOT,
		Items.APPLE, Items.BAKED_POTATO, Items.HAY_BLOCK, Items.EGG,
		Items.OAK_LOG, Items.SPRUCE_LOG, Items.OAK_PLANKS, Items.COBBLESTONE, Items.STONE, Items.GLASS,
		Items.BRICKS, Items.IRON_INGOT, Items.COPPER_INGOT, Items.COAL, Items.STICK, Items.RAIL,
		Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL,
		Items.IRON_HOE, Items.SHEARS, Items.SHIELD, Items.BOW, Items.CROSSBOW,
		Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE);
	private StockPolicy() { }

	public enum Category {
		FOOD("food"), MATERIAL("material"), TOOLS("tools"), OTHER("other");
		private final String key;
		Category(String key) { this.key = key; }
		public String key() { return key; }
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(RequestStockPolicy.TYPE, RequestStockPolicy.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetStockPolicy.TYPE, SetStockPolicy.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StockPolicyData.TYPE, StockPolicyData.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RequestStockPolicy.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!(player.level() instanceof ServerLevel level) || !validBoard(player, level, payload.board())) return;
			VillageRef ref = Villages.villageNear(level, payload.board(), 96);
			if (ref != null) ServerPlayNetworking.send(player, data(level, ref));
		});
		ServerPlayNetworking.registerGlobalReceiver(SetStockPolicy.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!(player.level() instanceof ServerLevel level) || !validBoard(player, level, payload.board())) return;
			VillageRef ref = Villages.villageNear(level, payload.board(), 96);
			if (ref == null || payload.itemId().isEmpty() || payload.itemId().length() > MAX_ID_LENGTH
				|| payload.category().length() > 16 || payload.target() < 0 || payload.target() > MAX_TARGET) return;
			Identifier id = Identifier.tryParse(payload.itemId());
			if (id == null || !id.toString().equals(payload.itemId())) return;
			Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null || item == Items.AIR || !category(item).key().equals(payload.category())) return;
			Map<String, Integer> policies = policies(ref);
			if (payload.clear()) {
				policies.remove(id.toString());
			} else {
				if (!policies.containsKey(id.toString()) && policies.size() >= MAX_POLICIES) return;
				policies.put(id.toString(), payload.target());
			}
			ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES, Map.copyOf(policies));
			ref.dirty();
			ServerPlayNetworking.send(player, data(level, ref));
		});
	}

	private static boolean validBoard(ServerPlayer player, ServerLevel level, net.minecraft.core.BlockPos board) {
		return !player.isSpectator() && player.blockPosition().distSqr(board) <= 24L * 24L
			&& level.hasChunkAt(board) && level.getBlockState(board).getBlock() instanceof de.villagerplus.block.DorftafelBlock;
	}

	public static Category category(Item item) {
		ItemStack sample = new ItemStack(item);
		// These are reusable worksite parts or paid road-building stock and belong in shared material storage.
		// Finished brick blocks are paid construction stock even though the builder
		// does not use them as its generic local palette fallback.
		// Wheat seeds are a paid input for the starter farm, so keep them in the Builder-accessible public stock.
		if (item == Items.WHEAT_SEEDS || item == Items.BRICKS || item == Items.SCAFFOLDING || item == Items.CRAFTING_TABLE || item == Items.LANTERN
			|| vanillaWorkstationOutput(item)) {
			return Category.MATERIAL;
		}
		if (sample.isDamageableItem() || sample.is(ConventionalItemTags.TOOLS) || sample.is(ConventionalItemTags.ARMORS)
			|| sample.is(ConventionalItemTags.MELEE_WEAPON_TOOLS) || sample.is(ConventionalItemTags.RANGED_WEAPON_TOOLS)) return Category.TOOLS;
		if (de.villagerplus.job.Meals.isFood(sample) || conventionalFood(sample) || foodIngredient(item)) return Category.FOOD;
		if (isRawSmeltingSupply(item)) return Category.OTHER;
		if (sample.is(ItemTags.SIGNS)) return Category.MATERIAL;
		if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
			if (isOreBlock(item)) return Category.OTHER;
			if (item == Items.CHEST || de.villagerplus.build.Supply.obtainable(blockItem.getBlock()) || supportedModdedBuildingBlock(item)) return Category.MATERIAL;
		}
		if (conventionalMaterial(sample)) return Category.MATERIAL;
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		return switch (path) {
			// Non-block ingredients used by village construction and vanilla equipment recipes.
			case "stick", "flint", "brick", "nether_brick", "book", "iron_ingot", "iron_nugget",
				"copper_ingot", "copper_nugget", "gold_ingot", "gold_nugget", "coal", "charcoal",
				"redstone", "quartz", "string", "leather", "feather", "tripwire_hook", "rail", "powered_rail", "activator_rail", "detector_rail",
				"minecart", "chest_minecart", "hopper_minecart", "furnace_minecart", "redstone_block" -> Category.MATERIAL;
			default -> Category.OTHER;
		};
	}

	/** Exact vanilla station results that village jobs craft and then withdraw from the public materials depot. */
	private static boolean vanillaWorkstationOutput(Item item) {
		return item == Items.FURNACE || item == Items.ANVIL || item == Items.BLAST_FURNACE || item == Items.BREWING_STAND;
	}

	/** Raw metal drops stay public for recipe-driven SmithSmelting, but are not building stock. */
	private static boolean isRawSmeltingSupply(Item item) {
		if (item instanceof net.minecraft.world.item.BlockItem) return false;
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		return id.getNamespace().equals("minecraft") && RAW_SMELTING_SUPPLIES.contains(id.getPath());
	}

	/** Ore blocks and raw-metal storage blocks are not buildable depot stock. */
	private static boolean isOreBlock(Item item) {
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		String path = id.getPath();
		return path.endsWith("_ore") || path.startsWith("raw_") && path.endsWith("_block");
	}

	/** Exact, recipe-backed mod furniture used as a durable wood building component in the installed pack. */
	private static boolean supportedModdedBuildingBlock(Item item) {
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		return id.getNamespace().equals("farmersdelight") && FARMERS_DELIGHT_WOOD_CABINETS.contains(id.getPath());
	}

	private static boolean conventionalFood(ItemStack stack) {
		return stack.is(ConventionalItemTags.FOODS) || stack.is(ConventionalItemTags.ANIMAL_FOODS)
			|| stack.is(ConventionalItemTags.FRUIT_FOODS) || stack.is(ConventionalItemTags.VEGETABLE_FOODS)
			|| stack.is(ConventionalItemTags.BERRY_FOODS) || stack.is(ConventionalItemTags.BREAD_FOODS)
			|| stack.is(ConventionalItemTags.COOKIE_FOODS) || stack.is(ConventionalItemTags.RAW_MEAT_FOODS)
			|| stack.is(ConventionalItemTags.COOKED_MEAT_FOODS) || stack.is(ConventionalItemTags.RAW_FISH_FOODS)
			|| stack.is(ConventionalItemTags.COOKED_FISH_FOODS) || stack.is(ConventionalItemTags.SOUP_FOODS)
			|| stack.is(ConventionalItemTags.CANDY_FOODS) || stack.is(ConventionalItemTags.PIE_FOODS)
			|| stack.is(ConventionalItemTags.CROPS) || stack.is(ConventionalItemTags.SEEDS)
			|| stack.is(ConventionalItemTags.EGGS) || stack.is(ConventionalItemTags.MUSHROOMS)
			|| stack.is(ConventionalItemTags.MILK_BUCKETS);
	}

	private static boolean conventionalMaterial(ItemStack stack) {
		return stack.is(ConventionalItemTags.STONES) || stack.is(ConventionalItemTags.COBBLESTONES)
			|| stack.is(ConventionalItemTags.GRAVELS) || stack.is(ConventionalItemTags.BRICKS)
			|| stack.is(ConventionalItemTags.INGOTS) || stack.is(ConventionalItemTags.NUGGETS)
			|| stack.is(ConventionalItemTags.RAW_MATERIALS) || stack.is(ConventionalItemTags.STRINGS)
			|| stack.is(ConventionalItemTags.LEATHERS)
			|| BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("coal")
			|| BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("charcoal");
	}

	private static boolean foodIngredient(Item item) {
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		return switch (path) {
			case "wheat", "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "cocoa_beans", "sugar",
				"egg", "milk_bucket", "hay_block", "carrot", "potato", "beetroot", "rice", "rice_panicle", "onion",
				"cabbage", "tomato", "lettuce", "radish", "corn", "flour", "cheese", "ink_sac", "bone" -> true;
			default -> path.endsWith("_seeds") || path.endsWith("_seed") || path.endsWith("_crop") || path.endsWith("_fruit")
				|| path.endsWith("_vegetable") || path.endsWith("_berry") || path.endsWith("_berries");
		};
	}

	public static int target(VillageRef ref, Item item) {
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		Integer target = ref.bellEntity().getAttachedOrElse(VillagerPlus.STOCK_POLICIES, Map.of()).get(id);
		return target != null && target >= 0 && target <= MAX_TARGET ? target : UNLIMITED;
	}

	/** Maximum count of this item the villager can currently deliver to communal storage. */
	public static int deliveryRoom(ServerLevel level, VillageRef ref, Item item) {
		int target = target(ref, item);
		if (target == UNLIMITED) return Integer.MAX_VALUE;
		return Math.max(0, target - serviceableCommunalCount(level, ref, item));
	}

	/** Damaged equipment beyond the replacement threshold does not satisfy a village's usable-stock target. */
	public static boolean isServiceableEquipment(ItemStack stack) {
		if (stack == null || stack.isEmpty() || category(stack.getItem()) != Category.TOOLS) return false;
		return !stack.isDamageableItem()
			|| (long)stack.getDamageValue() * 100L < (long)stack.getMaxDamage() * EQUIPMENT_REPLACEMENT_DAMAGE_LIMIT_PERCENT;
	}

	private static int serviceableCommunalCount(ServerLevel level, VillageRef ref, Item item) {
		long count = 0;
		for (Container box : Balance.storage(level, ref)) {
			if (box == null) continue;
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (stack.isEmpty() || !stack.is(item)) continue;
				if (category(item) == Category.TOOLS && stack.isDamageableItem() && !isServiceableEquipment(stack)) continue;
				count += stack.getCount();
			}
		}
		return (int)Math.min(Integer.MAX_VALUE, count);
	}

	/** Inserts into a recognized communal store while respecting its remaining per-item allowance. */
	public static ItemStack insert(ServerLevel level, VillageRef ref, Container destination, ItemStack offered) {
		String depot = depotType(level, ref, destination);
		return insertInto(level, ref, depot, destination, offered);
	}

	/** Inserts into a known village depot without relying on container wrapper identity (double chests create fresh wrappers). */
	public static ItemStack insertInto(ServerLevel level, VillageRef ref, String depot, Container destination, ItemStack offered) {
		if (offered.isEmpty()) return offered;
		// Unknown containers are not village depots. Fail closed so a private or foreign target cannot
		// accept goods through the generic hopper path without being counted against the village quota.
		if (depot == null) return offered.copy();
		if (!accepts(depot, offered.getItem())) return offered.copy();
		int allowed = Math.min(offered.getCount(), deliveryRoom(level, ref, offered.getItem()));
		if (allowed <= 0) return offered.copy();
		ItemStack moving = offered.copyWithCount(allowed);
		ItemStack insertRemainder = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, destination, moving, null);
		int moved = allowed - insertRemainder.getCount();
		return offered.copyWithCount(offered.getCount() - moved);
	}

	/** Routes a village delivery to its category depot and returns every item that could not be stored. */
	public static ItemStack insertVillage(ServerLevel level, VillageRef ref, ItemStack offered) {
		if (offered.isEmpty()) return offered;
		if (category(offered.getItem()) == Category.OTHER && !isRawSmeltingSupply(offered.getItem())) return offered.copy();
		String special = de.villagerplus.village.Specials.depotType(offered);
		if (special != null) {
			ItemStack rest = offered.copy();
			for (Container depot : Specials.containers(level, ref, special)) {
				if (rest.isEmpty()) break;
				rest = insertInto(level, ref, special, depot, rest);
			}
			return rest;
		}
		ItemStack rest = offered.copy();
		Container home = ref.chest(level);
		if (home != null) rest = insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, home, rest);
		for (Container lager : Lager.containers(level, ref.data())) {
			if (rest.isEmpty()) break;
			rest = insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, lager, rest);
		}
		return rest;
	}

	private static String depotType(ServerLevel level, VillageRef ref, Container destination) {
		for (String type : new String[] {de.villagerplus.build.Blueprints.GRANARY, de.villagerplus.build.Blueprints.TOOLSHED,
			de.villagerplus.build.Blueprints.STATION}) {
			if (Specials.containers(level, ref, type).contains(destination)) return type;
		}
		if (ref.chest(level) == destination || Lager.containers(level, ref.data()).contains(destination)) return de.villagerplus.build.Blueprints.STORAGE;
		return null;
	}

	private static boolean accepts(String depot, Item item) {
		Category category = category(item);
		return switch (depot) {
			case de.villagerplus.build.Blueprints.GRANARY -> category == Category.FOOD;
			case de.villagerplus.build.Blueprints.TOOLSHED -> category == Category.TOOLS;
			case de.villagerplus.build.Blueprints.STORAGE, de.villagerplus.build.Blueprints.STATION ->
				category == Category.MATERIAL || isRawSmeltingSupply(item);
			default -> true;
		};
	}

	/** Whether the public material store's existing delivery filter permits this item type. */
	public static boolean acceptsMaterialStoreItem(Item item) {
		return item != null && accepts(de.villagerplus.build.Blueprints.STORAGE, item);
	}

	/**
	 * Returns whether a direct vanilla inventory insertion must be refused at this position.
	 * Only registered public material chests are filtered; private and unrelated containers keep
	 * their own insertion behavior. Recipe-backed raw smelting inputs remain accepted like they are
	 * through the village delivery router.
	 */
	public static boolean rejectsDirectMaterialStoreInsert(ServerLevel level, BlockPos pos, ItemStack offered) {
		if (level == null || pos == null || offered == null || offered.isEmpty()
			|| acceptsMaterialStoreItem(offered.getItem()) || !level.hasChunkAt(pos)) return false;

		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock) || level.getBlockEntity(pos) == null) return false;
		BlockPos connectedHalf = null;
		boolean connectedHalfLoaded = false;
		if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			connectedHalf = pos.relative(ChestBlock.getConnectedDirection(state)).immutable();
			if (level.hasChunkAt(connectedHalf)) {
				BlockState otherState = level.getBlockState(connectedHalf);
				ChestType type = state.getValue(ChestBlock.TYPE);
				if (!(otherState.getBlock() instanceof ChestBlock)
					|| otherState.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
					|| otherState.getValue(ChestBlock.TYPE) == ChestType.SINGLE
					|| otherState.getValue(ChestBlock.TYPE) == type
					|| level.getBlockEntity(connectedHalf) == null) return false;
				connectedHalfLoaded = true;
			}
		}

		// A split-ownership pair is private if either real half that is currently loaded has an owner.
		if (Ownership.ownerAt(level, pos) != null
			|| connectedHalfLoaded && Ownership.ownerAt(level, connectedHalf) != null) return false;

		VillageRef ref = Villages.villageNear(level, pos, 256);
		if (ref == null) return false;
		List<BlockPos> registered = new ArrayList<>(ref.data().extra.lagerChests.size() + 1);
		ref.data().chest.ifPresent(registered::add);
		registered.addAll(ref.data().extra.lagerChests);
		return matchesRegisteredMaterialStorePosition(pos, state, registered);
	}

	/** Matches a loaded chest half against persistent registrations without inspecting or loading its partner chunk. */
	public static boolean matchesRegisteredMaterialStorePosition(BlockPos pos, BlockState state, List<BlockPos> registered) {
		if (pos == null || state == null || registered == null || !(state.getBlock() instanceof ChestBlock)) return false;

		BlockPos canonical = pos;
		BlockPos connected = null;
		ChestType type = state.getValue(ChestBlock.TYPE);
		if (type != ChestType.SINGLE) {
			connected = pos.relative(ChestBlock.getConnectedDirection(state)).immutable();
			if (type == ChestType.RIGHT) canonical = connected;
		}
		for (BlockPos registeredPos : registered) {
			if (registeredPos != null && (registeredPos.equals(canonical) || registeredPos.equals(pos)
				|| connected != null && registeredPos.equals(connected))) return true;
		}
		return false;
	}

	/** Counts only village-owned shared stores; private shop chests and villager inventory are excluded. */
	public static int communalCount(ServerLevel level, VillageRef ref, Item item) {
		long count = 0;
		for (Container box : Balance.storage(level, ref)) {
			if (box == null) continue;
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
			}
		}
		return (int)Math.min(Integer.MAX_VALUE, count);
	}

	/** Computes delivery room for a worker's distinct carried item types in one communal-storage scan. */
	public static Map<Item, Integer> deliveryRooms(ServerLevel level, VillageRef ref, java.util.Collection<Item> items) {
		Map<String, Integer> saved = sanitized(ref);
		Map<Item, Integer> targets = new java.util.IdentityHashMap<>();
		Map<Item, Integer> counts = new java.util.IdentityHashMap<>();
		for (Item item : items) {
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			int target = saved.getOrDefault(id, UNLIMITED);
			if (target != UNLIMITED) {
				targets.put(item, target);
				counts.put(item, 0);
			}
		}
		if (!targets.isEmpty()) for (Container box : Balance.storage(level, ref)) {
			if (box == null) continue;
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (stack.isEmpty() || !targets.containsKey(stack.getItem())) continue;
				Item item = stack.getItem();
				if (category(item) == Category.TOOLS && stack.isDamageableItem() && !isServiceableEquipment(stack)) continue;
				counts.merge(item, stack.getCount(), (a, b) -> (int)Math.min(Integer.MAX_VALUE, (long)a + b));
			}
		}
		Map<Item, Integer> rooms = new java.util.IdentityHashMap<>();
		for (Item item : items) {
			Integer target = targets.get(item);
			rooms.put(item, target == null ? Integer.MAX_VALUE : Math.max(0, target - counts.getOrDefault(item, 0)));
		}
		return rooms;
	}

	private static Map<String, Integer> policies(VillageRef ref) {
		return new LinkedHashMap<>(sanitized(ref));
	}

	private static Map<String, Integer> sanitized(VillageRef ref) {
		Map<String, Integer> saved = ref.bellEntity().getAttachedOrElse(VillagerPlus.STOCK_POLICIES, Map.of());
		Map<String, Integer> valid = new LinkedHashMap<>();
		for (var entry : saved.entrySet()) {
			String raw = entry.getKey();
			if (raw == null || raw.isEmpty() || raw.length() > MAX_ID_LENGTH || entry.getValue() == null
				|| entry.getValue() < 0 || entry.getValue() > MAX_TARGET) continue;
			Identifier id = Identifier.tryParse(raw);
			if (id == null || !id.toString().equals(raw) || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) continue;
			valid.put(raw, entry.getValue());
			if (valid.size() >= MAX_POLICIES) break;
		}
		return valid;
	}

	public static StockPolicyData data(ServerLevel level, VillageRef ref) {
		Map<String, Integer> saved = sanitized(ref);
		Map<String, Integer> counts = new TreeMap<>();
		for (Container box : Balance.storage(level, ref)) {
			if (box == null) continue;
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (stack.isEmpty()) continue;
				String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				counts.merge(id, stack.getCount(), (a, b) -> (int)Math.min(Integer.MAX_VALUE, (long)a + b));
			}
		}
		List<String> ids = new ArrayList<>(saved.keySet());
		counts.keySet().stream().filter(id -> !saved.containsKey(id)).forEach(ids::add);
		List<String> catalogIds = new ArrayList<>(TARGETABLE_ITEMS.stream()
			.filter(item -> category(item) != Category.OTHER)
			.map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
			.filter(id -> !ids.contains(id))
			.toList());
		for (String optionalId : OPTIONAL_MATERIAL_TARGET_IDS) {
			Identifier id = Identifier.parse(optionalId);
			Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item != null && category(item) == Category.MATERIAL && !ids.contains(optionalId)) catalogIds.add(optionalId);
		}
		catalogIds.sort(String::compareTo);
		ids.addAll(catalogIds);
		// Keep saved targets and actual stock visible first. The compact catalogue only fills
		// otherwise-empty space, so a village can set its first target before it owns the item.
		ids.sort(Comparator.comparing((String id) -> !saved.containsKey(id))
			.thenComparing(id -> !counts.containsKey(id)).thenComparing(id -> id));
		List<String> rows = new ArrayList<>();
		for (String raw : ids) {
			if (rows.size() >= MAX_ROWS) break;
			Identifier id = Identifier.tryParse(raw);
			if (id == null) continue;
			Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null || item == Items.AIR) continue;
			rows.add(raw + "|" + category(item).key() + "|" + counts.getOrDefault(raw, 0) + "|" + saved.getOrDefault(raw, UNLIMITED));
		}
		return new StockPolicyData(List.copyOf(rows));
	}
}
