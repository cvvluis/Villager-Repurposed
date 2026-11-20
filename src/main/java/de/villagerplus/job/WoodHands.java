package de.villagerplus.job;

import de.villagerplus.village.VillageRef;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Idle residents lend a hand at the timber supply while a building plan waits on logs or planks, the wood twin of
 * {@link StoneHands}. A plan's wood used to come from its owner and the one village woodcutter alone, about ten logs a day,
 * while a third of the working residents stood about. A resident only counts as a hand after his own job (and the stone
 * supply) found nothing to do, a claim lapses as soon as he does no wood work for a while, and the number of concurrent
 * hands is capped by the size of the log shortage. The work itself is the ordinary plan woodcutting (exact species, safe
 * walk-back, batch delivery to the public chest), so replanting, claims and safety rules stay untouched.
 */
public final class WoodHands {
	private WoodHands() { }

	/** Most hands at the same time. */
	static final int MAX_HANDS = 4;
	/** Idle time before a resident may become a hand. */
	static final long IDLE_BEFORE_CLAIM = 200L;
	/** A claim lapses after this long without wood work. */
	static final long CLAIM_TICKS = 1200L;
	/** One hand per this many logs the public chest still lacks. */
	static final int LOGS_PER_HAND = 8;

	private static final Map<UUID, Long> CLAIMS = new HashMap<>();

	static void resetForTest() {
		CLAIMS.clear();
	}

	static int claimCount(long now) {
		return activeClaims(now);
	}

	static boolean claimed(UUID id, long now) {
		Long last = CLAIMS.get(id);
		return last != null && now - last < CLAIM_TICKS;
	}

	private static int activeClaims(long now) {
		int n = 0;
		for (Iterator<Map.Entry<UUID, Long>> it = CLAIMS.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Long> e = it.next();
			if (e.getValue() > now) { it.remove(); continue; } // stale entry from another world/session
			if (now - e.getValue() < CLAIM_TICKS) n++;
		}
		return n;
	}

	/** Hands wanted for this log shortage: one per {@link #LOGS_PER_HAND} missing logs, 1..{@link #MAX_HANDS}. */
	static int handsFor(int shortage) {
		return Math.max(1, Math.min(MAX_HANDS, (Math.max(0, shortage) + LOGS_PER_HAND - 1) / LOGS_PER_HAND));
	}

	/**
	 * Logs of a species the open plan asks for that this resident carries while the public chest still lacks them: felled or cleared
	 * for the plan but never handed in (a mason clearing a building site kept 20 to 50 spruce logs in his pockets for days, while the
	 * plan waited for eight). The routine delivery uses this beside the job's own produce.
	 */
	public static java.util.function.Predicate<ItemStack> strayPlanLogs(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || !ref.data().hasPlan() || !Woodcutting.planNeedsWood(ref.data())) return null;
		Container box = ref.chest(c.level());
		if (box == null || Woodcutting.planLogShortfall(ref.data().wanted, box) <= 0) return null;
		var demanded = Woodcutting.demandedLogIds(ref.data().wanted);
		java.util.function.Predicate<ItemStack> requested = stack -> Woodcutting.matchesRequestedLog(stack, demanded);
		return JobUtil.count(c.v().getInventory(), requested) > 0 ? requested : null;
	}

	/**
	 * true = busy with wood work. The caller offers this only after the resident's own job and the stone supply reported
	 * nothing to do.
	 */
	public static boolean work(Ctx c) {
		if (!StoneHands.eligible(c)) return false;
		VillageRef ref = c.village();
		Container box = ref.chest(c.level());
		if (box == null) return false;
		var data = ref.data();
		UUID id = c.v().getUUID();
		long now = c.now();
		var work = c.v().getAttachedOrElse(de.villagerplus.VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
		// A tree already under the axe is finished whatever the claim state (the cursor would otherwise stay half cut for good).
		boolean midTree = work.root().isPresent() || work.removalPending();
		boolean demand = data.hasPlan() && Woodcutting.planNeedsWood(data);
		int shortage = demand ? Woodcutting.planLogShortfall(data.wanted, box) : 0;
		var demanded = demand ? Woodcutting.demandedLogIds(data.wanted) : java.util.Set.<String>of();
		java.util.function.Predicate<ItemStack> requested = stack -> Woodcutting.matchesRequestedLog(stack, demanded);
		int carried = JobUtil.count(c.v().getInventory(), requested);
		if (carried > 0 && (!demand || shortage <= 0) && !midTree
			&& JobUtil.depositSurplus(c, requested, 1, 0)) {
			c.setActivity("bringt Bauholz zur Dorfkiste");
			return true;
		}
		if (!demand || (shortage <= 0 && !midTree && carried <= 0)) {
			CLAIMS.remove(id);
			return false;
		}
		boolean claimed = claimed(id, now);
		if (!claimed && !midTree) {
			if (c.st().idleSince == 0L || now - c.st().idleSince < IDLE_BEFORE_CLAIM) return false;
			if (shortage <= 0 || activeClaims(now) >= handsFor(shortage)) return false;
		}
		boolean busy = Woodcutting.tick(c);
		if (busy && !claimed) {
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("Holzhelfer {}: {} hilft beim Bauholz (Tick {}, Fehlbetrag {} Staemme, Aktivitaet '{}')",
				c.mem().name, c.v().getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"),
				now, shortage, c.st().activity);
		}
		if (busy) CLAIMS.put(id, now);
		else if (!claimed) CLAIMS.remove(id);
		return busy;
	}
}
