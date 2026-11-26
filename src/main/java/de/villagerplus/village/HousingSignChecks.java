package de.villagerplus.village;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorfschildBlock;
import de.villagerplus.block.ModBlocks;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
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
			for (int dx = -2; dx <= 10; dx++) for (int dz = -2; dz <= 8; dz++) for (int dy = -1; dy <= 5; dy++) {
				BlockPos p = origin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.setBlock(p, dy == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
			}
			// The door faces indoors like the generated houses. An eave covers its exterior.
			BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
			for (int dx = 0; dx <= 8; dx++) for (int dz = 0; dz <= 6; dz++) for (int dy = 0; dy <= 3; dy++) {
				boolean wall = dx == 0 || dx == 8 || dz == 0 || dz == 6 || dy == 3;
				if (wall) level.setBlock(origin.offset(dx, dy, dz), stone, 3);
			}
			for (int dx = -1; dx <= 9; dx++) level.setBlock(origin.offset(dx, 3, 7), stone, 3);
			// A pair of facade windows sits close to the entrance; sign placement must use the clear wall beyond them.
			BlockPos leftWindow = origin.offset(2, 1, 6), rightWindow = origin.offset(6, 1, 6);
			for (BlockPos window : new BlockPos[] {leftWindow, rightWindow}) {
				level.setBlock(window, Blocks.GLASS.defaultBlockState(), 3);
				level.setBlock(window.above(), Blocks.GLASS.defaultBlockState(), 3);
			}
			BlockPos door = origin.offset(4, 0, 6);
			BlockState doorState = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
			level.setBlock(door, doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
			level.setBlock(door.above(), doorState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
			BlockPos bed = origin.offset(2, 0, 2);
			placeBed(level, bed);

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
			BlockPos signWall = sign.relative(level.getBlockState(sign).getValue(DorfschildBlock.FACING).getOpposite());
			require(Math.abs(sign.getX() - upper.getX()) == 4 && sign.getZ() == upper.getZ() + 1,
				"the sign uses an outer wall cell beyond the windows: " + sign.toShortString()); checks++;
			require(!level.getBlockState(signWall).is(Blocks.GLASS)
				&& signWall.distManhattan(leftWindow) >= 2 && signWall.distManhattan(rightWindow) >= 2,
				"the support block is opaque and at least one clear wall block away from each window"); checks++;
			// The lighting engine updates asynchronously after a roof is placed. Verify
			// the obstruction directly so this single-tick fixture does not sample stale skylight.
			require(level.getBlockState(sign.above(2)).is(Blocks.STONE_BRICKS)
				&& level.getBlockState(sign.north(2).above(2)).is(Blocks.STONE_BRICKS) && sign.getZ() > upper.getZ(),
				"the sign faces outside when the door faces in and the roof covers both wall faces"); checks++;
			BlockState placed = level.getBlockState(sign);
			require(placed.getValue(DorfschildBlock.ICON) == 1 && placed.getValue(DorfschildBlock.LEVEL) == 3
				&& placed.getValue(DorfschildBlock.FACING) == (sign.getZ() > upper.getZ() ? Direction.SOUTH : Direction.NORTH),
				"the sign shows the farmer icon (1), the resident's level (3 stars) and faces away from the wall on its side"); checks++;
			require("1:1:3".equals(firstMemory.signText), "the sign state is remembered (icon:residents:stars)"); checks++;

			// Simulate a saved house with a door-adjacent sign. The owner put a sign at
			// the first clear location in the meantime, so the house sign must use the other side.
			level.removeBlock(sign, false);
			BlockPos oldSign = door.above().east().south();
			level.setBlock(oldSign, placed, 3);
			firstMemory.signPos = Optional.of(oldSign);
			BlockState playerSign = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.SOUTH);
			level.setBlock(sign, playerSign, 3);
			SignBlockEntity playerSignEntity = (SignBlockEntity) level.getBlockEntity(sign);
			playerSignEntity.setText(playerSignEntity.getFrontText().setMessage(0, Component.literal("Player fixture")), true);
			BlockPos playerSignPos = sign;
			Housing.updateSign(level, first, firstMemory);
			sign = firstMemory.signPos.orElseThrow();
			require(!sign.equals(oldSign) && !sign.equals(playerSignPos) && level.getBlockState(oldSign).isAir()
				&& level.getBlockState(sign).getBlock() instanceof DorfschildBlock,
				"a saved door-adjacent village sign relocates to the available facade and removes its old block"); checks++;
			require(DorfschildBlock.describe(level, sign).stream().anyMatch(line -> line.contains(firstMemory.name)),
				"a sign beyond the old door-distance limit still shows its resident on interaction"); checks++;

			// A second resident with the same door: the sign becomes the shared-house sign and is not placed twice.
			second = spawn(level, origin.offset(4, 0, 3));
			second.setVillagerData(second.getVillagerData().withProfession(registry, VillagerProfession.LIBRARIAN).withLevel(1));
			VillagerMemory secondMemory = second.getAttachedOrCreate(VillagerPlus.MEMORY);
			BlockPos secondBed = origin.offset(4, 0, 2);
			placeBed(level, secondBed);
			second.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), secondBed));
			secondMemory.homeBed = Optional.of(secondBed);
			secondMemory.homeDoor = Optional.of(door);
			secondMemory.signPos = Optional.of(playerSignPos);
			// Only the resident with the smaller UUID maintains the shared sign; call both so the outcome does not depend on which one it is.
			Housing.updateSign(level, first, firstMemory);
			Housing.updateSign(level, second, secondMemory);
			Housing.updateSign(level, first, firstMemory);
			Housing.updateSign(level, second, secondMemory);
			BlockState shared = level.getBlockState(sign);
			require(shared.getBlock() instanceof DorfschildBlock && shared.getValue(DorfschildBlock.ICON) == 12,
				"two residents behind one door show the shared-house icon (12) on the same sign"); checks++;
			require(firstMemory.signPos.equals(Optional.of(sign)) && firstMemory.signPos.equals(secondMemory.signPos)
				&& level.getBlockState(playerSignPos).is(Blocks.OAK_WALL_SIGN)
				&& ((SignBlockEntity) level.getBlockEntity(playerSignPos)).getFrontText().getMessage(0, false).getString().equals("Player fixture"),
				"the moved sign becomes shared and a remembered player sign keeps its block and text"); checks++;

			// The remaining villager may still cache its old single-resident display when the other maintained the sign.
			firstMemory.signText = "1:1:3";
			second.getBrain().eraseMemory(MemoryModuleType.HOME);
			Housing.updateSign(level, first, firstMemory);
			require(level.getBlockState(sign).getValue(DorfschildBlock.ICON) == 1 && "1:1:3".equals(firstMemory.signText),
				"a nearby former resident with a cached door is excluded as soon as the current HOME is gone"); checks++;
			Housing.update(level, second, secondMemory);
			require(secondMemory.homeBed.isEmpty() && secondMemory.homeDoor.isEmpty() && secondMemory.signPos.isEmpty(),
				"a resident with no HOME no longer keeps a former house assignment"); checks++;

			// Simulate a saved second HOME whose bed is incomplete after a legacy build or a block change.
			second.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), secondBed));
			secondMemory.homeBed = Optional.of(secondBed);
			secondMemory.homeDoor = Optional.of(door);
			secondMemory.signPos = Optional.of(sign);
			level.setBlock(secondBed.south(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			require(level.getBlockState(secondBed).getBlock() instanceof BedBlock && level.getBlockState(secondBed.south()).isAir(),
				"the legacy fixture retains a head with no matching foot"); checks++;
			require(DorfschildBlock.describe(level, sign).getFirst().equals("Hier wohnt:"),
				"clicking the stored household sign excludes a resident whose remaining bed half is unusable"); checks++;
			Housing.update(level, second, secondMemory);
			require(!Housing.hasHome(second) && secondMemory.homeBed.isEmpty() && secondMemory.homeDoor.isEmpty()
				&& secondMemory.signPos.isEmpty() && Housing.hasHome(first)
				&& level.getBlockState(bed).getBlock() instanceof BedBlock && level.getBlockState(secondBed.south()).isAir(),
				"the stale HOME becomes housing demand while the occupied bed and house blocks are preserved"); checks++;

			// An unloaded bed cannot be inspected. Its stored assignments survive until its column is available.
			BlockPos unloadedHome = origin.offset(512, 0, 512);
			require(!de.villagerplus.world.LoadedColumns.ready(level, unloadedHome),
				"the distant HOME fixture starts unloaded"); checks++;
			second.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), unloadedHome));
			secondMemory.homeBed = Optional.of(unloadedHome);
			secondMemory.homeDoor = Optional.of(unloadedHome.north());
			Housing.update(level, second, secondMemory);
			require(Housing.hasHome(second) && secondMemory.homeBed.equals(Optional.of(unloadedHome))
				&& secondMemory.homeDoor.equals(Optional.of(unloadedHome.north()))
				&& !de.villagerplus.world.LoadedColumns.ready(level, unloadedHome),
				"housing preserves an unloaded HOME and does not load its chunk"); checks++;

			// A stale or externally supplied duplicate HOME can even refer to the other half of the same bed.
			BlockPos sameBedFoot = bed.south();
			second.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), sameBedFoot));
			secondMemory.homeBed = Optional.of(sameBedFoot);
			secondMemory.homeDoor = Optional.of(door);
			secondMemory.signPos = Optional.of(sign);
			require(DorfschildBlock.describe(level, sign).getFirst().equals("Hier wohnt:"),
				"the click roster counts matching head and foot claims as one bed before the next housing update"); checks++;
			Villager duplicateWinner = first.getUUID().compareTo(second.getUUID()) < 0 ? first : second;
			Villager duplicateLoser = duplicateWinner == first ? second : first;
			Housing.update(level, duplicateLoser, duplicateLoser.getAttachedOrCreate(VillagerPlus.MEMORY));
			require(duplicateLoser.getBrain().getMemory(MemoryModuleType.HOME).isEmpty()
				&& duplicateWinner.getBrain().getMemory(MemoryModuleType.HOME).isPresent(),
				"when two residents claim opposite halves of one physical bed, the higher UUID relinquishes its duplicate HOME"); checks++;
			Housing.updateSign(level, duplicateWinner, duplicateWinner.getAttachedOrCreate(VillagerPlus.MEMORY));
			require(level.getBlockState(sign).getValue(DorfschildBlock.ICON) != 12,
				"two HOME records referring to the same physical bed never count as a two-resident household"); checks++;
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

	private static void placeBed(ServerLevel level, BlockPos head) {
		BlockState bed = Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
		level.setBlock(head.south(), bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
		level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
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
