package de.villagerplus.build;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.job.Anim;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Persistent opt-in marker and cleanup record for the phased construction flow. */
public record ConstructionSite(BlockPos origin, List<TemporaryBlock> temporary, List<Integer> phaseEnds, boolean prepared, List<String> paletteIds) {
	public static final Codec<TemporaryBlock> TEMP_CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.fieldOf("pos").forGetter(TemporaryBlock::pos),
		Codec.BOOL.fieldOf("workbench").forGetter(TemporaryBlock::workbench)
	).apply(i, TemporaryBlock::new));
	public static final Codec<ConstructionSite> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.fieldOf("origin").forGetter(ConstructionSite::origin),
		TEMP_CODEC.listOf().optionalFieldOf("temporary", List.of()).forGetter(ConstructionSite::temporary),
		Codec.INT.listOf().optionalFieldOf("phase_ends", List.of()).forGetter(ConstructionSite::phaseEnds),
		Codec.BOOL.optionalFieldOf("prepared", false).forGetter(ConstructionSite::prepared),
		Codec.STRING.listOf().optionalFieldOf("palette", List.of()).forGetter(ConstructionSite::paletteIds)
	).apply(i, ConstructionSite::new));

	public record TemporaryBlock(BlockPos pos, boolean workbench) {}

	public ConstructionSite {
		temporary = List.copyOf(temporary);
		phaseEnds = List.copyOf(phaseEnds);
		paletteIds = List.copyOf(paletteIds);
	}

	public ConstructionSite(BlockPos origin, List<TemporaryBlock> temporary) { this(origin, temporary, List.of(), false, List.of()); }
	public ConstructionSite(BlockPos origin, List<TemporaryBlock> temporary, List<Integer> phaseEnds) { this(origin, temporary, phaseEnds, false, List.of()); }
	public ConstructionSite(BlockPos origin, List<TemporaryBlock> temporary, List<Integer> phaseEnds, boolean prepared) { this(origin, temporary, phaseEnds, prepared, List.of()); }

	/** Reorder an Architect plan into semantic phases, retaining deterministic order within each phase. */
	public static List<Step> order(List<Step> input, BlockPos origin, Architect.Design design, Palette palette) {
		List<List<Step>> phases = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
		Map<BlockPos, Step> stepsByPosition = indexByPosition(input);
		for (Step step : input) {
			phases.get(phaseOf(step, origin, design, palette, stepsByPosition)).add(step);
		}
		List<Step> result = new ArrayList<>(input.size());
		phases.forEach(result::addAll);
		return List.copyOf(result);
	}

	/** Preserve the historic cursor order unless this persisted plan explicitly opted into phased construction. */
	public static List<Step> forPlan(List<Step> input, BlockPos origin, Architect.Design design, Palette palette, ConstructionSite site) {
		return site != null && site.origin().equals(origin) && !site.phaseEnds().isEmpty() ? order(input, origin, design, palette) : input;
	}

	public static List<Integer> phaseEnds(List<Step> ordered, BlockPos origin, Architect.Design design, Palette palette) {
		int[] counts = new int[5];
		Map<BlockPos, Step> stepsByPosition = indexByPosition(ordered);
		for (Step step : ordered) counts[phaseOf(step, origin, design, palette, stepsByPosition)]++;
		List<Integer> ends = new ArrayList<>(5);
		int sum = 0;
		for (int count : counts) { sum += count; ends.add(sum); }
		return List.copyOf(ends);
	}

	public static int phaseOf(Step step, BlockPos origin, Architect.Design design, Palette palette, Map<BlockPos, Step> stepsByPosition) {
		if (isInteriorBedPart(step, stepsByPosition)) return 4;
		int y = step.pos().getY();
		int wallTop = origin.getY() + design.wallH();
		return y < origin.getY() ? 0 : step.linkedTo() != null ? 2
			: !step.free() && y < wallTop && palette.frame() != null && step.item() == palette.frame().asItem() ? 1
			: !step.free() && y < wallTop ? 2 : !step.free() ? 3 : 4;
	}

	private static boolean isInteriorBedPart(Step step, Map<BlockPos, Step> stepsByPosition) {
		if (step.item() == null && ItemTags.BEDS.equals(step.tag())) return true;
		if (step.linkedTo() == null) return false;
		Step anchor = stepsByPosition.get(step.linkedTo());
		return anchor != null && anchor.item() == null && ItemTags.BEDS.equals(anchor.tag());
	}

	private static Map<BlockPos, Step> indexByPosition(List<Step> steps) {
		Map<BlockPos, Step> result = new LinkedHashMap<>();
		for (Step step : steps) result.put(step.pos(), step);
		return result;
	}

	/** Place free temporary scenery in air only, then persist every position for restart-safe cleanup. */
	public static ConstructionSite prepare(ServerLevel level, ConstructionSite site, List<Step> steps, BlockPos origin, Direction facing, Container materials) {
		return prepare(level, site, steps, origin, facing, materials, null);
	}

	/** Runs the paid worksite setup with its active builder so staged blocks show the same work feedback as the plan. */
	public static ConstructionSite prepare(ServerLevel level, ConstructionSite site, List<Step> steps, BlockPos origin,
			Direction facing, Container materials, Ctx worker) {
		if (site.prepared()) return site;
		List<Step> footprint = steps.stream().filter(s -> !s.free() && s.pos().getY() >= origin.getY() - 1 && s.pos().getY() <= origin.getY() + 8).toList();
		if (footprint.isEmpty()) return new ConstructionSite(site.origin(), site.temporary(), site.phaseEnds(), true, site.paletteIds());
		int minX = footprint.stream().mapToInt(s -> s.pos().getX()).min().orElse(origin.getX()) - 1;
		int maxX = footprint.stream().mapToInt(s -> s.pos().getX()).max().orElse(origin.getX()) + 1;
		int minZ = footprint.stream().mapToInt(s -> s.pos().getZ()).min().orElse(origin.getZ()) - 1;
		int maxZ = footprint.stream().mapToInt(s -> s.pos().getZ()).max().orElse(origin.getZ()) + 1;
		List<TemporaryBlock> placed = new ArrayList<>();
		int frontExtent = footprint.stream().mapToInt(s -> (s.pos().getX() - origin.getX()) * facing.getStepX()
			+ (s.pos().getZ() - origin.getZ()) * facing.getStepZ()).max().orElse(1);
		BlockPos preferredBench = origin.relative(facing, frontExtent + 2);
		List<TemporaryBlock> targets = new ArrayList<>();
		for (int[] corner : new int[][] {{minX,minZ},{minX,maxZ},{maxX,minZ},{maxX,maxZ}}) {
			for (int dy = 0; dy < 2; dy++) targets.add(new TemporaryBlock(new BlockPos(corner[0], origin.getY() + dy, corner[1]), false));
		}

		// Keep still-owned temporary blocks across a retry/restart; forget only blocks that no longer match.
		for (TemporaryBlock previous : site.temporary()) {
			if (!level.hasChunkAt(previous.pos()) || ownsTemporary(level, previous)) placed.add(previous);
		}
		BlockPos bench = findWorkbenchPosition(level, preferredBench, facing, steps, placed, targets);
		if (bench != null) targets.add(new TemporaryBlock(bench, true));
		for (TemporaryBlock target : targets) {
			if (containsTemporary(placed, target)) continue;
			place(level, materials, target.pos(), target.workbench(), placed, worker);
		}
		ConstructionSite staged = new ConstructionSite(site.origin(), List.copyOf(placed), site.phaseEnds(), false, site.paletteIds());
		boolean ready = bench != null && missingSetupMaterials(level, staged, steps, origin, facing, materials).isEmpty();
		return new ConstructionSite(site.origin(), staged.temporary(), site.phaseEnds(), ready, site.paletteIds());
	}

	/** Remaining real inventory request needed to finish an interrupted scaffold/workbench preparation. */
	public static Map<Item, Integer> missingSetupMaterials(ServerLevel level, ConstructionSite site, List<Step> steps,
			BlockPos origin, Direction facing, Container materials) {
		if (site == null || site.prepared()) return Map.of();
		List<Step> footprint = steps.stream().filter(s -> !s.free() && s.pos().getY() >= origin.getY() - 1 && s.pos().getY() <= origin.getY() + 8).toList();
		if (footprint.isEmpty()) return Map.of();
		int minX = footprint.stream().mapToInt(s -> s.pos().getX()).min().orElse(origin.getX()) - 1;
		int maxX = footprint.stream().mapToInt(s -> s.pos().getX()).max().orElse(origin.getX()) + 1;
		int minZ = footprint.stream().mapToInt(s -> s.pos().getZ()).min().orElse(origin.getZ()) - 1;
		int maxZ = footprint.stream().mapToInt(s -> s.pos().getZ()).max().orElse(origin.getZ()) + 1;
		int frontExtent = footprint.stream().mapToInt(s -> (s.pos().getX() - origin.getX()) * facing.getStepX()
			+ (s.pos().getZ() - origin.getZ()) * facing.getStepZ()).max().orElse(1);
		BlockPos preferredBench = origin.relative(facing, frontExtent + 2);
		List<TemporaryBlock> targets = new ArrayList<>();
		for (int[] corner : new int[][] {{minX,minZ},{minX,maxZ},{maxX,minZ},{maxX,maxZ}}) {
			for (int dy = 0; dy < 2; dy++) targets.add(new TemporaryBlock(new BlockPos(corner[0], origin.getY() + dy, corner[1]), false));
		}
		BlockPos bench = findWorkbenchPosition(level, preferredBench, facing, steps, site.temporary(), targets);
		if (bench != null) targets.add(new TemporaryBlock(bench, true));

		int scaffoldNeed = 0;
		int workbenchNeed = 0;
		for (TemporaryBlock target : targets) {
			if (containsTemporary(site.temporary(), target) || !canPrepare(level, target, targets)) continue;
			if (target.workbench()) workbenchNeed++; else scaffoldNeed++;
		}
		Map<Item, Integer> missing = new LinkedHashMap<>();
		addMissing(missing, Items.SCAFFOLDING, scaffoldNeed, count(materials, Items.SCAFFOLDING));
		addMissing(missing, Items.CRAFTING_TABLE, workbenchNeed, count(materials, Items.CRAFTING_TABLE));
		return Collections.unmodifiableMap(missing);
	}

	/** Move the workbench a few blocks around an obstruction without replacing terrain or a planned building block. */
	private static BlockPos findWorkbenchPosition(ServerLevel level, BlockPos preferred, Direction facing,
			List<Step> steps, List<TemporaryBlock> existing, List<TemporaryBlock> scaffoldTargets) {
		for (TemporaryBlock temp : existing) {
			if (temp.workbench() && (!level.hasChunkAt(temp.pos()) || ownsTemporary(level, temp))) return temp.pos();
		}
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(preferred);
		Direction clockwise = facing.getClockWise();
		Direction counterClockwise = facing.getCounterClockWise();
		for (int radius = 1; radius <= 3; radius++) {
			candidates.add(preferred.relative(facing, radius));
			candidates.add(preferred.relative(clockwise, radius));
			candidates.add(preferred.relative(counterClockwise, radius));
			for (int side = 1; side <= radius; side++) {
				candidates.add(preferred.relative(facing, radius).relative(clockwise, side));
				candidates.add(preferred.relative(facing, radius).relative(counterClockwise, side));
			}
		}
		for (BlockPos candidate : candidates) {
			if (overlapsPlannedBlock(candidate, steps) || scaffoldTargets.stream().anyMatch(temp -> temp.pos().equals(candidate))) continue;
			if (canPlace(level, candidate, true)) return candidate;
		}
		return null;
	}

	private static boolean overlapsPlannedBlock(BlockPos candidate, List<Step> steps) {
		return steps.stream().anyMatch(step -> step.pos().equals(candidate) || step.pos().equals(candidate.above()));
	}

	private static void addMissing(Map<Item, Integer> missing, Item item, int required, int available) {
		int count = Math.max(0, required - available);
		if (count > 0) missing.put(item, count);
	}

	private static int count(Container box, Item item) {
		if (box == null) return 0;
		int count = 0;
		for (int slot = 0; slot < box.getContainerSize(); slot++) {
			ItemStack stack = box.getItem(slot);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	private static boolean containsTemporary(List<TemporaryBlock> blocks, TemporaryBlock target) {
		return blocks.stream().anyMatch(existing -> existing.pos().equals(target.pos()) && existing.workbench() == target.workbench());
	}

	private static boolean ownsTemporary(ServerLevel level, TemporaryBlock temp) {
		return level.getBlockState(temp.pos()).is(temp.workbench() ? Blocks.CRAFTING_TABLE : Blocks.SCAFFOLDING);
	}

	private static boolean place(ServerLevel level, Container materials, BlockPos pos, boolean workbench, List<TemporaryBlock> placed, Ctx worker) {
		if (!canPlace(level, pos, workbench)) return false;
		BlockState state = workbench ? Blocks.CRAFTING_TABLE.defaultBlockState() : Blocks.SCAFFOLDING.defaultBlockState()
			.setValue(ScaffoldingBlock.DISTANCE, 0);
		Item item = workbench ? Items.CRAFTING_TABLE : Items.SCAFFOLDING;
		if (materials == null || takeOne(materials, item) == null) return false;
		level.setBlock(pos, state, 3);
		if (!level.getBlockState(pos).is(state.getBlock())) {
			refund(level, materials, pos, item);
			return false;
		}
		Anim.blockPlace(level, pos, state, 0.8F, 1.0F);
		if (worker != null) {
			Anim.hold(worker, item);
			Anim.apply(worker.v(), worker.st(), worker.now());
			JobUtil.swing(worker.v());
		}
		placed.add(new TemporaryBlock(pos, workbench));
		return true;
	}

	private static boolean canPlace(ServerLevel level, BlockPos pos, boolean workbench) {
		if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir() || occupied(level, pos)) return false;
		BlockState state = workbench ? Blocks.CRAFTING_TABLE.defaultBlockState() : Blocks.SCAFFOLDING.defaultBlockState()
			.setValue(ScaffoldingBlock.DISTANCE, 0);
		return state.canSurvive(level, pos);
	}

	/** Upper scaffold cells become placeable after their planned lower cell is paid for and erected. */
	private static boolean canPrepare(ServerLevel level, TemporaryBlock target, List<TemporaryBlock> targets) {
		if (canPlace(level, target.pos(), target.workbench())) return true;
		if (target.workbench() || !level.hasChunkAt(target.pos()) || !level.getBlockState(target.pos()).isAir()
			|| occupied(level, target.pos())) return false;
		BlockPos below = target.pos().below();
		boolean stagedSupport = targets.stream().anyMatch(candidate -> !candidate.workbench() && candidate.pos().equals(below));
		return stagedSupport && canPlace(level, below, false);
	}

	private static ItemStack takeOne(Container box, Item item) {
		for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack stack = box.getItem(i);
			if (!stack.isEmpty() && stack.is(item)) {
				ItemStack taken = box.removeItem(i, 1);
				box.setChanged();
				return taken;
			}
		}
		return null;
	}

	private static void refund(ServerLevel level, Container box, BlockPos pos, Item item) {
		ItemStack stack = new ItemStack(item);
			if (box != null) stack = HopperBlockEntity.addItem(null, box, stack, null);
		if (!stack.isEmpty()) level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, stack));
	}

	private static void refund(ServerLevel level, de.villagerplus.village.VillageRef ref, BlockPos pos, Item item) {
		ItemStack rest = new ItemStack(item);
		Container home = ref.chest(level);
		if (home != null) rest = de.villagerplus.village.StockPolicy.insertInto(level, ref,
			de.villagerplus.build.Blueprints.STORAGE, home, rest);
		for (Container box : de.villagerplus.village.Lager.containers(level, ref.data())) {
			if (rest.isEmpty()) break;
			rest = de.villagerplus.village.StockPolicy.insertInto(level, ref, de.villagerplus.build.Blueprints.STORAGE, box, rest);
		}
		if (!rest.isEmpty()) level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, rest));
	}

	public static ConstructionSite cleanup(ServerLevel level, ConstructionSite site, Container materials) {
		return cleanup(level, site, materials, null);
	}

	public static ConstructionSite cleanup(ServerLevel level, ConstructionSite site, Container materials, de.villagerplus.village.VillageRef ref) {
		List<TemporaryBlock> remaining = new ArrayList<>();
		List<TemporaryBlock> removalOrder = new ArrayList<>(site.temporary());
		// Dismantle scaffold from the top down so upper pieces cannot collapse and drop as an unrelated block update.
		removalOrder.sort((a, b) -> {
			if (a.workbench() != b.workbench()) return Boolean.compare(a.workbench(), b.workbench());
			return Integer.compare(b.pos().getY(), a.pos().getY());
		});
		for (TemporaryBlock temp : removalOrder) {
			if (!level.hasChunkAt(temp.pos())) { remaining.add(temp); continue; }
			BlockState current = level.getBlockState(temp.pos());
			if (current.is(temp.workbench() ? Blocks.CRAFTING_TABLE : Blocks.SCAFFOLDING)) {
				level.setBlock(temp.pos(), Blocks.AIR.defaultBlockState(), 3);
				if (!level.getBlockState(temp.pos()).is(temp.workbench() ? Blocks.CRAFTING_TABLE : Blocks.SCAFFOLDING)) {
					if (ref == null) refund(level, materials, temp.pos(), temp.workbench() ? Items.CRAFTING_TABLE : Items.SCAFFOLDING);
					else refund(level, ref, temp.pos(), temp.workbench() ? Items.CRAFTING_TABLE : Items.SCAFFOLDING);
				} else {
					// Keep ownership metadata if a protection hook or another block update rejected removal.
					remaining.add(temp);
				}
			}
		}
		return new ConstructionSite(site.origin(), List.copyOf(remaining), site.phaseEnds(), site.prepared(), site.paletteIds());
	}

	private static boolean occupied(ServerLevel level, BlockPos pos) {
		return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> true).isEmpty();
	}

	public int phaseFor(int step, int total) {
		if (total <= 0 || phaseEnds().isEmpty()) return 0;
		int cursor = Math.max(0, Math.min(step, total));
		for (int i = 0; i < phaseEnds().size(); i++) if (cursor < phaseEnds().get(i)) return i;
		return 4;
	}

	public int phaseStart(int phase) {
		if (phaseEnds().isEmpty()) return 0;
		int index = Math.max(0, Math.min(phase, phaseEnds().size() - 1));
		return index == 0 ? 0 : phaseEnds().get(index - 1);
	}

	public int phaseEnd(int phase) {
		if (phaseEnds().isEmpty()) return 0;
		int index = Math.max(0, Math.min(phase, phaseEnds().size() - 1));
		return phaseEnds().get(index);
	}

	public int phaseCompleted(int step, int phase) {
		return Math.max(0, Math.min(step, phaseEnd(phase)) - phaseStart(phase));
	}

	public int phaseTotal(int phase) {
		return Math.max(0, phaseEnd(phase) - phaseStart(phase));
	}

	public int phasePercent(int step, int phase) {
		int total = phaseTotal(phase);
		return total == 0 ? 100 : phaseCompleted(step, phase) * 100 / total;
	}

	public int scaffoldCount() {
		return (int) temporary.stream().filter(temp -> !temp.workbench()).count();
	}

	public boolean hasWorkbench() {
		return temporary.stream().anyMatch(TemporaryBlock::workbench);
	}

	public static String phaseName(int phase) {
		return switch (phase) {
			case 0 -> "Fundament";
			case 1 -> "Tragwerk";
			case 2 -> "Wände, Fenster & Türen";
			case 3 -> "Dach";
			default -> "Innenausbau & Gerüstabbau";
		};
	}
}
