package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client request to create/delete a bounded village map zone. */
public record SetZone(BlockPos board, int minX, int minZ, int maxX, int maxZ, String kind, boolean delete, int requestId) implements CustomPacketPayload {
	public static final Type<SetZone> TYPE = new Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "set_zone"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SetZone> CODEC = StreamCodec.of(
		(buf, payload) -> {
			BlockPos.STREAM_CODEC.encode(buf, payload.board());
			ByteBufCodecs.VAR_INT.encode(buf, payload.minX());
			ByteBufCodecs.VAR_INT.encode(buf, payload.minZ());
			ByteBufCodecs.VAR_INT.encode(buf, payload.maxX());
			ByteBufCodecs.VAR_INT.encode(buf, payload.maxZ());
			ByteBufCodecs.STRING_UTF8.encode(buf, payload.kind());
			ByteBufCodecs.BOOL.encode(buf, payload.delete());
			ByteBufCodecs.VAR_INT.encode(buf, payload.requestId());
		},
		buf -> new SetZone(BlockPos.STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
			ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
			ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.VAR_INT.decode(buf)));

	/** Compatibility constructor for focused server checks and older internal callers. */
	public SetZone(BlockPos board, int minX, int minZ, int maxX, int maxZ, String kind, boolean delete) {
		this(board, minX, minZ, maxX, maxZ, kind, delete, 1);
	}

	/** Authoritative server result for a map-zone request. */
	public record Result(BlockPos board, int requestId, boolean success, String message) implements CustomPacketPayload {
		public static final Type<Result> TYPE = new Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "set_zone_result"));
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
