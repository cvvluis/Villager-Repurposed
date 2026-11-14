package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/** Checks live recipe lookup and ownership boundaries against a real furnace block entity. */
public final class SmithSmeltingChecks {
	private SmithSmeltingChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		BlockPos pos = ref.bell().offset(86, 0, 78);
		level.getChunkAt(pos);
		var old = level.getBlockState(pos);
		List<Container> outputStores = List.of();
		List<ContainerSnapshot> savedStores = List.of();
		int checks = 0;
		var emptyClaimData = ItemStack.OPTIONAL_CODEC.encodeStart(NbtOps.INSTANCE, ItemStack.EMPTY).result();
		check(emptyClaimData.isPresent(), "an empty furnace claim serializes without a block-entity save error"); checks++;
		var emptyClaimRoundTrip = emptyClaimData.flatMap(tag -> ItemStack.OPTIONAL_CODEC.parse(NbtOps.INSTANCE, tag).result());
		check(emptyClaimRoundTrip.isPresent() && emptyClaimRoundTrip.get().isEmpty(), "an empty furnace claim restores as empty"); checks++;
		var registryOps = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
		ItemStack savedClaim = new ItemStack(Items.IRON_INGOT, 4);
		var optionalClaimData = ItemStack.OPTIONAL_CODEC.encodeStart(registryOps, savedClaim).result();
		var standardClaimData = ItemStack.CODEC.encodeStart(registryOps, savedClaim).result();
		check(optionalClaimData.isPresent() && optionalClaimData.equals(standardClaimData),
			"non-empty furnace claims keep their existing saved format"); checks++;
		try {
			level.getChunkAt(pos);
			level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
			if (!(level.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)) {
				throw new IllegalStateException("SMITH SMELTING CHECK FAILED: furnace block entity missing");
			}
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			var furnaceSave = furnace.saveWithFullMetadata(level.registryAccess());
			check(furnaceSave.contains("fabric:attachments"), "a furnace with an empty smith claim saves its persistent attachments"); checks++;
			var recipe = SmithSmelting.matchingRecipeForCheck(level, new ItemStack(Items.RAW_IRON));
			check(recipe != null && recipe.assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(Items.RAW_IRON)))
				.is(Items.IRON_INGOT), "the loaded smelting registry resolves raw iron to its current output"); checks++;
			ItemStack outputSample = recipe == null ? ItemStack.EMPTY
				: recipe.assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(Items.RAW_IRON)));
			outputStores = deliveryStores(level, ref, outputSample);
			savedStores = snapshot(outputStores);
			check(recipe != null && SmithSmelting.claimBatch(furnace, new ItemStack(Items.RAW_IRON), recipe)
				&& furnace.getItem(0).is(Items.RAW_IRON)
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).is(Items.IRON_INGOT),
				"the expected recipe output is persisted before the real furnace input is exposed"); checks++;
			furnace.setItem(0, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 1);
			check(recipe != null && !SmithSmelting.claimBatch(furnace, new ItemStack(Items.RAW_IRON), recipe)
				&& furnace.getItem(0).isEmpty(), "smithing never claims a furnace owned by a pending charcoal batch"); checks++;
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			furnace.setItem(0, new ItemStack(Items.COBBLESTONE));
			check(recipe != null && !SmithSmelting.claimBatch(furnace, new ItemStack(Items.RAW_IRON), recipe)
				&& furnace.getItem(0).is(Items.COBBLESTONE), "smithing never overwrites foreign furnace input"); checks++;
			furnace.setItem(0, ItemStack.EMPTY);
			furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, new ItemStack(Items.IRON_INGOT, 4));
			furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 4));
			for (Container destination : outputStores) {
				for (int i = 0; i < destination.getContainerSize(); i++) destination.setItem(i, new ItemStack(Items.DIRT, 64));
				destination.setChanged();
			}
			check(SmithSmelting.deliverClaimedOutput(level, ref, furnace) == 0
				&& furnace.getItem(2).getCount() == 4
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).getCount() == 4,
				"full village storage retains both the real furnace output and its ownership claim"); checks++;
			if (outputStores.isEmpty()) throw new IllegalStateException("SMITH SMELTING CHECK FAILED: recipe output has no registered delivery store");
			Container partialStore = outputStores.get(0);
			partialStore.setItem(0, new ItemStack(Items.IRON_INGOT, 63));
			partialStore.setChanged();
			check(SmithSmelting.deliverClaimedOutput(level, ref, furnace) == 1
				&& furnace.getItem(2).getCount() == 3
				&& partialStore.getItem(0).getCount() == 64
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).getCount() == 3,
				"partial routed storage capacity commits only the inserted item and retains the remainder"); checks++;
			partialStore.clearContent();
			check(SmithSmelting.deliverClaimedOutput(level, ref, furnace) == 3
				&& furnace.getItem(2).isEmpty()
				&& furnace.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty()
				&& partialStore.getItem(0).getCount() == 3,
				"remaining output is committed once and releases its persistent furnace claim"); checks++;
			partialStore.clearContent();
			furnace.setAttached(VillagerPlus.SMITH_FURNACE, new ItemStack(Items.IRON_INGOT, 1));
			furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
			check(SmithSmelting.deliverClaimedOutput(level, ref, furnace) == 1
				&& furnace.getItem(2).getCount() == 2 && furnace.getAttached(VillagerPlus.SMITH_FURNACE).isEmpty(),
				"only the persisted batch amount is delivered, leaving unrelated excess untouched"); checks++;
			return checks;
		} finally {
			restore(savedStores);
			level.setBlock(pos, old, 3);
		}
	}

	private static List<ContainerSnapshot> snapshot(List<Container> containers) {
		List<ContainerSnapshot> saved = new ArrayList<>();
		for (Container container : containers) {
			List<ItemStack> items = new ArrayList<>();
			for (int slot = 0; slot < container.getContainerSize(); slot++) items.add(container.getItem(slot).copy());
			saved.add(new ContainerSnapshot(container, items));
		}
		return saved;
	}

	private static void restore(List<ContainerSnapshot> snapshots) {
		for (ContainerSnapshot snapshot : snapshots) {
			for (int slot = 0; slot < snapshot.items().size(); slot++) snapshot.container().setItem(slot, snapshot.items().get(slot).copy());
			snapshot.container().setChanged();
		}
	}

	private record ContainerSnapshot(Container container, List<ItemStack> items) { }

	private static List<Container> deliveryStores(ServerLevel level, VillageRef ref, ItemStack output) {
		String special = Specials.depotType(output);
		if (special != null) return Specials.containers(level, ref, special);
		List<Container> stores = new ArrayList<>();
		Container home = ref.chest(level);
		if (home != null) stores.add(home);
		stores.addAll(Lager.containers(level, ref.data()));
		return stores;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("SMITH SMELTING CHECK FAILED: " + message);
	}
}
