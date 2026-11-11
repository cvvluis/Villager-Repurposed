package de.villagerplus.job;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Surface quarry for plan stone: a resident with a pickaxe chips away natural stone that is exposed to open sky air
 * (outcrops and cliff faces), so no timber-supported mine is needed for the first cobblestone of a small village.
 */
public final class SurfaceStone {
	private SurfaceStone() { }

	private static final int RADIUS = 64;
	private static final int DEPTH = 8;
	private static final long FIND_INTERVAL_TICKS = 600L;
	private static final Map<UUID, BlockPos> TARGETS = new HashMap<>();
	private static final Map<UUID, Long> NEXT_FIND = new HashMap<>();
	private static final Map<UUID, Long> NEXT_LOG = new HashMap<>();
	/** Quarry cells whose stand spot no walker could reach (pits, funnels): not chosen again this session. */
	private static final java.util.Set<BlockPos> UNREACHABLE = new java.util.HashSet<>();

	/** true = busy quarrying (walking to or breaking a stone). Uses a carried pickaxe if there is one, otherwise works slowly by hand. */
	public static boolean tick(Ctx c) {
		if (c.village() == null) return false;
		// Without any pickaxe (none stored, none craftable) stone is chipped out by hand, much more slowly.
		boolean hasPickaxe = !Mining.pickaxe(c.v().getInventory()).isEmpty();
		ServerLevel level = c.level();
		UUID id = c.v().getUUID();
		BlockPos target = TARGETS.get(id);
		BlockPos stand = target == null ? null : standFor(level, target);
		if (target != null && stand == null) {
			TARGETS.remove(id);
			target = null;
		}
		if (target == null) {
			if (c.now() < NEXT_FIND.getOrDefault(id, 0L)) return false;
			target = find(c);
			if (target == null) {
				NEXT_FIND.put(id, c.now() + FIND_INTERVAL_TICKS);
				if (c.now() >= NEXT_LOG.getOrDefault(id, 0L)) {
					NEXT_LOG.put(id, c.now() + 2400L);
					org.slf4j.LoggerFactory.getLogger("villagerplus").info("Steinbruch {}: kein freiliegender Naturstein im Umkreis von {} Bloecken bei {}",
						c.mem().name, RADIUS, c.v().blockPosition().toShortString());
				}
				return false;
			}
			if (TARGETS.size() > 128) TARGETS.clear();
			TARGETS.put(id, target);
			stand = standFor(level, target);
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("Steinbruch {}: Ziel {} (Standplatz {})", c.mem().name,
				target.toShortString(), stand == null ? "-" : stand.toShortString());
			if (stand == null) return false;
			var route = de.villagerplus.build.Builder.workPath(c, stand);
			boolean reachable = route != null && route.canReach();
			if (!reachable) {
				org.slf4j.LoggerFactory.getLogger("villagerplus").info("Steinbruch {}: Ziel {} verworfen (Weg zum Standplatz {} nicht begehbar, Arbeiter bei {})",
					c.mem().name, target.toShortString(), stand.toShortString(), c.v().blockPosition().toShortString());
			}
			if (reachable) {
				// The path above only proves the way TO the stand, and vanilla routes happily drop down a ledge.
				// A stand below a cliff or in a pit would then strand the quarryman (Langweg: unreachable, 1 node).
				BlockPos home = de.villagerplus.build.Builder.villageReturn(level, c.village().bell());
				if (home != null && !de.villagerplus.village.Sites.walksBackTo(level, stand, home)) {
					reachable = false;
					org.slf4j.LoggerFactory.getLogger("villagerplus").info("Steinbruch {}: Ziel {} verworfen (kein Rueckweg vom Standplatz {} zur Dorfstrasse {})",
						c.mem().name, target.toShortString(), stand.toShortString(), home.toShortString());
				}
			}
			if (!reachable) {
				if (UNREACHABLE.size() > 512) UNREACHABLE.clear();
				UNREACHABLE.add(target);
				TARGETS.remove(id);
				NEXT_FIND.put(id, c.now() + 200L);
				return false;
			}
		}
		c.setActivity("bricht Stein für den Bauplan");
		Anim.hold(c, hasPickaxe ? Items.IRON_PICKAXE : Items.AIR);
		if (!JobUtil.approachLong(c, stand, 2.8)) return true;
		level.destroyBlock(target, false);
		JobUtil.give(level, c.v(), new ItemStack(Items.COBBLESTONE));
		level.playSound(null, target, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + (hasPickaxe ? 24 : 160);
		TARGETS.remove(id);
		return true;
	}

	/** Natural rock of the region: ordinary stone, plus terracotta and sandstone where the land is made of them. */
	private static boolean localRock(net.minecraft.world.level.block.state.BlockState state) {
		return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA)
			|| state.is(net.minecraft.world.level.block.Blocks.SANDSTONE) || state.is(net.minecraft.world.level.block.Blocks.RED_SANDSTONE);
	}

