package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Animationen: Dorfbewohner zeigen das passende Werkzeug in der Hand (Hacke, Spitzhacke, Axt, Schere, Angel, Buch,
 * Essen ...), schwingen den Arm und erzeugen Partikel und Geraeusche. Das Werkzeug wird von {@code JobManager}
 * automatisch wieder weggesteckt.
 */
public final class Anim {
	private static final long HOLD_TICKS = 50;
	private static final int HIT_TICKS = 6;
	private static final long BREAK_IDLE_TICKS = 100;
	private static final String HAND_VISUAL = "vp_anim_hand_visual";
	private static final String HAND_VISUAL_OWNER = "vp_anim_hand_owner";
	private static final java.util.Map<Villager, Breaking> BREAKING = new java.util.IdentityHashMap<>();
	private static final java.util.Map<Villager, HeldHand> HELD_HANDS = new java.util.IdentityHashMap<>();
	private static final java.util.Map<Villager, BreakDiagnostic> BREAK_DIAGNOSTICS = new java.util.IdentityHashMap<>();
	private static boolean registered;

	private static final class HeldHand {
		ItemStack previous;
		ItemStack shown;

		HeldHand(ItemStack previous, ItemStack shown) {
			this.previous = previous.copy();
			this.shown = shown.copy();
		}
	}

	private static final class BreakDiagnostic {
		int calls;
		int starts;
		String last = "not called";
		String lastReset = "none";
		final java.util.Map<String, Integer> resets = new java.util.LinkedHashMap<>();
	}

	private static final class Breaking {
		final Ctx context;
		final BlockPos pos;
		final BlockState state;
		final Item tool;
		final String activity;
		int stage;
		long advancedAt = Long.MIN_VALUE;
		long touchedAt;
		long swungAt;

		Breaking(Ctx c, BlockPos pos, BlockState state, Item tool, int resumeStage) {
			this.context = c;
			this.pos = pos.immutable();
			this.state = state;
			this.tool = tool;
			this.activity = c.st().activity;
			this.stage = Math.max(-1, Math.min(9, resumeStage));
			this.touchedAt = c.now();
			this.swungAt = c.now();
			this.advancedAt = this.stage < 0 ? Long.MIN_VALUE : c.now();
		}
	}

	private Anim() {
	}

	/** Zeigt {@code item} fuer kurze Zeit in der Hand (wird bei jeder Aktion verlaengert). */
	public static void hold(Ctx c, Item item) {
		c.st().tool = item;
		c.st().toolUntil = c.now() + HOLD_TICKS;
	}

	/** Returns the server-owned visual role; VillagerData is also synchronized to client render state. */
	public static E04Role role(Villager villager) {
		return E04Role.fromProfession(villager.getVillagerData().profession());
	}

	/** One paced work stroke: broadcasts every crack stage 0–9 before allowing removal on a later call. */
	public static boolean breakStep(Ctx c, BlockPos pos, BlockState state, Item tool) {
		return breakStep(c, pos, state, tool, -1);
	}

	/** Restores a persisted crack stage only when starting a fresh breaker for this exact target. */
	public static boolean breakStep(Ctx c, BlockPos pos, BlockState state, Item tool, int resumeStage) {
		registerBreaking();
		trackCall(c.v(), c.now(), resumeStage);
		Breaking work = BREAKING.get(c.v());
		String mismatch = mismatch(work, c, pos, state, tool);
		if (work != null && mismatch != null) {
			cancelBreaking(c.v(), "reset=" + mismatch);
			work = null;
		}
		if (state.isAir() || !c.level().hasChunkAt(pos) || c.level().getBlockState(pos) != state) {
			cancelBreaking(c.v(), "target-invalid pos=" + pos.toShortString());
			return false;
		}
		if (work == null) {
			work = new Breaking(c, pos, state, tool, resumeStage);
			BREAKING.put(c.v(), work);
			noteStart(c.v());
			diagnostic(c.v(), "start stage=" + work.stage + " resume=" + resumeStage + " now=" + c.now());
		}
		work.touchedAt = c.now();
		hold(c, tool);
		// Equipment must reach the client before the first swing packet.
		apply(c.v(), c.st(), c.now());
		if (work.stage >= 0 && c.now() - work.advancedAt < HIT_TICKS) {
			diagnostic(c.v(), "throttle stage=" + work.stage + " elapsed=" + (c.now() - work.advancedAt) + " now=" + c.now());
			return false;
		}
		if (work.stage == 9) {
			cancelBreaking(c.v(), "complete stage=9");
			return true;
		}
		work.stage++;
		work.advancedAt = c.now();
		stroke(work, c.now());
		c.level().destroyBlockProgress(c.v().getId(), pos, work.stage);
		diagnostic(c.v(), "advance stage=" + work.stage + " now=" + c.now());
		return false;
	}

