package de.villagerplus.village;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Isolated world regressions for persisted, bounded overland-route geometry. */
public final class RoadRouteChecks {
 private RoadRouteChecks() { }

 /** Integration API: run on a dedicated synthetic development-world pad; all touched blocks are restored. */
 public static int run(ServerLevel level,BlockPos origin) {
  Map<BlockPos,BlockState> before=new LinkedHashMap<>();
  snapshotPad(level,before,origin);
  int checks=0;
  try {
   buildPad(level,origin);
   checks+=dryGorgeBridgeCheck(level,origin,before);
   buildPad(level,origin);
   BlockPos west=origin.west(18),east=origin.east(18);
   String rejectedKey="road-route-check:rejected-bridge";
   String rejectedBridge=rejectedKey+":bridge:1:0";
   Map<String,Long> rejectedBridgeState=new HashMap<>();
   rejectedBridgeState.put(rejectedBridge,(long)origin.getY());
   require(!Roads.rememberBridgePlan(rejectedBridgeState,rejectedBridge,origin.getY(),null)
     &&!rejectedBridgeState.containsKey(rejectedBridge),
    "a rejected bridge plan clears its unbuilt deck marker");checks++;
   require(!Roads.routeCursorHasWork(level,rejectedBridgeState,rejectedKey,west,east,1),
    "a rejected bridge plan does not suppress a safe route replan");checks++;
   String routeKey="road-route-check:detour";
   Map<String,Long> persisted=new HashMap<>();
   // A steep, paved-material mound tests the longitudinal grade rule independently of the wall.
   for(int z=-2;z<=2;z++)for(int y=1;y<=3;y++)
    level.setBlock(origin.offset(8,y,z),Blocks.DIRT.defaultBlockState(),3);
   // A short building wall blocks the direct line but leaves a bounded, loaded bypass.
   for(int z=-2;z<=2;z++)for(int y=1;y<=3;y++)
    level.setBlock(origin.offset(0,y,z),Blocks.BRICKS.defaultBlockState(),3);
   require(RoadRoute.ensure(level,west,east,persisted,routeKey,null,null),"bounded planner finds a route around a building wall");checks++;
   int length=RoadRoute.length(persisted,routeKey,west,east);
   require(length>Roads.routeLength(west,east),"the obstruction produces a real detour");checks++;

   Set<BlockPos> union=new HashSet<>();BlockPos previous=null;Set<BlockPos> previousLanes=Set.of();
   for(int i=0;i<=length;i++) {
    BlockPos forward=RoadRoute.point(persisted,routeKey,west,east,i);
    BlockPos reverse=RoadRoute.point(persisted,routeKey,east,west,length-i);
    require(forward.getX()==reverse.getX()&&forward.getZ()==reverse.getZ(),"stored route is exactly reversible at step "+i);checks++;
    if(previous!=null) {
     int dx=Math.abs(forward.getX()-previous.getX()),dz=Math.abs(forward.getZ()-previous.getZ());
     require(dx+dz==1,"route uses only four-neighbor steps at "+i);checks++;
    }
    boolean alongX=RoadRoute.alongX(persisted,routeKey,west,east,i);
    Set<BlockPos> lanes=new HashSet<>();int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
    for(int side=-1;side<=1;side++) {
     int x=forward.getX()+(alongX?0:side),z=forward.getZ()+(alongX?side:0);
     RoadClearing.Column column=RoadClearing.column(level,null,x,z,new HashMap<>(),"route-check");
     require(column!=null,"each route section has three inspectable lanes");checks++;
     low=Math.min(low,column.ground().getY());high=Math.max(high,column.ground().getY());
     BlockState ground=level.getBlockState(column.ground());
     require(!ground.is(Blocks.BRICKS),"no lane is routed through the building footprint");checks++;
     lanes.add(new BlockPos(x,0,z));union.add(new BlockPos(x,0,z));
    }
    require(high-low<=1,"three-lane cross-section has at most one block of crossfall");checks++;
    if(!previousLanes.isEmpty()) {
     for(BlockPos lane:previousLanes) {
      boolean connected=lanes.stream().anyMatch(next->Math.abs(lane.getX()-next.getX())+Math.abs(lane.getZ()-next.getZ())<=1);
      require(connected,"lane edge has no gap at a straight or curved join");checks++;
     }
    }
    require(RoadRoute.gradeSafe(level,persisted,routeKey,west,east,i),"longitudinal profile stays within one block per step at "+i);checks++;
    previous=forward;previousLanes=lanes;
   }
   require(!union.isEmpty(),"detoured road exposes a nonempty three-lane footprint");checks++;

   // A saved path and in-progress cursor survive a codec-like map copy unchanged.
   persisted.put(routeKey,(long)Math.min(5,length/2));persisted.put(routeKey+":start",0L);
   Map<String,Long> reloaded=new HashMap<>(Map.copyOf(persisted));long savedCursor=reloaded.get(routeKey);
   require(RoadRoute.ensure(level,east,west,reloaded,routeKey,null,null),"reloaded canonical geometry is accepted from the opposite endpoint");checks++;
   require(reloaded.get(routeKey)==savedCursor&&RoadRoute.length(reloaded,routeKey,west,east)==length,
    "reloading the route does not change its cursor or length");checks++;
   for(int i=0;i<=length;i++) {
    BlockPos a=RoadRoute.point(reloaded,routeKey,west,east,i),b=RoadRoute.point(reloaded,routeKey,east,west,length-i);
    require(a.getX()==b.getX()&&a.getZ()==b.getZ(),"reloaded route remains exactly reversible at "+i);checks++;
   }

   // Old paid line progress must keep the old Manhattan route, even when a newer detour is possible.
   String legacyKey="road-route-check:legacy";Map<String,Long> legacy=new HashMap<>();
   legacy.put(legacyKey,9L);legacy.put(legacyKey+":start",4L);
   require(RoadRoute.ensure(level,west,east,legacy,legacyKey,null,null),"legacy route cursor is retained");checks++;
   int legacyLength=Roads.routeLength(west,east);
   require(legacy.get(legacyKey)==9L&&RoadRoute.length(legacy,legacyKey,west,east)==legacyLength,
    "paid legacy cursor and Manhattan length are unchanged");checks++;
   for(int i=0;i<=legacyLength;i++) {
    BlockPos expected=Roads.legacyRoutePoint(west,east,i,legacyLength),actual=RoadRoute.point(legacy,legacyKey,west,east,i);
    require(expected.equals(actual),"legacy path remains unchanged at "+i);checks++;
   }

   // Unknown terrain is planned as unknown without requesting/loading its chunk.
   // Keep both endpoints beyond the loaded fixture pad so real terrain just outside the pad
   // cannot affect this no-force-load assertion.
   BlockPos remoteStart=origin.east(800),remoteEnd=origin.east(1390);
   boolean remoteStartWasLoaded=level.hasChunkAt(remoteStart),remoteWasLoaded=level.hasChunkAt(remoteEnd);
   Map<String,Long> remote=new HashMap<>();
   require(RoadRoute.ensure(level,remoteStart,remoteEnd,remote,"road-route-check:remote",null,null),
    "planner can persist geometry while a distant route segment is unknown");checks++;
   require(level.hasChunkAt(remoteStart)==remoteStartWasLoaded&&!remoteStartWasLoaded
    &&level.hasChunkAt(remoteEnd)==remoteWasLoaded&&!remoteWasLoaded,
    "planning an unknown remote segment never loads its chunk");checks++;

   // A fully loaded barrier seals the bounded search; a retry timestamp suppresses immediate repeat work.
   for(int z=-16;z<=16;z++)for(int y=1;y<=4;y++)
    level.setBlock(origin.offset(0,y,z),Blocks.BRICKS.defaultBlockState(),3);
   String failedKey="road-route-check:sealed";Map<String,Long> failed=new HashMap<>();
   BlockPos shortWest=origin.west(10),shortEast=origin.east(10);
   boolean sealedAccepted=RoadRoute.ensureWithRetry(level,shortWest,shortEast,failed,failedKey,null,null,10_000,false);
   StringBuilder sealedRoute=new StringBuilder();
   if(sealedAccepted)for(int cursor=0;cursor<=RoadRoute.length(failed,failedKey,shortWest,shortEast);cursor++) {
    BlockPos point=RoadRoute.point(failed,failedKey,shortWest,shortEast,cursor);
    if(cursor>0)sealedRoute.append(',');
    sealedRoute.append(point.getX()-origin.getX()).append(',').append(point.getZ()-origin.getZ());
   }
   require(!sealedAccepted,"fully observed sealed corridor returns a bounded no-path result"
    +" (accepted="+sealedAccepted+", geometry="+failed.getOrDefault(failedKey+":routeGeometry",0L)
    +", count="+failed.getOrDefault(failedKey+":routeCount",0L)+", retryAt="
    +failed.getOrDefault(failedKey+":routeRetryAt",0L)+", sections=["+sealedRoute+"])");checks++;
   long retryAt=failed.getOrDefault(failedKey+":routeRetryAt",0L);Map<String,Long> failedSnapshot=Map.copyOf(failed);
   require(retryAt>10_000,"failed geometry stores a future retry time");checks++;
   boolean repeatedAccepted=RoadRoute.ensureWithRetry(level,shortWest,shortEast,failed,failedKey,null,null,10_001,false);
   require(!repeatedAccepted&&failed.equals(failedSnapshot),"repeated call inside the retry window does not rerun or mutate the failed plan"
    +" (accepted="+repeatedAccepted+", before="+failedSnapshot+", after="+failed+")");checks++;
   return checks;
  } finally {
   before.forEach((pos,state)->level.setBlock(pos,state,3));
  }
 }

