package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Privateigentum: Die Kiste im Haus gehoert dem Villager. Spieler duerfen sie weiterhin oeffnen, verlieren aber
 * Ansehen beim Besitzer (und dessen Nachbarn, die den Klatsch weitertragen), wenn sie sich bedienen oder die Kiste zerstoeren.
 */
public final class Ownership {
	private static final Map<UUID, Long> LAST_WARNING = new HashMap<>();

	private Ownership() {
	}

	/** Owner metadata for a loaded block entity only; this never loads a chunk. */
	public static UUID ownerAt(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !level.hasChunkAt(pos)) return null;
		BlockEntity be = level.getBlockEntity(pos);
		return be == null ? null : be.getAttached(VillagerPlus.OWNER);
	}

	public static boolean isOwnedBy(ServerLevel level, BlockPos pos, UUID owner) {
		return owner != null && owner.equals(ownerAt(level, pos));
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			check(player, world, hit.getBlockPos(), false);
			return InteractionResult.PASS;
		});
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			check(player, world, pos, true);
			return true;
		});
	}

	private static void check(Player player, Level world, BlockPos pos, boolean breaking) {
		if (!(world instanceof ServerLevel level) || player.isSpectator() || player.isCreative()) {
			return;
		}
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null) {
			return;
		}
		UUID ownerId = be.getAttached(VillagerPlus.OWNER);
		if (ownerId == null) {
			return;
		}
		Entity owner = level.getEntity(ownerId);
		if (!(owner instanceof Villager villager)) {
			return;
		}
		long now = level.getGameTime();
		Long last = LAST_WARNING.get(player.getUUID());
		if (!breaking && last != null && now - last < 1200L) {
			return;
		}
		LAST_WARNING.put(player.getUUID(), now);
		villager.getGossips().add(player.getUUID(), breaking ? GossipType.MAJOR_NEGATIVE : GossipType.MINOR_NEGATIVE, breaking ? 10 : 5);
		String name = villager.hasCustomName() ? villager.getCustomName().getString() : "einem Dorfbewohner";
		VillageMessages.overlay(player, Component.literal("Das ist die private Kiste von " + name + "!"));
	}
}
