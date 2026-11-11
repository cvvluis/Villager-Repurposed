package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.block.ModBlocks;
import de.villagerplus.build.Palette;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.Heightmap;

/** A broad octagonal square with free sightlines; no existing building or chest is removed. */
public final class Plaza {
 private Plaza() {}
 private static final int PLAZA_RADIUS=13;
 private static final int PLAZA_DIAMOND_RADIUS=22;
 private static final int PLAZA_EDGE_CLEARANCE=5;
 private static final int ROAD_ARM_START=PLAZA_RADIUS+1;
 private static final int ROAD_ARM_END=PLAZA_RADIUS+5;
 private static final int SURFACE_BLOCKS_PER_TICK=8;
 private static final int CIVIC_DETAILS_PER_TICK=4;
 private static final int WORK_BATCH_INTERVAL_TICKS=1;
 private static final long CIVIC_RETRY_TICKS=200L;
 private static final int SURFACE_SIDE=PLAZA_RADIUS*2+1;
 private static final int SURFACE_CELLS=SURFACE_SIDE*SURFACE_SIDE;
 public static boolean inside(ServerLevel level, VillageRef ref, BlockPos p, int margin) {
  MarketData m=Market.of(ref); if(m.plaza().isEmpty()) return false;
  return inside(m.plaza().get(),p,margin);
 }
 static boolean inside(BlockPos c,BlockPos p,int margin) {
  int x=Math.abs(c.getX()-p.getX()), z=Math.abs(c.getZ()-p.getZ());
  return x<=PLAZA_RADIUS+margin && z<=PLAZA_RADIUS+margin && x+z<=PLAZA_DIAMOND_RADIUS+margin*2;
 }
 private static final java.util.Set<String> SHRINE_FIXED=new java.util.HashSet<>();
 private static final java.util.Map<String,Long> DECOR_CHECK=new java.util.HashMap<>();
 private static final java.util.Map<String,Long> FOUNTAIN_CHECK=new java.util.HashMap<>();
 private static final java.util.Map<String,Long> SURFACE_CHECK=new java.util.HashMap<>();
 private static final java.util.Map<String,Integer> SURFACE_CURSOR=new java.util.HashMap<>();
 private static final java.util.Map<String,Boolean> SURFACE_PENDING=new java.util.HashMap<>();
 private static final java.util.Set<String> SURFACE_COMPLETE=new java.util.HashSet<>();
 private static final java.util.Map<String,Palette> SURFACE_PALETTE=new java.util.HashMap<>();
 private static final java.util.Map<String,Integer> CIVIC_CURSOR=new java.util.HashMap<>();
 private static final java.util.Set<String> CIVIC_DETAILS_COMPLETE=new java.util.HashSet<>();
 private static final java.util.Set<String> CIVIC_COMPLETE=new java.util.HashSet<>();

 private static String cacheKey(ServerLevel level,VillageRef ref) { return level.dimension().identifier()+"/"+ref.bell().asLong(); }

 private static final int[][] BENCHES={{-4,-3,1},{-3,-4,2},{4,-3,-1},{3,-4,2},
	{-4,3,1},{-3,4,-2},{4,3,-1},{3,4,-2}};
 private static final int[][] PLANTERS={{-5,-3},{5,-3},{-5,3},{5,3},{-3,-5},{3,-5},{-3,5},{3,5}};
 // Flower beds form four matching arms around the inner medallion.
 private static final int[][] FLOWER_BEDS={{-5,-4},{-4,-4},{4,-4},{5,-4},
	{-5,4},{-4,4},{4,4},{5,4},{-4,-5},{4,-5},{-4,5},{4,5}};
 private static final int[][] EXTRA_BENCHES={{-6,-4,1},{-4,-6,2},{6,-4,-1},{4,-6,2},
	{-6,4,1},{-4,6,-2},{6,4,-1},{4,6,-2}};
 private static final int[][] OUTER_BENCHES={{-7,-4,1},{-4,-7,2},{7,-4,-1},{4,-7,2},
	{-7,4,1},{-4,7,-2},{7,4,-1},{4,7,-2}};
 private static final int[][] EXTRA_PLANTERS={{-7,-5},{7,-5},{-7,5},{7,5},{-5,-7},{5,-7},{-5,7},{5,7}};
 private static final int[][] OUTER_PLANTERS={{-8,-3},{8,-3},{-8,3},{8,3},{-3,-8},{3,-8},{-3,8},{3,8}};
 private static final int[][] EXTRA_FLOWER_BEDS={{-6,-5},{6,-5},{-6,5},{6,5},{-5,-6},{5,-6},{-5,6},{5,6}};
 private static final int[][] OUTER_FLOWER_BEDS={{-7,-6},{-6,-7},{7,-6},{6,-7},{-7,6},{-6,7},{7,6},{6,7},
	{-7,-3},{-3,-7},{7,-3},{3,-7},{-7,3},{-3,7},{7,3},{3,7}};
 private static final int[][] RING_BENCHES={{-10,-5,1},{-10,5,1},{10,-5,-1},{10,5,-1},
	{-5,-10,2},{5,-10,2},{-5,10,-2},{5,10,-2}};
 private static final int[][] FAR_BENCHES={{-12,-4,1},{-12,4,1},{12,-4,-1},{12,4,-1},
	{-4,-12,2},{4,-12,2},{-4,12,-2},{4,12,-2}};
 private static final int[][] FAR_PLANTERS={{-11,-6},{11,-6},{-11,6},{11,6},{-6,-11},{6,-11},{-6,11},{6,11}};
 private static final int[][] FAR_FLOWER_BEDS={{-9,-8},{-8,-9},{9,-8},{8,-9},{-9,8},{-8,9},{9,8},{8,9},
	{-11,-3},{-11,3},{11,-3},{11,3},{-3,-11},{3,-11},{-3,11},{3,11}};
 private static final int[][] LAMP_POSTS={{-5,-5},{5,-5},{-5,5},{5,5}};
 private static final int[][] OUTER_LAMP_POSTS={{-7,-7},{7,-7},{-7,7},{7,7}};
 private static final int[][] PROMENADE_LAMP_POSTS={{-9,-9},{9,-9},{-9,9},{9,9}};
 private static final int[][] EDGE_LAMP_POSTS={{-11,-11},{11,-11},{-11,11},{11,11}};
 // Rain-catching cauldrons sit just outside the shrine supports and keep all four approaches open.
 private static final int[][] RAIN_BASINS={{-3,-3},{3,-3},{-3,3},{3,3}};
 private static final int[][] FOUNTAIN_RING={{-2,-2},{-1,-2},{0,-2},{1,-2},{2,-2},{-2,-1},{2,-1},{-2,0},
  {2,0},{-2,1},{2,1},{-2,2},{-1,2},{0,2},{1,2},{2,2}};

