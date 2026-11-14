package de.villagerplus.build;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Shipped building templates (vanilla structure files in {@code data/villagerplus/templates}), one file per building
 * type and biome family. They only supply the SHAPE: the blocks are still placed one by one from paid village stock
 * through {@link HouseTemplate#steps}, with the usual material substitution for what the village cannot supply.
 */
public final class TemplateLibrary {
	private TemplateLibrary() { }

	private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
	private static final Map<String, HouseTemplate> CACHE = new HashMap<>();

	/** Biome family of a biome id: desert / taiga / plains (the three shipped material sets). */
	public static String family(Identifier biome) {
		String p = biome == null ? "" : biome.getPath();
		if (p.contains("desert") || p.contains("badlands") || p.contains("savanna") || p.contains("mesa")) return "desert";
		if (p.contains("taiga") || p.contains("snow") || p.contains("frozen") || p.contains("grove") || p.contains("peaks")
			|| p.contains("ice") || p.contains("pine") || p.contains("spruce")) return "taiga";
		return "plains";
	}

	public static boolean has(String type) {
		return TemplateLibrary.class.getResource(path(type, "plains")) != null;
	}

	private static String path(String type, String family) {
		return "/data/villagerplus/templates/" + type + "_" + family + ".nbt";
	}

	/** Template for a building type in the biome family, or null when none is shipped or it cannot be read. */
	public static synchronized HouseTemplate get(String type, String family) {
		String key = type + "_" + family;
		if (CACHE.containsKey(key)) return CACHE.get(key);
		HouseTemplate loaded = null;
		try (InputStream in = TemplateLibrary.class.getResourceAsStream(path(type, family))) {
			if (in != null) loaded = parse(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()), "house".equals(type) || type.startsWith("haus"));
		} catch (Exception failure) {
			LOGGER.warn("Villagers: Repurposed: Vorlage {} nicht lesbar", key, failure);
		}
		CACHE.put(key, loaded);
		return loaded;
	}

	static HouseTemplate parse(CompoundTag root, boolean addBed) {
		ListTag size = root.getListOrEmpty("size");
		int sx = size.getIntOr(0, 0), sz = size.getIntOr(2, 0);
		if (sx <= 0 || sz <= 0) return null;
		ListTag paletteTag = root.getListOrEmpty("palette");
		BlockState[] palette = new BlockState[paletteTag.size()];
		for (int i = 0; i < palette.length; i++) palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteTag.getCompoundOrEmpty(i));
		int cx = (sx - 1) / 2, cz = (sz - 1) / 2;
		Map<BlockPos, BlockState> rel = new HashMap<>();
		Direction outward = Direction.SOUTH;
		ListTag blocks = root.getListOrEmpty("blocks");
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag b = blocks.getCompoundOrEmpty(i);
			ListTag pos = b.getListOrEmpty("pos");
			BlockState state = palette[Math.max(0, Math.min(palette.length - 1, b.getIntOr("state", 0)))];
			if (state.isAir()) continue;
			// Structure files put the floor at layer 0: the build centre's floor top is one block below the origin.
			rel.put(new BlockPos(pos.getIntOr(0, 0) - cx, pos.getIntOr(1, 0) - 1, pos.getIntOr(2, 0) - cz), state);
			if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				// A door faces from the outside towards the inside, so the outside is the opposite side.
				outward = state.getValue(DoorBlock.FACING).getOpposite();
			}
		}
		if (addBed) {
			// The files ship no furniture: a house needs a bed, otherwise nobody can call it home. Head against the wall
			// opposite the door: two beds side by side (the old standard houses slept two).
			Direction inward = outward.getOpposite();
			Direction side = inward.getClockWise();
			for (int offset : new int[] {-1, 1}) {
				BlockPos head = new BlockPos(0, 0, 0).relative(inward, 2).relative(side, offset);
				BlockPos foot = head.relative(outward);
				if (rel.containsKey(head) || rel.containsKey(foot)) continue;
				rel.put(foot, Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, inward).setValue(BedBlock.PART, BedPart.FOOT));
				rel.put(head, Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, inward).setValue(BedBlock.PART, BedPart.HEAD));
			}
		}
		return HouseTemplate.fromBlocks(rel, outward);
	}
}
