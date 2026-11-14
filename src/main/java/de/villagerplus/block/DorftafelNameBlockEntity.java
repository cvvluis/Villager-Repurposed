package de.villagerplus.block;

import de.villagerplus.village.Network;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Cached, synchronized village name displayed on the physical board. */
public final class DorftafelNameBlockEntity extends BlockEntity {
	private static final String VILLAGE_NAME_KEY = "village_name";
	private static final int REFRESH_INTERVAL = 20;
	private String villageName = "";
	/** Relief map samples (32 x 23): bits 0-3 terrain code, 4-6 height level, 8-12 marker; empty until the first scan. */
	public static final int MAP_W = 32;
	public static final int MAP_H = 23;
	private static final String RELIEF_KEY = "relief";
	private static final int RELIEF_INTERVAL = 200;
	private int[] relief = new int[0];

	/** Samples for the 3D map on the large board; empty when none were computed yet. */
	public int[] relief() {
		return relief;
	}

	public DorftafelNameBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.DORFTAFEL_NAME, pos, state);
	}

	public String villageName() {
		return villageName;
	}

	public static void tick(Level level, BlockPos pos, BlockState state, DorftafelNameBlockEntity entity) {
		if (!(level instanceof ServerLevel serverLevel) || Math.floorMod(serverLevel.getGameTime(), REFRESH_INTERVAL) != 0) {
			return;
		}

		VillageRef village = Villages.villageNear(serverLevel, pos, 96);
		String currentName = village == null ? "" : Network.villageName(village);
		boolean changed = !currentName.equals(entity.villageName);
		entity.villageName = currentName;
		if (village != null && DorftafelBlock.widthOf(state) == 4
			&& (entity.relief.length == 0 || Math.floorMod(serverLevel.getGameTime(), RELIEF_INTERVAL) == 0)) {
			try {
				int[] fresh = sampleRelief(serverLevel, village);
				if (!java.util.Arrays.equals(fresh, entity.relief)) {
					entity.relief = fresh;
					changed = true;
				}
			} catch (RuntimeException e) {
				// the map is decoration; a failed scan keeps the previous samples
			}
		}
		if (!changed) {
			return;
		}
		entity.setChanged();
		BlockState currentState = entity.getBlockState();
		serverLevel.sendBlockUpdated(pos, currentState, currentState, Block.UPDATE_ALL);
	}

	/** Samples terrain, height and markers of the village map into a 32 x 23 raster (one sample per map pixel). */
	public static int[] sampleRelief(ServerLevel level, VillageRef ref) {
		byte[][] grid = de.villagerplus.village.VillageMap.grid(level, ref);
		int grid48 = de.villagerplus.village.VillageMap.GRID;
		int cellSize = Math.max(1, de.villagerplus.village.VillageMap.gridCellSize(level, ref));
		int[] origin = de.villagerplus.village.VillageMap.gridOrigin(level, ref);
		double step = grid48 * (double) cellSize / MAP_W;
		double rowOffset = (MAP_W - MAP_H) / 2.0;
		int[] heights = new int[MAP_W * MAP_H];
		int[] terrain = new int[MAP_W * MAP_H];
		int[] marks = new int[MAP_W * MAP_H];
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int j = 0; j < MAP_H; j++) {
			for (int i = 0; i < MAP_W; i++) {
				double wx = origin[0] + (i + 0.5) * step;
				double wz = origin[1] + (j + rowOffset + 0.5) * step;
				int col = Math.max(0, Math.min(grid48 - 1, (int) ((wx - origin[0]) / cellSize)));
				int row = Math.max(0, Math.min(grid48 - 1, (int) ((wz - origin[1]) / cellSize)));
				int idx = j * MAP_W + i;
				terrain[idx] = grid[0][row * grid48 + col] & 15;
				marks[idx] = grid[1][row * grid48 + col] & 31;
				BlockPos at = BlockPos.containing(wx, 64, wz);
				if (terrain[idx] == 11 || !level.hasChunkAt(at)) {
					terrain[idx] = 11;
					heights[idx] = Integer.MIN_VALUE;
					continue;
				}
				int h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
				heights[idx] = h;
				min = Math.min(min, h);
				max = Math.max(max, h);
			}
		}
		int range = Math.max(8, max - min);
		int[] out = new int[MAP_W * MAP_H];
		for (int idx = 0; idx < out.length; idx++) {
			int level3 = 0;
			if (heights[idx] != Integer.MIN_VALUE && terrain[idx] != 3) {
				level3 = Math.max(0, Math.min(3, Math.round((heights[idx] - min) * 3.0F / range)));
			}
			out[idx] = terrain[idx] | (level3 << 4) | (marks[idx] << 8);
		}
		return out;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		output.putString(VILLAGE_NAME_KEY, villageName);
		if (relief.length > 0) {
			output.putIntArray(RELIEF_KEY, relief);
		}
		super.saveAdditional(output);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		villageName = input.getStringOr(VILLAGE_NAME_KEY, "");
		int[] loaded = input.getIntArray(RELIEF_KEY).orElse(new int[0]);
		relief = loaded.length == MAP_W * MAP_H ? loaded : new int[0];
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
