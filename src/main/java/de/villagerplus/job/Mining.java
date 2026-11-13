package de.villagerplus.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.Lager;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStyle;
import de.villagerplus.build.Palette;
import de.villagerplus.build.Supply;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Bergbau: Das Dorf legt abseits der Siedlung eine Mine an (Eingang mit Schild und Fackeln, Treppe nach unten, dann ein
 * waagerechter Stollen). Bergleute (Maurer, Schmiede) laufen hinein und bauen die Bloecke WIRKLICH ab: Stein, Erze
 * (inklusive angrenzender Erzadern), Erde, Kies ... Die Drops landen in ihrem Inventar und werden zur Dorfkiste gebracht.
 * Bei Wasser, Lava, Sand/Kies ueber dem Stollen oder Spawnern/Kisten wird der Stollen sicherheitshalber beendet.
 */
public final class Mining {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("villagerplus");
	static final int MAX_HORIZONTAL = 56;
	private static final int VEIN_LIMIT = 24;
	private static final int TUNNEL_HALF_WIDTH = 1;
	private static final int TUNNEL_CLEAR_HEIGHT = 3;
	private static final int SUPPORT_RADIUS = 2;
	private static final int SUPPORT_SPACING = 4;
	private static final int LIGHT_SPACING = 6;
	private static final Item[] TERRACE_FILL = {Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.COARSE_DIRT,
		Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.STONE, Items.DEEPSLATE};
	static final int WAITING_STAND_COUNT = 36;
	static final int MINE_WORK_STAND_COUNT = 3;
	private static final int WAITING_STAND_SCAN_MARGIN = 16;
	private static final int MINE_RESERVATION_TICKS = 80;
	private static final int OCCUPIED_SEAL_RETRY_TICKS = 200;
	private static final int BLOCKED_SEAL_RETRY_TICKS = 1200;
	private static final Palette MINE_ENTRANCE_SITE_PALETTE = new Palette(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS,
		Blocks.SPRUCE_LOG, Blocks.COBBLESTONE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB,
		Blocks.GLASS_PANE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE);
	private record MineSupplies(int fences, int framePlanks, int walkwayPlanks, int torches) {
		boolean empty() { return fences == 0 && framePlanks == 0 && walkwayPlanks == 0 && torches == 0; }
		/** Only what the gallery needs to stand and be lit: the paved walkway strip is cosmetic and never stops the digging. */
		MineSupplies structural() { return new MineSupplies(fences, framePlanks, 0, torches); }
		MineSupplies plus(MineSupplies other) {
			return new MineSupplies(fences + other.fences, framePlanks + other.framePlanks,
				walkwayPlanks + other.walkwayPlanks, torches + other.torches);
		}
	}
	static record MineFrameMaterials(Block fence, Item fenceItem, Block planks, Item plankItem) { }
	enum SupplyStatus { READY, BUSY, WAITING }
	private record MineSectionPlan(boolean loaded, boolean safe, List<BlockPos> blocks) { }
	static record MineSurfaceSealPlan(boolean loaded, List<BlockPos> coverCells, List<BlockPos> roofCells) { }
	private record PaidMineSlot(Container container, int slot) { }
	private record MinePortalChange(BlockPos pos, BlockState before, BlockState placed) { }
	private record PendingMineSeal(VillageData.Mine mine, boolean exhausted, long retryAt) { }
	static record MineEntranceBlock(BlockPos pos, BlockState state) { }
	private record MinePlankMaterial(Item item, Block block) { }
	private static final Map<String, Long> MINE_ENTRANCE_RETRY_AT = new LinkedHashMap<>();
	private static final Set<String> MINE_ENTRANCE_COMPLETE = new HashSet<>();
	private static final Map<ServerLevel, Map<Long, PendingMineSeal>> PENDING_MINE_SEALS = new WeakHashMap<>();
	private static final Map<ServerLevel, Map<Long, MineFrameMaterials>> MINE_FRAME_MATERIALS = new WeakHashMap<>();
	private static final List<MinePlankMaterial> MINE_PLANKS = List.of(
		new MinePlankMaterial(Items.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS),
		new MinePlankMaterial(Items.OAK_PLANKS, Blocks.OAK_PLANKS),
		new MinePlankMaterial(Items.BIRCH_PLANKS, Blocks.BIRCH_PLANKS),
		new MinePlankMaterial(Items.JUNGLE_PLANKS, Blocks.JUNGLE_PLANKS),
		new MinePlankMaterial(Items.ACACIA_PLANKS, Blocks.ACACIA_PLANKS),
		new MinePlankMaterial(Items.DARK_OAK_PLANKS, Blocks.DARK_OAK_PLANKS),
		new MinePlankMaterial(Items.MANGROVE_PLANKS, Blocks.MANGROVE_PLANKS),
		new MinePlankMaterial(Items.CHERRY_PLANKS, Blocks.CHERRY_PLANKS),
		new MinePlankMaterial(Items.PALE_OAK_PLANKS, Blocks.PALE_OAK_PLANKS),
		new MinePlankMaterial(Items.BAMBOO_PLANKS, Blocks.BAMBOO_PLANKS),
		new MinePlankMaterial(Items.CRIMSON_PLANKS, Blocks.CRIMSON_PLANKS),
		new MinePlankMaterial(Items.WARPED_PLANKS, Blocks.WARPED_PLANKS));

	private Mining() {
	}

	static {
		ServerTickEvents.END_LEVEL_TICK.register(Mining::tickPendingMineSeals);
	}

	/** Welche Vorraete werden gerade gebraucht? */
	public enum Need { STONE, METAL }

