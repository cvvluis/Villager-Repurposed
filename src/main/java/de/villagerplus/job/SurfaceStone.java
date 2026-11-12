package de.villagerplus.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
	/** Wait after a round in which every checked candidate was rejected (the rejected region is already remembered). */
	private static final long REJECT_RETRY_TICKS = 40L;
	/** Nearest candidates kept per search, and how many of them one tick may check (each check is a path plus a walk-back search). */
	private static final int CANDIDATES = 4;
	private static final int CHECKS_PER_TICK = 3;
	/** A rejected cell condemns its surroundings: a gorge floor is all unreachable, not just the one block that was tried. */
	private static final int REJECT_RADIUS = 10;
	private static final long REJECT_WALKBACK_TICKS = 24_000L;
	private static final long REJECT_PATH_TICKS = 3_000L;
	/** By hand a block takes long; with a pickaxe it is quick. */
	static final long HAND_COOLDOWN_TICKS = 100L;
	static final long PICKAXE_COOLDOWN_TICKS = 24L;
	private static final Map<UUID, BlockPos> TARGETS = new HashMap<>();
	private static final Map<UUID, Long> NEXT_FIND = new HashMap<>();
	private static final Map<UUID, Long> NEXT_LOG = new HashMap<>();
	/** Quarry cells whose stand spot no walker could reach (pits, funnels): not chosen again this session. */
	private static final java.util.Set<BlockPos> UNREACHABLE = new java.util.HashSet<>();
	/** Whole regions (centre, until-tick) whose cells could not be walked to or back from; see {@link #rejectRegion}. */
	private static final List<Region> REJECTED = new ArrayList<>();

	private record Region(BlockPos center, long until) { }
	private record Candidate(BlockPos pos, long score) { }

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
			List<BlockPos> candidates = find(c);
			if (candidates.isEmpty()) {
				NEXT_FIND.put(id, c.now() + FIND_INTERVAL_TICKS);
				if (c.now() >= NEXT_LOG.getOrDefault(id, 0L)) {
					NEXT_LOG.put(id, c.now() + 2400L);
					org.slf4j.LoggerFactory.getLogger("villagerplus").info("Steinbruch {}: kein freiliegender Naturstein im Umkreis von {} Bloecken bei {}",
						c.mem().name, RADIUS, c.v().blockPosition().toShortString());
				}
				return false;
			}
			int checks = 0;
			for (BlockPos candidate : candidates) {
				// An earlier rejection in this very round may already condemn this one: no path search for it.
				if (inRejectedRegion(candidate, c.now())) continue;
				if (checks++ >= CHECKS_PER_TICK) break;
				BlockPos candidateStand = acceptable(c, candidate);
				if (candidateStand == null) continue;
				target = candidate;
				stand = candidateStand;
				break;
			}
			if (target == null) {
				NEXT_FIND.put(id, c.now() + REJECT_RETRY_TICKS);
				return false;
			}
			if (TARGETS.size() > 128) TARGETS.clear();
			TARGETS.put(id, target);
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("Steinbruch {}: Ziel {} (Standplatz {})", c.mem().name,
				target.toShortString(), stand.toShortString());
		}
		c.setActivity("bricht Stein für den Bauplan");
		Anim.hold(c, hasPickaxe ? Items.IRON_PICKAXE : Items.AIR);
		if (!JobUtil.approachLong(c, stand, 2.8)) return true;
		level.destroyBlock(target, false);
		JobUtil.give(level, c.v(), new ItemStack(Items.COBBLESTONE));
		level.playSound(null, target, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + (hasPickaxe ? PICKAXE_COOLDOWN_TICKS : HAND_COOLDOWN_TICKS);
		TARGETS.remove(id);
		return true;
	}

	/**
	 * Stand cell of a candidate once it passed both walking checks (way there from the worker, way home from the stand), else null.
	 * A failure is remembered for the whole surrounding region so the next search does not offer its neighbours one by one.
	 */
	private static BlockPos acceptable(Ctx c, BlockPos target) {
		ServerLevel level = c.level();
		BlockPos stand = standFor(level, target);
		if (stand == null) return null;
		var route = de.villagerplus.build.Builder.workPath(c, stand);
		if (route == null || !route.canReach()) {
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("Steinbruch {}: Ziel {} verworfen (Weg zum Standplatz {} nicht begehbar, Arbeiter bei {})",
				c.mem().name, target.toShortString(), stand.toShortString(), c.v().blockPosition().toShortString());
			rejectRegion(target, c.now(), REJECT_PATH_TICKS);
			return null;
		}
		// The path above only proves the way TO the stand, and vanilla routes happily drop down a ledge.
		// A stand below a cliff or in a pit would then strand the quarryman (Langweg: unreachable, 1 node).
		BlockPos home = de.villagerplus.build.Builder.villageReturn(level, c.village().bell());
		if (home != null && !de.villagerplus.village.Sites.walksBackTo(level, stand, home)) {
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("Steinbruch {}: Ziel {} verworfen (kein Rueckweg vom Standplatz {} zur Dorfstrasse {})",
				c.mem().name, target.toShortString(), stand.toShortString(), home.toShortString());
			rejectRegion(target, c.now(), REJECT_WALKBACK_TICKS);
			return null;
		}
		return stand;
	}

	private static void rejectRegion(BlockPos target, long now, long ticks) {
		// A failed way home is a property of the terrain (kept for the session); a failed way there depends on where the worker stood.
		if (ticks >= REJECT_WALKBACK_TICKS) {
			if (UNREACHABLE.size() > 512) UNREACHABLE.clear();
			UNREACHABLE.add(target.immutable());
		}
		REJECTED.removeIf(r -> r.until() <= now);
		if (REJECTED.size() > 64) REJECTED.remove(0);
		REJECTED.add(new Region(target.immutable(), now + ticks));
	}

	/** True when {@code pos} lies inside a region whose rejection has not expired at game time {@code now}. */
	static boolean inRejectedRegion(BlockPos pos, long now) {
		for (Region r : REJECTED) {
			if (r.until() <= now) continue;
			long dx = pos.getX() - r.center().getX(), dz = pos.getZ() - r.center().getZ();
			if (dx * dx + dz * dz <= (long) REJECT_RADIUS * REJECT_RADIUS && Math.abs(pos.getY() - r.center().getY()) <= 6) return true;
		}
		return false;
	}

	/** Test hook: forget everything remembered between ticks. */
	static void resetMemory() {
		TARGETS.clear();
		NEXT_FIND.clear();
		NEXT_LOG.clear();
		UNREACHABLE.clear();
		REJECTED.clear();
	}

	/** Test hook: remember a rejected region exactly as a failed walk-back check does. */
	static void rejectForTest(BlockPos target, long now, long ticks) {
		rejectRegion(target, now, ticks);
	}

	/** Test hook: the quarry cell a worker is currently walking to, or null. */
	static BlockPos targetFor(UUID id) {
		return TARGETS.get(id);
	}

	/** Test hook: the nearest candidates a search would offer this worker now, best first. */
	static List<BlockPos> candidatesForTest(Ctx c) {
		return find(c);
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
	static BlockPos standFor(ServerLevel level, BlockPos pos) {
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

	/**
	 * The few candidates nearest to the worker, best first, always inside the village's quarry area around the bell.
	 * Walking dominates the time of a stone (a resident covers only a few blocks per second), so what counts is the way from
	 * where the worker stands now: the first stone is the closest one, and the next one sits beside the last, on the same face.
	 */
	private static List<BlockPos> find(Ctx c) {
		// Dev aid: a flag file next to the server properties switches the quarry off to exercise the mine fallback.
		if (DevFlags.has("vp-noquarry.flag")) return List.of();
		return scan(c, c.v().blockPosition(), RADIUS);
	}

	private static List<BlockPos> scan(Ctx c, BlockPos anchor, int radius) {
		ServerLevel level = c.level();
		BlockPos bell = c.village().bell();
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		List<Candidate> best = new ArrayList<>(CANDIDATES + 1);
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				long flat = (long) dx * dx + (long) dz * dz;
				if (best.size() >= CANDIDATES && flat >= best.get(best.size() - 1).score()) continue;
				int x = anchor.getX() + dx, z = anchor.getZ() + dz;
				// Never leave the village's quarry area, whichever point the search is anchored at.
				if (Math.abs(x - bell.getX()) > RADIUS || Math.abs(z - bell.getZ()) > RADIUS) continue;
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				// The village bounds are large (they cover the whole search area); only keep clear of the bell itself.
				if (Math.abs(x - bell.getX()) <= 8 && Math.abs(z - bell.getZ()) <= 8) continue;
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				for (int dy = 0; dy <= DEPTH; dy++) {
					int y = top - dy;
					if (Math.abs(y - bell.getY()) > 24) continue;
					// A quarry far below the village is a pit the walker cannot climb out of again (unreachable route home).
					if (y < bell.getY() - 4 || y > bell.getY() + 14) continue;
					probe.set(x, y, z);
					if (standFor(level, probe) == null) continue;
					if (claimed(probe) || UNREACHABLE.contains(probe) || inRejectedRegion(probe, c.now()) || nearBuilt(level, probe)) continue;
					long score = flat + (long) dy * dy;
					int at = best.size();
					while (at > 0 && best.get(at - 1).score() > score) at--;
					best.add(at, new Candidate(probe.immutable(), score));
					if (best.size() > CANDIDATES) best.remove(best.size() - 1);
					break;
				}
			}
		}
		List<BlockPos> result = new ArrayList<>(best.size());
		for (Candidate candidate : best) result.add(candidate.pos());
		return result;
	}

	private static boolean claimed(BlockPos pos) {
		for (BlockPos other : TARGETS.values()) if (other.equals(pos)) return true;
		return false;
	}
}