	private static String mismatch(Breaking work, Ctx c, BlockPos pos, BlockState state, Item tool) {
		if (work == null) return null;
		if (work.context.level() != c.level()) return "level";
		if (!work.pos.equals(pos)) return "position old=" + work.pos.toShortString() + " new=" + pos.toShortString();
		if (work.state != state) return "state-identity";
		if (work.tool != tool) return "tool";
		if (!work.activity.equals(c.st().activity)) return "activity old=" + work.activity + " new=" + c.st().activity;
		if (c.now() - work.touchedAt > BREAK_IDLE_TICKS) return "idle age=" + (c.now() - work.touchedAt);
		return null;
	}

	/** Clears the packet under the original breaker id, including when a job switches targets. */
	public static void cancelBreaking(Villager v) {
		cancelBreaking(v, "cancelled");
	}

	private static void cancelBreaking(Villager v, String reason) {
		Breaking work = BREAKING.remove(v);
		if (work != null) {
			work.context.level().destroyBlockProgress(v.getId(), work.pos, -1);
			noteReset(v, reason);
			diagnostic(v, reason + " stage=" + work.stage + " touchedAt=" + work.touchedAt);
		}
	}

	public static int breakingStage(Villager v) {
		Breaking work = BREAKING.get(v);
		return work == null ? -1 : work.stage;
	}

	/** Last development-only break cadence/reason, for bounded integration-check diagnostics. */
	public static String breakingDiagnostic(Villager v) {
		if (!de.villagerplus.dev.G02SupplyTrace.enabled()) return "disabled";
		BreakDiagnostic diagnostic = BREAK_DIAGNOSTICS.get(v);
		return diagnostic == null ? "none" : "calls=" + diagnostic.calls + "; starts=" + diagnostic.starts
			+ "; resetCounts=" + diagnostic.resets + "; lastReset=" + diagnostic.lastReset + "; last=" + diagnostic.last;
	}

	private static void trackCall(Villager v, long now, int resumeStage) {
		if (!de.villagerplus.dev.G02SupplyTrace.enabled()) return;
		if (BREAK_DIAGNOSTICS.size() > 512) BREAK_DIAGNOSTICS.clear();
		BreakDiagnostic diagnostic = BREAK_DIAGNOSTICS.computeIfAbsent(v, ignored -> new BreakDiagnostic());
		diagnostic.calls++;
		diagnostic.last = "call now=" + now + " resume=" + resumeStage;
	}

	private static void diagnostic(Villager v, String detail) {
		if (!de.villagerplus.dev.G02SupplyTrace.enabled()) return;
		BreakDiagnostic diagnostic = BREAK_DIAGNOSTICS.computeIfAbsent(v, ignored -> new BreakDiagnostic());
		diagnostic.last = detail;
	}

	private static void noteStart(Villager v) {
		if (!de.villagerplus.dev.G02SupplyTrace.enabled()) return;
		BREAK_DIAGNOSTICS.computeIfAbsent(v, ignored -> new BreakDiagnostic()).starts++;
	}

