package de.villagerplus.village;

import de.villagerplus.build.Builder;
import de.villagerplus.job.JobManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Clears static per-world caches so saves and dimensions do not leak across restarts. */
public final class WorldCaches {
	private WorldCaches() { }

	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearAll());
	}

	static void clearAll() {
		Anchor.clearCachesOnStop();
		Builder.clearWorldCaches();
		JobManager.clearWorldCaches();
		Villages.clearWorldCaches();
		VillagePoiCache.clear();
		VillageStyleCache.clear();
		SiteSearchDiagnostics.clear();
		BellProtection.clearPending();
	}
}