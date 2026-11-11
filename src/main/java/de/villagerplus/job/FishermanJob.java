package de.villagerplus.job;

import de.villagerplus.Config;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Fischer: sucht Wasser in der Naehe seines Arbeitsplatzes, angelt dort in festen Abstaenden und liefert Fische ab. */
public class FishermanJob implements Job {
	@Override
	public Predicate<ItemStack> produce() {
		return s -> s.is(ItemTags.FISHES);
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) {
			return false;
		}
		if (JobUtil.depositSurplus(c, produce(), 4, 0)) {
			return true;
		}
		JobState st = c.st();
		BlockPos water = st.target;
		if (water == null || !c.level().getFluidState(water).is(FluidTags.WATER)) {
			st.clearTarget();
			water = findWater(c);
			if (water == null) {
				c.setIdle(0, 600);
				return false;
			}
			st.target = water;
		}
		c.setActivity("angelt am Wasser");
		Anim.hold(c, Items.FISHING_ROD);
		if (!JobUtil.approach(c, water, 4.0)) {
			return true;
		}
		// Bis zum naechsten Fang steht der Fischer einfach da und angelt.
		JobUtil.swing(c.v());
		c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.FISHING, water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5, 2, 0.25, 0.0, 0.25, 0.02);
		if (c.v().getRandom().nextInt(Math.max(1, (int) (Config.FISH_INTERVAL / Config.JOB_INTERVAL / Skills.speed(c.mem())))) == 0) {
			ItemStack catchItem = roll(c);
			JobUtil.give(c.level(), c.v(), catchItem);
			c.level().playSound(null, water, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.6F, 1.0F);
			c.mem().jobsDone++;
		}
		return true;
	}

	@Override
	public boolean secondary(Ctx c) {
		return primary(c);
	}

	private static ItemStack roll(Ctx c) {
		int r = c.v().getRandom().nextInt(100);
		if (r < 55) {
			return new ItemStack(Items.COD);
		} else if (r < 85) {
			return new ItemStack(Items.SALMON);
		} else if (r < 95) {
			return new ItemStack(Items.TROPICAL_FISH);
		}
		return new ItemStack(Items.PUFFERFISH);
	}

	private static BlockPos findWater(Ctx c) {
		BlockPos center = c.v().blockPosition();
		GlobalPos job = c.v().getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
		if (job != null && job.dimension() == c.level().dimension()) {
			center = job.pos();
		}
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		int r = Config.FISH_RADIUS;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -3, -r), center.offset(r, 2, r))) {
			if (c.level().getFluidState(p).is(FluidTags.WATER) && c.level().getBlockState(p.above()).isAir()) {
				double d = p.distSqr(center);
				if (d < bestDist) {
					best = p.immutable();
					bestDist = d;
				}
			}
		}
		if (best == null && c.village() != null) best = findWideWater(c);
		return best;
	}

	/** Kein Wasser am Arbeitsplatz: naechste Wasserflaeche im weiteren Dorfumland (Oberflaeche, nahe am Ufer, ohne Hoehensprung). */
	private static BlockPos findWideWater(Ctx c) {
		BlockPos bell = c.village().bell();
		BlockPos here = c.v().blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		int r = 56;
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx += 2) {
			for (int dz = -r; dz <= r; dz += 2) {
				int x = bell.getX() + dx, z = bell.getZ() + dz;
				if (!c.level().hasChunk(x >> 4, z >> 4)) continue;
				int top = c.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
				if (Math.abs(top - bell.getY()) > 14) continue;
				probe.set(x, top, z);
				if (!c.level().getFluidState(probe).is(FluidTags.WATER) || !c.level().getBlockState(probe.above()).isAir() || UNREACHABLE_WATER.contains(probe)) continue;
				// Nearest to the village centre, not to the walker: otherwise the fishing spot creeps away from home with every trip.
				double d = probe.distSqr(bell);
				if (d < bestDist) {
					best = probe.immutable();
					bestDist = d;
				}
			}
		}
		if (best != null) {
			// Water behind cliffs or on an island would lock the fisherman on a target he can never reach: check the walk once.
			var route = de.villagerplus.build.Builder.workPath(c, best.above());
			if (route == null || !route.canReach()) {
				if (UNREACHABLE_WATER.size() > 256) UNREACHABLE_WATER.clear();
				UNREACHABLE_WATER.add(best);
				return null;
			}
		}
		return best;
	}

	/** Wide-water spots no walker could reach this session; skipped when searching again. */
	private static final java.util.Set<BlockPos> UNREACHABLE_WATER = new java.util.HashSet<>();
}
