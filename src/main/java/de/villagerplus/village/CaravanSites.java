package de.villagerplus.village;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Loaded-chunk-only placement for the whole caravan, including the golem's width and height. */
public final class CaravanSites {
 private CaravanSites() { }
 public record Group(BlockPos trader,BlockPos guard,List<BlockPos> donkeys) { }

 public static Group plan(ServerLevel level,BlockPos at) {
  var trader=de.villagerplus.entity.ModEntities.CARAVAN_TRADER.create(level,EntitySpawnReason.EVENT);
  var guard=EntityTypes.IRON_GOLEM.create(level,EntitySpawnReason.EVENT);
  var donkey=EntityTypes.DONKEY.create(level,EntitySpawnReason.EVENT);
  if(trader==null||guard==null||donkey==null)return null;
  List<AABB> reserved=new ArrayList<>();
  if(!canStand(level,trader,at,reserved))return null;
  reserved.add(bounds(trader,at));
  BlockPos g=find(level,guard,at.offset(2,0,-1),at.getY(),reserved);
  if(g==null)return null;reserved.add(bounds(guard,g));
  List<BlockPos> animals=new ArrayList<>();
  for(int side:new int[]{-1,1}) {
   BlockPos p=find(level,donkey,at.offset(side,0,1),at.getY(),reserved);
   if(p==null)return null;animals.add(p);reserved.add(bounds(donkey,p));
  }
  return new Group(at,g,List.copyOf(animals));
 }

 private static BlockPos find(ServerLevel level,Entity entity,BlockPos preferred,int y,List<AABB> reserved) {
  for(int radius=0;radius<=4;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
   if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
   BlockPos column=preferred.offset(dx,0,dz);if(!level.hasChunkAt(column))continue;
   BlockPos p=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column);
   if(Math.abs(p.getY()-y)<=2&&canStand(level,entity,p,reserved))return p;
  }
  return null;
 }

 public static boolean canStand(ServerLevel level,Entity entity,BlockPos feet,List<AABB> reserved) {
  AABB box=bounds(entity,feet);
  int minX=(int)Math.floor(box.minX),maxX=(int)Math.floor(box.maxX-1e-7);
  int minZ=(int)Math.floor(box.minZ),maxZ=(int)Math.floor(box.maxZ-1e-7);
  for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++) {
   BlockPos floor=new BlockPos(x,feet.getY()-1,z);
   if(!level.hasChunkAt(floor))return false;
   var state=level.getBlockState(floor);
   if(!state.getFluidState().isEmpty()||!state.isFaceSturdy(level,floor,Direction.UP)
    ||state.is(Blocks.MAGMA_BLOCK)||state.is(Blocks.CACTUS)||state.is(Blocks.CAMPFIRE)||state.is(Blocks.SOUL_CAMPFIRE))return false;
   for(int y=feet.getY();y<Math.ceil(box.maxY);y++)
    if(!level.getBlockState(new BlockPos(x,y,z)).getFluidState().isEmpty())return false;
  }
  return reserved.stream().noneMatch(box::intersects)&&level.noCollision(entity,box);
 }

 /** Keeps the harmless, armed guard beside the golem's exact navigation target. */
 public static BlockPos armedGuardSpot(BlockPos post) {
  return post==null?null:post.east();
 }

 /** Validates the visible guard's own footprint and keeps it outside the camp layout. */
 public static boolean canStandArmedGuard(ServerLevel level,Entity guard,BlockPos post,Iterable<BlockPos> campCells) {
  BlockPos spot=armedGuardSpot(post);
  if(spot==null||!level.hasChunkAt(spot))return false;
  AABB guardBox=bounds(guard,spot);
  for(BlockPos campCell:campCells)if(guardBox.intersects(new AABB(campCell)))return false;
  return canStand(level,guard,spot,List.of());
 }

 public static AABB bounds(Entity entity,BlockPos feet) {
  return entity.getBoundingBox().move(Vec3.atBottomCenterOf(feet).subtract(entity.position()));
 }

 public static boolean campCellFree(ServerLevel level,BlockPos p) {
  return level.hasChunkAt(p)&&!level.getBlockState(p).hasBlockEntity()
   &&level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(p),e->e.isAlive()).isEmpty();
 }

 /** Full clear 7x7 pad plus all requested placement cells; never clears plants/structures or loads remote chunks. */
 public static boolean canPlaceCampAt(ServerLevel level, BlockPos center, Iterable<BlockPos> placements) {
  for (int x=-3;x<=3;x++) for (int z=-3;z<=3;z++) {
   BlockPos floor=center.offset(x,-1,z);
   if (!level.hasChunkAt(floor)) return false;
   var ground=level.getBlockState(floor);
   if (!ground.getFluidState().isEmpty() || !ground.isFaceSturdy(level,floor,Direction.UP)) return false;
   BlockPos surface=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,floor);
   if (surface.getY()!=center.getY()) return false;
  }
  for (BlockPos pos:placements) {
   if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir() || !campCellFree(level,pos)) return false;
  }
  return true;
 }
}