 private static int dryGorgeBridgeCheck(ServerLevel level,BlockPos origin,Map<BlockPos,BlockState> before) {
  for(int x=-2;x<=3;x++)for(int z=-1;z<=1;z++)for(int y=-10;y<=-2;y++) {
   BlockPos pos=origin.offset(x,y,z).immutable();level.getChunkAt(pos);before.putIfAbsent(pos,level.getBlockState(pos));
  }
  for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++) {
   for(int y=0;y>=-7;y--)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(origin.offset(x,-8,z),Blocks.STONE.defaultBlockState(),3);
   // The stone bed can anchor bridge supports, but gravel cannot be placed on it
   // because its own support cell is open air. This distinguishes bridgeability
   // from ordinary surface-paving eligibility.
   level.setBlock(origin.offset(x,-9,z),Blocks.AIR.defaultBlockState(),3);
  }

  BlockPos west=origin.west(10),east=origin.east(10);String key="road-route-check:dry-gorge";
  Map<String,Long> state=new HashMap<>();
  require(RoadRoute.ensure(level,west,east,state,key,null,null),"bounded planner finds the loaded dry-gorge crossing");
  int length=RoadRoute.length(state,key,west,east),spanned=0,checks=1;
  boolean directGorgeLine=length==Roads.routeLength(west,east);
  StringBuilder deckCursors=new StringBuilder(),routeSections=new StringBuilder();
  for(int cursor=0;cursor<=length;cursor++) {
   Integer deckY=RoadRoute.gorgeDeckY(state,key,west,east,cursor);
   Integer reverseY=RoadRoute.gorgeDeckY(state,key,east,west,length-cursor);
   require(java.util.Objects.equals(deckY,reverseY),"saved deck heights reverse with the shared route cursor");checks++;
   if(cursor>0)routeSections.append(',');
   BlockPos route=RoadRoute.point(state,key,west,east,cursor);
   if(route.getX()!=west.getX()+cursor||route.getZ()!=origin.getZ())directGorgeLine=false;
   routeSections.append(cursor).append('@').append(route.getX()-origin.getX()).append(',')
    .append(route.getZ()-origin.getZ()).append('=').append(deckY==null?"-":deckY);
   if(deckY==null)continue;
   if(deckCursors.length()>0)deckCursors.append(',');
   deckCursors.append(cursor).append('@').append(deckY);
   spanned++;
   require(deckY==origin.getY()&&route.getX()>=origin.getX()-2&&route.getX()<=origin.getX()+3,
    "dry-gorge deck profile includes both banks and stays level across the crossing");checks++;
   if(route.getX()<origin.getX()-1||route.getX()>origin.getX()+2)continue;
   for(int side=-1;side<=1;side++) {
    boolean alongX=RoadRoute.alongX(state,key,west,east,cursor);
    BlockPos deck=new BlockPos(route.getX()+(alongX?0:side),deckY,route.getZ()+(alongX?side:0));
    RoadBridge.Plan plan=RoadBridge.plan(level,deck);
    require(plan!=null&&plan.next()!=null&&plan.next().bridge()
      &&plan.next().paving().is(Blocks.SPRUCE_LOG)&&RoadBridge.validTask(level,plan.next()),
     "each lane exposes a safe, paid first support task from the gorge floor");checks++;
   }
  }
  require(spanned==6&&directGorgeLine,"the planner prefers the direct, bounded gorge bridge and persists its deck profile"
   +" (span="+spanned+"/6, routeLength="+length+", routeSections="+(length+1)+", deckCursors=["+deckCursors
   +"], directLine="+directGorgeLine+", sections=["+routeSections+"])");checks++;

