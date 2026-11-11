package de.villagerplus.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtTradingPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.TradeWithPlayerGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** Independent caravan merchant with its own travel AI and a saveable visit snapshot. */
public final class CaravanTraderEntity extends AbstractVillager {
	private static final double PACK_SIDE_ROPE_OFFSET_Z = -0.45;
	private static final double ROPE_HOLD_HEIGHT_FACTOR = 0.58;
	private static final Codec<TravelData> TRAVEL_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.fieldOf("bell").forGetter(TravelData::bell),
		BlockPos.CODEC.fieldOf("spawn").forGetter(TravelData::spawn),
		Codec.INT.optionalFieldOf("partner", 0).forGetter(TravelData::partner),
		Codec.STRING.listOf().optionalFieldOf("members", List.of()).forGetter(TravelData::members),
		Codec.STRING.optionalFieldOf("guard", "").forGetter(TravelData::guard),
		Codec.LONG.optionalFieldOf("born", 0L).forGetter(TravelData::born),
		Codec.BOOL.optionalFieldOf("traded", false).forGetter(TravelData::traded),
		Codec.LONG.optionalFieldOf("leave_at", Long.MAX_VALUE).forGetter(TravelData::leaveAt),
		Codec.LONG.optionalFieldOf("next_unload", 0L).forGetter(TravelData::nextUnload),
		Codec.INT.optionalFieldOf("uses", 0).forGetter(TravelData::uses),
		Codec.INT.optionalFieldOf("reward", 0).forGetter(TravelData::reward),
		Codec.STRING.optionalFieldOf("road_key", "").forGetter(TravelData::roadKey),
		Codec.INT.optionalFieldOf("road_cursor", -1).forGetter(TravelData::roadCursor)
	).apply(instance, TravelData::new));
	private static final Codec<CampData> CAMP_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.listOf().optionalFieldOf("positions", List.of()).forGetter(CampData::positions),
		BlockState.CODEC.listOf().optionalFieldOf("states", List.of()).forGetter(CampData::states),
		Codec.BOOL.optionalFieldOf("built", false).forGetter(CampData::built),
		Codec.LONG.optionalFieldOf("retry_at", 0L).forGetter(CampData::retryAt),
		Codec.BOOL.optionalFieldOf("guard_duty_ended", false).forGetter(CampData::guardDutyEnded),
		Codec.BOOL.optionalFieldOf("armed_guard_removed", false).forGetter(CampData::armedGuardRemoved)
	).apply(instance, CampData::new));
	public static final Codec<VisitData> VISIT_DATA_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		TRAVEL_CODEC.fieldOf("travel").forGetter(VisitData::travel),
		CAMP_CODEC.fieldOf("camp").forGetter(VisitData::camp)
	).apply(instance, VisitData::new));

	private int tradeSpecialty;
	private int tradeRelation;
	private String partnerName = "unbekannt";
	private @Nullable BlockPos routeTarget;
	private @Nullable VisitData visitData;
	private final List<ItemStack> backedTradeStock = new ArrayList<>();
	private boolean backedTradeOffersInitialized;

	public CaravanTraderEntity(EntityType<? extends CaravanTraderEntity> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
	}

	/**
	 * Keep the leash anchor at this model's shoulder height and on its backpack side. The
	 * renderer's 180-degree body turn maps model-local +Z (the pack) to world -Z at yaw zero.
	 * Minecraft 26.2 exposes this through Entity.getRopeHoldPosition rather than getLeashOffset.
	 */
	@Override
	public Vec3 getRopeHoldPosition(float partialTickTime) {
		float bodyYaw = Mth.lerp(partialTickTime, this.yBodyRotO, this.yBodyRot);
		return this.getPosition(partialTickTime).add(ropeHoldOffset(bodyYaw, this.getBoundingBox().getYsize()));
	}

	/** Shared geometry for the native leash endpoint; yaw is interpolated by getRopeHoldPosition. */
	public static Vec3 ropeHoldOffset(float bodyYawDegrees, double entityHeight) {
		float yaw = bodyYawDegrees * (float)(Math.PI / 180.0);
		// The fitted model's shoulder is at about 58% of standing height. The old 85%
		// anchor landed in the head/eye area and made pack-animal leads cross the face.
		return new Vec3(0.0, entityHeight * ROPE_HOLD_HEIGHT_FACTOR, PACK_SIDE_ROPE_OFFSET_Z).yRot(-yaw);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new TradeWithPlayerGoal(this));
		this.goalSelector.addGoal(1, new LookAtTradingPlayerGoal(this));
		this.goalSelector.addGoal(1, new PanicGoal(this, 0.55));
		this.goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Monster.class, 10.0F, 0.55, 0.65));
		this.goalSelector.addGoal(2, new CaravanRouteGoal());
		this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.35));
	}

	public void setTradeProfile(int specialty, int relation, String partnerName) {
		this.tradeSpecialty = Math.floorMod(specialty, 4);
		this.tradeRelation = Math.max(-100, Math.min(100, relation));
		this.partnerName = partnerName == null || partnerName.isBlank() ? "unbekannt" : partnerName;
	}

	public int tradeSpecialty() { return this.tradeSpecialty; }
	public int tradeRelation() { return this.tradeRelation; }
	public String partnerName() { return this.partnerName; }

	public void setRouteTarget(@Nullable BlockPos target) {
		this.routeTarget = target == null ? null : target.immutable();
	}

	public @Nullable VisitData visitData() { return this.visitData; }
	public void setVisitData(@Nullable VisitData visitData) { this.visitData = visitData; }
	public boolean backedTradeOffersInitialized() { return this.backedTradeOffersInitialized; }
	public boolean hasBackedTradeStock() { return this.backedTradeStock.stream().anyMatch(stack -> !stack.isEmpty()); }
	public List<ItemStack> backedTradeStockSnapshot() { return this.backedTradeStock.stream().map(ItemStack::copy).toList(); }
	public void restoreBackedTradeStock(List<ItemStack> stacks) {
		for (ItemStack stack : stacks) if (!stack.isEmpty()) this.retainTradePayment(stack);
		this.refreshDisplayedTradeStock();
	}

	public int itemsPerTrade() { return this.tradeRelation >= 40 ? 16 : 8; }

	public boolean backedOffersCovered() {
		if (!this.backedTradeOffersInitialized) return false;
		List<ItemStack> demands = new ArrayList<>();
		List<Long> amounts = new ArrayList<>();
		for (MerchantOffer offer : this.getOffers()) {
			int uses = Math.max(0, offer.getMaxUses() - offer.getUses());
			if (uses == 0) continue;
			ItemStack result = offer.getResult();
			long demand = (long) result.getCount() * uses;
			int index = -1;
			for (int i = 0; i < demands.size(); i++) if (ItemStack.isSameItemSameComponents(demands.get(i), result)) { index = i; break; }
			if (index < 0) {
				demands.add(result.copyWithCount(1));
				amounts.add(demand);
			} else amounts.set(index, amounts.get(index) + demand);
		}
		for (int i = 0; i < demands.size(); i++) {
			long available = 0L;
			for (ItemStack stock : this.backedTradeStock) {
				if (ItemStack.isSameItemSameComponents(stock, demands.get(i))) available += stock.getCount();
			}
			if (available < amounts.get(i)) return false;
		}
		return true;
	}

	public static int backedOfferUses(int available, int itemsPerTrade) {
		return available <= 0 || itemsPerTrade <= 0 ? 0 : available / itemsPerTrade;
	}

	/** Replaces legacy/unbacked offers with finite offers backed by stock already removed from caravan cargo. */
	public void installBackedTradeOffers(List<ItemStack> realStock) {
		if (this.backedTradeOffersInitialized) return;
		MerchantOffers offers = new MerchantOffers();
		int itemsPerTrade = this.itemsPerTrade();
		int emeraldPrice = Math.max(1, (int) Math.round(4 * (1 - this.tradeRelation / 400.0)));
		for (ItemStack stock : realStock) {
			if (stock.isEmpty()) continue;
			int uses = backedOfferUses(stock.getCount(), itemsPerTrade);
			if (uses <= 0) continue;
			int offeredCount = uses * itemsPerTrade;
			ItemStack offered = stock.copyWithCount(offeredCount);
			this.backedTradeStock.add(offered.copy());
			offers.add(new MerchantOffer(
				new ItemCost(Items.EMERALD, emeraldPrice * Math.max(1, itemsPerTrade / 8)),
				stock.copyWithCount(itemsPerTrade), uses, 2, 0));
		}
		MerchantOffers currentOffers = this.getOffers();
		currentOffers.clear();
		currentOffers.addAll(offers);
		this.backedTradeOffersInitialized = true;
		this.refreshDisplayedTradeStock();
	}

	/** Old versions could save generated offers without physical cargo backing; keep the records but disable them. */
	public void disableUnbackedOffers() {
		for (MerchantOffer offer : this.getOffers()) offer.setToOutOfStock();
	}

	public boolean consumeBackedTradeStock(ItemStack requested) {
		if (requested.isEmpty()) return true;
		int available = this.backedTradeStock.stream()
			.filter(stack -> ItemStack.isSameItemSameComponents(stack, requested))
			.mapToInt(ItemStack::getCount).sum();
		if (available < requested.getCount()) return false;
		int remaining = requested.getCount();
		for (ItemStack stack : this.backedTradeStock) {
			if (remaining <= 0) break;
			if (!ItemStack.isSameItemSameComponents(stack, requested)) continue;
			int removed = Math.min(remaining, stack.getCount());
			stack.shrink(removed);
			remaining -= removed;
		}
		this.backedTradeStock.removeIf(ItemStack::isEmpty);
		boolean consumed = remaining == 0;
		if (consumed) this.refreshDisplayedTradeStock();
		return consumed;
	}

	private boolean retainTradePayment(ItemStack payment) {
		if (payment.isEmpty()) return true;
		ItemStack remaining = payment.copy();
		for (ItemStack stack : this.backedTradeStock) {
			if (!ItemStack.isSameItemSameComponents(stack, remaining) || stack.getCount() >= stack.getMaxStackSize()) continue;
			int moved = Math.min(remaining.getCount(), stack.getMaxStackSize() - stack.getCount());
			stack.grow(moved);
			remaining.shrink(moved);
			if (remaining.isEmpty()) {
				this.refreshDisplayedTradeStock();
				return true;
			}
		}
		while (!remaining.isEmpty()) {
			int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
			this.backedTradeStock.add(remaining.copyWithCount(moved));
			remaining.shrink(moved);
		}
		this.refreshDisplayedTradeStock();
		return remaining.isEmpty();
	}

	/** Keeps one synchronized presentation copy in hand without changing the merchant's backing stock. */
	private void refreshDisplayedTradeStock() {
		ItemStack display = this.backedTradeStock.stream()
			.filter(stack -> !stack.isEmpty())
			.findFirst()
			.map(stack -> stack.copyWithCount(1))
			.orElse(ItemStack.EMPTY);
		this.setItemSlot(EquipmentSlot.MAINHAND, display);
	}

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		if (held.is(Items.VILLAGER_SPAWN_EGG) || !this.isAlive() || this.isTrading() || this.isBaby()) {
			return super.mobInteract(player, hand);
		}
		if (this.level().isClientSide()) return InteractionResult.SUCCESS;
		if (!this.backedTradeOffersInitialized || !this.backedOffersCovered()) this.disableUnbackedOffers();
		if (this.getOffers().isEmpty()) return InteractionResult.CONSUME;
		this.setTradingPlayer(player);
		this.openTradingScreen(player, this.getDisplayName(), 1);
		return InteractionResult.SUCCESS;
	}

	@Override
	protected void updateTrades(ServerLevel level) {
		// Offers are installed by Caravan only after physical cargo was reserved for them.
	}

	@Override
	public void notifyTrade(MerchantOffer offer) {
		ItemStack result = offer.getResult().copy();
		if (!this.consumeBackedTradeStock(result)) {
			for (MerchantOffer current : this.getOffers()) current.setToOutOfStock();
			org.slf4j.LoggerFactory.getLogger("villagerplus").error(
				"Caravan merchant {} completed an offer without matching reserved stock: {}", this.getUUID(), result);
		} else {
			// Payments become real carried stock; no emeralds or other outputs are minted by the offer path.
			this.retainTradePayment(offer.getCostA());
		}
		super.notifyTrade(offer);
	}

	@Override
	protected void rewardTradeXp(MerchantOffer offer) {
		if (offer.shouldRewardExp()) {
			int xp = 3 + this.random.nextInt(4);
			this.level().addFreshEntity(new ExperienceOrb(this.level(), this.getX(), this.getY() + 0.5, this.getZ(), xp));
		}
	}

	@Override
	public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) { return null; }

	@Override
	public boolean removeWhenFarAway(double distanceSquared) { return false; }

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("vp_trade_specialty", this.tradeSpecialty);
		output.putInt("vp_trade_relation", this.tradeRelation);
		output.putString("vp_partner_name", this.partnerName);
		output.store("vp_backed_trade_stock", ItemStack.CODEC.listOf(), this.backedTradeStock.stream().map(ItemStack::copy).toList());
		output.putBoolean("vp_backed_trade_offers_initialized", this.backedTradeOffersInitialized);
		output.storeNullable("vp_route_target", BlockPos.CODEC, this.routeTarget);
		output.storeNullable("vp_caravan_visit", VISIT_DATA_CODEC, this.visitData);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.tradeSpecialty = Math.floorMod(input.getIntOr("vp_trade_specialty", 0), 4);
		this.tradeRelation = Math.max(-100, Math.min(100, input.getIntOr("vp_trade_relation", 0)));
		this.partnerName = input.getStringOr("vp_partner_name", "unbekannt");
		this.backedTradeStock.clear();
		this.backedTradeStock.addAll(input.read("vp_backed_trade_stock", ItemStack.CODEC.listOf())
			.orElse(List.of()).stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList());
		this.backedTradeOffersInitialized = input.getBooleanOr("vp_backed_trade_offers_initialized", false);
		this.routeTarget = input.read("vp_route_target", BlockPos.CODEC)
			.or(() -> input.read("wander_target", BlockPos.CODEC)).orElse(null);
		this.visitData = input.read("vp_caravan_visit", VISIT_DATA_CODEC).orElse(null);
		this.refreshDisplayedTradeStock();
		this.setPersistenceRequired();
	}

	@Override
	protected void dropEquipment(ServerLevel level) {
		// The held item is only a render copy of backedTradeStock, so it must never become a death drop.
		this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		super.dropEquipment(level);
	}

	@Override
	protected net.minecraft.sounds.SoundEvent getAmbientSound() {
		return this.isTrading() ? net.minecraft.sounds.SoundEvents.WANDERING_TRADER_TRADE : net.minecraft.sounds.SoundEvents.WANDERING_TRADER_AMBIENT;
	}
	@Override
	protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) { return net.minecraft.sounds.SoundEvents.WANDERING_TRADER_HURT; }
	@Override
	protected net.minecraft.sounds.SoundEvent getDeathSound() { return net.minecraft.sounds.SoundEvents.WANDERING_TRADER_DEATH; }
	@Override
	protected net.minecraft.sounds.SoundEvent getTradeUpdatedSound(boolean successful) {
		return successful ? net.minecraft.sounds.SoundEvents.WANDERING_TRADER_YES : net.minecraft.sounds.SoundEvents.WANDERING_TRADER_NO;
	}

	public List<String> infoLines() {
		List<String> lines = new java.util.ArrayList<>();
		lines.add("Handelspartner|" + this.partnerName + "|-1");
		lines.add("Spezialität|" + specialtyName(this.tradeSpecialty) + "|-1");
		lines.add("Beziehung|" + this.tradeRelation + "|-1");
		for (MerchantOffer offer : this.getOffers()) {
			ItemStack cost = offer.getCostA();
			ItemStack result = offer.getResult();
			lines.add("Angebot|" + cost.getCount() + "× " + cost.getHoverName().getString() + " → "
				+ result.getCount() + "× " + result.getHoverName().getString()
				+ (offer.isOutOfStock() ? " (ausverkauft)" : "") + "|-1");
		}
		return List.copyOf(lines);
	}

	private static String specialtyName(int specialty) {
		return switch (Math.floorMod(specialty, 4)) {
			case 0 -> "Metall und Brennstoff";
			case 1 -> "Textilien und Leder";
			case 2 -> "Lebensmittel";
			default -> "Holz und Stein";
		};
	}

	public record VisitData(TravelData travel, CampData camp) { }

	public record TravelData(BlockPos bell, BlockPos spawn, int partner, List<String> members, String guard,
		long born, boolean traded, long leaveAt, long nextUnload, int uses, int reward, String roadKey, int roadCursor) {
		public TravelData {
			bell = bell.immutable();
			spawn = spawn.immutable();
			members = List.copyOf(members);
			guard = guard == null ? "" : guard;
			roadKey = roadKey == null ? "" : roadKey;
		}
	}

	public record CampData(List<BlockPos> positions, List<BlockState> states, boolean built, long retryAt,
		boolean guardDutyEnded, boolean armedGuardRemoved) {
		public CampData {
			positions = positions.stream().map(BlockPos::immutable).toList();
			states = List.copyOf(states);
		}
	}

	private final class CaravanRouteGoal extends Goal {
		CaravanRouteGoal() { this.setFlags(EnumSet.of(Goal.Flag.MOVE)); }

		@Override
		public boolean canUse() { return shouldMoveToRoute(); }

		@Override
		public boolean canContinueToUse() { return shouldMoveToRoute(); }

		@Override
		public void tick() {
			BlockPos target = CaravanTraderEntity.this.routeTarget;
			if (target == null || !CaravanTraderEntity.this.getNavigation().isDone()) return;
			if (!target.closerToCenterThan(CaravanTraderEntity.this.position(), 10.0)) {
				net.minecraft.world.phys.Vec3 direction = new net.minecraft.world.phys.Vec3(
					target.getX() - CaravanTraderEntity.this.getX(),
					target.getY() - CaravanTraderEntity.this.getY(),
					target.getZ() - CaravanTraderEntity.this.getZ()).normalize();
				net.minecraft.world.phys.Vec3 step = direction.scale(10.0).add(CaravanTraderEntity.this.position());
				CaravanTraderEntity.this.getNavigation().moveTo(step.x, step.y, step.z, 0.5);
			} else {
				CaravanTraderEntity.this.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.5);
			}
		}

		private boolean shouldMoveToRoute() {
			BlockPos target = CaravanTraderEntity.this.routeTarget;
			return target != null && !CaravanTraderEntity.this.isTrading()
				&& !target.closerToCenterThan(CaravanTraderEntity.this.position(), 2.0);
		}
	}
}
