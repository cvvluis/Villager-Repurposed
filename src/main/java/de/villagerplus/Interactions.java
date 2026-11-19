package de.villagerplus;

import de.villagerplus.village.VillageMessages;
import de.villagerplus.job.JobManager;
import de.villagerplus.village.VillagerInfo;
import de.villagerplus.village.Wellbeing;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
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