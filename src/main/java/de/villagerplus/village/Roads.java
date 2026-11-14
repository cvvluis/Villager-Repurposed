package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.Config;
import de.villagerplus.job.Lighting;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Wege. Im Dorf: gerade, drei Blöcke breite Pfade (Gebäude, Felder, Wasser und ungeladene Chunks bleiben unberührt).
 * Zwischen Dörfern ab Beziehung +30 (oder aktiver Route): Überlandstraße. Beide Dörfer bauen sich von ihrem Ende bis zur
 * Mitte entgegen: Korridor roden (Blätter, Gras, Stämme natürlicher Bäume), Kiesmitte mit Pflasterrand, bezahlte
 * Holzbrücken über Wasser und begrenzte trockene Schluchten. Beleuchtung wird separat durch den RoadBuilderJob gesetzt.
 */
public final class Roads {
 public static final int TRADE_PARTNER = 30;

 /** A neighbour link carries road work: an explicit route, or a relation of at least {@link #TRADE_PARTNER} (trade partner). */
 public static boolean roadLinkActive(TradeNetwork.Link link) {
  return link != null && (link.route() || link.relation() >= TRADE_PARTNER);
 }
 public static final int LANTERN_EVERY = 16;
 static final int ROAD_HALF_WIDTH = 1;
 private static final long ROUTE_GEOMETRY_VERSION = 2; // rail cursor schema; route path schema lives in RoadRoute
 private static final int MAX_SAVED_PATH_GAP = 8;
 private static final int MAX_CROSSFALL = 2;
 private static final int MAX_PROFILE_ADJUSTMENT = 2;
 private static final int EXPANSION_COLUMNS_PER_SCAN = 48;
 // A safe natural tree may be up to 32 blocks tall, with its crown extending four blocks above its trunk.
 private static final int EXPANSION_SURFACE_SCAN_HEIGHT = 37;
 private static final long EXPANSION_RETRY_TICKS = 1200L;
 public static final long EXPANSION_GEOMETRY_VERSION = 3L;
 static final String EXPANSION = "vp_expansion:";
 private Roads() {}
 public enum WorkAction { PAVE, CLEAR, GAP_CLEAR, BRIDGE, LIGHTING, GRADE_CUT, GRADE_FILL, GRADE_PAVE, EXPANSION_CLEAR, EXPANSION_GRADE_CUT }
 public record Repair(BlockPos ground, BlockState paving, BlockPos worksite, boolean bridge, BlockState removal,
                      BlockPos treeRoot, String completionKey, BlockPos ownerBell, BlockPos peerBell,
                      WorkAction action, String gradeKey, int gradeCursor, int gradeY) {
  public Repair(BlockPos ground,BlockState paving) {this(ground,paving,ground.above(),false,null,null,null,null,null,WorkAction.PAVE,null,-1,Integer.MIN_VALUE);}
  public Repair(BlockPos ground,BlockState paving,BlockPos worksite,boolean bridge) {this(ground,paving,worksite,bridge,null,null,null,null,null,bridge?WorkAction.BRIDGE:WorkAction.PAVE,null,-1,Integer.MIN_VALUE);}
  public Repair(BlockPos ground,BlockState paving,BlockPos worksite,boolean bridge,BlockState removal,BlockPos treeRoot) {this(ground,paving,worksite,bridge,removal,treeRoot,null,null,null,removal==null?(bridge?WorkAction.BRIDGE:WorkAction.PAVE):WorkAction.CLEAR,null,-1,Integer.MIN_VALUE);}
  public Repair(BlockPos ground,BlockState paving,BlockPos worksite,boolean bridge,BlockState removal,BlockPos treeRoot,String completionKey) {this(ground,paving,worksite,bridge,removal,treeRoot,completionKey,null,null,completionKey!=null?WorkAction.LIGHTING:removal!=null?WorkAction.CLEAR:bridge?WorkAction.BRIDGE:WorkAction.PAVE,null,-1,Integer.MIN_VALUE);}
  public Repair(BlockPos ground,BlockState paving,BlockPos worksite,boolean bridge,BlockState removal,
                BlockPos treeRoot,String completionKey,BlockPos ownerBell,BlockPos peerBell) {
   this(ground,paving,worksite,bridge,removal,treeRoot,completionKey,ownerBell,peerBell,
    completionKey!=null?WorkAction.LIGHTING:removal!=null?WorkAction.CLEAR:bridge?WorkAction.BRIDGE:WorkAction.PAVE,null,-1,Integer.MIN_VALUE);
  }
 }

 /** Ensures stored route geometry for checks and shared cursors. */
 public static void syncRouteGeometry(ServerLevel level,BlockPos from,BlockPos to,Map<String,Long> state,String key,
  Map<String,Long> peerState,String peerKey) {
  RoadRoute.ensureWithRetry(level,from,to,state,key,peerState,peerKey,level.getGameTime(),false);
 }

 /** Horizontal lane cell for a construction/repair cursor and side (-1..1). */
 public static BlockPos laneCell(Map<String,Long> state,String key,BlockPos from,BlockPos to,int cursor,int side) {
  BlockPos route=RoadRoute.point(state,key,from,to,cursor);
  boolean alongX=RoadRoute.alongX(state,key,from,to,cursor);
  return new BlockPos(route.getX()+(alongX?0:side),route.getY(),route.getZ()+(alongX?side:0));
 }

 /** The ready pad is available to the house planner; incomplete pads are never advertised as build sites. */
 public static java.util.Optional<Development.ExpansionParcel> preparedExpansionParcel(VillageRef ref) {
  if(ref==null)return java.util.Optional.empty();
  Map<String,Long> state=ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
  if(state.getOrDefault(EXPANSION+"geometry",0L)!=EXPANSION_GEOMETRY_VERSION
    ||state.getOrDefault(EXPANSION+"ready",0L)!=1L)return java.util.Optional.empty();
  Long x=state.get(EXPANSION+"x"),z=state.get(EXPANSION+"z"),y=state.get(EXPANSION+"surface");
  if(x==null||z==null||y==null)return java.util.Optional.empty();
  return java.util.Optional.of(new Development.ExpansionParcel(new BlockPos(x.intValue(),y.intValue(),z.intValue()),
   y.intValue(),Development.EXPANSION_PARCEL_HALF));
 }

 /** Only resumable preparation outside active projects uses the shorter road-builder rescan interval. */
 public static boolean expansionPreparationActive(VillageRef ref) {
  if(ref==null)return false;
  Map<String,Long> state=ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
  return expansionPreparationActive(state,ref.data().hasPlan()||ref.data().hasBuildProjects());
 }

 static boolean expansionPreparationActive(Map<String,Long> state,boolean hasActiveBuild) {
  return !hasActiveBuild&&state!=null&&state.getOrDefault(EXPANSION+"geometry",0L)==EXPANSION_GEOMETRY_VERSION
   &&state.containsKey(EXPANSION+"x")&&state.getOrDefault(EXPANSION+"ready",0L)==0L;
 }

 /** Start one persisted natural pad only when additional beds are genuinely needed and no project owns the village. */
 private static Repair nextExpansionPreparation(ServerLevel level,VillageRef ref,Map<String,Long> state) {
  long beds=level.getPoiManager().getCountInRange(h->h.is(PoiTypes.HOME),ref.bell(),Config.VILLAGE_RADIUS,PoiManager.Occupancy.ANY);
  int population=Villages.residents(level,ref,Config.VILLAGE_RADIUS,v->!Guests.isGuest(v)).size();
  boolean activeBuild=ref.data().hasPlan()||ref.data().hasBuildProjects();
  if(!Development.needsExpansionPreparation(beds,population,activeBuild)||activeBuild)return null;

  if(state.getOrDefault(EXPANSION+"geometry",0L)!=EXPANSION_GEOMETRY_VERSION) {
   state.keySet().removeIf(key->key.startsWith(EXPANSION));
   state.put(EXPANSION+"geometry",EXPANSION_GEOMETRY_VERSION);
  }

  long preparedBeds=state.getOrDefault(EXPANSION+"prepared_beds",-1L);
  if(state.getOrDefault(EXPANSION+"ready",0L)==1L) {
   long preparedHouses=state.getOrDefault(EXPANSION+"prepared_houses",(long)ref.data().houses);
   if(!expansionHousingProgressed(preparedBeds,preparedHouses,beds,ref.data().houses,
     expansionParcelContainsHome(level,state)))return null;
   consumePreparedExpansionParcel(state);
  }
  if(!state.containsKey(EXPANSION+"x")) {
   if(level.getGameTime()<state.getOrDefault(EXPANSION+"retry",0L))return null;
   List<BlockPos> homes=level.getPoiManager().getInRange(h->h.is(PoiTypes.HOME),ref.bell(),Config.VILLAGE_RADIUS,
    PoiManager.Occupancy.ANY).map(p->p.getPos().immutable()).toList();
   int outerHomeRadius=0;
   for(BlockPos home:homes) {
    long dx=(long)home.getX()-ref.bell().getX(),dz=(long)home.getZ()-ref.bell().getZ();
    outerHomeRadius=Math.max(outerHomeRadius,(int)Math.floor(Math.sqrt(dx*dx+dz*dz)));
   }
   long parcelOrdinal=expansionSequence(state);
   int sideCount=Development.expansionParcelSideCount(parcelOrdinal,Development.EXPANSION_PARCEL_HALF,
    Development.EXPANSION_PARCEL_MAX_RADIUS);
   if(sideCount<=0)return null;
   int defaultSide=(int)Math.floorMod(Math.max(0,ref.data().houses),sideCount);
   int startSide=Math.floorMod(state.getOrDefault(EXPANSION+"side",(long)defaultSide).intValue(),sideCount);
   for(int attempt=0;attempt<Math.min(4,sideCount);attempt++) {
    int side=(startSide+attempt)%sideCount;
    BlockPos center=Development.expansionParcelCenter(ref.bell(),Math.max(homes.size(),ref.data().houses),outerHomeRadius,
     parcelOrdinal,side,Development.EXPANSION_PARCEL_HALF,Development.EXPANSION_PARCEL_MAX_RADIUS);
    if(center==null||!expansionParcelSuitable(level,ref,center))continue;
    state.put(EXPANSION+"x",(long)center.getX());state.put(EXPANSION+"z",(long)center.getZ());
    state.put(EXPANSION+"surface",(long)ref.bell().getY());
    state.put(EXPANSION+"survey_low",(long)Integer.MAX_VALUE);
    state.put(EXPANSION+"survey_high",(long)Integer.MIN_VALUE);state.put(EXPANSION+"phase",0L);
    state.put(EXPANSION+"cursor",0L);state.put(EXPANSION+"ready",0L);state.put(EXPANSION+"request_beds",beds);
    state.put(EXPANSION+"sequence",parcelOrdinal);state.put(EXPANSION+"side",(long)((side+1)%sideCount));state.remove(EXPANSION+"retry");
    break;
   }
   if(!state.containsKey(EXPANSION+"x")) {
    state.put(EXPANSION+"side",(long)((startSide+1)%sideCount));
    state.put(EXPANSION+"retry",level.getGameTime()+EXPANSION_RETRY_TICKS);
    ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();
    return null;
   }
  }

  if(level.getGameTime()<state.getOrDefault(EXPANSION+"retry",0L))return null;

  BlockPos center=new BlockPos(state.get(EXPANSION+"x").intValue(),state.get(EXPANSION+"surface").intValue(),
   state.get(EXPANSION+"z").intValue());
  int half=Development.EXPANSION_PARCEL_HALF,width=half*2+1,total=width*width;
  int cursor=(int)Math.max(0,Math.min(total,state.getOrDefault(EXPANSION+"cursor",0L)));
  if(state.getOrDefault(EXPANSION+"phase",0L)==0L) {
   int low=state.getOrDefault(EXPANSION+"survey_low",(long)Integer.MAX_VALUE).intValue();
   int high=state.getOrDefault(EXPANSION+"survey_high",(long)Integer.MIN_VALUE).intValue();
   for(int scanned=0;cursor<total&&scanned<EXPANSION_COLUMNS_PER_SCAN;scanned++) {
    int x=center.getX()+cursor%width-half,z=center.getZ()+cursor/width-half;
    BlockPos column=new BlockPos(x,ref.bell().getY(),z);
    if(!level.hasChunkAt(column)) {
     state.put(EXPANSION+"cursor",(long)cursor);state.put(EXPANSION+"retry",level.getGameTime()+200L);
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return null;
    }
    Map<String,Long> probe=new HashMap<>();
    RoadClearing.Column inspected=RoadClearing.column(level,ref,x,z,probe,"survey");
    if(inspected==null||inspected.next()!=null&&!RoadClearing.validTask(level,inspected.next())
      ||!expansionColumnSuitable(level,ref,inspected.ground())) {
     rejectExpansionParcel(level,ref,state);return null;
    }
    int surfaceY=inspected.ground().getY()+1;
    low=Math.min(low,surfaceY);high=Math.max(high,surfaceY);
    if(!Development.expansionReliefSupported(low,high)) {
     rejectExpansionParcel(level,ref,state);return null;
    }
    cursor++;
   }
   state.put(EXPANSION+"cursor",(long)cursor);state.put(EXPANSION+"survey_low",(long)low);
   state.put(EXPANSION+"survey_high",(long)high);
   if(cursor>=total) {
    if(low==Integer.MAX_VALUE||high==Integer.MIN_VALUE) {
     rejectExpansionParcel(level,ref,state);return null;
    }
    state.put(EXPANSION+"surface",(long)low);state.put(EXPANSION+"phase",1L);state.put(EXPANSION+"cursor",0L);
   }
   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return null;
  }
  for(int scanned=0;cursor<total&&scanned<EXPANSION_COLUMNS_PER_SCAN;scanned++) {
   int x=center.getX()+cursor%width-half,z=center.getZ()+cursor/width-half;
   BlockPos column=new BlockPos(x,ref.bell().getY(),z);
   if(!level.hasChunkAt(column)) {
    state.put(EXPANSION+"cursor",(long)cursor);state.put(EXPANSION+"retry",level.getGameTime()+200L);
    ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return null;
   }
   String clearingKey=EXPANSION+"column";
   RoadClearing.Column inspected=RoadClearing.column(level,ref,x,z,state,clearingKey);
   if(inspected==null) {
    rejectExpansionParcel(level,ref,state);return null;
   }
   if(inspected.next()!=null) {
    Roads.Repair clear=inspected.next();
    Repair task=new Repair(clear.ground(),Blocks.AIR.defaultBlockState(),clear.worksite(),false,clear.removal(),clear.treeRoot(),
     null,ref.bell(),null,WorkAction.EXPANSION_CLEAR,null,-1,Integer.MIN_VALUE);
    if(repairable(level,task))return task;
    rejectExpansionParcel(level,ref,state);return null;
   }
   int surfaceY=inspected.ground().getY()+1;
   int targetY=center.getY();
   Repair foliage=expansionCanopyTask(level,ref,inspected.ground());
   if(foliage!=null)return foliage;
   if(!expansionSurfaceWithinRelief(targetY,surfaceY)) {
    rejectExpansionParcel(level,ref,state);return null;
   }
   if(surfaceY>targetY) {
    BlockPos cut=inspected.ground();BlockState removal=level.getBlockState(cut);
    BlockPos worksite=gradeWorksite(level,cut);
    Repair task=worksite==null?null:new Repair(cut,Blocks.AIR.defaultBlockState(),worksite,false,removal,null,
     null,ref.bell(),null,WorkAction.EXPANSION_GRADE_CUT,null,-1,Integer.MIN_VALUE);
    if(task!=null&&repairable(level,task))return task;
    rejectExpansionParcel(level,ref,state);return null;
   }
   state.remove(clearingKey+":root");state.remove(clearingKey+":top");
   cursor++;
  }
  state.put(EXPANSION+"cursor",(long)cursor);
  if(cursor>=total) {
   state.put(EXPANSION+"ready",1L);state.put(EXPANSION+"prepared_beds",beds);
   state.put(EXPANSION+"prepared_houses",(long)ref.data().houses);
   state.remove(EXPANSION+"retry");
  }
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();
  return null;
 }

