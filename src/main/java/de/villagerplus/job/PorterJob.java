package de.villagerplus.job;

import de.villagerplus.Config;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Traeger (Arbeitslose und Dorftrottel): raeumen auf. Sie sammeln Items ein, die schon lange herumliegen, und
 * bringen sie zur Dorfkiste. Ohne lose Items raeumen sie falsch einsortierte Waren (Werkzeug im Materiallager, Material in der
 * Werkzeugkammer, Fremdes im Kornspeicher) per StockPolicy-Routing in das richtige Depot um.
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
		if (!LooseItemPickup.allowsPickup(c)) return sortOrIdle(c);
		JobState st = c.st();
		ItemEntity item = st.targetEntity instanceof ItemEntity e && availableDrop(c, e) ? e : null;
		if (item == null) {
			st.clearTarget();
			AABB box = c.v().getBoundingBox().inflate(Config.PORTER_RADIUS);
			item = c.level().getEntitiesOfClass(ItemEntity.class, box, e -> availableDrop(c, e))
				.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(c.v()))).orElse(null);
			if (item == null) {
				return sortOrIdle(c);
			}
			st.targetEntity = item;
		}
		c.setActivity("räumt auf");
		if (!JobUtil.approach(c, item.position(), LooseItemPickup.REACH)) {
			return true;
		}
		boolean collected = LooseItemPickup.collectInReach(c, item);
		st.clearTarget();
		if (!collected) return false;
		c.mem().jobsDone++;
		st.cooldownUntil = c.now() + 20;
		return true;
	}

	private static boolean availableDrop(Ctx c, ItemEntity item) {
		return item.getAge() >= Config.PORTER_MIN_ITEM_AGE && LooseItemPickup.canCollect(c, item)
			&& (!LooseItemPickup.inReach(c, item) || LooseItemPickup.clearReach(c, item));
	}

	private static boolean sortOrIdle(Ctx c) {
		if (PorterSorting.work(c)) return true;
		c.setIdle(0, 600);
		return false;
	}

	@Override
	public boolean secondary(Ctx c) {
		return primary(c);
	}
}
