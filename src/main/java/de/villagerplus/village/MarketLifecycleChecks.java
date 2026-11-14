package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.Livestock;
import de.villagerplus.build.Blueprints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** In-world checks for bounded market inventory, owner payment, and atomic failure. */
public final class MarketLifecycleChecks {
	private static final java.util.Set<ChunkPos> PENDING_ENTITY_TICK_TICKETS = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private MarketLifecycleChecks() { }

	public static int run(ServerLevel level, VillageRef ref, BlockPos origin) {
		BlockPos feedPen = selectMarketFeedPen(level, ref, origin);
		ChunkPos feedChunk = new ChunkPos(feedPen.getX() >> 4, feedPen.getZ() >> 4);
		if (!level.isPositionEntityTicking(feedPen)) {
			boolean alreadyForced = level.getChunkSource().getForceLoadedChunks().contains(feedChunk.pack());
			if (!alreadyForced) {
				if (!level.setChunkForced(feedChunk.x(), feedChunk.z(), true)) {
					throw new IllegalStateException("Market feed fixture could not force-load its pen chunk " + feedChunk);
				}
				PENDING_ENTITY_TICK_TICKETS.add(feedChunk);
			}
			level.getChunkAt(feedPen);
			level.waitForEntities(feedChunk, 0);
			if (!level.isPositionEntityTicking(feedPen)) return -1;
		}
		boolean ownsFeedChunkTicket = PENDING_ENTITY_TICK_TICKETS.contains(feedChunk);
		Container communal = ref.chest(level);
		if (communal == null) throw new IllegalStateException("Market checks need the village chest");
		List<ItemStack> communalBefore = copy(communal);
		MarketData marketBefore = Market.of(ref);
		long treasuryBefore = Network.treasuryEmeralds(ref);
		Map<String, BlockPos> oldSpecials = ref.bellEntity().getAttached(VillagerPlus.SPECIALS);
		List<ZonePolicy.Zone> oldZones = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		Villager owner = EntityTypes.VILLAGER.spawn(level, origin, EntitySpawnReason.COMMAND);
		if (owner == null) throw new IllegalStateException("Could not create market owner fixture");
		owner.addTag("vp090_fixture"); owner.setInvulnerable(true); owner.setNoAi(true);
		Villager customer = EntityTypes.VILLAGER.spawn(level, origin.east(2), EntitySpawnReason.COMMAND);
		if (customer == null) { owner.discard(); throw new IllegalStateException("Could not create market customer fixture"); }
		customer.addTag("vp090_fixture"); customer.setInvulnerable(true); customer.setNoAi(true);
		customer.setVillagerData(customer.getVillagerData().withProfession(level.registryAccess(),
			net.minecraft.world.entity.npc.villager.VillagerProfession.SHEPHERD));
		BlockPos stockPos = null;
		BlockPos pantryPos = origin.east(40);
		BlockPos toolsPos = origin.west(40);
		level.getChunkAt(pantryPos);
		level.getChunkAt(toolsPos);
		BlockSnapshot pantryBefore = BlockSnapshot.capture(level, pantryPos);
		BlockSnapshot toolsBefore = BlockSnapshot.capture(level, toolsPos);
		BlockState oldState = null;
		Container oldStock = null;
		List<ItemStack> oldStockBefore = List.of();
		java.util.UUID oldStockOwner = null;
		java.util.Optional<BlockPos> oldPen = ref.data().extra.pen;
		long oldCare = ref.data().extra.nextCare;
		List<Animal> breedingFixtures = new ArrayList<>();
		Map<BlockPos, BlockSnapshot> breedingSiteBefore = new java.util.LinkedHashMap<>();
		Map<BlockPos, BlockSnapshot> buyerStandBefore = new java.util.LinkedHashMap<>();
		ChunkPos breedingChunk = feedChunk;
		boolean entityTickingBeforeFixture = false;
		boolean entityTickingAfterForce = false;
		try {
			level.setBlock(pantryPos, Blocks.BARREL.defaultBlockState(), 3);
			level.setBlock(toolsPos, Blocks.BARREL.defaultBlockState(), 3);
			Specials.set(ref, Blueprints.GRANARY, pantryPos);
			Specials.set(ref, Blueprints.TOOLSHED, toolsPos);
			Container pantry = HopperBlockEntity.getContainerAt(level, pantryPos);
			Container tools = HopperBlockEntity.getContainerAt(level, toolsPos);
			if (pantry == null || tools == null) throw new IllegalStateException("Market public pantry/toolshed fixtures were not created");
			var memory = owner.getAttachedOrCreate(VillagerPlus.MEMORY);
			owner.getInventory().clearContent();
			owner.getInventory().setItem(0, new ItemStack(Items.EMERALD, 3));
			communal.clearContent(); pantry.setItem(0, new ItemStack(Items.BREAD, 64));
			require(Market.payRent(level, ref, owner), "private emerald pays one-time rent for a persisted stall career");
			require(count(owner.getInventory(), Items.EMERALD) == 2
				&& Network.treasuryEmeralds(ref) - treasuryBefore == 1 && count(communal, Items.EMERALD) == 0,
				"rent removes one real emerald from the owner wallet into the persisted village cash treasury"
					+ " (owner=" + count(owner.getInventory(), Items.EMERALD)
					+ ", treasury delta=" + (Network.treasuryEmeralds(ref) - treasuryBefore)
					+ ", material-depot emeralds=" + count(communal, Items.EMERALD) + ")");
			require(java.util.Arrays.asList(Market.goods(0)).containsAll(List.of(Items.WHEAT, Items.WHEAT_SEEDS)),
				"food stall offers actual breeding supplies as well as food");
			MarketData.Shop shop = new MarketData.Shop(owner.getUUID(), origin, Direction.NORTH.get2DDataValue(), 0,
				0, Math.max(1L, level.getGameTime()), level.getGameTime(), List.of(), false, 0);
			prepareBuyerStandRing(level, shop, buyerStandBefore);
			stockPos = Market.stockPos(shop);
			level.getChunkAt(stockPos);
			oldState = level.getBlockState(stockPos);
			oldStock = HopperBlockEntity.getContainerAt(level, stockPos);
			oldStockBefore = oldStock == null ? List.of() : copy(oldStock);
			var oldStockEntity = level.getBlockEntity(stockPos);
			oldStockOwner = oldStockEntity == null ? null : oldStockEntity.getAttached(VillagerPlus.OWNER);
			level.setBlock(stockPos, Blocks.CHEST.defaultBlockState(), 3);
			var stockEntity = level.getBlockEntity(stockPos);
			if (stockEntity == null) throw new IllegalStateException("Market fixture stock chest has no block entity");
			stockEntity.setAttached(VillagerPlus.OWNER, owner.getUUID());
			stockEntity.setChanged();
			Container shelf = Market.stock(level, shop);
			if (shelf == null) throw new IllegalStateException("Market fixture stock chest was not created or owned by its stall owner");
			shelf.clearContent();
			Market.save(ref, new MarketData(Market.of(ref).plaza(), List.of(shop), level.getGameTime() + 1000, 0));
			Ctx ctx = new Ctx(level, owner, JobManager.state(owner), memory, ref, level.getGameTime());

			Market.restock(ctx, shop);
			require(count(shelf, Items.BREAD) == 8, "owner stocks a visible first sale batch (shelf="
				+ count(shelf, Items.BREAD) + ", public pantry bread=" + count(pantry, Items.BREAD)
				+ ", owner emeralds=" + count(owner.getInventory(), Items.EMERALD)
				+ ", treasury delta=" + (Network.treasuryEmeralds(ref) - treasuryBefore) + ")");
			var firstDisplays = MarketDisplays.displaysFor(level, ref.bell(), shop.origin());
			require(firstDisplays.size() == 1 && firstDisplays.getFirst().getItemStack().is(Items.BREAD),
				"render-only bread display mirrors the real stall stock without adding inventory ("
					+ MarketDisplays.diagnostic(level, ref.bell(), shop, shelf) + ")");
			require(count(owner.getInventory(), Items.EMERALD) == 1
				&& Network.treasuryEmeralds(ref) - treasuryBefore == 2 && count(communal, Items.EMERALD) == 0,
				"stock payment deducts a second private emerald and credits the village cash account after rent");
			require(count(pantry, Items.BREAD) == 56, "first batch leaves the village food reserve");

			Market.restock(ctx, shop);
			require(count(shelf, Items.BREAD) == 16 && Network.treasuryEmeralds(ref) - treasuryBefore == 3,
				"second paid batch reaches the shelf limit and credits one more real emerald");
			var restockDisplays = MarketDisplays.displaysFor(level, ref.bell(), shop.origin());
			require(restockDisplays.size() == 1 && restockDisplays.getFirst().getItemStack().is(Items.BREAD),
				"repeated restocking updates one display instead of creating duplicates ("
					+ MarketDisplays.diagnostic(level, ref.bell(), shop, shelf) + ")");
			int ownerMoney = count(owner.getInventory(), Items.EMERALD);
			long villageTreasury = Network.treasuryEmeralds(ref);
			int villageBread = count(pantry, Items.BREAD);
			Market.restock(ctx, shop);
			require(count(shelf, Items.BREAD) == Market.STOCK_CAP, "restock cannot exceed the configured shelf cap");
			require(count(owner.getInventory(), Items.EMERALD) == ownerMoney
				&& Network.treasuryEmeralds(ref) == villageTreasury
				&& count(pantry, Items.BREAD) == villageBread,
				"full shelf does not take more owner money or village stock");

			shelf.clearContent();
			pantry.clearContent();
			tools.clearContent();
			communal.clearContent();
			for (int i = 0; i < communal.getContainerSize(); i++) communal.setItem(i, new ItemStack(Items.OAK_PLANKS, 64));
			ownerMoney = count(owner.getInventory(), Items.EMERALD);
			Market.restock(ctx, shop);
			require(count(shelf, Items.BREAD) == 0 && count(owner.getInventory(), Items.EMERALD) == ownerMoney,
				"no real public bread surplus leaves private money and goods untouched");

			// Restore a sale lot, then prove failed purchases leave all three real inventories untouched.
			shelf.clearContent(); shelf.setItem(0, new ItemStack(Items.BREAD, 8)); shelf.setItem(1, new ItemStack(Items.EMERALD, 2));
			customer.getInventory().clearContent();
			var customerMemory = customer.getAttachedOrCreate(VillagerPlus.MEMORY);
			customerMemory.homeChest = java.util.Optional.of(stockPos);
			int sellerBeforeProtectedWallet = count(owner.getInventory(), Items.EMERALD);
			Ctx protectedWalletBuyer = new Ctx(level, customer, JobManager.state(customer), customerMemory, ref, level.getGameTime());
			require(!Market.purchase(protectedWalletBuyer, owner, shop, Items.BREAD, 4, 1),
				"stale home-chest pointer cannot spend another villager's owner-marked stall wallet");
			require(count(shelf, Items.BREAD) == 8 && count(shelf, Items.EMERALD) == 2
				&& count(customer.getInventory(), Items.BREAD) == 0 && count(owner.getInventory(), Items.EMERALD) == sellerBeforeProtectedWallet,
				"protected-wallet retry leaves shelf goods, private emeralds, and seller funds unchanged");
			customerMemory.homeChest = java.util.Optional.empty();
			shelf.clearContent(); shelf.setItem(0, new ItemStack(Items.BREAD, 8));
			customer.getInventory().setItem(0, new ItemStack(Items.EMERALD));
			int ownerEmeralds = count(owner.getInventory(), Items.EMERALD);
			Ctx buyer = new Ctx(level, customer, JobManager.state(customer), customer.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
			require(!Market.purchase(buyer, owner, shop, Items.BREAD, 4, 2), "buyer without enough private emeralds is rejected");
			require(count(customer.getInventory(), Items.EMERALD) == 1 && count(shelf, Items.BREAD) == 8
				&& count(owner.getInventory(), Items.EMERALD) == ownerEmeralds, "rejected purchase changes no inventory");
			require(Market.purchase(buyer, owner, shop, Items.BREAD, 4, 1), "customer buys real goods from another villager");
			require(count(customer.getInventory(), Items.BREAD) == 4 && count(customer.getInventory(), Items.EMERALD) == 0
				&& count(owner.getInventory(), Items.EMERALD) == ownerEmeralds + 1 && count(shelf, Items.BREAD) == 4,
				"private payment, seller proceeds, buyer goods, and shelf stock balance exactly");
			customer.getInventory().clearContent();
			customer.getInventory().setItem(0, new ItemStack(Items.EMERALD));
			int ownerBeforeUnownedAttempt = count(owner.getInventory(), Items.EMERALD);
			stockEntity.removeAttached(VillagerPlus.OWNER);
			stockEntity.setChanged();
			require(Market.stock(level, shop) == null,
				"a replacement chest without the stall owner's private marker is not treated as market stock");
			require(!Market.purchase(buyer, owner, shop, Items.BREAD, 1, 1),
				"villager purchase refuses goods from a chest that no longer belongs to the registered stall owner");
			require(count(customer.getInventory(), Items.EMERALD) == 1 && count(shelf, Items.BREAD) == 4
				&& count(owner.getInventory(), Items.EMERALD) == ownerBeforeUnownedAttempt,
				"rejected unowned-stock sale preserves both wallets and all goods");
			stockEntity.setAttached(VillagerPlus.OWNER, owner.getUUID());
			stockEntity.setChanged();
			customer.getInventory().clearContent();
			customer.getInventory().setItem(0, new ItemStack(Items.EMERALD));
			customer.setAttached(VillagerPlus.LAST_SHOP, level.getGameTime() - 2_401L);
			ServerPlayer playerTrader = new ServerPlayer(level.getServer(), level,
				new GameProfile(java.util.UUID.randomUUID(), "MarketFixtureTrader"), ClientInformation.createDefault());
			owner.setTradingPlayer(playerTrader);
			int stockBeforePlayerTrade = count(shelf, Items.BREAD);
			int buyerEmeraldsBeforePlayerTrade = count(customer.getInventory(), Items.EMERALD);
			int sellerEmeraldsBeforePlayerTrade = count(owner.getInventory(), Items.EMERALD);
			Ctx activeSessionBuyer = new Ctx(level, customer, JobManager.state(customer), customerMemory, ref, level.getGameTime());
			require(!Market.visit(activeSessionBuyer), "residents skip a seller while a cached player-trade offer is open");
			require(count(shelf, Items.BREAD) == stockBeforePlayerTrade
				&& count(customer.getInventory(), Items.EMERALD) == buyerEmeraldsBeforePlayerTrade
				&& count(owner.getInventory(), Items.EMERALD) == sellerEmeraldsBeforePlayerTrade,
				"skipping the active player trader leaves stall stock and both private wallets untouched");
			require(!Market.purchase(activeSessionBuyer, owner, shop, Items.BREAD, 1, 1)
				&& count(shelf, Items.BREAD) == stockBeforePlayerTrade
				&& count(customer.getInventory(), Items.EMERALD) == buyerEmeraldsBeforePlayerTrade
				&& count(owner.getInventory(), Items.EMERALD) == sellerEmeraldsBeforePlayerTrade,
				"atomic purchase backstop rejects active player trade without changing either side");
			owner.setTradingPlayer(null);
			var saleDisplays = MarketDisplays.displaysFor(level, ref.bell(), shop.origin());
			require(saleDisplays.size() == 1 && saleDisplays.getFirst().getItemStack().is(Items.BREAD),
				"successful sale keeps a single display synchronized with remaining real bread stock ("
					+ MarketDisplays.diagnostic(level, ref.bell(), shop, shelf) + ")");
			require(Market.of(ref).sales() == 1 && MarketData.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
				MarketData.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, Market.of(ref)).getOrThrow()).getOrThrow().equals(Market.of(ref)),
				"rented shop and completed sale survive market-data serialization");
			require(Market.open(level), "resident shop visits run during configured market hours");
			shelf.clearContent(); shelf.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 4));
			customer.getInventory().clearContent(); customer.getInventory().setItem(0, new ItemStack(Items.EMERALD));
			long seedVisitAt = level.getGameTime() + 10_000L;
			customer.setAttached(VillagerPlus.LAST_SHOP, seedVisitAt - 2_400L);
			customer.teleportTo(origin.getX() + 0.5, origin.getY(), origin.getZ() - 2.5);
			BlockPos pen = feedPen;
			entityTickingBeforeFixture = level.isPositionEntityTicking(pen);
			entityTickingAfterForce = level.isPositionEntityTicking(pen);
			if (!entityTickingAfterForce) {
				throw new IllegalStateException("Market feed fixture pen is not entity-ticking after its forced-chunk ticket"
					+ " (before=" + entityTickingBeforeFixture + ", owns-force-ticket=" + ownsFeedChunkTicket
					+ ", after=" + entityTickingAfterForce
					+ ", entities-loaded-and-ticking=" + level.areEntitiesActuallyLoadedAndTicking(breedingChunk)
					+ ", chunk=" + breedingChunk + ")");
			}
			ref.data().extra.pen = java.util.Optional.of(pen);
			prepareChickenPen(level, pen, breedingSiteBefore);
			ZonePolicy.Zone marketPasture = marketFeedPasture(pen);
			boolean marketPastureValid = ZonePolicy.validBounds(ref, marketPasture.kind(), marketPasture.minX(),
				marketPasture.minZ(), marketPasture.maxX(), marketPasture.maxZ());
			if (!marketPastureValid) throw new IllegalStateException("Market feed fixture pasture is outside valid village bounds: " + marketPasture);
			int preSpawnChickenCount = marketChickensInPen(level, pen, marketPasture).size();
			if (preSpawnChickenCount + 2 >= Livestock.PEN_CAPACITY_PER_SPECIES) {
				throw new IllegalStateException("Market feed fixture pen lost capacity before chicken spawn: existing="
					+ preSpawnChickenCount + ", cap=" + Livestock.PEN_CAPACITY_PER_SPECIES);
			}
			// Livestock intentionally ignores animals outside any configured pasture. Give this pen
			// its own bounded pasture so unrelated saved zones cannot silently invalidate the pair.
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(marketPasture));
			ref.data().extra.nextCare = seedVisitAt;
			require(Livestock.marketFeedShortage(level, ref, customer, Items.WHEAT_SEEDS) == 0,
				"breeding supplies are not a market need when the registered pen has no eligible pair");
			ChickenFixture chickenAFixture = breedingChicken(level, pen, .5, .5, breedingFixtures);
			ChickenFixture chickenBFixture = breedingChicken(level, pen, 1.5, .5, breedingFixtures);
			Animal chickenA = chickenAFixture.animal();
			Animal chickenB = chickenBFixture.animal();
			int immediateChickenQuery = marketChickensInPen(level, pen, marketPasture).size();
			assertMarketChickensIndexed(level, pen, marketPasture, chickenAFixture, chickenBFixture,
				immediateChickenQuery, entityTickingBeforeFixture, entityTickingAfterForce);
			chickenA.setAge(200); chickenB.setAge(200);
			customer.teleportTo(pen.getX() + .5, pen.getY(), pen.getZ() + .5);
			Ctx coolingBreeder = new Ctx(level, customer, JobManager.state(customer), customer.getAttachedOrCreate(VillagerPlus.MEMORY), ref, seedVisitAt + 1);
			require(Livestock.marketFeedShortage(level, ref, customer, Items.WHEAT_SEEDS) == 0
				&& !Livestock.tick(coolingBreeder) && count(customer.getInventory(), Items.WHEAT_SEEDS) == 0,
				"post-breeding cooldown animals do not create market demand or consume feed early");
			ref.data().extra.nextCare = seedVisitAt;
			chickenA.setAge(0); chickenB.setAge(0);
			customer.teleportTo(origin.getX() + 0.5, origin.getY(), origin.getZ() - 2.5);
			assertMarketChickensIndexed(level, pen, marketPasture, chickenAFixture, chickenBFixture,
				immediateChickenQuery, entityTickingBeforeFixture, entityTickingAfterForce);
			int activeFeedShortage = Livestock.marketFeedShortage(level, ref, customer, Items.WHEAT_SEEDS);
			List<Animal> chickensInMarketPen = marketChickensInPen(level, pen, marketPasture);
			List<Animal> adultsInMarketPen = chickensInMarketPen.stream()
				.filter(animal -> animal.getAge() == 0 && !animal.isInLove()).toList();
			require(activeFeedShortage == 2,
				"two under-supplied adult chickens in the registered market pasture create exactly two seeds of private market demand (shortage="
					+ activeFeedShortage + ", buyer profession=" + de.villagerplus.Compat.jobKey(customer.getVillagerData().profession())
					+ ", buyer feed=" + count(customer.getInventory(), Items.WHEAT_SEEDS)
					+ ", public feed=" + count(communal, Items.WHEAT_SEEDS)
					+ ", chicken ages=" + chickenA.getAge() + "/" + chickenB.getAge()
					+ ", positions=" + chickenA.blockPosition() + "/" + chickenB.blockPosition()
					+ ", love=" + chickenA.isInLove() + "/" + chickenB.isInLove()
					+ ", query adults/total=" + adultsInMarketPen.size() + "/" + chickensInMarketPen.size()
					+ ", pre-spawn chickens=" + preSpawnChickenCount
					+ ", pasture valid=" + marketPastureValid
					+ ", registered pasture=" + marketPasture.minX() + "," + marketPasture.minZ()
					+ ".." + marketPasture.maxX() + "," + marketPasture.maxZ()
					+ ", pen=" + pen + ")");
			int ownerSeedMoney = count(owner.getInventory(), Items.EMERALD);
			customerMemory.lastMeal = seedVisitAt;
			Ctx seedBuyer = new Ctx(level, customer, JobManager.state(customer), customer.getAttachedOrCreate(VillagerPlus.MEMORY), ref, seedVisitAt);
			BlockPos seedStand = de.villagerplus.job.JobUtil.sharedStandSpot(seedBuyer, Market.counter(shop), owner.position(), 6.0);
			if (seedStand == null) throw new IllegalStateException("Market fixture has no safe food-stall buyer stand");
			customer.teleportTo(origin.getX() + 12.5, origin.getY(), origin.getZ() + 0.5);
			int seedStockBeforeWalk = count(shelf, Items.WHEAT_SEEDS);
			int seedMoneyBeforeWalk = count(customer.getInventory(), Items.EMERALD);
			require(Market.visit(seedBuyer) && count(shelf, Items.WHEAT_SEEDS) == seedStockBeforeWalk
				&& count(customer.getInventory(), Items.EMERALD) == seedMoneyBeforeWalk
				&& count(owner.getInventory(), Items.EMERALD) == ownerSeedMoney,
				"distant customer receives a walking target but cannot buy before reaching the selected stand");
			// Simulate arrival at the selected, safe interaction slot; the production path uses navigation only.
			customer.teleportTo(seedStand.getX() + 0.5, seedStand.getY(), seedStand.getZ() + 0.5);
			require(Market.visit(seedBuyer), "resident buys real wheat seeds from the food stall after reaching its stand");
			require(count(customer.getInventory(), Items.WHEAT_SEEDS) == 2 && count(customer.getInventory(), Items.EMERALD) == 0
				&& count(owner.getInventory(), Items.EMERALD) == ownerSeedMoney + 1 && count(shelf, Items.WHEAT_SEEDS) == 2
				&& Market.of(ref).sales() == 2, "seed purchase transfers only the two-feed shortage and one private emerald");
			require(!Market.visit(seedBuyer) && count(customer.getInventory(), Items.WHEAT_SEEDS) == 2
				&& count(owner.getInventory(), Items.EMERALD) == ownerSeedMoney + 1 && count(shelf, Items.WHEAT_SEEDS) == 2
				&& Market.of(ref).sales() == 2, "arrival commits the buyer's real seed purchase exactly once");
			customer.teleportTo(pen.getX() + .5, pen.getY(), pen.getZ() + .5);
			Ctx breeder = new Ctx(level, customer, JobManager.state(customer), customer.getAttachedOrCreate(VillagerPlus.MEMORY), ref, seedVisitAt + 1);
			require(Livestock.tick(breeder) && chickenA.isInLove() && chickenB.isInLove()
				&& count(customer.getInventory(), Items.WHEAT_SEEDS) == 0 && count(communal, Items.WHEAT_SEEDS) == 0,
				"the resident spends privately bought seeds on the real livestock breeding cycle");

			require(java.util.Arrays.asList(Market.goods(2)).contains(Items.IRON_PICKAXE),
				"material stall exposes a real iron tool product");
			MarketData.Shop toolShop = new MarketData.Shop(owner.getUUID(), origin, Direction.NORTH.get2DDataValue(), 2,
				0, Math.max(1L, level.getGameTime()), level.getGameTime(), List.of(), false, 0);
			MarketData marketWithTools = Market.of(ref);
			Market.save(ref, new MarketData(marketWithTools.plaza(), List.of(toolShop), level.getGameTime() + 1000,
				marketWithTools.sales()));
			shelf.clearContent(); communal.clearContent(); tools.clearContent();
			// Tools do not stack: two surplus picks must occupy two real container slots.
			tools.setItem(0, new ItemStack(Items.IRON_PICKAXE));
			tools.setItem(1, new ItemStack(Items.IRON_PICKAXE));
			require(count(tools, Items.IRON_PICKAXE) == 2
				&& count(Balance.storage(level, ref), Items.IRON_PICKAXE) == 2,
				"tool restock fixture exposes two real public iron pickaxes in the toolshed before the reserve check");
			int ownerToolMoney = count(Market.wallet(level, owner), Items.EMERALD);
			Ctx toolOwner = new Ctx(level, owner, JobManager.state(owner), memory, ref, level.getGameTime() + 10_001L);
			Market.restock(toolOwner, toolShop);
			int ownerToolMoneyAfter = count(Market.wallet(level, owner), Items.EMERALD);
			require(count(shelf, Items.IRON_PICKAXE) == 1 && count(tools, Items.IRON_PICKAXE) == 1
				&& ownerToolMoneyAfter == ownerToolMoney - 1
				&& Network.treasuryEmeralds(ref) - treasuryBefore == 4 && count(communal, Items.EMERALD) == 0,
				"owner stocks a real tool from public surplus and pays one private emerald to the village"
					+ " (shelf tools=" + count(shelf, Items.IRON_PICKAXE)
					+ ", village tools=" + count(tools, Items.IRON_PICKAXE)
					+ ", public tools=" + count(Balance.storage(level, ref), Items.IRON_PICKAXE)
					+ ", private emeralds=" + ownerToolMoney + "->" + ownerToolMoneyAfter
					+ ", treasury delta=" + (Network.treasuryEmeralds(ref) - treasuryBefore)
					+ ", material-depot emeralds=" + count(communal, Items.EMERALD) + ")");
			var toolDisplays = MarketDisplays.displaysFor(level, ref.bell(), toolShop.origin());
			require(toolDisplays.size() == 1 && toolDisplays.getFirst().getItemStack().is(Items.IRON_PICKAXE),
				"stocked tool is visible on the real stall display (" + MarketDisplays.diagnostic(level, ref.bell(), toolShop, shelf) + ")");

			long lowFundsAt = level.getGameTime() + 10_002L;
			customer.getInventory().clearContent(); customer.getInventory().setItem(0, new ItemStack(Items.EMERALD));
			customer.setAttached(VillagerPlus.LAST_SHOP, lowFundsAt - 2_400L);
			customer.teleportTo(origin.getX() + 0.5, origin.getY(), origin.getZ() - 2.5);
			int sellerBeforeLowFunds = count(owner.getInventory(), Items.EMERALD);
			Ctx lowFundsBuyer = new Ctx(level, customer, JobManager.state(customer), customer.getAttachedOrCreate(VillagerPlus.MEMORY), ref, lowFundsAt);
			require(!Market.visit(lowFundsBuyer), "one emerald cannot buy the two-emerald iron tool");
			require(count(customer.getInventory(), Items.EMERALD) == 1 && count(shelf, Items.IRON_PICKAXE) == 1
				&& count(owner.getInventory(), Items.EMERALD) == sellerBeforeLowFunds,
				"underfunded tool visit leaves buyer, shelf, and seller unchanged");

			long fullAt = level.getGameTime() + 10_003L;
			customer.getInventory().clearContent();
			for (int slot = 0; slot < customer.getInventory().getContainerSize(); slot++) {
				customer.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			}
			customer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
			customer.setAttached(VillagerPlus.LAST_SHOP, fullAt - 2_400L);
			customer.teleportTo(origin.getX() + 0.5, origin.getY(), origin.getZ() - 2.5);
			Ctx fullBuyer = new Ctx(level, customer, JobManager.state(customer), customer.getAttachedOrCreate(VillagerPlus.MEMORY), ref, fullAt);
			require(!Market.visit(fullBuyer), "resident with no free inventory slot cannot buy the iron tool");
			require(count(customer.getInventory(), Items.EMERALD) == 2 && count(customer.getInventory(), Items.IRON_PICKAXE) == 0
				&& count(shelf, Items.IRON_PICKAXE) == 1 && count(owner.getInventory(), Items.EMERALD) == sellerBeforeLowFunds,
				"full target inventory leaves money, tool stock, and seller wallet unchanged");

			long toolVisitAt = level.getGameTime() + 10_004L;
			customer.getInventory().clearContent(); customer.getInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
			customer.setAttached(VillagerPlus.LAST_SHOP, toolVisitAt - 2_400L);
			Ctx toolBuyer = new Ctx(level, customer, JobManager.state(customer), customer.getAttachedOrCreate(VillagerPlus.MEMORY), ref, toolVisitAt);
			BlockPos toolStand = de.villagerplus.job.JobUtil.sharedStandSpot(toolBuyer, Market.counter(toolShop), owner.position(), 6.0);
			if (toolStand == null) throw new IllegalStateException("Market fixture has no safe tool-stall buyer stand");
			customer.teleportTo(toolStand.getX() + 0.5, toolStand.getY(), toolStand.getZ() + 0.5);
			require(Market.visit(toolBuyer), "resident with a free slot buys the real iron tool through the visit flow");
			require(count(customer.getInventory(), Items.IRON_PICKAXE) == 1 && count(customer.getInventory(), Items.EMERALD) == 0
				&& count(owner.getInventory(), Items.EMERALD) == sellerBeforeLowFunds + 2 && count(shelf, Items.IRON_PICKAXE) == 0
				&& Market.of(ref).sales() == 3, "tool sale conserves stock and transfers exactly two private emeralds");
			require(MarketDisplays.displaysFor(level, ref.bell(), toolShop.origin()).isEmpty(),
				"sold-out tool is removed from the visible stock display");
			return 43;
		} finally {
			MarketDisplays.clear(level, ref.bell(), origin);
			Market.save(ref, marketBefore);
			long treasuryAfter = Network.treasuryEmeralds(ref);
			if (treasuryAfter > treasuryBefore) Network.spendTreasuryEmeralds(ref, treasuryAfter - treasuryBefore);
			else if (treasuryAfter < treasuryBefore) Network.creditTreasuryEmeralds(ref, treasuryBefore - treasuryAfter);
			communal.clearContent(); restore(communal, communalBefore);
			pantryBefore.restore(level, pantryPos);
			toolsBefore.restore(level, toolsPos);
			if (oldSpecials == null) ref.bellEntity().removeAttached(VillagerPlus.SPECIALS);
			else ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			if (oldZones == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES);
			else ref.bellEntity().setAttached(VillagerPlus.ZONES, oldZones);
			ref.dirty();
			owner.discard();
			customer.discard();
			breedingFixtures.forEach(Animal::discard);
			for (Map.Entry<BlockPos, BlockSnapshot> entry : breedingSiteBefore.entrySet()) {
				entry.getValue().restore(level, entry.getKey());
			}
			if (ownsFeedChunkTicket && PENDING_ENTITY_TICK_TICKETS.remove(feedChunk)) {
				level.setChunkForced(feedChunk.x(), feedChunk.z(), false);
			}
			ref.data().extra.pen = oldPen;
			ref.data().extra.nextCare = oldCare;
			ref.dirty();
			if (stockPos != null && oldState != null) {
				level.setBlock(stockPos, oldState, 3);
				Container restoredStock = HopperBlockEntity.getContainerAt(level, stockPos);
				if (restoredStock != null) { restoredStock.clearContent(); restore(restoredStock, oldStockBefore); }
				var restoredEntity = level.getBlockEntity(stockPos);
				if (restoredEntity != null) {
					if (oldStockOwner == null) restoredEntity.removeAttached(VillagerPlus.OWNER);
					else restoredEntity.setAttached(VillagerPlus.OWNER, oldStockOwner);
					restoredEntity.setChanged();
				}
			}
			for (Map.Entry<BlockPos, BlockSnapshot> entry : buyerStandBefore.entrySet()) {
				entry.getValue().restore(level, entry.getKey());
			}
			for (MarketData.Shop previousShop : marketBefore.shops()) {
				if (previousShop.opened() > 0 && previousShop.origin().equals(origin)) {
					Market.refreshDisplays(level, ref, previousShop);
				}
			}
		}
	}

	public static void releasePendingEntityTickTickets(ServerLevel level) {
		for (ChunkPos chunk : List.copyOf(PENDING_ENTITY_TICK_TICKETS)) {
			if (PENDING_ENTITY_TICK_TICKETS.remove(chunk)) level.setChunkForced(chunk.x(), chunk.z(), false);
		}
	}

	private static int count(Container box, Item item) { int n=0; for (int i=0;i<box.getContainerSize();i++) if (box.getItem(i).is(item)) n+=box.getItem(i).getCount(); return n; }
	private static int count(List<Container> boxes, Item item) { int n=0; for (Container box : boxes) n += count(box, item); return n; }
	private static List<ItemStack> copy(Container box) { List<ItemStack> out=new ArrayList<>(); for(int i=0;i<box.getContainerSize();i++) out.add(box.getItem(i).copy()); return out; }
	private static void restore(Container box,List<ItemStack> items) { for(int i=0;i<Math.min(box.getContainerSize(),items.size());i++) box.setItem(i,items.get(i).copy()); }
	private static void require(boolean condition,String message) { if(!condition) throw new IllegalStateException("Market lifecycle: "+message); }
	private static BlockPos selectMarketFeedPen(ServerLevel level, VillageRef ref, BlockPos origin) {
		List<BlockPos> candidates = List.of(origin.offset(6, 0, 6), origin.offset(-7, 0, 6),
			origin.offset(6, 0, -7), origin.offset(-7, 0, -7),
			origin.offset(35, 0, 40), origin.offset(-33, 0, 40),
			origin.offset(35, 0, -40), origin.offset(-33, 0, -40));
		BlockPos selected = null;
		int selectedPopulation = Integer.MAX_VALUE;
		boolean selectedEntityTicking = false;
		for (BlockPos candidate : candidates) {
			ZonePolicy.Zone pasture = marketFeedPasture(candidate);
			if (!ZonePolicy.validBounds(ref, pasture.kind(), pasture.minX(), pasture.minZ(), pasture.maxX(), pasture.maxZ())) continue;
			level.getChunkAt(candidate);
			boolean entityTicking = level.isPositionEntityTicking(candidate);
			int existingChickens = marketChickensInPen(level, candidate, pasture).size();
			// The two controlled adults must still leave headroom for breeding under the real pen cap.
			if (existingChickens + 2 >= Livestock.PEN_CAPACITY_PER_SPECIES
				|| (selectedEntityTicking && !entityTicking)
				|| (selectedEntityTicking == entityTicking && existingChickens >= selectedPopulation)) continue;
			selected = candidate;
			selectedPopulation = existingChickens;
			selectedEntityTicking = entityTicking;
			if (entityTicking && existingChickens == 0) break;
		}
		if (selected == null) throw new IllegalStateException("Market feed fixture could not find a valid pen below chicken capacity "
			+ Livestock.PEN_CAPACITY_PER_SPECIES + " without removing existing animals");
		return selected;
	}
	private static ZonePolicy.Zone marketFeedPasture(BlockPos pen) {
		return new ZonePolicy.Zone("market-lifecycle-feed-fixture", "pasture",
			pen.getX() - 2, pen.getZ() - 2, pen.getX() + 2, pen.getZ() + 2);
	}
	private static List<Animal> marketChickensInPen(ServerLevel level, BlockPos pen, ZonePolicy.Zone pasture) {
		AABB area = new AABB(pen).inflate(5, 3, 5);
		return level.getEntitiesOfClass(Animal.class, area,
			a -> a.isAlive() && a.getType() == EntityTypes.CHICKEN && inMarketPasture(a, area, pasture));
	}
	private static boolean inMarketPasture(Animal animal, AABB area, ZonePolicy.Zone pasture) {
		return area.contains(animal.position()) && animal.getX() >= pasture.minX()
			&& animal.getX() < (double)pasture.maxX() + 1 && animal.getZ() >= pasture.minZ()
			&& animal.getZ() < (double)pasture.maxZ() + 1;
	}
	private static void prepareChickenPen(ServerLevel level, BlockPos pen, Map<BlockPos, BlockSnapshot> before) {
		for (int offsetX = 0; offsetX <= 1; offsetX++) {
			BlockPos feet = pen.offset(offsetX, 0, 0);
			setFixtureBlock(level, before, feet.below(), Blocks.GRASS_BLOCK.defaultBlockState());
			for (int offsetY = 0; offsetY <= 2; offsetY++) {
				setFixtureBlock(level, before, feet.above(offsetY), Blocks.AIR.defaultBlockState());
			}
			if (!level.getBlockState(feet.below()).is(Blocks.GRASS_BLOCK)
				|| !level.getBlockState(feet).is(Blocks.AIR)
				|| !level.getBlockState(feet.above()).is(Blocks.AIR)
				|| !level.getBlockState(feet.above(2)).is(Blocks.AIR)) {
				throw new IllegalStateException("Market feed fixture could not prepare a two-block chicken stand at " + feet);
			}
		}
	}
	private static void setFixtureBlock(ServerLevel level, Map<BlockPos, BlockSnapshot> before, BlockPos pos, BlockState state) {
		before.computeIfAbsent(pos, target -> BlockSnapshot.capture(level, target));
		level.setBlock(pos, state, 3);
	}
	private static void prepareBuyerStandRing(ServerLevel level, MarketData.Shop shop,
		Map<BlockPos, BlockSnapshot> before) {
		// The remote test village bell is deliberately at Y=201; give the market's actual
		// interaction ring a reversible floor so this test exercises arrival rather than
		// asking the path/stand selector to find ground more than six blocks below the seller.
		for (int x = -4; x <= 4; x++) for (int z = -7; z <= 1; z++) {
			BlockPos feet = shop.origin().offset(x, 0, z);
			if (level.getBlockEntity(feet) != null || level.getBlockEntity(feet.above()) != null) continue;
			setFixtureBlock(level, before, feet.below(), Blocks.STONE.defaultBlockState());
			setFixtureBlock(level, before, feet, Blocks.AIR.defaultBlockState());
			setFixtureBlock(level, before, feet.above(), Blocks.AIR.defaultBlockState());
		}
	}
	private static void assertMarketChickensIndexed(ServerLevel level, BlockPos pen, ZonePolicy.Zone pasture,
		ChickenFixture firstFixture, ChickenFixture secondFixture, int immediateQueryCount,
		boolean entityTickingBefore, boolean entityTickingAfterForce) {
		Animal first = firstFixture.animal();
		Animal second = secondFixture.animal();
		List<Animal> immediatelyFound = marketChickensInPen(level, pen, pasture);
		boolean firstVisibleImmediately = immediatelyFound.contains(first);
		boolean secondVisibleImmediately = immediatelyFound.contains(second);
		level.waitForEntities(new ChunkPos(pen.getX() >> 4, pen.getZ() >> 4), 0);
		List<Animal> foundAfterEntityWait = marketChickensInPen(level, pen, pasture);
		boolean activePair = foundAfterEntityWait.contains(first) && foundAfterEntityWait.contains(second)
			&& first.getAge() == 0 && second.getAge() == 0 && !first.isInLove() && !second.isInLove();
		if (!activePair) {
			throw new IllegalStateException("Market feed fixture chickens are still absent from the active livestock entity query"
				+ " (immediate-query-count=" + immediateQueryCount
				+ ", immediate-visible=" + firstVisibleImmediately + "/" + secondVisibleImmediately
				+ ", post-entity-wait-count=" + foundAfterEntityWait.size()
				+ ", post-entity-wait-visible=" + foundAfterEntityWait.contains(first) + "/" + foundAfterEntityWait.contains(second)
				+ ", addFreshEntity=" + firstFixture.addFreshAccepted() + "/" + secondFixture.addFreshAccepted()
				+ ", level-match=" + (first.level() == level) + "/" + (second.level() == level)
				+ ", removed=" + first.isRemoved() + "/" + second.isRemoved()
				+ ", id-lookup=" + (level.getEntity(first.getId()) == first) + "/" + (level.getEntity(second.getId()) == second)
				+ ", alive=" + first.isAlive() + "/" + second.isAlive()
				+ ", type=" + first.getType() + "/" + second.getType()
				+ ", age=" + first.getAge() + "/" + second.getAge()
				+ ", chunk=" + first.chunkPosition() + "/" + second.chunkPosition()
				+ ", entity-ticking=" + entityTickingBefore + "->" + entityTickingAfterForce
				+ ", loaded-and-ticking=" + level.areEntitiesActuallyLoadedAndTicking(new ChunkPos(pen.getX() >> 4, pen.getZ() >> 4))
				+ ", pasture=" + pasture + ", pen=" + pen + ")");
		}
	}
	private record BlockSnapshot(BlockState state, CompoundTag blockEntity) {
		private static BlockSnapshot capture(ServerLevel level, BlockPos pos) {
			BlockEntity entity = level.getBlockEntity(pos);
			return new BlockSnapshot(level.getBlockState(pos),
				entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
		}

		private void restore(ServerLevel level, BlockPos pos) {
			level.removeBlockEntity(pos);
			level.setBlock(pos, state, 3);
			if (blockEntity != null) {
				BlockEntity restored = BlockEntity.loadStatic(pos, state, blockEntity.copy(), level.registryAccess());
				if (restored != null) level.setBlockEntity(restored);
			}
		}
	}
	private record ChickenFixture(Animal animal, boolean addFreshAccepted) { }
	private static ChickenFixture breedingChicken(ServerLevel level, BlockPos pen, double x, double z, List<Animal> fixtures) {
		Animal animal = EntityTypes.CHICKEN.create(level, EntitySpawnReason.COMMAND);
		if (animal == null) throw new IllegalStateException("Could not create market breeding chicken fixture");
		animal.addTag("vp090_fixture"); animal.setNoAi(true); animal.setInvulnerable(true); animal.setAge(0);
		animal.setPos(pen.getX() + x, pen.getY(), pen.getZ() + z);
		fixtures.add(animal);
		boolean addFreshAccepted = level.addFreshEntity(animal);
		if (!addFreshAccepted) throw new IllegalStateException("Could not add market breeding chicken to the server entity manager"
			+ " (addFreshEntity=false, level-match=" + (animal.level() == level) + ", removed=" + animal.isRemoved()
			+ ", alive=" + animal.isAlive() + ", type=" + animal.getType() + ", position=" + animal.position()
			+ ", chunk=" + animal.chunkPosition() + ", entity-ticking=" + level.isPositionEntityTicking(pen) + ")");
		return new ChickenFixture(animal, addFreshAccepted);
	}
}
