package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.Lager;
import de.villagerplus.village.VillageRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;

/** Deterministic, explicitly-gated checks for real construction cargo accounting. */
public final class BuildHaulChecks {
	private BuildHaulChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos spawn = ref.bell().offset(2, 2, 0);
		level.getChunkAt(spawn);
		Villager carrier = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
		if (carrier == null) throw new IllegalStateException("BUILD HAUL CHECK FAILED: cannot create carrier fixture");
		carrier.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		carrier.setNoAi(true);
		if (!level.addFreshEntity(carrier)) throw new IllegalStateException("BUILD HAUL CHECK FAILED: cannot register carrier fixture");
		carrier.addTag("vp090_fixture");
		BlockPos origin = ref.bell().offset(29, 3, 17).immutable();
		BlockPos source = ref.bell().offset(-3, 0, 2).immutable();
		Ctx context = new Ctx(level, carrier, new JobState(), carrier.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
		VillageData villageData = ref.data();
		List<VillageData.BuildProject> oldProjects = List.copyOf(villageData.buildProjects);
		Optional<BlockPos> oldPlanOrigin = villageData.planOrigin;
		long oldPlanProgress = villageData.planProgress;
		int oldProjectVersion = villageData.buildProjectVersion;
		List<BlockPos> oldLagerMore = List.copyOf(villageData.extra.lagerMore);
		BlockPos barrelPos = null;
		BlockState oldBarrelState = null;
		boolean barrelPlaced = false;
		int checks = 0;
		try {
			List<BlockPos> storageCandidates = new java.util.ArrayList<>();
			for (int index = 0; index < 80; index++) storageCandidates.add(new BlockPos(index, 64, 0));
			BlockPos distantStock = storageCandidates.getLast();
			check(BuildHaul.stockedSources(storageCandidates, distantStock::equals).equals(List.of(distantStock)),
				"more than 64 nearer empty storage candidates no longer hide a farther public source with demanded material"); checks++;

			barrelPos = emptyStorageFixturePos(level, ref.bell());
			oldBarrelState = level.getBlockState(barrelPos);
			villageData.extra.lagerMore.add(barrelPos);
			level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), 3);
			barrelPlaced = level.getBlockState(barrelPos).is(Blocks.BARREL);
			Container barrel = Lager.publicContainerAt(level, barrelPos);
			check(barrelPlaced && barrel != null, "registered storage fixture creates a public barrel source"); checks++;
			if (barrel != null) {
				barrel.setItem(0, new ItemStack(Items.STRIPPED_BIRCH_LOG, 1));
				check(BuildHaul.selectsSourceForCheck(context, List.of("minecraft:stripped_birch_log#1"),
					barrelPos, Items.STRIPPED_BIRCH_LOG),
					"wanted stripped birch log is matched to its exact item in a registered public barrel"); checks++;
				level.getChunkAt(origin);
				carrier.setPos(barrelPos.getX() + 1.5, barrelPos.getY(), barrelPos.getZ() + 0.5);
				BlockPos atPickup = carrier.blockPosition();
				SimpleContainer hauledStock = new SimpleContainer(4);
				boolean pickedUp = BuildHaul.tick(context, origin, origin,
					List.of("minecraft:stripped_birch_log#1"), true, hauledStock);
				check(pickedUp && barrel.getItem(0).isEmpty() && JobUtil.count(carrier.getInventory(),
					stack -> stack.is(Items.STRIPPED_BIRCH_LOG)) == 1
					&& BuildHaul.isMarkedCargo(carrier.getInventory().getItem(0))
					&& BuildHaul.visibleCargo(carrier).is(Items.STRIPPED_BIRCH_LOG)
					&& carrier.blockPosition().equals(atPickup),
					"the production haul tick atomically removes public stock, reserves the real visible load, and leaves the carrier at the pickup point"); checks++;
				carrier.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
				boolean deliveredByTick = BuildHaul.tick(context, origin, origin,
					List.of("minecraft:stripped_birch_log#1"), true, hauledStock);
				check(deliveredByTick && JobUtil.count(carrier.getInventory(), stack -> stack.is(Items.STRIPPED_BIRCH_LOG)) == 0
					&& JobUtil.count(hauledStock, stack -> stack.is(Items.STRIPPED_BIRCH_LOG)) == 1
					&& BuildHaul.manifest(context, origin, List.of()).isEmpty(),
					"the same production tick delivers the real item into project stock and clears its carrier reservation"); checks++;
			}

