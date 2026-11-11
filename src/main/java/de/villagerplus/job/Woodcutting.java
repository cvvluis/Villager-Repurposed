package de.villagerplus.job;

import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Holzfaellen: Der Pfeilmacher faellt echte Baeume in der Umgebung (Stamm fuer Stamm, von oben nach unten), bringt die
 * Staemme zur Dorfkiste und pflanzt einen Setzling nach, wenn er einen hat.
 */
public final class Woodcutting {
	private static final int RADIUS = 30;
	/** Plan-specific sourcing reaches beyond large CTOV village bounds, but never loads new chunks. */
	private static final int PLAN_RADIUS = 48;
	private static final int MAX_LOGS = 80;
	private static final int MAX_STRIPPED_LOGS_PER_TICK = 16;
	private static final int MAX_REPLANT_SITES = 32;
	private static final int PLANTATION_SITES_PER_TREE = 4;
	private static final int FALLBACK_PATCH_SITES = 3;
	private static final String RESERVED_CANOPY_PREFIX = "vp_reserved_canopy:";
	private static final int RESERVED_CANOPY_RADIUS = 4;
	private static final int RESERVED_CANOPY_MAX_LOGS = 64;
	private static final int RESERVED_CANOPY_MAX_HEIGHT = 32;
	private static final int RESERVED_CANOPY_SEARCH_COLUMNS = 3_000;
	private static final int RESERVED_ROAD_SAMPLES = 64;

	private Woodcutting() {
	}

	public static boolean needed(net.minecraft.server.level.ServerLevel level, VillageRef ref, VillageData d, Container box) {
		if (planNeedsWood(d)) return true;
		// Kein festes Limit: Holz wird beschafft, solange es im Vergleich zu Essen, Stein und Metall nicht im Ueberfluss da ist.
		return de.villagerplus.village.Balance.wants(level, ref, de.villagerplus.village.Balance.Kind.WOOD);
	}

	/** Keep construction fallback and the ordinary woodcutter cycle on the same plan-demand rules. */
	public static boolean planNeedsWood(VillageData d) {
		for (String w : d.wanted) {
			String id = w.split("#")[0];
			if (id.contains("planks") || id.contains("log") || id.contains("wood") || id.contains("stem") || id.contains("fence")
				|| id.contains("door") || id.contains("stairs") || id.contains("slab") || id.contains("sign")
				|| id.contains("crafting_table") || id.endsWith(":chest")) return true; // workbench and chests are crafted from planks
		}
		return false;
	}

	/**
	 * Turn real natural logs from the active plan's public village chest/storage into the exact stripped-log items it
	 * requests. The builder calls this only after the workshop had first chance to use logs for recipes. Public storage
	 * is assembled here from the village chest and registered Lager chests; nearby/private containers are never scanned.
	 *
	 * @return true when this tick converted logs, fetched the required axe, or is waiting for one
	 */
	public static boolean progressStrippedPlanDemand(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || !ref.data().hasPlan() || ref.data().wanted.isEmpty()) return false;
		Container destination = ref.chest(c.level());
		if (destination == null) return false;
		List<Container> publicStores = new ArrayList<>();
		publicStores.add(destination);
		for (Container lager : de.villagerplus.village.Lager.containers(c.level(), ref.data())) {
			if (lager != destination) publicStores.add(lager);
		}
		if (!hasStrippedShortage(ref.data().wanted, publicStores)) return false;

		ItemStack axe = usableStrippingAxe(c.v());
		if (axe.isEmpty()) {
			if (acquireStrippingAxe(c)) return true;
			c.setActivity("wartet auf eine haltbare Axt aus der Werkzeugkammer");
			return !hasOtherWoodDemand(ref.data().wanted);
		}

