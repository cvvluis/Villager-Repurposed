package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

/** Pflicht-Meilensteine eines Dorfes (Kornspeicher, Werkzeugkammer, Dorfkueche): Typ -> Ursprung des Gebaeudes. */
public final class Specials {
	private Specials() {
	}

	/** A public storage inventory and its position inside a loaded milestone building. */
	public record LocatedContainer(BlockPos pos, Container container) {
		public LocatedContainer {
			pos = pos.immutable();
			java.util.Objects.requireNonNull(container);
		}
	}

	public static Map<String, BlockPos> all(VillageRef ref) {
		Map<String, BlockPos> m = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		return m == null ? Map.of() : m;
	}

	public static boolean has(VillageRef ref, String type) {
		return all(ref).containsKey(type);
	}

	public static BlockPos get(VillageRef ref, String type) {
		return all(ref).get(type);
	}

	public static void remove(VillageRef ref, String type) {
		Map<String, BlockPos> m = new HashMap<>(all(ref));
		if (m.remove(type) == null) return;
		ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.copyOf(m));
		ref.dirty();
	}

	public static void set(VillageRef ref, String type, BlockPos origin) {
		Map<String, BlockPos> m = new HashMap<>(all(ref));
		m.put(type, origin);
		ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.copyOf(m));
		ref.dirty();
	}

	/** Zielgebaeude fuer einen Gegenstand: Nahrung -> Kornspeicher, Werkzeug/Waffe/Ruestung -> Werkzeugkammer (sonst null). */
	public static String depotType(net.minecraft.world.item.ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		return switch (StockPolicy.category(stack.getItem())) {
			case FOOD -> Blueprints_GRANARY;
			case TOOLS -> Blueprints_TOOLSHED;
			case MATERIAL -> isRepairGood(stack) ? Blueprints_TOOLSHED : null;
			case OTHER -> null;
		};
	}

	private static final java.util.Map<net.minecraft.world.item.Item, Boolean> REPAIR_GOODS = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * G05 design decision: "repair goods" are non-damageable public-material items that the loaded item rules accept as a
	 * repair ingredient of some equipment (for example leather) and that are not general construction or smelting stock.
	 * Ingots and nuggets (iron/copper/gold) stay ordinary public material because builders, smiths and the anvil recipe
	 * also consume them, and the anvil repair already reads every public container including the tool chamber.
	 * These goods prefer the tool chamber; without a chamber or room they fall back to ordinary public storage (never lost).
	 */
	public static boolean isRepairGood(net.minecraft.world.item.ItemStack stack) {
		if (stack.isEmpty() || stack.isDamageableItem() || StockPolicy.category(stack.getItem()) != StockPolicy.Category.MATERIAL) {
			return false;
		}
		return REPAIR_GOODS.computeIfAbsent(stack.getItem(), item -> {
			net.minecraft.world.item.ItemStack sample = new net.minecraft.world.item.ItemStack(item);
			if (item instanceof net.minecraft.world.item.BlockItem
				|| sample.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags.INGOTS)
				|| sample.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags.NUGGETS)
				|| sample.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags.STRINGS)) {
				return false;
			}
			for (net.minecraft.world.item.Item gear : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				net.minecraft.world.item.ItemStack damaged = new net.minecraft.world.item.ItemStack(gear);
				if (damaged.isDamageableItem() && damaged.isValidRepairItem(sample)) return true;
			}
			return false;
		});
	}

	private static final String Blueprints_GRANARY = de.villagerplus.build.Blueprints.GRANARY;
	private static final String Blueprints_TOOLSHED = de.villagerplus.build.Blueprints.TOOLSHED;

	/** Kiste/Fass des Meilenstein-Gebaeudes mit freiem Platz fuer diesen Gegenstand (sonst null). */
	public static BlockPos depotWithRoom(net.minecraft.server.level.ServerLevel level, VillageRef ref, String type, net.minecraft.world.item.ItemStack sample) {
		BlockPos origin = get(ref, type);
		if (origin == null || !de.villagerplus.world.LoadedColumns.ready(level, origin)) {
			return null;
		}
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-9, -2, -9), origin.offset(9, 8, 9))) {
			var box = Lager.publicStorageAt(level, pos) ? Lager.publicContainerAt(level, pos) : null;
			if (box == null) {
				continue;
			}
			for (int i = 0; i < box.getContainerSize(); i++) {
				var in = box.getItem(i);
				if (in.isEmpty() || (net.minecraft.world.item.ItemStack.isSameItemSameComponents(in, sample) && in.getCount() < in.getMaxStackSize())) {
					double distance = origin.distSqr(pos);
					if (distance < bestDistance) {
						best = pos.immutable();
						bestDistance = distance;
					}
					break;
				}
			}
		}
		return best;
	}

	/** Alle Kisten und Faesser eines Meilenstein-Gebaeudes (leer, wenn es nicht steht oder nicht geladen ist). */
	public static java.util.List<net.minecraft.world.Container> containers(net.minecraft.server.level.ServerLevel level, VillageRef ref, String type) {
		java.util.List<net.minecraft.world.Container> out = new java.util.ArrayList<>();
		BlockPos origin = get(ref, type);
		if (origin == null || !de.villagerplus.world.LoadedColumns.ready(level, origin)) {
			return out;
		}
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-9, -2, -9), origin.offset(9, 8, 9))) {
			if (!Lager.publicStorageAt(level, pos)) continue;
			var box = Lager.publicContainerAt(level, pos);
			if (box != null) {
				out.add(box);
			}
		}
		return out;
	}

	/** Loaded public storage in a milestone building, paired with its block position. */
	public static List<LocatedContainer> locatedContainers(ServerLevel level, VillageRef ref, String type) {
		List<LocatedContainer> out = new ArrayList<>();
		BlockPos origin = get(ref, type);
		if (origin == null || !de.villagerplus.world.LoadedColumns.ready(level, origin)) {
			return out;
		}
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-9, -2, -9), origin.offset(9, 8, 9))) {
			if (!de.villagerplus.world.LoadedColumns.ready(level, pos) || !Lager.publicStorageAt(level, pos)) continue;
			Container box = Lager.publicContainerAt(level, pos);
			if (box != null) {
				out.add(new LocatedContainer(pos, box));
			}
		}
		return out;
	}
}