			if (barrel != null) {
				// Two civic projects share the public stock: the one with the open wish list that leads keeps what it asks for.
				List<VillageData.BuildProject> savedProjects = List.copyOf(villageData.buildProjects);
				try {
					BlockPos leadOrigin = origin.offset(60, 0, 0).immutable(), otherOrigin = origin.offset(-60, 0, 0).immutable();
					long now = level.getGameTime();
					villageData.buildProjects.clear();
					villageData.buildProjects.add(new VillageData.BuildProject("granary", leadOrigin, 0, 0, 0, Optional.empty(), now,
						Optional.empty(), 0L, List.of("minecraft:cobblestone#6"), VillageData.ProjectHaul.empty(), Optional.empty(),
						Optional.empty(), Optional.empty(), false, false));
					villageData.buildProjects.add(new VillageData.BuildProject("storage", otherOrigin, 0, 0, 0, Optional.empty(), now,
						Optional.empty(), 0L, List.of("minecraft:cobblestone#10"), VillageData.ProjectHaul.empty(), Optional.empty(),
						Optional.empty(), Optional.empty(), false, false));
					barrel.clearContent();
					barrel.setItem(0, new ItemStack(Items.COBBLESTONE, 8));
					check(BuildHaul.leadReservationForCheck(context, leadOrigin).isEmpty()
						&& BuildHaul.leadReservationForCheck(context, otherOrigin).getOrDefault(Items.COBBLESTONE, 0) == 6,
						"the leading civic project reserves its open wish list against the other project"); checks++;
					check(BuildHaul.pickupCountForCheck(context, List.of("minecraft:cobblestone#10"), otherOrigin) == 2
						&& BuildHaul.pickupCountForCheck(context, List.of("minecraft:cobblestone#10"), leadOrigin) == 8,
						"the other project takes only what is above the reservation, the lead takes the stock"); checks++;
					villageData.buildProjects.set(0, new VillageData.BuildProject("granary", leadOrigin, 0, 0, 0, Optional.empty(),
						now - BuildHaul.LEAD_STALE_TICKS - 1L, Optional.empty(), 0L, List.of("minecraft:cobblestone#6"),
						VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, false));
					check(BuildHaul.leadReservationForCheck(context, otherOrigin).isEmpty(), "a lead that has not moved for a long time gives the reservation up"); checks++;
					barrel.clearContent();
				} finally {
					villageData.buildProjects.clear();
					villageData.buildProjects.addAll(savedProjects);
				}
			}

			ItemStack original = new ItemStack(Items.OAK_PLANKS, 5);
			CompoundTag unrelated = new CompoundTag();
			unrelated.putString("keep_me", "payload");
			original.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
			ItemStack marked = BuildHaul.markForCheck(original, context, origin, source);
			carrier.getInventory().setItem(0, marked);
			Anim.apply(carrier, context.st(), level.getGameTime());
			CustomData displayedData = carrier.getMainHandItem().get(DataComponents.CUSTOM_DATA);
			CustomData carriedData = carrier.getInventory().getItem(0).get(DataComponents.CUSTOM_DATA);
			check(carrier.getMainHandItem().is(Items.OAK_PLANKS) && carrier.getMainHandItem().getCount() == 1
				&& carrier.getInventory().getItem(0).getCount() == 5 && displayedData != null && carriedData != null
				&& !displayedData.copyTag().contains("vp_build_haul_project")
				&& displayedData.copyTag().getStringOr("keep_me", "").equals("payload")
				&& carriedData.copyTag().contains("vp_build_haul_project"),
				"visual cargo is a marker-free one-item copy while the real full cargo retains its route marker"); checks++;
			ItemStack unmarked = BuildHaul.clearMarkerForCheck(marked);
			CustomData unmarkedData = unmarked.get(DataComponents.CUSTOM_DATA);
			check(unmarkedData != null && unmarkedData.copyTag().getStringOr("keep_me", "").equals("payload"),
				"clearing haul metadata preserves unrelated item custom data"); checks++;

