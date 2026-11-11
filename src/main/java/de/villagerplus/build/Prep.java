package de.villagerplus.build;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Gelaendevorbereitung. Bauwerke werden in die Landschaft hineingebaut: Nur die Saeulen, in denen wirklich etwas gebaut wird
 * ({@link #footprint}), werden auf Basishoehe gebracht (Huegel dort abtragen = das Haus sitzt im Hang, Senken fuellt das Fundament
 * beim Bauen). Das Gelaende ringsum bleibt unveraendert. Nur Felder (fp = null) ebnen die ganze Flaeche ein. Zustandslos.
 */
public final class Prep {
	private Prep() {
	}

	private static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	/** Saeulen (x, z), in denen bezahlte Bloecke auf Boden-/Wandhoehe stehen, plus eingeschlossene Luecken (z. B. Brunnenmitte). */
	public static Set<Long> footprint(List<Step> steps, BlockPos anchor) {
		Set<Long> fp = new HashSet<>();
		for (Step s : steps) {
			if (!s.free() && s.pos().getY() >= anchor.getY() - 1) {
				fp.add(key(s.pos().getX(), s.pos().getZ()));
			}
		}
		for (Step s : steps) {
			if (s.free() && s.pos().getY() >= anchor.getY() - 1) {
				int x = s.pos().getX();
				int z = s.pos().getZ();
				if (!fp.contains(key(x, z))) {
					int n = (fp.contains(key(x + 1, z)) ? 1 : 0) + (fp.contains(key(x - 1, z)) ? 1 : 0) + (fp.contains(key(x, z + 1)) ? 1 : 0)
						+ (fp.contains(key(x, z - 1)) ? 1 : 0);
					if (n >= 3) {
						fp.add(key(x, z));
					}
				}
			}
		}
		return fp;
	}

	/**
	 * Gelaende eines abgebrochenen Bauplatzes wiederherstellen: Nur waehrend der Vorbereitung abgetragene Hoehen werden
	 * mit Erde (oben Gras) geschlossen. Niedriges Naturgelaende wird nicht als kostenloser Fuellboden behandelt.
	 */
	public static void restore(ServerLevel level, BlockPos anchor, java.util.Map<Long, Integer> record) {
		int base = anchor.getY();
		for (java.util.Map.Entry<Long, Integer> e : record.entrySet()) {
			int x = (int) (e.getKey() >> 32);
			int z = (int) (long) e.getKey();
			int orig = e.getValue();
			if (!level.hasChunkAt(new BlockPos(x, base, z))) {
				continue;
			}
			if (orig > base) {
				for (int y = base; y < orig; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = level.getBlockState(p);
					if (s.isAir() || s.canBeReplaced()) {
						level.setBlock(p, y == orig - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 3);
					}
				}
			}
		}
	}

	public static boolean contains(Set<Long> fp, int x, int z) {
		return fp.contains(key(x, z));
	}

	/** true = fertig, false = es gibt noch Arbeit (naechster Aufruf macht weiter). {@code fp} = null: gesamte Flaeche der Schritte. */
	public static boolean run(ServerLevel level, BlockPos anchor, List<Step> steps, int budget, Set<Long> fp) {
		return run(level, anchor, steps, budget, fp, null);
	}

	/**
	 * Wie {@link #run(ServerLevel, BlockPos, List, int, Set)}; {@code record} merkt sich je veraenderter Saeule die urspruengliche
	 * Gelaendehoehe, damit ein abgebrochener Bau das Gelaende wieder herrichten kann ({@link #restore}).
	 */
	public static boolean run(ServerLevel level, BlockPos anchor, List<Step> steps, int budget, Set<Long> fp, java.util.Map<Long, Integer> record) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, maxY = 0;
		for (Step s : steps) {
			if (fp != null && s.free()) {
				continue;
			}
			minX = Math.min(minX, s.pos().getX());
			maxX = Math.max(maxX, s.pos().getX());
			minZ = Math.min(minZ, s.pos().getZ());
			maxZ = Math.max(maxZ, s.pos().getZ());
			maxY = Math.max(maxY, s.pos().getY() - anchor.getY());
		}
		int base = anchor.getY();
		int work = 0;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (fp != null && !fp.contains(key(x, z))) {
					continue;
				}
				if (!level.hasChunkAt(new BlockPos(x, base, z))) {
					continue;
				}
				int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				boolean changed = false;
				for (int y = h - 1; y >= base; y--) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(p);
					if (!state.isAir() && !state.hasBlockEntity()) {
						level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
						changed |= level.getBlockState(p).isAir();
					}
				}
				for (int y = base; y <= base + maxY; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = level.getBlockState(p);
					if (!s.isAir() && s.canBeReplaced()) {
						level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
						changed = true;
					}
				}
				if (changed && record != null && h > base) {
					record.putIfAbsent(key(x, z), h);
				}
				if (changed && ++work >= budget) {
					return false;
				}
			}
		}
		return true;
	}
}