 // Legacy offset sets above remain available to the geometry audit. New plazas use
 // a symmetric inner pavilion, four compact garden courts, and an outer promenade.
 private enum CivicDetailKind { BENCH, PLANTER, FLOWER, LAMP, RAIN_BASIN }
 private record CivicDetail(CivicDetailKind kind,int dx,int dz,int facing) { }
 private static final CivicDetail[] CIVIC_DETAILS={
  new CivicDetail(CivicDetailKind.BENCH,-7,-4,1),new CivicDetail(CivicDetailKind.BENCH,-4,-7,2),
  new CivicDetail(CivicDetailKind.BENCH,7,-4,-1),new CivicDetail(CivicDetailKind.BENCH,4,-7,2),
  new CivicDetail(CivicDetailKind.BENCH,-7,4,1),new CivicDetail(CivicDetailKind.BENCH,-4,7,-2),
  new CivicDetail(CivicDetailKind.BENCH,7,4,-1),new CivicDetail(CivicDetailKind.BENCH,4,7,-2),
  new CivicDetail(CivicDetailKind.PLANTER,-7,-7,0),new CivicDetail(CivicDetailKind.PLANTER,7,-7,0),
  new CivicDetail(CivicDetailKind.PLANTER,-7,7,0),new CivicDetail(CivicDetailKind.PLANTER,7,7,0),
  // Four matching beds around each shrub make the green spaces read as deliberate courts.
  new CivicDetail(CivicDetailKind.FLOWER,-5,-7,0),new CivicDetail(CivicDetailKind.FLOWER,-7,-5,0),
  new CivicDetail(CivicDetailKind.FLOWER,-9,-7,0),new CivicDetail(CivicDetailKind.FLOWER,-7,-9,0),
  new CivicDetail(CivicDetailKind.FLOWER,5,-7,0),new CivicDetail(CivicDetailKind.FLOWER,7,-5,0),
  new CivicDetail(CivicDetailKind.FLOWER,9,-7,0),new CivicDetail(CivicDetailKind.FLOWER,7,-9,0),
  new CivicDetail(CivicDetailKind.FLOWER,-5,7,0),new CivicDetail(CivicDetailKind.FLOWER,-7,5,0),
  new CivicDetail(CivicDetailKind.FLOWER,-9,7,0),new CivicDetail(CivicDetailKind.FLOWER,-7,9,0),
  new CivicDetail(CivicDetailKind.FLOWER,5,7,0),new CivicDetail(CivicDetailKind.FLOWER,7,5,0),
  new CivicDetail(CivicDetailKind.FLOWER,9,7,0),new CivicDetail(CivicDetailKind.FLOWER,7,9,0),
  new CivicDetail(CivicDetailKind.LAMP,-11,-11,0),new CivicDetail(CivicDetailKind.LAMP,11,-11,0),
  new CivicDetail(CivicDetailKind.LAMP,-11,11,0),new CivicDetail(CivicDetailKind.LAMP,11,11,0)
 };
 // The promenade balances the four courts with another set of seats and planted accents.
 private static final CivicDetail[] OUTER_CIVIC_DETAILS={
  new CivicDetail(CivicDetailKind.BENCH,-9,-4,1),new CivicDetail(CivicDetailKind.BENCH,-9,4,1),
  new CivicDetail(CivicDetailKind.BENCH,9,-4,-1),new CivicDetail(CivicDetailKind.BENCH,9,4,-1),
  new CivicDetail(CivicDetailKind.BENCH,-4,-9,2),new CivicDetail(CivicDetailKind.BENCH,4,-9,2),
  new CivicDetail(CivicDetailKind.BENCH,-4,9,-2),new CivicDetail(CivicDetailKind.BENCH,4,9,-2),
  new CivicDetail(CivicDetailKind.PLANTER,-11,-6,0),new CivicDetail(CivicDetailKind.PLANTER,11,-6,0),
  new CivicDetail(CivicDetailKind.PLANTER,-11,6,0),new CivicDetail(CivicDetailKind.PLANTER,11,6,0),
  new CivicDetail(CivicDetailKind.PLANTER,-6,-11,0),new CivicDetail(CivicDetailKind.PLANTER,6,-11,0),
  new CivicDetail(CivicDetailKind.PLANTER,-6,11,0),new CivicDetail(CivicDetailKind.PLANTER,6,11,0),
  new CivicDetail(CivicDetailKind.FLOWER,-11,-4,0),new CivicDetail(CivicDetailKind.FLOWER,-4,-11,0),
  new CivicDetail(CivicDetailKind.FLOWER,11,-4,0),new CivicDetail(CivicDetailKind.FLOWER,4,-11,0),
  new CivicDetail(CivicDetailKind.FLOWER,-11,-8,0),new CivicDetail(CivicDetailKind.FLOWER,-8,-11,0),
  new CivicDetail(CivicDetailKind.FLOWER,11,-8,0),new CivicDetail(CivicDetailKind.FLOWER,8,-11,0),
  new CivicDetail(CivicDetailKind.FLOWER,-11,4,0),new CivicDetail(CivicDetailKind.FLOWER,-4,11,0),
  new CivicDetail(CivicDetailKind.FLOWER,11,4,0),new CivicDetail(CivicDetailKind.FLOWER,4,11,0),
  new CivicDetail(CivicDetailKind.FLOWER,-11,8,0),new CivicDetail(CivicDetailKind.FLOWER,-8,11,0),
  new CivicDetail(CivicDetailKind.FLOWER,11,8,0),new CivicDetail(CivicDetailKind.FLOWER,8,11,0),
  new CivicDetail(CivicDetailKind.LAMP,-9,-9,0),new CivicDetail(CivicDetailKind.LAMP,9,-9,0),
  new CivicDetail(CivicDetailKind.LAMP,-9,9,0),new CivicDetail(CivicDetailKind.LAMP,9,9,0)
 };
 private static final int ALL_CIVIC_DETAILS=CIVIC_DETAILS.length+OUTER_CIVIC_DETAILS.length;
 private static final int CIVIC_WORK_ITEMS=ALL_CIVIC_DETAILS+RAIN_BASINS.length;

 private record PaidSlot(Container container,int slot) { }
 record CivicBatch(int cursor,boolean pending) { }

 private static Set<Long> savedRoadGrounds(ServerLevel level,VillageRef ref) {
  Set<Long> grounds=new HashSet<>();
  for(BlockPos pos:Roads.savedVillagePathGrounds(level,ref)) grounds.add(pos.asLong());
  return grounds;
 }

 private static VillageRef ownerOfPlaza(ServerLevel level,BlockPos plaza) {
  if(plaza==null) return null;
  for(BlockPos bell:Villages.knownBellsNear(level,plaza,128)) {
   var entity=level.getBlockEntity(bell);
   if(!(entity instanceof net.minecraft.world.level.block.entity.BellBlockEntity)) continue;
   var data=entity.getAttached(VillagerPlus.VILLAGE);
   if(data==null) continue;
   VillageRef ref=new VillageRef(bell,data,entity);
   if(Market.of(ref).plaza().filter(plaza::equals).isPresent()) return ref;
  }
  return null;
 }

