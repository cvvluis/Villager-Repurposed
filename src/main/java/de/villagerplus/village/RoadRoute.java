package de.villagerplus.village;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Persistent, bounded, endpoint-order-independent horizontal geometry for overland routes. */
final class RoadRoute {
 private static final int MAX_MANHATTAN=640;
 private static final int MAX_EXTRA=48;
 private static final int MAX_DRY_GORGE_SPAN=16;
 private static final int MAX_GORGE_DEPTH=24;
 private static final int MARGIN=16;
 private static final int MAX_NODES=20_000;
 private static final String GEOMETRY="routeGeometry";
 private static final String COUNT="routeCount";
 private static final String POINT="routePoint:";
 private static final String GORGE_Y="gorgeY:";
 private RoadRoute() { }

 static boolean ensure(ServerLevel level,BlockPos a,BlockPos b,Map<String,Long> state,String key,
                       Map<String,Long> otherState,String otherKey) {
  return ensure(level,a,b,state,key,otherState,otherKey,false);
 }

 static boolean ensure(ServerLevel level,BlockPos a,BlockPos b,Map<String,Long> state,String key,
                       Map<String,Long> otherState,String otherKey,boolean preserveLegacy) {
  if(validStored(state,key,a,b))return true;
  if(validLegacy(state,key,a,b))return true;
  boolean localLegacy=preserveLegacy||state.getOrDefault(key,0L)>state.getOrDefault(key+":start",0L);
  if(localLegacy) {
   List<BlockPos> old=legacy(a,b);if(old==null){storeLegacy(state,key,a,b);return true;}
   store(state,key,a,b,old,2L,Map.of());return true;
  }
  // If the peer has already picked its path, reuse the same canonical sequence.
  if(otherState!=null&&otherKey!=null&&validStored(otherState,otherKey,a,b)) {
   copy(otherState,otherKey,state,key);
   return true;
  }
  if(otherState!=null&&otherKey!=null&&validLegacy(otherState,otherKey,a,b)) {
   storeLegacy(state,key,a,b);return true;
  }
  // Routes with paid progress from before geometry persistence keep their original X-then-Z line.
  boolean legacy=otherState!=null&&otherKey!=null
   &&otherState.getOrDefault(otherKey,0L)>otherState.getOrDefault(otherKey+":start",0L);
  List<BlockPos> legacyPoints=legacy?legacy(a,b):null;
  RoutePlan planned=legacy?(legacyPoints==null?null:new RoutePlan(legacyPoints,Map.of())):plan(level,a,b);
  if(planned==null)return false;
  store(state,key,a,b,planned.points(),legacy?2L:3L,planned.gorgeDeckYs());
  return true;
 }

 static boolean ensureWithRetry(ServerLevel level,BlockPos a,BlockPos b,Map<String,Long> state,String key,
                                Map<String,Long> otherState,String otherKey,long now,boolean preserveLegacy) {
  String retryKey=key+":routeRetryAt";
  if(state.getOrDefault(retryKey,0L)>now) {
   if(validStored(state,key,a,b)||validLegacy(state,key,a,b)) {
    state.remove(retryKey);return true;
   }
   boolean localPaidProgress=preserveLegacy
    ||state.getOrDefault(key,0L)>state.getOrDefault(key+":start",0L);
   boolean peerRouteAvailable=otherState!=null&&otherKey!=null
    &&(validStored(otherState,otherKey,a,b)||validLegacy(otherState,otherKey,a,b)
     ||otherState.getOrDefault(otherKey,0L)>otherState.getOrDefault(otherKey+":start",0L));
   // During cooldown, restore only already-paid/saved geometry. Do not rerun A* or
   // synthesize a fresh legacy path for a failed, empty route.
   if(localPaidProgress||peerRouteAvailable) {
    if(ensure(level,a,b,state,key,otherState,otherKey,preserveLegacy)) {
     state.remove(retryKey);return true;
    }
   }
   return false;
  }
  if(ensure(level,a,b,state,key,otherState,otherKey,preserveLegacy)) {
   state.remove(retryKey);return true;
  }
  state.put(retryKey,now+200);return false;
 }

