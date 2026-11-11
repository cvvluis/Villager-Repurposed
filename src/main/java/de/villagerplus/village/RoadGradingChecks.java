package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobState;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.RoadBuilderJob;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Real-stock road grade checks. The synthetic landscape and all block entities are restored after the run. */
public final class RoadGradingChecks {
 private RoadGradingChecks() { }

 public static int run(ServerLevel level,BlockPos origin) {
  BlockPos bell=origin.east(100),other=bell.east(80);int base=bell.getY()-1;
  Map<BlockPos,Cell> before=new LinkedHashMap<>();snapshotArea(level,before,bell,other,base);
  Villager worker=null;int checks=0;
  try {
   VillageRef ref=Checks090.fixtureForEquipment(level,bell);
   BlockPos originalDepotPos=ref.chestPos(level);var originalDepot=ref.chest(level);originalDepot.clearContent();
   BlockPos depotPos=bell.south(5);
   level.removeBlockEntity(originalDepotPos);level.setBlock(originalDepotPos,Blocks.AIR.defaultBlockState(),3);
   ref.data().chest=java.util.Optional.of(depotPos);
   ref.dirty();
   level.setBlock(depotPos.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(depotPos,Blocks.CHEST.defaultBlockState(),3);
   level.setBlock(depotPos.south().below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(depotPos.south(),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(depotPos.south().above(),Blocks.AIR.defaultBlockState(),3);
   var depot=ref.chest(level);depot.clearContent();
   for(int x=1;x<=80;x++)for(int side=-1;side<=1;side++)column(level,bell.offset(x,0,side),profileTerrainY(x,side,base),base);
   String key=Network.dim(level)+other.asLong();
   Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(
    new TradeNetwork.Link(Network.dim(level),other,"Road grade check",30,true,8,0)),Map.of(),0,0));
   Map<String,Long> roads=new HashMap<>();roads.put(key,10L);roads.put(key+":start",10L);
   require(RoadRoute.ensure(level,bell,other,roads,key,null,null),"profile fixture stores one reversible route");
   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(roads));ref.dirty();
   BlockPos route=RoadRoute.point(roads,key,bell,other,10);
   require(route.getX()==bell.getX()+10,"fixture cursor lies on the known straight route");checks++;

   Roads.Repair fill=Roads.nextConstruction(level,ref);
   require(fill!=null&&fill.action()==Roads.WorkAction.GRADE_FILL
    &&fill.ground().getY()==base+1&&fill.gradeY()==base+2,
    "two-block crossfall creates one paid support-fill task at the persisted route profile");checks++;
   BlockPos fillPos=fill.ground();
   level.setBlock(fillPos,Blocks.CHEST.defaultBlockState(),3);
   require(!Roads.repairable(level,fill)&&level.getBlockState(fillPos).is(Blocks.CHEST),
    "a container in the grading cell blocks the paid step and remains untouched");checks++;
   level.setBlock(fillPos,Blocks.AIR.defaultBlockState(),3);
   worker=EntityTypes.VILLAGER.spawn(level,bell.south(3),EntitySpawnReason.COMMAND);
   worker.setNoAi(true);worker.setInvulnerable(true);worker.addTag("vp090_fixture");
   worker.getInventory().clearContent();
   var memory=worker.getAttachedOrCreate(VillagerPlus.MEMORY);long now=level.getGameTime()+1000;
   JobState emptyState=new JobState();emptyState.roadBuildScanAt=0;
   worker.teleportTo(depotPos.getX()+.5,depotPos.getY(),depotPos.getZ()+1.5);
   boolean noStock=RoadBuilderJob.construct(new Ctx(level,worker,emptyState,memory,ref,now++));
   require(!noStock&&level.getBlockState(fillPos).isAir()&&cursor(ref,key)==10,
    "missing dirt leaves the hill and route cursor unchanged");checks++;

   depot.setItem(0,new ItemStack(Items.DIRT));
   JobState fillState=new JobState();fillState.roadBuildScanAt=0;
   worker.teleportTo(depotPos.getX()+.5,depotPos.getY(),depotPos.getZ()+1.5);
   require(RoadBuilderJob.construct(new Ctx(level,worker,fillState,memory,ref,now++))
    &&fillState.roadRepair!=null&&fillState.roadRepair.action()==Roads.WorkAction.GRADE_FILL
    &&depot.getItem(0).isEmpty()&&JobUtil.count(worker.getInventory(),s->s.is(Items.DIRT))==1
    &&level.getBlockState(fillPos).isAir(),"worker carries one real dirt block before filling");checks++;
   Roads.Repair savedFill=fillState.roadRepair;worker.teleportTo(savedFill.worksite().getX()+.5,savedFill.worksite().getY(),savedFill.worksite().getZ()+.5);
   require(RoadBuilderJob.construct(new Ctx(level,worker,fillState,memory,ref,now++))
    &&level.getBlockState(fillPos).is(Blocks.DIRT)&&JobUtil.count(worker.getInventory(),s->s.is(Items.DIRT))==0
    &&cursor(ref,key)==10,"arrival consumes dirt into the visible support and keeps the shared road cursor");checks++;

   Map<String,Long> savedRoads=ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
   Map<String,Long> reloaded=new HashMap<>(savedRoads);
   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(reloaded));ref.dirty();
   Roads.Repair resumed=Roads.nextConstruction(level,ref);
   require(resumed!=null&&resumed.action()==Roads.WorkAction.GRADE_PAVE
    &&resumed.ground().getY()==base+2&&resumed.gradeY()==base+2
    &&reloaded.getOrDefault(key+":gradeY:10",Long.MIN_VALUE)==base+2,
    "fresh job state resumes at the saved profile surface after the fill");checks++;
   depot.setItem(0,new ItemStack(Items.COBBLESTONE));
   JobState paveState=new JobState();paveState.roadBuildScanAt=0;
   worker.teleportTo(depotPos.getX()+.5,depotPos.getY(),depotPos.getZ()+1.5);
   require(RoadBuilderJob.construct(new Ctx(level,worker,paveState,memory,ref,now++))
    &&paveState.roadRepair!=null&&paveState.roadRepair.action()==Roads.WorkAction.GRADE_PAVE
    &&depot.getItem(0).isEmpty()&&JobUtil.count(worker.getInventory(),s->s.is(Items.COBBLESTONE))==1,
    "worker carries the separate surface material from the real depot");checks++;
   Roads.Repair surface=paveState.roadRepair;worker.teleportTo(surface.worksite().getX()+.5,surface.worksite().getY(),surface.worksite().getZ()+.5);
   require(RoadBuilderJob.construct(new Ctx(level,worker,paveState,memory,ref,now++))
    &&level.getBlockState(surface.ground()).is(Blocks.COBBLESTONE)
    &&level.getBlockState(fillPos).is(Blocks.DIRT)&&depot.getItem(0).isEmpty(),
    "paid pavement finishes over retained support without changing the saved profile");checks++;

   BlockPos cut=new BlockPos(bell.getX()+50,base+1,bell.getZ()+5),cutSupport=cut.below(),worksite=cut.east();
   level.setBlock(cutSupport.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(cutSupport,Blocks.DIRT.defaultBlockState(),3);
   level.setBlock(cut,Blocks.GRASS_BLOCK.defaultBlockState(),3);
   level.setBlock(cut.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(cut.above(2),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(worksite.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(worksite,Blocks.AIR.defaultBlockState(),3);level.setBlock(worksite.above(),Blocks.AIR.defaultBlockState(),3);
   Map<String,Long> withCut=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
   withCut.put(key+":gradeY:55",(long)base);ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(withCut));ref.dirty();
   Roads.Repair cutTask=new Roads.Repair(cut,Blocks.COBBLESTONE.defaultBlockState(),worksite,false,
    Blocks.GRASS_BLOCK.defaultBlockState(),null,null,bell,null,Roads.WorkAction.GRADE_CUT,key,55,base);
   JobState noCutStock=new JobState();noCutStock.roadRepair=cutTask;noCutStock.roadBuilding=true;
   worker.teleportTo(bell.getX()+3.5,bell.getY(),bell.getZ()+1.5);
   require(!RoadBuilderJob.construct(new Ctx(level,worker,noCutStock,memory,ref,now++))
    &&level.getBlockState(cut).is(Blocks.GRASS_BLOCK),"profile cut cannot start without its paving reserve");checks++;
   depot.setItem(0,new ItemStack(Items.COBBLESTONE));
   JobState cutState=new JobState();cutState.roadRepair=cutTask;cutState.roadBuilding=true;
   worker.teleportTo(depotPos.getX()+.5,depotPos.getY(),depotPos.getZ()+1.5);
   require(RoadBuilderJob.construct(new Ctx(level,worker,cutState,memory,ref,now++))
    &&JobUtil.count(worker.getInventory(),s->s.is(Items.COBBLESTONE))==1
    &&depot.getItem(0).isEmpty()
    &&level.getBlockState(cut).is(Blocks.GRASS_BLOCK),"real paving reserve is carried before any excavation");checks++;
   worker.teleportTo(worksite.getX()+.5,worksite.getY(),worksite.getZ()+.5);
   for(int stroke=0;stroke<=10;stroke++) {
    now+=stroke==0?1:6;
    boolean worked=RoadBuilderJob.construct(new Ctx(level,worker,cutState,memory,ref,now));
    if(stroke<10)require(worked&&level.getBlockState(cut).is(Blocks.GRASS_BLOCK),"visible cut keeps its block until the final stroke");
    else require(worked&&level.getBlockState(cut).isAir(),"final shovel stroke removes the scheduled natural soil");
   }
   require(JobUtil.count(worker.getInventory(),s->s.is(Items.DIRT))==1
    &&JobUtil.count(worker.getInventory(),s->s.is(Items.COBBLESTONE))==1
    &&ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()).getOrDefault(key+":gradeTouched:55",0L)==1L,
    "excavated dirt is retained and the partial grade survives volatile-job reset");checks+=2;
   Roads.Repair afterReload=new Roads.Repair(cutSupport,Blocks.COBBLESTONE.defaultBlockState(),worksite,false,
    null,null,null,bell,null,Roads.WorkAction.GRADE_PAVE,key,55,base);
   JobState reloadState=new JobState();reloadState.roadRepair=afterReload;reloadState.roadBuilding=true;
   require(Roads.repairable(level,afterReload)
    &&RoadBuilderJob.construct(new Ctx(level,worker,reloadState,memory,ref,now++))
    &&level.getBlockState(cutSupport).is(Blocks.COBBLESTONE)
    &&level.getBlockState(cut).isAir()&&JobUtil.count(worker.getInventory(),s->s.is(Items.DIRT))==1,
    "a fresh job resumes the saved cut profile, consumes its reserved paver once and retains spoil");checks++;
   return checks;
  } finally {
   if(worker!=null)worker.discard();restore(level,before);
  }
 }

 private static int profileTerrainY(int x,int side,int base) {
  if(x==9)return side==0?base+1:base;
  if(x==10)return side==-1?base:base+2;
  if(x==11)return side==1?base+2:base+1;
  if(x==12)return side==0?base:base;
  return base;
 }

 private static void column(ServerLevel level,BlockPos pos,int topY,int base) {
  for(int y=base-2;y<topY;y++)level.setBlock(new BlockPos(pos.getX(),y,pos.getZ()),Blocks.STONE.defaultBlockState(),3);
  level.setBlock(new BlockPos(pos.getX(),topY,pos.getZ()),Blocks.GRASS_BLOCK.defaultBlockState(),3);
  for(int y=topY+1;y<=topY+4;y++)level.setBlock(new BlockPos(pos.getX(),y,pos.getZ()),Blocks.AIR.defaultBlockState(),3);
 }

 private static void snapshotArea(ServerLevel level,Map<BlockPos,Cell> before,BlockPos bell,BlockPos end,int base) {
  for(int x=bell.getX()-4;x<=end.getX()+4;x++)for(int z=bell.getZ()-10;z<=bell.getZ()+10;z++)
   for(int y=base-3;y<=base+7;y++)snapshot(level,before,new BlockPos(x,y,z));
 }

 private static void snapshot(ServerLevel level,Map<BlockPos,Cell> before,BlockPos pos) {
  level.getChunkAt(pos);BlockEntity entity=level.getBlockEntity(pos);
  before.putIfAbsent(pos.immutable(),new Cell(level.getBlockState(pos),
   entity==null?null:entity.saveWithFullMetadata(level.registryAccess())));
 }

 private static void restore(ServerLevel level,Map<BlockPos,Cell> before) {
  for(BlockPos pos:before.keySet())level.removeBlockEntity(pos);
  before.forEach((pos,cell)->level.setBlock(pos,cell.state(),3));
  before.forEach((pos,cell)->{
   if(cell.nbt()==null)return;
   BlockEntity entity=BlockEntity.loadStatic(pos,cell.state(),cell.nbt().copy(),level.registryAccess());
   if(entity!=null)level.setBlockEntity(entity);
  });
 }

 private record Cell(BlockState state,CompoundTag nbt) { }
 private static long cursor(VillageRef ref,String key){return ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()).getOrDefault(key,-1L);}
 private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("Road grading: "+message);}
}
