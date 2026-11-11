package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Built;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.Network;
import de.villagerplus.village.Roads;
import de.villagerplus.village.TradeNetwork;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;

/** Material-paid road-lighting checks. The fixture snapshots every touched block and block entity. */
public final class RoadLightingChecks {
 private RoadLightingChecks() { }

 public static int run(ServerLevel level,BlockPos at) {
  BlockPos other=at.east(200);
  Map<BlockPos,Cell> before=snapshot(level,touched(at));
  var v=EntityTypes.VILLAGER.spawn(level,at.south(2),EntitySpawnReason.COMMAND);
  v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
  VillageRef ref=null;
  List<ZonePolicy.Zone> oldZones=null;
  List<Built> oldBuildings=null;
  Map<String,Long> oldRoads=null;
  TradeNetwork oldNetwork=null;
  try {
   ref=Checks090.fixtureForEquipment(level,at);
   var box=ref.chest(level);box.clearContent();
   for(int cursor:new int[]{16,32,48,64,80,96,112})for(int side:new int[]{-2,2})
	   loadSpacingRegion(level,at.offset(cursor,2,side),Lighting.ROAD_LAMP_SPACING);
   oldZones=ref.bellEntity().getAttached(VillagerPlus.ZONES);
   oldBuildings=ref.bellEntity().getAttached(VillagerPlus.BUILDINGS);
   oldRoads=ref.bellEntity().getAttached(VillagerPlus.ROADS);
   oldNetwork=ref.bellEntity().getAttached(VillagerPlus.NETWORK);
   if(level.getBlockEntity(ref.chestPos(level))!=null)level.getBlockEntity(ref.chestPos(level)).removeAttached(VillagerPlus.OWNER);
   String key=Network.dim(level)+other.asLong();
   Network.save(ref.bellEntity(),new TradeNetwork(List.of(),List.of(new TradeNetwork.Link(Network.dim(level),other,"Road lighting check",30,true,8,0)),Map.of(),0,0));
	   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.of(key,120L,key+":start",0L));
   // A saved paid legacy route is the canonical straight X-then-Z line for these endpoints.
	   for(int x=0;x<120;x++)for(int side=-1;side<=1;side++) {
    BlockPos p=at.offset(x,-1,side);level.getChunkAt(p);
    if(p.above().equals(at)||p.above().equals(ref.chestPos(level)))continue;
    level.setBlock(p.below(),Blocks.STONE.defaultBlockState(),3);
    level.setBlock(p,(side==0?Blocks.GRAVEL:Blocks.COBBLESTONE).defaultBlockState(),3);
    if(!p.above().equals(at))level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);
   }
	   for(int x=0;x<120;x++)for(int side:new int[]{-2,2}) {
    BlockPos ground=at.offset(x,-1,side);level.getChunkAt(ground);
    level.setBlock(ground.below(),Blocks.STONE.defaultBlockState(),3);
    level.setBlock(ground,Blocks.GRASS_BLOCK.defaultBlockState(),3);
    for(int dy=1;dy<=3;dy++)level.setBlock(ground.above(dy),Blocks.AIR.defaultBlockState(),3);
   }
   // Center the registered footprint over the route so its radius covers both roadside candidates.
   BlockPos lampGround=at.offset(16,-1,0);
   int checks=0;
   ref.bellEntity().setAttached(VillagerPlus.ZONES,List.of(new ZonePolicy.Zone("lamp-zone","farm",
    16+at.getX(),at.getZ()-2,16+at.getX(),at.getZ()+2)));
   var zoned=Roads.nextRepair(level,ref);
   check(zoned!=null&&zoned.ground().equals(at.offset(32,0,2)),
    "a protected zone excludes both roadside lamp sites before the next interval is chosen");checks++;
   ref.bellEntity().setAttached(VillagerPlus.ZONES,List.of());
   ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,List.of(new Built(Blueprints.HOUSE,lampGround,0,0,List.of())));
   var built=Roads.nextRepair(level,ref);
   check(built!=null&&built.ground().equals(at.offset(32,0,2)),
    "a registered building footprint excludes roadside lamp placement");checks++;
   ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,List.of());
   BlockPos nearbyLight=at.offset(5,2,2);level.setBlock(nearbyLight,Blocks.LANTERN.defaultBlockState(),3);
   var spaced=Roads.nextRepair(level,ref);
	   String spacingTrace=" [task="+(spaced==null?"null":spaced.ground().toShortString())+"; expected="+at.offset(96,0,2).toShortString()+"]";
	   check(spaced!=null&&spaced.ground().equals(at.offset(96,0,2)),
	    "a nearby existing light inside the 80-block road-lamp spacing is skipped on the stored route"+spacingTrace);checks++;
   level.setBlock(nearbyLight,Blocks.AIR.defaultBlockState(),3);
   var st=new JobState();var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);long now=level.getGameTime()+1000;
    Ctx c=new Ctx(level,v,st,mem,ref,now);
    var candidate=Roads.nextRepair(level,ref);
    check(candidate!=null&&candidate.ground().equals(at.offset(16,0,2))
      &&candidate.worksite().equals(at.offset(16,0,1)),
     "lamp post is scheduled off the three-wide route with a reachable worksite on its edge");checks++;
    // The candidate at route index 16 remains an unpaid worker task, never a Roads/tick placement.
    BlockPos first=at.offset(16,0,2);
    check(level.getBlockState(first).isAir(),"stable completed route has no free remote torch");checks++;
    level.setBlock(ref.chestPos(level).below(),Blocks.STONE.defaultBlockState(),3);
    box.setItem(0,new net.minecraft.world.item.ItemStack(Items.SPRUCE_LOG,4));
    box.setItem(1,new net.minecraft.world.item.ItemStack(Items.LANTERN,2));
    check(JobUtil.count(box,s->s.is(Items.LANTERN))==2,"shared road material storage accepts lantern stock");checks++;
    v.teleportTo(ref.chestPos(level).getX()+.5,ref.chestPos(level).getY(),ref.chestPos(level).getZ()+1.5);
    st.roadScanAt=0;
    check(RoadBuilderJob.maintain(c)&&box.getItem(0).getCount()==3&&JobUtil.count(v.getInventory(),s->s.is(Items.SPRUCE_LOG))==1,
     "worker collects exactly one real post log from shared material storage");checks++;
    Roads.tick(level,ref,now+500);
    check(level.getBlockState(first).isAir()&&JobUtil.count(v.getInventory(),s->s.is(Items.SPRUCE_LOG))==1,
     "road tick never places a carried lamp post remotely");checks++;
    // Drop volatile task memory between pickup and placement; persisted route work is rediscovered.
    st=new JobState();c=new Ctx(level,v,st,mem,ref,now+1);
    check(RoadBuilderJob.maintain(c)&&level.getBlockState(first).isAir()&&JobUtil.count(v.getInventory(),s->s.is(Items.SPRUCE_LOG))==1,
     "interrupted worker keeps cargo and rediscovers the same lamp target");checks++;
    v.teleportTo(candidate.worksite().getX()+.5,candidate.worksite().getY(),candidate.worksite().getZ()+.5);
    check(RoadBuilderJob.maintain(c)&&level.getBlockState(first).is(Blocks.SPRUCE_LOG)
      &&JobUtil.count(v.getInventory(),s->s.is(Items.SPRUCE_LOG))==0&&box.getItem(0).getCount()==3
      &&level.getBlockState(at.offset(16,-1,1)).is(Blocks.COBBLESTONE),
     "first paid log post is placed only after arrival beside the road without occupying its paving");checks++;
    var resumed=Roads.nextRepair(level,ref);
    check(resumed!=null&&resumed.ground().equals(first.above()),
     "an interrupted partial post resumes in place instead of scheduling a nearby duplicate");checks++;
    String firstDone="light:"+key+":16:1";
    var persisted=ref.bellEntity().getAttached(VillagerPlus.ROADS);
    check(persisted.getOrDefault(firstDone,0L)==1L,"completed lamp cursor is persisted separately from the road cursor");checks++;
    CompoundTag bellNbt=ref.bellEntity().saveWithFullMetadata(level.registryAccess());
    BlockEntity loaded=BlockEntity.loadStatic(at,level.getBlockState(at),bellNbt.copy(),level.registryAccess());
    check(loaded!=null&&loaded.getAttachedOrElse(VillagerPlus.ROADS,Map.of()).getOrDefault(firstDone,0L)==1L,
     "saved bell NBT round-trip retains lamp completion after restart");checks++;
    for(int stage=2;stage<=3;stage++) {
     st=new JobState();c=new Ctx(level,v,st,mem,ref,now+stage);
     BlockPos target=first.above(stage-1);
     v.teleportTo(ref.chestPos(level).getX()+.5,ref.chestPos(level).getY(),ref.chestPos(level).getZ()+1.5);
     boolean collected=RoadBuilderJob.maintain(c);
     Item expected=stage==2?Items.SPRUCE_LOG:Items.LANTERN;
     check(collected&&JobUtil.count(v.getInventory(),s->s.is(expected))==1,
      "lamp-post stage "+stage+" collects its matching physical material");checks++;
     if(stage==3) {
      Roads.Repair finalLamp=st.roadRepair;
      BlockPos laterLight=at.offset(32,2,2);
      level.setBlock(laterLight,Blocks.LANTERN.defaultBlockState(),3);
      v.teleportTo(candidate.worksite().getX()+.5,candidate.worksite().getY(),candidate.worksite().getZ()+.5);
      check(finalLamp!=null&&finalLamp.ground().equals(target)
        &&!RoadBuilderJob.repair(c,finalLamp)&&level.getBlockState(target).isAir()
        &&JobUtil.count(v.getInventory(),stack->stack.is(Items.LANTERN))==1,
       "a nearby light added during travel cancels the stale final-lantern target without consuming cargo");checks++;
      level.setBlock(laterLight,Blocks.AIR.defaultBlockState(),3);
      check(RoadBuilderJob.repair(c,finalLamp)&&level.getBlockState(target).is(Blocks.LANTERN)
        &&JobUtil.count(v.getInventory(),stack->stack.is(Items.LANTERN))==0,
       "the final lantern is placed after a fresh spacing check when its site is valid");checks++;
     } else {
      v.teleportTo(candidate.worksite().getX()+.5,candidate.worksite().getY(),candidate.worksite().getZ()+.5);
      boolean placed=RoadBuilderJob.maintain(c);
      check(placed&&level.getBlockState(target).is(Blocks.SPRUCE_LOG),
       "each lamp post stage is placed only at the worksite");checks++;
     }
    }
	   check(!Lighting.spaced(level,at.offset(32,2,2),at.offset(32,0,2)),
	    "the candidate at cursor 32 is rejected only 16 blocks from the completed post");checks++;
	   check(!Lighting.spaced(level,at.offset(48,2,2),at.offset(48,0,2)),
	    "the candidate at cursor 48 remains too close at 32 blocks");checks++;
	   check(!Lighting.spaced(level,at.offset(80,2,2),at.offset(80,0,2)),
	    "a road-lamp candidate is still rejected at 64 blocks from the completed post");checks++;
	   check(!Lighting.spaced(level,at.offset(88,2,2),at.offset(88,0,2)),
	    "a road-lamp candidate remains rejected at 72 blocks from the completed post");checks++;
	   check(Lighting.spaced(level,at.offset(96,2,2),at.offset(96,0,2)),
	    "a road-lamp candidate is accepted at the exact 80-block spacing boundary");checks++;
	   check(Lighting.spaced(level,at.offset(112,2,2),at.offset(112,0,2)),
	    "a road-lamp candidate is accepted once it is 96 blocks from the completed post");checks++;
    var secondTask=Roads.nextRepair(level,ref);
	   check(secondTask!=null&&secondTask.ground().equals(at.offset(96,0,2)),
	    "the 16- through 64-block candidates are skipped before the next lamp at 80 route blocks");checks++;
    check(level.getBlockState(first).is(Blocks.SPRUCE_LOG)&&level.getBlockState(first.above()).is(Blocks.SPRUCE_LOG)
      &&level.getBlockState(first.above(2)).is(Blocks.LANTERN)&&box.getItem(0).getCount()==2&&box.getItem(1).getCount()==1,
     "log/log/lantern structure consumes two logs and one lantern");checks++;
	   check(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()).getOrDefault(key,0L)==120L,
     "lighting never changes the saved road construction cursor");checks++;
    return checks;
  } finally {
   v.discard();
   if(ref!=null) {
    if(oldZones==null)ref.bellEntity().removeAttached(VillagerPlus.ZONES);else ref.bellEntity().setAttached(VillagerPlus.ZONES,oldZones);
    if(oldBuildings==null)ref.bellEntity().removeAttached(VillagerPlus.BUILDINGS);else ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,oldBuildings);
    if(oldRoads==null)ref.bellEntity().removeAttached(VillagerPlus.ROADS);else ref.bellEntity().setAttached(VillagerPlus.ROADS,oldRoads);
    if(oldNetwork==null)ref.bellEntity().removeAttached(VillagerPlus.NETWORK);else ref.bellEntity().setAttached(VillagerPlus.NETWORK,oldNetwork);
    ref.dirty();
   }
   restore(level,before);
  }
 }

 private static List<BlockPos> touched(BlockPos at) {
  List<BlockPos> out=new ArrayList<>();
	  for(int x=0;x<=120;x++)for(int side=-2;side<=2;side++)for(int dy=-2;dy<=4;dy++)out.add(at.offset(x,dy,side).immutable());
  for(int dx=2;dx<=4;dx++)for(int dy=-1;dy<=1;dy++)out.add(at.offset(dx,dy,0).immutable());
  for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++)out.add(at.offset(dx,dy,dz).immutable());
  return out;
 }

 private static void loadSpacingRegion(ServerLevel level,BlockPos center,int radius) {
  int minChunkX=Math.floorDiv(center.getX()-radius,16),maxChunkX=Math.floorDiv(center.getX()+radius,16);
  int minChunkZ=Math.floorDiv(center.getZ()-radius,16),maxChunkZ=Math.floorDiv(center.getZ()+radius,16);
  long radiusSquared=(long)radius*radius;
  for(int chunkX=minChunkX;chunkX<=maxChunkX;chunkX++) {
   int minX=chunkX<<4;long dx=(long)Math.max(minX,Math.min(minX+15,center.getX()))-center.getX();
   for(int chunkZ=minChunkZ;chunkZ<=maxChunkZ;chunkZ++) {
    int minZ=chunkZ<<4;long dz=(long)Math.max(minZ,Math.min(minZ+15,center.getZ()))-center.getZ();
    if(dx*dx+dz*dz>=radiusSquared)continue;
    level.getChunkAt(new BlockPos(minX+8,center.getY(),minZ+8));
   }
  }
 }

 private static Map<BlockPos,Cell> snapshot(ServerLevel level,List<BlockPos> positions) {
  Map<BlockPos,Cell> before=new HashMap<>();
  for(BlockPos p:positions) {
   level.getChunkAt(p);BlockEntity be=level.getBlockEntity(p);
   before.put(p, new Cell(level.getBlockState(p),be==null?null:be.saveWithFullMetadata(level.registryAccess())));
  }
  return before;
 }

 private static void restore(ServerLevel level,Map<BlockPos,Cell> before) {
  for(BlockPos p:before.keySet())level.removeBlockEntity(p);
  before.forEach((p,c)->level.setBlock(p,c.state(),3));
  before.forEach((p,c)->{
   if(c.nbt()==null)return;
   BlockEntity be=BlockEntity.loadStatic(p,c.state(),c.nbt().copy(),level.registryAccess());
   if(be!=null)level.setBlockEntity(be);
  });
 }

 private record Cell(BlockState state,CompoundTag nbt) { }
 private static void check(boolean ok,String message) {if(!ok)throw new IllegalStateException("Road lighting: "+message);}
}