 static boolean replanShared(ServerLevel level,BlockPos a,BlockPos b,Map<String,Long> state,String key,
                             Map<String,Long> otherState,String otherKey,long now) {
  clearGeometry(state,key);if(otherState!=null&&otherKey!=null)clearGeometry(otherState,otherKey);
  RoutePlan planned=plan(level,a,b);
  if(planned==null) {
   state.put(key+":routeRetryAt",now+200);
   if(otherState!=null&&otherKey!=null)otherState.put(otherKey+":routeRetryAt",now+200);
   return false;
  }
  store(state,key,a,b,planned.points(),3L,planned.gorgeDeckYs());
  if(otherState!=null&&otherKey!=null)store(otherState,otherKey,a,b,planned.points(),3L,planned.gorgeDeckYs());
  return true;
 }

 static void clearGeometry(Map<String,Long> state,String key) {
  String prefix=key+":";
  state.keySet().removeIf(name->name.equals(prefix+GEOMETRY)||name.equals(prefix+COUNT)
   ||name.equals(prefix+"routeFrom")||name.equals(prefix+"routeTo")||name.equals(prefix+"routeRetryAt")
   ||name.startsWith(prefix+POINT)||name.startsWith(prefix+GORGE_Y));
 }

 private static void store(Map<String,Long> state,String key,BlockPos a,BlockPos b,List<BlockPos> points,long version,
                           Map<Integer,Integer> gorgeDeckYs) {
  BlockPos low=a.asLong()<=b.asLong()?a:b,high=a.asLong()<=b.asLong()?b:a;
  state.put(key+":routeFrom",low.asLong());state.put(key+":routeTo",high.asLong());state.put(key+":"+GEOMETRY,version);
  state.put(key+":"+COUNT,(long)points.size());
  for(int i=0;i<points.size();i++)state.put(key+":"+POINT+i,points.get(i).asLong());
  state.keySet().removeIf(name->name.startsWith(key+":"+GORGE_Y));
  for(var entry:gorgeDeckYs.entrySet())state.put(key+":"+GORGE_Y+entry.getKey(),entry.getValue().longValue());
 }

 private static void storeLegacy(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  BlockPos low=a.asLong()<=b.asLong()?a:b,high=a.asLong()<=b.asLong()?b:a;
  state.put(key+":routeFrom",low.asLong());state.put(key+":routeTo",high.asLong());state.put(key+":"+GEOMETRY,2L);
  state.put(key+":"+COUNT,0L);
  state.keySet().removeIf(name->name.startsWith(key+":"+GORGE_Y));
 }

 static int length(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  if(validLegacy(state,key,a,b))return Roads.routeLength(a,b);
  if(validStored(state,key,a,b))return Math.max(0,state.get(key+":"+COUNT).intValue()-1);
  return Roads.routeLength(a,b);
 }

 static BlockPos point(Map<String,Long> state,String key,BlockPos a,BlockPos b,int cursor) {
  int length=length(state,key,a,b);int index=Math.max(0,Math.min(length,cursor));
  if(validStored(state,key,a,b)) {
   int canonical=a.asLong()<=b.asLong()?index:length-index;
   BlockPos saved=BlockPos.of(state.get(key+":"+POINT+canonical));
   return new BlockPos(saved.getX(),a.getY(),saved.getZ());
  }
  return Roads.legacyRoutePoint(a,b,index,length);
 }

 /** Saved walking-surface block Y for a dry gorge span, oriented to the caller's cursor. */
 static Integer gorgeDeckY(Map<String,Long> state,String key,BlockPos a,BlockPos b,int cursor) {
  int length=length(state,key,a,b);if(cursor<0||cursor>length)return null;
  int canonical=a.asLong()<=b.asLong()?cursor:length-cursor;
  Long y=state.get(key+":"+GORGE_Y+canonical);
  return y==null?null:y.intValue();
 }

 /** Adds a safe saved bridge profile around a newly reached steep edge on an older route. */
 static boolean ensureGorgeProfileAtCursor(ServerLevel level,Map<String,Long> state,String key,
                                           BlockPos a,BlockPos b,int cursor) {
  BlockPos ownerBell=Roads.loadedVillage(level,a)==null?null:a;
  BlockPos peerBell=Roads.loadedVillage(level,b)==null?null:b;
  return ensureGorgeProfileAtCursor(level,state,key,a,b,cursor,ownerBell,peerBell);
 }

