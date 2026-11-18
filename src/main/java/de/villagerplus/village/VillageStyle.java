package de.villagerplus.village;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Baustil eines Dorfes. Wird aus den vorhandenen Haeusern (um die Betten herum) gelesen, funktioniert deshalb auch
 * mit Dorfern aus Weltgenerator-Mods. Fehlt etwas, greift ein Standard passend zum Dorftyp.
 */
public record VillageStyle(Block wall, Block floor, Block roof, Block stairs, Block door, Block fence) {

	public static VillageStyle detect(ServerLevel level, BlockPos bell, Villager hint) {
		Map<Block, Integer> walls = new HashMap<>();
		Map<Block, Integer> floors = new HashMap<>();
		Map<Block, Integer> roofs = new HashMap<>();
		Map<Block, Integer> stairs = new HashMap<>();
		Map<Block, Integer> doors = new HashMap<>();

		List<BlockPos> beds = de.villagerplus.world.LoadedPoi.positionsInRange(level, h -> h.is(PoiTypes.HOME), bell, 48, PoiManager.Occupancy.ANY)
			.stream().sorted(java.util.Comparator.comparingDouble(bed -> bed.distSqr(bell))).limit(12).toList();

		for (BlockPos bed : beds) {
			BlockPos under = bed.below();
			BlockState us = level.getBlockState(under);
			if (full(level, under, us) && !natural(us)) {
				floors.merge(us.getBlock(), 1, Integer::sum);
			}
			for (BlockPos p : BlockPos.betweenClosed(bed.offset(-5, 0, -5), bed.offset(5, 5, 5))) {
				BlockState s = level.getBlockState(p);
				if (s.isAir()) {
					continue;
				}
				Block b = s.getBlock();
				int rel = p.getY() - bed.getY();
				if (b instanceof StairBlock) {
					if (rel >= 2) {
						stairs.merge(b, 1, Integer::sum);
					}
				} else if (b instanceof DoorBlock d) {
					if (d.type().canOpenByHand()) {
						doors.merge(b, 1, Integer::sum);
					}
				} else if (!(b instanceof BedBlock) && !s.is(BlockTags.LOGS) && full(level, p, s) && !natural(s)) {
					if (rel >= 3) {
						roofs.merge(b, 1, Integer::sum);
					} else if (rel <= 2) {
						walls.merge(b, 1, Integer::sum);
					}
				}
			}
		}

		VillageStyle def = defaults(hint);
		Block wall = best(walls, def.wall);
		Block floor = best(floors, def.floor);
		Block roof = best(roofs, def.roof);
		Block stair = best(stairs, def.stairs);
		Block door = best(doors, def.door);
		return new VillageStyle(wall, floor, roof, stair, door, def.fence);
	}

	private static boolean full(ServerLevel level, BlockPos p, BlockState s) {
		return s.isCollisionShapeFullBlock(level, p);
	}

	/** Gewachsener Untergrund zaehlt nicht als Baumaterial. */
	public static boolean natural(BlockState s) {
		return s.is(BlockTags.DIRT) || s.is(BlockTags.GRASS_BLOCKS) || s.is(BlockTags.MUD) || s.is(BlockTags.MOSS_BLOCKS)
			|| s.is(BlockTags.SUBSTRATE_OVERWORLD) || s.is(BlockTags.NYLIUM) || s.is(BlockTags.BADLANDS_TERRACOTTA) || s.is(BlockTags.ICE)
			|| s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.DIRT)
			|| s.is(BlockTags.LEAVES) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD)
			|| s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW) || s.is(Blocks.ICE)
			|| s.is(Blocks.PACKED_ICE) || s.is(Blocks.BEDROCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM);
	}

	private static Block best(Map<Block, Integer> tally, Block fallback) {
		Optional<Map.Entry<Block, Integer>> top = tally.entrySet().stream()
			.filter(e -> e.getKey().asItem() != Items.AIR && Block.byItem(e.getKey().asItem()) == e.getKey())
			.max(Map.Entry.comparingByValue());
		return top.map(Map.Entry::getKey).orElse(fallback);
	}

	// --- Standardstil je Dorftyp ----------------------------------------------------------------

	private static VillageStyle defaults(Villager hint) {
		String type = "plains";
		if (hint != null) {
			type = hint.getVillagerData().type().unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
		}
		return switch (type) {
			case "desert" -> of("sandstone", "smooth_sandstone", "cut_sandstone", "sandstone_stairs", "jungle_door", "jungle_fence");
			case "savanna" -> of("acacia_planks", "acacia_planks", "acacia_planks", "acacia_stairs", "acacia_door", "acacia_fence");
			case "snow", "taiga" -> of("cobblestone", "spruce_planks", "spruce_planks", "spruce_stairs", "spruce_door", "spruce_fence");
			default -> of("cobblestone", "oak_planks", "oak_planks", "oak_stairs", "oak_door", "oak_fence");
		};
	}

	private static VillageStyle of(String wall, String floor, String roof, String stairs, String door, String fence) {
		return new VillageStyle(block(wall), block(floor), block(roof), block(stairs), block(door), block(fence));
	}

	private static Block block(String name) {
		return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name)).orElse(Blocks.OAK_PLANKS);
	}
}
