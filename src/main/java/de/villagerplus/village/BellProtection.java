package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.world.LoadedColumns;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Village bell breaks require confirmation; sneak-break moves village data on the dropped bell item.
 * {@link de.villagerplus.mixin.VillageBellExplosionMixin} keeps bells from being destroyed by explosions.
 */
public final class BellProtection {
	public static final long CONFIRM_TICKS = 100L;
	public static final String RELOCATION_TAG = BellRelocation.RELOCATION_TAG;

	private record Pending(long bellPos, long until) { }
	private record PendingPlace(UUID playerId, BlockPos pos, UUID token, long until) { }

	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final Map<Long, PendingPlace> PENDING_PLACES = new HashMap<>();
	/** Ungenutzte Umzugs-Tokens (nach Serverstart leer — Platzierung aus Item-NBT ist dann einmalig möglich). */
	private static final Set<UUID> OPEN_RELOCATION_TOKENS = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> CONSUMED_RELOCATION_TOKENS = ConcurrentHashMap.newKeySet();

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
		OPEN_RELOCATION_TOKENS.clear();
		CONSUMED_RELOCATION_TOKENS.clear();
	}

	/** Entfernt Dorfdaten aus einer Item-Kopie (Kreativ-Duplett / Pick-Block-Klon). */
	public static void stripRelocationCopy(ItemStack copy) {
		if (copy == null || copy.isEmpty() || !copy.is(Items.BELL)) return;
		CustomData data = copy.get(DataComponents.CUSTOM_DATA);
		if (data == null || !data.copyTag().contains(RELOCATION_TAG)) return;
		copy.remove(DataComponents.CUSTOM_DATA);
	}

	private static InteractionResult onUseBlock(Player player, Level world, net.minecraft.world.InteractionHand hand,
		net.minecraft.world.phys.BlockHitResult hit) {
		if (world.isClientSide() || !(world instanceof ServerLevel level)) return InteractionResult.PASS;
		ItemStack stack = player.getItemInHand(hand);
		UUID token = relocationToken(stack);
		if (token == null) return InteractionResult.PASS;
		BlockPos place = hit.getBlockPos().relative(hit.getDirection());
		PENDING_PLACES.put(place.asLong(), new PendingPlace(player.getUUID(), place, token, level.getGameTime() + 4L));
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
			ItemStack stack = findRelocationBell(player, pending.token());
			if (stack == null) return true;
			afterPlace(level, player, pending.pos(), bellBe, stack);
			return true;
		});
	}

	private static ItemStack findRelocationBell(Player player, UUID token) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (matchesToken(stack, token)) return stack;
		}
		return null;
	}

	private static boolean matchesToken(ItemStack stack, UUID token) {
		UUID found = relocationToken(stack);
		return found != null && found.equals(token);
	}

	private static UUID relocationToken(ItemStack stack) {
		if (stack == null || !stack.is(Items.BELL)) return null;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null || !data.copyTag().contains(RELOCATION_TAG)) return null;
		var tag = data.copyTag().getCompoundOrEmpty(RELOCATION_TAG);
		if (!tag.contains(BellRelocation.TOKEN_KEY)) return null;
		try {
			return UUID.fromString(tag.getStringOr(BellRelocation.TOKEN_KEY, ""));
		} catch (IllegalArgumentException ignored) {
			return null;
		}
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
		UUID token = UUID.randomUUID();
		var payload = BellRelocation.export(bellBe, pos, level.dimension(), token);
		OPEN_RELOCATION_TOKENS.add(token);
		ItemStack bell = new ItemStack(Items.BELL);
		var root = new net.minecraft.nbt.CompoundTag();
		root.put(RELOCATION_TAG, payload);
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

	private static void afterPlace(ServerLevel level, Player player, BlockPos pos, BellBlockEntity bellBe, ItemStack stack) {
		if (!stack.is(Items.BELL)) return;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null || !data.copyTag().contains(RELOCATION_TAG)) return;
		var payload = data.copyTag().getCompoundOrEmpty(RELOCATION_TAG);
		UUID token = relocationToken(stack);
		if (token != null && CONSUMED_RELOCATION_TOKENS.contains(token)) {
			rejectPlace(level, player, pos, "message.villagerplus.bell_relocate_duplicate");
			return;
		}
		if (token != null && !OPEN_RELOCATION_TOKENS.remove(token)) {
			// Nach Reload: Token-Set leer, aber Item-NBT darf einmal konsumiert werden.
			if (CONSUMED_RELOCATION_TOKENS.contains(token)) {
				rejectPlace(level, player, pos, "message.villagerplus.bell_relocate_duplicate");
				return;
			}
		}
		if (Villages.villageNear(level, pos, Config.VILLAGE_RADIUS) != null) {
			if (token != null) OPEN_RELOCATION_TOKENS.add(token);
			rejectPlace(level, player, pos, "message.villagerplus.bell_place_too_close");
			return;
		}
		BellRelocation.ImportResult result = BellRelocation.importData(bellBe, payload, pos, level.dimension());
		if (!result.success()) {
			if (token != null) OPEN_RELOCATION_TOKENS.add(token);
			rejectPlace(level, player, pos, result.rejectionKey());
			return;
		}
		if (token != null) CONSUMED_RELOCATION_TOKENS.add(token);
		stack.remove(DataComponents.CUSTOM_DATA);
		realignResidents(level, result.oldBell(), pos, result.delta());
		if (player instanceof ServerPlayer sp) {
			VillageMessages.overlay(sp, Component.translatable("message.villagerplus.bell_relocated_place"));
			for (String key : result.noteKeys()) {
				VillageMessages.overlay(sp, Component.translatable(key));
			}
		}
		Villages.noteChunk(level.getChunkAt(pos));
	}

	private static void rejectPlace(ServerLevel level, Player player, BlockPos pos, String messageKey) {
		if (player instanceof ServerPlayer sp) {
			VillageMessages.overlay(sp, Component.translatable(messageKey));
		}
		level.removeBlock(pos, false);
	}

	private static void realignResidents(ServerLevel level, BlockPos oldBell, BlockPos newBell, BlockPos delta) {
		if (oldBell == null || delta.equals(BlockPos.ZERO)) return;
		String tag = "vp_resident_bell:" + oldBell.asLong();
		String newTag = "vp_resident_bell:" + newBell.asLong();
		int radius = Math.max(Config.VILLAGE_RADIUS, 128);
		AABB box = new AABB(oldBell).inflate(radius).minmax(new AABB(newBell).inflate(radius));
		for (Villager villager : level.getEntitiesOfClass(Villager.class, box, Villager::isAlive)) {
			if (!villager.entityTags().contains(tag)) continue;
			villager.removeTag(tag);
			villager.addTag(newTag);
			if (!delta.equals(BlockPos.ZERO)) {
				shiftMemory(villager, delta);
			}
		}
	}

	private static void shiftMemory(Villager villager, BlockPos delta) {
		VillagerMemory memory = villager.getAttachedOrCreate(VillagerPlus.MEMORY);
		memory.homeBed = memory.homeBed.map(p -> p.offset(delta));
		memory.homeDoor = memory.homeDoor.map(p -> p.offset(delta));
		memory.signPos = memory.signPos.map(p -> p.offset(delta));
		memory.homeChest = memory.homeChest.map(p -> p.offset(delta));
		for (int i = 0; i < memory.containers.size(); i++) {
			memory.containers.set(i, memory.containers.get(i).offset(delta));
		}
		for (int i = 0; i < memory.workSpots.size(); i++) {
			memory.workSpots.set(i, memory.workSpots.get(i).offset(delta));
		}
		villager.setAttached(VillagerPlus.MEMORY, memory);
	}

	public static boolean protectFromExplosion(ServerLevel level, BlockPos pos) {
		if (!LoadedColumns.ready(level, pos) || !level.getBlockState(pos).is(Blocks.BELL)) return false;
		BlockEntity be = level.getBlockEntity(pos);
		return be instanceof BellBlockEntity bellBe && bellBe.getAttachedOrCreate(VillagerPlus.VILLAGE) != null;
	}
}