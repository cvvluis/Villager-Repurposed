package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;

/** Real inventory/arrival checks in a dedicated remote development fixture. */
public final class RoadMaintenanceChecks {
 private RoadMaintenanceChecks() { }
 public static int run(ServerLevel level,BlockPos at) {
  level.getChunkAt(at);
  int surfaceY=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ())-1;
  Map<BlockPos,Cell> before=captureOuterFixture(level,at,surfaceY);
  int checks;
  try { checks=runFixture(level,at); }
  finally { restoreBridgeArea(level,before); }
  List<String> differences=differences(level,before,5);
  check(differences.isEmpty(),"outer road-maintenance fixture restores terrain, bells, depot inventories and route attachments"
   +(differences.isEmpty()?"":"; first differences: "+String.join(" || ",differences)));
  return checks+1;
 }
 private static int runFixture(ServerLevel level,BlockPos at) {
  level.getChunkAt(at);
  int groundY=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ())-1;
  at=new BlockPos(at.getX(),groundY+1,at.getZ());
  Checks090.fixtureForEquipment(level, at.east(80));
  var ref=Checks090.fixtureForEquipment(level,at);
  var box=ref.chest(level);box.clearContent();
  groundY=ref.bell().getY()-1;
  BlockPos other=at.east(80);
  var peerRef=Checks090.fixtureForEquipment(level,other);
  int damagedY=groundY;
  BlockPos damaged=new BlockPos(at.getX()+20,damagedY,at.getZ());
  String key=Network.dim(level)+other.asLong();
  String peerKey=Network.dim(level)+ref.bell().asLong();
  Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Road check",30,true,8,0)),Map.of(),0,0));
  Network.save(peerRef.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),ref.bell(),"Road check peer",30,true,8,0)),Map.of(),0,0));
  Map<String,Long> roads=new java.util.HashMap<>();
  roads.put(key,24L);roads.put(key+":start",1L);
  ref.bellEntity().setAttached(VillagerPlus.ROADS,roads);
  Map<String,Long> peerRoads=new java.util.HashMap<>();
  peerRoads.put(peerKey,24L);peerRoads.put(peerKey+":start",0L);
  peerRef.bellEntity().setAttached(VillagerPlus.ROADS,peerRoads);
  for(int x=0;x<24;x++)for(int side=-1;side<=1;side++) {
   BlockPos p=new BlockPos(at.getX()+x,groundY,at.getZ()+side);level.getChunkAt(p);
   level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(p,(side==0?Blocks.GRAVEL:Blocks.COBBLESTONE).defaultBlockState(),3);
   if(x>=6) {level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(2),Blocks.AIR.defaultBlockState(),3);}
  }
  BlockPos lampBase=new BlockPos(at.getX()+16,groundY+1,at.getZ()+1);
  level.setBlock(lampBase,Blocks.SPRUCE_LOG.defaultBlockState(),3);
  level.setBlock(lampBase.above(),Blocks.SPRUCE_LOG.defaultBlockState(),3);
  level.setBlock(lampBase.above(2),Blocks.LANTERN.defaultBlockState(),3);
  roads=new java.util.HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  peerRoads=new java.util.HashMap<>(peerRef.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  Roads.nextRepair(level,ref);
  roads=new java.util.HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  level.setBlock(damaged,Blocks.GRASS_BLOCK.defaultBlockState(),3);
  var v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);
  v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  var st=new JobState();var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);
  long now=level.getGameTime()+1000;int checks=0;
  try {
   Ctx c=new Ctx(level,v,st,mem,ref,now);
   check(!RoadBuilderJob.maintain(c)&&level.getBlockState(damaged).is(Blocks.GRASS_BLOCK),"no stock leaves road unchanged");checks++;
   ItemStack gravel=new ItemStack(Items.GRAVEL,8);
   gravel.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Straßenkies"));
   box.setItem(0,gravel);
   st=new JobState();c=new Ctx(level,v,st,mem,ref,now+1);st.roadScanAt=0;
   BlockPos depot=ref.chestPos(level);v.teleportTo(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
   check(RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==7&&JobUtil.count(v.getInventory(),s->s.is(Items.GRAVEL))==1,"arrival at communal depot transfers exactly one paving block");checks++;
   check(ItemStack.isSameItemSameComponents(v.getInventory().getItem(0),box.getItem(0)),"material pickup preserves stack components");checks++;
   RoadBuilderJob.maintain(c);
   check(level.getBlockState(damaged).is(Blocks.GRASS_BLOCK)&&JobUtil.count(v.getInventory(),s->s.is(Items.GRAVEL))==1,"walking does not remotely repair or consume cargo");checks++;
   v.teleportTo(damaged.getX()+.5,damaged.getY()+1,damaged.getZ()+1.5);
   check(RoadBuilderJob.maintain(c)&&level.getBlockState(damaged).is(Blocks.GRAVEL)&&v.getInventory().isEmpty()&&box.getItem(0).getCount()==7,"worksite arrival repairs from carried stock exactly once");checks++;
   st.roadScanAt=0;
   check(!RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==7,"intact road does not consume more stock");checks++;
   level.setBlock(damaged,Blocks.FARMLAND.defaultBlockState(),3);st.roadScanAt=0;
   check(!RoadBuilderJob.maintain(c)&&level.getBlockState(damaged).is(Blocks.FARMLAND),"farmland is protected even inside old route");checks++;
   level.setBlock(damaged,Blocks.GRASS_BLOCK.defaultBlockState(),3);st.roadScanAt=0;
   level.getBlockEntity(depot).setAttached(VillagerPlus.OWNER,v.getUUID());
   check(!RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==7,"private depot supplies no road materials");checks++;
   level.getBlockEntity(depot).removeAttached(VillagerPlus.OWNER);
   level.setBlock(damaged.below(),Blocks.AIR.defaultBlockState(),3);st.roadScanAt=0;
   check(!RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==7&&level.getBlockState(damaged).is(Blocks.GRASS_BLOCK),"unsupported ground cannot receive falling gravel");checks++;
   checks+=gravelLoopChecks(level,at,groundY,ref,peerRef,damaged,box,v,now+5);
   checks+=bridgeRepairChecks(level,at,groundY,ref,other,key,box,v,mem,now+10);
   return checks;
  } finally {v.discard();var be=level.getBlockEntity(ref.chestPos(level));if(be!=null)be.removeAttached(VillagerPlus.OWNER);}
 }


 /**
  * J06/J08 loop: road damage creates gravel demand, a mason quarries real natural gravel into the public depot,
  * the road builder withdraws it and places it. Every removed gravel block must be accounted for as stock,
  * cargo, flint (the vanilla 10% drop) or placed road surface; nothing is created or lost.
  */
 private static int gravelLoopChecks(ServerLevel level,BlockPos at,int groundY,VillageRef ref,VillageRef peerRef,BlockPos damaged,
                                     Container box,net.minecraft.world.entity.npc.villager.Villager builder,long now) {
  int checks=0;
  level.setBlock(damaged.below(),Blocks.STONE.defaultBlockState(),3);
  level.setBlock(damaged,Blocks.GRASS_BLOCK.defaultBlockState(),3);
  box.clearContent();
  builder.getInventory().clearContent();
  BlockPos[] sources=new BlockPos[6];
  for(int i=0;i<sources.length;i++) {
   BlockPos src=new BlockPos(at.getX()+9+i,groundY,at.getZ()+3);sources[i]=src;
   level.getChunkAt(src);
   level.setBlock(src.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(src,Blocks.GRAVEL.defaultBlockState(),3);
   level.setBlock(src.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(src.above(2),Blocks.AIR.defaultBlockState(),3);
  }
  var mason=EntityTypes.VILLAGER.spawn(level,sources[0].above().north(),EntitySpawnReason.COMMAND);
  mason.setNoAi(true);mason.setInvulnerable(true);mason.addTag("vp090_fixture");
  try {
   mason.getInventory().addItem(new ItemStack(Items.IRON_SHOVEL));
   JobState mst=new JobState();var mmem=mason.getAttachedOrCreate(VillagerPlus.MEMORY);
   BlockPos depot=ref.chestPos(level);
   for(int i=0;i<400&&(gravelOf(box)==0||gravelOf(mason.getInventory())>0);i++) {
    Ctx work=new Ctx(level,mason,mst,mmem,ref,now+i*6L);
    if(JobUtil.count(mason.getInventory(),s->s.is(Items.GRAVEL))>0) {
     BlockPos stand=JobUtil.sharedStandSpot(work,depot,4.0);
     if(stand!=null)mason.teleportTo(stand.getX()+.5,stand.getY(),stand.getZ()+.5);
    }
    new MasonJob().primary(work);
   }
   int removed=0;for(BlockPos src:sources)if(!level.getBlockState(src).is(Blocks.GRAVEL))removed++;
   int flint=flintOf(mason.getInventory())+flintOf(box);
   check(removed>=1&&gravelOf(box)+gravelOf(mason.getInventory())+flint==removed,
    "J06 ledger: every quarried gravel block is stock or flint, none lost or duplicated");checks++;
   check(removed<=3&&gravelOf(box)<=GravelQuarry.roadGravelTarget(false)+0,
    "J06 demand: a single damaged road cell requests one gravel block, not the construction batch");checks++;
   check(gravelOf(box)==1&&level.getBlockState(damaged).is(Blocks.GRASS_BLOCK),
    "J06 supply: gravel reaches the public depot while the road cell is still unrepaired");checks++;
   JobState bst=new JobState();bst.roadScanAt=0;Ctx bc=new Ctx(level,builder,bst,builder.getAttachedOrCreate(VillagerPlus.MEMORY),ref,now+4000);
   builder.teleportTo(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
   RoadBuilderJob.maintain(bc);
   builder.teleportTo(damaged.getX()+.5,damaged.getY()+1,damaged.getZ()+1.5);
   RoadBuilderJob.maintain(bc);
   check(level.getBlockState(damaged).is(Blocks.GRAVEL)&&gravelOf(box)==0&&gravelOf(builder.getInventory())==0,
    "J08 consume: the builder withdraws the quarried gravel and places it as road surface");checks++;
   int placed=level.getBlockState(damaged).is(Blocks.GRAVEL)?1:0;
   check(removed==placed+gravelOf(box)+gravelOf(builder.getInventory())+gravelOf(mason.getInventory())+flint,
    "J06->J08 balance: quarried blocks == placed + stock + cargo + flint");checks++;
   check(GravelQuarry.roadGravelTarget(true)==GravelQuarry.ROAD_CONSTRUCTION_BATCH&&GravelQuarry.roadGravelTarget(false)==1,
    "road construction asks for a bounded gravel batch, a repair for one block");checks++;

   // Exercise the separate live construction demand: unlike the one-block repair above, a new gravel lane needs eight.
   box.clearContent();builder.getInventory().clearContent();mason.getInventory().clearContent();
   mason.getInventory().setItem(0,new ItemStack(Items.IRON_SHOVEL));
   for(BlockPos src:sources)level.setBlock(src,Blocks.AIR.defaultBlockState(),3);
   BlockPos constructionTarget=at.offset(30,0,0);
   level.getChunkAt(constructionTarget);
   level.setBlock(constructionTarget.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(constructionTarget,Blocks.GRASS_BLOCK.defaultBlockState(),3);
   level.setBlock(constructionTarget.above(),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(constructionTarget.above(2),Blocks.AIR.defaultBlockState(),3);
   Roads.Repair constructionTask=new Roads.Repair(constructionTarget,Blocks.GRAVEL.defaultBlockState());
   check(Roads.repairable(level,constructionTask),"the gravel construction lane is a valid live paving task");checks++;
   BlockPos[] constructionSources=new BlockPos[20];
   for(int i=0;i<constructionSources.length;i++) {
    int x=at.getX()+6+i,z=at.getZ()+3;
    level.getChunkAt(new BlockPos(x,groundY,z));
    int sourceY=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
    BlockPos src=new BlockPos(x,sourceY,z);constructionSources[i]=src;
    level.setBlock(src.below(),Blocks.STONE.defaultBlockState(),3);
    level.setBlock(src,Blocks.GRAVEL.defaultBlockState(),3);
    level.setBlock(src.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(src.above(2),Blocks.AIR.defaultBlockState(),3);
   }
   int validConstructionSources=0;
   for(BlockPos src:constructionSources)if(GravelQuarry.naturalSource(level,src))validConstructionSources++;
   check(validConstructionSources==constructionSources.length,
    "construction quarry fixture supplies exposed, locally height-matched sources [valid="+validConstructionSources+"/"+constructionSources.length+"]");checks++;
   JobState constructionMasonState=new JobState();
   constructionMasonState.roadRepair=constructionTask;constructionMasonState.roadBuilding=true;
   int publicGravelBeforeConstruction=StockPolicy.communalCount(level,ref,Items.GRAVEL);
   int shovelBeforeConstruction=JobUtil.count(mason.getInventory(),s->s.is(Items.IRON_SHOVEL));
   boolean firstQuarryWork=false;
   String firstQuarryActivity="";
   BlockPos firstQuarryTarget=null,firstMasonPosition=null;
   check(constructionTask.removal()==null&&constructionTask.paving().is(Blocks.GRAVEL)
    &&constructionMasonState.roadBuilding&&publicGravelBeforeConstruction==0&&shovelBeforeConstruction==1,
    "construction demand fixture is initialized [removal="+constructionTask.removal()+", paving="
     +constructionTask.paving().getBlock()+", roadBuilding="+constructionMasonState.roadBuilding
     +", publicGravel="+publicGravelBeforeConstruction+", ironShovel="+shovelBeforeConstruction+"]");checks++;
   mason.teleportTo(constructionSources[0].getX()+.5,constructionSources[0].getY()+1,constructionSources[0].getZ()+.5);
   int constructionStart=0;
   while(constructionStart<2400
    &&(gravelOf(box)<GravelQuarry.ROAD_CONSTRUCTION_BATCH||gravelOf(mason.getInventory())>0)) {
    Ctx work=new Ctx(level,mason,constructionMasonState,mmem,ref,now+5000+constructionStart*6L);
    if(gravelOf(mason.getInventory())>0) {
     BlockPos stand=JobUtil.sharedStandSpot(work,depot,4.0);
     if(stand!=null)mason.teleportTo(stand.getX()+.5,stand.getY(),stand.getZ()+.5);
    } else {
     for(BlockPos src:constructionSources)if(GravelQuarry.naturalSource(level,src)) {
      constructionMasonState.target=src;
      mason.teleportTo(src.getX()+.5,src.getY()+1,src.getZ()+.5);break;
     }
    }
    boolean quarryWorked=GravelQuarry.work(work);
    if(constructionStart==0) {
     firstQuarryWork=quarryWorked;firstQuarryActivity=constructionMasonState.activity;
     firstQuarryTarget=constructionMasonState.target;firstMasonPosition=mason.blockPosition();
    }
    constructionStart++;
   }
   int constructionRemoved=0;
   for(BlockPos src:constructionSources)if(!level.getBlockState(src).is(Blocks.GRAVEL))constructionRemoved++;
   int constructionFlint=flintOf(mason.getInventory())+flintOf(box);
   check(constructionStart<2400&&gravelOf(box)==GravelQuarry.ROAD_CONSTRUCTION_BATCH
    &&gravelOf(mason.getInventory())==0&&constructionRemoved>=GravelQuarry.ROAD_CONSTRUCTION_BATCH
    &&constructionRemoved==gravelOf(box)+gravelOf(mason.getInventory())+constructionFlint,
    "J06 construction demand: real quarry drops fill the eight-gravel public batch with flint accounted"
     +" [ticks="+constructionStart+", stock="+gravelOf(box)+", carried="+gravelOf(mason.getInventory())
     +", removed="+constructionRemoved+", flint="+constructionFlint+", validSources="+validConstructionSources
     +", publicBefore="+publicGravelBeforeConstruction+", ironShovel="+shovelBeforeConstruction
     +", task="+constructionTask.action()+"/"+constructionTask.removal()+"/"+constructionTask.paving().getBlock()
     +", roadBuilding="+constructionMasonState.roadBuilding+", firstWork="+firstQuarryWork
     +", firstActivity="+firstQuarryActivity+", firstTarget="+firstQuarryTarget+", firstPosition="+firstMasonPosition+"]");checks++;

   JobState constructionBuilderState=new JobState();
   constructionBuilderState.roadRepair=constructionTask;constructionBuilderState.roadBuilding=true;
   builder.teleportTo(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
   Ctx constructionBuilder=new Ctx(level,builder,constructionBuilderState,
    builder.getAttachedOrCreate(VillagerPlus.MEMORY),ref,now+20000);
   check(RoadBuilderJob.construct(constructionBuilder)&&gravelOf(box)==7
    &&gravelOf(builder.getInventory())==1,
    "the active road builder collects one physical block from the funded construction batch");checks++;
   builder.teleportTo(constructionTarget.getX()+.5,constructionTarget.getY()+1,constructionTarget.getZ()+.5);
   constructionBuilder=new Ctx(level,builder,constructionBuilderState,constructionBuilder.mem(),ref,now+20001);
   Map<String,Long> savedConstructionRoads=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
   Map<String,Long> savedPeerConstructionRoads=new HashMap<>(peerRef.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
   boolean constructionPlaced;
   try { constructionPlaced=RoadBuilderJob.construct(constructionBuilder); }
   finally {
    ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(savedConstructionRoads));ref.dirty();
    peerRef.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(savedPeerConstructionRoads));peerRef.dirty();
   }
   check(constructionPlaced&&level.getBlockState(constructionTarget).is(Blocks.GRAVEL)
    &&gravelOf(box)==7&&gravelOf(builder.getInventory())==0,
    "the road builder places the quarried center lane from its real cargo");checks++;
   check(constructionRemoved==1+gravelOf(box)+gravelOf(builder.getInventory())
    +gravelOf(mason.getInventory())+constructionFlint,
    "J06->J08 construction balance: quarried blocks equal placed gravel, public stock, cargo, and flint");checks++;
  } finally {
   mason.discard();
   box.clearContent();builder.getInventory().clearContent();
   box.setItem(0,new ItemStack(Items.GRAVEL,7));
   for(BlockPos src:sources){level.setBlock(src,Blocks.AIR.defaultBlockState(),3);level.setBlock(src.below(),Blocks.STONE.defaultBlockState(),3);}
   level.setBlock(damaged,Blocks.GRASS_BLOCK.defaultBlockState(),3);level.setBlock(damaged.below(),Blocks.AIR.defaultBlockState(),3);
  }
  return checks;
 }
 private static int gravelOf(Container c){return JobUtil.count(c,s->s.is(Items.GRAVEL));}
 private static int flintOf(Container c){return JobUtil.count(c,s->s.is(Items.FLINT));}

 private static int bridgeRepairChecks(ServerLevel level,BlockPos at,int groundY,VillageRef ref,BlockPos other,String key,
                                       Container box,net.minecraft.world.entity.npc.villager.Villager villager,
                                       VillagerMemory memory,long now) {
  Map<String,Long> beforeRoads=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  List<ZonePolicy.Zone> beforeOwnerZones=List.copyOf(ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES,List.of()));
  var peerEntity=level.getBlockEntity(other);
  var peerData=peerEntity==null?null:peerEntity.getAttached(VillagerPlus.VILLAGE);
  VillageRef peerRef=peerData==null?null:new VillageRef(other,peerData,peerEntity);
  List<ZonePolicy.Zone> beforePeerZones=peerRef==null?List.of():List.copyOf(
   peerRef.bellEntity().getAttachedOrElse(VillagerPlus.ZONES,List.of()));
  int checks=0;
  ItemStack[] beforeDepot=contents(box),beforeInventory=contents(villager.getInventory());
  BlockPos deck=new BlockPos(at.getX()+36,groundY,at.getZ());
  BlockPos followupDeck=new BlockPos(at.getX()+37,groundY,at.getZ());
  BlockPos pierDeck=new BlockPos(at.getX()+38,groundY,at.getZ());
  Map<BlockPos,Cell> beforeWorld=captureBridgeArea(level,at,deck,pierDeck,groundY);
  try {
   for(int x=24;x<40;x++)for(int side=-1;side<=1;side++) {
    BlockPos p=new BlockPos(at.getX()+x,groundY,at.getZ()+side);level.getChunkAt(p);
    level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
    level.setBlock(p,(side==0?Blocks.GRAVEL:Blocks.COBBLESTONE).defaultBlockState(),3);
    level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(2),Blocks.AIR.defaultBlockState(),3);
   }
   for(int side:new int[]{-2,2}) {
    BlockPos farLampBase=new BlockPos(at.getX()+36,groundY+1,at.getZ()+side);
    level.setBlock(farLampBase,Blocks.SPRUCE_LOG.defaultBlockState(),3);
    level.setBlock(farLampBase.above(),Blocks.SPRUCE_LOG.defaultBlockState(),3);
    level.setBlock(farLampBase.above(2),Blocks.LANTERN.defaultBlockState(),3);
   }
   Map<String,Long> roads=new HashMap<>(beforeRoads);
   roads.keySet().removeIf(name->name.startsWith(key+":routePoint:")||name.equals(key+":routeGeometry")
    ||name.equals(key+":routeCount")||name.equals(key+":routeFrom")||name.equals(key+":routeTo"));
   BlockPos low=ref.bell().asLong()<=other.asLong()?ref.bell():other;
   BlockPos high=ref.bell().asLong()<=other.asLong()?other:ref.bell();
   roads.put(key,40L);roads.put(key+":start",1L);roads.put("repair:"+key,0L);
   roads.put(key+":routeGeometry",2L);roads.put(key+":routeCount",0L);
   roads.put(key+":routeFrom",low.asLong());roads.put(key+":routeTo",high.asLong());
   roads.put(key+":bridge:36:0",(long)deck.getY());
   roads.put(key+":bridge:37:0",(long)followupDeck.getY());
   roads.put(key+":bridge:38:0",(long)pierDeck.getY());
   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(roads));ref.dirty();

   level.setBlock(deck,Blocks.WATER.defaultBlockState(),3);
   level.setBlock(deck.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(followupDeck,Blocks.WATER.defaultBlockState(),3);
   level.setBlock(followupDeck.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(pierDeck,Blocks.SPRUCE_PLANKS.defaultBlockState(),3);
   for(int depth=1;depth<=3;depth++)level.setBlock(pierDeck.below(depth),Blocks.SPRUCE_LOG.defaultBlockState(),3);
   BlockPos missingPier=pierDeck.below(4);
   level.setBlock(missingPier,Blocks.WATER.defaultBlockState(),3);
   level.setBlock(missingPier.below(),Blocks.STONE.defaultBlockState(),3);
   for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
    if(dx==0&&dz==0)continue;
    level.setBlock(missingPier.offset(dx,0,dz),Blocks.STONE.defaultBlockState(),3);
   }

   // Build one valid plank target and one valid support-log target, then add protected zones
   // after planning to verify both the scan and the final placement-time recheck.
   BlockPos protectedLogDeck=new BlockPos(at.getX()+39,groundY+4,at.getZ());
   level.setBlock(protectedLogDeck,Blocks.WATER.defaultBlockState(),3);
   for(int depth=1;depth<=3;depth++)level.setBlock(protectedLogDeck.below(depth),Blocks.WATER.defaultBlockState(),3);
   level.setBlock(protectedLogDeck.below(4),Blocks.GRAVEL.defaultBlockState(),3);
   level.setBlock(protectedLogDeck.above(),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(protectedLogDeck.above(2),Blocks.AIR.defaultBlockState(),3);
   RoadBridge.Plan selectedPlank=RoadBridge.plan(level,deck,ref.bell(),other);
   RoadBridge.Plan selectedLog=RoadBridge.plan(level,protectedLogDeck,ref.bell(),other);
   check(selectedPlank!=null&&selectedPlank.next()!=null&&selectedPlank.next().paving().is(Blocks.SPRUCE_PLANKS),
    "unprotected bridge scan selects the deck plank before protection changes");
   check(selectedLog!=null&&selectedLog.next()!=null&&selectedLog.next().ground().equals(protectedLogDeck.below(3))
     &&selectedLog.next().paving().is(Blocks.SPRUCE_LOG),
    "unprotected bridge scan selects the lowest missing support log before protection changes");
   checks+=2;
   check(peerRef!=null,"peer bell is loaded for protected bridge placement validation");checks++;
   if(selectedPlank==null||selectedLog==null||selectedPlank.next()==null||selectedLog.next()==null)
    throw new IllegalStateException("Road maintenance: bridge protection fixture could not select both placements");

   var ownerZones=new java.util.ArrayList<>(beforeOwnerZones);
   ownerZones.add(new ZonePolicy.Zone("road-bridge-owner-protection","protected",deck.getX(),deck.getZ(),deck.getX(),deck.getZ()));
   ref.bellEntity().setAttached(VillagerPlus.ZONES,List.copyOf(ownerZones));ref.dirty();
   if(peerRef!=null) {
    var peerZones=new java.util.ArrayList<>(beforePeerZones);
    peerZones.add(new ZonePolicy.Zone("road-bridge-peer-protection","protected",protectedLogDeck.getX(),
     protectedLogDeck.getZ(),protectedLogDeck.getX(),protectedLogDeck.getZ()));
    peerRef.bellEntity().setAttached(VillagerPlus.ZONES,List.copyOf(peerZones));peerRef.dirty();
   }
   check(RoadBridge.plan(level,deck,ref.bell(),other)==null,
    "bridge scan rejects a deck plank inside the owner's protected village zone");
   check(RoadBridge.plan(level,protectedLogDeck,ref.bell(),other)==null,
    "bridge scan rejects a support log inside the peer's protected village zone");
   check(!Roads.repairable(level,selectedPlank.next())&&!Roads.repairable(level,selectedLog.next()),
    "queued bridge tasks revalidate owner and peer protection before placement");
   villager.getInventory().clearContent();villager.getInventory().setItem(0,new ItemStack(Items.SPRUCE_PLANKS,1));
   villager.teleportTo(selectedPlank.next().worksite().getX()+.5,selectedPlank.next().worksite().getY(),
    selectedPlank.next().worksite().getZ()+.5);
   Ctx protectedContext=new Ctx(level,villager,new JobState(),memory,ref,now);
   check(!RoadBuilderJob.repair(protectedContext,selectedPlank.next())
     &&level.getBlockState(deck).is(Blocks.WATER)
     &&JobUtil.count(villager.getInventory(),stack->stack.is(Items.SPRUCE_PLANKS))==1,
    "a newly protected deck target stays water and keeps its carried plank");
   villager.getInventory().clearContent();villager.getInventory().setItem(0,new ItemStack(Items.SPRUCE_LOG,1));
   villager.teleportTo(selectedLog.next().worksite().getX()+.5,selectedLog.next().worksite().getY(),
    selectedLog.next().worksite().getZ()+.5);
   check(!RoadBuilderJob.repair(protectedContext,selectedLog.next())
     &&level.getBlockState(selectedLog.next().ground()).is(Blocks.WATER)
     &&JobUtil.count(villager.getInventory(),stack->stack.is(Items.SPRUCE_LOG))==1,
    "a newly protected support target stays water and keeps its carried log");
   checks+=5;
   villager.getInventory().clearContent();
   ref.bellEntity().setAttached(VillagerPlus.ZONES,beforeOwnerZones);ref.dirty();
   if(peerRef!=null){peerRef.bellEntity().setAttached(VillagerPlus.ZONES,beforePeerZones);peerRef.dirty();}

   box.clearContent();
   box.setItem(0,new ItemStack(Items.SPRUCE_PLANKS,2));
   checks+=repairRecordedBridge(level,ref,box,villager,memory,key,deck,Blocks.SPRUCE_PLANKS,Items.SPRUCE_PLANKS,now,followupDeck);
   // The previous check proves the fallback selects cursor 37 next. Finish that
   // recorded deck repair before asking it to skip ahead to the missing pier at 38.
   // The rescan above may already have carried the second plank to the villager;
   // this helper clears carried inventory, so replenish the communal fixture stock.
   box.clearContent();box.setItem(0,new ItemStack(Items.SPRUCE_PLANKS,2));
   checks+=repairRecordedBridge(level,ref,box,villager,memory,key,followupDeck,
    Blocks.SPRUCE_PLANKS,Items.SPRUCE_PLANKS,now+1,null);
   box.clearContent();
   box.setItem(0,new ItemStack(Items.SPRUCE_LOG,2));
   checks+=repairRecordedBridge(level,ref,box,villager,memory,key,missingPier,Blocks.SPRUCE_LOG,Items.SPRUCE_LOG,now+2,null);
   return checks;
  } finally {
   restoreBridgeArea(level,beforeWorld);
   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(beforeRoads));ref.dirty();
   ref.bellEntity().setAttached(VillagerPlus.ZONES,beforeOwnerZones);ref.dirty();
   if(peerRef!=null){peerRef.bellEntity().setAttached(VillagerPlus.ZONES,beforePeerZones);peerRef.dirty();}
   restore(box,beforeDepot);restore(villager.getInventory(),beforeInventory);
  }
 }

 private static int repairRecordedBridge(ServerLevel level,VillageRef ref,Container depot,
                                         net.minecraft.world.entity.npc.villager.Villager villager,VillagerMemory memory,
                                         String key,BlockPos target,net.minecraft.world.level.block.Block expectedBlock,
                                         net.minecraft.world.item.Item expectedItem,long now,BlockPos followupTarget) {
  Map<String,Long> roads=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  roads.put("repair:"+key,0L);ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(roads));ref.dirty();
  Roads.Repair ordinary=Roads.nextRepair(level,ref);
  check(ordinary==null,"ordinary 96-lane scan has no surface task at the isolated bridge cursor");
  Roads.Repair bridgeOnly=RoadMaintenance.nextBridgeRepair(level,ref);
  check(bridgeOnly!=null&&bridgeOnly.bridge()&&bridgeOnly.ground().equals(target)
   &&bridgeOnly.paving().is(expectedBlock),"dedicated bridge fallback selects the missing deck/pier block");
  roads=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  roads.put("repair:"+key,0L);ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(roads));ref.dirty();
  JobState state=new JobState();state.roadScanAt=0;
  Ctx context=new Ctx(level,villager,state,memory,ref,now);
  BlockPos chest=ref.chestPos(level);
  villager.getInventory().clearContent();
  villager.teleportTo(chest.getX()+.5,chest.getY(),chest.getZ()+1.5);
  check(RoadBuilderJob.maintain(context)&&state.roadRepair!=null&&state.roadRepair.bridge()
   &&state.roadRepair.ground().equals(target)&&state.roadRepair.paving().is(expectedBlock),
   "active road job discovers the recorded damaged bridge block");
  check(JobUtil.count(villager.getInventory(),stack->stack.is(expectedItem))==1
   &&JobUtil.count(depot,stack->stack.is(expectedItem))==1,"bridge repair carries exactly one paid block from the communal depot");
  Roads.Repair task=state.roadRepair;
  RoadBuilderJob.maintain(context);
  villager.teleportTo(task.worksite().getX()+.5,task.worksite().getY(),task.worksite().getZ()+.5);
  check(RoadBuilderJob.maintain(context)&&level.getBlockState(target).is(expectedBlock)
   &&JobUtil.count(villager.getInventory(),stack->stack.is(expectedItem))==0
   &&JobUtil.count(depot,stack->stack.is(expectedItem))==1,
   "arriving at the bridge worksite restores its missing deck/pier block and consumes the carried item");
  int checks=5;
  if(followupTarget!=null) {
   check(RoadBuilderJob.maintain(context)&&state.roadRepair!=null&&state.roadRepair.bridge()
    &&state.roadRepair.ground().equals(followupTarget),
    "successful bridge repair immediately rescans and selects the next damaged span block");
   checks++;
  }
  return checks;
 }

 private record Cell(BlockState state,CompoundTag data) { }
 private static Map<BlockPos,Cell> captureOuterFixture(ServerLevel level,BlockPos at,int surfaceY) {
  Map<BlockPos,Cell> before=new LinkedHashMap<>();
  captureBox(level,before,at.getX()-1,at.getX()+43,surfaceY-8,surfaceY+16,at.getZ()-3,at.getZ()+7);
  captureBox(level,before,at.getX()+79,at.getX()+84,surfaceY-2,surfaceY+4,at.getZ()-1,at.getZ()+1);
  return before;
 }
 private static Map<BlockPos,Cell> captureBridgeArea(ServerLevel level,BlockPos at,BlockPos first,BlockPos second,int y) {
  Map<BlockPos,Cell> before=new LinkedHashMap<>();
  captureBox(level,before,at.getX()+24,second.getX()+3,y-6,y+7,at.getZ()-3,at.getZ()+3);
  return before;
 }
 private static void captureBox(ServerLevel level,Map<BlockPos,Cell> before,int minX,int maxX,int minY,int maxY,int minZ,int maxZ) {
  for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++) {
   BlockPos pos=new BlockPos(x,y,z);if(before.containsKey(pos))continue;level.getChunkAt(pos);BlockEntity be=level.getBlockEntity(pos);
   before.put(pos,new Cell(level.getBlockState(pos),be==null?null:be.saveWithFullMetadata(level.registryAccess())));
  }
 }
 private static void restoreBridgeArea(ServerLevel level,Map<BlockPos,Cell> before) {
  for(BlockPos pos:before.keySet())level.removeBlockEntity(pos);
  for(BlockPos pos:before.keySet())level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
  for(var entry:before.entrySet())level.setBlock(entry.getKey(),entry.getValue().state(),3);
  for(var entry:before.entrySet())if(entry.getValue().data()!=null) {
   BlockEntity be=BlockEntity.loadStatic(entry.getKey(),entry.getValue().state(),entry.getValue().data().copy(),level.registryAccess());
   if(be!=null)level.setBlockEntity(be);
  }
 }
 private static List<String> differences(ServerLevel level,Map<BlockPos,Cell> before,int limit) {
  List<String> differences=new java.util.ArrayList<>();
  for(var entry:before.entrySet()) {
   BlockEntity be=level.getBlockEntity(entry.getKey());
   Cell current=new Cell(level.getBlockState(entry.getKey()),be==null?null:be.saveWithFullMetadata(level.registryAccess()));
   if(!entry.getValue().equals(current)) {
    Cell original=entry.getValue();
    differences.add(entry.getKey().toShortString()+" state="+original.state()+" -> "+current.state()
     +"; BE="+shortTag(original.data())+" -> "+shortTag(current.data()));
    if(differences.size()>=limit)break;
   }
  }
  return differences;
 }
 private static String shortTag(CompoundTag tag) {
  if(tag==null)return "<none>";
  String value=tag.toString();
  return value.length()<=500?value:value.substring(0,500)+"…";
 }
 private static ItemStack[] contents(Container container) {
  ItemStack[] copy=new ItemStack[container.getContainerSize()];
  for(int i=0;i<copy.length;i++)copy[i]=container.getItem(i).copy();
  return copy;
 }
 private static void restore(Container container,ItemStack[] contents) {
  for(int i=0;i<Math.min(container.getContainerSize(),contents.length);i++)container.setItem(i,contents[i].copy());
  container.setChanged();
 }
 private static void check(boolean value,String message) {if(!value)throw new IllegalStateException("Road maintenance: "+message);}
}
