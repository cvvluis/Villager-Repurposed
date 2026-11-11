package de.villagerplus.job;

import de.villagerplus.Config;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Traeger (Arbeitslose und Dorftrottel): raeumen auf. Sie sammeln Items ein, die schon lange herumliegen, und
 * bringen sie zur Dorfkiste.
 */
public class PorterJob implements Job {
	@Override
	public Predicate<ItemStack> produce() {
		return s -> !BuildHaul.isMarkedCargo(s)
			&& !net.minecraft.world.entity.npc.villager.Villager.FOOD_POINTS.containsKey(s.getItem());
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) {
			de.villagerplus.village.Caravan.releasePorter(c.v());
			return false;
		}
		if (BuildHaul.returnOrphanedCargo(c)) return true;
		if (de.villagerplus.village.Caravan.porterDelivery(c)) return true;
		if (FreightLoading.work(c)) return true;
		if (JobUtil.depositSurplus(c, produce(), 16, 0)) {
			return true;
		}
		JobState st = c.st();
		ItemEntity item = st.targetEntity instanceof ItemEntity e && e.isAlive() ? e : null;
		if (item == null) {
			st.clearTarget();
			AABB box = c.v().getBoundingBox().inflate(Config.PORTER_RADIUS);
			item = c.level().getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && e.getAge() > Config.PORTER_MIN_ITEM_AGE)
				.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(c.v()))).orElse(null);
			if (item == null) {
				c.setIdle(0, 600);
				return false;
			}
			st.targetEntity = item;
		}
		c.setActivity("räumt auf");
		if (!JobUtil.approach(c, item.position(), 1.6)) {
			return true;
		}
		JobUtil.give(c.level(), c.v(), item.getItem().copy());
		item.discard();
		c.mem().jobsDone++;
		st.clearTarget();
		st.cooldownUntil = c.now() + 20;
		return true;
	}

	@Override
	public boolean secondary(Ctx c) {
		return primary(c);
	}
}
