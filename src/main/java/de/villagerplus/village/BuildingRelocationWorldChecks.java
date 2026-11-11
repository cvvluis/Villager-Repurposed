package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Built;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Palette;
import de.villagerplus.build.Step;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Destructive only inside the development runner's remote plateau; restores all cells and village registrations afterwards. */
public final class BuildingRelocationWorldChecks {
	private BuildingRelocationWorldChecks() { }
	private record Cell(BlockState state, CompoundTag data) { }
	private static final int FLAGS = 2 | 16 | 32;

	public static int run(ServerLevel level, VillageRef ref) {
		int passed = 0;
		BlockPos origin = ref.bell().offset(-44, 0, 24), target = origin.east(26), blockedTarget = target.east(26);
		Map<BlockPos, Cell> oldWorld = new LinkedHashMap<>();
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-12, -5, -12), blockedTarget.offset(12, 18, 12))) {
			BlockPos pos = p.immutable(); level.getChunkAt(pos); oldWorld.put(pos, cell(level, pos));
		}
		List<Built> oldBuildings = ref.bellEntity().getAttached(VillagerPlus.BUILDINGS);
		List<VillageData.BuildProject> oldProjects = List.copyOf(ref.data().buildProjects);
		Map<String, BlockPos> oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		Optional<BlockPos> oldSchool = ref.data().extra.school, oldLager = ref.data().extra.lager, oldChest = ref.data().chest;
		List<BlockPos> oldMore = List.copyOf(ref.data().extra.lagerMore), oldChests = List.copyOf(ref.data().extra.lagerChests);
		Villager witness = EntityTypes.VILLAGER.spawn(level, origin.south(10), EntitySpawnReason.COMMAND);
		if (witness == null) throw new IllegalStateException("RELOCATION WORLD CHECK FAILED: no witness");
		witness.addTag("vp090_fixture"); witness.setNoAi(true);
		witness.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), ref.bell()));
		var witnessMemory = witness.getAttachedOrCreate(VillagerPlus.MEMORY);
		AABB area = new AABB(origin.getX() - 12, origin.getY() - 5, origin.getZ() - 12, blockedTarget.getX() + 13, blockedTarget.getY() + 19, blockedTarget.getZ() + 13);
		List<java.util.UUID> oldDrops = level.getEntitiesOfClass(ItemEntity.class, area).stream().map(ItemEntity::getUUID).toList();
		try {
			// Clear a controlled level destination and give both sites the same natural grass surface.
			for (BlockPos pos : oldWorld.keySet()) {
				level.removeBlockEntity(pos);
				level.setBlock(pos, pos.getY() == origin.getY() - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), FLAGS);
			}
			Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE,
				Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
			Built built = new Built("granary", origin, 0, 0, palette.ids());
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, List.of(built));
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, Map.of("granary", origin));
			ref.data().extra.lager = Optional.of(origin); ref.data().extra.school = Optional.empty();
			ref.data().extra.lagerMore.clear(); ref.data().extra.lagerMore.add(origin);
			ref.data().extra.lagerChests.clear(); ref.data().extra.lagerChests.add(origin);
			List<Step> steps = Builder.stepsOfBuilt(level, ref, built, witness);
			for (Step step : steps) {
				Block material = step.linkedTo() != null ? level.getBlockState(step.linkedTo()).getBlock()
					: step.item() != null ? Block.byItem(step.item()) : step.tag() != null ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:white_bed")) : Blocks.AIR;
				level.setBlock(step.pos(), step.make().apply(material), FLAGS);
			}
			// This chest is deliberately absent from Architect's steps: it represents Furnisher's later interior inventory.
			check(steps.stream().noneMatch(s -> s.pos().equals(origin)), "fixture chest is post-build interior furniture"); passed++;
			level.setBlock(origin, Blocks.CHEST.defaultBlockState(), FLAGS);
			Container chest = (Container) level.getBlockEntity(origin);
			// This coordinate is already registered as a public material store. Mark the
			// fixture private before inserting its test contents so the real stock-policy
			// hook does not correctly reject the unclassified emeralds or food.
			level.getBlockEntity(origin).setAttached(VillagerPlus.OWNER, witness.getUUID());
			chest.setItem(0, new ItemStack(Items.EMERALD, 13)); chest.setItem(4, new ItemStack(Items.BREAD, 7)); chest.setChanged();
			check(contents(level, origin, Items.EMERALD) == 13 && contents(level, origin, Items.BREAD) == 7,
				"private fixture retains seeded inventory after material-store insertion hooks"); passed++;
			witnessMemory.homeChest = Optional.of(origin);
			Set<Long> plannedCells = steps.stream().map(step -> step.pos().asLong()).collect(java.util.stream.Collectors.toSet());
			var structureBox = de.villagerplus.build.Furnisher.boxOf(steps);
			BlockPos publicChestPos = null;
			for (BlockPos candidate : BlockPos.betweenClosed(structureBox.minX(), structureBox.minY(), structureBox.minZ(),
				structureBox.maxX(), structureBox.maxY(), structureBox.maxZ())) {
				boolean interior = candidate.getX() > structureBox.minX() && candidate.getX() < structureBox.maxX()
					&& candidate.getZ() > structureBox.minZ() && candidate.getZ() < structureBox.maxZ();
				boolean floorCell = plannedCells.contains(candidate.below().asLong());
				boolean separateInventory = Math.abs(candidate.getX() - origin.getX()) + Math.abs(candidate.getZ() - origin.getZ()) > 1;
				if (candidate.getY() == origin.getY() && interior && floorCell && separateInventory
					&& !plannedCells.contains(candidate.asLong()) && level.getBlockState(candidate).isAir()
					&& level.getBlockEntity(candidate) == null) {
					publicChestPos = candidate.immutable(); break;
				}
			}
			if (publicChestPos == null) throw new IllegalStateException("RELOCATION WORLD CHECK FAILED: no interior cell for public chest");
			level.setBlock(publicChestPos, Blocks.CHEST.defaultBlockState(), FLAGS);
			Container publicChest = (Container) level.getBlockEntity(publicChestPos);
			publicChest.setItem(1, new ItemStack(Items.EMERALD, 4)); publicChest.setItem(2, new ItemStack(Items.BREAD, 5)); publicChest.setChanged();
			check(contents(level, publicChestPos, Items.EMERALD) == 4 && contents(level, publicChestPos, Items.BREAD) == 5,
				"unregistered public interior fixture retains seeded inventory"); passed++;
			Map<Block, Integer> materialBefore = materials(level, oldWorld.keySet());
			String result = BuildingRelocation.move(level, ref, origin, target);
			check(result.startsWith("Gebäude versetzt"), "registered building moves successfully onto natural level ground: " + result); passed++;
			int movedPrivateEmeralds = contents(level, target, Items.EMERALD), movedPrivateBread = contents(level, target, Items.BREAD);
			check(level.getBlockState(origin).isAir() && movedPrivateEmeralds == 13 && movedPrivateBread == 7,
				"interior inventory leaves the source and arrives exactly once (source=" + level.getBlockState(origin)
					+ ", destination emeralds=" + movedPrivateEmeralds + ", bread=" + movedPrivateBread + ")"); passed++;
			check(Ownership.isOwnedBy(level, target, witness.getUUID()) && witnessMemory.homeChest.equals(Optional.of(target)),
				"private chest ownership and resident chest memory follow a moved building"); passed++;
			BlockPos movedPublicChest = publicChestPos.offset(target.getX() - origin.getX(), 0, target.getZ() - origin.getZ());
			check(level.getBlockState(publicChestPos).isAir() && contents(level, movedPublicChest, Items.EMERALD) == 4
				&& contents(level, movedPublicChest, Items.BREAD) == 5 && !Ownership.isOwnedBy(level, movedPublicChest, witness.getUUID()),
				"unowned public inventory moves with its building, preserving exact contents and public status"); passed++;
			check(materialBefore.equals(materials(level, oldWorld.keySet())) && noNewDrops(level, area, oldDrops),
				"move conserves block materials and creates no loose inventory drops"); passed++;
			Built moved = Builder.builtOf(ref).get(0);
			check(moved.origin().equals(target) && moved.palette().equals(built.palette()) && Specials.get(ref, "granary").equals(target)
				&& ref.data().extra.lager.equals(Optional.of(target)) && ref.data().extra.lagerMore.equals(List.of(target))
				&& ref.data().extra.lagerChests.equals(List.of(target)), "building, depot, palette and chest registrations move together"); passed++;

			level.setBlock(blockedTarget, Blocks.OBSIDIAN.defaultBlockState(), FLAGS);
			Map<BlockPos, Cell> blockedBefore = capture(level, oldWorld.keySet());
			String rejected = BuildingRelocation.move(level, ref, target, blockedTarget);
			check(rejected.contains("nicht frei") && unchanged(level, blockedBefore) && Builder.builtOf(ref).equals(List.of(moved)),
				"blocked target preserves every source, target, inventory and registration cell"); passed++;
			level.setBlock(blockedTarget, Blocks.AIR.defaultBlockState(), FLAGS);

			BlockPos primarySite = blockedTarget.offset(30, 0, 30);
			ref.data().buildProjects.clear();
			ref.data().buildProjects.add(project("house", primarySite,
				new VillageData.PlanBounds(primarySite.getX() - 2, primarySite.getX() + 2, primarySite.getZ() - 2, primarySite.getZ() + 2)));
			ref.data().buildProjects.add(project("school", blockedTarget,
				new VillageData.PlanBounds(blockedTarget.getX() - 8, blockedTarget.getX() + 8, blockedTarget.getZ() - 8, blockedTarget.getZ() + 8)));
			Map<BlockPos, Cell> reservationBefore = capture(level, oldWorld.keySet());
			String reserved = BuildingRelocation.move(level, ref, target, blockedTarget);
			check(reserved.contains("Bauprojekt") && unchanged(level, reservationBefore)
				&& Builder.builtOf(ref).equals(List.of(moved)) && contents(level, target, Items.EMERALD) == 13,
				"relocation rejects a destination reserved by a non-primary active project without changing either site"); passed++;
			ref.data().buildProjects.clear();

			Map<BlockPos, Cell> rollbackBefore = capture(level, oldWorld.keySet());
			int[] writes = {0};
			String rolledBack = BuildingRelocation.move(level, ref, target, blockedTarget, (pos, state) -> {
				if (++writes[0] == 30) return false;
				return level.getBlockState(pos).equals(state) || level.setBlock(pos, state, FLAGS);
			});
			check(writes[0] == 30 && rolledBack.contains("zurückgerollt") && unchanged(level, rollbackBefore),
				"a rejected write after partial placement rolls back the whole source and destination"); passed++;
			check(Builder.builtOf(ref).equals(List.of(moved)) && Specials.get(ref, "granary").equals(target)
				&& contents(level, target, Items.EMERALD) == 13 && Ownership.isOwnedBy(level, target, witness.getUUID())
				&& witnessMemory.homeChest.equals(Optional.of(target)) && noNewDrops(level, area, oldDrops),
				"rollback preserves registration and inventory without item drops"); passed++;

			Map<Block, Integer> overlapBefore = materials(level, oldWorld.keySet());
			if (!level.getBlockState(target.east()).isAir()) {
				BlockState sweptObstruction = level.getBlockState(target.east());
				Map<BlockPos, Cell> flowerBefore = capture(level, oldWorld.keySet());
				String rejectedFlowerShift = BuildingRelocation.move(level, ref, target, target.east());
				check(rejectedFlowerShift.contains("Zielfläche") && unchanged(level, flowerBefore)
					&& contents(level, target, Items.EMERALD) == 13 && level.getBlockState(target.east()).equals(sweptObstruction),
					"an obstruction in the swept destination is preserved by refusing that direction: " + rejectedFlowerShift); passed++;
			}
		BlockPos overlapDestination = null;
		String overlap = "Keine freie Richtung für den überlappenden Versatz.";
		for (Direction direction : List.of(Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH)) {
			BlockPos candidate = target.relative(direction);
			overlap = BuildingRelocation.move(level, ref, target, candidate);
			if (overlap.startsWith("Gebäude versetzt")) { overlapDestination = candidate; break; }
		}
		check(overlapDestination != null, "overlapping one-block shift finds a clear direction without damaging obstructions: " + overlap); passed++;
		int emeralds = contents(level, overlapDestination, Items.EMERALD), bread = contents(level, overlapDestination, Items.BREAD);
		check(emeralds == 13 && bread == 7 && Ownership.isOwnedBy(level, overlapDestination, witness.getUUID())
			&& witnessMemory.homeChest.equals(Optional.of(overlapDestination)),
				"overlapping shift preserves inventory and private ownership: emeralds=" + emeralds + ", bread=" + bread); passed++;
			Map<Block, Integer> overlapAfter = materials(level, oldWorld.keySet());
			check(overlapBefore.equals(overlapAfter), "overlapping shift conserves materials: before=" + overlapBefore + ", after=" + overlapAfter); passed++;
			check(noNewDrops(level, area, oldDrops), "overlapping shift creates no loose inventory or material drops"); passed++;
			return passed;
		} finally {
			witness.discard(); Villages.forget(witness.getUUID());
			restore(level, oldWorld);
			if (oldBuildings == null) ref.bellEntity().removeAttached(VillagerPlus.BUILDINGS); else ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, oldBuildings);
			ref.data().buildProjects.clear(); ref.data().buildProjects.addAll(oldProjects);
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS); else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.data().extra.school = oldSchool; ref.data().extra.lager = oldLager; ref.data().chest = oldChest;
			ref.data().extra.lagerMore.clear(); ref.data().extra.lagerMore.addAll(oldMore);
			ref.data().extra.lagerChests.clear(); ref.data().extra.lagerChests.addAll(oldChests); ref.dirty();
			for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, area)) if (!oldDrops.contains(drop.getUUID())) drop.discard();
		}
	}

	private static Cell cell(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		return new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess()));
	}
	private static Map<BlockPos, Cell> capture(ServerLevel level, Iterable<BlockPos> positions) {
		Map<BlockPos, Cell> result = new LinkedHashMap<>(); for (BlockPos p : positions) result.put(p, cell(level, p)); return result;
	}
	private static boolean unchanged(ServerLevel level, Map<BlockPos, Cell> before) {
		return before.entrySet().stream().allMatch(e -> e.getValue().equals(cell(level, e.getKey())));
	}
	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		for (BlockPos pos : before.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
		for (var entry : before.entrySet()) level.setBlock(entry.getKey(), entry.getValue().state(), FLAGS);
		for (var entry : before.entrySet()) if (entry.getValue().data() != null) {
			BlockEntity be = BlockEntity.loadStatic(entry.getKey(), entry.getValue().state(), entry.getValue().data().copy(), level.registryAccess());
			if (be != null) level.setBlockEntity(be);
		}
	}
	private static Map<Block, Integer> materials(ServerLevel level, Iterable<BlockPos> positions) {
		Map<Block, Integer> result = new HashMap<>();
		for (BlockPos pos : positions) { BlockState state = level.getBlockState(pos); if (!state.isAir()) result.merge(state.getBlock(), 1, Integer::sum); }
		return result;
	}
	private static int contents(ServerLevel level, BlockPos pos, net.minecraft.world.item.Item item) {
		if (!(level.getBlockEntity(pos) instanceof Container box)) return -1;
		int total = 0; for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(item)) total += box.getItem(i).getCount(); return total;
	}
	private static boolean noNewDrops(ServerLevel level, AABB area, List<java.util.UUID> old) {
		return level.getEntitiesOfClass(ItemEntity.class, area).stream().allMatch(e -> old.contains(e.getUUID()));
	}
	private static void check(boolean condition, String label) {
		if (!condition) throw new IllegalStateException("RELOCATION WORLD CHECK FAILED: " + label);
		System.out.println("RELOCATION WORLD PASS " + label);
	}
	private static VillageData.BuildProject project(String type, BlockPos origin, VillageData.PlanBounds bounds) {
		return new VillageData.BuildProject(type, origin, 0, 0, 0, Optional.empty(), 0L, Optional.empty(), 0L, List.of(),
			VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(), Optional.of(bounds), false, false);
	}
}

