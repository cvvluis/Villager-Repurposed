package de.villagerplus.build;

import de.villagerplus.village.VillageData;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;

/** R02 helpers for turning the builder's persisted shortage list into bounded caravan orders. */
public final class PlanSupplyOrder {
	private PlanSupplyOrder() { }

	/** Saved plan rows hold required stock totals. Duplicate rows are combined safely. */
	public static Map<Item, Integer> shortages(VillageData data) {
		if (data == null || !data.hasPlan()) return Map.of();
		return shortages(data.wanted);
	}

	/** Parse one project's persisted demand without accidentally reading another project's aggregate wishlist. */
	public static Map<Item, Integer> shortages(Iterable<String> rows) {
		if (rows == null) return Map.of();
		Map<Item, Integer> result = new LinkedHashMap<>();
		for (String row : rows) {
			if (row == null) continue;
			String[] parts = row.split("#", 2);
			Identifier id = Identifier.tryParse(parts[0]);
			if (id == null) continue;
			Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (!(item instanceof BlockItem) || item == Items.AIR) continue;
			int count = quantity(parts);
			if (count <= 0) continue;
			result.merge(item, count, (a, b) -> (int)Math.min(Integer.MAX_VALUE, (long)a + b));
		}
		return Collections.unmodifiableMap(result);
	}

	public static boolean requests(VillageData data, Item item) {
		return item != null && shortages(data).containsKey(item);
	}

	/** A request can spend only its recorded demand minus matching stock already available. */
	public static int remaining(VillageData data, Item item, int alreadyAvailable) {
		int requested = shortages(data).getOrDefault(item, 0);
		return Math.max(0, requested - Math.max(0, alreadyAvailable));
	}

	/** Exact ceiling in emeralds for a price quoted in sixty-fourths of an emerald. */
	public static int emeraldCost(int count, int pricePerItem) {
		if (count <= 0 || pricePerItem <= 0) return 0;
		long tradeValue = (long)count * pricePerItem;
		return (int)Math.min(Integer.MAX_VALUE, (tradeValue + 63L) / 64L);
	}

	private static int quantity(String[] parts) {
		if (parts.length < 2) return 1;
		try {
			return Math.max(0, Integer.parseInt(parts[1]));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}
}
