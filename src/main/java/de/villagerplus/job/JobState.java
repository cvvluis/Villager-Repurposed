package de.villagerplus.job;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/** Fluechtiger Laufzeit-Zustand eines Jobs (geht beim Neustart bewusst verloren). */
public class JobState {
 public int builderRelocations;
 public de.villagerplus.village.Roads.Repair roadRepair;
 public long roadScanAt;
 public long roadBuildScanAt;
 public boolean roadBuilding;
 public long nextHousing;
	/** Rotierendes Wellbeing-Arbeitsviertel pro tatsaechlichem Berufsversuch (unabhaengig von Weltzeit). */
	public int workQuarterCursor;
	/** Nach einer Aktion: bis dahin passiert nichts. */
	public long cooldownUntil;
	/** Pro Teilaufgabe (0 = primaer, 1 = sekundaer): bis dahin wurde nichts zu tun gefunden. */
	public final long[] idleUntil = new long[2];
	public BlockPos target;
	/** Runtime-only target while a farmer walks to a safe wild-grass seed source. */
	public BlockPos forageTarget;
	/** Next game time at which a forager may run the wide (far meadow) seed-grass search again. */
	public long forageFarSearchAt;
	/** Last safe grass patch that yielded no seeds; avoid immediately retrying the same low-yield spot. */
	public BlockPos forageRejectedTarget;
	public Entity targetEntity;
	public long walkStart;
	public long chestCheckAt;
	public long lightAt;
	/** Cached public tool-shed chest while the lamplighter fetches a real shovel for saved-road repair. */
	public BlockPos pathShovelSource;
	public long buildCheckAt;
	public long mineCheckAt;
	/** Cached anvil in the registered tool shed and next equipment-repair scan. */
	public BlockPos toolRepairAnvil;
	public long toolRepairCheckAt;
	public long toolRepairNextWorkAt;
	public BlockPos toolCraftBench;
	public long toolCraftScanAt;
	/** Dorfkiste war voll: bis dahin nicht erneut versuchen, Waren abzuliefern. */
	public long depositBlockedUntil;
	/** Zuletzt bekannte Fertigkeitsstufe (-1 = noch nicht gesetzt) fuer die Aufstiegsmeldung. */
	public int lastSkill = -1;
	/** Fuer die Haengen-Erkennung beim Hinlaufen. */
	public net.minecraft.world.phys.Vec3 lastPos;
	public long lastMoveAt;
	/** Seit wann der Dorfbewohner nichts Sinnvolles zu tun hat (0 = beschaeftigt); Grundlage des Job-Timeouts. */
	public long idleSince;
	/** Fehlversuche beim Hinlaufen zum selben Ziel (Anti-Haengetask: beim dritten wird abgebrochen). */
	public int stuckCount;
	/** Next patrol waypoint for custom guard/lookout jobs. */
	public int patrolIndex;
	/** Short-lived in-memory patrol route and next bounded scan time for a guard. */
	public List<BlockPos> guardPatrolPosts = List.of();
	public long guardPatrolPostsCheckAt;
	/** Raid bell cooldown, separate from the ordinary danger bell cooldown. */
	public long guardRaidAlarmAt;
	public double bestDist;
	/** Bis dahin zaehlt der Dorfbewohner als arbeitender Bergmann (darf in der Mine bleiben). */
	public long miningUntil;
	public long woodCheckAt;
	/** Bau wartet auf Material: bis dahin wird die (teure) Materialpruefung nicht wiederholt. */
	public long materialWaitUntil;
	/** Runtime-only route cache for the current construction-material pickup; the item-stack marker survives reloads. */
	public BlockPos buildHaulProject;
	public BlockPos buildHaulSource;
	public String buildHaulItemId = "";
	/** Werkzeug/Gegenstand, der dem Dorfbewohner gerade in der Hand gezeigt wird (Animation). */
	public net.minecraft.world.item.Item tool;
	public long toolUntil;
	public boolean holding;
	public Phase routinePhase;
	public int stage;
	public net.minecraft.world.entity.npc.villager.Villager chatPartner;
	public boolean chatInitiator;
	public long chatUntil;
	/** Stable safe meeting block chosen once for this pair, so crowd changes cannot make them orbit between spots. */
	public BlockPos chatMeetingSpot;
	public long chatCooldown;
	/** Next bounded retry after an unsuccessful nearby partner search (runtime-only). */
	public long chatSearchAt;
	public String activity = "";

	public void clearTarget() {
		target = null;
		targetEntity = null;
		walkStart = 0;
	}
}
