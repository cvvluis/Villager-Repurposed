package de.villagerplus.build;

import de.villagerplus.Config;
import de.villagerplus.village.VillageData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Focused checks for housing demand and persistent construction material requests. */
public final class BuilderChecks {
	private BuilderChecks() {}

	public static int run() {
		int checks = ArchitectRoleMarkerChecks.run();
		checks += FarmPlanSizingChecks.run();
		checks += PenBlueprintChecks.run();
		check(!Builder.shouldDepositPlanStone(1) && !Builder.shouldDepositPlanStone(11)
			&& Builder.shouldDepositPlanStone(12) && Builder.shouldDepositPlanStone(32),
			"a quarry worker keeps small cobblestone batches until the planned delivery buffer is reached"); checks++;
		check(Builder.HOUSE_CLEARANCE_RETRY_TICKS == 0L,
			"a successful one-block house-lot clearance does not add a 20-tick pause before the next work pulse"); checks++;
		var waitingVillage = new de.villagerplus.village.VillageRef(new net.minecraft.core.BlockPos(29_812_345, 72, -29_812_345), null, null);
		var otherVillage = new de.villagerplus.village.VillageRef(new net.minecraft.core.BlockPos(29_812_346, 72, -29_812_345), null, null);
		Builder.clearSpecialSiteSearchFailure(waitingVillage);
		Builder.clearSpecialSiteSearchFailure(otherVillage);
		try {
			check(!Builder.specialSiteSearchStalled(waitingVillage, Blueprints.GRANARY, 5000, true, 1),
				"an untried special site does not yield its priority"); checks++;
			Builder.noteSpecialSiteSearchFailure(waitingVillage, 1000);
			Builder.noteSpecialSiteSearchFailure(waitingVillage, 2000);
			check(!Builder.specialSiteSearchStalled(waitingVillage, Blueprints.GRANARY, 4599, true, 1)
				&& Builder.specialSiteSearchStalled(waitingVillage, Blueprints.GRANARY, 4600, true, 1),
				"a special site yields exactly at the stall boundary without resetting on retries"); checks++;
			check(!Builder.specialSiteSearchStalled(waitingVillage, null, 4600, true, 1)
				&& !Builder.specialSiteSearchStalled(waitingVillage, Blueprints.GRANARY, 4600, false, 1)
				&& !Builder.specialSiteSearchStalled(waitingVillage, Blueprints.GRANARY, 4600, true, 0)
				&& !Builder.specialSiteSearchStalled(otherVillage, Blueprints.GRANARY, 4600, true, 1),
				"housing priority needs a special plan, a bed deficit, homeless residents and this village's own failure"); checks++;
			Builder.clearSpecialSiteSearchFailure(waitingVillage);
			check(!Builder.specialSiteSearchStalled(waitingVillage, Blueprints.GRANARY, 9000, true, 1),
				"finding a site clears the stalled search state"); checks++;
		} finally {
			Builder.clearSpecialSiteSearchFailure(waitingVillage);
			Builder.clearSpecialSiteSearchFailure(otherVillage);
		}
		check(!Builder.shouldPreemptForSpecial(Blueprints.HOUSE, Blueprints.GRANARY)
			&& Builder.shouldPreemptForSpecial(Blueprints.DECOR, Blueprints.GRANARY)
			&& !Builder.shouldPreemptForSpecial(Blueprints.HOUSE, null),
			"a started house survives the pending milestone while optional decor still yields"); checks++;
		checks += parallelProjectScheduling();
		checks += constructionSupportScheduling();
		checks += projectStaffingRetry();
		checks += freightCarrierStaysWithProject();
		checks += abortKeepsProjectMaterials();
		checks += requestedPublicStockTransfer();
		checks += catalogHouseVariantCompatibility();
		check(Builder.shouldPrioritizeEarlyPublicStorage(2, true, Blueprints.TOOLSHED),
			"the first public shed can start at two residents after the granary, before later milestones and without a full-chest trigger"); checks++;
		check(!Builder.shouldPrioritizeEarlyPublicStorage(1, true, Blueprints.TOOLSHED),
			"the early first-shed priority waits until the village has two residents"); checks++;
		check(!Builder.shouldPrioritizeEarlyPublicStorage(4, false, Blueprints.TOOLSHED),
			"the early shed priority does not duplicate existing public storage"); checks++;
		check(Builder.shouldStartEarlyPublicStorage(4, true, Blueprints.TOOLSHED, false, 0, false)
			&& !Builder.shouldStartEarlyPublicStorage(4, true, Blueprints.TOOLSHED, false, 0, true),
			"the first shed is eligible when absent but an already active storage project blocks a duplicate"); checks++;
		check(Builder.firstPublicStorageMissing(false, true, false)
			&& Builder.firstPublicStorageMissing(true, true, false)
			&& !Builder.firstPublicStorageMissing(true, true, true)
			&& !Builder.firstPublicStorageMissing(true, false, false),
			"a verified public chest prevents duplicate first-shed priority, and an unloaded saved storage location is treated as unknown"); checks++;
		check(!Builder.shouldPrioritizeEarlyPublicStorage(4, true, Blueprints.GRANARY),
			"the first granary remains ahead of early public storage"); checks++;
		check(Builder.shouldPrioritizeEarlyPublicStorage(2, true, Blueprints.GRANARY, true, 1)
			&& Builder.shouldPrioritizeEarlyPublicStorage(2, true, Blueprints.GRANARY, true, 2)
			&& !Builder.shouldPrioritizeEarlyPublicStorage(2, true, Blueprints.GRANARY, true, 3)
			&& !Builder.shouldPrioritizeEarlyPublicStorage(2, true, Blueprints.GRANARY, false, 0),
			"the first shed can use an open slot beside a saved granary, but cannot exceed the three-project cap"); checks++;
		long storageSeed = 918_273L;
		long firstStorageSeed = Builder.civicDesignSeed(Blueprints.STORAGE, true, storageSeed, Architect.SizeTier.MULTI_STOREY);
		long laterStorageSeed = Builder.civicDesignSeed(Blueprints.STORAGE, false, storageSeed, Architect.SizeTier.MULTI_STOREY);
		long otherCivicSeed = Builder.civicDesignSeed(Blueprints.TOOLSHED, true, storageSeed, Architect.SizeTier.MULTI_STOREY);
		Architect.Design firstStorage = Builder.designForPlan(Blueprints.STORAGE, Builder.newPlanVariant(Blueprints.STORAGE, firstStorageSeed));
		Architect.Design laterStorage = Builder.designForPlan(Blueprints.STORAGE, Builder.newPlanVariant(Blueprints.STORAGE, laterStorageSeed));
		Palette shedPalette = new Palette(Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE,
			Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS, Blocks.OAK_PLANKS,
			Blocks.OAK_DOOR, Blocks.OAK_FENCE);
		List<Step> firstStorageSteps = Architect.build(firstStorage, shedPalette, BlockPos.ZERO, net.minecraft.core.Direction.NORTH);
		List<Step> laterStorageSteps = Architect.build(laterStorage, shedPalette, BlockPos.ZERO, net.minecraft.core.Direction.NORTH);
		int firstStoragePaid = firstStorageSteps.stream().filter(step -> !step.free() && !step.optional() && step.linkedTo() == null)
			.mapToInt(Step::cost).sum();
		int laterStoragePaid = laterStorageSteps.stream().filter(step -> !step.free() && !step.optional() && step.linkedTo() == null)
			.mapToInt(Step::cost).sum();
		Step firstStorageChest = Builder.firstStorageChestStep(firstStorageSteps, BlockPos.ZERO,
			net.minecraft.core.Direction.NORTH, firstStorage);
		check(firstStorageChest != null && firstStorageChest.item() == Items.CHEST && firstStorageChest.cost() == 1
			&& !firstStorageChest.optional()
			&& firstStorageChest.make().apply(Blocks.CHEST).getValue(net.minecraft.world.level.block.ChestBlock.FACING)
				== net.minecraft.core.Direction.NORTH
			&& firstStorageSteps.stream().anyMatch(step -> step.pos().equals(firstStorageChest.pos().below()))
			&& firstStorageSteps.stream().noneMatch(step -> step.pos().equals(firstStorageChest.pos())
				|| step.pos().equals(firstStorageChest.pos().above())),
			"the compact first shed includes one genuinely paid chest at the inside back wall, facing the open room"); checks++;
		check(Architect.sizeTier(firstStorageSeed) == Architect.SizeTier.COMPACT
			&& firstStorage.hw() == 3 && firstStorage.hd() == 2 && firstStorage.wallH() == 3
			&& firstStorageSteps.size() < laterStorageSteps.size()
			&& firstStoragePaid > 0 && firstStoragePaid < laterStoragePaid
			&& firstStorageSteps.stream().filter(step -> !step.free() && !step.optional() && step.linkedTo() == null)
				.allMatch(step -> step.cost() > 0)
			&& Architect.sizeTier(otherCivicSeed) == Architect.SizeTier.MULTI_STOREY,
			"the first public shed uses a smaller paid footprint and wall while later storage and other civic size tiers remain unchanged"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(null, Blueprints.GRANARY, true),
			"a full village chest does not delay the first granary with a capacity-only shed"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(Blueprints.GRANARY, Blueprints.GRANARY, true),
			"a full village chest does not abort a granary that is ready to place its first block"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(Blueprints.TOOLSHED, Blueprints.SCHOOL, true),
			"storage pressure does not evict an active civic milestone while the next one is queued"); checks++;
		check(Builder.shouldExpandStorageUnderPressure(null, null, true),
			"storage pressure still starts a capacity shed when no civic milestone is pending"); checks++;
		check(Builder.shouldExpandStorageUnderPressure(null, Blueprints.TOOLSHED, true, true),
			"a first public shed answers real storage pressure ahead of the later civic chain"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(null, Blueprints.TOOLSHED, true, true, true),
			"plan choice keeps urgently needed housing ahead of first-shed pressure"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(Blueprints.TOOLSHED, Blueprints.SCHOOL, true, true),
			"first-shed capacity pressure never aborts an already active toolshed plan"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(Blueprints.HOUSE, Blueprints.TOOLSHED, true, true, true),
			"first-shed pressure does not abort an active house while housing is still needed"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(Blueprints.HOUSE, Blueprints.TOOLSHED, true, true, false)
			&& !Builder.shouldExpandStorageUnderPressure(Blueprints.HOUSE, null, true, false, false),
			"an active house is never aborted or reset for either the first shed or later storage pressure"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(null, Blueprints.GRANARY, true, true),
			"first-storage pressure still waits for the first granary"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(null, Blueprints.GRANARY, true, true, true),
			"housing demand does not change the first granary's priority over storage pressure"); checks++;
		check(Builder.needsHouse(2, 2, 2)
			&& Builder.nextFarmerPlanType(2, 0, 0, true, false, false, false) == null
			&& Builder.nextFarmerPlanType(3, 0, 0, true, false, false, false) == null,
			"the first paid field waits for a public materials store when the village has no usable storage yet"); checks++;
		check(Blueprints.FARM.equals(Builder.nextFarmerPlanType(2, 0, 0, true, false, false, true)),
			"the first field can start from public village storage before the granary is finished, even with a housing deficit"); checks++;
		check(!Builder.shouldHoldForSpecial(false, true) && Builder.shouldHoldForSpecial(true, true)
			&& !Builder.shouldHoldForSpecial(false, false),
			"unrelated civic milestones never hold the farmer's work cycle while the mason can still wait for them"); checks++;
		check(Builder.nextFarmerPlanType(1, 1, 1, true, false, false, true) == null
			&& Blueprints.FARM.equals(Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION, 0, 0, true, false, false, true))
			&& Blueprints.PEN.equals(Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION, 0, 1, true, true, false, true))
			&& Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION, 0, 1, true, true, true, true) == null
			&& Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION, 0, 1, true, true, false, false) == null
			&& Blueprints.PEN.equals(Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION, 1, 1, true, false, false, true))
			&& Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION, 1, 2, true, true, false, true) == null
			&& Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION, 1, 1, true, false, true, true) == null,
			"the first field can queue one pen alongside it once public storage exists; no duplicate pen or pre-storage plan is queued, and a completed field still triggers its pen"); checks++;
		check(Blueprints.FARM.equals(Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION,
			0, 0, true, false, false, true, false))
			&& Blueprints.PEN.equals(Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION,
				0, 0, true, false, false, true, true))
			&& Builder.nextFarmerPlanType(Builder.MIN_INITIAL_PEN_POPULATION,
				0, 0, true, false, false, false, true) == null
			&& Builder.nextFarmerPlanType(1, 0, 0, true, false, false, true, true).equals(Blueprints.FARM),
			"a real eligible herd unlocks the paid first pen before a stalled starter field, but still requires public storage and the minimum population"); checks++;
		check(Builder.nextFarmerBuildAt(Blueprints.FARM, 1, true, 100) == 100
			&& Builder.nextFarmerBuildAt(Blueprints.FARM, 2, true, 100) == 100 + Config.BUILD_COOLDOWN
			&& Builder.nextFarmerBuildAt(Blueprints.PEN, 1, false, 100) == 100 + Config.BUILD_COOLDOWN,
			"the first completed field makes its waiting pen immediately eligible while later farms keep their cooldown"); checks++;
		SimpleContainer farmStock = new SimpleContainer(9);
		farmStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 7));
		check(Builder.farmPlanVariant(0, farmStock) < 0,
			"a starter field waits until all eight crop blocks can be paid"); checks++;
		farmStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		int starterFarmVariant = Builder.farmPlanVariant(0, farmStock);
		long starterSeedSteps = Blueprints.farm(BlockPos.ZERO, starterFarmVariant).stream()
			.filter(step -> Items.WHEAT_SEEDS.equals(step.item())).count();
		check(starterFarmVariant == 10 && starterFarmVariant / 10 == 1 && starterSeedSteps == 8,
			"eight real wheat seeds select a fully payable irrigated 3-by-3 starter plot"); checks++;
		SimpleContainer betterSeededFirstFarm = new SimpleContainer(9);
		betterSeededFirstFarm.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 24));
		check(Builder.farmPlanVariant(0, betterSeededFirstFarm) == 20
			&& Blueprints.farm(BlockPos.ZERO, 20).stream().filter(step -> Items.WHEAT_SEEDS.equals(step.item())).count() == 24,
			"the first village farm uses a fully funded 5-by-5 field when all 24 crop seeds are available"); checks++;
		betterSeededFirstFarm.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 48));
		SimpleContainer firstFarmSiteStock = new SimpleContainer(9);
		check(Builder.farmPlanVariant(0, betterSeededFirstFarm) == 20
			&& Builder.stageFarmSeeds(betterSeededFirstFarm, firstFarmSiteStock, Blueprints.farm(BlockPos.ZERO, 20), 0)
			&& count(betterSeededFirstFarm, Items.WHEAT_SEEDS) == 24
			&& count(firstFarmSiteStock, Items.WHEAT_SEEDS) == 24,
			"the first farm stays capped at 5-by-5, reserving 24 of 48 seeds and leaving 24 for later"); checks++;
		List<Step> starterFarmSteps = Blueprints.farm(BlockPos.ZERO, starterFarmVariant);
		SimpleContainer publicFarmStock = new SimpleContainer(9);
		publicFarmStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
		SimpleContainer starterFarmSiteStock = new SimpleContainer(9);
		check(Builder.stageFarmSeeds(publicFarmStock, starterFarmSiteStock, starterFarmSteps, 0)
			&& count(publicFarmStock, Items.WHEAT_SEEDS) == 0
			&& count(starterFarmSiteStock, Items.WHEAT_SEEDS) == 8,
			"a queued starter field reserves all eight real seeds in its persistent project stock"); checks++;
		SimpleContainer shortPublicFarmStock = new SimpleContainer(9);
		shortPublicFarmStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 7));
		SimpleContainer shortFarmSiteStock = new SimpleContainer(9);
		check(!Builder.stageFarmSeeds(shortPublicFarmStock, shortFarmSiteStock, starterFarmSteps, 0)
			&& count(shortPublicFarmStock, Items.WHEAT_SEEDS) == 7
			&& count(shortFarmSiteStock, Items.WHEAT_SEEDS) == 0,
			"seed staging leaves public stock untouched unless the complete remaining crop payment is available"); checks++;
		SimpleContainer resumePublicFarmStock = new SimpleContainer(9);
		resumePublicFarmStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 2));
		SimpleContainer resumeFarmSiteStock = new SimpleContainer(9);
		resumeFarmSiteStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 6));
		check(Builder.stageFarmSeeds(resumePublicFarmStock, resumeFarmSiteStock, starterFarmSteps, 0)
			&& count(resumePublicFarmStock, Items.WHEAT_SEEDS) == 0
			&& count(resumeFarmSiteStock, Items.WHEAT_SEEDS) == 8,
			"a resumed field stages only the missing seed balance into its existing project stock"); checks++;
		SimpleContainer largeSeedStock = new SimpleContainer(9);
		largeSeedStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
		largeSeedStock.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 53));
		int largeFarmVariant = Builder.farmPlanVariant(4, largeSeedStock);
		SimpleContainer largeFarmSiteStock = new SimpleContainer(9);
		check(largeFarmVariant == 50
			&& Builder.stageFarmSeeds(largeSeedStock, largeFarmSiteStock, Blueprints.farm(BlockPos.ZERO, largeFarmVariant), 0)
			&& count(largeSeedStock, Items.WHEAT_SEEDS) == 0
			&& count(largeFarmSiteStock, Items.WHEAT_SEEDS) == 117,
			"larger crop plans reserve every seed across legal project-stock stacks without truncation"); checks++;
		Step optionalWheatCrop = starterFarmSteps.stream().filter(step -> Items.WHEAT_SEEDS.equals(step.item())).findFirst().orElseThrow();
		check(Builder.farmStepRequiresPayment(Blueprints.FARM, optionalWheatCrop)
			&& !Builder.farmStepRequiresPayment(Blueprints.HOUSE, optionalWheatCrop),
			"farm crop steps cannot be skipped as free decoration when their reserved seeds are missing"); checks++;
		check(Builder.farmPlanVariant(1, farmStock) == 10,
			"later farm expansion can use a paid starter-size field while larger seed stock accumulates"); checks++;
		farmStock.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 48));
		check(Builder.farmPlanVariant(1, farmStock) == 30,
			"later farm plots grow to 7-by-7 only when all 48 crop steps are payable"); checks++;
		check(Builder.nextFarmerPlanType(2, 0, 1, false, true, false, true) == null,
			"an active farm project cannot reserve the same unspent seeds for a duplicate farm"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(Blueprints.TOOLSHED, Blueprints.SCHOOL, true, false),
			"capacity-only extra sheds still wait behind the early civic chain"); checks++;
		check(!Builder.shouldExpandStorageUnderPressure(null, null, false),
			"storage expansion requires an actual capacity shortage"); checks++;
		int cap = de.villagerplus.Config.MAX_VILLAGERS;
		check(Builder.needsHouse(cap, 0, cap - 1), "a capped village replaces housing when existing residents outnumber beds"); checks++;
		check(Builder.needsHouse(cap, 1, cap), "a capped village replaces housing when a resident lacks HOME and no spare bed exists"); checks++;
		check(!Builder.needsHouse(cap, 0, cap), "a capped village does not add a reserve house when every resident has a bed"); checks++;
		check(!Builder.needsHouse(cap, 1, cap + 1), "spare bed capacity prevents unnecessary construction even when a resident lacks HOME memory"); checks++;
		check(Builder.needsHouse(cap - 1, 0, cap - 1), "below the cap a full bed count still maintains the housing reserve"); checks++;
		check(!Builder.needsHouse(cap - 1, 0, cap), "extra bed capacity prevents unnecessary reserve construction"); checks++;
		check(!Builder.needsHouse(0, 0, 0), "an empty village does not start a housing plan"); checks++;

		List<Holder<Item>> doors = java.util.stream.StreamSupport.stream(
			BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.WOODEN_DOORS).spliterator(), false).toList();
		if (doors.isEmpty()) throw new IllegalStateException("Loaded item tag minecraft:doors is empty");
		Item firstDoor = doors.getFirst().value();
		VillageData data = new VillageData();
		Step doorStep = new Step(BlockPos.ZERO, null, ItemTags.WOODEN_DOORS, 1, false, null, b -> Blocks.OAK_DOOR.defaultBlockState());
		Builder.addWanted(data, doorStep, new SimpleContainer(9));
		String expected = BuiltInRegistries.ITEM.getKey(firstDoor) + "#16";
		check(data.wanted.equals(List.of(expected)), "tagged door requirement is requested: " + data.wanted);
		return checks + 1;
	}

	private static int requestedPublicStockTransfer() {
		int checks = 0;
		SimpleContainer specialtyStock = new SimpleContainer(8);
		specialtyStock.setItem(0, new net.minecraft.world.item.ItemStack(Items.BIRCH_PLANKS, 20));
		for (int slot = 1; slot <= 4; slot++) specialtyStock.setItem(slot, new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 64));
		specialtyStock.setItem(5, new net.minecraft.world.item.ItemStack(Items.COBBLESTONE, 32));
		specialtyStock.setItem(6, new net.minecraft.world.item.ItemStack(Items.CRAFTING_TABLE, 1));
		specialtyStock.setItem(7, new net.minecraft.world.item.ItemStack(Items.SCAFFOLDING, 2));
		SimpleContainer villageDepot = new SimpleContainer(4);
		villageDepot.setItem(0, new net.minecraft.world.item.ItemStack(Items.BIRCH_PLANKS, 5));
		List<String> demand = List.of("minecraft:birch_planks#16", "minecraft:cobblestone#23",
			"minecraft:crafting_table#1", "minecraft:scaffolding#2");
		int birch = Builder.moveRequestedPublicStock(specialtyStock, villageDepot, demand, true, true);
		check(birch == 11 && count(specialtyStock, Items.BIRCH_PLANKS) == 9
			&& count(villageDepot, Items.BIRCH_PLANKS) == 16,
			"public stock staging fills only the requested birch plank deficit after counting the village depot"); checks++;
		int cobble = Builder.moveRequestedPublicStock(specialtyStock, villageDepot, demand, true, true);
		check(cobble == 23 && count(specialtyStock, Items.COBBLESTONE) == 265
			&& count(villageDepot, Items.COBBLESTONE) == 23,
			"a large public cobblestone stack stages only the amount the project still needs"); checks++;
		int workbench = Builder.moveRequestedPublicStock(specialtyStock, villageDepot, demand, true, true);
		int scaffold = Builder.moveRequestedPublicStock(specialtyStock, villageDepot, demand, true, true);
		check(workbench == 1 && scaffold == 2 && count(specialtyStock, Items.CRAFTING_TABLE) == 0
			&& count(specialtyStock, Items.SCAFFOLDING) == 0,
			"setup materials are forwarded as real inventory only up to each remaining project request"); checks++;
		int privateMove = Builder.moveRequestedPublicStock(specialtyStock, villageDepot, demand, false, true);
		int privateDestinationMove = Builder.moveRequestedPublicStock(specialtyStock, villageDepot, demand, true, false);
		check(privateMove == 0 && privateDestinationMove == 0 && count(specialtyStock, Items.COBBLESTONE) == 265,
			"non-public source or destination inventory is never staged"); checks++;
		return checks;
	}

	private static int catalogHouseVariantCompatibility() {
		int checks = 0;
		long seed = Architect.withSizeTier(918_273L, Architect.SizeTier.COMPACT);
		int catalogVariant = Builder.newPlanVariant(Blueprints.HOUSE, seed);
		Architect.Design catalogDesign = HouseDesignCatalog.designForPlanVariant(catalogVariant);
		int mirroredTemplate = 1;
		int oldSubstitutedGenerated = 2;
		check((catalogVariant & 1) != 0 && (catalogVariant & 2) == 0
			&& HouseDesignCatalog.usesCatalog(catalogVariant, false)
			&& !HouseDesignCatalog.usesCatalog(catalogVariant, true)
			&& Builder.designForPlan(Blueprints.HOUSE, catalogVariant).equals(catalogDesign),
			"new source-free houses use the bit-0 catalog marker while saved template plans keep that bit as mirror state"); checks++;
		check(Builder.siteHalf(Blueprints.HOUSE, catalogVariant, catalogDesign) == HouseDesignCatalog.siteHalf(catalogDesign)
			&& Builder.siteHeadroom(Blueprints.HOUSE, catalogVariant, catalogDesign) == HouseDesignCatalog.siteHeadroom(catalogDesign),
			"house placement reserves the catalog's full porch/path footprint and actual roof clearance"); checks++;
		check(Builder.shouldPreferPreparedExpansionParcel(Blueprints.HOUSE, catalogVariant, false, 0, true, true)
			&& Builder.shouldPreferPreparedExpansionParcel(Blueprints.HOUSE, catalogVariant, false, 1, true, true)
			&& Builder.shouldPreferPreparedExpansionParcel(Blueprints.HOUSE, catalogVariant, false, 4, true, true)
			&& !Builder.shouldPreferPreparedExpansionParcel(Blueprints.HOUSE, catalogVariant, false, -1, true, true)
			&& !Builder.shouldPreferPreparedExpansionParcel(Blueprints.HOUSE, catalogVariant, false, 4, false, true)
			&& !Builder.shouldPreferPreparedExpansionParcel(Blueprints.HOUSE, catalogVariant, true, 4, true, true)
			&& !Builder.shouldPreferPreparedExpansionParcel(Blueprints.HOUSE, oldSubstitutedGenerated, false, 4, true, true)
			&& !Builder.shouldPreferPreparedExpansionParcel(Blueprints.TOOLSHED, catalogVariant, false, 4, true, true)
			&& !Builder.shouldPreferPreparedExpansionParcel(Blueprints.HOUSE, catalogVariant, false, 4, true, false),
			"any new source-free catalog house still needed for beds can use a ready parcel, including later houses; legacy and unready plans are rejected"); checks++;
		BlockPos bell = BlockPos.ZERO;
		check(Builder.houseCandidateWithinSearchRadius(bell, new BlockPos(0, 64, 18), 18, 88)
			&& !Builder.houseCandidateWithinSearchRadius(bell, new BlockPos(0, 64, 17), 18, 88)
			&& !Builder.houseCandidateWithinSearchRadius(bell, new BlockPos(0, 64, 89), 18, 88),
			"a prepared house candidate stays inside the existing outward-growth search window"); checks++;
		check(Builder.needsNaturalHousePreparation(Blueprints.HOUSE, catalogVariant, false)
			&& !Builder.needsNaturalHousePreparation(Blueprints.HOUSE, mirroredTemplate, true)
			&& !Builder.needsNaturalHousePreparation(Blueprints.HOUSE, oldSubstitutedGenerated, false)
			&& !Builder.needsNaturalHousePreparation(Blueprints.SCHOOL, catalogVariant, false),
			"natural residential grading and safe plant clearing apply only to new source-free catalog houses"); checks++;
		int defaultRadius = Builder.houseExpansionMinRadius(0, 0, 88);
		int existingEdgeRadius = Builder.houseExpansionMinRadius(4, 20, 88);
		int denseVillageRadius = Builder.houseExpansionMinRadius(20, 35, 88);
		check(defaultRadius == 18 && existingEdgeRadius > defaultRadius && denseVillageRadius > existingEdgeRadius,
			"new villages start beyond the old 10-block ring and expand with saved houses or the HOME-POI edge"); checks++;
		check(Builder.houseExpansionMinRadius(100, 100, 88) == 88,
			"house growth never expands the minimum beyond the configured 88-block search limit"); checks++;
		check(Builder.designForPlan(Blueprints.HOUSE, mirroredTemplate, true)
			.equals(Architect.designForPlan(Architect.Kind.HOUSE, mirroredTemplate))
			&& Builder.designForPlan(Blueprints.HOUSE, oldSubstitutedGenerated)
			.equals(Architect.designForPlan(Architect.Kind.HOUSE, oldSubstitutedGenerated))
			&& (((Builder.newPlanVariant(Blueprints.HOUSE, seed) | 2) >>> 2) == (catalogVariant >>> 2)),
			"legacy template mirrors and source-free substitution variants remain intact; fallback keeps the selected catalog design"); checks++;
		return checks;
	}

	private static int parallelProjectScheduling() {
		int checks = 0;
		UUID firstMason = UUID.randomUUID();
		UUID secondMason = UUID.randomUUID();
		UUID farmer = UUID.randomUUID();
		List<VillageData.BuildProject> projects = List.of(
			buildProject(Blueprints.SCHOOL, new BlockPos(10, 64, 10), firstMason, false),
			buildProject(Blueprints.TOOLSHED, new BlockPos(30, 64, 10), secondMason, false),
			buildProject(Blueprints.FARM, new BlockPos(50, 64, 10), farmer, false),
			buildProject(Blueprints.SCHOOL, new BlockPos(70, 64, 10), null, false),
			buildProject(Blueprints.FARM, new BlockPos(90, 64, 10), null, false),
			buildProject("", new BlockPos(110, 64, 10), firstMason, true));
		check(Builder.MAX_ACTIVE_PROJECTS == 3
			&& Builder.hasActiveProjectSlot(0) && Builder.hasActiveProjectSlot(1) && Builder.hasActiveProjectSlot(2)
			&& !Builder.hasActiveProjectSlot(3) && !Builder.hasActiveProjectSlot(4) && !Builder.hasActiveProjectSlot(-1),
			"the live-project scheduler admits up to three cursors and rejects invalid or over-capacity counts"); checks++;
		check(Builder.ownedProjectForWorker(projects, firstMason) == projects.getFirst()
			&& Builder.ownedProjectForWorker(projects, secondMason) == projects.get(1)
			&& Builder.ownedProjectForWorker(projects, farmer) == projects.get(2)
			&& Builder.ownedProjectForWorker(projects, UUID.randomUUID()) == null,
			"each resident resumes only their own independent cursor; cleanup records are not live assignments"); checks++;
		check(Builder.unownedProjectForWorker(projects, true) == projects.get(3)
			&& Builder.unownedProjectForWorker(projects, false) == projects.get(4),
			"ownerless building and farm cursors remain in the Mason and Farmer role lanes"); checks++;
		return checks;
	}

	private static int constructionSupportScheduling() {
		int checks = 0;
		check(Builder.shouldStaffPreparedProject(true, false)
			&& Builder.shouldStaffPreparedProject(false, true)
			&& !Builder.shouldStaffPreparedProject(false, false),
			"prepared crews remain available when a live adult owner is temporarily unavailable, but not when its owner is absent"); checks++;
		check(!Builder.shouldOfferConstructionSupport(true, true, true),
			"a resident keeps their profession's priority task when it performs work"); checks++;
		check(Builder.shouldOfferConstructionSupport(false, true, true),
			"a prepared construction crew can use the gap after its first-choice profession task is idle"); checks++;
		check(!Builder.shouldOfferConstructionSupport(false, false, true)
			&& !Builder.shouldOfferConstructionSupport(false, true, false),
			"outdoor construction support needs both safe weather and a ready crew project"); checks++;
		UUID owner = UUID.randomUUID();
		BlockPos origin = new BlockPos(32, 64, -16);
		VillageData.BuildProject setup = new VillageData.BuildProject(Blueprints.SCHOOL, origin, 0, 0, 0,
			Optional.empty(), 120L, Optional.of(owner), 0L, List.of(), VillageData.ProjectHaul.empty(),
			Optional.empty(), Optional.of(new ConstructionSite(origin, List.of(), List.of(4, 12), false, List.of())),
			Optional.empty(), false, false);
		check(Builder.supportsConstructionHaul(setup) && setup.owner().filter(owner::equals).isPresent()
			&& setup.construction().filter(site -> !site.prepared()).isPresent(),
			"a fixed-owner project accepts a material hauler during paid setup while its persisted cursor and owner remain intact"); checks++;
		VillageData.BuildProject noSetup = new VillageData.BuildProject(Blueprints.SCHOOL, origin.offset(20, 0, 0), 0, 0, 0,
			Optional.empty(), 120L, Optional.of(owner), 0L, List.of(), VillageData.ProjectHaul.empty(),
			Optional.empty(), Optional.empty(), Optional.empty(), false, false);
		check(!Builder.supportsConstructionHaul(noSetup),
			"haul-only helper dispatch requires the project's matching persisted construction site"); checks++;
		return checks;
	}

	private static int projectStaffingRetry() {
		int checks = 0;
		check(Builder.shouldRetryProjectStaffing(true, false, 400, 400)
			&& !Builder.shouldRetryProjectStaffing(true, false, 399, 400)
			&& !Builder.shouldRetryProjectStaffing(false, false, 400, 400)
			&& !Builder.shouldRetryProjectStaffing(true, true, 400, 400),
			"owner recovery waits for its bounded retry, skips inactive cursors, and leaves an active haul carrier assigned"); checks++;
		check(Builder.shouldAssignProjectWorker(true, true, false)
			&& !Builder.shouldAssignProjectWorker(false, true, false)
			&& !Builder.shouldAssignProjectWorker(true, false, false)
			&& !Builder.shouldAssignProjectWorker(true, true, true),
			"only an eligible reachable villager without another project assignment can replace a missing builder"); checks++;
		VillageData unstaffedBuilding = new VillageData();
		unstaffedBuilding.putBuildProject(buildProject(Blueprints.SCHOOL, new BlockPos(20, 64, 20), null, false));
		VillageData farmerOnly = new VillageData();
		farmerOnly.putBuildProject(buildProject(Blueprints.FARM, new BlockPos(40, 64, 20), null, false));
		check(Builder.hasPreparedCrewProject(unstaffedBuilding) && !Builder.hasPreparedCrewProject(farmerOnly),
			"idle adult job hooks can wake an unowned building cursor while farmer plans remain in their role lane"); checks++;
		return checks;
	}

	private static int freightCarrierStaysWithProject() {
		int checks = 0;
		UUID owner = UUID.randomUUID();
		UUID carrier = UUID.randomUUID();
		BlockPos origin = new BlockPos(72, 64, -44);
		String cargo = carrier + "|minecraft:oak_planks|5|" + new BlockPos(65, 64, -44).asLong();
		VillageData.BuildProject carrying = new VillageData.BuildProject(Blueprints.SCHOOL, origin, 0, 3, 0,
			Optional.empty(), 800L, Optional.of(owner), 0L, List.of(),
			new VillageData.ProjectHaul(List.of(), List.of(cargo)), Optional.empty(), Optional.empty(),
			Optional.of(new VillageData.PlanBounds(68, 76, -48, -40)), false, false);
		Builder.FreightReservations freight = Builder.freightReservations(List.of(carrying));
		Map<UUID, Builder.BuildCrewAssignment> assignments = new HashMap<>();
		Map<Long, Integer> helperCounts = new HashMap<>();
		boolean assigned = Builder.assignFreightCarrierToCrew(carrier, carrying, true, assignments, helperCounts);
		Builder.BuildCrewAssignment assignment = assignments.get(carrier);
		check(freight.carriers().contains(carrier)
			&& freight.projectOrigins().getOrDefault(carrier, Long.MIN_VALUE) == origin.asLong()
			&& assigned && assignment != null && assignment.helper() && assignment.origin().equals(origin)
			&& assignment.standSlot() == 1 && helperCounts.get(carrying.id()) == 1,
			"a real in-transit manifest keeps its carrier assigned to the exact project and reserves a helper slot"); checks++;
		check(!Builder.isAvailableBuildHelper(carrier, Set.of(owner), freight.carriers())
			&& Builder.isAvailableBuildHelper(carrier, Set.of(owner), Set.of()),
			"the carrier cannot be diverted while cargo is reserved and returns to ordinary crew selection after delivery clears the manifest"); checks++;

		VillageData.BuildProject other = new VillageData.BuildProject(Blueprints.TOOLSHED, origin.offset(30, 0, 0), 0, 0, 0,
			Optional.empty(), 800L, Optional.of(UUID.randomUUID()), 0L, List.of(),
			new VillageData.ProjectHaul(List.of(), List.of(cargo)), Optional.empty(), Optional.empty(),
			Optional.of(new VillageData.PlanBounds(98, 106, -48, -40)), false, false);
		Builder.FreightReservations ambiguous = Builder.freightReservations(List.of(carrying, other));
		check(ambiguous.carriers().contains(carrier) && !ambiguous.projectOrigins().containsKey(carrier),
			"conflicting persisted manifests reserve the villager without routing cargo to the wrong project"); checks++;
		return checks;
	}

	private static VillageData.BuildProject buildProject(String type, BlockPos origin, UUID owner, boolean cleanupOnly) {
		return new VillageData.BuildProject(type, origin, 0, 0, 0, Optional.empty(), 0L, Optional.ofNullable(owner), 0L,
			List.of(), VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(),
			Optional.of(new VillageData.PlanBounds(origin.getX() - 2, origin.getX() + 2,
				origin.getZ() - 2, origin.getZ() + 2)), false, cleanupOnly);
	}

	private static int count(SimpleContainer container, Item item) {
		int total = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
		}
		return total;
	}

	private static int abortKeepsProjectMaterials() {
		int checks = 0;
		VillageData data = new VillageData();
		BlockPos firstOrigin = new BlockPos(120, 64, -80);
		BlockPos secondOrigin = new BlockPos(160, 64, -80);
		UUID firstOwner = UUID.randomUUID();
		UUID secondOwner = UUID.randomUUID();
		String cargo = UUID.randomUUID() + "|minecraft:oak_planks|5|" + new BlockPos(100, 64, -80).asLong();
		var trackedSite = new ConstructionSite(firstOrigin,
			List.of(new ConstructionSite.TemporaryBlock(firstOrigin.east(), false),
				new ConstructionSite.TemporaryBlock(firstOrigin.east(2), true)), List.of(4, 12), true, List.of("oak"));
		VillageData.BuildProject first = new VillageData.BuildProject(Blueprints.SCHOOL, firstOrigin, 1, 17, 5,
			Optional.of(firstOrigin.west()), 700L, Optional.of(firstOwner), 0L, List.of("minecraft:oak_planks#9"),
			new VillageData.ProjectHaul(List.of(new net.minecraft.world.item.ItemStack(Items.OAK_PLANKS, 7),
				new net.minecraft.world.item.ItemStack(Items.SCAFFOLDING, 2)), List.of(cargo)), Optional.empty(),
			Optional.of(trackedSite), Optional.of(new VillageData.PlanBounds(110, 130, -90, -70)), false, false);
		VillageData.BuildProject second = new VillageData.BuildProject(Blueprints.TOOLSHED, secondOrigin, 3, 4, 2,
			Optional.empty(), 650L, Optional.of(secondOwner), 0L, List.of("minecraft:cobblestone#6"),
			VillageData.ProjectHaul.empty(), Optional.empty(), Optional.empty(),
			Optional.of(new VillageData.PlanBounds(150, 170, -90, -70)), false, false);
		data.putBuildProject(first);
		data.putBuildProject(second);
		data.syncPrimaryProjectView();
		VillageData.BuildProject cleanup = Builder.cleanupRecordForAbort(data, trackedSite, 900L);
		check(cleanup != null && cleanup.cleanupOnly() && cleanup.origin().equals(firstOrigin)
			&& cleanup.owner().equals(Optional.of(firstOwner)) && cleanup.bounds().equals(first.bounds())
			&& cleanup.construction().orElseThrow().temporary().equals(trackedSite.temporary())
			&& cleanup.cargoManifest().equals(List.of(cargo)),
			"abort snapshot retains the project owner, footprint, site blocks, and exact carrier manifest"); checks++;
		check(cleanup != null && cleanup.siteStock().stream().mapToInt(stack -> stack.getCount()).sum() == 9
			&& first.siteStock().stream().mapToInt(stack -> stack.getCount()).sum() == 9,
			"abort snapshot conserves every real site-stock item"); checks++;
		data.clearPlan();
		if (cleanup != null) data.putBuildProject(cleanup);
		data.syncPrimaryProjectView();
		check(data.activeBuildProjectCount() == 1 && data.buildProject(firstOrigin).filter(VillageData.BuildProject::cleanupOnly).isPresent()
			&& data.buildProject(secondOrigin).filter(VillageData.BuildProject::active).isPresent()
			&& data.planOrigin.equals(Optional.of(secondOrigin)) && data.planStep == second.step(),
			"aborting one of two projects preserves the second independent cursor while scheduling cleanup"); checks++;
		return checks;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("BUILDER CHECK FAILED: " + message);
	}
}
