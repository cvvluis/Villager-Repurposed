package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded in-world checks for demand, safe quarrying, gallery lights, real drops, and communal delivery. */
public final class GravelQuarryChecks {
	private GravelQuarryChecks() { }
	private record Cell(BlockState state, CompoundTag blockEntity) { }

	public static int run(ServerLevel level, BlockPos origin) {
	Map<BlockPos, Cell> before = new HashMap<>();
		Villager worker = null;
		int checks = 0;
		BlockPos bellPos = origin.offset(16, 0, 0);
		BlockPos chestPos = bellPos.east(3);
		try {
			for (int x = -10; x <= 20; x++) for (int z = -10; z <= 10; z++) {
				BlockPos floor = origin.offset(x, -1, z);
				level.getChunkAt(floor);
				for (int y = -1; y <= 4; y++) {
					BlockPos pos = origin.offset(x, y, z);
					BlockEntity blockEntity = level.getBlockEntity(pos);
					before.putIfAbsent(pos, new Cell(level.getBlockState(pos), blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess())));
					level.setBlock(pos, y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
				}
			}
			for (int i = 0; i < 12; i++) level.setBlock(origin.offset(i % 4, 0, i / 4), Blocks.GRAVEL.defaultBlockState(), 3);
			level.setBlock(origin.offset(8, -1, 0), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(origin.offset(8, 0, 0), Blocks.GRAVEL.defaultBlockState(), 3);
			for (Direction direction : Direction.Plane.HORIZONTAL) level.setBlock(origin.offset(8, 0, 0).relative(direction), Blocks.COBBLESTONE.defaultBlockState(), 3);
			check(GravelQuarry.naturalSource(level, origin), "exposed natural gravel is a valid source"); checks++;
			check(!GravelQuarry.naturalSource(level, origin.offset(8, 0, 0)), "road gravel framed by cobblestone is protected"); checks++;
			check(GravelQuarry.requestedGravel(java.util.List.of("minecraft:stone#2", "minecraft:gravel#17")) == 17
				&& GravelQuarry.requestedGravel(java.util.List.of("minecraft:gravel#bad")) == 0,
				"only a parseable village gravel request creates material demand"); checks++;

			level.setBlock(bellPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(bellPos, Blocks.BELL.defaultBlockState(), 3);
			BellBlockEntity bell = (BellBlockEntity) level.getBlockEntity(bellPos);
			VillageData data = new VillageData();
			data.chest = Optional.of(chestPos);
			activePlan(data, Blueprints.STATION, origin);
			bell.setAttached(VillagerPlus.VILLAGE, data);
			VillageRef ref = new VillageRef(bellPos, data, bell);
			level.setBlock(chestPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
			var box = ref.chest(level);
			worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("Cannot create gravel worker fixture");
			worker.setPos(origin.getX() + 1.5, origin.getY(), origin.getZ() + 0.5);
			worker.setNoAi(true); worker.setInvulnerable(true); worker.addTag("vp090_fixture");
			worker.getInventory().addItem(new ItemStack(Items.IRON_SHOVEL));
			level.addFreshEntity(worker);
			JobState state = new JobState();
			Ctx context = new Ctx(level, worker, state, worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
			data.wanted.clear();
			check(!GravelQuarry.work(context) && level.getBlockState(origin).is(Blocks.GRAVEL),
				"quarry does not mine without real village demand"); checks++;
			activePlan(data, Blueprints.HOUSE, origin);
			check(!GravelQuarry.work(context) && level.getBlockState(origin).is(Blocks.GRAVEL),
				"unrelated building material requests do not authorize quarrying"); checks++;
			activePlan(data, Blueprints.STATION, origin);
			for (int slot = 0; slot < worker.getInventory().getContainerSize(); slot++) {
				if (worker.getInventory().getItem(slot).isEmpty()) worker.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
			}
			check(!GravelQuarry.work(context) && level.getBlockState(origin).is(Blocks.GRAVEL),
				"full inventory leaves the source intact instead of spilling loot"); checks++;
			for (int slot = 0; slot < worker.getInventory().getContainerSize(); slot++) {
				if (worker.getInventory().getItem(slot).is(Items.COBBLESTONE)) worker.getInventory().setItem(slot, ItemStack.EMPTY);
			}
			int start = (int) level.getGameTime();
			for (int i = 0; i < 300 && total(box, Items.GRAVEL) == 0; i++) {
				Ctx work = new Ctx(level, worker, state, context.mem(), ref, start + i * 6L);
				if (JobUtil.count(worker.getInventory(), s -> s.is(Items.GRAVEL)) > 0) {
					BlockPos stand = JobUtil.sharedStandSpot(work, chestPos, 4.0);
					if (stand != null) worker.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
				}
				new MasonJob().primary(work);
			}
			check(total(box, Items.GRAVEL) > 0 && JobUtil.count(worker.getInventory(), s -> s.is(Items.GRAVEL)) == 0,
				"mason role harvests real shovel gravel for a station shortage and delivers it to the village chest"); checks++;
			check(total(box, Items.GRAVEL) <= 16, "quarry output stays within the registered request"); checks++;

			// Station-plan stock is communal across the village store and the registered station depot.
			BlockPos stockProbe = origin.offset(-5, 0, 0);
			level.setBlock(stockProbe, Blocks.GRAVEL.defaultBlockState(), 3);
			BlockPos stationDepotPos = origin.offset(14, 0, 6);
			level.setBlock(stationDepotPos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(stationDepotPos, Blocks.CHEST.defaultBlockState(), 3);
			Specials.set(ref, Blueprints.STATION, stationDepotPos);
			var stationBox = Lager.publicContainerAt(level, stationDepotPos);
			if (stationBox == null) throw new IllegalStateException("Cannot create public station depot fixture");
			box.clearContent();
			box.setItem(0, new ItemStack(Items.GRAVEL, 1));
			stationBox.setItem(0, new ItemStack(Items.GRAVEL, 15));
			data.wanted.clear(); data.wanted.add("minecraft:gravel#16");
			check(!GravelQuarry.work(context) && level.getBlockState(stockProbe).is(Blocks.GRAVEL),
				"existing public gravel in the village and station stores prevents duplicate quarrying"); checks++;
			box.clearContent();
			stationBox.clearContent();

			// A miner may recover gravel from a real, already advanced, lit and supported gallery.
			box.clearContent();
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.IRON_SHOVEL));
			int mineDescent = 3;
			BlockPos mineEntrance = origin.offset(0, mineDescent, 6);
			Direction mineDirection = Direction.EAST;
			data.mine = Optional.of(new VillageData.Mine(mineEntrance, mineDirection.get2DDataValue(), mineDescent + 1, origin.getY(), false));
			BlockPos mineCenter = origin.offset(mineDescent, 0, 6);
			BlockPos mineFloor = mineCenter.below();
			level.setBlock(mineFloor, Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(mineCenter, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(mineCenter.above(), Blocks.AIR.defaultBlockState(), 3);
			BlockPos gravelWall = mineCenter.relative(Direction.SOUTH);
			level.setBlock(gravelWall.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(gravelWall, Blocks.GRAVEL.defaultBlockState(), 3);
			level.setBlock(gravelWall.above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(mineCenter.relative(Direction.NORTH), Blocks.GRAVEL.defaultBlockState(), 3);
			level.setBlock(mineCenter.relative(Direction.NORTH).below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(mineCenter.relative(Direction.NORTH).above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(mineCenter.relative(Direction.EAST), Blocks.OAK_FENCE.defaultBlockState(), 3);
			level.setBlock(mineCenter.relative(Direction.EAST).below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(mineCenter.relative(Direction.EAST).above(), Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(mineCenter.relative(Direction.EAST).above(2), Blocks.TORCH.defaultBlockState(), 3);
			worker.teleportTo(mineCenter.getX() + 0.5, mineCenter.getY(), mineCenter.getZ() + 0.5);
			data.planType = Blueprints.STATION;
			data.planOrigin = Optional.of(origin);
			data.wanted.clear(); data.wanted.add("minecraft:gravel#1");
			int mineStart = (int) level.getGameTime();
			for (int i = 0; i < 140 && JobUtil.count(worker.getInventory(), s -> s.is(Items.GRAVEL)) == 0; i++) {
				GravelQuarry.work(new Ctx(level, worker, state, context.mem(), ref, mineStart + i * 6L));
			}
			check((level.getBlockState(gravelWall).isAir() || level.getBlockState(mineCenter.relative(Direction.NORTH)).isAir())
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.GRAVEL)) > 0,
				"active lit gallery yields the real shovel gravel drop without leaving the mine"); checks++;
			check(level.getBlockState(mineFloor).is(Blocks.STONE) && level.getBlockState(mineCenter.relative(Direction.EAST)).is(Blocks.OAK_FENCE)
				&& level.getBlockState(mineCenter.relative(Direction.EAST).above(2)).is(Blocks.TORCH),
				"underground gravel extraction preserves the gallery floor, support, and torch"); checks++;
			data.wanted.clear(); data.wanted.add("minecraft:gravel#1");
			worker.teleportTo(stationDepotPos.getX() + 0.5, stationDepotPos.getY(), stationDepotPos.getZ() + 0.5);
			new MasonJob().primary(new Ctx(level, worker, state, context.mem(), ref, mineStart + 840));
			check(total(stationBox, Items.GRAVEL) == 1 && total(box, Items.GRAVEL) == 0
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.GRAVEL)) == 0,
				"the mason returns mined gravel to the registered public station depot and clears its carried load"); checks++;
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.IRON_SHOVEL));
			data.wanted.clear(); data.wanted.add("minecraft:gravel#3");
			level.setBlock(mineCenter.relative(Direction.SOUTH), Blocks.GRAVEL.defaultBlockState(), 3);
			level.setBlock(mineCenter.relative(Direction.EAST), Blocks.AIR.defaultBlockState(), 3);
			worker.teleportTo(mineCenter.getX() + 0.5, mineCenter.getY(), mineCenter.getZ() + 0.5);
			check(!GravelQuarry.work(new Ctx(level, worker, state, context.mem(), ref, mineStart + 900))
				&& level.getBlockState(mineCenter.relative(Direction.SOUTH)).is(Blocks.GRAVEL),
				"unsupported gallery cannot authorize underground gravel extraction"); checks++;

			// A real mason restocks from the public chest before widening/supporting the next gallery section.
			box.clearContent();
			data.planType = Blueprints.STATION;
			data.planOrigin = Optional.of(origin);
			data.wanted.clear(); data.wanted.add("minecraft:stone#1");
			int descent = 3;
			BlockPos entrance = origin.offset(0, descent, 6);
			VillageData.Mine supportMine = new VillageData.Mine(entrance, mineDirection.get2DDataValue(), 6, origin.getY(), false);
			data.mine = Optional.of(supportMine);
			BlockPos supportFace = origin.offset(6, 0, 6);
			level.setBlock(supportFace.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(supportFace, Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(supportFace.above(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(supportFace.above(2), Blocks.STONE.defaultBlockState(), 3);
			for (Direction side : new Direction[] {Direction.NORTH, Direction.SOUTH}) {
				level.setBlock(supportFace.relative(side), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(supportFace.relative(side).above(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(supportFace.relative(side).above(2), Blocks.STONE.defaultBlockState(), 3);
			}
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
			state.mineCheckAt = 0; state.cooldownUntil = 0;
			worker.teleportTo(chestPos.getX() + 1.5, chestPos.getY(), chestPos.getZ() + 0.5);
			Mining.rememberMineRoute(worker, level, supportMine, 0);
			int supportStart = mineStart + 1000;
			boolean noKitWaited = !Mining.tick(new Ctx(level, worker, state, context.mem(), ref, supportStart), Mining.Need.STONE);
			check(noKitWaited && supportMine.index == 6 && level.getBlockState(supportFace).is(Blocks.STONE)
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.OAK_FENCE) || s.is(Items.OAK_PLANKS)) == 0,
				"the active mine waits at its saved cursor without inventing supports when the public stock and ingredients are empty"); checks++;
			// Five logs provide 20 planks: fences and sticks use ten; the five-wide
			// beams plus the gallery floor need eight, leaving two in public stock.
			box.setItem(0, new ItemStack(Items.OAK_LOG, 5));
			state.mineCheckAt = 0;
			boolean waitedAtSavedSection = false;
			boolean loadedSupportKit = false;
			long loadedSupportAt = supportStart;
			for (int attempt = 0; attempt < 24 && !loadedSupportKit; attempt++) {
				long now = supportStart + 120L + attempt * 120L;
				Mining.tick(new Ctx(level, worker, state, context.mem(), ref, now), Mining.Need.STONE);
				boolean carriedKit = JobUtil.count(worker.getInventory(), s -> s.is(Items.OAK_FENCE)) == 6
					&& JobUtil.count(worker.getInventory(), s -> s.is(Items.OAK_PLANKS)) == 8;
				if (supportMine.index == 6 && total(box, Items.OAK_LOG) < 2 && !carriedKit
					&& level.getBlockState(supportFace).is(Blocks.STONE)) waitedAtSavedSection = true;
				loadedSupportKit = carriedKit && JobUtil.count(worker.getInventory(), s -> s.is(Items.IRON_PICKAXE)) == 1;
				if (loadedSupportKit) loadedSupportAt = now;
			}
			check(loadedSupportKit && waitedAtSavedSection && total(box, Items.OAK_LOG) == 0
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.OAK_FENCE)) == 6
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.OAK_PLANKS)) == 8
				&& level.getBlockState(supportFace).is(Blocks.STONE) && supportMine.index == 6,
				"the miner crafts a full recipe batch from sufficient public logs and keeps the saved section parked until the kit is ready"
					+ " (loaded=" + loadedSupportKit + ", parked=" + waitedAtSavedSection + ", logs=" + total(box, Items.OAK_LOG)
					+ ", chest fences/planks/sticks=" + total(box, Items.OAK_FENCE) + "/" + total(box, Items.OAK_PLANKS) + "/" + total(box, Items.STICK)
					+ ", carried fences/planks=" + total(worker.getInventory(), Items.OAK_FENCE) + "/" + total(worker.getInventory(), Items.OAK_PLANKS)
					+ ", cursor=" + supportMine.index + ", waitUntil=" + state.mineCheckAt + ", activity=" + state.activity + ")"); checks++;
			worker.teleportTo(origin.getX() + 5.5, origin.getY(), origin.getZ() + 6.5);
			boolean supportedSection = Mining.tick(new Ctx(level, worker, state, context.mem(), ref, loadedSupportAt + 11), Mining.Need.STONE);
			Direction supportSide = mineDirection.getClockWise();
			BlockPos nearSupport = supportFace.relative(supportSide, -2);
			BlockPos farSupport = supportFace.relative(supportSide, 2);
			check(supportedSection && supportMine.index == 7
				&& level.getBlockState(nearSupport).is(Blocks.OAK_FENCE)
				&& level.getBlockState(farSupport).is(Blocks.OAK_FENCE)
				&& level.getBlockState(supportFace.relative(supportSide, -2).above(3)).is(Blocks.OAK_PLANKS)
				&& level.getBlockState(supportFace.above(3)).is(Blocks.OAK_PLANKS)
				&& level.getBlockState(supportFace.relative(supportSide, 2).above(3)).is(Blocks.OAK_PLANKS)
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.OAK_FENCE) || s.is(Items.OAK_PLANKS)) == 0,
				"the tunnel spends its staged fences and planks only when it places the visible paid supports"
					+ " (worked=" + supportedSection + ", cursor=" + supportMine.index + ", mineCheckAt=" + state.mineCheckAt
					+ ", cooldown=" + state.cooldownUntil + ", activity=" + state.activity + ", worker=" + worker.blockPosition()
					+ ", posts=" + level.getBlockState(nearSupport) + "/" + level.getBlockState(farSupport) + ", beams="
					+ level.getBlockState(supportFace.relative(supportSide, -2).above(3)) + "/"
					+ level.getBlockState(supportFace.above(3)) + "/"
					+ level.getBlockState(supportFace.relative(supportSide, 2).above(3)) + ", carried="
					+ total(worker.getInventory(), Items.OAK_FENCE) + "/" + total(worker.getInventory(), Items.OAK_PLANKS) + ")"); checks++;

			VillageData.Mine lightMine = new VillageData.Mine(entrance, mineDirection.get2DDataValue(), 12, origin.getY(), false);
			data.mine = Optional.of(lightMine);
			BlockPos lightFace = origin.offset(12, 0, 6);
			level.setBlock(lightFace.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(lightFace, Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(lightFace.above(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(lightFace.above(2), Blocks.STONE.defaultBlockState(), 3);
			Direction lightSide = mineDirection.getClockWise();
			BlockPos torchSpot = lightFace.relative(lightSide, 2);
			level.setBlock(torchSpot.relative(lightSide, 1), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(torchSpot.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(torchSpot, Blocks.AIR.defaultBlockState(), 3);
			worker.getInventory().clearContent();
			worker.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
			state.mineCheckAt = 0; state.cooldownUntil = 0;
			worker.teleportTo(chestPos.getX() + 1.5, chestPos.getY(), chestPos.getZ() + 0.5);
			box.clearContent();
			box.setItem(0, new ItemStack(Items.CHARCOAL, 1));
			box.setItem(1, new ItemStack(Items.STICK, 1));
			box.setItem(2, new ItemStack(Items.OAK_FENCE, 6));
			box.setItem(3, new ItemStack(Items.OAK_PLANKS, 8));
			boolean loadedTorch = false;
			long torchLoadedAt = loadedSupportAt + 40;
			for (int attempt = 0; attempt < 8 && total(worker.getInventory(), Items.TORCH) == 0; attempt++) {
				torchLoadedAt = Math.max(torchLoadedAt, state.mineCheckAt + 1L);
				loadedTorch |= Mining.tick(new Ctx(level, worker, state, context.mem(), ref, torchLoadedAt), Mining.Need.STONE);
				if (total(worker.getInventory(), Items.TORCH) == 0) torchLoadedAt += 10;
			}
			check(loadedTorch && total(box, Items.TORCH) + JobUtil.count(worker.getInventory(), s -> s.is(Items.TORCH)) > 0
				&& total(box, Items.CHARCOAL) == 0 && total(box, Items.STICK) == 0
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.TORCH)) == 1 && lightMine.index == 12,
				"the next lit section crafts and carries a torch from real public charcoal and stick stock without advancing early"
					+ " (loaded=" + loadedTorch + ", cursor=" + lightMine.index + ", activity=" + state.activity
					+ ", mineCheckAt=" + state.mineCheckAt + ", cooldown=" + state.cooldownUntil
					+ ", chest torch/charcoal/stick=" + total(box, Items.TORCH) + "/" + total(box, Items.CHARCOAL) + "/" + total(box, Items.STICK)
					+ ", carried torch=" + total(worker.getInventory(), Items.TORCH) + ", face=" + level.getBlockState(lightFace)
					+ ", worker=" + worker.blockPosition() + ")"); checks++;
			worker.teleportTo(origin.getX() + 11.5, origin.getY(), origin.getZ() + 6.5);
			long litSectionAt = Math.max(torchLoadedAt + 11, state.mineCheckAt + 1L);
			boolean litSection = Mining.tick(new Ctx(level, worker, state, context.mem(), ref, litSectionAt), Mining.Need.STONE);
			check(litSection && lightMine.index == 13 && level.getBlockState(torchSpot).is(Blocks.WALL_TORCH)
				&& level.getBlockState(torchSpot).getValue(HorizontalDirectionalBlock.FACING) == Direction.NORTH
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.TORCH)) == 0,
				"the miner spends the staged torch on a supported wall-mounted gallery light"
					+ " (worked=" + litSection + ", cursor=" + lightMine.index + ", activity=" + state.activity
					+ ", mineCheckAt=" + state.mineCheckAt + ", cooldown=" + state.cooldownUntil
					+ ", carried torch=" + total(worker.getInventory(), Items.TORCH) + ", spot=" + level.getBlockState(torchSpot) + ")"); checks++;

			// The existing mine lights are paid torches; the mason relocates them into alternating, collision-free wall mounts.
			Direction gallerySide = mineDirection.getClockWise();
			BlockPos dressedEntrance = origin.offset(0, descent, -8);
			VillageData.Mine dressedMine = new VillageData.Mine(dressedEntrance, mineDirection.get2DDataValue(), 13, origin.getY(), false);
			data.mine = Optional.of(dressedMine);
			BlockPos firstLightCenter = new BlockPos(dressedEntrance.east(4).getX(), origin.getY(), dressedEntrance.east(4).getZ());
			BlockPos secondLightCenter = new BlockPos(dressedEntrance.east(12).getX(), origin.getY(), dressedEntrance.east(12).getZ());
			BlockPos firstFloorTorch = firstLightCenter.relative(gallerySide, -2);
			BlockPos secondFloorTorch = secondLightCenter.relative(gallerySide, 2);
			BlockPos firstWallTorch = firstLightCenter.relative(gallerySide, -1).above();
			BlockPos secondWallTorch = secondLightCenter.relative(gallerySide, 1).above();
			BlockPos supportCenter = new BlockPos(dressedEntrance.east(6).getX(), origin.getY(), dressedEntrance.east(6).getZ());
			BlockPos existingSupport = supportCenter.relative(gallerySide, -2);
			level.setBlock(firstLightCenter.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(secondLightCenter.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(existingSupport.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(existingSupport, Blocks.OAK_FENCE.defaultBlockState(), 3);
			for (int lane = -1; lane <= 1; lane++) {
				level.setBlock(firstLightCenter.relative(gallerySide, lane).below(), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(secondLightCenter.relative(gallerySide, lane).below(), Blocks.STONE.defaultBlockState(), 3);
			}
			level.setBlock(firstFloorTorch.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(secondFloorTorch.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(firstFloorTorch, Blocks.TORCH.defaultBlockState(), 3);
			level.setBlock(secondFloorTorch, Blocks.TORCH.defaultBlockState(), 3);
			level.setBlock(firstFloorTorch.above(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(secondFloorTorch.above(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(firstWallTorch, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(secondWallTorch, Blocks.AIR.defaultBlockState(), 3);
			worker.getInventory().clearContent();
			int carriedTorches = JobUtil.count(worker.getInventory(), s -> s.is(Items.TORCH));
			worker.teleportTo(firstLightCenter.getX() + 0.5, firstLightCenter.getY(), firstLightCenter.getZ() + 0.5);
			boolean raisedFirstLight = GravelQuarry.work(new Ctx(level, worker, state, context.mem(), ref, litSectionAt + 10));
			worker.teleportTo(secondLightCenter.getX() + 0.5, secondLightCenter.getY(), secondLightCenter.getZ() + 0.5);
			boolean raisedSecondLight = GravelQuarry.work(new Ctx(level, worker, state, context.mem(), ref, litSectionAt + 21));
			check(raisedFirstLight && raisedSecondLight
				&& level.getBlockState(firstFloorTorch).isAir() && level.getBlockState(secondFloorTorch).isAir()
				&& level.getBlockState(firstWallTorch).is(Blocks.WALL_TORCH)
				&& level.getBlockState(firstWallTorch).getValue(HorizontalDirectionalBlock.FACING) == Direction.SOUTH
				&& level.getBlockState(secondWallTorch).is(Blocks.WALL_TORCH)
				&& level.getBlockState(secondWallTorch).getValue(HorizontalDirectionalBlock.FACING) == Direction.NORTH
				&& level.getBlockState(firstWallTorch).getCollisionShape(level, firstWallTorch).isEmpty()
				&& level.getBlockState(secondWallTorch).getCollisionShape(level, secondWallTorch).isEmpty()
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.TORCH)) == carriedTorches
				&& dressedMine.index == 13
				&& level.getBlockState(existingSupport).is(Blocks.OAK_FENCE)
				&& level.getBlockState(firstLightCenter.below()).is(Blocks.STONE)
				&& level.getBlockState(secondLightCenter.below()).is(Blocks.STONE),
				"the mason raises alternating eight-block gallery lights without spending torches or narrowing the supported floor"); checks++;
			dressedMine.index = 21;
			BlockPos unsupportedCenter = new BlockPos(dressedEntrance.east(20).getX(), origin.getY(), dressedEntrance.east(20).getZ());
			BlockPos unsupportedFloorTorch = unsupportedCenter.relative(gallerySide, -2);
			BlockPos unsupportedWallTorch = unsupportedCenter.relative(gallerySide, -1).above();
			level.setBlock(unsupportedCenter.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(unsupportedFloorTorch.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(unsupportedFloorTorch, Blocks.TORCH.defaultBlockState(), 3);
			level.setBlock(unsupportedWallTorch, Blocks.AIR.defaultBlockState(), 3);
			worker.teleportTo(unsupportedCenter.getX() + 0.5, unsupportedCenter.getY(), unsupportedCenter.getZ() + 0.5);
			boolean unsupportedMountRejected = !GravelQuarry.raiseGalleryLight(
				new Ctx(level, worker, state, context.mem(), ref, litSectionAt + 32));
			check(unsupportedMountRejected && level.getBlockState(unsupportedFloorTorch).is(Blocks.TORCH)
				&& level.getBlockState(unsupportedWallTorch).isAir()
				&& JobUtil.count(worker.getInventory(), s -> s.is(Items.TORCH)) == carriedTorches
				&& dressedMine.index == 21,
				"an unsupported light mount is skipped while the original torch and saved mine route remain intact"); checks++;
			return checks;
		} finally {
			if (worker != null) worker.discard();
			for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) {
				BlockPos pos = entry.getKey();
				Cell cell = entry.getValue();
				level.setBlock(pos, cell.state(), 2);
				if (cell.blockEntity() == null) {
					if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
				} else {
					BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
					if (restored != null) level.setBlockEntity(restored);
				}
			}
		}
	}

	private static int total(net.minecraft.world.Container container, net.minecraft.world.item.Item item) {
		return JobUtil.count(container, stack -> stack.is(item));
	}

	private static void activePlan(VillageData data, String type, BlockPos origin) {
		data.buildProjects.clear();
		data.putBuildProject(new VillageData.BuildProject(type, origin, 0, 0, 0, Optional.empty(), 0L,
			Optional.empty(), 0L, java.util.List.of("minecraft:gravel#16"), VillageData.ProjectHaul.empty(),
			Optional.empty(), Optional.empty(), Optional.empty(), false, false));
		data.syncPrimaryProjectView();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("GRAVEL QUARRY CHECK FAILED: " + message);
	}
}
