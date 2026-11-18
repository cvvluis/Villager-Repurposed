package de.villagerplus.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Reads columns that are already fully present. {@code hasChunkAt} is not enough: a chunk holder can exist while
 * {@code getChunk}/{@code getHeight}/{@code getBlockState} still waits in {@code getChunkBlocking} and generates terrain.
 * {@code getChunkNow} returns only a chunk the server can use without waiting.
 */
public final class LoadedColumns {
	private LoadedColumns() { }

	public static @Nullable LevelChunk full(Level level, int blockX, int blockZ) {
		if (!(level instanceof ServerLevel server)) return null;
		return server.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ));
	}

	/** Surface height, or null when the column is not fully loaded. Never generates or waits. */
	public static @Nullable Integer height(Level level, Heightmap.Types type, int blockX, int blockZ) {
		LevelChunk chunk = full(level, blockX, blockZ);
		if (chunk == null) return null;
		// Level.getHeight adds one to ChunkAccess.getHeight (that method returns the last occupied block).
		return chunk.getHeight(type, blockX, blockZ) + 1;
	}

	/** Block in a fully loaded column, or null. Never generates or waits. */
	public static @Nullable BlockState state(Level level, BlockPos pos) {
		if (pos == null) return null;
		LevelChunk chunk = full(level, pos.getX(), pos.getZ());
		return chunk == null ? null : chunk.getBlockState(pos);
	}

	/**
	 * True only when the column can be read without waiting or generating. A {@link ServerLevel} is checked with
	 * {@code getChunkNow}; other readers keep {@code hasChunkAt} (the client does not generate chunks).
	 */
	public static boolean ready(net.minecraft.world.level.LevelReader level, BlockPos pos) {
		if (level == null || pos == null) return false;
		if (level instanceof ServerLevel server) {
			return server.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())) != null;
		}
		return level.hasChunkAt(pos);
	}

	public static boolean readyChunk(Level level, int chunkX, int chunkZ) {
		if (!(level instanceof ServerLevel server)) return false;
		return server.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
	}

	/** {@link Level#getHeight} when the column is ready, otherwise {@link Level#getMinY()} and never a chunk load. */
	public static int heightOrMinY(Level level, Heightmap.Types type, int blockX, int blockZ) {
		Integer h = height(level, type, blockX, blockZ);
		return h == null ? level.getMinY() : h;
	}

	/** Same position {@code getHeightmapPos} would return, or null when the column is not fully loaded. */
	public static @Nullable BlockPos heightmapPos(Level level, Heightmap.Types type, BlockPos column) {
		if (column == null) return null;
		Integer y = height(level, type, column.getX(), column.getZ());
		return y == null ? null : new BlockPos(column.getX(), y, column.getZ());
	}
}
