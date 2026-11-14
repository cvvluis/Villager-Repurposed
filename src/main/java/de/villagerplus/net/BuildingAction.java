package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server: validated map action. For move, target is the source and destination is the new anchor. */
public record BuildingAction(BlockPos board, BlockPos target, BlockPos destination, String action) implements CustomPacketPayload {
	public static final String DEMOLISH = "demolish";
	public static final String MOVE = "move";
	public static final CustomPacketPayload.Type<BuildingAction> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "building_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BuildingAction> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, BuildingAction::board, BlockPos.STREAM_CODEC, BuildingAction::target, BlockPos.STREAM_CODEC, BuildingAction::destination,
		ByteBufCodecs.STRING_UTF8, BuildingAction::action, BuildingAction::new);
	public BuildingAction(BlockPos board, BlockPos target, String action) { this(board, target, target, action); }

	/** Reject future/unknown packet actions before any world mutation. */
	public boolean supportedAction() {
		return DEMOLISH.equals(action) || MOVE.equals(action);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
