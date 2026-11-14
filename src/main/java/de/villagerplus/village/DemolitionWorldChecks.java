package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Built;
import de.villagerplus.build.Builder;
import de.villagerplus.build.Palette;
import de.villagerplus.build.Step;
import de.villagerplus.net.BuildingAction;
import com.mojang.serialization.JsonOps;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.ChunkPos;

/** Safe, isolated world checks for demolition requests and protected resident storage. */
public final class DemolitionWorldChecks {
	private record Cell(BlockState state, CompoundTag data) { }
	private static final int FLAGS = 2 | 16 | 32;
	public static final int PREPARING = -1;
	private static final int FIXTURE_TICKET_RADIUS = 2;
	private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, ChunkPos> FIXTURE_TICKETS = new HashMap<>();

	private DemolitionWorldChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos origin = ref.bell().offset(-52, 0, 36);
		Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE,
			Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
		Built built = new Built(Blueprints.HOUSE, origin, 0, 0, palette.ids());
		List<Step> steps = Builder.stepsOfBuilt(level, ref, built, null);
		if (steps.isEmpty()) throw new IllegalStateException("DEMOLITION WORLD CHECK FAILED: blueprint has no steps");
		BlockPos itemDropPos = steps.get(0).pos();
		if (!prepareFixtureChunk(level, itemDropPos)) return PREPARING;
		try {
			return runPrepared(level, ref, origin, built, steps);
		} finally {
			cleanupFixtureTicket(level);
		}
	}

	/** Adds a nonblocking ticket, then waits for a later command after the server promotes the fixture to entity ticking. */
	private static boolean prepareFixtureChunk(ServerLevel level, BlockPos fixture) {
		var dimension = level.dimension();
		ChunkPos chunkPos = ChunkPos.containing(fixture);
		ChunkPos pending = FIXTURE_TICKETS.get(dimension);
		if (pending != null && !pending.equals(chunkPos)) cleanupFixtureTicket(level);
		try {
			// Radius 2 promotes the center ticket level from FULL to ENTITY_TICKING in 26.2.
			level.getChunkSource().addTicketWithRadius(Anchor.WALL_WORK, chunkPos, FIXTURE_TICKET_RADIUS);
			FIXTURE_TICKETS.put(dimension, chunkPos);
		} catch (RuntimeException | Error failure) {
			cleanupFixtureTicket(level);
			throw failure;
		}
		var chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
		FullChunkStatus status = chunk == null ? null : chunk.getFullStatus();
		if (status == null || !status.isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
			System.out.println("DEMOLITION WORLD PREPARING fixture=" + fixture + " chunk=" + chunkPos
				+ " status=" + (status == null ? "UNLOADED" : status)
				+ "; wait for server ticks, then rerun /vptest090 demolition; no checks counted");
			return false;
		}
		System.out.println("DEMOLITION WORLD FIXTURE READY fixture=" + fixture + " chunk=" + chunkPos + " status=" + status);
		return true;
	}

	private static void cleanupFixtureTicket(ServerLevel level) {
		ChunkPos chunkPos = FIXTURE_TICKETS.remove(level.dimension());
		if (chunkPos != null) {
			level.getChunkSource().removeTicketWithRadius(Anchor.WALL_WORK, chunkPos, FIXTURE_TICKET_RADIUS);
		}
	}

	private static int runPrepared(ServerLevel level, VillageRef ref, BlockPos origin, Built built, List<Step> steps) {
		int passed = 0;
		AABB fixtureBounds = new AABB(origin).inflate(64, 16, 64);
		Set<UUID> oldItemIds = level.getEntitiesOfClass(ItemEntity.class, fixtureBounds).stream()
			.map(ItemEntity::getUUID).collect(java.util.stream.Collectors.toCollection(HashSet::new));
		for (int dx = -5; dx <= 20; dx += 16) for (int dz = -5; dz <= 18; dz += 16) level.getChunkAt(origin.offset(dx, 0, dz));
		var box = de.villagerplus.build.Furnisher.boxOf(steps);
		Map<BlockPos, Cell> oldWorld = new LinkedHashMap<>();
		int floorY = origin.getY() - 1;
		int captureMinY = Math.min(box.minY() - 3, floorY - 17);
		int captureMaxY = Math.max(box.maxY() + 3, floorY + 2);
		for (BlockPos p : BlockPos.betweenClosed(box.minX() - 2, captureMinY, box.minZ() - 2, box.maxX() + 2, captureMaxY, box.maxZ() + 2)) {
			BlockPos pos = p.immutable(); level.getChunkAt(pos); oldWorld.put(pos, cell(level, pos));
		}
		List<Built> oldBuildings = ref.bellEntity().getAttached(VillagerPlus.BUILDINGS);
		List<Demolition.Job> oldJobs = ref.bellEntity().getAttached(VillagerPlus.DEMOLITION);
		List<ZonePolicy.Zone> oldZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		List<VillageData.BuildProject> oldProjects = List.copyOf(ref.data().buildProjects);
		BlockPos privateChestPos = steps.get(0).pos();
		try {
			for (BlockPos pos : oldWorld.keySet()) {
				level.removeBlockEntity(pos);
				level.setBlock(pos, pos.getY() == origin.getY() - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), FLAGS);
			}
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, List.of(built));
			level.setBlock(privateChestPos, Blocks.CHEST.defaultBlockState(), FLAGS);
			BlockEntity be = level.getBlockEntity(privateChestPos);
			if (!(be instanceof Container chest)) throw new IllegalStateException("DEMOLITION WORLD CHECK FAILED: fixture chest missing");
			chest.setItem(0, new ItemStack(Items.EMERALD, 9));
			chest.setChanged();
			be.setAttached(VillagerPlus.OWNER, java.util.UUID.randomUUID());

			check(Demolition.hasPrivateStorage(level, ref, built), "private resident chest is detected inside the blueprint footprint"); passed++;
			check(!Demolition.collect(level, ref, privateChestPos, level.getBlockState(privateChestPos), null) && level.getBlockState(privateChestPos).is(Blocks.CHEST)
				&& count(chest, Items.EMERALD) == 9, "demolition collection refuses a private chest without changing its contents"); passed++;
			String blocked = Demolition.request(level, ref, origin);
			check(blocked.contains("private Truhe") && Demolition.jobs(ref).isEmpty(), "an order is rejected until resident storage is moved or emptied"); passed++;

			be.removeAttached(VillagerPlus.OWNER);
			check(!Demolition.hasPrivateStorage(level, ref, built), "an unowned public container does not block an otherwise valid demolition"); passed++;
			VillageData.PlanBounds targetBounds = Demolition.buildingBounds(level, ref, built);
			BlockPos primarySite = origin.offset(30, 0, 30), secondarySite = origin.offset(4, 0, 0);
			ref.data().buildProjects.clear();
			ref.data().buildProjects.add(project("house", primarySite,
				new VillageData.PlanBounds(primarySite.getX() - 2, primarySite.getX() + 2, primarySite.getZ() - 2, primarySite.getZ() + 2)));
			ref.data().buildProjects.add(project("school", secondarySite,
				new VillageData.PlanBounds(targetBounds.minX(), targetBounds.maxX(), targetBounds.minZ(), targetBounds.maxZ())));
			check(Demolition.overlapsConstructionReservation(ref, targetBounds)
				&& Demolition.request(level, ref, origin).contains("laufenden Bauprojekt") && Demolition.jobs(ref).isEmpty(),
				"demolition preserves a non-primary project's reserved construction footprint"); passed++;
			ref.data().buildProjects.clear();
			int emeraldsBefore = recoverableCount(level, ref, privateChestPos, Items.EMERALD) + count(chest, Items.EMERALD);
			BlockEntity sourceBeforeRemoval = level.getBlockEntity(privateChestPos);
			boolean sourceWasContainer = sourceBeforeRemoval instanceof Container;
			int sourceEmeraldsBefore = sourceBeforeRemoval instanceof Container sourceContainer
				? count(sourceContainer, Items.EMERALD) : -1;
			Demolition.RemovalResult chestRemoval = Demolition.removeAndCollectResult(level, ref, privateChestPos,
				level.getBlockState(privateChestPos), null, () -> level.setBlock(privateChestPos, Blocks.AIR.defaultBlockState(), FLAGS));
			int emeraldsAfter = recoverableCount(level, ref, privateChestPos, Items.EMERALD);
			int looseEmeralds = nearbyDrops(level, privateChestPos, Items.EMERALD);
			Container sharedChest = ref.chest(level);
			List<Container> publicLagers = Lager.containers(level, ref.data());
			String lagerEmeralds = publicLagers.stream().map(container -> Integer.toString(count(container, Items.EMERALD))).toList().toString();
			String specialEmeralds = java.util.Arrays.stream(new String[] {Blueprints.GRANARY, Blueprints.TOOLSHED, Blueprints.STATION})
				.map(type -> type + "=" + Specials.containers(level, ref, type).stream()
					.mapToInt(container -> count(container, Items.EMERALD)).sum())
				.toList().toString();
			String nearbyEmeraldEntities = level.getEntitiesOfClass(ItemEntity.class, fixtureBounds).stream()
				.filter(item -> item.getItem().is(Items.EMERALD))
				.map(item -> item.blockPosition() + "x" + item.getItem().getCount()).toList().toString();
			check(chestRemoval.removed() && chestRemoval.dropsSecured() && level.getBlockState(privateChestPos).isAir()
				&& count(chest, Items.EMERALD) == 0,
				"public chest contents remain recoverable when a building is dismantled (before=" + emeraldsBefore
					+ ", immediatelyVisible=" + emeraldsAfter + ", dropAccepted=" + chestRemoval.dropsSecured()
					+ ", finalBlock=" + level.getBlockState(privateChestPos)
					+ ", sourceWasContainer=" + sourceWasContainer + ", sourceEmeraldsBefore=" + sourceEmeraldsBefore
					+ ", sourceContainer=" + count(chest, Items.EMERALD) + ", shared=" + (sharedChest == null ? "absent" : count(sharedChest, Items.EMERALD))
					+ ", lager=" + lagerEmeralds + ", specials=" + specialEmeralds
					+ ", category=" + StockPolicy.category(Items.EMERALD) + ", specialType=" + Specials.depotType(new ItemStack(Items.EMERALD))
					+ ", blockDrops=" + level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.BLOCK_DROPS)
					+ ", looseNear=" + looseEmeralds + ", itemEntities=" + nearbyEmeraldEntities + ")"); passed++;
			BlockPos transactionalChestPos = steps.stream().map(Step::pos)
				.filter(pos -> !pos.equals(privateChestPos)).findFirst().orElseThrow();
			level.setBlock(transactionalChestPos, Blocks.CHEST.defaultBlockState(), FLAGS);
			if (!(level.getBlockEntity(transactionalChestPos) instanceof Container transactionalChest)) {
				throw new IllegalStateException("DEMOLITION WORLD CHECK FAILED: transactional public chest missing");
			}
			transactionalChest.setItem(0, new ItemStack(Items.EMERALD, 7));
			transactionalChest.setItem(4, new ItemStack(Items.DIAMOND, 2));
			transactionalChest.setChanged();
			int transactionalEmeralds = recoverableCount(level, ref, transactionalChestPos, Items.EMERALD)
				+ count(transactionalChest, Items.EMERALD);
			int transactionalDiamonds = recoverableCount(level, ref, transactionalChestPos, Items.DIAMOND)
				+ count(transactionalChest, Items.DIAMOND);
			check(!Demolition.removeAndCollect(level, ref, transactionalChestPos, level.getBlockState(transactionalChestPos), null, () -> false)
				&& level.getBlockState(transactionalChestPos).is(Blocks.CHEST)
				&& transactionalChest.getItem(0).is(Items.EMERALD) && transactionalChest.getItem(0).getCount() == 7
				&& transactionalChest.getItem(4).is(Items.DIAMOND) && transactionalChest.getItem(4).getCount() == 2
				&& recoverableCount(level, ref, transactionalChestPos, Items.EMERALD) + count(transactionalChest, Items.EMERALD) == transactionalEmeralds
				&& recoverableCount(level, ref, transactionalChestPos, Items.DIAMOND) + count(transactionalChest, Items.DIAMOND) == transactionalDiamonds,
				"a rejected physical block removal restores exact public-chest slots without routing duplicate contents"); passed++;
			Demolition.RemovalResult transactionalRemoval = Demolition.removeAndCollectResult(level, ref, transactionalChestPos,
				level.getBlockState(transactionalChestPos), null,
				() -> level.setBlock(transactionalChestPos, Blocks.AIR.defaultBlockState(), FLAGS));
			check(transactionalRemoval.removed() && transactionalRemoval.dropsSecured()
				&& level.getBlockState(transactionalChestPos).isAir() && transactionalChest.isEmpty(),
				"successful physical removal routes each public-chest item exactly once"); passed++;
			BlockPos replaced = steps.stream().map(Step::pos).filter(p -> !p.equals(privateChestPos)).findFirst().orElseThrow();
			level.setBlock(replaced, Blocks.COBBLESTONE.defaultBlockState(), FLAGS);
			int cobbleBefore = recoverableCount(level, ref, replaced, Items.COBBLESTONE);
			check(Demolition.removeAndCollect(level, ref, replaced, level.getBlockState(replaced), null)
				&& level.getBlockState(replaced).isAir()
				&& recoverableCount(level, ref, replaced, Items.COBBLESTONE) >= cobbleBefore + 1,
				"a block externally replaced inside the footprint yields its actual recoverable drop"); passed++;
			for (int x = box.minX(); x <= box.maxX(); x++) for (int z = box.minZ(); z <= box.maxZ(); z++) {
				level.setBlock(new BlockPos(x, floorY, z), Blocks.DIRT_PATH.defaultBlockState(), FLAGS);
				for (int y = floorY - 3; y < floorY; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
				// Give the ordinary three-block excavation a stable subsoil base. The
				// deeper-hole case below provides its own support at floorY - 6.
				level.setBlock(new BlockPos(x, floorY - 4, z), Blocks.DIRT.defaultBlockState(), FLAGS);
			}
			int surfaceX = box.minX() + (box.maxX() - box.minX()) / 2;
			BlockPos restoredTop = new BlockPos(box.minX(), floorY, box.minZ());
			BlockPos protectedRoad = new BlockPos(box.maxX(), floorY, box.maxZ());
			BlockPos zonedColumn = new BlockPos(box.minX(), floorY, box.maxZ());
			BlockPos deepColumn = new BlockPos(surfaceX, floorY, box.minZ());
			BlockPos fluidColumn = new BlockPos(surfaceX, floorY, box.maxZ());
			int grassX = surfaceX + 1 < box.maxX() ? surfaceX + 1 : surfaceX - 1;
			BlockPos grassColumn = new BlockPos(grassX, floorY, box.maxZ());
			level.setBlock(restoredTop, Blocks.AIR.defaultBlockState(), FLAGS);
			level.setBlock(protectedRoad, Blocks.DIRT_PATH.defaultBlockState(), FLAGS);
			level.setBlock(zonedColumn, Blocks.AIR.defaultBlockState(), FLAGS);
			level.setBlock(deepColumn, Blocks.AIR.defaultBlockState(), FLAGS);
			level.setBlock(fluidColumn, Blocks.AIR.defaultBlockState(), FLAGS);
			level.setBlock(grassColumn, Blocks.AIR.defaultBlockState(), FLAGS);
			for (int y = floorY - 5; y < floorY; y++) level.setBlock(new BlockPos(surfaceX, y, box.minZ()), Blocks.AIR.defaultBlockState(), FLAGS);
			level.setBlock(new BlockPos(surfaceX, floorY - 6, box.minZ()), Blocks.STONE.defaultBlockState(), FLAGS);
			level.setBlock(new BlockPos(surfaceX, floorY + 1, box.minZ()), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
			level.setBlock(new BlockPos(surfaceX, floorY - 1, box.maxZ()), Blocks.WATER.defaultBlockState(), FLAGS);
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(new ZonePolicy.Zone("demolition-fixture", "field",
				zonedColumn.getX(), zonedColumn.getZ(), zonedColumn.getX(), zonedColumn.getZ())));
			for (int x = box.minX(); x <= box.maxX(); x++) {
				level.setBlock(new BlockPos(x, floorY, box.minZ() - 1), Blocks.SAND.defaultBlockState(), FLAGS);
				level.setBlock(new BlockPos(x, floorY, box.maxZ() + 1), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
			}
			BlockPos protectedChest = new BlockPos(box.minX() + 1, floorY - 1, box.minZ() + 1);
			level.setBlock(protectedChest, Blocks.CHEST.defaultBlockState(), FLAGS);
			Container underfloorChest = (Container)level.getBlockEntity(protectedChest);
			underfloorChest.setItem(0, new ItemStack(Items.EMERALD, 3));
			underfloorChest.setChanged();
			int dirtBeforeGrade = recoverableCount(level, ref, deepColumn, Items.DIRT);
			Demolition.restoreTerrain(level, ref, built, steps, null);
			check(VillageStyle.natural(level.getBlockState(restoredTop)) && !level.getBlockState(restoredTop).is(Blocks.DIRT_PATH)
				&& level.getBlockState(restoredTop.below()).is(Blocks.DIRT),
				"demolition fills the former excavation and restores a natural surface layer"); passed++;
			check(level.getBlockState(protectedRoad).is(Blocks.DIRT_PATH),
				"demolition renaturation preserves an existing road surface inside the bounded footprint"); passed++;
			check(level.getBlockState(zonedColumn).isAir() && level.getBlockState(zonedColumn.below()).isAir(),
				"demolition renaturation leaves a planned zone untouched"); passed++;
			check(level.getBlockState(fluidColumn.below()).is(Blocks.WATER) && level.getBlockState(fluidColumn).isAir(),
				"demolition renaturation preserves fluid columns instead of filling over water"); passed++;
			BlockPos deepSurface = deepColumn.below(5);
			check(level.getBlockState(deepSurface).is(Blocks.DIRT)
				&& level.getBlockState(new BlockPos(surfaceX, floorY + 1, box.minZ())).isAir()
				&& recoverableCount(level, ref, deepColumn, Items.DIRT) > dirtBeforeGrade,
				"demolition fills deeper foundation holes and salvages the one-block natural grade step"); passed++;
			BlockPos sandSample = new BlockPos(surfaceX,
			level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, surfaceX, box.minZ() - 1) - 1,
			box.minZ() - 1);
			BlockPos grassSample = new BlockPos(grassX,
			level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, grassX, box.maxZ() + 1) - 1,
			box.maxZ() + 1);
			BlockState restoredSand = level.getBlockState(deepColumn);
			BlockState restoredGrass = level.getBlockState(grassColumn);
			BlockState seededSand = level.getBlockState(sandSample);
			BlockState seededGrass = level.getBlockState(grassSample);
			check(grassX >= box.minX() && grassX <= box.maxX() && !grassColumn.equals(fluidColumn)
			&& sandSample.getY() == floorY && seededSand.is(Blocks.SAND)
			&& grassSample.getY() == floorY && seededGrass.is(Blocks.GRASS_BLOCK)
			&& restoredSand.is(Blocks.SAND) && restoredGrass.is(Blocks.GRASS_BLOCK),
				"restored columns match adjacent seeded natural surfaces: expected sand at " + deepColumn + " but got " + restoredSand
				+ "; expected grass at " + grassColumn + " but got " + restoredGrass
				+ "; heightmap seed sand at " + sandSample + " is " + seededSand
				+ "; heightmap seed grass at " + grassSample + " is " + seededGrass); passed++;
			check(level.getBlockState(protectedChest).is(Blocks.CHEST) && underfloorChest.getItem(0).is(Items.EMERALD)
				&& underfloorChest.getItem(0).getCount() == 3, "terrain restoration preserves a block entity and its contents"); passed++;
			check(Demolition.safeCursor(-5, steps.size()) == 0 && Demolition.safeCursor(steps.size() + 5, steps.size()) == steps.size(),
				"persisted demolition cursor safely clamps after interruption or data migration"); passed++;
			Set<Long> plannedPositions = steps.stream().map(step -> step.pos().asLong()).collect(java.util.stream.Collectors.toSet());
			BlockPos addedFurniture = null;
			for (BlockPos candidate : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
				if (!plannedPositions.contains(candidate.asLong()) && level.getBlockState(candidate).isAir()) {
					addedFurniture = candidate.immutable();
					break;
				}
			}
			if (addedFurniture == null) throw new IllegalStateException("DEMOLITION WORLD CHECK FAILED: no unplanned furnishing cell in blueprint bounds");
			check(level.setBlock(addedFurniture, Blocks.BARREL.defaultBlockState(), FLAGS), "fixture places an extra furnished container"); passed++;
			if (!(level.getBlockEntity(addedFurniture) instanceof Container addedBarrel)) {
				throw new IllegalStateException("DEMOLITION WORLD CHECK FAILED: furnished barrel container missing");
			}
			addedBarrel.setItem(0, new ItemStack(Items.EMERALD, 6));
			addedBarrel.setChanged();
			String accepted = Demolition.request(level, ref, origin);
			List<Demolition.Job> queuedJobs = Demolition.jobs(ref);
			check(accepted.startsWith("Abriss angeordnet") && queuedJobs.size() == 1
				&& queuedJobs.get(0).origin().equals(origin) && queuedJobs.get(0).index() == 0,
				"an authorized blueprint demolition is persisted as a resumable cursor"); passed++;
			Demolition.Job queued = queuedJobs.get(0);
			Demolition.Job captured = Demolition.captureAdditionalBlocks(level, ref, built, queued);
			BlockPos furnishingPosition = addedFurniture;
			check(captured.additionalBlocksCaptured() && captured.additionalBlocks().contains(addedFurniture)
				&& Demolition.stepsForJob(level, ref, built, captured, null).stream().anyMatch(step -> step.pos().equals(furnishingPosition)),
				"demolition cursor includes furnished blocks added inside the architect footprint"); passed++;
			Demolition.RemovalResult furnishedRemoval = Demolition.removeAndCollectResult(level, ref, addedFurniture,
				level.getBlockState(addedFurniture), null, () -> level.setBlock(furnishingPosition, Blocks.AIR.defaultBlockState(), FLAGS));
			check(furnishedRemoval.removed() && furnishedRemoval.dropsSecured()
				&& level.getBlockState(addedFurniture).isAir()
				&& count(addedBarrel, Items.EMERALD) == 0,
				"contents of a furnished public container remain recoverable during demolition"); passed++;
			Demolition.Job progressed = new Demolition.Job(origin, 17);
			var jobsCodec = Demolition.Job.CODEC.listOf();
			var encoded = jobsCodec.encodeStart(JsonOps.INSTANCE, List.of(progressed, captured)).getOrThrow();
			check(jobsCodec.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(List.of(progressed, captured)),
				"block cursor and captured furnishing positions survive the persistent demolition attachment codec"); passed++;
			check(new BuildingAction(origin, origin, BuildingAction.DEMOLISH).supportedAction()
				&& new BuildingAction(origin, origin, BuildingAction.MOVE).supportedAction()
				&& !new BuildingAction(origin, origin, "unknown").supportedAction(), "only the two supported map actions reach server handlers"); passed++;
			BlockState waterloggedStair = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
			check(!Demolition.skipDuringDemolition(waterloggedStair)
				&& Demolition.skipDuringDemolition(Blocks.WATER.defaultBlockState()),
				"waterlogged building blocks are removed while free water is skipped"); passed++;
			return passed;
		} finally {
			for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) {
				if (!oldItemIds.contains(item.getUUID())) item.discard();
			}
			restore(level, oldWorld);
			if (oldBuildings == null) ref.bellEntity().removeAttached(VillagerPlus.BUILDINGS); else ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, oldBuildings);
			if (oldJobs == null) ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION); else ref.bellEntity().setAttached(VillagerPlus.DEMOLITION, oldJobs);
			if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES); else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
			ref.data().buildProjects.clear(); ref.data().buildProjects.addAll(oldProjects);
			ref.dirty();
		}
	}

	private static int count(Container container, net.minecraft.world.item.Item item) {
		int total = 0;
		for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).is(item)) total += container.getItem(i).getCount();
		return total;
	}

	private static int nearbyDrops(ServerLevel level, BlockPos pos, net.minecraft.world.item.Item item) {
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4)).stream()
			.filter(drop -> drop.getItem().is(item)).mapToInt(drop -> drop.getItem().getCount()).sum();
	}

	private static int recoverableCount(ServerLevel level, VillageRef ref, BlockPos pos, net.minecraft.world.item.Item item) {
		int stored = 0;
		Container home = ref.chest(level);
		if (home != null) stored += count(home, item);
		for (Container lager : Lager.containers(level, ref.data())) stored += count(lager, item);
		return stored + nearbyDrops(level, pos, item);
	}

	private static Cell cell(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		return new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess()));
	}

	private static void restore(ServerLevel level, Map<BlockPos, Cell> oldWorld) {
		for (BlockPos pos : oldWorld.keySet()) level.removeBlockEntity(pos);
		for (BlockPos pos : oldWorld.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
		for (var entry : oldWorld.entrySet()) level.setBlock(entry.getKey(), entry.getValue().state(), FLAGS);
		for (var entry : oldWorld.entrySet()) if (entry.getValue().data() != null) {
			BlockEntity be = BlockEntity.loadStatic(entry.getKey(), entry.getValue().state(), entry.getValue().data().copy(), level.registryAccess());
			if (be != null) level.setBlockEntity(be);
		}
	}

	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("DEMOLITION WORLD CHECK FAILED: " + label);
		System.out.println("DEMOLITION WORLD PASS " + label);
	}

	private static VillageData.BuildProject project(String type, BlockPos origin, VillageData.PlanBounds bounds) {
		return new VillageData.BuildProject(type, origin, 0, 0, 0, java.util.Optional.empty(), 0L,
			java.util.Optional.empty(), 0L, List.of(), VillageData.ProjectHaul.empty(), java.util.Optional.empty(),
			java.util.Optional.empty(), java.util.Optional.of(bounds), false, false);
	}
}
