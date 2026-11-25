package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Workstation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * B05: systematic proof that every community-carrier container scan (village chest, warehouse, material sources, rail
 * resources, milestone depots, delivery destinations, crafting inputs) refuses private chests in every registration
 * shape: a stale village-chest registration, a half-owned double chest, a registered warehouse chest and a private
 * neighbour of the real public chest. Private contents must stay byte-identical.
 */
public final class ChestScanOwnershipChecks {
	private ChestScanOwnershipChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		VillageRef ref = Checks090.fixtureForEquipment(level, at);
		BlockPos publicPos = ref.chestPos(level);
		if (publicPos == null) throw new IllegalStateException("chest scan checks need a public village chest");
		Optional<BlockPos> oldRegistration = ref.data().chest;
		List<BlockPos> oldLager = List.copyOf(ref.data().extra.lagerChests);
		Optional<BlockPos> oldLagerCenter = ref.data().extra.lager;
		Map<String, BlockPos> oldSpecials = Map.copyOf(Specials.all(ref));
		Map<BlockPos, Cell> cells = new LinkedHashMap<>();
		BlockPos privateSingle = at.offset(-10, 1, 8);
		BlockPos pairLeft = at.offset(10, 1, 8);
		BlockPos pairRight = pairLeft.east();
		BlockPos neighbour = publicPos.relative(Direction.NORTH, 4).above(0);
		for (BlockPos p : List.of(privateSingle, pairLeft, pairRight, neighbour, publicPos)) snapshot(level, cells, p);
		Container publicBox = ref.chest(level);
		List<ItemStack> publicBefore = contents(publicBox);
		Villager owner = EntityTypes.VILLAGER.spawn(level, publicPos.east(1), EntitySpawnReason.COMMAND);
		if (owner == null) throw new IllegalStateException("chest scan owner fixture failed");
		int[] passed = {0};
		try {
			owner.addTag("vp090_fixture");
			owner.setNoAi(true);
			owner.setInvulnerable(true);
			publicBox.clearContent();

			// (a) stale village-chest registration on a private single chest
			placePrivate(level, privateSingle, owner, Items.OAK_PLANKS, 64);
			ref.data().chest = Optional.of(privateSingle);
			assertNoAccess(level, ref, passed, "stale village-chest registration", privateSingle, Items.OAK_PLANKS);

			// (b) half-owned double chest registered as the village chest and as a warehouse chest
			BlockState north = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
			level.setBlock(pairLeft, north.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
			level.setBlock(pairRight, north.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
			BlockEntity right = level.getBlockEntity(pairRight);
			Container pair = HopperBlockEntity.getContainerAt(level, pairLeft);
			if (right == null || pair == null || pair.getContainerSize() != 54) throw new IllegalStateException("double chest fixture failed");
			right.setAttached(VillagerPlus.OWNER, owner.getUUID());
			pair.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
			pair.setChanged();
			ref.data().chest = Optional.of(pairLeft);
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(pairLeft);
			ref.data().extra.lager = Optional.of(pairLeft);
			Specials.set(ref, de.villagerplus.build.Blueprints.GRANARY, pairLeft);
			assertNoAccess(level, ref, passed, "half-owned double chest", pairLeft, Items.COBBLESTONE);

			// (c) a registered warehouse chest that is private while the village chest stays public
			ref.data().chest = Optional.of(publicPos);
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.add(privateSingle);
			check(passed, Lager.containers(level, ref.data()).isEmpty() && !Lager.materialChests(level, ref).contains(privateSingle)
				&& Lager.chestWithRoom(level, ref.data()) == null && Lager.chestWithRoom(level, ref.data(), new ItemStack(Items.OAK_PLANKS)) == null
				&& Lager.countAll(level, ref.data(), s -> s.is(Items.OAK_PLANKS)) == 0
				&& RailResources.sourceFor(level, ref, Items.OAK_PLANKS) == null,
				"a private warehouse registration is invisible to storage, room search, counting and rail resources");

			// (d) private chest right next to the real public chest, both carrying the wanted item
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lager = oldLagerCenter;
			Specials.set(ref, de.villagerplus.build.Blueprints.GRANARY, neighbour);
			placePrivate(level, neighbour, owner, Items.IRON_INGOT, 64);
			publicBox.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
			Container combined = Lager.combined(level, ref);
			check(passed, combined != null && JobUtil.count(combined, s -> s.is(Items.IRON_INGOT)) == 3
				&& RailResources.stores(level, ref).stream().allMatch(c -> JobUtil.count(c, s -> s.is(Items.IRON_INGOT)) <= 3)
				&& Specials.containers(level, ref, de.villagerplus.build.Blueprints.GRANARY).stream()
					.allMatch(c -> JobUtil.count(c, s -> s.is(Items.IRON_INGOT)) <= 3),
				"a private neighbour of the public chest adds nothing to combined stock, rail stores or milestone depots");
			BlockPos source = RailResources.sourceFor(level, ref, Items.IRON_INGOT);
			check(passed, publicPos.equals(source) && !neighbour.equals(source), "rail resources fetch iron only from the public chest");

			// Behaviour: a crafting resident with all ingredients only in the private neighbour crafts nothing.
			publicBox.clearContent();
			ItemStack[] privateStock = {new ItemStack(Items.OAK_PLANKS, 64)};
			Container neighbourBox = HopperBlockEntity.getContainerAt(level, neighbour);
			neighbourBox.setItem(0, privateStock[0]);
			neighbourBox.setChanged();
			level.setBlock(publicPos.west(2), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
			Villager worker = EntityTypes.VILLAGER.spawn(level, publicPos.east(2), EntitySpawnReason.COMMAND);
			try {
				worker.addTag("vp090_fixture");
				worker.setNoAi(true);
				worker.setInvulnerable(true);
				Ctx ctx = new Ctx(level, worker, JobManager.state(worker), worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
				boolean crafted = Workstation.ensure(ctx, Blocks.BARREL);
				check(passed, !crafted && JobUtil.count(neighbourBox, s -> s.is(Items.OAK_PLANKS)) == 64
					&& JobUtil.count(publicBox, s -> !s.isEmpty()) == 0,
					"a profession crafting from the village chest never takes ingredients from a private neighbour");
				check(passed, JobUtil.box(ctx) == null || contents(JobUtil.box(ctx)).equals(contents(publicBox)) || JobUtil.chest(ctx).equals(publicPos),
					"the resolved delivery chest of a resident is the public one");
			} finally {
				worker.discard();
			}
			return passed[0];
		} finally {
			owner.discard();
			ref.data().chest = oldRegistration;
			ref.data().extra.lagerChests.clear();
			ref.data().extra.lagerChests.addAll(oldLager);
			ref.data().extra.lager = oldLagerCenter;
			ref.bellEntity().setAttached(VillagerPlus.SPECIALS, oldSpecials);
			ref.dirty();
			cells.forEach((pos, cell) -> level.removeBlockEntity(pos));
			cells.forEach((pos, cell) -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
			cells.forEach((pos, cell) -> level.setBlock(pos, cell.state(), 3));
			cells.forEach((pos, cell) -> {
				if (cell.data() == null) return;
				BlockEntity entity = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
				if (entity != null) level.setBlockEntity(entity);
			});
			Container restored = ref.chest(level);
			if (restored != null) for (int i = 0; i < publicBefore.size() && i < restored.getContainerSize(); i++) restored.setItem(i, publicBefore.get(i));
		}
	}

	private static void assertNoAccess(ServerLevel level, VillageRef ref, int[] passed, String shape, BlockPos pos, Item item) {
		check(passed, ref.chestPos(level) == null && ref.chest(level) == null && Lager.combined(level, ref) == null,
			shape + ": the village chest resolves to nothing instead of the private container");
		check(passed, Lager.publicContainerAt(level, pos) == null && !Lager.publicStorageAt(level, pos)
			&& Lager.materialChests(level, ref).stream().noneMatch(p -> p.equals(pos) || p.equals(pos.east())),
			shape + ": material sources and public-storage tests reject it");
		check(passed, RailResources.stores(level, ref).isEmpty(), shape + ": rail stores are empty");
		check(passed, RailResources.sourceFor(level, ref, item) == null, shape + ": rail source search finds nothing");
		check(passed, Specials.containers(level, ref, de.villagerplus.build.Blueprints.GRANARY).stream().noneMatch(c -> JobUtil.count(c, st -> st.is(item)) > 0),
			shape + ": milestone depot list never exposes the private contents");
		check(passed, Lager.containers(level, ref.data()).isEmpty(), shape + ": warehouse list is empty");
		check(passed, Lager.chestWithRoom(level, ref.data(), new ItemStack(item)) == null, shape + ": room search finds nothing");
		Container stored = HopperBlockEntity.getContainerAt(level, pos);
		check(passed, stored != null && JobUtil.count(stored, s -> s.is(item)) > 0, shape + ": private contents are untouched");
	}

	private static void placePrivate(ServerLevel level, BlockPos pos, Villager owner, Item item, int count) {
		level.getChunk(pos);
		level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
		BlockEntity entity = level.getBlockEntity(pos);
		Container box = HopperBlockEntity.getContainerAt(level, pos);
		if (entity == null || box == null) throw new IllegalStateException("private chest fixture failed");
		entity.setAttached(VillagerPlus.OWNER, owner.getUUID());
		box.clearContent();
		box.setItem(0, new ItemStack(item, count));
		box.setChanged();
	}

	private static List<ItemStack> contents(Container box) {
		List<ItemStack> out = new ArrayList<>();
		for (int i = 0; i < box.getContainerSize(); i++) out.add(box.getItem(i).copy());
		return out;
	}

	private static void snapshot(ServerLevel level, Map<BlockPos, Cell> cells, BlockPos pos) {
		level.getChunk(pos);
		if (cells.containsKey(pos)) return;
		BlockEntity be = level.getBlockEntity(pos);
		cells.put(pos.immutable(), new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
	}

	private static void check(int[] passed, boolean condition, String message) {
		if (!condition) throw new IllegalStateException("B05 chest scan: " + message);
		passed[0]++;
	}

	private record Cell(BlockState state, CompoundTag data) { }
}
