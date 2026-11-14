package de.villagerplus.village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Display.ItemDisplay;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Render-only shelf goods. The stall chest stays the sole source of inventory truth. */
final class MarketDisplays {
	private static final String DISPLAY_TAG = "vp_market_goods_display";
	private static final String ID_PREFIX = "vp_market_goods:";
	private static final Map<UUID, ServerLevel> PENDING = new HashMap<>();
	private static final Map<DisplayOwner, Set<UUID>> DISPLAY_IDS = new HashMap<>();
	private static final Map<UUID, DisplayOwner> DISPLAY_OWNERS = new HashMap<>();
	private static final Map<UUID, ItemDisplay> DISPLAY_ENTITIES = new HashMap<>();
	private static String lastSyncResult = "not synced yet";
	private record DisplayOwner(ServerLevel level, String identity) { }

	private MarketDisplays() { }

	static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ItemDisplay display && isDisplay(display)) {
				remember(level, display);
				PENDING.put(display.getUUID(), level);
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof ItemDisplay display) {
				PENDING.remove(display.getUUID());
				forget(level, display);
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(MarketDisplays::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Set<ServerLevel> stoppedLevels = new HashSet<>();
			for (ServerLevel level : server.getAllLevels()) stoppedLevels.add(level);
			PENDING.entrySet().removeIf(entry -> stoppedLevels.contains(entry.getValue()));
			DISPLAY_IDS.keySet().removeIf(owner -> stoppedLevels.contains(owner.level()));
			DISPLAY_ENTITIES.keySet().removeIf(id -> {
				DisplayOwner owner = DISPLAY_OWNERS.get(id);
				return owner != null && stoppedLevels.contains(owner.level());
			});
			DISPLAY_OWNERS.entrySet().removeIf(entry -> stoppedLevels.contains(entry.getValue().level()));
		});
	}

	static boolean isDisplay(ItemDisplay display) {
		return display.entityTags().contains(DISPLAY_TAG);
	}

	private static void remember(ServerLevel level, ItemDisplay display) {
		String identity = identityOf(display);
		if (identity == null) return;
		DisplayOwner owner = new DisplayOwner(level, identity);
		DISPLAY_OWNERS.put(display.getUUID(), owner);
		DISPLAY_ENTITIES.put(display.getUUID(), display);
		DISPLAY_IDS.computeIfAbsent(owner, ignored -> new HashSet<>()).add(display.getUUID());
	}

	private static void forget(ServerLevel level, ItemDisplay display) {
		DisplayOwner owner = DISPLAY_OWNERS.get(display.getUUID());
		if (owner != null && owner.level() == level) forget(owner, display.getUUID());
	}

	private static void forget(DisplayOwner owner, UUID id) {
		DISPLAY_OWNERS.remove(id, owner);
		DISPLAY_ENTITIES.remove(id);
		Set<UUID> ids = DISPLAY_IDS.get(owner);
		if (ids == null) return;
		ids.remove(id);
		if (ids.isEmpty()) DISPLAY_IDS.remove(owner);
	}

	private static void discard(ServerLevel level, ItemDisplay display) {
		display.discard();
		PENDING.remove(display.getUUID());
		forget(level, display);
	}

	static List<ItemDisplay> displaysFor(ServerLevel level, BlockPos bell, BlockPos origin) {
		DisplayOwner owner = new DisplayOwner(level, identity(bell, origin));
		Set<UUID> ids = DISPLAY_IDS.get(owner);
		if (ids == null || ids.isEmpty()) return List.of();
		List<ItemDisplay> result = new ArrayList<>();
		String key = ID_PREFIX + owner.identity();
		for (UUID id : new ArrayList<>(ids)) {
			ItemDisplay display = DISPLAY_ENTITIES.get(id);
			if (display != null && !display.isRemoved() && isDisplay(display) && display.entityTags().contains(key)) result.add(display);
			else forget(owner, id);
		}
		return result;
	}

	static String diagnostic(ServerLevel level, BlockPos bell, MarketData.Shop shop, Container stock) {
		Vec3 counter = counterPosition(shop, 0);
		Vec3 fallback = stockPosition(shop, 0);
		List<String> displays = displaysFor(level, bell, shop.origin()).stream()
			.map(display -> display.getItemStack().getItem() + "@" + display.blockPosition().toShortString()).toList();
		return "bread=" + count(stock, net.minecraft.world.item.Items.BREAD)
			+ ", displayCount=" + displays.size() + ", displays=" + displays
			+ ", lastSync=" + lastSyncResult
			+ ", counterChunkLoaded=" + level.hasChunkAt(BlockPos.containing(counter))
			+ "@" + BlockPos.containing(counter).toShortString()
			+ ", stockVisualChunkLoaded=" + level.hasChunkAt(BlockPos.containing(fallback))
			+ "@" + BlockPos.containing(fallback).toShortString();
	}

	static void clear(ServerLevel level, BlockPos bell, BlockPos origin) {
		for (ItemDisplay display : displaysFor(level, bell, origin)) discard(level, display);
	}

	/** Reconcile visual stacks from the real shop chest without loading chunks. */
	static void sync(ServerLevel level, VillageRef ref, MarketData.Shop shop, Container stock) {
		if (ref == null || shop == null || stock == null) return;
		List<ItemStack> goods = visibleGoods(stock, shop.kind());
		String identity = identity(ref.bell(), shop.origin());
		List<ItemDisplay> owned = displaysFor(level, ref.bell(), shop.origin());
		lastSyncResult = "goods=" + goods.size() + ", existing=" + owned.size();
		for (int slot = 0; slot < goods.size(); slot++) {
			String tag = tag(ref.bell(), shop.origin(), slot);
			ItemDisplay display = owned.stream().filter(candidate -> candidate.entityTags().contains(tag)).findFirst().orElse(null);
			for (ItemDisplay duplicate : owned) if (duplicate != display && duplicate.entityTags().contains(tag)) discard(level, duplicate);
			ItemStack stack = goods.get(slot);
			if (display == null) display = create(level, ref.bell(), shop, slot, tag, stack);
			else {
				if (!ItemStack.isSameItemSameComponents(display.getItemStack(), stack)) display.setItemStack(stack.copyWithCount(1));
				position(level, display, shop, slot);
				lastSyncResult += ", reused=" + display.getUUID();
			}
		}
		for (ItemDisplay display : owned) {
			int slot = slotOf(display, identity);
			if (slot < 0 || slot >= goods.size()) discard(level, display);
		}
	}

	private static List<ItemStack> visibleGoods(Container stock, int kind) {
		List<ItemStack> goods = new ArrayList<>();
		Item[] allowed = Market.goods(kind);
		for (int slot = 0; slot < stock.getContainerSize(); slot++) {
			ItemStack stack = stock.getItem(slot);
			if (stack.isEmpty() || java.util.Arrays.stream(allowed).noneMatch(stack::is)) continue;
			if (goods.stream().noneMatch(existing -> ItemStack.isSameItemSameComponents(existing, stack))) goods.add(stack.copyWithCount(1));
			if (goods.size() == allowed.length) break;
		}
		return goods;
	}

	/** Called when a persisted marker loads; stale/duplicated visuals are repaired against the chest. */
	static void reconcileLoaded(ServerLevel level, ItemDisplay display) {
		if (!isDisplay(display)) return;
		String identity = identityOf(display);
		if (identity == null) { discard(level, display); return; }
		String[] parts = identity.split(":", -1);
		if (parts.length != 2) { discard(level, display); return; }
		BlockPos bellPos;
		BlockPos origin;
		try {
			bellPos = BlockPos.of(Long.parseLong(parts[0]));
			origin = BlockPos.of(Long.parseLong(parts[1]));
		} catch (NumberFormatException invalidTag) {
			discard(level, display);
			return;
		}
		if (!level.hasChunkAt(bellPos) || !level.hasChunkAt(origin)) return;
		if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) { discard(level, display); return; }
		VillageRef ref = new VillageRef(bellPos, bell.getAttachedOrCreate(de.villagerplus.VillagerPlus.VILLAGE), bell);
		MarketData.Shop shop = Market.of(ref).shops().stream().filter(candidate -> candidate.origin().equals(origin)).findFirst().orElse(null);
		if (shop == null || shop.opened() == 0) { discard(level, display); return; }
		BlockPos stockPos = Market.stockPos(shop);
		if (!level.hasChunkAt(stockPos)) return;
		Container stock = Market.stock(level, shop);
		if (stock == null) { discard(level, display); return; }
		sync(level, ref, shop, stock);
	}

	private static void tick(ServerLevel level) {
		if (level.getGameTime() % 20 != 0 || PENDING.isEmpty()) return;
		for (var entry : new ArrayList<>(PENDING.entrySet())) {
			if (entry.getValue() != level) continue;
			PENDING.remove(entry.getKey());
			if (level.getEntity(entry.getKey()) instanceof ItemDisplay display) reconcileLoaded(level, display);
		}
	}

	private static ItemDisplay create(ServerLevel level, BlockPos bell, MarketData.Shop shop, int slot, String fullTag, ItemStack stack) {
		Vec3 pos = loadedPosition(level, shop, slot);
		if (pos == null) { lastSyncResult += ", create=skipped:no loaded counter/stock chunk"; return null; }
		String identity = identity(bell, shop.origin());
		ItemDisplay display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (display == null) { lastSyncResult += ", create=failed:entity type returned null"; return null; }
		display.addTag(DISPLAY_TAG);
		display.addTag(ID_PREFIX + identity);
		display.addTag(fullTag);
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setItemTransform(ItemDisplayContext.GROUND);
		display.setItemStack(stack.copyWithCount(1));
		display.setPos(pos.x, pos.y, pos.z);
		display.setYRot(Direction.from2DDataValue(shop.facing()).toYRot());
		if (!level.addFreshEntity(display)) { display.discard(); lastSyncResult += ", create=failed:addFreshEntity returned false"; return null; }
		remember(level, display);
		lastSyncResult += ", create=added:" + display.getUUID() + "@" + display.blockPosition().toShortString()
			+ ", ownerTag=" + display.entityTags().contains(ID_PREFIX + identity)
			+ ", displayTag=" + display.entityTags().contains(DISPLAY_TAG)
			+ ", cachedReference=" + (DISPLAY_ENTITIES.get(display.getUUID()) == display)
			+ ", immediateUuidIndex=" + (level.getEntity(display.getUUID()) == display);
		return display;
	}

	private static void position(ServerLevel level, ItemDisplay display, MarketData.Shop shop, int slot) {
		Vec3 pos = loadedPosition(level, shop, slot);
		if (pos == null) return;
		display.setPos(pos.x, pos.y, pos.z);
		display.setYRot(Direction.from2DDataValue(shop.facing()).toYRot());
	}

	private static Vec3 loadedPosition(ServerLevel level, MarketData.Shop shop, int slot) {
		Vec3 counter = counterPosition(shop, slot);
		if (level.hasChunkAt(BlockPos.containing(counter))) return counter;
		Vec3 stock = stockPosition(shop, slot);
		return level.hasChunkAt(BlockPos.containing(stock)) ? stock : null;
	}

	private static Vec3 counterPosition(MarketData.Shop shop, int slot) {
		Direction facing = Direction.from2DDataValue(shop.facing());
		Direction across = facing.getClockWise();
		int column = slot % 3;
		int row = slot / 3;
		return Vec3.atCenterOf(shop.origin())
			.add(across.getStepX() * (column - 1) * 0.68, 1.15 + row * 0.36, across.getStepZ() * (column - 1) * 0.68)
			.add(facing.getStepX() * (3.25 + row * 0.45), facing.getStepY() * (3.25 + row * 0.45), facing.getStepZ() * (3.25 + row * 0.45));
	}

	/** Use the already-loaded real stock chest as an in-stand visual anchor when its counter lies in an unloaded chunk. */
	private static Vec3 stockPosition(MarketData.Shop shop, int slot) {
		Direction across = Direction.from2DDataValue(shop.facing()).getClockWise();
		int column = slot % 3;
		int row = slot / 3;
		return Vec3.atCenterOf(Market.stockPos(shop))
			.add(across.getStepX() * (column - 1) * 0.24, 1.15 + row * 0.36,
				across.getStepZ() * (column - 1) * 0.24);
	}

	private static int count(Container container, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	private static String identity(BlockPos bell, BlockPos origin) {
		return bell.asLong() + ":" + origin.asLong();
	}

	private static String tag(BlockPos bell, BlockPos origin, int slot) {
		return ID_PREFIX + identity(bell, origin) + ":" + slot;
	}

	private static String identityOf(ItemDisplay display) {
		return display.entityTags().stream().filter(tag -> tag.startsWith(ID_PREFIX)).map(tag -> tag.substring(ID_PREFIX.length()))
			.filter(value -> {
				String[] parts = value.split(":", -1);
				if (parts.length != 3) return false;
				try { Long.parseLong(parts[0]); Long.parseLong(parts[1]); Integer.parseInt(parts[2]); return true; }
				catch (NumberFormatException ignored) { return false; }
			})
			.map(value -> { String[] parts = value.split(":", -1); return parts[0] + ":" + parts[1]; }).findFirst().orElse(null);
	}

	private static int slotOf(ItemDisplay display, String identity) {
		String prefix = ID_PREFIX + identity + ":";
		return display.entityTags().stream().filter(tag -> tag.startsWith(prefix)).map(tag -> {
			try { return Integer.parseInt(tag.substring(prefix.length())); }
			catch (NumberFormatException ignored) { return -1; }
		}).findFirst().orElse(-1);
	}
}
