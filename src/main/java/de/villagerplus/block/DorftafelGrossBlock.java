package de.villagerplus.block;

import com.mojang.serialization.MapCodec;
import de.villagerplus.village.VillageAdmin;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStyle;
import de.villagerplus.village.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * The large village board: four blocks wide, two high, wooden frame in the wood of the surroundings and a map in the middle.
 * The old three-block {@link DorftafelBlock} stays registered so boards in saved worlds keep working.
 */
public final class DorftafelGrossBlock extends DorftafelBlock {
	public static final MapCodec<DorftafelGrossBlock> CODEC = simpleCodec(DorftafelGrossBlock::new);
	public static final EnumProperty<Wood> WOOD = EnumProperty.create("wood", Wood.class);

	/** Frame woods that exist as planks, log and stripped log in the game. */
	public enum Wood implements StringRepresentable {
		OAK("oak"), SPRUCE("spruce"), BIRCH("birch"), JUNGLE("jungle"), ACACIA("acacia"), DARK_OAK("dark_oak"), MANGROVE("mangrove"), CHERRY("cherry");

		private final String id;

		Wood(String id) {
			this.id = id;
		}

		@Override
		public String getSerializedName() {
			return id;
		}

		static Wood fromName(String name) {
			if (name == null) return null;
			for (Wood w : values()) if (w.id.equals(name)) return w;
			return null;
		}
	}

	public DorftafelGrossBlock(Properties properties) {
		super(properties, true);
		registerDefaultState(defaultBlockState().setValue(WOOD, Wood.OAK));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(WOOD);
	}

	/** Wood of the village (its building wood or the wood of its floors), else the wood that fits the biome. */
	public static Wood woodFor(Level level, BlockPos pos) {
		if (level instanceof ServerLevel sl) {
			VillageRef ref = Villages.villageNear(sl, pos, 96);
			if (ref != null) {
				Wood byStem = Wood.fromName(VillageAdmin.woodStem(ref));
				if (byStem != null) return byStem;
				try {
					Block floor = VillageStyle.detect(sl, ref.bell(), null).floor();
					if (floor != null && floor.defaultBlockState().is(BlockTags.PLANKS)) {
						String path = BuiltInRegistries.BLOCK.getKey(floor).getPath();
						Wood byFloor = Wood.fromName(path.endsWith("_planks") ? path.substring(0, path.length() - "_planks".length()) : null);
						if (byFloor != null) return byFloor;
					}
				} catch (RuntimeException ignored) {
					// style detection is best effort; the biome decides below
				}
			}
		}
		String biome = level.getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
		if (biome.contains("cherry")) return Wood.CHERRY;
		if (biome.contains("mangrove")) return Wood.MANGROVE;
		if (biome.contains("jungle") || biome.contains("bamboo")) return Wood.JUNGLE;
		if (biome.contains("dark_forest")) return Wood.DARK_OAK;
		if (biome.contains("birch")) return Wood.BIRCH;
		if (biome.contains("desert") || biome.contains("badlands") || biome.contains("savanna") || biome.contains("mesa")) return Wood.ACACIA;
		if (biome.contains("taiga") || biome.contains("snow") || biome.contains("frozen") || biome.contains("grove")
			|| biome.contains("peaks") || biome.contains("pine")) return Wood.SPRUCE;
		return Wood.OAK;
	}
}
