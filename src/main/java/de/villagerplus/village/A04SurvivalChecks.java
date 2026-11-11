package de.villagerplus.village;

import de.villagerplus.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Regression checks for keeping managed residents active in Survival without a nearby player. */
public final class A04SurvivalChecks {
	private A04SurvivalChecks() { }

	public static int run() {
		int checks = 0;
		check(Anchor.shouldLeaseVillagerChunk(true, false, false),
			"a tracked resident outside entity-ticking range receives a refreshable work lease"); checks++;
		check(!Anchor.shouldLeaseVillagerChunk(true, true, false),
			"residents already entity-ticking do not add redundant chunk leases"); checks++;
		check(Anchor.shouldLeaseVillagerChunk(true, true, true),
			"residents keep renewing a chunk made entity-ticking by their own active work lease"); checks++;
		check(!Anchor.shouldLeaseVillagerChunk(false, false, true),
			"the existing keep-villages-loaded switch still disables work leases"); checks++;
		check(Config.KEEP_VILLAGES_LOADED && Anchor.VILLAGER_WORK.doesLoad() && Anchor.VILLAGER_WORK.doesSimulate()
			&& !Anchor.VILLAGER_WORK.persist() && Anchor.VILLAGER_WORK.timeout() == Config.HOUSING_INTERVAL + 40L
			&& Anchor.WORK_TICKET_REFRESH_INTERVAL < Anchor.VILLAGER_WORK.timeout()
			&& Identifier.fromNamespaceAndPath("villagerplus", "villager_work").equals(BuiltInRegistries.TICKET_TYPE.getKey(Anchor.VILLAGER_WORK)),
			"resident tickets are registered, entity-simulating, temporary, and outlive the 200-tick housing refresh"); checks++;
		return checks;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("A04 Survival check failed: " + message);
	}
}