	/** Persistenter, pro Villager gespeicherter Bewegungsbereich fuer einen laufenden Minenbesuch. */
	public record MineRoute(String dimension, BlockPos entrance, int dir, int index, int targetY, long reservationUntil,
			int workSlot) {
		public static final Codec<MineRoute> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("dimension").forGetter(MineRoute::dimension),
			BlockPos.CODEC.fieldOf("entrance").forGetter(MineRoute::entrance),
			Codec.INT.fieldOf("dir").forGetter(MineRoute::dir),
			Codec.INT.fieldOf("index").forGetter(MineRoute::index),
			Codec.INT.fieldOf("target_y").forGetter(MineRoute::targetY),
			Codec.LONG.optionalFieldOf("reservation_until", 0L).forGetter(MineRoute::reservationUntil),
			Codec.INT.optionalFieldOf("work_slot", -1).forGetter(MineRoute::workSlot)
		).apply(i, MineRoute::new));

		public MineRoute {
			entrance = entrance.immutable();
		}

		public MineRoute(String dimension, BlockPos entrance, int dir, int index, int targetY, long reservationUntil) {
			this(dimension, entrance, dir, index, targetY, reservationUntil, -1);
		}

		public MineRoute(String dimension, BlockPos entrance, int dir, int index, int targetY) {
			this(dimension, entrance, dir, index, targetY, 0L, -1);
		}
	}

	/** Kein festes Limit: gebaut wird, was im Vergleich zu Essen, Holz und dem Rest knapp ist (siehe {@code Balance}). */
	public static boolean needed(ServerLevel level, VillageRef ref, VillageData d, Container box, Need need) {
		if (need == Need.METAL) {
			return de.villagerplus.village.Balance.wants(level, ref, de.villagerplus.village.Balance.Kind.METAL);
		}
		if (PlanStoneSupply.needsCobble(level, d, box)) return true;
		// The plan asks for plain cobblestone directly (rustic or woodless palettes): mine it once the village chest cannot cover it.
		// Cobblestone stairs and slabs are crafted from cobblestone (6 per 4 stairs, 3 per 6 slabs), so they count as demand too.
		int stairs = PlanStoneSupply.wanted(d, net.minecraft.world.item.Items.COBBLESTONE_STAIRS);
		int slabs = PlanStoneSupply.wanted(d, net.minecraft.world.item.Items.COBBLESTONE_SLAB);
		int demand = PlanStoneSupply.wanted(d, net.minecraft.world.item.Items.COBBLESTONE) + (stairs * 3 + 1) / 2 + (slabs + 1) / 2;
		return d.hasPlan() && demand > JobUtil.count(box, stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE));
	}

	private static final java.util.Map<Long, Integer> UNREACHABLE_MINE_STEPS = new java.util.HashMap<>();
	private static final int UNREACHABLE_MINE_LIMIT = 3;

	private static final java.util.Map<java.util.UUID, Long> MINE_DIAG = new java.util.HashMap<>();

	/** Dev aid: rate-limited per worker so a stuck miner explains itself in the log. */
	private static void mineDiagnose(Ctx c, String what) {
		long last = MINE_DIAG.getOrDefault(c.v().getUUID(), -1_000_000L);
		if (c.now() - last < 1200L) return;
		MINE_DIAG.put(c.v().getUUID(), c.now());
		LOGGER.info("Mine {}: {}", c.mem().name, what);
	}

	/** true = beschaeftigt. */
	private static final java.util.Map<Long, Long> KIT_WAIT_SINCE = new java.util.HashMap<>();
	/** Section the running kit wait belongs to: a new section starts a fresh wait, so one timeout never unlocks the whole mine. */
	private static final java.util.Map<Long, Integer> KIT_WAIT_INDEX = new java.util.HashMap<>();
	/** Timber or coal may simply not exist near a village: after this much working time spent on one section's kit (restock trips, crafting, waiting) the miners dig that section without it, and the following ones too for {@link #KIT_SKIP_TICKS}. */
	static final long KIT_WAIT_TICKS = 1800L;
	/** After such a release the following sections are dug without waiting for the same kit again for this long. */
	static final long KIT_SKIP_TICKS = 12000L;
	/** One call counts at most this much waiting time, so nights and rest periods between two work spells are not charged. */
	static final long KIT_WAIT_STEP_CAP = 400L;
	private static final java.util.Map<Long, Long> KIT_WAIT_SPENT = new java.util.HashMap<>();
	private static final java.util.Map<Long, Long> KIT_WAIT_LAST = new java.util.HashMap<>();
	private static final java.util.Map<Long, Long> KIT_SKIP_UNTIL = new java.util.HashMap<>();
	/** The supply trip also covers the next few sections (only what the depot holds), so a trip is not made for every support or torch. */
	static final int KIT_LOOKAHEAD_SECTIONS = 6;
	private static final java.util.Map<java.util.UUID, Long> NOSTART_DIAG = new java.util.HashMap<>();

	/** True when mineDiag would log for this resident now (lets callers skip building expensive messages). */
	private static boolean mineDiagReady(Ctx c) {
		return c.now() >= NOSTART_DIAG.getOrDefault(c.v().getUUID(), 0L);
	}

	/** Rate-limited reason why the mine did not start for a resident. */
	private static void mineDiag(Ctx c, String reason) {
		java.util.UUID id = c.v().getUUID();
		if (c.now() < NOSTART_DIAG.getOrDefault(id, 0L)) return;
		NOSTART_DIAG.put(id, c.now() + 2400L);
		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Mine {}: {} bei {}", c.mem().name, reason, c.v().blockPosition().toShortString());
	}

	public static boolean tick(Ctx c, Need need) {
		VillageRef ref = c.village();
		if (ref == null) {
			return false;
		}
		JobState st = c.st();
		if (c.now() < st.mineCheckAt) {
			return false;
		}
		Container box = ref.chest(c.level());
		if (box == null) {
			return false;
		}
		VillageData d = ref.data();
		if (!needed(c.level(), ref, d, box, need)) {
			mineDiag(c, "Bedarf verneint (" + need + ")");
			st.mineCheckAt = c.now() + 600;
			return false;
		}
		ItemStack tool = pickaxe(c.v().getInventory());
		boolean byHand = false;
		if (tool.isEmpty() && need == Need.STONE) {
			// Same rule as the surface quarry: when no pickaxe can be had (no wood for handles), stone is chipped out by hand.
			// The stand-in only decides which drops a stone block yields; it is never placed into any inventory.
			tool = new ItemStack(net.minecraft.world.item.Items.WOODEN_PICKAXE);
			byHand = true;
		}
		if (tool.isEmpty() && need == Need.METAL && PlanStoneSupply.supplyMiningPickaxe(c, box, true)) {
			// Metal cannot be chipped out by hand: take a pickaxe from the village toolshed, or craft a plain one from public village stock,
			// exactly as the plan stone chain does, instead of waiting for a tool that nobody hands out.
			return true;
		}
		if (tool.isEmpty()) {
			mineDiag(c, "keine Spitzhacke");
			st.mineCheckAt = c.now() + 200;
			c.setActivity("wartet auf eine Spitzhacke");
			return false;
		}
		// Migriere gespeicherte Abschlusszustaende aus aelteren Versionen ebenfalls sofort in
		// die dauerhafte Altminenliste. So bleibt der Eingang auf Karte/Tafel als "Alte Mine"
		// sichtbar, selbst wenn gerade kein neuer Schacht angelegt werden kann.
		d.mine.filter(m -> m.done).ifPresent(m -> {
			archiveMine(d, m);
			d.mine = Optional.empty();
			ref.dirty();
		});
		if (d.mine.isEmpty()) {
			Optional<VillageData.Mine> created = create(c, ref);
			if (created.isEmpty()) {
				mineDiag(c, "keine Mine anlegbar (create leer)");
				st.mineCheckAt = c.now() + 2400;
				return false;
			}
			d.mine = created;
			ref.dirty();
		}
		VillageData.Mine m = d.mine.get();
		ensureMineEntrance(c.level(), ref, m, c.v(), c.now());
		// Cobblestone the village plan is waiting for goes to the public chest as soon as a useful load is on board. Residents who
		// mine for the plan without owning it used to keep it until their pockets held 64 items, while the plan waited for it.
		if (need == Need.STONE && PlanStoneSupply.cobbleDeliveryDue(c.level(), ref.data(), box,
				JobUtil.count(c.v().getInventory(), stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE)))) {
			if (c.v().getY() < m.entrance.getY() - 0.5 && nearest(m, c.v().position()) >= 1) {
				requestCapacityReturn(c, m);
				return true;
			}
			if (JobUtil.depositSurplus(c, stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE), 1, PlanStoneSupply.COBBLE_RESERVE)) {
				return true;
			}
		}
		MineRoute currentRoute = mineRoute(c.v());
		// Only as many residents may hold a mine route as there are stands (plus one waiting): the rest do other work.
		// Without this cap everybody with a stone job walks in and the gallery jams for good.
		{
			List<Villager> holders = new ArrayList<>(mineRouteWorkers(c.level(), m));
			// Tier 0: residents inside the gallery or standing in the shaft (index >= 1) never get evicted by waiters. Tier 1: the rest,
			// closest to the entrance first, so residents at the shaft outrank those still far away on the other side of the land.
			holders.sort(java.util.Comparator.<Villager>comparingInt(h -> {
				MineRoute r = mineRoute(h);
				return inMineArea(m, h) || (r != null && r.index() >= 1) ? 0 : 1;
			}).thenComparingDouble(h -> h.position().distanceToSqr(Vec3.atBottomCenterOf(m.entrance)))
				.thenComparing(Villager::getUUID));
			int cap = MINE_WORK_STAND_COUNT + 1;
			int rank = holders.indexOf(c.v());
			if (rank >= cap) {
				if (inMineArea(m, c.v())) {
					deferMineRouteAfterStall(c);
					exitStep(c);
					return true;
				}
				clearMineRoute(c.v());
				st.mineCheckAt = c.now() + 400;
				return false;
			}
			if (rank < 0 && holders.size() >= cap) {
				// A newcomer closer to the shaft than the farthest SURFACE holder takes over that lane (that one steps back);
				// holders working in the gallery are never displaced.
				Villager last = holders.get(cap - 1);
				MineRoute lastRoute = mineRoute(last);
				boolean lastWorking = inMineArea(m, last) || (lastRoute != null && lastRoute.index() >= 1);
				double mine2 = c.v().position().distanceToSqr(Vec3.atBottomCenterOf(m.entrance));
				double farthest = last.position().distanceToSqr(Vec3.atBottomCenterOf(m.entrance));
				if (lastWorking || mine2 >= farthest) {
					st.mineCheckAt = c.now() + 400;
					return false;
				}
			}
		}
		int workSlot = assignedMineWorkSlot(c, m);
		if (mineDiagReady(c)) mineDiag(c, "Minenstand: Eingang " + m.entrance.toShortString() + ", Tiefe " + m.index + ", Arbeitsplatz " + workSlot
			+ ", Pos " + c.v().blockPosition().toShortString() + (byHand ? ", von Hand" : "")
			+ (workSlot < 0 ? " :: " + workSlotDiagnostics(c, m) : ""));
		if (workSlot < 0) {
			if (inMineArea(m, c.v())) {
				deferMineRouteAfterStall(c);
				exitStep(c);
				return true;
			}
			if (noLanePathWatchdog(c, m)) return true;
			int queueSlot = waitingSlot(currentRoute);
			if (queueSlot < 0 || !mineWaitingStandAvailable(c, m, queueSlot)) {
				queueSlot = findMineWaitingSlot(c, m);
			}
			if (queueSlot >= 0) {
				rememberMineWaiting(c.v(), c.level(), m, queueSlot);
				c.st().mineCheckAt = c.now() + 40;
				waitAtMineStand(c, m, queueSlot);
			} else {
				// Waiting far from the shaft (e.g. after a distant timber trip) is pointless and the walk home may be beyond
				// pathfinding range: bring the resident back to the village once, like the stranded rescue does.
				double farSq = c.v().position().distanceToSqr(Vec3.atBottomCenterOf(m.entrance));
				if (farSq > 48.0 * 48.0 && !inMineArea(m, c.v()) && c.village() != null) {
					var arrival = de.villagerplus.build.Builder.villageReturn(c.level(), c.village().bell());
					if (arrival != null) {
						c.v().getNavigation().stop();
						c.v().teleportTo(arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5);
						c.v().resetFallDistance();
						c.st().cooldownUntil = c.now() + 40;
						org.slf4j.LoggerFactory.getLogger("villagerplus").info("Mine: {} wartete {} Bloecke vom Eingang entfernt und wurde ins Dorf zurueckgeholt",
							c.mem().name, (int) Math.sqrt(farSq));
						return true;
					}
				}
				if (farSq > 14.0 * 14.0 && !inMineArea(m, c.v())) {
					// No stand free and still far from the shaft: standing around here helps nobody. Release the resident for other
					// work and look again later, instead of reporting "busy" for hours.
					stopMineApproach(c);
					clearMineRoute(c.v());
					st.mineCheckAt = c.now() + 600;
					return false;
				}
				stopMineApproach(c);
				// Keep an existing admission/queue route so a blocked entrance resumes with the same owner.
				c.st().mineCheckAt = c.now() + 40;
				c.st().cooldownUntil = c.now() + 20;
				c.setActivity("wartet mit Abstand auf einen freien sicheren Minenstand");
			}
			return true;
		}
		NO_LANE_PATH_SINCE.remove(c.v().getUUID());
		if (isWaitingRoute(currentRoute)) {
			stopMineApproach(c);
			clearMineRoute(c.v());
		}
		rememberMineRoute(c.v(), c.level(), m, workSlot);
		reserveMineAdmission(c.st(), c.now());
		List<ItemStack> blockedSectionDrops = capacityBlockedSectionDrops(c, m, tool);
		if (!blockedSectionDrops.isEmpty()) {
			if (c.v().getY() < m.entrance.getY() - 0.5 && nearest(m, c.v().position()) >= 1) {
				requestCapacityReturn(c, m);
				return true;
			}
			if (JobUtil.depositSurplus(c, stack -> matchesMiningOutput(stack, blockedSectionDrops), 1, 0)) {
				return true;
			}
			st.mineCheckAt = c.now() + 200;
			c.setActivity("wartet auf freien Platz im Dorflager");
			return true;
		}
		// Hand mining happens where no wood exists for supports and torches: it works the stone face without a kit.
		SupplyStatus supplies = byHand ? SupplyStatus.READY : kitStatus(c, ref, m);
		if (supplies == SupplyStatus.WAITING) {
			st.mineCheckAt = c.now() + 200;
			return false;
		}
		if (supplies == SupplyStatus.BUSY) {
			return true;
		}

		c.setActivity("arbeitet in der Mine");
		Anim.hold(c, byHand ? net.minecraft.world.item.Items.AIR : tool.getItem());

		// Der Weg in den Stollen: ueber Land zum Eingang und danach mit normalen WalkTarget-Schritten ueber die
		// gespeicherten, jeweils nur einen Abschnitt entfernten Treppenzellen.
		int stand = Math.max(0, m.index - 1);
		// Vor jedem Betreten: Bruecke ueber einen Hohlraum (Hoehle, Schlucht) oder Fluessigkeit im Stollen -> Mine schliessen,
		// bevor jemand hineinfaellt (gilt auch fuer Minen, die schon vor dieser Pruefung angelegt wurden).
		if (stand > 0) {
			BlockPos approachCell = bottom(m, stand);
			BlockPos approachFloor = approachCell.below();
			if (waitForUnloadedApproach(c, approachCell)) {
				return true;
			}
			if (!patchFloor(c, approachFloor) && oneBlockTerraceGap(c.level(), approachFloor)) {
				c.setActivity("wartet auf Bruchstein für eine sichere Minenterrasse");
				return true;
			}
		}
		if (stand > 0 && !safeCell(c, bottom(m, stand))) {
			finishMine(c, ref, m, "Die Mine wurde geschlossen (Hohlraum unter dem Stollen).");
			exitStep(c);
			return true;
		}
		int here = nearest(m, c.v().position());
		BlockPos workStand = mineWorkStand(m, stand, workSlot);
		if (here != stand || !atMineWorkStand(c.v(), workStand)) {
			BlockPos next = nextMineWorkWaypoint(m, here, stand, workSlot);
			MineWalk result = walkMineStep(c, m, next);
			mineDiagnose(c, "Lauf " + result + " pos=" + c.v().blockPosition().toShortString() + " Eingang=" + m.entrance.toShortString()
				+ " Zelle=" + here + "/" + stand + " naechster=" + next.toShortString() + " Slot=" + workSlot);
			if (result == MineWalk.STUCK) {
				if (inMineArea(m, c.v())) {
					deferMineRouteAfterStall(c);
					return true;
				}
				waitOutsideBlockedMineEntry(c, m);
				return true;
			}
			if (result == MineWalk.BLOCKED) {
				finishMine(c, ref, m, "Die Mine wurde wegen eines blockierten Laufwegs geschlossen.");
				if (here >= 1) exitStep(c);
				else clearMineRoute(c.v());
				return true;
			}
			st.cooldownUntil = c.now() + (result == MineWalk.WAITING ? 40 : 8);
			return true;
		}
		mineDiagnose(c, "Graben pos=" + c.v().blockPosition().toShortString() + " Zelle=" + here + "/" + stand);
		dig(c, ref, m, tool);
		st.cooldownUntil = c.now() + 24;
		return true;
	}

	// --- Geometrie ------------------------------------------------------------------------------

	private static int descent(VillageData.Mine m) {
		return m.entrance.getY() - m.targetY;
	}

	/** Untere Zelle des Stollenabschnitts i (auf der der Dorfbewohner steht). */
	private static BlockPos bottom(VillageData.Mine m, int i) {
		Direction dir = Direction.from2DDataValue(m.dir);
		BlockPos p = m.entrance.relative(dir, i);
		return new BlockPos(p.getX(), Math.max(m.targetY, m.entrance.getY() - i), p.getZ());
	}

	static int cells(VillageData.Mine m, int i) {
		return TUNNEL_CLEAR_HEIGHT;
	}

	static boolean supportSection(int index) {
		return Math.floorMod(index, SUPPORT_SPACING) == 2;
	}

	static boolean lightSection(int index) {
		return index > 0 && Math.floorMod(index, LIGHT_SPACING) == LIGHT_SPACING / 2;
}

	static MineFrameMaterials mineFrameMaterials(Palette palette) {
		Block fence = palette != null && palette.fence() instanceof FenceBlock && Supply.obtainable(palette.fence())
			? palette.fence() : Blocks.OAK_FENCE;
		Block planks = matchingPlanks(fence);
		if (planks == null && palette != null && palette.floor() != null
			&& palette.floor().defaultBlockState().is(BlockTags.PLANKS) && Supply.obtainable(palette.floor())) {
			planks = palette.floor();
		}
		if (planks == null || !Supply.obtainable(planks)) planks = Blocks.OAK_PLANKS;
		return new MineFrameMaterials(fence, fence.asItem(), planks, planks.asItem());
	}

	private static Block matchingPlanks(Block fence) {
		Identifier key = BuiltInRegistries.BLOCK.getKey(fence);
		String path = key.getPath();
		if (!path.endsWith("_fence")) return null;
		Identifier plankKey = Identifier.fromNamespaceAndPath(key.getNamespace(),
			path.substring(0, path.length() - "_fence".length()) + "_planks");
		Block planks = BuiltInRegistries.BLOCK.getOptional(plankKey).orElse(null);
		return planks != null && planks.defaultBlockState().is(BlockTags.PLANKS) && Supply.obtainable(planks)
			? planks : null;
	}

	static MineFrameMaterials mineFrameMaterials(Ctx c, VillageData.Mine mine) {
		Map<Long, MineFrameMaterials> byEntrance = MINE_FRAME_MATERIALS.computeIfAbsent(c.level(), ignored -> new LinkedHashMap<>());
		return byEntrance.computeIfAbsent(mine.entrance.asLong(), ignored -> {
			BlockPos bell = c.village() == null ? mine.entrance : c.village().bell();
			Palette palette = Palette.detect(c.level(), bell, c.v(), mine.entrance.asLong(), mine.entrance);
			return mineFrameMaterials(palette);
		});
	}

	/** A paid center strip and matching footing pavers mark each bay without narrowing the route. */
	private static List<BlockPos> mineGalleryPlankCells(ServerLevel level, VillageData.Mine mine, BlockPos base) {
		List<BlockPos> cells = new ArrayList<>(3);
		BlockPos walkway = base.below();
		if (mineGalleryFloorCanBePaved(level, walkway)) cells.add(walkway.immutable());
		if (supportSection(mine.index)) {
			Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
			BlockPos left = base.relative(side, -SUPPORT_RADIUS).below();
			BlockPos right = base.relative(side, SUPPORT_RADIUS).below();
			boolean leftAlreadyPaved = level.hasChunkAt(left)
				&& (level.getBlockState(left).is(Blocks.OAK_PLANKS) || level.getBlockState(left).is(Blocks.COBBLESTONE));
			boolean rightAlreadyPaved = level.hasChunkAt(right)
				&& (level.getBlockState(right).is(Blocks.OAK_PLANKS) || level.getBlockState(right).is(Blocks.COBBLESTONE));
			if (!leftAlreadyPaved && !rightAlreadyPaved
				&& mineGalleryFloorCanBePaved(level, left) && mineGalleryFloorCanBePaved(level, right)) {
				cells.add(left.immutable());
				cells.add(right.immutable());
			}
		}
		return List.copyOf(cells);
	}

	private static boolean mineGalleryFloorCanBePaved(ServerLevel level, BlockPos floor) {
		if (!terraceFloorLoaded(level, floor) || level.getBlockEntity(floor) != null) return false;
		BlockState state = level.getBlockState(floor);
		return !state.is(Blocks.OAK_PLANKS) && !state.is(Blocks.COBBLESTONE)
			&& state.getFluidState().isEmpty() && state.isFaceSturdy(level, floor, Direction.UP)
			&& VillageStyle.natural(state) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)
			&& !state.is(Blocks.DIRT_PATH) && !state.is(Blocks.FARMLAND) && !state.is(Blocks.BEDROCK)
			&& !hazard(level, floor)
			&& level.getEntitiesOfClass(LivingEntity.class, new AABB(floor)).isEmpty();
	}

	private static boolean layMineGalleryWalkway(Ctx c, VillageData.Mine mine, BlockPos base) {
		List<BlockPos> cells = mineGalleryPlankCells(c.level(), mine, base);
		if (cells.isEmpty()) return true;
		Container inventory = c.v().getInventory();
		if (JobUtil.count(inventory, stack -> stack.is(Items.OAK_PLANKS)) < cells.size()) return false;
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		for (BlockPos pos : cells) {
			BlockState state = c.level().getBlockState(pos);
			if (!mineGalleryFloorCanBePaved(c.level(), pos)) return false;
			before.put(pos, state);
		}
		for (BlockPos pos : cells) {
			c.level().setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), 3);
			if (!c.level().getBlockState(pos).is(Blocks.OAK_PLANKS)) {
				before.forEach((restore, state) -> c.level().setBlock(restore, state, 3));
				return false;
			}
		}
		if (JobUtil.take(inventory, stack -> stack.is(Items.OAK_PLANKS), cells.size()) != cells.size()) {
			before.forEach((restore, state) -> c.level().setBlock(restore, state, 3));
			return false;
		}
		return true;
	}

	private static boolean mineWallTorchSupported(ServerLevel level, BlockPos spot, Direction side, int sign) {
		Direction facing = sign > 0 ? side.getOpposite() : side;
		BlockPos wall = spot.relative(side, sign);
		if (!level.hasChunkAt(wall)) return false;
		BlockState wallState = level.getBlockState(wall);
		return wallState.isFaceSturdy(level, wall, facing) && !wallState.hasBlockEntity()
			&& !(wallState.getBlock() instanceof FallingBlock) && wallState.getFluidState().isEmpty();
	}

	// --- Anlegen --------------------------------------------------------------------------------

	private static Optional<VillageData.Mine> create(Ctx c, VillageRef ref) {
		ServerLevel level = c.level();
		int[] why = new int[7];
		for (int attempt = 0; attempt < 200; attempt++) {
			double angle = c.v().getRandom().nextDouble() * Math.PI * 2;
			double dist = 14 + c.v().getRandom().nextInt(24);
			int x = ref.bell().getX() + (int) Math.round(Math.cos(angle) * dist);
			int z = ref.bell().getZ() + (int) Math.round(Math.sin(angle) * dist);
			if (!level.hasChunkAt(new BlockPos(x, ref.bell().getY(), z))) {
				why[0]++;
				continue;
			}
			int y0 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			// An entrance high on a hill (or in a hollow) far from the village level is a poor walk for everyone: keep it reachable.
			if (Math.abs(y0 - ref.bell().getY()) > 10) {
				why[1]++;
				continue;
			}
			BlockPos entrance = new BlockPos(x, y0, z);
			BlockState ground = level.getBlockState(entrance.below());
			if (!VillageStyle.natural(ground) || !ground.getFluidState().isEmpty()) {
				why[2]++;
				continue;
			}
			if (BAD_ENTRANCES.stream().anyMatch(b -> horizontalSq(b, entrance) < 12 * 12)) {
				why[3]++;
				continue;
			}
			if (ref.data().extra.oldMines.stream().anyMatch(o -> o.entrance.distSqr(entrance) < 24 * 24)) {
				why[3]++;
				continue;
			}
			// A mine must not claim ground an active construction project (farm, granary, ...) is about to use.
			if (ref.data().buildProjects.stream().anyMatch(p -> p.active() && horizontalSq(p.origin(), entrance) < 16 * 16)
				|| ref.data().planOrigin.filter(o -> horizontalSq(o, entrance) < 16 * 16).isPresent()) {
				why[4]++;
				continue;
			}
			int targetY = Math.min(y0 - 3, Math.max(level.getMinY() + 6, y0 - 20));
			Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(c.v().getRandom());
			VillageData.Mine m = new VillageData.Mine(entrance, dir.get2DDataValue(), 0, targetY, false);
			if (!previewOk(level, m)) {
				why[5]++;
				continue;
			}
			if (!mineEntranceSiteClear(level, m)) {
				why[6]++;
				continue;
			}
			// The village must be able to walk there: an entrance on an unreachable ledge would park every miner forever.
			var route = de.villagerplus.build.Builder.workPath(c, entrance);
			if (route == null || !route.canReach()) {
				why[1]++;
				continue;
			}
			JobUtil.tellNearbyPlayers(level, c.v(), 48, Component.literal("Das Dorf legt eine Mine an."));
			return Optional.of(m);
		}
		LOGGER.info("Mine: kein Eingang gefunden, Ablehnungen [ungeladen,Hoehe,Boden,Altmine,Bauplan,Vorschau,Eingangsplatz]={}, Vorschau-Gruende={}, nicht abbaubar={}", java.util.Arrays.toString(why), java.util.Arrays.toString(PREVIEW_WHY), NONMINEABLE);
		return Optional.empty();
	}

	private static long horizontalSq(BlockPos a, BlockPos b) {
		long dx = a.getX() - b.getX();
		long dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	/** The saved centerline and both walk lanes must be dry, supported, natural, and three blocks high. */
	/**
	 * A falling block in the roof is unsafe: once the gallery below is dug it drops into the walk lane and stays there.
	 */
	private static boolean unsafeFallingRoof(ServerLevel level, BlockPos roof) {
		// Any loose block in the roof would drop into the gallery as a stray block in the walk lane that nobody clears.
		return level.getBlockState(roof).getBlock() instanceof FallingBlock;
	}

	static final java.util.Map<String, Integer> NONMINEABLE = new java.util.HashMap<>();

	static final int[] PREVIEW_WHY = new int[12];

	private static boolean previewFail(int reason) {
		PREVIEW_WHY[reason]++;
		return false;
	}

	static boolean previewOk(ServerLevel level, VillageData.Mine m) {
		int len = descent(m) + 8;
		Direction side = Direction.from2DDataValue(m.dir).getClockWise();
		for (int i = 0; i < len; i++) {
			BlockPos b = bottom(m, i);
			if (!level.hasChunkAt(b)) {
				return previewFail(0);
			}
			for (int lane = -TUNNEL_HALF_WIDTH; lane <= TUNNEL_HALF_WIDTH; lane++) {
				BlockPos column = b.relative(side, lane);
				if (!level.hasChunkAt(column) || !level.hasChunkAt(column.below())
					|| !level.getBlockState(column.below()).isFaceSturdy(level, column.below(), Direction.UP)) {
					return previewFail(1);
				}
				for (int k = 0; k < cells(m, i); k++) {
					BlockPos p = column.above(k);
					if (!level.hasChunkAt(p) || hazard(level, p)) return previewFail(2);
					BlockState state = level.getBlockState(p);
					if (!state.isAir() && !mineable(state)) {
						NONMINEABLE.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), 1, Integer::sum);
						return previewFail(3);
					}
				}
				BlockPos roof = column.above(cells(m, i));
				if (!level.hasChunkAt(roof) || hazard(level, roof) || unsafeFallingRoof(level, roof)) return previewFail(4);
			}
			if (supportSection(i)) {
				for (int sign : new int[]{-1, 1}) {
					BlockPos post = b.relative(side, sign * SUPPORT_RADIUS);
					if (!level.hasChunkAt(post.below())
						|| !level.getBlockState(post.below()).isFaceSturdy(level, post.below(), Direction.UP)) return previewFail(5);
					for (int k = 0; k < cells(m, i); k++) {
						BlockPos p = post.above(k);
						if (!level.hasChunkAt(p) || hazard(level, p)) return previewFail(6);
						BlockState state = level.getBlockState(p);
						if (!state.isAir() && !state.is(BlockTags.WOODEN_FENCES) && !mineable(state)) return previewFail(7);
					}
				}
				for (int lane = -SUPPORT_RADIUS; lane <= SUPPORT_RADIUS; lane++) {
					BlockPos beam = b.relative(side, lane).above(cells(m, i));
					if (!level.hasChunkAt(beam) || hazard(level, beam)
						|| unsafeFallingRoof(level, beam)) return previewFail(8);
					BlockState state = level.getBlockState(beam);
					if (!state.isAir() && !state.is(BlockTags.PLANKS) && !mineable(state)) return previewFail(9);
				}
			}
			if (lightSection(i)) {
				int sign = Math.floorMod(i / LIGHT_SPACING, 2) == 0 ? -1 : 1;
				BlockPos light = b.relative(side, sign * SUPPORT_RADIUS);
				BlockPos wall = light.relative(side, sign);
				if (!level.hasChunkAt(light) || !level.hasChunkAt(light.below())
					|| !level.getBlockState(light.below()).isFaceSturdy(level, light.below(), Direction.UP)
					|| !level.hasChunkAt(wall) || !mineWallTorchSupported(level, light, side, sign)
					|| hazard(level, light) || level.getBlockState(light.above()).getBlock() instanceof FallingBlock) return previewFail(10);
				BlockState state = level.getBlockState(light);
				if (!state.isAir() && !isMineTorch(state) && !mineable(state)) return previewFail(11);
			}
		}
		return true;
	}

	/** Symmetric timber-and-stone entry with a peaked canopy, built only from paid public village stock. */
	static List<MineEntranceBlock> mineEntrancePlan(VillageData.Mine mine, Palette palette) {
		Direction direction = Direction.from2DDataValue(mine.dir);
		Direction side = direction.getClockWise();
		Block base = palette.base() == null ? Blocks.COBBLESTONE : palette.base();
		Block frame = palette.frame() == null ? base : palette.frame();
		Block accent = palette.wall2() == null ? base : palette.wall2();
		Block roof = palette.roofFull() == null ? base : palette.roofFull();
		Block roofEdge = palette.roofStair() instanceof StairBlock ? palette.roofStair() : roof;
		BlockPos porch = mine.entrance.relative(direction.getOpposite());
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		for (int sign : new int[]{-2, 2}) {
			putMineEntranceBlock(blocks, mine.entrance.relative(side, sign), base.defaultBlockState());
			putMineEntranceBlock(blocks, mine.entrance.relative(side, sign).above(), mineFrameState(frame, Direction.Axis.Y));
			putMineEntranceBlock(blocks, mine.entrance.relative(side, sign).above(2), mineFrameState(frame, Direction.Axis.Y));
			putMineEntranceBlock(blocks, porch.relative(side, sign), base.defaultBlockState());
			putMineEntranceBlock(blocks, porch.relative(side, sign).above(), mineFrameState(frame, Direction.Axis.Y));
			putMineEntranceBlock(blocks, porch.relative(side, sign).above(2), mineFrameState(frame, Direction.Axis.Y));
		}
		// Outer porch piers widen the portal without narrowing its three-block pedestrian lane.
		for (int sign : new int[]{-3, 3}) {
			BlockPos pier = porch.relative(side, sign);
			putMineEntranceBlock(blocks, pier, base.defaultBlockState());
			putMineEntranceBlock(blocks, pier.above(), mineFrameState(frame, Direction.Axis.Y));
			putMineEntranceBlock(blocks, pier.above(2), mineFrameState(frame, Direction.Axis.Y));
			// A real lantern sits on the supported timber pier, visible from the road and outside the walk lane.
			putMineEntranceBlock(blocks, pier.above(3), Blocks.LANTERN.defaultBlockState());
		}
		for (int lane = -2; lane <= 2; lane++) {
			BlockPos header = mine.entrance.relative(side, lane).above(TUNNEL_CLEAR_HEIGHT);
			putMineEntranceBlock(blocks, header, lane == 0 ? accent.defaultBlockState() : mineFrameState(frame, side.getAxis()));
		}
		// The porch gets its own supported crossbeam; the entrance header alone cannot carry an offset roof.
		for (int lane = -2; lane <= 2; lane++) {
			BlockPos porchHeader = porch.relative(side, lane).above(TUNNEL_CLEAR_HEIGHT);
			putMineEntranceBlock(blocks, porchHeader, lane == 0 ? accent.defaultBlockState() : mineFrameState(frame, side.getAxis()));
		}
		// A thin eave, stepped side slopes, and a raised ridge make the portal read as a timbered mine gable.
		for (int lane = -3; lane <= 3; lane++) {
			BlockState lower;
			if (Math.abs(lane) >= 2 && palette.roofSlab() instanceof SlabBlock slab) {
				lower = slab.defaultBlockState().setValue(SlabBlock.TYPE,
					net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
			} else {
				lower = roof.defaultBlockState();
			}
			putMineEntranceBlock(blocks, porch.relative(side, lane).above(4), lower);
		}
		for (int lane = -2; lane <= 2; lane++) {
			BlockState ridge = roof.defaultBlockState();
			if (Math.abs(lane) == 1 && roofEdge instanceof StairBlock) {
				ridge = roofEdge.defaultBlockState().setValue(StairBlock.FACING, lane < 0 ? side.getOpposite() : side);
			} else if (Math.abs(lane) == 2 && roofEdge instanceof StairBlock) {
				ridge = roofEdge.defaultBlockState().setValue(StairBlock.FACING, lane < 0 ? side.getOpposite() : side);
			}
			putMineEntranceBlock(blocks, porch.relative(side, lane).above(5), ridge);
		}
		// A paid, wood-matched sign faces the road from the inner porch upright.
		BlockPos sign = mineEntranceSignPos(mine);
		putMineEntranceBlock(blocks, sign, mineEntranceSignBlock(palette).defaultBlockState()
			.setValue(net.minecraft.world.level.block.WallSignBlock.FACING, direction.getOpposite()));
		return blocks.entrySet().stream().map(entry -> new MineEntranceBlock(entry.getKey().immutable(), entry.getValue())).toList();
	}

	/** Uses the village's own wood species for the paid sign, with spruce as a safe fallback for non-wood styles. */
	private static Block mineEntranceSignBlock(Palette palette) {
		for (Block material : new Block[] {palette.door(), palette.fence(), palette.frame(), palette.roofFull(), palette.wall()}) {
			if (material == null) continue;
			var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(material);
			String path = key.getPath();
			if (path.startsWith("stripped_")) path = path.substring("stripped_".length());
			for (String suffix : List.of("_door", "_fence_gate", "_fence", "_planks", "_log", "_wood")) {
				if (!path.endsWith(suffix) || path.length() == suffix.length()) continue;
				String signPath = path.substring(0, path.length() - suffix.length()) + "_wall_sign";
				var sign = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(
					net.minecraft.resources.Identifier.fromNamespaceAndPath(key.getNamespace(), signPath));
				if (sign.isPresent()) return sign.get();
			}
		}
		return Blocks.SPRUCE_WALL_SIGN;
	}

	private static BlockState mineFrameState(Block frame, Direction.Axis axis) {
		BlockState state = frame.defaultBlockState();
		return frame instanceof net.minecraft.world.level.block.RotatedPillarBlock
			? state.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, axis) : state;
	}

	private static BlockPos mineEntranceSignPos(VillageData.Mine mine) {
		Direction direction = Direction.from2DDataValue(mine.dir);
		Direction side = direction.getClockWise();
		BlockPos porch = mine.entrance.relative(direction.getOpposite());
		return porch.relative(side, -2).relative(direction.getOpposite()).above(2);
	}

	private static boolean mineEntranceSiteClear(ServerLevel level, VillageData.Mine mine) {
		Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
		List<MineEntranceBlock> plan = mineEntrancePlan(mine, MINE_ENTRANCE_SITE_PALETTE);
		for (MineEntranceBlock target : plan) {
			BlockPos pos = target.pos();
			if (!level.hasChunkAt(pos)) return false;
			BlockState state = level.getBlockState(pos);
			if (!state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) return false;
			if (mineEntranceFooting(mine, side, pos) && !mineEntranceFootingSupported(level, pos)) return false;
		}
		return !livingIntersectsMineEntrance(level, plan.stream().map(MineEntranceBlock::pos).toList());
	}

	private static void putMineEntranceBlock(Map<BlockPos, BlockState> blocks, BlockPos pos, BlockState state) {
		blocks.put(pos.immutable(), state);
	}

	static Map<Item, Integer> mineEntranceCost(VillageData.Mine mine, Palette palette) {
		Map<Item, Integer> cost = new LinkedHashMap<>();
		for (MineEntranceBlock block : mineEntrancePlan(mine, palette)) {
			Item item = block.state().getBlock().asItem();
			if (item != Items.AIR) cost.merge(item, 1, Integer::sum);
		}
		return Map.copyOf(cost);
	}

	private static String mineEntranceKey(ServerLevel level, VillageData.Mine mine) {
		return level.dimension().identifier() + "/" + mine.entrance.asLong();
	}

	private static void ensureMineEntrance(ServerLevel level, VillageRef ref, VillageData.Mine mine, Villager hint, long now) {
		String key = mineEntranceKey(level, mine);
		if (MINE_ENTRANCE_COMPLETE.contains(key) || now < MINE_ENTRANCE_RETRY_AT.getOrDefault(key, 0L)) return;
		MINE_ENTRANCE_RETRY_AT.put(key, now + 200L);
		Palette palette = Palette.detect(level, ref.bell(), hint, mine.entrance.asLong(), mine.entrance);
		MINE_FRAME_MATERIALS.computeIfAbsent(level, ignored -> new LinkedHashMap<>())
			.putIfAbsent(mine.entrance.asLong(), mineFrameMaterials(palette));
		if (buildMineEntrance(level, ref, mine, palette)) MINE_ENTRANCE_COMPLETE.add(key);
	}

	/** Idempotent all-or-nothing build: open/legacy-owned cells may change, private structures never do. */
	/** Dev diagnostic only: remembers which guard last refused the mine entrance build. */
	static volatile String lastEntranceRejection = "";
	private static boolean rejectEntrance(String reason) {
		lastEntranceRejection = reason;
		return false;
	}

	static boolean buildMineEntrance(ServerLevel level, VillageRef ref, VillageData.Mine mine, Palette palette) {
		Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
		boolean replaceLegacyMarker = legacyMineEntrancePattern(level, mine, side);
		List<MinePortalChange> changes = new ArrayList<>();
		Map<Item, Integer> cost = new LinkedHashMap<>();
		BlockPos signPos = mineEntranceSignPos(mine);
		for (MineEntranceBlock target : mineEntrancePlan(mine, palette)) {
			BlockPos pos = target.pos();
			if (!level.hasChunkAt(pos)) return rejectEntrance("r1");
			BlockState before = level.getBlockState(pos);
			if (mineEntranceFooting(mine, side, pos) && !mineEntranceFootingSupported(level, pos)) return rejectEntrance("r2");
			// Keep an already-owned legacy sign when a refreshed biome palette selects another wood species.
			if (pos.equals(signPos) && mineEntranceSignOwned(level, signPos)) continue;
			if (before.equals(target.state())) {
				if (pos.equals(signPos) && !mineEntranceSignOwned(level, signPos)) return rejectEntrance("r3");
				continue;
			}
			if (before.hasBlockEntity() || !before.getFluidState().isEmpty()
				|| (!before.isAir() && (!replaceLegacyMarker || !legacyMineEntranceBlock(mine, side, pos, before)))) return rejectEntrance("r4");
			Item item = target.state().getBlock().asItem();
			if (item == Items.AIR) return rejectEntrance("r5");
			changes.add(new MinePortalChange(pos, before, target.state()));
			cost.merge(item, 1, Integer::sum);
		}
		// Upgrade the previous mod-owned porch lights to the wider, supported lantern piers atomically.
		if (mineEntranceSignOwned(level, signPos)) {
			Direction direction = Direction.from2DDataValue(mine.dir);
			BlockPos porch = mine.entrance.relative(direction.getOpposite());
			for (int sign : new int[] {-2, 2}) {
				BlockPos oldTorch = porch.relative(side, sign).relative(direction.getOpposite()).above(1);
				if (!level.hasChunkAt(oldTorch)) return rejectEntrance("r6");
				BlockState state = level.getBlockState(oldTorch);
				if (state.getBlock() instanceof WallTorchBlock
					&& state.getValue(WallTorchBlock.FACING) == direction.getOpposite()) {
					changes.add(new MinePortalChange(oldTorch, state, Blocks.AIR.defaultBlockState()));
				}
			}
		}
		if (changes.isEmpty()) return writeMineEntranceSign(level, signPos);
		if (livingIntersectsMineEntrance(level, changes.stream().map(MinePortalChange::pos).toList())) return rejectEntrance("r7");
		Map<PaidMineSlot, ItemStack> payment = takeMineMaterials(level, ref, cost);
		if (payment == null) return rejectEntrance("r8");
		for (MinePortalChange change : changes) {
			level.setBlock(change.pos(), change.placed(), 3);
			if (!level.getBlockState(change.pos()).equals(change.placed())) {
				for (MinePortalChange rollback : changes) level.setBlock(rollback.pos(), rollback.before(), 3);
				restoreMinePayment(payment);
				return rejectEntrance("r9");
			}
		}
		if (!writeMineEntranceSign(level, signPos)) {
			for (MinePortalChange rollback : changes) level.setBlock(rollback.pos(), rollback.before(), 3);
			restoreMinePayment(payment);
			return rejectEntrance("r10");
		}
		return true;
	}

	private static boolean mineEntranceSignOwned(ServerLevel level, BlockPos signPos) {
		if (!(level.getBlockEntity(signPos) instanceof SignBlockEntity sign)) return false;
		String text = mineSignText(sign);
		return text.contains("DORFMINE") && text.contains("STOLLEN");
	}

	private static String mineSignText(SignBlockEntity sign) {
		StringBuilder text = new StringBuilder();
		for (int line = 0; line < 4; line++) text.append(sign.getFrontText().getMessage(line, false).getString()).append(' ');
		return text.toString();
	}

	private static boolean writeMineEntranceSign(ServerLevel level, BlockPos signPos) {
		if (!level.hasChunkAt(signPos)
			|| !(level.getBlockState(signPos).getBlock() instanceof net.minecraft.world.level.block.WallSignBlock)
			|| !(level.getBlockEntity(signPos) instanceof SignBlockEntity sign)) return false;
		SignText text = sign.getFrontText()
			.setMessage(0, Component.literal("DORFMINE"))
			.setMessage(1, Component.literal("STOLLEN"))
			.setMessage(2, Component.literal("EINGANG"))
			.setColor(net.minecraft.world.item.DyeColor.YELLOW)
			.setHasGlowingText(true);
		sign.setText(text, true);
		sign.setWaxed(true);
		sign.setChanged();
		BlockState state = level.getBlockState(signPos);
		level.sendBlockUpdated(signPos, state, state, 3);
		return true;
	}

	private static boolean mineEntranceFooting(VillageData.Mine mine, Direction side, BlockPos pos) {
		BlockPos porch = mine.entrance.relative(Direction.from2DDataValue(mine.dir).getOpposite());
		for (int sign : new int[]{-2, 2}) {
			if (pos.equals(mine.entrance.relative(side, sign)) || pos.equals(porch.relative(side, sign))) return true;
		}
		for (int sign : new int[]{-3, 3}) {
			if (pos.equals(porch.relative(side, sign))) return true;
		}
		return false;
	}

	private static boolean mineEntranceFootingSupported(ServerLevel level, BlockPos footing) {
		BlockPos floor = footing.below();
		return level.hasChunkAt(floor) && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
	}

	private static boolean legacyMineEntrancePattern(ServerLevel level, VillageData.Mine mine, Direction side) {
		int beam = 0, posts = 0, lanterns = 0;
		for (int lane = -2; lane <= 2; lane++) {
			BlockPos header = mine.entrance.relative(side, lane).above(TUNNEL_CLEAR_HEIGHT);
			if (!level.hasChunkAt(header)) return false;
			if (level.getBlockState(header).is(Blocks.SPRUCE_PLANKS)) beam++;
		}
		for (int sign : new int[]{-2, 2}) {
			BlockPos post = mine.entrance.relative(side, sign);
			for (int y = 0; y <= 1; y++) {
				if (!level.hasChunkAt(post.above(y))) return false;
				if (level.getBlockState(post.above(y)).is(Blocks.SPRUCE_FENCE)) posts++;
			}
			if (!level.hasChunkAt(post.above(TUNNEL_CLEAR_HEIGHT - 1))) return false;
			BlockState lantern = level.getBlockState(post.above(TUNNEL_CLEAR_HEIGHT - 1));
			if (lantern.is(Blocks.LANTERN) && lantern.getValue(net.minecraft.world.level.block.LanternBlock.HANGING)) lanterns++;
		}
		return beam >= 3 && posts >= 2 && lanterns >= 1;
	}

	private static boolean livingIntersectsMineEntrance(ServerLevel level, List<BlockPos> cells) {
		if (cells.isEmpty()) return false;
		int minX = cells.stream().mapToInt(BlockPos::getX).min().orElseThrow();
		int minY = cells.stream().mapToInt(BlockPos::getY).min().orElseThrow();
		int minZ = cells.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
		int maxX = cells.stream().mapToInt(BlockPos::getX).max().orElseThrow();
		int maxY = cells.stream().mapToInt(BlockPos::getY).max().orElseThrow();
		int maxZ = cells.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
		AABB bounds = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
		return level.getEntitiesOfClass(LivingEntity.class, bounds, LivingEntity::isAlive).stream()
			.anyMatch(entity -> cells.stream().anyMatch(pos -> entity.getBoundingBox().intersects(new AABB(pos))));
	}

	private static boolean legacyMineEntranceBlock(VillageData.Mine mine, Direction side, BlockPos pos, BlockState state) {
		Direction direction = Direction.from2DDataValue(mine.dir);
		int dx = pos.getX() - mine.entrance.getX(), dz = pos.getZ() - mine.entrance.getZ();
		int lane = dx * side.getStepX() + dz * side.getStepZ();
		int forward = dx * direction.getStepX() + dz * direction.getStepZ();
		int y = pos.getY() - mine.entrance.getY();
		if (forward != 0) return false;
		if ((lane == -2 || lane == 2) && y >= 0 && y <= 1 && state.is(Blocks.SPRUCE_FENCE)) return true;
		if (lane >= -2 && lane <= 2 && y == TUNNEL_CLEAR_HEIGHT && state.is(Blocks.SPRUCE_PLANKS)) return true;
		return (lane == -2 || lane == 2) && y == TUNNEL_CLEAR_HEIGHT - 1
			&& state.is(Blocks.LANTERN) && state.getValue(net.minecraft.world.level.block.LanternBlock.HANGING);
	}

	/** Verschlossene Mine: das vollständige 3x3-Portal wird mit Brettern geschlossen; das Wandschild bleibt davor. */
	public static void barricade(ServerLevel level, VillageData.Mine m, boolean exhausted) {
		VillageRef ref = mineVillage(level, m);
		if (ref != null) barricade(level, ref, m, exhausted);
	}

	private static VillageRef mineVillage(ServerLevel level, VillageData.Mine mine) {
		VillageRef ref = de.villagerplus.village.Villages.villageNear(level, mine.entrance, 128);
		if (ref == null) return null;
		boolean known = ref.data().mine.filter(active -> active.entrance.equals(mine.entrance)).isPresent()
			|| ref.data().extra.oldMines.stream().anyMatch(old -> old.entrance.equals(mine.entrance));
		return known ? ref : null;
	}

	public static void barricade(ServerLevel level, VillageRef ref, VillageData.Mine m, boolean exhausted) {
		// A queued close belongs only to a completed mine. If lifecycle logic has resumed this route,
		// discard stale retries instead of closing its entrance under active workers.
		if (!m.done) {
			forgetPendingMineSeal(level, m);
			return;
		}
		barricadeNow(level, ref, m, exhausted);
		if (mineSealComplete(level, m)) {
			forgetPendingMineSeal(level, m);
			return;
		}
		long retryDelay = canSeal(level, m) ? BLOCKED_SEAL_RETRY_TICKS : OCCUPIED_SEAL_RETRY_TICKS;
		scheduleMineSealRetry(level, m, exhausted, level.getGameTime() + retryDelay);
	}

	private static void barricadeNow(ServerLevel level, VillageRef ref, VillageData.Mine m, boolean exhausted) {
		Direction dir = Direction.from2DDataValue(m.dir);
		BlockPos e = m.entrance;
		if (!canSeal(level, m)) {
			return;
		}
		Direction side = dir.getClockWise();
		int openedSections = openedMineSections(m);
		List<MinePortalChange> portal = new ArrayList<>();
		for (int lane = -TUNNEL_HALF_WIDTH; lane <= TUNNEL_HALF_WIDTH; lane++) {
			for (int y = 0; y < TUNNEL_CLEAR_HEIGHT; y++) {
				BlockPos p = e.relative(side, lane).above(y);
				if (!level.hasChunkAt(p)) return;
				BlockState state = level.getBlockState(p);
				if (mineSealTargetProtected(level, p, state)) return;
				if (state.is(Blocks.SPRUCE_PLANKS) || state.isCollisionShapeFullBlock(level, p)) continue;
				boolean legacyFenceMarker = lane == 0 && y == 2 && state.is(Blocks.SPRUCE_FENCE);
				boolean legacyGateOrDoor = state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof DoorBlock;
				if (!state.isAir() && !legacyFenceMarker && !legacyGateOrDoor) return;
				portal.add(new MinePortalChange(p, state, Blocks.SPRUCE_PLANKS.defaultBlockState()));
			}
		}
		// The saved route can cut through uneven terrain even after its entrance gate is closed.
		// Seal each exposed/intersecting route column at its local surface or saved roof height.
		// Existing full blocks are retained; partial shapes and protected cells abort the whole plan.
		MineSurfaceSealPlan surfacePlan = mineSurfaceSealPlan(level, m);
		if (!surfacePlan.loaded()) return;
		List<MinePortalChange> surfaceCover = new ArrayList<>();
		Set<Long> planned = new HashSet<>();
		for (MinePortalChange change : portal) planned.add(change.pos().asLong());
		for (BlockPos p : surfacePlan.coverCells()) {
			if (planned.contains(p.asLong())) continue;
			if (!level.hasChunkAt(p)) return;
			BlockState state = level.getBlockState(p);
			if (mineSealTargetProtected(level, p, state)) return;
			if (state.isCollisionShapeFullBlock(level, p)) continue;
			if (!state.isAir()) return;
			planned.add(p.asLong());
			if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(p)).isEmpty()) return;
			surfaceCover.add(new MinePortalChange(p, state, Blocks.SPRUCE_PLANKS.defaultBlockState()));
		}
		List<MinePortalChange> roof = new ArrayList<>();
		for (BlockPos p : surfacePlan.roofCells()) {
			if (!level.hasChunkAt(p)) return;
			BlockState state = level.getBlockState(p);
			if (mineSealTargetProtected(level, p, state)) return;
			if (state.isCollisionShapeFullBlock(level, p)) continue;
			if (!state.isAir()) return;
			if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(p)).isEmpty()) return;
			if (!planned.add(p.asLong())) continue;
			roof.add(new MinePortalChange(p, state, Blocks.SPRUCE_PLANKS.defaultBlockState()));
		}
		List<MinePortalChange> openings = new ArrayList<>(portal);
		openings.addAll(surfaceCover);
		openings.addAll(roof);
		List<MinePlankMaterial> plankPlan = planMinePlanks(level, ref, openings.size());
		if (plankPlan == null) return;
		List<MinePortalChange> sealing = new ArrayList<>(openings.size());
		Map<Item, Integer> cost = new LinkedHashMap<>();
		for (int i = 0; i < openings.size(); i++) {
			MinePortalChange opening = openings.get(i);
			MinePlankMaterial material = plankPlan.get(i);
			sealing.add(new MinePortalChange(opening.pos(), opening.before(), material.block().defaultBlockState()));
			cost.merge(material.item(), 1, Integer::sum);
		}
		Direction out = dir.getOpposite();
		BlockPos signPos = e.above(1).relative(out);
		if (!level.hasChunkAt(signPos)) return;
		BlockState existingSignState = level.getBlockState(signPos);
		SignBlockEntity oldSign = level.getBlockEntity(signPos) instanceof SignBlockEntity sign ? sign : null;
		boolean existingSign = oldSign != null;
		if (existingSign) {
			StringBuilder oldText = new StringBuilder();
			for (int line = 0; line < 4; line++) oldText.append(oldSign.getFrontText().getMessage(line, false).getString()).append(' ');
			if (!oldText.toString().contains("Dorfmine") && !oldText.toString().contains("Alte Mine")
				&& !oldText.toString().contains("VERSCHLOSSEN")) return;
			if (openings.isEmpty() && oldText.toString().contains("VERSCHLOSSEN")) return;
		}
		boolean placeSign = !existingSign;
		if (placeSign && (mineSealHasPoi(level, signPos) || existingSignState.hasBlockEntity()
			|| (!existingSignState.isAir() && !existingSignState.canBeReplaced()))) return;
		if (placeSign) cost.merge(Items.SPRUCE_SIGN, 1, Integer::sum);
		Map<PaidMineSlot, ItemStack> payment = takeMineMaterials(level, ref, cost);
		if (payment == null) return;
		for (MinePortalChange change : sealing) {
			level.setBlock(change.pos(), change.placed(), 3);
			if (!level.getBlockState(change.pos()).is(change.placed().getBlock())) {
				rollbackMinePortal(level, sealing, signPos, existingSignState, placeSign, payment);
				return;
			}
		}
		if (placeSign) {
			level.setBlock(signPos, Blocks.SPRUCE_WALL_SIGN.defaultBlockState()
				.setValue(net.minecraft.world.level.block.WallSignBlock.FACING, out), 3);
		}
		if (!(level.getBlockEntity(signPos) instanceof SignBlockEntity sign)) {
			rollbackMinePortal(level, sealing, signPos, existingSignState, placeSign, payment);
			return;
		}
		SignText text = sign.getFrontText()
			.setMessage(0, Component.literal("[VERSCHLOSSEN"))
			.setMessage(1, Component.literal(exhausted ? "- ERSCHÖPFT]" : "- GEFAHR]"))
			.setMessage(3, Component.literal("Alte Mine"))
			.setColor(net.minecraft.world.item.DyeColor.RED)
			.setHasGlowingText(true);
		sign.setText(text, true);
		sign.setWaxed(true);
		sign.setChanged();
		level.playSound(null, e, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
	}

	static void scheduleMineSealRetry(ServerLevel level, VillageData.Mine mine, boolean exhausted, long retryAt) {
		PENDING_MINE_SEALS.computeIfAbsent(level, ignored -> new LinkedHashMap<>())
			.put(mine.entrance.asLong(), new PendingMineSeal(mine, exhausted, retryAt));
	}

	private static void forgetPendingMineSeal(ServerLevel level, VillageData.Mine mine) {
		Map<Long, PendingMineSeal> pending = PENDING_MINE_SEALS.get(level);
		if (pending == null) return;
		pending.remove(mine.entrance.asLong());
		if (pending.isEmpty()) PENDING_MINE_SEALS.remove(level);
	}

	private static void tickPendingMineSeals(ServerLevel level) {
		tickPendingMineSeals(level, level.getGameTime());
	}

	/** Testable bounded retry: an occupied mine is sealed as soon as its saved passage is clear. */
	static void tickPendingMineSeals(ServerLevel level, long now) {
		Map<Long, PendingMineSeal> pending = PENDING_MINE_SEALS.get(level);
		if (pending == null || pending.isEmpty()) return;
		var iterator = pending.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Long, PendingMineSeal> entry = iterator.next();
			PendingMineSeal retry = entry.getValue();
			if (now < retry.retryAt()) continue;
			VillageData.Mine mine = retry.mine();
			if (!mine.done) {
				iterator.remove();
				continue;
			}
			if (mineSealComplete(level, mine)) {
				iterator.remove();
				continue;
			}
			VillageRef ref = mineVillage(level, mine);
			if (ref == null || !level.hasChunkAt(mine.entrance)) {
				entry.setValue(new PendingMineSeal(mine, retry.exhausted(), now + BLOCKED_SEAL_RETRY_TICKS));
				continue;
			}
			if (!canSeal(level, mine)) {
				entry.setValue(new PendingMineSeal(mine, retry.exhausted(), now + OCCUPIED_SEAL_RETRY_TICKS));
				continue;
			}
			barricadeNow(level, ref, mine, retry.exhausted());
			if (mineSealComplete(level, mine)) iterator.remove();
			else entry.setValue(new PendingMineSeal(mine, retry.exhausted(), now + BLOCKED_SEAL_RETRY_TICKS));
		}
		if (pending.isEmpty()) PENDING_MINE_SEALS.remove(level);
	}

	static boolean mineSealComplete(ServerLevel level, VillageData.Mine mine) {
		Direction dir = Direction.from2DDataValue(mine.dir);
		Direction side = dir.getClockWise();
		for (int lane = -TUNNEL_HALF_WIDTH; lane <= TUNNEL_HALF_WIDTH; lane++) {
			for (int y = 0; y < TUNNEL_CLEAR_HEIGHT; y++) {
				BlockPos pos = mine.entrance.relative(side, lane).above(y);
				if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) return false;
			}
		}
		MineSurfaceSealPlan surfacePlan = mineSurfaceSealPlan(level, mine);
		if (!surfacePlan.loaded()) return false;
		for (BlockPos pos : surfacePlan.coverCells()) {
			if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) return false;
		}
		for (BlockPos roof : surfacePlan.roofCells()) {
			if (!level.hasChunkAt(roof) || !level.getBlockState(roof).isCollisionShapeFullBlock(level, roof)) return false;
		}
		return true;
	}

	static int openedMineSections(VillageData.Mine mine) {
		return Math.max(1, Math.min(mine.index, descent(mine) + MAX_HORIZONTAL));
	}

	/** Deterministic walkable surface footprint of the saved route, at the top block of the original terrain. */
	static List<BlockPos> mineSurfaceCoverCells(VillageData.Mine mine) {
		List<BlockPos> cells = new ArrayList<>();
		int surfaceY = mine.entrance.getY() - 1;
		int openedSections = openedMineSections(mine);
		for (int section = 0; section < openedSections; section++) {
			BlockPos base = bottom(mine, section);
			if (base.getY() + TUNNEL_CLEAR_HEIGHT < surfaceY) break;
			Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
			for (int lane = -TUNNEL_HALF_WIDTH; lane <= TUNNEL_HALF_WIDTH; lane++) {
				BlockPos column = base.relative(side, lane);
				cells.add(new BlockPos(column.getX(), surfaceY, column.getZ()));
			}
		}
		return List.copyOf(cells);
	}

	/**
	 * Plans the surface intersection and underground roof for every saved route column. Terrain height is sampled
	 * outside the three-wide excavation, since a heightmap read inside the shaft would mistake the tunnel floor
	 * for the original surface. Fully exposed columns are filled through the passage volume; intersecting columns
	 * get a full collision cap at the local surface; buried columns get a roof at the actual saved tunnel ceiling.
	 */
	static MineSurfaceSealPlan mineSurfaceSealPlan(ServerLevel level, VillageData.Mine mine) {
		List<BlockPos> cover = new ArrayList<>();
		List<BlockPos> roof = new ArrayList<>();
		Set<Long> coverSeen = new HashSet<>();
		Set<Long> roofSeen = new HashSet<>();
		Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
		for (int section = 0; section < openedMineSections(mine); section++) {
			BlockPos base = bottom(mine, section);
			for (int lane = -TUNNEL_HALF_WIDTH; lane <= TUNNEL_HALF_WIDTH; lane++) {
				BlockPos column = base.relative(side, lane);
				int surfaceY = mineTerrainSurfaceY(level, base, side, lane);
				if (surfaceY == Integer.MIN_VALUE) return new MineSurfaceSealPlan(false, List.of(), List.of());
				int roofY = base.getY() + TUNNEL_CLEAR_HEIGHT;
				if (surfaceY <= base.getY()) {
					for (int y = 0; y < TUNNEL_CLEAR_HEIGHT; y++) addUnique(cover, coverSeen, column.above(y));
					if (section > 0) addUnique(cover, coverSeen, column.above(TUNNEL_CLEAR_HEIGHT));
				} else if (surfaceY <= roofY) {
					// Heightmap surfaceY is the first air block above terrain. Mining cleared that
					// ground cell where the saved route crosses the surface, so restore the cap one
					// block below it. Capping at surfaceY leaves a walkable-height gap into the shaft.
					addUnique(cover, coverSeen, new BlockPos(column.getX(), surfaceY - 1, column.getZ()));
				} else {
					addUnique(roof, roofSeen, column.above(TUNNEL_CLEAR_HEIGHT));
				}
			}
		}
		return new MineSurfaceSealPlan(true, List.copyOf(cover), List.copyOf(roof));
	}

	private static int mineTerrainSurfaceY(ServerLevel level, BlockPos base, Direction side, int lane) {
		int probeLane = lane < 0 ? -3 : lane > 0 ? 3 : -3;
		BlockPos first = base.relative(side, probeLane);
		if (!level.hasChunkAt(first)) return Integer.MIN_VALUE;
		int firstY = mineTerrainSurfaceY(level, first);
		if (lane != 0) return firstY;
		BlockPos second = base.relative(side, 3);
		if (!level.hasChunkAt(second)) return Integer.MIN_VALUE;
		return Math.max(firstY, mineTerrainSurfaceY(level, second));
	}

	private static int mineTerrainSurfaceY(ServerLevel level, BlockPos sample) {
		int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample.getX(), sample.getZ());
		while (surfaceY > level.getMinY()
			&& level.getBlockState(new BlockPos(sample.getX(), surfaceY - 1, sample.getZ()))
				.is(net.minecraft.tags.BlockTags.OVERWORLD_NATURAL_LOGS)) {
			surfaceY--;
		}
		return surfaceY;
	}

	private static void addUnique(List<BlockPos> cells, Set<Long> seen, BlockPos pos) {
		if (seen.add(pos.asLong())) cells.add(pos.immutable());
	}

	private static boolean mineSealTargetProtected(ServerLevel level, BlockPos pos, BlockState state) {
		return state.hasBlockEntity() || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()
			|| mineSealHasPoi(level, pos);
	}

	private static boolean mineSealHasPoi(ServerLevel level, BlockPos pos) {
		return level.getPoiManager().getInRange(holder -> true, pos, 0,
			net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isPresent();
	}

	/** Prefer one stocked wood type; combine real public plank types only when no single type covers the plan. */
	private static List<MinePlankMaterial> planMinePlanks(ServerLevel level, VillageRef ref, int needed) {
		if (needed == 0) return List.of();
		Set<Container> stores = publicMineStores(level, ref);
		Map<Item, Integer> available = new LinkedHashMap<>();
		for (MinePlankMaterial material : MINE_PLANKS) {
			int count = 0;
			for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (!stack.isEmpty() && stack.is(material.item())) count += stack.getCount();
			}
			available.put(material.item(), count);
		}
		List<MinePlankMaterial> choices = new ArrayList<>(MINE_PLANKS);
		choices.sort((left, right) -> Integer.compare(available.get(right.item()), available.get(left.item())));
		if (available.values().stream().mapToInt(Integer::intValue).sum() < needed) return null;
		List<MinePlankMaterial> plan = new ArrayList<>(needed);
		for (MinePlankMaterial material : choices) {
			int take = Math.min(needed - plan.size(), available.get(material.item()));
			for (int i = 0; i < take; i++) plan.add(material);
			if (plan.size() == needed) break;
		}
		return plan.size() == needed ? List.copyOf(plan) : null;
	}

	private static Set<Container> publicMineStores(ServerLevel level, VillageRef ref) {
		Set<Container> stores = new LinkedHashSet<>();
		for (BlockPos pos : Lager.materialChests(level, ref)) {
			Container box = Lager.publicContainerAt(level, pos);
			if (box != null) stores.add(box);
		}
		return stores;
	}

	/** All-or-nothing withdrawal from public village material stores; private chests are filtered by Lager. */
	private static Map<PaidMineSlot, ItemStack> takeMineMaterials(ServerLevel level, VillageRef ref, Map<Item, Integer> cost) {
		Set<Container> stores = publicMineStores(level, ref);
		for (var entry : cost.entrySet()) {
			int stock = 0;
			for (Container box : stores) for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack stack = box.getItem(slot);
				if (!stack.isEmpty() && stack.is(entry.getKey())) stock += stack.getCount();
			}
			if (stock < entry.getValue()) return null;
		}
		Map<PaidMineSlot, ItemStack> before = new LinkedHashMap<>();
		for (var entry : cost.entrySet()) {
			int remaining = entry.getValue();
			for (Container box : stores) {
				for (int slot = 0; slot < box.getContainerSize() && remaining > 0; slot++) {
					ItemStack stack = box.getItem(slot);
					if (stack.isEmpty() || !stack.is(entry.getKey())) continue;
					PaidMineSlot key = new PaidMineSlot(box, slot);
					before.putIfAbsent(key, stack.copy());
					int amount = Math.min(remaining, stack.getCount());
					if (box.removeItem(slot, amount).getCount() != amount) {
						restoreMinePayment(before);
						return null;
					}
					remaining -= amount;
				}
				if (remaining == 0) break;
			}
			if (remaining != 0) {
				restoreMinePayment(before);
				return null;
			}
		}
		for (PaidMineSlot slot : before.keySet()) slot.container().setChanged();
		return before;
	}

	private static void rollbackMinePortal(ServerLevel level, List<MinePortalChange> portal, BlockPos signPos,
		BlockState oldSignState, boolean placedSign, Map<PaidMineSlot, ItemStack> payment) {
		for (MinePortalChange change : portal) level.setBlock(change.pos(), change.before(), 3);
		if (placedSign) level.setBlock(signPos, oldSignState, 3);
		restoreMinePayment(payment);
	}

	private static void restoreMinePayment(Map<PaidMineSlot, ItemStack> payment) {
		for (var entry : payment.entrySet()) {
			entry.getKey().container().setItem(entry.getKey().slot(), entry.getValue().copy());
			entry.getKey().container().setChanged();
		}
	}

	/**
	 * Kompatibilitaetspunkt fuer aeltere Aufrufer: ein beendeter Eingang bleibt erhalten und
	 * wird gesichert, statt Portalbloecke oder Schilder zu entfernen.
	 */
	public static void clearEntrance(ServerLevel level, VillageData.Mine m) {
		if (m.done) {
			barricade(level, m, true);
		}
	}

	/**
	 * Raeumt Altlasten frueherer Versionen auf: Stehende Eichenschilder "Dorfmine" und "Dorftafel" samt Laternenpfosten rund um das Dorf.
	 * Neue Mineneingaenge verwenden ein bezahltes Fichten-Wandschild am gerahmten Vorbau und werden hiervon nicht erfasst.
	 */
	public static int sweepLegacyMarkers(ServerLevel level, BlockPos bell) {
		int removed = 0;
		for (int x = bell.getX() - 90; x <= bell.getX() + 90; x++) {
			for (int z = bell.getZ() - 90; z <= bell.getZ() + 90; z++) {
				if (!level.hasChunkAt(new BlockPos(x, bell.getY(), z))) {
					continue;
				}
				int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos p = null;
				for (int yy = top + 2; yy >= top - 2; yy--) {
					if (level.getBlockState(new BlockPos(x, yy, z)).is(Blocks.OAK_SIGN)) {
						p = new BlockPos(x, yy, z);
						break;
					}
				}
				if (p == null) {
					continue;
				}
				if (!(level.getBlockEntity(p) instanceof SignBlockEntity sign)) {
					continue;
				}
				String text = "";
				for (int i = 0; i < 4; i++) {
					text += sign.getFrontText().getMessage(i, false).getString() + " ";
				}
				if (!text.contains("Dorfmine") && !text.contains("Dorftafel")) {
					continue;
				}
				level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
				removed++;
				for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, -1, -2), p.offset(2, 1, 2))) {
					if (level.getBlockState(q).is(Blocks.OAK_FENCE) && level.getBlockState(q.above()).is(Blocks.LANTERN) && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) {
						BlockPos fq = q.immutable();
						level.setBlock(fq.above(), Blocks.AIR.defaultBlockState(), 3);
						level.setBlock(fq, Blocks.AIR.defaultBlockState(), 3);
						break;
					}
				}
			}
		}
		return removed;
	}

	// --- Abbauen --------------------------------------------------------------------------------

	private static boolean isOre(BlockState s) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().endsWith("_ore");
	}

	static boolean hazard(ServerLevel level, BlockPos p) {
		if (!hazardAreaLoaded(level, p)) return true;
		BlockState s = level.getBlockState(p);
		if (s.hasBlockEntity() || s.getDestroySpeed(level, p) < 0) {
			return true;
		}
		for (Direction d : Direction.values()) {
			if (!level.getBlockState(p.relative(d)).getFluidState().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Plans every excavation for one visible section before any blocks or drops are changed. */
	static MineSectionPlan planSection(Ctx c, VillageData.Mine mine) {
		ServerLevel level = c.level();
		MineFrameMaterials materials = mineFrameMaterials(c, mine);
		BlockPos base = bottom(mine, mine.index);
		int height = cells(mine, mine.index);
		Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
		Set<BlockPos> blocks = new LinkedHashSet<>();
		for (int lane = -TUNNEL_HALF_WIDTH; lane <= TUNNEL_HALF_WIDTH; lane++) {
			BlockPos column = base.relative(side, lane);
			BlockPos floor = column.below();
			if (!level.hasChunkAt(floor)) return new MineSectionPlan(false, false, List.of());
			if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
				return new MineSectionPlan(true, false, List.of());
			}
			for (int k = 0; k < height; k++) {
				BlockPos p = column.above(k);
				if (!hazardAreaLoaded(level, p)) return new MineSectionPlan(false, false, List.of());
				if (hazard(level, p)) return new MineSectionPlan(true, false, List.of());
				BlockState state = level.getBlockState(p);
				if (!state.isAir()) {
					if (!mineable(state)) return new MineSectionPlan(true, false, List.of());
					blocks.add(p);
				}
			}
			BlockPos roof = column.above(height);
			if (!hazardAreaLoaded(level, roof)) return new MineSectionPlan(false, false, List.of());
			if (hazard(level, roof) || unsafeFallingRoof(level, roof)) {
				return new MineSectionPlan(true, false, List.of());
			}
		}

		if (supportSection(mine.index)) {
			for (int sign : new int[]{-1, 1}) {
				BlockPos post = base.relative(side, sign * SUPPORT_RADIUS);
				BlockPos floor = post.below();
				if (!level.hasChunkAt(floor)) return new MineSectionPlan(false, false, List.of());
				if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
					return new MineSectionPlan(true, false, List.of());
				}
				for (int k = 0; k < height; k++) {
					BlockPos p = post.above(k);
					if (!hazardAreaLoaded(level, p)) return new MineSectionPlan(false, false, List.of());
					if (hazard(level, p)) return new MineSectionPlan(true, false, List.of());
					BlockState state = level.getBlockState(p);
					if (!state.isAir() && !state.is(materials.fence())) {
						if (!mineable(state)) return new MineSectionPlan(true, false, List.of());
						blocks.add(p);
					}
				}
			}
			for (int lane = -SUPPORT_RADIUS; lane <= SUPPORT_RADIUS; lane++) {
				BlockPos beam = base.relative(side, lane).above(height);
				if (!hazardAreaLoaded(level, beam)) return new MineSectionPlan(false, false, List.of());
				if (hazard(level, beam) || unsafeFallingRoof(level, beam)) {
					return new MineSectionPlan(true, false, List.of());
				}
				BlockState state = level.getBlockState(beam);
				if (!state.isAir() && !state.is(materials.planks())) {
					if (!mineable(state)) return new MineSectionPlan(true, false, List.of());
					blocks.add(beam);
				}
			}
		}

		if (lightSection(mine.index)) {
			int sign = Math.floorMod(mine.index / LIGHT_SPACING, 2) == 0 ? -1 : 1;
			BlockPos spot = base.relative(side, sign * SUPPORT_RADIUS);
			BlockPos wall = spot.relative(side, sign);
			if (!level.hasChunkAt(spot.below())) return new MineSectionPlan(false, false, List.of());
			if (!level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP)) {
				return new MineSectionPlan(true, false, List.of());
			}
			if (!level.hasChunkAt(wall)) return new MineSectionPlan(false, false, List.of());
			if (!mineWallTorchSupported(level, spot, side, sign)) return new MineSectionPlan(true, false, List.of());
			if (!hazardAreaLoaded(level, spot)) return new MineSectionPlan(false, false, List.of());
			if (hazard(level, spot) || level.getBlockState(spot.above()).getBlock() instanceof FallingBlock) {
				return new MineSectionPlan(true, false, List.of());
			}
			BlockState state = level.getBlockState(spot);
			if (!state.isAir() && !isMineTorch(state)) {
				if (!mineable(state)) return new MineSectionPlan(true, false, List.of());
				blocks.add(spot);
			}
		}
		return new MineSectionPlan(true, true, List.copyOf(blocks));
	}

	static void dig(Ctx c, VillageRef ref, VillageData.Mine m, ItemStack tool) {
		ServerLevel level = c.level();
		BlockPos base = bottom(m, m.index);
		int cells = cells(m, m.index);
		Direction lateral = Direction.from2DDataValue(m.dir).getClockWise();
		// Keep all three terraced walking lanes on real supported ground; a missing one-block
		// landing is repaired only with the miner's cobblestone, never with a free placement.
		for (int lane = -TUNNEL_HALF_WIDTH; lane <= TUNNEL_HALF_WIDTH; lane++) {
			BlockPos floor = base.relative(lateral, lane).below();
			if (!terraceFloorLoaded(level, floor)) {
				c.setActivity("wartet auf geladenen Minenabschnitt");
				return;
			}
			if (!patchFloor(c, floor)) {
				if (oneBlockTerraceGap(level, floor)) {
					c.setActivity("wartet auf Bruchstein für eine sichere Minenterrasse");
					return;
				}
				finishMine(c, ref, m, "Die Mine wurde geschlossen (Hohlraum unter dem Stollenprofil).");
				return;
			}
		}
		MineSectionPlan plan = planSection(c, m);
		if (!plan.loaded()) {
			c.setActivity("wartet auf geladenen Minenabschnitt");
			return;
		}
		if (!plan.safe()) {
			finishMine(c, ref, m, "Die Mine wurde wegen eines unsicheren oder geschützten Stollenabschnitts geschlossen.");
			return;
		}
		List<BlockPos> dug = new ArrayList<>();
		List<BlockPos> toBreak = plan.blocks();
		if (!fits(c.v().getInventory(), dropsFor(c, toBreak, tool))) {
			requestCapacityReturn(c, m);
			return;
		}
		for (BlockPos p : toBreak) if (breakBlock(c, p, tool)) dug.add(p);
		JobUtil.swing(c.v());
		shape(c, m, base, cells);
		// Erzadern, die den Stollen beruehren, komplett mitnehmen.
		for (BlockPos p : dug) {
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (isOre(level.getBlockState(n))) {
					mineVein(c, n, tool);
				}
			}
		}
		c.mem().jobsDone++;
		m.index++;
		if (m.index >= descent(m) + MAX_HORIZONTAL) {
			finishMine(c, ref, m, "Die Mine ist ausgebeutet.");
		}
		ref.dirty();
	}

	/** Three-wide, three-high gallery with a paid floor strip, paired bay footings, fence ribs, roof beams, and wall lights. */
	static void shape(Ctx c, VillageData.Mine m, BlockPos base, int cells) {
		shape(c, m, base, cells, mineFrameMaterials(c, m));
	}

	static void shape(Ctx c, VillageData.Mine m, BlockPos base, int cells, MineFrameMaterials materials) {
		ServerLevel level = c.level();
		Direction dir = Direction.from2DDataValue(m.dir);
		Direction side = dir.getClockWise();
		layMineGalleryWalkway(c, m, base);
		if (supportSection(m.index)) {
			for (int sign : new int[]{-1, 1}) {
				BlockPos post = base.relative(side, sign * SUPPORT_RADIUS);
				for (int k = 0; k < cells; k++) {
					BlockPos position = post.above(k);
					if (level.getBlockState(position).isAir()
						&& JobUtil.count(c.v().getInventory(), stack -> stack.is(materials.fenceItem())) > 0
						&& level.setBlock(position, materials.fence().defaultBlockState(), 3)) {
						JobUtil.take(c.v().getInventory(), stack -> stack.is(materials.fenceItem()), 1);
					}
				}
			}
			for (int lane = -SUPPORT_RADIUS; lane <= SUPPORT_RADIUS; lane++) {
				BlockPos beam = base.relative(side, lane).above(cells);
				if (level.getBlockState(beam).isAir()
					&& JobUtil.count(c.v().getInventory(), stack -> stack.is(materials.plankItem())) > 0
					&& level.setBlock(beam, materials.planks().defaultBlockState(), 3)) {
					JobUtil.take(c.v().getInventory(), stack -> stack.is(materials.plankItem()), 1);
				}
			}
		}
		if (lightSection(m.index)) {
			int sign = Math.floorMod(m.index / LIGHT_SPACING, 2) == 0 ? -1 : 1;
			BlockPos spot = base.relative(side, sign * SUPPORT_RADIUS);
			Direction facing = sign > 0 ? side.getOpposite() : side;
			if (level.getBlockState(spot).isAir() && mineWallTorchSupported(level, spot, side, sign)
				&& JobUtil.count(c.v().getInventory(), stack -> stack.is(Items.TORCH)) > 0
				&& level.setBlock(spot, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, facing), 3)) {
				JobUtil.take(c.v().getInventory(), stack -> stack.is(Items.TORCH), 1);
			}
		}
	}

	private static boolean isMineTorch(BlockState state) {
		return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
	}

	/** Grabt eine Saeule der Hoehe h, wenn das gefahrlos geht. false = hier nicht (weitere Zellen dahinter auch nicht). */
	private static boolean carveColumn(Ctx c, BlockPos bottom, int h, List<BlockPos> dug) {
		ServerLevel level = c.level();
		BlockPos floor = bottom.below();
		if (!level.hasChunkAt(bottom) || !terraceFloorLoaded(level, floor)) {
			return false;
		}
		if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
			return false;
		}
		for (int k = 0; k < h; k++) {
			BlockPos p = bottom.above(k);
			if (!hazardAreaLoaded(level, p)) {
				return false;
			}
			if (hazard(level, p)) {
				return false;
			}
		}
		if (unsafeFallingRoof(level, bottom.above(h))) {
			return false;
		}
		for (int k = 0; k < h; k++) {
			BlockPos p = bottom.above(k);
			if (breakBlock(c, p, pickaxe(c.v().getInventory()))) {
				dug.add(p);
			}
		}
		return true;
	}

	static boolean breakBlock(Ctx c, BlockPos p, ItemStack tool) {
		ServerLevel level = c.level();
		BlockState s = level.getBlockState(p);
		if (!usablePickaxe(tool)
			|| s.isAir() || !s.getFluidState().isEmpty() || !mineable(s)) {
			return false;
		}
		List<ItemStack> drops = Block.getDrops(s, level, p, null, c.v(), tool.copy());
		if (!fits(c.v().getInventory(), drops)) return false;
		if (!level.setBlock(p, Blocks.AIR.defaultBlockState(), 3)) return false;
		Anim.blockBreak(level, p, s);
		for (ItemStack d : drops) JobUtil.give(level, c.v(), d.copy());
		return true;
	}

	private static boolean mineable(BlockState state) {
		// Desert and badlands bedrock under the sand layer: sandstone and red sandstone are ordinary diggable rock.
		// Surface plants in the way (dead bushes, dry grass, cactus) are cleared like any other block; fluids never are.
		if (state.getFluidState().isEmpty() && (state.is(BlockTags.REPLACEABLE) || state.is(Blocks.CACTUS)) && !state.isAir()) return true;
		return VillageStyle.natural(state) || isOre(state) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
			|| state.is(net.minecraft.tags.BlockTags.OVERWORLD_NATURAL_LOGS);
	}

	private static void mineVein(Ctx c, BlockPos start, ItemStack tool) {
		ServerLevel level = c.level();
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		seen.add(start.asLong());
		int mined = 0;
		while (!queue.isEmpty() && mined < VEIN_LIMIT) {
			BlockPos p = queue.poll();
			if (!level.hasChunkAt(p)) continue;
			if (!isOre(level.getBlockState(p)) || hazard(level, p)) {
				continue;
			}
			if (breakBlock(c, p, tool)) {
				mined++;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (level.hasChunkAt(n) && seen.add(n.asLong())) {
					queue.add(n);
				}
			}
		}
	}

	static ItemStack pickaxe(Container inventory) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (usablePickaxe(stack)) return stack;
		}
		return ItemStack.EMPTY;
	}

	private static boolean usablePickaxe(ItemStack stack) {
		return !stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.PICKAXES)
			&& (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
	}

	private static List<ItemStack> dropsFor(Ctx c, List<BlockPos> positions, ItemStack tool) {
		List<ItemStack> drops = new ArrayList<>();
		for (BlockPos pos : positions) {
			BlockState state = c.level().getBlockState(pos);
			if (!state.isAir()) drops.addAll(Block.getDrops(state, c.level(), pos, null, c.v(), tool.copy()));
		}
		return drops;
	}

	private static boolean fits(Container inventory, List<ItemStack> drops) {
		SimpleContainer simulation = new SimpleContainer(inventory.getContainerSize());
		for (int i = 0; i < inventory.getContainerSize(); i++) simulation.setItem(i, inventory.getItem(i).copy());
		for (ItemStack drop : drops) if (!simulation.addItem(drop.copy()).isEmpty()) return false;
		return true;
	}

	/**
	 * Predicts only the current safe, central mine section. This lets a miner turn around before
	 * digging when its real drops would overflow, then retry the same saved section after delivery.
	 * It deliberately reads loaded blocks only and never repairs or loads terrain during the check.
	 */
	static List<ItemStack> capacityBlockedSectionDrops(Ctx c, VillageData.Mine mine, ItemStack tool) {
		ServerLevel level = c.level();
		MineSectionPlan plan = planSection(c, mine);
		if (!plan.loaded() || !plan.safe() || plan.blocks().isEmpty()) {
			return List.of();
		}
		List<ItemStack> drops = dropsFor(c, plan.blocks(), tool);
		return fits(c.v().getInventory(), drops) ? List.of() : drops;
	}

	/** Mining product match used only during a demonstrated capacity block; supports and tools stay with the miner. */
	static boolean matchesMiningOutput(ItemStack stack, List<ItemStack> pendingDrops) {
		if (stack.isEmpty()) return false;
		for (ItemStack drop : pendingDrops) {
			if (!drop.isEmpty() && drop.getItem() == stack.getItem()) return true;
		}
		if (stack.is(Items.COBBLESTONE) || stack.is(Items.FLINT)
			|| stack.is(Items.COAL) || stack.is(Items.RAW_IRON) || stack.is(Items.RAW_COPPER)
			|| stack.is(Items.RAW_GOLD) || stack.is(Items.DIAMOND) || stack.is(Items.EMERALD)
			|| stack.is(Items.LAPIS_LAZULI) || stack.is(Items.REDSTONE) || stack.is(Items.QUARTZ)
			|| stack.is(Items.AMETHYST_SHARD)) {
			return true;
		}
		Block block = Block.byItem(stack.getItem());
		return block != Blocks.AIR && mineable(block.defaultBlockState());
	}

	private static void requestCapacityReturn(Ctx c, VillageData.Mine mine) {
		c.st().miningUntil = c.now();
		c.st().mineCheckAt = c.now() + 40;
		c.setActivity("kehrt wegen voller Taschen sicher zum Dorflager zurück");
		if (c.v().getY() < mine.entrance.getY() - 0.5 && nearest(mine, c.v().position()) >= 1) {
			exitStep(c);
		}
	}

	/** The active work section only proceeds when its visible support or light can be paid from real stock. */
	private static MineSupplies mineSuppliesForNextSection(ServerLevel level, VillageData.Mine mine,
			MineFrameMaterials materials) {
		BlockPos base = bottom(mine, mine.index);
		int cells = cells(mine, mine.index);
		Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
		int walkwayPlanks = mineGalleryPlankCells(level, mine, base).size();
		if (supportSection(mine.index)) {
			int fences = 0;
			int framePlanks = 0;
			for (int sign : new int[]{-1, 1}) {
				BlockPos post = base.relative(side, sign * SUPPORT_RADIUS);
				for (int k = 0; k < cells; k++) {
					BlockPos target = post.above(k);
					if (level.hasChunkAt(target) && !level.getBlockState(target).is(materials.fence())) fences++;
				}
			}
			for (int lane = -SUPPORT_RADIUS; lane <= SUPPORT_RADIUS; lane++) {
				BlockPos beam = base.relative(side, lane).above(cells);
				if (level.hasChunkAt(beam) && !level.getBlockState(beam).is(materials.planks())) framePlanks++;
			}
			return new MineSupplies(fences, framePlanks, walkwayPlanks, 0);
		}
		if (lightSection(mine.index)) {
			int sign = Math.floorMod(mine.index / LIGHT_SPACING, 2) == 0 ? -1 : 1;
			BlockPos torch = base.relative(side, sign * SUPPORT_RADIUS);
			return new MineSupplies(0, 0, walkwayPlanks,
				level.hasChunkAt(torch) && !isMineTorch(level.getBlockState(torch)) ? 1 : 0);
		}
		return new MineSupplies(0, 0, walkwayPlanks, 0);
	}

	private static MineSupplies missingMineSupplies(Container inventory, MineSupplies required, MineFrameMaterials materials) {
		int fenceCount = JobUtil.count(inventory, stack -> stack.is(materials.fenceItem()));
		int framePlankCount = JobUtil.count(inventory, stack -> stack.is(materials.plankItem()));
		int oakPlankCount = JobUtil.count(inventory, stack -> stack.is(Items.OAK_PLANKS));
		int missingFramePlanks = Math.max(0, required.framePlanks() - framePlankCount);
		int availableForWalkway = materials.plankItem() == Items.OAK_PLANKS
			? Math.max(0, framePlankCount - required.framePlanks()) : oakPlankCount;
		return new MineSupplies(
			Math.max(0, required.fences() - fenceCount),
			missingFramePlanks,
			Math.max(0, required.walkwayPlanks() - availableForWalkway),
			Math.max(0, required.torches() - JobUtil.count(inventory, stack -> stack.is(Items.TORCH))));
	}

	private static List<ItemStack> mineSupplyStacks(MineSupplies supplies, MineFrameMaterials materials) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		if (supplies.fences() > 0) counts.merge(materials.fenceItem(), supplies.fences(), Integer::sum);
		if (supplies.framePlanks() > 0) counts.merge(materials.plankItem(), supplies.framePlanks(), Integer::sum);
		if (supplies.walkwayPlanks() > 0) counts.merge(Items.OAK_PLANKS, supplies.walkwayPlanks(), Integer::sum);
		if (supplies.torches() > 0) counts.merge(Items.TORCH, supplies.torches(), Integer::sum);
		List<ItemStack> stacks = new ArrayList<>(counts.size());
		counts.forEach((item, count) -> stacks.add(new ItemStack(item, count)));
		return stacks;
	}

	/** Craft only a missing mine kit item, using the server's loaded recipe and then one real prerequisite at a time. */
	private static boolean craftMineSupply(Ctx c, Container chest, BlockPos chestPos, Container inventory,
			net.minecraft.world.item.Item item, int required) {
		int target = Math.max(0, required - JobUtil.count(inventory, stack -> stack.is(item)));
		int existing = JobUtil.count(chest, stack -> stack.is(item));
		if (target == 0 || existing >= target) return false;
		// Recipes are atomic batches (for example 4 planks, 3 fences, or 4 torches). Allow
		// exactly one loaded recipe batch beyond the current stock so a small remaining kit
		// requirement cannot deadlock, while keeping any surplus in the shared chest.
		int batch = largestMineSupplyRecipeBatch(c, item);
		if (batch == 0) batch = new ItemStack(item).getMaxStackSize();
		int outputCap = existing + batch;
		return RecipeCrafting.craft(c, chest, chestPos, stack -> stack.is(item), outputCap)
			|| RecipeCrafting.craftPrerequisite(c, chest, chestPos, item);
	}

	private static int largestMineSupplyRecipeBatch(Ctx c, net.minecraft.world.item.Item item) {
		int largest = 0;
		for (var holder : c.level().recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe recipe)) continue;
			ItemStack preview = RecipeCrafting.preview(recipe);
			if (preview != null && preview.is(item)) largest = Math.max(largest, preview.getCount());
		}
		return largest;
	}

	private static boolean craftMineSupplies(Ctx c, Container chest, BlockPos chestPos, Container inventory,
			MineSupplies required, MineFrameMaterials materials) {
		int beamAndWalkway = required.framePlanks()
			+ (materials.plankItem() == Items.OAK_PLANKS ? required.walkwayPlanks() : 0);
		return craftMineSupply(c, chest, chestPos, inventory, materials.fenceItem(), required.fences())
			|| craftMineSupply(c, chest, chestPos, inventory, materials.plankItem(), beamAndWalkway)
			|| (materials.plankItem() != Items.OAK_PLANKS
				&& craftMineSupply(c, chest, chestPos, inventory, Items.OAK_PLANKS, required.walkwayPlanks()))
			|| craftMineSupply(c, chest, chestPos, inventory, Items.TORCH, required.torches());
	}

	/**
	 * Supply state of the next section including the ten-minute fallback. Once the fallback fired for a section the restock
	 * logic is not called at all: its trips (leave the gallery, walk to the depot) used to run in the same tick as the order to
	 * walk to the work cell, and the resident shuttled between the entrance and the depot without ever digging.
	 */
	static void resetKitWaitForTest() {
		KIT_WAIT_SINCE.clear();
		KIT_WAIT_INDEX.clear();
		KIT_WAIT_SPENT.clear();
		KIT_WAIT_LAST.clear();
		KIT_SKIP_UNTIL.clear();
	}

	static SupplyStatus kitStatus(Ctx c, VillageRef ref, VillageData.Mine m) {
		long entranceKey = m.entrance.asLong();
		Integer waitIndex = KIT_WAIT_INDEX.get(entranceKey);
		if (waitIndex != null && waitIndex != m.index) {
			KIT_WAIT_SINCE.remove(entranceKey);
			KIT_WAIT_INDEX.remove(entranceKey);
			KIT_WAIT_SPENT.remove(entranceKey);
		}
		Long since = KIT_WAIT_SINCE.get(entranceKey);
		if (since != null && since > c.now()) { // stale entry from another world/session
			KIT_WAIT_SINCE.remove(entranceKey);
			KIT_WAIT_SPENT.remove(entranceKey);
			since = null;
		}
		// After a section was released without its kit the depot evidently cannot supply one: the next sections are not held up
		// again one by one for the same missing wood or coal (each hold-up used to cost about a working day) until the hold-off ends.
		Long skipUntil = KIT_SKIP_UNTIL.get(entranceKey);
		if (skipUntil != null && c.now() < skipUntil && skipUntil - c.now() <= KIT_SKIP_TICKS) return SupplyStatus.READY;
		if (since != null && KIT_WAIT_SPENT.getOrDefault(entranceKey, 0L) > KIT_WAIT_TICKS) {
			KIT_SKIP_UNTIL.put(entranceKey, c.now() + KIT_SKIP_TICKS);
			return SupplyStatus.READY;
		}
		SupplyStatus supplies = ensureMineSupplies(c, ref, m);
		if (supplies == SupplyStatus.READY) {
			KIT_WAIT_SINCE.remove(entranceKey);
			KIT_WAIT_INDEX.remove(entranceKey);
			KIT_WAIT_SPENT.remove(entranceKey);
		} else {
			// Only time the miners actually spend on the kit counts (restock trips, crafting, waiting at the depot): the nights and
			// rest periods in between do not, so the limit is a share of a working day and not a share of the calendar.
			Long last = KIT_WAIT_LAST.get(entranceKey);
			long step = last == null || last > c.now() ? 0L : Math.min(KIT_WAIT_STEP_CAP, c.now() - last);
			if (since == null) {
				KIT_WAIT_SINCE.put(entranceKey, c.now());
				KIT_WAIT_SPENT.put(entranceKey, 0L);
			} else {
				KIT_WAIT_SPENT.merge(entranceKey, step, Long::sum);
			}
			KIT_WAIT_LAST.put(entranceKey, c.now());
			KIT_WAIT_INDEX.put(entranceKey, m.index);
		}
		return supplies;
	}

	/** Restock at the village chest before the next structural section; excavation never conjures its supports or light. */
	private static SupplyStatus ensureMineSupplies(Ctx c, VillageRef ref, VillageData.Mine mine) {
		MineFrameMaterials materials = mineFrameMaterials(c, mine);
		MineSupplies required = mineSuppliesForNextSection(c.level(), mine, materials);
		Container inventory = c.v().getInventory();
		MineSupplies missing = missingMineSupplies(inventory, required, materials);
		// Supports, ribs and torches stop the digging; the paved walkway strip does not (a village without oak planks must not
		// wait ten minutes for one), so a section that needs no structure is dug at once with what the resident carries.
		if (missing.structural().empty()) return SupplyStatus.READY;
		if (c.v().getY() < mine.entrance.getY() - 0.5 && nearest(mine, c.v().position()) >= 1) {
			// Leave by the saved route first; the movement guard keeps the return trip inside the gallery.
			c.st().miningUntil = c.now();
			c.st().mineCheckAt = c.now() + 40;
			c.setActivity("kehrt für echte Stollenstützen und Fackeln zurück");
			exitStep(c);
			return SupplyStatus.BUSY;
		}
		BlockPos chestPos = ref.chestPos(c.level());
		Container chest = ref.chest(c.level());
		if (chestPos == null || chest == null) {
			c.setActivity("wartet auf Stützen- und Fackelmaterial im Dorflager");
			return SupplyStatus.WAITING;
		}
		List<ItemStack> transfer = mineSupplyStacks(missing.structural(), materials);
		if (!fits(inventory, transfer)) {
			if (JobUtil.depositSurplus(c, Mining::mineOutputForKitRoom, 1, 0)) {
				c.setActivity("bringt Minenerträge zum Lager, um Platz für Stollenmaterial zu schaffen");
				return SupplyStatus.BUSY;
			}
			c.setActivity("wartet auf freien Platz für Stollenmaterial");
			return SupplyStatus.WAITING;
		}
		if (!JobUtil.approachLong(c, chestPos, 4.5)) {
			c.setActivity("holt Stollenmaterial aus dem Dorflager");
			return SupplyStatus.BUSY;
		}
		// Recheck at the chest so a changed plan/cursor cannot withdraw an obsolete kit.
		VillageData.Mine current = ref.data().mine.orElse(mine);
		required = mineSuppliesForNextSection(c.level(), current, materials);
		missing = missingMineSupplies(inventory, required, materials);
		if (missing.structural().empty()) return SupplyStatus.READY;
		if (!hasMineSupplies(chest, missing.structural(), materials)) {
			// Small villages often have logs/coal but no pre-made fence/torch stock. Build just
			// the next missing input through loaded recipes while keeping the saved mine cursor
			// parked; intermediate crafting progress is real chest inventory and survives saves.
			// The walkway strip is only crafted where it is made of the frame's own planks (oak villages).
			MineSupplies craftFor = materials.plankItem() == Items.OAK_PLANKS ? required : required.structural();
			if (craftMineSupplies(c, chest, chestPos, inventory, craftFor, materials)) {
				missing = missingMineSupplies(inventory, required, materials);
				if (!hasMineSupplies(chest, missing.structural(), materials)) return SupplyStatus.BUSY;
			} else {
				c.setActivity("wartet auf Stützen- und Fackelmaterial im Dorflager");
				return SupplyStatus.WAITING;
			}
		}
		// One trip serves the next sections too: besides the next section's structure it takes, as far as the depot holds them
		// COMPLETE, the structures of the following few sections and the walkway planks. Nothing is crafted or reserved for that.
		Map<Item, Integer> need = mineSupplyItems(missing.structural(), materials);
		Map<Item, Integer> grab = new LinkedHashMap<>(need);
		MineSupplies cumulative = required.structural();
		int last = Math.min(current.index + KIT_LOOKAHEAD_SECTIONS, descent(current) + MAX_HORIZONTAL);
		for (int i = current.index + 1; i < last; i++) {
			VillageData.Mine ahead = new VillageData.Mine(current.entrance, current.dir, i, current.targetY, false);
			MineSupplies section = mineSuppliesForNextSection(c.level(), ahead, materials).structural();
			if (section.empty()) continue;
			MineSupplies candidate = cumulative.plus(section);
			Map<Item, Integer> items = mineSupplyItems(missingMineSupplies(inventory, candidate, materials), materials);
			boolean stocked = true;
			for (Map.Entry<Item, Integer> entry : items.entrySet()) {
				if (JobUtil.count(chest, stack -> stack.is(entry.getKey())) < entry.getValue()) stocked = false;
			}
			if (!stocked) break;
			cumulative = candidate;
			grab = items;
		}
		int walkway = missing.walkwayPlanks();
		if (walkway > 0) {
			int spare = JobUtil.count(chest, stack -> stack.is(Items.OAK_PLANKS)) - grab.getOrDefault(Items.OAK_PLANKS, 0);
			if (spare > 0) grab.merge(Items.OAK_PLANKS, Math.min(walkway, spare), Integer::sum);
		}
		if (!fits(inventory, stacksOf(grab))) grab = need;
		if (!fits(inventory, stacksOf(grab))) {
			c.setActivity("wartet auf freien Platz für Stollenmaterial");
			return SupplyStatus.WAITING;
		}
		if (!takeMineItems(chest, grab)) {
			c.setActivity("wartet auf Stützen- und Fackelmaterial im Dorflager");
			return SupplyStatus.WAITING;
		}
		for (ItemStack stack : stacksOf(grab)) JobUtil.give(c.level(), c.v(), stack);
		ref.dirty();
		Anim.hold(c, missing.fences() > 0 ? materials.fenceItem()
			: missing.framePlanks() > 0 ? materials.plankItem() : Items.TORCH);
		c.st().cooldownUntil = c.now() + 10;
		c.setActivity("nimmt echte Stollenstützen und Fackeln mit");
		return SupplyStatus.BUSY;
	}

	private static Map<Item, Integer> mineSupplyItems(MineSupplies supplies, MineFrameMaterials materials) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		for (ItemStack stack : mineSupplyStacks(supplies, materials)) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
		return counts;
	}

	private static List<ItemStack> stacksOf(Map<Item, Integer> counts) {
		List<ItemStack> stacks = new ArrayList<>(counts.size());
		counts.forEach((item, count) -> { if (count > 0) stacks.add(new ItemStack(item, count)); });
		return stacks;
	}

	/** Takes exactly these items from the shared chest or nothing at all. */
	private static boolean takeMineItems(Container chest, Map<Item, Integer> items) {
		List<ItemStack> before = new ArrayList<>(chest.getContainerSize());
		for (int i = 0; i < chest.getContainerSize(); i++) before.add(chest.getItem(i).copy());
		for (Map.Entry<Item, Integer> entry : items.entrySet()) {
			if (entry.getValue() <= 0) continue;
			if (JobUtil.take(chest, stack -> stack.is(entry.getKey()), entry.getValue()) != entry.getValue()) {
				for (int i = 0; i < before.size(); i++) chest.setItem(i, before.get(i));
				return false;
			}
		}
		return true;
	}

	private static boolean hasMineSupplies(Container chest, MineSupplies supplies, MineFrameMaterials materials) {
		int requestedMatchingPlanks = supplies.framePlanks()
			+ (materials.plankItem() == Items.OAK_PLANKS ? supplies.walkwayPlanks() : 0);
		return JobUtil.count(chest, stack -> stack.is(materials.fenceItem())) >= supplies.fences()
			&& JobUtil.count(chest, stack -> stack.is(materials.plankItem())) >= requestedMatchingPlanks
			&& (materials.plankItem() == Items.OAK_PLANKS
				|| JobUtil.count(chest, stack -> stack.is(Items.OAK_PLANKS)) >= supplies.walkwayPlanks())
			&& JobUtil.count(chest, stack -> stack.is(Items.TORCH)) >= supplies.torches();
	}

	static boolean mineOutputForKitRoom(ItemStack stack) {
		// Keep cobblestone available for the paid one-block terrace repairs later in this route.
		return !stack.is(Items.COBBLESTONE) && matchesMiningOutput(stack, List.of());
	}

	/**
	 * Kleine Luecke im Boden (Trampelpfad, Grasstufe, ein Block Hoehle) mit Bruchstein schliessen statt die Mine aufzugeben.
	 * Echte Hohlraeume (zwei Bloecke frei) bleiben ein Grund zum Schliessen.
	 */
	static boolean patchFloor(Ctx c, BlockPos floor) {
		ServerLevel level = c.level();
		if (!terraceFloorLoaded(level, floor) || level.getBlockEntity(floor) != null) {
			return false;
		}
		BlockState s = level.getBlockState(floor);
		if (s.isFaceSturdy(level, floor, Direction.UP)) {
			return true;
		}
		if (!s.getFluidState().isEmpty()) {
			return false;
		}
		BlockPos under = floor.below();
		if (s.isAir()
			&& level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) {
			Container inventory = c.v().getInventory();
			// Cobblestone first; if the miner holds none (it all went to the public chest) any plain rock or soil it dug itself will do.
			// Nothing is conjured: the block comes out of the miner's real inventory.
			Item fill = null;
			for (Item candidate : TERRACE_FILL) {
				if (JobUtil.count(inventory, stack -> stack.is(candidate)) >= 1) { fill = candidate; break; }
			}
			if (fill == null) return false;
			Item used = fill;
			if (level.setBlock(floor, Block.byItem(used).defaultBlockState(), 3)) {
				JobUtil.take(inventory, stack -> stack.is(used), 1);
				return true;
			}
		}
		return false;
	}

	/** Tick's entry guard: do not let safeCell mistake an unloaded support for a fatal hole. */
	static boolean waitForUnloadedApproach(Ctx c, BlockPos cell) {
		if (c.level().hasChunkAt(cell) && terraceFloorLoaded(c.level(), cell.below())) {
			return false;
		}
		c.setActivity("wartet auf geladenen Minenabschnitt");
		return true;
	}

	/** A single dry landing gap can be repaired; deeper openings are hazards, never a terrace. */
	static boolean oneBlockTerraceGap(ServerLevel level, BlockPos floor) {
		if (!terraceFloorLoaded(level, floor) || level.getBlockEntity(floor) != null) {
			return false;
		}
		BlockState state = level.getBlockState(floor);
		BlockPos below = floor.below();
		return state.isAir() && state.getFluidState().isEmpty()
			&& level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	/** Never inspect or mutate a landing in an unloaded chunk; both blocks participate in the step check. */
	private static boolean terraceFloorLoaded(ServerLevel level, BlockPos floor) {
		return level.hasChunkAt(floor) && level.hasChunkAt(floor.below());
	}

	/** Every state inspected by hazard() must already be in a loaded chunk. */
	private static boolean hazardAreaLoaded(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return false;
		for (Direction direction : Direction.values()) {
			if (!level.hasChunkAt(pos.relative(direction))) return false;
		}
		return true;
	}

	/** Geschlossene Schaechte erhalten; den Eingang erst nach dem Ausstieg aller Lebewesen versperren. */
	public static void tidyOldMines(ServerLevel level, VillageData d) {
		for (VillageData.Mine m : allMines(d)) {
			if (!m.done) continue;
			VillageRef ref = mineVillage(level, m);
			if (ref != null && ref.data() == d) barricade(level, ref, m, m.index >= descent(m) + MAX_HORIZONTAL);
		}
	}

	/** The normal village tick already knows the exact owner; use it to avoid charging a neighbouring village. */
	public static void tidyOldMines(ServerLevel level, VillageRef ref) {
		for (VillageData.Mine m : allMines(ref.data())) {
			if (m.done) barricade(level, ref, m, m.index >= descent(m) + MAX_HORIZONTAL);
		}
	}

	/** Archiviert einen abgeschlossenen Schacht genau einmal und behaelt seinen vollstaendigen Datensatz. */
	private static void archiveMine(VillageData d, VillageData.Mine mine) {
		if (d.extra.oldMines.stream().noneMatch(old -> old.entrance.equals(mine.entrance))) {
			d.extra.oldMines.add(mine);
		}
	}

	/** Auch Seitenstollen und noch ungeladene Abschnitte duerfen niemanden hinter der Barrikade einschliessen. */
	static boolean canSeal(ServerLevel level, VillageData.Mine m) {
		List<AABB> passages = new ArrayList<>();
		Direction dir = Direction.from2DDataValue(m.dir);
		AABB bounds = new AABB(m.entrance);
		// Match the seal plan's saved-route bound so a malformed oversized index cannot
		// make closure wait forever for chunks beyond the maximum mine the worker can open.
		for (int i = 0; i < openedMineSections(m); i++) {
			BlockPos base = bottom(m, i);
			AABB passage = new AABB(base).inflate(1, 0, 1).expandTowards(0, cells(m, i) - 1, 0);
			passages.add(passage);
			bounds = bounds.minmax(passage);
			if (i >= descent(m) && i % 7 == 5) {
				Direction side = (i / 7) % 2 == 0 ? dir.getCounterClockWise() : dir.getClockWise();
				AABB branch = new AABB(base.relative(side, 2)).minmax(new AABB(base.relative(side, 7))).expandTowards(0, 1, 0);
				passages.add(branch);
				bounds = bounds.minmax(branch);
			}
		}
		for (int x = net.minecraft.util.Mth.floor(bounds.minX) >> 4; x <= net.minecraft.util.Mth.floor(bounds.maxX) >> 4; x++) {
			for (int z = net.minecraft.util.Mth.floor(bounds.minZ) >> 4; z <= net.minecraft.util.Mth.floor(bounds.maxZ) >> 4; z++) {
				if (!level.hasChunkAt(new BlockPos(x << 4, m.entrance.getY(), z << 4))) return false;
			}
		}
		return level.getEntitiesOfClass(LivingEntity.class, bounds, e -> e.isAlive()
			&& passages.stream().anyMatch(area -> area.intersects(e.getBoundingBox()))).isEmpty();
	}

	/** Entrances that never worked (no path, nothing dug): not archived, only avoided when choosing a new site. */
	private static final java.util.Set<BlockPos> BAD_ENTRANCES = new java.util.HashSet<>();

	private static void finishMine(Ctx c, VillageRef ref, VillageData.Mine m, String message) {
		m.done = true;
		// An entrance nobody ever dug into is not an "old mine": remember it as a bad spot (this session) instead of
		// walling it up and fencing off the surrounding land for good, so the village can pick another site.
		boolean unused = m.index == 0;
		if (unused) {
			if (BAD_ENTRANCES.size() > 256) BAD_ENTRANCES.clear();
			BAD_ENTRANCES.add(m.entrance);
		} else {
			archiveMine(ref.data(), m);
		}
		if (ref.data().mine.filter(active -> active == m).isPresent()) {
			ref.data().mine = Optional.empty();
		}
		ref.dirty();
		if (!unused) barricade(c.level(), ref, m, message.contains("ausgebeutet"));
		c.st().mineCheckAt = c.now() + 12000;
		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Villagers: Repurposed: Mine bei {} beendet bei Abschnitt {}: {}", m.entrance, m.index, message);
		JobUtil.tellNearbyPlayers(c.level(), c.v(), 48, Component.literal(message));
		c.level().playSound(null, m.entrance, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.5F, 1.0F);
	}

	/** Zum Ueberprufen der Mine von aussen (z.B. Statuskommando). */
	public static String describe(VillageData d) {
		if (d.mine.isEmpty()) {
			return "keine";
		}
		VillageData.Mine m = d.mine.get();
		return (m.done ? "beendet" : "aktiv") + " bei " + m.entrance.toShortString() + ", Abschnitt " + m.index;
	}

	private static MineRoute mineRoute(Villager villager) {
		return villager.getAttached(VillagerPlus.MINING_ROUTE);
	}

	private static boolean isWaitingRoute(MineRoute route) {
		return route != null && route.index() < 0;
	}

	private static int waitingSlot(MineRoute route) {
		return isWaitingRoute(route) ? -route.index() - 1 : -1;
	}

	private static boolean routeMatchesMine(MineRoute route, ServerLevel level, VillageData.Mine mine) {
		return route != null && route.dimension().equals(level.dimension().identifier().toString())
			&& route.entrance().equals(mine.entrance) && route.dir() == mine.dir && route.targetY() == mine.targetY;
	}

	static boolean activeSurfaceReservation(MineRoute route, ServerLevel level, VillageData.Mine mine,
			long miningUntil, long now) {
		return route != null && route.index() >= 0 && mine != null && !mine.done
			&& routeMatchesMine(route, level, mine) && now < route.reservationUntil() && now < miningUntil;
	}

	/** Keep a single new surface route moving through the portal; established overlap is resolved consistently. */
	static boolean mineEntranceAdmissionBlocked(Ctx c, VillageData.Mine mine) {
		if (c == null || c.v() == null || c.level() == null || mine == null
			|| inMineArea(mine, c.v())) return false;
		MineRoute current = mineRoute(c.v());
		boolean currentHasAdmission = routeMatchesMine(current, c.level(), mine) && current.index() >= 0;
		AABB portal = new AABB(mine.entrance).inflate(0.35, 0.15, 0.35);
		for (Villager other : mineRouteWorkers(c.level(), mine)) {
			if (other == c.v()) continue;
			MineRoute route = mineRoute(other);
			if (route == null || route.index() < 0 || inMineArea(mine, other)) continue;
			// Only residents actually at the portal compete for the single admission lane. A holder still crossing the village
			// (or stuck far away) would otherwise lock every other miner out of the shaft for good.
			double dx = other.getX() - (mine.entrance.getX() + 0.5);
			double dz = other.getZ() - (mine.entrance.getZ() + 0.5);
			if (dx * dx + dz * dz > 12.0 * 12.0) continue;
			if (!currentHasAdmission) return true;
			if (portal.intersects(other.getBoundingBox()) && portal.intersects(c.v().getBoundingBox())
				&& other.getUUID().compareTo(c.v().getUUID()) < 0) return true;
		}
		return false;
	}

	private static AABB mineWorkerBounds(VillageData.Mine mine) {
		BlockPos routeEnd = mine.entrance.relative(Direction.from2DDataValue(mine.dir),
			descent(mine) + MAX_HORIZONTAL + 8);
		double minX = Math.min(mine.entrance.getX(), routeEnd.getX()) - WAITING_STAND_SCAN_MARGIN;
		double maxX = Math.max(mine.entrance.getX(), routeEnd.getX()) + WAITING_STAND_SCAN_MARGIN + 1.0;
		double minZ = Math.min(mine.entrance.getZ(), routeEnd.getZ()) - WAITING_STAND_SCAN_MARGIN;
		double maxZ = Math.max(mine.entrance.getZ(), routeEnd.getZ()) + WAITING_STAND_SCAN_MARGIN + 1.0;
		return new AABB(minX, mine.targetY - 4.0, minZ, maxX, mine.entrance.getY() + 5.0, maxZ);
	}

	private static List<Villager> mineRouteWorkers(ServerLevel level, VillageData.Mine mine) {
		// Residents still walking in from the village hold a lane too (their route lease), otherwise they all get the same free stand.
		return level.getEntitiesOfClass(Villager.class, mineWorkerBounds(mine).inflate(96.0, 40.0, 96.0), villager -> {
			MineRoute route = mineRoute(villager);
			return villager.isAlive() && routeMatchesMine(route, level, mine)
				// An expired lease may release a surface reservation, but an underground owner keeps its lane
				// until it has actually walked out. Otherwise a paused miner and its replacement can share a stand.
				&& (route.reservationUntil() > level.getGameTime()
					// Below the entrance height is not enough: someone standing on the hillside outside the shaft is not underground.
					|| inMineArea(mine, villager));
		});
	}

	static int liveMineRouteWorkerCount(ServerLevel level, VillageData.Mine mine) {
		return level == null || mine == null ? 0 : mineRouteWorkers(level, mine).size();
	}

	private static boolean hasOtherActiveMineWorker(ServerLevel level, Villager current, VillageData.Mine mine) {
		for (Villager other : mineRouteWorkers(level, mine)) {
			MineRoute route = mineRoute(other);
			if (other != current && route != null && route.index() >= 0) return true;
		}
		return false;
	}

	private static boolean hasOtherMineWorkerInWorkSlot(ServerLevel level, Villager current,
			VillageData.Mine mine, int slot) {
		for (Villager other : mineRouteWorkers(level, mine)) {
			MineRoute route = mineRoute(other);
			if (other != current && route != null && route.index() >= 0
					&& workSlotForRoute(route, other) == slot) return true;
		}
		return false;
	}

	static int preferredMineWorkSlot(UUID villagerId) {
		return Math.floorMod(villagerId.hashCode(), MINE_WORK_STAND_COUNT);
	}

	/** Three supported lanes in the actual tunnel section; no assignment can point outside the shaft. */
	static BlockPos mineWorkStand(VillageData.Mine mine, int section, int slot) {
		if (mine == null || section < 0 || section > mine.index || slot < 0 || slot >= MINE_WORK_STAND_COUNT) return null;
		int lane = slot == 0 ? 0 : slot == 1 ? -1 : 1;
		return bottom(mine, section).relative(Direction.from2DDataValue(mine.dir).getClockWise(), lane);
	}

	private static BlockPos nextMineWorkWaypoint(VillageData.Mine mine, int current, int target, int slot) {
		int next = current < 0 ? 0 : current + Integer.signum(target - current);
		return mineWorkStand(mine, next, slot);
	}

	private static boolean atMineWorkStand(Villager villager, BlockPos stand) {
		return stand != null && villager.position().distanceToSqr(Vec3.atBottomCenterOf(stand)) <= 0.30 * 0.30;
	}

	private static int assignedMineWorkSlot(Ctx c, VillageData.Mine mine) {
		if (mineEntranceAdmissionBlocked(c, mine)) return -1;
		MineRoute current = mineRoute(c.v());
		if (routeMatchesMine(current, c.level(), mine) && current.index() >= 0
				&& current.workSlot() >= 0 && current.workSlot() < MINE_WORK_STAND_COUNT
				&& mineWorkStandAvailable(c, mine, current.workSlot()) && !slotClaimedByHigherRank(c, mine, current.workSlot())) {
			return current.workSlot();
		}
		return findMineWorkSlot(c, mine);
	}

		/** Two residents on the same lane block each other forever: the one with the smaller UUID keeps it, the other gives it up. */
	private static boolean slotClaimedByHigherRank(Ctx c, VillageData.Mine mine, int slot) {
		for (Villager other : mineRouteWorkers(c.level(), mine)) {
			if (other == c.v() || !holdsLaneNow(mine, other)) continue;
			MineRoute route = mineRoute(other);
			if (route != null && route.index() >= 0 && workSlotForRoute(route, other) == slot
				&& other.getUUID().compareTo(c.v().getUUID()) < 0) return true;
		}
		return false;
	}

	/** A resident still far from the shaft (walking in from the village) does not hold a work lane yet; arrivals are sorted out by rank. */
	private static boolean holdsLaneNow(VillageData.Mine mine, Villager other) {
		if (inMineArea(mine, other)) return true;
		double dx = other.getX() - (mine.entrance.getX() + 0.5);
		double dz = other.getZ() - (mine.entrance.getZ() + 0.5);
		return dx * dx + dz * dz <= 20.0 * 20.0;
	}

	static int findMineWorkSlot(Ctx c, VillageData.Mine mine) {
		if (c == null || mine == null || mineEntranceAdmissionBlocked(c, mine)) return -1;
		List<Villager> routeWorkers = mineRouteWorkers(c.level(), mine);
		Set<Integer> occupied = new HashSet<>();
		for (Villager other : routeWorkers) {
			if (other == c.v() || !holdsLaneNow(mine, other)) continue;
			MineRoute route = mineRoute(other);
			if (route != null && route.index() >= 0) occupied.add(workSlotForRoute(route, other));
		}
		AABB nearMine = new AABB(mine.entrance).inflate(WAITING_STAND_SCAN_MARGIN, 4, WAITING_STAND_SCAN_MARGIN);
		List<LivingEntity> nearby = c.level().getEntitiesOfClass(LivingEntity.class, nearMine,
			entity -> entity != c.v() && entity.isAlive());
		int section = Math.max(0, mine.index - 1);
		int preferred = preferredMineWorkSlot(c.v().getUUID());
		for (int offset = 0; offset < MINE_WORK_STAND_COUNT; offset++) {
			int slot = (preferred + offset) % MINE_WORK_STAND_COUNT;
			if (occupied.contains(slot) || !mineWorkStandAvailable(c, mine, slot, section, routeWorkers, nearby)) continue;
			BlockPos stand = mineWorkStand(mine, section, slot);
			// The pathfinder cannot plan across a whole village: from far away the long overland approach is walked in legs
			// later, so only a near resident has to prove the route now.
			if (c.v().blockPosition().distSqr(stand) > 24.0 * 24.0) return slot;
			var path = c.v().getNavigation().createPath(stand, 1);
			if (path != null && path.canReach()) return slot;
			// Vanilla navigation often fails on sandy dunes; the long-route search used for the quarry still finds a way.
			var alternative = de.villagerplus.build.Builder.workPath(c, stand);
			if (alternative != null && alternative.canReach()) return slot;
		}
		return -1;
	}

	/** Dev diagnostic only: explains per work slot why findMineWorkSlot refuses it. */
	static String workSlotDiagnostics(Ctx c, VillageData.Mine mine) {
		StringBuilder out = new StringBuilder("admissionBlocked=").append(mineEntranceAdmissionBlocked(c, mine));
		List<Villager> routeWorkers = mineRouteWorkers(c.level(), mine);
		AABB nearMine = new AABB(mine.entrance).inflate(WAITING_STAND_SCAN_MARGIN, 4, WAITING_STAND_SCAN_MARGIN);
		List<LivingEntity> nearby = c.level().getEntitiesOfClass(LivingEntity.class, nearMine,
			entity -> entity != c.v() && entity.isAlive());
		int section = Math.max(0, mine.index - 1);
		for (int slot = 0; slot < MINE_WORK_STAND_COUNT; slot++) {
			BlockPos stand = mineWorkStand(mine, section, slot);
			out.append(" | slot").append(slot).append('@').append(stand == null ? "null" : stand.toShortString());
			if (stand == null) continue;
			boolean occupied = false;
			for (Villager other : routeWorkers) {
				MineRoute route = mineRoute(other);
				if (other != c.v() && route != null && route.index() >= 0 && workSlotForRoute(route, other) == slot) occupied = true;
			}
			var path = c.v().getNavigation().createPath(stand, 0);
			out.append(" occ=").append(occupied).append(" landing=").append(safeMineLanding(c.level(), stand))
				.append(" safe=").append(safeCell(c, stand)).append(" avail=")
				.append(mineWorkStandAvailable(c, mine, slot, section, routeWorkers, nearby))
				.append(" path=").append(path == null ? "null" : String.valueOf(path.canReach()));
		}
		for (Villager w : routeWorkers) {
			MineRoute r = mineRoute(w);
			out.append(" || Halter ").append(w.getName().getString()).append('@').append(w.blockPosition().toShortString())
				.append(" slot=").append(r == null ? -1 : r.workSlot()).append(" idx=").append(r == null ? -1 : r.index())
				.append(" reserviertBis=").append(r == null ? -1 : r.reservationUntil() - c.level().getGameTime());
		}
		return out.toString();
	}

	private static int workSlotForRoute(MineRoute route, Villager villager) {
		return route.workSlot() >= 0 && route.workSlot() < MINE_WORK_STAND_COUNT
			? route.workSlot() : preferredMineWorkSlot(villager.getUUID());
	}

	private static boolean mineWorkStandAvailable(Ctx c, VillageData.Mine mine, int slot) {
		List<Villager> routeWorkers = mineRouteWorkers(c.level(), mine);
		AABB nearMine = new AABB(mine.entrance).inflate(WAITING_STAND_SCAN_MARGIN, 4, WAITING_STAND_SCAN_MARGIN);
		List<LivingEntity> nearby = c.level().getEntitiesOfClass(LivingEntity.class, nearMine,
			entity -> entity != c.v() && entity.isAlive());
		return mineWorkStandAvailable(c, mine, slot, Math.max(0, mine.index - 1), routeWorkers, nearby);
	}

	private static boolean mineWorkStandAvailable(Ctx c, VillageData.Mine mine, int slot, int section,
			List<Villager> routeWorkers, List<LivingEntity> nearby) {
		BlockPos stand = mineWorkStand(mine, section, slot);
		if (stand == null || !safeMineLanding(c.level(), stand) || !safeCell(c, stand)) return false;
		for (Villager other : routeWorkers) {
			if (other == c.v() || !holdsLaneNow(mine, other)) continue;
			MineRoute route = mineRoute(other);
			if (route != null && route.index() >= 0 && workSlotForRoute(route, other) == slot) return false;
		}
		// Reserve the actual villager-sized landing volume. A nearly full 1x2 block cube made
		// adjacent one-block-spaced lanes overlap, which left only one of the three usable.
		AABB standVolume = c.v().getBoundingBox().move(
			stand.getX() + 0.5 - c.v().getX(),
			stand.getY() - c.v().getY(),
			stand.getZ() + 0.5 - c.v().getZ());
		for (LivingEntity other : nearby) {
			if (other.getBoundingBox().intersects(standVolume)) return false;
			if (other instanceof Villager villager && !villager.getNavigation().isDone()
					&& stand.equals(villager.getNavigation().getTargetPos())) return false;
		}
		return true;
	}

	private static boolean mineExitTurn(Ctx c, VillageData.Mine mine, int currentIndex) {
		for (Villager other : mineRouteWorkers(c.level(), mine)) {
			if (other == c.v()) continue;
			MineRoute route = mineRoute(other);
			if (route == null || route.index() < 0 || other.getY() >= mine.entrance.getY() - 0.5) continue;
			int otherIndex = nearest(mine, other.position());
			if (otherIndex < 0) otherIndex = nearestForReturnRecovery(mine, other.position());
			if (otherIndex >= 0 && (otherIndex < currentIndex
				|| otherIndex == currentIndex && other.getUUID().compareTo(c.v().getUUID()) < 0)) return false;
		}
		return true;
	}

	/** Fixed surface pads outside the portal; two-block spacing keeps even wide villager hitboxes apart. */
	static BlockPos mineWaitingStand(ServerLevel level, VillageData.Mine mine, int slot) {
		if (slot < 0 || slot >= WAITING_STAND_COUNT) return null;
		int sideGroup = slot / 3;
		int sign = sideGroup % 2 == 0 ? -1 : 1;
		// The porch row (one block behind the portal) carries the outer timber piers and lantern posts at lateral +-3,
		// so the pads start at +-4 instead of overlapping them.
		int lateralDistance = 4 + (sideGroup / 2) * 2;
		int forwardDistance = 1 + (slot % 3) * 2;
		Direction direction = Direction.from2DDataValue(mine.dir);
		Direction side = direction.getClockWise();
		BlockPos sample = mine.entrance.relative(direction.getOpposite(), forwardDistance)
			.relative(side, sign * lateralDistance);
		if (!level.hasChunkAt(new BlockPos(sample.getX(), mine.entrance.getY(), sample.getZ()))) return null;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample.getX(), sample.getZ());
		if (Math.abs(y - mine.entrance.getY()) > 2) return null;
		return new BlockPos(sample.getX(), y, sample.getZ());
	}

	/** Stable first choice for each worker; slot probing still avoids occupied or unsafe pads. */
	static int preferredMineWaitingSlot(UUID villagerId) {
		return Math.floorMod(villagerId.hashCode(), WAITING_STAND_COUNT);
	}

	static boolean mineWaitingCellSafe(Ctx c, BlockPos stand) {
		ServerLevel level = c.level();
		if (!level.hasChunkAt(stand) || !level.hasChunkAt(stand.below())
			|| !level.hasChunkAt(stand.above()) || !level.hasChunkAt(stand.above(2))) return false;
		BlockState feet = level.getBlockState(stand);
		BlockState head = level.getBlockState(stand.above());
		BlockState upper = level.getBlockState(stand.above(2));
		BlockPos floor = stand.below();
		if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
			|| level.getBlockEntity(floor) != null
			|| !feet.getFluidState().isEmpty() || feet.hasBlockEntity()
			|| !head.getFluidState().isEmpty() || head.hasBlockEntity()
			|| !upper.getFluidState().isEmpty() || upper.hasBlockEntity()) return false;
		double x = stand.getX() + 0.5, y = stand.getY(), z = stand.getZ() + 0.5;
		AABB destination = c.v().getBoundingBox().move(x - c.v().getX(), y - c.v().getY(), z - c.v().getZ());
		return level.noCollision(c.v(), destination);
	}

	private static boolean mineWaitingStandAvailable(Ctx c, VillageData.Mine mine, int slot) {
		AABB nearMine = new AABB(mine.entrance).inflate(WAITING_STAND_SCAN_MARGIN, 4, WAITING_STAND_SCAN_MARGIN);
		List<Villager> routeWorkers = mineRouteWorkers(c.level(), mine);
		List<LivingEntity> nearby = c.level().getEntitiesOfClass(LivingEntity.class, nearMine,
			entity -> entity != c.v() && entity.isAlive());
		return mineWaitingStandAvailable(c, mine, slot, routeWorkers, nearby);
	}

	private static boolean mineWaitingStandAvailable(Ctx c, VillageData.Mine mine, int slot,
			List<Villager> routeWorkers, List<LivingEntity> nearby) {
		BlockPos stand = mineWaitingStand(c.level(), mine, slot);
		if (stand == null || !mineWaitingCellSafe(c, stand)) return false;
		for (Villager other : routeWorkers) {
			if (other == c.v()) continue;
			MineRoute route = mineRoute(other);
			if (isWaitingRoute(route) && waitingSlot(route) == slot) return false;
		}
		AABB standVolume = new AABB(stand.getX() + 0.05, stand.getY(), stand.getZ() + 0.05,
			stand.getX() + 0.95, stand.getY() + 2.0, stand.getZ() + 0.95);
		for (LivingEntity other : nearby) {
			if (other.getBoundingBox().intersects(standVolume)) return false;
			if (other instanceof Villager villager && !villager.getNavigation().isDone()
				&& stand.equals(villager.getNavigation().getTargetPos())) return false;
		}
		return true;
	}

	static int findMineWaitingSlot(Ctx c, VillageData.Mine mine) {
		List<Villager> routeWorkers = mineRouteWorkers(c.level(), mine);
		AABB nearMine = new AABB(mine.entrance).inflate(WAITING_STAND_SCAN_MARGIN, 4, WAITING_STAND_SCAN_MARGIN);
		List<LivingEntity> nearby = c.level().getEntitiesOfClass(LivingEntity.class, nearMine,
			entity -> entity != c.v() && entity.isAlive());
		int preferred = preferredMineWaitingSlot(c.v().getUUID());
		for (int offset = 0; offset < WAITING_STAND_COUNT; offset++) {
			int slot = (preferred + offset) % WAITING_STAND_COUNT;
			if (!mineWaitingStandAvailable(c, mine, slot, routeWorkers, nearby)) continue;
			BlockPos stand = mineWaitingStand(c.level(), mine, slot);
			var path = c.v().getNavigation().createPath(stand, 0);
			if (path != null && path.canReach()) return slot;
		}
		return -1;
	}

	static void rememberMineWaiting(Villager villager, ServerLevel level, VillageData.Mine mine, int slot) {
		if (slot < 0 || slot >= WAITING_STAND_COUNT) return;
		long reservationUntil = level.getGameTime() + MINE_RESERVATION_TICKS;
		MineRoute current = mineRoute(villager);
		if (routeMatchesMine(current, level, mine) && current.index() == -slot - 1
			&& current.reservationUntil() > reservationUntil - MINE_RESERVATION_TICKS / 2) return;
		villager.setAttached(VillagerPlus.MINING_ROUTE, new MineRoute(
			level.dimension().identifier().toString(), mine.entrance, mine.dir, -slot - 1, mine.targetY,
			reservationUntil));
	}

	private static VillageData.Mine activeMineForWaitingRoute(Ctx c, MineRoute route) {
		if (c.village() == null || c.village().data().mine.isEmpty()) return null;
		VillageData.Mine mine = c.village().data().mine.get();
		return !mine.done && routeMatchesMine(route, c.level(), mine) ? mine : null;
	}

	private static void stopMineApproach(Ctx c) {
		c.v().getNavigation().stop();
		c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		c.st().clearTarget();
	}

	static void waitAtMineStand(Ctx c, VillageData.Mine mine, int slot) {
		if (!mineWaitingStandAvailable(c, mine, slot)) {
			int replacement = findMineWaitingSlot(c, mine);
			if (replacement < 0) {
				stopMineApproach(c);
				c.st().cooldownUntil = c.now() + 20;
				c.setActivity("wartet mit Abstand auf einen freien sicheren Minenstand");
				return;
			}
			slot = replacement;
			rememberMineWaiting(c.v(), c.level(), mine, slot);
		}
		BlockPos stand = mineWaitingStand(c.level(), mine, slot);
		MineWalk result = walkMineWaitingStep(c, stand);
		if (result == MineWalk.ARRIVED) {
			c.st().cooldownUntil = c.now() + 20;
			c.setActivity("wartet auf Abstand auf den freien Minenabschnitt");
		} else if (result == MineWalk.BLOCKED || result == MineWalk.STUCK) {
			stopMineApproach(c);
			int replacement = findMineWaitingSlot(c, mine);
			if (replacement >= 0) {
				rememberMineWaiting(c.v(), c.level(), mine, replacement);
				c.st().cooldownUntil = c.now() + 8;
			} else {
				c.st().cooldownUntil = c.now() + 20;
			}
			c.setActivity("wartet mit Abstand auf einen freien sicheren Minenstand");
		}
	}

	/** Save a unique surface retry route after entrance navigation stalls; never drop the shared mine cursor. */
	static void waitOutsideBlockedMineEntry(Ctx c, VillageData.Mine mine) {
		int slot = findMineWaitingSlot(c, mine);
		c.st().mineCheckAt = c.now() + 40;
		if (slot >= 0) {
			rememberMineWaiting(c.v(), c.level(), mine, slot);
			waitAtMineStand(c, mine, slot);
			return;
		}
		stopMineApproach(c);
		c.st().cooldownUntil = c.now() + 20;
		c.setActivity("wartet auf einen freien sicheren Minenweg");
	}

	private static MineWalk walkMineWaitingStep(Ctx c, BlockPos stand) {
		if (!mineWaitingCellSafe(c, stand)) return MineWalk.BLOCKED;
		if (c.v().getNavigation().isDone() || !stand.equals(c.v().getNavigation().getTargetPos())) {
			var path = c.v().getNavigation().createPath(stand, 0);
			if (path == null || !path.canReach()) {
				stopMineApproach(c);
				return MineWalk.BLOCKED;
			}
		}
		if (JobUtil.approach(c, stand, 0.25)) {
			stopMineApproach(c);
			return MineWalk.ARRIVED;
		}
		c.v().getBrain().setMemory(MemoryModuleType.WALK_TARGET,
			new WalkTarget(Vec3.atBottomCenterOf(stand), 0.35F, 0));
		return MineWalk.MOVING;
	}

	// --- Wege in der Mine -----------------------------------------------------------------------

	/** Index des naechsten Stollenabschnitts (ab dem Eingang) in Reichweite, sonst -1. */
	/**
	 * Below the entrance height AND close to the gallery. Height alone is not enough: the entrance may sit on a hill,
	 * so residents in the lowland around it are not "in the mine" and must simply walk to the entrance.
	 */
	private static boolean inMineArea(VillageData.Mine m, Villager v) {
		if (v.getY() >= m.entrance.getY() - 0.5) return false;
		double dx = v.getX() - (m.entrance.getX() + 0.5);
		double dz = v.getZ() - (m.entrance.getZ() + 0.5);
		double reach = m.index + 10.0;
		if (dx * dx + dz * dz > reach * reach) return false;
		// Lowland next to a hill entrance is below the entrance height but not in the gallery: it must really be at a gallery section
		// (or inside the return-recovery radius), otherwise exitStep would leave the resident waiting forever.
		return nearest(m, v.position()) >= 0 || nearestForReturnRecovery(m, v.position()) >= 0;
	}

	private static int nearest(VillageData.Mine m, Vec3 pos) {
		// Ohne Objekt-Erzeugung gerechnet (lief fuer jeden Bergmann bei jedem Takt ueber bis zu ~80 Abschnitte).
		int best = -1;
		double bestDist = 2.6 * 2.6;
		Direction dir = Direction.from2DDataValue(m.dir);
		double ex = m.entrance.getX() + 0.5;
		double ez = m.entrance.getZ() + 0.5;
		int ey = m.entrance.getY();
		for (int i = 0; i <= m.index; i++) {
			double dx = pos.x - (ex + dir.getStepX() * i);
			double dz = pos.z - (ez + dir.getStepZ() * i);
			double dy = pos.y - Math.max(m.targetY, ey - i);
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bestDist) {
				best = i;
				bestDist = d;
			}
		}
		return best;
	}

	/**
	 * A saved route can outlive a small server-side displacement during unload/reload. For the
	 * return path only, recover the closest supported route section when the worker is nearby;
	 * normal work and mine ownership still use nearest()'s tighter corridor bound.
	 */
	static int nearestForReturnRecovery(VillageData.Mine m, Vec3 pos) {
		int best = -1;
		double bestDist = 6.0 * 6.0;
		Direction dir = Direction.from2DDataValue(m.dir);
		double ex = m.entrance.getX() + 0.5;
		double ez = m.entrance.getZ() + 0.5;
		int ey = m.entrance.getY();
		for (int i = 0; i <= m.index; i++) {
			double dx = pos.x - (ex + dir.getStepX() * i);
			double dz = pos.z - (ez + dir.getStepZ() * i);
			double dy = pos.y - Math.max(m.targetY, ey - i);
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bestDist) {
				best = i;
				bestDist = d;
			}
		}
		return best;
	}

	enum MineWalk { ARRIVED, MOVING, WAITING, BLOCKED, STUCK }

	/** Nächste begrenzte Zelle auf dem gespeicherten Weg; -1 bedeutet den oberirdischen Anmarsch zum Eingang. */
	static BlockPos nextMineWaypoint(VillageData.Mine mine, int current, int target) {
		if (current < 0) return mine.entrance;
		return bottom(mine, current + Integer.signum(target - current));
	}

	/** Prüft eine Zielzelle und lässt vanilla ground navigation genau bis zu diesem einen Treppenabschnitt laufen. */
	/** On the surface: at or above the entrance height, or simply not inside the gallery (the entrance may sit on a hill above the village). */
	private static boolean onMineSurface(VillageData.Mine mine, Villager villager) {
		return !inMineArea(mine, villager);
	}

	static MineWalk walkMineStep(Ctx c, VillageData.Mine mine, BlockPos cell) {
		ServerLevel level = c.level();
		if (!level.hasChunkAt(cell) || !level.hasChunkAt(cell.below())) {
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			c.st().clearTarget();
			c.setActivity("wartet auf einen geladenen Minenweg");
			return MineWalk.WAITING;
		}
		if (!patchFloor(c, cell.below())) {
			if (oneBlockTerraceGap(level, cell.below())) {
				c.setActivity("wartet auf Bruchstein für eine sichere Minenterrasse");
				return MineWalk.WAITING;
			}
			return MineWalk.BLOCKED;
		}
		if (!safeMineLanding(level, cell)) return MineWalk.BLOCKED;
		// A collision or an unreachable route may be temporary. Never seal a paid mine just
		// because navigation cannot reach one assigned lane this tick.
		if (!safeCell(c, cell)) {
			mineDiagnose(c, "STUCK: Zielzelle " + cell.toShortString() + " nicht sicher (Boden " + level.getBlockState(cell.below()).getBlock().getDescriptionId()
				+ ", Kollision=" + !level.noCollision(c.v(), c.v().getBoundingBox().move(cell.getX() + 0.5 - c.v().getX(), cell.getY() - c.v().getY(), cell.getZ() + 0.5 - c.v().getZ())) + ")");
			return MineWalk.STUCK;
		}

		// Far from the mine (vanilla navigation range is short): hand the walk to the bounded long-route search first.
		double farSq = c.v().position().distanceToSqr(Vec3.atBottomCenterOf(cell));
		if (farSq > 8.0 * 8.0 && onMineSurface(mine, c.v()) && approachStalled(c, farSq)) return MineWalk.MOVING;
		if (farSq > 20.0 * 20.0 && onMineSurface(mine, c.v())) {
			c.setActivity("geht zur Mine");
			if (!JobUtil.approachLong(c, cell, 10.0)) return c.st().cooldownUntil > c.now() ? MineWalk.STUCK : MineWalk.MOVING;
		}
		// Preflight only when vanilla navigation is not already following this bounded waypoint.
		if (c.v().getNavigation().isDone() || !cell.equals(c.v().getNavigation().getTargetPos())) {
			var path = c.v().getNavigation().createPath(cell, 1);
			if (path == null || !path.canReach()) {
				// Plain vanilla navigation often fails on sandy dunes; the bounded long-route search used for the quarry still finds a way.
				var alternative = de.villagerplus.build.Builder.workPath(c, cell);
				if (alternative != null && alternative.canReach()) path = alternative;
			}
			if (path == null || !path.canReach()) {
				LOGGER.info("Mine: Wegpunkt {} unerreichbar fuer {} von {} (Abstand {}, Pfad {}, Zelle={}, darunter={}, darueber={}, Nachbarn N/S/O/W={}/{}/{}/{})", cell.toShortString(), c.mem().name,
					c.v().blockPosition().toShortString(), (int) Math.sqrt(farSq), path == null ? "null" : "nicht erreichbar",
					level.getBlockState(cell).getBlock().getDescriptionId(), level.getBlockState(cell.below()).getBlock().getDescriptionId(),
					level.getBlockState(cell.above()).getBlock().getDescriptionId(),
					level.getBlockState(cell.north()).getBlock().getDescriptionId(), level.getBlockState(cell.south()).getBlock().getDescriptionId(),
					level.getBlockState(cell.east()).getBlock().getDescriptionId(), level.getBlockState(cell.west()).getBlock().getDescriptionId());
				c.v().getNavigation().stop();
				c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
				c.st().clearTarget();
				c.setActivity("wartet auf einen freien Minenweg");
				// A mine whose entrance waypoint never gets a path (e.g. built over by a project) is given up so a new one is chosen.
				// Only failures inside the long-route handover radius count (farther away the long-route search handles it first).
				int misses = farSq <= 20.0 * 20.0 ? UNREACHABLE_MINE_STEPS.merge(mine.entrance.asLong(), 1, Integer::sum) : 0;
				if (misses >= UNREACHABLE_MINE_LIMIT && onMineSurface(mine, c.v())) {
					UNREACHABLE_MINE_STEPS.remove(mine.entrance.asLong());
					LOGGER.info("Mine: Eingang {} wird nach {} erfolglosen Wegsuchen aufgegeben", mine.entrance.toShortString(), misses);
					return MineWalk.BLOCKED;
				}
				return MineWalk.STUCK;
			}
		}
		return approachMineStep(c, cell);
	}

	/** First time a resident found a free lane it could not path to, per resident (see {@link #noLanePathWatchdog}). */
	private static final Map<UUID, Long> NO_LANE_PATH_SINCE = new HashMap<>();
	static final long NO_LANE_PATH_TICKS = 900L;

	/** True when at least one work lane is unoccupied and safe, so that a failed admission is down to the resident's own position, not to the crowd. */
	static boolean freeLaneExists(Ctx c, VillageData.Mine mine) {
		if (c == null || mine == null || mineEntranceAdmissionBlocked(c, mine)) return false;
		List<Villager> routeWorkers = mineRouteWorkers(c.level(), mine);
		Set<Integer> occupied = new HashSet<>();
		for (Villager other : routeWorkers) {
			if (other == c.v() || !holdsLaneNow(mine, other)) continue;
			MineRoute route = mineRoute(other);
			if (route != null && route.index() >= 0) occupied.add(workSlotForRoute(route, other));
		}
		AABB nearMine = new AABB(mine.entrance).inflate(WAITING_STAND_SCAN_MARGIN, 4, WAITING_STAND_SCAN_MARGIN);
		List<LivingEntity> nearby = c.level().getEntitiesOfClass(LivingEntity.class, nearMine,
			entity -> entity != c.v() && entity.isAlive());
		int section = Math.max(0, mine.index - 1);
		for (int slot = 0; slot < MINE_WORK_STAND_COUNT; slot++) {
			if (!occupied.contains(slot) && mineWorkStandAvailable(c, mine, slot, section, routeWorkers, nearby)) return true;
		}
		return false;
	}

	/**
	 * A resident who finds free, safe lanes but no path to any of them (observed: a miner living on a terrace above the mine, 14 blocks
	 * higher, asked again and again for 6000 ticks while the other miner worked alone) is put on the village street after
	 * {@link #NO_LANE_PATH_TICKS}, from where the walk to the mine works. Returns true when the resident was moved.
	 */
	static boolean noLanePathWatchdog(Ctx c, VillageData.Mine mine) {
		UUID id = c.v().getUUID();
		long now = c.now();
		if (!freeLaneExists(c, mine)) {
			NO_LANE_PATH_SINCE.remove(id);
			return false;
		}
		Long since = NO_LANE_PATH_SINCE.get(id);
		if (since == null || since > now || now - since > 6000L) {
			NO_LANE_PATH_SINCE.put(id, now);
			return false;
		}
		if (now - since < NO_LANE_PATH_TICKS) return false;
		NO_LANE_PATH_SINCE.remove(id);
		BlockPos arrival = c.village() == null ? null : de.villagerplus.build.Builder.villageReturn(c.level(), c.village().bell());
		if (arrival == null) return false;
		c.v().getNavigation().stop();
		c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		c.st().clearTarget();
		c.v().teleportTo(arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5);
		c.v().resetFallDistance();
		c.st().cooldownUntil = now + 20;
		c.st().mineCheckAt = now + 20;
		LOGGER.info("Mine: {} fand {} Ticks lang freie Arbeitsplaetze, aber keinen Weg dorthin (bei {}) und wurde auf die Dorfstrasse gesetzt",
			c.mem().name, NO_LANE_PATH_TICKS, c.v().blockPosition().toShortString());
		return true;
	}

	/** Test hook: forget the lane-path bookkeeping. */
	static void resetNoLanePathForTest() {
		NO_LANE_PATH_SINCE.clear();
	}

	/** Per resident: closest distance (squared) reached on the current overland walk to the mine, when it was reached, last visit, stalls so far. */
	private record ApproachProgress(double bestSq, long bestAt, long lastSeen, int stalls) { }
	private static final Map<UUID, ApproachProgress> MINE_APPROACH = new HashMap<>();
	/** A walk to the mine that gets no closer for this long is stuck (a doorway, a yard, a fight over the walk target), not slow. */
	static final long APPROACH_STALL_TICKS = 600L;
	static final int APPROACH_STALLS_BEFORE_GIVING_UP = 3;

	/**
	 * Watches the long walk to the mine: observed in the natural run, residents stood in their doorway for 3300 ticks (half a working
	 * day) on "geht zur Mine" without moving. After {@link #APPROACH_STALL_TICKS} without getting closer the resident is put back on
	 * the village street, exactly like a waiter who stands too far from the shaft; after three such resets the walk is dropped for a while.
	 * Returns true when this call handled the resident.
	 */
	static boolean approachStalled(Ctx c, double farSq) {
		UUID id = c.v().getUUID();
		long now = c.now();
		ApproachProgress p = MINE_APPROACH.get(id);
		if (p == null || now - p.lastSeen() > 200L) {
			MINE_APPROACH.put(id, new ApproachProgress(farSq, now, now, p == null || now - p.lastSeen() > 6000L ? 0 : p.stalls()));
			return false;
		}
		if (farSq < p.bestSq() - 4.0) {
			MINE_APPROACH.put(id, new ApproachProgress(farSq, now, now, p.stalls()));
			return false;
		}
		if (now - p.bestAt() <= APPROACH_STALL_TICKS) {
			MINE_APPROACH.put(id, new ApproachProgress(p.bestSq(), p.bestAt(), now, p.stalls()));
			return false;
		}
		int stalls = p.stalls() + 1;
		MINE_APPROACH.put(id, new ApproachProgress(Double.MAX_VALUE, now, now, stalls));
		c.v().getNavigation().stop();
		c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		c.st().clearTarget();
		if (stalls >= APPROACH_STALLS_BEFORE_GIVING_UP) {
			clearMineRoute(c.v());
			c.st().mineCheckAt = now + 1200;
			c.st().cooldownUntil = now + 40;
			MINE_APPROACH.remove(id);
			LOGGER.info("Mine: {} kommt auf dem Weg zur Mine bei {} nicht voran (Abstand {}); der Gang wird fuer eine Weile aufgegeben",
				c.mem().name, c.v().blockPosition().toShortString(), (int) Math.sqrt(farSq));
			return true;
		}
		BlockPos arrival = c.village() == null ? null : de.villagerplus.build.Builder.villageReturn(c.level(), c.village().bell());
		if (arrival != null) {
			c.v().teleportTo(arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5);
			c.v().resetFallDistance();
		}
		c.st().cooldownUntil = now + 20;
		LOGGER.info("Mine: {} kam auf dem Weg zur Mine {} Ticks nicht naeher (Abstand {}, bei {}) und wurde auf die Dorfstrasse gesetzt",
			c.mem().name, APPROACH_STALL_TICKS, (int) Math.sqrt(farSq), c.v().blockPosition().toShortString());
		return true;
	}

	/** Test hook: forget the walk-progress bookkeeping. */
	static void resetApproachForTest() {
		MINE_APPROACH.clear();
	}

	/** Propagates JobUtil's anti-stuck abort instead of immediately recreating the same mine waypoint. */
	static MineWalk approachMineStep(Ctx c, BlockPos cell) {
		long cooldownBefore = c.st().cooldownUntil;
		if (JobUtil.approach(c, cell, 0.9)) {
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			c.st().clearTarget();
			return MineWalk.ARRIVED;
		}
		if (c.st().cooldownUntil > cooldownBefore && c.st().cooldownUntil > c.now()) {
			// The last few blocks before a safe mine waypoint: vanilla navigation sometimes never delivers the walker (dunes, bushes,
			// a one-block step). Set him onto the waypoint instead of letting the whole mine starve.
			double gap = c.v().position().distanceToSqr(Vec3.atBottomCenterOf(cell));
			if (gap <= 8.0 * 8.0 && safeCell(c, cell)) {
				c.v().getNavigation().stop();
				c.v().teleportTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
				c.v().resetFallDistance();
				c.st().stuckCount = 0;
				c.st().bestDist = 0.0;
				c.st().cooldownUntil = c.now() + 10;
				LOGGER.info("Mine: {} kam {} Bloecke vor dem Wegpunkt {} nicht weiter und wurde darauf gesetzt", c.mem().name, (int) Math.sqrt(gap), cell.toShortString());
				return MineWalk.MOVING;
			}
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			c.st().clearTarget();
			c.setActivity("setzt den Minenweg nach einem Navigationsstopp neu an");
			var dbgPath = c.v().getNavigation().createPath(cell, 1);
			mineDiagnose(c, "STUCK: Navigationsstopp bei " + c.v().blockPosition().toShortString() + " (" + c.v().position() + ") auf dem Weg zu " + cell.toShortString()
				+ ", Pfad=" + (dbgPath == null ? "null" : "len " + dbgPath.getNodeCount() + " reach=" + dbgPath.canReach() + " ende=" + (dbgPath.getNodeCount() > 0 ? dbgPath.getEndNode() : null))
				+ ", stuckCount=" + c.st().stuckCount + ", Aktivitaet='" + c.st().activity + "'");
			return MineWalk.STUCK;
		}
		c.v().getBrain().setMemory(MemoryModuleType.WALK_TARGET,
			// closeEnough 0: the brain keeps walking until the villager stands IN the cell. With 1 it stops on the neighbouring block
			// (Manhattan distance 1) while the arrival test above wants 0.9 blocks - the walker then hangs one step short.
			new WalkTarget(Vec3.atBottomCenterOf(cell), (float) Math.min(0.55, 0.45 * Skills.speed(c.mem())), 0));
		return MineWalk.MOVING;
	}

	/** Leaves a stalled underground visit tagged, then lets JobManager resume it through the saved return route. */
	static void deferMineRouteAfterStall(Ctx c) {
		c.v().getNavigation().stop();
		c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		c.st().clearTarget();
		c.st().miningUntil = c.now();
		c.st().cooldownUntil = c.now() + 40;
		c.setActivity("kehrt nach einem hängenden Minenweg sicher zurück");
	}

	/** Alle bekannten Minen des Dorfes (aktuelle zuerst). */
	private static List<VillageData.Mine> allMines(VillageData d) {
		List<VillageData.Mine> out = new ArrayList<>();
		d.mine.ifPresent(out::add);
		out.addAll(d.extra.oldMines);
		return out;
	}

	/** Die Mine, in deren Stollen der Dorfbewohner gerade steckt (null = keine). */
	private static VillageData.Mine mineAround(Ctx c) {
		if (c.village() == null) {
			return null;
		}
		for (VillageData.Mine m : allMines(c.village().data())) {
			double reach = m.index + 10.0;
			if (c.v().position().distanceToSqr(Vec3.atBottomCenterOf(m.entrance)) > reach * reach) {
				continue;
			}
			if (nearest(m, c.v().position()) >= 1 && c.v().getY() < m.entrance.getY() - 0.5) {
				return m;
			}
		}
		return null;
	}

	/** Steckt der Dorfbewohner gerade im Stollen (unter Tage), ohne dort zu arbeiten? */
	public static boolean insideMine(Ctx c) {
		MineRoute saved = mineRoute(c.v());
		if (isWaitingRoute(saved)) {
			VillageData.Mine activeMine = activeMineForWaitingRoute(c, saved);
			if (activeMine != null && c.v().getY() >= activeMine.entrance.getY() - 0.5) {
				if (c.now() >= c.st().mineCheckAt && hasOtherActiveMineWorker(c.level(), c.v(), activeMine)) {
					rememberMineWaiting(c.v(), c.level(), activeMine, waitingSlot(saved));
					c.st().mineCheckAt = c.now() + 40;
				}
				if (c.now() < c.st().mineCheckAt) return true;
			}
			clearMineRoute(c.v());
			if (c.v().getY() >= saved.entrance().getY() - 0.5) {
				stopMineApproach(c);
				return false;
			}
		}
		VillageData.Mine mine = mineAround(c);
		if (mine == null) mine = taggedMine(c.v(), c.level());
		if (mine == null) {
			clearMineRoute(c.v());
			return false;
		}
		if (c.v().getY() >= mine.entrance.getY() - 0.5) {
			VillageData.Mine active = c.village() == null ? null : c.village().data().mine.orElse(null);
			if (activeSurfaceReservation(saved, c.level(), active, c.st().miningUntil, c.now())) return false;
			clearMineRoute(c.v());
			stopMineApproach(c);
			return false;
		}
		int workSlot = assignedMineWorkSlot(c, mine);
		if (workSlot < 0) return true;
		rememberMineRoute(c.v(), c.level(), mine, workSlot);
		return shouldExitMine(mine, c.st().miningUntil, c.now())
			|| hasOtherMineWorkerInWorkSlot(c.level(), c.v(), mine, workSlot);
	}

	static boolean shouldExitMine(VillageData.Mine mine, long miningUntil, long now) {
		return mine.done || now >= miningUntil;
	}

	static void reserveMineAdmission(JobState state, long now) {
		state.miningUntil = Math.max(state.miningUntil, now + MINE_RESERVATION_TICKS);
	}

	/**
	 * Final server-physics guard called from Entity.move after villager AI/navigation has chosen a displacement.
	 * The persistent villager attachment survives saves; route geometry is refreshed from the village Mine on work ticks.
	 */
	public static boolean allowMineMovement(Villager villager, Vec3 movement) {
		if (!(villager.level() instanceof ServerLevel level)) return true;
		VillageData.Mine mine = taggedMine(villager, level);
		if (mine == null) return true;
		Vec3 from = villager.position();
		// The route confinement only applies where the gallery is. A resident on the surface (or anywhere away from the saved
		// gallery) is free to walk, even while it still carries the saved route tag during the approach to the entrance.
		// The old test compared the PRE-collision destination with the entrance height: a walker standing exactly at entrance
		// level (feet y == entrance y, the normal case on flat ground) has a gravity nudge of about -0.08 in every move call,
		// counted as "below the entrance", and the whole move (horizontal part included) was cancelled: the resident froze with a
		// valid path and an active WALK_TARGET until the anti-stuck logic gave the task up.
		if (!inMineArea(mine, villager)) return true;
		AABB box = villager.getBoundingBox();
		return mineMovementAllowed(mine, from, movement, box.getXsize(), box.getYsize());
	}

	static boolean mineMovementAllowed(VillageData.Mine mine, Vec3 from, Vec3 movement, double width, double height) {
		double distance = movement.length();
		if (!Double.isFinite(distance) || distance > 3.0) return false;
		int samples = Math.max(1, (int) Math.ceil(distance / 0.08));
		double previous = routeDeviation(mine, from, width, height);
		boolean startedInside = previous <= 1.0E-6;
		for (int i = 1; i <= samples; i++) {
			Vec3 point = from.add(movement.scale((double) i / samples));
			double deviation = routeDeviation(mine, point, width, height);
			if (deviation > 1.0E-6 && (startedInside || deviation >= previous - 1.0E-4)) return false;
			previous = deviation;
		}
		return true;
	}

	private static double routeDeviation(VillageData.Mine mine, Vec3 point, double width, double height) {
		int last = Math.max(0, mine.index - 1);
		Direction direction = Direction.from2DDataValue(mine.dir);
		double dx = point.x - (mine.entrance.getX() + 0.5);
		double dz = point.z - (mine.entrance.getZ() + 0.5);
		double along = dx * direction.getStepX() + dz * direction.getStepZ();
		double lateral = dx * direction.getStepZ() - dz * direction.getStepX();
		if (point.y >= mine.entrance.getY() - 0.05) return 0.0;

		double passageWidth = TUNNEL_HALF_WIDTH * 2.0 + 1.0;
		double maxLateral = Math.max(0.0, (passageWidth - width) * 0.5 + 0.015);
		double lateralError = Math.max(0.0, Math.abs(lateral) - maxLateral);
		double alongError = Math.max(0.0, Math.max(-along - width * 0.5, along - last - width * 0.5));
		double clampedAlong = Math.max(0.0, Math.min(last, along));
		// The gallery is terraced one block per cell: a walker in cell i (along within half a block of i) stands on that cell's floor,
		// not on a continuous slope. The sloped model put the allowed band up to half a block above the real floor in the upper part
		// of each cell, so a walker landing on a terrace there was held above its floor by the guard (frozen mid-air, measured 600 to
		// 1400 blocked moves per miner in a natural run).
		double expectedFloor = mine.entrance.getY() - Math.min(Math.round(clampedAlong), descent(mine));
		int near = Math.max(0, Math.min(last, (int) Math.round(clampedAlong)));
		int clearBlocks = cells(mine, near);
		if (near > 0) clearBlocks = Math.min(clearBlocks, cells(mine, near - 1));
		if (near < last) clearBlocks = Math.min(clearBlocks, cells(mine, near + 1));
		double lowError = Math.max(0.0, expectedFloor - 0.18 - point.y);
		double highError = Math.max(0.0, point.y + height - expectedFloor - clearBlocks - 0.03);
		return Math.sqrt(lateralError * lateralError + alongError * alongError + lowError * lowError + highError * highError);
	}

	static void rememberMineRoute(Villager villager, ServerLevel level, VillageData.Mine mine) {
		MineRoute current = mineRoute(villager);
		int workSlot = routeMatchesMine(current, level, mine) && current.workSlot() >= 0
				&& current.workSlot() < MINE_WORK_STAND_COUNT
			? current.workSlot() : preferredMineWorkSlot(villager.getUUID());
		rememberMineRoute(villager, level, mine, workSlot);
	}

	static void rememberMineRoute(Villager villager, ServerLevel level, VillageData.Mine mine, int workSlot) {
		if (workSlot < 0 || workSlot >= MINE_WORK_STAND_COUNT) workSlot = preferredMineWorkSlot(villager.getUUID());
		long reservationUntil = level.getGameTime() + MINE_RESERVATION_TICKS;
		MineRoute current = mineRoute(villager);
		if (routeMatchesMine(current, level, mine) && current.index() == mine.index
			&& current.workSlot() == workSlot
			&& current.reservationUntil() > reservationUntil - MINE_RESERVATION_TICKS / 2) return;
		villager.setAttached(VillagerPlus.MINING_ROUTE, new MineRoute(
			level.dimension().identifier().toString(), mine.entrance, mine.dir, mine.index, mine.targetY,
			reservationUntil, workSlot));
		// The route owner must keep its exit corridor simulated after finishMine removes the active mine.
		de.villagerplus.village.Anchor.keepMineRouteLoaded(level, villager);
	}

	private static void clearMineRoute(Villager villager) {
		villager.removeAttached(VillagerPlus.MINING_ROUTE);
	}

	public static VillageData.Mine taggedMine(Villager villager, ServerLevel level) {
		MineRoute route = villager.getAttached(VillagerPlus.MINING_ROUTE);
		if (route == null || !route.dimension().equals(level.dimension().identifier().toString())
			|| route.dir() < 0 || route.dir() > 3
			|| route.index() < 0 || route.index() > MAX_HORIZONTAL + level.getMaxY() - level.getMinY()) return null;
		return new VillageData.Mine(route.entrance(), route.dir(), route.index(), route.targetY(), false);
	}

	/** A route landing must be loaded, supported, dry, and clear of containers through the full worker volume. */
	static boolean safeMineLanding(ServerLevel level, BlockPos cell) {
		if (level == null || cell == null || !level.hasChunkAt(cell) || !level.hasChunkAt(cell.below())
				|| !level.hasChunkAt(cell.above()) || !level.hasChunkAt(cell.above(2))) return false;
		BlockPos floor = cell.below();
		if (level.getBlockEntity(floor) != null
				|| !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return false;
		for (int height = 0; height < 3; height++) {
			BlockPos pos = cell.above(height);
			BlockState state = level.getBlockState(pos);
			if (state.hasBlockEntity() || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) return false;
		}
		return true;
	}

	/** Supported, dry landing plus collision clearance for this villager. */
	static boolean safeCell(Ctx c, BlockPos cell) {
		if (!safeMineLanding(c.level(), cell)) return false;
		double x = cell.getX() + 0.5;
		double y = cell.getY();
		double z = cell.getZ() + 0.5;
		AABB destination = c.v().getBoundingBox().move(x - c.v().getX(), y - c.v().getY(), z - c.v().getZ());
		return c.level().noCollision(c.v(), destination);
	}

	private static boolean solidFloor(Ctx c, BlockPos cell) {
		BlockPos f = cell.below();
		return c.level().hasChunkAt(f)
			&& c.level().getBlockState(f).isFaceSturdy(c.level(), f, net.minecraft.core.Direction.UP);
	}

	/** Holt einen Dorfbewohner aus dem Stollen, wenn er nicht (mehr) als Bergmann arbeitet. */
	public static void exitStep(Ctx c) {
		MineRoute saved = mineRoute(c.v());
		if (isWaitingRoute(saved)) {
			VillageData.Mine activeMine = activeMineForWaitingRoute(c, saved);
			if (activeMine != null && c.v().getY() >= activeMine.entrance.getY() - 0.5
				&& c.now() < c.st().mineCheckAt) {
				waitAtMineStand(c, activeMine, waitingSlot(saved));
				return;
			}
			clearMineRoute(c.v());
			stopMineApproach(c);
			if (c.v().getY() >= saved.entrance().getY() - 0.5) return;
		}
		VillageData.Mine m = mineAround(c);
		if (m == null) m = taggedMine(c.v(), c.level());
		if (m == null) {
			clearMineRoute(c.v());
			return;
		}
		rememberMineRoute(c.v(), c.level(), m);
		int k = nearest(m, c.v().position());
		if (k < 0 && inMineArea(m, c.v())) {
			k = nearestForReturnRecovery(m, c.v().position());
		}
		if (!inMineArea(m, c.v())) {
			clearMineRoute(c.v());
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			return;
		}
		if (k < 0) {
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			c.st().clearTarget();
			c.st().cooldownUntil = c.now() + 40;
			c.setActivity("wartet auf Wiederaufnahme des gespeicherten Minenrückwegs");
			return;
		}
		if (!mineExitTurn(c, m, k)) {
			stopMineApproach(c);
			c.st().cooldownUntil = c.now() + 20;
			c.setActivity("wartet im eigenen Minenabschnitt, bis der Rückweg frei ist");
			return;
		}
		BlockPos next = nextMineWaypoint(m, k, 0);
		MineWalk result = walkMineStep(c, m, next);
		if (result == MineWalk.STUCK) {
			deferMineRouteAfterStall(c);
			return;
		}
		if (result == MineWalk.BLOCKED) {
			c.v().getNavigation().stop();
			c.v().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			c.st().clearTarget();
			c.setActivity("wartet sicher im Stollen auf einen freien Rückweg");
		}
		c.st().cooldownUntil = c.now() + (result == MineWalk.WAITING || result == MineWalk.BLOCKED ? 40 : 8);
	}
}
