package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: Aktion im Nachbarn-Menue, addressed to the selected link identity. */
public record NetworkAction(BlockPos board, String action, int index, String targetDimension, BlockPos targetBell, int requestId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<NetworkAction> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "network_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, NetworkAction> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, NetworkAction::board, ByteBufCodecs.STRING_UTF8, NetworkAction::action,
		ByteBufCodecs.VAR_INT, NetworkAction::index, ByteBufCodecs.STRING_UTF8, NetworkAction::targetDimension,
		BlockPos.STREAM_CODEC, NetworkAction::targetBell, ByteBufCodecs.VAR_INT, NetworkAction::requestId, NetworkAction::new);

	public NetworkAction(BlockPos board, String action, int index, String targetDimension, BlockPos targetBell) {
		this(board, action, index, targetDimension, targetBell, 0);
	}

	/** Legacy/test convenience; current clients include the selected dimension and bell position. */
	public NetworkAction(BlockPos board, String action, int index) {
		this(board, action, index, "", BlockPos.ZERO, 0);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
