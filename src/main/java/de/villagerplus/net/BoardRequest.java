package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: refresh the board, optionally with one UUID-correlated selected-resident report. */
public record BoardRequest(BlockPos pos, String talkResidentId, int talkRequestId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BoardRequest> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "board_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BoardRequest> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, BoardRequest::pos,
		ByteBufCodecs.STRING_UTF8, BoardRequest::talkResidentId,
		ByteBufCodecs.VAR_INT, BoardRequest::talkRequestId,
		BoardRequest::new);

	public BoardRequest {
		talkResidentId = canonicalUuid(talkResidentId);
		if (talkResidentId.isBlank() || talkRequestId <= 0) {
			talkResidentId = "";
			talkRequestId = 0;
		}
	}

	public BoardRequest(BlockPos pos) {
		this(pos, "", 0);
	}

	private static String canonicalUuid(String value) {
		if (value == null || value.length() != 36) return "";
		try { return UUID.fromString(value).toString(); }
		catch (IllegalArgumentException ignored) { return ""; }
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