			List<String> loadedManifest = BuildHaul.manifest(context, origin, List.of());
			check(loadedManifest.size() == 1 && loadedManifest.getFirst().startsWith(carrier.getUUID() + "|minecraft:oak_planks|5|"),
				"loaded carrier inventory reconstructs the persisted item manifest"); checks++;
			Map<Item, Integer> loadedTotals = BuildHaul.inTransit(loadedManifest);
			check(loadedTotals.getOrDefault(Items.OAK_PLANKS, 0) == 5,
				"manifest demand reservation equals the real carried stack"); checks++;
			carrier.getInventory().setItem(0, marked.copyWithCount(6));
			List<String> refreshedManifest = BuildHaul.manifest(context, origin, loadedManifest);
			check(BuildHaul.inTransit(refreshedManifest).getOrDefault(Items.OAK_PLANKS, 0) == 6,
				"current carrier inventory replaces a stale same-tick manifest snapshot"); checks++;
			carrier.getInventory().setItem(0, marked.copy());

			String unresolved = UUID.randomUUID() + "|minecraft:oak_planks|3|" + source.asLong();
			List<String> reconciled = BuildHaul.manifest(context, origin, List.of(unresolved));
			check(reconciled.size() == 2 && BuildHaul.inTransit(reconciled).getOrDefault(Items.OAK_PLANKS, 0) == 8,
				"unloaded carrier reservation survives while loaded cargo is reconstructed"); checks++;
			check(BuildHaul.inTransit(List.of("bad", UUID.randomUUID() + "|minecraft:missing_item|9|0" )).isEmpty(),
				"malformed and unknown-item manifest rows are ignored safely"); checks++;

			SimpleContainer siteStock = new SimpleContainer(4);
			int moved = BuildHaul.deliverToStockForCheck(carrier.getInventory(), siteStock, context, origin);
			check(moved == 5 && carrier.getInventory().getItem(0).isEmpty()
				&& JobUtil.count(siteStock, stack -> stack.is(Items.OAK_PLANKS)) == 5,
				"delivery transfers the exact real count from carrier inventory into project stock"); checks++;
		List<String> afterDeliveryManifest = BuildHaul.manifest(context, origin, loadedManifest);
		check(afterDeliveryManifest.isEmpty(),
			"depositing the real load clears its persisted reservation so crew assignment can resume"); checks++;
			ItemStack delivered = siteStock.getItem(0);
			check(delivered.get(DataComponents.CUSTOM_DATA) != null
				&& delivered.get(DataComponents.CUSTOM_DATA).copyTag().getStringOr("keep_me", "").equals("payload")
				&& !delivered.get(DataComponents.CUSTOM_DATA).copyTag().contains("vp_build_haul_project"),
				"project stock consolidates delivery by stripping only the haul marker"); checks++;

			carrier.getInventory().setItem(0, BuildHaul.markForCheck(new ItemStack(Items.OAK_PLANKS, 5), context, origin, source));
			SimpleContainer partialStock = new SimpleContainer(1);
			partialStock.setItem(0, new ItemStack(Items.OAK_PLANKS, 63));
			int partial = BuildHaul.deliverToStockForCheck(carrier.getInventory(), partialStock, context, origin);
			check(partial == 1 && partialStock.getItem(0).getCount() == 64
				&& JobUtil.count(carrier.getInventory(), stack -> stack.is(Items.OAK_PLANKS)) == 4,
				"partial project-stock capacity preserves the undelivered real cargo"); checks++;

			carrier.getInventory().clearContent();
			carrier.getInventory().setItem(0, BuildHaul.markForCheck(new ItemStack(Items.OAK_PLANKS, 5), context, origin, source));
			SimpleContainer fullStock = new SimpleContainer(1);
			fullStock.setItem(0, new ItemStack(Items.DIRT, 64));
			int blocked = BuildHaul.deliverToStockForCheck(carrier.getInventory(), fullStock, context, origin);
			check(blocked == 0 && JobUtil.count(carrier.getInventory(), stack -> stack.is(Items.OAK_PLANKS)) == 5,
				"full project stock leaves all real cargo with its carrier for retry"); checks++;

			carrier.getInventory().clearContent();
			check(BuiltInRegistries.ITEM.getKey(Items.OAK_PLANKS).equals(Identifier.parse("minecraft:oak_planks")),
				"manifest item identifiers use the registered vanilla item id"); checks++;

