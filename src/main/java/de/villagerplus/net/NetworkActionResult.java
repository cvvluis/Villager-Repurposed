package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-confirmed result for one neighbor-tab action request. */
public record NetworkActionResult(BlockPos board, int requestId, boolean success, String message) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<NetworkActionResult> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "network_action_result"));
	public static final StreamCodec<RegistryFriendlyByteBuf, NetworkActionResult> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, NetworkActionResult::board,
		ByteBufCodecs.VAR_INT, NetworkActionResult::requestId,
		ByteBufCodecs.BOOL, NetworkActionResult::success,
		ByteBufCodecs.STRING_UTF8, NetworkActionResult::message,
		NetworkActionResult::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
