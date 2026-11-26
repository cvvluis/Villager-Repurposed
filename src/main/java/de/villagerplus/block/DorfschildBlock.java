package de.villagerplus.block;

import de.villagerplus.village.VillageMessages;
import com.mojang.serialization.MapCodec;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobManager;
import de.villagerplus.village.Housing;
import de.villagerplus.village.Names;
import de.villagerplus.village.Wellbeing;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Hausschild neben der Tuer: Sterne zeigen die Berufsstufe, ein Berufs-Symbol (Weizen, Amboss, Buch ...) zeigt, wer hier wohnt; bei mehreren Bewohnern das
 * Familien-Symbol. Rechtsklick nennt Namen, Berufe und Stimmung. Ersetzt die Vanilla-Schilder (die sich zu Hunderten in der
 * Landschaft sammelten).
 */
public class DorfschildBlock extends Block {
	public static final MapCodec<DorfschildBlock> CODEC = simpleCodec(DorfschildBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final IntegerProperty ICON = IntegerProperty.create("icon", 0, 13);
	/** Berufsstufe des (besten) Bewohners als Sterne unter dem Symbol; 0 = keine. */
	public static final IntegerProperty LEVEL = IntegerProperty.create("level", 0, 5);
	private static final VoxelShape NORTH = Block.box(2, 2, 14, 14, 14, 16);
	private static final VoxelShape SOUTH = Block.box(2, 2, 0, 14, 14, 2);
	private static final VoxelShape EAST = Block.box(0, 2, 2, 2, 14, 14);
	private static final VoxelShape WEST = Block.box(14, 2, 2, 16, 14, 14);

	public DorfschildBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ICON, 0).setValue(LEVEL, 0));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, ICON, LEVEL);
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
		if (level instanceof ServerLevel sl) {
			for (String line : describe(sl, pos)) VillageMessages.system(player, Component.literal(line));
		}
		return InteractionResult.SUCCESS_SERVER;
	}

	/** Name, Funktion, Bewohner und Status der am Schild gemeldeten Hausbewohner. */
	public static java.util.List<String> describe(ServerLevel level, BlockPos pos) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		List<Villager> nearby = level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(40.0), Villager::isAlive);
		List<Villager> residents = Housing.residentsWithBeds(level, nearby, memory -> memory.signPos.filter(pos::equals).isPresent());
		// Older signs can exist before any resident has saved the sign position.
		if (residents.isEmpty()) {
			residents = Housing.residentsWithBeds(level, nearby,
				memory -> memory.signPos.isEmpty() && memory.homeDoor.map(door -> door.distSqr(pos) <= 16).orElse(false));
		}
		if (residents.isEmpty()) {
			lines.add("Dieses Haus steht leer.");
			return lines;
		}
		lines.add(residents.size() > 1 ? "Haus der Familie:" : "Hier wohnt:");
		for (Villager v : residents) {
			VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
			Wellbeing.Status st = Wellbeing.of(level, v, mem);
			lines.add(" • " + (mem.name.isEmpty() ? "?" : mem.name) + " – " + Names.profession(v.getVillagerData().profession())
				+ " Stufe " + v.getVillagerData().level() + " – " + JobManager.activityOf(v, level) + " – Stimmung " + st.mood() + " %");
		}
		return lines;
	}
}
