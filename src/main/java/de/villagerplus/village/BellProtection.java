package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Built;
import de.villagerplus.world.LoadedColumns;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Village bell breaks require confirmation; sneak-break moves village data on the dropped bell item.
 * {@link de.villagerplus.mixin.VillageBellExplosionMixin} keeps bells from being destroyed by explosions.
 */
public final class BellProtection {
	private static final long CONFIRM_TICKS = 100L;
	private static final String RELOCATION_TAG = "villagerplus_village";
	private record Pending(long bellPos, long until) { }
	private record PendingPlace(UUID playerId, BlockPos pos, ItemStack stack, long until) { }
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final Map<Long, PendingPlace> PENDING_PLACES = new HashMap<>();

	private BellProtection() { }

	public static void register() {
		PlayerBlockBreakEvents.BEFORE.register(BellProtection::beforeBreak);
		UseBlockCallback.EVENT.register(BellProtection::onUseBlock);
		ServerTickEvents.END_SERVER_TICK.register(BellProtection::tickPendingPlaces);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = server.overworld().getGameTime();
			PENDING.entrySet().removeIf(entry -> now > entry.getValue().until());
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearPending());
	}

	public static void clearPending() {
		PENDING.clear();
		PENDING_PLACES.clear();
	}

	private static InteractionResult onUseBlock(Player player, Level world, net.minecraft.world.InteractionHand hand,
		net.minecraft.world.phys.BlockHitResult hit) {
		if (world.isClientSide() || !(world instanceof ServerLevel level)) return InteractionResult.PASS;
		ItemStack stack = player.getItemInHand(hand);
		if (!stack.is(Items.BELL)) return InteractionResult.PASS;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null || !data.copyTag().contains(RELOCATION_TAG)) return InteractionResult.PASS;
		BlockPos place = hit.getBlockPos().relative(hit.getDirection());
		PENDING_PLACES.put(place.asLong(), new PendingPlace(player.getUUID(), place, stack.copy(), level.getGameTime() + 4L));
		return InteractionResult.PASS;
	}

	private static void tickPendingPlaces(net.minecraft.server.MinecraftServer server) {
		if (PENDING_PLACES.isEmpty()) return;
		long now = server.overworld().getGameTime();
		PENDING_PLACES.entrySet().removeIf(entry -> {
			PendingPlace pending = entry.getValue();
			if (now > pending.until()) return true;
			ServerLevel level = server.getLevel(server.overworld().dimension());
			if (level == null) return false;
			if (!LoadedColumns.ready(level, pending.pos()) || !level.getBlockState(pending.pos()).is(Blocks.BELL)) return false;
			BlockEntity be = level.getBlockEntity(pending.pos());
			if (!(be instanceof BellBlockEntity bellBe)) return true;
			Player player = server.getPlayerList().getPlayer(pending.playerId());
			if (player == null) return true;
			afterPlace(level, player, pending.pos(), level.getBlockState(pending.pos()), bellBe, pending.stack());
			return true;
		});
	}

	private static boolean beforeBreak(Level world, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (world.isClientSide() || !(world instanceof ServerLevel level) || !(blockEntity instanceof BellBlockEntity bellBe)) {
			return true;
		}
		if (!state.is(Blocks.BELL) || bellBe.getAttachedOrCreate(VillagerPlus.VILLAGE) == null) {
			return true;
		}
		if (player.isCreative() && !player.isShiftKeyDown()) return true;
		long now = level.getGameTime();
		if (player.isShiftKeyDown()) {
			dropRelocationBell(level, player, pos, bellBe);
			return true;
		}
		Pending pending = PENDING.get(player.getUUID());
		boolean confirmed = pending != null && pending.bellPos() == pos.asLong() && now <= pending.until();
		if (!confirmed) {
			PENDING.put(player.getUUID(), new Pending(pos.asLong(), now + CONFIRM_TICKS));
			if (player instanceof ServerPlayer sp) {
				VillageMessages.overlay(sp, Component.translatable("message.villagerplus.bell_break_warning"));
			}
			return false;
		}
		PENDING.remove(player.getUUID());
		return true;
	}

	private static void dropRelocationBell(ServerLevel level, Player player, BlockPos pos, BellBlockEntity bellBe) {
		CompoundTag village = exportBellData(bellBe);
		ItemStack bell = new ItemStack(Items.BELL);
		CompoundTag root = new CompoundTag();
		root.put(RELOCATION_TAG, village);
		bell.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		level.removeBlock(pos, false);
		level.removeBlockEntity(pos);
		ItemEntity drop = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, bell);
		drop.setDefaultPickUpDelay();
		level.addFreshEntity(drop);
		if (player instanceof ServerPlayer sp) {
			VillageMessages.overlay(sp, Component.translatable("message.villagerplus.bell_relocated_pickup"));
		}
	}

	private static void afterPlace(ServerLevel level, Player player, BlockPos pos, BlockState state, BellBlockEntity bellBe, ItemStack stack) {
		if (!stack.is(Items.BELL)) return;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null || !data.copyTag().contains(RELOCATION_TAG)) return;
		if (Villages.villageNear(level, pos, de.villagerplus.Config.VILLAGE_RADIUS) != null) {
			if (player instanceof ServerPlayer sp) {
				VillageMessages.overlay(sp, Component.translatable("message.villagerplus.bell_place_too_close"));
			}
			level.removeBlock(pos, false);
			return;
		}
		importBellData(bellBe, data.copyTag().getCompoundOrEmpty(RELOCATION_TAG));
		stack.remove(DataComponents.CUSTOM_DATA);
		if (player instanceof ServerPlayer sp) {
			VillageMessages.overlay(sp, Component.translatable("message.villagerplus.bell_relocated_place"));
		}
		Villages.noteChunk(level.getChunkAt(pos));
	}

	private static CompoundTag exportBellData(BellBlockEntity bell) {
		CompoundTag out = new CompoundTag();
		encode(out, "village", VillageData.CODEC, bell.getAttachedOrCreate(VillagerPlus.VILLAGE));
		encode(out, "zones", ZonePolicy.Zone.CODEC.listOf(), bell.getAttachedOrElse(VillagerPlus.ZONES, List.of()));
		encode(out, "buildings", Built.CODEC.listOf(), bell.getAttachedOrElse(VillagerPlus.BUILDINGS, List.of()));
		encode(out, "village_name", com.mojang.serialization.Codec.STRING, bell.getAttached(VillagerPlus.VILLAGE_NAME));
		return out;
	}

	private static <T> void encode(CompoundTag out, String key, com.mojang.serialization.Codec<T> codec, T value) {
		if (value == null) return;
		codec.encodeStart(NbtOps.INSTANCE, value).ifSuccess(tag -> out.put(key, tag));
	}

	private static void importBellData(BellBlockEntity bell, CompoundTag tag) {
		decode(tag, "village", VillageData.CODEC).ifPresent(v -> bell.setAttached(VillagerPlus.VILLAGE, v));
		decode(tag, "zones", ZonePolicy.Zone.CODEC.listOf()).ifPresent(z -> bell.setAttached(VillagerPlus.ZONES, z));
		decode(tag, "buildings", Built.CODEC.listOf()).ifPresent(b -> bell.setAttached(VillagerPlus.BUILDINGS, b));
		decode(tag, "village_name", com.mojang.serialization.Codec.STRING).ifPresent(n -> bell.setAttached(VillagerPlus.VILLAGE_NAME, n));
	}

	private static <T> java.util.Optional<T> decode(CompoundTag tag, String key, com.mojang.serialization.Codec<T> codec) {
		if (!tag.contains(key)) return java.util.Optional.empty();
		return codec.parse(NbtOps.INSTANCE, tag.get(key)).result();
	}

	public static boolean protectFromExplosion(ServerLevel level, BlockPos pos) {
		if (!LoadedColumns.ready(level, pos) || !level.getBlockState(pos).is(Blocks.BELL)) return false;
		BlockEntity be = level.getBlockEntity(pos);
		return be instanceof BellBlockEntity bellBe && bellBe.getAttachedOrCreate(VillagerPlus.VILLAGE) != null;
	}
}