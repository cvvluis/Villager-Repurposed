package de.villagerplus.village;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Conservative read-only corridor inspection. Original tree bounds persist while its crown is felled. */
public final class RoadClearing {
 private RoadClearing() { }
 public record Column(BlockPos ground,Roads.Repair next) { }

 public static Column column(ServerLevel level,VillageRef ref,int x,int z,Map<String,Long> state,String key) {
  int referenceY=ref==null?level.getMinY():ref.bell().getY();
  if(!level.hasChunkAt(new BlockPos(x,referenceY,z)))return null;
  int rawHeight=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
  // Resolve the actual surface before looking for trunks. Capping at bellY + 20
  // made taller trunks or elevated forest edges look like solid ground.
  int capY=rawHeight;
  BlockPos top=new BlockPos(x,capY,z);
  for(int lowered=0;lowered<4&&soft(level.getBlockState(top));lowered++)top=top.below();
  BlockPos root=state.containsKey(key+":root")?BlockPos.of(state.get(key+":root")):null;
  int peak=state.containsKey(key+":top")?state.get(key+":top").intValue():top.getY();
  if(root==null&&ref!=null) {
   BlockPos[] column=findLogColumn(level,x,z);
   if(column!=null) {
    root=column[0];top=column[1];peak=top.getY();
    state.put(key+":root",root.asLong());state.put(key+":top",(long)peak);
   }
  }
  if(root==null&&level.getBlockState(top).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
   root=top;
   while(root.getY()>top.getY()-32&&level.getBlockState(root.below()).is(BlockTags.OVERWORLD_NATURAL_LOGS))root=root.below();
   if(top.getY()-root.getY()<3||!soil(level.getBlockState(root.below()))||protectedArea(level,root)||!canopy(level,top))return null;
   state.put(key+":root",root.asLong());state.put(key+":top",(long)top.getY());
  }
  if(root!=null) {
   if(root.getX()!=x||root.getZ()!=z||peak<root.getY()||peak-root.getY()>32||protectedArea(level,root))return null;
   // A cached crown bound is evidence for resuming an interrupted tree, not permission
    // to ignore later edits. If new trunk blocks appeared above it, invalidate these
    // bounds so the next route scan rediscovers the current tree instead of paving below it.
   for(int y=peak+1;y<=root.getY()+32;y++) {
    BlockPos above=new BlockPos(x,y,z);
    if(!level.hasChunkAt(above))return null;
    if(level.getBlockState(above).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
     state.remove(key+":root");state.remove(key+":top");
     return null;
    }
   }
   BlockPos ground=root.below();if(!soilOrRoad(level.getBlockState(ground)))return null;
   BlockPos pending=null;
   for(int y=peak;y>=root.getY();y--) {
    BlockPos p=new BlockPos(x,y,z);BlockState s=level.getBlockState(p);
    if(s.isAir())continue;
    if(!s.is(BlockTags.OVERWORLD_NATURAL_LOGS)||s.hasBlockEntity()
     ||(s.hasProperty(RotatedPillarBlock.AXIS)&&s.getValue(RotatedPillarBlock.AXIS)!=Direction.Axis.Y))return null;
    if(pending==null)pending=p;
   }
   if(pending!=null) {
    if(!level.getBlockState(root).is(BlockTags.OVERWORLD_NATURAL_LOGS))return null;
    BlockPos stand=stand(level,ground);
    if(stand==null)stand=standBesideColumn(level,root,pending);
    if(stand==null)return null;
    return new Column(ground,new Roads.Repair(pending,Blocks.AIR.defaultBlockState(),stand,false,level.getBlockState(pending),root));
   }
   top=ground;
  }
  for(int dy=1;dy<=3;dy++) {
   BlockPos p=top.above(dy);BlockState s=level.getBlockState(p);
   if(s.isAir())continue;
   if(!soft(s)||protectedArea(level,top.above()))return null;
   BlockPos stand=stand(level,top);if(stand==null)return null;
   return new Column(top,new Roads.Repair(p,Blocks.AIR.defaultBlockState(),stand,false,s,null));
  }
  return new Column(top,null);
 }

 public static boolean validTask(ServerLevel level,Roads.Repair task) {
  BlockPos p=task.ground(),stand=task.worksite();
  if(!level.hasChunkAt(p)||!level.hasChunkAt(stand)||!level.getBlockState(p).equals(task.removal()))return false;
  if(!safeStand(level,stand))return false;
  if(task.treeRoot()!=null) {
   BlockPos root=task.treeRoot();
   return p.getX()==root.getX()&&p.getZ()==root.getZ()&&p.getY()>=root.getY()&&p.getY()-root.getY()<=32
    &&level.getBlockState(root).is(BlockTags.OVERWORLD_NATURAL_LOGS)&&soil(level.getBlockState(root.below()))&&!protectedArea(level,root);
  }
  return soft(task.removal())&&!protectedArea(level,p);
 }