	private static void noteReset(Villager v, String reason) {
		if (!de.villagerplus.dev.G02SupplyTrace.enabled()) return;
		BreakDiagnostic diagnostic = BREAK_DIAGNOSTICS.computeIfAbsent(v, ignored -> new BreakDiagnostic());
		String counterKey = reason.split(" ", 2)[0];
		diagnostic.resets.merge(counterKey, 1, Integer::sum);
		diagnostic.lastReset = reason;
	}

	private static void stroke(Breaking work, long now) {
		Villager v = work.context.v();
		v.getLookControl().setLookAt(work.pos.getX() + 0.5, work.pos.getY() + 0.5, work.pos.getZ() + 0.5);
		JobUtil.swing(v);
		work.swungAt = now;
		work.context.level().playSound(null, work.pos, work.state.getSoundType().getHitSound(),
			net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.9F);
	}

	// Runs independently of job scheduling: pauses, cancellation and unload must not leave ghost cracks.
	static void maintainBreaking(ServerLevel level) {
		long now = level.getGameTime();
		for (Breaking work : java.util.List.copyOf(BREAKING.values())) {
			if (work.context.level() != level) continue;
			Villager v = work.context.v();
			JobState st = work.context.st();
			Phase phase = Phase.of(level.getOverworldClockTime());
			if (v.isRemoved() || !v.isAlive() || v.isSleeping() || v.isTrading() || phase.routine || phase == Phase.NIGHT
				|| level.isThundering() || !level.hasChunkAt(work.pos) || level.getBlockState(work.pos) != work.state
				|| now - work.touchedAt > BREAK_IDLE_TICKS || st.cooldownUntil > now + BREAK_IDLE_TICKS
				|| !work.activity.equals(st.activity) || st.tool != work.tool) {
				cancelBreaking(v);
				continue;
			}
			st.toolUntil = now + HOLD_TICKS;
			apply(v, st, now);
			if (now - work.swungAt >= HIT_TICKS) stroke(work, now);
		}
	}

