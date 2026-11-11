package de.villagerplus.job;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/** Arrival is simulated explicitly; inventory transactions and loaded recipes execute on a real server. */
public final class EquipmentProductionChecks {
 private EquipmentProductionChecks() { }
 private static void check(boolean value,String label) {if(!value)throw new IllegalStateException("EQUIPMENT PRODUCTION: "+label);}
 public static int run(ServerLevel level,BlockPos at) {
  var ref=Checks090.fixtureForEquipment(level,at);
  BlockPos shed=at.east(20),barrel=shed.east(),bench=shed.east(3);
  level.getChunkAt(bench);level.setBlock(barrel,Blocks.BARREL.defaultBlockState(),3);
  for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)
   level.setBlock(barrel.offset(dx,-1,dz),Blocks.STONE.defaultBlockState(),3);
  Specials.set(ref,Blueprints.TOOLSHED,shed);
  var depot=(BarrelBlockEntity)level.getBlockEntity(barrel);
  var source=ref.chest(level);source.clearContent();
  source.setItem(0,new ItemStack(Items.IRON_INGOT,3));source.setItem(1,new ItemStack(Items.STICK,2));
  depot.setItem(0,new ItemStack(Items.DIAMOND_AXE));depot.setItem(1,new ItemStack(Items.DIAMOND_SHOVEL));
  depot.setItem(2,new ItemStack(Items.DIAMOND_HOE));depot.setItem(3,new ItemStack(Items.DIAMOND_SWORD));
  var v=EntityTypes.VILLAGER.spawn(level,bench.south(),EntitySpawnReason.COMMAND);
  v.addTag("vp090_fixture");v.setNoAi(true);v.setInvulnerable(true);
  var state=new JobState();var memory=v.getAttachedOrCreate(VillagerPlus.MEMORY);
  long now=level.getGameTime()+1000;int checks=0;
  try {
   Ctx c=new Ctx(level,v,state,memory,ref,now);
   check(!EquipmentProduction.work(c)&&source.getItem(0).getCount()==3,"no station leaves source unchanged");checks++;
   level.setBlock(bench,Blocks.CRAFTING_TABLE.defaultBlockState(),3);state.toolCraftScanAt=0;
   for(int slot=4;slot<depot.getContainerSize();slot++)depot.setItem(slot,new ItemStack(Items.DIAMOND_AXE));
   check(!EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&source.getItem(1).getCount()==2&&v.getInventory().isEmpty(),"full toolshed suppresses ingredient pickup before a replacement is started");checks++;
   for(int slot=4;slot<depot.getContainerSize();slot++)depot.setItem(slot,ItemStack.EMPTY);
   for(int slot=0;slot<v.getInventory().getContainerSize();slot++)v.getInventory().setItem(slot,new ItemStack(Items.DIAMOND,64));
   v.teleportTo(at.getX()+2.5,at.getY(),at.getZ()+.5);
   check(!EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&source.getItem(1).getCount()==2,
      "full villager inventory leaves communal recipe ingredients untouched");checks++;
   v.getInventory().clearContent();v.teleportTo(bench.getX()+.5,bench.getY(),bench.getZ()+2.5);
   check(EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&v.getInventory().isEmpty(),"distant worker must fetch ingredients before crafting");checks++;
   v.teleportTo(at.getX()+2.5,at.getY(),at.getZ()+.5);
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT))==3&&source.getItem(0).isEmpty(),"physical pickup transfers exactly three ingots");checks++;
   source.setItem(1,ItemStack.EMPTY);
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT))==3
     &&JobUtil.count(v.getInventory(),s->s.is(Items.STICK))==0
     &&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==0,
     "an interrupted recipe with a missing ingredient retains all carried materials without creating output");checks++;
   source.setItem(1,new ItemStack(Items.STICK,2));
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.STICK))==2&&source.getItem(1).isEmpty(),"second pickup transfers exactly two sticks");checks++;
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==0&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT))==3,"walking to bench does not consume ingredients remotely");checks++;
   v.teleportTo(bench.getX()+.5,bench.getY(),bench.getZ()+2.5);
   for(int slot=4;slot<depot.getContainerSize();slot++)depot.setItem(slot,new ItemStack(Items.DIAMOND_AXE));
   check(!EquipmentProduction.work(c)&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==0
     &&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT))==3&&JobUtil.count(v.getInventory(),s->s.is(Items.STICK))==2,
     "capacity change at the bench leaves all replacement ingredients in inventory");checks++;
   for(int slot=4;slot<depot.getContainerSize();slot++)depot.setItem(slot,ItemStack.EMPTY);
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT)||s.is(Items.STICK))==0,"loaded pickaxe recipe consumes three ingots and two sticks at bench");checks++;
   for(int slot=4;slot<depot.getContainerSize();slot++)depot.setItem(slot,new ItemStack(Items.DIAMOND_AXE));
   check(!EquipmentProduction.work(c)&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1,"full depot preserves carried replacement");checks++;
   depot.setItem(4,ItemStack.EMPTY);depot.setAttached(VillagerPlus.OWNER,v.getUUID());
   check(!EquipmentProduction.work(c)&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1,"private depot cannot receive communal tool");checks++;
   depot.removeAttached(VillagerPlus.OWNER);
   v.teleportTo(barrel.getX()+.5,barrel.getY(),barrel.getZ()+2.5);
   boolean startedDelivery=EquipmentProduction.work(c);
   check(startedDelivery&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1&&state.target!=null,
     "finished replacement stays with smith while the route to its reserved toolshed stand starts");checks++;
   BlockPos deliveryStand=state.target.immutable();
   v.teleportTo(deliveryStand.getX()+.5,deliveryStand.getY(),deliveryStand.getZ()+.5);
   state.cooldownUntil=0;
   boolean delivered=EquipmentProduction.work(new Ctx(level,v,state,memory,ref,now+1));
   check(delivered&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==0
     &&JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE))==1,"finished replacement transfers to toolshed only after reaching its reserved stand"
       +" [delivered="+delivered+", carried="+JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))
       +", depot="+JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE))+", target="+state.target+", stand="+deliveryStand
       +", position="+v.blockPosition()+", blockedUntil="+state.depositBlockedUntil+", cooldownUntil="+state.cooldownUntil+"]");checks++;
   depot.setItem(4,ItemStack.EMPTY);
   // The same iron also makes shears. Cover that separate reserve so this case isolates
   // the configured zero target for iron pickaxes instead of suppressing every iron recipe.
   depot.setItem(5,new ItemStack(Items.SHEARS));
   source.setItem(0,new ItemStack(Items.IRON_INGOT,3));source.setItem(1,new ItemStack(Items.STICK,2));
   ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES,Map.of("minecraft:iron_pickaxe",0));
   check(!EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&source.getItem(1).getCount()==2,"zero stock target suppresses recipe before pickup");checks++;
   ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
   var pending=EntityTypes.VILLAGER.spawn(level,bench.south(2),EntitySpawnReason.COMMAND);
   pending.addTag("vp090_fixture");pending.setNoAi(true);pending.setInvulnerable(true);
   pending.getBrain().setMemory(MemoryModuleType.MEETING_POINT,GlobalPos.of(level.dimension(),ref.bell()));
   ItemStack pendingWorn=new ItemStack(Items.IRON_PICKAXE);pendingWorn.setDamageValue((pendingWorn.getMaxDamage()*3+3)/4);
   pending.getInventory().setItem(0,pendingWorn);
   try {
    ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES,Map.of("minecraft:iron_pickaxe",1));
    check(EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&source.getItem(1).getCount()==2&&v.getInventory().isEmpty(),
       "a worn tool carried by another resident does not block scheduling its replacement");checks++;
    ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
    pending.getInventory().setItem(0,new ItemStack(Items.IRON_PICKAXE));
    check(!EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&source.getItem(1).getCount()==2,
       "healthy tool already carried by another resident satisfies the shared reserve");checks++;
    pending.getInventory().clearContent();
    for(EquipmentSlot hand:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND)) {
     pending.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
     pending.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
     pending.setItemSlot(hand,new ItemStack(Items.IRON_PICKAXE));
     check(!EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&source.getItem(1).getCount()==2&&v.getInventory().isEmpty(),
        "healthy "+hand.getName()+" tool already equipped by another resident satisfies reserve without material pickup");checks++;
    }
    pending.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
    pending.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
    ItemStack carriedWorn=new ItemStack(Items.IRON_PICKAXE);
    carriedWorn.setDamageValue((carriedWorn.getMaxDamage()*3+3)/4);
    pending.getInventory().setItem(0,carriedWorn);
    ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES,Map.of("minecraft:iron_pickaxe",1));
    v.teleportTo(at.getX()+2.5,at.getY(),at.getZ()+.5);
    check(EquipmentProduction.work(c)&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT))==3&&source.getItem(0).isEmpty(),
       "worn tool carried by another resident does not reserve the only delivery slot for unusable stock");checks++;
    v.getInventory().clearContent();source.setItem(0,new ItemStack(Items.IRON_INGOT,3));source.setItem(1,new ItemStack(Items.STICK,2));
    ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
   } finally {pending.discard();ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);}
   ItemStack almostWorn=new ItemStack(Items.IRON_PICKAXE);almostWorn.setDamageValue(almostWorn.getMaxDamage()*3/4-1);depot.setItem(4,almostWorn);
   check(!EquipmentProduction.work(c)&&source.getItem(0).getCount()==3&&source.getItem(1).getCount()==2,
      "a compatible pickaxe below the replacement durability threshold satisfies reserve");checks++;
   ItemStack worn=new ItemStack(Items.IRON_PICKAXE);worn.setDamageValue((worn.getMaxDamage()*3+3)/4);depot.setItem(4,worn);
   depot.setItem(5,ItemStack.EMPTY); // reserve a separate slot for the new replacement
   v.teleportTo(at.getX()+2.5,at.getY(),at.getZ()+.5);
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT))==3&&source.getItem(0).isEmpty(),
      "a pickaxe at or above the durability threshold triggers real material pickup");checks++;
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.STICK))==2&&source.getItem(1).isEmpty(),
      "worn-tool replacement collects its second recipe ingredient without loss");checks++;
   v.teleportTo(bench.getX()+.5,bench.getY(),bench.getZ()+2.5);EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1
      &&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT)||s.is(Items.STICK))==0,
      "loaded recipe creates a durable replacement for the worn compatible tool");checks++;
   v.teleportTo(barrel.getX()+.5,barrel.getY(),barrel.getZ()+2.5);
   boolean replacementRoute=EquipmentProduction.work(c);
   check(replacementRoute&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1
      &&JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE)&&s.getDamageValue()>0)==1&&state.target!=null,
      "fresh replacement remains carried as the smith routes to its shared toolshed stand");checks++;
   BlockPos replacementStand=state.target.immutable();
   v.teleportTo(replacementStand.getX()+.5,replacementStand.getY(),replacementStand.getZ()+.5);
   state.cooldownUntil=0;
   EquipmentProduction.work(new Ctx(level,v,state,memory,ref,c.now()+1));
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==0
      &&JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE))==2
      &&JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE)&&s.getDamageValue()==0)==1,
      "fresh replacement reaches the shared toolshed while worn stock remains distinguishable");checks++;

   depot.setItem(4,worn.copy());depot.setItem(5,ItemStack.EMPTY);
   source.setItem(0,new ItemStack(Items.IRON_INGOT,3));source.setItem(1,new ItemStack(Items.STICK,2));
   ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES,Map.of("minecraft:iron_pickaxe",1));
   state.cooldownUntil=0;
   v.teleportTo(at.getX()+2.5,at.getY(),at.getZ()+.5);
   EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT))==3&&source.getItem(0).isEmpty(),
      "a broken pickaxe in the toolshed does not consume the usable-stock target before replacement");checks++;
   EquipmentProduction.work(c);v.teleportTo(bench.getX()+.5,bench.getY(),bench.getZ()+2.5);EquipmentProduction.work(c);
   check(JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1
       &&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT)||s.is(Items.STICK))==0,
       "the registered pickaxe recipe consumes its exact ingredients and produces a real output beside broken stock");checks++;
    state.cooldownUntil=0;
    boolean cappedReplacementDelivered=deliverCarriedEquipment(c,barrel);
    check(cappedReplacementDelivered&&JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE))==2
       &&JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE)&&s.getDamageValue()==0)==1
       &&JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE)&&s.getDamageValue()>0)==1
       &&v.getInventory().isEmpty(),
       "the stock cap is satisfied by one usable replacement while the broken tool remains available for repair");checks++;
   ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);

   depot.clearContent();
   depot.setItem(0,new ItemStack(Items.DIAMOND_AXE));depot.setItem(1,new ItemStack(Items.DIAMOND_SHOVEL));
   depot.setItem(2,new ItemStack(Items.DIAMOND_HOE));depot.setItem(3,new ItemStack(Items.DIAMOND_SWORD));
   for(int slot=4;slot<depot.getContainerSize()-1;slot++)depot.setItem(slot,new ItemStack(Items.DIAMOND_AXE));
   source.setItem(0,ItemStack.EMPTY);source.setItem(1,ItemStack.EMPTY);
   v.getInventory().clearContent();
   v.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT,3));
   v.getInventory().setItem(1,new ItemStack(Items.STICK,2));
   v.teleportTo(bench.getX()+.5,bench.getY(),bench.getZ()+2.5);
   boolean craftedIntoLastSlot=EquipmentProduction.work(c);
   int carriedPickaxes=JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE));
   int emptyDepotSlots=0;
   for(int slot=0;slot<depot.getContainerSize();slot++)if(depot.getItem(slot).isEmpty())emptyDepotSlots++;
   int carriedIron=JobUtil.count(v.getInventory(),s->s.is(Items.IRON_INGOT));
   int carriedSticks=JobUtil.count(v.getInventory(),s->s.is(Items.STICK));
   check(craftedIntoLastSlot&&carriedPickaxes==1&&emptyDepotSlots==1&&carriedIron==0&&carriedSticks==0,
       "one free slot accepts the real replacement output (work="+craftedIntoLastSlot+", carriedPickaxes="+carriedPickaxes
           +", emptySlots="+emptyDepotSlots+", target="+StockPolicy.target(ref,Items.IRON_PICKAXE)
           +", quotaRoom="+StockPolicy.deliveryRoom(level,ref,Items.IRON_PICKAXE)
           +", toolshedContainers="+Specials.containers(level,ref,Blueprints.TOOLSHED).size()
           +", iron="+carriedIron
           +", sticks="+carriedSticks
           +", sourceIron="+JobUtil.count(source,s->s.is(Items.IRON_INGOT))
           +", sourceSticks="+JobUtil.count(source,s->s.is(Items.STICK))+")");checks++;
   depot.setItem(depot.getContainerSize()-1,new ItemStack(Items.DIAMOND_AXE));
   v.teleportTo(barrel.getX()+.5,barrel.getY(),barrel.getZ()+2.5);
   check(!EquipmentProduction.work(c)&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==1,
       "a chamber filled after crafting keeps the output with the worker until capacity returns");checks++;
   depot.setItem(depot.getContainerSize()-1,ItemStack.EMPTY);
   check(deliverCarriedEquipment(c,barrel)&&JobUtil.count(v.getInventory(),s->s.is(Items.IRON_PICKAXE))==0
       &&JobUtil.count(depot,s->s.is(Items.IRON_PICKAXE))==1,
       "the retained replacement is delivered to the toolshed when its last slot reopens");checks++;

   checks+=replaceWornFamily(level,ref,c,depot,source,v,barrel,bench,Items.DIAMOND_AXE,Items.IRON_AXE,3,2);
   checks+=replaceWornFamily(level,ref,c,depot,source,v,barrel,bench,Items.DIAMOND_SHOVEL,Items.IRON_SHOVEL,1,2);
   checks+=replaceWornFamily(level,ref,c,depot,source,v,barrel,bench,Items.DIAMOND_HOE,Items.IRON_HOE,2,2);
   checks+=replaceWornFamily(level,ref,c,depot,source,v,barrel,bench,Items.DIAMOND_SWORD,Items.IRON_SWORD,2,1);
   checks+=replaceWornArmorSlots(level,ref,c,depot,source,v,barrel,bench);
   checks+=produceLoadedSidearms(level,ref,c,depot,source,v,barrel,bench);
   checks+=produceLoadedDurableUtilityTools(level,ref,c,depot,source,v,barrel,bench);

   level.setBlock(shed.south(3),Blocks.ANVIL.defaultBlockState(),3);
   state.idleUntil[1]=0;
   EquipmentMaintenance.repairAtToolshed(c);
   check(state.idleUntil[1]==0,"empty repair queue does not idle other smith work");checks++;
   return checks;
  } finally {
   v.discard();ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);Specials.remove(ref,Blueprints.TOOLSHED);
   for(BlockPos pos:new BlockPos[]{at,at.below(),at.east(3),at.east(3).below(),barrel,bench,shed.south(3)})level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
  }
 }

 private static int replaceWornArmorSlots(ServerLevel level,de.villagerplus.village.VillageRef ref,Ctx c,
      BarrelBlockEntity depot,net.minecraft.world.Container source,net.minecraft.world.entity.npc.villager.Villager worker,
      BlockPos barrel,BlockPos bench) {
  List<EquipmentSlot> slots=List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET);
  Map<EquipmentSlot,LoadedEquipmentRecipe> recipes=new java.util.EnumMap<>(EquipmentSlot.class);
  int checks=0;
  for(EquipmentSlot slot:slots) {
   LoadedEquipmentRecipe recipe=loadedArmorRecipe(level,slot);
   check(recipe!=null,"a registered crafting recipe is loaded for armor slot "+slot.getName());checks++;
   recipes.put(slot,recipe);
  }
  int adults=de.villagerplus.village.Villages.residents(level,ref,96,v->!v.isBaby()).size();
  int reserve=Math.max(1,Math.min(8,(adults+7)/8));
  for(EquipmentSlot target:slots) {
   depot.clearContent();worker.getInventory().clearContent();source.clearContent();
   int depotSlot=0;
   for(EquipmentSlot other:slots)if(other!=target) {
    ItemStack stock=recipes.get(other).output().copy();
    for(int i=0;i<reserve;i++)depot.setItem(depotSlot++,stock.copy());
   }
   LoadedEquipmentRecipe plan=recipes.get(target);
   ItemStack worn=plan.output().copy();
   if(worn.isDamageableItem()) {
    worn.setDamageValue((worn.getMaxDamage()*3+3)/4);
    worker.getInventory().setItem(0,worn.copy());
   }
   EquipmentSlot protectedSlot=target==EquipmentSlot.HEAD?EquipmentSlot.CHEST:EquipmentSlot.HEAD;
   ItemStack enchanted=recipes.get(protectedSlot).output().copy();
   enchanted.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION),1);
   ItemStack enchantedSnapshot=enchanted.copy();
   worker.setItemSlot(protectedSlot,enchanted);
   check(enchanted.isEnchanted()&&EquipmentProduction.armorSlot(enchanted)==protectedSlot,
       "existing equipped armor fixture is enchanted and remains in its original slot");checks++;
   int materialSlot=0;
   for(var ingredient:plan.ingredients().entrySet())source.setItem(materialSlot++,new ItemStack(ingredient.getKey(),ingredient.getValue()));

   BlockPos materialChest=ref.chestPos(level);
   check(materialChest!=null,"public material chest exists for armor recipe "+target);checks++;
   worker.teleportTo(materialChest.getX()+.5,materialChest.getY(),materialChest.getZ()+1.5);
   int fetchCalls=0;
   while(!hasRecipeIngredients(worker.getInventory(),plan.ingredients())&&fetchCalls++<12)EquipmentProduction.work(c);
   check(hasRecipeIngredients(worker.getInventory(),plan.ingredients())&&noRecipeIngredients(source,plan.ingredients()),
      "real ingredients for loaded "+target.getName()+" recipe are picked up from public storage");checks++;
   if(worn.isDamageableItem()) {
    check(ItemStack.matches(worker.getInventory().getItem(0),worn),
       "worn "+target.getName()+" armor remains intact in the worker inventory before crafting");checks++;
   }
   boolean walkingToBench=EquipmentProduction.work(c);
   check(walkingToBench,"armor worker takes the existing route from storage to the crafting table for "+target.getName());checks++;
   worker.teleportTo(bench.getX()+.5,bench.getY(),bench.getZ()+2.5);
   boolean crafted=EquipmentProduction.work(c);
   check(crafted&&JobUtil.count(worker.getInventory(),stack->EquipmentProduction.armorSlot(stack)==target
       &&StockPolicy.isServiceableEquipment(stack))==1
       &&hasUnchangedWornArmor(worker.getInventory(),worn)
       &&ItemStack.matches(worker.getItemBySlot(protectedSlot),enchantedSnapshot)
       &&noRecipeIngredients(worker.getInventory(),plan.ingredients()),
      "loaded recipe crafts one usable "+target.getName()+" piece without consuming worn or enchanted equipment");checks++;
   worker.teleportTo(barrel.getX()+.5,barrel.getY(),barrel.getZ()+2.5);
   boolean delivered=deliverCarriedEquipment(c,barrel);
   check(delivered&&JobUtil.count(worker.getInventory(),stack->EquipmentProduction.armorSlot(stack)==target
       &&StockPolicy.isServiceableEquipment(stack))==0
       &&JobUtil.count(depot,stack->EquipmentProduction.armorSlot(stack)==target
           &&StockPolicy.isServiceableEquipment(stack))==1
       &&hasUnchangedWornArmor(worker.getInventory(),worn)
       &&ItemStack.matches(worker.getItemBySlot(protectedSlot),enchantedSnapshot),
      "usable "+target.getName()+" reaches the toolshed while worn and enchanted gear remains unchanged");checks++;
   worker.setItemSlot(protectedSlot,ItemStack.EMPTY);
  }
  return checks;
 }

 private static LoadedEquipmentRecipe loadedArmorRecipe(ServerLevel level,EquipmentSlot target) {
  return loadedEquipmentRecipe(level,stack->EquipmentProduction.armorSlot(stack)==target);
 }

 private static LoadedEquipmentRecipe loadedSidearmRecipe(ServerLevel level,Item target) {
  return loadedItemRecipe(level,target);
 }

 private static LoadedEquipmentRecipe loadedItemRecipe(ServerLevel level,Item target) {
  return loadedEquipmentRecipe(level,stack->stack.is(target));
 }

 private static LoadedEquipmentRecipe loadedEquipmentRecipe(ServerLevel level,Predicate<ItemStack> target) {
  for(var holder:level.recipeAccess().getRecipes()) {
   if(!(holder.value() instanceof CraftingRecipe recipe))continue;
   ItemStack preview=RecipeCrafting.preview(recipe);
   if(preview==null||!target.test(preview)
       ||!BuiltInRegistries.ITEM.getKey(preview.getItem()).getNamespace().equals("minecraft"))continue;
   var placement=recipe.placementInfo();
   if(placement.isImpossibleToPlace()||placement.ingredients().isEmpty()||placement.slotsToIngredientIndex().isEmpty())continue;
   int[] uses=new int[placement.ingredients().size()];
   for(int ingredient:placement.slotsToIngredientIndex())if(ingredient!=net.minecraft.world.item.crafting.PlacementInfo.EMPTY_SLOT)uses[ingredient]++;
   Map<Item,Integer> sampleItems=new LinkedHashMap<>();
   boolean available=true;
   for(int i=0;i<uses.length;i++) {
    if(uses[i]==0){available=false;break;}
    Item ingredient=placement.ingredients().get(i).items()
        .map(candidate->candidate.value())
        .filter(item->StockPolicy.category(item)!=StockPolicy.Category.TOOLS)
        .findFirst().orElse(null);
    if(ingredient==null){available=false;break;}
    sampleItems.merge(ingredient,uses[i],Integer::sum);
   }
   if(!available||sampleItems.size()>9)continue;
   SimpleContainer sample=new SimpleContainer(9);
   int slot=0;
   for(var item:sampleItems.entrySet())sample.setItem(slot++,new ItemStack(item.getKey(),item.getValue()));
   CraftingInput input=RecipeCrafting.inputFor(recipe,sample,level);
   if(input==null)continue;
   ItemStack output=recipe.assemble(input);
   if(!target.test(output)||!StockPolicy.isServiceableEquipment(output))continue;
   Map<Item,Integer> exactItems=new HashMap<>();
   for(ItemStack ingredient:input.items())if(!ingredient.isEmpty())exactItems.merge(ingredient.getItem(),ingredient.getCount(),Integer::sum);
   return new LoadedEquipmentRecipe(output.copy(),Map.copyOf(exactItems));
  }
  return null;
 }

 private static boolean hasRecipeIngredients(net.minecraft.world.Container inventory,Map<Item,Integer> ingredients) {
  for(var ingredient:ingredients.entrySet())if(JobUtil.count(inventory,stack->stack.is(ingredient.getKey()))<ingredient.getValue())return false;
  return true;
 }

 private static boolean noRecipeIngredients(net.minecraft.world.Container inventory,Map<Item,Integer> ingredients) {
  for(var ingredient:ingredients.entrySet())if(JobUtil.count(inventory,stack->stack.is(ingredient.getKey()))!=0)return false;
  return true;
 }

 private static boolean hasUnchangedWornArmor(net.minecraft.world.Container inventory,ItemStack worn) {
  if(worn.isEmpty()||!worn.isDamageableItem())return true;
  for(int slot=0;slot<inventory.getContainerSize();slot++)if(ItemStack.matches(inventory.getItem(slot),worn))return true;
  return false;
 }

 private static int produceLoadedSidearms(ServerLevel level,de.villagerplus.village.VillageRef ref,Ctx c,
      BarrelBlockEntity depot,net.minecraft.world.Container source,net.minecraft.world.entity.npc.villager.Villager worker,
      BlockPos barrel,BlockPos bench) {
  return produceLoadedItems(level,ref,c,depot,source,worker,barrel,bench,List.of(Items.BOW,Items.CROSSBOW,Items.SHIELD));
 }

 private static int produceLoadedDurableUtilityTools(ServerLevel level,de.villagerplus.village.VillageRef ref,Ctx c,
      BarrelBlockEntity depot,net.minecraft.world.Container source,net.minecraft.world.entity.npc.villager.Villager worker,
      BlockPos barrel,BlockPos bench) {
  return produceLoadedItems(level,ref,c,depot,source,worker,barrel,bench,
      List.of(Items.SHEARS,Items.FISHING_ROD,Items.FLINT_AND_STEEL,Items.BRUSH));
 }

 private static int produceLoadedItems(ServerLevel level,de.villagerplus.village.VillageRef ref,Ctx c,
      BarrelBlockEntity depot,net.minecraft.world.Container source,net.minecraft.world.entity.npc.villager.Villager worker,
      BlockPos barrel,BlockPos bench,List<Item> targets) {
  int checks=0;
  for(Item item:targets) {
   LoadedEquipmentRecipe plan=loadedItemRecipe(level,item);
   if(plan==null) {
    check(!hasLoadedCraftingOutput(level,item),"no loaded Minecraft crafting recipe with safe public ingredients exists for "+BuiltInRegistries.ITEM.getKey(item));checks++;
    continue;
   }
   checks++;
   checks+=produceLoadedItemFromRecipe(level,ref,c,depot,source,worker,barrel,bench,item,plan);
  }
  return checks;
 }

 private static int produceLoadedItemFromRecipe(ServerLevel level,de.villagerplus.village.VillageRef ref,Ctx c,
      BarrelBlockEntity depot,net.minecraft.world.Container source,net.minecraft.world.entity.npc.villager.Villager worker,
      BlockPos barrel,BlockPos bench,Item target,LoadedEquipmentRecipe plan) {
  List<net.minecraft.world.Container> toolshed=Specials.containers(level,ref,Blueprints.TOOLSHED);
  if(toolshed.stream().noneMatch(container->container==depot))
   throw new IllegalStateException("The focused toolshed barrel is not one of the public production depots");
  List<ToolshedContainerSnapshot> originalToolshed=snapshotToolshed(toolshed);
  try {
  fillEveryToolshedContainer(toolshed,depot);
  // Crossbow inputs can also make a shovel. Keep that reserve satisfied so the
  // generic smith priority does not start a different recipe in this focused fixture.
  source.clearContent();worker.getInventory().clearContent();
  ItemStack worn=plan.output().copy();
  if(worn.isDamageableItem()) {
   worn.setDamageValue((worn.getMaxDamage()*3+3)/4);
   worker.getInventory().setItem(0,worn.copy());
  }
  Item protectedItem=target==Items.BOW?Items.CROSSBOW:Items.BOW;
  ItemStack enchantedHand=new ItemStack(protectedItem);
  enchantedHand.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING),1);
  ItemStack enchantedSnapshot=enchantedHand.copy();
  worker.setItemSlot(EquipmentSlot.MAINHAND,enchantedHand);
  int materialSlot=0;
  for(var ingredient:plan.ingredients().entrySet())source.setItem(materialSlot++,new ItemStack(ingredient.getKey(),ingredient.getValue()));
  Map<Item,Integer> sourceIngredientCountsBefore=ingredientCounts(source,plan.ingredients());
  String ingredientStatusBefore=ingredientStatus(source,plan.ingredients());
  boolean ingredientsPresentBefore=hasRecipeIngredients(source,plan.ingredients());
  boolean everyToolshedSlotOccupied=everyToolshedSlotOccupied(toolshed);
  boolean noToolshedOutputRoom=Specials.depotWithRoom(level,ref,Blueprints.TOOLSHED,plan.output())==null;
  boolean fullOutputDepot=everyToolshedSlotOccupied&&noToolshedOutputRoom;
  BlockPos materialChest=ref.chestPos(level);
  check(materialChest!=null,"public material chest exists for "+BuiltInRegistries.ITEM.getKey(target));
  worker.teleportTo(materialChest.getX()+.5,materialChest.getY(),materialChest.getZ()+1.5);
  boolean blockedByFullToolshed=!EquipmentProduction.work(c);
  boolean sourceUnchanged=sourceIngredientCountsBefore.equals(ingredientCounts(source,plan.ingredients()));
  String ingredientStatusAfter=ingredientStatus(source,plan.ingredients());
  boolean ingredientsNotCarried=noRecipeIngredients(worker.getInventory(),plan.ingredients());
  boolean wornUnchanged=hasUnchangedWornArmor(worker.getInventory(),worn);
  boolean handUnchanged=ItemStack.matches(worker.getItemBySlot(EquipmentSlot.MAINHAND),enchantedSnapshot);
  check(fullOutputDepot&&ingredientsPresentBefore&&blockedByFullToolshed&&sourceUnchanged&&ingredientsNotCarried&&wornUnchanged&&handUnchanged,
      "full toolshed blocks "+BuiltInRegistries.ITEM.getKey(target)+" ingredient pickup without changing worn or enchanted gear"
          +" (fullOutputDepot="+fullOutputDepot+"; everySlotOccupied="+everyToolshedSlotOccupied+"; noOutputRoom="+noToolshedOutputRoom
          +"; ingredientsPresentBefore="+ingredientsPresentBefore+"; ingredientsBefore={"+ingredientStatusBefore+"}; ingredientsAfter={"+ingredientStatusAfter+"}"
          +"; blocked="+blockedByFullToolshed+"; sourceUnchanged="+sourceUnchanged+"; carriedClear="+ingredientsNotCarried
          +"; worn="+wornUnchanged+"; hand="+handUnchanged+")");
  depot.setItem(depot.getContainerSize()-1,ItemStack.EMPTY);

  int fetchCalls=0;
  while(!hasRecipeIngredients(worker.getInventory(),plan.ingredients())&&fetchCalls++<12)EquipmentProduction.work(c);
  StringBuilder ingredientStatus=new StringBuilder();
  for(var ingredient:plan.ingredients().entrySet()) {
   if(!ingredientStatus.isEmpty())ingredientStatus.append(", ");
   ingredientStatus.append(BuiltInRegistries.ITEM.getKey(ingredient.getKey())).append(" needed=").append(ingredient.getValue())
       .append(" carried=").append(JobUtil.count(worker.getInventory(),stack->stack.is(ingredient.getKey())))
       .append(" source=").append(JobUtil.count(source,stack->stack.is(ingredient.getKey())));
  }
  check(hasRecipeIngredients(worker.getInventory(),plan.ingredients())&&noRecipeIngredients(source,plan.ingredients())
      &&ItemStack.matches(worker.getItemBySlot(EquipmentSlot.MAINHAND),enchantedSnapshot),
      "loaded "+BuiltInRegistries.ITEM.getKey(target)+" recipe picks exact public ingredients while preserving the equipped item"
          +" ("+ingredientStatus+"; fetchCalls="+fetchCalls+"; cooldown="+c.st().cooldownUntil+"; now="+c.now()+")");
  boolean walkingToBench=EquipmentProduction.work(c);
  check(walkingToBench,"worker uses the existing route to the crafting table for "+BuiltInRegistries.ITEM.getKey(target));
  worker.teleportTo(bench.getX()+.5,bench.getY(),bench.getZ()+2.5);
  boolean crafted=EquipmentProduction.work(c);
  check(crafted&&JobUtil.count(worker.getInventory(),stack->ItemStack.isSameItemSameComponents(stack,plan.output()))==1
      &&JobUtil.count(worker.getInventory(),stack->stack.is(target)&&StockPolicy.isServiceableEquipment(stack))==1
      &&hasUnchangedWornArmor(worker.getInventory(),worn)
      &&ItemStack.matches(worker.getItemBySlot(EquipmentSlot.MAINHAND),enchantedSnapshot)
      &&noRecipeIngredients(worker.getInventory(),plan.ingredients()),
      "the actual loaded recipe creates one usable "+BuiltInRegistries.ITEM.getKey(target)+" without consuming worn or enchanted gear");

  depot.setItem(depot.getContainerSize()-1,new ItemStack(Items.DIAMOND_AXE));
  worker.teleportTo(barrel.getX()+.5,barrel.getY(),barrel.getZ()+2.5);
  check(!EquipmentProduction.work(c)
      &&JobUtil.count(worker.getInventory(),stack->ItemStack.isSameItemSameComponents(stack,plan.output()))==1
      &&JobUtil.count(worker.getInventory(),stack->stack.is(target)&&StockPolicy.isServiceableEquipment(stack))==1
      &&hasUnchangedWornArmor(worker.getInventory(),worn)
      &&ItemStack.matches(worker.getItemBySlot(EquipmentSlot.MAINHAND),enchantedSnapshot),
      "a toolshed filled after crafting retains the "+BuiltInRegistries.ITEM.getKey(target)+" output with the worker");
  depot.setItem(depot.getContainerSize()-1,ItemStack.EMPTY);
  boolean delivered=deliverCarriedEquipment(c,barrel);
  check(delivered&&JobUtil.count(worker.getInventory(),stack->stack.is(target)&&StockPolicy.isServiceableEquipment(stack))==0
      &&JobUtil.count(depot,stack->ItemStack.isSameItemSameComponents(stack,plan.output()))==1
      &&JobUtil.count(depot,stack->stack.is(target)&&StockPolicy.isServiceableEquipment(stack))==1
      &&hasUnchangedWornArmor(worker.getInventory(),worn)
      &&ItemStack.matches(worker.getItemBySlot(EquipmentSlot.MAINHAND),enchantedSnapshot),
      "usable "+BuiltInRegistries.ITEM.getKey(target)+" reaches the toolshed when capacity returns");
  worker.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
  return 7;
  } finally {
   restoreToolshed(originalToolshed);
  }
 }

 private static boolean deliverCarriedEquipment(Ctx c,BlockPos barrel) {
  c.v().teleportTo(barrel.getX()+.5,barrel.getY(),barrel.getZ()+2.5);
  if(!EquipmentProduction.work(c)||c.st().target==null)return false;
  BlockPos stand=c.st().target.immutable();
  c.v().teleportTo(stand.getX()+.5,stand.getY(),stand.getZ()+.5);
  c.st().cooldownUntil=0;
  boolean delivered=EquipmentProduction.work(new Ctx(c.level(),c.v(),c.st(),c.mem(),c.village(),c.now()+1));
  if(delivered)c.st().cooldownUntil=0;
  return delivered;
 }

 private static void fillEveryToolshedContainer(List<net.minecraft.world.Container> toolshed,BarrelBlockEntity primary) {
  for(net.minecraft.world.Container container:toolshed) {
   for(int slot=0;slot<container.getContainerSize();slot++)container.setItem(slot,new ItemStack(Items.DIAMOND_AXE));
   container.setChanged();
  }
  if(primary.getContainerSize()>0) {
   primary.setItem(0,new ItemStack(Items.IRON_SHOVEL));
   primary.setChanged();
  }
 }

 private static List<ToolshedContainerSnapshot> snapshotToolshed(List<net.minecraft.world.Container> toolshed) {
  List<ToolshedContainerSnapshot> snapshots=new ArrayList<>();
  for(net.minecraft.world.Container container:toolshed) {
   List<ItemStack> items=new ArrayList<>();
   for(int slot=0;slot<container.getContainerSize();slot++)items.add(container.getItem(slot).copy());
   snapshots.add(new ToolshedContainerSnapshot(container,List.copyOf(items)));
  }
  return List.copyOf(snapshots);
 }

 private static void restoreToolshed(List<ToolshedContainerSnapshot> snapshots) {
  for(ToolshedContainerSnapshot snapshot:snapshots) {
   net.minecraft.world.Container container=snapshot.container();
   for(int slot=0;slot<Math.min(container.getContainerSize(),snapshot.items().size());slot++)
    container.setItem(slot,snapshot.items().get(slot).copy());
   container.setChanged();
  }
 }

 private static boolean everyToolshedSlotOccupied(List<net.minecraft.world.Container> toolshed) {
  if(toolshed.isEmpty())return false;
  for(net.minecraft.world.Container container:toolshed) {
   if(container.getContainerSize()==0)return false;
   for(int slot=0;slot<container.getContainerSize();slot++)if(container.getItem(slot).isEmpty())return false;
  }
  return true;
 }

 private static Map<Item,Integer> ingredientCounts(net.minecraft.world.Container inventory,Map<Item,Integer> ingredients) {
  Map<Item,Integer> counts=new LinkedHashMap<>();
  for(Item item:ingredients.keySet())counts.put(item,JobUtil.count(inventory,stack->stack.is(item)));
  return counts;
 }

 private static String ingredientStatus(net.minecraft.world.Container inventory,Map<Item,Integer> ingredients) {
  StringBuilder status=new StringBuilder();
  List<Map.Entry<Item,Integer>> ordered=new ArrayList<>(ingredients.entrySet());
  ordered.sort(Comparator.comparing(entry->BuiltInRegistries.ITEM.getKey(entry.getKey()).toString()));
  for(var ingredient:ordered) {
   if(!status.isEmpty())status.append(", ");
   Item item=ingredient.getKey();
   status.append(BuiltInRegistries.ITEM.getKey(item)).append(" needed=").append(ingredient.getValue())
       .append(" source=").append(JobUtil.count(inventory,stack->stack.is(item)))
       .append(" category=").append(StockPolicy.category(item).key())
       .append(" acceptedByStorage=").append(StockPolicy.acceptsMaterialStoreItem(item));
  }
  return status.toString();
 }

 private record ToolshedContainerSnapshot(net.minecraft.world.Container container,List<ItemStack> items) { }

 private static boolean hasLoadedCraftingOutput(ServerLevel level,Item target) {
  for(var holder:level.recipeAccess().getRecipes())if(holder.value() instanceof CraftingRecipe recipe) {
   ItemStack preview=RecipeCrafting.preview(recipe);
   if(preview!=null&&preview.is(target))return true;
  }
  return false;
 }

 private record LoadedEquipmentRecipe(ItemStack output,Map<Item,Integer> ingredients) { }

 private static int replaceWornFamily(ServerLevel level,de.villagerplus.village.VillageRef ref,Ctx c,
      BarrelBlockEntity depot,net.minecraft.world.Container source,net.minecraft.world.entity.npc.villager.Villager worker,
      BlockPos barrel,BlockPos bench,Item wornItem,Item output,int iron,int sticks) {
  depot.clearContent();
  int slot=0;
  for(Item other:otherFamilyStock(output))depot.setItem(slot++,new ItemStack(other));
  ItemStack worn=new ItemStack(wornItem);
  worn.setDamageValue((worn.getMaxDamage()*3+3)/4);
  depot.setItem(slot,worn);
  source.clearContent();source.setItem(0,new ItemStack(Items.IRON_INGOT,iron));source.setItem(1,new ItemStack(Items.STICK,sticks));
  worker.getInventory().clearContent();
  String id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output).toString();
  ref.bellEntity().setAttached(VillagerPlus.STOCK_POLICIES,Map.of(id,1));

  BlockPos materialChest=ref.chestPos(level);
  check(materialChest!=null,"public material chest exists for replacement recipe");
  worker.teleportTo(materialChest.getX()+.5,materialChest.getY(),materialChest.getZ()+1.5);
  EquipmentProduction.work(c);
  EquipmentProduction.work(c);
  check(JobUtil.count(worker.getInventory(),s->s.is(Items.IRON_INGOT))==iron
      &&JobUtil.count(worker.getInventory(),s->s.is(Items.STICK))==sticks
      &&JobUtil.count(source,s->s.is(Items.IRON_INGOT)||s.is(Items.STICK))==0,
      "real recipe ingredients are transferred from public storage for "+id);
  boolean walkingToBench=EquipmentProduction.work(c);
  check(walkingToBench,"replacement worker takes the normal route from storage to the crafting table for "+id);
  worker.teleportTo(bench.getX()+.5,bench.getY(),bench.getZ()+2.5);
  boolean crafted=EquipmentProduction.work(c);
  check(crafted&&JobUtil.count(worker.getInventory(),s->s.is(output))==1
      &&JobUtil.count(worker.getInventory(),s->s.is(Items.IRON_INGOT)||s.is(Items.STICK))==0
      &&depot.getItem(slot).getItem()==wornItem&&depot.getItem(slot).getDamageValue()>=worn.getMaxDamage()*3/4,
      "loaded recipe consumes exact ingredients and crafts a usable "+id+" beside worn "+wornItem);
  boolean delivered=deliverCarriedEquipment(c,barrel);
  check(delivered&&JobUtil.count(worker.getInventory(),s->s.is(output))==0
      &&JobUtil.count(depot,s->s.is(output))==1
      &&JobUtil.count(depot,s->s.is(wornItem)&&s.getDamageValue()>=s.getMaxDamage()*3/4)==1,
      "usable "+id+" is delivered to the toolshed while worn family stock remains distinct");
  ref.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
  return 5;
 }

 private static List<Item> otherFamilyStock(Item output) {
  List<Item> stock=new ArrayList<>();
  if(output!=Items.IRON_PICKAXE)stock.add(Items.DIAMOND_PICKAXE);
  if(output!=Items.IRON_AXE)stock.add(Items.DIAMOND_AXE);
  if(output!=Items.IRON_SHOVEL)stock.add(Items.DIAMOND_SHOVEL);
  if(output!=Items.IRON_HOE)stock.add(Items.DIAMOND_HOE);
  if(output!=Items.IRON_SWORD)stock.add(Items.DIAMOND_SWORD);
  return stock;
 }
}
