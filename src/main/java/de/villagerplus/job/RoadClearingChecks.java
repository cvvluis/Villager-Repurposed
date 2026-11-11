package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

/** Paced real tree felling, exact log drops and structure protection on a remote synthetic road. */
public final class RoadClearingChecks {
 private RoadClearingChecks() { }
 public static int run(ServerLevel level,BlockPos at) {
  level.getChunkAt(at);
  int groundY=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ())-1;
  at=new BlockPos(at.getX(),groundY+1,at.getZ());
  Map<BlockPos,Cell> before=capture(level,at,groundY);
  net.minecraft.world.entity.npc.villager.Villager v=null;
  try {
  Checks090.fixtureForEquipment(level, at.east(80));
  var ref=Checks090.fixtureForEquipment(level,at);
  groundY=ref.bell().getY()-1;
  BlockPos other=at.east(80);
  var peerRef=Checks090.fixtureForEquipment(level,other);
  String key=Network.dim(level)+other.asLong();
  String peerKey=Network.dim(level)+ref.bell().asLong();
  Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Clearing check",30,true,8,0)),Map.of(),0,0));
  Network.save(peerRef.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),ref.bell(),"Clearing check peer",30,true,8,0)),Map.of(),0,0));
  int constructionCursor=12;
  Map<String,Long> roads=new java.util.HashMap<>();
  roads.put(key,(long)constructionCursor);roads.put(key+":start",1L);
  Map<String,Long> peerRoads=new java.util.HashMap<>();
  peerRoads.put(peerKey,(long)constructionCursor);peerRoads.put(peerKey+":start",0L);
  ref.bellEntity().setAttached(VillagerPlus.ROADS,roads);
  peerRef.bellEntity().setAttached(VillagerPlus.ROADS,peerRoads);
  roads=new java.util.HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  peerRoads=new java.util.HashMap<>(peerRef.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  Roads.syncRouteGeometry(level,ref.bell(),peerRef.bell(),roads,key,peerRoads,peerKey);
  ref.bellEntity().setAttached(VillagerPlus.ROADS,roads);
  peerRef.bellEntity().setAttached(VillagerPlus.ROADS,peerRoads);
  BlockPos lane=Roads.laneCell(roads,key,ref.bell(),peerRef.bell(),constructionCursor,-1);
  for(int x=Math.min(at.getX()+9,lane.getX()-2);x<=Math.max(at.getX()+35,lane.getX()+2);x++)for(int z=Math.min(at.getZ()-5,lane.getZ()-2);z<=Math.max(at.getZ()+12,lane.getZ()+2);z++) {
   BlockPos surface=new BlockPos(x,groundY,z);level.getChunkAt(surface);
   level.setBlock(surface.below(),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(surface,Blocks.GRASS_BLOCK.defaultBlockState(),3);
   level.setBlock(surface.above(),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(surface.above(2),Blocks.AIR.defaultBlockState(),3);
  }
  BlockPos root=new BlockPos(lane.getX(),groundY,lane.getZ());
  level.setBlock(root.below(),Blocks.DIRT.defaultBlockState(),3);
  tree(level,root,5);
  for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++) {
   if(dx==0&&dz==0)continue;
   BlockPos standPad=root.offset(dx,1,dz);level.setBlock(standPad,Blocks.AIR.defaultBlockState(),3);level.setBlock(standPad.above(),Blocks.AIR.defaultBlockState(),3);
  }
  check(level.getBlockState(root.above(1)).is(Blocks.OAK_LOG)&&level.getBlockState(root.above(5)).is(Blocks.OAK_LOG),
   "fixture tree missing at lane "+root);
  Roads.nextRepair(level,ref);
  v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);var st=new JobState();long now=level.getGameTime()+1000;int checks=0;
   var first=Roads.nextConstruction(level,ref);
   check(first!=null&&first.removal()!=null&&first.ground().equals(root.above(5))&&level.getBlockState(root.above(5)).is(Blocks.OAK_LOG),"planning identifies highest natural log without remotely clearing; task="+first
    +" column="+RoadClearing.column(level,ref,root.getX(),root.getZ(),new HashMap<>(),"debug")
    +" top="+level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,root.getX(),root.getZ())
    +" log="+level.getBlockState(root)+" logTag="+level.getBlockState(root).is(net.minecraft.tags.BlockTags.OVERWORLD_NATURAL_LOGS)
    +" soil="+level.getBlockState(root.below())+" leaf="+level.getBlockState(root.above(5).east())+" diagnostic="+RoadClearing.diagnostic(level,root,root.above(5)));checks++;
   Ctx c=new Ctx(level,v,st,mem,ref,now);RoadBuilderJob.construct(c);
   check(v.getInventory().isEmpty()&&level.getBlockState(root.above(5)).is(Blocks.OAK_LOG)&&Anim.breakingStage(v)==-1,"distant worker must approach before any felling");checks++;
   for(int log=5;log>=1;log--) {
    st=new JobState();c=new Ctx(level,v,st,mem,ref,now);
    var task=Roads.nextConstruction(level,ref);
    check(task!=null&&task.ground().equals(root.above(log))&&task.removal()!=null,"saved tree evidence accepts remaining short stump after interruption");checks++;
    BlockPos stand=task.worksite();v.teleportTo(stand.getX()+.5,stand.getY(),stand.getZ()+.5);
    for(int stage=0;stage<10;stage++) {
     c=new Ctx(level,v,st,mem,ref,now);RoadBuilderJob.construct(c);
     check(Anim.breakingStage(v)==stage&&level.getBlockState(task.ground()).is(Blocks.OAK_LOG),"each crack stage 0-9 retains the real log until final stroke");now+=6;
    }
    checks++;
    c=new Ctx(level,v,st,mem,ref,now);RoadBuilderJob.construct(c);now+=6;
    check(level.getBlockState(task.ground()).isAir()&&JobUtil.count(v.getInventory(),s->s.is(Items.OAK_LOG))==6-log,"final stroke produces exactly one real carried log");checks++;
   }
   var paving=Roads.nextConstruction(level,ref);
   check(paving!=null&&paving.removal()==null&&paving.ground().equals(root),"cleared tree transitions to the original soil height for paid paving");checks++;
   BlockPos beam=at.offset(24,0,0);
   for(int y=groundY-2;y<=groundY+20;y++)level.setBlock(new BlockPos(beam.getX(),y,beam.getZ()),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(beam.below(),Blocks.STONE.defaultBlockState(),3);
   tree(level,beam,1);
   check(RoadClearing.column(level,ref,beam.getX(),beam.getZ(),new HashMap<>(),"beam")==null&&level.getBlockState(beam.above(1)).is(Blocks.OAK_LOG),"isolated placed timber is protected even near leaves");checks++;
   tree(level,beam,5);
   for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)if(dx!=0||dz!=0) {
    BlockPos pad=beam.offset(dx,0,dz);level.setBlock(pad,Blocks.GRASS_BLOCK.defaultBlockState(),3);
    level.setBlock(pad.above(),Blocks.AIR.defaultBlockState(),3);level.setBlock(pad.above(2),Blocks.AIR.defaultBlockState(),3);
   }
   Map<String,Long> remembered=new HashMap<>();
   var originalColumn=RoadClearing.column(level,ref,beam.getX(),beam.getZ(),remembered,"resume");
   check(originalColumn!=null&&originalColumn.next()!=null&&remembered.containsKey("resume:root")&&remembered.get("resume:top")==beam.getY()+5,
    "first scan persists the original trunk bounds for safe resume");checks++;
   BlockPos lateLog=beam.above(6);level.setBlock(lateLog,Blocks.OAK_LOG.defaultBlockState(),3);
   check(RoadClearing.column(level,ref,beam.getX(),beam.getZ(),remembered,"resume")==null&&level.getBlockState(lateLog).is(Blocks.OAK_LOG)
     &&!remembered.containsKey("resume:root")&&!remembered.containsKey("resume:top"),
    "a trunk extension above saved crown invalidates stale clearing without mutation");checks++;
   level.setBlock(lateLog,Blocks.AIR.defaultBlockState(),3);
   var resumed=RoadClearing.column(level,ref,beam.getX(),beam.getZ(),remembered,"resume");
   check(resumed!=null&&resumed.next()!=null&&resumed.next().ground().equals(beam.above(5))
     &&remembered.get("resume:top")==beam.getY()+5,"tree is rediscovered with refreshed bounds after the obstruction is removed");checks++;
   level.setBlock(beam.east(),Blocks.OAK_PLANKS.defaultBlockState(),3);
   check(RoadClearing.column(level,ref,beam.getX(),beam.getZ(),new HashMap<>(),"house")==null,"structural planks next to rooted logs prevent clearing");checks++;
   level.setBlock(beam.east(),Blocks.AIR.defaultBlockState(),3);level.setBlock(beam.east(),Blocks.BARREL.defaultBlockState(),3);
   check(RoadClearing.column(level,ref,beam.getX(),beam.getZ(),new HashMap<>(),"private")==null,"nearby container protects tree column");checks++;
   BlockPos hedge=at.offset(24,0,9);
   level.setBlock(hedge,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true),3);
   check(RoadClearing.column(level,ref,hedge.getX(),hedge.getZ(),new HashMap<>(),"hedge")==null,"persistent decorative foliage is protected");checks++;
   BlockPos plant=at.offset(33,0,9);level.setBlock(plant,Blocks.SHORT_GRASS.defaultBlockState(),3);
   var soft=RoadClearing.column(level,ref,plant.getX(),plant.getZ(),new HashMap<>(),"plant");
   check(soft!=null&&soft.next()!=null&&soft.next().removal().is(Blocks.SHORT_GRASS),"wild vegetation becomes an animated removal task");checks++;
   level.setBlock(plant,Blocks.OAK_PLANKS.defaultBlockState(),3);
   check(!Roads.repairable(level,soft.next()),"changed target invalidates removal rather than breaking new construction");checks++;
   return checks;
  } finally {
   if(v!=null){Anim.cancelBreaking(v);v.discard();}
   restore(level,before);
  }
 }
 private static Map<BlockPos,Cell> capture(ServerLevel level,BlockPos at,int y) {
  Map<BlockPos,Cell> before=new LinkedHashMap<>();
  for(int x=at.getX()-2;x<=at.getX()+45;x++)for(int z=at.getZ()-8;z<=at.getZ()+18;z++)
   for(int py=y-4;py<=y+24;py++) capture(level,before,new BlockPos(x,py,z));
  for(BlockPos bell:List.of(at,at.east(80)))for(BlockPos pos:List.of(bell.below(),bell,bell.east(3).below(),bell.east(3)))
   for(int py=pos.getY()-1;py<=pos.getY()+1;py++)capture(level,before,new BlockPos(pos.getX(),py,pos.getZ()));
  return before;
 }
 private static void capture(ServerLevel level,Map<BlockPos,Cell> before,BlockPos pos) {
  if(before.containsKey(pos))return;
  level.getChunkAt(pos);BlockEntity be=level.getBlockEntity(pos);
  before.put(pos,new Cell(level.getBlockState(pos),be==null?null:be.saveWithFullMetadata(level.registryAccess())));
 }
 private static void restore(ServerLevel level,Map<BlockPos,Cell> before) {
  for(BlockPos pos:before.keySet())level.removeBlockEntity(pos);
  before.forEach((pos,cell)->{
   level.setBlock(pos,cell.state(),3);
   level.getPathTypeCache().invalidate(pos);
  });
  before.forEach((pos,cell)->{
   if(cell.nbt()==null)return;
   BlockEntity be=BlockEntity.loadStatic(pos,cell.state(),cell.nbt().copy(),level.registryAccess());
   if(be!=null)level.setBlockEntity(be);
  });
 }
 private record Cell(BlockState state,CompoundTag nbt) { }
 private static void tree(ServerLevel level,BlockPos surface,int height){
  level.setBlock(surface,Blocks.GRASS_BLOCK.defaultBlockState(),3);
  for(int y=1;y<=height;y++)level.setBlock(surface.above(y),Blocks.OAK_LOG.defaultBlockState(),3);
  level.setBlock(surface.above(height).east(),Blocks.OAK_LEAVES.defaultBlockState(),3);
 }
 private static void check(boolean value,String label){if(!value)throw new IllegalStateException("Road clearing: "+label);}
}
