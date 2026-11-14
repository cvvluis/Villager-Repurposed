package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Synthetic arrival/transaction checks for paid three-lane road construction. */
public final class RoadConstructionChecks {
 private RoadConstructionChecks() { }
 public static int run(ServerLevel level,BlockPos at) {
  var ref=Checks090.fixtureForEquipment(level,at);
  BlockPos fixtureDepot=ref.chestPos(level);
  level.removeBlockEntity(fixtureDepot);level.setBlock(fixtureDepot,Blocks.AIR.defaultBlockState(),3);
  BlockPos isolatedDepot=at.south(8);level.removeBlockEntity(isolatedDepot);
  level.setBlock(isolatedDepot,Blocks.AIR.defaultBlockState(),3);
  level.setBlock(isolatedDepot.below(),Blocks.STONE.defaultBlockState(),3);
  level.setBlock(isolatedDepot,Blocks.CHEST.defaultBlockState(),3);
  ref.data().chest=java.util.Optional.of(isolatedDepot);ref.dirty();
  Container box=ref.chest(level);box.clearContent();
  BlockPos other=at.east(80);String key=Network.dim(level)+other.asLong();
  Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Construction check",30,true,8,0)),Map.of(),0,0));
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.of(key,10L,key+":start",10L));
  for(int x=1;x<=80;x++)for(int side=-1;side<=1;side++) {
   BlockPos p=at.offset(x,x<=10?-1:0,side);level.getChunkAt(p);
   level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),3);level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(2),Blocks.AIR.defaultBlockState(),3);
  }
  for(int x=1;x<=80;x++)for(int side:new int[]{-2,2}) {
   BlockPos p=at.offset(x,x<=10?-1:0,side);level.getChunkAt(p);
   level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(p,Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(p.above(2),Blocks.AIR.defaultBlockState(),3);
  }
  Map<String,Long> savedRoads=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  Roads.syncRouteGeometry(level,ref.bell(),other,savedRoads,key,null,null);
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(savedRoads));ref.dirty();
  level.setBlock(at.offset(12,0,1),Blocks.FARMLAND.defaultBlockState(),3);
  var v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);var st=new JobState();long now=level.getGameTime()+1000;int checks=0;
  try {
   check(Roads.nextRepair(level,ref)==null,"unbuilt village approach before recorded road start is excluded from repair");checks++;
   Roads.tick(level,ref,now);
   check(level.getBlockState(at.offset(10,-1,0)).is(Blocks.GRASS_BLOCK)&&cursor(ref,key)==10,"village tick neither remotely paves nor advances road");checks++;
   Ctx c=new Ctx(level,v,st,mem,ref,now);
   check(!RoadBuilderJob.construct(c)&&cursor(ref,key)==10,"missing materials preserve cursor and terrain");checks++;
   Roads.Repair firstPalette=Roads.nextConstruction(level,ref);
   check(firstPalette!=null,"flat section exposes a paid lane task before material collection");checks++;
   stockSection(box,firstPalette);
   // A second builder can finish the lane while the first carries its paid block to the worksite.
   JobState raceState=new JobState();Ctx race=new Ctx(level,v,raceState,mem,ref,now);
   var raceDepot=ref.chestPos(level);v.teleportTo(raceDepot.getX()+.5,raceDepot.getY(),raceDepot.getZ()+1.5);
   check(RoadBuilderJob.construct(race)&&raceState.roadRepair!=null
    &&JobUtil.count(v.getInventory(),s->s.is(firstPalette.paving().getBlock().asItem()))==1,
    "first builder carries one paid block for the saved route lane");checks++;
   Roads.Repair racedLane=raceState.roadRepair;BlockPos racedTarget=racedLane.ground();
   check(JobUtil.take(box,s->s.is(racedLane.paving().getBlock().asItem()),1)==1,
    "the second builder also pays for its completed lane");checks++;
   level.setBlock(racedTarget,racedLane.paving(),3);
   v.teleportTo(racedTarget.getX()+.5,racedTarget.getY()+1,racedTarget.getZ()+1.5);
   boolean raceContinued=RoadBuilderJob.construct(race);
   check(raceContinued&&level.getBlockState(racedTarget).equals(racedLane.paving())
    &&raceState.roadRepair!=null&&!raceState.roadRepair.ground().equals(racedTarget)
    &&raceState.roadRepair.gradeCursor()==10
    &&JobUtil.count(v.getInventory(),s->s.is(racedLane.paving().getBlock().asItem()))==1
    &&JobUtil.count(box,s->s.is(raceState.roadRepair.paving().getBlock().asItem()))==1,
    "a stale lane is preserved and the next paid lane resumes immediately without re-spending carried material");checks++;
   level.setBlock(racedTarget,Blocks.GRASS_BLOCK.defaultBlockState(),3);
   box.clearContent();stockSection(box,firstPalette);v.getInventory().clearContent();
   var edgeBlock=firstPalette.paving().getBlock();
   var centerBlock=edgeBlock==Blocks.SANDSTONE?Blocks.SMOOTH_SANDSTONE:Blocks.GRAVEL;
   Map<BlockPos,net.minecraft.world.level.block.state.BlockState> completedLanes=new HashMap<>();
   for(int lane=0;lane<3;lane++) {
    st.roadBuildScanAt=0;
    var depot=ref.chestPos(level);v.teleportTo(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
    int stockBefore=JobUtil.count(box,s->!s.isEmpty());
    boolean collected=RoadBuilderJob.construct(c);
    Roads.Repair laneTask=st.roadRepair;
    var laneItem=laneTask==null?Items.AIR:laneTask.paving().getBlock().asItem();
    check(collected&&laneTask!=null&&JobUtil.count(v.getInventory(),s->s.is(laneItem))==1
     &&JobUtil.count(box,s->!s.isEmpty())==stockBefore-1,"lane material must be physically collected from the isolated public depot; task="
      +(laneTask==null?"null":laneTask.action()+" "+laneTask.paving().getBlock().toString())+", sources="+Lager.materialChests(level,ref)
      +", depot="+ref.chestPos(level)+", stock="+stockBefore+", carried="+JobUtil.count(v.getInventory(),s->!s.isEmpty()));checks++;
    BlockPos target=st.roadRepair.ground();RoadBuilderJob.construct(c);
    check(level.getBlockState(target).is(Blocks.GRASS_BLOCK)&&cursor(ref,key)==10,"partial lane remains unpaid/unbuilt while walking");checks++;
    int depotAfterPickup=JobUtil.count(box,s->!s.isEmpty());
    // A restart drops the volatile task but preserves the carried block and saved village cursor.
    st=new JobState();c=new Ctx(level,v,st,mem,ref,now);
    v.teleportTo(target.getX()+.5,target.getY()+1,target.getZ()+1.5);
    boolean placedAfterResume=RoadBuilderJob.construct(c);
    check(placedAfterResume&&v.getInventory().isEmpty()&&level.getBlockState(target).equals(laneTask.paving())
     &&JobUtil.count(box,s->!s.isEmpty())==depotAfterPickup
     &&completedLanes.entrySet().stream().allMatch(e->level.getBlockState(e.getKey()).equals(e.getValue())),
     "resume reuses the carried lane block once and preserves every already-built road lane");checks++;
    v.getInventory().addItem(new ItemStack(laneTask.paving().getBlock().asItem()));
    boolean duplicatePlacement=RoadBuilderJob.repair(c,laneTask);
    check(!duplicatePlacement&&JobUtil.count(v.getInventory(),s->s.is(laneTask.paving().getBlock().asItem()))==1
     &&level.getBlockState(target).equals(laneTask.paving()),
     "retrying a completed saved lane leaves its road block and second material untouched");checks++;
    v.getInventory().clearContent();
    completedLanes.put(target,laneTask.paving());
    if(lane<2) {
     check(st.roadBuilding&&st.roadRepair!=null&&st.roadRepair.gradeCursor()==10,
      "a paid cross-section keeps its next lane task ahead of repair work");checks++;
     // Recreate volatile work state to simulate interruption between individual lane blocks.
     st=new JobState();c=new Ctx(level,v,st,mem,ref,now);
    }
   }
   check(box.isEmpty()&&level.getBlockState(at.offset(10,-1,-1)).is(edgeBlock)
    &&level.getBlockState(at.offset(10,-1,0)).is(centerBlock)&&level.getBlockState(at.offset(10,-1,1)).is(edgeBlock),
    "three-lane section consumes the selected biome's edge and center materials");checks++;
   var edgeSlab=edgeBlock==Blocks.SANDSTONE?Blocks.SANDSTONE_SLAB:Blocks.COBBLESTONE_SLAB;
   BlockPos leftCurb=at.offset(10,-1,-2),rightCurb=at.offset(10,-1,2);
   check(level.getBlockState(leftCurb).is(edgeSlab)&&level.getBlockState(rightCurb).is(edgeSlab)
    &&level.getBlockState(leftCurb).getValue(SlabBlock.TYPE)==SlabType.BOTTOM
    &&level.getBlockState(rightCurb).getValue(SlabBlock.TYPE)==SlabType.BOTTOM
    &&level.getBlockState(at.offset(10,-1,-1)).is(edgeBlock)
    &&level.getBlockState(at.offset(10,-1,1)).is(edgeBlock),
    "each road shoulder spends one matching bottom slab while leaving all three full-height travel lanes open");checks++;

   check(st.roadBuilding&&st.roadRepair!=null&&st.roadRepair.gradeCursor()==11
    &&st.roadRepair.ground().getY()==at.getY(),"the completed flat section hands off to the saved one-block uphill profile");checks++;
   stockSection(box,st.roadRepair);
   for(int lane=0;lane<3;lane++) {
    st.roadBuildScanAt=0;
    var depot=ref.chestPos(level);v.teleportTo(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
    var currentTask=st.roadRepair;
    var expectedItem=currentTask.paving().getBlock().asItem();
    var expectedBlock=currentTask.paving().getBlock();
    check(RoadBuilderJob.construct(c)&&JobUtil.count(v.getInventory(),s->s.is(expectedItem))==1,
     "uphill lane requires one real paving block before work");checks++;
    if(lane!=1) {
     var curbItem=(edgeBlock==Blocks.SANDSTONE?Items.SANDSTONE_SLAB:Items.COBBLESTONE_SLAB);
     check(RoadBuilderJob.construct(c)&&JobUtil.count(v.getInventory(),s->s.is(curbItem))==1,
      "uphill outside lane collects one paid low-slab shoulder before placement");checks++;
    }
    Roads.Repair hill=st.roadRepair;
    check(hill!=null&&hill.gradeCursor()==11&&hill.ground().getX()==at.getX()+11
     &&hill.ground().getY()==at.getY(),"one-block rise is stored as the next paid three-lane profile");checks++;
    if(lane==0) {
     v.teleportTo(at.getX()+10.5,at.getY(),at.getZ()+.5);
     var path=de.villagerplus.build.Builder.workPath(c,new BlockPos(at.getX()+11,at.getY()+1,at.getZ()));
     check(path!=null&&path.canReach(),"villager pathfinding can climb the one-block longitudinal step");checks++;
    }
    v.teleportTo(hill.worksite().getX()+.5,hill.worksite().getY(),hill.worksite().getZ()+.5);
    check(RoadBuilderJob.construct(c)&&v.getInventory().isEmpty()
     &&level.getBlockState(hill.ground()).is(expectedBlock)
     &&cursor(ref,key)==(lane<2?11:12),"uphill paving holds the cursor through the first two lanes and advances after the third");checks++;
    if(lane<2)check(st.roadBuilding&&st.roadRepair!=null&&st.roadRepair.gradeCursor()==11,
     "the builder immediately resumes the next uphill lane");
    if(lane<2)checks++;
   }
   check(cursor(ref,key)==12&&Roads.nextConstruction(level,ref)==null&&cursor(ref,key)==12,
    "the completed raised section advances once and a farmland lane stops the next cross-section");checks++;
   check(level.getBlockState(at.offset(11,0,-1)).is(Blocks.COBBLESTONE)
    &&level.getBlockState(at.offset(11,0,0)).is(Blocks.GRAVEL)
    &&level.getBlockState(at.offset(11,0,1)).is(Blocks.COBBLESTONE)
    &&level.getBlockState(at.offset(11,1,-1)).isAir()&&level.getBlockState(at.offset(11,2,1)).isAir(),
    "all three uphill lanes are paved with two clear blocks of villager headroom");checks++;

   for(int side=-1;side<=1;side++) {
    BlockPos cliff=at.offset(12,0,side);
    level.setBlock(cliff,Blocks.AIR.defaultBlockState(),3);
    level.setBlock(cliff.above(),Blocks.STONE.defaultBlockState(),3);
    level.setBlock(cliff.above(2),Blocks.GRASS_BLOCK.defaultBlockState(),3);
    level.setBlock(cliff.above(3),Blocks.AIR.defaultBlockState(),3);
   }
   box.setItem(0,new ItemStack(Items.COBBLESTONE,2));box.setItem(1,new ItemStack(Items.GRAVEL));
   JobState steepState=new JobState();steepState.roadBuildScanAt=0;
   v.teleportTo(ref.chestPos(level).getX()+.5,ref.chestPos(level).getY(),ref.chestPos(level).getZ()+1.5);
   Ctx steep=new Ctx(level,v,steepState,mem,ref,now++);
   check(!RoadBuilderJob.construct(steep)&&box.getItem(0).getCount()==2&&box.getItem(1).getCount()==1
    &&v.getInventory().isEmpty()&&cursor(ref,key)==12,
    "a two-block jump is rejected before stock is collected or the saved cursor advances");checks++;
   check(Roads.nextConstruction(level,ref)==null&&cursor(ref,key)==12
    &&level.getBlockState(at.offset(12,2,-1)).is(Blocks.GRASS_BLOCK),
    "unsafe steep terrain remains intact and blocks the entire three-lane section");checks++;
   return checks;
  } finally {v.discard();}
 }
 private static void stockSection(Container box,Roads.Repair firstTask) {
  boolean sand=firstTask.paving().is(Blocks.SANDSTONE)||firstTask.paving().is(Blocks.SMOOTH_SANDSTONE);
  box.clearContent();
  box.setItem(0,new ItemStack(sand?Items.SANDSTONE:Items.COBBLESTONE,2));
  box.setItem(1,new ItemStack(sand?Items.SMOOTH_SANDSTONE:Items.GRAVEL));
  box.setItem(2,new ItemStack(sand?Items.SANDSTONE_SLAB:Items.COBBLESTONE_SLAB,2));
 }
 private static long cursor(VillageRef ref,String key) {return ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()).getOrDefault(key,-1L);}
 private static void check(boolean value,String label){if(!value)throw new IllegalStateException("Road construction: "+label);}
}
