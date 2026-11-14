package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Built;
import de.villagerplus.build.Furnisher;
import de.villagerplus.build.Step;
import de.villagerplus.job.Anim;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Abriss: Ein Maurer traegt ein selbst gebautes Gebaeude Block fuer Block von oben nach unten ab - mit sichtbaren Rissstufen
 * 0-9, Werkzeugschwung und Partikeln. Baumaterial und Kisteninhalt wandern in die Dorfkiste; das Gelaende wird ebnet und mit der
 * Oberflaeche der Umgebung renaturiert. Ausgeloest ueber die Karte der Dorftafel (Gebaeude anklicken -> Abriss).
 */
public final class Demolition {
	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	record RemovalResult(boolean removed, boolean dropsSecured) { }
	private static final int MAX_RESTORATION_DEPTH = 16;
	private static final int MAX_RESTORATION_SIDE = 64;
	private static final long MAX_RESTORATION_COLUMNS = 4096L;
	public record Job(BlockPos origin, int index, List<BlockPos> additionalBlocks, boolean additionalBlocksCaptured) {
		public Job {
			origin = origin.immutable();
			additionalBlocks = additionalBlocks.stream().map(BlockPos::immutable).distinct().toList();
		}

		/** Preserves compatibility with callers and old saves that only stored the cursor. */
		public Job(BlockPos origin, int index) {
			this(origin, index, List.of(), false);
		}

