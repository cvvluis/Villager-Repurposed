package de.villagerplus.block;

import de.villagerplus.village.VillageMessages;
import com.mojang.serialization.MapCodec;
import de.villagerplus.net.BoardData;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Die Dorftafel: ein eigener Block, drei Bloecke breit und zwei hoch (sechs Teile, jedes anklickbar) (Anschlagtafel mit Dach). Rechtsklick oeffnet das
 * Dorfverwaltungsmenue des naechsten Dorfes (Karte, Kennzahlen, Einwohner, Details, Expeditionen). Sie steht in jeder Dorfmitte
 * und kann auch selbst aufgestellt werden.
 */
public class DorftafelBlock extends BaseEntityBlock {
	public static final MapCodec<DorftafelBlock> CODEC = simpleCodec(DorftafelBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
	private static final VoxelShape NORTH = Block.box(0, 0, 5, 16, 16, 11);
	private static final VoxelShape SOUTH = Block.box(0, 0, 5, 16, 16, 11);
	private static final VoxelShape EAST = Block.box(5, 0, 0, 11, 16, 16);
	private static final VoxelShape WEST = Block.box(5, 0, 0, 11, 16, 16);

	/** Linke/rechte Haelfte, vom Betrachter aus gesehen. */
	public enum Part implements StringRepresentable {
		LEFT("left"), MIDDLE("middle"), RIGHT("right"),
		/** Second middle column of the four-block board; the old three-block board never uses it. */
		INNER("inner");

		private final String id;

		Part(String id) {
			this.id = id;
		}

		@Override
		public String getSerializedName() {
			return id;
		}
	}

	/** Four columns instead of three (the large board); fixed per block type. */
	private final boolean wide;

	public DorftafelBlock(Properties properties) {
		this(properties, false);
	}

	protected DorftafelBlock(Properties properties, boolean wide) {
		super(properties);
		this.wide = wide;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.LEFT).setValue(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	public boolean wide() {
		return wide;
	}

	/** Columns of a board: 3 for the old board, 4 for the large one. */
	public static int widthOf(BlockState state) {
		return state.getBlock() instanceof DorftafelBlock b && b.wide ? 4 : 3;
	}

	/** The part standing at a column index (left to right as seen from the front). */
	public static Part partAt(boolean wide, int index) {
		return wide ? new Part[] {Part.LEFT, Part.MIDDLE, Part.INNER, Part.RIGHT}[index]
			: new Part[] {Part.LEFT, Part.MIDDLE, Part.RIGHT}[index];
	}

	/** Column index of a part (inverse of partAt). */
	public static int indexOf(boolean wide, Part part) {
		return switch (part) {
			case LEFT -> 0;
			case MIDDLE -> 1;
			case INNER -> 2;
			case RIGHT -> wide ? 3 : 2;
		};
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, PART, HALF);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		// A single entity on the upper middle part owns the physical village-name display.
		if (state.getValue(PART) == Part.MIDDLE && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			return new DorftafelNameBlockEntity(pos, state);
		}
		return null;
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level instanceof ServerLevel
			? createTickerHelper(type, ModBlocks.DORFTAFEL_NAME, DorftafelNameBlockEntity::tick)
			: null;
	}

	/** Die unteren Teile (links nach rechts vom Betrachter aus); "rechts" liegt gegen den Uhrzeigersinn der Blickrichtung. */
	public static BlockPos[] parts(BlockState state, BlockPos pos) {
		boolean wide = widthOf(state) == 4;
		Direction r = state.getValue(FACING).getCounterClockWise();
		BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
		BlockPos left = lower.relative(r, -indexOf(wide, state.getValue(PART)));
		BlockPos[] out = new BlockPos[widthOf(state)];
		for (int i = 0; i < out.length; i++) out[i] = left.relative(r, i);
		return out;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		Direction f = ctx.getHorizontalDirection().getOpposite();
		for (int i = 0; i < (wide ? 4 : 3); i++) {
			BlockPos p = ctx.getClickedPos().relative(f.getCounterClockWise(), i);
			if ((i > 0 && !ctx.getLevel().getBlockState(p).canBeReplaced(ctx)) || !ctx.getLevel().getBlockState(p.above()).canBeReplaced(ctx)) {
				return null;
			}
		}
		return defaultBlockState().setValue(FACING, f).setValue(PART, Part.LEFT).setValue(HALF, DoubleBlockHalf.LOWER);
	}

	/** Setzt alle Teile ab der linken unteren Position (6 beim alten, 8 beim grossen Brett). */
	public static void placeAll(Level level, BlockPos left, BlockState base) {
		boolean wide = widthOf(base) == 4;
		Direction r = base.getValue(FACING).getCounterClockWise();
		if (base.getBlock() instanceof DorftafelGrossBlock) {
			base = base.setValue(DorftafelGrossBlock.WOOD, DorftafelGrossBlock.woodFor(level, left));
		}
		for (int i = 0; i < (wide ? 4 : 3); i++) {
			BlockPos p = left.relative(r, i);
			BlockState s = base.setValue(PART, partAt(wide, i));
			level.setBlock(p, s.setValue(HALF, DoubleBlockHalf.LOWER), 3);
			level.setBlock(p.above(), s.setValue(HALF, DoubleBlockHalf.UPPER), 3);
		}
	}

	/** Sind alle Teile vorhanden? */
	public static boolean complete(Level level, BlockPos anyPart, BlockState state) {
		boolean wide = widthOf(state) == 4;
		BlockPos[] lower = parts(state, anyPart);
		for (int i = 0; i < lower.length; i++) {
			for (BlockPos p : new BlockPos[] {lower[i], lower[i].above()}) {
				BlockState s = level.getBlockState(p);
				if (!(s.getBlock() instanceof DorftafelBlock) || s.getValue(PART) != partAt(wide, i)) {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		if (!level.isClientSide()) {
			placeAll(level, pos, state);
		}
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		for (BlockPos low : parts(state, pos)) {
			for (BlockPos other : new BlockPos[] {low, low.above()}) {
				if (!other.equals(pos) && level.getBlockState(other).is(this)) {
					level.setBlock(other, Blocks.AIR.defaultBlockState(), 35);
				}
			}
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return switch (state.getValue(FACING)) {
			case SOUTH -> SOUTH;
			case EAST -> EAST;
			case WEST -> WEST;
			default -> NORTH;
		};
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
			open(sl, sp, pos);
		}
		return InteractionResult.SUCCESS_SERVER;
	}

	/** Schickt dem Spieler die Daten des naechsten Dorfes; ohne Dorf in der Naehe gibt es nur einen Hinweis. */
	private static java.util.List<String> quotaAndMap(ServerLevel level, VillageRef ref) {
		java.util.List<String> rows = new java.util.ArrayList<>(de.villagerplus.village.Staffing.quotaRows(level, ref));
		rows.addAll(de.villagerplus.village.Demolition.mapRows(level, ref));
		rows.addAll(de.villagerplus.village.ZonePolicy.rows(ref));
		return rows;
	}

	public static void open(ServerLevel level, ServerPlayer player, BlockPos pos) {
		VillageRef ref = Villages.villageNear(level, pos, 96);
		if (ref == null) {
			VillageMessages.system(player, Component.literal("Zu dieser Dorftafel gehört (noch) kein Dorf – es fehlt eine Dorfglocke in der Nähe."));
			return;
		}
		ServerPlayNetworking.send(player, BoardData.build(level, ref, pos));
		ServerPlayNetworking.send(player, new de.villagerplus.net.QuotaData(quotaAndMap(level, ref)));
	}
}