			long progressNow = Math.max(1L, level.getGameTime());
			long staleProgress = Math.max(0L, progressNow - 72_000L);
			String inTransit = carrier.getUUID() + "|minecraft:oak_planks|5|" + source.asLong();
			VillageData.BuildProject haulingProject = new VillageData.BuildProject("house", origin, 0, 7, 0,
				Optional.empty(), staleProgress, Optional.of(carrier.getUUID()), 0L, List.of(),
				new VillageData.ProjectHaul(List.of(), List.of(inTransit)), Optional.empty(), Optional.empty(),
				Optional.empty(), false, false);
			villageData.buildProjects.clear();
			villageData.buildProjects.add(haulingProject);
			villageData.planOrigin = Optional.of(origin);
			villageData.planProgress = staleProgress;
			boolean progressTouched = BuildHaul.touchProjectProgressForHaul(villageData, origin, progressNow);
			VillageData.BuildProject progressedProject = villageData.buildProject(origin).orElse(null);
			check(progressTouched && progressedProject != null && progressedProject.progress() == progressNow
				&& progressedProject.wanted().isEmpty() && progressedProject.cargoManifest().equals(List.of(inTransit))
				&& villageData.planProgress == progressNow,
				"real haul progress refreshes stalled project clocks while preserving the in-transit reservation"); checks++;
			check(!BuildHaul.touchProjectProgressForHaul(villageData, origin, progressNow),
				"same-tick haul heartbeat does not dirty project progress repeatedly"); checks++;
			checks += siteWorkshopChecks(level, ref, carrier, barrelPos, origin);
			checks += constructionContinuationChecks(level, carrier, barrelPos.above(5));
			return checks;
		} finally {
			if (barrelPlaced && barrelPos != null && oldBarrelState != null) level.setBlock(barrelPos, oldBarrelState, 3);
			villageData.extra.lagerMore.clear();
			villageData.extra.lagerMore.addAll(oldLagerMore);
			villageData.buildProjects.clear();
			villageData.buildProjects.addAll(oldProjects);
			villageData.planOrigin = oldPlanOrigin;
			villageData.planProgress = oldPlanProgress;
			villageData.buildProjectVersion = oldProjectVersion;
			ref.dirty();
			carrier.discard();
		}
	}

	/** The observed roof stall: logs already delivered to the site must be usable there, without moving public goods. */
	private static int siteWorkshopChecks(ServerLevel level, VillageRef ref, Villager worker, BlockPos bench, BlockPos origin) {
		var data = ref.data();
		var oldOrigin = data.planOrigin;
		String oldType = data.planType;
		var oldWanted = List.copyOf(data.wanted);
		BlockState oldBench = level.getBlockState(bench);
		int checks = 0;
		try {
			data.planOrigin = Optional.of(origin);
			data.planType = "granary";
			data.wanted.clear();
			data.wanted.add("minecraft:spruce_stairs#24");
			var site = new de.villagerplus.build.ConstructionSite(origin,
				List.of(new de.villagerplus.build.ConstructionSite.TemporaryBlock(bench, true)), List.of(), true);
			SimpleContainer stock = new SimpleContainer(27);
			stock.setItem(0, new ItemStack(Items.SPRUCE_LOG, 14));
			level.setBlock(bench, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
			worker.setPos(bench.getX() + 1.5, bench.getY(), bench.getZ() + 0.5);
			Ctx c = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
			int publicBefore = JobUtil.count(ref.chest(level), stack -> true);
			checks += siteWorkshopReviewChecks(c, site, bench);
			check(!Workshop.tickAtSite(c, new de.villagerplus.build.ConstructionSite(origin, site.temporary()), stock, Map.of())
				&& !Workshop.tickAtSite(c, new de.villagerplus.build.ConstructionSite(origin.east(), site.temporary(), List.of(), true), stock, Map.of())
				&& JobUtil.count(stock, stack -> stack.is(Items.SPRUCE_LOG)) == 14,
				"unprepared or foreign project sites cannot consume the selected project's materials"); checks++;
			worker.setPos(bench.getX() + 12.5, bench.getY(), bench.getZ() + 0.5);
			Workshop.tickAtSite(c, site, stock, Map.of());
			check(JobUtil.count(stock, stack -> stack.is(Items.SPRUCE_LOG)) == 14
				&& JobUtil.count(stock, stack -> stack.is(Items.SPRUCE_PLANKS)) == 0,
				"a distant worker cannot consume site stock or craft remotely"); checks++;
			worker.setPos(bench.getX() + 1.5, bench.getY(), bench.getZ() + 0.5);
			check(Workshop.tickAtSite(c, site, stock, Map.of()), "site logs must start the loaded plank/stair recipe chain at the real site workbench"); checks++;
			for (int attempt = 0; attempt < 8 && JobUtil.count(stock, stack -> stack.is(Items.SPRUCE_STAIRS)) < 24; attempt++) {
				int missing = 24 - JobUtil.count(stock, stack -> stack.is(Items.SPRUCE_STAIRS));
				data.wanted.clear();
				data.wanted.add("minecraft:spruce_stairs#" + missing);
				Workshop.tickAtSite(c, site, stock, Map.of());
			}
			int stairs = JobUtil.count(stock, stack -> stack.is(Items.SPRUCE_STAIRS));
			int planks = JobUtil.count(stock, stack -> stack.is(Items.SPRUCE_PLANKS));
			int logs = JobUtil.count(stock, stack -> stack.is(Items.SPRUCE_LOG));
			check(stairs == 24 && 3 * stairs + 2 * planks + 8 * logs == 112,
				"twenty-four site stairs conserve all fourteen logs through loaded recipes, including leftover planks [stairs=" + stairs + ", planks=" + planks + ", logs=" + logs + "]"); checks++;
			check(JobUtil.count(ref.chest(level), stack -> true) == publicBefore,
				"site crafting does not withdraw from or deposit into public storage"); checks++;
			data.wanted.clear();
			check(!Workshop.tickAtSite(c, site, stock, Map.of()), "a satisfied site does not keep crafting"); checks++;
			SimpleContainer full = new SimpleContainer(2);
			full.setItem(0, new ItemStack(Items.SPRUCE_LOG, 64));
			full.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
			data.wanted.clear();
			data.wanted.add("minecraft:spruce_stairs#4");
			check(!Workshop.tickAtSite(c, site, full, Map.of()) && full.getItem(0).getCount() == 64 && full.getItem(1).getCount() == 64,
				"a full site rejects an intermediate recipe without losing logs or unrelated materials"); checks++;
			data.wanted.add("minecraft:spruce_stairs#4");
			level.setBlock(bench, Blocks.AIR.defaultBlockState(), 3);
			check(!Workshop.tickAtSite(c, site, stock, Map.of()) && JobUtil.count(stock, stack -> stack.is(Items.SPRUCE_STAIRS)) == stairs,
				"a removed site workbench prevents crafting without consuming any stock"); checks++;
			return checks;
		} finally {
			level.setBlock(bench, oldBench, 3);
			data.planOrigin = oldOrigin;
			data.planType = oldType;
			data.wanted.clear();
			data.wanted.addAll(oldWanted);
		}
	}

	private static int siteWorkshopReviewChecks(Ctx c, de.villagerplus.build.ConstructionSite site, BlockPos bench) {
		var data = c.village().data();
		var wanted = List.copyOf(data.wanted);
		long progress = data.planProgress;
		List<String> failures = new java.util.ArrayList<>();
		try {
			SimpleContainer partial = new SimpleContainer(8);
			partial.setItem(0, new ItemStack(Items.SPRUCE_STAIRS, 4));
			partial.setItem(1, new ItemStack(Items.SPRUCE_PLANKS, 6));
			data.wanted.clear(); data.wanted.add("minecraft:spruce_stairs#1");
			Workshop.tickAtSite(c, site, partial, Map.of());
			if (JobUtil.count(partial, stack -> stack.is(Items.SPRUCE_STAIRS)) != 8) failures.add("partial final stair batch rejected");

			SimpleContainer logs = new SimpleContainer(8);
			logs.setItem(0, new ItemStack(Items.SPRUCE_LOG, 8));
			data.wanted.clear(); data.wanted.add("minecraft:spruce_stairs#4");
			var directLogs = List.of(
				new de.villagerplus.build.Step(bench.above(5), Items.OAK_LOG, net.minecraft.tags.ItemTags.LOGS, 6, false, null, b -> b.defaultBlockState()),
				new de.villagerplus.build.Step(bench.above(6), Items.SPRUCE_STAIRS, null, 4, false, null, b -> b.defaultBlockState()));
			var reserves = de.villagerplus.build.Builder.sitePlacementReservations(c.level(), directLogs, 0, logs);
			if (reserves.getOrDefault(Items.SPRUCE_LOG, 0) != 6) failures.add("log tag alternatives not reserved for direct steps");
			Workshop.tickAtSite(c, site, logs, reserves);
			if (JobUtil.count(logs, stack -> stack.is(Items.SPRUCE_LOG)) < 6) failures.add("direct log placement reserve consumed");

			SimpleContainer planks = new SimpleContainer(8);
			planks.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 12));
			data.wanted.clear(); data.wanted.add("minecraft:spruce_stairs#8");
			Workshop.tickAtSite(c, site, planks, Map.of(Items.SPRUCE_PLANKS, 6));
			if (JobUtil.count(planks, stack -> stack.is(Items.SPRUCE_PLANKS)) < 6) failures.add("direct plank placement reserve consumed");

			SimpleContainer stone = new SimpleContainer(8);
			stone.setItem(0, new ItemStack(Items.STONE_BRICKS, 1));
			data.wanted.clear(); data.wanted.add("minecraft:stone_brick_stairs#1");
			if (Workshop.tickAtSite(c, site, stone, Map.of()) || stone.getItem(0).getCount() != 1
				|| !stone.getItem(0).is(Items.STONE_BRICKS)) failures.add("crafting table executed stonecutter recipe");

			SimpleContainer distant = new SimpleContainer(8);
			distant.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 6));
			data.wanted.clear(); data.wanted.add("minecraft:spruce_stairs#4");
			c.v().setPos(bench.getX() + 12.5, bench.getY(), bench.getZ() + 0.5);
			if (Workshop.tickAtSite(c, site, distant, Map.of())) failures.add("unreachable suspended bench monopolizes owner");
			c.v().setPos(bench.getX() + 1.5, bench.getY(), bench.getZ() + 0.5);

			SimpleContainer fresh = new SimpleContainer(8);
			fresh.setItem(0, new ItemStack(Items.SPRUCE_PLANKS, 6));
			data.planProgress = c.now() - 40_000;
			Workshop.tickAtSite(c, site, fresh, Map.of());
			if (data.planProgress != c.now()) failures.add("committed craft leaves progress clock stale");
			check(failures.isEmpty(), String.join("; ", failures));
			return 7;
		} finally {
			data.wanted.clear(); data.wanted.addAll(wanted);
			data.planProgress = progress;
		}
	}

	/** Exercise the dispatcher continuation against real marked inventory, funded placements, and a cancelled order. */
	private static int constructionContinuationChecks(ServerLevel level, Villager worker, BlockPos bell) {
		Map<BlockPos, BlockState> before = new java.util.LinkedHashMap<>();
		BlockPos origin = bell.south(4), source = bell.east();
		List<de.villagerplus.build.Step> oldSteps = null;
		Map<Long, List<de.villagerplus.build.Step>> cachedSteps = null;
		try {
			for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) for (int dy = -1; dy <= 2; dy++) {
				BlockPos pos = bell.offset(dx, dy, dz);
				level.getChunkAt(pos);
				if (level.getBlockEntity(pos) != null) throw new IllegalStateException("cargo continuation fixture overlaps a block entity");
				before.put(pos, level.getBlockState(pos));
				level.setBlock(pos, dy == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
			}
			level.setBlock(bell, Blocks.BELL.defaultBlockState(), 2);
			level.setBlock(source, Blocks.CHEST.defaultBlockState(), 2);
			var bellEntity = (net.minecraft.world.level.block.entity.BellBlockEntity) level.getBlockEntity(bell);
			VillageData data = new VillageData();
			data.chest = Optional.of(source);
			bellEntity.setAttached(VillagerPlus.VILLAGE, data);
			VillageRef ref = new VillageRef(bell, data, bellEntity);
			de.villagerplus.village.Villages.noteChunk(level.getChunkAt(bell));
			worker.setVillagerData(worker.getVillagerData().withProfession(level.registryAccess(),
				net.minecraft.world.entity.npc.villager.VillagerProfession.FARMER));
			worker.setNoAi(false);
			de.villagerplus.village.Villages.bindResident(level, ref, worker, null);
			worker.getInventory().clearContent();
			worker.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 5.5);
			Ctx c = new Ctx(level, worker, new JobState(), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
			var field = de.villagerplus.build.Builder.class.getDeclaredField("STEPS");
			field.setAccessible(true);
			@SuppressWarnings("unchecked") var map = (Map<Long, List<de.villagerplus.build.Step>>) field.get(null);
			cachedSteps = map;
			oldSteps = map.put(origin.asLong(), List.of(de.villagerplus.build.Step.block(origin, Blocks.OAK_PLANKS)));
			var project = new VillageData.BuildProject("house", origin, 0, 0, 0, Optional.empty(), 1L,
				Optional.empty(), 0L, List.of(), new VillageData.ProjectHaul(List.of(new ItemStack(Items.OAK_PLANKS, 1)), List.of()),
				Optional.empty(), Optional.of(new de.villagerplus.build.ConstructionSite(origin, List.of(), List.of(), true)),
				Optional.empty(), false, false);
			data.putBuildProject(project);
			worker.getInventory().setItem(0, BuildHaul.markForCheck(new ItemStack(Items.OAK_PLANKS, 5), c, origin, source));
			check(JobManager.continueConstructionHaul(c), "a non-porter resumes its real reserved cargo without needing a crew assignment");
			var delivered = data.buildProject(origin).orElseThrow();
			int siteCount = delivered.siteStock().stream().filter(stack -> stack.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum();
			check(siteCount == 6 && worker.getInventory().isEmpty() && delivered.step() == 0 && level.getBlockState(origin).isAir(),
				"delivery comes before construction even when the next placement is funded; all five planks join the real site stock");

			data.buildProjects.clear();
			data.putBuildProject(new VillageData.BuildProject(project.type(), origin, 0, 0, 0, Optional.empty(), 1L,
				Optional.empty(), 0L, List.of(), VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, true));
			worker.getInventory().setItem(0, BuildHaul.markForCheck(new ItemStack(Items.OAK_PLANKS, 7), c, origin, source));
			worker.setPos(source.getX() + 1.5, source.getY(), source.getZ() + 0.5);
			check(JobManager.continueConstructionHaul(c) && worker.getInventory().isEmpty()
				&& JobUtil.count(ref.chest(level), stack -> stack.is(Items.OAK_PLANKS)) == 7,
				"a farmer physically returns all reserved goods after cancellation instead of retaining orphan cargo");
			worker.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
			check(!JobManager.continueConstructionHaul(c) && worker.getInventory().getItem(0).getCount() == 3,
				"unmarked personal goods never start the construction continuation");
			BlockPos unloadedOrigin = new BlockPos(500_000, 280, 500_000);
			check(!de.villagerplus.world.LoadedColumns.ready(level, unloadedOrigin),
				"the continuation retry fixture uses an unloaded destination without loading its chunk");
			data.buildProjects.clear();
			data.putBuildProject(new VillageData.BuildProject("house", unloadedOrigin, 0, 0, 0, Optional.empty(), 1L,
				Optional.empty(), 0L, List.of(), VillageData.ProjectHaul.empty(), Optional.empty(),
				Optional.of(new de.villagerplus.build.ConstructionSite(unloadedOrigin, List.of(), List.of(), true)),
				Optional.empty(), false, false));
			worker.getInventory().setItem(0, BuildHaul.markForCheck(new ItemStack(Items.SPRUCE_LOG, 1), c, unloadedOrigin, source));
			check(JobManager.continueConstructionHaul(c)
				&& JobUtil.count(worker.getInventory(), BuildHaul::isMarkedCargo) == 1
				&& !de.villagerplus.world.LoadedColumns.ready(level, unloadedOrigin),
				"an unavailable cargo destination keeps ownership of the work attempt and preserves its reserved log without a chunk load");
			return 6;
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("cargo continuation step fixture unavailable", failure);
		} finally {
			if (cachedSteps != null) {
				if (oldSteps == null) cachedSteps.remove(origin.asLong()); else cachedSteps.put(origin.asLong(), oldSteps);
			}
			before.forEach((pos, state) -> level.setBlock(pos, state, 2));
		}
	}

	private static BlockPos emptyStorageFixturePos(ServerLevel level, BlockPos bell) {
		for (int y = 5; y <= 20; y++) {
			for (int dx = -6; dx <= 6; dx++) {
				for (int dz = -6; dz <= 6; dz++) {
					BlockPos pos = bell.offset(dx, y, dz);
					if (level.hasChunkAt(pos) && level.getBlockState(pos).isAir() && level.getBlockEntity(pos) == null) {
						return pos.immutable();
					}
				}
			}
		}
		throw new IllegalStateException("BUILD HAUL CHECK FAILED: no loaded air position for barrel fixture");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("BUILD HAUL CHECK FAILED: " + message);
	}
}
