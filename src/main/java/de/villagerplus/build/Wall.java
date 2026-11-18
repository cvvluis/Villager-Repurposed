package de.villagerplus.build;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Stadtmauer als Burgmauer: drei Bloecke dick, oben ein begehbarer Wehrgang mit Bruestung (Zinnen aussen, Gelaender innen,
 * Laternen), Treppen auf der Innenseite, Tore (3 breit, ueberbrueckt vom Wehrgang) mit Tortuermen und Eckwaechtern.
 * Die Mauer folgt dem Ring um die Glocke; jede "Saeule" t ist ein Querschnitt von drei Bloecken Tiefe.
 */
public final class Wall {
	public static final int THICK = 3;
	/** Geometry is fixed before placing a column, especially after water has been replaced by its paid foundation. */
	public record Profile(int baseY, List<Integer> footings) {
		public static final Codec<Profile> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("base_y").forGetter(Profile::baseY), Codec.INT.listOf().fieldOf("footings").forGetter(Profile::footings)
		).apply(i, Profile::new));
		public Profile { footings = List.copyOf(footings); }
	}
	public static final AttachmentType<Map<String, Profile>> PROFILES = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath("villagerplus", "wall_profiles"), Codec.unboundedMap(Codec.STRING, Profile.CODEC));
	/** Called during mod initialization so the independent persistent attachment is registered before worlds load. */
	public static void register() { }

	/** Ein zu setzender Block; {@code paid} = bezahlt aus der Dorfkiste (Mauermaterial), sonst gratis (Zierde, Luft). */
	public record Cell(BlockPos pos, BlockState state, boolean paid) { }

	private Wall() {
	}

	/** Position der Saeule t auf dem quadratischen Ring mit Radius r um die Glocke (Seiten im Uhrzeigersinn). */
	public static int[] ringOffset(int r, int t) {
		int side = t / (2 * r);
		int k = t % (2 * r);
		return switch (side) {
			case 0 -> new int[] {-r + k, -r};
			case 1 -> new int[] {r, -r + k};
			case 2 -> new int[] {r - k, r};
			default -> new int[] {-r, r - k};
		};
	}

	/** Richtung nach innen (zur Dorfmitte) fuer die Seite der Saeule t. */
	public static Direction inward(int r, int t) {
		return switch (t / (2 * r)) {
			case 0 -> Direction.SOUTH;
			case 1 -> Direction.WEST;
			case 2 -> Direction.NORTH;
			default -> Direction.EAST;
		};
	}

	/** Bodenhoehe (erste freie Luftschicht) ohne die eigene Mauer: Mauerbloecke, Zaeune, Laternen und Torbogen zaehlen nicht mit. */
	public static int groundHeight(ServerLevel level, int x, int z, Block wall) {
		int h = de.villagerplus.world.LoadedColumns.heightOrMinY(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		while (h > level.getMinY() + 1) {
			BlockState s = level.getBlockState(new BlockPos(x, h - 1, z));
			if (s.is(wall) || s.is(Blocks.LANTERN) || s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock || s.getBlock() instanceof StairBlock) {
				h--;
			} else if (s.isAir() && level.getBlockState(new BlockPos(x, h, z)).is(wall)) {
				h--;
			} else {
				break;
			}
		}
		return h;
	}

	private static Profile profile(ServerLevel level, BlockPos bell, Block wall, int r, int t) {
		if (r <= 0 || t < 0 || t >= 8 * r) return null;
		var owner = level.getBlockEntity(bell);
		Map<String, Profile> stored = owner == null ? Map.of() : owner.getAttachedOrElse(PROFILES, Map.of());
		String key = r + ":" + t;
		Profile saved = stored.get(key);
		if (saved != null && saved.footings().size() == THICK + 6 && saved.baseY() >= level.getMinY()
			&& saved.baseY() + 11 < level.getMaxY()) return saved;
		int[] o = ringOffset(r, t);
		Direction in = inward(r, t);
		int base = level.getMinY();
		List<Integer> footings = new ArrayList<>();
		for (int kk = 0; kk < THICK + 6; kk++) {
			int x = bell.getX() + o[0] + in.getStepX() * kk, z = bell.getZ() + o[1] + in.getStepZ() * kk;
			if (!de.villagerplus.world.LoadedColumns.ready(level, new BlockPos(x, bell.getY(), z))) return null;
			int surface = groundHeight(level, x, z, wall);
			if (kk < THICK) base = Math.max(base, surface);
			int footing = supportingFloor(level, x, z, surface);
			if (footing == Integer.MIN_VALUE) return null;
			footings.add(footing);
		}
		if (base + 11 >= level.getMaxY()) return null;
		Profile result = new Profile(base, footings);
		if (owner != null) {
			Map<String, Profile> next = new HashMap<>(stored); next.put(key, result);
			owner.setAttached(PROFILES, Map.copyOf(next)); owner.setChanged();
		}
		return result;
	}

	/** First free block above firm ground; water columns receive real paid foundation blocks down to the bed. */
	private static int supportingFloor(ServerLevel level, int x, int z, int surface) {
		for (int y = surface - 1; y >= level.getMinY(); y--) {
			BlockPos p = new BlockPos(x, y, z);
			BlockState state = level.getBlockState(p);
			if (state.getFluidState().isEmpty() && state.isFaceSturdy(level, p, Direction.UP)) return y + 1;
		}
		return Integer.MIN_VALUE;
	}

	/** Block identity handles automatically updated fence connections while rejecting foreign replacement blocks. */
	public static boolean matches(Cell cell, BlockState actual) {
		if (cell.state().isAir()) return actual.isAir();
		if (!actual.is(cell.state().getBlock())) return false;
		if (cell.state().getBlock() instanceof StairBlock) return actual.getValue(StairBlock.FACING) == cell.state().getValue(StairBlock.FACING)
			&& actual.getValue(StairBlock.HALF) == cell.state().getValue(StairBlock.HALF);
		return true;
	}

	public static boolean mayReplace(Cell cell, BlockState actual) {
		if (matches(cell, actual) || actual.isAir() || actual.canBeReplaced() || !actual.getFluidState().isEmpty()) return true;
		return cell.state().isAir() && (de.villagerplus.village.VillageStyle.natural(actual)
			|| actual.is(net.minecraft.tags.BlockTags.LOGS) || actual.is(net.minecraft.tags.BlockTags.LEAVES));
	}

	/** All cells of a column, including underwater foundations; empty means unavailable terrain/chunks, never completion. */
	public static List<Cell> column(ServerLevel level, BlockPos bell, Block wall, Block stairs, Block fence, int r, int t) {
		List<Cell> out = new ArrayList<>();
		if (r <= 0 || t < 0 || t >= 8 * r) return out;
		int[] o = ringOffset(r, t);
		Direction in = inward(r, t);
		int bx = bell.getX() + o[0];
		int bz = bell.getZ() + o[1];
		if (!de.villagerplus.world.LoadedColumns.ready(level, new BlockPos(bx, bell.getY(), bz))) {
			return out;
		}
		for (int kk = 0; kk < THICK + 6; kk++) if (!de.villagerplus.world.LoadedColumns.ready(level, new BlockPos(bx + in.getStepX() * kk, bell.getY(), bz + in.getStepZ() * kk))) return out;
		Profile profile = profile(level, bell, wall, r, t);
		if (profile == null) return out;
		int hb = profile.baseY();
		int k = t % (2 * r);
		int gate = Math.abs(k - r);
		boolean opening = gate <= 1;
		boolean tower = gate == 2 || gate == 3 || k <= 1 || k >= 2 * r - 2;
		boolean stairCol = !tower && !opening && t % 48 == 24;
		BlockState w = wall.defaultBlockState();
		BlockState f = fence.defaultBlockState();
		for (int kk = 0; kk < THICK; kk++) {
			int x = bx + in.getStepX() * kk;
			int z = bz + in.getStepZ() * kk;
			if (!de.villagerplus.world.LoadedColumns.ready(level, new BlockPos(x, hb, z))) {
				continue;
			}
			int hk = profile.footings().get(kk);
			int from = Math.min(hk, hb);
			int top = tower ? 9 : 5;
			if (opening) {
				// Torgasse: 4 hoch frei, darueber der Sturz; der Wehrgang laeuft ueber das Tor.
				for (int dy = 0; dy <= 3; dy++) {
					out.add(new Cell(new BlockPos(x, hb + dy, z), Blocks.AIR.defaultBlockState(), false));
				}
				for (int dy = 4; dy <= 5; dy++) {
					out.add(new Cell(new BlockPos(x, hb + dy, z), w, true));
				}
			} else {
				for (int y = from; y <= hb + top; y++) {
					out.add(new Cell(new BlockPos(x, y, z), w, true));
				}
			}
			// Bruestung: aussen Zinnen (Vollreihe + jede zweite hoeher), innen Gelaender mit Laternen.
			int base = hb + top + 1;
			if (kk == 0) {
				out.add(new Cell(new BlockPos(x, base, z), w, true));
				if (t % 2 == 0) {
					out.add(new Cell(new BlockPos(x, base + 1, z), w, true));
				}
			} else if (tower) {
				if ((t + kk) % 2 == 0) {
					out.add(new Cell(new BlockPos(x, base, z), w, true));
				}
			} else if (kk == 2 && !stairCol) {
				out.add(new Cell(new BlockPos(x, base, z), f, false));
				if (t % 10 == 5) {
					out.add(new Cell(new BlockPos(x, base + 1, z), Blocks.LANTERN.defaultBlockState(), false));
				}
			}
		}
		if (stairCol) {
			// Treppe an der Innenseite: Stufe kk (3..8) liegt auf Hoehe 8-kk, Stufen zeigen (hoch) zur Mauer.
			for (int kk = THICK; kk <= THICK + 5; kk++) {
				int x = bx + in.getStepX() * kk;
				int z = bz + in.getStepZ() * kk;
				if (!de.villagerplus.world.LoadedColumns.ready(level, new BlockPos(x, hb, z))) {
					continue;
				}
				int stepY = hb + (THICK + 5 - kk);
				int hk = profile.footings().get(kk);
				for (int y = Math.min(hk, stepY); y < stepY; y++) {
					out.add(new Cell(new BlockPos(x, y, z), w, true));
				}
				BlockState st = stairs instanceof StairBlock ? stairs.defaultBlockState().setValue(StairBlock.FACING, in.getOpposite()) : w;
				out.add(new Cell(new BlockPos(x, stepY, z), st, false));
			}
		}
		return out;
	}
}
