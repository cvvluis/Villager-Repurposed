package de.villagerplus.village;

import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Meals;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;

/**
 * Ausgewogene Vorraete: Es gibt keine festen Mengenlimits (die Kisten duerfen so voll werden, wie sie wollen). Stattdessen
 * achten die Villager darauf, dass Essen, Holz, Stein und Metall im Dorf ungefaehr im Gleichgewicht bleiben: Wer etwas
 * beschafft, schaut, welcher Vorrat (Dorfkiste + Lager) gerade im Verhaeltnis zu den anderen am knappsten ist.
 */
public final class Balance {
	public enum Kind { FOOD, WOOD, STONE, METAL }

	private Balance() {
	}

	/** Dorfkiste und alle Truhen des Lagerschuppens. */
	public static List<Container> storage(ServerLevel level, VillageRef ref) {
		Map<BlockPos, Container> byPosition = new LinkedHashMap<>();
		for (BlockPos pos : Lager.materialChests(level, ref)) addStorage(level, byPosition, pos);
		// Kornspeicher, Werkzeugkammer und Bahnhof gehoeren zum Dorfvorrat (Nahrung liegt dort, nicht in der Dorfkiste).
		for (String type : new String[] {de.villagerplus.build.Blueprints.GRANARY, de.villagerplus.build.Blueprints.TOOLSHED, de.villagerplus.build.Blueprints.STATION}) {
			BlockPos origin = Specials.get(ref, type);
			if (origin == null || !level.hasChunkAt(origin)) continue;
			for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-9, -2, -9), origin.offset(9, 8, 9))) {
				if (Lager.publicStorageAt(level, pos)) addStorage(level, byPosition, pos);
			}
		}
		return new ArrayList<>(byPosition.values());
	}

	private static void addStorage(ServerLevel level, Map<BlockPos, Container> stores, BlockPos pos) {
		Container container = Lager.publicContainerAt(level, pos);
		if (container == null) return;
		BlockPos key = pos.immutable();
		var state = level.getBlockState(pos);
		// A primary chest reference may point at the right half while specialist scans return
		// the left half. Both positions expose the same 54-slot inventory and count only once.
		if (state.getBlock() instanceof ChestBlock
			&& state.getValue(ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.RIGHT) {
			key = pos.relative(ChestBlock.getConnectedDirection(state)).immutable();
		}
		stores.putIfAbsent(key, container);
	}

	/** Vorrat einer Gruppe in vergleichbaren Einheiten (Metall zaehlt mehr als Bruchstein). */
	public static long stock(List<Container> boxes, Kind kind) {
		long total = 0;
		for (Container box : boxes) {
			total += switch (kind) {
				case FOOD -> JobUtil.count(box, Meals::isFood);
				case WOOD -> 2L * JobUtil.count(box, s -> s.is(ItemTags.LOGS)) + JobUtil.count(box, s -> s.is(ItemTags.PLANKS)) / 2;
				case STONE -> JobUtil.count(box, s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.STONE)) / 2;
				case METAL -> 6L * JobUtil.count(box, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON) || s.is(Items.COAL)
					|| s.is(Items.CHARCOAL) || s.is(Items.COPPER_INGOT) || s.is(Items.RAW_COPPER));
			};
		}
		return total;
	}

	/** Soll diese Gruppe gerade beschafft werden? Ja, solange sie nicht deutlich ueber dem Durchschnitt aller Gruppen liegt. */
	public static boolean wants(ServerLevel level, VillageRef ref, Kind kind) {
		long now = level.getGameTime();
		long[] cached = CACHE.get(ref.bell().asLong());
		if (cached == null || now >= cached[0]) {
			// Alle Gruppen auf einmal und nur alle 100 Ticks neu berechnen (das Durchzaehlen aller Kisten ist teuer).
			List<Container> boxes = storage(level, ref);
			long mask = 0;
			if (!boxes.isEmpty()) {
				long sum = 0;
				long[] stocks = new long[Kind.values().length];
				for (Kind k : Kind.values()) {
					stocks[k.ordinal()] = stock(boxes, k);
					sum += stocks[k.ordinal()];
				}
				double average = sum / (double) stocks.length;
				for (Kind k : Kind.values()) {
					if (stocks[k.ordinal()] <= average * (VillageAdmin.focusKind(ref) == k ? 2.5 : 1.25) + 16) {
						mask |= 1L << k.ordinal();
					}
				}
			}
			cached = new long[] {now + 100L, mask};
			CACHE.put(ref.bell().asLong(), cached);
		}
		return (cached[1] & (1L << kind.ordinal())) != 0;
	}

	private static final java.util.Map<Long, long[]> CACHE = new java.util.HashMap<>();
}