 static boolean expansionParcelSuitable(ServerLevel level,VillageRef ref,BlockPos center) {
  int half=Development.EXPANSION_PARCEL_HALF,low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
  for(var home:level.getPoiManager().getInRange(h->h.is(PoiTypes.HOME),ref.bell(),Config.VILLAGE_RADIUS,
    PoiManager.Occupancy.ANY).toList()) {
   if(!Development.expansionParcelClearOfHome(center,half,home.getPos(),half,Development.EXPANSION_PARCEL_CLEARANCE))return false;
  }
  for(int dx:new int[]{-half,0,half})for(int dz:new int[]{-half,0,half}) {
   BlockPos column=new BlockPos(center.getX()+dx,ref.bell().getY(),center.getZ()+dz);
   if(!level.hasChunkAt(column))return false;
   RoadClearing.Column inspected=RoadClearing.column(level,ref,column.getX(),column.getZ(),new HashMap<>(),"probe");
   if(inspected==null||inspected.next()!=null&&!RoadClearing.validTask(level,inspected.next())
     ||!expansionColumnSuitable(level,ref,inspected.ground()))return false;
   BlockPos ground=inspected.ground();
   int surface=ground.getY()+1;low=Math.min(low,surface);high=Math.max(high,surface);
  }
  return Development.expansionReliefSupported(low,high);
 }

 /** Keep the incremental grade pass aligned with the relief accepted by the saved parcel survey. */
 static boolean expansionSurfaceWithinRelief(int targetSurfaceY,int surfaceY) {
  return Development.expansionReliefSupported(targetSurfaceY,surfaceY);
 }

 private static boolean expansionColumnSuitable(ServerLevel level,VillageRef ref,BlockPos ground) {
  if(ground==null||!level.hasChunkAt(ground)||!expansionNaturalGround(level,ground,level.getBlockState(ground))
    ||Lighting.protectedVillageCell(level,ref,ground)||trustedVillagePathCell(level,ref,ground)
    ||isProtectedLaneCell(level,ground)||protectedExpansionColumn(level,ground)
    ||Development.nearOriginalStructure(level,ref,ground,Development.EXPANSION_PARCEL_CLEARANCE))return false;
  for(int dy=4;dy<=EXPANSION_SURFACE_SCAN_HEIGHT;dy++) {
   BlockPos overhead=ground.above(dy);
   if(!level.hasChunkAt(overhead))return false;
   BlockState cover=level.getBlockState(overhead);
   if(cover.hasBlockEntity()||!cover.getFluidState().isEmpty())return false;
   if(cover.is(BlockTags.LEAVES)&&cover.hasProperty(LeavesBlock.PERSISTENT)
     &&cover.getValue(LeavesBlock.PERSISTENT))return false;
   if(!cover.isAir()&&!cover.is(BlockTags.LEAVES)&&!cover.is(BlockTags.OVERWORLD_NATURAL_LOGS))return false;
  }
  return true;
 }

 private static void rejectExpansionParcel(ServerLevel level,VillageRef ref,Map<String,Long> state) {
  int nextSide=Math.floorMod(state.getOrDefault(EXPANSION+"side",0L).intValue(),Development.EXPANSION_PARCEL_OUTER_RING_DIRECTIONS);
  clearExpansionState(state);state.put(EXPANSION+"side",(long)nextSide);
  state.put(EXPANSION+"retry",level.getGameTime()+EXPANSION_RETRY_TICKS);
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();
 }