	private static void registerBreaking() {
		if (registered) return;
		registered = true;
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(Anim::maintainBreaking);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof Villager v) {
				cancelBreaking(v);
				restoreHeldHand(v);
				BREAK_DIAGNOSTICS.remove(v);
			}
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof Villager v) restoreHeldHand(v);
			return true;
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Villager v : java.util.List.copyOf(BREAKING.keySet())) cancelBreaking(v);
			for (Villager v : java.util.List.copyOf(HELD_HANDS.keySet())) restoreHeldHand(v);
			BREAK_DIAGNOSTICS.clear();
		});
	}

	/** Wird vom JobManager in jedem Takt aufgerufen: Hand mit dem gewuenschten Gegenstand abgleichen. */
	public static void apply(Villager v, JobState st, long now) {
		registerBreaking();
		ItemStack cargo = BuildHaul.visibleCargo(v);
		ItemStack desired = !cargo.isEmpty() ? cargo : st.tool != null && now < st.toolUntil
			? new ItemStack(st.tool) : carriedCaravanFreight(v);
		ItemStack current = v.getItemBySlot(EquipmentSlot.MAINHAND);
		if (!desired.isEmpty()) {
			ItemStack shown = visualCopy(v, desired);
			VillagerMemory memory = v.getAttachedOrCreate(VillagerPlus.MEMORY);
			HeldHand held = HELD_HANDS.get(v);
			if (held == null) {
				ItemStack previous = current.copy();
				if (memory.animHandPending && sameStack(current, memory.animHandShown.orElse(ItemStack.EMPTY))) {
					previous = memory.animHandPrevious.orElse(ItemStack.EMPTY).copy();
				}
				held = new HeldHand(previous, shown);
				HELD_HANDS.put(v, held);
			} else if (!sameStack(current, held.shown)) {
				// Another system changed equipment while the visual item was shown; preserve that newer hand.
				held.previous = current.copy();
				held.shown = shown;
			} else {
				held.shown = shown;
			}
			memory.animHandPending = true;
			memory.animHandPrevious = held.previous.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(held.previous.copy());
			memory.animHandShown = java.util.Optional.of(held.shown.copy());
			if (!sameStack(current, shown)) {
				v.setItemSlot(EquipmentSlot.MAINHAND, shown.copy());
			}
			st.holding = true;
		} else if (st.holding || HELD_HANDS.containsKey(v) || v.getAttachedOrCreate(VillagerPlus.MEMORY).animHandPending) {
			restoreHeldHand(v);
			st.holding = false;
			st.tool = null;
		}
	}

	private static void restoreHeldHand(Villager v) {
		HeldHand held = HELD_HANDS.remove(v);
		VillagerMemory memory = v.getAttachedOrCreate(VillagerPlus.MEMORY);
		ItemStack current = v.getItemBySlot(EquipmentSlot.MAINHAND);
		ItemStack shown = held == null ? memory.animHandShown.orElse(ItemStack.EMPTY) : held.shown;
		ItemStack previous = held == null ? memory.animHandPrevious.orElse(ItemStack.EMPTY) : held.previous;
		// Do not undo a real equipment change made by another system after our last display update.
		if ((held != null || memory.animHandPending) && sameStack(current, shown)) {
			v.setItemSlot(EquipmentSlot.MAINHAND, previous.copy());
		}
		memory.animHandPending = false;
		memory.animHandPrevious = java.util.Optional.empty();
		memory.animHandShown = java.util.Optional.empty();
	}

	private static boolean sameStack(ItemStack first, ItemStack second) {
		return first != null && second != null && ItemStack.isSameItemSameComponents(first, second)
			&& first.getCount() == second.getCount();
	}

	private static ItemStack visualCopy(Villager villager, ItemStack desired) {
		ItemStack display = desired.copy();
		CustomData custom = display.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = custom == null ? new CompoundTag() : custom.copyTag();
		tag.putBoolean(HAND_VISUAL, true);
		tag.putString(HAND_VISUAL_OWNER, villager.getUUID().toString());
		display.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return display;
	}

	static void forgetHeldHandRuntimeForCheck(Villager villager) {
		HELD_HANDS.remove(villager);
	}

	/** Shows one visual copy of the real cargo only while its tagged caravan donkey is leashed to this porter. */
	private static ItemStack carriedCaravanFreight(Villager porter) {
		if (!(porter.level() instanceof ServerLevel level)) return ItemStack.EMPTY;
		AABB search = porter.getBoundingBox().inflate(12.0);
		Donkey donkey = level.getEntitiesOfClass(Donkey.class, search, candidate -> candidate.isAlive()
			&& candidate.getLeashHolder() == porter
			&& candidate.entityTags().stream().anyMatch(tag -> tag.startsWith("vp_caravan_bell:")))
			.stream().min(java.util.Comparator.comparingDouble(candidate -> candidate.distanceToSqr(porter))).orElse(null);
		if (donkey == null) return ItemStack.EMPTY;
		for (int slot = 2; slot < 17; slot++) {
			var access = donkey.getSlot(500 + slot);
			if (access != null && !access.get().isEmpty()) return access.get().copyWithCount(1);
		}
		return ItemStack.EMPTY;
	}

	/** Brockelnde Block-Partikel + Geraeusch (wie beim Abbauen). */
	public static void blockBreak(ServerLevel level, BlockPos pos, BlockState state) {
		level.levelEvent(2001, pos, Block.getId(state));
	}

	/** Plays the placed block's sound and sends a bounded burst of its own particles to tracking clients. */
	public static void blockPlace(ServerLevel level, BlockPos pos, BlockState state, float volume, float pitch) {
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, volume, pitch);
		level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, state),
			pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0.05);
	}

	/** Essens-Partikel vor dem Gesicht. */
	public static void eatParticles(ServerLevel level, Villager v, Item food) {
		level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food), v.getX(), v.getY() + 1.5, v.getZ(), 6, 0.15, 0.1, 0.15, 0.05);
	}
}
