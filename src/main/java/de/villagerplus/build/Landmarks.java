package de.villagerplus.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Dorfbauten ausser Wohnhaeusern im neuen Stil (Material aus der {@link Palette} des Dorfes): Brunnen mit Dach, Rathaus (grosses
 * Haus mit zwei Geschossen), Tiergehege, die fuenf Reichtums-Aufbauten. Wie beim {@link Architect} ergibt derselbe Seed denselben Bau.
 */
public final class Landmarks {
	private Landmarks() {
	}

	/** Bauwerke, die hier entworfen werden. */
	public static boolean handles(String type) {
		return type.equals(Blueprints.WELL) || type.equals(Blueprints.TOWNHALL) || type.equals(Blueprints.PEN) || type.equals(Blueprints.DECOR);
	}

	/** Halbe Kantenlaenge des Bauplatzes. */
	public static int half(String type) {
		return switch (type) {
			case Blueprints.WELL -> 2;
			case Blueprints.TOWNHALL -> Architect.hallDesign().half();
			case Blueprints.PEN -> 4;
			default -> 3;
		};
	}

	public static int headroom(String type) {
		return switch (type) {
			case Blueprints.WELL -> 6;
			case Blueprints.TOWNHALL -> Architect.hallDesign().headroom();
			case Blueprints.PEN -> 4;
			default -> 8;
		};
	}

	/** Stufe des Reichtums-Aufbaus steckt in den unteren 3 Bits des Seeds. */
	public static long decorSeed(int tier, long random) {
		return (random << 3) | (tier % 5);
	}

	public static List<Step> build(String type, long seed, Palette p, BlockPos c, Direction dir) {
		Map<BlockPos, Step> m = new LinkedHashMap<>();
		RandomSource r = RandomSource.create(seed * 7919 + 13);
		switch (type) {
			case Blueprints.WELL -> well(m, p, c, dir, r);
			case Blueprints.PEN -> pen(m, p, c, dir, r);
			case Blueprints.DECOR -> decor(m, p, c, dir, (int) (seed & 7), r);
			default -> {
				List<Step> hall = new ArrayList<>(Architect.build(Architect.hallDesign(), p, c, dir));
				for (Step s : hall) {
					m.put(s.pos().immutable(), s);
				}
				hallPlaza(m, p, c, dir);
			}
		}
		List<Step> steps = new ArrayList<>(m.values());
		steps.sort(java.util.Comparator.comparingInt(s -> s.pos().getY()));
		return steps;
	}

	// --- Helfer --------------------------------------------------------------------------------------

	private static BlockPos at(BlockPos c, Direction dir, int i, int j, int y) {
		return c.relative(dir.getClockWise(), i).relative(dir, j).above(y);
	}

	private static void put(Map<BlockPos, Step> m, Step s) {
		m.put(s.pos().immutable(), s);
	}

	private static void free(Map<BlockPos, Step> m, BlockPos pos, BlockState state) {
		put(m, Step.free(pos, state));
	}

	private static Block named(String id, Block fallback) {
		return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(fallback);
	}