 static boolean ensureGorgeProfileAtCursor(ServerLevel level,Map<String,Long> state,String key,
                                           BlockPos a,BlockPos b,int cursor,BlockPos ownerBell,BlockPos peerBell) {
  if(gorgeDeckY(state,key,a,b,cursor)!=null)return false;
  int length=length(state,key,a,b);
  for(int edge=Math.max(0,cursor-1);edge<=Math.min(length-1,cursor);edge++) {
   BlockPos from=point(state,key,a,b,edge),to=point(state,key,a,b,edge+1);
   int dx=to.getX()-from.getX(),dz=to.getZ()-from.getZ();
   Column fromColumn=column(level,from.getX(),from.getZ(),a),toColumn=column(level,to.getX(),to.getZ(),a);
   // A legacy route may reach a gorge through a known but non-paveable floor cell.
   // Let dryGorgeAhead validate the high bank, all interior lanes, supports, and ownership.
   if(!fromColumn.known()||!toColumn.known()||Math.abs(fromColumn.y()-toColumn.y())<=1)continue;
   int startX,startZ,scanX,scanZ;
   if(fromColumn.y()>toColumn.y()) {startX=from.getX();startZ=from.getZ();scanX=dx;scanZ=dz;}
   else {startX=to.getX();startZ=to.getZ();scanX=-dx;scanZ=-dz;}
   GorgeCrossing crossing=dryGorgeAhead(level,startX,startZ,scanX,scanZ,a,b,new HashMap<>(),ownerBell,peerBell);
   if(crossing==null)continue;
   Map<Long,Integer> cursorByPoint=new HashMap<>();int first=Integer.MAX_VALUE,last=Integer.MIN_VALUE;
   for(int routeCursor=0;routeCursor<=length;routeCursor++) {
    BlockPos route=point(state,key,a,b,routeCursor);
    long pointKey=key(route.getX(),route.getZ());
    if(!crossing.deckByPoint().containsKey(pointKey))continue;
    cursorByPoint.put(pointKey,routeCursor);first=Math.min(first,routeCursor);last=Math.max(last,routeCursor);
   }
   if(cursorByPoint.size()!=crossing.deckByPoint().size()||last-first+1!=crossing.deckByPoint().size())continue;
   boolean contiguous=true;
   for(int routeCursor=first;routeCursor<=last;routeCursor++) {
    BlockPos route=point(state,key,a,b,routeCursor);
    if(!crossing.deckByPoint().containsKey(key(route.getX(),route.getZ()))) {contiguous=false;break;}
   }
   if(!contiguous)continue;
   for(var entry:crossing.deckByPoint().entrySet()) {
    Integer routeCursor=cursorByPoint.get(entry.getKey());
    if(routeCursor==null)continue;
    int canonical=a.asLong()<=b.asLong()?routeCursor:length-routeCursor;
    state.put(key+":"+GORGE_Y+canonical,entry.getValue().longValue());
   }
   if(gorgeDeckY(state,key,a,b,cursor)!=null)return true;
  }
  return false;
 }

