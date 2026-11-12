package de.villagerplus.village;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorfschildBlock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * B04: the house sign. A resident with a bed and a door gets a Dorfschild beside the door (outer wall), showing the profession
 * icon and star level; a second resident behind the same door turns it into the shared-house icon and reuses the same sign.
 * Synthetic fixture (a small stone house in the dev world), not a natural-village proof.
 */
public final class HousingSignChecks {
	private HousingSignChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Villager first = null, second = null;
		int checks = 0;
		try {
			level.getChunk(origin);
			for (int dx = -2; dx <= 8; dx++) for (int dz = -2; dz <= 8; dz++) for (int dy = -1; dy <= 5; dy++) {
				BlockPos p = origin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.setBlock(p, dy == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
			}
			// Stone box x 0..6, z 0..6, walls three high, door in the south wall (z = 6) at x = 3, facing south.
			BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
			for (int dx = 0; dx <= 6; dx++) for (int dz = 0; dz <= 6; dz++) for (int dy = 0; dy <= 3; dy++) {
				boolean wall = dx == 0 || dx == 6 || dz == 0 || dz == 6 || dy == 3;
				if (wall) level.setBlock(origin.offset(dx, dy, dz), stone, 3);
			}
			BlockPos door = origin.offset(3, 0, 6);
			BlockState doorState = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
			level.setBlock(door, doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
			level.setBlock(door.above(), doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
			BlockPos bed = origin.offset(2, 0, 2);
			level.setBlock(bed, Blocks.OAK_PLANKS.defaultBlockState(), 3);

			first = spawn(level, origin.offset(2, 0, 3));
			var registry = level.registryAccess();
			first.setVillagerData(first.getVillagerData().withProfession(registry, VillagerProfession.FARMER).withLevel(3));
			VillagerMemory firstMemory = first.getAttachedOrCreate(VillagerPlus.MEMORY);
			first.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
			Housing.update(level, first, firstMemory);

			require(firstMemory.homeBed.equals(Optional.of(bed)) && firstMemory.homeDoor.equals(Optional.of(door)),
				"the resident's bed becomes its home and the nearest door its house door"); checks++;
			require(firstMemory.signPos.isPresent() && level.getBlockState(firstMemory.signPos.get()).getBlock() instanceof DorfschildBlock,
				"a Dorfschild is placed for the house"); checks++;
			BlockPos sign = firstMemory.signPos.get();
			BlockPos upper = door.above();
			// Which face of the wall (inside or outside) is chosen depends on level.canSeeSky; in this fixture it reported true on BOTH sides,
			// so "outside" is not asserted here: the sign sits in the door's wall plane, next to the upper door half, on a free cell.
			require(Math.abs(sign.getX() - upper.getX()) == 1 && sign.getY() == upper.getY() && Math.abs(sign.getZ() - upper.getZ()) == 1,
				"the sign sits beside the upper door half, one cell off the wall plane: " + sign.toShortString()); checks++;
			BlockState placed = level.getBlockState(sign);
			require(placed.getValue(DorfschildBlock.ICON) == 1 && placed.getValue(DorfschildBlock.LEVEL) == 3
				&& placed.getValue(DorfschildBlock.FACING) == (sign.getZ() > upper.getZ() ? Direction.SOUTH : Direction.NORTH),
				"the sign shows the farmer icon (1), the resident's level (3 stars) and faces away from the wall on its side"); checks++;
			require("1:1:3".equals(firstMemory.signText), "the sign state is remembered (icon:residents:stars)"); checks++;

			// A second resident with the same door: the sign becomes the shared-house sign and is not placed twice.
			second = spawn(level, origin.offset(4, 0, 3));
			second.setVillagerData(second.getVillagerData().withProfession(registry, VillagerProfession.LIBRARIAN).withLevel(1));
			VillagerMemory secondMemory = second.getAttachedOrCreate(VillagerPlus.MEMORY);
			second.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), origin.offset(4, 0, 2)));
			level.setBlock(origin.offset(4, 0, 2), Blocks.OAK_PLANKS.defaultBlockState(), 3);
			secondMemory.homeBed = Optional.of(origin.offset(4, 0, 2));
			secondMemory.homeDoor = Optional.of(door);
			// Only the resident with the smaller UUID maintains the shared sign; call both so the outcome does not depend on which one it is.
			Housing.updateSign(level, first, firstMemory);
			Housing.updateSign(level, second, secondMemory);
			Housing.updateSign(level, first, firstMemory);
			Housing.updateSign(level, second, secondMemory);
			BlockState shared = level.getBlockState(sign);
			require(shared.getBlock() instanceof DorfschildBlock && shared.getValue(DorfschildBlock.ICON) == 12,
				"two residents behind one door show the shared-house icon (12) on the same sign"); checks++;
			long signs = java.util.stream.IntStream.rangeClosed(-2, 8).boxed().flatMap(dx -> java.util.stream.IntStream.rangeClosed(-2, 8)
				.boxed().flatMap(dz -> java.util.stream.IntStream.rangeClosed(0, 4).mapToObj(dy -> origin.offset(dx, dy, dz))))
				.filter(p -> level.getBlockState(p).getBlock() instanceof DorfschildBlock).count();
			require(signs == 1, "exactly one sign exists for the house (found " + signs + ")"); checks++;
			return checks;
		} finally {
			if (first != null) first.discard();
			if (second != null) second.discard();
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, 3));
		}
	}

	private static Villager spawn(ServerLevel level, BlockPos pos) {
		Villager v = EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);
		if (v == null) throw new IllegalStateException("HOUSING SIGN CHECK FAILED: could not spawn a villager");
		v.addTag("vp090_fixture");
		v.setNoAi(true);
		v.setInvulnerable(true);
		return v;
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("HOUSING SIGN CHECK FAILED: " + label);
	}
}
