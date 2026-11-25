package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.*;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Paid bridge work with explicit arrival; dedicated remote fixture is retained for a real restart check. */
public final class RoadBridgeChecks {
 private RoadBridgeChecks() { }
 public static int run(ServerLevel level,BlockPos at) {
  var ref=Checks090.fixtureForEquipment(level,at);
  // The fixture depot lies inside the road corridor built below, so the depot moves beside it (as in RoadConstructionChecks).
  BlockPos fixtureDepot=ref.chestPos(level);
  level.removeBlockEntity(fixtureDepot);level.setBlock(fixtureDepot,Blocks.AIR.defaultBlockState(),3);
  BlockPos isolatedDepot=at.south(8);level.removeBlockEntity(isolatedDepot);
  level.setBlock(isolatedDepot,Blocks.AIR.defaultBlockState(),3);
  level.setBlock(isolatedDepot.below(),Blocks.STONE.defaultBlockState(),3);
  level.setBlock(isolatedDepot,Blocks.CHEST.defaultBlockState(),3);
  ref.data().chest=java.util.Optional.of(isolatedDepot);ref.dirty();
  var box=ref.chest(level);box.clearContent();
  BlockPos other=at.east(80),deck=at.offset(12,-1,0);String key=Network.dim(level)+other.asLong();
  Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Bridge check",30,true,8,0)),Map.of(),0,0));
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.of(key,12L,key+":start",12L));
  // A route needs real, loaded terrain: the planner (RoadRoute) refuses to plan across the natural land below this sky fixture.
  // Like RoadConstructionChecks, build a flat three-lane corridor at ground level (y-1) all the way to the link, so that the
  // production planner stores the route; the water gap below is cut into it. The flanks are flush with the lanes so the
  // bridge worker's ledge stays at bank height (a lower flank would be the nearest ledge to the pier foot).
  for(int x=1;x<=80;x++)for(int side=-2;side<=2;side++) {
   BlockPos p=at.offset(x,-1,side);level.getChunkAt(p);
   level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),3);
   level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(2),Blocks.AIR.defaultBlockState(),3);
  }
  for(int side=-1;side<=1;side++) {
   BlockPos p=deck.offset(0,0,side);level.getChunkAt(p);level.setBlock(p.below(3),Blocks.STONE.defaultBlockState(),3);
   for(int depth=0;depth<3;depth++)level.setBlock(p.below(depth),Blocks.WATER.defaultBlockState(),3);
   level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(2),Blocks.AIR.defaultBlockState(),3);
  }
  Map<String,Long> savedRoads=new java.util.HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  Roads.syncRouteGeometry(level,ref.bell(),other,savedRoads,key,null,null);
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(savedRoads));ref.dirty();
  // The route planner (and Roads.nextConstruction's grade look-ahead over cursor-1..cursor+1) accepts only natural, repairable
  // ground next to a deck: the banks stay grass, and the protected farmland starts one cell behind the far bank. It is placed
  // after the route is stored, as in RoadConstructionChecks, because the planner would otherwise route around it.
  for(int side=-1;side<=1;side++) {
   BlockPos farm=deck.offset(2,0,side);
   level.setBlock(farm.below(),Blocks.STONE.defaultBlockState(),3);level.setBlock(farm,Blocks.FARMLAND.defaultBlockState(),3);
   level.setBlock(farm.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(farm.above(2),Blocks.AIR.defaultBlockState(),3);
  }
  var v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);var st=new JobState();long now=level.getGameTime()+1000;int checks=0;
  try {
   Ctx c=new Ctx(level,v,st,mem,ref,now);
   check(!RoadBuilderJob.construct(c)&&level.getBlockState(deck.below(2)).is(Blocks.WATER),"no bridge material creates no free support");checks++;
   box.setItem(0,new ItemStack(Items.SPRUCE_LOG,6));box.setItem(1,new ItemStack(Items.SPRUCE_PLANKS,3));
   for(int step=0;step<9;step++) {
    st.roadBuildScanAt=0;BlockPos depot=ref.chestPos(level);v.teleportTo(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
    check(RoadBuilderJob.construct(c)&&!v.getInventory().isEmpty(),"each pier/deck block is picked up physically");checks++;
    var task=st.roadRepair;check(task!=null&&task.bridge()&&task.worksite().getY()==deck.getY()+1,"worker stays at deck height rather than descending into water");checks++;
    RoadBuilderJob.construct(c);check(level.getBlockState(task.ground()).is(Blocks.WATER),"carried bridge block is not placed remotely");checks++;
    BlockPos stand=task.worksite();v.teleportTo(stand.getX()+.5,stand.getY(),stand.getZ()+.5);
    check(RoadBuilderJob.construct(c)&&v.getInventory().isEmpty()&&level.getBlockState(task.ground()).equals(task.paving()),"arrival places and consumes one bridge block");checks++;
    st=new JobState();c=new Ctx(level,v,st,mem,ref,now);
   }
   check(box.isEmpty(),"three lanes consume exactly six logs and three planks");checks++;
   check(Roads.nextConstruction(level,ref)==null&&ref.bellEntity().getAttached(VillagerPlus.ROADS).get(key)==13L,"complete paid bridge advances once and stops at protected farmland");checks++;
   BlockPos deep=deck.east(24);level.getChunkAt(deep);
   level.setBlock(deep.below(27),Blocks.STONE.defaultBlockState(),3);
   for(int d=0;d<27;d++)level.setBlock(deep.below(d),Blocks.WATER.defaultBlockState(),3);
   level.setBlock(deep.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(deep.above(2),Blocks.AIR.defaultBlockState(),3);
   check(RoadBridge.plan(level,deep)==null&&level.getBlockState(deep).is(Blocks.WATER),"water without footing within 24 blocks remains unchanged");checks++;
   level.setBlock(deep,Blocks.LAVA.defaultBlockState(),3);
   check(RoadBridge.plan(level,deep)==null,"lava is never treated as bridge water");checks++;
   return checks;
  } finally {v.discard();}
 }

 /**
  * D04 ravine lead: a dry gorge (four columns wide, seven air cells deep, stone floor) on a three-lane route is bridged by the
  * physical, paid road worker from the persisted gorge profile (piers from the ravine floor, deck at bank height). Fixture proof
  * only, not a natural run.
  */
 public static int runRavine(ServerLevel level,BlockPos at) {
  var ref=Checks090.fixtureForEquipment(level,at);
  BlockPos fixtureDepot=ref.chestPos(level);
  level.removeBlockEntity(fixtureDepot);level.setBlock(fixtureDepot,Blocks.AIR.defaultBlockState(),3);
  BlockPos isolatedDepot=at.south(8);level.removeBlockEntity(isolatedDepot);
  level.setBlock(isolatedDepot,Blocks.AIR.defaultBlockState(),3);
  level.setBlock(isolatedDepot.below(),Blocks.STONE.defaultBlockState(),3);
  level.setBlock(isolatedDepot,Blocks.CHEST.defaultBlockState(),3);
  ref.data().chest=java.util.Optional.of(isolatedDepot);ref.dirty();
  var box=ref.chest(level);box.clearContent();
  BlockPos other=at.east(80);String key=Network.dim(level)+other.asLong();
  Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Ravine check",30,true,8,0)),Map.of(),0,0));
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.of(key,12L,key+":start",12L));
  final int gapFrom=12,gapTo=15,depth=8;
  for(int x=1;x<=80;x++)for(int side=-2;side<=2;side++) {
   BlockPos p=at.offset(x,-1,side);level.getChunkAt(p);
   level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),3);
   level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(2),Blocks.AIR.defaultBlockState(),3);
  }
  BlockPos deckBase=at.offset(0,-1,0);
  for(int x=gapFrom;x<=gapTo;x++)for(int side=-1;side<=1;side++) {
   BlockPos top=deckBase.offset(x,0,side);
   for(int d=0;d<depth;d++)level.setBlock(top.below(d),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(top.below(depth),Blocks.STONE.defaultBlockState(),3);
  }
  Map<String,Long> savedRoads=new java.util.HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  Roads.syncRouteGeometry(level,ref.bell(),other,savedRoads,key,null,null);
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(savedRoads));ref.dirty();
  var v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);var st=new JobState();long now=level.getGameTime()+1000;int checks=0;
  try {
   Ctx c=new Ctx(level,v,st,mem,ref,now);
   check(!RoadBuilderJob.construct(c)&&level.getBlockState(deckBase.offset(gapFrom,0,0)).isAir(),"ravine: no bridge material creates no free support");checks++;
   int cells=(gapTo-gapFrom+1)*3,logs=cells*(depth-1),planks=cells;
   box.setItem(0,new ItemStack(Items.SPRUCE_LOG,64));box.setItem(1,new ItemStack(Items.SPRUCE_LOG,logs-64));
   box.setItem(2,new ItemStack(Items.SPRUCE_PLANKS,planks));
   int placed=0;
   for(int step=0;step<cells*depth+20;step++) {
    st.roadBuildScanAt=0;BlockPos depot=ref.chestPos(level);v.teleportTo(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
    if(!RoadBuilderJob.construct(c)||v.getInventory().isEmpty())break;
    var task=st.roadRepair;
    if(task==null||!task.bridge())throw new IllegalStateException("Road bridge: ravine: unexpected non-bridge task at step "+step);
    RoadBuilderJob.construct(c);
    check(!level.getBlockState(task.ground()).equals(task.paving()),"ravine: carried bridge block is not placed remotely");
    BlockPos stand=task.worksite();v.teleportTo(stand.getX()+.5,stand.getY(),stand.getZ()+.5);
    check(RoadBuilderJob.construct(c)&&v.getInventory().isEmpty()&&level.getBlockState(task.ground()).equals(task.paving()),"ravine: arrival places and consumes one bridge block");
    placed++;
    st=new JobState();c=new Ctx(level,v,st,mem,ref,now);
   }
   check(placed==logs+planks,"ravine: exactly "+(logs+planks)+" paid blocks were placed (placed="+placed+")");checks++;
   check(box.isEmpty(),"ravine: all supplied logs and planks are consumed, none appear from nothing");checks++;
   for(int x=gapFrom;x<=gapTo;x++)for(int side=-1;side<=1;side++) {
    BlockPos top=deckBase.offset(x,0,side);
    boolean ok=level.getBlockState(top).is(Blocks.SPRUCE_PLANKS)&&level.getBlockState(top.below(depth)).is(Blocks.STONE);
    for(int d=1;d<depth&&ok;d++)ok=level.getBlockState(top.below(d)).is(Blocks.SPRUCE_LOG);
    check(ok,"ravine: deck at bank height on a log pier down to the ravine floor at "+x+","+side);
   }
   checks+=(gapTo-gapFrom+1)*3;
   long cursor=ref.bellEntity().getAttached(VillagerPlus.ROADS).getOrDefault(key,-1L);
   check(cursor>gapTo,"ravine: route cursor advanced past the bridge (cursor="+cursor+")");checks++;
   for(int x=gapFrom;x<=gapTo;x++)for(int side:new int[]{-2,2}) {
    check(level.getBlockState(deckBase.offset(x,0,side)).is(Blocks.GRASS_BLOCK),"ravine: flanks outside the three lanes stay untouched");checks++;
   }
   return checks;
  } finally {v.discard();}
 }

 public static int verify(ServerLevel level,BlockPos at) {
  level.getChunkAt(at);var be=level.getBlockEntity(at);check(be!=null,"saved bridge fixture bell loads");
  String key=Network.dim(level)+at.east(80).asLong();var state=be.getAttached(VillagerPlus.ROADS);
  check(state!=null&&state.getOrDefault(key,-1L)==13&&state.getOrDefault(key+":start",-1L)==12,"road cursor and boundary survive real server restart");
  for(int side=-1;side<=1;side++) {
   BlockPos deck=at.offset(12,-1,side);level.getChunkAt(deck);
   check(state.getOrDefault(key+":bridge:12:"+side,Long.MIN_VALUE)==deck.getY(),"bridge deck elevation survives restart");
   check(level.getBlockState(deck).is(Blocks.SPRUCE_PLANKS)&&level.getBlockState(deck.below()).is(Blocks.SPRUCE_LOG)&&level.getBlockState(deck.below(2)).is(Blocks.SPRUCE_LOG),"paid deck and all pier blocks survive restart");
  }
  return 8;
 }
 private static void check(boolean value,String message) {if(!value)throw new IllegalStateException("Road bridge: "+message);}
}
