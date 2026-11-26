package de.villagerplus;

import de.villagerplus.village.VillageMessages;
import de.villagerplus.village.ChestAccess;
import de.villagerplus.block.ModItems;
import de.villagerplus.job.JobManager;
import de.villagerplus.village.VillagerInfo;
import de.villagerplus.village.Wellbeing;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Schleichen + Rechtsklick: Geschenke machen (bleibt im Gedaechtnis) bzw. nachfragen, was der Dorfbewohner weiss. */
public final class Interactions {
	private Interactions() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || !(world instanceof ServerLevel level)
				|| !player.getItemInHand(hand).is(ModItems.CHEST_ACCESS)) return InteractionResult.PASS;
			var targetId = player.getAttached(VillagerPlus.CHEST_ACCESS_TARGET);
			if (targetId == null) return InteractionResult.PASS;
			var target = level.getEntity(targetId);
			if (!(target instanceof Villager villager) || !villager.isAlive()) {
				player.removeAttached(VillagerPlus.CHEST_ACCESS_TARGET);
				VillageMessages.overlay(player, Component.translatable("message.villagerplus.chest_access.missing_villager"));
				return InteractionResult.FAIL;
			}
			var pos = hit.getBlockPos();
			var currentAccess = villager.getAttached(VillagerPlus.CHEST_ACCESS);
			if (player.isSpectator()) {
				player.removeAttached(VillagerPlus.CHEST_ACCESS_TARGET);
				return InteractionResult.FAIL;
			}
			if (player.isShiftKeyDown() && currentAccess != null && currentAccess.grantedBy().equals(player.getUUID())
				&& ChestAccess.includes(level, villager, pos)) {
				ChestAccess.revoke(villager, player.getUUID());
				player.removeAttached(VillagerPlus.CHEST_ACCESS_TARGET);
				VillageMessages.overlay(player, Component.translatable("message.villagerplus.chest_access.revoked", villager.getName()));
				return InteractionResult.SUCCESS;
			}
			if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 256.0
				|| villager.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 256.0
				|| player.isSpectator() || !(player instanceof ServerPlayer serverPlayer)
				|| !serverPlayer.mayInteract(level, pos)
				|| !(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) {
				player.removeAttached(VillagerPlus.CHEST_ACCESS_TARGET);
				VillageMessages.overlay(player, Component.translatable("message.villagerplus.chest_access.invalid_chest"));
				return InteractionResult.FAIL;
			}
			if (!ChestAccess.mayManage(villager, serverPlayer.getUUID())) {
				player.removeAttached(VillagerPlus.CHEST_ACCESS_TARGET);
				VillageMessages.overlay(player, Component.translatable("message.villagerplus.chest_access.already_granted"));
				return InteractionResult.FAIL;
			}
			if (!ChestAccess.assign(level, pos, serverPlayer, villager)) {
				player.removeAttached(VillagerPlus.CHEST_ACCESS_TARGET);
				VillageMessages.overlay(player, Component.translatable("message.villagerplus.chest_access.invalid_chest"));
				return InteractionResult.FAIL;
			}
			player.removeAttached(VillagerPlus.CHEST_ACCESS_TARGET);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.6, villager.getZ(), 6, 0.3, 0.3, 0.3, 0.0);
			VillageMessages.overlay(player, Component.translatable("message.villagerplus.chest_access.granted", villager.getName()));
			return InteractionResult.SUCCESS;
		});
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (hand == InteractionHand.MAIN_HAND && world instanceof ServerLevel && !player.isSpectator() && player.isShiftKeyDown()
				&& player.getItemInHand(hand).is(ModItems.CHEST_ACCESS) && entity instanceof Villager villager) {
				ChestAccess access = villager.getAttached(VillagerPlus.CHEST_ACCESS);
				if (access != null) {
					if (!ChestAccess.revoke(villager, player.getUUID())) {
						player.removeAttached(VillagerPlus.CHEST_ACCESS_TARGET);
						VillageMessages.overlay(player, Component.translatable("message.villagerplus.chest_access.already_granted"));
						return InteractionResult.FAIL;
					}
					player.removeAttached(VillagerPlus.CHEST_ACCESS_TARGET);
					VillageMessages.overlay(player, Component.translatable("message.villagerplus.chest_access.revoked", villager.getName()));
					return InteractionResult.SUCCESS;
				}
			}
			if (hand == InteractionHand.MAIN_HAND && !player.isSpectator() && world instanceof ServerLevel level && entity instanceof Villager villager
				&& player.getItemInHand(hand).is(ModItems.CHEST_ACCESS)) {
				player.setAttached(VillagerPlus.CHEST_ACCESS_TARGET, villager.getUUID());
				VillageMessages.overlay(player, Component.translatable("message.villagerplus.chest_access.select_chest", villager.getName()));
				return InteractionResult.SUCCESS;
			}
			if (hand == InteractionHand.MAIN_HAND && !player.isShiftKeyDown() && entity instanceof Villager tired && world instanceof ServerLevel tl
				&& !tired.isBaby() && Wellbeing.refusesTrade(Wellbeing.of(tl, tired, tired.getAttachedOrCreate(VillagerPlus.MEMORY)))) {
				VillageMessages.overlay(player, Component.translatable("message.villagerplus.villager_exhausted"));
				return InteractionResult.FAIL;
			}
			if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || !(entity instanceof Villager villager)
				|| !(world instanceof ServerLevel level)) {
				return InteractionResult.PASS;
			}
			ItemStack held = player.getItemInHand(hand);
			if (held.isEmpty()) {
				inspect(level, player, villager);
				return InteractionResult.SUCCESS;
			}
			if (gift(level, player, villager, held)) {
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
	}

	private static boolean gift(ServerLevel level, Player player, Villager villager, ItemStack held) {
		GossipType type;
		int amount;
		if (held.is(Items.DIAMOND)) {
			type = GossipType.MAJOR_POSITIVE;
			amount = 4;
		} else if (held.is(Items.EMERALD)) {
			type = GossipType.MINOR_POSITIVE;
			amount = 8;
		} else if (held.is(Items.BREAD) || held.is(Items.GOLDEN_CARROT) || held.is(Items.CAKE)) {
			type = GossipType.MINOR_POSITIVE;
			amount = 4;
		} else {
			return false;
		}
		if (!player.getAbilities().instabuild) {
			held.shrink(1);
		}
		villager.getGossips().add(player.getUUID(), type, amount);
		villager.getAttachedOrCreate(VillagerPlus.MEMORY).gifts.merge(player.getUUID(), 1, Integer::sum);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.6, villager.getZ(), 8, 0.4, 0.4, 0.4, 0.0);
		VillageMessages.overlay(player, Component.translatable("message.villagerplus.villager_thanks"));
		return true;
	}

	private static void inspect(ServerLevel level, Player player, Villager villager) {
		StringBuilder sb = new StringBuilder();
		for (String line : VillagerInfo.lines(level, villager, player)) {
			String[] p = line.split("\\|", 3);
			if (p.length >= 2 && !p[0].equals("Gespräche") && !p[0].equals("Preise") && !p[0].equals("Geschenke")) {
				sb.append(sb.length() == 0 ? "" : " | ").append(p[0]).append(": ").append(p[1]);
			}
		}
		VillageMessages.overlay(player, Component.literal(sb.toString()));
	}
}
