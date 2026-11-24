package de.villagerplus.job;

import de.villagerplus.village.Caravan;
import de.villagerplus.village.Market;
import de.villagerplus.village.VillageRef;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Idle residents lend a hand at the village's stone supply while a building plan waits on cobblestone. Nobody changes
 * profession and nobody is pulled from useful work: a resident only counts as a hand after his own job found nothing to do
 * (the caller offers him this task last), a claim lapses as soon as he does no stone work for a while, and the number of
 * concurrent hands is capped by the size of the shortage and by the free lanes of the mine. All work goes through the
 * ordinary mine and surface-quarry code, so lane leases, kit, safety rules and the delivery-on-demand rule stay untouched;
 * the stone itself comes from real blocks and ends in real inventories (the public village chest).
 */
public final class StoneHands {
	private StoneHands() { }

	/** Most hands at the same time (the mine has three work stands). */
	static final int MAX_HANDS = 3;
	/** Idle time before a resident may become a hand. */
	static final long IDLE_BEFORE_CLAIM = 200L;
	/** A claim lapses after this long without stone work. */
	static final long CLAIM_TICKS = 1200L;
	/** Carried cobblestone from which the surface quarry stops collecting and delivers. */
	static final int SURFACE_LOAD = 12;

	private static final Map<UUID, Long> CLAIMS = new HashMap<>();
	/** Residents who took stone work on and may still carry some: they deliver it whatever the claim state. */
	private static final java.util.Set<UUID> CARRIERS = new java.util.HashSet<>();

	static void resetForTest() {
		CLAIMS.clear();
		CARRIERS.clear();
	}

	static boolean carrier(UUID id) {
		return CARRIERS.contains(id);
	}

	static void markCarrierForTest(UUID id) {
		CARRIERS.add(id);
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

	/** Hands wanted for this shortage: one per 16 missing cobblestones, 1..{@link #MAX_HANDS}. */
	static int handsFor(int shortage) {
		return Math.max(1, Math.min(MAX_HANDS, (Math.max(0, shortage) + 15) / 16));
	}

	/** A plan without progress for this long gets every lane of the mine staffed, whatever the size of the shortage. */
	static final long STALL_FULL_HANDS = 2400L;

	/** {@link #handsFor} for a plan that has not moved for {@code stalledFor} ticks: the whole mine crew, not one hand per 16 stones. */
	static int handsForStall(int shortage, long stalledFor) {
		return shortage > 0 && stalledFor >= STALL_FULL_HANDS ? MAX_HANDS : handsFor(shortage);
	}

	/** Residents who may help: adults who are neither the village's guard nor a shopkeeper, a nitwit or busy as a carrier. */
	static boolean eligible(Ctx c) {
		Villager v = c.v();
		VillageRef ref = c.village();
		return ref != null && !v.isBaby() && !v.isSleeping() && !v.isTrading()
			&& !v.getVillagerData().profession().is(VillagerProfession.NITWIT)
			&& Market.shop(ref, v.getUUID()) == null && !Caravan.carryingFor(v);
	}

	/** Complete an unclaimed side job's existing load before a different profession can take its route over. */
	static boolean deliverStrayPlanStone(Ctx c) {
		if (!eligible(c) || claimed(c.v().getUUID(), c.now()) || WoodHands.hasActiveWork(c)) return false;
		Container box = c.village().chest(c.level());
		if (box == null) return false;
		java.util.function.Predicate<ItemStack> stray = stack -> de.villagerplus.build.CobbleLike.stone(stack)
			&& !BuildHaul.isMarkedCargo(stack);
		int carried = JobUtil.count(c.v().getInventory(), stray);
		return (PlanStoneSupply.projectRequestFunded(c.village().data(), box, c.v().getInventory())
			|| PlanStoneSupply.cobbleDeliveryDue(c.level(), c.village().data(), box, carried))
			&& JobUtil.depositSurplus(c, stray, 1, 0);
	}

	/**
	 * true = busy with stone work. The caller offers this only after the resident's own job reported nothing to do.
	 */
	public static boolean work(Ctx c) {
		if (!eligible(c) || BuildHaul.carrying(c.v())) return false;
		VillageRef ref = c.village();
		Container box = ref.chest(c.level());
		if (box == null) return false;
		UUID id = c.v().getUUID();
		long now = c.now();
		boolean claimed = claimed(id, now);
		boolean needed = Mining.needed(c.level(), ref, ref.data(), box, Mining.Need.STONE);
		// A resident who mined for the plans delivers what he carries, whether or not his claim still stands (the cap on hands
		// must never leave a load in somebody's pockets): when a useful load is on board, or the shortage is gone.
		if (CARRIERS.contains(id)) {
			int carried = JobUtil.count(c.v().getInventory(), de.villagerplus.build.CobbleLike::stone);
			if (carried <= 0) {
				CARRIERS.remove(id);
			} else if ((!needed || PlanStoneSupply.projectRequestFunded(ref.data(), box, c.v().getInventory())
				|| PlanStoneSupply.cobbleDeliveryDue(c.level(), ref.data(), box, carried)) && !Mining.holdLoadUnderground(c, carried)
				&& JobUtil.depositSurplus(c, de.villagerplus.build.CobbleLike::stone, 1, 0)) {
				return true;
			}
		}
		if (!needed) {
			CLAIMS.remove(id);
			return false;
		}
		if (!claimed) {
			if (c.st().idleSince == 0L || now - c.st().idleSince < IDLE_BEFORE_CLAIM) return false;
			int shortage = PlanStoneSupply.villageCobbleShortage(ref.data(), box);
			if (activeClaims(now) >= handsForStall(shortage, ref.data().planProgress > 0L ? now - ref.data().planProgress : 0L)) return false;
		}
		boolean busy = false;
		// Only as many mine route holders as the mine has stands: a hand never takes a lane from a miner.
		if (Mining.laneFreeFor(c)) busy = Mining.tick(c, Mining.Need.STONE, true);
		if (!busy) busy = surfaceStep(c, box);
		if (busy && !claimed) {
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("Steinhelfer {}: {} hilft beim Bruchstein (Tick {}, Fehlbetrag {}, Aktivitaet '{}')", c.mem().name,
				c.v().getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"), now,
				PlanStoneSupply.villageCobbleShortage(ref.data(), box), c.st().activity);
		}
		if (busy) {
			CLAIMS.put(id, now);
			CARRIERS.add(id);
		} else if (!claimed) {
			CLAIMS.remove(id);
		}
		return busy;
	}

	/** Deliver a useful load of surface stone, else break the next exposed stone (by pickaxe if carried, otherwise by hand). */
	static boolean surfaceStep(Ctx c, Container box) {
		int carried = JobUtil.count(c.v().getInventory(), de.villagerplus.build.CobbleLike::stone);
		if ((PlanStoneSupply.projectRequestFunded(c.village().data(), box, c.v().getInventory())
			|| PlanStoneSupply.cobbleDeliveryDue(c.level(), c.village().data(), box, carried))
			&& JobUtil.depositSurplus(c, (ItemStack stack) -> de.villagerplus.build.CobbleLike.stone(stack), 1, 0)) {
			return true;
		}
		return carried < SURFACE_LOAD && SurfaceStone.tick(c);
	}
}