 private static boolean soil(BlockState s){return (s.is(BlockTags.DIRT)||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.MYCELIUM)||s.is(Blocks.PODZOL)||s.is(Blocks.COARSE_DIRT))&&!s.is(Blocks.FARMLAND);}
 private static boolean soilOrRoad(BlockState s){return soil(s)||s.is(Blocks.COBBLESTONE)||s.is(Blocks.GRAVEL)||s.is(Blocks.DIRT_PATH)||s.is(Blocks.STONE);}
 private static boolean soft(BlockState s){
  if(s.hasBlockEntity()||!s.getFluidState().isEmpty())return false;
  if(s.is(BlockTags.LEAVES))return s.hasProperty(LeavesBlock.PERSISTENT)&&!s.getValue(LeavesBlock.PERSISTENT);
  return s.is(BlockTags.FLOWERS)||s.is(Blocks.SHORT_GRASS)||s.is(Blocks.TALL_GRASS)||s.is(Blocks.FERN)||s.is(Blocks.LARGE_FERN)||s.is(Blocks.SNOW)||s.is(Blocks.VINE)||s.is(Blocks.DEAD_BUSH);
 }
 private static boolean canopy(ServerLevel level,BlockPos top){
  for(BlockPos p:BlockPos.betweenClosed(top.offset(-3,-2,-3),top.offset(3,4,3)))
   if(level.hasChunkAt(p)&&level.getBlockState(p).is(BlockTags.LEAVES)&&soft(level.getBlockState(p)))return true;
  return false;
 }
 private static BlockPos[] findLogColumn(ServerLevel level,int x,int z) {
  int maxY=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
  int minY=Math.max(level.getMinY()+1,maxY-32);
  for(int y=minY;y<=maxY;y++) {
   BlockPos seed=new BlockPos(x,y,z);
   if(!level.getBlockState(seed).is(BlockTags.OVERWORLD_NATURAL_LOGS))continue;
   BlockPos low=seed,high=seed;
   while(low.getY()>minY-32&&level.getBlockState(low.below()).is(BlockTags.OVERWORLD_NATURAL_LOGS))low=low.below();
   while(high.getY()<maxY+32&&level.getBlockState(high.above()).is(BlockTags.OVERWORLD_NATURAL_LOGS))high=high.above();
   if(high.getY()-low.getY()<2||!soilOrRoad(level.getBlockState(low.below()))||protectedArea(level,low)||!canopy(level,high))continue;
   return new BlockPos[]{low,high};
  }
  return null;
 }
 private static boolean protectedArea(ServerLevel level,BlockPos root){
  for(BlockPos p:BlockPos.betweenClosed(root.offset(-2,-1,-2),root.offset(2,32,2))) {
   if(!level.hasChunkAt(p))return true;
   BlockState s=level.getBlockState(p);Block b=s.getBlock();
   if(s.hasBlockEntity()||s.is(Blocks.FARMLAND)||s.is(BlockTags.PLANKS)||b instanceof DoorBlock||b instanceof FenceBlock||b instanceof StairBlock||b instanceof SlabBlock||b instanceof BedBlock)return true;
  }
  return false;
 }
 private static BlockPos stand(ServerLevel level,BlockPos ground){
  for(int r=0;r<=5;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
   if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
   BlockPos p=ground.offset(dx,1,dz);if(level.hasChunkAt(p)&&safeStand(level,p))return p;
  }
  return null;
 }
 /** Feet beside the trunk when corridor turf leaves no air one block above soil. */
 private static BlockPos standBesideColumn(ServerLevel level,BlockPos root,BlockPos pending){
  int minY=root.getY(),maxY=pending.getY();
  for(int r=0;r<=5;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
   if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
   if(dx==0&&dz==0)continue;
   for(int y=maxY;y>=minY;y--) {
    BlockPos p=new BlockPos(root.getX()+dx,y,root.getZ()+dz);
    if(level.hasChunkAt(p)&&safeStand(level,p))return p;
   }
  }
  return null;
 }
 private static boolean safeStand(ServerLevel level,BlockPos p){return level.getBlockState(p).isAir()&&level.getBlockState(p.above()).isAir()
  &&level.getBlockState(p.below()).getFluidState().isEmpty()&&level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP);}

 public static String diagnostic(ServerLevel level,BlockPos root,BlockPos top) {
  BlockPos ground=root.below();
  BlockPos stand=stand(level,ground);
  if(stand==null)stand=standBesideColumn(level,root,top);
  return "soil="+soil(level.getBlockState(ground))+",canopy="+canopy(level,top)+",protected="+protectedArea(level,root)+",stand="+stand;
 }
}
