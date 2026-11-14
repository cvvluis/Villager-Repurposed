package de.villagerplus.build;

import de.villagerplus.job.JobUtil;
import net.minecraft.world.item.Items;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;

/** Chooses the largest real crop plot the village can fully seed before a farm plan is queued. */
public final class FarmPlanSizing {
	private FarmPlanSizing() { }

	public static int chooseVariant(int projectedFarms, Container publicStock) {
		if (projectedFarms < 0 || publicStock == null) return -1;
		if (projectedFarms == 0) {
			int wheatSeeds = JobUtil.count(publicStock, stack -> stack.is(Items.WHEAT_SEEDS));
			if (wheatSeeds >= 24) return 20;
			return wheatSeeds >= 8 ? 10 : -1;
		}
		// Later plots grow with population and complete seed payment, up to 11x11.
		int preferredHalf = 3 + Math.min(2, projectedFarms / 2);
		for (int half = preferredHalf; half >= 1; half--) {
			int waterCells = half <= 3 ? 1 : 4;
			int requiredSeeds = (half * 2 + 1) * (half * 2 + 1) - waterCells;
			for (int kind = 0; kind < Blueprints.seedKinds(); kind++) {
				Item seed = Blueprints.seed(kind);
				if (JobUtil.count(publicStock, stack -> stack.is(seed)) >= requiredSeeds) {
					return kind + 10 * half;
				}
			}
		}
		return -1;
	}
}