 static boolean benchOffset(int dx,int dz) { return decorationOffsetSafe(dx,dz)&&(containsOffset(BENCHES,dx,dz)||containsOffset(EXTRA_BENCHES,dx,dz)||containsOffset(OUTER_BENCHES,dx,dz)||containsOffset(RING_BENCHES,dx,dz)||containsOffset(FAR_BENCHES,dx,dz)); }
 static boolean planterOffset(int dx,int dz) { return decorationOffsetSafe(dx,dz)&&(containsOffset(PLANTERS,dx,dz)||containsOffset(EXTRA_PLANTERS,dx,dz)||containsOffset(OUTER_PLANTERS,dx,dz)||containsOffset(FAR_PLANTERS,dx,dz)); }
 static boolean flowerOffset(int dx,int dz) { return decorationOffsetSafe(dx,dz)&&(containsOffset(FLOWER_BEDS,dx,dz)||containsOffset(EXTRA_FLOWER_BEDS,dx,dz)||containsOffset(OUTER_FLOWER_BEDS,dx,dz)||containsOffset(FAR_FLOWER_BEDS,dx,dz)); }
 static boolean lampOffset(int dx,int dz) { return containsOffset(LAMP_POSTS,dx,dz)||containsOffset(OUTER_LAMP_POSTS,dx,dz)
  ||containsOffset(PROMENADE_LAMP_POSTS,dx,dz)||containsOffset(EDGE_LAMP_POSTS,dx,dz); }
 static boolean rainBasinOffset(int dx,int dz) { return decorationOffsetSafe(dx,dz)&&containsOffset(RAIN_BASINS,dx,dz); }
 static boolean civicBenchOffset(int dx,int dz) { return civicDetailOffset(CivicDetailKind.BENCH,dx,dz); }
 static boolean civicPlanterOffset(int dx,int dz) { return civicDetailOffset(CivicDetailKind.PLANTER,dx,dz); }
 static boolean civicFlowerOffset(int dx,int dz) { return civicDetailOffset(CivicDetailKind.FLOWER,dx,dz); }
 static boolean civicLampOffset(int dx,int dz) { return civicDetailOffset(CivicDetailKind.LAMP,dx,dz); }
 static boolean civicRainBasinOffset(int dx,int dz) { return rainBasinOffset(dx,dz); }
 static int civicDetailCount() { return CIVIC_DETAILS.length; }
 static int outerCivicDetailCount() { return OUTER_CIVIC_DETAILS.length; }
 static int allCivicDetailCount() { return ALL_CIVIC_DETAILS; }
 static int civicWorkItemCount() { return CIVIC_WORK_ITEMS; }
 static int civicDetailBudget() { return CIVIC_DETAILS_PER_TICK; }
 static int surfaceWorkBudget() { return SURFACE_BLOCKS_PER_TICK; }
 static int workBatchIntervalTicks() { return WORK_BATCH_INTERVAL_TICKS; }
 private static boolean civicDetailOffset(CivicDetailKind kind,int dx,int dz) {
  for(CivicDetail detail:CIVIC_DETAILS) if(detail.kind()==kind&&detail.dx()==dx&&detail.dz()==dz) return true;
  return false;
 }
 static boolean outerCivicBenchOffset(int dx,int dz) { return outerCivicDetailOffset(CivicDetailKind.BENCH,dx,dz); }
 static boolean outerCivicPlanterOffset(int dx,int dz) { return outerCivicDetailOffset(CivicDetailKind.PLANTER,dx,dz); }
 static boolean outerCivicFlowerOffset(int dx,int dz) { return outerCivicDetailOffset(CivicDetailKind.FLOWER,dx,dz); }
 static boolean outerCivicLampOffset(int dx,int dz) { return outerCivicDetailOffset(CivicDetailKind.LAMP,dx,dz); }
 private static boolean outerCivicDetailOffset(CivicDetailKind kind,int dx,int dz) {
  for(CivicDetail detail:OUTER_CIVIC_DETAILS) if(detail.kind()==kind&&detail.dx()==dx&&detail.dz()==dz) return true;
  return false;
 }
 /** Quadrant id for the four garden courts; their clear 5-wide axes are excluded. */
 static int gardenCourtIndex(int dx,int dz) {
  int x=Math.abs(dx),z=Math.abs(dz);
  if(dx==0||dz==0||x<4||x>9||z<4||z>9) return -1;
  return (dx>0?2:0)+(dz>0?1:0);
 }
 static boolean civicZoneOverlapsSquare(ZonePolicy.Zone zone,int centerX,int centerZ,int radius) {
  if(zone==null||radius<0) return false;
  long minX=(long)centerX-radius,maxX=(long)centerX+radius;
  long minZ=(long)centerZ-radius,maxZ=(long)centerZ+radius;
  return Math.min((long)zone.minX(),zone.maxX())<=maxX
   &&Math.max((long)zone.minX(),zone.maxX())>=minX
   &&Math.min((long)zone.minZ(),zone.maxZ())<=maxZ
   &&Math.max((long)zone.minZ(),zone.maxZ())>=minZ;
 }
 private static boolean protectedZoneIntersects(VillageRef ref,BlockPos center,int radius) {
  if(ref==null||center==null||radius<0) return false;
  return ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES,java.util.List.of()).stream()
   .anyMatch(zone->civicZoneOverlapsSquare(zone,center.getX(),center.getZ(),radius));
 }
 static boolean lampOffsetSafe(int dx,int dz) {
  return lampOffset(dx,dz)&&Math.abs(dx)>2&&Math.abs(dz)>2
	  &&Math.abs(dx)<=PLAZA_RADIUS&&Math.abs(dz)<=PLAZA_RADIUS&&Math.abs(dx)+Math.abs(dz)<=PLAZA_DIAMOND_RADIUS
   &&!(dx>=-2&&dx<=1&&dz>=3&&dz<=5)
   &&!(dx>=-3&&dx<=1&&dz>=-6&&dz<=-4);
 }
 private static boolean containsOffset(int[][] offsets,int dx,int dz) {
  for(int[] offset:offsets) if(offset[0]==dx&&offset[1]==dz) return true;
  return false;
 }

 /** Decoration occupies only the outer garden ring, outside the five-wide approaches and civic structures. */
 static boolean decorationOffsetSafe(int dx,int dz) {
	  return decorationRingOffsetSafe(dx,dz)&&!lampOffset(dx,dz); // legacy light rings remain reserved in the compatibility map
 }
	private static boolean decorationRingOffsetSafe(int dx,int dz) {
	  if(Math.max(Math.abs(dx),Math.abs(dz))>12 || Math.abs(dx)+Math.abs(dz)>20 || Math.abs(dx)<=2 || Math.abs(dz)<=2) return false;
	  if(Math.abs(dx)<=2 && Math.abs(dz)<=2) return false; // shrine and its roof supports
	  if(dx>=-2 && dx<=1 && dz>=3 && dz<=5) return false; // double chest and its access side
	  if(dx>=-1 && dx<=1 && dz>=-5 && dz<=-4) return false; // Dorftafel and its three-wide front access
	  return true;
	}
	/** Safe cell for the active plaza layout; historical lamp rings do not block the new court planters. */
	static boolean civicDetailOffsetSafe(int dx,int dz,boolean lamp) {
	  if(lamp) return lampOffsetSafe(dx,dz);
	  return decorationRingOffsetSafe(dx,dz)&&!civicLampOffset(dx,dz)&&!outerCivicLampOffset(dx,dz);
	}

 static boolean surfaceUpgradeOffsetSafe(int dx,int dz) {
	  return Math.abs(dx)<=PLAZA_RADIUS && Math.abs(dz)<=PLAZA_RADIUS && Math.abs(dx)+Math.abs(dz)<=PLAZA_DIAMOND_RADIUS;
 }
	/** Palette-matched 13x13 square frame gives the central pavilion a clear, deliberate boundary. */
 static boolean focalSquareBorderOffset(int dx,int dz) {
	  return Math.max(Math.abs(dx),Math.abs(dz))==6;
 }
	/** The 17x17 middle frame separates the pavilion's garden circuit from the outer seating ring. */
	static boolean middleSquareBorderOffset(int dx,int dz) {
		  return Math.max(Math.abs(dx),Math.abs(dz))==8;
	}
	/** Two clipped square rings widen the fountain court into a readable octagonal medallion. */
	static boolean innerMedallionRingOffset(int dx,int dz) {
		return Math.max(Math.abs(dx),Math.abs(dz))==4&&Math.min(Math.abs(dx),Math.abs(dz))>=3;
	}
	static boolean outerMedallionRingOffset(int dx,int dz) {
		return Math.max(Math.abs(dx),Math.abs(dz))==5&&Math.min(Math.abs(dx),Math.abs(dz))>=3;
	}
 static boolean outerSquareBorderOffset(int dx,int dz) {
	  return Math.max(Math.abs(dx),Math.abs(dz))==10;
 }
 /** The four five-wide road arms continue from the plaza edge through the bounded site apron. */
 static boolean roadArmOffset(int dx,int dz) {
  int x=Math.abs(dx),z=Math.abs(dz);
	  return x>=ROAD_ARM_START&&x<=ROAD_ARM_END&&z<=2 || z>=ROAD_ARM_START&&z<=ROAD_ARM_END&&x<=2;
 }
 static boolean basePavingOffset(int dx,int dz) {
	  return Math.abs(dx)==PLAZA_RADIUS || Math.abs(dz)==PLAZA_RADIUS
	  || Math.abs(dx)+Math.abs(dz)==PLAZA_DIAMOND_RADIUS || Math.abs(dx)<=2 || Math.abs(dz)<=2
	  || accentPavingOffset(dx,dz);
 }
 /** Every nested medallion/frame uses the palette accent and is eligible for the paid surface upgrade. */
 static boolean accentPavingOffset(int dx,int dz) {
	  return focalSquareBorderOffset(dx,dz) || middleSquareBorderOffset(dx,dz)
	   || outerSquareBorderOffset(dx,dz) || plazaInlayOffset(dx,dz);
 }
	/** Palette-matched octagonal fountain court, double-diamond medallion, and diagonal rays form the central motif. */
 static boolean plazaInlayOffset(int dx,int dz) {
  int x=Math.abs(dx),z=Math.abs(dz);
	  boolean diagonalRays=x==z&&x>=4&&x<=11;
	  boolean fountainCourt=innerMedallionRingOffset(dx,dz)||outerMedallionRingOffset(dx,dz);
  boolean fountainMedallion=x+z==7&&x>=3&&z>=3;
	  boolean innerDiamondBorder=x+z==12&&x>=3&&z>=3;
	  boolean outerDiamondBorder=x+z==20&&x>=6&&z>=6;
  return fountainCourt||fountainMedallion||diagonalRays||innerDiamondBorder||outerDiamondBorder;
 }

 /** Progress the civic composition in small, paid, restart-safe batches. */
 static void decorate(ServerLevel level, VillageRef ref, Villager hint, BlockPos c, long now) {
  String key=cacheKey(level,ref)+"/plaza-"+c.asLong();
  if(!level.hasChunkAt(c)) return;
  Set<Long> roadGrounds=savedRoadGrounds(level,ref);
  Palette p=null;
  String surfaceKey=key+"/server-"+System.identityHashCode(level.getServer());
  if(!SURFACE_COMPLETE.contains(surfaceKey)&&now>=SURFACE_CHECK.getOrDefault(surfaceKey,0L)) {
   SURFACE_CHECK.put(surfaceKey,now+WORK_BATCH_INTERVAL_TICKS);
   p=SURFACE_PALETTE.computeIfAbsent(surfaceKey,ignored->Palette.detect(level,ref.bell(),hint,c.asLong(),c));
   SurfaceBatch batch=upgradeSurface(level,ref,c,p,SURFACE_CURSOR.getOrDefault(surfaceKey,0),roadGrounds);
   boolean pending=SURFACE_PENDING.getOrDefault(surfaceKey,false)||batch.pending();
   if(batch.cursor()>=SURFACE_CELLS) {
    SURFACE_CURSOR.put(surfaceKey,0);
    SURFACE_PENDING.remove(surfaceKey);
    SURFACE_PALETTE.remove(surfaceKey);
    if(pending) SURFACE_CHECK.put(surfaceKey,now+CIVIC_RETRY_TICKS);
    else SURFACE_COMPLETE.add(surfaceKey);
   } else {
    SURFACE_CURSOR.put(surfaceKey,batch.cursor());
    SURFACE_PENDING.put(surfaceKey,pending);
    SURFACE_CHECK.put(surfaceKey,now+WORK_BATCH_INTERVAL_TICKS);
   }
  }
  if(CIVIC_COMPLETE.contains(surfaceKey)||now<DECOR_CHECK.getOrDefault(surfaceKey,0L)) return;
  DECOR_CHECK.put(surfaceKey,now+WORK_BATCH_INTERVAL_TICKS);
  if(p==null) p=Palette.detect(level,ref.bell(),hint,c.asLong(),c);
  String fountainKey=surfaceKey+"/fountain";
  boolean fountainReady=fountainComplete(level,c,p.base());
  if(!fountainReady&&now>=FOUNTAIN_CHECK.getOrDefault(fountainKey,0L)) {
   FOUNTAIN_CHECK.put(fountainKey,now+CIVIC_RETRY_TICKS);
   fountainReady=ensureFountain(level,ref,c,p);
  }
  if(CIVIC_DETAILS_COMPLETE.contains(surfaceKey)) {
   if(fountainReady) CIVIC_COMPLETE.add(surfaceKey);
   else DECOR_CHECK.put(surfaceKey,Math.max(now+WORK_BATCH_INTERVAL_TICKS,FOUNTAIN_CHECK.getOrDefault(fountainKey,now+CIVIC_RETRY_TICKS)));
   return;
  }
  int cursor=Math.max(0,Math.min(CIVIC_WORK_ITEMS,CIVIC_CURSOR.getOrDefault(surfaceKey,0)));
  CivicBatch batch=decorateCivicBatch(level,ref,c,p,roadGrounds,cursor);
  int end=batch.cursor();
  boolean pending=batch.pending();
  if(end>=CIVIC_WORK_ITEMS) {
   CIVIC_CURSOR.put(surfaceKey,0);
   if(pending) DECOR_CHECK.put(surfaceKey,now+CIVIC_RETRY_TICKS);
   else {
    CIVIC_DETAILS_COMPLETE.add(surfaceKey);
    if(fountainReady) CIVIC_COMPLETE.add(surfaceKey);
    else DECOR_CHECK.put(surfaceKey,Math.max(now+WORK_BATCH_INTERVAL_TICKS,FOUNTAIN_CHECK.getOrDefault(fountainKey,now+CIVIC_RETRY_TICKS)));
   }
  } else {
   CIVIC_CURSOR.put(surfaceKey,end);
   DECOR_CHECK.put(surfaceKey,now+WORK_BATCH_INTERVAL_TICKS);
  }
 }

 /** Place the paid civic-details batch independently from fountain funding so a missing water bucket cannot stall the whole plaza. */
 static CivicBatch decorateCivicBatch(ServerLevel level,VillageRef ref,BlockPos c,Palette p,Set<Long> roadGrounds,int cursor) {
  Block[] flowers=flowerPalette(p,c);
  Block lampSupport=p.frame()==null?p.base():p.frame();
  cursor=Math.max(0,Math.min(CIVIC_WORK_ITEMS,cursor));
  int end=Math.min(CIVIC_WORK_ITEMS,cursor+CIVIC_DETAILS_PER_TICK);
  boolean pending=false;
  int flowerIndex=0;
  for(int i=0;i<cursor;i++) if(civicDetailAt(i).kind()==CivicDetailKind.FLOWER) flowerIndex++;
  for(int i=cursor;i<end;i++) {
   CivicDetail detail=civicDetailAt(i);
   BlockPos at=c.offset(detail.dx(),0,detail.dz());
   if(!civicDetailSafe(detail)) { /* fixed geometry guard for future layout edits */ }
   else if(protectedZoneIntersects(ref,at,0)) { /* user zone remains untouched */ }
   else if(!level.hasChunkAt(at.below())) { pending=true; }
   else if(reservedRoadCell(level,at.below(),roadGrounds)) { /* saved road/path remains untouched */ }
   else if(!civicDetailLoaded(level,at,detail.kind())) { pending=true; }
   else if(civicDetailOccupied(level,at,detail.kind())) { /* preserve existing plaza or player blocks */ }
   else if(!civicDetailSpaceClear(level,at,detail.kind())) { pending=true; }
   else if(!civicDetailHasMaterials(level,ref,p,flowers,flowerIndex,at,detail)) { pending=true; }
   else {
    boolean placed=switch(detail.kind()) {
     case BENCH -> p.roofStair() instanceof StairBlock
      &&placePaidBlock(level,ref,at,p.roofStair().defaultBlockState()
       .setValue(StairBlock.FACING,benchFacing(detail.facing())),p.roofStair(),roadGrounds);
     case PLANTER -> placePaidPlanter(level,ref,at,p.base(),shrubLeaves(p),roadGrounds);
     case FLOWER -> {
      Block flower=flowers[flowerIndex%flowers.length];
      yield placePaidBlock(level,ref,at,flower.defaultBlockState(),flower,roadGrounds);
     }
     case LAMP -> placePaidLamp(level,ref,at,lampSupport,p.base(),roadGrounds);
     case RAIN_BASIN -> placePaidBlock(level,ref,at,Blocks.CAULDRON.defaultBlockState(),Blocks.CAULDRON,roadGrounds);
    };
    boolean unsupportedBench=detail.kind()==CivicDetailKind.BENCH&&!(p.roofStair() instanceof StairBlock);
    if(!placed&&!unsupportedBench) pending=true;
   }
   if(detail.kind()==CivicDetailKind.FLOWER) flowerIndex++;
  }
  return new CivicBatch(end,pending);
 }

 private static CivicDetail civicDetailAt(int index) {
  if(index<CIVIC_DETAILS.length) return CIVIC_DETAILS[index];
  if(index<ALL_CIVIC_DETAILS) return OUTER_CIVIC_DETAILS[index-CIVIC_DETAILS.length];
  int[] basin=RAIN_BASINS[index-ALL_CIVIC_DETAILS];
  return new CivicDetail(CivicDetailKind.RAIN_BASIN,basin[0],basin[1],0);
 }

 private static boolean civicDetailSafe(CivicDetail detail) {
  return detail.kind()==CivicDetailKind.LAMP
   ?lampOffsetSafe(detail.dx(),detail.dz())
   :civicDetailOffsetSafe(detail.dx(),detail.dz(),false)&&!roadArmOffset(detail.dx(),detail.dz());
 }

 private static boolean reservedRoadCell(ServerLevel level,BlockPos ground,Set<Long> roadGrounds) {
  return level.hasChunkAt(ground)&&(roadGrounds.contains(ground.asLong())||Roads.isProtectedLaneCell(level,ground)
   ||level.getBlockState(ground).is(Blocks.DIRT_PATH));
 }

 private static boolean civicDetailLoaded(ServerLevel level,BlockPos at,CivicDetailKind kind) {
  int top=switch(kind) { case BENCH,PLANTER,LAMP -> 2; case FLOWER,RAIN_BASIN -> 0; };
  for(int dy=-1;dy<=top;dy++) if(!level.hasChunkAt(at.above(dy))) return false;
  return true;
 }

 private static boolean civicDetailSpaceClear(ServerLevel level,BlockPos at,CivicDetailKind kind) {
  int top=switch(kind) { case BENCH,PLANTER,LAMP -> 2; case FLOWER,RAIN_BASIN -> 0; };
  for(int dy=1;dy<=top;dy++) if(!decorationCellClear(level,at.above(dy))) return false;
  return true;
 }

 private static boolean civicDetailOccupied(ServerLevel level,BlockPos at,CivicDetailKind kind) {
  int top=switch(kind) { case BENCH,PLANTER,LAMP -> 2; case FLOWER,RAIN_BASIN -> 0; };
  for(int dy=0;dy<=top;dy++) if(!level.getBlockState(at.above(dy)).isAir()) return true;
  return false;
 }

 private static boolean civicDetailHasMaterials(ServerLevel level,VillageRef ref,Palette p,Block[] flowers,
                                                int flowerIndex,BlockPos at,CivicDetail detail) {
  return switch(detail.kind()) {
   case BENCH -> !(p.roofStair() instanceof StairBlock)||hasPublicMaterials(level,ref,p.roofStair());
   case PLANTER -> hasPublicMaterials(level,ref,p.base(),shrubLeaves(p));
   case FLOWER -> hasPublicMaterials(level,ref,flowers[flowerIndex%flowers.length]);
   case LAMP -> {
    Block support=p.frame()==null?p.base():p.frame();
    if(!support.defaultBlockState().isFaceSturdy(level,at,Direction.UP)) support=p.base();
    yield support.defaultBlockState().isFaceSturdy(level,at,Direction.UP)
     &&hasPublicMaterials(level,ref,support,support,Blocks.LANTERN);
   }
   case RAIN_BASIN -> hasPublicMaterials(level,ref,Blocks.CAULDRON);
  };
 }

	static Direction benchFacing(int direction) {
  return switch(direction) {
   case 1 -> Direction.EAST;
   case -1 -> Direction.WEST;
   case 2 -> Direction.SOUTH;
   case -2 -> Direction.NORTH;
   default -> Direction.NORTH;
  };
 }

 /** Safely fills legacy/partial plaza gaps in small loaded batches; existing blocks are never replaced. */
 record SurfaceBatch(int cursor,boolean pending) { }
 static SurfaceBatch upgradeSurface(ServerLevel level,VillageRef ref,BlockPos c,Palette palette,int cursor,Set<Long> roadGrounds) {
  final int side=SURFACE_SIDE,total=SURFACE_CELLS,budget=SURFACE_BLOCKS_PER_TICK;
  int start=Math.floorMod(cursor,total);
  int end=Math.min(total,start+budget);
  boolean pending=false;
  MarketData market=Market.of(ref);
  for(int index=start;index<end;index++) {
   int dx=index/side-PLAZA_RADIUS,dz=index%side-PLAZA_RADIUS;
   if(!surfaceUpgradeOffsetSafe(dx,dz)) continue;
   BlockPos column=c.offset(dx,0,dz);
   if(!level.hasChunkAt(column)) { pending=true;continue; }
   int surfaceY=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ());
   if(surfaceY!=c.getY()) continue;
   BlockPos floor=c.offset(dx,-1,dz);
   if(!level.hasChunkAt(floor)) { pending=true;continue; }
   if(protectedZoneIntersects(ref,floor,0)||reservedRoadCell(level,floor,roadGrounds)) continue;
   var existing=level.getBlockState(floor);
	  boolean inlay=accentPavingOffset(dx,dz);
   if(existing.is(palette.base())||(existing.is(palette.floor())&&!inlay)) continue;
   if(existing.hasBlockEntity()||!existing.getFluidState().isEmpty()
    ||(!VillageStyle.natural(existing)&&!(inlay&&existing.is(palette.floor())))
    ||existing.is(BlockTags.LEAVES)||existing.is(BlockTags.LOGS)||existing.is(Blocks.BEDROCK)) continue;
   boolean clear=true;
   for(int dy=1;dy<=3;dy++) {
    BlockPos head=floor.above(dy);
    if(!level.hasChunkAt(head)||!level.getBlockState(head).isAir()||level.getBlockEntity(head)!=null) { clear=false;break; }
   }
   if(!clear||!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
    new net.minecraft.world.phys.AABB(floor).expandTowards(0,3,0)).isEmpty()) continue;
   if(market.shops().stream().anyMatch(shop->Math.abs(shop.origin().getX()-floor.getX())<=4
    &&Math.abs(shop.origin().getZ()-floor.getZ())<=4)) continue;
   if(level.getPoiManager().getInRange(h->true,floor,2,
    net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
    .anyMatch(p->Math.abs(p.getPos().getX()-floor.getX())<=1&&Math.abs(p.getPos().getY()-floor.getY())<=1
     &&Math.abs(p.getPos().getZ()-floor.getZ())<=1)) continue;
	  Block paving=surfaceMaterialForOffset(dx,dz,palette.base(),palette.floor());
	  if(paving==null) continue;
	  var target=paving.defaultBlockState();
   if(target.getBlock().asItem()==net.minecraft.world.item.Items.AIR) continue;
   if(!hasPublicMaterials(level,ref,target.getBlock())) { pending=true;continue; }
   level.setBlock(floor,target,3);
   if(!level.getBlockState(floor).is(target.getBlock())||!payBlocks(level,ref,target.getBlock())) {
    level.setBlock(floor,existing,3);
    pending=true;
   }
  }
  return new SurfaceBatch(end,pending);
 }

	/** The third square frame and medallion accents consume the same real palette stock as the outer border. */
	static Block surfaceMaterialForOffset(int dx,int dz,Block base,Block floor) {
	 if(base==null||floor==null) return null;
	 return basePavingOffset(dx,dz)?base:floor;
	}

 /** Exact palette item cost for a placed group; AIR has no payable block item. */
 static Optional<Map<Item,Integer>> materialCost(net.minecraft.world.level.block.Block... blocks) {
  Map<Item,Integer> needed=new LinkedHashMap<>();
  for(var block:blocks) {
   if(block==null) return Optional.empty();
   Item item=block.asItem();
   if(item==Items.AIR) return Optional.empty();
   needed.merge(item,1,Integer::sum);
  }
  return Optional.of(Map.copyOf(needed));
 }

 static boolean fountainRingOffset(int dx,int dz) {
  return Math.max(Math.abs(dx),Math.abs(dz))==2;
 }

 static Map<Item,Integer> fountainCost(Block basinBlock) {
  if(basinBlock==null||basinBlock.asItem()==Items.AIR) return Map.of();
  return Map.of(Items.WATER_BUCKET,1);
 }

 static Item fountainBucketReturn() { return Items.BUCKET; }

 /** A real public water bucket funds the basin; its empty bucket returns to the same public slot. */
 static boolean ensureFountain(ServerLevel level,VillageRef ref,BlockPos center,Palette palette) {
  if(fountainComplete(level,center,palette.base())) return true;
  if(!level.hasChunkAt(center)||level.getBlockEntity(center)!=null) return false;
  BlockState centerBefore=level.getBlockState(center);
  boolean legacyMarker=centerBefore.is(palette.base())
   &&level.getBlockState(center.above()).is(palette.base())
   &&level.getBlockState(center.above(2)).is(Blocks.CHISELED_STONE_BRICKS)
   &&level.getBlockState(center.above(3)).is(Blocks.LANTERN);
  if(!centerBefore.is(palette.base())||level.getBlockEntity(center.above())!=null) return false;
  for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
   BlockPos poolCell=center.offset(dx,0,dz);
   if(!level.hasChunkAt(poolCell)||level.getBlockEntity(poolCell)!=null
    ||!level.getBlockState(poolCell).is(palette.base())||!noLivingEntity(level,poolCell)
    ||level.getPoiManager().getInRange(h->true,poolCell,1,
     net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isPresent()) return false;
   for(int dy=1;dy<=3;dy++) {
    BlockPos head=poolCell.above(dy);
    if(!level.hasChunkAt(head)||level.getBlockEntity(head)!=null||!noLivingEntity(level,head)
     ||level.getPoiManager().getInRange(h->true,head,1,
      net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isPresent()) return false;
    BlockState overhead=level.getBlockState(head);
    boolean oldMarker=legacyMarker&&dx==0&&dz==0
     &&((dy==1&&overhead.is(palette.base()))
      ||(dy==2&&overhead.is(Blocks.CHISELED_STONE_BRICKS))
      ||(dy==3&&overhead.is(Blocks.LANTERN)));
    if(!overhead.isAir()&&!oldMarker) return false;
   }
  }
  for(int[] offset:FOUNTAIN_RING) {
   BlockPos rim=center.offset(offset[0],0,offset[1]);
   BlockPos floor=rim.below();
   if(!level.hasChunkAt(rim)||!level.hasChunkAt(floor)||level.getBlockEntity(rim)!=null
    ||!level.getBlockState(rim).is(palette.base())
    ||!level.getBlockState(rim).isCollisionShapeFullBlock(level,rim)
    ||!level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP)
    ||!noLivingEntity(level,rim)
    ||level.getPoiManager().getInRange(h->true,rim,1,
     net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isPresent()) return false;
  }
  Map<Item,Integer> cost=fountainCost(palette.base());
  if(cost.isEmpty()||!hasPublicStock(level,ref,cost)) return false;
  PaidSlot waterSource=singleWaterBucket(level,ref);
  if(waterSource==null) return false;

  Map<BlockPos,BlockState> poolBefore=new LinkedHashMap<>();
  for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
   BlockPos poolCell=center.offset(dx,0,dz);
   poolBefore.put(poolCell,level.getBlockState(poolCell));
  }
  BlockState upperMarkerBefore=level.getBlockState(center.above());
  ItemStack bucketBefore=waterSource.container().getItem(waterSource.slot()).copy();
  ItemStack removed=waterSource.container().removeItem(waterSource.slot(),1);
  if(removed.isEmpty()||!removed.is(Items.WATER_BUCKET)) {
   waterSource.container().setItem(waterSource.slot(),bucketBefore);
   waterSource.container().setChanged();
   return false;
  }
  for(BlockPos poolCell:poolBefore.keySet()) level.setBlock(poolCell,Blocks.AIR.defaultBlockState(),3);
  if(legacyMarker) {
   level.setBlock(center.above(),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(center.above(2),Blocks.AIR.defaultBlockState(),3);
   level.setBlock(center.above(3),Blocks.AIR.defaultBlockState(),3);
  }
  level.setBlock(center,Blocks.WATER.defaultBlockState(),3);
  if(!level.getBlockState(center).is(Blocks.WATER)) {
   waterSource.container().setItem(waterSource.slot(),bucketBefore);
   waterSource.container().setChanged();
   rollbackFountain(level,poolBefore,center,centerBefore,upperMarkerBefore,legacyMarker);
   return false;
  }
  waterSource.container().setItem(waterSource.slot(),new ItemStack(fountainBucketReturn()));
  waterSource.container().setChanged();
  level.playSound(null,center,net.minecraft.sounds.SoundEvents.WATER_AMBIENT,
   net.minecraft.sounds.SoundSource.BLOCKS,0.5F,1.0F);
  return true;
 }

 private static boolean fountainComplete(ServerLevel level,BlockPos center,Block basinBlock) {
  if(!level.hasChunkAt(center)||!level.getBlockState(center).getFluidState().isSource()) return false;
  for(int[] offset:FOUNTAIN_RING) {
   BlockPos rim=center.offset(offset[0],0,offset[1]);
   if(!level.hasChunkAt(rim)||!level.getBlockState(rim).is(basinBlock)) return false;
  }
  return true;
 }

 private static boolean hasPublicStock(ServerLevel level,VillageRef ref,Map<Item,Integer> needed) {
  Set<Container> stores=publicStores(level,ref);
  for(var cost:needed.entrySet()) {
   int count=0;
   for(Container box:stores) for(int slot=0;slot<box.getContainerSize();slot++) {
    ItemStack stack=box.getItem(slot);
    if(!stack.isEmpty()&&stack.is(cost.getKey())) count+=stack.getCount();
   }
   if(count<cost.getValue()) return false;
  }
  return true;
 }

 /** True only when public village stores already contain every real block item needed. */
 static boolean hasPublicMaterials(ServerLevel level,VillageRef ref,Block... blocks) {
  Map<Item,Integer> needed=materialCost(blocks).orElse(null);
  return needed!=null&&!needed.isEmpty()&&hasPublicStock(level,ref,needed);
 }

 private static PaidSlot singleWaterBucket(ServerLevel level,VillageRef ref) {
  for(Container box:publicStores(level,ref)) for(int slot=0;slot<box.getContainerSize();slot++) {
   ItemStack stack=box.getItem(slot);
   if(stack.is(Items.WATER_BUCKET)&&stack.getCount()==1) return new PaidSlot(box,slot);
  }
  return null;
 }

 private static Set<Container> publicStores(ServerLevel level,VillageRef ref) {
  Set<Container> stores=new LinkedHashSet<>();
  for(BlockPos pos:Lager.materialChests(level,ref)) {
   Container box=Lager.publicContainerAt(level,pos);
   if(box!=null) stores.add(box);
  }
  return stores;
 }

 private static void rollbackFountain(ServerLevel level,Map<BlockPos,BlockState> poolBefore,BlockPos center,
                                      BlockState centerBefore,BlockState upperMarkerBefore,boolean legacyMarker) {
  for(var entry:poolBefore.entrySet()) level.setBlock(entry.getKey(),entry.getValue(),3);
  if(legacyMarker) {
   level.setBlock(center,centerBefore,3);
   level.setBlock(center.above(),upperMarkerBefore,3);
   level.setBlock(center.above(2),Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),3);
   level.setBlock(center.above(3),Blocks.LANTERN.defaultBlockState(),3);
  }
 }

 private static boolean placePaidBlock(ServerLevel level,VillageRef ref,BlockPos at,BlockState target,
                                       net.minecraft.world.level.block.Block cost,Set<Long> roadGrounds) {
  if(!hasPublicMaterials(level,ref,cost)||!decorationCellFree(level,at,roadGrounds)) return false;
  BlockState before=level.getBlockState(at);
  if(!before.isAir()) return false;
  level.setBlock(at,target,3);
  if(!level.getBlockState(at).is(target.getBlock())||!payBlocks(level,ref,cost)) {
   level.setBlock(at,before,3);
   return false;
  }
  return true;
 }

 private static boolean placePaidPlanter(ServerLevel level,VillageRef ref,BlockPos at,
                                         net.minecraft.world.level.block.Block base,
                                         net.minecraft.world.level.block.Block leaves,Set<Long> roadGrounds) {
  BlockPos leavesPos=at.above();
  if(!hasPublicMaterials(level,ref,base,leaves)||!decorationCellFree(level,at,roadGrounds)
    ||!decorationCellClear(level,leavesPos)) return false;
  BlockState oldBase=level.getBlockState(at),oldLeaves=level.getBlockState(leavesPos);
  if(!oldBase.isAir()||!oldLeaves.isAir()) return false;
  var leafState=leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
  level.setBlock(at,base.defaultBlockState(),3);
  if(!level.getBlockState(at).is(base)||!level.getBlockState(at).isFaceSturdy(level,at,Direction.UP)
    ||!decorationCellClear(level,leavesPos)) {
   level.setBlock(at,oldBase,3);
   return false;
  }
  level.setBlock(leavesPos,leafState,3);
  if(!level.getBlockState(at).is(base)||!level.getBlockState(leavesPos).is(leaves)
    ||!payBlocks(level,ref,base,leaves)) {
   level.setBlock(leavesPos,oldLeaves,3);
   level.setBlock(at,oldBase,3);
   return false;
  }
  return true;
 }

 /** Two-block palette post with a lantern above; all three blocks are paid together or none remains. */
 private static boolean placePaidLamp(ServerLevel level,VillageRef ref,BlockPos at,
                                      net.minecraft.world.level.block.Block support,
                                      net.minecraft.world.level.block.Block fallback,Set<Long> roadGrounds) {
  if(!support.defaultBlockState().isFaceSturdy(level,at,Direction.UP)) support=fallback;
  if(!support.defaultBlockState().isFaceSturdy(level,at,Direction.UP)
    ||!hasPublicMaterials(level,ref,support,support,Blocks.LANTERN)) return false;
  BlockPos upper=at.above(),lamp=upper.above();
  if(!decorationCellFree(level,at,roadGrounds)||!decorationCellClear(level,upper)
    ||!decorationCellClear(level,lamp)) return false;
  BlockState oldSupport=level.getBlockState(at),oldUpper=level.getBlockState(upper),oldLamp=level.getBlockState(lamp);
  if(!oldSupport.isAir()||!oldUpper.isAir()||!oldLamp.isAir()) return false;
  BlockState supportState=support.defaultBlockState();
  BlockState lampState=Blocks.LANTERN.defaultBlockState();
  level.setBlock(at,supportState,3);
  if(!level.getBlockState(at).is(support)||!level.getBlockState(at).isFaceSturdy(level,at,Direction.UP)) {
   level.setBlock(at,oldSupport,3);
   return false;
  }
  level.setBlock(upper,supportState,3);
  if(!level.getBlockState(upper).is(support)||!level.getBlockState(upper).isFaceSturdy(level,upper,Direction.UP)) {
   level.setBlock(upper,oldUpper,3);
   level.setBlock(at,oldSupport,3);
   return false;
  }
  level.setBlock(lamp,lampState,3);
  if(!level.getBlockState(at).is(support)||!level.getBlockState(upper).is(support)
    ||!level.getBlockState(lamp).is(Blocks.LANTERN)
    ||!payBlocks(level,ref,support,support,Blocks.LANTERN)) {
   level.setBlock(lamp,oldLamp,3);
   level.setBlock(upper,oldUpper,3);
   level.setBlock(at,oldSupport,3);
   return false;
  }
  return true;
 }

 /** Pay from public village storage only; verify the whole cost before removal and restore touched slots on failure. */
 private static boolean payBlocks(ServerLevel level,VillageRef ref,net.minecraft.world.level.block.Block... blocks) {
  Map<Item,Integer> needed=materialCost(blocks).orElse(null);
  if(needed==null||needed.isEmpty()||!hasPublicStock(level,ref,needed)) return false;
  Set<Container> stores=publicStores(level,ref);
  Map<PaidSlot,ItemStack> before=new LinkedHashMap<>();
  for(var cost:needed.entrySet()) {
   int remaining=cost.getValue();
   for(Container box:stores) {
    for(int slot=0;slot<box.getContainerSize()&&remaining>0;slot++) {
     ItemStack stack=box.getItem(slot);
     if(stack.isEmpty()||!stack.is(cost.getKey())) continue;
     PaidSlot key=new PaidSlot(box,slot);
     before.putIfAbsent(key,stack.copy());
     int take=Math.min(remaining,stack.getCount());
     ItemStack removed=box.removeItem(slot,take);
     if(removed.getCount()!=take) {
      restorePayment(before);
      return false;
     }
     remaining-=take;
    }
    if(remaining==0) break;
   }
   if(remaining!=0) {
    restorePayment(before);
    return false;
   }
  }
  for(PaidSlot slot:before.keySet()) slot.container().setChanged();
  return true;
 }

 private static void restorePayment(Map<PaidSlot,ItemStack> before) {
  for(var entry:before.entrySet()) {
   entry.getKey().container().setItem(entry.getKey().slot(),entry.getValue().copy());
   entry.getKey().container().setChanged();
  }
 }

 private static net.minecraft.world.level.block.Block shrubLeaves(Palette p) {
  String fence=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.fence()).getPath();
  String wood=fence.endsWith("_fence")?fence.substring(0,fence.length()-"_fence".length()):"oak";
  return switch(wood) {
   case "spruce" -> Blocks.SPRUCE_LEAVES;
   case "birch" -> Blocks.BIRCH_LEAVES;
   case "jungle" -> Blocks.JUNGLE_LEAVES;
   case "acacia" -> Blocks.ACACIA_LEAVES;
   case "dark_oak" -> Blocks.DARK_OAK_LEAVES;
   case "mangrove" -> Blocks.MANGROVE_LEAVES;
   case "cherry" -> Blocks.CHERRY_LEAVES;
   case "azalea" -> Blocks.AZALEA_LEAVES;
   default -> Blocks.OAK_LEAVES;
  };
 }

 private static net.minecraft.world.level.block.Block[] flowerPalette(Palette p,BlockPos c) {
  String fence=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.fence()).getPath();
  net.minecraft.world.level.block.Block[] choices=fence.startsWith("spruce_")||fence.startsWith("dark_oak_")
   ?new net.minecraft.world.level.block.Block[]{Blocks.AZURE_BLUET,Blocks.ALLIUM,Blocks.CORNFLOWER,Blocks.POPPY}
   :fence.startsWith("acacia_")||fence.startsWith("mangrove_")
    ?new net.minecraft.world.level.block.Block[]{Blocks.POPPY,Blocks.ORANGE_TULIP,Blocks.ALLIUM,Blocks.CORNFLOWER}
    :new net.minecraft.world.level.block.Block[]{Blocks.POPPY,Blocks.CORNFLOWER,Blocks.ALLIUM,Blocks.AZURE_BLUET};
  int shift=Math.floorMod((int)(c.asLong()^(c.asLong()>>>32)),choices.length);
  return new net.minecraft.world.level.block.Block[]{choices[shift],choices[(shift+1)%choices.length],choices[(shift+2)%choices.length],choices[(shift+3)%choices.length]};
 }

 static boolean decorationCellFree(ServerLevel level,BlockPos pos,Set<Long> roadGrounds) {
  BlockPos ground=pos.below();
  if(!level.hasChunkAt(pos)||!level.hasChunkAt(ground)) return false;
  BlockState groundState=level.getBlockState(ground);
  return !reservedRoadCell(level,ground,roadGrounds)
   &&level.getBlockEntity(ground)==null&&!groundState.hasBlockEntity()
   &&decorationCellClear(level,pos)&&groundState.isFaceSturdy(level,ground,Direction.UP);
 }
 private static boolean decorationCellClear(ServerLevel level,BlockPos pos) {
  return level.hasChunkAt(pos) && level.getBlockState(pos).isAir() && level.getBlockEntity(pos)==null
   &&noLivingEntity(level,pos)
   &&level.getPoiManager().getInRange(h->true,pos,1,
    net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).findAny().isEmpty();
 }
 private static boolean noLivingEntity(ServerLevel level,BlockPos pos) {
  return level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(pos)).isEmpty();
 }

 /** Symmetrisches Satteldach auf vier durchgehenden Pfosten (Pfostenkopf = Dachunterkante, nichts schwebt). */
 static void buildShrineRoof(ServerLevel level,BlockPos c,Palette p) {
  var full=p.roofFull()!=null?p.roofFull():Blocks.STONE_BRICKS;
  // Pfosten (Zaun) von der Plattform bis zur Dachunterkante
  for(int x:new int[]{-1,1}) for(int z:new int[]{-2,2}) for(int y=1;y<=4;y++) level.setBlock(c.offset(x,y,z),p.fence().defaultBlockState(),3);
  for(int z=-2;z<=2;z++) {
   // untere Dachreihe: Traufe (Treppen) aussen, Vollblock innen
   for(int x=-2;x<=2;x++) {
    var st=full.defaultBlockState();
    if(Math.abs(x)==2 && p.roofStair() instanceof StairBlock) st=p.roofStair().defaultBlockState().setValue(StairBlock.FACING,x<0?Direction.WEST:Direction.EAST);
    level.setBlock(c.offset(x,5,z),st,3);
   }
   // obere Reihe: Treppen zur Mitte hin, First als Vollblock
   for(int x=-1;x<=1;x++) {
    var st=full.defaultBlockState();
    if(x!=0 && p.roofStair() instanceof StairBlock) st=p.roofStair().defaultBlockState().setValue(StairBlock.FACING,x<0?Direction.WEST:Direction.EAST);
    level.setBlock(c.offset(x,6,z),st,3);
   }
  }
  // hängende Laterne unter dem Dach, Mittelsäule bleibt frei zugänglich
  level.setBlock(c.offset(0,4,0),Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING,true),3);
 }

 /** Einmal je Sitzung: den Schrein eines schon bestehenden Platzes neu decken (alte Dachform war schief und schwebte). */
 public static void refreshShrine(ServerLevel level,VillageRef ref,Villager hint) {
  String key=cacheKey(level,ref);
  MarketData m=Market.of(ref); if(m.plaza().isEmpty()||!SHRINE_FIXED.add(key)) return;
  BlockPos c=m.plaza().get(); if(!level.hasChunkAt(c)) { SHRINE_FIXED.remove(key); return; }
  Palette p=Palette.detect(level,ref.bell(),hint,c.asLong(),c);
  // Altes Dach (Hoehe 4-6, x -2..2, z -1..1) nur entfernen, wenn es ein Dachblock ist (Treppe/Platte/Vollblock des Dorfes)
  for(int x=-2;x<=2;x++) for(int z=-1;z<=1;z++) for(int y=4;y<=6;y++) {
   BlockPos q=c.offset(x,y,z); var st=level.getBlockState(q);
   if(st.getBlock() instanceof StairBlock || st.getBlock() instanceof net.minecraft.world.level.block.SlabBlock || st.getBlock()==p.roofFull()) level.setBlock(q,Blocks.AIR.defaultBlockState(),3);
  }
  for(int x:new int[]{-1,1}) for(int z:new int[]{-1,1}) for(int y=1;y<=3;y++) { // alte Pfosten (innerhalb) entfernen, neue stehen bei z=+-2
   BlockPos q=c.offset(x,y,z); if(level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.FenceBlock) level.setBlock(q,Blocks.AIR.defaultBlockState(),3);
  }
  buildShrineRoof(level,c,p);
 }
 public static void ensure(ServerLevel level,VillageRef ref,Villager hint,long now) {
  MarketData m=Market.of(ref);
  if(m.plaza().isPresent()) { decorate(level,ref,hint,m.plaza().get(),now); return; }
  if(now<m.nextPlan()) return;
  Market.save(ref,new MarketData(m.plaza(),m.shops(),now+600,m.sales()));
  BlockPos center=Development.center(level,ref);
  BlockPos c=findCentralSite(level,center,ref.bell(),ref.bell().asLong(),Math.floorDiv(now,600L));
  if(c!=null) build(level,ref,c,hint);
 }

 /** Deterministic centre-out search. Existing buildings and homes are skipped by {@link #site}; each retry moves outward. */
 static BlockPos findCentralSite(ServerLevel level,BlockPos center,BlockPos bell,long seed) {
  return findCentralSite(level,center,bell,seed,0L);
 }
 static BlockPos findCentralSite(ServerLevel level,BlockPos center,BlockPos bell,long seed,long attempt) {
  final int samples=192; final double maxRadius=48.0;
  // A retry must sample new terrain; repeating the same 192 positions can never
  // discover a suitable gap between buildings that the first pass missed.
  double phase=(seed&0xffffL)*(Math.PI*2.0/65536.0)+attempt*2.399963229728653;
  VillageRef context=villageAt(level,bell);
  for(int i=0;i<samples;i++) {
   double fraction=(i+0.5)/samples;
   double radius=2.0+Math.sqrt(fraction)*maxRadius;
   double angle=phase+i*Math.PI*(3.0-Math.sqrt(5.0));
   int x=center.getX()+(int)Math.round(Math.cos(angle)*radius);
   int z=center.getZ()+(int)Math.round(Math.sin(angle)*radius);
   BlockPos column=new BlockPos(x,center.getY(),z);
   if(!level.hasChunkAt(column)) continue;
   BlockPos at=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
   BlockPos plaza=site(level,at,PLAZA_RADIUS,PLAZA_EDGE_CLEARANCE,null,context);
   if(plaza==null||Math.abs(plaza.getY()-bell.getY())>12) continue;
   if(!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
			new net.minecraft.world.phys.AABB(plaza).inflate(PLAZA_RADIUS+PLAZA_EDGE_CLEARANCE,8,PLAZA_RADIUS+PLAZA_EDGE_CLEARANCE)).isEmpty()) continue;
   return plaza;
  }
  return null;
 }
 private static VillageRef villageAt(ServerLevel level,BlockPos bell) {
  var entity=level.getBlockEntity(bell);
  if(!(entity instanceof net.minecraft.world.level.block.entity.BellBlockEntity)) return null;
  var data=entity.getAttached(VillagerPlus.VILLAGE);
  return data==null?null:new VillageRef(bell,data,entity);
 }
 /** Checks the full footprint plus three blocks of clearance, before changing any block. */
 public static BlockPos site(ServerLevel level,BlockPos at,int half,int clearance) {
  VillageRef context=Villages.villageNear(level,at,128);
  BlockPos plaza=context==null?null:Market.of(context).plaza().orElse(null);
  return site(level,at,half,clearance,plaza,context);
 }
 /** Validate a site relative to the already-known plaza, without rediscovering it from nearby village bells. */
 static BlockPos site(ServerLevel level,BlockPos at,int half,int clearance,BlockPos plaza) {
  return site(level,at,half,clearance,plaza,ownerOfPlaza(level,plaza));
 }
 private static BlockPos site(ServerLevel level,BlockPos at,int half,int clearance,BlockPos plaza,VillageRef context) {
  if(plaza!=null) at=clearApproachRoutes(plaza,at,half);
  if(at==null||!level.hasChunkAt(at)) return null;
  // A market may be planned before a civic plaza can find suitable terrain. Keep its
  // saved footprint reserved so a later plaza retry cannot pave over the paid shop site.
  if(Market.reserved(level,context,at,half+clearance)) return null;
  if(protectedZoneIntersects(context,at,half+clearance)) return null;
  // Keep a real buffer around every registered civic, work, and home point.
  BlockPos site=at;
  if(level.getPoiManager().getInRange(h->true,site,half+clearance+5,net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
    .anyMatch(p->Math.abs(p.getPos().getX()-site.getX())<=half+clearance+2 && Math.abs(p.getPos().getZ()-site.getZ())<=half+clearance+2))return null;
  Set<Long> roadGrounds=context==null?Set.of():savedRoadGrounds(level,context);
  int high=Integer.MIN_VALUE,low=Integer.MAX_VALUE;
  for(int x=-half-clearance;x<=half+clearance;x++) for(int z=-half-clearance;z<=half+clearance;z++) {
   BlockPos col=at.offset(x,0,z);
   if(plaza!=null && (Math.abs(x)>half || Math.abs(z)>half) && inside(plaza,col,0))continue;
   if(!level.hasChunkAt(col)) return null;
   int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,col.getX(),col.getZ());
   BlockPos ground=new BlockPos(col.getX(),y-1,col.getZ()); var state=level.getBlockState(ground);
   if(roadGrounds.contains(ground.asLong())||state.is(Blocks.DIRT_PATH)||Roads.isProtectedLaneCell(level,ground)) return null;
   boolean paving=state.is(Blocks.SANDSTONE)||state.is(Blocks.SMOOTH_SANDSTONE)||state.is(Blocks.CUT_SANDSTONE);
   if((!VillageStyle.natural(state)&&!paving) || !state.getFluidState().isEmpty() || state.is(BlockTags.LEAVES)
    || state.is(Blocks.FARMLAND) || !state.isFaceSturdy(level,ground,Direction.UP)) return null;
   for(int dy=0;dy<6;dy++) { var s=level.getBlockState(ground.above(dy+1)); if(!s.isAir() && !s.canBeReplaced()) return null; }
   if(Math.abs(x)<=half && Math.abs(z)<=half) { high=Math.max(high,y); low=Math.min(low,y); }
  }
  if(high-low>4) return null;
  return new BlockPos(at.getX(),high,at.getZ());
 }
 /** Move a planned market footprint outward until it clears the four walkable plaza approaches. */
 static BlockPos clearApproachRoutes(BlockPos plaza,BlockPos candidate,int half) {
  int dx=candidate.getX()-plaza.getX(),dz=candidate.getZ()-plaza.getZ();
  Direction outward=Math.abs(dx)>=Math.abs(dz)?(dx<0?Direction.WEST:Direction.EAST):(dz<0?Direction.NORTH:Direction.SOUTH);
  BlockPos moved=candidate;
  int maxSteps=Math.max(1,2*half+ROAD_ARM_END-ROAD_ARM_START+1);
  for(int step=0;step<=maxSteps;step++) {
   if(!overlapsApproach(plaza,moved,half)) return moved;
   moved=moved.relative(outward);
  }
  return null;
 }
 static boolean overlapsApproach(BlockPos plaza,BlockPos candidate,int half) {
  int dx=candidate.getX()-plaza.getX(),dz=candidate.getZ()-plaza.getZ();
  for(Direction direction:Direction.Plane.HORIZONTAL) {
   int along=dx*direction.getStepX()+dz*direction.getStepZ();
   int across=Math.abs(dx*direction.getStepZ()-dz*direction.getStepX());
	   // Plaza.build lays each five-block path from the plaza edge through its outer apron.
	   if(along+half>=ROAD_ARM_START && along-half<=ROAD_ARM_END && across<=half+2) return true;
  }
  return false;
 }
 private static void build(ServerLevel level,VillageRef ref,BlockPos c,Villager hint) {
  Palette p=Palette.detect(level,ref.bell(),hint,c.asLong(),c);
  for(int x=-PLAZA_RADIUS;x<=PLAZA_RADIUS;x++) for(int z=-PLAZA_RADIUS;z<=PLAZA_RADIUS;z++) {
	   if(Math.abs(x)+Math.abs(z)>PLAZA_DIAMOND_RADIUS) continue;
   BlockPos floor=c.offset(x,-1,z);
   int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,floor.getX(),floor.getZ());
   for(int k=y;k<c.getY()-1;k++) level.setBlock(new BlockPos(floor.getX(),k,floor.getZ()),p.base().defaultBlockState(),3);
	   boolean ring=Math.abs(x)==PLAZA_RADIUS || Math.abs(z)==PLAZA_RADIUS
	    || Math.abs(x)+Math.abs(z)==PLAZA_DIAMOND_RADIUS;
   level.setBlock(floor,(ring || Math.abs(x)<=2 || Math.abs(z)<=2 ? p.base() : p.floor()).defaultBlockState(),3);
   for(int dy=0;dy<3;dy++) if(level.getBlockState(c.offset(x,dy,z)).canBeReplaced()) level.setBlock(c.offset(x,dy,z),Blocks.AIR.defaultBlockState(),3);
  }
  // Low fountain with four wide approach paths; chest and board remain accessible.
  for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) level.setBlock(c.offset(x,0,z),
   p.base().defaultBlockState(),3);
  level.setBlock(c.above(),p.base().defaultBlockState(),3);
  level.setBlock(c.above(2),Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),3);
  level.setBlock(c.above(3),Blocks.LANTERN.defaultBlockState(),3);
  buildShrineRoof(level,c,p);
  if(p.roofStair() instanceof StairBlock) for(int side:new int[]{-1,1}) for(int z:new int[]{-3,3})
	   level.setBlock(c.offset(side*6,0,z),p.roofStair().defaultBlockState().setValue(StairBlock.FACING,side<0?Direction.WEST:Direction.EAST),3);
  for(Direction dir:Direction.Plane.HORIZONTAL) for(int n=ROAD_ARM_START;n<=ROAD_ARM_END;n++) {
   BlockPos road=c.relative(dir,n);int height=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,road.getX(),road.getZ());
	   int target=c.getY()-(n-PLAZA_RADIUS);if(height>target+1)break;
   for(int side=-2;side<=2;side++) {
    BlockPos stair=new BlockPos(road.getX(),target,road.getZ()).relative(dir.getClockWise(),side);
    var cur=level.getBlockState(stair);if(!cur.isAir()&&!cur.canBeReplaced()&&!VillageStyle.natural(cur))continue;
    var stairState=p.roofStair().defaultBlockState();if(p.roofStair() instanceof StairBlock)stairState=stairState.setValue(StairBlock.FACING,dir.getOpposite());
    level.setBlock(stair,stairState,3);
   }
  }
  BlockPos old=ref.chestPos(level), chest=c.offset(-1,0,4);
  var oldBox=ref.chest(level); java.util.List<net.minecraft.world.item.ItemStack> contents=new java.util.ArrayList<>();
  if(oldBox!=null) for(int i=0;i<oldBox.getContainerSize();i++) contents.add(oldBox.getItem(i).copy());
  var state=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH);
  level.setBlock(chest,state.setValue(ChestBlock.TYPE,ChestType.LEFT),3);
  level.setBlock(chest.east(),state.setValue(ChestBlock.TYPE,ChestType.RIGHT),3);
  var box=net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level,chest);
  if(box==null || contents.size()>box.getContainerSize()) throw new IllegalStateException("Plaza chest capacity mismatch");
  for(int i=0;i<contents.size();i++) box.setItem(i,contents.get(i));
  if(oldBox!=null) oldBox.clearContent();
  ref.data().chest=Optional.of(chest);
  // Old center remains as an empty chest; only the mod's own board is relocated.
  if(ref.data().extra.board.isPresent()) {
   BlockPos b=ref.data().extra.board.get(); var bs=level.getBlockState(b);
   if(bs.getBlock() instanceof DorftafelBlock) for(BlockPos q:DorftafelBlock.parts(bs,b)) for(BlockPos t:new BlockPos[]{q,q.above()})
    if(level.getBlockState(t).getBlock() instanceof DorftafelBlock) level.setBlock(t,Blocks.AIR.defaultBlockState(),3);
  }
  BlockPos board=c.offset(-2,0,-5);
  DorftafelBlock.placeAll(level,board,ModBlocks.DORFTAFEL_GROSS.defaultBlockState().setValue(DorftafelBlock.FACING,Direction.SOUTH));
  ref.data().extra.board=Optional.of(board); ref.dirty();
  MarketData m=Market.of(ref); Market.save(ref,new MarketData(Optional.of(c),m.shops(),m.nextPlan(),m.sales()));
  decorate(level,ref,hint,c,level.getGameTime());
  Journal.add(level,ref,"Bauen","Dorfgemeinschaft","Dorfschrein und Plaza errichtet bei "+c.toShortString(),0);
  org.slf4j.LoggerFactory.getLogger("villagerplus").info("Plaza errichtet bei {} (alte Dorfkiste {})",c,old);
 }
}
