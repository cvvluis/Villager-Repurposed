package de.villagerplus.village;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Formats Villages: Repurposed notifications without changing vanilla or other mods\' chat. */
public final class VillageMessages {
    /** Stable system-message marker; client-only toast styling keys off this exact prefix. */
    public static final String CHAT_MARKER = "◆ DORF  › ";

    private VillageMessages() {}

    public static Component format(Component message) {
        return Component.literal("◆ ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal("DORF").withStyle(ChatFormatting.DARK_GREEN).withStyle(style -> style.withBold(true)))
            .append(Component.literal("  › ").withStyle(ChatFormatting.DARK_GRAY))
            .append(message.copy());
    }

    /** The villager's own name, or a translatable stand-in (e.g. "message.villagerplus.name_the_villager") when it has none. */
    public static Component nameOr(String name, String fallbackKey) {
        return name == null || name.isEmpty() ? Component.translatable(fallbackKey) : Component.literal(name);
    }

    public static void system(Player player, Component message) {
        player.sendSystemMessage(format(message));
    }

    public static void overlay(Player player, Component message) {
        player.sendOverlayMessage(format(message));
    }
}