	/**
	 * A natural stone block with a walkable, sky-lit cell beside it (solid floor, two free blocks), or null.
	 * The returned cell is where the quarryman stands.
	 */
	private static BlockPos standFor(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos) || !localRock(level.getBlockState(pos))) return null;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos stand = pos.relative(side);
			if (!level.hasChunkAt(stand)) continue;
			if (level.getBlockState(stand).isAir() && level.getBlockState(stand.above()).isAir() && level.canSeeSky(stand)
				&& level.getBlockState(stand.below()).isFaceSturdy(level, stand.below(), Direction.UP)) return stand;
		}
		return null;
	}

	/**
	 * True when something man-made is within two blocks of the candidate: village walls, a player's building, doors, glass,
	 * planks, light sources, containers. Only genuinely natural surroundings may be quarried.
	 */
	private static boolean nearBuilt(ServerLevel level, BlockPos pos) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
					if (!level.hasChunkAt(p)) continue;
					net.minecraft.world.level.block.state.BlockState state = level.getBlockState(p);
					if (state.isAir() || !state.getFluidState().isEmpty()) continue;
					if (state.hasBlockEntity() || isManMade(state)) return true;
				}
			}
		}
		return false;
	}

	private static boolean isManMade(net.minecraft.world.level.block.state.BlockState state) {
		net.minecraft.world.level.block.Block b = state.getBlock();
		return state.is(BlockTags.PLANKS) || state.is(BlockTags.DOORS) || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS)
			|| state.is(BlockTags.WALLS) || state.is(BlockTags.FENCES) || state.is(BlockTags.BEDS) || state.is(BlockTags.WOOL)
			|| state.is(BlockTags.WOOL_CARPETS) || state.is(BlockTags.IMPERMEABLE)
			|| (state.is(BlockTags.LOGS) && !state.is(BlockTags.OVERWORLD_NATURAL_LOGS))
			|| b == net.minecraft.world.level.block.Blocks.COBBLESTONE || b == net.minecraft.world.level.block.Blocks.STONE_BRICKS
			|| b == net.minecraft.world.level.block.Blocks.TORCH || b == net.minecraft.world.level.block.Blocks.WALL_TORCH
			|| b == net.minecraft.world.level.block.Blocks.LANTERN || b == net.minecraft.world.level.block.Blocks.CUT_SANDSTONE
			|| b == net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE || b == net.minecraft.world.level.block.Blocks.CHISELED_SANDSTONE
			|| b == net.minecraft.world.level.block.Blocks.CUT_RED_SANDSTONE || b == net.minecraft.world.level.block.Blocks.SMOOTH_RED_SANDSTONE;
	}

	private static BlockPos find(Ctx c) {
		// Dev aid: a flag file next to the server properties switches the quarry off to exercise the mine fallback.
		if (DevFlags.has("vp-noquarry.flag")) return null;
		ServerLevel level = c.level();
		BlockPos bell = c.village().bell();
		// Search around the village centre (not around the walker), otherwise the quarry creeps ever farther away.
		BlockPos center = bell;
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		BlockPos best = null;
		long bestDistance = Long.MAX_VALUE;
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				long flat = (long) dx * dx + (long) dz * dz;
				if (flat >= bestDistance) continue;
				int x = center.getX() + dx, z = center.getZ() + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				// The village bounds are large (they cover the whole search area); only keep clear of the bell itself.
				if (Math.abs(x - bell.getX()) <= 8 && Math.abs(z - bell.getZ()) <= 8) continue;
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				for (int dy = 0; dy <= DEPTH; dy++) {
					int y = top - dy;
					if (Math.abs(y - center.getY()) > 24) continue;
						// A quarry far below the village is a pit the walker cannot climb out of again (unreachable route home).
						if (y < bell.getY() - 4 || y > bell.getY() + 14) continue;
					probe.set(x, y, z);
					if (standFor(level, probe) == null || claimed(probe) || UNREACHABLE.contains(probe) || nearBuilt(level, probe)) continue;
					long distance = flat + (long) dy * dy;
					if (distance < bestDistance) {
						best = probe.immutable();
						bestDistance = distance;
					}
					break;
				}
			}
		}
		return best;
	}

	private static boolean claimed(BlockPos pos) {
		for (BlockPos other : TARGETS.values()) if (other.equals(pos)) return true;
		return false;
	}
}
