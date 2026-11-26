package de.villagerplus.build;

import de.villagerplus.village.VillageStyle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;

/** Maps the material roles of the shipped NBT designs without changing their shape or named details. */
final class TemplatePalette {
	private static final String VERSION = "villagerplus:nbt_palette_v1";
	private static final int ROLE_COUNT = 11;
	private enum Shell { WALL, TRIM, ROOF, OTHER }

	private TemplatePalette() { }

	static boolean marked(List<String> ids) {
		return ids != null && ids.size() == ROLE_COUNT + 1 && VERSION.equals(ids.getLast());
	}

	static List<String> ids(Palette palette) {
		List<String> ids = new ArrayList<>(palette.ids());
		ids.add(VERSION);
		return List.copyOf(ids);
	}

	static Palette saved(List<String> ids) {
		return marked(ids) ? Palette.ofIds(ids) : null;
	}

	static Block material(BlockPos relative, Block source, Palette palette, Identifier biome, boolean tallHouse) {
		Block replacement = role(relative, source, palette, biome, tallHouse);
		if (Supply.obtainable(replacement)) return replacement;
		VillageStyle style = new VillageStyle(palette.wall(), palette.floor(), palette.roofFull(), palette.roofStair(),
			palette.door(), palette.fence());
		return Supply.substitute(source, style, biome);
	}

	private static Block role(BlockPos relative, Block source, Palette palette, Identifier biome, boolean tallHouse) {
		var state = source.defaultBlockState();
		if (state.is(BlockTags.LOGS)) {
			return palette.frame() != null && palette.frame().defaultBlockState().is(BlockTags.LOGS) ? palette.frame() : null;
		}
		if (source instanceof DoorBlock) return palette.door() instanceof DoorBlock ? palette.door() : null;
		if (source instanceof FenceBlock) return palette.fence() instanceof FenceBlock ? palette.fence() : null;
		if (source instanceof StairBlock && relative.getY() >= 4) {
			return palette.roofStair() instanceof StairBlock ? palette.roofStair() : null;
		}
		if (source instanceof SlabBlock && relative.getY() >= 3) {
			return palette.roofSlab() instanceof SlabBlock ? palette.roofSlab() : null;
		}
		if (!fullBlock(source)) return null;
		if (relative.getY() == -1) {
			if (state.is(BlockTags.PLANKS)) return fullBlock(palette.floor()) ? palette.floor() : null;
			if (fullShell(source, biome) != Shell.OTHER || state.is(BlockTags.BASE_STONE_OVERWORLD)
				|| blockPath(source).contains("stone") || blockPath(source).equals("cobbled_deepslate")) {
				return fullBlock(palette.base()) ? palette.base() : null;
			}
			return null;
		}
		Block local = tallHouse
			? state.is(BlockTags.PLANKS) ? palette.wall() : null
			: switch (fullShell(source, biome)) {
				case WALL -> palette.wall();
				case TRIM -> palette.wall2();
				case ROOF -> palette.roofFull();
				case OTHER -> null;
			};
		return fullBlock(local) ? local : null;
	}

	/** Compact houses and warehouses share the same three role blocks in each shipped biome family. */
	private static Shell fullShell(Block source, Identifier biome) {
		String path = blockPath(source);
		return switch (TemplateLibrary.family(biome)) {
			case "taiga" -> switch (path) {
				case "birch_planks" -> Shell.WALL;
				case "deepslate_bricks" -> Shell.TRIM;
				case "dark_oak_planks" -> Shell.ROOF;
				default -> Shell.OTHER;
			};
			case "desert" -> switch (path) {
				case "smooth_sandstone" -> Shell.WALL;
				case "smooth_red_sandstone" -> Shell.TRIM;
				case "terracotta" -> Shell.ROOF;
				default -> Shell.OTHER;
			};
			default -> switch (path) {
				case "white_terracotta" -> Shell.WALL;
				case "stone_bricks" -> Shell.TRIM;
				case "spruce_planks" -> Shell.ROOF;
				default -> Shell.OTHER;
			};
		};
	}

	private static boolean fullBlock(Block block) {
		return block != null && block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	private static String blockPath(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).getPath();
	}
}
