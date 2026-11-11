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