 private static boolean expansionNaturalGround(ServerLevel level,BlockPos ground,BlockState state) {
  return level.hasChunkAt(ground)&&level.getBlockEntity(ground)==null&&!state.hasBlockEntity()
   &&state.getFluidState().isEmpty()&&state.isFaceSturdy(level,ground,net.minecraft.core.Direction.UP)
   &&(state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT)||state.is(Blocks.COARSE_DIRT)
    ||state.is(Blocks.PODZOL)||state.is(Blocks.MYCELIUM)||state.is(Blocks.ROOTED_DIRT));
 }

 private static boolean protectedExpansionColumn(ServerLevel level,BlockPos ground) {
  for(int dy=-4;dy<=EXPANSION_SURFACE_SCAN_HEIGHT;dy++) {
   BlockPos pos=ground.above(dy);
   if(!level.hasChunkAt(pos))return true;
   BlockState state=level.getBlockState(pos);Block block=state.getBlock();
   if(state.hasBlockEntity()||level.getBlockEntity(pos)!=null||state.is(Blocks.FARMLAND)
    ||state.is(BlockTags.PLANKS)||block instanceof ChestBlock||block instanceof BarrelBlock||block instanceof BedBlock
    ||block instanceof DoorBlock||block instanceof FenceBlock||block instanceof StairBlock||block instanceof SlabBlock)return true;
  }
  return false;
 }

 static Repair expansionCanopyTask(ServerLevel level,VillageRef ref,BlockPos ground) {
  for(int dy=4;dy<=EXPANSION_SURFACE_SCAN_HEIGHT;dy++) {
   BlockPos leaf=ground.above(dy);
   if(!level.hasChunkAt(leaf))return null;
   BlockState leaves=level.getBlockState(leaf);
   if(!leaves.is(BlockTags.LEAVES)||!leaves.hasProperty(LeavesBlock.PERSISTENT)
    ||leaves.getValue(LeavesBlock.PERSISTENT)||leaves.hasBlockEntity()||level.getBlockEntity(leaf)!=null
    ||!leaves.getFluidState().isEmpty())continue;
   BlockPos worksite=expansionCanopyWorksite(level,ground,leaf);
   if(worksite==null)continue;
   Repair task=new Repair(leaf,Blocks.AIR.defaultBlockState(),worksite,false,leaves,null,null,ref.bell(),null,
    WorkAction.EXPANSION_CLEAR,null,-1,Integer.MIN_VALUE);
   if(repairable(level,task))return task;
  }
  return null;
 }

 private static BlockPos expansionCanopyWorksite(ServerLevel level,BlockPos ground,BlockPos target) {
  BlockPos best=null;double bestDistance=Double.MAX_VALUE;
  for(int radius=0;radius<=3;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
   if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
   int x=ground.getX()+dx,z=ground.getZ()+dz;
   if(!level.hasChunkAt(new BlockPos(x,ground.getY(),z)))continue;
   int surface=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
   BlockPos feet=new BlockPos(x,surface,z),floor=feet.below();
   if(!level.hasChunkAt(feet)||!level.hasChunkAt(floor)||!level.hasChunkAt(feet.above())
    ||!level.getBlockState(feet).isAir()||!level.getBlockState(feet.above()).isAir()
    ||!expansionNaturalGround(level,floor,level.getBlockState(floor))||!level.getBlockState(floor).isFaceSturdy(level,floor,net.minecraft.core.Direction.UP))continue;
   double distance=feet.distSqr(target);
   if(distance>36||distance>=bestDistance)continue;
   best=feet;bestDistance=distance;
  }
  return best;
 }

 private static void clearExpansionState(Map<String,Long> state) {
  state.keySet().removeIf(key->key.startsWith(EXPANSION)&&!key.equals(EXPANSION+"geometry")
   &&!key.equals(EXPANSION+"sequence")&&!key.equals(EXPANSION+"side"));
 }

 static long expansionSequence(Map<String,Long> state) {
  return Math.max(0L,state.getOrDefault(EXPANSION+"sequence",0L));
 }

 static boolean expansionHousingProgressed(long preparedBeds,long preparedHouses,long beds,int houses,
   boolean preparedParcelOccupied) {
  return preparedParcelOccupied&&(beds>preparedBeds||houses>preparedHouses);
 }

 private static boolean expansionParcelContainsHome(ServerLevel level,Map<String,Long> state) {
  Long x=state.get(EXPANSION+"x"),z=state.get(EXPANSION+"z"),surface=state.get(EXPANSION+"surface");
  if(x==null||z==null||surface==null)return false;
  BlockPos center=new BlockPos(x.intValue(),surface.intValue(),z.intValue());
  return level.getPoiManager().getInRange(h->h.is(PoiTypes.HOME),center,Development.EXPANSION_PARCEL_HALF*2,
   PoiManager.Occupancy.ANY).anyMatch(p->Development.inExpansionParcel(center,Development.EXPANSION_PARCEL_HALF,p.getPos()));
 }

 /** Called only after the saved parcel's HOME count has increased; failed plots keep the same ordinal. */
 static void consumePreparedExpansionParcel(Map<String,Long> state) {
  long sequence=expansionSequence(state);
  if(sequence<Long.MAX_VALUE) {
   state.put(EXPANSION+"sequence",sequence+1L);
   if(sequence+1L==Development.EXPANSION_PARCEL_BASE_COUNT)state.put(EXPANSION+"side",0L);
  }
  clearExpansionState(state);
 }

 /** One pending lane block. Progress is recovered from real completed lane blocks, including after reload. */
 public static Repair nextConstruction(ServerLevel level,VillageRef ref) {
  Map<String,Long> state=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  Repair expansion=nextExpansionPreparation(level,ref,state);
  if(expansion!=null) {
   ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return expansion;
  }
  for(var link:Network.of(ref.bellEntity()).links()) {
   if(!link.dimension().equals(Network.dim(level))||!roadLinkActive(link))continue;
   String key=link.dimension()+link.bell().asLong();
   VillageRef peer=Network.resolve(level,link);
   Map<String,Long> peerState=peer==null?null:peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
   String peerKey=Network.dim(level)+ref.bell().asLong();
   if(!RoadRoute.ensureWithRetry(level,ref.bell(),link.bell(),state,key,peerState,peerKey,level.getGameTime(),false))continue;
   int length=RoadRoute.length(state,key,ref.bell(),link.bell());
   boolean existing=state.containsKey(key);
   // Old cursors are retained; metadata is added without replaying already paid/built segments.
   int cursor=existing?(int)Math.max(0,Math.min(Integer.MAX_VALUE,state.get(key))):0;
   if(!existing) {
    var bounds=Development.bounds(level,ref);
    while(cursor<=length/2+1) {
     BlockPos p=RoadRoute.point(state,key,ref.bell(),link.bell(),cursor);
     if(p.getX()<bounds.minX()-2||p.getX()>bounds.maxX()+2||p.getZ()<bounds.minZ()-2||p.getZ()>bounds.maxZ()+2)break;
     cursor++;
    }
    state.put(key,(long)cursor);
    state.put(key+":start",(long)cursor);
   }
   int startCursor=(int)Math.max(0,Math.min(length,state.getOrDefault(key+":start",0L)));
   state.put(key+":from",ref.bell().asLong());state.put(key+":to",link.bell().asLong());state.put(key+":geometry",ROUTE_GEOMETRY_VERSION);
   for(int inspected=0;cursor<=length/2+1&&inspected<8;inspected++) {
    BlockPos route=RoadRoute.point(state,key,ref.bell(),link.bell(),cursor);
    boolean alongX=RoadRoute.alongX(state,key,ref.bell(),link.bell(),cursor);
    RoadRoute.ensureGorgeProfileAtCursor(level,state,key,ref.bell(),link.bell(),cursor,ref.bell(),link.bell());
    Integer gorgeDeckY=RoadRoute.gorgeDeckY(state,key,ref.bell(),link.bell(),cursor);
    Repair pending=null;boolean blocked=false,unavailableChunk=false;int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
    BlockPos[] grounds=new BlockPos[ROAD_HALF_WIDTH*2+1];boolean[] bridges=new boolean[ROAD_HALF_WIDTH*2+1];
    for(int side=-ROAD_HALF_WIDTH;side<=ROAD_HALF_WIDTH;side++) {
     int lane=side+ROAD_HALF_WIDTH;
     int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
     if(!level.hasChunkAt(new BlockPos(x,ref.bell().getY(),z))){blocked=true;unavailableChunk=true;break;}
     BlockPos p=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
     var clearing=RoadClearing.column(level,ref,x,z,state,key+":clear:"+cursor+":"+side);
     if(clearing==null){blocked=true;unavailableChunk=!columnChunksLoaded(level,x,z);break;}
     p=clearing.ground();
     grounds[lane]=p;
     if(clearing.next()!=null) {
      low=Math.min(low,p.getY());high=Math.max(high,p.getY());
      if(pending==null)pending=clearing.next();continue;
     }
     String bridgeKey=key+":bridge:"+cursor+":"+side;
     if(state.containsKey(bridgeKey)||level.getBlockState(p).is(Blocks.WATER)||level.getBlockState(p).is(Blocks.SPRUCE_PLANKS)) {
      if(state.containsKey(bridgeKey))p=new BlockPos(x,state.get(bridgeKey).intValue(),z);
      var plan=RoadBridge.plan(level,p,ref.bell(),link.bell());
      if(!rememberBridgePlan(state,bridgeKey,p.getY(),plan)){blocked=true;break;}
      low=Math.min(low,p.getY());high=Math.max(high,p.getY());
      bridges[lane]=true;
      if(pending==null&&plan.next()!=null)pending=plan.next();
      continue;
     }
     if(gorgeDeckY!=null&&gorgeDeckY-p.getY()>=2) {
      BlockPos deck=new BlockPos(x,gorgeDeckY,z);
      var plan=RoadBridge.plan(level,deck,ref.bell(),link.bell());
      if(!rememberBridgePlan(state,bridgeKey,gorgeDeckY,plan)){blocked=true;break;}
      low=Math.min(low,gorgeDeckY);high=Math.max(high,gorgeDeckY);
      bridges[lane]=true;
      if(pending==null&&plan.next()!=null)pending=plan.next();
      continue;
     }
     grounds[lane]=p;
     low=Math.min(low,p.getY());high=Math.max(high,p.getY());
    }
    boolean badGrade=gorgeDeckY==null&&(high-low>MAX_CROSSFALL
     ||!RoadRoute.gradeSafe(level,state,key,ref.bell(),link.bell(),cursor));
    if(pending!=null&&pending.action()==WorkAction.CLEAR) {
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return pending;
    }
    if(blocked||badGrade) {
     if(!unavailableChunk&&cursor==startCursor
      &&!routeCursorHasWork(level,state,key,ref.bell(),link.bell(),cursor)
      &&(peerState==null||peerState.getOrDefault(peerKey,0L)<=peerState.getOrDefault(peerKey+":start",0L)
      &&!routeCursorHasWork(level,peerState,peerKey,link.bell(),ref.bell(),peerState.getOrDefault(peerKey,0L).intValue()))) {
      Map<String,Long> peerMutable=peerState==null?null:new HashMap<>(peerState);
      if(RoadRoute.replanShared(level,ref.bell(),link.bell(),state,key,peerMutable,peerKey,level.getGameTime())) {
       clearAllGradeProfiles(state,key);
       state.remove(key);state.remove(key+":start");
       if(peerMutable!=null){clearAllGradeProfiles(peerMutable,peerKey);peerMutable.remove(peerKey);peerMutable.remove(peerKey+":start");}
      }
      if(peer!=null&&peerMutable!=null){peer.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(peerMutable));peer.dirty();}
      ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();
     }
     break;
    }
    if(pending!=null) {
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return pending;
    }

    // Water and dry-gorge spans share the paid pier/deck workflow; gorge lanes also
    // retain the persisted profile for any lane that meets the bank before the others.
    boolean hasBridge=bridges[0]||bridges[1]||bridges[2];
    if(hasBridge&&gorgeDeckY==null) {
     for(int side=-ROAD_HALF_WIDTH;side<=ROAD_HALF_WIDTH;side++) {
      int lane=side+ROAD_HALF_WIDTH;if(bridges[lane])continue;
      BlockPos p=grounds[lane];if(p==null){blocked=true;break;}
      BlockState current=level.getBlockState(p);boolean sand=sandPalette(level,route,current);
      Block paving=paving(side,sand);
      if(current.is(paving))continue;
      Repair task=new Repair(p,paving.defaultBlockState());
      if(!repairable(level,task)){blocked=true;break;}
      if(pending==null)pending=task;
     }
     if(blocked)break;
     if(pending!=null){ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return pending;}
     state.put(key,(long)++cursor);continue;
    }

    int profileY=gorgeDeckY!=null?gorgeDeckY:state.containsKey(gradeYKey(key,cursor))
     ?state.get(gradeYKey(key,cursor)).intValue():grounds[1].getY();
    if(gorgeDeckY==null&&Math.abs(profileY-grounds[1].getY())>MAX_PROFILE_ADJUSTMENT){
     clearGradeProfileIfUntouched(state,key,cursor);
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();break;
    }
    state.put(gradeYKey(key,cursor),(long)profileY);
    String sandMaskKey=gradeSandKey(key,cursor);
    if(!state.containsKey(sandMaskKey)) {
     long sandMask=sandPalette(level,route,level.getBlockState(grounds[ROAD_HALF_WIDTH]))
      ?(1L<<(ROAD_HALF_WIDTH*2+1))-1L:0L;
     state.put(sandMaskKey,sandMask);
    }
    for(int side=-ROAD_HALF_WIDTH;side<=ROAD_HALF_WIDTH;side++) {
     int lane=side+ROAD_HALF_WIDTH;BlockPos ground=grounds[lane];if(ground==null){blocked=true;break;}
     state.putIfAbsent(gradeBaseYKey(key,cursor,side),(long)ground.getY());
    }
    ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();
    for(int side=-ROAD_HALF_WIDTH;side<=ROAD_HALF_WIDTH;side++) {
     int lane=side+ROAD_HALF_WIDTH;
     if(gorgeDeckY!=null&&bridges[lane])continue;
     BlockPos ground=grounds[lane];if(ground==null){blocked=true;break;}
     int delta=profileY-ground.getY();
     if(Math.abs(delta)>MAX_PROFILE_ADJUSTMENT){blocked=true;break;}
     boolean sand=(state.getOrDefault(gradeSandKey(key,cursor),0L)&(1L<<(side+ROAD_HALF_WIDTH)))!=0;
     BlockState roadPaving=paving(side,sand).defaultBlockState();
     Repair task=null;
     if(delta<0) {
      BlockState cut=level.getBlockState(ground);
      BlockPos worksite=gradeWorksite(level,ground);
      if(worksite==null||!naturalSoil(cut)||cut.hasBlockEntity()||!cut.getFluidState().isEmpty()) {blocked=true;break;}
      task=gradeTask(ground,roadPaving,worksite,cut,ref.bell(),null,WorkAction.GRADE_CUT,key,cursor,profileY);
     } else if(delta>1) {
      BlockPos fill=ground.above();BlockPos worksite=gradeWorksite(level,fill);
      if(worksite==null){blocked=true;break;}
      task=gradeTask(fill,Blocks.DIRT.defaultBlockState(),worksite,null,ref.bell(),null,WorkAction.GRADE_FILL,key,cursor,profileY);
     } else {
      BlockPos target=delta==1?new BlockPos(ground.getX(),profileY,ground.getZ()):ground;
      if(level.getBlockState(target).is(roadPaving.getBlock()))continue;
      BlockPos worksite=gradeWorksite(level,target);
      if(worksite==null){blocked=true;break;}
      task=gradeTask(target,roadPaving,worksite,null,ref.bell(),null,WorkAction.GRADE_PAVE,key,cursor,profileY);
     }
     if(!repairable(level,task)){blocked=true;break;}
     if(pending==null)pending=task;
    }
    if(blocked) {
     clearGradeProfileIfUntouched(state,key,cursor);
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();break;
    }
    if(pending!=null) {
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return pending;
    }
    state.put(key,(long)++cursor);
   }
  }
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return null;
 }

 private static boolean columnChunksLoaded(ServerLevel level,int x,int z) {
  for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
   if(!level.hasChunkAt(new BlockPos(x+dx,level.getMinY(),z+dz)))return false;
  return true;
 }

 static boolean rememberBridgePlan(Map<String,Long> state,String bridgeKey,int deckY,RoadBridge.Plan plan) {
  if(plan==null) {state.remove(bridgeKey);return false;}
  state.put(bridgeKey,(long)deckY);return true;
 }

 static boolean routeCursorHasWork(ServerLevel level,Map<String,Long> state,String key,BlockPos a,BlockPos b,int cursor) {
  String prefix=key+":clear:"+cursor+":";
  for(String name:state.keySet())if(name.startsWith(prefix)||name.startsWith(key+":bridge:"+cursor+":"))return true;
  if(state.getOrDefault(gradeTouchedKey(key,cursor),0L)!=0L)return true;
  int length=RoadRoute.length(state,key,a,b);if(cursor<0||cursor>length)return false;
  BlockPos route=RoadRoute.point(state,key,a,b,cursor);boolean alongX=RoadRoute.alongX(state,key,a,b,cursor);
  Long profile=state.get(gradeYKey(key,cursor));
  for(int side=-1;side<=1;side++) {
   int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
   if(!level.hasChunkAt(new BlockPos(x,a.getY(),z)))continue;
   BlockPos ground=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
   BlockState s=level.getBlockState(ground);
   if(s.is(Blocks.GRAVEL)||s.is(Blocks.COBBLESTONE)||s.is(Blocks.SANDSTONE)||s.is(Blocks.SMOOTH_SANDSTONE)||s.is(Blocks.SPRUCE_PLANKS))return true;
   if(profile!=null) {
    long original=state.getOrDefault(gradeBaseYKey(key,cursor,side),Long.MIN_VALUE);
    if(original!=Long.MIN_VALUE&&ground.getY()!=original)return true;
    BlockPos target=new BlockPos(x,profile.intValue(),z);
    if(level.hasChunkAt(target)&&isRoadSurface(level.getBlockState(target)))return true;
   }
  }
  return false;
 }

 /** Bounded rotating inspection of already built road halves. Never extends an unfinished route. */
 public static Repair nextRepair(ServerLevel level,VillageRef ref) {
  Map<String,Long> state=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  Repair villageGap=nextVillageGap(level,ref,state);
  if(villageGap!=null){ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return villageGap;}
  for(var link:Network.of(ref.bellEntity()).links()) {
   if(!link.dimension().equals(Network.dim(level))||!roadLinkActive(link))continue;
   String key=link.dimension()+link.bell().asLong();
   VillageRef peer=Network.resolve(level,link);
   Map<String,Long> peerState=peer==null?null:peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
   String peerKey=Network.dim(level)+ref.bell().asLong();
   if(!RoadRoute.ensureWithRetry(level,ref.bell(),link.bell(),state,key,peerState,peerKey,level.getGameTime(),false))continue;
   int length=RoadRoute.length(state,key,ref.bell(),link.bell());
   int built=(int)Math.min(length/2+2,state.getOrDefault(key,0L));
   int start=(int)Math.max(0,Math.min(built,state.getOrDefault(key+":start",0L)));
   if(built<=start)continue;
   Repair lighting=nextLighting(level,state,key,ref,peer,link.bell(),start,built);
   if(lighting!=null) {
    ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return lighting;
   }
   int total=(built-start)*3,scan=(int)Math.floorMod(state.getOrDefault("repair:"+key,0L),total);
   for(int i=0;i<Math.min(total,96);i++) {
    int index=(scan+i)%total,cursor=start+index/3,side=index%3-1;
    BlockPos route=RoadRoute.point(state,key,ref.bell(),link.bell(),cursor);
    boolean alongX=RoadRoute.alongX(state,key,ref.bell(),link.bell(),cursor);
    int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
    BlockPos col=new BlockPos(x,ref.bell().getY(),z);if(!level.hasChunkAt(col))continue;
    BlockPos ground=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
    BlockState s=level.getBlockState(ground);
    Long savedPalette=state.get(gradeSandKey(key,cursor));
    boolean sand=savedPalette==null?sandPalette(level,route,s)
     :(savedPalette&(1L<<(side+ROAD_HALF_WIDTH)))!=0;
    Block desired=side==0?(sand?Blocks.SMOOTH_SANDSTONE:Blocks.GRAVEL):(sand?Blocks.SANDSTONE:Blocks.COBBLESTONE);
    Repair repair=new Repair(ground,desired.defaultBlockState());
    if(repairable(level,repair)) {
     state.put("repair:"+key,(long)((index+1)%total));
     ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return repair;
    }
   }
   state.put("repair:"+key,(long)((scan+96)%total));
  }
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();return null;
 }

 private static String gradeYKey(String key,int cursor){return key+":gradeY:"+cursor;}
 private static String gradeSandKey(String key,int cursor){return key+":gradeSand:"+cursor;}
 private static String gradeBaseYKey(String key,int cursor,int side){return key+":gradeBaseY:"+cursor+":"+side;}
 private static String gradeTouchedKey(String key,int cursor){return key+":gradeTouched:"+cursor;}

 private static void clearGradeProfileIfUntouched(Map<String,Long> state,String key,int cursor) {
  if(state.getOrDefault(gradeTouchedKey(key,cursor),0L)!=0L)return;
  state.remove(gradeYKey(key,cursor));state.remove(gradeTouchedKey(key,cursor));
  for(int side=-1;side<=1;side++) {
   state.remove(gradeBaseYKey(key,cursor,side));
  }
  state.remove(gradeSandKey(key,cursor));
 }

 private static void clearAllGradeProfiles(Map<String,Long> state,String key) {
  state.keySet().removeIf(name->name.startsWith(key+":gradeY:")||name.startsWith(key+":gradeSand:")
   ||name.startsWith(key+":gradeBaseY:")||name.startsWith(key+":gradeTouched:"));
 }

 /** The center is the wear surface; the two outer lanes are a contrasting, walkable edge. */
 static Block paving(int side,boolean sand) {
  return side==0?(sand?Blocks.SMOOTH_SANDSTONE:Blocks.GRAVEL):(sand?Blocks.SANDSTONE:Blocks.COBBLESTONE);
 }

 private static boolean sandPalette(ServerLevel level,BlockPos route,BlockState surface) {
  String biomePath=level.getBiome(route).unwrapKey().map(key->key.identifier().getPath()).orElse("");
  return sandPaletteForBiome(biomePath,surface);
 }

 static boolean sandPaletteForBiome(String biomePath,BlockState surface) {
  String path=biomePath==null?"":biomePath.toLowerCase(java.util.Locale.ROOT);
  boolean aridOrSandy=path.contains("desert")||path.contains("badlands")||path.contains("mesa")||path.contains("beach");
  return aridOrSandy||sandy(surface);
 }

 private static boolean sandy(BlockState state) {
  return state.is(BlockTags.SAND)||state.is(Blocks.SANDSTONE)||state.is(Blocks.SMOOTH_SANDSTONE)
   ||state.is(Blocks.RED_SANDSTONE);
 }

 private static Repair gradeTask(BlockPos ground,BlockState paving,BlockPos worksite,BlockState removal,
                                 BlockPos ownerBell,BlockPos peerBell,WorkAction action,String key,int cursor,int y) {
  return new Repair(ground,paving,worksite,false,removal,null,null,ownerBell,peerBell,action,key,cursor,y);
 }

 private static BlockPos gradeWorksite(ServerLevel level,BlockPos target) {
  if(!level.hasChunkAt(target))return null;
  BlockPos best=null;double bestDistance=Double.MAX_VALUE;
  for(int radius=1;radius<=3;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
   if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
   int x=target.getX()+dx,z=target.getZ()+dz;
   if(!level.hasChunkAt(new BlockPos(x,target.getY(),z)))continue;
   int height=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
   BlockPos feet=new BlockPos(x,height,z);
   if(!safeGradeWorksite(level,target,feet))continue;
   double distance=feet.distSqr(target);
   if(distance>=bestDistance)continue;
   best=feet;bestDistance=distance;
  }
  return best;
 }

 private static boolean safeGradeWorksite(ServerLevel level,BlockPos target,BlockPos feet) {
  if(!level.hasChunkAt(target)||!level.hasChunkAt(feet)||!level.hasChunkAt(feet.below())
   ||feet.distSqr(target)>9||!level.getBlockState(feet).isAir()||!level.getBlockState(feet.above()).isAir())return false;
  BlockState floor=level.getBlockState(feet.below());
  return !floor.hasBlockEntity()&&!floor.is(Blocks.FARMLAND)&&floor.getFluidState().isEmpty()
   &&floor.isFaceSturdy(level,feet.below(),net.minecraft.core.Direction.UP);
 }

 private static boolean gradeSupport(ServerLevel level,BlockPos pos) {
  if(!level.hasChunkAt(pos))return false;
  BlockState state=level.getBlockState(pos);
  if(state.hasBlockEntity()||state.is(Blocks.FARMLAND)||!state.getFluidState().isEmpty()
   ||!state.isFaceSturdy(level,pos,net.minecraft.core.Direction.UP))return false;
  return naturalSoil(state)||isRoadSurface(state)||state.is(Blocks.DIRT_PATH)||state.is(Blocks.STONE);
 }

 private static boolean gradeProfileSaved(ServerLevel level,Repair task) {
  if(task.gradeKey()==null||task.gradeCursor()<0||task.ownerBell()==null||!level.hasChunkAt(task.ownerBell()))return false;
  var bell=level.getBlockEntity(task.ownerBell());
  if(!(bell instanceof net.minecraft.world.level.block.entity.BellBlockEntity))return false;
  Map<String,Long> roads=bell.getAttachedOrElse(VillagerPlus.ROADS,Map.of());
  return roads.getOrDefault(gradeYKey(task.gradeKey(),task.gradeCursor()),Long.MIN_VALUE)==task.gradeY();
 }

 private static boolean gradeTaskValid(ServerLevel level,Repair task) {
  BlockPos p=task.ground();
  if(!gradeProfileSaved(level,task)||!level.hasChunkAt(p)||!level.hasChunkAt(p.above())||!level.hasChunkAt(p.below())
   ||!level.getBlockState(p.above()).isAir()||!level.getBlockState(p.above(2)).isAir()
   ||occupied(level,p)||isProtectedLaneCell(level,p)||!safeGradeWorksite(level,p,task.worksite()))return false;
  BlockState state=level.getBlockState(p);
  if(task.action()==WorkAction.GRADE_CUT) {
   return task.removal()!=null&&state.equals(task.removal())&&p.getY()>task.gradeY()
    &&p.getY()-task.gradeY()<=MAX_PROFILE_ADJUSTMENT&&naturalSoil(state)&&!state.hasBlockEntity()
    &&state.getFluidState().isEmpty()&&gradeSupport(level,p.below());
  }
  if(task.action()==WorkAction.GRADE_FILL) {
   return task.paving().is(Blocks.DIRT)&&p.getY()==task.gradeY()-1&&state.isAir()
    &&gradeSupport(level,p.below());
  }
  if(task.action()!=WorkAction.GRADE_PAVE||p.getY()!=task.gradeY()||!gradeSupport(level,p.below()))return false;
  return state.isAir()||(!state.hasBlockEntity()&&state.getFluidState().isEmpty()
   &&(naturalSoil(state)||state.is(Blocks.DIRT_PATH))
   &&state.isFaceSturdy(level,p,net.minecraft.core.Direction.UP));
 }

 /** Records a completed physical grading operation beside the saved route cursor/profile. */
 public static void noteGradeWork(ServerLevel level,Repair task) {
  if(task==null||task.gradeKey()==null||task.ownerBell()==null||!level.hasChunkAt(task.ownerBell()))return;
  var bell=level.getBlockEntity(task.ownerBell());
  if(!(bell instanceof net.minecraft.world.level.block.entity.BellBlockEntity))return;
  Map<String,Long> roads=new HashMap<>(bell.getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  if(roads.getOrDefault(gradeYKey(task.gradeKey(),task.gradeCursor()),Long.MIN_VALUE)!=task.gradeY())return;
  roads.put(gradeTouchedKey(task.gradeKey(),task.gradeCursor()),1L);
  bell.setAttached(VillagerPlus.ROADS,Map.copyOf(roads));
  bell.setChanged();
 }

 public static boolean repairable(ServerLevel level,Repair repair) {
  BlockPos p=repair.ground();if(!level.hasChunkAt(p))return false;
  if(isProtectedLaneCell(level,p))return false;
  if(repair.action()==WorkAction.EXPANSION_CLEAR||repair.action()==WorkAction.EXPANSION_GRADE_CUT)
   return expansionTaskValid(level,repair);
  if(repair.action()==WorkAction.GAP_CLEAR)return gapClearTaskValid(level,repair);
  if(repair.action()==WorkAction.GRADE_CUT||repair.action()==WorkAction.GRADE_FILL
   ||repair.action()==WorkAction.GRADE_PAVE)return gradeTaskValid(level,repair);
  if(repair.ownerBell()!=null&&repair.peerBell()==null
   &&(repair.paving().is(Blocks.DIRT)||repair.paving().is(Blocks.DIRT_PATH))) {
   var bell=level.getBlockEntity(repair.ownerBell());
   if(bell==null)return false;
   var data=bell.getAttached(VillagerPlus.VILLAGE);
   return data!=null&&villageGapValid(level,new VillageRef(repair.ownerBell(),data,bell),p);
  }
  if(repair.completionKey()!=null)return lightingRepairable(level,repair);
  if(repair.removal()!=null)return RoadClearing.validTask(level,repair);
  if(repair.bridge())return RoadBridge.validTask(level,repair);
  BlockState s=level.getBlockState(p);
  if(s.is(repair.paving().getBlock())||s.hasBlockEntity()||!s.getFluidState().isEmpty()||s.is(Blocks.FARMLAND))return false;
  if(!(naturalSoil(s)||s.is(Blocks.DIRT_PATH)||s.is(Blocks.STONE)))return false;
  if(!s.isFaceSturdy(level,p,net.minecraft.core.Direction.UP))return false;
  if(repair.paving().getBlock() instanceof net.minecraft.world.level.block.FallingBlock
   &&!level.getBlockState(p.below()).isFaceSturdy(level,p.below(),net.minecraft.core.Direction.UP))return false;
  return level.getBlockState(p.above()).isAir()&&level.getBlockState(p.above(2)).isAir();
 }

 private static boolean expansionTaskValid(ServerLevel level,Repair task) {
  if(task.ownerBell()==null||task.peerBell()!=null||!task.paving().isAir()||task.removal()==null
    ||!level.hasChunkAt(task.ownerBell())||!level.hasChunkAt(task.worksite()))return false;
  var bell=level.getBlockEntity(task.ownerBell());
  if(!(bell instanceof net.minecraft.world.level.block.entity.BellBlockEntity))return false;
  var data=bell.getAttached(VillagerPlus.VILLAGE);
  if(data==null)return false;
  VillageRef ref=new VillageRef(task.ownerBell(),data,bell);
  if(data.hasPlan()||data.hasBuildProjects())return false;
  Map<String,Long> state=bell.getAttachedOrElse(VillagerPlus.ROADS,Map.of());
  if(state.getOrDefault(EXPANSION+"geometry",0L)!=EXPANSION_GEOMETRY_VERSION
    ||state.getOrDefault(EXPANSION+"phase",0L)!=1L||state.getOrDefault(EXPANSION+"ready",0L)!=0L
    ||!state.containsKey(EXPANSION+"x")||!state.containsKey(EXPANSION+"surface")||!state.containsKey(EXPANSION+"z"))return false;
  BlockPos center=new BlockPos(state.get(EXPANSION+"x").intValue(),state.get(EXPANSION+"surface").intValue(),
   state.get(EXPANSION+"z").intValue());
  if(!Development.inExpansionParcel(center,Development.EXPANSION_PARCEL_HALF,task.ground())
    ||Lighting.protectedVillageCell(level,ref,task.ground())
    ||trustedVillagePathCell(level,ref,task.ground())
    ||protectedExpansionColumn(level,new BlockPos(task.ground().getX(),center.getY()-1,task.ground().getZ()))
    ||Development.nearOriginalStructure(level,ref,task.ground(),Development.EXPANSION_PARCEL_CLEARANCE))return false;
  if(task.action()==WorkAction.EXPANSION_CLEAR) {
   if(task.ground().getY()<center.getY()-1
    ||task.ground().getY()>center.getY()+EXPANSION_SURFACE_SCAN_HEIGHT+Development.EXPANSION_PARCEL_MAX_RELIEF-1)return false;
   return !occupied(level,task.ground())&&RoadClearing.validTask(level,task);
  }
  if(task.ground().getY()<center.getY()
    ||task.ground().getY()>center.getY()+Development.EXPANSION_PARCEL_MAX_RELIEF-1
    ||!expansionNaturalGround(level,task.ground(),task.removal())
    ||!level.getBlockState(task.ground()).equals(task.removal())
    ||!level.hasChunkAt(task.ground().below())||!expansionNaturalGround(level,task.ground().below(),level.getBlockState(task.ground().below()))
    ||!level.getBlockState(task.ground().above()).isAir()
    ||!safeGradeWorksite(level,task.ground(),task.worksite())||occupied(level,task.ground()))return false;
  return true;
 }

 /** Inspect only previously traversed village path cells; a repair uses one carried dirt block. */
 private static Repair nextVillageGap(ServerLevel level,VillageRef ref,Map<String,Long> state) {
  BlockPos plaza=Market.of(ref).plaza().orElse(null);if(plaza==null)return null;
  int length=routeLength(plaza,ref.bell());
  int built=(int)Math.max(0L,Math.min((long)length+1,state.getOrDefault("center",0L)));
  if(built==0)return null;
  int total=built*3,scan=(int)Math.floorMod(state.getOrDefault("repair:center",0L),total);
  for(int i=0;i<Math.min(total,96);i++) {
   int index=(scan+i)%total,cursor=index/3,side=index%3-1;
   BlockPos route=routePoint(plaza,ref.bell(),cursor,length);
   boolean alongX=alongXAt(plaza,ref.bell(),cursor,length);
   int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
   BlockPos column=new BlockPos(x,ref.bell().getY(),z);if(!level.hasChunkAt(column))continue;
   int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
   BlockPos target=new BlockPos(x,y,z);
   if(villageGapValid(level,ref,target)) {
    state.put("repair:center",(long)((index+1)%total));
    BlockPos worksite=villageGapWorksite(level,ref,target);
    if(worksite==null)continue;
    BlockPos obstruction=firstGapObstruction(level,target);
    if(obstruction!=null) {
     BlockState removal=level.getBlockState(obstruction);
     return new Repair(obstruction,Blocks.AIR.defaultBlockState(),worksite,false,removal,null,
      gapClearKey(target),ref.bell(),null,WorkAction.GAP_CLEAR,null,-1,Integer.MIN_VALUE);
    }
    return new Repair(target,Blocks.DIRT_PATH.defaultBlockState(),worksite,false,null,null,null,ref.bell(),null);
   }
  }
  state.put("repair:center",(long)((scan+96)%total));return null;
 }

 private static boolean villageGapValid(ServerLevel level,VillageRef ref,BlockPos target) {
  if(!gapTerrain(level,target)||!trustedVillagePathCell(level,ref,target)||Lighting.protectedVillageCell(level,ref,target)
   ||isProtectedLaneCell(level,target))return false;
  BlockPos plaza=Market.of(ref).plaza().orElse(null);if(plaza==null)return false;
  Map<String,Long> state=ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
  int length=routeLength(plaza,ref.bell());
  int built=(int)Math.max(0L,Math.min((long)length+1,state.getOrDefault("center",0L)));
  for(int cursor=0;cursor<built;cursor++) {
   BlockPos route=routePoint(plaza,ref.bell(),cursor,length);
   boolean alongX=alongXAt(plaza,ref.bell(),cursor,length);
   for(int side=-1;side<=1;side++) {
    if(target.getX()!=route.getX()+(alongX?0:side)||target.getZ()!=route.getZ()+(alongX?side:0))continue;
    var direction=alongX?net.minecraft.core.Direction.EAST:net.minecraft.core.Direction.NORTH;
    final int lane=side; final boolean horizontal=alongX;
    if(gapAnchored(level,target,direction,p -> straightVillageLaneCell(plaza,ref.bell(),length,built,p,horizontal,lane)
      && !Lighting.protectedVillageCell(level,ref,p) && !isProtectedLaneCell(level,p)))return true;
   }
  }
  return false;
 }

 /** The worker must stand on an existing/previously repaired lane cell beside an open gap. */
 static BlockPos villageGapWorksite(ServerLevel level,VillageRef ref,BlockPos target) {
  BlockPos best=null;int bestDistance=Integer.MAX_VALUE;
  for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++) {
   int distance=Math.abs(dx)+Math.abs(dz);
   // Keep one block of setback from the open gap: vanilla navigation can reject a stand
   // directly on its lip, while the worker can still place from two blocks away.
   if(distance<2||distance>3||distance>=bestDistance)continue;
   int x=target.getX()+dx,z=target.getZ()+dz;
   BlockPos probe=new BlockPos(x,target.getY(),z);if(!level.hasChunkAt(probe))continue;
   int groundY=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
   BlockPos floor=new BlockPos(x,groundY,z);
   if(!level.getBlockState(floor).is(Blocks.DIRT_PATH)||!trustedVillagePathCell(level,ref,floor)
    ||Lighting.protectedVillageCell(level,ref,floor)||isProtectedLaneCell(level,floor))continue;
   BlockPos support=floor.below(),feet=floor.above();
   if(!level.getBlockState(support).isFaceSturdy(level,support,net.minecraft.core.Direction.UP)
    ||!level.getBlockState(feet).isAir()||!level.getBlockState(feet.above()).isAir())continue;
   best=feet;bestDistance=distance;
  }
  return best;
 }

 static boolean gapAnchored(ServerLevel level,BlockPos target,net.minecraft.core.Direction direction) {
  return gapAnchored(level,target,direction,p -> true);
 }

 static boolean straightVillageLaneCell(BlockPos plaza,BlockPos bell,int length,int built,BlockPos p,boolean alongX,int side) {
  for(int cursor=0;cursor<built;cursor++) {
   if(alongXAt(plaza,bell,cursor,length)!=alongX)continue;
   BlockPos route=routePoint(plaza,bell,cursor,length);
   if(p.getX()==route.getX()+(alongX?0:side)&&p.getZ()==route.getZ()+(alongX?side:0))return true;
  }
  return false;
 }

 /** Both ends must be actual path. A short, safe one-block slope may bridge an unfinished gap,
  * but placed dirt cannot itself become an endpoint; each placement is revalidated. */
 static boolean gapAnchored(ServerLevel level,BlockPos target,net.minecraft.core.Direction direction,
                            java.util.function.Predicate<BlockPos> allowed) {
  if(!gapTerrain(level,target)||!allowed.test(target))return false;
  GapAnchor a=gapAnchorDistance(level,target,direction,allowed),b=gapAnchorDistance(level,target,direction.getOpposite(),allowed);
  if(a==null||b==null||a.distance()+b.distance()-1>MAX_SAVED_PATH_GAP)return false;
  // A short missing segment may follow a one-block natural slope. Keep every step walkable:
  // the target must bridge both anchors within their horizontal distance, and the anchors
  // themselves cannot differ by more than one block per intervening step.
  return Math.abs(a.y()-target.getY())<=a.distance()
   &&Math.abs(b.y()-target.getY())<=b.distance()
   &&Math.abs(a.y()-b.y())<=a.distance()+b.distance();
 }

 record GapAnchor(int distance,int y) { }

 static GapAnchor gapAnchorDistance(ServerLevel level,BlockPos target,net.minecraft.core.Direction direction,
                                    java.util.function.Predicate<BlockPos> allowed) {
  for(int step=1;step<=MAX_SAVED_PATH_GAP;step++) {
   BlockPos pos=target.relative(direction,step);
   if(!level.hasChunkAt(pos)||!allowed.test(pos))return null;
   // Treat a dirt-path block one level above or below the target as a valid stair endpoint.
   // Minecraft's normal navigation can traverse that single-block step without teleporting.
   for(int dy=-1;dy<=1;dy++) {
    BlockPos candidate=pos.above(dy);
    if(level.getBlockState(candidate).is(Blocks.DIRT_PATH)) {
     return safePathAnchor(level,candidate)?new GapAnchor(step,candidate.getY()):null;
    }
   }
   BlockState state=level.getBlockState(pos);
   if(state.isAir()) { if(!gapTerrain(level,pos))return null; }
   else if(state.is(Blocks.DIRT)) {
    BlockState support=level.getBlockState(pos.below());
    if(!naturalSoil(support)||support.hasBlockEntity()||!support.getFluidState().isEmpty()
      ||!support.isFaceSturdy(level,pos.below(),net.minecraft.core.Direction.UP)
      ||!level.getBlockState(pos.above()).isAir()||!level.getBlockState(pos.above(2)).isAir())return null;
   } else if(gapObstacle(level,pos)) { if(!gapTerrain(level,pos))return null; }
   else return null;
  }
  return null;
 }

 private static boolean safePathAnchor(ServerLevel level,BlockPos path) {
  return level.hasChunkAt(path)&&level.hasChunkAt(path.above(2))
   &&level.getBlockState(path.below()).isFaceSturdy(level,path.below(),net.minecraft.core.Direction.UP)
   &&level.getBlockState(path.above()).isAir()&&level.getBlockState(path.above(2)).isAir();
 }

 /** Supported path surface with two blocks of headroom; at most one safe obstruction may be cleared first. */
 static boolean gapTerrain(ServerLevel level,BlockPos target) {
  int obstructions=0;
  for(int dy=0;dy<=2;dy++) {
   BlockPos cell=target.above(dy);
   if(!gapClearanceCell(level,cell))return false;
   if(!level.getBlockState(cell).isAir()&&++obstructions>1)return false;
  }
  BlockState support=level.getBlockState(target.below());
  return !support.is(Blocks.FARMLAND)&&!support.is(Blocks.DIRT_PATH)&&naturalSoil(support)
   &&!support.hasBlockEntity()&&support.getFluidState().isEmpty()
   &&support.isFaceSturdy(level,target.below(),net.minecraft.core.Direction.UP);
 }

 private static boolean gapClearanceCell(ServerLevel level,BlockPos pos) {
  if(!level.hasChunkAt(pos)||occupied(level,pos))return false;
  BlockState state=level.getBlockState(pos);
  return state.isAir()||gapObstacle(level,pos);
 }

 /** Only one-cell natural plants and a thin snow obstruction may be removed from the path clearance box. */
 static boolean gapObstacle(ServerLevel level,BlockPos pos) {
  if(!level.hasChunkAt(pos)||occupied(level,pos))return false;
  BlockState state=level.getBlockState(pos);
  if(state.isAir()||state.hasBlockEntity()||!state.getFluidState().isEmpty())return false;
  if(state.is(Blocks.SNOW)||state.is(Blocks.SHORT_GRASS)||state.is(Blocks.FERN)||state.is(Blocks.DEAD_BUSH))return true;
  if(!state.is(BlockTags.FLOWERS))return false;
  // Tall flowers use the same block in both halves; never remove a multipart plant one cell at a time.
  return !level.getBlockState(pos.above()).is(state.getBlock())
   &&!level.getBlockState(pos.below()).is(state.getBlock());
 }

 private static BlockPos firstGapObstruction(ServerLevel level,BlockPos target) {
  for(int dy=0;dy<=2;dy++) {
   BlockPos pos=target.above(dy);
   if(!level.getBlockState(pos).isAir())return pos;
  }
  return null;
 }

 private static String gapClearKey(BlockPos target) { return "path-gap:"+target.asLong(); }

 private static BlockPos gapClearTarget(Repair task) {
  if(task.completionKey()==null||!task.completionKey().startsWith("path-gap:"))return null;
  try { return BlockPos.of(Long.parseLong(task.completionKey().substring("path-gap:".length()))); }
  catch(NumberFormatException ignored) { return null; }
 }

 private static boolean gapClearTaskValid(ServerLevel level,Repair task) {
  BlockPos target=gapClearTarget(task);
  if(target==null||task.ownerBell()==null||task.peerBell()!=null||task.removal()==null
   ||!task.paving().isAir()||!level.hasChunkAt(task.ownerBell()))return false;
  var bell=level.getBlockEntity(task.ownerBell());
  if(!(bell instanceof net.minecraft.world.level.block.entity.BellBlockEntity))return false;
  var data=bell.getAttached(VillagerPlus.VILLAGE);
  if(data==null)return false;
  VillageRef ref=new VillageRef(task.ownerBell(),data,bell);
  if(!villageGapValid(level,ref,target))return false;
  BlockPos blocker=firstGapObstruction(level,target);
  return blocker!=null&&blocker.equals(task.ground())&&level.getBlockState(blocker).equals(task.removal())
   &&gapObstacle(level,blocker)&&task.worksite()!=null
   &&task.worksite().equals(villageGapWorksite(level,ref,target));
 }

 /** Do not pave over village bells or milestone/communal storage containers. */
 public static boolean isProtectedLaneCell(ServerLevel level,BlockPos ground) {
  for(int dy=0;dy<=1;dy++) {
   BlockPos pos=ground.above(dy);
   if(!level.hasChunkAt(pos))continue;
   var state=level.getBlockState(pos);
   if(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BellBlockEntity)return true;
   if(!(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
    ||state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock))continue;
   if(level.getBlockEntity(pos) instanceof net.minecraft.world.Container)return true;
  }
  return false;
 }

 /** A paid log/log/lantern post is installed by the same physical worker route as road repairs. */
 private static Repair nextLighting(ServerLevel level,Map<String,Long> state,String key,VillageRef ref,VillageRef peer,
                                    BlockPos b,int start,int built) {
  BlockPos a=ref.bell();
  int first=Math.max(start,((start+LANTERN_EVERY-1)/LANTERN_EVERY)*LANTERN_EVERY);
  for(int cursor=first;cursor<built;cursor+=LANTERN_EVERY) {
   if(!trustedBuiltRoadCursor(level,state,key,a,b,cursor,built))continue;
   String doneKey="light:"+key+":"+cursor;
   BlockPos route=RoadRoute.point(state,key,a,b,cursor);
   if(peer==null&&route.distSqr(b)<=24*24)continue;
   boolean alongX=RoadRoute.alongX(state,key,a,b,cursor);
   LampSite site=null;
   for(int side:new int[]{1,-1}) {
    site=lightTarget(level,ref,peer,route,alongX,side);
    if(site!=null&&(level.getBlockState(site.base().above(2)).is(Blocks.LANTERN)
      ||Lighting.spaced(level,site.base().above(2),site.base())))break;
    site=null;
   }
   if(site==null)continue;
   BlockPos base=site.base();
   Block[] post={Blocks.SPRUCE_LOG,Blocks.SPRUCE_LOG,Blocks.LANTERN};
   for(int stage=0;stage<post.length;stage++) {
    BlockPos target=base.above(stage);String completion=doneKey+":"+(stage+1);
    BlockState desired=post[stage].defaultBlockState();
    if(post[stage]==Blocks.LANTERN)desired=desired.setValue(net.minecraft.world.level.block.LanternBlock.HANGING,false);
    boolean done=state.getOrDefault(completion,0L)!=0L;
    if(level.hasChunkAt(target)&&level.getBlockState(target).is(post[stage])) {
     state.put(completion,1L);continue;
    }
    if(done)state.remove(completion); // A damaged lamp can be rebuilt later as a paid repair.
    Repair task=new Repair(target,desired,site.worksite(),false,null,null,completion,a,peer==null?null:peer.bell());
    if(lightingRepairable(level,task))return task;
    break; // A blocked lower post must be resolved before higher stages can be built.
   }
  }
  return null;
 }

 private static boolean trustedBuiltRoadCursor(ServerLevel level,Map<String,Long> state,String key,BlockPos a,BlockPos b,
                                                int cursor,int built) {
  int length=RoadRoute.length(state,key,a,b);
  if(cursor<0||cursor>=built||cursor>length||!level.hasChunkAt(RoadRoute.point(state,key,a,b,cursor)))return false;
  BlockPos route=RoadRoute.point(state,key,a,b,cursor);boolean alongX=RoadRoute.alongX(state,key,a,b,cursor);
  for(int side=-1;side<=1;side++) {
   int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
   BlockPos column=new BlockPos(x,route.getY(),z);
   if(!level.hasChunkAt(column))return false;
   BlockPos ground=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
   if(!isRoadSurface(level.getBlockState(ground)))return false;
  }
  return true;
 }

 private record LampSite(BlockPos base,BlockPos worksite) { }

 private static LampSite lightTarget(ServerLevel level,VillageRef ref,VillageRef peer,BlockPos route,boolean alongX,int side) {
  int x=route.getX()+(alongX?0:side*2),z=route.getZ()+(alongX?side*2:0);
  int laneX=route.getX()+(alongX?0:side),laneZ=route.getZ()+(alongX?side:0);
  BlockPos column=new BlockPos(x,route.getY(),z),laneColumn=new BlockPos(laneX,route.getY(),laneZ);
  if(!level.hasChunkAt(column)||!level.hasChunkAt(laneColumn))return null;
  BlockPos support=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
  for(int step=0;step<3&&(level.getBlockState(support).is(Blocks.SPRUCE_LOG)
    ||level.getBlockState(support).is(Blocks.LANTERN));step++)support=support.below();
  BlockPos laneGround=new BlockPos(laneX,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,laneX,laneZ)-1,laneZ);
  BlockState floor=level.getBlockState(support),lane=level.getBlockState(laneGround);
  if(!isRoadSurface(lane)||!naturalSoil(floor)||floor.hasBlockEntity()||!floor.getFluidState().isEmpty()
    ||!floor.isFaceSturdy(level,support,net.minecraft.core.Direction.UP)
    ||Math.abs(support.getY()-laneGround.getY())>1||support.getY()+3>=level.getMaxY())return null;
  if(Lighting.protectedVillageCell(level,ref,support)||Lighting.protectedVillageCell(level,peer,support)
    ||trustedVillagePathCell(level,ref,support)||(peer!=null&&trustedVillagePathCell(level,peer,support)))return null;
  if(isProtectedLaneCell(level,support))return null;
  BlockPos base=support.above();
  Block[] allowed={Blocks.SPRUCE_LOG,Blocks.SPRUCE_LOG,Blocks.LANTERN};
  for(int dy=0;dy<=2;dy++) {
   BlockPos cell=base.above(dy);
   if(!level.hasChunkAt(cell))return null;
   BlockState state=level.getBlockState(cell);
   if((!state.isAir()&&!state.is(allowed[dy]))||!state.getFluidState().isEmpty()||state.hasBlockEntity())return null;
  }
  AABB post=new AABB(x,support.getY()+1,z,x+1,support.getY()+4,z+1);
  if(!level.getEntities((Entity)null,post,Entity::isAlive).isEmpty())return null;
  BlockPos worksite=laneGround.above();
  if(!level.hasChunkAt(worksite)||!level.getBlockState(worksite).isAir()
    ||!level.getBlockState(worksite.above()).isAir())return null;
  return new LampSite(base,worksite);
 }

 private static boolean lightingRepairable(ServerLevel level,Repair repair) {
  BlockPos target=repair.ground(),support=target.below();
  if(!level.hasChunkAt(target)||!level.hasChunkAt(support))return false;
  BlockState state=level.getBlockState(target),floor=level.getBlockState(support);
  if(!state.isAir()||!state.getFluidState().isEmpty()||floor.hasBlockEntity()
    ||!floor.isFaceSturdy(level,support,net.minecraft.core.Direction.UP))return false;
  if(repair.paving().is(Blocks.SPRUCE_LOG)) {
   if(!(isRoadSurface(floor)||floor.is(Blocks.SPRUCE_LOG)||naturalSoil(floor)))return false;
  } else if(repair.paving().is(Blocks.LANTERN)) {
   if(!floor.is(Blocks.SPRUCE_LOG))return false;
  } else return false;
  BlockPos base=support;
  for(int step=0;step<2&&level.getBlockState(base).is(Blocks.SPRUCE_LOG);step++)base=base.below();
  if(level.getBlockState(base).is(Blocks.SPRUCE_LOG))return false;
  if(repair.ownerBell()!=null) {
   VillageRef owner=loadedVillage(level,repair.ownerBell());
   if(owner==null)return false;
   if(Lighting.protectedVillageCell(level,owner,base)||trustedVillagePathCell(level,owner,base))return false;
  }
  if(repair.peerBell()!=null) {
   VillageRef peer=loadedVillage(level,repair.peerBell());
   if(peer==null) {
    if(base.distSqr(repair.peerBell())<=24*24)return false;
   } else if(Lighting.protectedVillageCell(level,peer,base)||trustedVillagePathCell(level,peer,base))return false;
  }
  AABB post=new AABB(target.getX(),base.getY()+1,target.getZ(),target.getX()+1,base.getY()+4,target.getZ()+1);
  if(!level.getEntities((Entity)null,post,Entity::isAlive).isEmpty())return false;
  // The villager may have spent many ticks reaching this stage. Recheck at repair time,
  // immediately before RoadBuilderJob places the final lantern.
  return !repair.paving().is(Blocks.LANTERN)||Lighting.spaced(level,target,base);
 }

 static VillageRef loadedVillage(ServerLevel level,BlockPos bell) {
  if(!level.hasChunkAt(bell))return null;
  var entity=level.getBlockEntity(bell);
  if(!(entity instanceof net.minecraft.world.level.block.entity.BellBlockEntity))return null;
  VillageData data=entity.getAttached(VillagerPlus.VILLAGE);
  return data==null?null:new VillageRef(bell,data,entity);
 }

 /** Checks bridge targets and their support surface without loading either village chunk. */
 static boolean protectedBridgeCell(ServerLevel level,BlockPos target,BlockPos ownerBell,BlockPos peerBell) {
  if(target==null)return true;
  BlockPos floor=target.below();
  if(ownerBell!=null) {
   VillageRef owner=loadedVillage(level,ownerBell);
   if(owner==null||Lighting.protectedVillageCell(level,owner,target)
     ||Lighting.protectedVillageCell(level,owner,floor))return true;
  }
  if(peerBell!=null) {
   VillageRef peer=loadedVillage(level,peerBell);
   if(peer==null) {
    // Do not force-load a distant peer. Its configurable zones extend at most 64 blocks from its bell.
    if(Math.abs((long)target.getX()-peerBell.getX())<=64L
      &&Math.abs((long)target.getZ()-peerBell.getZ())<=64L)return true;
   } else if(Lighting.protectedVillageCell(level,peer,target)
     ||Lighting.protectedVillageCell(level,peer,floor))return true;
  }
  return false;
 }

 private static boolean isRoadSurface(BlockState state) {
  return state.is(Blocks.GRAVEL)||state.is(Blocks.SMOOTH_SANDSTONE)||state.is(Blocks.SANDSTONE)
   ||state.is(Blocks.COBBLESTONE)||state.is(Blocks.SPRUCE_PLANKS);
 }
 public static void tick(ServerLevel level,VillageRef ref,long now) {
  Map<String,Long> state=ref.bellEntity().getAttached(VillagerPlus.ROADS);if(state==null)state=Map.of();
  if(now<state.getOrDefault("next",0L))return;
  Map<String,Long> next=new HashMap<>(state);next.put("next",now+200);
  var plaza=Market.of(ref).plaza();
  if(plaza.isPresent()) advance(level,plaza.get(),ref.bell(),"center",next,8,false);
  // Overland paving is performed at the worksite by RoadBuilderJob, with real carried materials.
  // Rail construction is bilateral: each side must know the other, meet population/milestone gates,
  // and independently pay from its own stores as its half of the route advances.
  for(var link:Network.of(ref.bellEntity()).links()) {
   if(link.relation()<METROPOLIS||!link.dimension().equals(Network.dim(level)))continue;
   VillageRef other=Network.resolve(level,link);
	  if(other==null||!railProjectReady(level,ref,other))continue;
   advanceRail(level,ref.bell(),link.bell(),"rail"+link.bell().asLong(),next,4,ref,
    other.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  }
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(next));ref.dirty();
 }

 /** Headless/dev check entry for bilateral metropolis rail without duplicating private advance logic. */
 public static boolean advanceMetropolisRailCheck(ServerLevel level,VillageRef ref,VillageRef peer,Map<String,Long> state,int budget) {
	  if(!railProjectReady(level,ref,peer))return false;
  for(var link:Network.of(ref.bellEntity()).links()) {
   if(link.relation()<METROPOLIS||!link.dimension().equals(Network.dim(level))||!link.bell().equals(peer.bell()))continue;
   advanceRail(level,ref.bell(),link.bell(),"rail"+link.bell().asLong(),state,budget,ref,
    peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
   return true;
  }
  return false;
 }

 /** Dev/autotest probe for station boarding cursor selection on a paved route. */
 public static int stationRailStartForCheck(ServerLevel level,BlockPos a,BlockPos b,BlockPos station,Map<String,Long> state,String routeKey) {
  int length=RoadRoute.length(state,routeKey,a,b);
  return findStationRailStart(level,a,b,station,length/2+1,length,state,routeKey);
 }

 public static final int METROPOLIS=90;
 public static final int RAIL_POP=8;

 static int routeLength(BlockPos a,BlockPos b) { return Math.abs(b.getX()-a.getX())+Math.abs(b.getZ()-a.getZ()); }

 /** True only for cells on the already-built, persistent plaza-to-bell village path corridor. */
 public static boolean trustedVillagePathCell(ServerLevel level,VillageRef ref,BlockPos cell) {
  if(ref==null||cell==null||!level.hasChunkAt(cell))return false;
  BlockPos plaza=Market.of(ref).plaza().orElse(null);
  if(plaza==null)return false;
  Map<String,Long> state=ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
  long saved=state.getOrDefault("center",0L);
  int length=routeLength(plaza,ref.bell());
  int built=(int)Math.max(0L,Math.min((long)length+1L,saved));
  if(built<=0)return false;
  for(int cursor=0;cursor<built;cursor++) {
   BlockPos route=routePoint(plaza,ref.bell(),cursor,length);
   boolean alongX=alongXAt(plaza,ref.bell(),cursor,length);
   for(int side=-1;side<=1;side++) {
    int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
    if(x==cell.getX()&&z==cell.getZ())return true;
   }
  }
  return false;
 }

 /** Loaded surface cells of the already-built plaza-to-bell corridor, including its walkable shoulders. */
 public static List<BlockPos> savedVillagePathGrounds(ServerLevel level,VillageRef ref) {
  if(ref==null||ref.bellEntity()==null)return List.of();
  BlockPos plaza=Market.of(ref).plaza().orElse(null);
  if(plaza==null)return List.of();
  Map<String,Long> state=ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
  int length=routeLength(plaza,ref.bell());
  int built=(int)Math.max(0L,Math.min((long)length+1L,state.getOrDefault("center",0L)));
  if(built<=0)return List.of();
  Map<Long,BlockPos> cells=new LinkedHashMap<>();
  for(int cursor=0;cursor<built;cursor++) {
   BlockPos route=routePoint(plaza,ref.bell(),cursor,length);
   boolean alongX=alongXAt(plaza,ref.bell(),cursor,length);
   for(int side=-1;side<=1;side++) {
    int x=route.getX()+(alongX?0:side),z=route.getZ()+(alongX?side:0);
    BlockPos column=new BlockPos(x,route.getY(),z);
    if(!level.hasChunkAt(column))continue;
    BlockPos ground=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
    // x/z was produced directly from routePoint within the saved built range above, and
    // trustedVillagePathCell intentionally ignores Y. Re-scanning that route here would
    // make this full-path enumeration quadratic in its length.
    if(level.hasChunkAt(ground))cells.putIfAbsent(ground.asLong(),ground.immutable());
   }
  }
  return List.copyOf(cells.values());
 }

 /** Canonical X-then-Z Manhattan route. Reversing endpoints returns exactly the same path backward. */
 static BlockPos routePoint(BlockPos a,BlockPos b,int cursor,int length) {
  return legacyRoutePoint(a,b,cursor,length);
 }

 static BlockPos legacyRoutePoint(BlockPos a,BlockPos b,int cursor,int length) {
  if(length<=0)return a;
  boolean forward=a.asLong()<=b.asLong();BlockPos start=forward?a:b,end=forward?b:a;
  int index=forward?cursor:length-cursor;
  index=Math.max(0,Math.min(length,index));
  int dx=end.getX()-start.getX(),dz=end.getZ()-start.getZ();
  int x=start.getX()+(int)Math.copySign(Math.min(Math.abs(dx),index),dx);
  int z=start.getZ()+(int)Math.copySign(Math.max(0,index-Math.abs(dx)),dz);
  return new BlockPos(x,a.getY(),z);
 }

 static boolean alongXAt(BlockPos a,BlockPos b,int cursor,int length) {
  if(length<=0)return true;
  boolean forward=a.asLong()<=b.asLong();BlockPos start=forward?a:b,end=forward?b:a;
  int index=forward?cursor:length-cursor;
  int xSteps=Math.abs(end.getX()-start.getX());
  if(index<xSteps)return true;
  if(index>xSteps)return false;
  return xSteps>0;
 }

 static int stationStartCursor(BlockPos a,BlockPos b,BlockPos station,int last,int total) {
  int selected=-1;double distance=24*24;
  for(int cursor=0;cursor<=last;cursor++) {
   BlockPos p=routePoint(a,b,cursor,total);double candidate=station.distSqr(p);
   if(candidate<=distance){selected=cursor;distance=candidate;}
  }
  return selected;
 }

 private static int findStationRailStart(ServerLevel level,BlockPos a,BlockPos b,BlockPos station,int last,int total,
                                         Map<String,Long> state,String routeKey) {
  int selected=-1;double distance=24*24;
  for(int cursor=0;cursor<=last;cursor++) {
   BlockPos point=RoadRoute.point(state,routeKey,a,b,cursor);
   if(!level.hasChunkAt(point))continue;
   RailSite site=findRailSite(level,point.getX(),point.getZ(),a.getY());
   if(site==null)continue;
   BlockState footing=level.getBlockState(site.support());
   BlockState rail=level.getBlockState(site.rail());
   if(!(railRoad(footing)||rail.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock))continue;
   double candidate=station.distSqr(point);
   if(candidate<distance){selected=cursor;distance=candidate;}
  }
  if(selected<0) {
   int fallback=stationStartCursor(a,b,station,last,total);
   if(fallback>=0) {
    BlockPos point=RoadRoute.point(state,routeKey,a,b,fallback);
    if(level.hasChunkAt(point)&&findRailSite(level,point.getX(),point.getZ(),a.getY())!=null)selected=fallback;
   }
  }
  return selected;
 }

	static boolean reciprocalMetropolis(ServerLevel level,VillageRef source,VillageRef target) {
	  if(!validRailVillage(level,source)||!validRailVillage(level,target)||source.bell().equals(target.bell()))return false;
	  String dimension=Network.dim(level);
	  return hasMetropolisLink(source,target,dimension)&&hasMetropolisLink(target,source,dimension);
	 }

	private static boolean hasMetropolisLink(VillageRef source,VillageRef target,String dimension) {
	  return Network.of(source.bellEntity()).links().stream().anyMatch(link->link.relation()>=METROPOLIS
	   &&link.dimension().equals(dimension)&&link.bell().equals(target.bell()));
	 }

	/** All project gates are checked on the server before either endpoint advances or pays for track. */
	static boolean railProjectReady(ServerLevel level,VillageRef source,VillageRef target) {
	  return reciprocalMetropolis(level,source,target)&&Network.projectApproved(source,target)
	   &&railReady(level,source)&&railReady(level,target);
	 }

	private static boolean validRailVillage(ServerLevel level,VillageRef ref) {
	  if(ref==null||ref.bell()==null||ref.data()==null||ref.bellEntity()==null||!level.hasChunkAt(ref.bell()))return false;
	  var entity=level.getBlockEntity(ref.bell());
	  return entity==ref.bellEntity()&&entity instanceof net.minecraft.world.level.block.entity.BellBlockEntity
	   &&entity.getAttached(VillagerPlus.VILLAGE)==ref.data();
	 }

	static boolean railReady(ServerLevel level,VillageRef ref) {
	  if(!validRailVillage(level,ref))return false;
	  int population=Villages.residents(level,ref,de.villagerplus.Config.VILLAGE_RADIUS,v->v.isAlive()).size();
	  if(population<RAIL_POP||!RailResources.hasCentralStorage(level,ref)||!railProgressReady(level,ref))return false;
	  // Each endpoint needs its own public iron, gold, and redstone reserve; rails are paid for per segment.
	  return RailResources.hasMetallurgyStock(level,ref);
	 }

	/** The builder's full civic sequence is the local civilization-progress gate for rail. */
	private static boolean railProgressReady(ServerLevel level,VillageRef ref) {
	  String[] depots={de.villagerplus.build.Blueprints.GRANARY,de.villagerplus.build.Blueprints.TOOLSHED,
	   de.villagerplus.build.Blueprints.STATION};
	  for(String type:depots) {
	   BlockPos origin=Specials.get(ref,type);
	   if(origin==null||!level.hasChunkAt(origin)||!RailResources.hasDepot(level,ref,type))return false;
	  }
	  if(!Specials.has(ref,de.villagerplus.build.Blueprints.KITCHEN))return false;
	  BlockPos kitchen=Specials.get(ref,de.villagerplus.build.Blueprints.KITCHEN);
	  if(kitchen==null||!level.hasChunkAt(kitchen))return false;
	  BlockPos school=ref.data().extra.school.orElse(null);
	  return school!=null&&level.hasChunkAt(school);
	 }

 /** Baut eine verbundene Schienenhälfte ab dem straßennahen Bahnhofszugang bis zur gemeinsamen Streckenmitte. */
 private static void advanceRail(ServerLevel level,BlockPos a,BlockPos b,String key,Map<String,Long> state,int budget,VillageRef ref,
                                 Map<String,Long> otherState){
  String routeKey=Network.dim(level)+b.asLong();
  String otherKey=Network.dim(level)+a.asLong();
  if(!RoadRoute.ensureWithRetry(level,a,b,state,routeKey,otherState,otherKey,level.getGameTime(),state.getOrDefault(key,0L)>0))return;
  int length=RoadRoute.length(state,routeKey,a,b);if(length==0)return;
  int last=length/2+1;
  int cursor=cursorFor(state,key,a,b);
  BlockPos station=Specials.get(ref,de.villagerplus.build.Blueprints.STATION);
  if(station==null)return;
  int stationStart=findStationRailStart(level,a,b,station,last,length,state,routeKey);
  if(stationStart<0)return;
  if(cursor<stationStart)cursor=stationStart;
  for(int count=0;cursor<=last&&count<budget;cursor++,count++){
   BlockPos route=RoadRoute.point(state,routeKey,a,b,cursor);int x=route.getX(),z=route.getZ();
   boolean alongX=RoadRoute.alongX(state,routeKey,a,b,cursor);
   BlockPos col=new BlockPos(x,a.getY(),z);if(!level.hasChunkAt(col))break;
   RailSite site=findRailSite(level,x,z,a.getY());
   if(site==null){
    if(cursor<=length/2&&state.getOrDefault("road:"+key,0L)<1) {state.put("road:"+key,1L);}
    break;
   }
   boolean power=cursor%6==3;
   BlockState existing=level.getBlockState(site.rail());
   boolean alreadyTrack=existing.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock;
   BlockPos previousPoint=cursor>stationStart?RoadRoute.point(state,routeKey,a,b,cursor-1):null;
   RailSite previous=previousPoint==null?null:findRailSite(level,previousPoint.getX(),previousPoint.getZ(),a.getY());
   boolean previousRail=previous!=null&&level.getBlockState(previous.rail()).getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock;
   if(alreadyTrack&&(!RailTransport.safeRail(level,site.rail())
    ||cursor>stationStart&&(!previousRail||!RailTransport.connected(level,previous.rail(),site.rail()))))break;
   boolean poweredTrack=alreadyTrack&&existing.is(Blocks.POWERED_RAIL)&&site.supported();
   boolean needsTrack=!alreadyTrack||power&&!poweredTrack;
   boolean trackPlaced=!needsTrack;
   if(needsTrack) {
    boolean needsSupport=power&&!site.supported();
    // Check the complete payment before clearing vegetation or replacing an incompatible rail.
    if(!RailResources.canPay(level,ref,power,needsSupport))break;
    BlockState oldSupport=level.getBlockState(site.support());
    if(!existing.isAir()&&(!alreadyTrack&&!existing.canBeReplaced()
     ||!level.setBlock(site.rail(),Blocks.AIR.defaultBlockState(),3)))break;
    if(needsSupport&&!level.setBlock(site.support(),Blocks.REDSTONE_BLOCK.defaultBlockState(),3)) {
     if(!existing.isAir())level.setBlock(site.rail(),existing,3);
     break;
    }
    var railBlock=(net.minecraft.world.level.block.BaseRailBlock)(power?Blocks.POWERED_RAIL:Blocks.RAIL);
    var shape=alongX?net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST
     :net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH;
    BlockState desired=railBlock.defaultBlockState().setValue(railBlock.getShapeProperty(),shape);
    if(level.setBlock(site.rail(),desired,3)) {
     boolean joinsPrevious=RailTransport.safeRail(level,site.rail())
      &&(cursor==stationStart||(previousRail&&RailTransport.connected(level,previous.rail(),site.rail())));
     if(joinsPrevious&&RailResources.pay(level,ref,power,needsSupport))trackPlaced=true;
     else {
      level.setBlock(site.rail(),existing,3);
      if(needsSupport)level.setBlock(site.support(),oldSupport,3);
      break;
     }
    } else {
     if(!existing.isAir())level.setBlock(site.rail(),existing,3);
     if(needsSupport)level.setBlock(site.support(),oldSupport,3);
     break;
    }
   }
   // Existing rails are reused unless an unpowered scheduled point needs its paid powered-rail upgrade.
   if(trackPlaced&&cursor==stationStart&&!platform(level,ref,x,site.rail().getY()-1,z,alongX))break;
  }
  state.put(key,(long)cursor);
 }

 private record RailSite(BlockPos support,BlockPos rail,boolean supported) { }

 private static RailSite findRailSite(ServerLevel level,int x,int z,int platformY) {
  int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
  int high=platformY>level.getMinY()+4?Math.max(top,platformY+1):top;
  int low=Math.min(top-3,platformY>level.getMinY()+4?platformY-4:top-3);
  for(int y=high;y>=low;y--) {
   BlockPos pos=new BlockPos(x,y,z);BlockState state=level.getBlockState(pos);
   if(state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock) {
    BlockPos below=pos.below();return new RailSite(below,pos,level.getBlockState(below).is(Blocks.REDSTONE_BLOCK));
   }
   if(state.is(Blocks.REDSTONE_BLOCK)&&railRoad(level.getBlockState(pos.below())))return new RailSite(pos,pos.above(),true);
   if(railRoad(state))return new RailSite(pos,pos.above(),false);
  }
  return null;
 }

 private static boolean railRoad(BlockState state) {
  return state.is(Blocks.GRAVEL)||state.is(Blocks.SMOOTH_SANDSTONE)||state.is(Blocks.SPRUCE_PLANKS);
 }

 private static int cursorFor(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  String from=key+":from",to=key+":to",geometry=key+":geometry";
  if(state.getOrDefault(from,Long.MIN_VALUE)!=a.asLong()||state.getOrDefault(to,Long.MIN_VALUE)!=b.asLong()
   ||state.getOrDefault(geometry,0L)!=ROUTE_GEOMETRY_VERSION) {
   state.put(key,0L);
   state.put(from,a.asLong());
   state.put(to,b.asLong());
   state.put(geometry,ROUTE_GEOMETRY_VERSION);
   return 0;
  }
  return state.getOrDefault(key,0L).intValue();
 }

 /** Places the station platform only when the complete build is clear and its real public-stock cost is paid. */
 private static boolean platform(ServerLevel level,VillageRef village,int x,int y,int z,boolean alongX){
  int sx=alongX?0:1,sz=alongX?1:0; // Seitenrichtung
  List<PlatformBlock> placements=new ArrayList<>();
  for(int along=-2;along<=2;along++) for(int w=2;w<=3;w++){
   BlockPos p=new BlockPos(x+(alongX?along:w*sx),y,z+(alongX?w*sz:along));
   if(!planPlatformBlock(level,placements,p,Blocks.STONE_BRICKS.defaultBlockState(),true))return false;
  }
  for(int along=-2;along<=2;along+=4){
   BlockPos post=new BlockPos(x+(alongX?along:3*sx),y+1,z+(alongX?3*sz:along));
   for(int h=0;h<3;h++) if(!planPlatformBlock(level,placements,post.above(h),Blocks.SPRUCE_FENCE.defaultBlockState(),false))return false;
  }
  for(int along=-2;along<=2;along++) for(int w=2;w<=3;w++){
   BlockPos roof=new BlockPos(x+(alongX?along:w*sx),y+4,z+(alongX?w*sz:along));
   if(!planPlatformBlock(level,placements,roof,Blocks.SPRUCE_SLAB.defaultBlockState(),false))return false;
  }
  BlockPos bench=new BlockPos(x+(alongX?0:3*sx),y+1,z+(alongX?3*sz:0));
  if(!planPlatformBlock(level,placements,bench,Blocks.SPRUCE_STAIRS.defaultBlockState(),false))return false;
  BlockPos lamp=new BlockPos(x+(alongX?0:2*sx),y+3,z+(alongX?2*sz:0));
  if(!planPlatformBlock(level,placements,lamp,Blocks.LANTERN.defaultBlockState()
    .setValue(net.minecraft.world.level.block.LanternBlock.HANGING,true),false))return false;

  Map<Item,Integer> cost=new LinkedHashMap<>();
  for(PlatformBlock placement:placements)cost.merge(placement.desired().getBlock().asItem(),1,Integer::sum);
  List<Container> stores=RailResources.stores(level,village);
  List<PlatformStockSnapshot> payment=takePlatformMaterials(stores,cost);
  if(payment==null)return false;
  List<PlatformBlock> placed=new ArrayList<>();
  for(PlatformBlock placement:placements){
   if(!level.setBlock(placement.position(),placement.desired(),3)){
    rollbackPlatform(level,placed);
    restorePlatformMaterials(payment);
    return false;
   }
   placed.add(placement);
  }
  return true;
 }

 private record PlatformBlock(BlockPos position,BlockState before,BlockState desired) { }
 private record PlatformStockSnapshot(Container container,int slot,ItemStack stack) { }

 private static boolean planPlatformBlock(ServerLevel level,List<PlatformBlock> placements,BlockPos pos,
                                          BlockState desired,boolean floor){
  if(pos.getY()<level.getMinY()||pos.getY()>=level.getMaxY()||!level.hasChunkAt(pos)
    ||level.getBlockEntity(pos)!=null)return false;
  BlockState before=level.getBlockState(pos);
  if(before.is(desired.getBlock()))return true;
  boolean replaceable=floor&&(before.isAir()||before.canBeReplaced()||before.is(BlockTags.DIRT)
    ||before.is(Blocks.GRASS_BLOCK)||before.is(Blocks.SAND));
  if(!before.isAir()&&!replaceable)return false;
  placements.add(new PlatformBlock(pos.immutable(),before,desired));
  return true;
 }

 private static List<PlatformStockSnapshot> takePlatformMaterials(List<Container> stores,Map<Item,Integer> cost){
  if(cost.isEmpty())return List.of();
  Map<Item,Integer> available=new HashMap<>();
  for(Container box:stores)for(int slot=0;slot<box.getContainerSize();slot++){
   ItemStack stack=box.getItem(slot);
   if(!stack.isEmpty()&&cost.containsKey(stack.getItem()))available.merge(stack.getItem(),stack.getCount(),Integer::sum);
  }
  for(var item:cost.entrySet())if(available.getOrDefault(item.getKey(),0)<item.getValue())return null;

  List<PlatformStockSnapshot> before=new ArrayList<>();
  for(Container box:stores)for(int slot=0;slot<box.getContainerSize();slot++){
   ItemStack stack=box.getItem(slot);
   if(!stack.isEmpty()&&cost.containsKey(stack.getItem()))before.add(new PlatformStockSnapshot(box,slot,stack.copy()));
  }
  for(var item:cost.entrySet()){
   int left=item.getValue();
   for(Container box:stores)for(int slot=0;slot<box.getContainerSize()&&left>0;slot++){
    ItemStack stack=box.getItem(slot);
    if(stack.isEmpty()||!stack.is(item.getKey()))continue;
    int take=Math.min(left,stack.getCount());
    stack.shrink(take);
    if(stack.isEmpty())box.setItem(slot,ItemStack.EMPTY);
    box.setChanged();
    left-=take;
   }
   if(left>0){restorePlatformMaterials(before);return null;}
  }
  return before;
 }

 private static void restorePlatformMaterials(List<PlatformStockSnapshot> before){
  for(PlatformStockSnapshot entry:before){
   entry.container().setItem(entry.slot(),entry.stack().copy());
   entry.container().setChanged();
  }
 }

 private static void rollbackPlatform(ServerLevel level,List<PlatformBlock> placed){
  for(int index=placed.size()-1;index>=0;index--){
   PlatformBlock placement=placed.get(index);
   level.setBlock(placement.position(),placement.before(),3);
  }
 }

 /** Fortschritt der Überlandstrecke 0..100 (für die Tafel) – aus dem eigenen Cursor, die andere Hälfte baut das Nachbardorf. */
 public static int progress(VillageRef ref,TradeNetwork.Link link,BlockPos from){
  Map<String,Long> state=ref.bellEntity().getAttached(VillagerPlus.ROADS);if(state==null)return 0;
  BlockPos source=ref.bell();
  String key=link.dimension()+link.bell().asLong();
  int length=RoadRoute.length(state,key,source,link.bell());if(length==0)return 100;
  long cursor=state.getOrDefault(key,0L);
  long start=state.getOrDefault(key+":start",0L);
  if(start>length/2+1)return 100;
  return (int)Math.max(0,Math.min(100,(cursor-start)*100/Math.max(1,length/2+2-start)));
 }

 /** Join a nearby, already paved incoming route and follow it toward this village. */
 static BlockPos caravanWaypoint(ServerLevel level,VillageRef ref,BlockPos current) {
  int bestCursor=-1,bestLength=0;BlockPos bestOther=null;double bestDistance=20*20;
  Map<String,Long> state=new HashMap<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of()));
  for(TradeNetwork.Link link:Network.of(ref.bellEntity()).links()) {
   if(!roadLinkActive(link)||!link.dimension().equals(Network.dim(level)))continue;
   BlockPos other=link.bell();
   String routeKey=link.dimension()+other.asLong();
   VillageRef peer=Network.resolve(level,link);
   Map<String,Long> peerState=peer==null?null:peer.bellEntity().getAttachedOrElse(VillagerPlus.ROADS,Map.of());
   String peerKey=Network.dim(level)+ref.bell().asLong();
   if(!RoadRoute.ensureWithRetry(level,ref.bell(),other,state,routeKey,peerState,peerKey,level.getGameTime(),false))continue;
   int length=RoadRoute.length(state,routeKey,ref.bell(),other);if(length==0)continue;
   for(int i=0;i<=length/2;i++) {
   BlockPos route=RoadRoute.point(state,routeKey,ref.bell(),other,i);int x=route.getX(),z=route.getZ();
    if(!level.hasChunkAt(new BlockPos(x,ref.bell().getY(),z)))continue;
    int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;BlockState road=level.getBlockState(new BlockPos(x,y,z));
    if(!(road.is(Blocks.GRAVEL)||road.is(Blocks.SMOOTH_SANDSTONE)||road.is(Blocks.SPRUCE_PLANKS)))continue;
    double distance=current.distSqr(new BlockPos(x,y,z));if(distance<bestDistance){bestDistance=distance;bestCursor=i;bestOther=other;bestLength=length;}
   }
  }
  ref.bellEntity().setAttached(VillagerPlus.ROADS,Map.copyOf(state));ref.dirty();
  if(bestCursor<0)return null;
  int waypoint=Math.max(0,bestCursor-6);
  String routeKey=Network.dim(level)+bestOther.asLong();
  BlockPos point=RoadRoute.point(state,routeKey,ref.bell(),bestOther,waypoint);int x=point.getX(),z=point.getZ();
  return new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
 }

 private static boolean clearable(BlockState s){
  return s.isAir()||s.canBeReplaced()||s.is(BlockTags.LEAVES)||s.is(BlockTags.FLOWERS)||s.is(Blocks.SNOW)||s.is(Blocks.VINE);
 }

 /** Resolves terrain below a naturally rooted tree before paving; buildings and placed timber remain untouched. */
 static BlockPos surface(ServerLevel level,int x,int z){
  int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
  BlockPos topPos=new BlockPos(x,top,z);
  if(!level.getBlockState(topPos).is(BlockTags.LOGS))return topPos;
  int lowest=Math.max(level.getMinY(),top-32);
  int cursor=top;
  while(cursor>=lowest){
   if(!level.getBlockState(new BlockPos(x,cursor,z)).is(BlockTags.LOGS)){cursor--;continue;}
   int base=cursor;
   while(base>lowest&&level.getBlockState(new BlockPos(x,base-1,z)).is(BlockTags.LOGS))base--;
   BlockPos root=new BlockPos(x,base,z);
   if(cursor-base>=3&&naturalSoil(level.getBlockState(root.below()))&&hasNearbyLeaves(level,x,z,cursor)){
    for(int y=base;y<=cursor;y++){
     BlockPos trunk=new BlockPos(x,y,z);
     if(level.getBlockState(trunk).is(BlockTags.LOGS))level.setBlock(trunk,Blocks.AIR.defaultBlockState(),3);
    }
    return root.below();
   }
   cursor=base-1;
  }
  return topPos;
 }

 private static boolean naturalSoil(BlockState state){
  return state.is(BlockTags.DIRT)||state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.MYCELIUM)
   ||state.is(Blocks.PODZOL)||state.is(Blocks.COARSE_DIRT)||state.is(Blocks.SNOW_BLOCK)
   ||state.is(Blocks.SAND)||state.is(Blocks.RED_SAND)||state.is(Blocks.GRAVEL);
 }

 private static boolean hasNearbyLeaves(ServerLevel level,int x,int z,int top){
  int minY=Math.max(level.getMinY(),top-3),maxY=Math.min(level.getMaxY()-1,top+4);
  for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int y=minY;y<=maxY;y++){
   BlockPos pos=new BlockPos(x+dx,y,z+dz);
   if(level.hasChunkAt(pos)&&level.getBlockState(pos).is(BlockTags.LEAVES))return true;
  }
  return false;
 }

 private static void clearSoftGrowth(ServerLevel level,BlockPos ground){
  for(int h=1;h<=4;h++){
   BlockPos above=ground.above(h);BlockState state=level.getBlockState(above);
   if(clearable(state)&&!state.isAir())level.setBlock(above,Blocks.AIR.defaultBlockState(),3);
  }
 }

 private static void advance(ServerLevel level,BlockPos a,BlockPos b,String key,Map<String,Long> state,int budget,boolean overland) {
  int length=routeLength(a,b);if(length==0)return;
  int last=overland?length/2+1:length; // Überland: nur die eigene Hälfte
  int cursor=cursorFor(state,key,a,b);
  for(int count=0;cursor<=last&&count<budget;cursor++,count++) {
   boolean alongX=alongXAt(a,b,cursor,length);
   BlockPos route=routePoint(a,b,cursor,length);int x=route.getX(),z=route.getZ();
   BlockPos col=new BlockPos(x,a.getY(),z);if(!level.hasChunkAt(col))break;
   boolean loaded=true;for(int side=-2;side<=2;side++) {
    BlockPos adjacent=new BlockPos(x+(alongX?0:side),a.getY(),z+(alongX?side:0));
    if(!level.hasChunkAt(adjacent)){loaded=false;break;}
   }
   if(!loaded)break;
   BlockPos previous=cursor>0?routePoint(a,b,cursor-1,length):null;
   for(int side=-1;side<=1;side++) {
    int px=x+(alongX?0:side),pz=z+(alongX?side:0);
    if(overland) paveOverland(level,px,pz,side==0);
    else {
     int targetY=surface(level,px,pz).getY();
     if(previous!=null) {
      int previousX=previous.getX()+(alongX?0:side),previousZ=previous.getZ()+(alongX?side:0);
      int previousY=pathY(level,previousX,previousZ);
      if(previousY!=Integer.MIN_VALUE)targetY=grade(targetY,previousY);
     }
     pavePath(level,px,pz,targetY);
    }
   }
  }
  state.put(key,(long)cursor);
 }

 static void pavePath(ServerLevel level,int px,int pz){
  BlockPos ground=surface(level,px,pz);
  pavePath(level,px,pz,ground.getY());
 }

 static int grade(int terrainY,int previousPathY){
  return Math.max(previousPathY-1,Math.min(previousPathY+1,terrainY));
 }

 private static int pathY(ServerLevel level,int x,int z){
  int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
  for(int y=top+2;y>=top-4;y--){
   BlockPos pos=new BlockPos(x,y,z);
   if(level.hasChunkAt(pos)&&level.getBlockState(pos).is(Blocks.DIRT_PATH))return y;
  }
  return Integer.MIN_VALUE;
 }

 static void pavePath(ServerLevel level,int px,int pz,int targetY){
  BlockPos ground=surface(level,px,pz);var s=level.getBlockState(ground);
  if(s.is(Blocks.FARMLAND)||s.hasBlockEntity())return;
  if(!s.getFluidState().isEmpty()){
   buildFootbridge(level,ground);
   return;
  }
  int difference=targetY-ground.getY();
  if(Math.abs(difference)>2)return;
  if(difference<0){
   for(int y=ground.getY();y>targetY;y--){
    BlockPos cut=new BlockPos(px,y,pz);BlockState cutState=level.getBlockState(cut);
    if(!naturalSoil(cutState)||cutState.hasBlockEntity()||!cutState.getFluidState().isEmpty()||occupied(level,cut))return;
   }
  }
  BlockPos target=new BlockPos(px,targetY,pz);
  BlockState targetState=level.getBlockState(target);
  if(difference>0){
   if(!targetState.isAir()&&!targetState.canBeReplaced()||targetState.hasBlockEntity()||!targetState.getFluidState().isEmpty()||occupied(level,target))return;
   for(int y=ground.getY()+1;y<targetY;y++){
    BlockPos fill=new BlockPos(px,y,pz);BlockState fillState=level.getBlockState(fill);
    if((!fillState.isAir()&&!fillState.canBeReplaced())||fillState.hasBlockEntity()||!fillState.getFluidState().isEmpty()||occupied(level,fill))return;
   }
  }
  if(!naturalSoil(level.getBlockState(new BlockPos(px,Math.min(ground.getY(),targetY),pz))))return;
  if(difference<0){
   for(int y=ground.getY();y>targetY;y--)level.setBlock(new BlockPos(px,y,pz),Blocks.AIR.defaultBlockState(),3);
  }else if(difference>0){
   for(int y=ground.getY()+1;y<targetY;y++)level.setBlock(new BlockPos(px,y,pz),Blocks.DIRT.defaultBlockState(),3);
   level.setBlock(target,Blocks.DIRT.defaultBlockState(),3);
  }
  ground=target;s=level.getBlockState(ground);
  clearSoftGrowth(level,ground);
  BlockState above=level.getBlockState(ground.above());
  if((!above.isAir()&&!above.canBeReplaced()) || above.hasBlockEntity() || s.is(Blocks.FARMLAND)||s.hasBlockEntity())return;
  if(!naturalSoil(s)||!s.isFaceSturdy(level,ground,net.minecraft.core.Direction.UP))return;
  if(!above.isAir())level.setBlock(ground.above(),Blocks.AIR.defaultBlockState(),3);
  level.setBlock(ground,Blocks.DIRT_PATH.defaultBlockState(),3);
 }

 private static boolean occupied(ServerLevel level,BlockPos pos){
  AABB box=new AABB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1);
  return !level.getEntities((Entity)null,box,Entity::isAlive).isEmpty();
 }

 private static void paveOverland(ServerLevel level,int px,int pz,boolean center){
  BlockPos ground=surface(level,px,pz);int y=ground.getY();var s=level.getBlockState(ground);
  if(s.is(Blocks.FARMLAND)||s.hasBlockEntity())return; // Felder und Kisten/Schilder der Dörfer nie anfassen
  // Vegetation im Korridor entfernen, nachdem natürlich verwurzelte Stämme bis zur Krone geräumt wurden.
  for(int h=1;h<=3;h++){
   BlockPos p=ground.above(h);var up=level.getBlockState(p);
   if(clearable(up)&&!up.isAir()){level.setBlock(p,Blocks.AIR.defaultBlockState(),3);}
  }
  if(!level.getBlockState(ground.above()).isAir())return; // Haus, Mauer o. ä. – nicht durchbauen
  boolean sandy=sandPalette(level,ground,s);
  Block pave=center?(sandy?Blocks.SMOOTH_SANDSTONE:Blocks.GRAVEL):(sandy?Blocks.SANDSTONE:Blocks.COBBLESTONE);
  if(!s.getFluidState().isEmpty()){
   buildFootbridge(level,ground);
   return;
  }
  if(s.is(BlockTags.DIRT)||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.SAND)||s.is(Blocks.RED_SAND)||s.is(Blocks.GRAVEL)||s.is(Blocks.DIRT_PATH)||s.is(Blocks.STONE)||s.is(Blocks.SNOW_BLOCK)||s.is(Blocks.PODZOL)||s.is(Blocks.COARSE_DIRT))
   level.setBlock(ground,pave.defaultBlockState(),3);
 }

 static void buildFootbridge(ServerLevel level,BlockPos deck){
  int supportDepth=0;
  for(int depth=1;depth<=24;depth++){
   BlockPos pile=deck.below(depth);if(!level.hasChunkAt(pile))return;
   BlockState bed=level.getBlockState(pile);
   if(bed.isFaceSturdy(level,pile,net.minecraft.core.Direction.UP)){supportDepth=depth;break;}
   if(!bed.isAir()&&bed.getFluidState().isEmpty())return;
  }
  if(supportDepth==0)return;
  for(int depth=1;depth<supportDepth;depth++)level.setBlock(deck.below(depth),Blocks.SPRUCE_LOG.defaultBlockState(),3);
  level.setBlock(deck,Blocks.SPRUCE_PLANKS.defaultBlockState(),3);
 }

}
