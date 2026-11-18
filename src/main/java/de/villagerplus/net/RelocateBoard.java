package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client request to move the official village board to the requesting player's stand position. */
public record RelocateBoard(BlockPos board, int requestId) implements CustomPacketPayload {
	public static final Type<RelocateBoard> TYPE = new Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "relocate_board"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RelocateBoard> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, RelocateBoard::board,
		ByteBufCodecs.VAR_INT, RelocateBoard::requestId,
		RelocateBoard::new);

	public RelocateBoard(BlockPos board) {
		this(board, 1);
	}

	public record Result(BlockPos board, int requestId, boolean success, String message) implements CustomPacketPayload {
		public static final Type<Result> TYPE = new Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "relocate_board_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, Result::board,
			ByteBufCodecs.VAR_INT, Result::requestId,
			ByteBufCodecs.BOOL, Result::success,
			ByteBufCodecs.STRING_UTF8, Result::message,
			Result::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}