package de.villagerplus.job;

import de.villagerplus.village.VillageData;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Specials;
import de.villagerplus.village.VillageRef;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Checks the loaded recipes and real inventory transaction without supplying the final output. */
public final class PlanStoneSupplyChecks {
 private PlanStoneSupplyChecks() { }
 public static int run(ServerLevel level) {
  Map<BlockPos, Cell> before = new HashMap<>();
  VillageData data = new VillageData();
  data.planType = "granary";
  data.planOrigin = Optional.of(new BlockPos(10, 70, 20));
  data.wanted.add("minecraft:stone_bricks#4");
  SimpleContainer stock = new SimpleContainer(4);
  check(PlanStoneSupply.supports(level, true, true), "loaded stone and brick recipes");
  check(PlanStoneSupply.needsCobble(level, data, stock), "brick shortage requests raw stone");
  stock.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
  check(!PlanStoneSupply.needsCobble(level, data, stock), "existing raw input bounds mining");
  stock.setItem(0, new ItemStack(Items.STONE, 4));
  check(!PlanStoneSupply.needsCobble(level, data, stock), "existing intermediate bounds mining");
  data.wanted.add("minecraft:stone#1");
  check(!PlanStoneSupply.craftRequestedBricks(level, data, stock, true)
    && JobUtil.count(stock, s -> s.is(Items.STONE)) == 4, "separately requested stone is reserved");
  stock.setItem(0, new ItemStack(Items.STONE, 5));
  check(PlanStoneSupply.craftRequestedBricks(level, data, stock, true)
    && JobUtil.count(stock, s -> s.is(Items.STONE)) == 1
    && JobUtil.count(stock, s -> s.is(Items.STONE_BRICKS)) == 4, "real recipe delivers bricks and reserves stone");
  data.wanted.add("minecraft:cobblestone#2");
  stock.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
  check(PlanStoneSupply.planNeedsSmelting(level, data, stock, Items.COBBLESTONE, Items.STONE),
    "plan consumes only cobblestone beyond a separately requested reserve");
  check(!PlanStoneSupply.needsCobble(level, data, stock), "existing cobblestone surplus covers the remaining stone input");
  stock.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
  check(PlanStoneSupply.needsCobble(level, data, stock), "mine starts only after the cobblestone reserve is exhausted");
  stock.setItem(0, new ItemStack(Items.STONE, 5));
  stock.setItem(1, new ItemStack(Items.STONE_BRICKS, 6));
  data.wanted.add("minecraft:cracked_stone_bricks#2");
  check(PlanStoneSupply.planNeedsSmelting(level, data, stock, Items.STONE_BRICKS, Items.CRACKED_STONE_BRICKS),
    "cracking consumes only brick surplus beyond the separately requested amount");
  stock.setItem(1, new ItemStack(Items.STONE_BRICKS, 4));
  check(!PlanStoneSupply.planNeedsSmelting(level, data, stock, Items.STONE_BRICKS, Items.CRACKED_STONE_BRICKS),
    "separately requested stone bricks stay reserved");
  check(PlanStoneSupply.needsFurnace(level, data, stock), "an unmet loaded-recipe stone output asks for a communal furnace");
  BlockPos depot = new BlockPos(180, 80, 180);
  BlockPos furnacePos = depot.east(2);
  level.getChunkAt(depot);
  level.getChunkAt(furnacePos);
  remember(level, before, depot.below());
  remember(level, before, depot);
  remember(level, before, furnacePos.below());
  remember(level, before, furnacePos);
  try {
   level.setBlock(depot.below(), Blocks.STONE.defaultBlockState(), 3);
   level.setBlock(depot, Blocks.CHEST.defaultBlockState(), 3);
   level.setBlock(furnacePos.below(), Blocks.STONE.defaultBlockState(), 3);
   level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
   BlockEntity blockEntity = level.getBlockEntity(furnacePos);
   if (!(blockEntity instanceof AbstractFurnaceBlockEntity))
    throw new IllegalStateException("PLAN STONE SUPPLY CHECK FAILED: furnace block entity missing");
   AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) blockEntity;
   VillageData inFlight = new VillageData();
   inFlight.planType = "granary";
   inFlight.planOrigin = Optional.of(new BlockPos(200, 80, 200));
   inFlight.chest = Optional.of(depot);
   inFlight.wanted.add("minecraft:stone#1");
   SimpleContainer inFlightStock = new SimpleContainer(4);
   furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 0);
   furnace.setAttached(VillagerPlus.SMITH_FURNACE, new ItemStack(Items.STONE));
   furnace.setItem(0, new ItemStack(Items.COBBLESTONE));
   check(!PlanStoneSupply.needsCobble(level, inFlight, inFlightStock)
     && !PlanStoneSupply.needsFurnace(level, inFlight, inFlightStock)
     && !PlanStoneSupply.planNeedsSmelting(level, inFlight, inFlightStock, Items.COBBLESTONE, Items.STONE),
     "the real input and persistent output claim satisfy this plan without duplicate mining or smelting");
   furnace.setItem(0, ItemStack.EMPTY);
   furnace.setItem(2, new ItemStack(Items.STONE));
   check(!PlanStoneSupply.needsCobble(level, inFlight, inFlightStock)
     && !PlanStoneSupply.needsFurnace(level, inFlight, inFlightStock),
     "finished but not yet delivered output remains reserved to the same plan");
   var savedFurnace = furnace.saveWithFullMetadata(level.registryAccess());
   level.removeBlockEntity(furnacePos);
   BlockEntity restored = BlockEntity.loadStatic(furnacePos, level.getBlockState(furnacePos), savedFurnace.copy(), level.registryAccess());
   if (!(restored instanceof AbstractFurnaceBlockEntity))
    throw new IllegalStateException("PLAN STONE SUPPLY CHECK FAILED: restored furnace block entity missing");
   level.setBlockEntity(restored);
   furnace = (AbstractFurnaceBlockEntity) restored;
   check(!PlanStoneSupply.needsCobble(level, inFlight, inFlightStock)
     && !PlanStoneSupply.needsFurnace(level, inFlight, inFlightStock),
     "persisted output claim remains reserved after furnace reload");
   furnace.setAttached(VillagerPlus.COMMUNAL_FURNACE, 1);
   check(PlanStoneSupply.needsCobble(level, inFlight, inFlightStock)
     && PlanStoneSupply.needsFurnace(level, inFlight, inFlightStock),
     "a furnace owned by another communal process cannot satisfy the stone plan");
  } finally {
   restore(level, before);
  }
  SimpleContainer full = new SimpleContainer(2);
  full.setItem(0, new ItemStack(Items.STONE, 5));
  full.setItem(1, new ItemStack(Items.DIRT, 64));
  check(!PlanStoneSupply.craftRequestedBricks(level, data, full, true)
    && JobUtil.count(full, s -> s.is(Items.STONE)) == 5
    && JobUtil.count(full, s -> s.is(Items.DIRT)) == 64, "full storage leaves inputs untouched");
  data.clearPlan();
  check(!PlanStoneSupply.needsCobble(level, data, stock)
    && !PlanStoneSupply.needsFurnace(level, data, stock)
    && !PlanStoneSupply.craftRequestedBricks(level, data, full, true), "abandoned plan stops sourcing and crafting");
  int pickaxeChecks = pickaxeRecipeChecks(level) + storedPickaxeCheck(level);
  return 18 + pickaxeChecks;
 }

 private static int pickaxeRecipeChecks(ServerLevel level) {
  SimpleContainer stock = new SimpleContainer(4);
  stock.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
  stock.setItem(1, new ItemStack(Items.STICK, 2));
  SimpleContainer inventory = new SimpleContainer(8);
  check(PlanStoneSupply.craftPlanPickaxe(level, stock, inventory)
    && JobUtil.count(inventory, stack -> stack.is(Items.WOODEN_PICKAXE)) == 1
    && JobUtil.count(stock, stack -> stack.is(Items.OAK_PLANKS) || stack.is(Items.STICK)) == 0,
    "loaded wooden-pickaxe recipe consumes its real plank and stick inputs");
  SimpleContainer shortStock = new SimpleContainer(2);
  shortStock.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
  shortStock.setItem(1, new ItemStack(Items.STICK, 2));
  SimpleContainer noTool = new SimpleContainer(8);
  check(!PlanStoneSupply.craftPlanPickaxe(level, shortStock, noTool)
    && JobUtil.count(shortStock, stack -> stack.is(Items.OAK_PLANKS)) == 2
    && JobUtil.count(shortStock, stack -> stack.is(Items.STICK)) == 2 && noTool.isEmpty(),
    "insufficient recipe inputs remain untouched and do not create a free pickaxe");
  return 2;
 }

 private static int storedPickaxeCheck(ServerLevel level) {
  Map<BlockPos, Cell> before = new HashMap<>();
  Villager worker = null;
  BlockPos bellPos = new BlockPos(220, 80, 180);
  BlockPos toolshedPos = bellPos.east(3);
  try {
   level.getChunkAt(bellPos);
   level.getChunkAt(toolshedPos);
   remember(level, before, bellPos);
   remember(level, before, toolshedPos);
   level.setBlock(bellPos, Blocks.BELL.defaultBlockState().setValue(
     net.minecraft.world.level.block.BellBlock.FACING, Direction.NORTH), 3);
   if (!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell))
    throw new IllegalStateException("PLAN STONE SUPPLY CHECK FAILED: bell block entity missing");
   level.setBlock(toolshedPos, Blocks.CHEST.defaultBlockState(), 3);
   if (!(level.getBlockEntity(toolshedPos) instanceof net.minecraft.world.Container toolshed))
    throw new IllegalStateException("PLAN STONE SUPPLY CHECK FAILED: toolshed container missing");
   VillageData data = new VillageData();
   data.planType = "granary";
   data.planOrigin = Optional.of(bellPos.east(10));
   data.planStep = 9;
   data.wanted.add("minecraft:stone_bricks#4");
   data.extra.planMaterialWait = Optional.of("waits for the same active plan");
   VillageRef ref = new VillageRef(bellPos, data, bell);
   Specials.set(ref, Blueprints.TOOLSHED, toolshedPos);
   toolshed.setItem(0, new ItemStack(Items.IRON_PICKAXE));
   worker = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
   if (worker == null) throw new IllegalStateException("PLAN STONE SUPPLY CHECK FAILED: villager missing");
   worker.setPos(toolshedPos.getX() + .5, toolshedPos.getY(), toolshedPos.getZ() + .5);
   worker.setNoAi(true);
   worker.setInvulnerable(true);
   worker.addTag("vp090_fixture");
   level.addFreshEntity(worker);
   Ctx context = new Ctx(level, worker, new JobState(),
     worker.getAttachedOrCreate(VillagerPlus.MEMORY), ref, level.getGameTime());
   check(PlanStoneSupply.supplyMiningPickaxe(context, new SimpleContainer(4))
     && JobUtil.count(worker.getInventory(), stack -> stack.is(Items.IRON_PICKAXE)) == 1
     && JobUtil.count(toolshed, stack -> stack.is(Items.IRON_PICKAXE)) == 0,
     "plan mining withdraws and debits a real pickaxe from its public toolshed");
   check(data.planStep == 9 && data.wanted.contains("minecraft:stone_bricks#4")
     && data.extra.planMaterialWait.equals(Optional.of("waits for the same active plan")),
     "tool preparation preserves the plan cursor, demand, and saved wait state");
   return 2;
  } finally {
   if (worker != null) worker.discard();
   restore(level, before);
  }
 }
 private record Cell(net.minecraft.world.level.block.state.BlockState state, net.minecraft.nbt.CompoundTag blockEntity) { }
 private static void remember(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
  if (before.containsKey(pos)) return;
  BlockEntity entity = level.getBlockEntity(pos);
  before.put(pos, new Cell(level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
 }
 private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
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
 private static void check(boolean value, String message) {
  if (!value) throw new IllegalStateException("PLAN STONE SUPPLY CHECK FAILED: " + message);
 }
}
