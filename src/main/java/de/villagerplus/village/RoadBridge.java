package de.villagerplus.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only bridge planning; the road worker places each paid log/plank separately. */
public final class RoadBridge {
 private static final int MAX_SUPPORT_DEPTH=24;
 private RoadBridge() { }
 public record Plan(Roads.Repair next) { }

 public static Plan plan(ServerLevel level,BlockPos deck) {
  return plan(level,deck,null,null);
 }

 /** Plans one paid bridge placement while retaining the village protection owners for revalidation. */
 public static Plan plan(ServerLevel level,BlockPos deck,BlockPos ownerBell,BlockPos peerBell) {
  if(!level.hasChunkAt(deck)||!clear(level,deck.above())||!clear(level,deck.above(2)))return null;
  BlockState surface=level.getBlockState(deck);
  if(!(replaceableWater(surface)||surface.is(Blocks.SPRUCE_PLANKS)))return null;
  int depth=0;
  for(int d=1;d<=MAX_SUPPORT_DEPTH;d++) {
   BlockPos p=deck.below(d);if(!level.hasChunkAt(p))return null;
   BlockState s=level.getBlockState(p);
   if(s.hasBlockEntity())return null;
   if(s.is(Blocks.SPRUCE_LOG))continue;
   if(s.getFluidState().isEmpty()&&s.isFaceSturdy(level,p,Direction.UP)){depth=d;break;}
   if(!replaceableWater(s))return null;
  }
  if(depth==0)return null;
  BlockPos pending=null;
  for(int d=depth-1;d>=1;d--) {
   BlockPos p=deck.below(d);BlockState s=level.getBlockState(p);
   if(s.is(Blocks.SPRUCE_LOG))continue;
   if(!replaceableWater(s))return null;
   if(Roads.protectedBridgeCell(level,p,ownerBell,peerBell))return null;
   if(pending==null)pending=p;
  }
  if(!surface.is(Blocks.SPRUCE_PLANKS)&&Roads.protectedBridgeCell(level,deck,ownerBell,peerBell))return null;
  if(pending==null&&surface.is(Blocks.SPRUCE_PLANKS))return new Plan(null);
  BlockPos target=pending==null?deck:pending;
  BlockPos stand=stand(level,target);if(stand==null)return null;
  var task=new Roads.Repair(target,
   (pending==null?Blocks.SPRUCE_PLANKS:Blocks.SPRUCE_LOG).defaultBlockState(),stand,true,
   null,null,null,ownerBell,peerBell);
  return validTask(level,task)?new Plan(task):null;
 }

 public static boolean validTask(ServerLevel level,Roads.Repair task) {
  BlockPos p=task.ground(),stand=task.worksite();
  if(!level.hasChunkAt(p)||!level.hasChunkAt(stand))return false;
  if(!(task.paving().is(Blocks.SPRUCE_LOG)||task.paving().is(Blocks.SPRUCE_PLANKS)))return false;
  BlockState floor=level.getBlockState(p.below());
  if(!replaceableWater(level.getBlockState(p))||floor.hasBlockEntity()||floor.is(Blocks.FARMLAND)
    ||!supported(level,p.below())||Roads.protectedBridgeCell(level,p,task.ownerBell(),task.peerBell()))return false;
  BlockState standFloor=level.getBlockState(stand.below());
  if(standFloor.hasBlockEntity()||standFloor.is(Blocks.FARMLAND)
    ||!clear(level,stand)||!clear(level,stand.above())||!supported(level,stand.below()))return false;
  return Math.abs(p.getX()-stand.getX())<=3&&Math.abs(p.getZ()-stand.getZ())<=3
   &&Math.abs(stand.getY()-p.getY())<=MAX_SUPPORT_DEPTH+1;
 }

 private static BlockPos stand(ServerLevel level,BlockPos target) {
  // Use a nearby natural ledge, either at the pier foot or the bank. The worker
  // never has to stand in the void while the support column grows.
  for(int verticalDistance=0;verticalDistance<=MAX_SUPPORT_DEPTH;verticalDistance++) {
   for(int sign=0;sign<(verticalDistance==0?1:2);sign++) {
    int vertical=verticalDistance==0?0:(sign==0?-verticalDistance:verticalDistance);
    for(int radius=1;radius<=3;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
     if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
     BlockPos p=target.offset(dx,vertical,dz);
     if(!level.hasChunkAt(p)||!level.hasChunkAt(p.below())||!level.hasChunkAt(p.above()))continue;
     BlockState floor=level.getBlockState(p.below());
     if(floor.hasBlockEntity()||floor.is(Blocks.FARMLAND)||!supported(level,p.below())
       ||!clear(level,p)||!clear(level,p.above()))continue;
     return p;
    }
   }
  }
  return null;
 }
 private static boolean replaceableWater(BlockState s){return s.isAir()||s.is(Blocks.WATER);}
 private static boolean clear(ServerLevel level,BlockPos p){return level.getBlockState(p).isAir();}
 private static boolean supported(ServerLevel level,BlockPos p){var s=level.getBlockState(p);return s.getFluidState().isEmpty()&&s.isFaceSturdy(level,p,Direction.UP);}
}
