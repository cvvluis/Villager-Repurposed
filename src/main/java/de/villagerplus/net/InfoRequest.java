package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: Steckbriefanfrage mit Korrelations-ID fuer die passende Antwort. */
public record InfoRequest(int requestId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<InfoRequest> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "info_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, InfoRequest> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, InfoRequest::requestId, InfoRequest::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