		int converted = 0;
		while (converted < MAX_STRIPPED_LOGS_PER_TICK) {
			axe = usableStrippingAxe(c.v());
			if (axe.isEmpty()) break;
			int paid = supplyStrippedPlanLogs(ref.data().wanted, destination, publicStores, 1);
			if (paid == 0) break;
			axe.hurtAndBreak(1, c.level(), null, ignored -> { });
			syncAxe(c, axe);
			converted += paid;
		}
		if (converted == 0) return false;
		c.setActivity("streift Bauholz für den Bauplan");
		return true;
	}

	/** Inventory-only core so the paid input/output transfer can be checked without mutating a world or a test harness. */
	static int supplyStrippedPlanLogs(List<String> wanted, Container destination, List<Container> publicStores) {
		return supplyStrippedPlanLogs(wanted, destination, publicStores, MAX_STRIPPED_LOGS_PER_TICK);
	}

	private static int supplyStrippedPlanLogs(List<String> wanted, Container destination, List<Container> publicStores, int limit) {
		if (wanted == null || wanted.isEmpty() || destination == null || publicStores == null || publicStores.isEmpty()) return 0;
		Map<net.minecraft.world.item.Item, Integer> demands = new java.util.LinkedHashMap<>();
		for (String request : wanted) {
			if (request == null || request.isBlank()) continue;
			String[] fields = request.split("#", 2);
			net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(fields[0]);
			if (id == null) continue;
			var output = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			int count = positiveCount(fields);
			if (output == null || count == 0 || strippingSource(output) == null) continue;
			demands.merge(output, count, (a, b) -> (int)Math.min(Integer.MAX_VALUE, (long)a + b));
		}

		int converted = 0;
		for (var demand : demands.entrySet()) {
			if (converted >= limit) break;
			var output = demand.getKey();
			var source = strippingSource(output);
			if (source == null) continue;
			int alreadyAvailable = countAcross(publicStores, stack -> stack.is(output));
			int needed = Math.max(0, demand.getValue() - alreadyAvailable);
			if (needed == 0) continue;
			int sourceStock = countAcross(publicStores, stack -> stack.is(source));
			int reserved = reserveNaturalLogs(wanted, source);
			int convertible = Math.min(needed, Math.max(0, sourceStock - reserved));
			for (int i = 0; i < convertible && converted < limit; i++) {
				if (!convertOnePublicLog(publicStores, destination, source, output)) break;
				converted++;
			}
		}
		return converted;
	}

	private static boolean hasStrippedShortage(List<String> wanted, List<Container> publicStores) {
		Map<net.minecraft.world.item.Item, Integer> demands = new java.util.LinkedHashMap<>();
		for (String request : wanted) {
			if (request == null || request.isBlank()) continue;
			String[] fields = request.split("#", 2);
			net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(fields[0]);
			if (id == null) continue;
			var output = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			int count = positiveCount(fields);
			if (output == null || count == 0 || strippingSource(output) == null) continue;
			demands.merge(output, count, (a, b) -> (int)Math.min(Integer.MAX_VALUE, (long)a + b));
		}
		return demands.entrySet().stream().anyMatch(demand -> countAcross(publicStores, stack -> stack.is(demand.getKey())) < demand.getValue());
	}

	private static boolean hasOtherWoodDemand(List<String> wanted) {
		for (String request : wanted) {
			if (request == null || request.isBlank()) continue;
			net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(request.split("#", 2)[0]);
			if (id == null) continue;
			String path = id.getPath();
			if (path.startsWith("stripped_") && path.endsWith("_log")) continue;
			var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item != null && isNaturalLog(item)) return true;
			if (path.endsWith("_planks") || path.endsWith("_wood") || path.endsWith("_stem")
				|| path.endsWith("_stairs") || path.endsWith("_slab") || path.endsWith("_fence")
				|| path.endsWith("_fence_gate") || path.endsWith("_door") || path.endsWith("_trapdoor")
				|| path.endsWith("_sign") || path.endsWith("_button") || path.endsWith("_pressure_plate")) return true;
		}
		return false;
	}

	private static ItemStack usableStrippingAxe(net.minecraft.world.entity.npc.villager.Villager villager) {
		ItemStack carried = usableStrippingAxe(villager.getInventory());
		if (!carried.isEmpty()) return carried;
		for (net.minecraft.world.entity.EquipmentSlot hand : List.of(
			net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.entity.EquipmentSlot.OFFHAND)) {
			ItemStack stack = villager.getItemBySlot(hand);
			if (usableStrippingAxe(stack)) return stack;
		}
		return ItemStack.EMPTY;
	}

	static boolean usableStrippingAxe(ItemStack stack) {
		return !stack.isEmpty() && stack.is(ItemTags.AXES) && stack.isDamageableItem()
			&& stack.getDamageValue() < stack.getMaxDamage();
	}

	private static boolean acquireStrippingAxe(Ctx c) {
		VillageRef ref = c.village();
		BlockPos shed = de.villagerplus.village.Specials.get(ref, de.villagerplus.build.Blueprints.TOOLSHED);
		if (shed == null || !c.level().hasChunkAt(shed)) return false;
		for (Container tools : de.villagerplus.village.Specials.containers(c.level(), ref, de.villagerplus.build.Blueprints.TOOLSHED)) {
			for (int slot = 0; slot < tools.getContainerSize(); slot++) {
				ItemStack available = tools.getItem(slot);
				if (!usableStrippingAxe(available)) continue;
				c.setActivity("holt eine Axt aus der Werkzeugkammer für den Bauplan");
				if (!JobUtil.approachLong(c, shed, 8.0)) return true;
				if (!usableStrippingAxe(c.v()).isEmpty() || !usableStrippingAxe(tools.getItem(slot))) return true;
				SimpleContainer preview = copyInventory(c.v().getInventory());
				if (!HopperBlockEntity.addItem(null, preview, available.copyWithCount(1), null).isEmpty()) {
					c.setActivity("braucht freien Inventarplatz für die Axt");
					return true;
				}
				ItemStack paid = tools.removeItem(slot, 1);
				if (!usableStrippingAxe(paid)) {
					HopperBlockEntity.addItem(null, tools, paid, null);
					tools.setChanged();
					return true;
				}
				tools.setChanged();
				ItemStack left = HopperBlockEntity.addItem(null, c.v().getInventory(), paid, null);
				if (!left.isEmpty()) {
					HopperBlockEntity.addItem(null, tools, left, null);
					tools.setChanged();
				}
				c.st().cooldownUntil = c.now() + 40;
				c.mem().jobsDone++;
				return true;
			}
		}
		return false;
	}

	private static ItemStack usableStrippingAxe(Container inventory) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (usableStrippingAxe(stack)) return stack;
		}
		return ItemStack.EMPTY;
	}

	private static void syncAxe(Ctx c, ItemStack axe) {
		Container inventory = c.v().getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot) == axe) {
				inventory.setChanged();
				return;
			}
		}
		for (net.minecraft.world.entity.EquipmentSlot hand : List.of(
			net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.entity.EquipmentSlot.OFFHAND)) {
			if (c.v().getItemBySlot(hand) == axe) {
				c.v().setItemSlot(hand, axe);
				return;
			}
		}
	}

	private static SimpleContainer copyInventory(Container source) {
		SimpleContainer copy = new SimpleContainer(source.getContainerSize());
		for (int slot = 0; slot < source.getContainerSize(); slot++) copy.setItem(slot, source.getItem(slot).copy());
		return copy;
	}

	/** Name pairing is accepted only for a registered stripped *_log block whose source is a natural log. */
	static net.minecraft.world.item.Item strippingSource(net.minecraft.world.item.Item output) {
		if (output == null || output == Items.AIR) return null;
		net.minecraft.resources.Identifier outputId = BuiltInRegistries.ITEM.getKey(output);
		String path = outputId.getPath();
		if (!path.startsWith("stripped_") || !path.endsWith("_log")) return null;
		net.minecraft.resources.Identifier sourceId = net.minecraft.resources.Identifier.fromNamespaceAndPath(
			outputId.getNamespace(), path.substring("stripped_".length()));
		var source = BuiltInRegistries.ITEM.getOptional(sourceId).orElse(null);
		return source != null && isNaturalLog(source) ? source : null;
	}

	private static int positiveCount(String[] fields) {
		if (fields.length < 2) return 1;
		try {
			return Math.max(0, Integer.parseInt(fields[1]));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private static int countAcross(List<Container> stores, java.util.function.Predicate<ItemStack> match) {
		int total = 0;
		for (Container store : stores) total = (int)Math.min(Integer.MAX_VALUE, (long)total + de.villagerplus.job.JobUtil.count(store, match));
		return total;
	}

	/** Do not strip logs already demanded as logs or as the input for a pending plank shortage. */
	private static int reserveNaturalLogs(List<String> wanted, net.minecraft.world.item.Item source) {
		int reserved = 0;
		String sourceId = BuiltInRegistries.ITEM.getKey(source).toString();
		for (String request : wanted) {
			if (request == null || request.isBlank()) continue;
			String[] fields = request.split("#", 2);
			int count = positiveCount(fields);
			if (count == 0) continue;
			net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(fields[0]);
			if (id == null) continue;
			if (id.toString().equals(sourceId)) {
				reserved = (int)Math.min(Integer.MAX_VALUE, (long)reserved + count);
				continue;
			}
			String path = id.getPath();
			if (!path.endsWith("_planks")) continue;
			net.minecraft.resources.Identifier plankLogId = net.minecraft.resources.Identifier.fromNamespaceAndPath(id.getNamespace(),
				path.substring(0, path.length() - "_planks".length()) + "_log");
			if (!plankLogId.toString().equals(sourceId)) continue;
			reserved = (int)Math.min(Integer.MAX_VALUE, (long)reserved + (count + 3L) / 4L);
		}
		return reserved;
	}

	private static boolean convertOnePublicLog(List<Container> stores, Container destination,
		net.minecraft.world.item.Item source, net.minecraft.world.item.Item output) {
		for (Container store : stores) {
			for (int slot = 0; slot < store.getContainerSize(); slot++) {
				ItemStack current = store.getItem(slot);
				if (current.isEmpty() || !current.is(source)) continue;
				if (!canInsertAfterInputRemoval(store, slot, destination, output)) return false;
				ItemStack paidInput = store.removeItem(slot, 1);
				if (paidInput.isEmpty()) return false;
				store.setChanged();
				ItemStack remainder = HopperBlockEntity.addItem(null, destination, new ItemStack(output), null);
				if (!remainder.isEmpty()) {
					// Single-threaded server inventory mutation makes this a defensive rollback path, not an expected branch.
					HopperBlockEntity.addItem(null, store, paidInput, null);
					store.setChanged();
					return false;
				}
				return true;
			}
		}
		return false;
	}

	private static boolean canInsertAfterInputRemoval(Container store, int slot, Container destination,
		net.minecraft.world.item.Item output) {
		SimpleContainer simulated = new SimpleContainer(destination.getContainerSize());
		for (int i = 0; i < destination.getContainerSize(); i++) simulated.setItem(i, destination.getItem(i).copy());
		if (store == destination) simulated.removeItem(slot, 1);
		return HopperBlockEntity.addItem(null, simulated, new ItemStack(output), null).isEmpty();
	}

	/** true = beschaeftigt. */
	public static boolean tick(Ctx c) {
		JobState st = c.st();
		WoodcutterWork work = c.v().getAttachedOrElse(de.villagerplus.VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
		diagnose(c, "Aufruf: woodCheckAt-in=" + (st.woodCheckAt - c.now()) + ", Dorf=" + (c.village() != null)
			+ ", Kiste=" + (c.village() != null && c.village().chest(c.level()) != null) + ", pending=" + work.removalPending()
			+ ", Wurzel=" + work.root().map(BlockPos::toShortString).orElse("keine"));
		if (work.removalPending()) {
			return finishPendingRemoval(c, work);
		}
		work = replantNearby(c, work);
		VillageRef ref = c.village();
		if (ref == null) return false;
		if (c.now() < st.woodCheckAt) {
			return false;
		}
		Container box = ref.chest(c.level());
		if (box == null) {
			st.woodCheckAt = c.now() + 600;
			return false;
		}
		ServerLevel level = c.level();
		boolean planWoodDemand = ref.data().hasPlan() && planNeedsWood(ref.data());
		Set<String> demandedLogs = planWoodDemand ? demandedLogIds(ref.data().wanted) : Set.of();
		BlockPos base = work.root().orElse(null);
		boolean materialDemand = needed(level, ref, ref.data(), box);
		if (!planWoodDemand && base == null) {
			BlockPos reservedTree = findReservedCanopyTree(c);
			if (reservedTree != null) {
				work = work.start(reservedTree, RESERVED_CANOPY_PREFIX + species(level.getBlockState(reservedTree).getBlock()));
				setWork(c, work);
				base = reservedTree;
			}
		}
		boolean reservedCanopyWork = isReservedCanopyWork(work);
		if (reservedCanopyWork && !reservedCanopyRoot(level, ref, base, c.v().blockPosition())) {
			setWork(c, work.abandonTree());
			st.clearTarget();
			st.woodCheckAt = c.now() + 200;
			return true;
		}
		if (!materialDemand && !reservedCanopyWork) {
			diagnose(c, "kein Holzbedarf (Plan-Holzbedarf=" + planWoodDemand + ", Wunschliste=" + ref.data().wanted.size() + ")");
			st.woodCheckAt = c.now() + 600;
			return false;
		}
		if (base != null && !level.hasChunkAt(base)) return true;
		if (planWoodDemand && base != null && !matchesRequestedLog(level.getBlockState(base).getBlock(), demandedLogs)) {
			// A saved cursor can outlive its original construction request. Preserve a replant location,
			// but do not keep harvesting a species the active plan cannot use.
			work = finishTrackedTree(work);
			setWork(c, work);
			st.clearTarget();
			st.woodCheckAt = c.now() + 40;
			return true;
		}
		if (base == null) {
			st.clearTarget();
			base = findTree(c, planWoodDemand, demandedLogs);
			if (base == null) {
				diagnose(c, "kein erreichbarer Baum (Planholz=" + planWoodDemand + ", gesuchte Stämme=" + demandedLogs + ")");
				st.woodCheckAt = c.now() + 1200;
				return false;
			}
			work = work.start(base, species(level.getBlockState(base).getBlock()));
			setWork(c, work);
		}
		// Before the first cut, a saved far tree must not outlive a clearly nearer suitable one: always walk to the nearest.
		if (!reservedCanopyWork && work.log().isEmpty() && work.stage() < 0 && !work.removalPending()
			&& c.now() >= NEAREST_TREE_RECHECK.getOrDefault(c.v().getUUID(), 0L)) {
			NEAREST_TREE_RECHECK.put(c.v().getUUID(), c.now() + 300L);
			BlockPos nearer = findTree(c, planWoodDemand, demandedLogs);
			if (nearer != null && !nearer.equals(base)) {
				double toNearer = c.v().blockPosition().distSqr(nearer), toSaved = c.v().blockPosition().distSqr(base);
				if (toNearer + 100.0 < toSaved) {
					work = work.start(nearer, species(level.getBlockState(nearer).getBlock()));
					setWork(c, work);
					base = nearer;
					st.clearTarget();
				}
			}
		}
		// A saved cursor can outlive its canopy (leaves decay naturally); preserve the stump until every connected log is handled.
		if (!level.hasChunkAt(base)) return true;
		if (!level.getBlockState(base).is(BlockTags.OVERWORLD_NATURAL_LOGS) || !rootSoil(level.getBlockState(base.below()))) {
			work = finishTrackedTree(work);
			setWork(c, work);
			st.clearTarget();
			st.woodCheckAt = c.now() + 40;
			return true;
		}
		if (hasBuiltWoodNearby(level, base)) {
			setWork(c, work.abandonTree());
			st.clearTarget();
			st.woodCheckAt = c.now() + 1200;
			return true;
		}
		if (!base.equals(st.target) || st.targetEntity != null) {
			st.clearTarget();
			st.stuckCount = 0;
			st.bestDist = 0.0;
			st.lastMoveAt = 0;
			st.target = base;
		}
		c.setActivity(reservedCanopyWork ? "rodet die Waldfläche für die Dorf-Erweiterung" : "fällt einen Baum");
		Anim.hold(c, Items.IRON_AXE);
		if (!(planWoodDemand || reservedCanopyWork ? JobUtil.approachLong(c, base, 2.8) : JobUtil.approach(c, base, 2.8))) {
			if (planWoodDemand && !reservedCanopyWork && JobUtil.longRouteKnownUnreachable(c.v(), base)) {
				// No walkable way to this tree: remember it and choose another instead of retrying it forever.
				markTreeUnreachable(c.v().getUUID(), base, c.now());
				setWork(c, work.abandonTree());
				st.clearTarget();
				st.woodCheckAt = c.now() + 20;
			}
			return true;
		}
		List<BlockPos> logs = connectedLogs(level, base);
		if (logs.isEmpty()) {
			work = finishTrackedTree(work);
			setWork(c, work);
			st.clearTarget();
			st.woodCheckAt = c.now() + 40;
			return true;
		}
		// Von oben nach unten faellen: der Stamm bleibt nicht in der Luft haengen.
		BlockPos top = logs.get(0);
		if (work.log().isEmpty() || !work.log().get().equals(top)) {
			work = work.select(top);
			setWork(c, work);
		}
		BlockState state = level.getBlockState(top);
		List<ItemStack> drops = Block.getDrops(state, level, top, null, c.v(), new ItemStack(Items.IRON_AXE));
		if (!canFitAllDrops(c.v().getInventory(), drops)) {
			c.setActivity("braucht freien Inventarplatz für Baumaterial");
			return true;
		}
		if (Anim.breakStep(c, top, state, Items.IRON_AXE, work.stage())) {
			work = work.claimRemoval(drops, state.getBlock());
			setWork(c, work);
			return true;
		}
		int stage = Anim.breakingStage(c.v());
		if (stage != work.stage()) setWork(c, work.withStage(stage));
		return true;
	}

	/**
	 * Naechster natuerlicher Baumstamm, der auf Erde steht. Statt jeden Block im Umkreis (rund 59 000) zu pruefen, wird je
	 * Spalte nur die oberste Hoehe gelesen (Heightmap ohne Laub): Liegt dort ein Stamm, wird er bis zum Fuss verfolgt.
	 */
	private static BlockPos findTree(Ctx c, boolean restrictToPlan, Set<String> demandedLogs) {
		BlockPos tree = findTree(c, restrictToPlan, demandedLogs, searchRadius(restrictToPlan), false);
		if (!restrictToPlan) return tree;
		// Plan wood: strict village footprint near, then only buildings protected near, then the same further out.
		if (tree == null) tree = findTree(c, true, demandedLogs, searchRadius(true), true);
		if (tree == null) tree = findTree(c, true, demandedLogs, EXTENDED_PLAN_RADIUS, false);
		if (tree == null) tree = findTree(c, true, demandedLogs, EXTENDED_PLAN_RADIUS, true);
		return tree;
	}

	/** Second, wider ring for construction wood when no reachable tree is left within {@link #PLAN_RADIUS}. */
	public static final int EXTENDED_PLAN_RADIUS = 112;

	private static BlockPos findTree(Ctx c, boolean restrictToPlan, Set<String> demandedLogs, int radius, boolean relaxed) {
		ServerLevel level = c.level();
		BlockPos center = c.v().blockPosition();
		BoundingBox village = c.village() == null ? null : de.villagerplus.village.Development.bounds(level, c.village());
		// Plan wood only keeps clear of actual buildings, not of the (huge) bounding box around the whole village.
		List<BlockPos> sites = relaxed && c.village() != null ? buildingSites(level, c.village()) : null;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos best = null;
		int bestD2 = Integer.MAX_VALUE;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int d2 = dx * dx + dz * dz;
				if (d2 >= bestD2) {
					continue;
				}
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				if (Math.abs(y - center.getY()) > 24 || !level.getBlockState(pos.set(x, y, z)).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
					continue;
				}
				int base = y;
				while (y - base < 48 && level.getBlockState(pos.set(x, base - 1, z)).is(BlockTags.LOGS)) {
					base--;
				}
				BlockPos root = new BlockPos(x, base, z);
			boolean inVillage = sites != null ? nearSite(sites, root) : village != null && root.getX() >= village.minX() && root.getX() <= village.maxX()
				&& root.getY() >= village.minY() && root.getY() <= village.maxY()
				&& root.getZ() >= village.minZ() && root.getZ() <= village.maxZ();
			if (!inVillage && (!restrictToPlan || matchesRequestedLog(level.getBlockState(root).getBlock(), demandedLogs))
				&& !treeUnreachable(c.v().getUUID(), root, c.now()) && isHarvestableTree(level, root)) {
					best = root;
					bestD2 = d2;
				}
			}
		}
		return best;
	}

	/** Trees a worker could not path to (water, cliffs); skipped for a day so it picks a reachable one instead. */
	private static final Map<java.util.UUID, Map<Long, Long>> UNREACHABLE_TREES = new java.util.HashMap<>();
	private static final long UNREACHABLE_TREE_TICKS = 24_000L;

	public static void markTreeUnreachable(java.util.UUID worker, BlockPos root, long now) {
		if (UNREACHABLE_TREES.size() > 512) UNREACHABLE_TREES.clear();
		UNREACHABLE_TREES.computeIfAbsent(worker, ignored -> new java.util.HashMap<>()).put(root.asLong(), now + UNREACHABLE_TREE_TICKS);
	}

	public static boolean treeUnreachable(java.util.UUID worker, BlockPos root, long now) {
		Map<Long, Long> trees = UNREACHABLE_TREES.get(worker);
		if (trees == null) return false;
		Long until = trees.get(root.asLong());
		if (until == null) return false;
		if (now >= until) { trees.remove(root.asLong()); return false; }
		return true;
	}

	/** Homes, built structures and the bell of a village: trees close to these are never felled for plan wood. */
	public static List<BlockPos> buildingSites(ServerLevel level, de.villagerplus.village.VillageRef ref) {
		List<BlockPos> sites = new java.util.ArrayList<>();
		sites.add(ref.bell());
		level.getPoiManager().getInRange(h -> h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME), ref.bell(), 110,
			net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).forEach(p -> sites.add(p.getPos().immutable()));
		for (var built : de.villagerplus.build.Builder.builtOf(ref)) sites.add(built.origin());
		return sites;
	}

	/** True when a tree stands within a building's margin (houses and built structures need room around them). */
	public static boolean nearSite(List<BlockPos> sites, BlockPos root) {
		for (BlockPos site : sites) {
			if (Math.abs(site.getX() - root.getX()) <= 9 && Math.abs(site.getZ() - root.getZ()) <= 9) return true;
		}
		return false;
	}

	/** Hard ceiling (blocks from the nearest village anchor) for a plan-wood trek before the stranded rescue applies. */
	static final int EXPEDITION_LIMIT = EXTENDED_PLAN_RADIUS + 48;

	/** True while this worker is walking to or felling a tracked tree (so the far-from-village rescue must not fire). */
	public static boolean onWoodExpedition(Ctx c) {
		return expeditionTarget(c) != null;
	}

	/** The tree this worker is committed to (persisted cursor first, then the walk target), or null. */
	public static BlockPos expeditionTarget(Ctx c) {
		WoodcutterWork work = c.v().getAttachedOrElse(de.villagerplus.VillagerPlus.WOODCUTTER_WORK, WoodcutterWork.empty());
		BlockPos target = work.root().orElse(null);
		if (target == null && c.st().targetEntity == null && c.st().target != null
			&& c.level().hasChunkAt(c.st().target)
			&& c.level().getBlockState(c.st().target).is(BlockTags.OVERWORLD_NATURAL_LOGS)) target = c.st().target;
		if (target == null) return null;
		return c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(target)) < 160.0 * 160.0 ? target : null;
	}

	private static final Map<java.util.UUID, Long> NEAREST_TREE_RECHECK = new java.util.HashMap<>();

	private static final Map<java.util.UUID, Long> LAST_DIAGNOSTIC = new java.util.HashMap<>();

	/** Dev aid: why a woodcutter leaves its tick without work (rate-limited per worker). */
	static void diagnose(Ctx c, String why) {
		long last = LAST_DIAGNOSTIC.getOrDefault(c.v().getUUID(), -1_000_000L);
		if (c.now() - last < 2400L) return;
		LAST_DIAGNOSTIC.put(c.v().getUUID(), c.now());
		org.slf4j.LoggerFactory.getLogger("villagerplus").info("Holzfaeller {}: {}", c.v().getName().getString(), why);
	}

	public static int searchRadius(boolean restrictToPlan) {
		return restrictToPlan ? PLAN_RADIUS : RADIUS;
	}

	/** Selects a small natural tree only inside a saved housing/market parcel or over a saved village-road cell. */
	static BlockPos findReservedCanopyTree(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null) return null;
		ServerLevel level = c.level();
		BlockPos worker = c.v().blockPosition();
		List<CanopyParcel> parcels = reservedCanopyParcels(level, ref, worker);
		Set<Long> checkedColumns = new HashSet<>();
		Set<Long> checkedRoots = new HashSet<>();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		int inspected = 0;
		for (CanopyParcel parcel : parcels) {
			int margin = parcel.road() ? RESERVED_CANOPY_RADIUS : 0;
			for (int x = parcel.minX() - margin; x <= parcel.maxX() + margin; x++) {
				for (int z = parcel.minZ() - margin; z <= parcel.maxZ() + margin; z++) {
					long column = BlockPos.asLong(x, 0, z);
					if (!checkedColumns.add(column)) continue;
					if (++inspected > RESERVED_CANOPY_SEARCH_COLUMNS) return best;
					if (!level.hasChunk(x >> 4, z >> 4)) continue;
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
					if (Math.abs(y - worker.getY()) > 64 || !level.getBlockState(new BlockPos(x, y, z)).is(BlockTags.OVERWORLD_NATURAL_LOGS)) continue;
					int baseY = y;
					while (y - baseY < 48 && level.getBlockState(new BlockPos(x, baseY - 1, z)).is(BlockTags.LOGS)) baseY--;
					BlockPos root = new BlockPos(x, baseY, z);
					if (!checkedRoots.add(root.asLong()) || !reservedCanopyRootInParcel(root, parcel)) continue;
					List<BlockPos> logs = connectedLogs(level, root);
					if (!smallSafeCanopy(level, ref, root, logs)) continue;
					if (parcel.road() && !leavesOverRoad(level, root, logs, parcel.roadCell())) continue;
					double distance = root.distSqr(worker);
					if (distance < bestDistance) {
						best = root.immutable();
						bestDistance = distance;
					}
				}
			}
		}
		return best;
	}

	static boolean isReservedCanopyWork(WoodcutterWork work) {
		return work != null && work.species().startsWith(RESERVED_CANOPY_PREFIX);
	}

	static WoodcutterWork finishTrackedTree(WoodcutterWork work) {
		return isReservedCanopyWork(work) ? work.abandonTree() : work.finishTree();
	}

	private static boolean reservedCanopyRoot(ServerLevel level, VillageRef ref, BlockPos root, BlockPos near) {
		if (root == null) return false;
		return reservedCanopyParcels(level, ref, near).stream().anyMatch(parcel -> reservedCanopyRootInParcel(root, parcel));
	}

	private static List<CanopyParcel> reservedCanopyParcels(ServerLevel level, VillageRef ref, BlockPos near) {
		List<CanopyParcel> parcels = new ArrayList<>();
		de.villagerplus.village.Roads.preparedExpansionParcel(ref).ifPresent(parcel -> {
			BlockPos center = parcel.center();
			parcels.add(new CanopyParcel(center.getX() - parcel.half(), center.getX() + parcel.half(),
				center.getZ() - parcel.half(), center.getZ() + parcel.half(), false, null));
		});
		de.villagerplus.village.MarketData market = de.villagerplus.village.Market.of(ref);
		market.plaza().ifPresent(plaza -> parcels.add(squareCanopyParcel(plaza, 12)));
		for (de.villagerplus.village.MarketData.Shop shop : market.shops()) parcels.add(squareCanopyParcel(shop.origin(), 8));
		de.villagerplus.village.Roads.savedVillagePathGrounds(level, ref).stream()
			.sorted(java.util.Comparator.comparingDouble(pos -> pos.distSqr(near)))
			.limit(RESERVED_ROAD_SAMPLES)
			.forEach(ground -> parcels.add(new CanopyParcel(ground.getX(), ground.getX(), ground.getZ(), ground.getZ(), true, ground)));
		return parcels;
	}

	private static CanopyParcel squareCanopyParcel(BlockPos center, int half) {
		return new CanopyParcel(center.getX() - half, center.getX() + half,
			center.getZ() - half, center.getZ() + half, false, null);
	}

	private static boolean reservedCanopyRootInParcel(BlockPos root, CanopyParcel parcel) {
		if (!parcel.road()) return root.getX() >= parcel.minX() && root.getX() <= parcel.maxX()
			&& root.getZ() >= parcel.minZ() && root.getZ() <= parcel.maxZ();
		return Math.abs(root.getX() - parcel.roadCell().getX()) <= RESERVED_CANOPY_RADIUS
			&& Math.abs(root.getZ() - parcel.roadCell().getZ()) <= RESERVED_CANOPY_RADIUS;
	}

	private static boolean smallSafeCanopy(ServerLevel level, VillageRef ref, BlockPos root, List<BlockPos> logs) {
		if (!reservedCanopySizeAllowed(logs.size(), canopyHeight(root, logs)) || !isHarvestableTree(level, root)) return false;
		int topY = logs.stream().mapToInt(BlockPos::getY).max().orElse(root.getY());
		if (level.getPoiManager().getInRange(point -> point.is(PoiTypes.HOME) || point.is(PoiTypes.MEETING), root, 8,
				PoiManager.Occupancy.ANY).findAny().isPresent()) return false;
		for (BlockPos log : logs) {
			if (!level.hasChunkAt(log) || level.getBlockEntity(log) != null
				|| de.villagerplus.job.Lighting.protectedVillageCell(level, ref, log)
				|| de.villagerplus.village.Roads.trustedVillagePathCell(level, ref, log)) return false;
		}
		// Reject nearby private containers and any unloaded edge rather than felling beside unseen ownership.
		for (int x = root.getX() - 2; x <= root.getX() + 2; x++) {
			for (int z = root.getZ() - 2; z <= root.getZ() + 2; z++) {
				for (int y = root.getY() - 1; y <= topY + 2; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!level.hasChunkAt(pos) || level.getBlockEntity(pos) != null) return false;
				}
			}
		}
		return true;
	}

	/** Expansion clearing accepts substantial natural trees, but remains bounded to a safe 64 logs and 32 blocks high. */
	static boolean reservedCanopySizeAllowed(int logCount, int height) {
		return logCount > 0 && logCount <= RESERVED_CANOPY_MAX_LOGS
			&& height > 0 && height <= RESERVED_CANOPY_MAX_HEIGHT;
	}

	private static int canopyHeight(BlockPos root, List<BlockPos> logs) {
		return logs.stream().mapToInt(BlockPos::getY).max().orElse(root.getY()) - root.getY() + 1;
	}

	private static boolean leavesOverRoad(ServerLevel level, BlockPos root, List<BlockPos> logs, BlockPos ground) {
		int topY = Math.min(root.getY() + RESERVED_CANOPY_MAX_HEIGHT + 4,
			logs.stream().mapToInt(BlockPos::getY).max().orElse(root.getY()) + 5);
		for (int y = Math.max(root.getY() + 1, ground.getY() + 1); y <= topY; y++) {
			BlockState leaves = level.getBlockState(new BlockPos(ground.getX(), y, ground.getZ()));
			if (leaves.is(BlockTags.LEAVES)
				&& (!leaves.hasProperty(LeavesBlock.PERSISTENT) || !leaves.getValue(LeavesBlock.PERSISTENT))) return true;
		}
		return false;
	}

	private record CanopyParcel(int minX, int maxX, int minZ, int maxZ, boolean road, BlockPos roadCell) { }

	/** Exact log items that can satisfy the current missing construction materials. */
	public static Set<String> demandedLogIds(List<String> wanted) {
		Set<String> result = new LinkedHashSet<>();
		String[] derivedSuffixes = {"_pressure_plate", "_hanging_sign", "_fence_gate", "_trapdoor", "_stairs", "_slab", "_fence", "_door", "_sign", "_button", "_wall"};
		for (String request : wanted) {
			if (request == null || request.isBlank()) continue;
			String rawId = request.split("#", 2)[0];
			net.minecraft.resources.Identifier id;
			try {
				id = net.minecraft.resources.Identifier.parse(rawId);
			} catch (RuntimeException invalidId) {
				continue;
			}
			var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null) continue;
			if (isNaturalLog(item)) {
				result.add(id.toString());
				continue;
			}
			String path = id.getPath();
			String logPath = null;
			if (path.startsWith("stripped_") && path.endsWith("_log")) {
				// The vanilla axe output consumes this exact natural-log species one-for-one.
				logPath = path.substring("stripped_".length());
			} else if (path.endsWith("_planks")) {
				logPath = path.substring(0, path.length() - "_planks".length()) + "_log";
			} else {
				for (String suffix : derivedSuffixes) {
					if (path.endsWith(suffix)) {
						logPath = path.substring(0, path.length() - suffix.length()) + "_log";
						break;
					}
				}
			}
			if (logPath == null) continue;
			net.minecraft.resources.Identifier logId = net.minecraft.resources.Identifier.fromNamespaceAndPath(id.getNamespace(), logPath);
			var log = BuiltInRegistries.ITEM.getOptional(logId).orElse(null);
			if (log != null && isNaturalLog(log)) result.add(logId.toString());
		}
		return Set.copyOf(result);
	}

	/** A candidate or saved cursor is usable only when its exact log item is still requested. */
	public static boolean matchesRequestedLog(Block log, Set<String> demandedLogs) {
		return log.defaultBlockState().is(BlockTags.OVERWORLD_NATURAL_LOGS)
			&& demandedLogs.contains(BuiltInRegistries.ITEM.getKey(log.asItem()).toString());
	}

	/** Carried construction supply must be a natural log of an exact species the active plan can use. */
	public static boolean matchesRequestedLog(ItemStack stack, Set<String> demandedLogs) {
		return !stack.isEmpty() && isNaturalLog(stack.getItem())
			&& demandedLogs.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	private static boolean isNaturalLog(net.minecraft.world.item.Item item) {
		return new ItemStack(item).is(ItemTags.LOGS)
			&& Block.byItem(item).defaultBlockState().is(BlockTags.OVERWORLD_NATURAL_LOGS);
	}

	/** Reject rooted log columns without a nearby leaf canopy; this avoids treating timber structures as harvestable trees. */
	public static boolean hasCanopy(ServerLevel level, BlockPos base) {
		int topY = base.getY();
		while (topY - base.getY() < 48 && level.getBlockState(new BlockPos(base.getX(), topY + 1, base.getZ())).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
			topY++;
		}
		for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, Math.max(1, topY - base.getY() - 1), -3),
			base.offset(3, Math.min(48, topY - base.getY() + 4), 3))) {
			BlockState state = level.getBlockState(p);
			if (state.is(BlockTags.LEAVES)
				&& (!state.hasProperty(LeavesBlock.PERSISTENT) || !state.getValue(LeavesBlock.PERSISTENT))) return true;
		}
		return false;
	}

	public static boolean isHarvestableTree(ServerLevel level, BlockPos base) {
		return level.getBlockState(base).is(BlockTags.OVERWORLD_NATURAL_LOGS)
			&& rootSoil(level.getBlockState(base.below())) && hasCanopy(level, base) && !hasBuiltWoodNearby(level, base);
	}

	/** Logs beside a built wooden feature are decorative/structural and remain protected. */
	private static boolean hasBuiltWoodNearby(ServerLevel level, BlockPos base) {
		for (BlockPos pos : BlockPos.betweenClosed(base.offset(-2, -1, -2), base.offset(2, 12, 2))) {
			BlockState state = level.getBlockState(pos);
			if (state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.FENCE_GATES)
				|| state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.WOODEN_TRAPDOORS) || state.is(BlockTags.WOODEN_STAIRS)
				|| state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_BUTTONS) || state.is(BlockTags.WOODEN_PRESSURE_PLATES)) return true;
		}
		return false;
	}

	private static boolean rootSoil(BlockState state) {
		return state.is(BlockTags.DIRT)||state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.MYCELIUM)||state.is(Blocks.PODZOL)||state.is(Blocks.COARSE_DIRT);
	}

	/** Alle zusammenhaengenden Staemme ab dem Fuss, hoechste zuerst. */
	private static List<BlockPos> connectedLogs(ServerLevel level, BlockPos base) {
		Set<Long> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		List<BlockPos> out = new ArrayList<>();
		queue.add(base);
		seen.add(base.asLong());
		while (!queue.isEmpty() && out.size() < MAX_LOGS) {
			BlockPos p = queue.poll();
			if (!level.getBlockState(p).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
				continue;
			}
			out.add(p);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos n = p.offset(dx, dy, dz);
						if (level.getBlockState(n).is(BlockTags.OVERWORLD_NATURAL_LOGS) && seen.add(n.asLong())) {
							queue.add(n);
						}
					}
				}
			}
		}
		out.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
		return out;
	}

	static boolean matchingSapling(String species, ItemStack stack) {
		if (!stack.is(ItemTags.SAPLINGS)) return false;
		return !species.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(species + "_sapling");
	}

	static String species(Block log) {
		String id = BuiltInRegistries.BLOCK.getKey(log).toString();
		int split = id.indexOf(':');
		String namespace = split < 0 ? "minecraft" : id.substring(0, split);
		String path = split < 0 ? id : id.substring(split + 1);
		if (path.endsWith("_log")) path = path.substring(0, path.length() - 4);
		else if (path.endsWith("_wood")) path = path.substring(0, path.length() - 5);
		else if (path.endsWith("_stem")) path = path.substring(0, path.length() - 5);
		else return "";
		return namespace + ":" + path;
	}

	private static boolean finishPendingRemoval(Ctx c, WoodcutterWork work) {
		ServerLevel level = c.level();
		BlockPos log = work.log().orElse(null);
		if (log != null && !level.hasChunkAt(log)) return true;
		boolean matchingLog = log != null && level.getBlockState(log).is(BlockTags.OVERWORLD_NATURAL_LOGS)
			&& BuiltInRegistries.BLOCK.getKey(level.getBlockState(log).getBlock()).toString().equals(work.pendingBlock());
		if (!work.removalConfirmed() && matchingLog) {
			if (!canFitAllDrops(c.v().getInventory(), work.pendingDrops())) {
				c.setActivity("wartet auf freien Inventarplatz für den gefällten Stamm");
				return true;
			}
		}
		if (work.removalConfirmed() && !canFitAllDrops(c.v().getInventory(), work.pendingDrops())) {
			c.setActivity("wartet auf freien Inventarplatz für den gefällten Stamm");
			return true;
		}
		if (!work.removalConfirmed() && work.root().filter(root -> hasBuiltWoodNearby(level, root)).isPresent()) {
			setWork(c, work.abandonTree());
			return true;
		}
		if (!work.removalConfirmed() && matchingLog) {
			BlockState felled = level.getBlockState(log);
			if (!level.setBlock(log, Blocks.AIR.defaultBlockState(), 3)) return true;
			setWork(c, work.confirmRemoval());
			Anim.blockBreak(level, log, felled);
			return true;
		}
		if (!work.removalConfirmed()) {
			// Another actor changed the block after our claim. Its drops belong to that change;
			// discard our unearned claim rather than duplicate them into the villager inventory.
			setWork(c, work.afterRemovalDelivered());
			return true;
		}
		if (matchingLog) {
			// After reload, a persisted confirmation plus the same block still present is ambiguous
			// (the chunk may have saved before removal). Prefer no reward over duplicating the log.
			setWork(c, work.afterRemovalDelivered());
			return true;
		}
		for (ItemStack drop : work.pendingDrops()) JobUtil.give(level, c.v(), drop.copy());
		WoodcutterWork next = work.afterRemovalDelivered();
		if (log != null && work.root().filter(log::equals).isPresent()) {
			BlockPos stump = work.root().orElseThrow();
			if (isReservedCanopyWork(work)) {
				next = next.abandonTree();
			} else {
				next = next.finishTree();
				next = queuePlantation(c, next, stump, work.species());
			}
		}
		setWork(c, next);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		c.st().cooldownUntil = c.now() + 16;
		return true;
	}

	/**
	 * Simulate the whole drop batch against a copy of the villager inventory. Testing each stack
	 * against the live inventory independently can approve several drops that all need the same
	 * last slot, causing later drops to spill onto the ground after the tree is removed.
	 */
	static boolean canFitAllDrops(Container inventory, List<ItemStack> drops) {
		SimpleContainer simulated = new SimpleContainer(inventory.getContainerSize());
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			simulated.setItem(slot, inventory.getItem(slot).copy());
		}
		for (ItemStack drop : drops) {
			if (drop.isEmpty()) continue;
			if (!simulated.addItem(drop.copy()).isEmpty()) return false;
		}
		return true;
	}

	private static WoodcutterWork replantNearby(Ctx c, WoodcutterWork work) {
		if (work.replantSites().isEmpty()) return work;
		List<WoodcutterWork.ReplantSite> remaining = new ArrayList<>(work.replantSites());
		BoundingBox villageBounds = c.village() == null ? null : de.villagerplus.village.Development.bounds(c.level(), c.village());
		boolean changed = false;
		for (int i = 0; i < remaining.size(); i++) {
			WoodcutterWork.ReplantSite site = remaining.get(i);
			if (site.retryAt() > c.now()) continue;
			BlockPos base = site.pos();
			if (!c.level().hasChunkAt(base)) continue;
			Block sapling = saplingBlock(site.species());
			if (sapling == null || !canPlantAt(c.level(), villageBounds, base, sapling)) {
				remaining.remove(i--);
				changed = true;
				continue;
			}
			if (c.v().blockPosition().distSqr(base) > 20) continue;
			if (plantFromInventory(c, base, site.species(), sapling, villageBounds)
				|| plantFromChest(c, base, site.species(), sapling, villageBounds)) {
				remaining.remove(i--);
				changed = true;
			} else {
				remaining.set(i, site.retryAt(c.now() + 1200));
				changed = true;
			}
		}
		if (!changed) return work;
		WoodcutterWork updated = work.updateReplantSites(remaining);
		setWork(c, updated);
		return updated;
	}

	private static boolean plantFromInventory(Ctx c, BlockPos base, String species, Block sapling, BoundingBox villageBounds) {
		Container inv = c.v().getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!matchingSapling(species, stack)) continue;
			if (!canPlantAt(c.level(), villageBounds, base, sapling)
				|| !c.level().setBlock(base, sapling.defaultBlockState(), 3)) return false;
			stack.shrink(1);
			inv.setChanged();
			return true;
		}
		return false;
	}

	private static boolean plantFromChest(Ctx c, BlockPos base, String species, Block sapling, BoundingBox villageBounds) {
		if (c.village() == null) return false;
		Container box = JobUtil.box(c);
		if (box == null) return false;
		for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack stack = box.getItem(i);
			if (!matchingSapling(species, stack)) continue;
			if (!canPlantAt(c.level(), villageBounds, base, sapling)
				|| !c.level().setBlock(base, sapling.defaultBlockState(), 3)) return false;
			box.removeItem(i, 1);
			box.setChanged();
			return true;
		}
		return false;
	}

	/** Resolve the one exact sapling item for a logged tree species; no cross-species substitution is allowed. */
	static Block saplingBlock(String species) {
		if (species == null || species.isBlank()) return null;
		net.minecraft.resources.Identifier id;
		try {
			id = net.minecraft.resources.Identifier.parse(species + "_sapling");
		} catch (RuntimeException invalidId) {
			return null;
		}
		var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null || !new ItemStack(item).is(ItemTags.SAPLINGS)) return null;
		Block sapling = Block.byItem(item);
		return sapling == Blocks.AIR ? null : sapling;
	}

	/** A replant target is vacant, outside the village footprint, and on an allowed forest-soil block. */
	static boolean canPlantAt(ServerLevel level, BoundingBox villageBounds, BlockPos pos, Block sapling) {
		if (sapling == null || sapling == Blocks.AIR || !level.hasChunkAt(pos) || inBounds(villageBounds, pos)) return false;
		BlockPos soilPos = pos.below();
		if (!level.hasChunkAt(soilPos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.above(2))) return false;
		BlockState target = level.getBlockState(pos);
		BlockState soil = level.getBlockState(soilPos);
		if (!target.isAir() || target.hasBlockEntity() || level.getBlockEntity(pos) != null
			|| !naturalForestSoil(soil) || soil.hasBlockEntity() || level.getBlockEntity(soilPos) != null
			|| !level.getBlockState(pos.above()).isAir() || !level.getBlockState(pos.above(2)).isAir()
			|| !level.getBlockState(pos.above()).getFluidState().isEmpty()
			|| !level.getBlockState(pos.above(2)).getFluidState().isEmpty()
			|| hasBuiltWoodNearby(level, pos)) return false;
		return sapling.defaultBlockState().canSurvive(level, pos);
	}

	private static boolean naturalForestSoil(BlockState state) {
		return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
			|| state.is(Blocks.COARSE_DIRT) || state.is(Blocks.MYCELIUM);
	}

	private static boolean inBounds(BoundingBox bounds, BlockPos pos) {
		return bounds != null && pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
			&& pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
			&& pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
	}

	/**
	 * After a tree is removed, queue a compact stump-centered plantation. If the stump is unsafe, use up to three
	 * nearby fallback gaps instead. The bounded persistent queue resumes after reload and spends only matching saplings.
	 */
	static WoodcutterWork queuePlantation(Ctx c, WoodcutterWork work, BlockPos stump, String species) {
		Block sapling = saplingBlock(species);
		if (sapling == null) return work;
		BoundingBox villageBounds = c.village() == null ? null : de.villagerplus.village.Development.bounds(c.level(), c.village());
		if (!canPlantAt(c.level(), villageBounds, stump, sapling)) {
			return queueFallbackPatch(c, work, stump, species);
		}
		List<WoodcutterWork.ReplantSite> sites = new ArrayList<>(work.replantSites());
		if (sites.stream().noneMatch(site -> site.pos().equals(stump))) {
			sites.add(new WoodcutterWork.ReplantSite(stump, species, 0));
		}
		int added = 0;
		for (BlockPos candidate : plantationCandidates(stump)) {
			if (added >= PLANTATION_SITES_PER_TREE - 1 || sites.size() >= MAX_REPLANT_SITES) break;
			if (sites.stream().anyMatch(site -> site.pos().equals(candidate))
				|| !canPlantAt(c.level(), villageBounds, candidate, sapling)) continue;
			sites.add(new WoodcutterWork.ReplantSite(candidate, species, 0));
			added++;
		}
		while (sites.size() > MAX_REPLANT_SITES) sites.remove(0);
		return work.updateReplantSites(sites);
	}

	/** Keep a fallback around an unusable stump for callers that need a patch independent of that stump. */
	static WoodcutterWork queueFallbackPatch(Ctx c, WoodcutterWork work, BlockPos stump, String species) {
		Block sapling = saplingBlock(species);
		BoundingBox villageBounds = c.village() == null ? null : de.villagerplus.village.Development.bounds(c.level(), c.village());
		if (sapling == null || canPlantAt(c.level(), villageBounds, stump, sapling)) return work;
		List<WoodcutterWork.ReplantSite> sites = new ArrayList<>(work.replantSites().stream()
			.filter(site -> !site.pos().equals(stump)).toList());
		while (sites.size() > MAX_REPLANT_SITES) sites.remove(0);
		int added = 0;
		for (BlockPos candidate : fallbackPatchCandidates(stump)) {
			if (sites.size() >= MAX_REPLANT_SITES || added >= FALLBACK_PATCH_SITES) break;
			if (sites.stream().anyMatch(site -> site.pos().equals(candidate))
				|| !canPlantAt(c.level(), villageBounds, candidate, sapling)) continue;
			sites.add(new WoodcutterWork.ReplantSite(candidate, species, 0));
			added++;
		}
		if (added == 0) return work.updateReplantSites(sites);
		while (sites.size() > MAX_REPLANT_SITES) sites.remove(0);
		return work.updateReplantSites(sites);
	}

	static List<BlockPos> plantationCandidates(BlockPos stump) {
		return List.of(stump.east(4), stump.west(4), stump.north(4), stump.south(4));
	}

	static List<BlockPos> fallbackPatchCandidates(BlockPos stump) {
		return List.of(stump.east(2), stump.west(2), stump.north(2), stump.south(2));
	}

	private static void setWork(Ctx c, WoodcutterWork work) {
		c.v().setAttached(de.villagerplus.VillagerPlus.WOODCUTTER_WORK, work);
	}
}
