package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.build.Builder;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Maurer: baut das Dorf aus (Haeuser nach dem Vorbild der Nachbarhaeuser, Brunnen, Wege), stellt Materialien her
 * (Steinmetzarbeiten) und baut Stein in der Dorfmine WIRKLICH ab. Vormittags zuerst Bauen, nachmittags zuerst Mine.
 */
public class MasonJob implements Job {
	@Override
	public Predicate<ItemStack> produce() {
		return s -> !Meals.isFood(s);
	}

	@Override
	public boolean primary(Ctx c) {
		return GravelQuarry.work(c) || build(c) || workshop(c) || mine(c) || placeTorch(c);
	}

	@Override
	public boolean secondary(Ctx c) {
		if (GravelQuarry.work(c)) {
			return true;
		}
		if (c.village() != null && c.village().data().hasPlan() && build(c)) {
			return true;
		}
		return RoadBuilderJob.work(c) || mine(c) || workshop(c) || build(c) || placeTorch(c);
	}

	private static boolean build(Ctx c) {
		return Builder.tick(c, true);
	}

	private static boolean workshop(Ctx c) {
		return Workshop.tick(c);
	}

	private boolean mine(Ctx c) {
		if (c.village() == null) {
			return false;
		}
		// Volles Inventar vorher zur Dorfkiste bringen.
		if (JobUtil.depositSurplus(c, produce(), 64, 0)) {
			return true;
		}
		return Mining.tick(c, Mining.Need.STONE);
	}

	private static boolean placeTorch(Ctx c) {
		JobState st = c.st();
		if (c.now() < st.lightAt) {
			return false;
		}
		st.lightAt = c.now() + Config.TORCH_COOLDOWN;
		Container box = JobUtil.box(c);
		if (box == null || JobUtil.count(box, s -> s.is(Items.TORCH)) == 0) {
			return false;
		}
		int x = c.v().getBlockX() + c.v().getRandom().nextInt(29) - 14;
		int z = c.v().getBlockZ() + c.v().getRandom().nextInt(29) - 14;
		if (!c.level().hasChunkAt(new BlockPos(x, c.v().getBlockY(), z))) {
			return false;
		}
		BlockPos p = new BlockPos(x, c.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
		BlockPos below = p.below();
		if (c.level().getBrightness(LightLayer.BLOCK, p) != 0 || !c.level().getBlockState(p).isAir()
			|| !c.level().getBlockState(below).isFaceSturdy(c.level(), below, Direction.UP)
			|| !c.level().getBlockState(below).getFluidState().isEmpty()) {
			return false;
		}
		if (JobUtil.take(box, s -> s.is(Items.TORCH), 1) == 1) {
			c.level().setBlock(p, Blocks.TORCH.defaultBlockState(), 3);
			Anim.hold(c, Items.TORCH);
			JobUtil.swing(c.v());
			c.setActivity("stellt Fackeln auf");
		}
		return false;
	}
}