		public static final Codec<Job> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("origin").forGetter(Job::origin),
			Codec.INT.optionalFieldOf("index", 0).forGetter(Job::index),
			BlockPos.CODEC.listOf().optionalFieldOf("additional_blocks", List.of()).forGetter(Job::additionalBlocks),
			Codec.BOOL.optionalFieldOf("additional_blocks_captured", false).forGetter(Job::additionalBlocksCaptured)
		).apply(i, Job::new));
	}

	private record Key(ServerLevel level, BlockPos pos) {}
	private record Claim(java.util.UUID owner, long touchedAt) {}
	private static final Map<Key, List<Step>> STEPS = new HashMap<>();
	private static final Map<Key, Claim> CLAIMS = new HashMap<>();
	static {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) ->
			CLAIMS.entrySet().removeIf(e -> e.getKey().level() == level && e.getValue().owner().equals(entity.getUUID())));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			STEPS.clear();
			CLAIMS.clear();
		});
	}

	private Demolition() {
	}

	static int safeCursor(int index, int size) {
		return Math.max(0, Math.min(index, size));
	}

	/** Captures in-building blocks added after the saved architect plan (for example furnished storage or school fixtures). */
	private static List<BlockPos> additionalBlocks(ServerLevel level, VillageRef ref, Built built) {
		List<Step> planned = Builder.stepsOfBuilt(level, ref, built, null);
		if (planned.isEmpty()) return List.of();
		Set<Long> plannedPositions = new HashSet<>();
		for (Step step : planned) plannedPositions.add(step.pos().asLong());
		BoundingBox box = Furnisher.boxOf(planned);
		List<BlockPos> extras = new ArrayList<>();
		for (BlockPos cursor : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			BlockPos pos = cursor.immutable();
			BlockState state = level.getBlockState(pos);
			if (plannedPositions.contains(pos.asLong()) || skipDuringDemolition(state) || VillageStyle.natural(state)) continue;
			extras.add(pos);
		}
		extras.sort(Comparator.<BlockPos>comparingInt(pos -> pos.getY()).reversed()
			.thenComparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getZ()));
		return List.copyOf(extras);
	}

	/** Captures legacy jobs once and keeps the old cursor anchored to the deterministic architect-plan prefix. */
	static Job captureAdditionalBlocks(ServerLevel level, VillageRef ref, Built built, Job job) {
		if (job.additionalBlocksCaptured()) return job;
		int baseSize = Builder.stepsOfBuilt(level, ref, built, null).size();
		return new Job(job.origin(), safeCursor(job.index(), baseSize), additionalBlocks(level, ref, built), true);
	}

	/** Architect steps stay first; captured furnished blocks follow in their persisted, stable order. */
	static List<Step> stepsForJob(ServerLevel level, VillageRef ref, Built built, Job job, net.minecraft.world.entity.npc.villager.Villager villager) {
		List<Step> all = new ArrayList<>(Builder.stepsOfBuilt(level, ref, built, villager));
		all.sort(Comparator.comparingInt((Step step) -> step.pos().getY()).reversed());
		Set<Long> included = new HashSet<>();
		for (Step step : all) included.add(step.pos().asLong());
		BoundingBox box = all.isEmpty() ? null : Furnisher.boxOf(all);
		for (BlockPos pos : job.additionalBlocks()) {
			if (box == null || !inside(box, pos) || !included.add(pos.asLong())) continue;
			// The observed state is not used to rebuild the block; it only supplies a stable cursor position.
			all.add(Step.free(pos, Blocks.AIR.defaultBlockState()));
		}
		return all;
	}

	public static List<Job> jobs(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.DEMOLITION, List.of());
	}

	private static void save(VillageRef ref, List<Job> jobs) {
		ref.bellEntity().setAttached(VillagerPlus.DEMOLITION, List.copyOf(jobs));
		ref.dirty();
	}

	private static boolean allowed(String type) {
		return type.equals(Blueprints.HOUSE) || type.equals(Blueprints.SCHOOL) || Blueprints.special(type);
	}

	public static String label(String type) {
		return switch (type) {
			case Blueprints.HOUSE -> "Wohnhaus";
			case Blueprints.SCHOOL -> "Dorfschule";
			case Blueprints.GRANARY -> "Kornspeicher";
			case Blueprints.TOOLSHED -> "Werkzeugkammer";
			case Blueprints.KITCHEN -> "Dorfküche";
			case Blueprints.STATION -> "Bahnhof";
			default -> type;
		};
	}

	/** Zeilen fuer die Tafel: {@code @o|x|z} (Rasterursprung) und {@code @b|typ|name|x|y|z|abriss} je abreissbarem Gebaeude. */
	public static List<String> mapRows(ServerLevel level, VillageRef ref) {
		List<String> rows = new ArrayList<>();
		int[] o = VillageMap.gridOrigin(level, ref);
		rows.add("@o|" + o[0] + "|" + o[1]);
		var queued = jobs(ref);
		for (Built b : Builder.builtOf(ref)) {
			if (allowed(b.type())) {
				boolean running = queued.stream().anyMatch(j -> j.origin().equals(b.origin()));
				rows.add("@b|" + b.type() + "|" + label(b.type()) + "|" + b.origin().getX() + "|" + b.origin().getY() + "|" + b.origin().getZ() + "|" + (running ? "1" : "0"));
			}
		}
		return rows;
	}

	/** Abriss anordnen (Gebaeude in der Naehe von {@code at}). Meldet den Grund bei Ablehnung. */
	public static String request(ServerLevel level, VillageRef ref, BlockPos at) {
		Built found = null;
		for (Built b : Builder.builtOf(ref)) {
			if (allowed(b.type()) && Math.abs(b.origin().getX() - at.getX()) <= 3 && Math.abs(b.origin().getZ() - at.getZ()) <= 3) {
				found = b;
			}
		}
		if (found == null) {
			return "Dort steht kein abreißbares Gebäude des Dorfes.";
		}
		final Built target = found;
		if (!footprintLoaded(level, ref, target)) {
			return "Der Gebäudeabschnitt ist nicht vollständig geladen. Geh näher heran und ordne den Abriss erneut an.";
		}
		if (hasPrivateStorage(level, ref, target)) {
			return "Im Gebäude steht noch eine private Truhe. Räume oder versetze sie zuerst; der Abriss schützt private Inhalte.";
		}
		if (overlapsConstructionReservation(ref, buildingBounds(level, ref, target))) {
			return "Das Gebäude oder sein Bauplatz wird von einem laufenden Bauprojekt benötigt.";
		}
		List<Job> list = new ArrayList<>(jobs(ref));
		if (list.stream().anyMatch(j -> j.origin().equals(target.origin()))) {
			return "Der Abriss läuft schon.";
		}
		// The worker snapshots the complete in-building contents on its first tick so edits
		// between the map action and the start of physical work are included as well.
		list.add(new Job(target.origin(), 0));
		save(ref, list);
		Journal.add(level, ref, "Bauen", "Dorftafel", "Abriss angeordnet: " + label(target.type()), 0);
		return "Abriss angeordnet: " + label(target.type()) + ". Ein Maurer trägt es Block für Block ab.";
	}

	/** Arbeit des Maurers am Abriss (true = beschaeftigt). */
	public static boolean work(Ctx c, VillageRef ref) {
		List<Job> list = new ArrayList<>(jobs(ref));
		// With no demolition job, this subsystem must not cancel another activity's
		// breaker (for example the active-plan wood supplier called by Builder.tick).
		if (list.isEmpty()) return false;
		ServerLevel level = c.level();
		Job job = list.get(0);
		BlockPos jobOrigin = job.origin();
		Built b = Builder.builtOf(ref).stream().filter(x -> x.origin().equals(jobOrigin)).findFirst().orElse(null);
		if (b == null || !level.hasChunkAt(job.origin())) {
			clearSite(level, job.origin());
			if (b == null) {
				list.remove(0);
				save(ref, list);
			}
			return false;
		}
		if (!footprintLoaded(level, ref, b)) {
			Anim.cancelBreaking(c.v());
			c.setActivity("wartet, bis das gesamte Gebäude geladen ist");
			return true;
		}
		if (overlapsConstructionReservation(ref, buildingBounds(level, ref, b))) {
			Anim.cancelBreaking(c.v());
			c.setActivity("wartet, bis das überlappende Bauprojekt abgeschlossen ist");
			return true;
		}
		if (!job.additionalBlocksCaptured()) {
			job = captureAdditionalBlocks(level, ref, b, job);
			list.set(0, job);
			save(ref, list);
		}
		Job activeJob = job;
		List<Step> steps = STEPS.computeIfAbsent(new Key(level, activeJob.origin()),
			k -> stepsForJob(level, ref, b, activeJob, c.v()));
		// Persisted attachments can outlive code changes or be manually edited. Keep the cursor in range
		// so a negative index cannot crash a mason and an oversized cursor safely reaches completion.
		int i = safeCursor(job.index(), steps.size());
		while (i < steps.size()) {
			if (!level.hasChunkAt(steps.get(i).pos())) {
				Anim.cancelBreaking(c.v());
				return true;
			}
			BlockState st = level.getBlockState(steps.get(i).pos());
			if (skipDuringDemolition(st)) {
				i++;
			} else {
				break;
			}
		}
		if (i >= steps.size()) {
			if (!footprintLoaded(level, ref, b)) {
				Anim.cancelBreaking(c.v());
				c.setActivity("wartet, bis das gesamte Gebäude geladen ist");
				return true;
			}
			Set<BlockPos> capturedExtras = new HashSet<>(job.additionalBlocks());
			List<BlockPos> lateExtras = additionalBlocks(level, ref, b).stream()
				.filter(pos -> !capturedExtras.contains(pos)).toList();
			if (!lateExtras.isEmpty()) {
				List<BlockPos> updatedExtras = new ArrayList<>(job.additionalBlocks());
				updatedExtras.addAll(lateExtras);
				job = new Job(job.origin(), i, updatedExtras, true);
				list.set(0, job);
				save(ref, list);
				STEPS.remove(new Key(level, job.origin()));
				return true;
			}
			if (hasPrivateStorage(level, ref, b)) {
				Anim.cancelBreaking(c.v());
				c.setActivity("wartet auf Räumung einer privaten Truhe");
				return true;
			}
			finish(c, ref, b, steps);
			list.remove(0);
			save(ref, list);
			return true;
		}
		// Parallelarbeit: jeder Maurer reserviert sich einen eigenen, von keinem anderen beanspruchten Block (Reservierung verfaellt nach 3 s).
		BlockPos pos = null;
		BlockPos available = null;
		java.util.UUID me = c.v().getUUID();
		CLAIMS.entrySet().removeIf(e -> {
			if (e.getKey().level() != level || c.now() - e.getValue().touchedAt() <= 60) return false;
			if (level.getEntity(e.getValue().owner()) instanceof net.minecraft.world.entity.npc.villager.Villager owner) Anim.cancelBreaking(owner);
			return true;
		});
		for (int j = i, seen = 0; j < steps.size() && seen < 60; j++) {
			BlockPos cand = steps.get(j).pos();
			if (!level.hasChunkAt(cand)) continue;
			BlockState cs = level.getBlockState(cand);
			if (skipDuringDemolition(cs)) {
				continue;
			}
			seen++;
			Claim claim = CLAIMS.get(new Key(level, cand));
			if (claim != null && claim.owner().equals(me)) {
				pos = cand;
				break;
			}
			if (claim == null && available == null) available = cand;
		}
		if (pos == null) pos = available;
		if (pos == null) {
			Anim.cancelBreaking(c.v());
			return true; // All candidates belong to other masons; never steal their crack progress.
		}
		CLAIMS.put(new Key(level, pos), new Claim(me, c.now()));
		BlockState state = level.getBlockState(pos);
		c.setActivity("reißt " + label(b.type()) + " ab");
		if (hasPrivateContainer(level, pos)) {
			CLAIMS.remove(new Key(level, pos));
			Anim.cancelBreaking(c.v());
			c.setActivity("wartet auf Räumung einer privaten Truhe");
			return true;
		}
		// Der Maurer steht am Boden unter dem Block (Dachfirst liegt hoch ueber ihm); gezaehlt wird nur die Entfernung in der Ebene.
		if (!JobUtil.approach(c, new BlockPos(pos.getX(), b.origin().getY(), pos.getZ()), 4.0)) {
			Anim.cancelBreaking(c.v());
			return true;
		}
		if (!Anim.breakStep(c, pos, state, demolitionTool(state))) {
			c.st().cooldownUntil = c.now() + 6;
		} else {
			if (!removeAndCollect(level, ref, pos, state, c.v())) {
				CLAIMS.remove(new Key(level, pos));
				c.setActivity(hasPrivateContainer(level, pos)
					? "wartet auf Räumung einer privaten Truhe"
					: "wartet auf erfolgreichen Blockabbau");
				c.st().cooldownUntil = c.now() + 6;
				return true;
			}
			CLAIMS.remove(new Key(level, pos));
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, state),
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.05);
			level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
			// A parallel worker may have removed a later step. Never skip the still occupied cursor block.
			if (pos.equals(steps.get(i).pos())) i++;
			c.mem().jobsDone++;
			c.st().cooldownUntil = c.now() + 4;
		}
		list.set(0, new Job(job.origin(), i, job.additionalBlocks(), job.additionalBlocksCaptured()));
		save(ref, list);
		return true;
	}

	private static net.minecraft.world.item.Item demolitionTool(BlockState state) {
		if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) return Items.IRON_AXE;
		if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) return Items.IRON_SHOVEL;
		if (state.is(net.minecraft.tags.BlockTags.WOOL)) return Items.SHEARS;
		return Items.IRON_PICKAXE;
	}

	/** Leaves free fluid alone, but does not mistake a waterlogged building block for an empty position. */
	static boolean skipDuringDemolition(BlockState state) {
		return state.isAir() || (!state.getFluidState().isEmpty()
			&& !state.hasProperty(BlockStateProperties.WATERLOGGED));
	}

	private static void clearSite(ServerLevel level, BlockPos origin) {
		List<Step> steps = STEPS.remove(new Key(level, origin));
		if (steps == null) return;
		for (Step step : steps) {
			Claim claim = CLAIMS.remove(new Key(level, step.pos()));
			if (claim != null && level.getEntity(claim.owner()) instanceof net.minecraft.world.entity.npc.villager.Villager owner) Anim.cancelBreaking(owner);
		}
	}

	/** Drops und öffentliche Kisteninhalte gehen in Dorfdepots; private Villager-Truhen blockieren den Abriss. */
	static boolean collect(ServerLevel level, VillageRef ref, BlockPos pos, BlockState state,
			net.minecraft.world.entity.npc.villager.Villager breaker) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof Container && be.getAttached(VillagerPlus.OWNER) != null) return false;
		List<ItemStack> contents = snapshotContents(be);
		List<ItemStack> out = captureDrops(level, pos, state, be, breaker, contents);
		if (be instanceof Container cont) {
			cont.clearContent();
		}
		return returnDrops(level, ref, pos, out);
	}

	/** Removes first and routes the saved drops only after that removal succeeds. */
	static boolean removeAndCollect(ServerLevel level, VillageRef ref, BlockPos pos, BlockState state,
			net.minecraft.world.entity.npc.villager.Villager breaker) {
		return removeAndCollect(level, ref, pos, state, breaker,
			() -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
	}

	/** The removal action overload keeps failure handling deterministic in the isolated world fixture. */
	static boolean removeAndCollect(ServerLevel level, VillageRef ref, BlockPos pos, BlockState state,
			net.minecraft.world.entity.npc.villager.Villager breaker, BooleanSupplier removeBlock) {
		return removeAndCollectResult(level, ref, pos, state, breaker, removeBlock).removed();
	}

	static RemovalResult removeAndCollectResult(ServerLevel level, VillageRef ref, BlockPos pos, BlockState state,
			net.minecraft.world.entity.npc.villager.Villager breaker, BooleanSupplier removeBlock) {
		if (!level.hasChunkAt(pos) || state.isAir() || level.getBlockState(pos) != state) return new RemovalResult(false, false);
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof Container && be.getAttached(VillagerPlus.OWNER) != null) return new RemovalResult(false, false);

		// Keep a slot-exact snapshot: some container loot tables consume their live inventory
		// while constructing drops, and a rejected block removal must not consume that inventory.
		List<ItemStack> contents = snapshotContents(be);
		List<ItemStack> out;
		try {
			out = captureDrops(level, pos, state, be, breaker, contents);
		} catch (RuntimeException | Error failure) {
			restoreContainerContents(level, pos, state, be, contents);
			throw failure;
		}
		if (be instanceof Container container) container.clearContent();
		boolean removed;
		try {
			removed = removeBlock.getAsBoolean();
		} catch (RuntimeException | Error failure) {
			if (level.getBlockState(pos) == state) restoreContainerContents(level, pos, state, be, contents);
			else returnDrops(level, ref, pos, out);
			throw failure;
		}
		if (!removed && level.getBlockState(pos) == state) {
			restoreContainerContents(level, pos, state, be, contents);
			return new RemovalResult(false, true);
		}
		return new RemovalResult(true, returnDrops(level, ref, pos, out));
	}

	private static List<ItemStack> snapshotContents(BlockEntity be) {
		if (!(be instanceof Container container)) return List.of();
		List<ItemStack> contents = new ArrayList<>(container.getContainerSize());
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			contents.add(container.getItem(slot).copy());
		}
		return contents;
	}

	private static List<ItemStack> captureDrops(ServerLevel level, BlockPos pos, BlockState state, BlockEntity be,
			net.minecraft.world.entity.npc.villager.Villager breaker, List<ItemStack> contents) {
		List<ItemStack> out = new ArrayList<>(Block.getDrops(state, level, pos, be, breaker, new ItemStack(demolitionTool(state))));
		// Preserve the captured inventory even if the loot table changed the live block entity.
		// If the loot table already emitted the same stacks, only append a missing remainder.
		for (ItemStack content : contents) {
			if (content.isEmpty()) continue;
			int represented = out.stream().filter(drop -> ItemStack.isSameItemSameComponents(drop, content))
				.mapToInt(ItemStack::getCount).sum();
			if (represented < content.getCount()) out.add(content.copyWithCount(content.getCount() - represented));
		}
		return out;
	}

	private static void restoreContainerContents(ServerLevel level, BlockPos pos, BlockState state, BlockEntity be,
			List<ItemStack> contents) {
		if (!(be instanceof Container) || level.getBlockState(pos) != state) return;
		if (!(level.getBlockEntity(pos) instanceof Container container)) return;
		container.clearContent();
		for (int slot = 0; slot < Math.min(container.getContainerSize(), contents.size()); slot++) {
			container.setItem(slot, contents.get(slot).copy());
		}
		container.setChanged();
	}

	private static boolean returnDrops(ServerLevel level, VillageRef ref, BlockPos pos, List<ItemStack> drops) {
		boolean secured = true;
		for (ItemStack drop : drops) {
			ItemStack rest = StockPolicy.insertVillage(level, ref, drop);
			if (!rest.isEmpty()) {
				// StockPolicy already routed every supported category to its village depot.
				// Any remainder, including OTHER items from a public chest, must stay recoverable;
				// create the returned entity directly: Block.popResource silently produced no
				// entity for this path even when BLOCK_DROPS was enabled.
				if (!spawnRemainder(level, pos, rest)) {
					BlockPos fallback = ref.bell().above(2);
					if (!spawnRemainder(level, fallback, rest)) {
						LOGGER.error("Could not return demolition remainder {} near {} or village bell {}",
							rest, pos, ref.bell());
						secured = false;
					}
				}
			}
		}
		return secured;
	}

	private static boolean spawnRemainder(ServerLevel level, BlockPos pos, ItemStack remainder) {
		ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, remainder.copy());
		entity.setDefaultPickUpDelay();
		return level.addFreshEntity(entity);
	}

	/** Reject an order before work starts when any inventory belonging to a resident lies within the blueprint footprint. */
	static boolean hasPrivateStorage(ServerLevel level, VillageRef ref, Built built) {
		List<Step> steps = Builder.stepsOfBuilt(level, ref, built, null);
		if (steps.isEmpty()) return false;
		var box = Furnisher.boxOf(steps);
		for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			if (level.hasChunkAt(p) && hasPrivateContainer(level, p)) return true;
		}
		return false;
	}

	/** Bounds include the builder's three-block reservation margin, matching its project-site checks. */
	static VillageData.PlanBounds buildingBounds(ServerLevel level, VillageRef ref, Built built) {
		List<Step> steps = Builder.stepsOfBuilt(level, ref, built, null);
		if (steps.isEmpty()) return new VillageData.PlanBounds(built.origin().getX() - 3, built.origin().getX() + 3,
			built.origin().getZ() - 3, built.origin().getZ() + 3);
		var box = Furnisher.boxOf(steps);
		return new VillageData.PlanBounds(box.minX() - 3, box.maxX() + 3, box.minZ() - 3, box.maxZ() + 3);
	}

	/** Includes non-primary projects and cleanup records, whose saved scaffold/material state also reserves its site. */
	static boolean overlapsConstructionReservation(VillageRef ref, VillageData.PlanBounds target) {
		VillageData data = ref.data();
		for (VillageData.BuildProject project : data.buildProjects) {
			if (!project.active() && !project.cleanupOnly()) continue;
			VillageData.PlanBounds reserved = project.bounds().orElseGet(() -> fallbackProjectBounds(project));
			if (target.overlaps(reserved)) return true;
		}
		// Pre-migration worlds may still have only the legacy single-plan view.
		if (data.hasPlan() && data.buildProjects.stream().noneMatch(project -> project.active()
			&& project.origin().equals(data.planOrigin.orElseThrow()))) {
			VillageData.PlanBounds legacy = fallbackPlanBounds(data.planType, data.planOrigin.orElseThrow(), data.planVariant);
			if (target.overlaps(legacy)) return true;
		}
		return false;
	}

	private static VillageData.PlanBounds fallbackProjectBounds(VillageData.BuildProject project) {
		if (project.cleanupOnly()) {
			BlockPos p = project.origin();
			return new VillageData.PlanBounds(p.getX() - 3, p.getX() + 3, p.getZ() - 3, p.getZ() + 3);
		}
		return fallbackPlanBounds(project.type(), project.origin(), project.variant());
	}

	private static VillageData.PlanBounds fallbackPlanBounds(String type, BlockPos origin, int variant) {
		// Old persisted projects without bounds are rare; reserve a deliberately broad footprint until Builder
		// regenerates their exact bounds so a legacy plan cannot be overwritten by demolition or relocation.
		int half = Math.max(64, Math.max(0, variant / 10)) + 3;
		return new VillageData.PlanBounds(origin.getX() - half, origin.getX() + half, origin.getZ() - half, origin.getZ() + half);
	}

	private static boolean footprintLoaded(ServerLevel level, VillageRef ref, Built built) {
		List<Step> steps = Builder.stepsOfBuilt(level, ref, built, null);
		if (steps.isEmpty()) return true;
		var box = Furnisher.boxOf(steps);
		for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
			for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
				if (!level.hasChunk(x, z)) return false;
			}
		}
		return true;
	}

	static boolean hasPrivateContainer(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		return be instanceof Container && be.getAttached(VillagerPlus.OWNER) != null;
	}

	/** Abschluss: Grube auffuellen, Oberflaeche der Umgebung uebernehmen, Registrierung austragen. */
	private static void finish(Ctx c, VillageRef ref, Built b, List<Step> steps) {
		ServerLevel level = c.level();
		restoreTerrain(level, ref, b, steps, c.v());
		Builder.forget(ref, b);
		VillageData d = ref.data();
		if (b.type().equals(Blueprints.HOUSE)) {
			d.houses = Math.max(0, d.houses - 1);
		} else if (b.type().equals(Blueprints.SCHOOL)) {
			d.extra.school = java.util.Optional.empty();
		} else if (Blueprints.special(b.type())) {
			Specials.remove(ref, b.type());
		}
		d.nextBuild = c.now() + 600;
		ref.dirty();
		clearSite(level, b.origin());
		Journal.add(level, ref, "Bauen", c.mem().name, label(b.type()) + " abgerissen, Gelände renaturiert", 0);
		JobUtil.tellNearbyPlayers(level, c.v(), 40, net.minecraft.network.chat.Component.literal("Abriss fertig: " + label(b.type()) + " – Material liegt in der Dorfkiste."));
	}

	/** Füllt den alten Baukörper bis zur früheren Geländeebene und stellt eine natürliche Deckschicht her. */
	static void restoreTerrain(ServerLevel level, VillageRef ref, Built b, List<Step> steps,
			net.minecraft.world.entity.npc.villager.Villager breaker) {
		if (steps.isEmpty()) return;
		var box = Furnisher.boxOf(steps);
		long width = (long)box.maxX() - box.minX() + 1L;
		long depth = (long)box.maxZ() - box.minZ() + 1L;
		if (width <= 0 || depth <= 0 || width > MAX_RESTORATION_SIDE || depth > MAX_RESTORATION_SIDE
			|| width * depth > MAX_RESTORATION_COLUMNS) return;
		int floorY = b.origin().getY() - 1;
		List<BoundingBox> nearbyHomes = nearbyHomeFootprints(level, ref, b);
		List<BlockPos> nearbyBeds = nearbyHomePois(level, box);
		Map<Long, BlockState> surfaceCache = new HashMap<>();
		for (int x = box.minX(); x <= box.maxX(); x++) {
			for (int z = box.minZ(); z <= box.maxZ(); z++) {
				BlockPos top = new BlockPos(x, floorY, z);
				if (!level.hasChunkAt(top) || protectedLandColumn(level, ref, box, nearbyHomes, nearbyBeds, x, z, floorY)) continue;
				BlockPos raisedStep = top.above();
				BlockState raised = level.getBlockState(raisedStep);
				if (level.hasChunkAt(raisedStep) && level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1 == raisedStep.getY()
					&& VillageStyleNatural(raised) && raised.isFaceSturdy(level, raisedStep, net.minecraft.core.Direction.UP)
					&& raised.getFluidState().isEmpty() && level.getBlockEntity(raisedStep) == null
					&& !raised.is(Blocks.BEDROCK) && !raised.is(Blocks.ICE) && !raised.is(Blocks.PACKED_ICE)) {
					if (!removeAndCollect(level, ref, raisedStep, raised, breaker)) continue;
				}

				BlockState surface = surfaceForColumn(level, box, x, z, floorY, surfaceCache);
				int fillBottom = Math.max(level.getMinY() + 1, floorY - MAX_RESTORATION_DEPTH);
				int supportY = floorY - 1;
				boolean safeColumn = true;
				boolean foundSupport = false;
				for (; supportY >= fillBottom; supportY--) {
					BlockPos p = new BlockPos(x, supportY, z);
					if (!level.hasChunkAt(p)) { safeColumn = false; break; }
					BlockState cur = level.getBlockState(p);
					if (!cur.getFluidState().isEmpty() || level.getBlockEntity(p) != null) { safeColumn = false; break; }
					if (!cur.isAir() && !cur.canBeReplaced()) {
						foundSupport = cur.isFaceSturdy(level, p, net.minecraft.core.Direction.UP);
						break;
					}
				}
				if (safeColumn && !foundSupport && supportY < fillBottom) {
					BlockPos base = new BlockPos(x, fillBottom - 1, z);
					if (level.hasChunkAt(base)) {
						BlockState baseState = level.getBlockState(base);
						foundSupport = baseState.getFluidState().isEmpty() && level.getBlockEntity(base) == null
							&& baseState.isFaceSturdy(level, base, net.minecraft.core.Direction.UP);
						supportY = fillBottom - 1;
					}
				}
				if (!safeColumn || !foundSupport) continue;
				for (int y = supportY + 1; y < floorY; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState cur = level.getBlockState(p);
					if (cur.isAir() || cur.canBeReplaced()) level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
				}
				BlockState cur = level.getBlockState(top);
				if (level.getBlockEntity(top) == null && cur.getFluidState().isEmpty()
					&& !protectedRoadSurface(cur) && (cur.isAir() || cur.canBeReplaced())) {
					level.setBlock(top, surface, 2);
				}
			}
		}
	}

	private static List<BoundingBox> nearbyHomeFootprints(ServerLevel level, VillageRef ref, Built target) {
		List<BoundingBox> boxes = new ArrayList<>();
		for (Built home : Builder.builtOf(ref)) {
			if (!home.type().equals(Blueprints.HOUSE) || home.origin().equals(target.origin())) continue;
			List<Step> homeSteps = Builder.stepsOfBuilt(level, ref, home, null);
			if (!homeSteps.isEmpty()) boxes.add(Furnisher.boxOf(homeSteps));
		}
		return boxes;
	}

	private static List<BlockPos> nearbyHomePois(ServerLevel level, BoundingBox box) {
		int centerX = box.minX() + (box.maxX() - box.minX()) / 2;
		int centerZ = box.minZ() + (box.maxZ() - box.minZ()) / 2;
		int radius = Math.min(64, Math.max(box.maxX() - box.minX(), box.maxZ() - box.minZ()) + 8);
		return level.getPoiManager().getInRange(h -> h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME),
			new BlockPos(centerX, box.minY(), centerZ), radius,
			net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
			.map(poi -> poi.getPos().immutable()).filter(pos -> !insideHorizontal(box, pos.getX(), pos.getZ())).toList();
	}

	private static boolean protectedLandColumn(ServerLevel level, VillageRef ref, BoundingBox box,
			List<BoundingBox> nearbyHomes, List<BlockPos> nearbyBeds, int x, int z, int floorY) {
		for (ZonePolicy.Zone zone : ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of())) {
			if (x >= zone.minX() && x <= zone.maxX() && z >= zone.minZ() && z <= zone.maxZ()) return true;
		}
		BlockPos ground = new BlockPos(x, floorY, z);
		if (Roads.trustedVillagePathCell(level, ref, ground) || Roads.isProtectedLaneCell(level, ground)
			|| protectedRoadSurface(level.getBlockState(ground))) return true;
		for (BoundingBox home : nearbyHomes) {
			if (x >= home.minX() - 1 && x <= home.maxX() + 1 && z >= home.minZ() - 1 && z <= home.maxZ() + 1) return true;
		}
		for (BlockPos bed : nearbyBeds) {
			if (Math.abs(bed.getX() - x) <= 1 && Math.abs(bed.getZ() - z) <= 1) return true;
		}
		return false;
	}

	private static boolean protectedRoadSurface(BlockState state) {
		return state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL) || state.is(Blocks.COBBLESTONE)
			|| state.is(Blocks.SANDSTONE) || state.is(Blocks.SMOOTH_SANDSTONE) || state.is(Blocks.SPRUCE_PLANKS);
	}

	private static boolean insideHorizontal(BoundingBox box, int x, int z) {
		return x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ();
	}

	/** Uses nearby natural ground for each restored column, so a mixed or biome-edge site is not painted one color. */
	private static BlockState surfaceForColumn(ServerLevel level, BoundingBox box, int x, int z, int floorY,
			Map<Long, BlockState> surfaceCache) {
		BlockPos top = new BlockPos(x, floorY, z);
		BlockState current = level.getBlockState(top);
		if (VillageStyleNatural(current)) return current;
		for (int radius = 1; radius <= 8; radius++) {
			BlockState nearest = null;
			long nearestDistance = Long.MAX_VALUE;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					int sampleX = x + dx, sampleZ = z + dz;
					if (inside(box, sampleX, sampleZ)) continue;
					BlockState candidate = sampledSurface(level, sampleX, sampleZ, floorY, surfaceCache);
					if (candidate == null) continue;
					long distance = (long) dx * dx + (long) dz * dz;
					if (distance < nearestDistance) {
						nearest = candidate;
						nearestDistance = distance;
					}
				}
			}
			if (nearest != null) return nearest;
		}
		return Blocks.GRASS_BLOCK.defaultBlockState();
	}

	private static BlockState sampledSurface(ServerLevel level, int x, int z, int floorY, Map<Long, BlockState> cache) {
		long key = BlockPos.asLong(x, 0, z);
		if (cache.containsKey(key)) return cache.get(key);
		BlockState surface = null;
		BlockPos column = new BlockPos(x, floorY, z);
		if (level.hasChunkAt(column)) {
			int sampleY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
			if (Math.abs(sampleY - floorY) <= 8) {
				BlockState candidate = level.getBlockState(new BlockPos(x, sampleY, z));
				if (VillageStyleNatural(candidate) && candidate.getFluidState().isEmpty()) surface = candidate;
			}
		}
		cache.put(key, surface);
		return surface;
	}

	private static boolean inside(BoundingBox box, int x, int z) {
		return x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ();
	}

	private static boolean inside(BoundingBox box, BlockPos pos) {
		return pos.getX() >= box.minX() && pos.getX() <= box.maxX()
			&& pos.getY() >= box.minY() && pos.getY() <= box.maxY()
			&& pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ();
	}

	private static boolean VillageStyleNatural(BlockState s) {
		return VillageStyle.natural(s);
	}
}