  String legacyKey="road-route-check:legacy-gorge";Map<String,Long> legacy=new HashMap<>();
  require(RoadRoute.ensure(level,west,east,legacy,legacyKey,null,null,true),"older paid path keeps its Manhattan geometry");checks++;
  require(RoadRoute.ensureGorgeProfileAtCursor(level,legacy,legacyKey,west,east,8)
    &&RoadRoute.gorgeDeckY(legacy,legacyKey,west,east,8)!=null
    &&RoadRoute.gorgeDeckY(legacy,legacyKey,east,west,Roads.routeLength(west,east)-8)!=null,
   "an older route can persist the same safe deck profile when it reaches the steep edge");checks++;
  for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++) {
   require(level.getBlockState(origin.offset(x,0,z)).isAir()
     &&level.getBlockState(origin.offset(x,-8,z)).is(Blocks.STONE),
    "route planning leaves the gorge and its support beds untouched");checks++;
  }
  return checks;
 }

 private static void snapshotPad(ServerLevel level,Map<BlockPos,BlockState> before,BlockPos origin) {
  for(int x=-35;x<=35;x++)for(int z=-32;z<=32;z++)for(int dy=-1;dy<=4;dy++) {
   BlockPos pos=origin.offset(x,dy,z).immutable();level.getChunkAt(pos);before.putIfAbsent(pos,level.getBlockState(pos));
  }
 }

 private static void buildPad(ServerLevel level,BlockPos origin) {
  for(int x=-35;x<=35;x++)for(int z=-32;z<=32;z++) {
   level.setBlock(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);
   level.setBlock(origin.offset(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState(),3);
   for(int y=1;y<=4;y++)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
  }
 }

 private static void require(boolean condition,String message) {
  if(!condition)throw new IllegalStateException("Road route: "+message);
 }
}