 /** Returns the loaded paved surface's feet cell only when the centerline is physically walkable. */
 static BlockPos walkablePavedFeet(ServerLevel level,BlockPos routePoint) {
  BlockPos probe=new BlockPos(routePoint.getX(),routePoint.getY(),routePoint.getZ());
  if(!level.hasChunkAt(probe))return null;
  int feetY=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,routePoint.getX(),routePoint.getZ());
  if(feetY<=level.getMinY()||feetY>=level.getMaxY()-1)return null;
  BlockPos ground=new BlockPos(routePoint.getX(),feetY-1,routePoint.getZ());
  BlockPos feet=new BlockPos(routePoint.getX(),feetY,routePoint.getZ());
  BlockPos head=feet.above();
  if(!level.hasChunkAt(ground)||!level.hasChunkAt(feet)||!level.hasChunkAt(head))return null;
  BlockState surface=level.getBlockState(ground);
  if(!isPavedRoad(surface)||surface.hasBlockEntity()||!surface.getFluidState().isEmpty()
   ||!surface.isFaceSturdy(level,ground,Direction.UP)
   ||!clearTravelCell(level,feet)||!clearTravelCell(level,head))return null;
  return feet;
 }

 private static boolean clearTravelCell(ServerLevel level,BlockPos pos) {
  BlockState state=level.getBlockState(pos);
  return state.getFluidState().isEmpty()&&state.getCollisionShape(level,pos).isEmpty();
 }

 private static boolean isPavedRoad(BlockState state) {
  return state.is(Blocks.GRAVEL)||state.is(Blocks.SMOOTH_SANDSTONE)||state.is(Blocks.SPRUCE_PLANKS);
 }

 static boolean alongX(Map<String,Long> state,String key,BlockPos a,BlockPos b,int cursor) {
  int length=length(state,key,a,b);if(length==0)return true;
  int canonical=a.asLong()<=b.asLong()?cursor:length-cursor;
  BlockPos here=point(state,key,a,b,cursor);
  int adjacent=canonical<length?canonical+1:canonical-1;
  int localAdjacent=a.asLong()<=b.asLong()?adjacent:length-adjacent;
  BlockPos next=point(state,key,a,b,localAdjacent);
  return here.getX()!=next.getX();
 }

 static boolean gradeSafe(ServerLevel level,Map<String,Long> state,String key,BlockPos a,BlockPos b,int cursor) {
  int length=length(state,key,a,b);int from=Math.max(0,cursor-1),to=Math.min(length,cursor+1);
  int previous=Integer.MIN_VALUE;
  for(int i=from;i<=to;i++) {
   BlockPos point=point(state,key,a,b,i);
   int endpointDistance=Math.min(Math.abs(point.getX()-a.getX())+Math.abs(point.getZ()-a.getZ()),
    Math.abs(point.getX()-b.getX())+Math.abs(point.getZ()-b.getZ()));
   if(endpointDistance<=8)continue;
   Column column=column(level,point.getX(),point.getZ(),a);
   if(!column.passable())return false;
   if(!column.known())continue;
   if(previous!=Integer.MIN_VALUE&&Math.abs(column.y()-previous)>1)return false;
   previous=column.y();
  }
  return true;
 }

 private static boolean validStored(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  if(state==null)return false;
  long low=a.asLong()<=b.asLong()?a.asLong():b.asLong(),high=a.asLong()<=b.asLong()?b.asLong():a.asLong();
  long count=state.getOrDefault(key+":"+COUNT,0L);
  if(!(count>=1&&count<=MAX_MANHATTAN+MAX_EXTRA+1
   &&state.getOrDefault(key+":routeFrom",Long.MIN_VALUE)==low
   &&state.getOrDefault(key+":routeTo",Long.MIN_VALUE)==high
   &&state.containsKey(key+":"+POINT+"0")&&state.containsKey(key+":"+POINT+(count-1))))return false;
  for(int i=0;i<count;i++)if(state.get(key+":"+POINT+i)==null)return false;
  return true;
 }

 private static boolean validLegacy(Map<String,Long> state,String key,BlockPos a,BlockPos b) {
  if(state==null||state.getOrDefault(key+":"+GEOMETRY,0L)!=2L||state.getOrDefault(key+":"+COUNT,-1L)!=0L)return false;
  long low=a.asLong()<=b.asLong()?a.asLong():b.asLong(),high=a.asLong()<=b.asLong()?b.asLong():a.asLong();
  return state.getOrDefault(key+":routeFrom",Long.MIN_VALUE)==low&&state.getOrDefault(key+":routeTo",Long.MIN_VALUE)==high;
 }

 private static void copy(Map<String,Long> from,String fromKey,Map<String,Long> to,String toKey) {
  to.keySet().removeIf(name->name.startsWith(toKey+":"+GORGE_Y));
  to.put(toKey+":"+GEOMETRY,from.getOrDefault(fromKey+":"+GEOMETRY,3L));
  to.put(toKey+":routeFrom",from.get(fromKey+":routeFrom"));to.put(toKey+":routeTo",from.get(fromKey+":routeTo"));
  long count=from.get(fromKey+":"+COUNT);to.put(toKey+":"+COUNT,count);
  for(int i=0;i<count;i++)to.put(toKey+":"+POINT+i,from.get(fromKey+":"+POINT+i));
  String sourcePrefix=fromKey+":"+GORGE_Y,targetPrefix=toKey+":"+GORGE_Y;
  for(var entry:from.entrySet())if(entry.getKey().startsWith(sourcePrefix))
   to.put(targetPrefix+entry.getKey().substring(sourcePrefix.length()),entry.getValue());
 }

 private static List<BlockPos> legacy(BlockPos a,BlockPos b) {
  int length=Roads.routeLength(a,b);if(length>MAX_MANHATTAN)return null;
  BlockPos low=a.asLong()<=b.asLong()?a:b,high=a.asLong()<=b.asLong()?b:a;
  List<BlockPos> result=new ArrayList<>(length+1);
  for(int i=0;i<=length;i++)result.add(Roads.legacyRoutePoint(low,high,i,length));
  return result;
 }

 private record Node(int x,int z,int g,int h) { int f(){return g+h;} }
 private record Cell(boolean passable,int y,boolean known) { }
 private record RoutePlan(List<BlockPos> points,Map<Integer,Integer> gorgeDeckYs) { }
 private record GorgeCrossing(int endX,int endZ,int distance,Map<Long,Integer> deckByPoint) { }

 private static RoutePlan plan(ServerLevel level,BlockPos a,BlockPos b) {
  BlockPos low=a.asLong()<=b.asLong()?a:b,high=a.asLong()<=b.asLong()?b:a;
  BlockPos ownerBell=Roads.loadedVillage(level,a)==null?null:a;
  BlockPos peerBell=Roads.loadedVillage(level,b)==null?null:b;
  int distance=Math.abs(high.getX()-low.getX())+Math.abs(high.getZ()-low.getZ());
  if(distance==0||distance>MAX_MANHATTAN)return null;
  int minX=Math.min(low.getX(),high.getX())-MARGIN,maxX=Math.max(low.getX(),high.getX())+MARGIN;
  int minZ=Math.min(low.getZ(),high.getZ())-MARGIN,maxZ=Math.max(low.getZ(),high.getZ())+MARGIN;
  Map<Long,Cell> cells=new HashMap<>();Map<Long,Integer> cost=new HashMap<>();Map<Long,Long> parent=new HashMap<>();
  PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingInt(Node::f).thenComparingInt(Node::h)
   .thenComparingInt(Node::x).thenComparingInt(Node::z));
  BlockPos searchStart=low,searchGoal=high;
  Column lowColumn=column(level,low.getX(),low.getZ(),low),highColumn=column(level,high.getX(),high.getZ(),high);
  if(lowColumn.known()&&highColumn.known()&&highColumn.y()>lowColumn.y()) {searchStart=high;searchGoal=low;}
  long start=key(searchStart.getX(),searchStart.getZ()),goal=key(searchGoal.getX(),searchGoal.getZ());
  cost.put(start,0);open.add(new Node(searchStart.getX(),searchStart.getZ(),0,
   heuristic(searchStart.getX(),searchStart.getZ(),searchGoal.getX(),searchGoal.getZ())));
  Map<Long,Integer> gorgeDeckByPoint=new HashMap<>();Set<Long> gorgeInterior=new HashSet<>();
  int visited=0;int[][] dirs={{1,0},{0,1},{-1,0},{0,-1}};
  while(!open.isEmpty()&&visited++<MAX_NODES) {
   Node node=open.poll();long current=key(node.x(),node.z());
   if(node.g()!=cost.getOrDefault(current,Integer.MAX_VALUE))continue;
   if(gorgeInterior.contains(current))continue;
   if(current==goal)break;
   Cell here=cell(level,node.x(),node.z(),searchStart,searchGoal,cells);
   if(!here.passable())continue;
   for(int[] dir:dirs) {
    int x=node.x()+dir[0],z=node.z()+dir[1];if(x<minX||x>maxX||z<minZ||z>maxZ)continue;
    Cell next=cell(level,x,z,searchStart,searchGoal,cells);
    if(here.known()&&next.known()&&Math.abs(next.y()-here.y())>1) {
     if(here.y()>next.y()) {
      GorgeCrossing crossing=dryGorgeAhead(level,node.x(),node.z(),dir[0],dir[1],searchStart,searchGoal,cells,
       ownerBell,peerBell);
      if(crossing!=null&&node.g()+crossing.distance()<=distance+MAX_EXTRA
        &&gorgeCrossSection(level,node.x(),node.z(),dir[0],dir[1],searchStart,ownerBell,peerBell)
        &&gorgeCrossSection(level,crossing.endX(),crossing.endZ(),dir[0],dir[1],searchStart,ownerBell,peerBell)) {
       boolean accepted=true;
       for(int step=1;step<=crossing.distance();step++) {
        long pointKey=key(node.x()+dir[0]*step,node.z()+dir[1]*step);int g=node.g()+step;
        if(g>=cost.getOrDefault(pointKey,Integer.MAX_VALUE)){accepted=false;break;}
       }
       if(accepted) {
        long previous=current;
        Integer startDeckY=crossing.deckByPoint().get(current);
        if(startDeckY!=null)gorgeDeckByPoint.put(current,startDeckY);
        for(int step=1;step<=crossing.distance();step++) {
         int px=node.x()+dir[0]*step,pz=node.z()+dir[1]*step;long pointKey=key(px,pz);int g=node.g()+step;
        cost.put(pointKey,g);parent.put(pointKey,previous);previous=pointKey;
         Integer deckY=crossing.deckByPoint().get(pointKey);if(deckY!=null)gorgeDeckByPoint.put(pointKey,deckY);
        if(step<crossing.distance()) {
         gorgeInterior.add(pointKey);
        }
       }
        open.add(new Node(crossing.endX(),crossing.endZ(),node.g()+crossing.distance(),
         heuristic(crossing.endX(),crossing.endZ(),searchGoal.getX(),searchGoal.getZ())));
       }
      }
     }
     continue;
    } // Abrupt terrain changes are allowed only as a validated bounded dry-gorge bridge.
    if(!next.passable())continue;
    if(!crossSection(level,node.x(),node.z(),dir[0],dir[1],searchStart,searchGoal,cells)
     ||!crossSection(level,x,z,dir[0],dir[1],searchStart,searchGoal,cells))continue;
    int g=node.g()+1;
    if(g>distance+MAX_EXTRA)continue;
    long nextKey=key(x,z);if(g>=cost.getOrDefault(nextKey,Integer.MAX_VALUE))continue;
    cost.put(nextKey,g);parent.put(nextKey,current);
    open.add(new Node(x,z,g,heuristic(x,z,searchGoal.getX(),searchGoal.getZ())));
   }
  }
  if(!cost.containsKey(goal))return null;
  ArrayList<BlockPos> canonical=new ArrayList<>();long cursor=goal;
  for(int safety=0;safety<=MAX_MANHATTAN+MAX_EXTRA;safety++) {
   BlockPos packed=BlockPos.of(cursor);canonical.add(new BlockPos(packed.getX(),0,packed.getZ()));if(cursor==start)break;
   Long previous=parent.get(cursor);if(previous==null)return null;cursor=previous;
  }
  BlockPos packedStart=BlockPos.of(start);
  if(!canonical.get(canonical.size()-1).equals(new BlockPos(packedStart.getX(),0,packedStart.getZ())))return null;
  Collections.reverse(canonical);
  if(searchStart!=low)Collections.reverse(canonical);
  Map<Integer,Integer> gorgeDeckYs=new HashMap<>();
  for(int i=0;i<canonical.size();i++) {
   BlockPos p=canonical.get(i);Integer deckY=gorgeDeckByPoint.get(key(p.getX(),p.getZ()));
   if(deckY!=null)gorgeDeckYs.put(i,deckY);
  }
  return new RoutePlan(List.copyOf(canonical),Map.copyOf(gorgeDeckYs));
 }

 private static GorgeCrossing dryGorgeAhead(ServerLevel level,int x,int z,int dx,int dz,BlockPos start,BlockPos end,
                                             Map<Long,Cell> cells,BlockPos ownerBell,BlockPos peerBell) {
  Column startColumn=column(level,x,z,start);
  if(!startColumn.passable()||!startColumn.known()
   ||!gorgeCrossSection(level,x,z,dx,dz,start,ownerBell,peerBell))return null;
  for(int distance=2;distance<=MAX_DRY_GORGE_SPAN+1;distance++) {
   int endX=x+dx*distance,endZ=z+dz*distance;
   Cell endCell=cell(level,endX,endZ,start,end,cells);
   // Unknown terrain makes the whole candidate span unsafe. Known but non-paveable
   // terrain can be an interior gorge floor, so continue scanning for the far bank.
   if(!endCell.known())return null;
   if(!endCell.passable())continue;
   if(endCell.y()<startColumn.y()-1||Math.abs(endCell.y()-startColumn.y())>distance)continue;
   if(!gorgeCrossSection(level,endX,endZ,dx,dz,start,ownerBell,peerBell))continue;
   Map<Long,Integer> deckByPoint=new HashMap<>();int deepest=0;boolean valid=true;
   for(int step=1;step<distance&&valid;step++) {
    int px=x+dx*step,pz=z+dz*step;
    int deckY=interpolate(startColumn.y(),endCell.y(),step,distance);
    for(int side=-Roads.ROAD_HALF_WIDTH;side<=Roads.ROAD_HALF_WIDTH;side++) {
     int sx=px+(dz==0?0:side),sz=pz+(dx==0?0:side);
     Cell lane=cell(level,sx,sz,start,end,cells);
     // A deep bridge deck does not need the gorge floor to accept ordinary gravel paving;
     // the paid support plan below validates that load path independently. Keep ordinary,
     // low-clearance sections subject to the normal roadability test.
     if(!lane.known()||(!lane.passable()&&deckY-lane.y()<2)) {valid=false;break;}
     RoadClearing.Column clearing=RoadClearing.column(level,null,sx,sz,new HashMap<>(),"gorge:"+sx+":"+sz);
     if(clearing==null||clearing.next()!=null) {valid=false;break;}
     BlockPos ground=clearing.ground();BlockState floor=level.getBlockState(ground);
     if(!floor.getFluidState().isEmpty()||floor.hasBlockEntity()||floor.is(Blocks.FARMLAND)
       ||Roads.isProtectedLaneCell(level,ground)
       ||Roads.protectedBridgeCell(level,ground,ownerBell,peerBell)) {valid=false;break;}
     int delta=deckY-ground.getY();
     if(delta < -2||delta>MAX_GORGE_DEPTH) {valid=false;break;}
     if(delta<0&&!cuttable(floor)) {valid=false;break;}
     BlockPos deck=new BlockPos(sx,deckY,sz);
     if(delta>0) {
      if(!level.hasChunkAt(deck)) {valid=false;break;}
      BlockState deckState=level.getBlockState(deck);
      if((!deckState.isAir()&&!deckState.is(Blocks.SPRUCE_PLANKS))||deckState.hasBlockEntity()
       ||!deckState.getFluidState().isEmpty()||!level.getBlockState(deck.above()).isAir()
       ||!level.getBlockState(deck.above(2)).isAir()) {valid=false;break;}
      if(delta>=2&&RoadBridge.plan(level,deck,ownerBell,peerBell)==null) {valid=false;break;}
     }
     deepest=Math.max(deepest,delta);
    }
    if(valid)deckByPoint.put(key(px,pz),deckY);
   }
   if(valid&&deepest>=3) {
    deckByPoint.put(key(x,z),startColumn.y());deckByPoint.put(key(endX,endZ),endCell.y());
    return new GorgeCrossing(endX,endZ,distance,Map.copyOf(deckByPoint));
   }
  }
  return null;
 }

 private static int interpolate(int fromY,int toY,int step,int length) {
  return fromY+(int)Math.round((toY-fromY)*(step/(double)length));
 }

 private static boolean gorgeCrossSection(ServerLevel level,int x,int z,int dx,int dz,BlockPos reference,
                                           BlockPos ownerBell,BlockPos peerBell) {
  int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
  for(int side=-Roads.ROAD_HALF_WIDTH;side<=Roads.ROAD_HALF_WIDTH;side++) {
   int sx=x+(dz==0?0:side),sz=z+(dx==0?0:side);
   Column lane=column(level,sx,sz,reference);
   if(!lane.passable()||!lane.known())return false;
   if(Roads.protectedBridgeCell(level,new BlockPos(sx,lane.y(),sz),ownerBell,peerBell))return false;
   low=Math.min(low,lane.y());high=Math.max(high,lane.y());
  }
  return high-low<=2;
 }

 private static boolean cuttable(BlockState state) {
  return state.is(net.minecraft.tags.BlockTags.DIRT)||state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.MYCELIUM)
   ||state.is(Blocks.PODZOL)||state.is(Blocks.COARSE_DIRT)||state.is(Blocks.SNOW_BLOCK)
   ||state.is(Blocks.SAND)||state.is(Blocks.RED_SAND)||state.is(Blocks.GRAVEL);
 }

 private static Cell cell(ServerLevel level,int x,int z,BlockPos start,BlockPos end,Map<Long,Cell> memo) {
  long key=key(x,z);Cell cached=memo.get(key);if(cached!=null)return cached;
  int endpointDistance=Math.min(Math.abs(x-start.getX())+Math.abs(z-start.getZ()),Math.abs(x-end.getX())+Math.abs(z-end.getZ()));
  if(!level.hasChunkAt(new BlockPos(x,start.getY(),z))) {Cell c=new Cell(true,0,false);memo.put(key,c);return c;}
  if(endpointDistance<=8) {
   int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
   Cell c=new Cell(true,y,true);memo.put(key,c);return c;
  }
  Column result=column(level,x,z,start);
  Cell cell=new Cell(result.passable(),result.y(),result.known());memo.put(key,cell);return cell;
 }

 private record Column(boolean passable,int y,boolean known) { }
 private static Column column(ServerLevel level,int x,int z,BlockPos reference) {
  BlockPos probe=new BlockPos(x,reference.getY(),z);if(!level.hasChunkAt(probe))return new Column(true,0,false);
  Map<String,Long> scratch=new HashMap<>();RoadClearing.Column clearing=RoadClearing.column(level,null,x,z,scratch,"route");
  if(clearing==null)return new Column(false,0,true);
  BlockPos ground=clearing.ground();BlockState surface=level.getBlockState(ground);
  if(!surface.getFluidState().isEmpty())return new Column(RoadBridge.plan(level,ground)!=null,ground.getY(),true);
  if(clearing.next()==null) {
   if(surface.is(Blocks.GRAVEL)||surface.is(Blocks.COBBLESTONE)||surface.is(Blocks.SANDSTONE)
    ||surface.is(Blocks.SMOOTH_SANDSTONE)||surface.is(Blocks.SPRUCE_PLANKS))return new Column(true,ground.getY(),true);
   BlockState desired=Blocks.GRAVEL.defaultBlockState();
   if(!surface.is(desired.getBlock())&&!Roads.repairable(level,new Roads.Repair(ground,desired)))return new Column(false,ground.getY(),true);
  }
  return new Column(true,ground.getY(),true);
 }

 private static boolean crossSection(ServerLevel level,int x,int z,int dx,int dz,BlockPos start,BlockPos end,Map<Long,Cell> memo) {
  int px=dz, pz=dx;int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
  for(int side=-Roads.ROAD_HALF_WIDTH;side<=Roads.ROAD_HALF_WIDTH;side++) {
   int sx=x+px*side,sz=z+pz*side;
   int endpointDistance=Math.min(Math.abs(sx-start.getX())+Math.abs(sz-start.getZ()),Math.abs(sx-end.getX())+Math.abs(sz-end.getZ()));
   if(endpointDistance<=8)continue;
   Column c=column(level,sx,sz,start);if(!c.passable())return false;if(!c.known())continue;
   min=Math.min(min,c.y());max=Math.max(max,c.y());
  }
  // The physical builder can safely cut/fill up to two blocks to level a three-lane section.
  return min==Integer.MAX_VALUE||max-min<=2;
 }

 private static int heuristic(int x,int z,int tx,int tz){return Math.abs(tx-x)+Math.abs(tz-z);}
 private static long key(int x,int z){return new BlockPos(x,0,z).asLong();}
}
