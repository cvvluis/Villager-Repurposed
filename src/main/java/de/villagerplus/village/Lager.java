package de.villagerplus.village;

import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Lagerhaeuser: nehmen den Ueberschuss auf, wenn die Dorfkiste voll ist. Ein Lagerhaus ist eine Kopie eines Dorfgebaeudes, innen mit
 * Doppelkisten eingerichtet (siehe {@code Furnisher}); die Kistenpositionen stehen in {@code Extra.lagerChests}. Ist alles voll,
 * baut das Dorf ein weiteres Lagerhaus.
 */
public final class Lager {
	public static final int MAX_EXTRA_SHEDS = 3;

	private Lager() {
	}

	/** Mittelpunkte aller Lagerhaeuser des Dorfes. */
	public static List<BlockPos> centers(VillageData d) {
		List<BlockPos> out = new ArrayList<>();
		d.extra.lager.ifPresent(out::add);
		out.addAll(d.extra.lagerMore);
		return out;
	}

	/** Noch vorhandene Lagerkisten (bei Doppelkisten die linke Haelfte). */
	private static List<BlockPos> chests(ServerLevel level, VillageData d) {
		if (d.extra.lagerChests.isEmpty() && d.extra.lager.isPresent()) {
			// Uebernahme alter Welten (0.7.0): Kisten der bekannten Lagerhaeuser einmalig suchen und merken.
			for (BlockPos center : centers(d)) {
				for (BlockPos p : BlockPos.betweenClosed(center.offset(-5, -1, -5), center.offset(5, 4, 5))) {
					if (!level.hasChunkAt(p)) continue;
					var s = level.getBlockState(p);
					if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.RIGHT) {
						d.extra.lagerChests.add(p.immutable());
					}
				}
			}
		}
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos p : d.extra.lagerChests) {
			if (level.hasChunkAt(p) && level.getBlockState(p).getBlock() instanceof ChestBlock) {
				out.add(p);
			}
		}
		return out;
	}

	/** Nach Bau/Umbau eines Lagerhauses (frueher: Zwischenspeicher verwerfen; die Positionen sind jetzt gespeichert). */
	public static void invalidate() {
	}

	/** Eine Lagerkiste mit freiem Platz (null = keine/alle voll). */
	public static BlockPos chestWithRoom(ServerLevel level, VillageData d) {
		for (BlockPos p : chests(level, d)) {
			Container box = publicContainerAt(level, p);
			if (box != null && JobUtil.hasEmptySlot(box)) {
				return p;
			}
		}
		return null;
	}

	/** Materiallager-Ziel fuer einen konkreten Stapel; verhindert, dass ein voller/inkompatibler Kisteninhalt blockiert. */
	public static BlockPos chestWithRoom(ServerLevel level, VillageData d, ItemStack sample) {
		for (BlockPos p : chests(level, d)) {
			Container box = publicContainerAt(level, p);
			if (box == null) continue;
			for (int i = 0; i < box.getContainerSize(); i++) {
				ItemStack current = box.getItem(i);
				if (current.isEmpty() || (ItemStack.isSameItemSameComponents(current, sample)
					&& current.getCount() < current.getMaxStackSize())) return p;
			}
		}
		return null;
	}

	/** Physische, oeffentliche Materialabholstellen; Privatkisten sind ausgeschlossen. */
	public static List<BlockPos> materialChests(ServerLevel level, VillageRef ref) {
		java.util.Set<BlockPos> out = new java.util.LinkedHashSet<>();
		BlockPos primary = ref.chestPos(level);
		if (primary != null) out.add(primary);
		out.addAll(chests(level, ref.data()));
		return out.stream().filter(p -> publicContainerAt(level, p) != null).toList();
	}

	/** Alle Lagerkisten als Behaelter (eine Doppelkiste = ein Behaelter). */
	public static List<Container> containers(ServerLevel level, VillageData d) {
		List<Container> out = new ArrayList<>();
		for (BlockPos p : chests(level, d)) {
			Container box = publicContainerAt(level, p);
			if (box != null) {
				out.add(box);
			}
		}
		return out;
	}

	public static Container publicContainerAt(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return null;
		var blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null || Ownership.ownerAt(level, pos) != null) return null;
		var state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
			BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
			if (!level.hasChunkAt(other)) return null;
			var otherState = level.getBlockState(other);
			var type = state.getValue(ChestBlock.TYPE);
			if (!(otherState.getBlock() instanceof ChestBlock) || otherState.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
				|| otherState.getValue(ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE
				|| otherState.getValue(ChestBlock.TYPE) == type) return null;
			if (level.getBlockEntity(other) == null || Ownership.ownerAt(level, other) != null) return null;
		}
		return HopperBlockEntity.getContainerAt(level, pos);
	}

	/** Public storage accepted by village carriers; private chests and either privately owned double-chest half fail closed. */
	public static boolean publicStorageAt(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return false;
		var state = level.getBlockState(pos);
		boolean chest = state.getBlock() instanceof ChestBlock
			&& state.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.RIGHT;
		boolean storage = chest || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock;
		return storage && publicContainerAt(level, pos) != null;
	}

	/** Belegte Plaetze / alle Plaetze in den Lagerkisten (fuer die Dorftafel). */
	public static int[] fill(ServerLevel level, VillageData d) {
		int used = 0;
		int total = 0;
		for (Container box : containers(level, d)) {
			total += box.getContainerSize();
			for (int i = 0; i < box.getContainerSize(); i++) {
				if (!box.getItem(i).isEmpty()) {
					used++;
				}
			}
		}
		return new int[] {used, total};
	}

	/** Dorfkiste und alle Lagerkisten als ein gemeinsames Lager (zum Bauen, Zaehlen und Bezahlen). null = keine Dorfkiste. */
	public static Container combined(ServerLevel level, VillageRef ref) {
		Container result = ref.chest(level);
		if (result == null) {
			return null;
		}
		for (Container lager : containers(level, ref.data())) {
			result = new net.minecraft.world.CompoundContainer(result, lager);
		}
		return result;
	}

	/** Anzahl passender Gegenstaende in allen Lagerkisten. */
	public static int countAll(ServerLevel level, VillageData d, Predicate<ItemStack> match) {
		int n = 0;
		for (Container box : containers(level, d)) {
			n += JobUtil.count(box, match);
		}
		return n;
	}

	/**
	 * Macht in einer vollen Dorfkiste einen Platz frei, indem der kleinste Stapel, der gerade nicht gebraucht wird, ins Lager
	 * wandert. true = es ist jetzt ein Platz frei.
	 */
	public static boolean freeSlot(ServerLevel level, VillageData d, Container box, Predicate<ItemStack> needed) {
		if (JobUtil.hasEmptySlot(box)) {
			return true;
		}
		int best = -1;
		for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack s = box.getItem(i);
			if (!s.isEmpty() && !needed.test(s) && (best < 0 || s.getCount() < box.getItem(best).getCount())) {
				best = i;
			}
		}
		if (best < 0) {
			return false;
		}
		ItemStack stack = box.getItem(best);
		for (Container lager : containers(level, d)) {
			ItemStack rest = HopperBlockEntity.addItem(null, lager, stack.copy(), null);
			if (rest.isEmpty()) {
				box.setItem(best, ItemStack.EMPTY);
				box.setChanged();
				return true;
			}
			stack.setCount(rest.getCount());
		}
		return false;
	}

	/** Legt Waren ab: erst Dorfkiste, dann Lager; was nirgends mehr Platz findet, landet vor der Kiste auf dem Boden. */
	public static void deposit(ServerLevel level, VillageRef ref, ItemStack stack) {
		ItemStack rest = StockPolicy.insertVillage(level, ref, stack);
		if (!rest.isEmpty()) {
			BlockPos at = ref.chestPos(level) != null ? ref.chestPos(level) : ref.bell();
			level.addFreshEntity(new ItemEntity(level, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, rest));
		}
	}
}
