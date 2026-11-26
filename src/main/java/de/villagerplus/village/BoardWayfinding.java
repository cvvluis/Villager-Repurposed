package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorftafelBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/** Wegweiser zur Dorftafel: Chat-Koordinaten und gedrosselte Actionbar im Dorfgebiet. */
public final class BoardWayfinding {
	private static final Map<UUID, Long> NEXT_HINT = new HashMap<>();
	private static final long HINT_COOLDOWN_TICKS = 24000L;
	private static final int HINT_RADIUS = Config.VILLAGE_RADIUS;

	private BoardWayfinding() { }

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> NEXT_HINT.remove(handler.getPlayer().getUUID()));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!Config.BOARD_WAYFINDING) return;
			long now = server.overworld().getGameTime();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.isSpectator() || !(player.level() instanceof ServerLevel level)) continue;
				if (now < NEXT_HINT.getOrDefault(player.getUUID(), 0L)) continue;
				VillageRef ref = Villages.villageNear(level, player.blockPosition(), HINT_RADIUS);
				if (ref == null || ref.data().extra.board.isEmpty()) continue;
				BoundingBox box = Development.bounds(level, ref);
				BlockPos playerPos = player.blockPosition();
				if (!insideExtent(box, playerPos)) continue;
				BlockPos board = ref.data().extra.board.get();
				Component hint = distanceHint(playerPos, board);
				if (hint != null) {
					player.sendOverlayMessage(hint);
					NEXT_HINT.put(player.getUUID(), now + HINT_COOLDOWN_TICKS);
				}
			}
		});
	}

	private static boolean insideExtent(BoundingBox box, BlockPos pos) {
		return pos.getX() >= box.minX() && pos.getX() <= box.maxX()
			&& pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ();
	}

	public static Component distanceHint(BlockPos from, BlockPos board) {
		int dx = board.getX() - from.getX();
		int dz = board.getZ() - from.getZ();
		int dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
		String dir = compass(dx, dz);
		return Component.translatable("message.villagerplus.board_wayfind_hint", dist, dir);
	}

	private static String compass(int dx, int dz) {
		if (Math.abs(dx) < 4 && Math.abs(dz) < 4) return "hier";
		if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? "osten" : "westen";
		return dz > 0 ? "süden" : "norden";
	}

	public static MutableComponent coordinatesComponent(BlockPos pos, boolean allowTeleport) {
		String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
		MutableComponent text = Component.literal(pos.toShortString()).withStyle(ChatFormatting.AQUA)
			.withStyle(Style.EMPTY
				.withClickEvent(new ClickEvent.SuggestCommand(coords))
				.withHoverEvent(new HoverEvent.ShowText(Component.translatable("message.villagerplus.board_coords_copy"))));
		if (allowTeleport) {
			text = text.append(Component.literal(" "))
				.append(Component.translatable("message.villagerplus.board_coords_tp").withStyle(Style.EMPTY
					.withClickEvent(new ClickEvent.RunCommand("/tp @s " + coords))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("/tp")))));
		}
		return text;
	}

	public static void announcePlaza(ServerLevel level, BlockPos plaza, BlockPos board) {
		Component msg = board == null ? Component.translatable("message.villagerplus.plaza_built_no_board")
			: Component.translatable("message.villagerplus.plaza_built", board.toShortString());
		long radiusSq = 64L * 64L;
		for (ServerPlayer player : level.players()) {
			if (player.blockPosition().distSqr(plaza) <= radiusSq) {
				VillageMessages.overlay(player, msg);
			}
		}
		if (Config.DEBUG) {
			org.slf4j.LoggerFactory.getLogger("villagerplus").info("Plaza {} board {}", plaza.toShortString(), board == null ? "-" : board.toShortString());
		}
	}

	public static BlockPos officialBoard(VillageRef ref) {
		return ref == null ? null : ref.data().extra.board.orElse(null);
	}

	public static boolean hasOfficialBoard(ServerLevel level, VillageRef ref) {
		BlockPos board = officialBoard(ref);
		if (board == null) return false;
		var state = de.villagerplus.world.LoadedColumns.state(level, board);
		return state != null && state.getBlock() instanceof DorftafelBlock;
	}
}
