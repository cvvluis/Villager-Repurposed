package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Explicit player-granted access to one chest, kept separately from a resident's private home chest. */
public record ChestAccess(GlobalPos chest, UUID grantedBy, UUID chestInstance, Optional<UUID> neighborInstance) {
	public static final Codec<ChestAccess> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		GlobalPos.CODEC.fieldOf("chest").forGetter(ChestAccess::chest),
		net.minecraft.core.UUIDUtil.CODEC.fieldOf("granted_by").forGetter(ChestAccess::grantedBy),
		net.minecraft.core.UUIDUtil.CODEC.fieldOf("chest_instance").forGetter(ChestAccess::chestInstance),
		net.minecraft.core.UUIDUtil.CODEC.optionalFieldOf("neighbor_instance").forGetter(ChestAccess::neighborInstance)
	).apply(instance, ChestAccess::new));

	/** Grant only to a loaded, unlocked chest with no unresolved loot table. */
	public static ChestAccess grant(ServerLevel level, BlockPos pos, ServerPlayer player, UUID villagerId) {
		if (player.isSpectator() || !player.mayInteract(level, pos) || !safeToUse(level, pos, villagerId)) return null;
		BlockState state = level.getBlockState(pos);
		BlockPos neighbor = connectedChest(level, pos, state);
		if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE && neighbor == null) return null;
		Optional<UUID> neighborId = Optional.empty();
		if (neighbor != null) {
			if (!player.mayInteract(level, neighbor) || !safeToUse(level, neighbor, villagerId)) return null;
			neighborId = Optional.of(instanceId(level.getBlockEntity(neighbor)));
		}
		return new ChestAccess(GlobalPos.of(level.dimension(), pos), player.getUUID(),
			instanceId(level.getBlockEntity(pos)), neighborId);
	}

	/** Only the player who currently granted access may replace or revoke it. */
	public static boolean mayManage(Villager villager, UUID player) {
		ChestAccess current = villager.getAttached(VillagerPlus.CHEST_ACCESS);
		return current == null || current.grantedBy().equals(player);
	}

	/** Assigns an accessible chest without allowing another player to overwrite the existing grant. */
	public static boolean assign(ServerLevel level, BlockPos pos, ServerPlayer player, Villager villager) {
		if (!mayManage(villager, player.getUUID())) return false;
		ChestAccess access = grant(level, pos, player, villager.getUUID());
		if (access == null) return false;
		villager.setAttached(VillagerPlus.CHEST_ACCESS, access);
		return true;
	}

	/** Removes a grant only when the caller is its original grantor. */
	public static boolean revoke(Villager villager, UUID player) {
		ChestAccess current = villager.getAttached(VillagerPlus.CHEST_ACCESS);
		if (current == null || !current.grantedBy().equals(player)) return false;
		villager.removeAttached(VillagerPlus.CHEST_ACCESS);
		return true;
	}

	/** Returns the assigned chest only when its instance, shape, dimension and already-loaded chunks still match. */
	public static Container container(ServerLevel level, Villager villager) {
		ChestAccess access = villager.getAttached(VillagerPlus.CHEST_ACCESS);
		if (access == null || !access.chest().dimension().equals(level.dimension())) return null;
		BlockPos pos = access.chest().pos();
		if (!safeToUse(level, pos, villager.getUUID())
			|| !access.chestInstance().equals(currentInstance(level.getBlockEntity(pos)))) return null;
		BlockState state = level.getBlockState(pos);
		BlockPos neighbor = connectedChest(level, pos, state);
		if (access.neighborInstance().isEmpty()) {
			return state.getValue(ChestBlock.TYPE) == ChestType.SINGLE && neighbor == null
				? (Container) level.getBlockEntity(pos) : null;
		}
		if (neighbor == null || !safeToUse(level, neighbor, villager.getUUID())
			|| !access.neighborInstance().get().equals(currentInstance(level.getBlockEntity(neighbor)))) return null;
		return new CompoundContainer((Container) level.getBlockEntity(pos), (Container) level.getBlockEntity(neighbor));
	}

	/** Food can only be taken from the assigned chest at close range. */
	public static Container nearbyContainer(ServerLevel level, Villager villager, double reach) {
		ChestAccess access = villager.getAttached(VillagerPlus.CHEST_ACCESS);
		if (access == null || !access.chest().dimension().equals(level.dimension())) return null;
		BlockPos pos = access.chest().pos();
		if (villager.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > reach * reach) return null;
		return container(level, villager);
	}

	/** Whether a clicked position is one validated half of the resident's assigned chest. */
	public static boolean includes(ServerLevel level, Villager villager, BlockPos clicked) {
		ChestAccess access = villager.getAttached(VillagerPlus.CHEST_ACCESS);
		if (access == null || !access.chest().dimension().equals(level.dimension()) || container(level, villager) == null) return false;
		BlockPos primary = access.chest().pos();
		if (primary.equals(clicked)) return true;
		BlockPos neighbor = connectedChest(level, primary, level.getBlockState(primary));
		return neighbor != null && neighbor.equals(clicked);
	}

	private static boolean safeToUse(ServerLevel level, BlockPos pos, UUID villagerId) {
		if (!de.villagerplus.world.LoadedColumns.ready(level, pos)
			|| !(level.getBlockState(pos).getBlock() instanceof ChestBlock)) return false;
		if (StockPolicy.isRegisteredMaterialStore(level, pos)) return false;
		BlockEntity entity = level.getBlockEntity(pos);
		if (!(entity instanceof ChestBlockEntity chest) || chest.isLocked() || chest.getLootTable() != null) return false;
		UUID owner = chest.getAttached(VillagerPlus.OWNER);
		return owner == null || owner.equals(villagerId);
	}

	private static UUID instanceId(BlockEntity entity) {
		UUID id = entity.getAttached(VillagerPlus.CHEST_INSTANCE_ID);
		if (id == null) {
			id = UUID.randomUUID();
			entity.setAttached(VillagerPlus.CHEST_INSTANCE_ID, id);
		}
		return id;
	}

	private static UUID currentInstance(BlockEntity entity) {
		return entity == null ? null : entity.getAttached(VillagerPlus.CHEST_INSTANCE_ID);
	}

	/** Returns the other half only when both already-loaded chest states form a matching double chest. */
	private static BlockPos connectedChest(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
		Direction direction = ChestBlock.getConnectedDirection(state);
		BlockPos otherPos = pos.relative(direction);
		if (!de.villagerplus.world.LoadedColumns.ready(level, otherPos)) return null;
		BlockState other = level.getBlockState(otherPos);
		if (!(other.getBlock() instanceof ChestBlock)
			|| other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
			|| other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
			|| ChestBlock.getConnectedDirection(other) != direction.getOpposite()) return null;
		return otherPos;
	}
}