	private static BlockState lantern(boolean hanging) {
		return Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, hanging);
	}

	private static Block fence(Palette p) {
		return p.fence() != null ? p.fence() : Blocks.OAK_FENCE;
	}

	private static Block trim(Palette p) {
		return p.frame() != null ? p.frame() : p.base();
	}

	private static void stair(Map<BlockPos, Step> m, Palette p, BlockPos pos, Direction facing, boolean upsideDown) {
		if (p.roofStair() instanceof StairBlock st) {
			put(m, Step.block(pos, st, b -> {
				BlockState s = b.defaultBlockState().setValue(StairBlock.FACING, facing);
				return upsideDown ? s.setValue(StairBlock.HALF, Half.TOP) : s;
			}));
		} else {
			put(m, Step.block(pos, p.roofFull()));
		}
	}

	private static void slab(Map<BlockPos, Step> m, Palette p, BlockPos pos, boolean top) {
		if (p.roofSlab() instanceof SlabBlock sl) {
			put(m, Step.block(pos, sl, b -> b.defaultBlockState().setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM)));
		} else {
			put(m, Step.block(pos, p.roofFull()));
		}
	}

	/** Pfosten (waagerechter oder senkrechter Stamm bleibt senkrecht) mit Laterne obenauf. */
	private static void lampPost(Map<BlockPos, Step> m, Palette p, BlockPos foot, int height) {
		for (int y = 0; y < height; y++) {
			put(m, Step.block(foot.above(y), fence(p)));
		}
		free(m, foot.above(height), lantern(false));
	}

	private static BlockState log(Block block, Direction.Axis axis) {
		BlockState s = block.defaultBlockState();
		return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, axis) : s;
	}

	private static void flowers(Map<BlockPos, Step> m, RandomSource r, BlockPos pos) {
		Block[] f = {Blocks.POPPY, Blocks.CORNFLOWER, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.ALLIUM};
		free(m, pos, f[r.nextInt(f.length)].defaultBlockState());
	}

	// --- Brunnen: 5x5, gemauerter Rand, Dach auf vier Pfosten, haengende Laterne ------------------------

	private static void well(Map<BlockPos, Step> m, Palette p, BlockPos c, Direction dir, RandomSource r) {
		for (int i = -2; i <= 2; i++) {
			for (int j = -2; j <= 2; j++) {
				boolean outer = Math.max(Math.abs(i), Math.abs(j)) == 2;
				put(m, Step.block(at(c, dir, i, j, -1), outer ? p.base() : p.floor()));
				for (int fy = -2; fy >= -3; fy--) {
					free(m, at(c, dir, i, j, fy), p.base().defaultBlockState());
				}
			}
		}
		// Brunnenrand um das Wasser.
		for (int i = -1; i <= 1; i++) {
			for (int j = -1; j <= 1; j++) {
				if (i != 0 || j != 0) {
					put(m, Step.block(at(c, dir, i, j, 0), p.base()));
				}
			}
		}
		free(m, c.below(), Blocks.WATER.defaultBlockState());
		// Pfosten und Walmdach.
		for (int sx = -2; sx <= 2; sx += 4) {
			for (int sz = -2; sz <= 2; sz += 4) {
				for (int y = 0; y < 3; y++) {
					put(m, Step.block(at(c, dir, sx, sz, y), y == 2 || p.frame() == null ? fence(p) : trim(p), b -> log(b, Direction.Axis.Y)));
				}
			}
		}
		for (int i = -2; i <= 2; i++) {
			for (int j = -2; j <= 2; j++) {
				if (Math.max(Math.abs(i), Math.abs(j)) == 2) {
					Direction in = Math.abs(i) >= Math.abs(j) ? (i > 0 ? dir.getClockWise().getOpposite() : dir.getClockWise()) : (j > 0 ? dir.getOpposite() : dir);
					stair(m, p, at(c, dir, i, j, 3), in, false);
				}
			}
		}
		for (int i = -1; i <= 1; i++) {
			for (int j = -1; j <= 1; j++) {
				if (Math.max(Math.abs(i), Math.abs(j)) == 1) {
					Direction in = Math.abs(i) >= Math.abs(j) ? (i > 0 ? dir.getClockWise().getOpposite() : dir.getClockWise()) : (j > 0 ? dir.getOpposite() : dir);
					stair(m, p, at(c, dir, i, j, 4), in, false);
				} else {
					put(m, Step.block(at(c, dir, i, j, 3), p.roofFull()));
				}
			}
		}
		put(m, Step.block(at(c, dir, 0, 0, 4), p.roofFull()));
		free(m, at(c, dir, 0, 0, 2), lantern(true));
		// Eimer (Kessel mit Wasser) und Blumen daneben.
		free(m, at(c, dir, 0, 3, 0), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
		for (int k = 0; k < 3; k++) {
			flowers(m, r, at(c, dir, r.nextInt(5) - 2, r.nextBoolean() ? 3 : -3, 0));
		}
	}

	// --- Rathaus ---------------------------------------------------------------------------------------

	/** Gepflasterter Vorplatz mit Laternenpfosten vor dem Rathaus. */
	private static void hallPlaza(Map<BlockPos, Step> m, Palette p, BlockPos c, Direction dir) {
		Architect.Design d = Architect.hallDesign();
		int front = d.hd() + 1;
		for (int i = -2; i <= 2; i++) {
			for (int j = front; j <= front + 3; j++) {
				free(m, at(c, dir, i, j, -1), p.floor().defaultBlockState());
			}
		}
		for (int side = -1; side <= 1; side += 2) {
			BlockPos lamp = at(c, dir, side * 3, front + 3, 0);
			put(m, Step.block(lamp, fence(p)));
			put(m, Step.block(lamp.above(), fence(p)));
			free(m, lamp.above(2), lantern(false));
		}
	}

	// --- Tiergehege: 9x9, Zaun mit Tor, Ecklaternen, Traenke, Heu, Schattendach -----------------------

	private static void pen(Map<BlockPos, Step> m, Palette p, BlockPos c, Direction dir, RandomSource r) {
		Block gate = Blueprints.gateFor(fence(p));
		BlockPos gatePos = at(c, dir, 0, 4, 0);
		for (int i = -4; i <= 4; i++) {
			for (int j = -4; j <= 4; j++) {
				if (Math.abs(i) != 4 && Math.abs(j) != 4) {
					continue;
				}
				BlockPos pos = at(c, dir, i, j, 0);
				boolean corner = Math.abs(i) == 4 && Math.abs(j) == 4;
				if (pos.equals(gatePos) && gate instanceof FenceGateBlock) {
					Direction facing = dir;
					put(m, Step.block(pos, gate, b -> b.defaultBlockState().setValue(FenceGateBlock.FACING, facing)));
				} else if (corner) {
					put(m, Step.block(pos, trim(p), b -> log(b, Direction.Axis.Y)));
					put(m, Step.block(pos.above(), trim(p), b -> log(b, Direction.Axis.Y)));
					free(m, pos.above(2), lantern(false));
				} else {
					put(m, Step.block(pos, fence(p)));
				}
			}
		}
		// Tor-Pfosten mit Laternen und Weg nach draussen.
		for (int side = -1; side <= 1; side += 2) {
			BlockPos post = at(c, dir, side, 4, 0);
			put(m, Step.block(post, trim(p), b -> log(b, Direction.Axis.Y)));
			put(m, Step.block(post.above(), trim(p), b -> log(b, Direction.Axis.Y)));
			free(m, post.above(2), lantern(false));
		}
		for (int j = 5; j <= 7; j++) {
			free(m, at(c, dir, 0, j, -1), Blocks.DIRT_PATH.defaultBlockState());
		}
		// Traenke und Heuballen in der hinteren Ecke.
		free(m, at(c, dir, -3, -3, 0), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
		free(m, at(c, dir, -2, -3, 0), Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
		free(m, at(c, dir, 3, -3, 0), Blocks.HAY_BLOCK.defaultBlockState());
		free(m, at(c, dir, 2, -3, 0), Blocks.HAY_BLOCK.defaultBlockState());
		for (int i = 0; i < 4; i++) {
			flowers(m, r, at(c, dir, r.nextInt(7) - 3, 5 + r.nextInt(2), 0));
		}
	}

	// --- Reichtums-Aufbauten -------------------------------------------------------------------------

	private static void decor(Map<BlockPos, Step> m, Palette p, BlockPos c, Direction dir, int tier, RandomSource r) {
		switch (tier % 5) {
			case 0 -> lanternWalk(m, p, c, dir, r);
			case 1 -> marketStall(m, p, c, dir, r);
			case 2 -> monument(m, p, c, dir, r);
			case 3 -> fountain(m, p, c, dir, r);
			default -> festivalGround(m, p, c, dir, r);
		}
	}

	/** Stufe 0: ein Laternenpfahl am kurzen Weg, zwei Baenke und Blumenbeete. */
	private static void lanternWalk(Map<BlockPos, Step> m, Palette p, BlockPos c, Direction dir, RandomSource r) {
		for (int i = -3; i <= 3; i++) {
			free(m, at(c, dir, i, 0, -1), Blocks.DIRT_PATH.defaultBlockState());
		}
		for (int side = -1; side <= 1; side += 2) {
			stair(m, p, at(c, dir, side * 2 + side, 1, 0), dir.getOpposite(), false);
			stair(m, p, at(c, dir, side * 2, 1, 0), dir.getOpposite(), false);
		}
		for (int k = 0; k < 6; k++) {
			BlockPos flower = at(c, dir, r.nextInt(7) - 3, r.nextBoolean() ? 2 : -2, 0);
			if (!flower.equals(at(c, dir, -3, -2, 0)) && !flower.equals(at(c, dir, 3, 2, 0))) {
				flowers(m, r, flower);
			}
		}
		// One lamp is enough for this short walk and avoids a tightly paired entrance.
		lampPost(m, p, at(c, dir, 0, -1, 0), 2);
	}

	/** Stufe 1: Marktstand mit Stoffdach aus Wolle, Tresen, Fass und Kisten. */
	private static void marketStall(Map<BlockPos, Step> m, Palette p, BlockPos c, Direction dir, RandomSource r) {
		String[] cols = {"red", "blue", "green", "yellow", "orange", "purple"};
		Block a = named("minecraft:" + cols[r.nextInt(cols.length)] + "_wool", Blocks.OAK_PLANKS);
		Block w = named("minecraft:white_wool", Blocks.OAK_PLANKS);
		for (int i = -2; i <= 2; i++) {
			for (int j = -1; j <= 1; j++) {
				put(m, Step.block(at(c, dir, i, j, -1), p.floor()));
			}
		}
		for (int sx = -2; sx <= 2; sx += 4) {
			for (int sz = -1; sz <= 1; sz += 2) {
				for (int y = 0; y < 3; y++) {
					put(m, Step.block(at(c, dir, sx, sz, y), fence(p)));
				}
			}
		}
		for (int i = -2; i <= 2; i++) {
			for (int j = -1; j <= 1; j++) {
				put(m, Step.block(at(c, dir, i, j, 3), Math.floorMod(i, 2) == 0 ? a : w));
			}
		}
		// Tresen an der Vorderseite, Ware dahinter.
		for (int i = -1; i <= 1; i++) {
			slab(m, p, at(c, dir, i, 1, 0), true);
		}
		free(m, at(c, dir, -1, -1, 0), Blocks.BARREL.defaultBlockState());
		free(m, at(c, dir, 0, -1, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
		free(m, at(c, dir, 1, -1, 0), Blocks.HAY_BLOCK.defaultBlockState());
		free(m, at(c, dir, 0, 0, 2), lantern(true));
	}

	/** Stufe 2: Denkmal auf gestufter Plattform, Saeule mit Goldspitze, Laternen und Blumen. */
	private static void monument(Map<BlockPos, Step> m, Palette p, BlockPos c, Direction dir, RandomSource r) {
		for (int i = -3; i <= 3; i++) {
			for (int j = -3; j <= 3; j++) {
				boolean ring = Math.max(Math.abs(i), Math.abs(j)) == 3;
				put(m, Step.block(at(c, dir, i, j, -1), ring ? p.base() : p.floor()));
			}
		}
		for (int i = -2; i <= 2; i++) {
			for (int j = -2; j <= 2; j++) {
				if (Math.max(Math.abs(i), Math.abs(j)) == 2) {
					Direction out = Math.abs(i) >= Math.abs(j) ? (i > 0 ? dir.getClockWise() : dir.getClockWise().getOpposite()) : (j > 0 ? dir : dir.getOpposite());
					stair(m, p, at(c, dir, i, j, 0), out.getOpposite(), false);
				} else {
					put(m, Step.block(at(c, dir, i, j, 0), p.wall2()));
				}
			}
		}
		for (int y = 1; y <= 5; y++) {
			Block pillar = y == 1 || y == 5 ? trim(p) : p.wall();
			put(m, Step.block(at(c, dir, 0, 0, y), pillar, b -> log(b, Direction.Axis.Y)));
		}
		put(m, Step.block(at(c, dir, 0, 0, 6), p.base()));
		free(m, at(c, dir, 0, 0, 7), Blocks.GOLD_BLOCK.defaultBlockState());
		for (int sx = -2; sx <= 2; sx += 4) {
			lampPost(m, p, at(c, dir, sx, -3, 0), 2);
			lampPost(m, p, at(c, dir, sx, 3, 0), 2);
		}
		for (int k = 0; k < 5; k++) {
			flowers(m, r, at(c, dir, r.nextBoolean() ? 3 : -3, r.nextInt(5) - 2, 1));
		}
	}

	/** Stufe 3: Springbrunnen - Becken mit Rand und Treppenstufen, Mittelsaeule, Laterne. */
	private static void fountain(Map<BlockPos, Step> m, Palette p, BlockPos c, Direction dir, RandomSource r) {
		for (int i = -3; i <= 3; i++) {
			for (int j = -3; j <= 3; j++) {
				int dist = Math.max(Math.abs(i), Math.abs(j));
				put(m, Step.block(at(c, dir, i, j, -1), dist == 3 ? p.floor() : p.base()));
			}
		}
		for (int i = -2; i <= 2; i++) {
			for (int j = -2; j <= 2; j++) {
				if (Math.max(Math.abs(i), Math.abs(j)) == 2) {
					Direction out = Math.abs(i) >= Math.abs(j) ? (i > 0 ? dir.getClockWise() : dir.getClockWise().getOpposite()) : (j > 0 ? dir : dir.getOpposite());
					stair(m, p, at(c, dir, i, j, 0), out.getOpposite(), false);
				} else if (i != 0 || j != 0) {
					free(m, at(c, dir, i, j, -1), Blocks.WATER.defaultBlockState());
				}
			}
		}
		put(m, Step.block(at(c, dir, 0, 0, 0), p.base()));
		put(m, Step.block(at(c, dir, 0, 0, 1), trim(p), b -> log(b, Direction.Axis.Y)));
		put(m, Step.block(at(c, dir, 0, 0, 2), p.base()));
		for (int k = 0; k < 4; k++) {
			Direction d2 = Direction.from2DDataValue(k);
			stair(m, p, at(c, dir, 0, 0, 3).relative(d2), d2.getOpposite(), true);
		}
		free(m, at(c, dir, 0, 0, 3), lantern(false));
		for (int k = 0; k < 4; k++) {
			flowers(m, r, at(c, dir, r.nextBoolean() ? 3 : -3, r.nextInt(5) - 2, 0));
		}
	}

	/** Stufe 4: Festplatz - Pflaster im Muster, Maibaum mit bunten Baendern, Baenke, Laternenpfosten, Blumenkuebel. */
	private static void festivalGround(Map<BlockPos, Step> m, Palette p, BlockPos c, Direction dir, RandomSource r) {
		for (int i = -3; i <= 3; i++) {
			for (int j = -3; j <= 3; j++) {
				put(m, Step.block(at(c, dir, i, j, -1), (i + j) % 2 == 0 ? p.floor() : p.base()));
			}
		}
		for (int y = 0; y < 6; y++) {
			put(m, Step.block(at(c, dir, 0, 0, y), trim(p), b -> log(b, Direction.Axis.Y)));
		}
		String[] cols = {"red", "yellow", "blue", "green"};
		for (int k = 0; k < 4; k++) {
			Direction d2 = Direction.from2DDataValue(k);
			Block wool = named("minecraft:" + cols[k] + "_wool", Blocks.OAK_PLANKS);
			for (int dist = 1; dist <= 2; dist++) {
				put(m, Step.block(at(c, dir, 0, 0, 5 - dist + 1).relative(d2, dist), wool));
			}
		}
		for (int sx = -3; sx <= 3; sx += 6) {
			for (int sz = -3; sz <= 3; sz += 6) {
				lampPost(m, p, at(c, dir, sx, sz, 0), 2);
			}
		}
		for (int side = -1; side <= 1; side += 2) {
			stair(m, p, at(c, dir, side * 2, 3, 0), dir.getOpposite(), false);
			stair(m, p, at(c, dir, side * 2, -3, 0), dir, false);
			stair(m, p, at(c, dir, 3, side * 2, 0), dir.getClockWise().getOpposite(), false);
			stair(m, p, at(c, dir, -3, side * 2, 0), dir.getClockWise(), false);
		}
		for (int i = -2; i <= 2; i += 4) {
			free(m, at(c, dir, i, 0, 0), Blocks.POTTED_RED_TULIP.defaultBlockState());
			free(m, at(c, dir, 0, i, 0), Blocks.POTTED_CORNFLOWER.defaultBlockState());
		}
		for (int k = 0; k < 4; k++) {
			flowers(m, r, at(c, dir, r.nextInt(3) - 1, r.nextInt(3) - 1, 0));
		}
	}
}
