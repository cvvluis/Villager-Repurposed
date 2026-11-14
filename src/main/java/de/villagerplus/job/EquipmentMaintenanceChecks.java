package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Balance;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/** Explicit world checks for SmithJob equipment upkeep. */
public final class EquipmentMaintenanceChecks {
	private static int passed;
	private static void check(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("EQUIPMENT CHECK FAILED: " + label);
		passed++;
		System.out.println("VP090 PASS " + label);
	}

	private EquipmentMaintenanceChecks() {
	}

	public static int run(ServerLevel level, BlockPos at) {
		passed = 0;
		VillageRef ref = Checks090.fixtureForEquipment(level, at);
		BlockPos shed = at.offset(6, 0, 6);
		BlockPos barrel = shed.east(2);
		BlockPos publicSupplyPos = barrel.south(2);
		BlockPos anvil = shed.east(4);
		level.getChunk(barrel);
		level.setBlock(barrel.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
		level.setBlock(publicSupplyPos.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(publicSupplyPos, Blocks.BARREL.defaultBlockState(), 3);
		level.setBlock(anvil.below(), Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(anvil, Blocks.ANVIL.defaultBlockState(), 3);
		Specials.set(ref, de.villagerplus.build.Blueprints.TOOLSHED, shed);
		BarrelBlockEntity depot = (BarrelBlockEntity) level.getBlockEntity(barrel);
		BarrelBlockEntity publicSupply = (BarrelBlockEntity) level.getBlockEntity(publicSupplyPos);
		ItemStack previousPublicMaterial = publicSupply.getItem(0).copy();
		var previousDepotOwner = depot.getAttached(VillagerPlus.OWNER);
		ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		pickaxe.setDamageValue(600);
		depot.setItem(0, pickaxe);
		depot.setItem(1, new ItemStack(Items.DIAMOND, 2));

		Villager smith = EntityTypes.VILLAGER.spawn(level, anvil.east(1), EntitySpawnReason.COMMAND);
		smith.addTag("vp090_fixture");
		smith.setNoAi(true);
		var memory = smith.getAttachedOrCreate(VillagerPlus.MEMORY);
		JobState state = JobManager.state(smith);
		int before = pickaxe.getDamageValue();
		int count = depot.getItem(1).getCount();
		boolean repaired = new SmithJob().secondary(new Ctx(level, smith, state, memory, ref, level.getGameTime()));
		check(repaired && pickaxe.getDamageValue() == before - pickaxe.getMaxDamage() / 4,
			"smith repairs one quarter durability at the toolshed anvil");
		check(count == depot.getItem(1).getCount() + 1,
			"toolshed repair consumes exactly one compatible repair material");

		depot.clearContent();
		ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
		Component helmetName = Component.literal("Heirloom helmet");
		helmet.set(DataComponents.CUSTOM_NAME, helmetName);
		ItemStack chestplate = new ItemStack(Items.IRON_CHESTPLATE);
		chestplate.enchant(
			level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION), 2);
		ItemStack leggings = new ItemStack(Items.GOLDEN_LEGGINGS);
		ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);
		ItemStack[] armor = {helmet, chestplate, leggings, boots};
		Item[] repairMaterials = {Items.LEATHER, Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND};
		String[] armorNames = {"helmet", "chestplate", "leggings", "boots"};
		int[] armorDamage = new int[armor.length];
		for (int i = 0; i < armor.length; i++) {
			armor[i].setDamageValue(armor[i].getMaxDamage() / 2);
			armorDamage[i] = armor[i].getDamageValue();
			depot.setItem(i, armor[i]);
			depot.setItem(i + armor.length, new ItemStack(repairMaterials[i]));
		}
		state.toolRepairNextWorkAt = 0;
		var chestplateEnchantments = chestplate.get(DataComponents.ENCHANTMENTS);
		for (int i = 0; i < armor.length; i++) {
			boolean armorRepaired = EquipmentMaintenance.repairAtToolshed(
				new Ctx(level, smith, state, memory, ref, level.getGameTime() + 201L + i));
			int expectedDamage = Math.max(0, armorDamage[i] - Math.max(1, armor[i].getMaxDamage() / 4));
			check(armorRepaired && armor[i].getDamageValue() == expectedDamage
				&& depot.getItem(i + armor.length).isEmpty(),
				"toolshed repairs " + armorNames[i] + " with exactly one compatible village material");
		}
		check(helmet.get(DataComponents.CUSTOM_NAME).equals(helmetName)
			&& chestplate.get(DataComponents.ENCHANTMENTS).equals(chestplateEnchantments),
			"armor repair preserves the same item's custom name and enchantments");

		depot.clearContent();
		ItemStack firstHelmet = new ItemStack(Items.DIAMOND_HELMET);
		ItemStack secondHelmet = new ItemStack(Items.DIAMOND_HELMET);
		firstHelmet.setDamageValue(firstHelmet.getMaxDamage() / 2);
		secondHelmet.setDamageValue(secondHelmet.getMaxDamage() / 2);
		depot.setItem(0, firstHelmet);
		depot.setItem(1, secondHelmet);
		state.toolRepairNextWorkAt = 0;
		boolean usedDuplicateAsMaterial = EquipmentMaintenance.repairAtToolshed(
			new Ctx(level, smith, state, memory, ref, level.getGameTime() + 500));
		check(!usedDuplicateAsMaterial && depot.getItem(0).getCount() == 1 && depot.getItem(1).getCount() == 1
			&& firstHelmet.isDamaged() && secondHelmet.isDamaged(),
			"a duplicate valuable armor piece is never consumed as repair material");

		depot.clearContent();
		ItemStack interruptedPickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		interruptedPickaxe.setDamageValue(900);
		depot.setItem(0, interruptedPickaxe);
		depot.setItem(1, new ItemStack(Items.DIAMOND, 1));
		state.toolRepairAnvil = null;
		state.toolRepairCheckAt = 0;
		state.toolRepairNextWorkAt = 0;
		state.walkStart = 0;
		state.bestDist = 0.0;
		state.stuckCount = 0;
		BlockPos routeStart = anvil.east(8);
		level.getChunkAt(routeStart);
		smith.teleportTo(routeStart.getX() + 0.5, routeStart.getY(), routeStart.getZ() + 0.5);
		int interruptedDamage = interruptedPickaxe.getDamageValue();
		long interruptedDiamonds = depot.getItem(1).getCount();
		long repairStartAt = level.getGameTime() + 600L;
		boolean startedTowardAnvil = EquipmentMaintenance.repairAtToolshed(
			new Ctx(level, smith, state, memory, ref, repairStartAt));
		check(startedTowardAnvil && anvil.equals(state.toolRepairAnvil) && state.walkStart == repairStartAt
			&& Math.abs(smith.getX() - (anvil.getX() + 0.5)) > 2.8
			&& interruptedPickaxe.getDamageValue() == interruptedDamage && depot.getItem(1).getCount() == interruptedDiamonds,
			"smith starts a real-anvil repair route without spending its tool or repair material before arrival");

		level.setBlock(anvil, Blocks.COBBLESTONE.defaultBlockState(), 3);
		boolean abandonedMissingTarget = EquipmentMaintenance.repairAtToolshed(
			new Ctx(level, smith, state, memory, ref, repairStartAt + 1));
		check(!abandonedMissingTarget && state.toolRepairAnvil == null
			&& Math.abs(smith.getX() - (anvil.getX() + 0.5)) > 2.8
			&& interruptedPickaxe.getDamageValue() == interruptedDamage && depot.getItem(1).getCount() == interruptedDiamonds,
			"anvil removed during the walk clears the cached target without consuming repair material or durability");

		level.setBlock(anvil, Blocks.ANVIL.defaultBlockState(), 3);
		boolean retriedAnvilScan = EquipmentMaintenance.repairAtToolshed(
			new Ctx(level, smith, state, memory, ref, repairStartAt + 201));
		check(retriedAnvilScan && anvil.equals(state.toolRepairAnvil)
			&& interruptedPickaxe.getDamageValue() == interruptedDamage && depot.getItem(1).getCount() == interruptedDiamonds,
			"repair scan retries after its interval and selects the restored anvil without spending resources en route");

		// The selected tool can become private while the smith is walking. Keep its repair
		// material in a separate public barrel so a stale captured Container would still pass
		// the material check; the tool itself must nevertheless remain untouched.
		try {
			level.setBlock(anvil, Blocks.ANVIL.defaultBlockState(), 3);
			depot.clearContent();
			ItemStack privatePickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
			privatePickaxe.setDamageValue(900);
			depot.setItem(0, privatePickaxe);
			publicSupply.setItem(0, new ItemStack(Items.DIAMOND));
			publicSupply.setChanged();
			int publicDiamonds = Balance.storage(level, ref).stream()
				.mapToInt(box -> JobUtil.count(box, stack -> stack.is(Items.DIAMOND))).sum();
			check(publicDiamonds == 1, "private-depot fixture exposes its one diamond through current communal supplies [count="
				+ publicDiamonds + ", slot=" + publicSupply.getItem(0) + "]");
			state.toolRepairAnvil = null;
			state.toolRepairCheckAt = 0;
			state.toolRepairNextWorkAt = 0;
			state.walkStart = 0;
			state.bestDist = 0.0;
			state.stuckCount = 0;
			state.clearTarget();
			smith.teleportTo(routeStart.getX() + 0.5, routeStart.getY(), routeStart.getZ() + 0.5);
			long privateRepairStart = repairStartAt + 400L;
			boolean startedTowardPrivateTarget = EquipmentMaintenance.repairAtToolshed(
				new Ctx(level, smith, state, memory, ref, privateRepairStart));
			int privateDamageBefore = privatePickaxe.getDamageValue();
			check(startedTowardPrivateTarget && state.walkStart == privateRepairStart
				&& publicSupply.getItem(0).getCount() == 1,
				"smith keeps public repair material and the damaged target unchanged before arrival [started="
					+ startedTowardPrivateTarget + ", walkStart=" + state.walkStart + ", expected=" + privateRepairStart
					+ ", publicMaterial=" + publicSupply.getItem(0) + ", anvil=" + state.toolRepairAnvil
					+ ", nextWork=" + state.toolRepairNextWorkAt + ", target=" + state.target + "]");

			depot.setAttached(VillagerPlus.OWNER, smith.getUUID());
			depot.setChanged();
			smith.teleportTo(anvil.getX() + 1.5, anvil.getY(), anvil.getZ() + 0.5);
			boolean refusedPrivateTarget = EquipmentMaintenance.repairAtToolshed(
				new Ctx(level, smith, state, memory, ref, privateRepairStart + 1L));
			check(!refusedPrivateTarget && privatePickaxe.getDamageValue() == privateDamageBefore
				&& publicSupply.getItem(0).getCount() == 1,
				"arrival recheck skips a newly private toolshed item and leaves the public repair material untouched");
		} finally {
			if (previousDepotOwner == null) depot.removeAttached(VillagerPlus.OWNER);
			else depot.setAttached(VillagerPlus.OWNER, previousDepotOwner);
			depot.setChanged();
			publicSupply.setItem(0, previousPublicMaterial);
			publicSupply.setChanged();
		}

		// A compatible repair ingredient can disappear or be replaced while the smith walks.
		// Recheck at the anvil and leave the damaged item plus incompatible public stock alone.
		depot.clearContent();
		ItemStack noMaterialPickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		noMaterialPickaxe.setDamageValue(900);
		depot.setItem(0, noMaterialPickaxe);
		publicSupply.setItem(0, new ItemStack(Items.DIAMOND));
		publicSupply.setChanged();
		state.toolRepairAnvil = null;
		state.toolRepairCheckAt = 0;
		state.toolRepairNextWorkAt = 0;
		state.walkStart = 0;
		state.bestDist = 0.0;
		state.stuckCount = 0;
		smith.teleportTo(routeStart.getX() + 0.5, routeStart.getY(), routeStart.getZ() + 0.5);
		long materialRepairStart = repairStartAt + 500L;
		boolean startedBeforeMaterialChange = EquipmentMaintenance.repairAtToolshed(
			new Ctx(level, smith, state, memory, ref, materialRepairStart));
		int noMaterialDamage = noMaterialPickaxe.getDamageValue();
		check(startedBeforeMaterialChange && noMaterialDamage == 900
			&& publicSupply.getItem(0).is(Items.DIAMOND),
			"smith reserves neither the damaged tool nor its real diamond before reaching the anvil");
		publicSupply.setItem(0, new ItemStack(Items.IRON_INGOT));
		publicSupply.setChanged();
		smith.teleportTo(anvil.getX() + 1.5, anvil.getY(), anvil.getZ() + 0.5);
		boolean declinedWithoutMaterial = EquipmentMaintenance.repairAtToolshed(
			new Ctx(level, smith, state, memory, ref, materialRepairStart + 1L));
		check(!declinedWithoutMaterial && noMaterialPickaxe.getDamageValue() == noMaterialDamage
			&& publicSupply.getItem(0).is(Items.IRON_INGOT)
			&& publicSupply.getItem(0).getCount() == 1,
			"repair reports no work after the valid material is gone and preserves equipment plus remaining stock");
		publicSupply.setItem(0, previousPublicMaterial);
		publicSupply.setChanged();

		level.setBlock(anvil, Blocks.AIR.defaultBlockState(), 3);
		pickaxe.setDamageValue(900);
		depot.setItem(0, pickaxe);
		depot.setItem(1, new ItemStack(Items.DIAMOND, 1));
		int unchangedDamage = pickaxe.getDamageValue();
		int unchangedDiamonds = depot.getItem(1).getCount();
		state.toolRepairCheckAt = 0;
		state.toolRepairNextWorkAt = 0;
		boolean noAnvil = EquipmentMaintenance.repairAtToolshed(new Ctx(level, smith, state, memory, ref, level.getGameTime() + 201));
		check(!noAnvil && pickaxe.getDamageValue() == unchangedDamage && depot.getItem(1).getCount() == unchangedDiamonds,
			"missing toolshed anvil leaves both equipment and materials untouched");

		smith.discard();
		Specials.remove(ref, de.villagerplus.build.Blueprints.TOOLSHED);
		for (BlockPos pos : new BlockPos[] {barrel, barrel.below(), publicSupplyPos, publicSupplyPos.below(), anvil, anvil.below(), ref.bell(), ref.bell().east(3), ref.bell().below(), ref.bell().east(3).below()}) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}
		return passed;
	}
}
